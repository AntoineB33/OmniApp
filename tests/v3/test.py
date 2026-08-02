import tkinter as tk


class Task:
    def __init__(self, name, priority, min_time, color):
        self.name = name
        self.priority = priority
        self.min_time = min_time
        self.color = color

class ToolTip:
    """Manages floating info bubbles for canvas elements."""
    def __init__(self, canvas):
        self.canvas = canvas
        self.tooltip_window = None
        self.data = {}
        
        # Bind events to any canvas item tagged with "task_panel"
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

def generate_schedule(tasks, total_duration):
    """Generates a schedule minimizing local error to priority targets."""
    time_now = 0
    actual_times = {t.name: 0 for t in tasks}
    schedule = []
    total_priority = sum(t.priority for t in tasks)
    
    while time_now < total_duration:
        best_task = None
        max_deficit = -float('inf')
        
        for t in tasks:
            target_ratio = t.priority / total_priority
            if time_now == 0:
                deficit = target_ratio
            else:
                actual_ratio = actual_times[t.name] / time_now
                deficit = target_ratio - actual_ratio
                
            if deficit > max_deficit:
                max_deficit = deficit
                best_task = t

        if best_task is None:
            break  # No task can be scheduled, exit loop
                
        # --- MERGE LOGIC ADDED HERE ---
        # If the schedule isn't empty and the last task is the same as the new one, extend it
        if schedule and schedule[-1]['name'] == best_task.name:
            schedule[-1]['duration'] += best_task.min_time
        else:
            # Otherwise, append a new block
            schedule.append({
                'name': best_task.name,
                'start': time_now,
                'duration': best_task.min_time,
                'color': best_task.color
            })
        
        time_now += best_task.min_time
        actual_times[best_task.name] += best_task.min_time
        
    return schedule

def copy_to_clipboard(root, title, schedule):
    """Formats the schedule and copies it to the clipboard."""
    lines = [title]
    for block in schedule:
        lines.append(f"Task {block['name']}: {block['start']} - {block['start'] + block['duration']}")
    
    clipboard_text = "\n".join(lines)
    root.clipboard_clear()
    root.clipboard_append(clipboard_text)

def draw_schedules(root, canvas, test_cases, window_width=900):
    y_offset = 20
    px_per_min = 4
    margin_left = 90  # Increased margin to fit the copy button
    margin_right = 30
    row_duration = (window_width - margin_left - margin_right) // px_per_min
    row_height = 40
    row_spacing = 20
    
    tooltip = ToolTip(canvas)
    
    for title, tasks, total_duration in test_cases:
        schedule = generate_schedule(tasks, total_duration)
        
        # 1. Create and place the Copy Button
        btn = tk.Button(canvas, text="Copy\nData", cursor="hand2",
                        command=lambda t=title, s=schedule: copy_to_clipboard(root, t, s))
        canvas.create_window(15, y_offset, window=btn, anchor="nw")
        
        # 2. Draw Test Title
        txt_id = canvas.create_text(margin_left, y_offset, text=title, font=("Arial", 13, "bold"), anchor="nw")
        
        # Fix overlapping from image_b99e81.png by calculating the actual text box height
        bbox = canvas.bbox(txt_id)
        y_offset = bbox[3] + 20 
        
        # 3. Draw the timeline blocks
        max_row_idx = 0
        for block in schedule:
            block_start = block['start']
            remaining = block['duration']
            
            # Text for the tooltip bubble
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
                
                # Draw task panel with "task_panel" tag
                rect_id = canvas.create_rectangle(x1, y1, x2, y2, fill=block['color'], 
                                                  outline="black", tags="task_panel")
                tooltip.register(rect_id, hover_info)
                
                # Add text if panel is wide enough
                if (x2 - x1) > 30:
                    text_id = canvas.create_text((x1 + x2) / 2, (y1 + y2) / 2, 
                                                 text=block['name'], font=("Arial", 10), tags="task_panel")
                    tooltip.register(text_id, hover_info)
                
                remaining -= time_in_row
                block_start += time_in_row
                max_row_idx = max(max_row_idx, row_idx)
                
        y_offset += (max_row_idx + 1) * (row_height + row_spacing) + 40
        
    canvas.config(scrollregion=(0, 0, window_width, y_offset))

def main():
    test_cases = [
        (
            "Test 1: 50/50 Split (10min each)\n-> Alternates perfectly A, B, A, B...",
            [
                Task("A", priority=50, min_time=10, color="#FF9999"),
                Task("B", priority=50, min_time=10, color="#99CCFF")
            ],
            180 
        ),
        (
            "Test 2: 75/25 Split (10min each)\n-> Schedules naturally A, B, A, A, A, B, A, A...",
            [
                Task("A", priority=75, min_time=10, color="#FF9999"),
                Task("B", priority=25, min_time=10, color="#99CCFF")
            ],
            180
        ),
        (
            "Test 3: Equal Priority, Mismatched times (A: 30m, B: 10m)\n-> Schedules A(30m), then B(10m) x3 to catch up.",
            [
                Task("A", priority=50, min_time=30, color="#FF9999"),
                Task("B", priority=50, min_time=10, color="#99CCFF")
            ],
            240
        ),
        (
            "Test 4: Three Tasks (A: 50% 20m, B: 30% 10m, C: 20% 15m)",
            [
                Task("A", priority=50, min_time=20, color="#FF9999"),
                Task("B", priority=30, min_time=10, color="#99CCFF"),
                Task("C", priority=20, min_time=15, color="#99FF99")
            ],
            400
        )
    ]

    root = tk.Tk()
    root.title("Task Scheduler Timeline")
    root.geometry("950x700")

    canvas = tk.Canvas(root, bg="white")
    vbar = tk.Scrollbar(root, orient=tk.VERTICAL, command=canvas.yview)
    canvas.configure(yscrollcommand=vbar.set)

    vbar.pack(side=tk.RIGHT, fill=tk.Y)
    canvas.pack(side=tk.LEFT, fill=tk.BOTH, expand=True)

    # --- ADD MOUSEWHEEL SUPPORT HERE ---
    def _on_mousewheel(event):
        # Cross-platform check for scroll direction
        if getattr(event, 'num', 0) == 4 or event.delta > 0:
            canvas.yview_scroll(-1, "units")
        elif getattr(event, 'num', 0) == 5 or event.delta < 0:
            canvas.yview_scroll(1, "units")

    # Bind to all elements so scrolling works anywhere in the window
    canvas.bind_all("<MouseWheel>", _on_mousewheel) # Windows & macOS
    canvas.bind_all("<Button-4>", _on_mousewheel)   # Linux scroll up
    canvas.bind_all("<Button-5>", _on_mousewheel)   # Linux scroll down
    # -----------------------------------

    draw_schedules(root, canvas, test_cases, window_width=900)

    root.mainloop()

if __name__ == "__main__":
    main()