# Scheduler Input Requirements & Edge Cases

Switching to $now\ line$ mode 3 does not inherently change the rule state input. However, if the $now\ line$ intersects a task panel with 0 resilience to "no screen," the $now\ line$ effectively bisects this panel. As the $now\ line$ moves forward continuously, it constantly retracts the future portion of the task. Because time is continuous, this creates an infinite sequence of infinitesimal input changes. Mathematically, the scheduler engine is triggered to restart from scratch at every infinitesimal time step, leaving exactly 0 execution time for each run. Therefore, the engine calculates nothing—which is functionally equivalent to the engine not being triggered at all. Consequently, the previous set of rules output is still applied, and the future schedule remains unchanged.

When a user drags a block over the $now\ line$, but the current mode forbids that block's presence at the exact present moment (e.g., holding a standard task panel while the $now\ line$ is in mode 3, or holding a "no screen" block while the $now\ line$ is in mode 1), the held block splits around the $now\ line$. Its temporal footprint becomes `[start, $now line$[ ∪ ]$now line$, end]`.

This splitting occurs because the held block must be preserved as much as possible without violating the state requirements defined in `docs\scheduler_requirements.md`. 

Because the held block surrounds the continuously moving $now\ line$, its boundaries change continuously. Just as with the mode 3 bisection, this mathematically triggers the engine at every infinitesimal moment. With zero calculation time available per run, no computation occurs. This effectively locks the timeline into the previous set of rules output until the block is either placed or moved away from the present moment.

Similarly, if the user drags a task panel with 0 resilience to "no screen" over a scheduled screen break in the future, the strict screen break acts as an exclusion operator. It dynamically creates a hole in the held task panel exactly where they overlap, updating continuously as the block is moved by the mouse.
