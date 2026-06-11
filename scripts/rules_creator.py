import math
from fractions import Fraction
from collections import defaultdict

class TaskScheduler:
    def __init__(self, tasks):
        self.tasks = tasks
        self._normalize_priorities()
        self.hyperperiod = self._calculate_hyperperiod()
        self.schedule = []
        
    def _normalize_priorities(self):
        total_p = sum(Fraction(str(t['priority'])) for t in self.tasks)
        for t in self.tasks:
            t['frac_priority'] = Fraction(str(t['priority'])) / total_p
            
    def _calculate_hyperperiod(self):
        intervals = [Fraction(t['min_time'], 1) / t['frac_priority'] for t in self.tasks]
        nums = [f.numerator for f in intervals]
        dens = [f.denominator for f in intervals]
        
        lcm_num = nums[0]
        for n in nums[1:]:
            lcm_num = (lcm_num * n) // math.gcd(lcm_num, n)
            
        gcd_den = dens[0]
        for d in dens[1:]:
            gcd_den = math.gcd(gcd_den, d)
            
        return int(lcm_num / gcd_den)

    def generate_timeline(self):
        time = 0
        
        # 1. Calculate the ideal interval for each task
        task_info = {}
        for t in self.tasks:
            required_time = self.hyperperiod * t['frac_priority']
            num_blocks = required_time / t['min_time']
            interval = self.hyperperiod / num_blocks
            
            task_info[t['name']] = {
                'min_time': t['min_time'],
                'interval': float(interval),
                'blocks_scheduled': 0
            }
            
        # 2. Earliest Deadline First (EDF) Scheduling
        while time < self.hyperperiod:
            best_task_name = None
            earliest_deadline = float('inf')
            
            for name, info in task_info.items():
                # The "deadline" is the ideal finish time of the NEXT block
                next_deadline = (info['blocks_scheduled'] + 1) * info['interval']
                
                # Tie-breaker: If deadlines are equal, it just picks the first in the loop
                if next_deadline < earliest_deadline:
                    earliest_deadline = next_deadline
                    best_task_name = name
                    
            # 3. Schedule the winning task
            best_info = task_info[best_task_name]
            
            self.schedule.append({
                'name': best_task_name,
                'start': time,
                'length': best_info['min_time']
            })
            
            best_info['blocks_scheduled'] += 1
            time += best_info['min_time']

    def extract_rules(self):
        starts = defaultdict(list)
        for panel in self.schedule:
            starts[panel['name']].append(panel['start'])
            
        rules = []
        for t in self.tasks:
            name = t['name']
            task_starts = starts[name]
            num_panels = len(task_starts)
            
            if num_panels == 0:
                continue
                
            interval = self.hyperperiod / num_panels
            length = t['min_time']
            base_offset = task_starts[0]
            
            exceptions = []
            for i, actual_start in enumerate(task_starts):
                ideal_start = base_offset + (i * interval)
                shift = actual_start - ideal_start
                if abs(shift) > 0.1: # Account for minor floating point rounding
                    direction = "delayed" if shift > 0 else "advanced"
                    exceptions.append(f"iteration {i+1} is {direction} by {abs(shift):.1f} mins")
            
            rule_text = f"**Task {name}**: {length} minutes appears every {interval:.1f} minutes."
            if exceptions:
                rule_text += f"\n    Exceptions: {', '.join(exceptions)}"
            else:
                rule_text += " (Perfectly periodic, no exceptions)."
                
            rules.append(rule_text)
            
        return rules