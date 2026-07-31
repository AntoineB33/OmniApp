import math
import matplotlib.colors as mcolors
import matplotlib.pyplot as plt


class Task:
    def __init__(self, name, priority, min_time):
        self.name = name
        self.priority = priority / 100.0  # Normalize percentage to 0-1
        self.min_time = min_time

def infinite_scheduler(tasks, starting_timeline=None, periods=None):
    """
    Generator that yields the next scheduled task indefinitely.
    
    Debt caused by ANY task (whether in the starting_timeline, pre-scheduled, 
    or dynamically placed by the scheduler itself) decays exponentially.
    - Past executions decay as we move forward.
    - Future pre-scheduled executions radiate debt backwards, growing as we approach them.
    """
    # past_debt tracks the integral of e^{-k(t - τ)} for all executed tasks (DECAYS)
    past_debt = {task.name: 0.0 for task in tasks}
    future_pre = []

    k = math.log(2) / HALF_LIFE if HALF_LIFE else 0.0

    # 1. Initialize past_debt at t=0 and separate future blocks
    if starting_timeline:
        for b in starting_timeline:
            t_name = b['task']
            if t_name not in past_debt:
                continue
            
            if b['end'] <= 0:
                # Completely in the past
                if k > 0:
                    past_debt[t_name] += (math.exp(k * b['end']) - math.exp(k * b['start'])) / k
                else:
                    past_debt[t_name] += (b['end'] - b['start'])
            elif b['start'] < 0:
                # Straddling t=0
                if k > 0:
                    past_debt[t_name] += (1.0 - math.exp(k * b['start'])) / k
                else:
                    past_debt[t_name] += (0 - b['start'])
                future_pre.append({'task': t_name, 'start': 0, 'end': b['end']})
            else:
                # Completely in the future
                future_pre.append(b)
            
    future_pre.sort(key=lambda x: x['start'])
    
    if periods is not None:
        periods = sorted(periods, key=lambda x: x['start'])
    
    current_time = 0.0
    
    while True:
        # Check if current_time falls inside a pre-scheduled block
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
            
            # Advance time and accumulate past_debt
            if k > 0:
                decay = math.exp(-k * dur)
                for t_name in past_debt:
                    past_debt[t_name] *= decay
                if overlapping_block['task'] in past_debt:
                    past_debt[overlapping_block['task']] += (1 - decay) / k
            else:
                if overlapping_block['task'] in past_debt:
                    past_debt[overlapping_block['task']] += dur
                        
            current_time = overlapping_block['end']
            continue
            
        # We are in a gap. Find how long the gap lasts until the next pre-scheduled block
        next_start = float('inf')
        for b in future_pre:
            if b['start'] > current_time:
                next_start = min(next_start, b['start'])
                
        # Determine if we are inside a valid period and which tasks are allowed
        accepted_tasks = [t.name for t in tasks]
        current_period_end = float('inf')
        
        if periods is not None:
            current_p = None
            next_p_start = float('inf')
            
            for p in periods:
                if p['start'] <= current_time < p['end']:
                    current_p = p
                    break
                elif p['start'] > current_time:
                    next_p_start = min(next_p_start, p['start'])
                    
            if current_p:
                accepted_tasks = current_p['accepted_tasks']
                current_period_end = current_p['end']
            else:
                # Outside any valid period. Advance to the next event.
                advance_to = min(next_start, next_p_start)
                if advance_to == float('inf'):
                    break
                
                dur = advance_to - current_time
                if k > 0:
                    decay = math.exp(-k * dur)
                    for t_name in past_debt:
                        past_debt[t_name] *= decay
                        
                current_time = advance_to
                continue

        # Clamp the gap by the end of the current period
        gap = min(next_start, current_period_end) - current_time
        valid_tasks = [t for t in tasks if t.min_time <= gap and t.name in accepted_tasks]
        
        if not valid_tasks:
            advance_to = min(next_start, current_period_end)
            if advance_to == float('inf'):
                break 
            
            dur = advance_to - current_time
            if k > 0:
                decay = math.exp(-k * dur)
                for t_name in past_debt:
                    past_debt[t_name] *= decay
                    
            current_time = advance_to
            continue
            
        # Calculate Total Debt = Decaying Past Debt + Decaying Future Pre-scheduled Debt
        def get_total_debt(t_name):
            f_debt = 0.0
            if k > 0:
                for b in future_pre:
                    if b['task'] == t_name and b['end'] > current_time:
                        s = max(current_time, b['start'])
                        e = b['end']
                        f_debt += (math.exp(-k * (s - current_time)) - math.exp(-k * (e - current_time))) / k
            else:
                for b in future_pre:
                    if b['task'] == t_name and b['end'] > current_time:
                        f_debt += (b['end'] - max(current_time, b['start']))
                        
            return past_debt[t_name] + f_debt

        # Pick the most starved task based entirely on the unified decaying debt
        best_task = min(
            valid_tasks,
            key=lambda t: get_total_debt(t.name) / t.priority
        )
        
        duration = best_task.min_time
        min_valid = min(t.min_time for t in valid_tasks)
        
        # Avoid leaving unschedulable microscopic slivers of time in the gap
        if gap - duration < min_valid and gap >= duration:
            duration = gap
            
        yield {
            'task': best_task.name,
            'start': current_time,
            'duration': duration,
            'end': current_time + duration,
            'type': 'scheduled'
        }
        
        # Advance time and decay ALL past debt
        if k > 0:
            decay = math.exp(-k * duration)
            for t_name in past_debt:
                past_debt[t_name] *= decay
            
            # The scheduler's ongoing executions add equivalent decaying debt
            past_debt[best_task.name] += (1 - decay) / k
        else:
            past_debt[best_task.name] += duration
            
        current_time += duration


def get_schedule(tasks, time_limit, starting_timeline=None, periods=None):
    scheduler = infinite_scheduler(tasks, starting_timeline, periods)
    schedule = []
    for block in scheduler:
        if block['start'] >= time_limit:
            break
        schedule.append(block)
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
    # Task A will monopolize time from 70 to 130. 
    # Notice how B executes heavily right BEFORE 70, due to Task A radiating future debt.
    starting_timeline_7 = [{'task': 'Task A', 'start': 70, 'end': 130}]
    schedule_7 = get_schedule(tasks_7, time_limit_7, starting_timeline=starting_timeline_7)
    visualize_schedule(schedule_7, tasks_7, time_limit_7, axes[6], "Test 7: Future Debt Growth (B runs pre-emptively because A looms at t=70)")

    # ==========================================
    # TEST 8: Exponential Memory Loss Simulation
    # ==========================================
    tasks_8 = [Task(name="Task A", priority=50, min_time=10), Task(name="Task B", priority=50, min_time=10)]
    time_limit_8 = 100
    # A dominated heavily just prior to t=0, but exponential decay means the scheduler rapidly 
    # forgets the past debt and resumes natural swapping behavior.
    schedule_8 = get_schedule(tasks_8, time_limit_8, starting_timeline=[{'task': 'Task A', 'start': -100, 'end': 0}])
    visualize_schedule(schedule_8, tasks_8, time_limit_8, axes[7], "Test 8: Exponential Decay - B catches up quickly")

    # ==========================================
    # TEST 9: Long-term percentages adapt fluidly
    # ==========================================
    tasks_9 = [Task(name="Task A", priority=25, min_time=10), Task(name="Task B", priority=25, min_time=10), Task(name="Task C", priority=50, min_time=10)]
    time_limit_9 = 2000
    # Because all debts dynamically decay now, the percentages over a very long time scale adapt smoothly
    # to recent states rather than obsessively tracking absolute long-term lifetimes.
    schedule_9 = get_schedule(tasks_9, time_limit_9, starting_timeline=[{'task': 'Task C', 'start': -1000, 'end': 0}])
    visualize_schedule(schedule_9, tasks_9, time_limit_9, axes[8], "Test 9: Long-term Fluid Decay Priority Matching")

    # ==========================================
    # TEST 10: Huge Placed Task Mid-Run
    # ==========================================
    tasks_10 = [Task(name="Task A", priority=80, min_time=10), Task(name="Task B", priority=20, min_time=10)]
    time_limit_10 = 500
    # Task B is pre-scheduled right in the middle for a huge chunk (200 minutes). 
    # Its placed future debt will starve it slightly before it happens, and its placed past debt will decay away afterwards.
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

    fig.subplots_adjust(left=0.08, right=0.80, top=0.96, bottom=0.04, hspace=0.85)
    show_scrollable(fig)