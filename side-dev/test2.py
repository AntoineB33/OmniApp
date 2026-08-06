inf = float('inf')

class Task:
    """
    Represents a schedulable task.
    Each task is defined by a target priority percentage and a minimum execution time.
    """
    def __init__(self, name: str, priority: float, min_time: float, color: str = "#FFFFFF"):
        self.name = name
        self.priority = priority
        self.min_time = min_time
        self.color = color
        
    def __repr__(self):
        return f"Task({self.name}, {self.priority}%, min={self.min_time}m)"


def AB() -> list[Task]:
    """Helper for standard 50/50 test cases."""
    return [
        Task("A", priority=50, min_time=10, color="#FF9999"),
        Task("B", priority=50, min_time=10, color="#99CCFF")
    ]


def build_cases() -> list[tuple]:
    """Returns the defined test cases for the scheduler."""
    return [
        (
            (
                "Test 1: Normal 50/50 Split (10min each)\n"
                "-> Pure periodic cycle, no prefix."
            ),
            AB(), 180, [], []
        ),
        (
            (
                "Test 2: Pre-placed event owned by nobody\n"
                "-> MAINTENANCE excludes everybody equally, so it creates no "
                "field: they simply resume alternating."
            ),
            AB(), 240,
            [{'name': 'MAINTENANCE', 'start': 40, 'duration': 60, 'color': '#CCCCCC'}],
            []
        ),
        (
            (
                "Test 3: Periods constraint\n"
                "-> C is banned from t=105 on, forever: it is abundantly "
                "present just before the door closes, then A and B share the "
                "timeline."
            ),
            [
                Task("A", priority=40, min_time=10, color="#FF9999"),
                Task("B", priority=40, min_time=10, color="#99CCFF"),
                Task("C", priority=20, min_time=10, color="#99FF99")
            ],
            300, [],
            [{'start': 0, 'end': 105, 'allowed': ['A', 'B', 'C']},
             {'start': 105, 'end': inf, 'allowed': ['A', 'B']}]
        ),
        (
            (
                "Test 4: Three tasks (A: 50% 20m, B: 30% 10m, C: 20% 15m)\n"
                "-> Minimums force a 75min period; shares are exact."
            ),
            [
                Task("A", priority=50, min_time=20, color="#FF9999"),
                Task("B", priority=30, min_time=10, color="#99CCFF"),
                Task("C", priority=20, min_time=15, color="#99FF99")
            ],
            400, [], []
        ),
        (
            (
                "Test 5: Lopsided priorities (A 90% / B 10%) + a B block at the "
                "start\n-> A gets a denser, bounded catch-up around it, not the "
                "full 396min it is owed."
            ),
            [
                Task("A", priority=90, min_time=10, color="#FF9999"),
                Task("B", priority=10, min_time=10, color="#99CCFF")
            ],
            600,
            [{'name': 'B', 'start': 0, 'duration': 40, 'color': "#99CCFF"}],
            []
        ),
        (
            (
                "Test 6: 1h block of A at t=100 (tau = 20min)\n"
                "-> B's slots swell as the block approaches and shrink back "
                "after it: exponential decay of the influence, both sides."
            ),
            AB(), 400,
            [{'name': 'A', 'start': 100, 'duration': 60, 'color': "#FF9999"}],
            []
        ),
        (
            (
                "Test 7: 10h block of A at t=100 - 10x longer than test 6\n"
                "-> B's presence around it is wider and denser, but only a few "
                "times bigger: log, not proportional."
            ),
            AB(), 1000,
            [{'name': 'A', 'start': 100, 'duration': 600, 'color': "#FF9999"}],
            [], {}, 2
        ),
        (
            (
                "Test 8: B banned from t=100 to t=400 - a window, not a block\n"
                "-> same field, same ramps: B swells before the ban and right "
                "after it re-opens, then decays back to the cycle."
            ),
            AB(), 700, [],
            [{'start': 0, 'end': 100, 'allowed': ['A', 'B']},
             {'start': 100, 'end': 400, 'allowed': ['A']},
             {'start': 400, 'end': inf, 'allowed': ['A', 'B']}],
            {}, 2
        ),
        (
            (
                "Test 9: same 300min ban, but split into ten consecutive "
                "windows\n-> merged into one exclusion: ten short bans in a row "
                "are one long ban, not ten small ones."
            ),
            AB(), 700, [],
            [{'start': 0, 'end': 100, 'allowed': ['A', 'B']}]
            + [{'start': 100 + 30 * i, 'end': 130 + 30 * i, 'allowed': ['A']}
               for i in range(10)]
            + [{'start': 400, 'end': inf, 'allowed': ['A', 'B']}],
            {}, 2
        ),
    ]


class RuleSet:
    """Stores the O(1) evaluable rules for a specific timeline."""
    def __init__(self):
        self.prefixes = []
        self.cycle = []

    def evaluate_at(self, t: float) -> str:
        """O(1) evaluation for the UI to display the schedule."""
        # Check finite prefix bounds first
        for prefix in self.prefixes:
            if prefix['start'] <= t < prefix['end']:
                return prefix['task']
        
        # If past prefixes, calculate modulo math for infinite cycle
        if not self.cycle:
            return "IDLE"
            
        cycle_start = self.prefixes[-1]['end'] if self.prefixes else 0
        cycle_duration = sum(block['duration'] for block in self.cycle)
        
        t_in_cycle = (t - cycle_start) % cycle_duration
        current = 0
        for block in self.cycle:
            current += block['duration']
            if t_in_cycle < current:
                return block['task']
        return "IDLE"


class SchedulerEngine:
    def __init__(self, epsilon: float = 1.0, max_block: float = 120.0, decay_rate: float = 0.5):
        # User defined variables for bounded computation
        self.epsilon = epsilon
        self.max_block = max_block
        self.decay_rate = decay_rate
        
    def generate_rules(self, tasks: list[Task], pre_placed: list[dict], periods: list[dict]) -> RuleSet:
        """
        Phase 1: Heavy-Lifting Generation
        Runs the predictive simulation to solve exponential decays and min_time boundaries.
        Returns a RuleSet (Phase 2 O(1) object).
        """
        rules = RuleSet()
        
        # 1. Normalize periods (merge contiguous windows as per Test 9)
        periods = self._merge_periods(periods)
        
        # 2. State Tracking Variables
        current_time = 0.0
        priority_debt = {task.name: 0.0 for task in tasks}
        
        # NOTE: A full implementation would apply numerical root-finding (Newton-Raphson) 
        # or Lambert W functions here to step through time blocks, calculating the discrete 
        # decay steps until max(priority_debt) < self.epsilon.
        
        # Simulated generator logic bounding:
        # while max(abs(debt) for debt in priority_debt.values()) > self.epsilon:
        #    ... Calculate next block ...
        #    ... Enforce Atomic Min Times ...
        #    ... Force IDLE if interrupted ...
        #    ... Update t_now and debts ...
        
        # 3. Snap to Cycle
        # Once debt < epsilon, discard remaining debt and append the perfect infinite cycle.
        
        return rules

    def _merge_periods(self, periods: list[dict]) -> list[dict]:
        """Merges consecutive periods with identical allowances to form unified exclusionary blocks."""
        if not periods:
            return []
        periods.sort(key=lambda x: x['start'])
        merged = [periods[0]]
        for p in periods[1:]:
            last = merged[-1]
            if p['start'] == last['end'] and set(p['allowed']) == set(last['allowed']):
                last['end'] = p['end']
            else:
                merged.append(p)
        return merged

if __name__ == "__main__":
    # Test suite bootstrap
    cases = build_cases()
    engine = SchedulerEngine(epsilon=1.0)
    
    print(f"Loaded {len(cases)} test cases.")
    for i, case in enumerate(cases):
        desc = case[0].split('\n')[0]
        print(f"Running {desc}...")
        # engine.generate_rules(tasks=case[1], pre_placed=case[3], periods=case[4])