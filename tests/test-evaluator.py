import importlib.util
import itertools
import sys
from pathlib import Path


def load_module(module_name, file_path):
    """Dynamically loads a Python file as a module (handles hyphens in filenames)."""
    spec = importlib.util.spec_from_file_location(module_name, file_path)
    
    # Add a check for spec.loader to satisfy strict type checking
    if spec is None or spec.loader is None:
        print(f"Error: Could not load {file_path}. Make sure the file exists.")
        sys.exit(1)
        
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def calculate_integral_score(schedule, tasks, t_max):
    """
    Calculates the discrete approximation of the double integral of the scheduling error.
    Lower score is better.
    """
    # 1. Build minute-by-minute prefix sums for O(1) window queries
    # prefix_active[t] = total active minutes from time 0 to t
    # prefix_task[name][t] = total minutes task ran from time 0 to t
    prefix_active = [0] * (t_max + 1)
    prefix_task = {tk.name: [0] * (t_max + 1) for tk in tasks}
    
    current_t = 0
    for chunk in schedule:
        if current_t >= t_max:
            break
            
        task_name = chunk["task"]
        dur = int(chunk["duration"]) # Schedulers yield math.ceil integer chunks
        
        end_t = min(current_t + dur, t_max)
        is_active = task_name not in ["INACTIVE", "IDLE / DEAD TIME"]
        
        for t in range(current_t, end_t):
            prefix_active[t + 1] = prefix_active[t] + (1 if is_active else 0)
            for tk in tasks:
                prefix_task[tk.name][t + 1] = prefix_task[tk.name][t] + (1 if tk.name == task_name else 0)
                
        current_t = end_t

    # 2. Compute ideal priorities
    total_priority = sum(tk.priority for tk in tasks)
    targets = {tk.name: tk.priority / total_priority for tk in tasks}
    
    total_error_integral = 0.0
    valid_windows = 0
    
    # 3. Calculate 2D Integral sum over all possible [t1, t2] windows
    for t1 in range(t_max):
        for t2 in range(t1 + 1, t_max + 1):
            active_duration = prefix_active[t2] - prefix_active[t1]
            
            # If the window has no active time, perfect fairness is trivially 0 discrepancy
            if active_duration > 0:
                window_error = 0.0
                for tk in tasks:
                    actual_ratio = (prefix_task[tk.name][t2] - prefix_task[tk.name][t1]) / active_duration
                    window_error += abs(actual_ratio - targets[tk.name])
                
                total_error_integral += window_error
                valid_windows += 1

    # Return the average error per active window to normalize the result
    return total_error_integral / valid_windows if valid_windows > 0 else float('inf')


def run_evaluation(algo_module, t_max, test_name, tasks_builder, pattern_builder):
    """Generates the schedule using the provided module and scores it."""
    tasks = tasks_builder(algo_module)
    timeline = pattern_builder(algo_module)
    
    # Generate schedule up to T_max
    scheduler = algo_module.schedule_timeline(tasks, timeline)
    schedule = []
    accumulated_time = 0
    
    for chunk in scheduler:
        schedule.append(chunk)
        accumulated_time += chunk["duration"]
        if accumulated_time >= t_max:
            break
            
    score = calculate_integral_score(schedule, tasks, t_max)
    return score


def main():
    print("Loading modules...")
    
    # Dynamically get the folder where test-evaluator.py is located
    current_dir = Path(__file__).parent
    wfwfq_path = str(current_dir / "test-wfwfq.py")
    ffq_path = str(current_dir / "test-ccb.py")
    
    wfwfq_mod = load_module("wfwfq", wfwfq_path)
    ffq_mod = load_module("ffq", ffq_path)
    
    T_MAX = 1500  # Evaluate over 25 hours of schedule (high enough for long-term limit, low enough for quick python execution)

    # --- Define Test Scenarios (Independent from modules) ---
    def build_tasks_ex2(mod):
        return [
            mod.Task("Deep Work", priority=60, min_time=90, needs_screen=True),
            mod.Task("Reading", priority=20, min_time=45, needs_screen=False),
            mod.Task("Quick Chores", priority=20, min_time=15, needs_screen=False)
        ]

    def build_pattern_ex2(mod):
        base_pattern = [
            mod.TimeBlock(120, mod.Period.SCREEN),
            mod.TimeBlock(60, mod.Period.NO_SCREEN),
            mod.TimeBlock(420, mod.Period.BOTH),
            mod.TimeBlock(30, mod.Period.INACTIVE)
        ]
        return itertools.cycle(base_pattern)

    print(f"\nEvaluating Custom Daily Pattern (T = {T_MAX} minutes)")
    print("-" * 60)
    
    # Evaluate WF²Q
    score_wfwfq = run_evaluation(wfwfq_mod, T_MAX, "WF²Q", build_tasks_ex2, build_pattern_ex2)
    print(f"WF²Q Integral Discrepancy Score: {score_wfwfq:.5f}")
    
    # Evaluate FFQ
    score_ffq = run_evaluation(ffq_mod, T_MAX, "FFQ", build_tasks_ex2, build_pattern_ex2)
    print(f"FFQ Integral Discrepancy Score:  {score_ffq:.5f}")
    
    print("-" * 60)
    if score_ffq < score_wfwfq:
        print(f"🏆 FFQ is better by {(score_wfwfq - score_ffq):.5f} (lower is better)")
    elif score_wfwfq < score_ffq:
        print(f"🏆 WF²Q is better by {(score_ffq - score_wfwfq):.5f} (lower is better)")
    else:
        print("🤝 It's a tie!")

if __name__ == "__main__":
    main()