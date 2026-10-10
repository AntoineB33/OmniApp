The timeline has a hidden level for the "alternative schedule", and other hidden levels called $tm_levels$. When a blue/orange outlined block is positioned at $t_r$, then what was there before is added at $t_r$ to the lowest $tm_level$ where nothing is at $t_r$. What is in the timeline is the result of the overlap of those $tm_levels$, applying them from top to bottom, ignoring those that are incompatible with the higher $tm_levels$.

Example 1: the user drags a "no phone unlocked" period to a "no computer unlocked" period, then drags task A with 0 resilience to "no screen". On the timeline, it would be task A with "no phone unlocked" and no "no computer unlocked" because it would imply a "no screen" period which is incompatible with task A.

Example 2: suppose the timeline is only task A, then the user manually adds a task B at 10h-12h. There are now two $tm_levels$: the bottom one with task A all along, and the top one with task B at 10h-12h. Next, the user selects task B at 11h-12h and task A at 12h-13h and drags it 4h further in the future. Now, the bottom $tm_level$ has an "Inactivity" period at 12h-13h, and the top $tm_level$ has task B at 15h-16h and task A at 16h-17h.

Additional rule: if the element $e$ that is getting dragged is going to make a hole in the bottom $tm_level$ , then if there is another element $e2$ present at both edges of the hole (when completely dragged away) that is incompatible with $e$, then the hole is getting filled with $e2$.

If it still makes a hole after dragging out an "Inactivity" period, then the hole is getting filled with an "Inactivity" period.




# Scheduler Input Requirements & Edge Cases

Switching to $now\ line$ mode 3 does not inherently change the rule state input. However, if the $now\ line$ intersects a task panel with 0 resilience to "no screen," the $now\ line$ effectively bisects this panel. As the $now\ line$ moves forward continuously, it constantly retracts the future portion of the task. Because time is continuous, it creates in theory an infinite sequence of infinitesimal input changes. Theoretically, to match the scheduler_requirements.md, the scheduler engine is triggered to restart from scratch at every infinitesimal time step, leaving exactly 0 execution time for each run. Therefore, the engine calculates nothing—which is functionally equivalent to the engine not being triggered at all. So in reality, the scheduler engine isn't triggered when there is in theory a sequence of infinitesimal changes in the scheduler engine's inputs. Consequently, the previous set of rules output is still applied, and the future schedule remains unchanged.

When a user drags a block over the $now\ line$, but the current mode forbids that block's presence at the exact present moment (e.g., holding a standard task panel while the $now\ line$ is in mode 3, or holding a "no screen" block while the $now\ line$ is in mode 1), the held block splits around the $now\ line$. Its temporal footprint becomes `[start, $now line$[ ∪ ]$now line$, end]`.

This splitting occurs because the held block must be preserved as much as possible without violating the state requirements defined in `docs\scheduler_requirements.md`. 

Because the held block surrounds the continuously moving $now\ line$, its boundaries change continuously. Just as with the mode 3 bisection, this mathematically triggers the engine at every infinitesimal moment. This effectively locks the timeline into the previous set of rules output until the block is either placed or moved away from the present moment.

Similarly, if the user drags a task panel with 0 resilience to "no screen" over a scheduled screen break in the future, the strict screen break acts as an exclusion operator. It dynamically creates a hole in the held task panel exactly where they overlap, updating continuously as the block is moved by the mouse.




All blocks can be dragged. When held, a block receives a blue outline. Multiple blocks can be dragged simultaneously using the right-click "edit..." option.

If a dragged period has required accompanying periods, those accompanying periods are dragged alongside it. For example: dragging a "no screen" period will simultaneously drag the associated ("no computer unlocked" or "not on a computer") and ("no phone unlocked" or "not on a phone") periods.

Conversely, if the dragged period originally induced the presence of a dependent period, that dependent period is removed from the original time interval. For example: dragging "no computer unlocked" away from its original placement will retract the "no screen" period it induced.

When a block gets dragged or more generally edited, it gets outlined in blue. If the block includes a blue/orange outlined block in its entirety or partially, then the included block loses its blue/orange outlines. Example: 10h-12h of "no screen" and 11h-13h of "no screen" outlined in blue, and the user edits the 10h-12h "no screen" period by dragging its past edge 1h further into the past, there is the 9h-12h block outlined in blue, and the 12h-13h block outlined in blue.

When an orange outlined block/chip becomes blue outlined, that means that the configuration that created this orange outlined block/chip gets an "exception" info (e.g., timer every day at 10h, with an exception for the occurrence of tomorrow that got dragged 1h later).