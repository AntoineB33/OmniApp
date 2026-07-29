import itertools
from collections.abc import Iterable, Iterator
from enum import Enum


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
        self.ideal_cycle_allocation = 0.0
        self.total_scheduled_time = 0.0


class TimeBlock:
    def __init__(self, duration: float, period_type: Period):
        self.duration = duration
        self.period_type = period_type


def init_task_metrics(tasks: list[Task]):
    total_priority = sum(t.priority for t in tasks)
    global_cycle_time = 0.0

    for t in tasks:
        t.normalized_priority = t.priority / total_priority
        required_cycle = t.min_time / t.normalized_priority
        global_cycle_time = max(global_cycle_time, required_cycle)

    for t in tasks:
        t.ideal_cycle_allocation = global_cycle_time * t.normalized_priority


def is_task_valid_for_period(task: Task, period_type: Period) -> bool:
    if period_type == Period.INACTIVE:
        return False
    if period_type == Period.BOTH:
        return True
    if period_type == Period.SCREEN and task.needs_screen:
        return True
    return bool(period_type == Period.NO_SCREEN and not task.needs_screen)


def schedule_timeline(tasks: list[Task], timeline: Iterable[TimeBlock]) -> Iterator[dict]:
    init_task_metrics(tasks)
    
    for block_idx, block in enumerate(timeline):
        if block.period_type == Period.INACTIVE:
            yield {
                "task": "INACTIVE",
                "duration": block.duration,
                "block": block_idx,
                "type": "INACTIVE"
            }
            continue

        duration = int(block.duration)
        valid_tasks = [t for t in tasks if is_task_valid_for_period(t, block.period_type)]
        
        if not valid_tasks:
            yield {
                "task": "IDLE / DEAD TIME",
                "duration": block.duration,
                "block": block_idx,
                "type": block.period_type.name
            }
            continue

        # --- Knapsack / DP Look-Ahead Logic ---
        # State definition: dp[c] = (idle_time, score, list_of_allocations)
        # where `c` is the capacity evaluated so far.
        current_totals = {t.name: t.total_scheduled_time for t in tasks}
        total_active_so_far = sum(current_totals.values())
        
        dp = {0: (0, 0.0, [])}
        
        for c in range(1, duration + 1):
            # Option 1: Implicitly idle for this minute
            p_idle, p_err, p_alloc = dp[c - 1]
            best_idle = p_idle + 1
            best_err = p_err
            best_alloc = p_alloc
            
            # Option 2: Explore packing tasks into the block
            for t in valid_tasks:
                min_t = int(t.min_time)
                
                if c >= min_t:
                    # Look-ahead optimization: check all valid durations a task could occupy
                    for d in range(min_t, c + 1):
                        sub_idle, _, sub_alloc = dp[c - d]
                        new_alloc = sub_alloc + [(t.name, d)]
                        
                        # Score this allocation based on priority balance
                        temp_totals = current_totals.copy()
                        added_dur = 0
                        for n, dur in new_alloc:
                            temp_totals[n] = temp_totals.get(n, 0) + dur
                            added_dur += dur
                            
                        new_total = total_active_so_far + added_dur
                        err = 0.0
                        
                        if new_total > 0:
                            for tk in tasks:
                                target = tk.normalized_priority
                                actual = temp_totals.get(tk.name, 0.0) / new_total
                                err += (actual - target) ** 2
                                
                        # Fragmentation penalty: strongly prefer continuous allocations
                        err += len(new_alloc) * 0.0001
                        
                        # If better (primarily fewer idle minutes, secondarily better priority score)
                        if (sub_idle, err) < (best_idle, best_err):
                            best_idle = sub_idle
                            best_err = err
                            best_alloc = new_alloc
                            
            dp[c] = (best_idle, best_err, best_alloc)
            
        # The block is solved optimally
        final_idle, _, final_alloc = dp[duration]
        
        # Merge contiguous identical tasks to clean up output
        merged = []
        for name, dur in final_alloc:
            if merged and merged[-1][0] == name:
                merged[-1] = (name, merged[-1][1] + dur)
            else:
                merged.append((name, dur))
                
        # Emit scheduling and update global state
        for name, dur in merged:
            for t in tasks:
                if t.name == name:
                    t.total_scheduled_time += dur
                    break
                    
            yield {
                "task": name,
                "duration": dur,
                "block": block_idx,
                "type": block.period_type.name
            }
            
        if final_idle > 0:
            yield {
                "task": "IDLE / DEAD TIME",
                "duration": final_idle,
                "block": block_idx,
                "type": block.period_type.name
            }


def print_colored_timeline(title: str, schedule: list[dict]):
    COLORS = [
        '\033[44m',  # Blue
        '\033[42m',  # Green
        '\033[45m',  # Magenta
        '\033[43m',  # Yellow
        '\033[46m',  # Cyan
    ]
    INACTIVE_COLOR = '\033[100m'  # Dark Gray
    IDLE_COLOR = '\033[41m'       # Red
    RESET = '\033[0m'

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
    print(f"EXAMPLE: {title}")
    print(f"{'-' * visual_width}")

    timeline_bar = ""
    chars_used = 0

    for i, entry in enumerate(schedule):
        name = entry["task"]
        duration = entry["duration"]
        period_type = entry.get("type", "")

        if name == "INACTIVE":
            color = INACTIVE_COLOR
        elif name == "IDLE / DEAD TIME":
            color = IDLE_COLOR
        else:
            color = task_colors[name]

        if i == len(schedule) - 1:
            chars = visual_width - chars_used
        else:
            chars = round((duration / total_time) * visual_width)
            chars_used += chars

        # Add oblique lines for NO_SCREEN periods
        fill_char = '/' if period_type == "NO_SCREEN" else ' '

        if fill_char == '/':
            timeline_bar += f"{color}\033[30m{fill_char * chars}{RESET}"
        else:
            timeline_bar += f"{color}{fill_char * chars}{RESET}"

    print(timeline_bar)
    print()

    # Build legend
    legend_items = []
    legend_items.append(f"\033[47m\033[30m///{RESET} NO SCREEN ZONE")

    if any(e["task"] == "INACTIVE" for e in schedule):
        legend_items.append(f"{INACTIVE_COLOR}  {RESET} INACTIVE")
    if any(e["task"] == "IDLE / DEAD TIME" for e in schedule):
        legend_items.append(f"{IDLE_COLOR}  {RESET} IDLE")

    for task_name, color in task_colors.items():
        task_time = sum(e["duration"] for e in schedule if e["task"] == task_name)
        legend_items.append(f"{color}  {RESET} {task_name} ({task_time:.1f}m)")

    for i in range(0, len(legend_items), 3):
        print(" | ".join(legend_items[i:i+3]))

    print(f"Total Displayed Timeline Duration: {total_time:.1f} minutes")
    print(f"{'-' * visual_width}\n")


# --- Run Examples ---
if __name__ == "__main__":

    # EXAMPLE 1: The Requested 1h/1h Balancing Test on an Infinite Timeline
    tasks_1 = [
        Task("Task A (7m min)", priority=50, min_time=7, needs_screen=True),
        Task("Task B (1h min)", priority=50, min_time=60, needs_screen=False)
    ]
    
    base_pattern_1 = [
        TimeBlock(60, Period.BOTH),
        TimeBlock(60, Period.NO_SCREEN)
    ]
    infinite_timeline_1 = itertools.cycle(base_pattern_1)
    
    scheduler_1 = schedule_timeline(tasks_1, infinite_timeline_1)
    schedule_1_slice = list(itertools.islice(scheduler_1, 6))
    print_colored_timeline("Knapsack Lookahead - Perfect 1h/1h Balancing", schedule_1_slice)


    # EXAMPLE 2: A highly customized daily routine repeating infinitely
    tasks_2 = [
        Task("Deep Work", priority=60, min_time=90, needs_screen=True),
        Task("Reading", priority=20, min_time=45, needs_screen=False),
        Task("Quick Chores", priority=20, min_time=15, needs_screen=False)
    ]
    
    base_pattern_2 = [
        TimeBlock(120, Period.SCREEN),
        TimeBlock(60, Period.NO_SCREEN),
        TimeBlock(420, Period.BOTH),
        TimeBlock(30, Period.INACTIVE)
    ]
    infinite_timeline_2 = itertools.cycle(base_pattern_2)
    
    scheduler_2 = schedule_timeline(tasks_2, infinite_timeline_2)
    schedule_2_slice = list(itertools.islice(scheduler_2, 15))
    print_colored_timeline("Knapsack Lookahead - Custom Daily Pattern", schedule_2_slice)


    # EXAMPLE 3: One-off starting timeline followed by an infinite loop
    tasks_3 = [
        Task("a", priority=45, min_time=45, needs_screen=True),
        Task("b", priority=45, min_time=45, needs_screen=True),
        Task("c", priority=10, min_time=45, needs_screen=True)
    ]
    
    initial_timeline = [
        TimeBlock(120, Period.INACTIVE),
        TimeBlock(60, Period.SCREEN)
    ]
    
    repeating_pattern = [
        TimeBlock(180, Period.BOTH),
        TimeBlock(30, Period.INACTIVE)
    ]
    
    combined_timeline = itertools.chain(
        initial_timeline, 
        itertools.cycle(repeating_pattern)
    )
    
    scheduler_3 = schedule_timeline(tasks_3, combined_timeline)
    schedule_3_slice = list(itertools.islice(scheduler_3, 10))
    print_colored_timeline("Mixed Timeline - One-Off Start + Infinite Loop", schedule_3_slice)