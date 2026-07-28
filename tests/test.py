from enum import Enum
from typing import List, Optional

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
        
        # Scheduling metrics
        self.normalized_priority = 0.0
        self.ideal_cycle_allocation = 0.0
        self.total_scheduled_time = 0.0

class TimeBlock:
    def __init__(self, duration: float, period_type: Period):
        self.duration = duration
        self.period_type = period_type

def init_task_metrics(tasks: List[Task]):
    """Calculates the ideal cycle allocation to minimize variance, just like before."""
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

# --- Example Run ---
if __name__ == "__main__":
    my_tasks = [
        Task("Task A (Screen)", priority=50, min_time=7, needs_screen=True),
        Task("Task B (No Screen)", priority=50, min_time=60, needs_screen=False)
    ]
    
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