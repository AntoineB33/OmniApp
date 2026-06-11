from fractions import Fraction
import math
from fractions import Fraction
from collections import defaultdict
import matplotlib.pyplot as plt
import matplotlib.patches as mpatches

def plot_timeline(schedule, t1, t2):
    """
    Takes the generated schedule and plots a horizontal Gantt-style timeline.
    """
    fig, ax = plt.subplots(figsize=(12, 5))
    
    # 1. Determine Y-axis rows (Extract unique tasks, put IDLE at the bottom)
    tasks = sorted(list(set(p['name'] for p in schedule if p['name'] != '---')))
    tasks.append('---') # IDLE track
    
    # Assign a Y-coordinate for each task row
    y_coords = {task: i * 10 for i, task in enumerate(reversed(tasks))}
    
    # 2. Define colors for our panel types
    colors = {
        'PINNED': '#d62728',  # Red
        'FILLED': '#1f77b4',  # Blue
        'IDLE': '#cccccc'     # Gray
    }
    
    # 3. Draw the task panels
    for panel in schedule:
        y_pos = y_coords[panel['name']]
        start = panel['start']
        duration = panel['end'] - panel['start']
        
        # broken_barh takes a list of (start, width) tuples, and a (y_bottom, y_height) tuple
        ax.broken_barh(xranges=[(start, duration)], 
                       yrange=(y_pos - 4, 8), 
                       facecolors=colors[panel['type']],
                       edgecolor='black', 
                       linewidth=1)
        
        # Add the duration text inside the block if it's wide enough to fit
        if duration >= 10:
            text_color = 'white' if panel['type'] != 'IDLE' else 'black'
            ax.text(start + (duration / 2), y_pos, f"{duration}m", 
                    ha='center', va='center', color=text_color, fontsize=9, fontweight='bold')

    # 4. Format the Axes and Grid
    ax.set_yticks([y_coords[t] for t in tasks])
    ax.set_yticklabels([f"Task {t}" if t != '---' else "IDLE" for t in tasks])
    ax.set_xlabel("Time (minutes)", fontsize=11)
    ax.set_xlim(t1, t2)
    
    # Add vertical grid lines for readability
    ax.set_xticks(range(t1, t2 + 1, 30)) # Ticks every 30 mins
    ax.grid(True, axis='x', linestyle='--', alpha=0.6)
    
    # 5. Build the Legend
    legend_patches = [mpatches.Patch(color=color, label=label) for label, color in colors.items()]
    ax.legend(handles=legend_patches, loc='upper right', bbox_to_anchor=(1, 1.15), ncol=3)
    
    ax.set_title("Dynamic Task Schedule Timeline", fontsize=14, pad=20)
    plt.tight_layout()
    plt.show()

# ==========================================
# Phase 1: Rule Extractor (The Perfect Schedule)
# ==========================================
# ==========================================
# Phase 1: Rule Extractor (The Perfect Schedule)
# ==========================================
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

# ==========================================
# Phase 2: Dynamic Gap Filler (The Real Schedule)
# ==========================================
class DynamicGapFiller:
    def __init__(self, tasks, pinned_tasks, t1, t2):
        self.tasks = tasks
        self.pinned_tasks = sorted(pinned_tasks, key=lambda x: x['start'])
        self.t1 = t1
        self.t2 = t2
        self._normalize_priorities()
        self.schedule = []
        
    def _normalize_priorities(self):
        total_p = sum(Fraction(str(t['priority'])) for t in self.tasks)
        for t in self.tasks:
            t['frac_priority'] = Fraction(str(t['priority'])) / total_p

    def _build_timeline_chunks(self):
        chunks = []
        current_time = self.t1
        
        for p in self.pinned_tasks:
            if p['end'] <= self.t1 or p['start'] >= self.t2:
                continue
            
            p_start = max(self.t1, p['start'])
            p_end = min(self.t2, p['end'])
            
            if p_start > current_time:
                chunks.append({'type': 'gap', 'start': current_time, 'end': p_start})
                
            chunks.append({'type': 'pinned', 'name': p['name'], 'start': p_start, 'end': p_end})
            current_time = p_end
            
        if current_time < self.t2:
            chunks.append({'type': 'gap', 'start': current_time, 'end': self.t2})
            
        return chunks

    def generate_schedule(self):
        chunks = self._build_timeline_chunks()
        supplied = {t['name']: 0 for t in self.tasks}
        
        for chunk in chunks:
            if chunk['type'] == 'pinned':
                duration = chunk['end'] - chunk['start']
                if chunk['name'] in supplied:
                    supplied[chunk['name']] += duration
                self.schedule.append({
                    'type': 'PINNED',
                    'name': chunk['name'],
                    'start': chunk['start'],
                    'end': chunk['end']
                })
                
            elif chunk['type'] == 'gap':
                curr = chunk['start']
                
                while curr < chunk['end']:
                    gap_remaining = chunk['end'] - curr
                    best_task = None
                    max_deficit = float('-inf')
                    
                    for t in self.tasks:
                        if t['min_time'] <= gap_remaining:
                            elapsed_total = curr - self.t1
                            expected = elapsed_total * t['frac_priority']
                            deficit = expected - supplied[t['name']]
                            
                            if deficit > max_deficit:
                                max_deficit = deficit
                                best_task = t
                                
                    if best_task:
                        self.schedule.append({
                            'type': 'FILLED',
                            'name': best_task['name'],
                            'start': curr,
                            'end': curr + best_task['min_time']
                        })
                        supplied[best_task['name']] += best_task['min_time']
                        curr += best_task['min_time']
                    else:
                        self.schedule.append({
                            'type': 'IDLE',
                            'name': '---',
                            'start': curr,
                            'end': chunk['end']
                        })
                        break
                        
        return self.schedule

# ==========================================
# Example Usage
# ==========================================
if __name__ == "__main__":
    def check_priority_satisfaction(schedule, tasks, tolerance=0.05):
        actual_by_task = defaultdict(int)
        total_work_time = 0

        for panel in schedule:
            duration = panel['end'] - panel['start']
            if panel['type'] != 'IDLE':
                actual_by_task[panel['name']] += duration
                total_work_time += duration

        print("\n=====================================================")
        print(" PRIORITY SATISFACTION CHECK")
        print("=====================================================")

        all_satisfied = True
        for task in tasks:
            expected_share = float(task['frac_priority'])
            actual_share = (actual_by_task[task['name']] / total_work_time) if total_work_time else 0.0
            delta = abs(actual_share - expected_share)
            satisfied = delta <= tolerance
            all_satisfied = all_satisfied and satisfied
            status = "OK" if satisfied else "OFF"
            print(
                f"Task {task['name']}: actual={actual_share:.3f}, expected={expected_share:.3f}, "
                f"delta={delta:.3f} -> {status}"
            )

        print(f"Overall priority satisfaction: {'YES' if all_satisfied else 'NO'}")

    # Base task rules
    tasks = [
        {"name": "A", "priority": 0.50, "min_time": 45},
        {"name": "B", "priority": 0.45, "min_time": 45},
        {"name": "C", "priority": 0.05, "min_time": 45}
    ]

    # Pre-existing schedule with fixed appointments
    # pinned_tasks = [
    #     {"name": "A", "start": 60, "end": 150}, # A huge 90-min block of Task A
    #     {"name": "C", "start": 200, "end": 220} # A 20-min block of Task C
    # ]
    # pinned_tasks = [
    #     {"name": "A", "start": 60, "end": 150}, # A huge 90-min block of Task A
    #     {"name": "C", "start": 200, "end": 220} # A 20-min block of Task C
    # ]
    pinned_tasks=[]

    t1 = 0
    t2 = 24*60 # 24-hour window

    print("=====================================================")
    print(" PHASE 1: THEORETICAL RULES (Infinite Perfect Loop)")
    print("=====================================================")
    rule_extractor = TaskScheduler(tasks)
    rule_extractor.generate_timeline()
    rules = rule_extractor.extract_rules()
    
    print(f"Calculated Hyperperiod: {rule_extractor.hyperperiod} minutes\n")
    for rule in rules:
        print(f"- {rule}")

    print("\n=====================================================")
    print(f" PHASE 2: DYNAMIC REAL-WORLD SCHEDULE ({t1}m to {t2}m)")
    print("=====================================================")
    gap_filler = DynamicGapFiller(tasks, pinned_tasks, t1, t2)
    final_schedule = gap_filler.generate_schedule()

    for panel in final_schedule:
        duration = panel['end'] - panel['start']
        print(f"[{panel['start']:>3} - {panel['end']:>3}] ({duration:>2}m) | {panel['type']:<7} | Task {panel['name']}")

    check_priority_satisfaction(final_schedule, tasks)


    # ==========================================
    # Run the visualization (Append to previous script)
    # ==========================================
    # Assuming `final_schedule`, `t1`, and `t2` are already defined from the previous code:
    plot_timeline(final_schedule, t1, t2)