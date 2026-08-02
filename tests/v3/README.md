There are tasks. Each task has a minimum time and a priority percentage. The scheduler must place panels of tasks in a timeline from t_now to +infinity. When the scheduler places a task, it cannot then place another task until the placed one reaches its minimum time.
The scheduler must satisfy as much as possible the priority percentages, in a scale as small as possible.
Example: task A 50% 10min and task B 50% 10min
wrong: task A 1h, then task B 1h and so on...
right: task A 10min, then task B 10min and so on...

Since the timeline to fill is infinite, the scheduler doesn't give an infinite list, but a finite list of rules to fill the timeline with a O(1) complexity. This list is used to construct the schedule when displaying it from t=0 to t=x.
Example: task A 50% 10min and task B 50% 10min
result: list of three elements:
- task A 10min
- task B 10min
- repeat

The starting timeline has already placed tasks.

The timeline is formed of periods. Each period defines a set of tasks it accepts.

If a debt or an excess is higher that it could be in a scheduling on a clear and all accepting timeline, then the higher part is ignored exponentially in relation to the distance from the task creating this debt or excess, and completely ignored with an epsilon rounding. The decay speed is always the same.

If the starting timeline has aperiodic, infinite events, or if the number of necessary instructions is too large, then the resulting set of rules should not cover up to +infinity, but as far as possible in the future. There must be at most 50 rules.