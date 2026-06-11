from collections import defaultdict

# Import our custom modules
from rules_creator import TaskScheduler
from scheduler import DynamicGapFiller
from plotter import plot_timeline

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


if __name__ == "__main__":
    # Base task rules
    tasks = [
        {"name": "A", "priority": 0.50, "min_time": 45},
        {"name": "B", "priority": 0.45, "min_time": 45},
        {"name": "C", "priority": 0.05, "min_time": 45}
    ]

    # Pre-existing schedule with fixed appointments
    pinned_tasks = []

    t1 = 0
    t2 = 24 * 60 # 24-hour window

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

    # Run the visualization
    plot_timeline(final_schedule, t1, t2)