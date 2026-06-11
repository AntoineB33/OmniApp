from fractions import Fraction

class DynamicGapFiller:
    def __init__(self, tasks, pinned_tasks, t1, t2):
        self.tasks = tasks
        # Sort pinned tasks chronologically
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
        """Breaks the t1 to t2 window into a sequence of 'pinned' and 'gap' chunks."""
        chunks = []
        current_time = self.t1
        
        for p in self.pinned_tasks:
            # Ignore tasks entirely outside our time window
            if p['end'] <= self.t1 or p['start'] >= self.t2:
                continue
            
            # Clip pinned tasks to strictly fit within t1 and t2
            p_start = max(self.t1, p['start'])
            p_end = min(self.t2, p['end'])
            
            # If there's space before this pinned task, it's a free gap
            if p_start > current_time:
                chunks.append({'type': 'gap', 'start': current_time, 'end': p_start})
                
            chunks.append({'type': 'pinned', 'name': p['name'], 'start': p_start, 'end': p_end})
            current_time = p_end
            
        # Add any remaining time as a final gap
        if current_time < self.t2:
            chunks.append({'type': 'gap', 'start': current_time, 'end': self.t2})
            
        return chunks

    def generate_schedule(self):
        chunks = self._build_timeline_chunks()
        supplied = {t['name']: 0 for t in self.tasks}
        
        for chunk in chunks:
            if chunk['type'] == 'pinned':
                # Pinned tasks automatically happen. We update the supplied time.
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
                # Fill the gap dynamically based on who is starving the most
                curr = chunk['start']
                
                while curr < chunk['end']:
                    gap_remaining = chunk['end'] - curr
                    best_task = None
                    max_deficit = float('-inf')
                    
                    for t in self.tasks:
                        # Task must physically fit into the remaining gap
                        if t['min_time'] <= gap_remaining:
                            # Deficit = (Expected time so far) - (Actual time given)
                            elapsed_total = curr - self.t1
                            expected = elapsed_total * t['frac_priority']
                            deficit = expected - supplied[t['name']]
                            
                            if deficit > max_deficit:
                                max_deficit = deficit
                                best_task = t
                                
                    if best_task:
                        # Schedule the chosen task
                        self.schedule.append({
                            'type': 'FILLED',
                            'name': best_task['name'],
                            'start': curr,
                            'end': curr + best_task['min_time']
                        })
                        supplied[best_task['name']] += best_task['min_time']
                        curr += best_task['min_time']
                    else:
                        # If no task's min_time fits in the remaining gap, 
                        # we must leave the rest of this gap empty (Idle)
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
    # Base task rules
    tasks = [
        {"name": "A", "priority": 0.50, "min_time": 30},
        {"name": "B", "priority": 0.30, "min_time": 20},
        {"name": "C", "priority": 0.20, "min_time": 10}
    ]

    # Pre-existing schedule with fixed appointments
    pinned_tasks = [
        {"name": "A", "start": 60, "end": 150}, # A huge 90-min block of Task A
        {"name": "C", "start": 200, "end": 220} # A 20-min block of Task C
    ]

    t1 = 0
    t2 = 300 # 5-hour window

    scheduler = DynamicGapFiller(tasks, pinned_tasks, t1, t2)
    final_schedule = scheduler.generate_schedule()

    print(f"### Master Schedule ({t1} to {t2} mins) ###\n")
    for panel in final_schedule:
        duration = panel['end'] - panel['start']
        print(f"[{panel['start']:>3} - {panel['end']:>3}] ({duration:>2}m) | {panel['type']:<7} | Task {panel['name']}")