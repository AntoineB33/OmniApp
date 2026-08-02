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