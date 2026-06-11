import math

def gcd_multiple(numbers):
    g = numbers[0]
    for n in numbers[1:]:
        g = math.gcd(g, n)
    return g

class ProportionalScheduler:
    def __init__(self, tasks):
        """
        tasks: list of dicts, e.g., [{'name': 'x', 'p': 50, 'min_time': 45}, ...]
        p is the priority percentage (0-100).
        """
        self.tasks = tasks
        
        self.G = gcd_multiple([t['p'] for t in tasks])
        self.N_slots = {t['name']: t['p'] // self.G for t in tasks}
        self.Total_N = sum(self.N_slots.values())
        
        self._generate_ideal_pattern()
        
        self.D = max(t['min_time'] / (t['p'] / 100.0) for t in tasks)
        self.slot_duration = self.D / self.Total_N

    def _generate_ideal_pattern(self):
        sorted_tasks = sorted(self.tasks, key=lambda t: (-t['p'], t['name']))
        self.pattern = [None] * self.Total_N
        empty_indices = list(range(self.Total_N))
        
        for t in sorted_tasks:
            name = t['name']
            n = self.N_slots[name]
            L = len(empty_indices)
            
            selected_pos = [int(k * L / n) for k in range(n)]
            for pos in selected_pos:
                self.pattern[empty_indices[pos]] = name
                
            empty_indices = [idx for i, idx in enumerate(empty_indices) if i not in selected_pos]

    def _get_offset(self, past_schedule):
        """
        past_schedule: list of tuples (task_name, duration)
        """
        time_spent = {}
        for name, duration in past_schedule:
            time_spent[name] = time_spent.get(name, 0) + duration
            
        max_index = -1
        
        for name, total_time in time_spent.items():
            if total_time <= 0: continue
            
            # Calculate how many equivalent base slots this accumulated time represents
            slots_fulfilled = int(total_time // self.slot_duration)
            n = self.N_slots.get(name, 0)
            if n == 0: continue
            
            completed_quotas = slots_fulfilled // n
            
            if completed_quotas > 0:
                last_idx_in_pattern = -1
                for i, t_name in enumerate(self.pattern):
                    if t_name == name:
                        last_idx_in_pattern = i
                        
                global_index = (completed_quotas - 1) * self.Total_N + last_idx_in_pattern
                if global_index > max_index:
                    max_index = global_index
                    
        return max_index + 1

    def schedule(self, past_schedule, pinned_tasks=None, num_slots=8):
        """
        pinned_tasks: list of dicts [{'start': time, 'duration': int, 'name': 'TASK'}, ...]
        num_slots: Number of base slots to draw from the ideal pattern.
        """
        if pinned_tasks is None:
            pinned_tasks = []
        
        # Ensure pinned tasks are evaluated in chronological order
        pinned_tasks = sorted(pinned_tasks, key=lambda x: x['start'])

        offset = self._get_offset(past_schedule)
        idx = offset
        
        current_time = 0
        schedule_chunks = []
        
        slots_drawn = 0
        pinned_idx = 0
        
        current_pattern_task = None
        pattern_time_remaining = 0
        
        # Keep processing until we've fulfilled the requested number of slots
        # AND we've scheduled any fragment remaining from a collision.
        while slots_drawn < num_slots or pattern_time_remaining > 0:
            
            # 1. Pull the next dynamic task if we need one
            if pattern_time_remaining <= 0 and slots_drawn < num_slots:
                current_pattern_task = self.pattern[idx % self.Total_N]
                pattern_time_remaining = self.slot_duration
                idx += 1
                slots_drawn += 1
                
            # 2. Check distance to the next pinned task
            time_to_next_pin = float('inf')
            if pinned_idx < len(pinned_tasks):
                time_to_next_pin = pinned_tasks[pinned_idx]['start'] - current_time
                
            # 3. If we hit a pinned task exactly, schedule it and jump time forward
            if time_to_next_pin <= 0:
                p_task = pinned_tasks[pinned_idx]
                schedule_chunks.append((p_task['name'], p_task['duration']))
                current_time += p_task['duration']
                pinned_idx += 1
                continue # Loop again to re-evaluate without losing pattern_time_remaining
                
            # 4. Schedule the dynamic task up to the boundary of the next collision
            chunk_duration = min(pattern_time_remaining, time_to_next_pin)
            
            if chunk_duration > 0:
                schedule_chunks.append((current_pattern_task, chunk_duration))
                current_time += chunk_duration
                pattern_time_remaining -= chunk_duration
        
        # 5. Clean up the timeline by merging consecutive chunks with the identical name
        merged_results = []
        for name, dur in schedule_chunks:
            # Rounding to prevent floating point `.000001` weirdness
            dur = round(dur)
            if merged_results and merged_results[-1][0] == name:
                merged_results[-1] = (name, merged_results[-1][1] + dur)
            else:
                merged_results.append((name, dur))
                
        return merged_results

# ---------------------------------------------------------
# TESTS WITH ASSERTS
# ---------------------------------------------------------
if __name__ == "__main__":
    def format_output(res):
        return " ".join(f"{t} ({d}min)" for t, d in res)

    print("Running Tests...")

    # ----- OLD TESTS ADAPTED -----
    tasks_1_2 = [{'name': 'x', 'p': 50, 'min_time': 45}, {'name': 'y', 'p': 50, 'min_time': 45}]
    scheduler_1 = ProportionalScheduler(tasks_1_2)
    
    # Test 1 (Original)
    past_1 = [('x', 45)] * 8
    res_1 = format_output(scheduler_1.schedule(past_1, num_slots=8))
    assert res_1 == "y (45min) x (45min) y (45min) x (45min) y (45min) x (45min) y (45min) x (45min)"
    print("Output 1 (Adapted): Passed")
    
    # Test 2 (Original - No past schedule)
    # Tasks: x (50%), y (50%)
    past_2 = []
    res_2 = format_output(scheduler_1.schedule(past_2, num_slots=8))
    expected_2 = "x (45min) y (45min) x (45min) y (45min) x (45min) y (45min) x (45min) y (45min)"
    assert res_2 == expected_2, f"Test 2 Failed.\nExpected: {expected_2}\nGot: {res_2}"
    print("Output 2 (Adapted): Passed")

    # Test 3 (Original - Offset calculation with a 3rd minor task)
    # Tasks: x (50%), y (45%), z (5%)
    tasks_3 = [
        {'name': 'x', 'p': 50, 'min_time': 45},
        {'name': 'y', 'p': 45, 'min_time': 45},
        {'name': 'z', 'p': 5,  'min_time': 45}
    ]
    scheduler_3 = ProportionalScheduler(tasks_3)
    
    # Past schedule: 10 'y' blocks of 45 minutes
    past_3 = [('y', 45)] * 10
    res_3 = format_output(scheduler_3.schedule(past_3, num_slots=8))
    expected_3 = "x (45min) z (45min) x (45min) y (45min) x (45min) y (45min) x (45min) y (45min)"
    assert res_3 == expected_3, f"Test 3 Failed.\nExpected: {expected_3}\nGot: {res_3}"
    print("Output 3 (Adapted): Passed")

    # Test 4 (Original)
    tasks_4 = [{'name': 'x', 'p': 80, 'min_time': 30}, {'name': 'y', 'p': 20, 'min_time': 70}]
    scheduler_4 = ProportionalScheduler(tasks_4)
    res_4 = format_output(scheduler_4.schedule([], num_slots=10)) # 10 slots = 2 full cycles
    assert res_4 == "x (280min) y (70min) x (280min) y (70min)"
    print("Output 4 (Adapted): Passed")

    # ----- NEW TESTS -----
    
    # Test 5: Random length past tasks
    # 10m + 80m + 270m = 360 minutes total of 'x'. Base slot is 45m.
    # 360 / 45 = exactly 8 slots. This should act exactly like Test 1.
    past_5 = [('x', 10), ('x', 80), ('x', 270)]
    res_5 = format_output(scheduler_1.schedule(past_5, num_slots=8))
    assert res_5 == "y (45min) x (45min) y (45min) x (45min) y (45min) x (45min) y (45min) x (45min)"
    print("Output 5 (Random Past Lengths): Passed")

    # Test 6: Pinned Task Splitting a Block
    # Schedule: x(45), y(45), x(45), y(45).
    # Pin at t=60 for 30m. It should interrupt the first 'y' block.
    # Result: x(45), y(15), PIN(30), y(30), x(45), y(45)
    pinned_6 = [{'start': 60, 'duration': 30, 'name': 'MEETING'}]
    res_6 = format_output(scheduler_1.schedule([], pinned_tasks=pinned_6, num_slots=4))
    expected_6 = "x (45min) y (15min) MEETING (30min) y (30min) x (45min) y (45min)"
    assert res_6 == expected_6, f"Failed 6. Got: {res_6}"
    print("Output 6 (Pinned task interruption): Passed")

    # Test 7: Complex Merge with Pinned Task
    # Base pattern is x(280), y(70).
    # Pin at t=100 for 50m.
    # Result: x(100), LUNCH(50), x(180) -> this fulfills the 280m of 'x', followed by y(70).
    pinned_7 = [{'start': 100, 'duration': 50, 'name': 'LUNCH'}]
    res_7 = format_output(scheduler_4.schedule([], pinned_tasks=pinned_7, num_slots=5))
    expected_7 = "x (100min) LUNCH (50min) x (180min) y (70min)"
    assert res_7 == expected_7, f"Failed 7. Got: {res_7}"
    print("Output 7 (Complex pin merge): Passed")

    print("\nAll assert tests passed successfully!")