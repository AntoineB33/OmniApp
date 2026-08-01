There are tasks. Each task has a minimum time and a priority percentage. The scheduler must place panels of tasks in a timeline from t_now to +infinity. When the scheduler places a task, it cannot then place another task until the placed one reaches its minimum time.
The scheduler must satisfy as much as possible the priority percentages, in a scale as small as possible.
Example: task A 50% 10min and task B 50% 10min
wrong: task A 1h, then task B 1h and so on...
right: task A 10min, then task B 10min and so on...

The starting timeline has already placed tasks.

The timeline is formed of periods. Each period defines a set of tasks it accepts.

A debt or an excess that is higher that it could be in a scheduling on a clear and all task accepted timeline is ignored exponentially as we move away from it (in both direction). The decay speed is always the same.

Since the timeline to fill is infinite, the scheduler doesn't give an infinite list, but a finite list of rules to fill the timeline with a O(1) complexity. It is possible because to infinity the scheduling becomes an exact pattern that repeat infinitely.
Example 1:
In test 3, the result must include the instruction to fill every gap between long A tasks with only B tasks. Here it is a very simple repeating pattern.
Example 2:
Task A 50% 10min and task B 50% 10min, task A 1h at t=0 on the starting timeline.
The result instructions are the placement of the tasks right after the long task A, followed by the simple instruction : task A 10min, then task B 10min and so on...

For now, I am looking for the algorithm that does exactly that, even if it takes time with hundreds of tasks and hundreds of tasks and periods in the starting timeline.

Does the script strictly satisfies the requirements? If not fix it, or explain why the requirements are flawed.
Keep the current example tests, and add more if needed.

Anomaly on current script: Test 3 must actually be an infinite pattern, not just repeated 5 times. Add a way to define infinite patterns in the starting timeline.