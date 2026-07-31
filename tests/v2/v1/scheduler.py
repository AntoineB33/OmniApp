#!/usr/bin/env python3
import json
import sys


def main():
    try:
        input_data = json.load(sys.stdin)
    except json.JSONDecodeError:
        print("[]")
        return
        
    tasks_def = input_data.get("tasks", [])
    initial_timeline = input_data.get("initial_timeline", [])
    periods = input_data.get("periods", [])
    max_duration = input_data.get("duration", 0)
    
    # Build tasks dictionary
    tasks = {t["id"]: t for t in tasks_def}
    
    # Schedulable tasks (must have priority > 0 to avoid division by zero)
    schedulable_tasks = {t["id"] for t in tasks_def if t.get("priority", 0) > 0}
    
    # Virtual times for each schedulable task
    V = {t_id: 0.0 for t_id in schedulable_tasks}
    
    # Extract all critical event times to safely bound blocks
    events = set()
    events.add(max_duration)
    for b in initial_timeline:
        events.add(b["start"])
        events.add(b["start"] + b["duration"])
    for p in periods:
        events.add(p["start"])
        events.add(p["start"] + p["duration"])
        
    # Sorted list of event boundaries
    events = sorted([e for e in events if e >= 0])
    
    # We will output all placed blocks (including initial ones that fit)
    schedule = []
    for b in initial_timeline:
        if b["start"] < max_duration:
            dur = min(b["duration"], max_duration - b["start"])
            if dur > 0:
                schedule.append({"id": b["id"], "start": b["start"], "duration": dur})
                
    def get_allowed_tasks(current_time):
        """Returns the set of tasks allowed at the current time based on periods."""
        active_periods = [p for p in periods if p["start"] <= current_time < p["start"] + p["duration"]]
        
        # Intersect all active periods
        allowed = set(schedulable_tasks)
        for p in active_periods:
            allowed = allowed.intersection(p.get("included_tasks", []))
        return allowed

    t = 0
    
    while t < max_duration:
        # 1. Check if we are currently inside a forced initial_timeline block
        inside_initial = False
        for b in initial_timeline:
            if b["start"] <= t < b["start"] + b["duration"]:
                block_end = b["start"] + b["duration"]
                
                # Advance virtual time for this task, acting as the "memory" of phase
                if b["id"] in schedulable_tasks:
                    dur_from_t = block_end - t
                    V[b["id"]] += dur_from_t / tasks[b["id"]]["priority"]
                
                t = block_end
                inside_initial = True
                break
        
        if inside_initial:
            continue
            
        # 2. Free time scheduling
        S = get_allowed_tasks(t)
        
        if not S:
            # If no tasks are allowed, fast-forward to the next event
            next_event = min((e for e in events if e > t), default=max_duration)
            t = next_event
            continue
            
        # --- Debt Forgiveness Rule (The Cap) ---
        # To ignore debt but preserve exact phase, we cap how far behind a task's virtual 
        # time can fall. The maximum natural divergence in a clean schedule is bounded by
        # the maximum step size (min_time / priority) among the active tasks.
        v_max = max(round(V[k], 6) for k in S)
        d_max = max(tasks[k]["min_time"] / tasks[k]["priority"] for k in S)
        
        for k in S:
            V[k] = max(V[k], v_max - d_max)
            
        # Pick the task with the minimum virtual time
        # Break ties consistently using task ID
        best_task = min(S, key=lambda k: (round(V[k], 6), k))
        
        # Determine how long we can run it
        next_event = min((e for e in events if e > t), default=max_duration)
        run_time = min(tasks[best_task]["min_time"], next_event - t)
        
        if run_time > 0:
            schedule.append({
                "id": best_task,
                "start": t,
                "duration": run_time
            })
            # Advance virtual time
            V[best_task] += run_time / tasks[best_task]["priority"]
            t += run_time
        else:
            # Fallback to avoid infinite loops if something goes wrong
            t += 1
            
    # Sort the final combined schedule by start time
    schedule.sort(key=lambda x: x["start"])
    print(json.dumps(schedule))

if __name__ == "__main__":
    main()