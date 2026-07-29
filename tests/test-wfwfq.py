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

        # History tracking for tie-breaking and priority percentage
        self.allocated = 0.0


class TimeBlock:
    def __init__(self, duration: float, period_type: Period, forced_task: 'Task | None' = None):
        self.duration = duration
        self.period_type = period_type
        self.forced_task = forced_task


def is_task_valid_for_period(task: Task, period_type: Period) -> bool:
    if period_type == Period.INACTIVE:
        return False
    if period_type == Period.BOTH:
        return True
    if period_type == Period.SCREEN and task.needs_screen:
        return True
    return bool(period_type == Period.NO_SCREEN and not task.needs_screen)


class TimelineStream:
    def __init__(self, block_iterable: Iterable[TimeBlock]):
        self.iterator = iter(block_iterable)
        self.current_t = 0.0
        self.blocks = []

    def get_block_at(self, t: float) -> dict | None:
        while not self.blocks or self.blocks[-1]['end'] <= t:
            try:
                b = next(self.iterator)
                start = self.current_t
                end = start + b.duration
                self.blocks.append({
                    "start": start,
                    "end": end,
                    "duration": b.duration,
                    "type": b.period_type,
                    "forced_task": b.forced_task
                })
                self.current_t = end
            except StopIteration:
                break
        
        for b in self.blocks:
            if b['start'] <= t < b['end']:
                return b
        return None


def sub_scheduler(tasks: list[Task], stream: TimelineStream, t_i: float) -> tuple[list[dict], float]:
    """
    Calculates the schedule for the window from t_i to +infinity, returning ONLY 
    the scheduling of the FIRST scheduled task up to when it reaches its minimum time.
    It can be interrupted by already placed tasks or incompatible periods.
    """
    t = t_i
    chosen_task = None
    accumulated = 0.0
    schedule = []
    
    while True:
        b = stream.get_block_at(t)
        if not b:
            break  # Timeline exhausted
            
        block_end = b['end']
        duration = block_end - t
        
        if b['forced_task']:
            # Forced tasks strictly interrupt
            schedule.append({
                "task": b['forced_task'].name,
                "duration": duration,
                "type": "PRE-PLACED"
            })
            if b['forced_task'] in tasks:
                b['forced_task'].allocated += duration
            t += duration
            continue
            
        if b['type'] == Period.INACTIVE:
            schedule.append({
                "task": "INACTIVE",
                "duration": duration,
                "type": "INACTIVE"
            })
            t += duration
            continue
            
        if chosen_task is None:
            # We must select the next task to schedule. 
            valid_tasks = [tk for tk in tasks if is_task_valid_for_period(tk, b['type'])]
            
            if not valid_tasks:
                schedule.append({
                    "task": "IDLE / DEAD TIME",
                    "duration": duration,
                    "type": b['type'].name
                })
                t += duration
                continue
                
            # Pick task minimizing the 2d graph integral absolute difference 
            # (which implies lowest presence percentage vs priority).
            def get_score(tk):
                if tk.priority == 0: return float('inf')
                return tk.allocated / tk.priority
                
            # Tie breaker: looks at presence time in t < t_i (tk.allocated) -> lower is better.
            valid_tasks.sort(key=lambda tk: (get_score(tk), tk.allocated))
            chosen_task = valid_tasks[0]
            accumulated = 0.0
            
        # A task has been chosen, it locks non-preemptively until its min_time is reached.
        if is_task_valid_for_period(chosen_task, b['type']):
            run_time = min(duration, chosen_task.min_time - accumulated)
            schedule.append({
                "task": chosen_task.name,
                "duration": run_time,
                "type": b['type'].name
            })
            chosen_task.allocated += run_time
            accumulated += run_time
            t += run_time
            
            if accumulated >= chosen_task.min_time:
                # The chosen task has fulfilled its minimum time. We return the schedule chunk.
                break
        else:
            # Locked but invalid in this period; cannot schedule another task. Wait it out.
            schedule.append({
                "task": f"IDLE (Waiting for {chosen_task.name})",
                "duration": duration,
                "type": b['type'].name
            })
            t += duration
            
    return schedule, t


def schedule_timeline_raw(tasks: list[Task], timeline: Iterable[TimeBlock]) -> Iterator[dict]:
    stream = TimelineStream(timeline)
    
    for tk in tasks:
        tk.allocated = 0.0
        
    t_now = 0.0
    t_i = t_now
    
    # 168h limit (10080 minutes)
    limit_minutes = 168 * 60.0
    
    while t_i - t_now < limit_minutes:
        schedule_chunk, t_next = sub_scheduler(tasks, stream, t_i)
        if not schedule_chunk:
            break
            
        yield from schedule_chunk
            
        # t_i becomes the end of this added schedule
        t_i = t_next


def schedule_timeline(tasks: list[Task], timeline: Iterable[TimeBlock]) -> Iterator[dict]:
    """Wraps the raw scheduler to merge contiguous identical yields for cleaner output."""
    prev = None
    for item in schedule_timeline_raw(tasks, timeline):
        if prev is None:
            prev = item
        elif prev["task"] == item["task"] and prev["type"] == item["type"]:
            prev["duration"] += item["duration"]
        else:
            yield prev
            prev = item
    if prev is not None:
        yield prev


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
    if total_time == 0: 
        return
        
    visual_width = 80

    task_colors = {}
    color_idx = 0

    for entry in schedule:
        name = entry["task"]
        if name not in ["INACTIVE", "IDLE / DEAD TIME"] and not name.startswith("IDLE (") and name not in task_colors:
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
        elif name == "IDLE / DEAD TIME" or name.startswith("IDLE ("):
            color = IDLE_COLOR
        else:
            color = task_colors.get(name, RESET)

        if i == len(schedule) - 1:
            chars = visual_width - chars_used
        else:
            chars = round((duration / total_time) * visual_width)
            chars_used += chars

        if chars > 0:
            fill_char = '/' if period_type == "NO_SCREEN" else ' '
            if fill_char == '/':
                timeline_bar += f"{color}\033[30m{fill_char * chars}{RESET}"
            else:
                timeline_bar += f"{color}{fill_char * chars}{RESET}"

    print(timeline_bar)
    print()

    # Build legend
    legend_items = []
    legend_items.append(f"\033[47m\033[30m///{RESET} NO SCREEN")

    if any(e["task"] == "INACTIVE" for e in schedule):
        legend_items.append(f"{INACTIVE_COLOR}  {RESET} INACTIVE")
    if any(e["task"].startswith("IDLE") for e in schedule):
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

    # # EXAMPLE 1: Infinite Timeline, 1h/1h perfect balancing
    # tasks_1 = [
    #     Task("Task A (7m min)", priority=50, min_time=7, needs_screen=True),
    #     Task("Task B (1h min)", priority=50, min_time=60, needs_screen=False)
    # ]
    # base_pattern_1 = [
    #     TimeBlock(60, Period.BOTH),
    #     TimeBlock(60, Period.NO_SCREEN)
    # ]
    # scheduler_1 = schedule_timeline(tasks_1, itertools.cycle(base_pattern_1))
    # schedule_1_slice = list(itertools.islice(scheduler_1, 8))
    # print_colored_timeline("WF²Q Continuous Lookahead - Perfect 1h/1h Balancing", schedule_1_slice)

    # # EXAMPLE 2: Custom Daily Pattern
    # tasks_2 = [
    #     Task("Deep Work", priority=60, min_time=90, needs_screen=True),
    #     Task("Reading", priority=20, min_time=45, needs_screen=False),
    #     Task("Quick Chores", priority=20, min_time=15, needs_screen=False)
    # ]
    # base_pattern_2 = [
    #     TimeBlock(120, Period.SCREEN),
    #     TimeBlock(60, Period.NO_SCREEN),
    #     TimeBlock(420, Period.BOTH),
    #     TimeBlock(30, Period.INACTIVE)
    # ]
    # scheduler_2 = schedule_timeline(tasks_2, itertools.cycle(base_pattern_2))
    # schedule_2_slice = list(itertools.islice(scheduler_2, 30))
    # print_colored_timeline("WF²Q Continuous Lookahead - Custom Daily Pattern", schedule_2_slice)

    # # EXAMPLE 3: One-off initialization followed by an infinite loop
    # tasks_3 = [
    #     Task("Task X", priority=45, min_time=45, needs_screen=True),
    #     Task("Task Y", priority=45, min_time=45, needs_screen=True),
    #     Task("Task Z", priority=10, min_time=45, needs_screen=True)
    # ]
    # initial_timeline = [
    #     TimeBlock(120, Period.INACTIVE),
    #     TimeBlock(60, Period.SCREEN)
    # ]
    # repeating_pattern = [
    #     TimeBlock(180, Period.BOTH),
    #     TimeBlock(30, Period.INACTIVE)
    # ]
    # combined_timeline = itertools.chain(initial_timeline, itertools.cycle(repeating_pattern))
    # scheduler_3 = schedule_timeline(tasks_3, combined_timeline)
    # schedule_3_slice = list(itertools.islice(scheduler_3, 15))
    # print_colored_timeline("Mixed Timeline - One-Off Start + Infinite Loop", schedule_3_slice)

    # EXAMPLE 4
    # Define tasks
    t_deep_work = Task("Deep Work", priority=50, min_time=45, needs_screen=True)
    t_reading = Task("Reading", priority=50, min_time=45, needs_screen=True)
    
    tasks_list = [t_deep_work, t_reading]

    # Create a timeline with a pre-placed task
    timeline = [
        # Hardcoded: 120 mins of Deep Work exactly at the start
        TimeBlock(120, Period.SCREEN, forced_task=t_deep_work),
        TimeBlock(60, Period.NO_SCREEN),
        TimeBlock(1800, Period.BOTH)
    ]
    
    scheduler = schedule_timeline(tasks_list, timeline)
    schedule_4_slice = list(itertools.islice(scheduler, 10))
    print_colored_timeline("Forced Task Initialization", schedule_4_slice)