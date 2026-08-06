#!/usr/bin/env python3
"""
Tk timeline and test cases for the cyclic proportional-share scheduler.
"""

import sys
import tkinter as tk
from fractions import Fraction
from math import inf

# Import scheduler data models and core functions
from scheduler import (
    MAX_RULES,
    MovingWindow,
    Placement,
    Scheduler,
    Task,
    frac,
    generate_schedule,
    human,
    stamp,
)

# Import the algebraic SchedulerEngine from test2.py
from test2 import TP_DURATION, SchedulerEngine

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


def get_schedule_rules(tasks, pre_placed=None, periods=None, t_now=0, **kw):
    """Finite rule list: (prefix_blocks, cycle_blocks). Caps at MAX_RULES."""
    timeline = [Placement(p['name'], frac(p['start']),
                          frac(p['start']) + frac(p['duration']),
                          p.get('color', '#CCCCCC'))
                for p in (pre_placed or [])]
    plan = Scheduler(tasks, **kw).plan(timeline=timeline, periods=periods or [],
                                       t_now=t_now, max_rules=MAX_RULES)
    as_blocks = lambda slots: [{'name': s.task, 'duration': s.duration,
                                'color': s.color} for s in slots]
    return as_blocks(plan.prefix), as_blocks(plan.cycle), plan


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
        if plan:
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

    for case in test_cases:
        title, tasks, total_duration, pre_placed, periods = case[:5]
        options = case[5] if len(case) > 5 else {}
        px = case[6] if len(case) > 6 else px_per_min
        row_duration = (window_width - margin_left - margin_right) // px

        prefix_blocks, cycle_blocks, plan = get_schedule_rules(
            tasks, pre_placed, periods, **options)
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

                x1 = margin_left + float(start_in_row) * px
                x2 = margin_left + float(start_in_row + time_in_row) * px
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

    return y_offset


class MovingWindowPanel:
    """A timeline whose one period slides, redrawn from the dynamic rules."""

    ROW_H = 44
    FPS = 25
    BUTTON_H = 45          # the two-line button, so a one-line title still
    TITLE_FONT = ("Arial", 10, "bold")   # leaves the gutter room for it

    def __init__(self, parent, x, y, width, title, mw, sweep_seconds=25,
                 start=0):
        self.mw = mw
        self.title = title
        self.t = frac(start)
        self.step_size = mw.span / (sweep_seconds * self.FPS)
        self.margin = 12
        # the same left button column the static cases use, so "Copy Rules" is
        # in the one place the eye already looks for it
        self.gutter = 75
        self.px = float(width - self.gutter - self.margin) / float(mw.span)
        self.width = width
        self.canvas = tk.Canvas(parent, width=width, height=self.ROW_H + 108,
                                bg="white", highlightthickness=0)
        parent.create_window(x, y, window=self.canvas, anchor="nw")
        self._measure_header()
        self.canvas.configure(height=self.height)
        self.button = tk.Button(self.canvas, text="Copy\nRules", cursor="hand2",
                                command=self._copy_rules)
        self.canvas.create_window(0, 4, window=self.button, anchor="nw")
        self._tick()

    def _copy_rules(self):
        c = self.canvas
        c.clipboard_clear()
        c.clipboard_append("\n".join([
            self.title,
            "",
            (f"Rules ({human(self.mw.width)} period accepting only "
             f"{'/'.join(self.mw.allowed)}; each duration is affine in the "
             f"period's position t):"),
            self.mw.rules_text(),
            "",
            (f"Period now at {stamp(self.t)} -> "
             f"{self.mw.regime_at(self.t).label}"),
        ]))

    def _measure_header(self):
        c = self.canvas
        probe = c.create_text(self.gutter, 4, text=self.title, anchor="nw",
                              font=self.TITLE_FONT)
        title_bottom = c.bbox(probe)[3]
        c.delete(probe)
        self.y_rules = title_bottom + 6
        self.y_prefix = self.y_rules + 18
        self.y_cycle = self.y_prefix + 16
        self.top = max(self.y_cycle + 24, self.BUTTON_H + 12)
        self.height = self.top + self.ROW_H + 26

    def _x(self, t):
        return self.gutter + float(t) * self.px

    def _tick(self):
        self._draw()
        self.t += self.step_size
        if self.t + self.mw.width >= self.mw.span:
            self.t = frac(0)
        self.canvas.after(int(1000 / self.FPS), self._tick)

    def _draw(self):
        c = self.canvas
        c.delete("frame")
        regime = self.mw.regime_at(self.t)
        prefix, cycle = self.mw.blocks_at(self.t)
        schedule = generate_schedule(prefix, cycle, self.mw.span)

        c.create_text(self.gutter, 4, text=self.title, anchor="nw",
                      font=self.TITLE_FONT, tags="frame")
        c.create_text(self.gutter, self.y_rules, anchor="nw", font=("Arial", 9),
                      fill="#333333", tags="frame",
                      text=f"period at {stamp(self.t)}   ->   rules for "
                           f"[{stamp(regime.lo)}, {stamp(regime.hi)})")
        
        # Truncate text if it gets too long
        p_text = regime.prefix_text
        if len(p_text) > 130: p_text = p_text[:127] + "..."
        c_text = regime.cycle_text
        if len(c_text) > 130: c_text = c_text[:127] + "..."

        c.create_text(self.gutter, self.y_prefix, anchor="nw",
                      font=("Courier", 9), fill="#7A0000", tags="frame",
                      text="Prefix: " + p_text)
        c.create_text(self.gutter, self.y_cycle, anchor="nw",
                      font=("Courier", 9), fill="#00407A", tags="frame",
                      text="Cycle:  " + c_text)

        top = self.top
        for block in schedule:
            x1, x2 = self._x(block['start']), self._x(block['start']
                                                      + block['duration'])
            x2 = min(x2, self._x(self.mw.span))
            if x2 <= x1:
                continue
            c.create_rectangle(x1, top, x2, top + self.ROW_H,
                               fill=block['color'], outline="black",
                               tags="frame")
            if x2 - x1 > 26:
                c.create_text((x1 + x2) / 2, top + self.ROW_H / 2,
                              text=block['name'], font=("Arial", 9),
                              tags="frame")

        wx1, wx2 = self._x(self.t), self._x(self.t + self.mw.width)
        c.create_rectangle(wx1, top - 7, max(wx2, wx1 + 2), top + self.ROW_H + 7,
                           fill="#222222", outline="#222222", tags="frame")
        c.create_text(min(wx1 + 6, self.width - 90), top + self.ROW_H + 10,
                      anchor="nw", font=("Arial", 8), fill="#222222",
                      tags="frame",
                      text=f"{human(self.mw.width)}, only "
                           f"{'/'.join(self.mw.allowed)}")


# --------------------------------------------------------------------------- #
#  Test Data Models & Adapter
# --------------------------------------------------------------------------- #

AB = lambda: [Task("A", priority=50, min_time=10, color="#FF9999"),
              Task("B", priority=50, min_time=10, color="#99CCFF")]


class Test2MovingWindowAdapter:
    """Duck-types a MovingWindow so MovingWindowPanel can render test2.py results"""
    
    class DummyRegime:
        def __init__(self, prefix, cycle, tp):
            self.lo = float(tp)
            self.hi = float(tp) + 1.0 # arbitrary display bounds
            self.label = "Algebraic Output (test2.py)"
            self.prefix_text = ", ".join(f"{b['name']} {b['duration']:.1f}s" for b in prefix)
            self.cycle_text = ", ".join(f"{b['name']} {b['duration']:.1f}s" for b in cycle)
            
    def __init__(self, span_seconds):
        self.span = span_seconds
        self.width = TP_DURATION
        self.allowed = ["A"]

    def _map_color(self, task_name):
        return "#FF9999" if task_name == 'A' else "#99CCFF" if task_name == 'B' else "#CCCCCC"

    def blocks_at(self, tp):
        engine = SchedulerEngine(tp=float(tp))
        prefix_raw, cycle_raw = engine.find_schedule()
        
        prefix = [{'name': b.task, 'duration': b.duration, 'color': self._map_color(b.task)} for b in prefix_raw]
        cycle = [{'name': b.task, 'duration': b.duration, 'color': self._map_color(b.task)} for b in cycle_raw]
        
        return prefix, cycle

    def regime_at(self, tp):
        p, c = self.blocks_at(tp)
        return self.DummyRegime(p, c, tp)
        
    def rules_text(self):
        return "Generated continuously via test2.py O(1) state hash engine."


def build_cases():
    return [
        (
            (
                "Test 1: Normal 50/50 Split (10min each)\n"
                "-> Pure periodic cycle, no prefix."
            ),
            AB(), 180, [], []
        ),
        (
            (
                "Test 2: Pre-placed event owned by nobody\n"
                "-> MAINTENANCE excludes everybody equally, so it creates no "
                "field: they simply resume alternating."
            ),
            AB(), 240,
            [{'name': 'MAINTENANCE', 'start': 40, 'duration': 60, 'color': '#CCCCCC'}],
            []
        ),
        (
            (
                "Test 3: Periods constraint\n"
                "-> C is banned from t=105 on, forever: it is abundantly "
                "present just before the door closes, then A and B share the "
                "timeline."
            ),
            [
                Task("A", priority=40, min_time=10, color="#FF9999"),
                Task("B", priority=40, min_time=10, color="#99CCFF"),
                Task("C", priority=20, min_time=10, color="#99FF99")
            ],
            300, [],
            [{'start': 0, 'end': 105, 'allowed': ['A', 'B', 'C']},
             {'start': 105, 'end': inf, 'allowed': ['A', 'B']}]
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
            400, [], []
        ),
        (
            (
                "Test 5: Lopsided priorities (A 90% / B 10%) + a B block at the "
                "start\n-> A gets a denser, bounded catch-up around it, not the "
                "full 396min it is owed."
            ),
            [
                Task("A", priority=90, min_time=10, color="#FF9999"),
                Task("B", priority=10, min_time=10, color="#99CCFF")
            ],
            600,
            [{'name': 'B', 'start': 0, 'duration': 40, 'color': "#99CCFF"}],
            []
        ),
        (
            (
                "Test 6: 1h block of A at t=100 (tau = 20min)\n"
                "-> B's slots swell as the block approaches and shrink back "
                "after it: exponential decay of the influence, both sides."
            ),
            AB(), 400,
            [{'name': 'A', 'start': 100, 'duration': 60, 'color': "#FF9999"}],
            []
        ),
        (
            (
                "Test 7: 10h block of A at t=100 - 10x longer than test 6\n"
                "-> B's presence around it is wider and denser, but only a few "
                "times bigger: log, not proportional."
            ),
            AB(), 1000,
            [{'name': 'A', 'start': 100, 'duration': 600, 'color': "#FF9999"}],
            [], {}, 2
        ),
        (
            (
                "Test 8: B banned from t=100 to t=400 - a window, not a block\n"
                "-> same field, same ramps: B swells before the ban and right "
                "after it re-opens, then decays back to the cycle."
            ),
            AB(), 700, [],
            [{'start': 0, 'end': 100, 'allowed': ['A', 'B']},
             {'start': 100, 'end': 400, 'allowed': ['A']},
             {'start': 400, 'end': inf, 'allowed': ['A', 'B']}],
            {}, 2
        ),
        (
            (
                "Test 9: same 300min ban, but split into ten consecutive "
                "windows\n-> merged into one exclusion: ten short bans in a row "
                "are one long ban, not ten small ones."
            ),
            AB(), 700, [],
            [{'start': 0, 'end': 100, 'allowed': ['A', 'B']}]
            + [{'start': 100 + 30 * i, 'end': 130 + 30 * i, 'allowed': ['A']}
               for i in range(10)]
            + [{'start': 400, 'end': inf, 'allowed': ['A', 'B']}],
            {}, 2
        ),
    ]


def build_moving_cases():
    return [
        (
            ("Test 10 (test2.py Integration): a 20s period accepting only A, sliding\n"
             "-> Uses the algebraic SchedulerEngine (O(1) continuous resolution)"),
            Test2MovingWindowAdapter(span_seconds=1500),
            60,
        ),
        (
            ("Test 11: the same sliding 20s period, three tasks "
             "(A 50%, B 30%, C 20%, 10min each)\n"
             "-> the rule list is derived the same way, and certified against "
             "the scheduler regime by regime."),
            MovingWindow([Task("A", priority=50, min_time=10, color="#FF9999"),
                          Task("B", priority=30, min_time=10, color="#99CCFF"),
                          Task("C", priority=20, min_time=10, color="#99FF99")],
                         width=Fraction(1, 3), allowed=["A"], span=100),
            35,
        ),
    ]


def draw_moving_windows(canvas, cases, y_offset, window_width=900):
    panels = []
    for title, mw, sweep in cases:
        panel = MovingWindowPanel(canvas, 15, y_offset, window_width - 60,
                                  title, mw, sweep_seconds=sweep)
        panels.append(panel)
        y_offset += panel.height + 26
    return y_offset, panels


def verify(samples=600):
    """`uv run test.py --verify`"""
    ok = True
    for title, mw, _ in build_moving_cases():
        if isinstance(mw, Test2MovingWindowAdapter): continue # skip verify for adapter
        print(title.splitlines()[0])
        for r in mw.regimes:
            print(f"  {r.label:>24}  prefix: {r.prefix_text}")
            print(f"  {'':>26}cycle : {r.cycle_text}")

        horizon = 2 * mw.span
        bad = []
        for i in range(samples):
            t = mw.span * Fraction(i, samples)
            prefix, cycle = mw.blocks_at(t)
            plan = mw.plan_at(t)
            got = generate_schedule(prefix, cycle, horizon)
            want = generate_schedule(
                [{'name': s.task, 'duration': s.duration, 'color': s.color}
                 for s in plan.prefix],
                [{'name': s.task, 'duration': s.duration, 'color': s.color}
                 for s in plan.cycle], horizon)
            if [(b['name'], b['start'], b['duration']) for b in got] != \
               [(b['name'], b['start'], b['duration']) for b in want]:
                bad.append(t)
        ok &= not bad
        print(f"  -> {samples - len(bad)}/{samples} positions reproduce the "
              f"scheduler exactly"
              + ("" if not bad else f"   MISMATCH at {[stamp(x) for x in bad[:5]]}")
              + "\n")
    print("OK" if ok else "FAILED")
    return 0 if ok else 1


def main():
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

    y = draw_schedules(root, canvas, build_cases(), window_width=900)
    y, _panels = draw_moving_windows(canvas, build_moving_cases(), y,
                                     window_width=900)
    canvas.config(scrollregion=(0, 0, 900, y))
    root.mainloop()


if __name__ == "__main__":
    if "--verify" in sys.argv:
        raise SystemExit(verify())
    main()