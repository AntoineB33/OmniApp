There are tasks. Each task has a minimum time and a priority percentage. The scheduler must place panels of tasks in a timeline from t_now to +infinity. When the scheduler places a task, it cannot then place another task until the placed one reaches its minimum time.
The scheduler must satisfy as much as possible the priority percentages, in a scale as small as possible.
Example: task A 50% 10min and task B 50% 10min
wrong: task A 1h, then task B 1h and so on...
right: task A 10min, then task B 10min and so on...

Write the Python script with one example test in the __main__ block and a function to visualize the result.


Now the starting timeline has already placed tasks.
But here are the problems I had in mind:
- task A 50% 10min and task B 50% 10min, if the starting timeline has a lot of task A before t_now, I don't want debt decreasing task A in the future. Instead, I want debt to be completely ignored here, but simply task B will be the starting task after t_now. In other words, in this case, from t_now to +infinity, it is the normal schedule, simply shifted for it to start with task B because of the previous abundance of task A.
- If the starting timeline has this infinite pattern: task A 1h, then nothing for 15min, then task A 1h and so on... If the previous point is strictly applied here, each window of the timeline that has no task yet will be a window in the normal schedule, so it will be task A 1h, then task B 10min, then task A 5min, then task A 1h and so on... it would add task A even though task A is already too much present. In this case, it should be task A 1h, task B 15min, task A 1h and so on...

Modify the previous Python script to satisfy this new requirement. Keep the same example test, and add two more for the two scenarios described above. The three example tests must be visible in the same window (with a vertical scrollbar if necessary).