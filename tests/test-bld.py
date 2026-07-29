import itertools
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

        # Bounded Deficit Queue State
        self.target_rate = 0.0  # Weight w_i
        self.queue = 0.0        # Virtual backlog Q_i


class TimeBlock:
    def __init__(self, duration: float, period_type: Period):
        self.duration = duration
        self.period_type = period_type


def is_task_valid_for_period(task: Task, period_type: Period) -> bool:
    if period_type == Period.INACTIVE:
        return False
    if period_type == Period.BOTH:
        return True
    if period_type == Period.SCREEN and task.needs_screen:
        return True
    return bool(period_type == Period.NO_SCREEN and not task.needs_screen)


class ContinuousTimelineStream:
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

    def get_allowed_continuous(self, t: float, task: Task) -> float:
        allowed = 0.0
        curr_t = t
        while True:
            self._ensure_cached_up_to(curr_t)
            b = self.get_block_at(curr_t)
            if not b or not is_task_valid_for_period(task, b["type"]):
                break
            
            dur = b["end"] - curr_t
            allowed += dur
            curr_t = b["end"]
            
            if allowed > 10000: # Infinite lookahead cap
                return float('inf')
        return allowed


def max_weight_scheduler(tasks: list[Task], timeline: Iterable[TimeBlock]) -> Iterator[dict]:
    stream = ContinuousTimelineStream(timeline)
    
    total_priority = sum(t.priority for t in tasks)
    for tk in tasks:
        tk.target_rate = tk.priority / total_priority
        tk.queue = 0.0  # Initial backlog
        
    t = 0.0
    MAX_QUEUE_CAP = 300.0  # Prevents long-term starvation burstiness

    while True:
        b = stream.get_block_at(t)
        if not b:
            break

        if b["type"] == Period.INACTIVE:
            dur = b["end"] - t
            yield {"task": "INACTIVE", "duration": dur, "type": "INACTIVE"}
            t += dur
            continue

        # 1. Identify Valid Tasks & evaluate continuous availability
        candidates = []
        for tk in tasks:
            if is_task_valid_for_period(tk, b["type"]):
                allowed_space = stream.get_allowed_continuous(t, tk)
                if allowed_space >= tk.min_time:
                    candidates.append((tk, allowed_space))

        # 2. Idle if no tasks fit minimum time
        if not candidates:
            dur = b["end"] - t
            yield {"task": "IDLE / DEAD TIME", "duration": dur, "type": b["type"].name}
            t += dur
            continue

        # 3. Max-Weight Decision Rule: Pick task with largest virtual backlog Q_i
        candidates.sort(key=lambda item: item[0].queue, reverse=True)
        winner, max_allowed = candidates[0]

        # Determine duration: Run for at least min_time, bound by current block boundary
        time_to_boundary = b["end"] - t
        run_duration = max(winner.min_time, min(time_to_boundary, max_allowed))
        
        # Avoid leaving tiny dead-time fragments in blocks
        rem = max_allowed - run_duration
        if 0 < rem < min(tk.min_time for tk in tasks):
            run_duration = max_allowed

        # 4. Update Deficit Queues (Lyapunov Drift Step)
        for tk in tasks:
            # Service supplied to winner, arrival rate added to all
            allocated = run_duration if tk == winner else 0.0
            arrival = run_duration * tk.target_rate
            tk.queue = max(0.0, min(MAX_QUEUE_CAP, tk.queue + arrival - allocated))

        # 5. Yield execution
        rem_time = run_duration
        while rem_time > 0:
            cb = stream.get_block_at(t)
            if not cb: break
            step = min(rem_time, cb["end"] - t)
            yield {
                "task": winner.name,
                "duration": step,
                "type": cb["type"].name
            }
            rem_time -= step
            t += step