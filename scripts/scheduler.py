from fractions import Fraction

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