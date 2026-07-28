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


def schedule_timeline(tasks: list[Task], timeline: list[TimeBlock]) -> list[dict]:
    init_task_metrics(tasks)
    schedule = []

    for block_idx, block in enumerate(timeline):
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

    print(f"Total Timeline Duration: {total_time:.1f} minutes")
    print(f"{'-' * visual_width}\n")


# --- Run Examples ---
if __name__ == "__main__":

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
        Task("Task Z (Both)", priority=34, min_time=30, needs_screen=False)
    ]
    timeline_3 = [
        TimeBlock(120, Period.SCREEN),
        TimeBlock(120, Period.NO_SCREEN),
        TimeBlock(180, Period.BOTH)
    ]
    schedule_3 = schedule_timeline(tasks_3, timeline_3)
    print_colored_timeline("The Catch-Up Effect (Starvation Credit)", schedule_3)