#!/usr/bin/env python3
"""
test_displayer.py
GUI tools, CLI runner, and visual logic for displaying scheduler tests.
"""

import sys
from fractions import Fraction

try:
    import tkinter as tk
except ImportError:
    tk = None

from scheduler_logic import (
    MAX_RULES,
    MovingWindowPlan,
    Placement,
    Scheduler,
    flatten,
    frac,
    human,
    rule_lines,
    stamp,
)
from test_configs import build_cases, get_test_10, verify_test10


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

def copy_symbolic_rules(root, title, plan):
    root.clipboard_clear()
    root.clipboard_append("\n".join([title, ""] + plan.lines()))

SCROLL_PADDING = 20

def refresh_scrollregion(canvas):
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

        if len(case) == 4 and isinstance(case[1], MovingWindowPlan):
            plan10, total_duration, window_sec = case[1], case[2], case[3]

            test10_state = {
                'title': title,
                'plan': plan10,
                'window': window_sec,
                'total_duration': Fraction(total_duration),
                'y_offset': y_offset,
                'tp': 0.0,
                'playing': False
            }
            y_offset += (0 + 1) * (row_height + row_spacing) + 40
            continue

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
        t10_y = test10_state['y_offset']
        assert tk is not None
        btn_t10 = tk.Button(canvas, text="Copy\nRules", cursor="hand2",
                            command=lambda: copy_symbolic_rules(
                                root, test10_state['title'], test10_state['plan']))
        canvas.create_window(15, t10_y, window=btn_t10, anchor="nw")

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
            for item in canvas.find_withtag("test10_dyn"):
                tooltip.unregister(item)
            canvas.delete("test10_dyn")

            st = test10_state
            status = "playing" if st['playing'] else "paused"
            n, t = st['plan'].phase(st['tp'])
            canvas.itemconfig(st['sub_id'],
                              text=f"t_p = {st['tp']:.1f}s ({status})  |  n = {n}, t = {t:.1f}s  |  "
                                   f"{st['plan'].regime_at(t).name}  |  symbolic rules, substituted not recomputed")

            prefix, cycle = st['plan'].instantiate(st['tp'])
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
                if st['tp'] + st['window'] > float(st['total_duration'] * 60):
                    st['tp'] = 0.0

            refresh_scrollregion(canvas)
            tooltip.refresh()
            root.after(50, animate_test10)

        animate_test10()

    refresh_scrollregion(canvas)
    return y_offset

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

    print_test10_rules(test10_case[0], test10_case[1])

def print_test10_rules(title, plan):
    print(f"--- {title.splitlines()[0]} ---")
    print("[ SYMBOLIC RULE SET - this is the output; it does not depend on t_p ]")
    for line in plan.lines(): print("  " + line)
    print("[ a few instantiations, for reading only ]")
    for sample_tp in [300.0, 590.0, 600.0, 610.0, 800.0, 1400.0]:
        prefix, cycle = plan(sample_tp)
        print(f"  t_p = {sample_tp}s : {plan.regime_at_tp(sample_tp).name}")
        if prefix:
            print("    Prefix:")
            for line in rule_lines(prefix, "      "): print(line)
        print("    Cycle:")
        for line in rule_lines(cycle, "      "): print(line)
        print()

def main():
    verify_only = "--verify" in sys.argv
    rules_only = "--rules" in sys.argv
    no_ui = verify_only or rules_only or "--no-ui" in sys.argv

    cases = build_cases()
    test10 = get_test_10()

    if rules_only:
        print_test10_rules(test10[0], test10[1])
        return
    if not verify_only:
        print_terminal_results(cases, test10)
    
    # Check configurations against constraints
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