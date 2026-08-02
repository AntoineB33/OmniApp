import math

import matplotlib.colors as mcolors
import matplotlib.pyplot as plt


class Task:
    def __init__(self, name, priority, min_time):
        self.name = name
        self.priority = priority
        self.min_time = min_time

def get_status(t, starting_timeline, periods, tasks):
    """
    Evaluates the state of the timeline at time t.
    Returns whether the time is blocked by a pre-placed task,
    and if free, what the active limits/periods are.
    """
    running_preplaced = None
    next_preplaced_start = float('inf')
    
    # 1. Check pre-placed tasks
    for b in starting_timeline:
        s = b['start']
        e = b.get('end', s)
        r = b.get('repeat', None)
        
        if r:
            if t >= s:
                cycle = (t - s) // r
                cycle_start = s + cycle * r
                cycle_end = cycle_start + (e - s)
                if t < cycle_end:
                    running_preplaced = (b['task'], cycle_end)
                    break
                else:
                    nxt = s + (cycle + 1) * r
                    if nxt < next_preplaced_start:
                        next_preplaced_start = nxt
            else:
                if s < next_preplaced_start:
                    next_preplaced_start = s
        else:
            if s <= t < e:
                running_preplaced = (b['task'], e)
                break
            elif t < s:
                if s < next_preplaced_start:
                    next_preplaced_start = s
                    
    if running_preplaced:
        return {'status': 'blocked', 'task': running_preplaced[0], 'end': running_preplaced[1]}
        
    gap_end = next_preplaced_start
    
    # 2. Check period restrictions
    active = set()
    any_period_active = False
    next_period_event = float('inf')
    
    if not periods:
        active = set(t_obj.name for t_obj in tasks)
    else:
        for p in periods:
            s = p['start']
            e = p.get('end', s)
            r = p.get('repeat', None)
            tasks_allowed = p.get('accepted_tasks', [])
            
            if r:
                if t >= s:
                    cycle = (t - s) // r
                    cycle_start = s + cycle * r
                    cycle_end = cycle_start + (e - s)
                    if t < cycle_end:
                        active.update(tasks_allowed)
                        any_period_active = True
                        if cycle_end < next_period_event:
                            next_period_event = cycle_end
                    else:
                        nxt = s + (cycle + 1) * r
                        if nxt < next_period_event:
                            next_period_event = nxt
                else:
                    if s < next_period_event:
                        next_period_event = s
            else:
                if s <= t < e:
                    active.update(tasks_allowed)
                    any_period_active = True
                    if e < next_period_event:
                        next_period_event = e
                elif t < s:
                    if s < next_period_event:
                        next_period_event = s
                        
        if not any_period_active:
            active = set()
            
    boundary = min(gap_end, next_period_event)
    
    return {'status': 'free', 'gap_end': gap_end, 'boundary': boundary, 'active': active}

def run_scheduler(tasks, starting_timeline, periods, max_instructions=5000, time_limit=None):
    """
    Core engine: simulates exact debts mathematically using an exponential decay integral.
    Can run until a time limit (for plotting) or until a cycle is detected (for O(1) rule generation).
    """
    max_static = 0
    lcm_val = 1
    
    def compute_lcm(a, b):
        return abs(a * b) // math.gcd(a, b) if a and b else 1
        
    for b in starting_timeline + periods:
        if 'repeat' in b:
            lcm_val = compute_lcm(lcm_val, b['repeat'])
        else:
            end_val = b.get('end', b['start'])
            if end_val > max_static:
                max_static = end_val
                
    earliest_time = 0
    for b in starting_timeline + periods:
        if b['start'] < earliest_time:
            earliest_time = b['start']
            
    t = earliest_time
    debts = {t_obj.name: 0.0 for t_obj in tasks}
    
    # Lambda for continuous exponential decay
    lambda_rate = math.log(2) / HALF_LIFE
    
    schedule = []
    instructions = []
    state_to_idx = {}
    
    transient = None
    cycle = None
    
    def advance_debts(dt, running_task):
        if dt <= 0: return
        decay = math.exp(-lambda_rate * dt)
        for t_obj in tasks:
            name = t_obj.name
            P = t_obj.priority / 100.0
            rate = P
            if running_task == name:
                rate = P - 1.0
                
            # Exact integral for continuous leak/fill
            if lambda_rate > 1e-6:
                debts[name] = debts[name] * decay + rate * (1 - decay) / lambda_rate
            else:
                debts[name] = debts[name] + rate * dt
                
            # Epsilon rounding to forget ancient memory blocks
            if abs(debts[name]) < 1e-4:
                debts[name] = 0.0
                
    def get_state():
        # Round to 3 decimal places to ensure robust mathematical cycle convergence
        d_tup = tuple(round(debts[tsk.name], 3) for tsk in tasks)
        if t <= max_static:
            return (d_tup, round(t, 2))
        else:
            return (d_tup, round(t % lcm_val, 2) if lcm_val > 1 else 0)
            
    while True:
        if time_limit is not None and t >= time_limit:
            break
            
        if time_limit is None and transient is not None:
            break
            
        status = get_status(t, starting_timeline, periods, tasks)
        
        if status['status'] == 'blocked':
            task_name = status['task']
            end_time = status['end']
            dt = end_time - t
            advance_debts(dt, task_name)
            if end_time > 0 and (time_limit is None or t < time_limit):
                schedule.append({'task': task_name, 'start': max(0, t), 'end': end_time})
            t = end_time
        else:
            active = status['active']
            gap_end = status['gap_end']
            boundary = status['boundary']
            
            best_task = None
            best_debt = -float('inf')
            
            for t_obj in tasks:
                if t_obj.name in active:
                    if t + t_obj.min_time <= gap_end:
                        if debts[t_obj.name] > best_debt:
                            best_debt = debts[t_obj.name]
                            best_task = t_obj
                            
            if best_task:
                dt = best_task.min_time
                
                # O(1) Limit Cycle Detection
                if t >= 0 and time_limit is None and transient is None:
                    st = get_state()
                    if st in state_to_idx:
                        cycle_start = state_to_idx[st]
                        transient = instructions[:cycle_start]
                        cycle = instructions[cycle_start:]
                        break
                    else:
                        state_to_idx[st] = len(instructions)
                        instructions.append(best_task.name)
                        if len(instructions) >= max_instructions:
                            transient = instructions
                            cycle = []
                            break
                            
                advance_debts(dt, best_task.name)
                if t + dt > 0 and (time_limit is None or t < time_limit):
                    schedule.append({'task': best_task.name, 'start': max(0, t), 'end': t + dt})
                t += dt
            else:
                dt = boundary - t
                if dt <= 0 or boundary == float('inf'):
                    break
                advance_debts(dt, None)
                t = boundary
                
    return schedule, transient, cycle

def get_schedule(tasks, time_limit, starting_timeline, periods):
    schedule, _, _ = run_scheduler(tasks, starting_timeline, periods, time_limit=time_limit)
    return schedule

def print_test_instructions(name, tasks, starting_timeline, periods):
    print(f"--- {name} ---")
    _, transient, cycle = run_scheduler(tasks, starting_timeline, periods, max_instructions=5000, time_limit=None)
    
    if cycle is None or len(cycle) == 0:
        print(f"Total instructions: Capped at {len(transient)}")
        full_list = transient
    else:
        print(f"Total instructions: {len(transient) + len(cycle)} distinct states (Transient: {len(transient)}, Loop: {len(cycle)})")
        # Extend to easily grab the first 10, even if it loops quickly
        full_list = transient + cycle * max(1, 10 // len(cycle) + 1)
        
    print("First 10 instructions:", full_list[:10])
    print()

def visualize_schedule(schedule, tasks, time_limit, ax, title):
    colors = list(mcolors.TABLEAU_COLORS.values())
    display_tasks = tasks[:8]  
    task_colors = {task.name: colors[i % len(colors)] for i, task in enumerate(display_tasks)}
    
    task_names = [t.name for t in display_tasks]
    y_ticks = []
    y_labels = []

    for i, name in enumerate(task_names):
        task_blocks = []
        for b in schedule:
            if b['task'] == name:
                start_clamped = max(0, b['start'])
                end_clamped = min(time_limit, b['end'])
                if end_clamped > start_clamped:
                    task_blocks.append((start_clamped, end_clamped - start_clamped))
        
        y_pos = i * 10
        if task_blocks:
            ax.broken_barh(task_blocks, (y_pos + 1, 8), facecolors=task_colors.get(name, 'gray'), edgecolor='black')
        
        y_ticks.append(y_pos + 5)
        y_labels.append(name)

    stats_text = "Target vs Actual (Shown):\n"
    for task in display_tasks:
        actual_time = 0
        for b in schedule:
            if b['task'] == task.name:
                start_clamped = max(0, b['start'])
                end_clamped = min(time_limit, b['end'])
                if end_clamped > start_clamped:
                    actual_time += end_clamped - start_clamped
        actual_pct = (actual_time / time_limit * 100) if time_limit > 0 else 0
        stats_text += f"\n{task.name}: Target {task.priority:.0f}%, Actual {actual_pct:.1f}%"

    ax.set_yticks(y_ticks)
    ax.set_yticklabels(y_labels)
    ax.set_xlabel('Time (minutes)')
    ax.set_title(title)
    ax.set_xlim(0, time_limit)
    ax.grid(True, axis='x', linestyle='--', alpha=0.7)
    
    props = {'boxstyle': 'round', 'facecolor': 'wheat', 'alpha': 0.5}
    ax.text(1.02, 0.95, stats_text, transform=ax.transAxes, fontsize=10, verticalalignment='top', bbox=props)

def show_scrollable(fig, window_title="Scheduler tests"):
    try:
        import tkinter as tk
        from matplotlib.backends.backend_tkagg import FigureCanvasTkAgg
    except ImportError:
        plt.show()
        return

    root = tk.Tk()
    root.title(window_title)
    root.geometry("1250x900")

    scroll_canvas = tk.Canvas(root, borderwidth=0, highlightthickness=0)
    vbar = tk.Scrollbar(root, orient=tk.VERTICAL, command=scroll_canvas.yview)
    scroll_canvas.configure(yscrollcommand=vbar.set)
    vbar.pack(side=tk.RIGHT, fill=tk.Y)
    scroll_canvas.pack(side=tk.LEFT, fill=tk.BOTH, expand=True)

    holder = tk.Frame(scroll_canvas)
    window_id = scroll_canvas.create_window((0, 0), window=holder, anchor="nw")

    fig_canvas = FigureCanvasTkAgg(fig, master=holder)
    widget = fig_canvas.get_tk_widget()
    fig_w, fig_h = (int(v * fig.dpi) for v in fig.get_size_inches())
    widget.configure(width=fig_w, height=fig_h)
    widget.pack(fill=tk.BOTH, expand=True)
    fig_canvas.draw()

    def on_holder_configure(_event=None):
        scroll_canvas.configure(scrollregion=scroll_canvas.bbox("all"))
    def on_canvas_configure(event):
        scroll_canvas.itemconfigure(window_id, width=event.width)
    def on_mousewheel(event):
        scroll_canvas.yview_scroll(-event.delta // 120, "units")

    holder.bind("<Configure>", on_holder_configure)
    scroll_canvas.bind("<Configure>", on_canvas_configure)
    root.bind_all("<MouseWheel>", on_mousewheel)            
    root.bind_all("<Button-4>", lambda e: scroll_canvas.yview_scroll(-1, "units"))  
    root.bind_all("<Button-5>", lambda e: scroll_canvas.yview_scroll(1, "units"))

    root.mainloop()

if __name__ == "__main__":
    HALF_LIFE = 30.0
    
    test_configs = [
        {
            "name": "Test 1: Original Scenario (No Starting Timeline)",
            "tasks": [Task("Task A", 50, 10), Task("Task B", 30, 15), Task("Task C", 20, 5)],
            "time_limit": 100, "starting_timeline": [], "periods": []
        },
        {
            "name": "Test 2: Massive Task A past debt decays",
            "tasks": [Task("Task A", 50, 10), Task("Task B", 50, 10)],
            "time_limit": 100, "starting_timeline": [{'task': 'Task A', 'start': -1000, 'end': 0}], "periods": []
        },
        {
            "name": "Test 3: Infinite repeating blocks using 'repeat' key",
            "tasks": [Task("Task A", 50, 10), Task("Task B", 50, 10)],
            "time_limit": 400, "starting_timeline": [{'task': 'Task A', 'start': 0, 'end': 60, 'repeat': 180}], "periods": []
        },
        {
            "name": "Test 4: Massive Task A and B before t_now",
            "tasks": [Task("Task A", 45, 45), Task("Task B", 45, 45), Task("Task C", 10, 45)],
            "time_limit": 2000, "starting_timeline": [{'task': 'Task A', 'start': -1000, 'end': 0}, {'task': 'Task B', 'start': -1000, 'end': 0}], "periods": []
        },
        {
            "name": "Test 5: Exclusive Periods",
            "tasks": [Task("Task A", 50, 10), Task("Task B", 50, 10)],
            "time_limit": 100, "starting_timeline": [], "periods": [{'start': 0, 'end': 50, 'accepted_tasks': ['Task A']}, {'start': 50, 'end': 100, 'accepted_tasks': ['Task B']}]
        },
        {
            "name": "Test 6: Overlapping Allow-lists",
            "tasks": [Task("Task A", 40, 10), Task("Task B", 40, 10), Task("Task C", 20, 10)],
            "time_limit": 150, "starting_timeline": [], "periods": [{'start': 0, 'end': 60, 'accepted_tasks': ['Task A', 'Task C']}, {'start': 60, 'end': 120, 'accepted_tasks': ['Task B', 'Task C']}, {'start': 120, 'end': 150, 'accepted_tasks': ['Task A', 'Task B', 'Task C']}]
        },
        {
            "name": "Test 7: Future Debt Growth (Looming Task A)",
            "tasks": [Task("Task A", 50, 10), Task("Task B", 50, 10)],
            "time_limit": 100, "starting_timeline": [{'task': 'Task A', 'start': 70, 'end': 130}], "periods": []
        },
        {
            "name": "Test 8: Exponential Decay - B catches up quickly",
            "tasks": [Task("Task A", 50, 10), Task("Task B", 50, 10)],
            "time_limit": 100, "starting_timeline": [{'task': 'Task A', 'start': -100, 'end': 0}], "periods": []
        },
        {
            "name": "Test 9: Long-term Fluid Decay Priority Matching",
            "tasks": [Task("Task A", 25, 10), Task("Task B", 25, 10), Task("Task C", 50, 10)],
            "time_limit": 2000, "starting_timeline": [{'task': 'Task C', 'start': -1000, 'end': 0}], "periods": []
        },
        {
            "name": "Test 10: Handling large mid-run pre-scheduled blocks smoothly",
            "tasks": [Task("Task A", 80, 10), Task("Task B", 20, 10)],
            "time_limit": 500, "starting_timeline": [{'task': 'Task B', 'start': 200, 'end': 400}], "periods": []
        },
        {
            "name": "Test 11: High Performance Scaling (50 tasks, 100 periods)",
            "tasks": [Task(f"Task {i}", 100.0/50, 5) for i in range(50)],
            "time_limit": 1000, 
            "starting_timeline": [{'task': f"Task {i%50}", 'start': i*20+15, 'end': i*20+20} for i in range(100)],
            "periods": [{'start': i*20, 'end': i*20+15, 'accepted_tasks': [f"Task {j}" for j in range(50)]} for i in range(100)]
        },
        {
            "name": "Test 12: Priority Zero Edge Case",
            "tasks": [Task("Task A", 100, 10), Task("Task B", 0, 10)],
            "time_limit": 100, "starting_timeline": [],
            "periods": [{'start': 0, 'end': 40, 'accepted_tasks': ['Task A', 'Task B']}, {'start': 40, 'end': 60, 'accepted_tasks': ['Task B']}, {'start': 60, 'end': 100, 'accepted_tasks': ['Task A', 'Task B']}]
        },
        {
            "name": "Test 13: Infinite Periodic constraints using 'repeat'",
            "tasks": [Task("Task A", 50, 10), Task("Task B", 50, 10)],
            "time_limit": 300, "starting_timeline": [],
            "periods": [{'start': 0, 'end': 30, 'accepted_tasks': ['Task A'], 'repeat': 100}]
        },
        {
            "name": "Test 14: Aperiodic Infinite Events (Triggers Capping Limit)",
            "tasks": [Task("Task A", 50, 10), Task("Task B", 50, 10)],
            "time_limit": 400, 
            "starting_timeline": [{'task': 'Task A', 'start': (i*10)**1.5, 'end': (i*10)**1.5 + 10} for i in range(1, 150)],
            "periods": []
        }
    ]

    SUBPLOT_HEIGHT_IN = 3.5
    fig, axes = plt.subplots(len(test_configs), 1, figsize=(14, SUBPLOT_HEIGHT_IN * len(test_configs)))

    print("Extracting O(1) Instructions for all tests...\n")
    for idx, tc in enumerate(test_configs):
        print_test_instructions(tc["name"], tc["tasks"], tc["starting_timeline"], tc["periods"])
        schedule = get_schedule(tc["tasks"], tc["time_limit"], tc["starting_timeline"], tc["periods"])
        visualize_schedule(schedule, tc["tasks"], tc["time_limit"], axes[idx], tc["name"])

    fig.subplots_adjust(left=0.08, right=0.80, top=0.97, bottom=0.03, hspace=0.85)
    show_scrollable(fig)