import math

import matplotlib.colors as mcolors
import matplotlib.pyplot as plt


class Task:
    def __init__(self, name, priority, min_time):
        self.name = name
        self.priority = priority / 100.0  # Normalize percentage to 0-1
        self.min_time = min_time


class SchedulerFunction:
    """
    A callable scheduler that acts as a function f(time) -> Task | None.
    It generates the timeline dynamically and caches history for fast querying.
    """
    def __init__(self, tasks, starting_timeline=None, periods=None, half_life=30.0):
        self.tasks = tasks
        self.starting_timeline = starting_timeline or []
        self.periods = periods or []
        self.half_life = half_life
        
        self.generator = self._run()
        self.history = []

    def __call__(self, t):
        if t < 0:
            return None
            
        # Advance the generator until our history covers time t
        while not self.history or self.history[-1]['end'] <= t:
            try:
                self.history.append(next(self.generator))
            except StopIteration:
                break
                
        # Find the block that intersects t
        for b in self.history:
            if b['start'] <= t < b['end']:
                if b['task'] is None: 
                    return None
                return next((task for task in self.tasks if task.name == b['task']), None)
                
        return None

    def _run(self):
        k = math.log(2) / self.half_life if self.half_life else 0.0
        
        # C is the natural debt boundary. A task's natural cyclical debt in a clear 
        # schedule is safely bounded by the sum of minimum times of all tasks.
        # Only debt EXCESS beyond C decays.
        C = sum(t.min_time for t in self.tasks)
        debts = {t.name: 0.0 for t in self.tasks}
        
        past_blocks = []
        future_pre = []
        
        for b in self.starting_timeline:
            if b['end'] <= 0:
                past_blocks.append(b)
            elif b['start'] < 0:
                past_blocks.append({'task': b['task'], 'start': b['start'], 'end': 0})
                future_pre.append({'task': b['task'], 'start': 0, 'end': b['end']})
            else:
                future_pre.append(b)
                
        past_blocks.sort(key=lambda x: x['start'])
        future_pre.sort(key=lambda x: x['start'])
        
        def simulate_interval(dt, task_running):
            """
            Exact piecewise ODE solver for time advancing. Applies continuous exponential
            decay to the EXCESS debt while naturally integrating ideal debt and actual execution.
            """
            for t in self.tasks:
                name = t.name
                e = 1.0 if name == task_running else 0.0
                R = t.priority - e  # Rate of change of debt inside the bounds
                
                t_rem = dt
                while t_rem > 0:
                    d = debts[name]
                    eps = 1e-9  # Epsilon for floating point safety
                    
                    # 1. Excess debt (or sitting on the boundary wanting to grow)
                    if d > C + eps or (d >= C - eps and R > 0):
                        y0 = d - C
                        denominator = R - k * y0
                        ratio = R / denominator if abs(denominator) > 1e-12 else -1.0
                        
                        target_t = float('inf')
                        # Check if it will hit the boundary 'C' (y=0) within finite time
                        if k > 0 and 0 < ratio < 1:
                            target_t = -math.log(ratio) / k
                            
                        step_t = min(t_rem, target_t)
                        if k > 0:
                            debts[name] = C + R/k + (y0 - R/k) * math.exp(-k * step_t)
                        else:
                            debts[name] = d + R * step_t
                            
                        t_rem -= step_t
                        if abs(debts[name] - C) < eps:
                            debts[name] = C

                    # 2. Deficit debt (or sitting on the bottom boundary wanting to shrink)
                    elif d < -C - eps or (d <= -C + eps and R < 0):
                        y0 = d + C
                        denominator = R - k * y0
                        ratio = R / denominator if abs(denominator) > 1e-12 else -1.0
                        
                        target_t = float('inf')
                        if k > 0 and 0 < ratio < 1:
                            target_t = -math.log(ratio) / k
                            
                        step_t = min(t_rem, target_t)
                        if k > 0:
                            debts[name] = -C + R/k + (y0 - R/k) * math.exp(-k * step_t)
                        else:
                            debts[name] = d + R * step_t
                            
                        t_rem -= step_t
                        if abs(debts[name] - (-C)) < eps:
                            debts[name] = -C
                            
                    # 3. Inside the safe boundaries [-C, C], integration is purely linear
                    else:
                        target_t = float('inf')
                        if R > 0:
                            target_t = (C - d) / R
                        elif R < 0:
                            target_t = (-C - d) / R
                            
                        step_t = min(t_rem, target_t)
                        debts[name] += R * step_t
                        t_rem -= step_t
                        
                        if abs(debts[name] - C) < eps:
                            debts[name] = C
                        elif abs(debts[name] - (-C)) < eps:
                            debts[name] = -C

        # Process starting blocks that occurred entirely in the past
        if past_blocks:
            current_sim_time = past_blocks[0]['start']
            for b in past_blocks:
                if b['start'] > current_sim_time:
                    simulate_interval(b['start'] - current_sim_time, None)
                simulate_interval(b['end'] - b['start'], b['task'])
                current_sim_time = b['end']
            if current_sim_time < 0:
                simulate_interval(0 - current_sim_time, None)
                
        current_time = 0.0
        
        while True:
            # 1. Check if we overlap a pre-scheduled block
            overlapping_block = None
            for b in future_pre:
                if b['start'] <= current_time < b['end']:
                    overlapping_block = b
                    break
            
            if overlapping_block:
                dur = overlapping_block['end'] - current_time
                yield {
                    'task': overlapping_block['task'],
                    'start': current_time,
                    'duration': dur,
                    'end': overlapping_block['end'],
                    'type': 'pre-scheduled'
                }
                simulate_interval(dur, overlapping_block['task'])
                current_time = overlapping_block['end']
                continue
                
            # 2. We are in a gap
            next_start = float('inf')
            for b in future_pre:
                if b['start'] > current_time:
                    next_start = min(next_start, b['start'])
            
            accepted_tasks = [t.name for t in self.tasks]
            current_period_end = float('inf')
            
            if self.periods:
                current_p = None
                next_p_start = float('inf')
                
                for p in self.periods:
                    if p['start'] <= current_time < p['end']:
                        current_p = p
                        break
                    elif p['start'] > current_time:
                        next_p_start = min(next_p_start, p['start'])
                        
                if current_p:
                    accepted_tasks = current_p['accepted_tasks']
                    current_period_end = current_p['end']
                else:
                    advance_to = min(next_start, next_p_start)
                    if advance_to == float('inf'):
                        break
                    
                    dur = advance_to - current_time
                    yield {
                        'task': None,
                        'start': current_time,
                        'duration': dur,
                        'end': advance_to,
                        'type': 'gap (outside periods)'
                    }
                    simulate_interval(dur, None)
                    current_time = advance_to
                    continue

            gap = min(next_start, current_period_end) - current_time
            valid_tasks = [t for t in self.tasks if t.min_time <= gap and t.name in accepted_tasks]
            
            if not valid_tasks:
                advance_to = min(next_start, current_period_end)
                if advance_to == float('inf'):
                    break 
                
                dur = advance_to - current_time
                yield {
                    'task': None,
                    'start': current_time,
                    'duration': dur,
                    'end': advance_to,
                    'type': 'gap (no valid tasks)'
                }
                simulate_interval(dur, None)
                current_time = advance_to
                continue

            # Calculate scores to pick the most starved task based on priority ratio
            best_task = valid_tasks[0]  
            best_score = float('-inf')
            
            for t in valid_tasks:
                f_eff = 0.0
                
                # Merge contiguous future blocks mathematically to prevent chunking dependencies 
                merged_blocks = []
                for b in future_pre:
                    if b['task'] == t.name and b['end'] > current_time:
                        s = max(current_time, b['start'])
                        e = b['end']
                        if merged_blocks and abs(merged_blocks[-1]['end'] - s) < 1e-9:
                            merged_blocks[-1]['end'] = e
                        else:
                            merged_blocks.append({'start': s, 'end': e})
                
                for mb in merged_blocks:
                    D = mb['end'] - mb['start']
                    dist = mb['start'] - current_time
                    
                    # Only decay the EXCESS duration of future blocks
                    if D > C:
                        f_eff += C + (D - C) * (math.exp(-k * dist) if k > 0 else 1.0)
                    else:
                        f_eff += D
                        
                total_debt = debts[t.name] - f_eff
                score = total_debt / t.priority
                
                if score > best_score:
                    best_score = score
                    best_task = t
            
            duration = best_task.min_time
            min_valid = min(t.min_time for t in valid_tasks)
            
            if gap - duration < min_valid and gap >= duration:
                duration = gap
                
            yield {
                'task': best_task.name,
                'start': current_time,
                'duration': duration,
                'end': current_time + duration,
                'type': 'scheduled'
            }
            
            simulate_interval(duration, best_task.name)
            current_time += duration


def get_schedule(tasks, time_limit, starting_timeline=None, periods=None):
    """
    Wrapper function to extract discrete blocks up to `time_limit` for plotting.
    Under the hood, it just pushes the functional scheduler forward.
    """
    hl = HALF_LIFE if 'HALF_LIFE' in globals() else 30.0
    scheduler_func = SchedulerFunction(tasks, starting_timeline, periods, half_life=hl)
    
    # We query the function at the target time to force it to generate the timeline up to `time_limit`.
    scheduler_func(time_limit)
    
    schedule = []
    for b in scheduler_func.history:
        if b['start'] >= time_limit:
            break
        if b['task'] is not None:  # Exclude raw gaps from final visualization
            schedule.append(b)
            
    return schedule


def visualize_schedule(schedule, tasks, time_limit, ax, title):
    colors = list(mcolors.TABLEAU_COLORS.values())
    task_colors = {task.name: colors[i % len(colors)] for i, task in enumerate(tasks)}
    
    task_names = [t.name for t in tasks]
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
            ax.broken_barh(task_blocks, (y_pos + 1, 8), facecolors=task_colors[name], edgecolor='black')
        
        y_ticks.append(y_pos + 5)
        y_labels.append(name)

    stats_text = "Target vs Actual:\n"
    for task in tasks:
        actual_time = 0
        for b in schedule:
            if b['task'] == task.name:
                start_clamped = max(0, b['start'])
                end_clamped = min(time_limit, b['end'])
                if end_clamped > start_clamped:
                    actual_time += end_clamped - start_clamped
        actual_pct = (actual_time / time_limit * 100) if time_limit > 0 else 0
        stats_text += f"\n{task.name}: Target {task.priority*100:.0f}%, Actual {actual_pct:.1f}%"

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
    TEST_COUNT = 10
    SUBPLOT_HEIGHT_IN = 3.5
    fig, axes = plt.subplots(TEST_COUNT, 1, figsize=(14, SUBPLOT_HEIGHT_IN * TEST_COUNT))

    # ==========================================
    # TEST 1: Original base behavior
    # ==========================================
    tasks_1 = [Task(name="Task A", priority=50, min_time=10), Task(name="Task B", priority=30, min_time=15), Task(name="Task C", priority=20, min_time=5)]
    time_limit_1 = 100
    schedule_1 = get_schedule(tasks_1, time_limit_1, starting_timeline=[])
    visualize_schedule(schedule_1, tasks_1, time_limit_1, axes[0], "Test 1: Original Scenario (No Starting Timeline)")

    # ==========================================
    # TEST 2: Ignoring Debt but Shifting Start
    # ==========================================
    tasks_2 = [Task(name="Task A", priority=50, min_time=10), Task(name="Task B", priority=50, min_time=10)]
    time_limit_2 = 100
    schedule_2 = get_schedule(tasks_2, time_limit_2, starting_timeline=[{'task': 'Task A', 'start': -1000, 'end': 0}])
    visualize_schedule(schedule_2, tasks_2, time_limit_2, axes[1], "Test 2: Massive Task A past debt decays")

    # ==========================================
    # TEST 3: Infinite Pattern pre-scheduled (Bidirectional Check)
    # ==========================================
    tasks_3 = [Task(name="Task A", priority=50, min_time=10), Task(name="Task B", priority=50, min_time=10)]
    time_limit_3 = 300
    starting_timeline_3 = []
    start_time = 0
    for i in range(5):
        starting_timeline_3.append({'task': 'Task A', 'start': start_time, 'end': start_time + 60})
        start_time += 60 + 120
        
    schedule_3 = get_schedule(tasks_3, time_limit_3, starting_timeline=starting_timeline_3)
    visualize_schedule(schedule_3, tasks_3, time_limit_3, axes[2], "Test 3: Symmetrical valley between two massive A blocks")

    # ==========================================
    # TEST 4: Double massive debt
    # ==========================================
    tasks_4 = [Task(name="Task A", priority=45, min_time=45), Task(name="Task B", priority=45, min_time=45), Task(name="Task C", priority=10, min_time=45)]
    time_limit_4 = 2000
    schedule_4 = get_schedule(tasks_4, time_limit_4, starting_timeline=[{'task': 'Task A', 'start': -1000, 'end': 0}, {'task': 'Task B', 'start': -1000, 'end': 0}])
    visualize_schedule(schedule_4, tasks_4, time_limit_4, axes[3], "Test 4: Massive Task A and B before t_now (C won't drift forever)")

    # ==========================================
    # TEST 5: Exclusive Periods
    # ==========================================
    tasks_5 = [Task(name="Task A", priority=50, min_time=10), Task(name="Task B", priority=50, min_time=10)]
    time_limit_5 = 100
    schedule_5 = get_schedule(tasks_5, time_limit_5, periods=[{'start': 0, 'end': 50, 'accepted_tasks': ['Task A']}, {'start': 50, 'end': 100, 'accepted_tasks': ['Task B']}])
    visualize_schedule(schedule_5, tasks_5, time_limit_5, axes[4], "Test 5: Exclusive Periods")

    # ==========================================
    # TEST 6: Complex Intersecting Periods
    # ==========================================
    tasks_6 = [Task(name="Task A", priority=40, min_time=10), Task(name="Task B", priority=40, min_time=10), Task(name="Task C", priority=20, min_time=10)]
    time_limit_6 = 150
    schedule_6 = get_schedule(tasks_6, time_limit_6, periods=[{'start': 0, 'end': 60, 'accepted_tasks': ['Task A', 'Task C']}, {'start': 60, 'end': 120, 'accepted_tasks': ['Task B', 'Task C']}, {'start': 120, 'end': 150, 'accepted_tasks': ['Task A', 'Task B', 'Task C']}])
    visualize_schedule(schedule_6, tasks_6, time_limit_6, axes[5], "Test 6: Overlapping Allow-lists")

    # ==========================================
    # TEST 7: Looming Future Debt Simulation
    # ==========================================
    tasks_7 = [Task(name="Task A", priority=50, min_time=10), Task(name="Task B", priority=50, min_time=10)]
    time_limit_7 = 100
    starting_timeline_7 = [{'task': 'Task A', 'start': 70, 'end': 130}]
    schedule_7 = get_schedule(tasks_7, time_limit_7, starting_timeline=starting_timeline_7)
    visualize_schedule(schedule_7, tasks_7, time_limit_7, axes[6], "Test 7: Future Debt Growth (B runs pre-emptively because A looms at t=70)")

    # ==========================================
    # TEST 8: Exponential Memory Loss Simulation
    # ==========================================
    tasks_8 = [Task(name="Task A", priority=50, min_time=10), Task(name="Task B", priority=50, min_time=10)]
    time_limit_8 = 100
    schedule_8 = get_schedule(tasks_8, time_limit_8, starting_timeline=[{'task': 'Task A', 'start': -100, 'end': 0}])
    visualize_schedule(schedule_8, tasks_8, time_limit_8, axes[7], "Test 8: Exponential Decay - B catches up quickly")

    # ==========================================
    # TEST 9: Long-term percentages adapt fluidly
    # ==========================================
    tasks_9 = [Task(name="Task A", priority=25, min_time=10), Task(name="Task B", priority=25, min_time=10), Task(name="Task C", priority=50, min_time=10)]
    time_limit_9 = 2000
    schedule_9 = get_schedule(tasks_9, time_limit_9, starting_timeline=[{'task': 'Task C', 'start': -1000, 'end': 0}])
    visualize_schedule(schedule_9, tasks_9, time_limit_9, axes[8], "Test 9: Long-term Fluid Decay Priority Matching")

    # ==========================================
    # TEST 10: Huge Placed Task Mid-Run
    # ==========================================
    tasks_10 = [Task(name="Task A", priority=80, min_time=10), Task(name="Task B", priority=20, min_time=10)]
    time_limit_10 = 500
    schedule_10 = get_schedule(tasks_10, time_limit_10, starting_timeline=[{'task': 'Task B', 'start': 200, 'end': 400}])
    visualize_schedule(schedule_10, tasks_10, time_limit_10, axes[9], "Test 10: Handling large mid-run pre-scheduled blocks smoothly")

    tests = [
        (tasks_1, time_limit_1, schedule_1),
        (tasks_2, time_limit_2, schedule_2),
        (tasks_3, time_limit_3, schedule_3),
        (tasks_4, time_limit_4, schedule_4),
        (tasks_5, time_limit_5, schedule_5),
        (tasks_6, time_limit_6, schedule_6),
        (tasks_7, time_limit_7, schedule_7),
        (tasks_8, time_limit_8, schedule_8),
        (tasks_9, time_limit_9, schedule_9),
        (tasks_10, time_limit_10, schedule_10),
    ]

    for idx, (t_list, t_limit, sched) in enumerate(tests):
        print(f"\n--- Schedule {idx+1} ---")
        for b in sched[:12]:
            print(f"[{b['start']:>3.1f}m -> {b['end']:>3.1f}m] {b['task']} (Type: {b.get('type')})")
        if len(sched) > 12: print("...")
        
        print("\nTarget vs Actual:")
        for task in t_list:
            actual_time = 0
            for b in sched:
                if b['task'] == task.name:
                    start_clamped = max(0, b['start'])
                    end_clamped = min(t_limit, b['end'])
                    if end_clamped > start_clamped:
                        actual_time += end_clamped - start_clamped
            
            actual_pct = (actual_time / t_limit * 100) if t_limit > 0 else 0
            print(f"  {task.name}: Target {task.priority*100:.0f}%, Actual {actual_pct:.1f}%")

    print("\n--- Functional API Test ---")
    my_func = SchedulerFunction(tasks_1, half_life=30.0)
    test_time = 17.5
    current_task = my_func(test_time)
    print(f"At t={test_time} minutes, the executing task is: {current_task.name if current_task else 'None'}")

    fig.subplots_adjust(left=0.08, right=0.80, top=0.96, bottom=0.04, hspace=0.85)
    show_scrollable(fig)