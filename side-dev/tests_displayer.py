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

Tests 12 to 14's rule lists are chains, derived a link at a time rather than
before anything can be shown: their panel settles the chain on a BACKGROUND
THREAD and draws whatever is known now -- definitive up to the front,
provisional past it.
Scrolling to it straight away shows the far end of the schedule still changing
while the definitive part grows from t=0.

Those three are the cases that are DERIVED WHILE THEY ARE SHOWN, and one of them
is on screen at a time: a second bar at the top chooses which (the last of them
by default) and it is drawn at the bottom of the window. Only the case on
screen derives. Choosing another does not restart anything -- the case being
left keeps every link it has settled, because the chain lives in its own
window object and the selector only swaps the PANEL, so a case chosen again
goes on from the link it had reached. Two chains settling at once would be two
workers competing for one interpreter and for the thread that draws, and each
front would crawl at half its pace.

Nothing that costs more than a frame runs on the thread that draws. Deriving one
link of test 12's chain is a third of a second and fitting one regime is up to
six, against a frame of a twelfth -- so a window that derived between frames was
not a window that stayed answering, it was one that blocked for a third of a
second at a time and, once, for six. The rebuild the tau bar triggers is the
same shape: the scheduling runs on a worker and each stage is DRAWN, on the main
thread, as it lands.
"""

import queue
import sys
import threading
import time
import traceback
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
    resulting_shares,
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

# The resulting share is ONE number, not two: what the drawn timeline gave a
# task, over the time some task was allowed to run in. It lives in
# scheduler_logic (`resulting_shares`) because the check in test_configs reports
# the same measure, and the two must not be able to disagree.

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

def info_chars(width):
    """How many characters of INFO_FONT fit across a block `width` pixels wide.

    The canvas scrolls vertically only, so a line wider than the block is a line
    the window cannot show: this is the budget every line in it is cut to, and
    what is cut off is kept whole on the tooltip."""
    return max(20, int(width // INFO_CHAR_W))

def _state_text(tasks):
    """One state of the percentages, as a line.

    Tasks declaring the same minimum and the same percentage are collapsed into
    a run, because the two states of twenty-one tasks differ in a handful of
    numbers and naming them one by one buries exactly that."""
    total = sum((t.priority for t in tasks), frac(0)) or frac(1)
    groups = {}
    for t in tasks:
        groups.setdefault((t.min_time, t.priority), []).append(t.name)
    return ";  ".join(f"{compact_names(names)} {human(m)} {_pct(pr / total)}"
                      for (m, pr), names in groups.items())

def _state_tip(head, tasks):
    """The same state spelled out task by task, for the tooltip: the runs above
    are folded, never hidden."""
    total = sum((t.priority for t in tasks), frac(0)) or frac(1)
    rows = [f"  {t.name:<4}{human(t.min_time):>8}{_pct(t.priority / total):>8}"
            for t in tasks]
    return "\n".join([head] + rows)

def state_lines(states, chars=None, at=None):
    """A sliding set of percentages: both its ends, WHEN each of them is in
    force, and where the line stands between them (test 13).

    The table above these lines is the state at t_p and says nothing about what
    it is travelling between, which is the case itself: at t_p=36h "A 37.5%" is
    a number, and "50% at 24h -> 25% at 48h, and we are halfway" is the answer.

    So each end is named by the STRETCH OF t_p IT GOVERNS rather than by the one
    instant it is pinned at -- a state is held outside the transition, so "at
    24h" was a third of what there is to say about where it applies -- and a
    line between the two says where t_p is, how far across it has come, and what
    the percentages therefore are there. `at` is `(t_p, tasks)`, this frame's;
    without it that line says the transition has not been entered.

    The COUNT of lines does not depend on `at`, which is what lets the block be
    measured once (`info_layout`) and re-worded every frame."""
    if not states: return []
    lo, hi = states[0][0], states[-1][0]
    head = (f"the percentages SLIDE between two states, t_p {stamp(lo)} -> {stamp(hi)}, "
            f"and are HELD at the nearer one outside that:")
    tip = (f"the plan made at t_p satisfies the percentages at exactly t_p."
           f"\nUp to t_p {stamp(lo)} the first state stands; from t_p {stamp(hi)} on,"
           f"\nthe second does -- held, never extrapolated: a percentage carried"
           f"\npast the state it was fitted to leaves the hundred it is a share of.")
    out = [(_clip(head, chars), tip)]
    for i, (pos, tasks) in enumerate(states):
        first, last = i == 0, i == len(states) - 1
        when = (f"t_p <= {stamp(pos)}" if first and not last else
                f"t_p >= {stamp(pos)}" if last and not first else f"t_p = {stamp(pos)}")
        label = "from" if first and not last else "to" if last and not first else "at"
        out.append((_clip(f"  {label:<4} ({when}):  " + _state_text(tasks), chars),
                    _state_tip(f"the percentages for {when}, the state pinned at "
                               f"t_p {stamp(pos)}", tasks)))
        if first and not last:
            out.append(_crossing_line(lo, hi, at, chars))
    return out

def _crossing_line(lo, hi, at, chars):
    """Where the line stands between the two states, and what the percentages
    are there: the half of the answer a table read at t_p cannot give."""
    if at is None:
        return (_clip("  ...   (the transition: the line is not in it yet)", chars), None)
    tp, tasks = at
    x = (min(max((frac(tp) - lo) / (hi - lo), frac(0)), frac(1)) if hi > lo else frac(0))
    where = ("before it, so the first state stands" if tp <= lo else
             "past it, so the second state stands" if tp >= hi else
             f"{float(x) * 100:.0f}% of the way across")
    return (_clip(f"  ...   t_p = {stamp(tp)}, {where}:  " + _state_text(tasks), chars),
            _state_tip(f"the percentages at the line, t_p {stamp(tp)}", tasks))

def info_layout(tasks, periods, width=780, states=()):
    """The fixed shape of the block: how many task rows per column, the period
    lines, the end states of a blend if the case has any, and the height the
    three of them need."""
    cols_max = max(1, int(width // TASK_COL_W))
    rows = min(TASK_COL_ROWS, len(tasks)) or 1
    cols = -(-len(tasks) // rows)
    if cols > cols_max:
        rows = -(-len(tasks) // cols_max)
    chars = info_chars(width)
    plines = period_lines(periods, chars=chars)
    slines = state_lines(states, chars=chars)
    return rows, plines, slines, INFO_LINE_H * (3 + rows + len(plines) + len(slines))

def draw_info(canvas, x, y, tasks, periods, got, open_total, rows, plines,
              slines=(), tag=None, tooltip=None):
    """Draw the block and return its height. `got` is `resulting_shares`, and
    `open_total` how much timeline it was measured over. `slines` are the ends
    of a sliding set of percentages (`state_lines`), drawn under the table they
    are the two ends of -- nothing on a case whose percentages stand still."""
    tags = (tag,) if tag else ()

    def text(ty, s, fill, tx=None):
        return canvas.create_text(x if tx is None else tx, ty, anchor="nw",
                                  font=INFO_FONT, fill=fill, tags=tags, text=s)

    text(y, f"tasks ({len(tasks)}):  name, minimum, priority  ->  resulting share of "
            f"the {human(open_total)} some task is allowed in", "#333333")
    total = sum((t.priority for t in tasks), frac(0)) or frac(1)
    for i, t in enumerate(tasks):
        col, row = divmod(i, rows)
        cell = (f"{t.name:<4}{human(t.min_time):>7}{_pct(t.priority / total):>7}"
                f" ->{_pct(got.get(t.name, 0)):>8}")
        text(y + INFO_LINE_H * (1 + row), cell, "#000000", tx=x + col * TASK_COL_W)

    # the timeline is not made of tasks alone -- a pre-placed block owned by
    # nobody, and the time a minimum could not fit into, hold their share of it
    # too, and a table of tasks that did not sum to the timeline would look like
    # an error rather than like the answer
    rest = {n: s for n, s in got.items() if n not in {t.name for t in tasks}}
    if rest:
        text(y + INFO_LINE_H * (1 + rows),
             "not a task:  " + ", ".join(f"{n} {_pct(s)}" for n, s in sorted(rest.items())),
             "#777777")

    y1 = y + INFO_LINE_H * (2 + rows)
    for i, (line, tip) in enumerate(slines):
        item = text(y1 + INFO_LINE_H * i, line, "#7A0000" if tip else "#333333")
        if tip and tooltip is not None: tooltip.register(item, tip)

    y2 = y1 + INFO_LINE_H * len(slines)
    text(y2, f"static periods ({len(periods)}), by the set of tasks they do NOT allow:", "#333333")
    for i, (line, tip) in enumerate(plines):
        item = text(y2 + INFO_LINE_H * (1 + i), line, "#7030A0")
        if tip and tooltip is not None: tooltip.register(item, tip)
    return INFO_LINE_H * (3 + rows + len(plines) + len(slines))


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
        rows, plines, slines, _h = info_layout(
            tasks, periods, width=window_width - margin_left - margin_right)
        info_y = canvas.bbox(sub_id)[3] + 6
        drawn = [(b['start'], b['start'] + b['duration'], b['name']) for b in schedule]
        got, open_total = resulting_shares(drawn, periods, [t.name for t in tasks],
                                           lo=start_time, hi=start_time + total_duration)
        height = draw_info(canvas, margin_left, info_y, tasks, periods,
                           got, open_total, rows, plines, slines, tooltip=tooltip)
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


# Handing the interpreter back to the drawing thread promptly.
#
# Every Tcl call releases the interpreter lock and has to queue for it again,
# so once a worker is running, what a frame costs is not its work but its
# thousand handovers. Measured on this window: 500 canvas calls take 1.9 ms
# alone, 264 ms behind two workers at the default 5 ms switch interval, and
# 8.4 ms at 0.5 ms. The default is tuned for threads that hold the lock in long
# stretches; the thread that draws holds it in very short ones and needs it
# back at once, which is exactly what a shorter interval buys. It costs the
# workers a little throughput and buys the window a factor of thirty -- and
# without it, moving the deriving off the frame achieves nothing at all,
# because the frame then spends its time waiting rather than working.
SWITCH_INTERVAL = 0.0005


def share_the_interpreter():
    """Called wherever a worker is started; idempotent."""
    if sys.getswitchinterval() > SWITCH_INTERVAL:
        sys.setswitchinterval(SWITCH_INTERVAL)


class Deriver:
    """A thread that derives while the window draws.

    Deriving between frames does not keep a window answering, it only makes the
    blocks shorter than the work: a link of test 12's chain is a third of a
    second and a regime is up to six, against a frame of a twelfth, and both
    were measured blocking the loop for exactly that long. On a thread of its
    own the same work leaves the loop its slices -- the GIL costs THROUGHPUT
    here, not responsiveness, and responsiveness is the one thing the window
    may not spend, since what this case is FOR is watching the definitive part
    grow and a frozen window shows nothing growing.

    A thread alone is not enough, and it is worth being exact about why, because
    the measurement is counter-intuitive: with the work moved here and nothing
    else changed, a frame of test 12's panel went from 3.2 s to 3.2 s. The
    deriving was gone from it and it was waiting instead -- every Tcl call
    releases the interpreter lock and queues for it again behind a worker that
    holds it for a whole switch interval. `share_the_interpreter` is the other
    half of the fix and the frame is 42 ms with it.

    What crosses the threads is the derived object itself -- written here, read
    by the drawing thread -- and that is sound without a lock because the chain
    is only ever appended to and publishes its front last (`_extend_chain`): a
    reader between two commits sees an earlier state of it, never a torn one.

    `generation` is the only handshake: it counts units of work done, so the
    panel can tell a frame that is out of date from one that is not.
    """

    IDLE_SLEEP = 0.02          # nothing to derive: give the drawing thread all of it

    def __init__(self, job):
        self.job = job         # one unit of work; True if anything changed
        self.generation = 0
        self.stopped = False
        share_the_interpreter()
        self.thread = threading.Thread(target=self._run, daemon=True)
        self.thread.start()

    def _run(self):
        while not self.stopped:
            try:
                worked = self.job()
            except Exception:                    # a derivation that raises must
                traceback.print_exc()            # say so, not vanish with its
                return                           # thread and leave a dead panel
            if worked:
                self.generation += 1
            else:
                time.sleep(self.IDLE_SLEEP)

    def stop(self):
        """Ask the thread to end after the unit it is in.

        A unit is not interrupted -- a link is a third of a second and a fit is
        bounded by its own deadline -- so a torn-down panel costs at most that
        much of a background thread, and never anything of the window."""
        self.stopped = True


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
    # how much room the status sentence above the rules is given. It WRAPS
    # inside the panel rather than running off the right edge of a canvas that
    # only scrolls up and down -- which is what put test 13's "where the line
    # stands between the two states" a thousand pixels past the window.
    STATUS_H = 30
    # the room above the FIRST row of the timeline. A row does not begin at its
    # own top: the t_p line overshoots it by 12px and the labels beside the
    # markers ("definitive", a moving period's name) are anchored `sw` a few
    # pixels higher still, so a row's drawing reaches about 20px above
    # `_row_y(0)`. Reserving less than that does not clip anything -- the canvas
    # has no rows -- it simply draws the timeline's first label ON TOP of the
    # last line of the info block, which is what test 13's "static periods" line
    # and its green "definitive" label were doing.
    ROW_LABEL_H = 36
    # how far a label lifted off a neighbour moves, and how many tiers it may
    # use before it gives up and accepts the collision
    LABEL_TIER_H = 12
    LABEL_TIERS = 2

    def __init__(self, root, canvas, tooltip, y, title, mw, sweep_seconds, width=900):
        self.root, self.canvas, self.tooltip, self.mw = root, canvas, tooltip, mw
        self.title = title
        self.tag = f"moving{id(self)}"
        # The timeline is deleted and rebuilt every frame; the buttons beside it
        # are made once. They are therefore on a tag of their OWN -- one the
        # frame never deletes and `destroy` does, which is what lets a panel be
        # taken off the canvas without taking the case off with it.
        self.chrome = self.tag + ":chrome"
        self.playing = False
        self.scrubbing = False
        self.stopped = False
        self.dirty = True
        self._setup(width, sweep_seconds)

        assert tk is not None
        self.btn_copy = tk.Button(canvas, text="Copy\nRules", cursor="hand2",
                                  command=lambda: copy_dynamic_rules(root, title, mw))
        canvas.create_window(15, y, window=self.btn_copy, anchor="nw", tags=self.chrome)
        self.btn_play = tk.Button(canvas, text="Play", width=6, cursor="hand2",
                                  command=self._toggle)
        canvas.create_window(15, y + 48, window=self.btn_play, anchor="nw",
                            tags=self.chrome)

        canvas.bind("<Button-1>", self._on_press, add="+")
        canvas.bind("<B1-Motion>", self._on_drag, add="+")
        canvas.bind("<ButtonRelease-1>", self._on_release, add="+")

        # on `chrome`, with the buttons: the title is made ONCE, like they are,
        # so the frame must not delete it -- but `destroy` must. Untagged it
        # belonged to neither, and a canvas item on no tag is one nothing can
        # take off: choosing another case in the selector left the title of the
        # case being replaced on the canvas for good, and the new panel drew
        # itself straight through it (test 14's paragraph under test 13's).
        head = canvas.create_text(self.MARGIN_LEFT, y, text=title, tags=self.chrome,
                                  font=("Arial", 11, "bold"), anchor="nw")
        self.y_status = canvas.bbox(head)[3] + 2
        self.y_rules = self.y_status + self.STATUS_H
        # the tasks and the static periods, between the rules and the timeline.
        # Its SHAPE is fixed -- only the resulting shares in it move with t_p --
        # so the space it needs is measured once, here.
        self.y_info = self.y_rules + 16
        # the ends of a sliding set of percentages, where the case has any
        # (test 13). They are a property of the case, not of t_p, so like the
        # rest of the block's shape they are settled once, here.
        self.info_ends = mw.blend_states() if hasattr(mw, "blend_states") else []
        self.info_width = width - self.MARGIN_LEFT - self.MARGIN_RIGHT
        self.info_chars = info_chars(self.info_width)
        self.info_rows, self.info_periods, self.info_states, info_h = info_layout(
            mw.tasks, mw.periods, width=self.info_width, states=self.info_ends)
        self.top = self.y_info + info_h + self.ROW_LABEL_H
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
        self.dirty = True          # the status line says which it is

    def stop(self):
        """End this panel's after() loop: its canvas is about to go.

        A panel drives itself, so a rebuild that only destroyed the canvas would
        leave the loop drawing into a widget that no longer exists."""
        self.stopped = True

    def destroy(self):
        """Take this panel off the canvas -- and NOTHING else.

        What a panel owns is the drawing; the case itself lives in `self.mw`,
        which is left exactly as it stands. So a panel that is destroyed while
        its chain is half derived costs the derivation nothing: put the same
        window in a new panel and it goes on from the link it had reached.

        The canvas bindings the panel added cannot be removed one by one (Tk
        replaces the whole binding when it is rewritten), so the handlers ask
        `_running()` instead: after this a stray click reaches a panel that
        declines it rather than one that redraws itself back onto the canvas."""
        self.stop()
        if not self.canvas.winfo_exists(): return
        for item in self.canvas.find_withtag(self.tag):
            if self.tooltip is not None: self.tooltip.unregister(item)
        self.canvas.delete(self.tag)
        for button in (self.btn_copy, self.btn_play):
            button.destroy()                 # takes its canvas window with it
        self.canvas.delete(self.chrome)

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
        if not self._running(): return
        cx, cy = self._canvas_xy(event)
        row = self._row_under(cy)
        if row is None: return
        if not (self.MARGIN_LEFT <= cx <= self._x(self.row_duration)): return
        self.scrubbing = True
        if self.playing: self._toggle()   # a position the user chose is not one
        self.tp = self._tp_at(cx, row)    # the sweep may take back
        self._draw(); self.dirty = False; self._present()

    def _on_drag(self, event):
        if not (self.scrubbing and self._running()): return
        cx, cy = self._canvas_xy(event)
        # once the drag has started it owns the pointer: leaving the row band
        # sideways or vertically drags along the row it began on, clamped
        row = self._row_under(cy)
        self.tp = self._tp_at(cx, row if row is not None else int(self.tp // self.row_duration))
        self._draw(); self.dirty = False; self._present()

    def _on_release(self, _event):
        self.scrubbing = False

    def _x(self, minutes):
        return self.MARGIN_LEFT + float(minutes) * self.px_per_min

    def _row_y(self, row_idx):
        return self.top + row_idx * (self.ROW_H + self.ROW_SPACING)

    # A frame that DRAWS is not a frame the user sees. Tk repaints from an idle
    # handler, and an idle handler runs only when no timer is due -- so four
    # panels whose after() loops are perpetually overdue starve the idle queue
    # for good: everything was drawn into the canvas and none of it was ever
    # painted, which is the blank window that opens and never shows a test. A
    # frame therefore ends by PRESENTING what it drew, and the next one is
    # scheduled with a floor under its delay so the event queue always gets a
    # slice of its own. (What made the loops overdue in the first place was
    # deriving inside them, which is now a `Deriver`'s job -- but the floor
    # stands: it is what a redraw of four full timelines costs that decides.)
    MIN_DELAY_MS = 8

    def _tick(self):
        if not self._running(): return
        t0 = time.perf_counter()
        if self._work(): self.dirty = True
        if self.playing:
            self._advance_tp()
            self.dirty = True
        # Redrawing a panel nothing has changed is not free -- it rebuilds every
        # rectangle of the timeline and re-measures the scrollregion over the
        # whole canvas -- and a paused panel changes nothing at all. So a frame
        # draws only what moved, which is what leaves the loop the room to
        # present it (and test 12's worker the room to settle its chain: the
        # GIL is shared, so a frame not drawn is a link derived).
        if self.dirty:
            self._draw()
            refresh_scrollregion(self.canvas)
            self.tooltip.refresh()
            self.dirty = False
        self._present()
        self.canvas.after(self._delay(t0), self._tick)

    def _advance_tp(self):
        self.tp += self.step
        if self.tp + self.mw.reach >= self.mw.span:   # the period itself, not
            self.tp = frac(0)                         # merely its start, reached
                                                      # the end of the timeline

    def _work(self):
        """What this panel picks up between frames, if anything; True if the
        drawing is now out of date. Nothing here: the rule list was fitted once,
        when the case was built. It never DERIVES -- a frame that scheduled
        would be a frame the window spent not answering."""
        return False

    def _delay(self, t0):
        """The frame interval, measured from the START of the frame."""
        spent = int((time.perf_counter() - t0) * 1000)
        return max(self.MIN_DELAY_MS, int(1000 / self.FPS) - spent)

    def _present(self):
        """Run Tk's pending idle handlers: this is what puts the frame on
        screen. Only idle events, so no user callback can re-enter the tick."""
        if self._running(): self.canvas.update_idletasks()

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
                      fill="#333333", tags=self.tag, width=self.info_width,
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
        # measured against the environment AS IT STANDS at this t_p: the sliding
        # period is a period like any other, so where it allows nobody it is cut
        # out of the share exactly as a night is
        got, open_total = resulting_shares(drawn, list(mw.periods) + list(mw.sliding(tp)),
                                           [t.name for t in mw.tasks],
                                           lo=frac(0), hi=mw.span)
        draw_info(c, self.MARGIN_LEFT, self.y_info, mw.tasks, mw.periods,
                  got, open_total, self.info_rows, self.info_periods,
                  self.info_states, tag=self.tag, tooltip=self.tooltip)

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

        # what keeps two period labels apart. Test 11 slides three periods a few
        # minutes from each other, which at this scale is a dozen pixels: named
        # where they stand, the third is written straight through the second.
        placed = {}
        for w in mw.moving(tp):
            for x1, y1, x2, y2 in self._bar(w['start'], w['end']):
                rect = c.create_rectangle(x1, y1 - 6, max(x2, x1 + 2), y2 + 6,
                                          outline="#D40000", width=2, tags=self.tag)
                self.tooltip.register(rect, w.get('label', 'period'))
            for x1, y1, _x2, _y2 in self._bar(w['start'], w['end'])[:1]:
                item = c.create_text(x1, y1 - 8, text=w.get('label', ''), fill="#D40000",
                                     font=("Arial", 7), anchor="sw", tags=self.tag)
                self._stagger(item, placed, y1)

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

    def _stagger(self, item, placed, row_y):
        """Lift a label off the ones already named on its row.

        A period is labelled where it STANDS, so two of them close together are
        two labels in the same pixels -- and a label written over another is not
        a shorter label, it is an unreadable one. `placed` remembers the right
        edge and the tier of every label drawn on `row_y` this frame; a new one
        that would start left of a right edge on its tier moves up a line.
        `ROW_LABEL_H` is the room the top tier needs above the row."""
        bb = self.canvas.bbox(item)
        row = placed.setdefault(row_y, [])
        if bb is None: return
        tier = 0
        while (tier + 1 < self.LABEL_TIERS
               and any(t == tier and bb[0] < right for right, t in row)):
            tier += 1
            self.canvas.move(item, 0, -self.LABEL_TIER_H)
        row.append((bb[2], tier))

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

    The deriving runs on a `Deriver`, not between frames. A link is a third of
    a second and the frame that draws it a twelfth, so interleaving them does
    not keep the window answering -- it blocks it for a third of a second at a
    time, for the half-minute the chain takes, and then for the six a regime is
    allowed. On its own thread the same work leaves every frame its slice, and
    the panel only ever DRAWS what the worker has published.

    t_p behaves as it does everywhere else: everything left of it is frozen,
    and here it also consumes the breaks it goes past.
    """

    ROWS = 6
    FPS = 12
    # its status sentence is the long one (the pace, the links, the regimes and
    # the rules at the line), so it wraps to three lines where the moving cases
    # take one
    STATUS_H = 46

    def __init__(self, root, canvas, tooltip, y, title, pw, sweep_seconds, width=900):
        self.pw = pw
        # set before the base class runs its first frame, which reads them
        self.want = None            # the position the worker owes rules for
        self.at = None              # where the line is, for the worker to read
        self.shares = None          # (tp, settled, got, open_total), published
        self.measured = 0.0         # when the worker last measured them
        self.seen = 0               # the last generation of its work drawn here
        self.shown = 0.0            # when that was
        self.deriver = None
        super().__init__(root, canvas, tooltip, y, title, pw, sweep_seconds, width=width)
        self.deriver = Deriver(self._derive)

    def _setup(self, width, sweep_seconds):
        pw = self.pw
        self.tp = pw.tp_home()
        # whether the line is still WAITING at the origin for the first day to
        # become definitive. It stops waiting the moment it teleports -- or the
        # moment somebody moves it themselves, since a position the user chose
        # is not one the panel may take back
        self.parked = self.tp < pw.sweep_start
        self.rows = self.ROWS
        self.row_duration = pw.span / self.rows
        self.px_per_min = (width - self.MARGIN_LEFT - self.MARGIN_RIGHT) / float(self.row_duration)
        self.step = (pw.span - pw.sweep_start) / (sweep_seconds * self.FPS)

    def _tp_at(self, cx, row):
        self.parked = False          # the user's own hand ends the wait
        t = super()._tp_at(cx, row)
        return min(max(t, self.pw.tp_start), self.pw.span)

    def _teleport(self):
        """The jump onto the definitive part -- test 12's t_p line landing at 24h.

        Until then the line stands at the origin over a schedule being planned
        whole, which is what the case is: the front crawls out of t=0 and
        everything past it goes on changing. The instant the front reaches the
        teleport instant the schedule under it will not move again, and the line
        lands there. Nothing is swept by the jump -- the line was never at a
        position in between, so no break in the first day was ever reached."""
        pw = self.pw
        if not self.parked or pw.tp_teleport is None: return False
        if pw.settled < pw.tp_teleport: return False
        self.tp, self.parked = pw.tp_teleport, False
        return True

    # How much of the timeline may settle before the shares are measured again
    # while the chain is still growing -- a fiftieth of the span, so the number
    # is seen to move about fifty times over the derivation and the measuring
    # costs a couple of seconds of the worker's time in total. Every share
    # quoted before the chain is settled is provisional anyway: it is read off a
    # timeline whose far end is still changing, which is what the panel says.
    SHARE_STEP = 50

    # ...and how often, at most, they are measured while it is still growing.
    # The line moving makes them stale, and under the sweep it moves every
    # frame -- so without a rate of their own, measuring them would take the
    # whole worker and pressing Play would stop the chain settling altogether.
    # This bounds them to about a tenth of it. Once the chain is settled the
    # worker has nothing else to do and the gate is lifted, so a position the
    # user scrubs to is measured at once.
    SHARE_EVERY_S = 0.5

    def _shares_at(self, tp):
        """The resulting shares at tp, measured over the timeline as it stands.

        This is the whole cost of a frame of this panel -- 65 ms of it against
        1 ms for every rectangle on screen -- so it is measured HERE, on the
        worker, and the frame only reads the answer."""
        pw = self.pw
        got, open_total = resulting_shares(
            pw.timeline(tp, fit=False),
            list(pw.periods) + list(pw.sliding(tp)),
            [t.name for t in pw.tasks_at(tp)], lo=frac(0), hi=pw.span)
        return (tp, pw.settled, got, open_total)

    def _stale_shares(self):
        at, have = self.at, self.shares
        if at is None: return False
        if have is None or have[0] != at: return True
        return self.pw.settled - have[1] >= self.pw.span / self.SHARE_STEP

    def _derive(self):
        """One unit of work, ON THE WORKER THREAD.

        The shares where the line has moved and they are due -- a frame is
        waiting on them. Then the two chains, which are the timeline itself
        and which the shares are only a number read off. Then the rules AT
        THE LINE, but only for a line standing still: the sweep
        crosses a regime every frame, so chasing it would spend every fit on a
        position already left behind. A position the user stops at (or clicks
        on) is derived and kept, and the frames until it lands draw the
        standing chain there instead, which is the provisional answer the case
        is allowed.

        `local_end` is asked for as well, and not as an afterthought: it is the
        second half of what a frame needs, and leaving it to the frame would put
        a plan -- a tenth of a second -- back on the drawing thread at every new
        position of the line. `ready_at` is the test both halves have to pass.
        """
        pw = self.pw
        if self.stopped: return False
        now = time.perf_counter()
        if self._stale_shares() and (pw.done or now - self.measured >= self.SHARE_EVERY_S):
            self.measured = now
            self.shares = self._shares_at(self.at)
            return True
        if not pw.done:
            pw.step()
            return True
        tp = self.want
        if tp is not None and not pw.ready_at(tp):
            pw.regime_at(tp)
            pw.local_end(tp)
            self.shares = None           # the rules at the line changed them
            return True
        return False

    # How often a chain that is GROWING is redrawn. Not every frame: a link is
    # a third of a second and covers twenty minutes of timeline, so redrawing at
    # twelve frames a second draws the same picture three times over -- and a
    # frame of this panel is a full timeline of rectangles plus the shares read
    # off it, which is main-thread work the worker is competing for. Four times
    # a second shows the front moving just as plainly and leaves the interpreter
    # to the deriving. What the USER does is not throttled by this: a scrub
    # draws from its own handler and the sweep sets `dirty` every frame.
    PROGRESS_S = 0.25

    def _work(self):
        """On the DRAWING thread: say where the line is, and take what the
        worker has published. Nothing here derives anything."""
        moved = self._teleport()
        self.at = self.tp
        self.want = None if self.playing else self.tp
        if self.deriver is None or self.deriver.generation == self.seen:
            return moved
        now = time.perf_counter()
        if now - self.shown < self.PROGRESS_S: return moved
        self.seen, self.shown = self.deriver.generation, now
        return True

    def stop(self):
        super().stop()
        if self.deriver is not None: self.deriver.stop()

    def _advance_tp(self):
        self.parked = False          # a line under way is a line the user drives
        self.tp += self.step
        if self.tp >= self.mw.span: self.tp = self.pw.sweep_start

    def _status(self):
        pw = self.pw
        settled, worked = pw.pace()
        rate = float(settled) / worked if worked else 0.0
        state = "settled" if pw.done else "still deriving"
        r = pw.rules_at(self.tp)
        rules = (f"rules at the line: exact, for t_p in {r.label}" if r is not None else
                 "rules at the line: provisional (stop the sweep to derive them)")
        # where the percentages are at the line is NOT said here: it belongs
        # beside the two states it is travelling between (`state_lines`), and
        # appended to a sentence this long it was drawn a thousand pixels off
        # the right edge of the canvas -- which is to say it was not drawn
        wait = ("" if not self.parked else
                f" (waiting at the origin: it teleports to {stamp(pw.tp_teleport)} "
                f"once the definitive part reaches it)")
        return (f"t_p = {stamp(self.tp)}{wait}   ->   definitive up to t = {stamp(pw.settled)}"
                f"   ({len(pw.segments)} links, {len(pw.regimes)} regimes, {state}, "
                f"{rate:.0f} min of timeline per second of work; {rules}, substituted "
                f"into, not recomputed)")

    def _draw(self):
        c, pw, tp = self.canvas, self.pw, self.tp
        for item in c.find_withtag(self.tag): self.tooltip.unregister(item)
        c.delete(self.tag)

        c.create_text(self.MARGIN_LEFT, self.y_status, anchor="nw", font=("Arial", 9),
                      fill="#333333", tags=self.tag, width=self.info_width,
                      text=self._status())
        seg = pw.segment_at(tp)
        c.create_text(self.MARGIN_LEFT, self.y_rules - 14, anchor="nw", font=("Courier", 8),
                      fill="#7A0000", tags=self.tag,
                      text=("Link at t_p " + (seg.label if seg else "(not settled yet)")
                            + ":  " + self._ellipsis(seg.prefix_text if seg else "")))
        c.create_text(self.MARGIN_LEFT, self.y_rules, anchor="nw", font=("Courier", 8),
                      fill="#00407A", tags=self.tag,
                      text="Cycle:  " + self._ellipsis(seg.cycle_text if seg else ""))

        # The share is read off the timeline this frame is DRAWING -- never from
        # `plan_at`/`regime_at`, which would schedule, and drawing a frame may
        # not do that. It used to be read off the rules at the line instead, and
        # fell back to their PREFIX where they had no cycle: on this case that is
        # always, so the number quoted was a one-off catch-up burst measured over
        # an environment truncated at the 6h lookahead -- 33.79% for a task the
        # timeline underneath it was giving 2%.
        drawn = pw.timeline(tp, fit=False)
        # the percentages are read AT THE LINE: where they slide (test 13) the
        # table is the statement the plan at t_p was made to satisfy, so the
        # targets the shares are being compared against are the ones in force
        tasks = pw.tasks_at(tp)
        # ...and MEASURED on the worker (`_shares_at`), because measuring them is
        # the whole cost of this frame. Until it has answered for this position
        # the table is drawn without them rather than the frame waiting: a share
        # a moment behind the line is the same provisional number the rest of
        # the panel is, and a window that stops to compute one is not.
        have = self.shares
        got = have[2] if have is not None else {}
        open_total = have[3] if have is not None else frac(0)
        # the two end states are the CASE's and were measured once; where the
        # line stands between them is this frame's, so the block is re-worded
        # here rather than reused. Its shape does not move: the count of lines
        # is the same with `at` as without it
        slines = state_lines(self.info_ends, chars=self.info_chars, at=(tp, tasks))
        draw_info(c, self.MARGIN_LEFT, self.y_info, tasks, pw.periods,
                  got, open_total, self.info_rows, self.info_periods,
                  slines, tag=self.tag, tooltip=self.tooltip)

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


def draw_progressive_case(root, canvas, tooltip, case, y_offset, window_width=900):
    """ONE derived case, at the bottom of the window.

    Singular on purpose: a progressive case settles its chain on a worker for
    minutes, so two of them shown at once are two workers competing for the
    interpreter and for the thread that draws -- and the front of each crawls
    at half the pace. Which one is on screen is the selector's business
    (`Workbench.show_slow`); the others keep what they have derived and are not
    drawn, so they derive nothing."""
    title, pw, sweep = case
    return ProgressiveCasePanel(root, canvas, tooltip, y_offset, title, pw, sweep,
                                width=window_width)


def menu_label(title, chars=70):
    """A case's one-line name, for the selector: its title up to the colon that
    ends the headline (`Test 12 (progressive rule list)`), never the paragraph
    under it."""
    head = title.splitlines()[0]
    cut = head.find("): ")
    if cut > 0: head = head[:cut + 1]
    elif ": " in head: head = head.split(": ", 1)[0]
    return head if len(head) <= chars else head[:chars - 3] + "..."


def case_tag(title):
    """The short name the bar uses when it is talking about several: `Test 12`."""
    return menu_label(title).split(" (")[0]

def print_statement(tasks, periods, shares=None, open_total=None, with_periods=True):
    """The case's own statement -- the same table the window draws, in text."""
    total = sum((t.priority for t in tasks), frac(0)) or frac(1)
    print(f"[ TASKS ] name, minimum, priority"
          + (f" -> resulting share of the {human(open_total)} some task is allowed in"
             if shares is not None else ""))
    for t in tasks:
        got = f" -> {_pct(shares.get(t.name, 0)):>8}" if shares is not None else ""
        print(f"  {t.name:<4}{human(t.min_time):>8}{_pct(t.priority / total):>8}{got}")
    if not with_periods: return          # the second state of a blend: same environment
    print(f"[ STATIC PERIODS ] {len(periods)}, by the set of tasks they do NOT allow")
    for line, _tip in period_lines(periods):
        print("  " + line)

def print_terminal_results(cases, moving_cases, progressive_cases=(), settle_progressive=True):
    for case in cases:
        title, tasks, total_duration, pre_placed, periods, options = case_parts(case)
        prefix, cycle, plan = get_schedule_rules(tasks, pre_placed, periods, **options)
        drawn = [(b['start'], b['start'] + b['duration'], b['name'])
                 for b in generate_schedule(prefix, cycle, total_duration, start=plan.start)]
        got, open_total = resulting_shares(drawn, periods, [t.name for t in tasks],
                                           lo=plan.start, hi=plan.start + total_duration)

        print(f"--- {title.splitlines()[0]} ---")
        print_statement(tasks, periods, got, open_total)
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
        print_progressive_rules(title, pw, settle=settle_progressive)

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

def print_progressive_rules(title, pw, max_links=8, settle=True):
    """The chain, printed.

    `settle` is what tells the terminal from the window. Settling the three
    days takes a couple of minutes, and the window is the one place that must
    NOT wait for it: its panel derives the chain a link at a time between
    frames, which is what lets the definitive part grow from t=0 while the
    schedule is on screen. Printing the finished chain here first would settle
    the very object the panel is about to show, and the user would open the
    window on an answer that never moves again."""
    print(f"--- {title.splitlines()[0]} ---")
    # BOTH ends, each named by the STRETCH OF t_p IT GOVERNS (test 13): a state
    # printed without that says what the percentages are and nothing about when
    # they are that, which is the half the requirement is about -- and a state
    # named by the one instant it is pinned at says a third of it, since outside
    # the transition the nearer state is held rather than left behind.
    states = pw.blend_states()
    print_statement(pw.tasks_at(states[0][0] if states else pw.tp_start), pw.periods)
    if states:
        lo, hi = states[0][0], states[-1][0]
        print(f"[ THE PERCENTAGES SLIDE ] the table above is the state HELD for "
              f"t_p <= {stamp(lo)}; it crosses to the one below between "
              f"t_p={stamp(lo)} and t_p={stamp(hi)}, and that one is then HELD "
              f"for t_p >= {stamp(hi)}:")
    for _pos, tasks in states[1:]:
        print_statement(tasks, pw.periods, with_periods=False)
    print("[ PROGRESSIVE RULE SET - a chain of links, settled from t=0 outward ]")
    if not settle:
        print("  derived in the window, a link at a time: the definitive part grows")
        print("  from t=0 while the schedule is shown. `--rules` prints it instead.")
        print()
        return
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

    def __init__(self, root, width=900, on_ready=None):
        self.root, self.width = root, width
        self.scale = 1.0
        self.panels, self.tooltip = [], None
        self.body = self.canvas = None
        self.cases = self.moving = self.progressive = None
        # The derived cases: every one of them is KEPT (the chain each has
        # settled lives in its own window object, which nothing here rebuilds),
        # and exactly one of them is on screen and therefore deriving.
        self.slow_index = None       # the user's choice, kept across rebuilds
        self.slow_panel = None       # the one panel showing it
        self.slow_y = 0              # where at the bottom of the canvas it goes
        # `on_ready` is called, once, on the main thread when the first build is
        # on screen: nothing may read `cases`/`moving`/`progressive` before that,
        # since the build no longer finishes inside this constructor.
        self.on_ready = on_ready
        self.generation = 0          # which rebuild owns the display
        self.pending = None          # the stages that build is finishing
        self.built = True
        self._y, self._t0 = 0, 0.0
        self._bar()
        # The constructor now RETURNS before the build does, so the event loop
        # is running while it runs -- which is the difference between a window
        # that shows itself working and one Windows marks Not Responding.
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
        # the slider only ever moves the NUMBER (its command writes the entry).
        # Dragging it rebuilds nothing: a rebuild runs the scheduler over every
        # case, so it happens when the user says so -- Apply, Default, or Return
        # in the entry -- and never as a side effect of looking for a value.

        tk.Button(bar, text="Apply", cursor="hand2",
                  command=lambda: self.apply(self.text.get())).pack(side=tk.LEFT)
        tk.Button(bar, text="Default", cursor="hand2",
                  command=lambda: self.apply(1.0)).pack(side=tk.LEFT, padx=4)

        self.status = tk.Label(bar, text="", fg="#555555", font=("Arial", 9))
        self.status.pack(side=tk.LEFT, padx=10)
        self._slow_bar()

    BUILDING = "(building...)"

    def _slow_bar(self):
        """The selector for the cases that are DERIVED WHILE THEY ARE SHOWN.

        Tests 12 to 14 are not built and then displayed: each settles a chain
        of links on a worker (minutes for the crowded ones), and the point of the case is watching
        the definitive part grow. Two of them growing at once is two workers
        competing for one interpreter and for the thread that draws, so each
        front crawls at half its pace and the window pays for both.

        So one is shown at a time, and only the one shown derives. Choosing
        another does not restart anything: the case being left keeps every link
        it has settled -- the chain is in its window object, which the selector
        never touches -- and the one chosen goes on from the link IT had
        reached. Coming back to a case is resuming it, not rerunning it."""
        assert tk is not None
        bar = tk.Frame(self.root, bd=1, relief="raised", padx=8, pady=4)
        bar.pack(side=tk.TOP, fill=tk.X)
        tk.Label(bar, text="derived while shown  (one at a time; the others keep "
                           "what they have settled)",
                 font=("Arial", 9, "bold")).pack(side=tk.LEFT)
        self.slow_text = tk.StringVar(value=self.BUILDING)
        self.slow_menu = tk.OptionMenu(bar, self.slow_text, self.BUILDING)
        self.slow_menu.config(width=44, anchor="w", state="disabled",
                              font=("Arial", 9), cursor="hand2")
        self.slow_menu.pack(side=tk.LEFT, padx=8)
        self.slow_status = tk.Label(bar, text="", fg="#555555", font=("Arial", 9))
        self.slow_status.pack(side=tk.LEFT)

    # ---------------- which derived case is on screen ---------------- #

    def _offer_slow(self):
        """Fill the selector with the cases the build produced."""
        menu = self.slow_menu["menu"]
        menu.delete(0, "end")
        for i, (title, _pw, _sweep) in enumerate(self.progressive or ()):
            menu.add_command(label=menu_label(title),
                             command=lambda i=i: self.show_slow(i, reveal=True))
        self.slow_menu.config(state="normal" if self.progressive else "disabled")

    def show_slow(self, index, reveal=False):
        """Put one derived case at the bottom of the window, and only it.

        The panel is what is swapped; the case is not. Destroying the panel
        stops its worker after the unit it is in and leaves its window holding
        every link settled so far, so the same case put back on screen carries
        on from there -- which is why this is a display change and never a
        derivation thrown away."""
        if not self.progressive: return
        index = max(0, min(index, len(self.progressive) - 1))
        # choosing the case already on screen is not a reason to throw its panel
        # away: the chain would survive it, but the position of the line would not
        if index == self.slow_index and self.slow_panel is not None: return
        self.slow_index = index
        case = self.progressive[index]
        self.slow_text.set(menu_label(case[0]))
        if self.slow_panel is not None:
            self.slow_panel.destroy()       # its chain stays in its window
            self.slow_panel = None
        self.slow_panel = draw_progressive_case(self.root, self.canvas, self.tooltip,
                                                case, self.slow_y, window_width=self.width)
        refresh_scrollregion(self.canvas)
        # A case the USER asked for is one they want to look at, and it is at the
        # bottom of a canvas several screens long. The build's own first call
        # does not move the view: there the scroll offset being restored is the
        # position the user was reading before the rebuild.
        if reveal: self._scroll_to(self.slow_y)
        self._say_slow()

    def _scroll_to(self, y):
        self.canvas.update_idletasks()          # the new scrollregion is in force
        _x1, _y1, _x2, y2 = self.canvas.bbox("all") or (0, 0, 0, 0)
        total = y2 + SCROLL_PADDING
        if total > 0: self.canvas.yview_moveto(max(0.0, y - 12) / total)

    def _say_slow(self):
        """What the cases that are NOT on screen are holding, as they were left.

        A snapshot, not a reading: a case nobody is showing derives nothing, so
        the front quoted here is exactly where it will resume from."""
        kept = ", ".join(
            f"{case_tag(title)} "
            + (f"definitive to {stamp(pw.settled)}" if pw.settled else "not started")
            for i, (title, pw, _sweep) in enumerate(self.progressive or ())
            if i != self.slow_index)
        self.slow_status.config(
            text=f"not shown, so not deriving: {kept}   (it resumes there when chosen)"
                 if kept else "")

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

    POLL_MS = 30

    def rebuild(self):
        """Start a build, and draw its stages as they land.

        A stage costs what it costs -- fitting tests 10-11's regimes is seconds
        of scheduling -- and seconds spent on the thread that draws are seconds
        the window is not a window. So the scheduling runs on a worker. Tk is
        not thread-safe, so the worker only ever BUILDS: the widgets of a stage
        are made here, by the loop that owns them, the moment that stage
        arrives. The display fills in from the top while the rest is still
        being scheduled, and the bar says which stage is running.

        The queue is handed to the worker rather than read off `self`: a
        rebuild the user triggers while one is running must leave the old
        worker writing into a queue nobody reads, not into the new one.
        """
        self.generation += 1
        gen = self.generation
        offset = self._scroll_offset()
        self._teardown()
        self._make_body()
        # the derived cases are a function of tau like everything else, so the
        # ones on record are gone until the build hands over new ones: the
        # selector must not offer a case whose panel it could no longer draw
        self.progressive = None
        self.slow_menu.config(state="disabled")
        self.slow_text.set(self.BUILDING)
        self.slow_status.config(text="")
        self.pending = queue.Queue()
        self.built = False
        self._y, self._t0 = 0, time.perf_counter()
        self._say("scheduling tests 1-10...")
        share_the_interpreter()
        threading.Thread(target=self._build, args=(gen, self.scale, self.pending),
                         daemon=True).start()
        self._collect(gen, offset, self.pending)

    def _build(self, gen, scale, out):
        """The whole build, off the drawing thread; one message per stage."""
        try:
            out.put((gen, "cases", build_cases(scale)))
            out.put((gen, "moving", build_moving_cases(scale)))
            out.put((gen, "progressive", build_progressive_cases(scale)))
        except Exception as exc:                 # a build that dies says so in
            traceback.print_exc()                # the bar rather than leaving
            out.put((gen, "error", exc))         # the window filling for ever

    def _collect(self, gen, offset, box):
        """Draw whatever the builder has finished. Main thread only."""
        if gen != self.generation: return        # a newer rebuild owns the display
        while True:
            try:
                _g, stage, payload = box.get_nowait()
            except queue.Empty:
                break
            self._draw_stage(stage, payload, offset)
        if not self.built:
            self.root.after(self.POLL_MS, lambda: self._collect(gen, offset, box))

    def _draw_stage(self, stage, payload, offset):
        if stage == "error":
            self.built = True
            self._say(f"the build failed: {payload}", "#B00000")
            return
        if stage == "cases":
            self.cases = payload
            self._y = draw_schedules(self.root, self.canvas, payload,
                                     window_width=self.width)
            refresh_scrollregion(self.canvas)
            self._say("fitting the dynamic rule lists of tests 10-11...")
        elif stage == "moving":
            self.moving = payload
            load_colors(payload)
            self.tooltip = ToolTip(self.canvas)
            self._y = draw_moving_cases(self.root, self.canvas, self.tooltip, payload,
                                        self._y, window_width=self.width,
                                        panels=self.panels)
            refresh_scrollregion(self.canvas)
            self._say("opening test 12's chain (it is derived in its panel, live)...")
        else:
            self.progressive = payload
            load_colors(payload)
            self.slow_y = self._y
            self._offer_slow()
            # the LAST of them by default -- and the user's own choice instead,
            # where they have made one: a rebuild is a new tau, not a new mind.
            self.show_slow(len(payload) - 1 if self.slow_index is None
                           else self.slow_index)
            self._restore_scroll(offset)
            self.built = True
            self._say(f"{len(self.cases) + len(self.moving) + len(payload)} cases "
                      f"rebuilt in {time.perf_counter() - self._t0:.1f}s "
                      f"(the derived ones one at a time, from the bar above)")
            if self.on_ready is not None:
                ready, self.on_ready = self.on_ready, None
                ready(self)

    def _scroll_offset(self):
        """Where the viewport's top edge sits, in canvas pixels.

        Kept in PIXELS rather than as the scrollbar's fraction: a rebuild can
        change the total height a little (a regime more or fewer), and a
        fraction of a different total lands somewhere else. The case the user
        was reading stays under the cursor."""
        if self.canvas is None: return 0.0
        return self.canvas.canvasy(0)

    def _restore_scroll(self, offset):
        if not offset: return
        self.canvas.update_idletasks()          # the new scrollregion is in force
        _x1, _y1, _x2, y2 = self.canvas.bbox("all") or (0, 0, 0, 0)
        total = y2 + SCROLL_PADDING
        if total > 0: self.canvas.yview_moveto(offset / total)

    def _teardown(self):
        for panel in self.panels: panel.stop()
        self.panels = []
        # not in `panels`: this one is swapped by the selector, not only by a
        # rebuild, so it is held on its own
        if self.slow_panel is not None: self.slow_panel.stop()
        self.slow_panel = None
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

    ui = not no_ui and tk is not None
    if ui:
        # Nothing is built before the window exists: tests 10-11 fit their
        # regimes in their constructor, and that alone is seconds the user
        # would spend looking at no window at all.
        print("--- the window ---")
        print("  it opens now, empty, and fills itself in from the top: the scheduling")
        print("  runs on worker threads, so the window answers throughout. The cases")
        print("  that DERIVE while they are shown (tests 12 to 14) are chosen one at a")
        print("  time from the bar at the top and drawn at the bottom -- the last of")
        print("  them by default. Scrolling down to it without pressing play shows the")
        print("  far end still changing while the definitive part grows from t=0.")
        print("  Choosing another leaves the first holding every link it settled: only")
        print("  the case on screen derives, and a case chosen again resumes there.")
        print("  The rules are printed here once the build is on screen.")
        print("  The checks are `uv run tests_displayer.py --verify`.\n", flush=True)

        root = tk.Tk()
        root.title("Task Scheduler Timeline with Constraints (including the dynamic rule list)")
        root.geometry("950x700")
        # The statement and the rules, off the objects the window is showing --
        # printed WITHOUT settling test 12, which is its panel's job to do live.
        # It waits for the build because the build no longer waits for anything:
        # `Workbench` returns with the window empty and fills it from a worker,
        # so there is nothing to print until it says there is.
        def ready(bench):
            print_terminal_results(bench.cases, bench.moving, bench.progressive,
                                   settle_progressive=False)

        Workbench(root, width=900, on_ready=ready)
        root.mainloop()
        return

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

    # The checks SETTLE test 12 -- `verify_progressive` calls `pw.settle()`, two
    # minutes of work -- so they belong to the verification run, not the display
    # run: doing them first would both delay the window by those two minutes and
    # hand it a chain with nothing left to derive, when the whole point of the
    # case is that the definitive schedule grows from t=0 while it is shown.
    #
    # what the rules MEAN (self-consistency), then whether they are still the
    # same rules the project agreed on
    failures = verify_moving(moving)
    failures += verify_progressive(progressive)
    failures += check_rules_snapshot(cases, moving, progressive)

    if no_ui:
        raise SystemExit(1 if failures else 0)
    print("tkinter is not available: skipping the UI (rules and checks above are unaffected).")

if __name__ == "__main__":
    main()
