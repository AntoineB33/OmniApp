import itertools
import math
from collections.abc import Callable, Iterable, Iterator
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

        # WF²Q State Tracking (Virtual Time)
        self.weight = 0.0      # Normalized priority (0.0 to 1.0)
        self.allocated = 0.0   # Total actual minutes this task has run


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
    """
    Wraps an infinite or finite timeline iterable.
    Provides continuous-time lookahead to ensure minimum times are respected
    even if they cross block boundaries.
    """
    def __init__(self, block_iterable: Iterable[TimeBlock]):
        self.iterator = iter(block_iterable)
        self.blocks = []
        self.cached_end_time = 0.0

    def _ensure_cached_up_to(self, target_t: float) -> bool:
        while self.cached_end_time <= target_t:
            try:
                b = next(self.iterator)
                self.blocks.append({
                    "type": b.period_type,
                    "start": self.cached_end_time,
                    "end": self.cached_end_time + b.duration,
                    "duration": b.duration,
                    "forced_task": b.forced_task  
                })
                self.cached_end_time += b.duration
            except StopIteration:
                return False
        return True

    def get_block_at(self, t: float) -> dict | None:
        self._ensure_cached_up_to(t)
        for b in self.blocks:
            if b["start"] <= t < b["end"]:
                return b
        return None

    def get_allowed_continuous(self, t: float, task: Task, is_valid_func: Callable) -> float:
        """Looks ahead to see how long `task` can run uninterrupted starting from `t`."""
        allowed = 0.0
        curr_t = t
        while True:
            self._ensure_cached_up_to(curr_t)
            b = None
            for blk in self.blocks:
                if blk["start"] <= curr_t < blk["end"]:
                    b = blk
                    break
            
            if not b or not is_valid_func(task, b["type"]):
                break
                
            duration_in_block = b["end"] - curr_t
            allowed += duration_in_block
            curr_t = b["end"]
            
            # Prevent infinite lookahead loops on 'BOTH' blocks
            if allowed > 14400: # Cap lookahead at 10 days
                return float('inf')
                
        return allowed

    def get_time_until_next_boundary(self, t: float) -> float:
        b = self.get_block_at(t)
        return b["end"] - t if b else 0.0


def side_scheduler(tasks: list[Task]) -> dict[str, float]:
    """
    Uses the scheduler logic on a clean timeline to determine the 
    maximum continuous time (t_max_i) each task can get in a row.
    """
    sim_tasks = [Task(t.name, t.priority, t.min_time, t.needs_screen) for t in tasks]
    
    # A completely clean infinite timeline
    clean_timeline = [TimeBlock(1000000, Period.BOTH)]
    max_durations = {tk.name: 0.0 for tk in sim_tasks}
    
    # Run the raw scheduler without recursion
    scheduler = _schedule_timeline_raw(sim_tasks, clean_timeline, t_now=0.0, _is_side_scheduler=True)
    
    prev = None
    # Simulate enough chunks to find the maximum possible continuous block (steady-state)
    for _ in range(1000 * len(sim_tasks)):
        try:
            item = next(scheduler)
            if prev is None:
                prev = item
            elif prev["task"] == item["task"] and prev["type"] == item["type"]:
                prev["duration"] += item["duration"]
            else:
                name = prev["task"]
                if name in max_durations:
                    max_durations[name] = max(max_durations[name], prev["duration"])
                prev = item
        except StopIteration:
            break
            
    if prev and prev["task"] in max_durations:
        max_durations[prev["task"]] = max(max_durations[prev["task"]], prev["duration"])
        
    return max_durations


def _schedule_timeline_raw(tasks: list[Task], timeline: Iterable[TimeBlock], t_now: float = 0.0, _is_side_scheduler: bool = False) -> Iterator[dict]:
    stream = TimelineStream(timeline)
    
    # Initialize normalized weights
    total_priority = sum(t.priority for t in tasks)
    for tk in tasks:
        tk.weight = tk.priority / total_priority
        tk.allocated = 0.0
        
    # Global System Virtual Time (tracks the progress of the active fluid system)
    V = 0.0
    t = 0.0
    
    # Pre-process past actual allocations if looking from t_now > 0
    if not _is_side_scheduler and t_now > 0.0:
        past_actual_durations = {tk.name: 0.0 for tk in tasks}
        
        while t < t_now:
            b = stream.get_block_at(t)
            if not b:
                break
            dur = min(b["end"], t_now) - t
            if b.get("forced_task"):
                past_actual_durations[b["forced_task"].name] += dur
            t += dur
            
        t_max_map = side_scheduler(tasks)
        
        # Alter the scheduler's view of the past: 
        # Capping the historical allocated time exactly bounds the influence of the past to [t_fst_i, t_now]
        for tk in tasks:
            kept_past = min(past_actual_durations[tk.name], t_max_map[tk.name])
            tk.allocated = kept_past
            V += kept_past
            
    while True:
        current_block = stream.get_block_at(t)
        if not current_block:
            break  # Timeline exhausted
            
        # If we are starting mid-block because of t_now, time_to_boundary will adapt
        if current_block["type"] == Period.INACTIVE:
            dur = stream.get_time_until_next_boundary(t)
            yield {"task": "INACTIVE", "duration": dur, "type": "INACTIVE"}
            t += dur
            continue
            
        if current_block.get("forced_task"):
            forced_task = current_block["forced_task"]
            dur = stream.get_time_until_next_boundary(t)
            
            # Update metrics so the scheduler accounts for this time mathematically
            forced_task.allocated += dur
            V += dur
            
            yield {
                "task": forced_task.name, 
                "duration": dur, 
                "type": "PRE-PLACED"
            }
            t += dur
            continue
            
        # 1. Filter Eligible Candidates (Must be valid AND have enough continuous space)
        valid_candidates = []
        for tk in tasks:
            if is_task_valid_for_period(tk, current_block["type"]):
                allowed_space = stream.get_allowed_continuous(t, tk, is_task_valid_for_period)
                if allowed_space >= tk.min_time:
                    valid_candidates.append((tk, allowed_space))
                    
        # 2. Idle if constrained
        if not valid_candidates:
            dur = stream.get_time_until_next_boundary(t)
            yield {"task": "IDLE / DEAD TIME", "duration": dur, "type": current_block["type"].name}
            t += dur
            continue
            
        # 3. Calculate Virtual Start Time (VST) and Virtual Finish Time (VFT)
        candidates_info = []
        for tk, allowed_space in valid_candidates:
            vst = tk.allocated / tk.weight
            vft = (tk.allocated + tk.min_time) / tk.weight
            candidates_info.append((tk, allowed_space, vst, vft))
            
        # 4. Apply WF²Q Eligibility Condition
        eligible = [c for c in candidates_info if c[2] <= V]
        
        # In constrained environments, relax rule to prevent dead idling
        if not eligible:
            eligible = candidates_info
            
        # 5. Select Lowest Virtual Finish Time
        eligible.sort(key=lambda x: x[3]) # Sort by VFT ascending
        
        time_to_boundary = stream.get_time_until_next_boundary(t)
        
        # Boundary Deferral Heuristic
        fitting_tasks = [c for c in eligible if c[0].min_time <= time_to_boundary]
        
        if fitting_tasks and eligible[0][0].min_time > time_to_boundary:
            winner_info = fitting_tasks[0]
            target_vft = eligible[0][3] 
        else:
            winner_info = eligible[0]
            target_vft = eligible[1][3] if len(eligible) > 1 else float('inf')
            
        winner, max_allowed, _, _ = winner_info
        
        # 6. Determine Optimal Scheduling Duration
        # FIX: We subtract winner.min_time because picking this task inherently advances its 
        # execution by at least min_time. We want its *next* theoretical VFT to tie with the target_vft.
        dt_eq = max(0.0, (target_vft * winner.weight) - winner.allocated - winner.min_time)
        
        dt_eligible = float('inf')
        for c in candidates_info:
            if c[2] > V:
                dt_eligible = min(dt_eligible, c[2] - V)
                
        dt_limit = min(dt_eq, dt_eligible)
        boundary_cap = max(winner.min_time, time_to_boundary)
        
        duration = max(winner.min_time, min(max_allowed, dt_limit, boundary_cap))
        
        # Anti-fragmentation
        remaining = max_allowed - duration
        min_possible = min(tk.min_time for tk in tasks)
        if 0 < remaining < min_possible:
            duration = max_allowed

        duration = min(duration, 1440.0) 
        duration = math.ceil(duration)
        
        winner.allocated += duration
        V += duration
        
        # Yield the chunk 
        rem = duration
        while rem > 0:
            b = stream.get_block_at(t)
            if not b: break
            to_boundary = b["end"] - t
            step = min(rem, to_boundary)
            
            yield {
                "task": winner.name,
                "duration": step,
                "type": b["type"].name
            }
            rem -= step
            t += step


class ScheduledTimeline:
    """
    Acts as an iterator for contiguous block yielding (supporting the visualizer),
    while also fulfilling the requirement of being a callable function f(t) -> Task.
    """
    def __init__(self, tasks: list[Task], timeline: Iterable[TimeBlock], t_now: float = 0.0):
        self.tasks = tasks
        self.iterator = self._merge_generator(_schedule_timeline_raw(tasks, timeline, t_now))
        self.blocks = []
        self.t_now = t_now
        self.current_t = t_now
        
    def _merge_generator(self, gen: Iterator[dict]) -> Iterator[dict]:
        prev = None
        for item in gen:
            if prev is None:
                prev = item
            elif prev["task"] == item["task"] and prev["type"] == item["type"]:
                prev["duration"] += item["duration"]
            else:
                yield prev
                prev = item
        if prev is not None:
            yield prev

    def __iter__(self):
        return self

    def __next__(self):
        chunk = next(self.iterator)
        self.blocks.append({
            "start": self.current_t,
            "end": self.current_t + chunk["duration"],
            "task": chunk["task"],
            "type": chunk["type"],
            "duration": chunk["duration"]
        })
        self.current_t += chunk["duration"]
        return chunk

    def __call__(self, time_param: float) -> 'Task | str | None':
        """
        Calculates and returns the Task object assigned at `time_param`.
        """
        if time_param < self.t_now:
            return None
        
        # Consume the generator up to the requested time parameter
        while not self.blocks or self.blocks[-1]["end"] <= time_param:
            try:
                next(self)
            except StopIteration:
                break
        
        for b in self.blocks:
            if b["start"] <= time_param < b["end"]:
                task_name = b["task"]
                for tk in self.tasks:
                    if tk.name == task_name:
                        return tk
                return task_name
        return None


def schedule_timeline(tasks: list[Task], timeline: Iterable[TimeBlock], t_now: float = 0.0) -> ScheduledTimeline:
    """Wrapper that instantiates the timeline functor."""
    return ScheduledTimeline(tasks, timeline, t_now)


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
            color = task_colors.get(name, INACTIVE_COLOR)

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

    # Build legend
    legend_items = []
    legend_items.append(f"\033[47m\033[30m///{RESET} NO SCREEN")

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

    # EXAMPLE 1: Infinite Timeline, 1h/1h perfect balancing
    tasks_1 = [
        Task("Task A (7m min)", priority=50, min_time=7, needs_screen=True),
        Task("Task B (1h min)", priority=50, min_time=60, needs_screen=False)
    ]
    base_pattern_1 = [
        TimeBlock(60, Period.BOTH),
        TimeBlock(60, Period.NO_SCREEN)
    ]
    scheduler_1 = schedule_timeline(tasks_1, itertools.cycle(base_pattern_1))
    schedule_1_slice = list(itertools.islice(scheduler_1, 6))
    print_colored_timeline("WF²Q Continuous Lookahead - Perfect 1h/1h Balancing", schedule_1_slice)

    # EXAMPLE 2: Custom Daily Pattern
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
    scheduler_2 = schedule_timeline(tasks_2, itertools.cycle(base_pattern_2))
    schedule_2_slice = list(itertools.islice(scheduler_2, 30))
    print_colored_timeline("WF²Q Continuous Lookahead - Custom Daily Pattern", schedule_2_slice)

    # EXAMPLE 3: One-off initialization followed by an infinite loop
    tasks_3 = [
        Task("Task X", priority=45, min_time=45, needs_screen=True),
        Task("Task Y", priority=45, min_time=45, needs_screen=True),
        Task("Task Z", priority=10, min_time=45, needs_screen=True)
    ]
    initial_timeline = [
        TimeBlock(120, Period.INACTIVE),
        TimeBlock(60, Period.SCREEN)
    ]
    repeating_pattern = [
        TimeBlock(180, Period.BOTH),
        TimeBlock(30, Period.INACTIVE)
    ]
    combined_timeline = itertools.chain(initial_timeline, itertools.cycle(repeating_pattern))
    scheduler_3 = schedule_timeline(tasks_3, combined_timeline)
    schedule_3_slice = list(itertools.islice(scheduler_3, 10))
    print_colored_timeline("Mixed Timeline - One-Off Start + Infinite Loop", schedule_3_slice)

    # EXAMPLE 4
    t_deep_work = Task("Deep Work", priority=50, min_time=45, needs_screen=True)
    t_reading = Task("Reading", priority=50, min_time=45, needs_screen=True)
    
    tasks_list = [t_deep_work, t_reading]

    timeline = [
        TimeBlock(120, Period.SCREEN, forced_task=t_deep_work),
        TimeBlock(60, Period.NO_SCREEN),
        TimeBlock(1800, Period.BOTH)
    ]
    
    # We now test `t_now = 120` to verify how it caps past execution memory.
    scheduler_4 = schedule_timeline(tasks_list, timeline, t_now=120)
    schedule_4_slice = list(itertools.islice(scheduler_4, 10))
    print_colored_timeline("Forced Task Initialization (t_now = 120, bounded past context)", schedule_4_slice)
    
    # Verify the "function f(t) -> task" callable requirement
    sample_task = scheduler_4(200.0) 
    print(f"Callable Test at t=200.0 -> {sample_task.name if isinstance(sample_task, Task) else sample_task}")