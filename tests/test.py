from enum import Enum
<<<<<<< HEAD
from typing import List
=======
from typing import List, Optional
>>>>>>> b7b92aec3a94c50dc41a0fc65a1232710a7eed50

class Period(Enum):
    INACTIVE = 0
    SCREEN = 1
    NO_SCREEN = 2
    BOTH = 3

class Task:
    def __init__(self, name: str, priority: float, min_time: float, needs_screen: bool):
        self.name = name
        self.priority = priority
        self.min_time = min_time
        self.needs_screen = needs_screen
        
<<<<<<< HEAD
=======
        # Scheduling metrics
>>>>>>> b7b92aec3a94c50dc41a0fc65a1232710a7eed50
        self.normalized_priority = 0.0
        self.ideal_cycle_allocation = 0.0
        self.total_scheduled_time = 0.0

class TimeBlock:
    def __init__(self, duration: float, period_type: Period):
        self.duration = duration
        self.period_type = period_type

def init_task_metrics(tasks: List[Task]):
<<<<<<< HEAD
=======
    """Calculates the ideal cycle allocation to minimize variance, just like before."""
>>>>>>> b7b92aec3a94c50dc41a0fc65a1232710a7eed50
    total_priority = sum(t.priority for t in tasks)
    global_cycle_time = 0.0
    
    for t in tasks:
        t.normalized_priority = t.priority / total_priority
        required_cycle = t.min_time / t.normalized_priority
        if required_cycle > global_cycle_time:
            global_cycle_time = required_cycle
            
    for t in tasks:
        t.ideal_cycle_allocation = global_cycle_time * t.normalized_priority

def is_task_valid_for_period(task: Task, period_type: Period) -> bool:
    if period_type == Period.INACTIVE:
        return False
    if period_type == Period.BOTH:
        return True
    if period_type == Period.SCREEN and task.needs_screen:
        return True
    if period_type == Period.NO_SCREEN and not task.needs_screen:
        return True
    return False

def schedule_timeline(tasks: List[Task], timeline: List[TimeBlock]) -> List[dict]:
    init_task_metrics(tasks)
    schedule = []
<<<<<<< HEAD
    
    for block_idx, block in enumerate(timeline):
        # Handle inactive blocks immediately
        if block.period_type == Period.INACTIVE:
            schedule.append({
                "task": "INACTIVE", 
                "duration": block.duration, 
                "block": block_idx, 
                "type": "INACTIVE"
            })
            continue
            
        remaining_time = block.duration
        while remaining_time > 0:
            valid_tasks = [
                t for t in tasks 
                if is_task_valid_for_period(t, block.period_type) 
                and t.min_time <= remaining_time
            ]
            
            if not valid_tasks:
                schedule.append({
                    "task": "IDLE / DEAD TIME", 
                    "duration": remaining_time, 
                    "block": block_idx, 
                    "type": block.period_type.name
                })
                break
                
            valid_tasks.sort(key=lambda t: t.total_scheduled_time / t.normalized_priority)
            selected_task = valid_tasks[0]
            
            duration_to_schedule = max(selected_task.min_time, selected_task.ideal_cycle_allocation)
            duration_to_schedule = min(duration_to_schedule, remaining_time)
            
            schedule.append({
                "task": selected_task.name, 
                "duration": duration_to_schedule,
                "block": block_idx,
                "type": block.period_type.name
            })
            
            selected_task.total_scheduled_time += duration_to_schedule
            remaining_time -= duration_to_schedule
            
    return schedule

def print_colored_timeline(title: str, schedule: List[dict]):
    COLORS = [
        '\033[44m', # Blue
        '\033[42m', # Green
        '\033[45m', # Magenta
        '\033[43m', # Yellow
        '\033[46m', # Cyan
    ]
    INACTIVE_COLOR = '\033[100m' # Dark Gray
    IDLE_COLOR = '\033[41m'      # Red
    RESET = '\033[0m'
    
    total_time = sum(entry["duration"] for entry in schedule)
    visual_width = 80
    
    # Assign colors to specific tasks dynamically
    task_colors = {}
    color_idx = 0
    
    for entry in schedule:
        name = entry["task"]
        if name not in ["INACTIVE", "IDLE / DEAD TIME"] and name not in task_colors:
            task_colors[name] = COLORS[color_idx % len(COLORS)]
            color_idx += 1
            
    print(f"\n{'-' * visual_width}")
    print(f"EXAMPLE: {title}")
    print(f"{'-' * visual_width}")
    
    timeline_bar = ""
    chars_used = 0
    
    for i, entry in enumerate(schedule):
        name = entry["task"]
        duration = entry["duration"]
        
        if name == "INACTIVE":
            color = INACTIVE_COLOR
        elif name == "IDLE / DEAD TIME":
            color = IDLE_COLOR
        else:
            color = task_colors[name]
            
        if i == len(schedule) - 1:
            chars = visual_width - chars_used
        else:
            chars = int(round((duration / total_time) * visual_width))
            chars_used += chars
            
        timeline_bar += f"{color}{' ' * chars}{RESET}"
        
    print(timeline_bar)
    print()
    
    # Build legend
    legend_items = []
    if any(e["task"] == "INACTIVE" for e in schedule):
        legend_items.append(f"{INACTIVE_COLOR}  {RESET} INACTIVE")
    if any(e["task"] == "IDLE / DEAD TIME" for e in schedule):
        legend_items.append(f"{IDLE_COLOR}  {RESET} IDLE")
        
    for task_name, color in task_colors.items():
        # Sum total time for this task to show in legend
        task_time = sum(e["duration"] for e in schedule if e["task"] == task_name)
        legend_items.append(f"{color}  {RESET} {task_name} ({task_time:.1f}m)")
        
    # Group legend items into lines so it doesn't wrap messily
    for i in range(0, len(legend_items), 3):
        print(" | ".join(legend_items[i:i+3]))
        
    print(f"Total Timeline Duration: {total_time:.1f} minutes")
    print(f"{'-' * visual_width}\n")
=======
    
    for block_idx, block in enumerate(timeline):
        remaining_time = block.duration
        
        while remaining_time > 0:
            # 1. Filter valid tasks for this specific moment
            valid_tasks = [
                t for t in tasks 
                if is_task_valid_for_period(t, block.period_type) 
                and t.min_time <= remaining_time
            ]
            
            if not valid_tasks:
                # No tasks fit the remaining time or constraints. Time becomes idle.
                if remaining_time > 0 and block.period_type != Period.INACTIVE:
                    schedule.append({"task": "IDLE / DEAD TIME", "duration": remaining_time, "block": block_idx})
                break
                
            # 2. Find the most "starved" task
            # Score = Actual Time / Priority. Lower score means it needs time more urgently.
            valid_tasks.sort(key=lambda t: t.total_scheduled_time / t.normalized_priority)
            selected_task = valid_tasks[0]
            
            # 3. Determine duration (Scale up to ideal allocation if possible, bound by remaining time)
            duration_to_schedule = max(selected_task.min_time, selected_task.ideal_cycle_allocation)
            duration_to_schedule = min(duration_to_schedule, remaining_time)
            
            # 4. Book it
            schedule.append({
                "task": selected_task.name, 
                "duration": duration_to_schedule,
                "block": block_idx,
                "type": block.period_type.name
            })
            
            selected_task.total_scheduled_time += duration_to_schedule
            remaining_time -= duration_to_schedule
            
    return schedule
>>>>>>> b7b92aec3a94c50dc41a0fc65a1232710a7eed50

# --- Example Run ---
if __name__ == "__main__":
    my_tasks = [
        Task("Task A (Screen)", priority=50, min_time=7, needs_screen=True),
        Task("Task B (No Screen)", priority=50, min_time=60, needs_screen=False)
    ]
    
<<<<<<< HEAD
    # EXAMPLE 1: The Classic Day
    tasks_1 = [
        Task("Coding (Screen)", priority=50, min_time=30, needs_screen=True),
        Task("Reading (No Screen)", priority=50, min_time=60, needs_screen=False)
    ]
    timeline_1 = [
        TimeBlock(120, Period.INACTIVE),
        TimeBlock(90, Period.SCREEN),
        TimeBlock(90, Period.NO_SCREEN),
        TimeBlock(180, Period.BOTH)
    ]
    schedule_1 = schedule_timeline(tasks_1, timeline_1)
    print_colored_timeline("The Classic Day (Constraints matched nicely)", schedule_1)


    # EXAMPLE 2: Fragmented Availability (Creates Dead Time)
    tasks_2 = [
        Task("Deep Work (Screen)", priority=70, min_time=90, needs_screen=True),
        Task("Quick Tasks (Screen)", priority=30, min_time=15, needs_screen=True)
    ]
    # The 60m BOTH block is too short for Deep Work (requires 90m)! 
    # Quick Tasks will run, and the rest becomes dead time.
    timeline_2 = [
        TimeBlock(60, Period.BOTH),
        TimeBlock(30, Period.INACTIVE),
        TimeBlock(120, Period.BOTH)
    ]
    schedule_2 = schedule_timeline(tasks_2, timeline_2)
    print_colored_timeline("Fragmented Availability (Forces Dead Time)", schedule_2)


    # EXAMPLE 3: The Catch-Up Effect (Starvation)
    tasks_3 = [
        Task("Task X (Screen)", priority=33, min_time=20, needs_screen=True),
        Task("Task Y (No Screen)", priority=33, min_time=20, needs_screen=False),
        Task("Task Z (Both)", priority=34, min_time=30, needs_screen=False) # Highest priority
    ]
    # Task Z can run anywhere, but X hogs the first block, and Y hogs the second.
    # By the time the BOTH block arrives, Z is extremely "starved" and will dominate it.
    timeline_3 = [
        TimeBlock(120, Period.SCREEN),
        TimeBlock(120, Period.NO_SCREEN),
        TimeBlock(180, Period.BOTH) 
    ]
    schedule_3 = schedule_timeline(tasks_3, timeline_3)
    print_colored_timeline("The Catch-Up Effect (Starvation Credit)", schedule_3)
=======
    # A realistic day timeline
    my_timeline = [
        TimeBlock(duration=480, period_type=Period.INACTIVE),  # 8 hours sleep
        TimeBlock(duration=45, period_type=Period.NO_SCREEN),  # Morning routine (no screen)
        TimeBlock(duration=180, period_type=Period.BOTH),      # Work block (anything goes)
        TimeBlock(duration=30, period_type=Period.SCREEN)      # Short screen-only block
    ]
    
    result = schedule_timeline(my_tasks, my_timeline)
    
    print(f"{'BLOCK':<10} | {'CONDITION':<12} | {'TASK':<20} | {'DURATION'}")
    print("-" * 60)
    for entry in result:
        print(f"Block {entry.get('block', '-'):<4} | {entry.get('type', 'INACTIVE'):<12} | {entry['task']:<20} | {entry['duration']:.1f} min")
>>>>>>> b7b92aec3a94c50dc41a0fc65a1232710a7eed50
