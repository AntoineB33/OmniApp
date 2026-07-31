There are tasks. Each task has a minimum time and a priority percentage. The scheduler must place panels of tasks in a timeline from t_now to +infinity. When the scheduler places a task, it cannot then place another task until the placed one reaches its minimum time.
The scheduler must satisfy as much as possible the priority percentages, in a scale as small as possible.
Example: task A 50% 10min and task B 50% 10min
wrong: task A 1h, then task B 1h and so on...
right: task A 10min, then task B 10min and so on...

The starting timeline has already placed tasks.

The timeline is formed of periods. Each period defines a set of tasks it accepts.

A debt or an excess that is higher that it could be in a scheduling on a clear and all task accepted timeline is ignored exponentially as we move away from it (in both direction). The decay speed is always the same.

Since the timeline is infinite, the result of the scheduler is a function that takes time as a parameter and returns a task or null.

For now, I am looking for the algorithm that does exactly that, even if it takes time with hundreds of tasks and hundreds of tasks and periods in the starting timeline.

Does the script strictly satisfies the requirements? If not fix it. Keep the current example tests, and add more if needed.