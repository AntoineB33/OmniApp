#!/usr/bin/env python3
"""
Combined Scheduler:
- Runs the 9 tests from test.py using the exact fractional math scheduler.
- Runs Test 10 using purely algebraic, O(1) rules parameterized by a moving t_p, updating real-time.
- Displays results in the terminal and in the Tkinter window.
"""

import math
import tkinter as tk
from dataclasses import dataclass, field
from fractions import Fraction
from math import exp, inf, log

# =========================================================================== #
#  test.py Logic (For the 9 tests + UI)
# =========================================================================== #

MAX_RULES = 50
IDLE_COLOR = "#F0F0F0"
FOREVER = None
DECAY_RATE = 0.01

def frac(x):
    if isinstance(x, Fraction): return x
    if isinstance(x, float): return Fraction(x).limit_denominator(10 ** 9)
    return Fraction(x)

def ceil_to(x, step):
    return Fraction(-((-x) // step)) * step

def human(d, unit_seconds=60):
    total = frac(d) * unit_seconds
    if total.denominator != 1: return f"{float(d):.4g}min"
    s = int(total)
    sign, s = ("-", -s) if s < 0 else ("", s)
    h, r = divmod(s, 3600)
    m, sec = divmod(r, 60)
    out = []
    if h: out.append(f"{h}h")
    if m: out.append(f"{m}min")
    if sec or not out: out.append(f"{sec}s")
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

# --- UI & Harness ---

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
        else:
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

    for block in prefix_blocks:
        if time_now >= total_duration: break
        append_block(block)

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
        for block in prefix_blocks:
            lines.append(f"- task {block['name']} {human(block['duration'])}")
    if cycle_blocks:
        lines.append("Cycle:")
        for block in cycle_blocks:
            lines.append(f"- task {block['name']} {human(block['duration'])}")
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
    x1, y1, x2, y2 = bbox
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
        if len(case) == 3 and callable(case[1]):
            evaluator, total_duration = case[1], case[2]
            
            test10_state = {
                'title': title,
                'evaluator': evaluator,
                'total_duration': Fraction(total_duration),
                'y_offset': y_offset,
                'tp': 0.0,
                'playing': False
            }
            # Pre-allocate one row for Test 10 (since 80min fits inside row_duration 195min)
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

        def animate_test10():
            # Clear old dynamic drawing items
            for item in canvas.find_withtag("test10_dyn"):
                tooltip.unregister(item)
            canvas.delete("test10_dyn")

            st = test10_state
            status = "playing" if st['playing'] else "paused"
            canvas.itemconfig(st['sub_id'], text=f"tp = {st['tp']:.1f}s ({status})  |  Real-time Algebraic O(1) rules updated dynamically")

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

            if st['playing']:
                st['tp'] += 20.0
                if st['tp'] > float(st['total_duration'] * 60):
                    st['tp'] = 0.0

            # Test 10 redraws itself, so the content height is only known after a
            # frame: keep the scrollable area in step with what is on the canvas.
            refresh_scrollregion(canvas)

            # The blocks the pointer was over were just destroyed and remade, so the
            # bubble must be re-judged against what is under the cursor NOW.
            tooltip.refresh()

            root.after(100, animate_test10)

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

def get_test_10():
    title = (
        "Test 10 (Algebraic O(1)): tp parameter continuously evaluates across the timeline\n"
        "-> Updates real-time according to algebraic rules (satisfies all edge cases & decay compensations)."
    )
    
    def test10_rules_evaluator(tp_sec):
        # Time variables
        t1 = 600.0   # A's 10m minimum time boundary
        t2 = 1200.0  # B's 10m minimum time boundary (1 full cycle)
        t_mod = tp_sec % t2
        n_cycles = int(tp_sec // t2)
        
        prefix = []
        
        # Frozen Past: previous cycles 
        for _ in range(n_cycles):
            prefix.append({'name': 'A', 'duration': 600.0, 'color': '#FF9999'})
            prefix.append({'name': 'B', 'duration': 600.0, 'color': '#99CCFF'})
            
        # O(1) Algebraic Evaluation
        if t_mod < 580.0:
            # tp is fully inside A's block: No interruption needed, normal cycle continues
            pass 
            
        elif 580.0 <= t_mod <= t1:
            # tp overlaps A's end: A must stretch to cover it. B repays the debt.
            a_dur = t_mod + 20.0
            debt = (a_dur - 600.0) * math.exp(-DECAY_RATE * (600.0 / 60.0))
            prefix.extend([
                {'name': 'A', 'duration': a_dur, 'color': '#FF9999'},
                {'name': 'B', 'duration': 600.0 + debt, 'color': '#99CCFF'}
            ])
            
        elif t1 < t_mod <= t1 + 20.0:
            # tp overlaps the exact boundary: A stretches, IDLEs, B gets debt applied
            a_dur = t1 + 20.0
            idle_dur = t_mod - t1
            debt = (a_dur - 600.0) * math.exp(-DECAY_RATE * (600.0 / 60.0))
            prefix.append({'name': 'A', 'duration': a_dur, 'color': '#FF9999'})
            if idle_dur > 0:
                prefix.append({'name': 'IDLE', 'duration': idle_dur, 'color': IDLE_COLOR})
            prefix.append({'name': 'B', 'duration': 600.0 + debt, 'color': '#99CCFF'})
            
        else: # t1 + 20.0 < t_mod <= t2
            # tp falls deep into B's block: B breaks, IDLEs exactly for 20s window, B finishes
            b1_dur = t_mod - t1
            idle_dur = 20.0
            b2_dur = 600.0 - b1_dur
            prefix.extend([
                {'name': 'A', 'duration': t1, 'color': '#FF9999'},
                {'name': 'B', 'duration': b1_dur, 'color': '#99CCFF'},
                {'name': 'IDLE', 'duration': idle_dur, 'color': IDLE_COLOR}
            ])
            if b2_dur > 0:
                prefix.append({'name': 'B', 'duration': b2_dur, 'color': '#99CCFF'})

        # The subsequent endless cycle
        cycle = [
            {'name': 'A', 'duration': 600.0, 'color': '#FF9999'},
            {'name': 'B', 'duration': 600.0, 'color': '#99CCFF'}
        ]
        
        # Merge identical consecutive blocks
        merged_prefix = []
        for b in prefix:
            if b['duration'] <= 0: continue
            if merged_prefix and merged_prefix[-1]['name'] == b['name']:
                merged_prefix[-1]['duration'] += b['duration']
            else:
                merged_prefix.append(b.copy())
        
        # Convert seconds to fractions of minutes for the UI renderer
        for b in merged_prefix:
            b['duration'] = frac(b['duration'] / 60.0)
        for b in cycle:
            b['duration'] = frac(b['duration'] / 60.0)
            
        return merged_prefix, cycle
        
    return (title, test10_rules_evaluator, 80)

def print_terminal_results(cases, test10_case):
    for case in cases:
        title = case[0]
        tasks, _total_duration, pre_placed, periods = case[1], case[2], case[3], case[4]
        options = case[5] if len(case) > 5 else {}
        prefix, cycle, plan = get_schedule_rules(tasks, pre_placed, periods, **options)
        
        print(f"--- {title.splitlines()[0]} ---")
        if prefix:
            print("[ PREFIX ]")
            for b in prefix: print(f"  Task {b['name']}: {human(b['duration'])}")
        if cycle:
            print("[ CYCLE ]")
            for b in cycle: print(f"  Task {b['name']}: {human(b['duration'])}")
            print(f"  (Period: {human(plan.period)})\n")
        else:
            print("[ NO CYCLE FOUND ]\n")
            
    # Print sample rule states for Algebraic Test 10
    t10_title, t10_evaluator, _ = test10_case
    print(f"--- {t10_title.splitlines()[0]} ---")
    for sample_tp in [300.0, 590.0, 610.0, 800.0]:
        print(f"[ Snapshot of Algebraic Rules for tp = {sample_tp}s ]")
        prefix, cycle = t10_evaluator(sample_tp)
        if prefix:
            print("  Prefix:")
            for b in prefix: print(f"    Task {b['name']}: {human(b['duration'])}")
        print("  Cycle:")
        for b in cycle: print(f"    Task {b['name']}: {human(b['duration'])}")
        print("")


def main():
    cases = build_cases()
    test10 = get_test_10()
    
    # Render to terminal
    print_terminal_results(cases, test10)
    
    # Render to UI
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