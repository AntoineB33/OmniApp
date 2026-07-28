import itertools
from collections.abc import Iterable
from enum import Enum

import pulp


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
        self.normalized_priority = 0.0

class TimeBlock:
    def __init__(self, duration: float, period_type: Period):
        self.duration = duration
        self.period_type = period_type

def solve_exact_schedule(tasks: list[Task], timeline: Iterable[TimeBlock], max_duration: int) -> list[dict]:
    # 1. Initialize Task Metrics
    total_priority = sum(t.priority for t in tasks)
    for t in tasks:
        t.normalized_priority = t.priority / total_priority

    # 2. Expand timeline into minute-by-minute array safely handling infinite iterables
    period_map = []
    for block in timeline:
        period_map.extend([block.period_type] * int(block.duration))
        if len(period_map) >= max_duration:
            break
    
    # Fallback just in case a finite, shorter list is passed
    if len(period_map) < max_duration:
        cycler = itertools.cycle(period_map)
        period_map.extend([next(cycler) for _ in range(max_duration - len(period_map))])
        
    period_map = period_map[:max_duration]

    active_time_so_far = []
    current_active = 0
    for p in period_map:
        if p != Period.INACTIVE:
            current_active += 1
        active_time_so_far.append(current_active)

    # 3. Setup MILP Problem
    prob = pulp.LpProblem("Exact_Schedule_Optimization", pulp.LpMinimize)

    T_range = range(max_duration)
    Tasks_range = range(len(tasks))

    # Variables
    x = pulp.LpVariable.dicts("x", (T_range, Tasks_range), cat='Binary')
    idle = pulp.LpVariable.dicts("idle", T_range, cat='Binary')
    start = pulp.LpVariable.dicts("start", (T_range, Tasks_range), cat='Binary')
    error = pulp.LpVariable.dicts("error", (T_range, Tasks_range), lowBound=0, cat='Continuous')
    acc_x = pulp.LpVariable.dicts("acc_x", (T_range, Tasks_range), cat='Continuous')

    # 4. Constraints
    for t in T_range:
        current_period = period_map[t]

        # 4a. State Constraints
        if current_period == Period.INACTIVE:
            prob += idle[t] == 0
            for i in Tasks_range:
                prob += x[t][i] == 0
        else:
            prob += pulp.lpSum([x[t][i] for i in Tasks_range]) + idle[t] == 1

            for i in Tasks_range:
                task = tasks[i]
                if current_period == Period.SCREEN and not task.needs_screen:
                    prob += x[t][i] == 0
                if current_period == Period.NO_SCREEN and task.needs_screen:
                    prob += x[t][i] == 0

        # 4b. Start Trigger & Min Time Constraints
        for i in Tasks_range:
            if t == 0:
                prob += start[t][i] >= x[t][i]
                prob += acc_x[t][i] == x[t][i]
            else:
                prob += start[t][i] >= x[t][i] - x[t-1][i]
                prob += acc_x[t][i] == acc_x[t-1][i] + x[t][i]

            task = tasks[i]
            min_k_bound = min(t + int(task.min_time), max_duration)
            required_blocks = min_k_bound - t
            
            prob += pulp.lpSum([x[k][i] for k in range(t, min_k_bound)]) >= required_blocks * start[t][i]

        # 4c. Discrepancy Tracking 
        for i in Tasks_range:
            target_time = active_time_so_far[t] * tasks[i].normalized_priority
            prob += error[t][i] >= acc_x[t][i] - target_time
            prob += error[t][i] >= target_time - acc_x[t][i]

    # 5. Objective Function
    prob += pulp.lpSum([error[t][i] for t in T_range for i in Tasks_range]) + pulp.lpSum([idle[t] * 1000 for t in T_range])

    # 6. Solve (Time limit of 30s)
    print(f"\nStarting exact optimization solver for {max_duration} minutes... (Max 30 seconds)")
    prob.solve(pulp.PULP_CBC_CMD(msg=True, timeLimit=30, gapRel=0.01))

    if pulp.LpStatus[prob.status] not in ['Optimal', 'Not Solved']:
        print(f"Solver stopped with status: {pulp.LpStatus[prob.status]}")
    elif pulp.LpStatus[prob.status] == 'Not Solved':
        print("Solver failed to find a valid schedule in time.")
        return []

    # 7. Reconstruct Schedule
    schedule = []
    current_task = None
    current_duration = 0

    for t in T_range:
        if period_map[t] == Period.INACTIVE:
            running = "INACTIVE"
        elif idle[t].varValue is not None and idle[t].varValue > 0.5:
            running = "IDLE / DEAD TIME"
        else:
            running = next((tasks[i].name for i in Tasks_range if x[t][i].varValue is not None and x[t][i].varValue > 0.5), "IDLE / DEAD TIME")

        if running == current_task:
            current_duration += 1
        else:
            if current_task is not None:
                schedule.append({
                    "task": current_task,
                    "duration": current_duration,
                    "type": period_map[t-1].name
                })
            current_task = running
            current_duration = 1

    if current_task is not None:
        schedule.append({
            "task": current_task,
            "duration": current_duration,
            "type": period_map[max_duration-1].name
        })

    return schedule


def print_colored_timeline(title: str, schedule: list[dict]):
    COLORS = [
        '\033[44m', '\033[42m', '\033[45m', '\033[43m', '\033[46m',
    ]
    INACTIVE_COLOR = '\033[100m'
    IDLE_COLOR = '\033[41m'
    RESET = '\033[0m'

    if not schedule:
        return

    total_time = sum(entry["duration"] for entry in schedule)
    visual_width = 80
    task_colors = {}
    color_idx = 0

    for entry in schedule:
        name = entry["task"]
        if name not in ["INACTIVE", "IDLE / DEAD TIME"] and name not in task_colors:
            task_colors[name] = COLORS[color_idx % len(COLORS)]
            color_idx += 1

    print(f"\n{'-' * visual_width}")
    print(f"EXACT SOLVER: {title}")
    print(f"{'-' * visual_width}")

    timeline_bar = ""
    chars_used = 0

    for i, entry in enumerate(schedule):
        name = entry["task"]
        duration = entry["duration"]
        period_type = entry.get("type", "")

        color = INACTIVE_COLOR if name == "INACTIVE" else (IDLE_COLOR if name == "IDLE / DEAD TIME" else task_colors[name])

        if i == len(schedule) - 1:
            chars = visual_width - chars_used
        else:
            chars = round((duration / total_time) * visual_width)
            chars_used += chars

        fill_char = '/' if period_type == "NO_SCREEN" else ' '
        if fill_char == '/':
            timeline_bar += f"{color}\033[30m{fill_char * chars}{RESET}"
        else:
            timeline_bar += f"{color}{fill_char * chars}{RESET}"

    print(timeline_bar)
    print()

    legend_items = [f"\033[47m\033[30m///{RESET} NO SCREEN"]
    if any(e["task"] == "INACTIVE" for e in schedule):
        legend_items.append(f"{INACTIVE_COLOR}  {RESET} INACTIVE")
    if any(e["task"] == "IDLE / DEAD TIME" for e in schedule):
        legend_items.append(f"{IDLE_COLOR}  {RESET} IDLE")

    for task_name, color in task_colors.items():
        task_time = sum(e["duration"] for e in schedule if e["task"] == task_name)
        legend_items.append(f"{color}  {RESET} {task_name} ({task_time:.1f}m)")

    for i in range(0, len(legend_items), 3):
        print(" | ".join(legend_items[i:i+3]))
    print(f"Total Duration: {total_time:.1f} minutes")
    print(f"{'-' * visual_width}\n")


if __name__ == "__main__":

    # EXAMPLE 1
    tasks_1 = [
        Task("Task A (7m min)", priority=50, min_time=7, needs_screen=True),
        Task("Task B (1h min)", priority=50, min_time=60, needs_screen=True)
    ]
    base_pattern_both = [TimeBlock(120, Period.BOTH)]
    schedule_exact_1 = solve_exact_schedule(tasks_1, base_pattern_both, max_duration=240)
    print_colored_timeline("4-Hour Exact Optimization", schedule_exact_1)


    # EXAMPLE 3: One-off starting timeline followed by an infinite loop
    tasks_3 = [
        Task("a", priority=45, min_time=45, needs_screen=True),
        Task("b", priority=45, min_time=45, needs_screen=True),
        Task("c", priority=10, min_time=45, needs_screen=True)
    ]
    
    # 1. Define the one-off initial pattern (runs exactly once)
    initial_timeline = [
        TimeBlock(120, Period.INACTIVE),  # E.g., system startup or morning routine
        TimeBlock(60, Period.SCREEN)      # E.g., forced initial catch-up window
    ]
    
    # 2. Define the pattern that will repeat forever
    repeating_pattern = [
        TimeBlock(180, Period.BOTH),      # 3 hours of work
        TimeBlock(30, Period.INACTIVE)    # 30 minute break
    ]
    
    # 3. Chain them together
    combined_timeline = itertools.chain(
        initial_timeline, 
        itertools.cycle(repeating_pattern)
    )
    
    # Run the exact solver for a bounded 12-hour (720 min) window to capture the loop 
    schedule_exact_3 = solve_exact_schedule(tasks_3, combined_timeline, max_duration=720)
    
    print_colored_timeline("Mixed Timeline - One-Off Start + Infinite Loop (720m bounded)", schedule_exact_3)