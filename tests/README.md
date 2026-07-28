The scheduler must schedule tasks on a timeline, so that the presence of a task over a window is as close as possible to the priority percentage, when looking at every window (every size and every position). There must be some kind of difference average over window size and position that must be as small as possible.

On an infinite timeline, some periods are associated to a task, some to several with a defined percentage for each task. Some periods are inactivity periods, some periods allow only screen tasks, some only no screen tasks, some allow both.
The scheduler must add tasks to the future part of the timeline. Each tasks has a minimum time that must be respected by the scheduler (but not necessarily respected by the already placed tasks).

Example with a clean starting timeline: task A 50% 7min task B 50% 1h
result: 1h of task A, 1h of task B, and so on...
why? because compared to another result like 56min of task A, 1h of task B, 63min of task A, 1h of task B, and so on..., the average score over window position and size is even lower.



# Optimizer Comparison

| Feature | test-lazy.py (Greedy) | Knapsack Look-Ahead (DP) | test.py (MILP Optimizer) |
| --- | --- | --- | --- |
| **Speed** | Instantaneous | Very fast (milliseconds) | Slow (takes seconds to minutes) |
| **Timeline** | Can be truly infinite | Can be truly infinite | Must be strictly bounded (capped duration) |
| **Result Quality** | "Good enough" / Approximate | Locally optimal (perfectly packed within each block) | Mathematically optimal (minimizes exact global error) |
| **Memory usage** | Very low | Low (only stores state for the current block) | High (builds massive equation matrices) |