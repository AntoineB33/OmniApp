import importlib.util
import itertools
import sys
from pathlib import Path


def load_module(module_name, file_path):
    """Dynamically loads a Python file as a module."""
    spec = importlib.util.spec_from_file_location(module_name, file_path)
    if spec is None or spec.loader is None:
        print(f"Error: Could not load {file_path}. Make sure the file exists.")
        sys.exit(1)
        
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def calculate_scores(schedule, tasks, t_max):
    """
    Calculates both the 2D Integral Score (Average Discrepancy) and the 
    Maximum Window Discrepancy (Worst-Case Fairness).
    """
    prefix_active = [0] * (t_max + 1)
    prefix_task = {tk.name: [0] * (t_max + 1) for tk in tasks}
    
    current_t = 0
    for chunk in schedule:
        if current_t >= t_max:
            break
            
        task_name = chunk["task"]
        dur = int(chunk["duration"])
        
        end_t = min(current_t + dur, t_max)
        is_active = task_name not in ["INACTIVE", "IDLE / DEAD TIME"]
        
        for t in range(current_t, end_t):
            prefix_active[t + 1] = prefix_active[t] + (1 if is_active else 0)
            for tk in tasks:
                prefix_task[tk.name][t + 1] = prefix_task[tk.name][t] + (1 if tk.name == task_name else 0)
                
        current_t = end_t

    total_priority = sum(tk.priority for tk in tasks)
    targets = {tk.name: tk.priority / total_priority for tk in tasks}
    
    total_error_integral = 0.0
    max_window_error = 0.0
    valid_windows = 0
    
    for t1 in range(t_max):
        for t2 in range(t1 + 1, t_max + 1):
            active_duration = prefix_active[t2] - prefix_active[t1]
            
            if active_duration > 0:
                window_error = 0.0
                for tk in tasks:
                    actual_ratio = (prefix_task[tk.name][t2] - prefix_task[tk.name][t1]) / active_duration
                    window_error += abs(actual_ratio - targets[tk.name])
                
                total_error_integral += window_error
                max_window_error = max(max_window_error, window_error)
                valid_windows += 1

    avg_integral = total_error_integral / valid_windows if valid_windows > 0 else float('inf')
    return avg_integral, max_window_error


def run_evaluation(algo_module, t_max, tasks_builder, pattern_builder):
    tasks = tasks_builder(algo_module)
    timeline = pattern_builder(algo_module)
    
    scheduler = algo_module.schedule_timeline(tasks, timeline)
    schedule = []
    accumulated_time = 0
    
    for chunk in scheduler:
        schedule.append(chunk)
        accumulated_time += chunk["duration"]
        if accumulated_time >= t_max:
            break
            
    return calculate_scores(schedule, tasks, t_max)


def main():
    print("Loading modules...")
    current_dir = Path(__file__).parent
    wfwfq_mod = load_module("wfwfq", str(current_dir / "test-wfwfq.py"))
    ffq_mod = load_module("ffq", str(current_dir / "test-ccb.py"))
    
    T_MAX = 1500

    scenarios = []

    # --- SCENARIO 1: The Original Daily Pattern ---
    def build_tasks_1(mod):
        return [
            mod.Task("Deep Work", priority=60, min_time=90, needs_screen=True),
            mod.Task("Reading", priority=20, min_time=45, needs_screen=False),
            mod.Task("Quick Chores", priority=20, min_time=15, needs_screen=False)
        ]
    def build_pattern_1(mod):
        return itertools.cycle([
            mod.TimeBlock(120, mod.Period.SCREEN),
            mod.TimeBlock(60, mod.Period.NO_SCREEN),
            mod.TimeBlock(420, mod.Period.BOTH),
            mod.TimeBlock(30, mod.Period.INACTIVE)
        ])
    scenarios.append(("1. Standard Daily Pattern (Low Contention)", build_tasks_1, build_pattern_1))

    # --- SCENARIO 2: Adversarial Credit Starvation (The WF²Q Killer Feature) ---
    # Background task runs during NO_SCREEN, forcing Screen tasks to build massive credit.
    # FFQ will burst the high priority screen task and starve the low priority one.
    # WF²Q will neatly interleave them using Virtual Finish Times.
    def build_tasks_2(mod):
        return [
            mod.Task("High Pri Screen", priority=80, min_time=10, needs_screen=True),
            mod.Task("Low Pri Screen", priority=10, min_time=10, needs_screen=True),
            mod.Task("Background (No Screen)", priority=10, min_time=10, needs_screen=False)
        ]
    def build_pattern_2(mod):
        return itertools.cycle([
            mod.TimeBlock(300, mod.Period.NO_SCREEN), 
            mod.TimeBlock(300, mod.Period.SCREEN)     
        ])
    scenarios.append(("2. Adversarial Credit Starvation (High Contention)", build_tasks_2, build_pattern_2))

    # --- RUN EVALUATIONS ---
    for name, t_builder, p_builder in scenarios:
        print(f"\n{name} (T = {T_MAX} minutes)")
        print("-" * 70)
        
        wf_int, wf_max = run_evaluation(wfwfq_mod, T_MAX, t_builder, p_builder)
        ffq_int, ffq_max = run_evaluation(ffq_mod, T_MAX, t_builder, p_builder)
        
        print(f"{'Algorithm':<10} | {'Integral Score (Avg)':<22} | {'Worst-case Discrepancy (Max)':<25}")
        print("-" * 70)
        print(f"{'WF²Q':<10} | {wf_int:<22.5f} | {wf_max:<25.5f}")
        print(f"{'FFQ':<10} | {ffq_int:<22.5f} | {ffq_max:<25.5f}")
        
        print("-" * 70)
        if wf_int < ffq_int:
            print(f"🏆 Integral Winner: WF²Q (by {ffq_int - wf_int:.5f})")
        else:
            print(f"🏆 Integral Winner: FFQ  (by {wf_int - ffq_int:.5f})")
            
        if wf_max < ffq_max:
            print(f"🛡️ Worst-Case Winner: WF²Q (by {ffq_max - wf_max:.5f})")
        else:
            print(f"🛡️ Worst-Case Winner: FFQ  (by {wf_max - ffq_max:.5f})")

if __name__ == "__main__":
    main()