#!/usr/bin/env python3
"""
Cyclic proportional-share scheduler with minimum slot durations + Tk timeline.

The rules
---------
Each task has a minimum time (once placed, nothing else can start until it
elapses) and a priority percentage. The timeline is infinite, so the scheduler
returns a finite list of rules instead of a list of slots:

    [ prefix ]  played once (fixed blocks, constrained windows, catch-up)
    [ cycle  ]  repeated forever

Drawing t=0..x just unrolls that list, and any instant can be looked up in
O(log rules) with Plan.task_at().

The cycle
---------
Built analytically on the smallest period that can exist:

    T        = max(min_i / p_i)      <- below this, some task cannot get a slot
    budget_i = p_i * T  (>= min_i)   <- exact share, by construction

Inside the period a virtual-clock greedy (Weighted Fair Queuing) interleaves
the budgets at the finest granularity: it always serves the most starved task
(smallest v[i] = served_i / p_i), giving it just enough to catch up with the
runner-up, never less than its minimum, never more than its remaining budget,
and never leaving a remainder too small to be placed. Shares inside the cycle
are therefore exact, not approximate.

The prefix
----------
Pre-placed blocks cannot be moved, and period windows restrict which tasks are
allowed, so while any of them is still ahead the same greedy runs freely and
its output goes to the prefix. Once the context is frozen forever, the
remaining debt is paid off (bounded by one period: max_lag stops an
interrupted task from monopolising the timeline afterwards) and the cycle is
attached.

Everything is fractions.Fraction, so the shares never drift.
"""

import tkinter as tk
from bisect import bisect_right
from dataclasses import dataclass, field
from fractions import Fraction
from itertools import accumulate
from math import inf

MAX_RULES = 50
IDLE_COLOR = "#F0F0F0"

# A window whose end is FOREVER never closes.  Using None rather than math.inf
# keeps every arithmetic value in the scheduler an exact Fraction.
FOREVER = None


# --------------------------------------------------------------------------- #
#  helpers
# --------------------------------------------------------------------------- #

def frac(x):
    """Exact Fraction from int / str / Fraction, sane one from float."""
    if isinstance(x, Fraction):
        return x
    if isinstance(x, float):
        return Fraction(x).limit_denominator(10 ** 9)
    return Fraction(x)


def ceil_to(x, step):
    return Fraction(-((-x) // step)) * step


def human(d, unit_seconds=60):
    """Pretty-print a duration expressed in minutes."""
    total = frac(d) * unit_seconds
    if total.denominator != 1:
        return f"{float(d):.4g}min"
    s = int(total)
    sign, s = ("-", -s) if s < 0 else ("", s)
    h, r = divmod(s, 3600)
    m, sec = divmod(r, 60)
    out = []
    if h:
        out.append(f"{h}h")
    if m:
        out.append(f"{m}min")
    if sec or not out:
        out.append(f"{sec}s")
    return sign + " ".join(out)


def stamp(t):
    """Absolute time label, e.g. 1h 20min."""
    return human(t)


# --------------------------------------------------------------------------- #
#  data model
# --------------------------------------------------------------------------- #

class Task:
    def __init__(self, name, priority, min_time, color):
        self.name = str(name)
        self.priority = frac(priority)
        self.min_time = frac(min_time)
        self.color = color
        if self.priority < 0:
            raise ValueError(f"{name}: negative priority")
        if self.min_time <= 0:
            raise ValueError(f"{name}: min_time must be > 0")


@dataclass(frozen=True)
class Slot:
    """A rule: run `task` for `duration`."""
    task: str
    duration: Fraction
    color: str = "#DDDDDD"

    def __str__(self):
        return f"{self.task} {human(self.duration)}"


@dataclass(frozen=True)
class Placement:
    """A concrete piece of timeline."""
    task: str
    start: Fraction
    end: Fraction
    color: str = "#DDDDDD"

    @property
    def duration(self):
        return self.end - self.start


@dataclass
class Plan:
    """`prefix` once, then `cycle` forever, starting at `start`."""
    start: Fraction
    prefix: list
    cycle: list
    shares: dict = field(default_factory=dict)

    def __post_init__(self):
        self.period = sum((s.duration for s in self.cycle), Fraction(0))
        self.cycle_start = self.start + sum((s.duration for s in self.prefix),
                                            Fraction(0))
        self._pre_off = [Fraction(0), *accumulate(s.duration for s in self.prefix)]
        self._cyc_off = [Fraction(0), *accumulate(s.duration for s in self.cycle)]

    def task_at(self, t):
        """Which task occupies instant t?  O(log rules)."""
        t = frac(t)
        if t < self.start:
            return None
        if t < self.cycle_start:
            return self.prefix[bisect_right(self._pre_off, t - self.start) - 1].task
        if not self.period:
            return None
        k = (t - self.cycle_start) % self.period
        return self.cycle[bisect_right(self._cyc_off, k) - 1].task


# --------------------------------------------------------------------------- #
#  the scheduler
# --------------------------------------------------------------------------- #

class Scheduler:
    """Builds the finite rule list."""

    def __init__(self, tasks, resolution=None, max_lag=None):
        tasks = list(tasks)
        active = [t for t in tasks if t.priority > 0]
        if not active:
            raise ValueError("no task with a positive priority")
        total = sum(t.priority for t in active)

        self.tasks = active
        self.p = {t.name: t.priority / total for t in active}
        self.minimum = {t.name: t.min_time for t in active}
        self.color = {t.name: t.color for t in active}
        self.resolution = frac(resolution) if resolution else None
        # smallest period able to give every task one slot >= its minimum
        self.min_period = max(self.minimum[n] / self.p[n] for n in self.p)
        # a task may never be more than one period's worth of virtual time
        # ahead of / behind the clock: bounds the catch-up after an interruption
        self.max_lag = frac(max_lag) if max_lag is not None else self.min_period

    # ---------------- shares, restricted to a set of tasks ---------------- #

    def _shares(self, allowed):
        total = sum(self.p[n] for n in allowed)
        return {n: self.p[n] / total for n in allowed}

    def _period(self, allowed):
        p = self._shares(allowed)
        return max(self.minimum[n] / p[n] for n in allowed)

    # ---------------- one greedy step ---------------- #

    def _pick(self, v, candidates):
        # most starved task; ties -> biggest share, then name (deterministic)
        return min(candidates, key=lambda n: (v[n], -self.p[n], n))

    def _chunk(self, name, v, candidates, p=None):
        p = p or self.p
        others = [v[n] for n in candidates if n != name]
        target = min(others) if others else v[name]
        need = p[name] * (target - v[name])     # time to catch the runner-up
        c = max(self.minimum[name], need)
        if self.resolution:
            c = ceil_to(c, self.resolution)
        return c

    def _clamp(self, v, t):
        lo, hi = t - self.max_lag, t + self.max_lag
        for n in v:
            v[n] = min(max(v[n], lo), hi)

    # ---------------- the repeating cycle ---------------- #

    def steady_cycle(self, allowed):
        """
        The cycle, built on the smallest possible period:

            T          = max(min_i / p_i)          <- provably minimal
            budget_i   = p_i * T  (>= min_i)       <- exact share, by construction

        Inside the period the same greedy runs, with two extra rules: a slot
        never exceeds the task's remaining budget, and it never leaves a
        remainder smaller than the minimum (that crumb could not be placed).
        """
        allowed = sorted(allowed)
        p = self._shares(allowed)
        T = self._period(allowed)
        rem = {n: p[n] * T for n in allowed}
        v = {n: Fraction(0) for n in allowed}

        slots = []
        while any(rem[n] > 0 for n in allowed):
            live = [n for n in allowed if rem[n] > 0]
            name = self._pick(v, live)
            c = min(self._chunk(name, v, live, p), rem[name])
            if rem[name] - c < self.minimum[name]:      # no unplaceable crumb
                c = rem[name]
            slots.append(Slot(name, c, self.color[name]))
            v[name] += c / p[name]
            rem[name] -= c
        return slots

    def coarse_cycle(self, allowed):
        """One slot per task: same minimal period and exact shares, but the
        coarsest possible interleaving. Used when the fine cycle needs too
        many rules to stay a practical, finite list."""
        p = self._shares(allowed)
        T = self._period(allowed)
        order = sorted(allowed, key=lambda n: (-p[n], n))
        return [Slot(n, p[n] * T, self.color[n]) for n in order]

    # ---------------- context (pre-placed blocks / periods) ---------------- #

    @staticmethod
    def _normalise_periods(periods):
        """Windows with exact Fraction bounds; an infinite end becomes FOREVER."""
        out = []
        for w in periods:
            end = w.get('end', FOREVER)
            out.append({'start': frac(w['start']),
                        'end': FOREVER if end is FOREVER or end == inf else frac(end),
                        'allowed': set(w['allowed'])})
        return out

    @staticmethod
    def _active_pre(pre, t):
        for p in pre:
            if p.start <= t < p.end:
                return p
        return None

    def _allowed_at(self, periods, t):
        for w in periods:
            if w['start'] <= t and (w['end'] is FOREVER or t < w['end']):
                return [n for n in self.p if n in w['allowed']]
        return list(self.p)

    @staticmethod
    def _next_boundary(pre, periods, t):
        """Next instant where the context changes, or None if it never does."""
        best = None
        for p in pre:
            if p.start > t and (best is None or p.start < best):
                best = p.start
        for w in periods:
            for b in (w['start'], w['end']):
                if b is not FOREVER and b > t and (best is None or b < best):
                    best = b
        return best

    # ---------------- the plan ---------------- #

    def plan(self, timeline=(), periods=(), t_now=0, lookback=None,
             max_rules=MAX_RULES):
        """
        Phase 1: walk the constrained part of the timeline (pre-placed blocks,
                 period windows) with the free greedy  -> prefix.
        Phase 2: once the context is frozen forever, pay off the remaining debt
                 -> prefix, then attach the analytic cycle -> cycle.
        """
        t_now = frac(t_now)
        timeline = sorted(timeline, key=lambda p: p.start)
        periods = self._normalise_periods(periods)

        past = [p for p in timeline if p.end <= t_now]
        pre = [p for p in timeline if p.end > t_now]     # committed / still ahead

        # --- debt inherited from the already-placed past ---------------- #
        window = frac(lookback) if lookback is not None else self.min_period
        w_start = t_now - window
        if past:
            w_start = max(w_start, min(p.start for p in past))
        w_start = min(w_start, t_now)
        elapsed = t_now - w_start

        served = {n: Fraction(0) for n in self.p}
        for p in past:
            if p.task in served:
                served[p.task] += max(Fraction(0),
                                      min(p.end, t_now) - max(p.start, w_start))

        lag = {n: served[n] / self.p[n] - elapsed for n in self.p}
        base = min(lag.values())
        t = t_now
        v = {n: t + lag[n] - base for n in self.p}      # only differences matter
        self._clamp(v, t)

        slots = []

        # --- phase 1: constrained part of the timeline ------------------- #
        while len(slots) < max_rules:
            block = self._active_pre(pre, t)
            if block:                                   # cannot be moved
                slots.append(Slot(block.task, block.end - t, block.color))
                if block.task in v:
                    v[block.task] += (block.end - t) / self.p[block.task]
                t = block.end
                self._clamp(v, t)
                continue

            limit = self._next_boundary(pre, periods, t)
            if limit is None:
                break                                   # context frozen: phase 2

            allowed = self._allowed_at(periods, t)
            gap = limit - t
            fitting = [n for n in allowed if self.minimum[n] <= gap]
            if not fitting:                             # nothing fits: idle
                slots.append(Slot("IDLE", gap, IDLE_COLOR))
                t = limit
                continue

            name = self._pick(v, fitting)
            c = min(self._chunk(name, v, fitting), gap)
            slots.append(Slot(name, c, self.color[name]))
            v[name] += c / self.p[name]
            t += c
            self._clamp(v, t)

        # --- phase 2: pay off the debt, then repeat forever -------------- #
        cycle = []
        allowed = self._allowed_at(periods, t)
        if allowed and len(slots) < max_rules:
            horizon = t + self._period(allowed)         # catch-up is bounded
            while len(slots) < max_rules and t < horizon:
                if len({v[n] for n in allowed}) == 1:
                    break                               # square again
                name = self._pick(v, allowed)
                c = self._chunk(name, v, allowed)
                slots.append(Slot(name, c, self.color[name]))
                v[name] += c / self.p[name]
                t += c
                self._clamp(v, t)
            cycle = self.steady_cycle(allowed)
            if len(cycle) > max_rules:
                cycle = self.coarse_cycle(allowed)

        prefix, cycle = self._tidy(slots, cycle)
        period = sum((s.duration for s in cycle), Fraction(0))
        shares = {}
        if period:
            for s in cycle:
                shares[s.task] = shares.get(s.task, Fraction(0)) + s.duration / period
        return Plan(start=t_now, prefix=prefix, cycle=cycle, shares=shares)

    # ---------------- internals ---------------- #

    @staticmethod
    def _merge_run(slots):
        out = []
        for s in slots:
            if out and out[-1].task == s.task:
                out[-1] = Slot(s.task, out[-1].duration + s.duration, s.color)
            else:
                out.append(s)
        return out

    def _tidy(self, prefix, cycle):
        """No two consecutive slots of the same task, wrap-around included."""
        prefix, cycle = self._merge_run(list(prefix)), self._merge_run(list(cycle))

        # the cycle wraps onto itself: move the head out, merge it into the tail
        while len(cycle) > 1 and cycle[0].task == cycle[-1].task:
            head = cycle.pop(0)
            cycle[-1] = Slot(cycle[-1].task,
                             cycle[-1].duration + head.duration, head.color)
            prefix.append(head)
        prefix = self._merge_run(prefix)

        # prefix/cycle junction: rotate the cycle once (timeline unchanged)
        for _ in range(len(cycle)):
            if not (prefix and len(cycle) > 1 and prefix[-1].task == cycle[0].task):
                break
            head = cycle.pop(0)
            cycle.append(head)
            prefix[-1] = Slot(head.task,
                              prefix[-1].duration + head.duration, head.color)
        return prefix, cycle


# --------------------------------------------------------------------------- #
#  presentation
# --------------------------------------------------------------------------- #

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

        text = self.data.get(current[0])
        if not text:
            return

        x, y = event.x_root + 15, event.y_root + 15
        self.tooltip_window = tk.Toplevel(self.canvas)
        self.tooltip_window.wm_overrideredirect(True)
        self.tooltip_window.wm_geometry(f"+{x}+{y}")

        label = tk.Label(self.tooltip_window, text=text, background="#ffffe0",
                         relief="solid", borderwidth=1, justify="left",
                         font=("Arial", 9))
        label.pack()

    def hide_tooltip(self, event):
        if self.tooltip_window:
            self.tooltip_window.destroy()
            self.tooltip_window = None

    def move_tooltip(self, event):
        if self.tooltip_window:
            x, y = event.x_root + 15, event.y_root + 15
            self.tooltip_window.wm_geometry(f"+{x}+{y}")


def get_schedule_rules(tasks, pre_placed=None, periods=None, t_now=0):
    """Finite rule list: (prefix_blocks, cycle_blocks). Caps at MAX_RULES."""
    timeline = [Placement(p['name'], frac(p['start']),
                          frac(p['start']) + frac(p['duration']),
                          p.get('color', '#CCCCCC'))
                for p in (pre_placed or [])]
    plan = Scheduler(tasks).plan(timeline=timeline, periods=periods or [],
                                 t_now=t_now, max_rules=MAX_RULES)
    as_blocks = lambda slots: [{'name': s.task, 'duration': s.duration,
                                'color': s.color} for s in slots]
    return as_blocks(plan.prefix), as_blocks(plan.cycle), plan


def generate_schedule(prefix_blocks, cycle_blocks, total_duration,
                      start=Fraction(0)):
    """Generates the timeline up to 'total_duration' by unrolling the rules."""
    schedule = []
    time_now = frac(start)

    def append_block(block_template):
        nonlocal time_now
        if schedule and schedule[-1]['name'] == block_template['name']:
            schedule[-1]['duration'] += block_template['duration']
        else:
            schedule.append({'name': block_template['name'],
                             'start': time_now,
                             'duration': block_template['duration'],
                             'color': block_template['color']})
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


def copy_to_clipboard(root, title, prefix_blocks, cycle_blocks, plan):
    lines = [title, "Rules:"]
    if prefix_blocks:
        lines.append("Prefix:")
        for block in prefix_blocks:
            lines.append(f"- task {block['name']} {human(block['duration'])}")
    if cycle_blocks:
        lines.append("Cycle:")
        for block in cycle_blocks:
            lines.append(f"- task {block['name']} {human(block['duration'])}")
        lines.append("- repeat")
        lines.append(f"Period: {human(plan.period)}")
        lines.append("Shares: " + ", ".join(
            f"{n} {float(s) * 100:.4g}%" for n, s in sorted(plan.shares.items())))
    else:
        lines.append("(No cycle found - capped by rule limit or bounded timeline)")

    root.clipboard_clear()
    root.clipboard_append("\n".join(lines))


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
        prefix_blocks, cycle_blocks, plan = get_schedule_rules(tasks, pre_placed,
                                                               periods)
        schedule = generate_schedule(prefix_blocks, cycle_blocks, total_duration,
                                     start=plan.start)

        btn = tk.Button(canvas, text="Copy\nRules", cursor="hand2",
                        command=lambda t=title, p=prefix_blocks, c=cycle_blocks,
                        pl=plan: copy_to_clipboard(root, t, p, c, pl))
        canvas.create_window(15, y_offset, window=btn, anchor="nw")

        if cycle_blocks:
            summary = (f"period {human(plan.period)}   |   " + ", ".join(
                f"{n} {float(s) * 100:.4g}%" for n, s in sorted(plan.shares.items())))
        else:
            summary = f"no cycle found (capped at {MAX_RULES} rules)"

        txt_id = canvas.create_text(margin_left, y_offset, text=title,
                                    font=("Arial", 11, "bold"), anchor="nw")
        bbox = canvas.bbox(txt_id)
        sub_id = canvas.create_text(margin_left, bbox[3] + 2, text=summary,
                                    font=("Arial", 9), fill="#555555", anchor="nw")
        y_offset = canvas.bbox(sub_id)[3] + 18

        max_row_idx = 0
        for block in schedule:
            block_start = block['start']
            remaining = block['duration']

            original_start = block['start']
            original_end = original_start + block['duration']
            hover_info = (f"Task {block['name']}\n"
                          f"Start: {stamp(original_start)}\n"
                          f"End: {stamp(original_end)}\n"
                          f"Duration: {human(block['duration'])}")

            while remaining > 0:
                row_idx = int(block_start // row_duration)
                start_in_row = block_start % row_duration
                time_in_row = min(remaining, row_duration - start_in_row)

                x1 = margin_left + float(start_in_row) * px_per_min
                x2 = margin_left + float(start_in_row + time_in_row) * px_per_min
                y1 = y_offset + row_idx * (row_height + row_spacing)
                y2 = y1 + row_height

                rect_id = canvas.create_rectangle(x1, y1, x2, y2, fill=block['color'],
                                                  outline="black", tags="task_panel")
                tooltip.register(rect_id, hover_info)

                if (x2 - x1) > 30:
                    text_id = canvas.create_text((x1 + x2) / 2, (y1 + y2) / 2,
                                                 text=block['name'],
                                                 font=("Arial", 10), tags="task_panel")
                    tooltip.register(text_id, hover_info)

                remaining -= time_in_row
                block_start += time_in_row
                max_row_idx = max(max_row_idx, row_idx)

        for row_idx in range(max_row_idx + 1):
            y1 = y_offset + row_idx * (row_height + row_spacing)
            canvas.create_text(margin_left - 8, y1 + row_height / 2,
                               text=stamp(row_idx * row_duration),
                               font=("Arial", 8), fill="#777777", anchor="e")

        y_offset += (max_row_idx + 1) * (row_height + row_spacing) + 40

    canvas.config(scrollregion=(0, 0, window_width, y_offset))


def main():
    test_cases = [
        (
            (
                "Test 1: Normal 50/50 Split (10min each)\n"
                "-> Pure periodic cycle, no prefix."
            ),
            [
                Task("A", priority=50, min_time=10, color="#FF9999"),
                Task("B", priority=50, min_time=10, color="#99CCFF")
            ],
            180, [], []
        ),
        (
            (
                "Test 2: Pre-placed event interrupts the timeline\n"
                "-> Both tasks lose the same amount, so they resume alternating: "
                "no monopoly block."
            ),
            [
                Task("A", priority=50, min_time=10, color="#FF9999"),
                Task("B", priority=50, min_time=10, color="#99CCFF")
            ],
            240,
            [{'name': 'MAINTENANCE', 'start': 40, 'duration': 60, 'color': '#CCCCCC'}],
            []
        ),
        (
            (
                "Test 3: Periods constraint\n"
                "-> C is only allowed before t=100; after that A and B share the "
                "timeline forever."
            ),
            [
                Task("A", priority=40, min_time=10, color="#FF9999"),
                Task("B", priority=40, min_time=10, color="#99CCFF"),
                Task("C", priority=20, min_time=10, color="#99FF99")
            ],
            300,
            [],
            [{'start': 0, 'end': 100, 'allowed': ['A', 'B', 'C']},
             {'start': 100, 'end': inf, 'allowed': ['A', 'B']}]
        ),
        (
            (
                "Test 4: Three tasks (A: 50% 20m, B: 30% 10m, C: 20% 15m)\n"
                "-> Minimums force a 75min period; shares are exact."
            ),
            [
                Task("A", priority=50, min_time=20, color="#FF9999"),
                Task("B", priority=30, min_time=10, color="#99CCFF"),
                Task("C", priority=20, min_time=15, color="#99FF99")
            ],
            400,
            [],
            []
        ),
        (
            (
                "Test 5: Lopsided priorities + an already-placed past\n"
                "-> B ran 0..40 while its share is 10%, so A gets a long catch-up "
                "prefix."
            ),
            [
                Task("A", priority=90, min_time=10, color="#FF9999"),
                Task("B", priority=10, min_time=10, color="#99CCFF")
            ],
            600,
            [{'name': 'B', 'start': 0, 'duration': 40, 'color': "#99CCFF"}],
            []
        ),
    ]

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