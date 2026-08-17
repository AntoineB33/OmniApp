#!/usr/bin/env python3
"""
tests_displayer.py
GUI tools, CLI runner, and visual logic for displaying scheduler tests.

The moving period of tests 10 and 11 is moved *here*: the displayer advances
t_p every frame and reads the timeline off the rule list the scheduler derived
once. No scheduling happens while the period slides.

Under each test's rules and above its timeline is what the case IS: every task
with its minimum, its priority and the share those two actually BUY, and every
static period with the set of tasks it turns away. The first two are the case's
own statement and never move; the resulting share is a property of the schedule,
so on a sliding case it is read off the rule list at t_p and moves with it --
substituted into, like everything else here, never rescheduled.

A bar at the top of the window owns tau -- the constant the compensation field
decays over, on both sides of a blockage -- as a multiple of each case's own
default. Changing it rebuilds every rule list, since they are a function of it.

Test 12's rule list is too long to derive before anything can be shown, so its
panel settles one link of the chain between frames and draws whatever is known
now: definitive up to the front, provisional past it. Scrolling to it straight
away shows the far end of the schedule still changing while the definitive part
grows from t=0.
"""

import sys
import time
from fractions import Fraction

try:
    import tkinter as tk
except ImportError:
    tk = None

from rules_snapshot import check_rules_snapshot, write_rules_snapshot
from scheduler_logic import (
    IDLE,
    IDLE_COLOR,
    MAX_RULES,
    frac,
    human,
    rule_lines,
    stamp,
)
from test_configs import (
    build_cases,
    build_moving_cases,
    build_progressive_cases,
    case_parts,
    get_schedule_rules,
    timeline_of,
    verify_moving,
    verify_progressive,
)


class ToolTip:
    def __init__(self, canvas):
        self.canvas = canvas
        self.tooltip_window = None
        self.label = None
        self.data = {}
        self.canvas.bind("<Motion>", self._on_motion, add="+")
        self.canvas.bind("<Leave>", self._on_leave, add="+")
        self.canvas.bind("<Button>", self._on_leave, add="+")
        for seq in ("<MouseWheel>", "<Button-4>", "<Button-5>"):
            self.canvas.bind_all(seq, self._on_wheel, add="+")

    def _on_wheel(self, _event):
        # bound at the application level, so a rebuild leaves this binding
        # pointing at a canvas that is gone: ask before touching it
        if self.canvas.winfo_exists(): self.canvas.after_idle(self.refresh)

    def register(self, item_id, text):
        self.data[item_id] = text

    def unregister(self, item_id):
        self.data.pop(item_id, None)

    def refresh(self):
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

def copy_dynamic_rules(root, title, mw):
    root.clipboard_clear()
    root.clipboard_append("\n".join([title, ""] + mw.lines()))

SCROLL_PADDING = 20

def refresh_scrollregion(canvas):
    bbox = canvas.bbox("all")
    if not bbox: return
    _x1, _y1, x2, y2 = bbox
    canvas.config(scrollregion=(0, 0, x2 + SCROLL_PADDING, y2 + SCROLL_PADDING))

TASK_COLORS = {}

def block_color(name, fallback="#DDDDDD"):
    return TASK_COLORS.get(name, fallback)


# --------------------------------------------------------------------------- #
#  what a case IS, under its timeline: the tasks it declares (minimum, priority,
#  and the share those two actually BUY) and the static periods (each with the
#  set of tasks it turns away)
#
#  The minimum, the priority and the periods are the case's own statement and
#  never move. The resulting share is a property of the schedule, so on a
#  sliding case it is recomputed at every t_p -- from the rule list evaluated
#  there, not from a fresh scheduling -- and it moves as the period slides.
#
#  Two of them are shown, because they answer two different questions: the
#  CYCLE share is the steady state the rules settle into (the plan's own
#  `shares`), and the TIMELINE share is what the drawn span really gives, prefix
#  and frozen past and idle time included. They differ exactly where the
#  disturbance is -- which is the interesting part.
# --------------------------------------------------------------------------- #

INFO_FONT = ("Courier", 8)
INFO_CHAR_W = 7                  # what ("Courier", 8) measures to, so the block
INFO_LINE_H = 14                 # can be laid out without asking the toolkit
TASK_CELL_W = 37                 # characters of one task's row
TASK_COL_W = INFO_CHAR_W * TASK_CELL_W
TASK_COL_ROWS = 6
PERIOD_LINES = 6
PERIOD_SPANS = 5

def _pct(x):
    return f"{float(x) * 100:.4g}%"

def _natural(name):
    """A name split into its text head and its trailing number, so N2 sorts
    before N10 and a run of them can be recognised."""
    head = name.rstrip("0123456789")
    tail = name[len(head):]
    return head, int(tail) if tail else -1

def compact_names(names):
    """"A, N1, N2, ... N10" -> "A, N1-N10".

    Test 12 refuses twenty-one tasks by name, fifty-five times over. A run is a
    thing to read; the same twenty names spelled out is a thing to scroll past.
    """
    out, run = [], []

    def flush():
        if not run: return
        out.append(f"{run[0]}-{run[-1]}" if len(run) >= 3 else ", ".join(run))
        run.clear()

    for n in sorted(names, key=_natural):
        if run:
            (ph, pn), (h, num) = _natural(run[-1]), _natural(n)
            if h == ph and pn >= 0 and num == pn + 1:
                run.append(n)
                continue
            flush()
        run.append(n)
    flush()
    return ", ".join(out) or "(nobody)"

def _duration_of(item):
    return item['duration'] if isinstance(item, dict) else item.duration

def _name_of(item):
    return item['name'] if isinstance(item, dict) else item.task

def cycle_shares(blocks):
    """The share of the cycle each task holds -- blocks or Slots alike."""
    total = sum((_duration_of(b) for b in blocks), frac(0))
    if not total: return {}
    out = {}
    for b in blocks:
        out[_name_of(b)] = out.get(_name_of(b), frac(0)) + _duration_of(b)
    return {n: d / total for n, d in out.items()}

def span_shares(spans):
    """The share of the drawn timeline each task holds, from (start, end, name)."""
    out, total = {}, frac(0)
    for start, end, name in spans:
        if end <= start: continue
        out[name] = out.get(name, frac(0)) + (end - start)
        total += end - start
    return {n: d / total for n, d in out.items()} if total else {}

def _edge(t):
    return "forever" if t is None or t == float('inf') else stamp(t)

def _clip(text, chars):
    return text if chars is None or len(text) <= chars else text[:chars - 3] + "..."

def period_lines(periods, max_lines=PERIOD_LINES, max_spans=PERIOD_SPANS, chars=None):
    """The static periods, grouped by the set of tasks they refuse.

    Ten consecutive bans of the same set is one sentence said ten times (test
    9), and test 12 says its two sets fifty-five times, so the group is the
    line and the spans are where it applies. The full list is on the tooltip:
    nothing is hidden, only folded."""
    groups = {}
    for w in periods:
        groups.setdefault(frozenset(w.get('forbidden') or ()), []).append(w)
    ordered = sorted(groups.items(), key=lambda kv: min(frac(w['start']) for w in kv[1]))

    out = []
    for who, ws in ordered[:max_lines]:
        ws = sorted(ws, key=lambda w: frac(w['start']))
        spans = [f"[{_edge(w['start'])}, {_edge(w.get('end'))})" for w in ws]
        text = (f"refuses {compact_names(who)}  x{len(ws)}:  "
                + ", ".join(spans[:max_spans]) + (" ..." if len(spans) > max_spans else ""))
        tip = "\n".join([f"refuses {compact_names(who)}  ({len(ws)} period(s))"]
                        + [f"  {s}  {w.get('label', '')}".rstrip()
                           for s, w in list(zip(spans, ws))[:40]]
                        + ([f"  ... {len(ws) - 40} more"] if len(ws) > 40 else []))
        out.append((_clip(text, chars), tip))
    if len(ordered) > max_lines:
        out.append((f"... {len(ordered) - max_lines} more sets of refused tasks", None))
    return out or [("(none: every task may run everywhere)", None)]

def info_layout(tasks, periods, width=780):
    """The fixed shape of the block: how many task rows per column, the period
    lines, and the height the two of them need."""
    cols_max = max(1, int(width // TASK_COL_W))
    rows = min(TASK_COL_ROWS, len(tasks)) or 1
    cols = -(-len(tasks) // rows)
    if cols > cols_max:
        rows = -(-len(tasks) // cols_max)
    # the canvas scrolls vertically only, so a line wider than the block is a
    # line the window cannot show: it is cut here and kept whole on the tooltip
    plines = period_lines(periods, chars=max(20, int(width // INFO_CHAR_W)))
    return rows, plines, INFO_LINE_H * (3 + rows + len(plines))

def draw_info(canvas, x, y, tasks, periods, cyc, tl, rows, plines, tag=None, tooltip=None):
    """Draw the block and return its height. `cyc` and `tl` are the resulting
    shares of the cycle and of the drawn timeline."""
    tags = (tag,) if tag else ()

    def text(ty, s, fill, tx=None):
        return canvas.create_text(x if tx is None else tx, ty, anchor="nw",
                                  font=INFO_FONT, fill=fill, tags=tags, text=s)

    text(y, f"tasks ({len(tasks)}):  name, minimum, priority  ->  resulting share "
            f"of the cycle (and of the whole drawn timeline)", "#333333")
    total = sum((t.priority for t in tasks), frac(0)) or frac(1)
    for i, t in enumerate(tasks):
        col, row = divmod(i, rows)
        cell = (f"{t.name:<4}{human(t.min_time):>7}{_pct(t.priority / total):>7}"
                f" ->{_pct(cyc.get(t.name, 0)):>7}{'(' + _pct(tl.get(t.name, 0)) + ')':>9}")
        text(y + INFO_LINE_H * (1 + row), cell, "#000000", tx=x + col * TASK_COL_W)

    # the drawn timeline is not made of tasks alone -- a pre-placed block owned
    # by nobody and the idling a minimum could not fit into hold their share of
    # it too, and a table of tasks that did not sum to the timeline would look
    # like an error rather than like the answer
    rest = {n: s for n, s in tl.items() if n not in {t.name for t in tasks}}
    if rest:
        text(y + INFO_LINE_H * (1 + rows),
             "not a task, of the drawn timeline:  "
             + ", ".join(f"{n} {_pct(s)}" for n, s in sorted(rest.items())), "#777777")

    y2 = y + INFO_LINE_H * (2 + rows)
    text(y2, f"static periods ({len(periods)}), by the set of tasks they do NOT allow:", "#333333")
    for i, (line, tip) in enumerate(plines):
        item = text(y2 + INFO_LINE_H * (1 + i), line, "#7030A0")
        if tip and tooltip is not None: tooltip.register(item, tip)
    return INFO_LINE_H * (3 + rows + len(plines))


def draw_schedules(root, canvas, test_cases, window_width=900):
    y_offset = 20
    px_per_min = 4
    margin_left = 90
    margin_right = 30
    row_height = 40
    row_spacing = 20
    row_duration = (window_width - margin_left - margin_right) // px_per_min
    tooltip = ToolTip(canvas)

    for case in test_cases:
        title, tasks, total_duration, pre_placed, periods, options = case_parts(case)
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

        # what the case declares, and what the schedule made of it. Nothing here
        # moves on a static case -- it is one plan -- so it is drawn once.
        rows, plines, _h = info_layout(tasks, periods,
                                       width=window_width - margin_left - margin_right)
        info_y = canvas.bbox(sub_id)[3] + 6
        drawn = [(b['start'], b['start'] + b['duration'], b['name']) for b in schedule]
        height = draw_info(canvas, margin_left, info_y, tasks, periods,
                           plan.shares, span_shares(drawn), rows, plines, tooltip=tooltip)
        local_y_offset = info_y + height + 14

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

    refresh_scrollregion(canvas)
    return y_offset


class MovingCasePanel:
    """A timeline whose one period slides, redrawn from the dynamic rules.

    Every frame advances t_p and evaluates the rule list at the new position:
    a binary search over the regimes and some arithmetic. The scheduler is not
    run again -- it ran once, when the rule list was built.

    The user drives t_p directly too: a click on the timeline puts it under the
    pointer (pausing the sweep, since a position the user chose is not one the
    sweep may take back) and a drag keeps it there. Scrubbing is the same
    substitution the sweep does, so it costs the same nothing."""

    ROW_H = 40
    ROW_SPACING = 20
    FPS = 20
    MARGIN_LEFT = 90
    MARGIN_RIGHT = 30

    def __init__(self, root, canvas, tooltip, y, title, mw, sweep_seconds, width=900):
        self.root, self.canvas, self.tooltip, self.mw = root, canvas, tooltip, mw
        self.title = title
        self.tag = f"moving{id(self)}"
        self.playing = False
        self.scrubbing = False
        self.stopped = False
        self._setup(width, sweep_seconds)

        assert tk is not None
        self.btn_copy = tk.Button(canvas, text="Copy\nRules", cursor="hand2",
                                  command=lambda: copy_dynamic_rules(root, title, mw))
        canvas.create_window(15, y, window=self.btn_copy, anchor="nw")
        self.btn_play = tk.Button(canvas, text="Play", width=6, cursor="hand2",
                                  command=self._toggle)
        canvas.create_window(15, y + 48, window=self.btn_play, anchor="nw")

        canvas.bind("<Button-1>", self._on_press, add="+")
        canvas.bind("<B1-Motion>", self._on_drag, add="+")
        canvas.bind("<ButtonRelease-1>", self._on_release, add="+")

        head = canvas.create_text(self.MARGIN_LEFT, y, text=title, font=("Arial", 11, "bold"), anchor="nw")
        self.y_status = canvas.bbox(head)[3] + 2
        self.y_rules = self.y_status + 30
        # the tasks and the static periods, between the rules and the timeline.
        # Its SHAPE is fixed -- only the resulting shares in it move with t_p --
        # so the space it needs is measured once, here.
        self.y_info = self.y_rules + 16
        self.info_rows, self.info_periods, info_h = info_layout(
            mw.tasks, mw.periods, width=width - self.MARGIN_LEFT - self.MARGIN_RIGHT)
        self.top = self.y_info + info_h + 12
        self.height = (self.top + self.rows * (self.ROW_H + self.ROW_SPACING) + 30) - y
        self._tick()

    def _setup(self, width, sweep_seconds):
        """How much timeline one row shows, and how fast t_p crosses it.

        The displayed timeline is the case's span, whatever the window is sized
        to: only the scale changes with the screen, never the timeline."""
        mw = self.mw
        self.tp = frac(0)
        self.rows = 1
        self.row_duration = mw.span
        self.px_per_min = (width - self.MARGIN_LEFT - self.MARGIN_RIGHT) / float(mw.span)
        self.step = mw.span / (sweep_seconds * self.FPS)

    def _toggle(self):
        self.playing = not self.playing
        self.btn_play.config(text="Pause" if self.playing else "Play")

    def stop(self):
        """End this panel's after() loop: its canvas is about to go.

        A panel drives itself, so a rebuild that only destroyed the canvas would
        leave the loop drawing into a widget that no longer exists."""
        self.stopped = True

    def _running(self):
        return not self.stopped and self.canvas.winfo_exists()

    # ---------------- the user's own hand on t_p ----------------------------- #

    # The pointer may stray a little above or below the bar and still mean it:
    # the t_p marker and the period outline are drawn outside the row itself.
    GRAB_PAD = 12

    def _row_under(self, cy):
        """Which of this panel's rows the pointer is on, or None for neither."""
        for row in range(self.rows):
            y1 = self._row_y(row)
            if y1 - self.GRAB_PAD <= cy <= y1 + self.ROW_H + self.GRAB_PAD:
                return row
        return None

    def _tp_at(self, cx, row):
        minutes = row * self.row_duration + frac(cx - self.MARGIN_LEFT) / frac(self.px_per_min)
        return min(max(minutes, frac(0)), self.mw.span)

    def _canvas_xy(self, event):
        return self.canvas.canvasx(event.x), self.canvas.canvasy(event.y)

    def _on_press(self, event):
        cx, cy = self._canvas_xy(event)
        row = self._row_under(cy)
        if row is None: return
        if not (self.MARGIN_LEFT <= cx <= self._x(self.row_duration)): return
        self.scrubbing = True
        if self.playing: self._toggle()   # a position the user chose is not one
        self.tp = self._tp_at(cx, row)    # the sweep may take back
        self._draw()

    def _on_drag(self, event):
        if not self.scrubbing: return
        cx, cy = self._canvas_xy(event)
        # once the drag has started it owns the pointer: leaving the row band
        # sideways or vertically drags along the row it began on, clamped
        row = self._row_under(cy)
        self.tp = self._tp_at(cx, row if row is not None else int(self.tp // self.row_duration))
        self._draw()

    def _on_release(self, _event):
        self.scrubbing = False

    def _x(self, minutes):
        return self.MARGIN_LEFT + float(minutes) * self.px_per_min

    def _row_y(self, row_idx):
        return self.top + row_idx * (self.ROW_H + self.ROW_SPACING)

    def _tick(self):
        if not self._running(): return
        self._draw()
        if self.playing:
            self.tp += self.step
            if self.tp + self.mw.reach >= self.mw.span:   # the period itself, not
                self.tp = frac(0)                         # merely its start, reached
        refresh_scrollregion(self.canvas)                 # the end of the timeline
        self.tooltip.refresh()
        self.canvas.after(int(1000 / self.FPS), self._tick)

    def _bar(self, start, end, **kw):
        """One span of the timeline, wrapped over the rows it crosses."""
        out = []
        t, end = frac(start), min(frac(end), self.mw.span)
        while t < end:
            row = int(t // self.row_duration)
            if row >= self.rows: break
            in_row = t % self.row_duration
            width = min(end - t, self.row_duration - in_row)
            y1 = self._row_y(row)
            out.append((self._x(in_row), y1, self._x(in_row + width), y1 + self.ROW_H))
            t += width
        return out

    def _draw(self):
        c, mw, tp = self.canvas, self.mw, self.tp
        for item in c.find_withtag(self.tag): self.tooltip.unregister(item)
        c.delete(self.tag)

        regime = mw.regime_at(tp)
        c.create_text(self.MARGIN_LEFT, self.y_status, anchor="nw", font=("Arial", 9),
                      fill="#333333", tags=self.tag,
                      text=f"t_p = {stamp(tp)}   ->   rules for t_p in {regime.label}"
                           f"   ({'playing' if self.playing else 'paused'};"
                           f" substituted into the rule list, not recomputed)")
        c.create_text(self.MARGIN_LEFT, self.y_rules - 14, anchor="nw", font=("Courier", 8),
                      fill="#7A0000", tags=self.tag,
                      text="Prefix: " + self._ellipsis(regime.prefix_text))
        c.create_text(self.MARGIN_LEFT, self.y_rules, anchor="nw", font=("Courier", 8),
                      fill="#00407A", tags=self.tag,
                      text="Cycle:  " + self._ellipsis(regime.cycle_text))

        drawn = timeline_of(mw, tp)
        prefix, cycle = mw.blocks_at(tp)
        draw_info(c, self.MARGIN_LEFT, self.y_info, mw.tasks, mw.periods,
                  cycle_shares(cycle or prefix), span_shares(drawn),
                  self.info_rows, self.info_periods, tag=self.tag, tooltip=self.tooltip)

        for start, end, name in drawn:
            if end <= start: continue
            frozen = end <= tp
            hover = (f"Task {name}\nStart: {stamp(start)}\nEnd: {stamp(end)}\n"
                     f"Duration: {human(end - start)}\n"
                     f"{'frozen past' if frozen else 'from the rules'}")
            for x1, y1, x2, y2 in self._bar(start, end):
                rect = c.create_rectangle(x1, y1, x2, y2, fill=block_color(name),
                                          outline="#999999" if frozen else "black",
                                          tags=(self.tag, "task_panel"))
                self.tooltip.register(rect, hover)
                if x2 - x1 > 26:
                    txt = c.create_text((x1 + x2) / 2, (y1 + y2) / 2, text=name,
                                        font=("Arial", 9), tags=(self.tag, "task_panel"))
                    self.tooltip.register(txt, hover)

        for w in mw.moving(tp):
            for x1, y1, x2, y2 in self._bar(w['start'], w['end']):
                rect = c.create_rectangle(x1, y1 - 6, max(x2, x1 + 2), y2 + 6,
                                          outline="#D40000", width=2, tags=self.tag)
                self.tooltip.register(rect, w.get('label', 'period'))
            for x1, y1, _x2, _y2 in self._bar(w['start'], w['end'])[:1]:
                c.create_text(x1, y1 - 8, text=w.get('label', ''), fill="#D40000",
                              font=("Arial", 7), anchor="sw", tags=self.tag)

        for row in range(self.rows):
            y1 = self._row_y(row)
            c.create_text(self.MARGIN_LEFT - 8, y1 + self.ROW_H / 2,
                          text=stamp(row * self.row_duration), font=("Arial", 8),
                          fill="#777777", anchor="e", tags=self.tag)
        for x1, y1, _x2, _y2 in self._bar(tp, tp + Fraction(1, 600)):
            c.create_line(x1, y1 - 12, x1, y1 + self.ROW_H + 12, fill="#D40000",
                          width=2, tags=self.tag)
            c.create_text(x1 + 2, y1 + self.ROW_H + 12, text="t_p", fill="#D40000",
                          font=("Arial", 8, "bold"), anchor="nw", tags=self.tag)

    @staticmethod
    def _ellipsis(text, width=132):
        return text if len(text) <= width else text[:width - 3] + "..."


def draw_moving_cases(root, canvas, tooltip, cases, y_offset, window_width=900, panels=None):
    for title, mw, sweep in cases:
        panel = MovingCasePanel(root, canvas, tooltip, y_offset, title, mw, sweep,
                                width=window_width)
        if panels is not None: panels.append(panel)
        y_offset += panel.height + 30
    return y_offset



class ProgressiveCasePanel(MovingCasePanel):
    """A timeline whose rules are still being derived while it is shown.

    The chain is settled a link at a time, between frames, so the window stays
    live and what is drawn is whatever is known at this frame. Left of the
    front the rules are DEFINITIVE and will not move again; right of it the
    display carries the last link's cycle on as a provisional answer, which is
    why -- with the sweep paused and nothing touched -- the far end of the
    timeline goes on changing while the definitive part grows from t=0.

    One link per frame rather than a worker thread: a link is a tenth of a
    second, the frame that draws it is a sixtieth, and interleaving them keeps
    the window answering while it fills. A thread would only add the GIL.

    t_p behaves as it does everywhere else: everything left of it is frozen,
    and here it also consumes the breaks it goes past.
    """

    ROWS = 6
    FPS = 12

    def __init__(self, root, canvas, tooltip, y, title, pw, sweep_seconds, width=900):
        self.pw = pw
        super().__init__(root, canvas, tooltip, y, title, pw, sweep_seconds, width=width)

    def _setup(self, width, sweep_seconds):
        pw = self.pw
        self.tp = pw.tp_start
        self.rows = self.ROWS
        self.row_duration = pw.span / self.rows
        self.px_per_min = (width - self.MARGIN_LEFT - self.MARGIN_RIGHT) / float(self.row_duration)
        self.step = (pw.span - pw.tp_start) / (sweep_seconds * self.FPS)

    def _tp_at(self, cx, row):
        t = super()._tp_at(cx, row)
        return min(max(t, self.pw.tp_start), self.pw.span)

    WORK_BUDGET = 0.02          # seconds of deriving per frame: one link

    def _tick(self):
        if not self._running(): return
        # The two chains first -- they are the timeline itself. Then the rules
        # AT THE LINE, but ONLY while the line is standing still: deriving one
        # takes a second or two and the sweep crosses a regime every frame, so
        # doing it while playing would stall the line instead of moving it. A
        # position the user stops at (or clicks on) becomes exact within a frame
        # or two, and stays so -- a regime is derived once and kept.
        if not self.pw.done:
            self.pw.advance(budget=self.WORK_BUDGET)
        elif not self.playing and not self.pw.has_rules_at(self.tp):
            self.pw.regime_at(self.tp)
        self._draw()
        if self.playing:
            self.tp += self.step
            if self.tp >= self.mw.span: self.tp = self.pw.tp_start
        refresh_scrollregion(self.canvas)
        self.tooltip.refresh()
        self.canvas.after(int(1000 / self.FPS), self._tick)

    def _status(self):
        pw = self.pw
        settled, worked = pw.pace()
        rate = float(settled) / worked if worked else 0.0
        state = "settled" if pw.done else "still deriving"
        r = pw.rules_at(self.tp)
        rules = (f"rules at the line: exact, for t_p in {r.label}" if r is not None else
                 "rules at the line: provisional (stop the sweep to derive them)")
        return (f"t_p = {stamp(self.tp)}   ->   definitive up to t = {stamp(pw.settled)}"
                f"   ({len(pw.segments)} links, {len(pw.regimes)} regimes, {state}, "
                f"{rate:.0f} min of timeline per second of work; {rules}, substituted "
                f"into, not recomputed)")

    def _draw(self):
        c, pw, tp = self.canvas, self.pw, self.tp
        for item in c.find_withtag(self.tag): self.tooltip.unregister(item)
        c.delete(self.tag)

        c.create_text(self.MARGIN_LEFT, self.y_status, anchor="nw", font=("Arial", 9),
                      fill="#333333", tags=self.tag, text=self._status())
        seg = pw.segment_at(tp)
        c.create_text(self.MARGIN_LEFT, self.y_rules - 14, anchor="nw", font=("Courier", 8),
                      fill="#7A0000", tags=self.tag,
                      text=("Link at t_p " + (seg.label if seg else "(not settled yet)")
                            + ":  " + self._ellipsis(seg.prefix_text if seg else "")))
        c.create_text(self.MARGIN_LEFT, self.y_rules, anchor="nw", font=("Courier", 8),
                      fill="#00407A", tags=self.tag,
                      text="Cycle:  " + self._ellipsis(seg.cycle_text if seg else ""))

        # the cycle share comes from the rules AT THE LINE when they have been
        # derived, and from the link otherwise: never from `regime_at`, which
        # would derive one, and drawing a frame may not do that.
        local = pw.blocks_at(tp, fit=False)
        if local is not None: cycle = local[1] or local[0]
        elif seg is not None: cycle = seg.cycle or seg.prefix
        else: cycle = []
        drawn = pw.timeline(tp, fit=False)
        draw_info(c, self.MARGIN_LEFT, self.y_info, pw.tasks, pw.periods,
                  cycle_shares(cycle), span_shares(drawn),
                  self.info_rows, self.info_periods, tag=self.tag, tooltip=self.tooltip)

        for start, end, name in drawn:
            if end <= start: continue
            frozen = end <= tp
            known = start < pw.settled
            hover = (f"Task {name}\nStart: {stamp(start)}\nEnd: {stamp(end)}\n"
                     f"Duration: {human(end - start)}\n"
                     f"{'frozen past' if frozen else 'from the rules' if known else 'provisional: not settled yet'}")
            for x1, y1, x2, y2 in self._bar(start, end):
                rect = c.create_rectangle(
                    x1, y1, x2, y2, fill=block_color(name),
                    outline="#999999" if frozen else "black",
                    stipple="" if known else "gray50",
                    tags=(self.tag, "task_panel"))
                self.tooltip.register(rect, hover)
                if x2 - x1 > 26:
                    txt = c.create_text((x1 + x2) / 2, (y1 + y2) / 2, text=name,
                                        font=("Arial", 8), tags=(self.tag, "task_panel"))
                    self.tooltip.register(txt, hover)

        for w in pw.sliding(tp):
            for x1, y1, x2, y2 in self._bar(w['start'], w['end']):
                rect = c.create_rectangle(x1, y1 - 4, max(x2, x1 + 2), y2 + 4,
                                          outline="#D40000",
                                          width=2 if w.get('pinned') else 1, tags=self.tag)
                self.tooltip.register(rect, w.get('label', 'period'))
        for w in pw.periods:
            if w['start'] >= pw.span or w['end'] <= 0: continue
            for x1, y1, x2, y2 in self._bar(max(w['start'], frac(0)), w['end']):
                rect = c.create_rectangle(x1, y1 - 2, max(x2, x1 + 2), y2 + 2,
                                          outline="#7030A0", width=1, tags=self.tag)
                self.tooltip.register(rect, w.get('label', 'period'))

        for row in range(self.rows):
            y1 = self._row_y(row)
            c.create_text(self.MARGIN_LEFT - 8, y1 + self.ROW_H / 2,
                          text=stamp(row * self.row_duration), font=("Arial", 8),
                          fill="#777777", anchor="e", tags=self.tag)
        for x1, y1, _x2, _y2 in self._bar(pw.settled, pw.settled + Fraction(1, 600)):
            c.create_line(x1, y1 - 6, x1, y1 + self.ROW_H + 6, fill="#008000",
                          width=2, tags=self.tag)
            c.create_text(x1 + 2, y1 - 6, text="definitive", fill="#008000",
                          font=("Arial", 7), anchor="sw", tags=self.tag)
        for x1, y1, _x2, _y2 in self._bar(tp, tp + Fraction(1, 600)):
            c.create_line(x1, y1 - 12, x1, y1 + self.ROW_H + 12, fill="#D40000",
                          width=2, tags=self.tag)
            c.create_text(x1 + 2, y1 + self.ROW_H + 12, text="t_p", fill="#D40000",
                          font=("Arial", 8, "bold"), anchor="nw", tags=self.tag)


def draw_progressive_cases(root, canvas, tooltip, cases, y_offset, window_width=900, panels=None):
    for title, pw, sweep in cases:
        panel = ProgressiveCasePanel(root, canvas, tooltip, y_offset, title, pw, sweep,
                                     width=window_width)
        if panels is not None: panels.append(panel)
        y_offset += panel.height + 30
    return y_offset

def print_statement(tasks, periods, shares=None):
    """The case's own statement -- the same table the window draws, in text."""
    total = sum((t.priority for t in tasks), frac(0)) or frac(1)
    print(f"[ TASKS ] name, minimum, priority"
          + (" -> resulting share of the cycle" if shares is not None else ""))
    for t in tasks:
        got = f" -> {_pct(shares.get(t.name, 0)):>8}" if shares is not None else ""
        print(f"  {t.name:<4}{human(t.min_time):>8}{_pct(t.priority / total):>8}{got}")
    print(f"[ STATIC PERIODS ] {len(periods)}, by the set of tasks they do NOT allow")
    for line, _tip in period_lines(periods):
        print("  " + line)

def print_terminal_results(cases, moving_cases, progressive_cases=()):
    for case in cases:
        title, tasks, _total_duration, pre_placed, periods, options = case_parts(case)
        prefix, cycle, plan = get_schedule_rules(tasks, pre_placed, periods, **options)

        print(f"--- {title.splitlines()[0]} ---")
        print_statement(tasks, periods, plan.shares)
        if prefix:
            print("[ PREFIX ]")
            for line in rule_lines(prefix, "  "): print(line)
        if cycle:
            print("[ CYCLE ]")
            for line in rule_lines(cycle, "  "): print(line)
            print(f"  (Period: {human(plan.period)})\n")
        else:
            print("[ NO CYCLE FOUND ]\n")

    for title, mw, _sweep in moving_cases:
        print_moving_rules(title, mw)
    for title, pw, _sweep in progressive_cases:
        print_progressive_rules(title, pw)

def print_moving_rules(title, mw, max_regimes=12):
    print(f"--- {title.splitlines()[0]} ---")
    # no resulting share here: it is a function of t_p, and the rule list below
    # is what it is read off at each position
    print_statement(mw.tasks, mw.periods)
    print("[ DYNAMIC RULE SET - this is the output; the display substitutes t_p into it ]")
    for line in mw.lines()[:4 + 3 * max_regimes]: print("  " + line)
    if len(mw.regimes) > max_regimes:
        print(f"  ... {len(mw.regimes) - max_regimes} more regimes "
              f"(the Copy Rules button gives all of them)")
    print()

def print_progressive_rules(title, pw, max_links=8):
    print(f"--- {title.splitlines()[0]} ---")
    print_statement(pw.tasks, pw.periods)
    print("[ PROGRESSIVE RULE SET - a chain of links, settled from t=0 outward ]")
    pw.settle()
    for line in pw.lines(max_segments=max_links): print("  " + line)
    print()


# --------------------------------------------------------------------------- #
#  the window: a bar at the top that owns tau, and the display it rebuilds
# --------------------------------------------------------------------------- #

TAU_MIN, TAU_MAX, TAU_STEP = 0.05, 8.0, 0.05

def load_colors(cases):
    for _t, mw, _s in cases:
        TASK_COLORS.update(mw.sched.color)
        TASK_COLORS.setdefault(IDLE, IDLE_COLOR)
        TASK_COLORS.setdefault("MAINTENANCE", "#CCCCCC")


class Workbench:
    """The whole window: the tau bar, and the canvas of tests under it.

    tau is the decay constant of the compensation field -- how far, on either
    side of an exclusion, the deprivation it caused still swells the deprived
    task's slots, and how fast an imbalance older than a period is forgotten.
    Its default is each case's own minimal period `max(mi/pi)`, so the knob is
    a MULTIPLE of that rather than an absolute span: one number that means the
    same thing to test 6's 60 minutes and to test 12's three days.

    Changing it REBUILDS everything. The rule lists are a function of tau --
    every regime was fitted with it, by the scheduler -- so there is nothing to
    patch in place, which is why the bar says what it is doing while it works.
    The panels drive their own after() loops, so they are stopped before the
    canvas they draw into is destroyed.
    """

    def __init__(self, root, width=900, initial=None):
        self.root, self.width = root, width
        self.scale = 1.0
        self.panels, self.tooltip = [], None
        self.body = self.canvas = None
        self.initial = initial          # the default-tau cases the checks built
        self._bar()
        self.rebuild()

    # ---------------- the bar ---------------- #

    def _bar(self):
        assert tk is not None
        bar = tk.Frame(self.root, bd=1, relief="raised", padx=8, pady=5)
        bar.pack(side=tk.TOP, fill=tk.X)
        tk.Label(bar, text="tau  (exponential decay of the influence, both directions)",
                 font=("Arial", 9, "bold")).pack(side=tk.LEFT)
        tk.Label(bar, text="x").pack(side=tk.LEFT, padx=(10, 1))

        self.text = tk.StringVar(value="1")
        entry = tk.Entry(bar, textvariable=self.text, width=6, justify="right")
        entry.pack(side=tk.LEFT)
        entry.bind("<Return>", lambda _e: self.apply(self.text.get()))

        self.slider = tk.Scale(bar, from_=TAU_MIN, to=TAU_MAX, resolution=TAU_STEP,
                               orient=tk.HORIZONTAL, showvalue=False, length=200,
                               command=lambda v: self.text.set(f"{float(v):g}"))
        self.slider.set(1.0)
        self.slider.pack(side=tk.LEFT, padx=8)
        self.slider.bind("<ButtonRelease-1>", lambda _e: self.apply(self.slider.get()))

        tk.Button(bar, text="Apply", cursor="hand2",
                  command=lambda: self.apply(self.text.get())).pack(side=tk.LEFT)
        tk.Button(bar, text="Default", cursor="hand2",
                  command=lambda: self.apply(1.0)).pack(side=tk.LEFT, padx=4)

        self.status = tk.Label(bar, text="", fg="#555555", font=("Arial", 9))
        self.status.pack(side=tk.LEFT, padx=10)

    @staticmethod
    def _parse(value):
        """A positive multiple, inside the range the slider offers, or None.

        Zero is not a small tau, it is a different model -- the field has no
        width to decay over at all -- so it is refused rather than clamped."""
        try:
            scale = float(str(value).strip().lstrip("x").strip())
        except ValueError:
            return None
        if scale <= 0: return None
        return min(max(scale, TAU_MIN), TAU_MAX)

    def apply(self, value):
        scale = self._parse(value)
        if scale is None:
            self.status.config(text=f"a positive multiple, {TAU_MIN:g} to {TAU_MAX:g}",
                               fg="#B00000")
            return
        self.text.set(f"{scale:g}")
        self.slider.set(scale)
        if scale == self.scale: return
        self.scale = scale
        self.rebuild()

    def _say(self, tail, color="#555555"):
        self.status.config(text=f"tau = x{self.scale:g} of each case's minimal "
                                f"period   |   {tail}", fg=color)

    # ---------------- the display under it ---------------- #

    def rebuild(self):
        self._say("rebuilding the rule lists (the scheduler is run again)...")
        self.root.update_idletasks()
        self._teardown()

        t0 = time.perf_counter()
        if self.initial is not None and self.scale == 1.0:
            cases, moving, progressive = self.initial   # already built for the checks
            self.initial = None
        else:
            cases = build_cases(self.scale)
            moving = build_moving_cases(self.scale)
            progressive = build_progressive_cases(self.scale)
        load_colors(moving + progressive)

        self._make_body()
        y = draw_schedules(self.root, self.canvas, cases, window_width=self.width)
        self.tooltip = ToolTip(self.canvas)
        y = draw_moving_cases(self.root, self.canvas, self.tooltip, moving, y,
                              window_width=self.width, panels=self.panels)
        draw_progressive_cases(self.root, self.canvas, self.tooltip, progressive, y,
                               window_width=self.width, panels=self.panels)
        refresh_scrollregion(self.canvas)
        self._say(f"{len(cases) + len(moving) + len(progressive)} cases rebuilt "
                  f"in {time.perf_counter() - t0:.1f}s")

    def _teardown(self):
        for panel in self.panels: panel.stop()
        self.panels = []
        if self.tooltip is not None: self.tooltip.hide()
        self.tooltip = None
        if self.body is not None: self.body.destroy()
        self.body = self.canvas = None

    def _make_body(self):
        assert tk is not None
        self.body = tk.Frame(self.root)
        self.body.pack(side=tk.TOP, fill=tk.BOTH, expand=True)
        canvas = tk.Canvas(self.body, bg="white")
        vbar = tk.Scrollbar(self.body, orient=tk.VERTICAL, command=canvas.yview)
        canvas.configure(yscrollcommand=vbar.set)
        vbar.pack(side=tk.RIGHT, fill=tk.Y)
        canvas.pack(side=tk.LEFT, fill=tk.BOTH, expand=True)
        self.canvas = canvas

        def wheel(event):
            if getattr(event, 'num', 0) == 4 or event.delta > 0:
                canvas.yview_scroll(-3, "units")
            elif getattr(event, 'num', 0) == 5 or event.delta < 0:
                canvas.yview_scroll(3, "units")

        for seq in ("<MouseWheel>", "<Button-4>", "<Button-5>"):
            canvas.bind_all(seq, wheel)
        canvas.bind_all("<Prior>", lambda e: canvas.yview_scroll(-1, "pages"))
        canvas.bind_all("<Next>", lambda e: canvas.yview_scroll(1, "pages"))
        canvas.bind_all("<Home>", lambda e: canvas.yview_moveto(0.0))
        canvas.bind_all("<End>", lambda e: canvas.yview_moveto(1.0))


def main():
    verify_only = "--verify" in sys.argv
    rules_only = "--rules" in sys.argv
    update_rules = "--update-rules" in sys.argv
    no_ui = verify_only or rules_only or update_rules or "--no-ui" in sys.argv

    cases = build_cases()
    moving = build_moving_cases()
    progressive = build_progressive_cases()
    load_colors(moving + progressive)

    if update_rules:
        text = write_rules_snapshot(cases, moving, progressive)
        print(f"wrote the rule sets on record ({len(text.splitlines())} lines)")
        return
    if rules_only:
        for title, mw, _sweep in moving:
            print_moving_rules(title, mw, max_regimes=len(mw.regimes))
        for title, pw, _sweep in progressive:
            print_progressive_rules(title, pw, max_links=None)
        return
    if not verify_only:
        print_terminal_results(cases, moving, progressive)

    # what the rules MEAN (self-consistency), then whether they are still the
    # same rules the project agreed on
    failures = verify_moving(moving)
    failures += verify_progressive(progressive)
    failures += check_rules_snapshot(cases, moving, progressive)

    if no_ui:
        raise SystemExit(1 if failures else 0)
    if tk is None:
        print("tkinter is not available: skipping the UI (rules and checks above are unaffected).")
        return

    root = tk.Tk()
    root.title("Task Scheduler Timeline with Constraints (including the dynamic rule list)")
    root.geometry("950x700")

    # the cases the checks above already built are the tau = x1 ones, so the
    # window opens on them instead of scheduling everything a second time
    Workbench(root, width=900, initial=(cases, moving, progressive))
    root.mainloop()

if __name__ == "__main__":
    main()
