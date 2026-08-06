import math
from dataclasses import dataclass

# --- Constants & Configuration ---
MIN_TIME_S = 600       # 10 minutes
TP_DURATION = 20       # 20 seconds restriction
TARGET_B = 0.5         # Target percentage for B (A is 1 - TARGET_B)
DECAY_RATE = 0.01      # Exponential decay factor for priority debt
EPSILON = 1.0          # Rounding epsilon to discretize state space and guarantee O(1) cycle detection

@dataclass
class Block:
    task: str
    duration: float
    is_frozen: bool = False

class SchedulerEngine:
    def __init__(self, tp: float):
        self.tp = tp
        self.tp_end = tp + TP_DURATION
        
        self.t = 0.0
        self.debt_b = 0.0  # Positive means B is owed time, negative means A is owed
        
        self.active_task: str | None = None
        self.task_end_time = 0.0
        
        self.history = []
        self.state_ledger = {}  # For cycle detection: state_hash -> history_index

    def _get_current_state_hash(self):
        """
        Creates a discrete hash of the system state.
        This is the core of the O(1) mathematical cycle detection.
        """
        # Round the debt using the epsilon to force a finite state space
        discrete_debt = round(self.debt_b / EPSILON) * EPSILON
        
        return (
            self.active_task,
            round(self.task_end_time - self.t, 1), # Remaining locked time
            discrete_debt
        )

    def _apply_decay_and_debt(self, duration: float, executed_task: str):
        """
        Applies exponential decay to existing debt, then adds newly accrued debt.
        """
        # 1. Decay existing debt over the duration
        self.debt_b *= math.exp(-DECAY_RATE * duration)
        
        # 2. Accrue new debt based on what ran vs target
        if executed_task == 'A':
            # B was starved, B gains debt
            self.debt_b += (TARGET_B * duration)
        elif executed_task == 'B':
            # B ran, B pays off debt
            self.debt_b -= ((1.0 - TARGET_B) * duration)
        elif executed_task == 'IDLE':
            # Both starved, no relative shift
            pass

    def _choose_next_task(self) -> str:
        """Determines the next task based on strict constraints and priority debt."""
        # Constraint 1: Are we inside the tp restrictive window?
        if self.tp <= self.t < self.tp_end:
            return 'A'
            
        # Constraint 2: Is a task currently locked by MIN_TIME?
        if self.t < self.task_end_time and self.active_task is not None:
            return self.active_task

        # Constraint 3: Resolve based on priority ledger
        # If debt_b is heavily positive, B has been deprived and must run.
        if self.debt_b > 0:
            return 'B'
        else:
            return 'A'

    def find_schedule(self) -> tuple[list[Block], list[Block]]:
        """
        Simulates time forward until an exact state match is found, 
        returning the Prefix and the Cycle.
        """
        while True:
            # 1. Check for Cycle (only possible once the tp disturbance is passed)
            if self.t >= self.tp_end and self.t >= self.task_end_time:
                state = self._get_current_state_hash()
                if state in self.state_ledger:
                    cycle_start_idx = self.state_ledger[state]
                    prefix = self.history[:cycle_start_idx]
                    cycle = self.history[cycle_start_idx:]
                    return prefix, cycle
                
                # Record state for future detection
                self.state_ledger[state] = len(self.history)

            # 2. Determine what to schedule
            next_task = self._choose_next_task()
            
            # 3. Determine how long this block runs (Next Boundary Event)
            if next_task != self.active_task:
                # Starting a new task, lock it for MIN_TIME
                self.active_task = next_task
                self.task_end_time = self.t + MIN_TIME_S

            # Find the next time boundary where logic might change
            next_event = self.task_end_time
            if self.t < self.tp < next_event:
                next_event = self.tp
            if self.t < self.tp_end < next_event:
                next_event = self.tp_end

            duration = next_event - self.t

            # 4. Apply scheduling and update debt
            # (Idle if B is scheduled but we hit the tp block where B is forbidden)
            executed = next_task
            if executed == 'B' and self.tp <= self.t < self.tp_end:
                executed = 'IDLE'

            self._apply_decay_and_debt(duration, executed)

            # 5. Record block and advance time
            is_frozen = self.t < self.tp
            
            # Merge contiguous blocks of the same task in history
            if self.history and self.history[-1].task == executed and self.history[-1].is_frozen == is_frozen:
                self.history[-1].duration += duration
            else:
                self.history.append(Block(executed, duration, is_frozen))
                
            self.t = next_event


# --- Execution for a specific branch test ---
if __name__ == "__main__":
    # Test case: tp occurs at 9 min 40 sec (580 seconds)
    # This falls right at the end of A's initial 10-minute block.
    tp_test = 580.0
    
    scheduler = SchedulerEngine(tp=tp_test)
    prefix, cycle = scheduler.find_schedule()

    print(f"--- Algebraic Schedule for tp = {tp_test}s ---")
    print("\n[ PREFIX ] (Includes frozen past + debt recovery)")
    for b in prefix:
        status = "(Frozen)" if b.is_frozen else "(Dynamic)"
        print(f"  Task {b.task}: {b.duration:.1f}s {status}")

    print("\n[ CYCLE ] (O(1) Repeating Pattern)")
    for b in cycle:
        print(f"  Task {b.task}: {b.duration:.1f}s")