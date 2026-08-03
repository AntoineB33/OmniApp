import math
import tkinter as tk

# ---------------------------------------------------------------------------
# One single decay speed for the whole scheduler, written as a half-life in
# minutes. It sets how far the influence of an anomaly (a lockout, a pre-placed
# block) reaches - identically before it and after it.
# ---------------------------------------------------------------------------
DECAY_HALF_LIFE = 50.0
DECAY_RATE = 0.5 ** (1.0 / DECAY_HALF_LIFE)
LAMBDA = math.log(2.0) / DECAY_HALF_LIFE
EPSILON = 1.0


class Task:
    def __init__(self, name, priority, min_time, color):
        self.name = name
        self.priority = priority
        self.min_time = min_time
        self.color = color


class ToolTip:
    """Manages floating info bubbles for canvas elements."""
    def __init__(self, canvas):
        self.canvas = canvas
        self.tooltip_window = None
        self.data = {}

        self.canvas.tag_bind("task_panel", "<Enter>", self.show_tooltip)
        self.canvas.tag_bind("task_panel", "<Leave>", self.hide_tooltip)
        self.canvas.tag_bind("task_panel", "<Motion>", self.move_tooltip)

    def register(self, item_id, text):
        self.data[item_id] = text

    def show_tooltip(self, event):
        current = self.canvas.find_withtag("current")
        if not current:
            return

        item_id = current[0]
        text = self.data.get(item_id)
        if not text:
            return

        x, y = event.x_root + 15, event.y_root + 15
        self.tooltip_window = tk.Toplevel(self.canvas)
        self.tooltip_window.wm_overrideredirect(True)
        self.tooltip_window.wm_geometry(f"+{x}+{y}")

        label = tk.Label(self.tooltip_window, text=text, background="#ffffe0",
                         relief="solid", borderwidth=1, justify="left", font=("Arial", 9))
        label.pack()

    def hide_tooltip(self, event):
        if self.tooltip_window:
            self.tooltip_window.destroy()
            self.tooltip_window = None

    def move_tooltip(self, event):
        if self.tooltip_window:
            x, y = event.x_root + 15, event.y_root + 15
            self.tooltip_window.wm_geometry(f"+{x}+{y}")


def get_clear_timeline_bounds(tasks):
    time_now = 0
    actual_times = {t.name: 0 for t in tasks}
    P = sum(t.priority for t in tasks)
    seen = set()
    max_v = {t.name: -float('inf') for t in tasks}
    min_v = {t.name: float('inf') for t in tasks}
    while True:
        st = tuple(time_now * t.priority - actual_times[t.name] * P for t in tasks)
        if st in seen:
            break
        seen.add(st)
        best, best_v = None, -float('inf')
        for t in tasks:
            v = t.priority * time_now - actual_times[t.name] * P
            max_v[t.name] = max(max_v[t.name], v)
            min_v[t.name] = min(min_v[t.name], v)
            if v > best_v:
                best_v, best = v, t
        if best is None:
            break
        time_now += best.min_time
        actual_times[best.name] += best.min_time
    for t in tasks:
        min_v[t.name] = min(min_v[t.name], t.priority * time_now - actual_times[t.name] * P)
        max_v[t.name] = max(max_v[t.name], t.priority * t.min_time)
        min_v[t.name] = min(min_v[t.name], -(P - t.priority) * t.min_time)
    return max_v, min_v


def merge(iv):
    out = []
    for s, e in sorted(iv):
        if out and s <= out[-1][1]:
            out[-1][1] = max(out[-1][1], e)
        else:
            out.append([s, e])
    return out


def build_anomalies(tasks, pre_placed, periods):
    lock = {t.name: [] for t in tasks}
    forced = {t.name: [] for t in tasks}
    for p in periods:
        for t in tasks:
            if t.name not in p['allowed']:
                lock[t.name].append([p['start'], p['end']])
    for b in pre_placed:
        iv = [b['start'], b['start'] + b['duration']]
        for t in tasks:
            (forced if b['name'] == t.name else lock)[t.name].append(list(iv))
    return {n: merge(v) for n, v in lock.items()}, {n: merge(v) for n, v in forced.items()}


def past_kernel(intervals, time_now):
    """Integral of decay**(distance) over the parts of the intervals that lie in
    the past. One minute of anomaly at t' weighs decay**(t - t'), so a long
    anomaly saturates at 1/LAMBDA instead of counting for ever."""
    acc = 0.0
    for s, e in intervals:
        if time_now >= e:
            acc += (DECAY_RATE ** (time_now - e) - DECAY_RATE ** (time_now - s)) / LAMBDA
        elif time_now > s:
            acc += (1.0 - DECAY_RATE ** (time_now - s)) / LAMBDA
    return acc


def bounds_now(t, time_now, lock, forced, cmax, cmin, P):
    """The imbalance the task is allowed to carry right now: the clear-timeline
    bound, widened by the anomalies that already happened, with everything past
    that ignored (exponentially with the distance, epsilon-rounded)."""
    over = t.priority * past_kernel(lock, time_now)
    under = (P - t.priority) * past_kernel(forced, time_now)
    return cmax + (over if over >= EPSILON else 0.0), cmin - (under if under >= EPSILON else 0.0)


def allowed_at(tasks, periods, time_now):
    if periods:
        for p in periods:
            if p['start'] <= time_now < p['end']:
                return [t for t in tasks if t.name in p['allowed']]
    return list(tasks)


def reverse_tail(tasks, boundary, lock, forced, cmax, cmin, P, periods, max_blocks=200):
    """Schedules the stretch that ends exactly at `boundary`, walking BACKWARDS
    from it.

    Read backwards, an anomaly that starts at `boundary` is an anomaly that just
    ended, so this is literally the forward catch-up code running in reverse
    time: the task that is about to be locked out carries, at tau = 0, the debt
    the lockout will create (capped by the same decay rule), and pays it down
    block by block going back in time. Reversed again at the end, that produces
    the agglomeration immediately BEFORE the boundary, decaying away from it -
    the mirror image of the agglomeration immediately after one.
    """
    allowed = allowed_at(tasks, periods, boundary - 1) if boundary > 0 else []
    if not allowed:
        return []

    lock_len, forced_len = {}, {}
    for t in allowed:
        lock_len[t.name] = next((e - s for s, e in lock[t.name] if s == boundary), 0)
        forced_len[t.name] = next((e - s for s, e in forced[t.name] if s == boundary), 0)
    affected = [t for t in allowed if lock_len[t.name] or forced_len[t.name]]
    if not affected:
        return []

    # imbalance each task will have accumulated when the anomaly is over
    v_off = {t.name: t.priority * lock_len[t.name] - (P - t.priority) * forced_len[t.name]
             for t in allowed}
    served = {t.name: 0 for t in allowed}
    forgiven = {t.name: 0.0 for t in allowed}
    tau = 0
    steps = []

    def state(t):
        return v_off[t.name] + t.priority * tau - served[t.name] * P - forgiven[t.name]

    while len(steps) < max_blocks:
        # the part of the imbalance that is beyond the (widened) bound is really
        # ignored, i.e. dropped from the state for good, not just hidden
        for t in allowed:
            hi = cmax[t.name] + (t.priority * past_kernel([[-lock_len[t.name], 0]], tau)
                                 if lock_len[t.name] else 0.0)
            lo = cmin[t.name] - ((P - t.priority) * past_kernel([[-forced_len[t.name], 0]], tau)
                                 if forced_len[t.name] else 0.0)
            v = state(t)
            if v > hi:
                forgiven[t.name] += v - hi
            elif v < lo:
                forgiven[t.name] += v - lo

        # stop once every task the anomaly touches is back inside its normal band
        if all(cmin[t.name] <= state(t) <= cmax[t.name] for t in affected):
            break

        best, best_key = None, -float('inf')
        for t in allowed:
            v = state(t)
            if v > best_key:
                best_key, best = v, t
        if best is None:
            break
        steps.append({'name': best.name, 'duration': best.min_time, 'color': best.color})
        served[best.name] += best.min_time
        tau += best.min_time

    steps.reverse()
    return steps


def get_schedule_rules(tasks, pre_placed=None, periods=None, max_rules=50):
    pre_placed = pre_placed or []
    periods = periods or []
    P = sum(t.priority for t in tasks)
    cmax, cmin = get_clear_timeline_bounds(tasks)
    lock, forced = build_anomalies(tasks, pre_placed, periods)

    # every point where an anomaly starts gets a reverse-scheduled tail
    starts = sorted({s for d in (lock, forced) for iv in d.values() for s, _ in iv if s > 0})
    tails = {}
    for s in starts:
        tail = reverse_tail(tasks, s, lock, forced, cmax, cmin, P, periods)
        if tail:
            tails[s] = (tail, sum(b['duration'] for b in tail))

    time_now = 0
    actual = {t.name: 0 for t in tasks}
    forgiven = {t.name: 0.0 for t in tasks}
    raw = []
    seen = {}
    pre_sorted = sorted(pre_placed, key=lambda x: x['start'])

    while len(raw) < max_rules:
        active = None
        for p in pre_sorted:
            if p['start'] <= time_now < p['start'] + p['duration']:
                active = p
                break
        if active:
            jump = (active['start'] + active['duration']) - time_now
            raw.append({'name': active['name'], 'duration': jump,
                        'color': active.get('color', '#DDDDDD')})
            if active['name'] in actual:
                actual[active['name']] += jump          # the block IS that task
            else:
                # a foreign block starves every task equally, so it creates no
                # relative imbalance: share it out in proportion to the priorities
                for t in tasks:
                    actual[t.name] += jump * t.priority / P
            time_now += jump
            continue

        # are we inside the reverse-scheduled run-up to the next anomaly?
        nxt = next((s for s in starts if s > time_now and s in tails), None)
        if nxt is not None:
            tail, tail_len = tails[nxt]
            gap = nxt - time_now
            if gap <= tail_len:
                chosen, acc = [], 0
                for b in reversed(tail):
                    if acc + b['duration'] > gap:
                        break
                    chosen.append(b)
                    acc += b['duration']
                if chosen:
                    chosen.reverse()
                    if acc < gap:  # keep the seam exact by stretching the first block
                        chosen[0] = dict(chosen[0], duration=chosen[0]['duration'] + gap - acc)
                    for b in chosen:
                        raw.append(dict(b))
                        if b['name'] in actual:
                            actual[b['name']] += b['duration']
                        time_now += b['duration']
                    continue

        allowed = allowed_at(tasks, periods, time_now)
        if not allowed:
            break

        future_pre = any(p['start'] >= time_now for p in pre_sorted)
        if not future_pre and not periods:
            st = tuple(time_now * t.priority - actual[t.name] * P - forgiven[t.name] for t in tasks)
            if st in seen:
                i = seen[st]
                return compress(raw[:i]), compress(raw[i:])
            seen[st] = len(raw)

        best, best_key = None, -float('inf')
        for t in tasks:
            v = t.priority * time_now - actual[t.name] * P - forgiven[t.name]
            hi, lo = bounds_now(t, time_now, lock[t.name], forced[t.name], cmax[t.name], cmin[t.name], P)
            if v > hi:
                forgiven[t.name] += v - hi
            elif v < lo:
                forgiven[t.name] += v - lo
        for t in allowed:
            v = t.priority * time_now - actual[t.name] * P - forgiven[t.name]
            if v > best_key:
                best_key, best = v, t
        if best is None:
            break
        raw.append({'name': best.name, 'duration': best.min_time, 'color': best.color})
        actual[best.name] += best.min_time
        time_now += best.min_time

    return compress(raw), []


def compress(steps):
    out = []
    for s in steps:
        if out and out[-1]['name'] == s['name']:
            out[-1]['duration'] += s['duration']
        else:
            out.append({'name': s['name'], 'duration': s['duration'], 'color': s['color']})
    return out


def generate_schedule(prefix_blocks, cycle_blocks, total_duration):
    """Generates the timeline up to 'total_duration' by unrolling the finite rules."""
    schedule = []
    time_now = 0

    def append_block(block_template):
        nonlocal time_now
        if schedule and schedule[-1]['name'] == block_template['name']:
            schedule[-1]['duration'] += block_template['duration']
        else:
            schedule.append({
                'name': block_template['name'],
                'start': time_now,
                'duration': block_template['duration'],
                'color': block_template['color']
            })
        time_now += block_template['duration']

    for block in prefix_blocks:
        if time_now >= total_duration:
            break
        append_block(block)

    if not cycle_blocks:
        return schedule

    while time_now < total_duration:
        for block in cycle_blocks:
            if time_now >= total_duration:
                break
            append_block(block)

    return schedule


def copy_to_clipboard(root, title, prefix_blocks, cycle_blocks):
    lines = [title, "Rules:"]
    if prefix_blocks:
        lines.append("Prefix:")
        for block in prefix_blocks:
            lines.append(f"- task {block['name']} {block['duration']}min")
    if cycle_blocks:
        lines.append("Cycle:")
        for block in cycle_blocks:
            lines.append(f"- task {block['name']} {block['duration']}min")
        lines.append("- repeat")
    else:
        lines.append("(No cycle found - capped by rule limit or bounded timeline)")

    clipboard_text = "\n".join(lines)
    root.clipboard_clear()
    root.clipboard_append(clipboard_text)


def draw_schedules(root, canvas, test_cases, window_width=900):
    y_offset = 20
    px_per_min = 4
    margin_left = 90
    margin_right = 30
    row_duration = (window_width - margin_left - margin_right) // px_per_min
    row_height = 40
    row_spacing = 20

    tooltip = ToolTip(canvas)

    for title, tasks, total_duration, pre_placed, periods in test_cases:
        prefix_blocks, cycle_blocks = get_schedule_rules(tasks, pre_placed, periods)
        schedule = generate_schedule(prefix_blocks, cycle_blocks, total_duration)

        btn = tk.Button(canvas, text="Copy\nRules", cursor="hand2",
                        command=lambda t=title, p=prefix_blocks, c=cycle_blocks: copy_to_clipboard(root, t, p, c))
        canvas.create_window(15, y_offset, window=btn, anchor="nw")

        txt_id = canvas.create_text(margin_left, y_offset, text=title, font=("Arial", 11, "bold"), anchor="nw")
        bbox = canvas.bbox(txt_id)
        y_offset = bbox[3] + 20

        max_row_idx = 0
        for block in schedule:
            block_start = block['start']
            remaining = block['duration']

            original_start = block['start']
            original_end = original_start + block['duration']
            hover_info = f"Task {block['name']}\nStart: {original_start}\nEnd: {original_end}"

            while remaining > 0:
                row_idx = block_start // row_duration
                start_in_row = block_start % row_duration
                time_in_row = min(remaining, row_duration - start_in_row)

                x1 = margin_left + start_in_row * px_per_min
                x2 = margin_left + (start_in_row + time_in_row) * px_per_min
                y1 = y_offset + row_idx * (row_height + row_spacing)
                y2 = y1 + row_height

                rect_id = canvas.create_rectangle(x1, y1, x2, y2, fill=block['color'],
                                                  outline="black", tags="task_panel")
                tooltip.register(rect_id, hover_info)

                if (x2 - x1) > 30:
                    text_id = canvas.create_text((x1 + x2) / 2, (y1 + y2) / 2,
                                                 text=block['name'], font=("Arial", 10), tags="task_panel")
                    tooltip.register(text_id, hover_info)

                remaining -= time_in_row
                block_start += time_in_row
                max_row_idx = max(max_row_idx, row_idx)

        y_offset += (max_row_idx + 1) * (row_height + row_spacing) + 40

    canvas.config(scrollregion=(0, 0, window_width, y_offset))



def build_test_cases():
    return [
        (
            "Test 1: Normal 50/50 Split (10min each)\n-> Pure Periodic Cycle",
            [Task("A", 50, 10, "#FF9999"), Task("B", 50, 10, "#99CCFF")],
            180, [], []
        ),
        (
            "Test 2: Foreign pre-placed event [40,100)\n-> Locks out A and B equally, so no relative imbalance is created.",
            [Task("A", 50, 10, "#FF9999"), Task("B", 50, 10, "#99CCFF")],
            240,
            [{'name': 'MAINTENANCE', 'start': 40, 'duration': 60, 'color': '#CCCCCC'}],
            []
        ),
        (
            "Test 2b: The pre-placed block IS task A [40,100)\n-> B pre-pays right BEFORE it and catches up right AFTER it.",
            [Task("A", 50, 10, "#FF9999"), Task("B", 50, 10, "#99CCFF")],
            240,
            [{'name': 'A', 'start': 40, 'duration': 60, 'color': '#FF9999'}],
            []
        ),
        (
            "Test 3: C is only allowed in [0,100)\n-> C agglomerates against t=100 and stops far from it.",
            [Task("A", 40, 10, "#FF9999"), Task("B", 40, 10, "#99CCFF"), Task("C", 20, 10, "#99FF99")],
            300,
            [],
            [{'start': 0, 'end': 100, 'allowed': ['A', 'B', 'C']},
             {'start': 100, 'end': 9999, 'allowed': ['A', 'B']}]
        ),
        (
            "Test 3b: C is locked out only in [100,200)\n-> Same pile-up on both edges: the decay is symmetric.",
            [Task("A", 40, 10, "#FF9999"), Task("B", 40, 10, "#99CCFF"), Task("C", 20, 10, "#99FF99")],
            400,
            [],
            [{'start': 0, 'end': 100, 'allowed': ['A', 'B', 'C']},
             {'start': 100, 'end': 200, 'allowed': ['A', 'B']},
             {'start': 200, 'end': 9999, 'allowed': ['A', 'B', 'C']}]
        ),
        (
            "Test 4: Three Tasks (A: 50% 20m, B: 30% 10m, C: 20% 15m)",
            [Task("A", 50, 20, "#FF9999"), Task("B", 30, 10, "#99CCFF"), Task("C", 20, 15, "#99FF99")],
            400, [], []
        )
    ]

def main():
    test_cases = build_test_cases()

    root = tk.Tk()
    root.title("Task Scheduler Timeline with Constraints")
    root.geometry("950x700")

    canvas = tk.Canvas(root, bg="white")
    vbar = tk.Scrollbar(root, orient=tk.VERTICAL, command=canvas.yview)
    canvas.configure(yscrollcommand=vbar.set)

    vbar.pack(side=tk.RIGHT, fill=tk.Y)
    canvas.pack(side=tk.LEFT, fill=tk.BOTH, expand=True)

    def _on_mousewheel(event):
        if getattr(event, 'num', 0) == 4 or event.delta > 0:
            canvas.yview_scroll(-1, "units")
        elif getattr(event, 'num', 0) == 5 or event.delta < 0:
            canvas.yview_scroll(1, "units")

    canvas.bind_all("<MouseWheel>", _on_mousewheel)
    canvas.bind_all("<Button-4>", _on_mousewheel)
    canvas.bind_all("<Button-5>", _on_mousewheel)

    draw_schedules(root, canvas, test_cases, window_width=900)
    root.mainloop()


if __name__ == "__main__":
    main()