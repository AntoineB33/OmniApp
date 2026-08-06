#!/usr/bin/env python3
"""
Tk timeline and test cases for the cyclic proportional-share scheduler.
Adapted to test the test2.py algebraic SchedulerEngine (Test 10).
"""

import tkinter as tk
from math import inf

# Import the SchedulerEngine from test2.py
from test2 import TP_DURATION, SchedulerEngine

# --------------------------------------------------------------------------- #
#  Helpers (replacing missing scheduler imports)
# --------------------------------------------------------------------------- #

def stamp(seconds: float) -> str:
    """Format seconds into MM:SS"""
    m = int(seconds // 60)
    s = int(seconds % 60)
    return f"{m:02d}:{s:02d}"

def human(seconds: float) -> str:
    """Format seconds into readable duration"""
    if seconds == inf:
        return "inf"
    m = int(seconds // 60)
    s = int(seconds % 60)
    if m > 0 and s > 0:
        return f"{m}m {s}s"
    elif m > 0:
        return f"{m}m"
    return f"{s}s"

def generate_schedule(prefix, cycle, total_duration, start=0):
    """Concretizes prefix and cycle blocks into a linear timeline."""
    schedule = []
    t = start
    
    # Process Prefix
    for b in prefix:
        if t >= total_duration: break
        dur = min(b['duration'], total_duration - t)
        if dur > 0:
            schedule.append({
                'name': b['name'], 
                'duration': dur, 
                'color': b['color'], 
                'start': t
            })
            t += dur
            
    if not cycle:
        return schedule
        
    # Process Cycle until horizon
    while t < total_duration:
        for b in cycle:
            if t >= total_duration: break
            dur = min(b['duration'], total_duration - t)
            if dur > 0:
                schedule.append({
                    'name': b['name'], 
                    'duration': dur, 
                    'color': b['color'], 
                    'start': t
                })
                t += dur
    return schedule

def task_color(task_name):
    if task_name == 'A': return "#FF9999"
    if task_name == 'B': return "#99CCFF"
    return "#CCCCCC" # IDLE or other

# --------------------------------------------------------------------------- #
#  presentation
# --------------------------------------------------------------------------- #

class Test2MovingWindow:
    """Adapter to wrap test2.py SchedulerEngine for the UI."""
    def __init__(self, span_seconds):
        self.span = span_seconds
        self.width = TP_DURATION
        self.allowed = ["A"]

    def blocks_at(self, tp):
        engine = SchedulerEngine(tp=tp)
        prefix_raw, cycle_raw = engine.find_schedule()
        
        prefix = [{'name': b.task, 'duration': b.duration, 'color': task_color(b.task)} for b in prefix_raw]
        cycle = [{'name': b.task, 'duration': b.duration, 'color': task_color(b.task)} for b in cycle_raw]
        
        return prefix, cycle


class MovingWindowPanel:
    """A timeline whose one period slides, redrawn from the dynamic rules."""

    ROW_H = 44
    FPS = 25
    BUTTON_H = 45          
    TITLE_FONT = ("Arial", 10, "bold")   

    def __init__(self, parent, x, y, width, title, mw, sweep_seconds=25, start=0):
        self.mw = mw
        self.title = title
        self.t = float(start)
        self.step_size = mw.span / (sweep_seconds * self.FPS)
        self.margin = 12
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
        
        prefix, cycle = self.mw.blocks_at(self.t)
        prefix_txt = ", ".join(f"{b['name']} {b['duration']:.1f}s" for b in prefix)
        cycle_txt = ", ".join(f"{b['name']} {b['duration']:.1f}s" for b in cycle)
        
        c.clipboard_append("\n".join([
            self.title,
            "",
            f"Period now at {stamp(self.t)}",
            f"Prefix: {prefix_txt}",
            f"Cycle: {cycle_txt}"
        ]))

    def _measure_header(self):
        c = self.canvas
        probe = c.create_text(self.gutter, 4, text=self.title, anchor="nw", font=self.TITLE_FONT)
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
            self.t = 0.0
        self.canvas.after(int(1000 / self.FPS), self._tick)

    def _draw(self):
        c = self.canvas
        c.delete("frame")
        
        prefix, cycle = self.mw.blocks_at(self.t)
        schedule = generate_schedule(prefix, cycle, self.mw.span)

        c.create_text(self.gutter, 4, text=self.title, anchor="nw",
                      font=self.TITLE_FONT, tags="frame")
        c.create_text(self.gutter, self.y_rules, anchor="nw", font=("Arial", 9),
                      fill="#333333", tags="frame",
                      text=f"tp window at {stamp(self.t)}   ->   [{stamp(self.t)}, {stamp(self.t + self.mw.width)})")
        
        # Summarize blocks for UI text
        prefix_txt = ", ".join(f"{b['name']} {b['duration']:.1f}s" for b in prefix)
        cycle_txt = ", ".join(f"{b['name']} {b['duration']:.1f}s" for b in cycle)
        
        c.create_text(self.gutter, self.y_prefix, anchor="nw",
                      font=("Courier", 9), fill="#7A0000", tags="frame",
                      text="Prefix: " + (prefix_txt if len(prefix_txt) < 100 else prefix_txt[:97] + "..."))
        c.create_text(self.gutter, self.y_cycle, anchor="nw",
                      font=("Courier", 9), fill="#00407A", tags="frame",
                      text="Cycle:  " + cycle_txt)

        top = self.top
        for block in schedule:
            x1, x2 = self._x(block['start']), self._x(block['start'] + block['duration'])
            x2 = min(x2, self._x(self.mw.span))
            if x2 <= x1:
                continue
            c.create_rectangle(x1, top, x2, top + self.ROW_H,
                               fill=block['color'], outline="black", tags="frame")
            if x2 - x1 > 26:
                c.create_text((x1 + x2) / 2, top + self.ROW_H / 2,
                              text=block['name'], font=("Arial", 9), tags="frame")

        wx1, wx2 = self._x(self.t), self._x(self.t + self.mw.width)
        c.create_rectangle(wx1, top - 7, max(wx2, wx1 + 2), top + self.ROW_H + 7,
                           fill="#222222", outline="#222222", tags="frame")
        c.create_text(min(wx1 + 6, self.width - 90), top + self.ROW_H + 10,
                      anchor="nw", font=("Arial", 8), fill="#222222", tags="frame",
                      text=f"{human(self.mw.width)}, only {'/'.join(self.mw.allowed)}")


def build_moving_cases():
    return [
        (
            ("Test 10: Algebraic sliding block (A 50% 10min, B 50% 10min) with 20s restrict window.\n"
             "-> Integrated directly with test2.py SchedulerEngine O(1) loop detection."),
            Test2MovingWindow(span_seconds=1800), # Show 30 mins to easily see cycles
            200, # sweep_seconds (how fast it moves across the screen)
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

def main():
    root = tk.Tk()
    root.title("Task Scheduler Timeline with Constraints (test2.py Integration)")
    root.geometry("1000x400")

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

    # Note: Static cases removed to focus purely on test2.py dynamic integration (Test 10)
    y_offset = 20
    y_offset, _panels = draw_moving_windows(canvas, build_moving_cases(), y_offset,
                                            window_width=960)
    canvas.config(scrollregion=(0, 0, 960, y_offset + 50))
    root.mainloop()

if __name__ == "__main__":
    main()