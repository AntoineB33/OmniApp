#!/usr/bin/env python3
"""
Combined Scheduler:
- Runs the 9 tests from test.py using the exact fractional math scheduler.
- Runs Test 10 using purely algebraic, O(1) rules parameterized by a moving t_p,
  updating in real time.
- Displays results in the terminal and in the Tkinter window.

Run with --verify to check Test 10's invariants without opening the UI.
"""

import sys
from dataclasses import dataclass, field

try:
    import tkinter as tk
except ImportError:          # headless box: --verify and --no-ui still work
    tk = None

import itertools
from fractions import Fraction
from math import exp, inf, log

# =========================================================================== #
#  test.py Logic (For the 9 tests + UI)
# =========================================================================== #

MAX_RULES = 50
IDLE_COLOR = "#F0F0F0"
FOREVER = None

def frac(x):
    if isinstance(x, Fraction): return x
    if isinstance(x, float): return Fraction(x).limit_denominator(10 ** 9)
    return Fraction(x)

def ceil_to(x, step):
    return Fraction(-((-x) // step)) * step

def human(d, unit_seconds=60):
    """Render a duration given in minutes. Sub-second parts survive as decimals,
    so a debt-repayment block reads '10min 12.6s' instead of collapsing."""
    total = float(frac(d) * unit_seconds)
    sign, total = ("-", -total) if total < 0 else ("", total)
    h = int(total // 3600)
    rest = total - 3600 * h
    m = int(rest // 60)
    sec = rest - 60 * m
    out = []
    if h: out.append(f"{h}h")
    if m: out.append(f"{m}min")
    if sec > 1e-9 or not out:
        out.append(f"{round(sec)}s" if abs(sec - round(sec)) < 1e-9 else f"{sec:.4g}s")
    return sign + " ".join(out)

def stamp(t):
    return human(t)

class Task:
    def __init__(self, name, priority, min_time, color):
        self.name = str(name)
        self.priority = frac(priority)
        self.min_time = frac(min_time)
        self.color = color

@dataclass(frozen=True)
class Slot:
    task: str
    duration: Fraction
    color: str = "#DDDDDD"

@dataclass(frozen=True)
class Placement:
    task: str
    start: Fraction
    end: Fraction
    color: str = "#DDDDDD"
    @property
    def duration(self): return self.end - self.start

@dataclass
class Plan:
    start: Fraction
    prefix: list
    cycle: list
    shares: dict = field(default_factory=dict)

    def __post_init__(self):
        self.period = sum((s.duration for s in self.cycle), Fraction(0))

class Scheduler:
    def __init__(self, tasks, resolution=None, max_lag=None, tau=None, max_boost=6, field_floor=Fraction(1, 10), max_reach=None):
        tasks = list(tasks)
        active = [t for t in tasks if t.priority > 0]
        total = sum(t.priority for t in active)
        self.tasks = active
        self.p = {t.name: t.priority / total for t in active}
        self.minimum = {t.name: t.min_time for t in active}
        self.color = {t.name: t.color for t in active}
        self.resolution = frac(resolution) if resolution else None
        self.min_period = max(self.minimum[n] / self.p[n] for n in self.p)

        self.tau = frac(tau) if tau is not None else self.min_period
        self.max_boost = frac(max_boost)
        self.field_floor = frac(field_floor)
        self.max_reach = frac(max_reach) if max_reach is not None else 6 * self.tau
        self.max_amp = float(self.field_floor) * (exp(float(self.max_reach) / float(self.tau)) - 1.0)
        self.field = {}
        self.field_end = None
        self.max_lag = frac(max_lag) if max_lag is not None else None

    def _shares(self, allowed):
        total = sum(self.p[n] for n in allowed)
        return {n: self.p[n] / total for n in allowed}

    def _period(self, allowed):
        p = self._shares(allowed)
        return max(self.minimum[n] / p[n] for n in allowed)

    def _sources(self, timeline, periods):
        everyone = set(self.p)
        raw = []
        for p in timeline:
            if p.end > p.start: raw.append((p.start, p.end, everyone - {p.task}))
        for w in periods:
            end = inf if w['end'] is FOREVER else w['end']
            if end > w['start']: raw.append((w['start'], end, everyone - set(w['allowed'])))

        spans = {n: [] for n in everyone}
        for start, end, excluded in raw:
            if len(excluded) == len(everyone): continue
            for n in excluded: spans[n].append((start, end))
        return {n: self._merge_spans(s) for n, s in spans.items() if s}

    @staticmethod
    def _merge_spans(spans):
        out = []
        for start, end in sorted(spans):
            if out and start <= out[-1][1]: out[-1] = (out[-1][0], max(out[-1][1], end))
            else: out.append((start, end))
        return out

    def _set_field(self, timeline=(), periods=()):
        tau = float(self.tau)
        self.field = {}
        self.field_end = None
        for name, spans in self._sources(timeline, periods).items():
            entries = []
            for start, end in spans:
                length = inf if end == inf else float(end - start)
                if length < self.minimum[name]: continue
                amp = min(length / tau, self.max_amp)
                entries.append((start, end, amp))
                if end == inf: continue
                reach = tau * log(1.0 + amp / float(self.field_floor))
                stop = end + frac(reach)
                if self.field_end is None or stop > self.field_end:
                    self.field_end = stop
            self.field[name] = entries

    def _boost(self, name, t):
        spans = self.field.get(name)
        if not spans: return Fraction(1)
        tau, acc = 0.0, 0.0
        tau = float(self.tau)
        for start, end, amp in spans:
            if start <= t <= end: d = 0.0
            elif t < start: d = float(start - t)
            else: d = float(t - end)
            acc += amp * exp(-d / tau)
        if acc <= 0.0: return Fraction(1)
        return frac(1.0 + min(acc, float(self.max_boost) - 1.0))

    def _relax(self, v, dt, T, active):
        if not active: return
        lo = min(v[n] for n in active)
        if dt > 0:
            f = frac(exp(-float(dt) / float(self.tau)))
            for n in active:
                over = v[n] - lo - T
                if over > 0: v[n] -= over * (1 - f)
        for n in v:
            if n not in active:
                v[n] = min(max(v[n], lo - T), lo + T)

    def _pick(self, v, candidates, last=None):
        pool = [n for n in candidates if n != last] or list(candidates)
        return min(pool, key=lambda n: (v[n], -self.p[n], n))

    def _chunk(self, name, v, candidates, p=None, boost=None, T=None):
        p = p or self.p
        others = [v[n] for n in candidates if n != name]
        target = min(others) if others else v[name]
        need = p[name] * (target - v[name])
        c = max(self.minimum[name], need)
        if boost is not None:
            unit = max(self.minimum[name], p[name] * T)
            c = min(max(c, self.minimum[name] * boost), unit * boost)
        if self.resolution: c = ceil_to(c, self.resolution)
        return c

    def _clamp(self, v, t):
        if self.max_lag is None: return
        lo, hi = t - self.max_lag, t + self.max_lag
        for n in v: v[n] = min(max(v[n], lo), hi)

    @staticmethod
    def _push(slots, task, duration, color):
        if slots and slots[-1].task == task:
            slots[-1] = Slot(task, slots[-1].duration + duration, color)
        else:
            slots.append(Slot(task, duration, color))

    def steady_cycle(self, allowed):
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
            if rem[name] - c < self.minimum[name]: c = rem[name]
            slots.append(Slot(name, c, self.color[name]))
            v[name] += c / p[name]
            rem[name] -= c
        return slots

    def coarse_cycle(self, allowed):
        p = self._shares(allowed)
        T = self._period(allowed)
        order = sorted(allowed, key=lambda n: (-p[n], n))
        return [Slot(n, p[n] * T, self.color[n]) for n in order]

    @staticmethod
    def _normalise_periods(periods):
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
            if p.start <= t < p.end: return p
        return None

    def _allowed_at(self, periods, t):
        for w in periods:
            if w['start'] <= t and (w['end'] is FOREVER or t < w['end']):
                return [n for n in self.p if n in w['allowed']]
        return list(self.p)

    @staticmethod
    def _next_boundary(pre, periods, t):
        best = None
        for p in pre:
            if p.start > t and (best is None or p.start < best): best = p.start
        for w in periods:
            for b in (w['start'], w['end']):
                if b is not FOREVER and b > t and (best is None or b < best): best = b
        return best

    def _blocked_from(self, name, pre, periods, t):
        best = None
        for p in pre:
            if p.start > t and (best is None or p.start < best): best = p.start
        bounds = sorted({b for w in periods for b in (w['start'], w['end']) if b is not FOREVER and b > t})
        for b in bounds:
            if best is not None and b >= best: break
            if name not in self._allowed_at(periods, b): return b
        return best

    def _next_placeable(self, missing, pre, periods, t):
        if not missing: return None
        bounds = sorted({b for w in periods for b in (w['start'], w['end']) if b is not FOREVER and b > t}
                        | {p.end for p in pre if p.end > t})
        for b in bounds:
            for n in missing:
                if n not in self._allowed_at(periods, b): continue
                room = self._blocked_from(n, pre, periods, b)
                if room is None or room - b >= self.minimum[n]: return b
        return None

    def plan(self, timeline=(), periods=(), t_now=0, lookback=None, max_rules=MAX_RULES):
        t_now = frac(t_now)
        timeline = sorted(timeline, key=lambda p: p.start)
        periods = self._normalise_periods(periods)
        self._set_field(timeline, periods)

        past = [p for p in timeline if p.end <= t_now]
        pre = [p for p in timeline if p.end > t_now]

        window = frac(lookback) if lookback is not None else self.min_period
        w_start = t_now - window
        if past: w_start = max(w_start, min(p.start for p in past))
        w_start = min(w_start, t_now)
        elapsed = t_now - w_start

        served = {n: Fraction(0) for n in self.p}
        for p in past:
            if p.task in served:
                served[p.task] += max(Fraction(0), min(p.end, t_now) - max(p.start, w_start))

        lag = {n: served[n] / self.p[n] - elapsed for n in self.p}
        base = min(lag.values())
        t = t_now
        v = {n: t + lag[n] - base for n in self.p}
        self._clamp(v, t)

        slots = []
        last = None
        free_tail = False
        steps, max_steps = 0, 200 * max_rules

        def owed(n):
            if n == last and slots and slots[-1].task == n:
                return max(Fraction(0), self.minimum[n] - slots[-1].duration)
            return self.minimum[n]

        while len(slots) < max_rules and steps < max_steps:
            steps += 1
            allowed = self._allowed_at(periods, t)
            T = self._period(allowed) if allowed else self.min_period

            block = self._active_pre(pre, t)
            if block:
                d = block.end - t
                self._push(slots, block.task, d, block.color)
                if block.task in v: v[block.task] += d / self.p[block.task]
                t = block.end
                last = block.task
                free_tail = False
                self._relax(v, 0, T, allowed)
                self._clamp(v, t)
                continue

            limit = self._next_boundary(pre, periods, t)
            if limit is None and (self.field_end is None or t >= self.field_end):
                break

            room = {n: self._blocked_from(n, pre, periods, t) for n in allowed}
            fitting = [n for n in allowed if room[n] is None or room[n] - t >= owed(n)]

            if not fitting:
                if limit is None: break
                gap = limit - t
                if free_tail and slots:
                    tail = slots[-1]
                    slots[-1] = Slot(tail.task, tail.duration + gap, tail.color)
                    v[tail.task] += gap / self.p[tail.task]
                else:
                    self._push(slots, "IDLE", gap, IDLE_COLOR)
                    last = None
                    free_tail = False
                t += gap
                continue

            name = self._pick(v, fitting, last)
            boost = self._boost(name, t)
            c = self._chunk(name, v, fitting, boost=boost, T=T)

            room_limit = room[name]
            if room_limit is not None:
                c = min(c, room_limit - t)

            back = self._next_placeable([n for n in self.p if n not in fitting], pre, periods, t)
            if back is not None:
                c = min(c, max(back, t + owed(name)) - t)

            self._push(slots, name, c, self.color[name])
            v[name] += c / (self.p[name] * boost)
            t += c
            last = name
            free_tail = True
            self._relax(v, c, T, allowed)
            self._clamp(v, t)

        cycle = []
        allowed = self._allowed_at(periods, t)
        if allowed and len(slots) < max_rules:
            T = self._period(allowed)
            horizon = t + 4 * T
            while len(slots) < max_rules and t < horizon:
                spread = max(v[n] for n in allowed) - min(v[n] for n in allowed)
                if spread <= T: break
                name = self._pick(v, allowed, last)
                boost = self._boost(name, t)
                c = self._chunk(name, v, allowed, boost=boost, T=T)
                self._push(slots, name, c, self.color[name])
                v[name] += c / (self.p[name] * boost)
                t += c
                last = name
                self._relax(v, c, T, allowed)
                self._clamp(v, t)
            cycle = self.steady_cycle(allowed)
            if len(cycle) > max_rules: cycle = self.coarse_cycle(allowed)
            cycle = self._phase(cycle, v, allowed, last)

        prefix, cycle = self._tidy(slots, cycle)
        period = sum((s.duration for s in cycle), Fraction(0))
        shares = {}
        if period:
            for s in cycle:
                shares[s.task] = shares.get(s.task, Fraction(0)) + s.duration / period
        return Plan(start=t_now, prefix=prefix, cycle=cycle, shares=shares)

    def _phase(self, cycle, v, allowed, last):
        if not cycle: return cycle
        first = self._pick(v, allowed, last)
        for i, s in enumerate(cycle):
            if s.task == first: return cycle[i:] + cycle[:i]
        return cycle

    @staticmethod
    def _merge_run(slots):
        out = []
        for s in slots:
            if out and out[-1].task == s.task:
                out[-1] = Slot(s.task, out[-1].duration + s.duration, s.color)
            else: out.append(s)
        return out

    def _tidy(self, prefix, cycle):
        prefix, cycle = self._merge_run(list(prefix)), self._merge_run(list(cycle))
        while len(cycle) > 1 and cycle[0].task == cycle[-1].task:
            head = cycle.pop(0)
            cycle[-1] = Slot(cycle[-1].task, cycle[-1].duration + head.duration, head.color)
            prefix.append(head)
        prefix = self._merge_run(prefix)
        for _ in range(len(cycle)):
            if not (prefix and len(cycle) > 1 and prefix[-1].task == cycle[0].task): break
            head = cycle.pop(0)
            cycle.append(head)
            prefix[-1] = Slot(head.task, prefix[-1].duration + head.duration, head.color)
        return prefix, cycle

# =========================================================================== #
#  Rule segments
# =========================================================================== #
# A rule list is a list of segments. A segment is either a plain block
#   {'name', 'duration', 'color'}
# or a repeated group
#   {'repeat': n, 'blocks': [block, ...]}
# The repeated group is what keeps the rule list O(1): the frozen past of Test 10
# is n identical cycles, and n must never be spelled out block by block.

def flatten(segments):
    out = []
    for seg in segments:
        if 'blocks' in seg:
            for _ in range(int(seg.get('repeat', 1))):
                out.extend(seg['blocks'])
        else:
            out.append(seg)
    return out

def rule_lines(segments, indent="- "):
    lines = []
    for seg in segments:
        if 'blocks' in seg:
            lines.append(f"{indent}repeat {int(seg['repeat'])}x:")
            for b in seg['blocks']:
                lines.append(f"{indent}    task {b['name']} {human(b['duration'])}")
        else:
            lines.append(f"{indent}task {seg['name']} {human(seg['duration'])}")
    return lines

# =========================================================================== #
#  UI & Harness
# =========================================================================== #

class ToolTip:
    """Hover bubble driven by WHERE THE POINTER IS, not by item Enter/Leave events.

    Test 10 deletes and recreates its blocks every frame. An item destroyed under
    a motionless cursor delivers no reliable <Leave>, and its replacement delivers
    a fresh <Enter>, so an Enter/Leave-bound tooltip both stranded windows on the
    screen and leaked a new Toplevel per frame. Here a single reusable window is
    shown/updated/hidden from the item actually under the pointer, and `refresh()`
    re-evaluates that after any redraw or scroll.
    """

    def __init__(self, canvas):
        self.canvas = canvas
        self.tooltip_window = None
        self.label = None
        self.data = {}
        self.canvas.bind("<Motion>", self._on_motion, add="+")
        self.canvas.bind("<Leave>", self._on_leave, add="+")
        self.canvas.bind("<Button>", self._on_leave, add="+")
        for seq in ("<MouseWheel>", "<Button-4>", "<Button-5>"):
            self.canvas.bind_all(seq, lambda e: self.canvas.after_idle(self.refresh), add="+")

    def register(self, item_id, text):
        self.data[item_id] = text

    def unregister(self, item_id):
        self.data.pop(item_id, None)

    def refresh(self):
        """Re-evaluate the bubble against the current pointer position."""
        if not self.canvas.winfo_exists(): return
        root_x, root_y = self.canvas.winfo_pointerxy()
        x = root_x - self.canvas.winfo_rootx()
        y = root_y - self.canvas.winfo_rooty()
        inside = 0 <= x < self.canvas.winfo_width() and 0 <= y < self.canvas.winfo_height()
        text = self._text_under(x, y) if inside else None
        self._apply(text, root_x, root_y)

    def hide(self):
        if self.tooltip_window:
            self.tooltip_window.destroy()
            self.tooltip_window = None
            self.label = None

    def _on_motion(self, event):
        self._apply(self._text_under(event.x, event.y), event.x_root, event.y_root)

    def _on_leave(self, _event):
        self.hide()

    def _text_under(self, x, y):
        cx, cy = self.canvas.canvasx(x), self.canvas.canvasy(y)
        for item in reversed(self.canvas.find_overlapping(cx, cy, cx, cy)):
            text = self.data.get(item)
            if text: return text
        return None

    def _apply(self, text, root_x, root_y):
        if tk is None:
            # No Tk available (headless); nothing to show
            return
        if not text:
            self.hide()
            return
        x, y = root_x + 15, root_y + 15
        if self.tooltip_window is None:
            self.tooltip_window = tk.Toplevel(self.canvas)
            self.tooltip_window.wm_overrideredirect(True)
            self.label = tk.Label(self.tooltip_window, text=text, background="#ffffe0",
                                  relief="solid", borderwidth=1, justify="left", font=("Arial", 9))
            self.label.pack()
        elif self.label is not None:
            self.label.config(text=text)
        self.tooltip_window.wm_geometry(f"+{x}+{y}")

def get_schedule_rules(tasks, pre_placed=None, periods=None, t_now=0, **kw):
    timeline = [Placement(p['name'], frac(p['start']), frac(p['start']) + frac(p['duration']), p.get('color', '#CCCCCC'))
                for p in (pre_placed or [])]
    plan = Scheduler(tasks, **kw).plan(timeline=timeline, periods=periods or [], t_now=t_now, max_rules=MAX_RULES)
    as_blocks = lambda slots: [{'name': s.task, 'duration': s.duration, 'color': s.color} for s in slots]
    return as_blocks(plan.prefix), as_blocks(plan.cycle), plan

def generate_schedule(prefix_blocks, cycle_blocks, total_duration, start=Fraction(0)):
    schedule = []
    time_now = frac(start)

    def append_block(block_template):
        nonlocal time_now
        if schedule and schedule[-1]['name'] == block_template['name']:
            schedule[-1]['duration'] += block_template['duration']
        else:
            schedule.append({'name': block_template['name'], 'start': time_now,
                             'duration': block_template['duration'], 'color': block_template['color']})
        time_now += block_template['duration']

    for block in flatten(prefix_blocks):
        if time_now >= total_duration: break
        append_block(block)

    cycle_blocks = flatten(cycle_blocks)
    if not cycle_blocks: return schedule

    while time_now < total_duration:
        for block in cycle_blocks:
            if time_now >= total_duration: break
            append_block(block)

    return schedule

def copy_to_clipboard(root, title, prefix_blocks, cycle_blocks, period_text="", shares_text=""):
    lines = [title, "Rules:"]
    if prefix_blocks:
        lines.append("Prefix:")
        lines.extend(rule_lines(prefix_blocks))
    if cycle_blocks:
        lines.append("Cycle:")
        lines.extend(rule_lines(cycle_blocks))
        lines.append("- repeat")
        if period_text: lines.append(period_text)
        if shares_text: lines.append(shares_text)
    else:
        lines.append("(No cycle found - capped by rule limit or bounded timeline)")

    root.clipboard_clear()
    root.clipboard_append("\n".join(lines))

SCROLL_PADDING = 20

def refresh_scrollregion(canvas):
    """Size the scrollable area to whatever is actually drawn, plus a margin."""
    bbox = canvas.bbox("all")
    if not bbox: return
    _x1, _y1, x2, y2 = bbox
    canvas.config(scrollregion=(0, 0, x2 + SCROLL_PADDING, y2 + SCROLL_PADDING))

def draw_schedules(root, canvas, test_cases, window_width=900):
    y_offset = 20
    px_per_min = 4
    margin_left = 90
    margin_right = 30
    row_height = 40
    row_spacing = 20
    row_duration = (window_width - margin_left - margin_right) // px_per_min
    tooltip = ToolTip(canvas)

    test10_state = {}

    for case in test_cases:
        title = case[0]

        # Test 10 dynamic evaluation format
        if len(case) == 4 and callable(case[1]):
            evaluator, total_duration, window_sec = case[1], case[2], case[3]

            test10_state = {
                'title': title,
                'evaluator': evaluator,
                'window': window_sec,
                'total_duration': Fraction(total_duration),
                'y_offset': y_offset,
                'tp': 0.0,
                'playing': False
            }
            # Pre-allocate one row for Test 10 (80min fits inside row_duration 195min)
            y_offset += (0 + 1) * (row_height + row_spacing) + 40
            continue

        # Standard test cases
        tasks, total_duration, pre_placed, periods = case[1], case[2], case[3], case[4]
        options = case[5] if len(case) > 5 else {}
        px = case[6] if len(case) > 6 else px_per_min

        prefix_blocks, cycle_blocks, plan = get_schedule_rules(tasks, pre_placed, periods, **options)
        start_time = plan.start
        if cycle_blocks:
            period_str = f"period {human(plan.period)}"
            shares_str = ", ".join(f"{n} {float(s) * 100:.4g}%" for n, s in sorted(plan.shares.items()))
            summary = f"{period_str}   |   {shares_str}"
        else:
            period_str, shares_str = "", ""
            summary = f"no cycle found (capped at {MAX_RULES} rules)"

        schedule = generate_schedule(prefix_blocks, cycle_blocks, total_duration, start=start_time)

        assert tk is not None
        btn = tk.Button(canvas, text="Copy\nRules", cursor="hand2",
                        command=lambda t=title, p=prefix_blocks, c=cycle_blocks,
                                       ps=period_str, ss=shares_str: copy_to_clipboard(root, t, p, c, ps, ss))
        canvas.create_window(15, y_offset, window=btn, anchor="nw")

        txt_id = canvas.create_text(margin_left, y_offset, text=title, font=("Arial", 11, "bold"), anchor="nw")
        bbox = canvas.bbox(txt_id)
        sub_id = canvas.create_text(margin_left, bbox[3] + 2, text=summary, font=("Arial", 9), fill="#555555", anchor="nw")
        local_y_offset = canvas.bbox(sub_id)[3] + 18

        max_row_idx = 0
        for block in schedule:
            block_start = block['start']
            remaining = block['duration']
            original_start = block['start']
            original_end = original_start + block['duration']
            hover_info = (f"Task {block['name']}\nStart: {stamp(original_start)}\n"
                          f"End: {stamp(original_end)}\nDuration: {human(block['duration'])}")

            while remaining > 0:
                row_idx = int(block_start // row_duration)
                start_in_row = block_start % row_duration
                time_in_row = min(remaining, row_duration - start_in_row)

                x1 = margin_left + float(start_in_row) * px
                x2 = margin_left + float(start_in_row + time_in_row) * px
                y1 = local_y_offset + row_idx * (row_height + row_spacing)
                y2 = y1 + row_height

                rect_id = canvas.create_rectangle(x1, y1, x2, y2, fill=block['color'], outline="black", tags="task_panel")
                tooltip.register(rect_id, hover_info)

                if (x2 - x1) > 30:
                    text_id = canvas.create_text((x1 + x2) / 2, (y1 + y2) / 2, text=block['name'], font=("Arial", 10), tags="task_panel")
                    tooltip.register(text_id, hover_info)

                remaining -= time_in_row
                block_start += time_in_row
                max_row_idx = max(max_row_idx, row_idx)

        for row_idx in range(max_row_idx + 1):
            y1 = local_y_offset + row_idx * (row_height + row_spacing)
            canvas.create_text(margin_left - 8, y1 + row_height / 2, text=stamp(row_idx * row_duration),
                               font=("Arial", 8), fill="#777777", anchor="e")

        y_offset = local_y_offset + (max_row_idx + 1) * (row_height + row_spacing) + 40

    if test10_state:
        # Create static Test 10 UI elements once to avoid memory leaks
        t10_y = test10_state['y_offset']
        assert tk is not None
        btn_t10 = tk.Button(canvas, text="Copy\nRules", cursor="hand2",
                            command=lambda: copy_to_clipboard(
                                root, test10_state['title'],
                                *test10_state['evaluator'](test10_state['tp'])
                            ))
        canvas.create_window(15, t10_y, window=btn_t10, anchor="nw")

        # tp only advances while playing; the test starts paused at tp = 0.
        btn_play = tk.Button(canvas, text="Play", width=6, cursor="hand2")

        def toggle_play():
            test10_state['playing'] = not test10_state['playing']
            btn_play.config(text="Pause" if test10_state['playing'] else "Play")

        btn_play.config(command=toggle_play)
        canvas.create_window(15, t10_y + 48, window=btn_play, anchor="nw")

        txt_id_t10 = canvas.create_text(margin_left, t10_y, text=test10_state['title'], font=("Arial", 11, "bold"), anchor="nw")
        bbox_t10 = canvas.bbox(txt_id_t10)
        sub_id_t10 = canvas.create_text(margin_left, bbox_t10[3] + 2, text="", font=("Arial", 9), fill="#555555", anchor="nw")

        test10_state['draw_y'] = canvas.bbox(sub_id_t10)[3] + 18
        test10_state['sub_id'] = sub_id_t10

        def draw_window_marker(st, max_row_idx):
            """The 20s period itself, drawn on the timeline. It is ~1px wide at this
            zoom, so it gets a minimum width and a label to stay findable."""
            tp_min = st['tp'] / 60.0
            span_min = st['window'] / 60.0
            row_idx = int(tp_min // row_duration)
            if row_idx > max_row_idx: return
            start_in_row = tp_min % row_duration
            x1 = margin_left + start_in_row * px_per_min
            x2 = max(x1 + 3, margin_left + min(start_in_row + span_min, row_duration) * px_per_min)
            y1 = st['draw_y'] + row_idx * (row_height + row_spacing)
            canvas.create_rectangle(x1, y1 - 7, x2, y1 + row_height + 7, outline="#D40000", width=2,
                                    tags=("test10_dyn",))
            canvas.create_text(x1, y1 - 9, text="t_p", fill="#D40000", font=("Arial", 8, "bold"),
                               anchor="sw", tags=("test10_dyn",))

        def animate_test10():
            # Clear old dynamic drawing items
            for item in canvas.find_withtag("test10_dyn"):
                tooltip.unregister(item)
            canvas.delete("test10_dyn")

            st = test10_state
            status = "playing" if st['playing'] else "paused"
            canvas.itemconfig(st['sub_id'],
                              text=f"t_p = {st['tp']:.1f}s ({status})  |  {regime_name(st['tp'])}"
                                   f"  |  algebraic O(1) rules, re-read every frame")

            prefix, cycle = st['evaluator'](st['tp'])
            schedule = generate_schedule(prefix, cycle, st['total_duration'], start=Fraction(0))

            max_row_idx = 0
            for block in schedule:
                block_start = block['start']
                remaining = block['duration']
                orig_s = block['start']
                orig_e = orig_s + remaining
                hover = (f"Task {block['name']}\nStart: {stamp(orig_s)}\n"
                         f"End: {stamp(orig_e)}\nDuration: {human(remaining)}")

                while remaining > 0:
                    row_idx = int(block_start // row_duration)
                    start_in_row = block_start % row_duration
                    time_in_row = min(remaining, row_duration - start_in_row)

                    x1 = margin_left + float(start_in_row) * px_per_min
                    x2 = margin_left + float(start_in_row + time_in_row) * px_per_min
                    y1 = st['draw_y'] + row_idx * (row_height + row_spacing)
                    y2 = y1 + row_height

                    rect_id = canvas.create_rectangle(x1, y1, x2, y2, fill=block['color'], outline="black", tags=("task_panel", "test10_dyn"))
                    tooltip.register(rect_id, hover)

                    if (x2 - x1) > 30:
                        text_id = canvas.create_text((x1 + x2) / 2, (y1 + y2) / 2, text=block['name'], font=("Arial", 10), tags=("task_panel", "test10_dyn"))
                        tooltip.register(text_id, hover)

                    remaining -= time_in_row
                    block_start += time_in_row
                    max_row_idx = max(max_row_idx, row_idx)

            for row_idx in range(max_row_idx + 1):
                y1 = st['draw_y'] + row_idx * (row_height + row_spacing)
                canvas.create_text(margin_left - 8, y1 + row_height / 2, text=stamp(row_idx * row_duration),
                                   font=("Arial", 8), fill="#777777", anchor="e", tags="test10_dyn")

            draw_window_marker(st, max_row_idx)

            if st['playing']:
                st['tp'] += 5.0
                # The whole period, not just its leading edge, has to clear the
                # displayed timeline before the sweep restarts.
                if st['tp'] + st['window'] > float(st['total_duration'] * 60):
                    st['tp'] = 0.0

            # Test 10 redraws itself, so the content height is only known after a
            # frame: keep the scrollable area in step with what is on the canvas.
            refresh_scrollregion(canvas)

            # The blocks the pointer was over were just destroyed and remade, so the
            # bubble must be re-judged against what is under the cursor NOW.
            tooltip.refresh()

            root.after(50, animate_test10)

        animate_test10()

    refresh_scrollregion(canvas)
    return y_offset

AB = lambda: [Task("A", priority=50, min_time=10, color="#FF9999"),
              Task("B", priority=50, min_time=10, color="#99CCFF")]

def build_cases():
    return [
        (
            ("Test 1: Normal 50/50 Split (10min each)\n-> Pure periodic cycle, no prefix."),
            AB(), 180, [], []
        ),
        (
            ("Test 2: Pre-placed event owned by nobody\n-> MAINTENANCE excludes everybody equally, so it creates no field: they simply resume alternating."),
            AB(), 240, [{'name': 'MAINTENANCE', 'start': 40, 'duration': 60, 'color': '#CCCCCC'}], []
        ),
        (
            ("Test 3: Periods constraint\n-> C is banned from t=105 on, forever: it is abundantly present just before the door closes, then A and B share the timeline."),
            [Task("A", priority=40, min_time=10, color="#FF9999"), Task("B", priority=40, min_time=10, color="#99CCFF"), Task("C", priority=20, min_time=10, color="#99FF99")],
            300, [], [{'start': 0, 'end': 105, 'allowed': ['A', 'B', 'C']}, {'start': 105, 'end': inf, 'allowed': ['A', 'B']}]
        ),
        (
            ("Test 4: Three tasks (A: 50% 20m, B: 30% 10m, C: 20% 15m)\n-> Minimums force a 75min period; shares are exact."),
            [Task("A", priority=50, min_time=20, color="#FF9999"), Task("B", priority=30, min_time=10, color="#99CCFF"), Task("C", priority=20, min_time=15, color="#99FF99")],
            400, [], []
        ),
        (
            ("Test 5: Lopsided priorities (A 90% / B 10%) + a B block at the start\n-> A gets a denser, bounded catch-up around it, not the full 396min it is owed."),
            [Task("A", priority=90, min_time=10, color="#FF9999"), Task("B", priority=10, min_time=10, color="#99CCFF")],
            600, [{'name': 'B', 'start': 0, 'duration': 40, 'color': "#99CCFF"}], []
        ),
        (
            ("Test 6: 1h block of A at t=100 (tau = 20min)\n-> B's slots swell as the block approaches and shrink back after it: exponential decay of the influence, both sides."),
            AB(), 400, [{'name': 'A', 'start': 100, 'duration': 60, 'color': "#FF9999"}], []
        ),
        (
            ("Test 7: 10h block of A at t=100 - 10x longer than test 6\n-> B's presence around it is wider and denser, but only a few times bigger: log, not proportional."),
            AB(), 1000, [{'name': 'A', 'start': 100, 'duration': 600, 'color': "#FF9999"}], [], {}, 2
        ),
        (
            ("Test 8: B banned from t=100 to t=400 - a window, not a block\n-> same field, same ramps: B swells before the ban and right after it re-opens, then decays back to the cycle."),
            AB(), 700, [], [{'start': 0, 'end': 100, 'allowed': ['A', 'B']}, {'start': 100, 'end': 400, 'allowed': ['A']}, {'start': 400, 'end': inf, 'allowed': ['A', 'B']}], {}, 2
        ),
        (
            ("Test 9: same 300min ban, but split into ten consecutive windows\n-> merged into one exclusion: ten short bans in a row are one long ban, not ten small ones."),
            AB(), 700, [], [{'start': 0, 'end': 100, 'allowed': ['A', 'B']}] + [{'start': 100 + 30 * i, 'end': 130 + 30 * i, 'allowed': ['A']} for i in range(10)] + [{'start': 400, 'end': inf, 'allowed': ['A', 'B']}], {}, 2
        )
    ]

# =========================================================================== #
#  Test 10: algebraic O(1) rules parameterized by the moving period start t_p
# =========================================================================== #
#
# The window W = [t_p, t_p + WINDOW) admits only A, and t_p sweeps forward.
# The freezing requirement is a consistency condition BETWEEN plans:
#
#     for every t_p < t_p', the two rule sets must describe the same
#     timeline on [0, t_p).
#
# Everything below is derived from that. Three regimes, and only three:
#
#   (1) t_p + WINDOW <= T_A            the window sits inside A's block.
#                                      Nothing to do: the plain cycle already
#                                      satisfies it.
#
#   (2) T_A - WINDOW < t_p <= T_A      the window straddles the A->B boundary,
#                                      so B may not begin at T_A. A is allowed
#                                      inside the window, so A absorbs the
#                                      overhang rather than the timeline idling.
#                                      Overrun e = t_p + WINDOW - T_A, at most one
#                                      window wide, repaid to B over the following
#                                      B blocks with exponential decay.
#
#   (3) t_p > T_A                      the window lands inside B's block. B is
#                                      already running (it started at T_A in every
#                                      plan with a larger t_p), so B pauses for the
#                                      window and resumes. B still receives its full
#                                      minimum; the 20s of idle is unavoidable,
#                                      because A cannot claim it without breaking
#                                      B's atomic block.
#
# The deleted fourth regime is the bug: the previous version pinned A to T_A+WINDOW
# and grew an idle for t_p in (T_A, T_A+WINDOW], which put A at t=605s when t_p=610s
# while regime (3) put B there for t_p=621s. Both times are below both t_p values,
# so the frozen past changed. There is no room for a regime between (2) and (3):
# a plan whose window starts after T_A cannot forbid B at T_A, so B starts there.

DEBT_EPS = 0.5  # rounding epsilon, seconds: below this the decay tail is folded in

def repayment_terms(excess, ratio, eps=DEBT_EPS):
    """Split `excess` into the decaying extras handed to successive B blocks.

    Term k is excess*(1-ratio)*ratio^k, i.e. exponential decay with distance from
    the disturbance, exactly like the field the other nine tests use. The tail is
    cut once it drops under the epsilon and folded into the last term, so the
    terms sum to `excess` EXACTLY: the decay shapes the repayment, it never
    cancels part of the debt. The term count is bounded by log(excess/eps)/|ln
    ratio|, a constant, so the rule list stays O(1).
    """
    terms = []
    rest = float(excess)
    while rest > eps:
        pay = rest * (1.0 - ratio)
        terms.append(pay)
        rest -= pay
    if terms:
        terms[-1] += rest
    elif rest > 0:
        terms = [rest]
    return terms

def make_test10_evaluator(tasks, window_sec=20.0):
    """Build the O(1) rule evaluator for a two-task timeline swept by an A-only
    window. Every constant is derived from the same Scheduler that runs tests 1-9,
    so the two halves of this file cannot drift apart."""
    sched = Scheduler(tasks)
    a, b = tasks[0].name, tasks[1].name
    color = {a: tasks[0].color, b: tasks[1].color, "IDLE": IDLE_COLOR}

    period = float(sched.min_period) * 60.0          # T   = 1200 s
    block = {n: float(sched.p[n]) * period for n in (a, b)}   # 600 s each
    tau = float(sched.tau) * 60.0                    # same tau as the field
    ratio = exp(-period / tau)                       # debt carried to the next B block
    stretch_from = block[a] - window_sec             # T_A - WINDOW = t_1 - WINDOW

    # t_1: the last t_p for which A can still cover the whole window by extending
    # its own block. Past it the window no longer touches A's block at all, so
    # there is nothing for A to absorb. It is computed, not chosen, and it sits
    # above 9min40 exactly as required.
    t_1 = block[a]

    def blk(name, seconds):
        return {'name': name, 'duration': seconds, 'color': color[name]}

    def evaluate(tp_sec, to_minutes=True):
        tp = max(0.0, float(tp_sec))
        n_cycles = int(tp // period)
        t = tp - n_cycles * period

        prefix = []
        # The frozen past is n untouched cycles: one segment, not n of them.
        if n_cycles:
            prefix.append({'repeat': n_cycles,
                           'blocks': [blk(a, block[a]), blk(b, block[b])]})

        if t <= stretch_from:
            pass                                        # regime (1)
        elif t <= t_1:                                  # regime (2)
            excess = t + window_sec - block[a]
            if excess > 0:
                prefix.append(blk(a, block[a] + excess))
                terms = repayment_terms(excess, ratio)
                for i, extra in enumerate(terms):
                    prefix.append(blk(b, block[b] + extra))
                    if i != len(terms) - 1:
                        prefix.append(blk(a, block[a]))
        else:                                           # regime (3)
            served = t - block[a]
            prefix.append(blk(a, block[a]))
            prefix.append(blk(b, served))
            prefix.append(blk("IDLE", window_sec))
            rest = block[b] - served
            if rest > 0:
                prefix.append(blk(b, rest))

        cycle = [blk(a, block[a]), blk(b, block[b])]

        prefix = [seg for seg in prefix if 'blocks' in seg or seg['duration'] > 0]
        # Never leave the prefix ending on the task the cycle opens with, or the
        # two would be drawn as one over-long block.
        if prefix and 'blocks' not in prefix[-1] and prefix[-1]['name'] == cycle[0]['name']:
            cycle = cycle[1:] + cycle[:1]

        if to_minutes:
            conv = lambda blocks: [dict(x, duration=frac(x['duration'] / 60.0)) for x in blocks]
            prefix = [{'repeat': s['repeat'], 'blocks': conv(s['blocks'])} if 'blocks' in s
                      else conv([s])[0] for s in prefix]
            cycle = conv(cycle)
        return prefix, cycle

    evaluate.period = period
    evaluate.block = block
    evaluate.window = window_sec
    evaluate.t_1 = t_1
    evaluate.stretch_from = stretch_from
    evaluate.ratio = ratio
    return evaluate

TEST10_WINDOW = 20.0
TEST10_TOTAL_MIN = 80
_T10 = make_test10_evaluator(AB(), TEST10_WINDOW)

def regime_name(tp):
    t = float(tp) % _T10.period
    if t <= _T10.stretch_from: return "window inside A -> untouched cycle"
    if t <= _T10.t_1: return "window on the A/B seam -> A absorbs it, B repaid"
    return "window inside B -> B pauses and resumes"

def get_test_10():
    title = (
        "Test 10 (Algebraic O(1)): the 20s A-only period starts at t_p and sweeps right\n"
        "-> rules are a closed form in t_p; every plan agrees with every earlier plan on t < t_p."
    )
    return (title, _T10, TEST10_TOTAL_MIN, TEST10_WINDOW)

# =========================================================================== #
#  Verification of the Test 10 invariants
# =========================================================================== #

def timeline_of(prefix, cycle, horizon):
    """[(start, end, name)] covering [0, horizon), in seconds."""
    out, t = [], 0.0
    for blk in flatten(prefix):
        d = float(blk['duration'])
        if d <= 0: continue
        out.append((t, t + d, blk['name']))
        t += d
        if t >= horizon: return out
    cycle = flatten(cycle)
    if not cycle: return out
    while t < horizon:
        for blk in cycle:
            d = float(blk['duration'])
            out.append((t, t + d, blk['name']))
            t += d
            if t >= horizon: break
    return out

def occupant(tl, t):
    for s, e, n in tl:
        if s <= t < e: return n
    return None

def first_disagreement(t1, t2, cut, tol=1e-6):
    pts = sorted({p for s, e, _ in t1 + t2 for p in (s, e) if -tol <= p <= cut} | {0.0, cut})
    for lo, hi in itertools.pairwise(pts):
        if hi - lo <= tol: continue
        mid = (lo + hi) / 2.0
        if occupant(t1, mid) != occupant(t2, mid): return mid
    return None

def verify_test10(evaluate=_T10, horizon_sec=TEST10_TOTAL_MIN * 60, verbose=True):
    T, W = evaluate.period, evaluate.window
    evaluate.block['A']
    failures = []

    # A grid dense enough to catch the regime seams, plus the seams themselves
    # approached from both sides.
    grid = {round(x * 0.5, 6) for x in range(int(horizon_sec * 2) + 1)}
    for k in range(int(horizon_sec // T) + 2):
        for off in (evaluate.stretch_from, evaluate.t_1, T):
            for d in (-1e-3, -1e-6, 0.0, 1e-6, 1e-3, 0.25, 1.0):
                v = k * T + off + d
                if 0 <= v <= horizon_sec: grid.add(round(v, 6))
    grid = sorted(grid)

    # 1. Frozen past. Consecutive pairs suffice: agreement on [0, t_prev) chains
    #    backwards, so if every neighbouring pair agrees, every pair does.
    prev_tp, prev_tl = None, None
    for tp in grid:
        tl = timeline_of(*evaluate(tp, to_minutes=False), horizon=horizon_sec + 2 * T)
        if prev_tl is not None:
            bad = first_disagreement(prev_tl, tl, prev_tp)
            if bad is not None:
                failures.append(f"frozen past broken at t={bad:.6f}s "
                                f"between t_p={prev_tp}s and t_p={tp}s "
                                f"({occupant(prev_tl, bad)} -> {occupant(tl, bad)})")
        prev_tp, prev_tl = tp, tl

    # 2. Minimum times, and B's fragments summing to its minimum across the idle.
    for tp in grid[::7]:
        tl = timeline_of(*evaluate(tp, to_minutes=False), horizon=horizon_sec + 2 * T)
        runs = []                      # [name, served, end] with idle pauses folded in
        for s, e, n in tl:
            if runs and runs[-1][0] == n:
                runs[-1][1] += e - s
            elif (n != "IDLE" and len(runs) >= 2
                  and runs[-1][0] == "IDLE" and runs[-2][0] == n):
                runs.pop()             # an idle pause does not end a service
                runs[-1][1] += e - s
            else:
                runs.append([n, e - s, e])
                continue
            runs[-1][2] = e
        for name, served, end in runs:
            if name == "IDLE" or end > horizon_sec:
                continue               # never judge a run the horizon cut short
            if served < float(evaluate.block[name]) - 1e-6:
                failures.append(f"t_p={tp}s: {name} served {served:.3f}s < minimum "
                                f"{evaluate.block[name]:.0f}s")
                break

    # 3. Shares. Over a long horizon the served time must converge to 50/50 and the
    #    idle must stay bounded by one window per sweep position.
    for tp in grid[::37]:
        tl = timeline_of(*evaluate(tp, to_minutes=False), horizon=20 * T)
        served = {}
        for s, e, n in tl:
            served[n] = served.get(n, 0.0) + min(e, 20 * T) - s
        idle = served.pop("IDLE", 0.0)
        total = sum(served.values())
        for n, d in served.items():
            if abs(d / total - 0.5) > 2e-3:
                failures.append(f"t_p={tp}s: {n} share {d / total:.5f} off 50%")
                break
        if idle > W + 1e-6:
            failures.append(f"t_p={tp}s: idle {idle:.3f}s exceeds one window")

    # 4. Rule count stays finite and small for every t_p.
    worst = 0
    for tp in grid:
        prefix, cycle = evaluate(tp)
        worst = max(worst, len(prefix) + len(cycle))
    if worst > MAX_RULES:
        failures.append(f"rule list reaches {worst} entries, over the {MAX_RULES} cap")

    if verbose:
        print("--- Test 10 invariants ---")
        print(f"  grid: {len(grid)} values of t_p over [0, {horizon_sec}]s")
        print(f"  t_1 = {evaluate.t_1:.0f}s, stretch begins at {evaluate.stretch_from:.0f}s, "
              f"decay ratio per period = {evaluate.ratio:.4f}")
        print(f"  largest rule list: {worst} entries (cap {MAX_RULES})")
        if failures:
            print(f"  FAIL ({len(failures)}):")
            for f in failures[:10]: print(f"    - {f}")
        else:
            print("  PASS: frozen past, minimum times, exact shares, bounded idle, O(1) rules")
        print()
    return failures

# =========================================================================== #

def print_terminal_results(cases, test10_case):
    for case in cases:
        title = case[0]
        tasks, _total_duration, pre_placed, periods = case[1], case[2], case[3], case[4]
        options = case[5] if len(case) > 5 else {}
        prefix, cycle, plan = get_schedule_rules(tasks, pre_placed, periods, **options)

        print(f"--- {title.splitlines()[0]} ---")
        if prefix:
            print("[ PREFIX ]")
            for line in rule_lines(prefix, "  "): print(line)
        if cycle:
            print("[ CYCLE ]")
            for line in rule_lines(cycle, "  "): print(line)
            print(f"  (Period: {human(plan.period)})\n")
        else:
            print("[ NO CYCLE FOUND ]\n")

    t10_title, t10_evaluator = test10_case[0], test10_case[1]
    print(f"--- {t10_title.splitlines()[0]} ---")
    for sample_tp in [300.0, 590.0, 600.0, 610.0, 800.0, 1400.0]:
        print(f"[ rules at t_p = {sample_tp}s : {regime_name(sample_tp)} ]")
        prefix, cycle = t10_evaluator(sample_tp)
        if prefix:
            print("  Prefix:")
            for line in rule_lines(prefix, "    "): print(line)
        print("  Cycle:")
        for line in rule_lines(cycle, "    "): print(line)
        print()

def main():
    verify_only = "--verify" in sys.argv
    no_ui = verify_only or "--no-ui" in sys.argv

    cases = build_cases()
    test10 = get_test_10()

    if not verify_only:
        print_terminal_results(cases, test10)
    verify_test10()

    if no_ui:
        return
    if tk is None:
        print("tkinter is not available: skipping the UI (rules and checks above are unaffected).")
        return

    root = tk.Tk()
    root.title("Task Scheduler Timeline with Constraints (Including Real-Time Algebraic O(1))")
    root.geometry("950x700")

    canvas = tk.Canvas(root, bg="white")
    vbar = tk.Scrollbar(root, orient=tk.VERTICAL, command=canvas.yview)
    canvas.configure(yscrollcommand=vbar.set)

    vbar.pack(side=tk.RIGHT, fill=tk.Y)
    canvas.pack(side=tk.LEFT, fill=tk.BOTH, expand=True)

    def _on_mousewheel(event):
        if getattr(event, 'num', 0) == 4 or event.delta > 0:
            canvas.yview_scroll(-3, "units")
        elif getattr(event, 'num', 0) == 5 or event.delta < 0:
            canvas.yview_scroll(3, "units")

    canvas.bind_all("<MouseWheel>", _on_mousewheel)
    canvas.bind_all("<Button-4>", _on_mousewheel)
    canvas.bind_all("<Button-5>", _on_mousewheel)
    canvas.bind_all("<Prior>", lambda e: canvas.yview_scroll(-1, "pages"))
    canvas.bind_all("<Next>", lambda e: canvas.yview_scroll(1, "pages"))
    canvas.bind_all("<Home>", lambda e: canvas.yview_moveto(0.0))
    canvas.bind_all("<End>", lambda e: canvas.yview_moveto(1.0))

    all_cases = cases + [test10]
    draw_schedules(root, canvas, all_cases, window_width=900)

    refresh_scrollregion(canvas)
    root.mainloop()

if __name__ == "__main__":
    main()