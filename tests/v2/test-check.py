import json
import math
import os
import subprocess
from typing import Any

INF = 1_000_000_000  # Represents infinity for timeline bounds

# --- Configuration ---
SCHEDULER_SCRIPTS = [
    "./v1/scheduler.py",
    "./v2/scheduler.py"
]

# --- Test Cases ---
# Using patterns to define recurring tasks and periods
TEST_CASES = [
    {
        "name": "Example 3: Infinite Task Pattern and Universal Period",
        "tasks": [
            {"id": "A", "priority": 50, "min_time": 10},
            {"id": "B", "priority": 50, "min_time": 10}
        ],
        "task_patterns": [
            {
                # A 1-hour block of Task A, repeating every 24 hours, infinitely
                "sequence": [{"id": "A", "duration": 60}],
                "start": 0,
                "interval": 1440, # 24 hours in minutes
                "repetitions": math.inf 
            }
        ],
        "period_patterns": [
            {
                # A 2-hour maintenance window where only B is allowed, every 12 hours
                "included_tasks": ["B"],
                "duration": 120,
                "start": 120,
                "interval": 720,
                "repetitions": 5
            }
        ],
        "test_duration": 2880 # 48 hours
    }
]

def expand_patterns(tasks_def: list[dict], task_patterns: list[dict], period_patterns: list[dict], duration: int) -> tuple[list[dict], list[dict]]:
    """Unrolls patterns into flat lists of timeline blocks up to the test duration."""
    flat_tasks = []
    flat_periods = []
    
    # 1. Base Universal Period (-infinity to +infinity allowing all tasks)
    all_task_ids = [t["id"] for t in tasks_def]
    flat_periods.append({
        "start": -INF,
        "duration": 2 * INF,
        "included_tasks": all_task_ids
    })

    # 2. Expand Task Patterns
    for pattern in task_patterns:
        current_time = pattern["start"]
        reps = 0
        while reps < pattern["repetitions"] and current_time < duration:
            seq_offset = 0
            for block in pattern["sequence"]:
                block_start = current_time + seq_offset
                if block_start < duration:
                    flat_tasks.append({
                        "id": block["id"],
                        "start": block_start,
                        "duration": block["duration"]
                    })
                seq_offset += block["duration"]
            
            current_time += pattern.get("interval", seq_offset) # If no interval, run back-to-back
            reps += 1

    # 3. Expand Period Patterns
    for pattern in period_patterns:
        current_time = pattern["start"]
        reps = 0
        while reps < pattern["repetitions"] and current_time < duration:
            if current_time < duration:
                flat_periods.append({
                    "start": current_time,
                    "duration": pattern["duration"],
                    "included_tasks": pattern["included_tasks"]
                })
            current_time += pattern.get("interval", pattern["duration"])
            reps += 1

    # Sort sequentially
    flat_tasks.sort(key=lambda x: x["start"])
    flat_periods.sort(key=lambda x: x["start"])
    
    return flat_tasks, flat_periods

def run_scheduler(script_path: str, payload: dict[str, Any]) -> list[dict[str, Any]]:
    """Executes the scheduler script and returns the parsed JSON schedule."""
    # (Implementation remains exactly the same as previously defined)
    process = subprocess.Popen(
        ["python", script_path],
        stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True
    )
    stdout, stderr = process.communicate(input=json.dumps(payload))
    if process.returncode != 0:
        raise RuntimeError(f"Script {script_path} failed:\n{stderr}")
    return json.loads(stdout)

def extract_schedule_sequence(schedule: list[dict[str, Any]], start_time: int, end_time: int) -> list[tuple[str, int]]:
    """Extracts a sequence of (task_id, duration) within a specific time window."""
    sequence = []
    for block in schedule:
        if block["start"] >= start_time and (block["start"] + block["duration"]) <= end_time:
            sequence.append((block["id"], block["duration"]))
    return sequence

def is_subsequence(sub: list[tuple[str, int]], main: list[tuple[str, int]]) -> bool:
    """Checks if the 'sub' sequence exists continuously inside the 'main' sequence."""
    n, m = len(sub), len(main)
    if n == 0: return True
    for i in range(m - n + 1):
        if main[i:i+n] == sub: return True
    return False

def calculate_clear_windows(initial_tasks: list[dict], periods: list[dict], all_tasks: set, duration: int) -> list[tuple[int, int]]:
    """Calculates time intervals completely free of initial tasks and restrictive periods."""
    blocked_intervals = []
    
    for t in initial_tasks:
        blocked_intervals.append((t["start"], t["start"] + t["duration"]))
        
    for p in periods:
        # A period is only "blocking" if it restricts tasks (doesn't include all tasks)
        if set(p.get("included_tasks", [])) != all_tasks:
            # Bound the infinite periods for calculation
            start = max(0, p["start"])
            end = min(duration, p["start"] + p["duration"])
            if start < end:
                blocked_intervals.append((start, end))
        
    blocked_intervals.sort(key=lambda x: x[0])
    
    # Merge blocks
    merged = []
    for b in blocked_intervals:
        if not merged or merged[-1][1] < b[0]:
            merged.append(b)
        else:
            merged[-1] = (merged[-1][0], max(merged[-1][1], b[1]))
            
    # Invert to find clear windows
    clear_windows = []
    current_time = 0
    for b in merged:
        if current_time < b[0]:
            clear_windows.append((current_time, b[0]))
        current_time = max(current_time, b[1])
        
    if current_time < duration:
        clear_windows.append((current_time, duration))
        
    return clear_windows

def main():
    for script in SCHEDULER_SCRIPTS:
        if not os.path.exists(script):
            continue
            
        print(f"\n=== Testing {script} ===")
        
        for case in TEST_CASES:
            print(f"  Running Case: {case['name']}")
            all_task_ids = {t["id"] for t in case["tasks"]}
            
            # Expand declarative patterns into flat timeline data
            flat_initial_tasks, flat_periods = expand_patterns(
                case["tasks"], 
                case.get("task_patterns", []), 
                case.get("period_patterns", []), 
                case["test_duration"]
            )
            
            # 1. Run Clean Timeline (Only the Universal Period)
            clean_payload = {
                "tasks": case["tasks"],
                "initial_timeline": [],
                "periods": [{"start": -INF, "duration": 2 * INF, "included_tasks": list(all_task_ids)}],
                "duration": case["test_duration"]
            }
            clean_schedule = run_scheduler(script, clean_payload)
            clean_sequence = extract_schedule_sequence(clean_schedule, 0, case["test_duration"])
            
            # 2. Run Dirty Timeline
            dirty_payload = {
                "tasks": case["tasks"],
                "initial_timeline": flat_initial_tasks,
                "periods": flat_periods,
                "duration": case["test_duration"]
            }
            dirty_schedule = run_scheduler(script, dirty_payload)
            
            # 3. Analyze Clear Windows
            clear_windows = calculate_clear_windows(flat_initial_tasks, flat_periods, all_task_ids, case["test_duration"])
            
            passed = True
            min_possible_time = min(t["min_time"] for t in case["tasks"])
            
            for w_start, w_end in clear_windows:
                if (w_end - w_start) < min_possible_time:
                    continue
                    
                dirty_sequence = extract_schedule_sequence(dirty_schedule, w_start, w_end)
                
                if not is_subsequence(dirty_sequence, clean_sequence):
                    print(f"    [FAIL] Window [{w_start}-{w_end}] sequence not found in clean schedule.")
                    passed = False
                    break
                    
            if passed:
                print("    [PASS] All clear windows match the clean timeline pattern.")

if __name__ == "__main__":
    main()