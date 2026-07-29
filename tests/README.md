There are tasks. Each task has a minimum time and a priority percentage. The scheduler must place panels of tasks in a timeline from t_now to +infinity. The starting timeline has already placed tasks. It also has periods where only some tasks are allowed. When the scheduler places a task, it can't then place another task until this one reaches its minimum time (can only be interrupted by already placed tasks or periods).

The scheduler does t_i = t_now then enters a loop. At each iteration:
- sub_scheduler calculates the schedule for the window from t_i to +infinity. The result is only the scheduling of the first scheduled task up to when this task reaches its minimum time.
- The result is added to the timeline.
- t_i becomes the end of this added schedule.
- if t_i - t_now >= 168h, then stop the loop.

With a window from t1 to t2, we calculate the sum for each task of the absolute difference between its presence percentage and its priority percentage. We have an infinite continuous 2d graph of this sum over t1 and t2, and sub_scheduler must efficiently find a schedule that gives the 2d graph f where $$\liminf_{T \to \infty} \int_{t_i}^{T} \int_{t_i}^{T} (f(t1, t2) - g(t1, t2)) \,dt2 \,dt1 <= 0$$ with g every other possible graphs (or as much as possible if it is not possible).

If sub_scheduler ends up with several equally scored schedules, it looks at the presence time of every tasks in t < t_i and used it as a tie breaker
Example:
choice 1 : task A 1h, task B 1h and repeat...
choice 2 : task B 1h, task A 1h, and repeat...
if A is more present than B in the past, then take choice 2.