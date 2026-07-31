There are tasks. Each task has a minimum time and a priority percentage. The scheduler must place panels of tasks in a timeline from t_now to +infinity. The starting timeline has already placed tasks. The timeline is composed of periods, each can exclude any set of tasks. When the scheduler places a task, it cannot then place another task until this one reaches its minimum time (can only be interrupted by already placed tasks or periods).

The scheduler must satisfy as much as possible the priority percentages, in a scale as small as possible.

Example: task A 50% 10min and task B 50% 10min
wrong: task A 1h, then task B 1h and so on...
right: task A 10min, then task B 10min and so on...

I want presence debt to be ignored.
In a window w1 of the timeline that is clear (no task and only periods that include all tasks), the schedule of this window must be the same as a window w2 of the same size somewhere in the schedule of a completely clear timeline. The memory of the tasks placed before in the timeline only dictates where should w2 be placed.

Example 1: task A 50% 10min and task B 50% 10min, timeline starts with 1h of task A
wrong: task A 1h, task B 1h, then task A 10min, task B 10min and so on...
right: task A 1h, then task B 10min, task A 10min and so on...
In this example, the schedule of a completely clear timeline is this:
task A 10min, then task B 10min and so on...
The memory of task A 1h dictated that w2 must be placed at the start of task B 10min.

Example 2: task A 45% 10min, task B 45% 10min and task C 10% 10min, timeline starts with 3h of task A then 3h of task B then 3h of task D.
result at 6h should start with task C 10min

For a not clear window, I don't know how to formulate this idea of ignoring debt.