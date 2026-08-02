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


def get_schedule_rules(tasks):
    """
    Simulates the schedule mathematically to find the periodic cycle.
    Returns a finite list of rules (prefix blocks and repeating cycle blocks).
    """
    time_now = 0
    actual_times = {t.name: 0 for t in tasks}
    total_priority = sum(t.priority for t in tasks)
    
    raw_steps = []
    state_seen = {}
    
    while True:
        # Define the exact deterministic state of the scheduler
        if time_now == 0:
            state = "START"
        else:
            # We use exact integer arithmetic to define the state to avoid floating point inconsistencies
            state = tuple(time_now * t.priority - actual_times[t.name] * total_priority for t in tasks)
            
        if state in state_seen:
            cycle_start = state_seen[state]
            break
            
        state_seen[state] = len(raw_steps)
        
        best_task = None
        if time_now == 0:
            max_v = -float('inf')
            for t in tasks:
                if t.priority > max_v:
                    max_v = t.priority
                    best_task = t
        else:
            max_v = -float('inf')
            for t in tasks:
                # Integer equivalent of (target_ratio - actual_ratio) for exact tie-breaking logic
                v = t.priority * time_now - actual_times[t.name] * total_priority
                if v > max_v:
                    max_v = v
                    best_task = t
                    
        if best_task is None:
            break
            
        raw_steps.append(best_task)
        time_now += best_task.min_time
        actual_times[best_task.name] += best_task.min_time

    # Compress identical sequential tasks into combined duration blocks
    def compress(steps):
        blocks = []
        for step in steps:
            if blocks and blocks[-1]['name'] == step.name:
                blocks[-1]['duration'] += step.min_time
            else:
                blocks.append({
                    'name': step.name,
                    'duration': step.min_time,
                    'color': step.color
                })
        return blocks

    prefix_blocks = compress(raw_steps[:cycle_start])
    cycle_blocks = compress(raw_steps[cycle_start:])
    
    return prefix_blocks, cycle_blocks


def generate_schedule(prefix_blocks, cycle_blocks, total_duration):
    """Generates the timeline up to 'total_duration' by unrolling the finite rules."""
    schedule = []
    time_now = 0
    
    def append_block(block_template):
        nonlocal time_now
        # Merge logic to handle boundaries between loops cleanly
        if schedule and schedule[-1]['name'] == block_template['name']:
            schedule[-1]['duration'] += block_template['duration']
        else:
            schedule.append({
                'name': block_template['name'],
                'start': time_now,
                'duration': block_template['duration'],
                'color': block_template['color']
            })
        time_now += block_template['duration']

    # 1. Add prefix
    for block in prefix_blocks:
        if time_now >= total_duration:
            break
        append_block(block)
        
    # 2. Loop the cycle until the timeline total is reached
    if not cycle_blocks:
        return schedule 
        
    while time_now < total_duration:
        for block in cycle_blocks:
            if time_now >= total_duration:
                break
            append_block(block)
            
    return schedule

def copy_to_clipboard(root, title, prefix_blocks, cycle_blocks):
    """Formats the schedule rules and copies it to the clipboard as demonstrated in the README."""
    lines = [title, "Rules:"]
    
    if prefix_blocks:
        lines.append("Prefix:")
        for block in prefix_blocks:
            lines.append(f"- task {block['name']} {block['duration']}min")
        lines.append("Cycle:")
    
    for block in cycle_blocks:
        lines.append(f"- task {block['name']} {block['duration']}min")
        
    lines.append("- repeat")
    
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
    row_spacing = 20
    
    tooltip = ToolTip(canvas)
    
    for title, tasks, total_duration in test_cases:
        prefix_blocks, cycle_blocks = get_schedule_rules(tasks)
        schedule = generate_schedule(prefix_blocks, cycle_blocks, total_duration)
        
        # 1. Create and place the Copy Button (Now exports the Rules)
        btn = tk.Button(canvas, text="Copy\nRules", cursor="hand2",
                        command=lambda t=title, p=prefix_blocks, c=cycle_blocks: copy_to_clipboard(root, t, p, c))
        canvas.create_window(15, y_offset, window=btn, anchor="nw")
        
        # 2. Draw Test Title
        txt_id = canvas.create_text(margin_left, y_offset, text=title, font=("Arial", 13, "bold"), anchor="nw")
        
        bbox = canvas.bbox(txt_id)
        y_offset = bbox[3] + 20 
        
        # 3. Draw the timeline blocks
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

    def _on_mousewheel(event):
        if getattr(event, 'num', 0) == 4 or event.delta > 0:
            canvas.yview_scroll(-1, "units")
        elif getattr(event, 'num', 0) == 5 or event.delta < 0:
            canvas.yview_scroll(1, "units")

    canvas.bind_all("<MouseWheel>", _on_mousewheel) 
    canvas.bind_all("<Button-4>", _on_mousewheel)   
    canvas.bind_all("<Button-5>", _on_mousewheel)   

    draw_schedules(root, canvas, test_cases, window_width=900)

    root.mainloop()

if __name__ == "__main__":
    main()