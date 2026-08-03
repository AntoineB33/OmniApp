import tkinter as tk

from scheduler_v3 import EPSILON, debt_curves, generate_schedule, get_schedule_rules


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
    curve_height = 45          # room above each row for the decay curve
    row_spacing = curve_height + 20

    tooltip = ToolTip(canvas)

    for title, tasks, total_duration, pre_placed, periods in test_cases:
        prefix_blocks, cycle_blocks = get_schedule_rules(tasks, pre_placed, periods)
        schedule = generate_schedule(prefix_blocks, cycle_blocks, total_duration)
        curves, envelopes, peak_excess = debt_curves(tasks, schedule, pre_placed, periods, total_duration)

        btn = tk.Button(canvas, text="Copy\nRules", cursor="hand2",
                        command=lambda t=title, p=prefix_blocks, c=cycle_blocks: copy_to_clipboard(root, t, p, c))
        canvas.create_window(15, y_offset, window=btn, anchor="nw")

        txt_id = canvas.create_text(margin_left, y_offset, text=title, font=("Arial", 11, "bold"), anchor="nw")
        bbox = canvas.bbox(txt_id)
        y_offset = bbox[3] + 20 + curve_height

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

        # Drawn above the panels, touching their top edge exactly when the
        # value falls under EPSILON:
        #   thin dotted = the envelope, i.e. how much out-of-bound imbalance the
        #                 decay still counts at this distance from an anomaly.
        #                 This is the pure exponential.
        #   solid       = how much the task actually carries out of bounds.
        colors = {t.name: t.color for t in tasks}
        dashes = {t.name: ((), (6, 3), (2, 3), (8, 3, 2, 3))[i % 4] for i, t in enumerate(tasks)}

        def plot(points, color, width, dash, peak_excess=peak_excess, curve_height=curve_height, y_offset=y_offset):
            segment, last_row = [], None
            for now, value in points:
                row_idx = now // row_duration
                if value <= 0.0 or row_idx != last_row:
                    if len(segment) > 3:
                        canvas.create_line(*segment, fill=color, width=width,
                                           smooth=True, dash=dash)
                    segment = []
                    last_row = row_idx
                    if value <= 0.0:
                        continue
                x = margin_left + (now % row_duration) * px_per_min
                top = y_offset + row_idx * (row_height + row_spacing)
                segment.extend([x, top - curve_height * (value - EPSILON) / (peak_excess - EPSILON)])
            if len(segment) > 3:
                canvas.create_line(*segment, fill=color, width=width, smooth=True, dash=dash)

        if peak_excess > EPSILON:
            for name in curves:
                plot(envelopes[name], colors[name], 1, (1, 4))
                plot(curves[name], colors[name], 2, dashes[name])

        y_offset += (max_row_idx + 1) * (row_height + row_spacing) + 40

    canvas.config(scrollregion=(0, 0, window_width, y_offset))