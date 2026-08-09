#!/usr/bin/env python3
"""
tests_displayer.py
GUI tools, CLI runner, and visual logic for displaying scheduler tests.

The moving period of tests 10 and 11 is moved *here*: the displayer advances
t_p every frame and reads the timeline off the rule list the scheduler derived
once. No scheduling happens while the period slides.
"""

import sys
from fractions import Fraction

try:
    import tkinter as tk
except ImportError:
    tk = None

from scheduler_logic import (
    IDLE,
    IDLE_COLOR,
    MAX_RULES,
    Placement,
    Scheduler,
    as_blocks,
    frac,
    human,
    rule_lines,
    stamp,
)
from test_configs import (
    build_cases,
    build_moving_cases,
    timeline_of,
    verify_moving,
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
            self.canvas.bind_all(seq, lambda e: self.canvas.after_idle(self.refresh), add="+")

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

def get_schedule_rules(tasks, pre_placed=None, periods=None, t_now=0, **kw):
    timeline = [Placement(p['name'], frac(p['start']), frac(p['start']) + frac(p['duration']), p.get('color', '#CCCCCC'))
                for p in (pre_placed or [])]
    plan = Scheduler(tasks, **kw).plan(timeline=timeline, periods=periods or [], t_now=t_now, max_rules=MAX_RULES)
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
        title = case[0]
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

    refresh_scrollregion(canvas)
    return y_offset


class MovingCasePanel:
    """A timeline whose one period slides, redrawn from the dynamic rules.

    Every frame advances t_p and evaluates the rule list at the new position:
    a binary search over the regimes and some arithmetic. The scheduler is not
    run again -- it ran once, when the rule list was built."""

    ROW_H = 40
    ROW_SPACING = 20
    FPS = 20
    MARGIN_LEFT = 90
    MARGIN_RIGHT = 30

    def __init__(self, root, canvas, tooltip, y, title, mw, sweep_seconds, width=900):
        self.root, self.canvas, self.tooltip, self.mw = root, canvas, tooltip, mw
        self.title = title
        self.tag = f"moving{id(self)}"
        self.tp = frac(0)
        self.playing = True
        # the displayed timeline is the case's span, whatever the window is
        # sized to: only the scale changes with the screen, never the timeline
        self.row_duration = mw.span
        self.px_per_min = (width - self.MARGIN_LEFT - self.MARGIN_RIGHT) / float(mw.span)
        self.rows = 1
        self.step = mw.span / (sweep_seconds * self.FPS)

        self.btn_copy = tk.Button(canvas, text="Copy\nRules", cursor="hand2",
                                  command=lambda: copy_dynamic_rules(root, title, mw))
        canvas.create_window(15, y, window=self.btn_copy, anchor="nw")
        self.btn_play = tk.Button(canvas, text="Pause", width=6, cursor="hand2",
                                  command=self._toggle)
        canvas.create_window(15, y + 48, window=self.btn_play, anchor="nw")

        head = canvas.create_text(self.MARGIN_LEFT, y, text=title, font=("Arial", 11, "bold"), anchor="nw")
        self.y_status = canvas.bbox(head)[3] + 2
        self.y_rules = self.y_status + 30
        self.top = self.y_rules + 30
        self.height = (self.top + self.rows * (self.ROW_H + self.ROW_SPACING) + 30) - y
        self._tick()

    def _toggle(self):
        self.playing = not self.playing
        self.btn_play.config(text="Pause" if self.playing else "Play")

    def _x(self, minutes):
        return self.MARGIN_LEFT + float(minutes) * self.px_per_min

    def _row_y(self, row_idx):
        return self.top + row_idx * (self.ROW_H + self.ROW_SPACING)

    def _tick(self):
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

        for start, end, name in timeline_of(mw, tp):
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


def draw_moving_cases(root, canvas, tooltip, cases, y_offset, window_width=900):
    for title, mw, sweep in cases:
        panel = MovingCasePanel(root, canvas, tooltip, y_offset, title, mw, sweep,
                                width=window_width)
        y_offset += panel.height + 30
    return y_offset


def print_terminal_results(cases, moving_cases):
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

    for title, mw, _sweep in moving_cases:
        print_moving_rules(title, mw)

def print_moving_rules(title, mw, max_regimes=12):
    print(f"--- {title.splitlines()[0]} ---")
    print("[ DYNAMIC RULE SET - this is the output; the display substitutes t_p into it ]")
    for line in mw.lines()[:4 + 3 * max_regimes]: print("  " + line)
    if len(mw.regimes) > max_regimes:
        print(f"  ... {len(mw.regimes) - max_regimes} more regimes "
              f"(the Copy Rules button gives all of them)")
    print()


def main():
    verify_only = "--verify" in sys.argv
    rules_only = "--rules" in sys.argv
    no_ui = verify_only or rules_only or "--no-ui" in sys.argv

    cases = build_cases()
    moving = build_moving_cases()
    for _t, mw, _s in moving:
        TASK_COLORS.update(mw.sched.color)
        TASK_COLORS.setdefault(IDLE, IDLE_COLOR)
        TASK_COLORS.setdefault("MAINTENANCE", "#CCCCCC")

    if rules_only:
        for title, mw, _sweep in moving:
            print_moving_rules(title, mw, max_regimes=len(mw.regimes))
        return
    if not verify_only:
        print_terminal_results(cases, moving)

    failures = verify_moving(moving)

    if no_ui:
        raise SystemExit(1 if failures else 0)
    if tk is None:
        print("tkinter is not available: skipping the UI (rules and checks above are unaffected).")
        return

    root = tk.Tk()
    root.title("Task Scheduler Timeline with Constraints (including the dynamic rule list)")
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

    y = draw_schedules(root, canvas, cases, window_width=900)
    tooltip = ToolTip(canvas)
    draw_moving_cases(root, canvas, tooltip, moving, y, window_width=900)

    refresh_scrollregion(canvas)
    root.mainloop()

if __name__ == "__main__":
    main()
