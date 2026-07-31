import matplotlib.colors as mcolors
import matplotlib.pyplot as plt


class Task:
    def __init__(self, name, priority, min_time):
        self.name = name
        self.priority = priority / 100.0  # Normalize percentage to 0-1
        self.min_time = min_time

def infinite_scheduler(tasks, starting_timeline=None):
    """
    Generator that yields the next scheduled task indefinitely, respecting a pre-existing 
    starting timeline. 
    """
    historical_time = {task.name: 0.0 for task in tasks}
    future_pre = []

    # 1. Separate the timeline into "past" (for tie-breaking) and "future" (fixed schedule)
    if starting_timeline:
        for b in starting_timeline:
            task_name = b['task']
            if b['end'] <= 0:
                historical_time[task_name] += (b['end'] - b['start'])
            elif b['start'] < 0:
                historical_time[task_name] += (0 - b['start'])
                future_pre.append({'task': task_name, 'start': 0, 'end': b['end']})
            else:
                future_pre.append(b)
                
    future_pre.sort(key=lambda x: x['start'])
    
    # 2. Track allocated time strictly starting from t_now (t=0)
    allocated_time = {task.name: 0.0 for task in tasks}
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
            # Pre-scheduled tasks accumulate debt normally in the future
            allocated_time[overlapping_block['task']] += dur
            current_time = overlapping_block['end']
            continue
            
        # We are in a gap. Find how long the gap lasts until the next pre-scheduled block
        next_start = float('inf')
        for b in future_pre:
            if b['start'] > current_time:
                next_start = min(next_start, b['start'])
                
        gap = next_start - current_time
        
        # Only consider tasks that can actually fit in the gap
        valid_tasks = [t for t in tasks if t.min_time <= gap]
        
        if not valid_tasks:
            if next_start == float('inf'):
                break # No tasks fit and no future blocks exist
            current_time = next_start
            continue
            
        # Find the task that is currently most starved.
        # TIE-BREAKER: if (allocated/priority) is equal, look at historical time to ignore massive
        # past debt mathematically but still shift the starting task correctly.
        best_task = min(
            valid_tasks,
            key=lambda t: (
                allocated_time[t.name] / t.priority,
                historical_time[t.name] / t.priority
            )
        )
        
        duration = best_task.min_time
        min_valid = min(t.min_time for t in valid_tasks)
        
        # If the remaining gap after placing this task is too small for ANY valid task,
        # extend the current task to fill the gap. (Avoids dead space).
        if gap - duration < min_valid and gap >= duration:
            duration = gap
            
        yield {
            'task': best_task.name,
            'start': current_time,
            'duration': duration,
            'end': current_time + duration,
            'type': 'scheduled'
        }
        
        allocated_time[best_task.name] += duration
        current_time += duration

def get_schedule(tasks, time_limit, starting_timeline=None):
    """
    Extracts scheduled tasks up to a given time limit.
    """
    scheduler = infinite_scheduler(tasks, starting_timeline)
    schedule = []
    
    for block in scheduler:
        if block['start'] >= time_limit:
            break
        schedule.append(block)
        
    return schedule

def visualize_schedule(schedule, tasks, time_limit, ax, title):
    """
    Plots the timeline on the provided Matplotlib axis.
    """
    colors = list(mcolors.TABLEAU_COLORS.values())
    task_colors = {task.name: colors[i % len(colors)] for i, task in enumerate(tasks)}
    
    task_names = [t.name for t in tasks]
    y_ticks = []
    y_labels = []

    for i, name in enumerate(task_names):
        task_blocks = []
        for b in schedule:
            if b['task'] == name:
                # Clamp visual blocks so they don't stretch infinitely off-screen
                start_clamped = max(0, b['start'])
                end_clamped = min(time_limit, b['end'])
                if end_clamped > start_clamped:
                    task_blocks.append((start_clamped, end_clamped - start_clamped))
        
        y_pos = i * 10
        if task_blocks:
            ax.broken_barh(task_blocks, (y_pos + 1, 8), facecolors=task_colors[name], edgecolor='black')
        
        y_ticks.append(y_pos + 5)
        y_labels.append(name)

    # Calculate actual achieved percentages in this timeframe
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
    ax.text(1.02, 0.95, stats_text, transform=ax.transAxes, fontsize=10,
            verticalalignment='top', bbox=props)

if __name__ == "__main__":
    # Use a large vertical figure size to naturally fit the 4 subplots in the same window
    fig, axes = plt.subplots(4, 1, figsize=(14, 14))

    # ==========================================
    # TEST 1: Original base behavior
    # ==========================================
    tasks_1 = [
        Task(name="Task A", priority=50, min_time=10),
        Task(name="Task B", priority=30, min_time=15),
        Task(name="Task C", priority=20, min_time=5)
    ]
    time_limit_1 = 100
    schedule_1 = get_schedule(tasks_1, time_limit_1, starting_timeline=[])
    visualize_schedule(schedule_1, tasks_1, time_limit_1, axes[0], 
                       "Test 1: Original Scenario (No Starting Timeline)")

    # ==========================================
    # TEST 2: Ignoring Debt but Shifting Start
    # ==========================================
    tasks_2 = [
        Task(name="Task A", priority=50, min_time=10),
        Task(name="Task B", priority=50, min_time=10)
    ]
    time_limit_2 = 100
    # Task A has ran heavily before t_now=0
    starting_timeline_2 = [{'task': 'Task A', 'start': -1000, 'end': 0}]
    schedule_2 = get_schedule(tasks_2, time_limit_2, starting_timeline=starting_timeline_2)
    visualize_schedule(schedule_2, tasks_2, time_limit_2, axes[1], 
                       "Test 2: Massive Task A before t_now (Debt Ignored, Starts with B)")

    # ==========================================
    # TEST 3: Infinite Pattern pre-scheduled
    # ==========================================
    tasks_3 = [
        Task(name="Task A", priority=50, min_time=10),
        Task(name="Task B", priority=50, min_time=10)
    ]
    time_limit_3 = 200
    starting_timeline_3 = []
    # Pattern: Task A runs for 1h, then 15m gap (repeat infinitely)
    for i in range(5):
        start_time = i * 75
        starting_timeline_3.append({'task': 'Task A', 'start': start_time, 'end': start_time + 60})
        
    schedule_3 = get_schedule(tasks_3, time_limit_3, starting_timeline=starting_timeline_3)
    visualize_schedule(schedule_3, tasks_3, time_limit_3, axes[2], 
                       "Test 3: Pre-Scheduled Infinite Pattern (Scheduler fills gaps dynamically)")



    tasks_4 = [
        Task(name="Task A", priority=45, min_time=45),
        Task(name="Task B", priority=45, min_time=45),
        Task(name="Task C", priority=10, min_time=45)
    ]
    time_limit_4 = 200
    starting_timeline_4 = [
        {'task': 'Task A', 'start': -1000, 'end': 0},
        {'task': 'Task B', 'start': -1000, 'end': 0}
    ]
    schedule_4 = get_schedule(tasks_4, time_limit_4, starting_timeline=starting_timeline_4)
    visualize_schedule(schedule_4, tasks_4, time_limit_4, axes[3],
                       "Test 4: Massive Task A and B before t_now (Debt Ignored, Starts with C)")





    # Console output to verify block generation natively
    for idx, sched in enumerate([schedule_1, schedule_2, schedule_3, schedule_4]):
        print(f"\n--- Schedule {idx+1} ---")
        for b in sched[:8]:
            print(f"[{b['start']:>3}m -> {b['end']:>3}m] {b['task']} (Type: {b.get('type')})")
        if len(sched) > 8: print("...")

    # tight_layout() cannot see the stat boxes (they are drawn outside the axes at x=1.02),
    # so lay the grid out manually: a right gutter for the boxes and enough vertical
    # spacing that an x-label never collides with the title below it.
    fig.subplots_adjust(left=0.08, right=0.80, top=0.95, bottom=0.05, hspace=0.75)
    # By default, a 14x12 window will fit smoothly on standard modern displays.
    # Matplotlib's native UI allows zooming/panning instead of raw window scrollbars.
    plt.show()