class TaskScheduler:
    def __init__(self, tasks):
        self.tasks = tasks
        self.vtime = {t: 0.0 for t in tasks}
        
        # Pre-calculate costs: Inverse of priority (e.g., 50% -> 2.0, 5% -> 20.0)
        self.costs = {t: 1.0 / (props['priority'] / 100.0) for t, props in tasks.items()}
        
        # Determine the uniform slot duration based on the largest minimum time
        self.slot_duration = max(props['min_time'] for props in tasks.values())
        
    def apply_bounded_lag(self):
        """Forgives deep debt by pulling starved tasks up to the leader's heels."""
        if not self.vtime:
            return
        
        max_vtime = max(self.vtime.values())
        
        # The maximum lag allowed is the cost of the most frequent task
        max_lag = min(self.costs.values()) 
        min_allowed = max_vtime - max_lag
        
        for t in self.vtime:
            if self.vtime[t] < min_allowed:
                self.vtime[t] = min_allowed
                
    def add_past_schedule(self, past_list):
        """Simulates the past history to establish the initial timeline debt."""
        for t in past_list:
            if t in self.vtime:
                self.vtime[t] += self.costs[t]
        
        # Apply the debt forgiveness so the system can smoothly resume rhythm
        self.apply_bounded_lag()
        
    def get_next_task(self):
        # Select task with lowest vtime. Alphabetical tie-breaker ensures consistency.
        best_task = min(self.vtime.keys(), key=lambda t: (self.vtime[t], t))
        
        # Advance the task's virtual time
        self.vtime[best_task] += self.costs[best_task]
        return best_task, self.slot_duration
        
    def generate_future_schedule(self, steps):
        return [self.get_next_task() for _ in range(steps)]

# ==========================================
# Running the Examples
# ==========================================

print("--- Example 1 ---")
tasks_1 = {
    'x': {'priority': 50, 'min_time': 45},
    'y': {'priority': 50, 'min_time': 45}
}

# With Past Schedule
scheduler_1a = TaskScheduler(tasks_1)
scheduler_1a.add_past_schedule(['x'] * 8)
schedule_1a = scheduler_1a.generate_future_schedule(8)
print("Past schedule is 8 x's:")
print("Output:", " ".join(t for t, duration in schedule_1a))

# Without Past Schedule
scheduler_1b = TaskScheduler(tasks_1)
schedule_1b = scheduler_1b.generate_future_schedule(8)
print("\nPast schedule is nothing:")
print("Output:", " ".join(t for t, duration in schedule_1b))


print("\n--- Example 2 ---")
tasks_2 = {
    'x': {'priority': 50, 'min_time': 45},
    'y': {'priority': 45, 'min_time': 45},
    'z': {'priority': 5,  'min_time': 45}
}
scheduler_2 = TaskScheduler(tasks_2)
scheduler_2.add_past_schedule(['y'] * 10)
schedule_2 = scheduler_2.generate_future_schedule(50)
print("Past schedule is 10 y's:")
print("Output:", " ".join(t for t, duration in schedule_2))


print("\n--- Example 3 ---")
tasks_3 = {
    'x': {'priority': 50, 'min_time': 30},
    'y': {'priority': 50, 'min_time': 25}
}
scheduler_3 = TaskScheduler(tasks_3)
schedule_3 = scheduler_3.generate_future_schedule(4)
print("Past schedule is nothing:")
formatted_output = ", ".join(f"{t} for {d} min" for t, d in schedule_3)
print("Output:", formatted_output + "...")



print("\n--- Example 4: The Monopoly ---")
tasks_4 = {
    'A': {'priority': 90, 'min_time': 10},
    'B': {'priority': 5,  'min_time': 10},
    'C': {'priority': 5,  'min_time': 10}
}
scheduler_4 = TaskScheduler(tasks_4)
# No past schedule, just watching the natural rhythm
schedule_4 = scheduler_4.generate_future_schedule(25)
print("Output:", " ".join(t for t, duration in schedule_4))


print("\n--- Example 5: Vacation Recovery ---")
tasks_5 = {
    'Main': {'priority': 80, 'min_time': 30},
    'Side': {'priority': 20, 'min_time': 30}
}
scheduler_5 = TaskScheduler(tasks_5)
scheduler_5.add_past_schedule(['Side'] * 10)
schedule_5 = scheduler_5.generate_future_schedule(10)
print("Output:", " ".join(t for t, duration in schedule_5))


print("\n--- Example 6: The Traffic Jam ---")
tasks_6 = {
    'A': {'priority': 20, 'min_time': 15},
    'B': {'priority': 20, 'min_time': 15},
    'C': {'priority': 20, 'min_time': 15},
    'D': {'priority': 20, 'min_time': 15},
    'E': {'priority': 20, 'min_time': 15}
}
scheduler_6 = TaskScheduler(tasks_6)
scheduler_6.add_past_schedule(['A'] * 5)
schedule_6 = scheduler_6.generate_future_schedule(15)
print("Output:", " ".join(t for t, duration in schedule_6))