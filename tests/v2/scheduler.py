from dataclasses import dataclass
from fractions import Fraction


@dataclass
class Task:
    name: str
    priority: float  # Percentage (e.g. 50 for 50%)
    min_time: int    # Minimum time in minutes

def generate_schedule(tasks: list[Task]) -> list[str]:
    """
    Generates a finite repeating sequence of tasks based on minimum times
    and priority percentages, maintaining O(1) scheduling complexity.
    """
    if not tasks:
        return []

    # Filter out tasks with 0 priority
    active_tasks = [t for t in tasks if t.priority > 0]
    if not active_tasks:
        return []

    # Calculate "strides" for each task.
    # Stride represents how much "virtual time" a task consumes when scheduled once.
    # By picking the task with the smallest accumulated virtual time (pass), 
    # we perfectly balance the priority ratios on the smallest possible scale.
    strides = []
    for t in active_tasks:
        # Convert to string first to avoid floating point representation issues
        p = Fraction(str(t.priority))
        time = Fraction(t.min_time)
        strides.append(time / p)

    # State tracks the accumulated virtual time for each task
    passes = [Fraction(0) for _ in active_tasks]
    
    history = {}
    sequence = []

    while True:
        # Normalize the state by subtracting the minimum pass from all passes.
        # This prevents the numbers from growing infinitely and lets us detect cycles.
        min_pass = min(passes)
        state = tuple(p - min_pass for p in passes)
        
        # If we've seen this exact relative state before, we found our loop!
        if state in history:
            cycle_start = history[state]
            result = sequence[:]
            
            if cycle_start == 0:
                result.append("repeat")
            else:
                result.append(f"repeat from index {cycle_start}")
                
            return result
        
        # Record the state and its index in the sequence
        history[state] = len(sequence)

        # Find the task that is most "behind" on its schedule (pass == min_pass == 0)
        # Tie breaking: pick the one with the smallest stride (higher priority/smaller time) first
        best_idx = -1
        for i, p in enumerate(state):
            if p == 0 and (best_idx == -1 or strides[i] < strides[best_idx]):
                best_idx = i

        # Add to sequence and advance its virtual time
        task = active_tasks[best_idx]
        sequence.append(f"task {task.name} {task.min_time}min")
        passes[best_idx] += strides[best_idx]