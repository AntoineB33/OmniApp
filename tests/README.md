There are tasks. Each task has a minimum time and a priority percentage. The scheduler must place panels of tasks in a timeline from t_now to +infinity. When the scheduler places a task, it cannot then place another task until the placed one reaches its minimum time.
The scheduler must satisfy as much as possible the priority percentages, in a scale as small as possible.
Example: task A 50% 10min and task B 50% 10min
wrong: task A 1h, then task B 1h and so on...
right: task A 10min, then task B 10min and so on...

The starting timeline has already placed tasks.

The timeline is formed of periods. Each period defines a set of tasks it accepts.

If a debt or an excess is higher that it could be in a scheduling on a clear and all accepting timeline, then the higher part is ignored exponentially in relation to the distance from the task creating this debt or excess, and completely ignored with an epsilon rounding. The decay speed is always the same.
Example: task A 50% 10min and task B 50% 10min, between 100 and 1000 only task B is possible.
result: Near 100 before it, task A becomes predominant.

Since the timeline to fill is infinite, the scheduler doesn't give an infinite list, but a finite list of rules to fill the timeline with a O(1) complexity.
Example 1:
In test 3, the result must include the instruction to fill every gap between long A tasks with only B tasks. Here it is a very simple repeating pattern.
Example 2:
Task A 50% 10min and task B 50% 10min, task A 1h at t=0 on the starting timeline.
The result instructions are the placement of the tasks right after the long task A, followed by the simple instruction : task A 10min, then task B 10min and so on...

If the starting timeline has aperiodic, infinite events, or if the number of necessary instructions is too large, then it is capped.

For now, I am looking for the algorithm that does exactly that, even if it takes time with hundreds of tasks and hundreds of tasks and periods in the starting timeline.

For each example test in the script, the list of instructions must be printed (the 10 first and the total number of instructions).