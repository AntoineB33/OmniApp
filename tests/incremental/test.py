import matplotlib.colors as mcolors
import matplotlib.pyplot as plt


class Task:
    def __init__(self, name, priority, min_time):
        self.name = name
        self.priority = priority / 100.0  # Normalize percentage to 0-1
        self.min_time = min_time

def infinite_scheduler(tasks):
    """
    Generator that yields the next scheduled task indefinitely.
    Uses a stride-scheduling approach to maintain priority ratios.
    """
    # Track the total time allocated to each task
    allocated_time = {task.name: 0 for task in tasks}
    current_time = 0

    while True:
        # Find the task with the lowest (allocated_time / priority) ratio.
        # This identifies the task that is currently most "starved" of its target share.
        best_task = min(
            tasks, 
            key=lambda t: allocated_time[t.name] / t.priority
        )

        # Yield the scheduling block
        yield {
            'task': best_task.name,
            'start': current_time,
            'duration': best_task.min_time,
            'end': current_time + best_task.min_time
        }

        # Update the trackers
        allocated_time[best_task.name] += best_task.min_time
        current_time += best_task.min_time

def get_schedule(tasks, time_limit):
    """
    Extracts scheduled tasks from the infinite generator up to a time limit.
    """
    scheduler = infinite_scheduler(tasks)
    schedule = []
    
    for block in scheduler:
        if block['start'] >= time_limit:
            break
        schedule.append(block)
        
    return schedule

def visualize_schedule(schedule, tasks, time_limit):
    """
    Creates a Gantt chart to visualize the timeline.
    """
    _, ax = plt.subplots(figsize=(12, 4))
    
    # Assign distinct colors to tasks
    colors = list(mcolors.TABLEAU_COLORS.values())
    task_colors = {task.name: colors[i % len(colors)] for i, task in enumerate(tasks)}
    
    # Prepare data for broken_barh
    task_names = [t.name for t in tasks]
    y_ticks = []
    y_labels = []

    for i, name in enumerate(task_names):
        # Extract blocks for this specific task
        task_blocks = [(b['start'], b['duration']) for b in schedule if b['task'] == name]
        
        # Plot them on the y-axis
        y_pos = i * 10
        ax.broken_barh(task_blocks, (y_pos + 1, 8), facecolors=task_colors[name], edgecolor='black')
        
        y_ticks.append(y_pos + 5)
        y_labels.append(name)

    # Calculate actual achieved percentages in this timeframe
    total_time = schedule[-1]['end'] if schedule else 0
    stats_text = "Target vs Actual:\n"
    for task in tasks:
        actual_time = sum(b['duration'] for b in schedule if b['task'] == task.name)
        actual_pct = (actual_time / total_time * 100) if total_time > 0 else 0
        stats_text += f"{task.name}: Target {task.priority*100:.0f}%, Actual {actual_pct:.1f}%\n"

    # Formatting the plot
    ax.set_yticks(y_ticks)
    ax.set_yticklabels(y_labels)
    ax.set_xlabel('Time (minutes)')
    ax.set_title('Task Scheduler Timeline')
    ax.set_xlim(0, time_limit)
    ax.grid(True, axis='x', linestyle='--', alpha=0.7)
    
    # Add stats text box
    props = {'boxstyle': 'round', 'facecolor': 'wheat', 'alpha': 0.5}
    ax.text(1.02, 0.95, stats_text, transform=ax.transAxes, fontsize=10,
            verticalalignment='top', bbox=props)

    plt.tight_layout()
    plt.show()

if __name__ == "__main__":
    # Example constraints
    tasks = [
        Task(name="Task A", priority=50, min_time=10),
        Task(name="Task B", priority=30, min_time=15),
        Task(name="Task C", priority=20, min_time=5)
    ]
    
    total_observation_time = 100  # minutes

    # Generate the schedule
    schedule = get_schedule(tasks, total_observation_time)
    
    # Print the text timeline
    print("Timeline:")
    for block in schedule:
        print(f"[{block['start']:>3}m -> {block['end']:>3}m] {block['task']} (Duration: {block['duration']}m)")
        
    # Plot the result
    visualize_schedule(schedule, tasks, total_observation_time)