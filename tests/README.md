There are tasks. Each task has a minimum time (they can't be scheduled with a continuous time that is shorter) and a priority percentage. The scheduler must place panels of tasks in a timeline from t_current to +infinity. That means the result of the scheduler must be a function that takes t and returns the task (the result can't be directly the whole schedule because it is infinite).
The starting timeline has already placed tasks. It also has periods where only some tasks are allowed.
With a window from t1 to t2, we calculate the sum for each task of the absolute difference between its presence percentage and its priority percentage. We have an infinite continuous 2d graph of this sum over t1 and t2, and the scheduler must efficiently find a function that gives the 2d graph f where $$\liminf_{T \to \infty} \int_{0}^{T} \int_{0}^{T} (f(t1, t2) - g(t1, t2)) \,dt2 \,dt1 <= 0$$ with g every other possible graphs (or as much as possible if such function doesn't exist).
Would be the most efficient algorithm to find the best return function?


# Optimizer Comparison

| Feature | test-lazy.py (Greedy) | Knapsack Look-Ahead (DP) | test.py (MILP Optimizer) |
| --- | --- | --- | --- |
| **Speed** | Instantaneous | Very fast (milliseconds) | Slow (takes seconds to minutes) |
| **Timeline** | Can be truly infinite | Can be truly infinite | Must be strictly bounded (capped duration) |
| **Result Quality** | "Good enough" / Approximate | Locally optimal (perfectly packed within each block) | Mathematically optimal (minimizes exact global error) |
| **Memory usage** | Very low | Low (only stores state for the current block) | High (builds massive equation matrices) |


new requirements:
When several choices are possible, the tasks already placed in the past part of the timeline are taken into account to choose the best option.
Example:
choice 1 : task A 1h, task B 1h, task A 1h, task C 1h and so on...
choice 2 : task A 1h, task C 1h, task A 1h, task B 1h and so on...
if B is more present than C in the past, then take choice 2.

$$\liminf_{T \to \infty} \int_{0}^{T} (f(x) - g(x)) dx < 0$$
