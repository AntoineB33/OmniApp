class Task:
    def __init__(self, name: str, priority: float, min_time: float):
        self.name = name
        self.priority = priority
        self.min_time = min_time
        self.normalized_priority = 0.0
        self.cycle_allocation = 0.0

def calculate_optimal_schedule(tasks: list[Task]) -> list[tuple[str, float]]:
    if not tasks:
        return []
        
    total_priority = sum(task.priority for task in tasks)
    for task in tasks:
        task.normalized_priority = task.priority / total_priority
        
    global_cycle_time = 0.0
    for task in tasks:
        required_cycle = task.min_time / task.normalized_priority
        if required_cycle > global_cycle_time:
            global_cycle_time = required_cycle
            
    schedule_cycle = []
    for task in tasks:
        task.cycle_allocation = global_cycle_time * task.normalized_priority
        schedule_cycle.append((task.name, task.cycle_allocation))
        
    return schedule_cycle

def print_colored_timeline(title: str, schedule_cycle: list[tuple[str, float]]):
    # ANSI background colors for terminal visualization
    COLORS = [
        '\033[44m', # Blue
        '\033[42m', # Green
        '\033[45m', # Magenta
        '\033[43m', # Yellow
        '\033[46m', # Cyan
        '\033[41m'  # Red
    ]
    RESET = '\033[0m'
    
    total_time = sum(duration for _, duration in schedule_cycle)
    visual_width = 80 # Total characters wide for the timeline bar
    
    print(f"\n{'-' * visual_width}")
    print(f"EXAMPLE: {title}")
    print(f"{'-' * visual_width}")
    
    # 1. Build and print the colored timeline bar
    timeline_bar = ""
    chars_used = 0
    
    for i, (name, duration) in enumerate(schedule_cycle):
        color = COLORS[i % len(COLORS)]
        
        # Calculate proportional width (handle rounding on the last element)
        if i == len(schedule_cycle) - 1:
            chars = visual_width - chars_used
        else:
            chars = int(round((duration / total_time) * visual_width))
            chars_used += chars
            
        timeline_bar += f"{color}{' ' * chars}{RESET}"
        
    print(timeline_bar)
    print()
    
    # 2. Print the legend below the timeline
    legend_items = []
    for i, (name, duration) in enumerate(schedule_cycle):
        color = COLORS[i % len(COLORS)]
        # Add color block, name, and allocated time
        legend_items.append(f"{color}  {RESET} {name} ({duration:.1f}m)")
        
    print(" | ".join(legend_items))
    print(f"Total Optimal Cycle Time: {total_time:.1f} minutes")
    print(f"{'-' * visual_width}\n")

# --- Run Examples ---
if __name__ == "__main__":
    
    # Example 1: The original example
    tasks_1 = [
        Task("Task A", priority=50, min_time=7),
        Task("Task B", priority=50, min_time=60)
    ]
    cycle_1 = calculate_optimal_schedule(tasks_1)
    print_colored_timeline("Balanced Priorities, Unbalanced Minimums", cycle_1)

    # Example 2: Three tasks with mixed priorities
    tasks_2 = [
        Task("Task X (20%)", priority=20, min_time=5),
        Task("Task Y (30%)", priority=30, min_time=15),  # This is the limiting task (15 / 0.3 = 50m cycle)
        Task("Task Z (50%)", priority=50, min_time=10)
    ]
    cycle_2 = calculate_optimal_schedule(tasks_2)
    print_colored_timeline("Three Tasks Mixed", cycle_2)

    # Example 3: Extreme Skew (High priority, fast min vs Low priority, slow min)
    tasks_3 = [
        Task("Fast/High Pri (90%)", priority=90, min_time=1),
        Task("Slow/Low Pri (10%)", priority=10, min_time=10) # Limits cycle to 100m total
    ]
    cycle_3 = calculate_optimal_schedule(tasks_3)
    print_colored_timeline("Extreme Skew", cycle_3)