### System Overview

The scheduler is designed to allocate tasks along an infinite timeline, starting from $t_{now}$. Its primary goal is to distribute tasks according to their assigned priorities while maintaining the finest possible granularity and adapting to pre-existing schedule constraints.

### Task Allocation & Granularity

Each task is defined by a **target priority percentage** and a **minimum execution time**.

* **Atomic Blocks:** Once a task is scheduled, no other task can be placed until the current task's minimum execution time is met. e.g., if task B is scheduled at t=0 and a period p that only allows task A is at t=1, and task B has a minimum time of 2, then the whole period p is scheduled with nothing.
* **Fine-Grained Scheduling:** The scheduler must match the target priority percentages across the smallest possible time window, avoiding unnecessarily large monolithic blocks.
* *Example:* For Task A (50%, 10 min) and Task B (50%, 10 min), the scheduler should alternate `[Task A: 10m] -> [Task B: 10m] -> repeat`, rather than creating coarse blocks like `[Task A: 1h] -> [Task B: 1h]`.



### Output Architecture ($O(1)$ Generation)

Because the timeline is infinite, the scheduler does not generate an endless sequence. Instead, it outputs a **finite list of rules**—typically a specific sequence of initial placements followed by a repeating cycle. This allows any UI or downstream system to calculate and display the schedule from $t=0$ to $t=x$ with $O(1)$ complexity.

### Environmental Constraints & Compensation

The timeline is not a blank slate. It is influenced by two main factors:

1. **Periods:** Time windows that dictate exactly which sets of tasks are allowed to run.
2. **Pre-placed Tasks:** Tasks that have already been locked into the timeline.

**Exponential Decay Compensation:**
Pre-placed tasks and restrictive periods inevitably create priority deficits or surpluses. The scheduler naturally compensates for this by heavily scheduling a deprived task immediately before or after a blockage. However, this compensation utilizes an **exponential decay** model with a rounding epsilon. For instance, a 1-hour pre-placed block of Task A will moderately boost Task B around it. If Task A is locked for 100 hours, Task B's presence around it will be higher, but **exponential decay** higher. This exact same decay mechanism applies to restrictive periods: if a period forbids a task from running for a long duration, the compensation around that period is similarly bounded. The influence of the blockage decays over distance, preventing extreme, disruptive overcompensation at the boundaries.

Test 10 (task A 50% 10min and task B 50% 10min) must have a 20 second period that allows only task task A, and that continuously moves to the right. Of course the scheduling can change violently, but everything at t < $t_p$ stays frozen, such as $t_p$ is the starting time of the 20s period. The scheduler must add in its set of rules the rules for the dynamic schedule. This result in algebraic rules parameterized by $t_p$.
Note: As explained in the Atomic Blocks paragraph, task B can be interrupted during its 10 first minutes by idling periods.

idea of result: 
When $t_p$ < 9min40, then:
Cycle:
- task A 10min
- task B 10min
- repeat
When $t_p$ ≤ $t_1$, such as $t_1$ is a calculated time with $t_1$ > 9min40, then:
Prefix:
- task A $t_p$ + 20s
- task B 10min + debt repayment (function of $t_p$ of complexity O(1))
[more tasks depending on debt repayment...]
Cycle:
- task A 10min
- task B 10min [or reverse, depending on debt repayment...]
- repeat
When $t_p$ is between $t_1$ and $t_1$ + 20s, then:
Prefix:
- task A $t_1$ + 20s
- nothing  $t_p$ - $t_1$
- task B 10min + debt repayment (function of $t_p$ of complexity O(1))
[more tasks depending on debt repayment...]
Cycle:
- task A 10min
- task B 10min [or reverse, depending on debt repayment...]
- repeat
When $t_p$ is between $t_1$ and $t_2$, then:
Prefix:
- task A $t_1$
- task B $t_p$ - $t_1$ - 20s
- nothing
- task B ...
...

I wrote it here, but actually everything at t < $t_p$ stays frozen so there is no need to say it in the prefix rules.

The displayed schedule must update in real-time following the set of rules (not by triggering new calculations). If the 20s period reaches the end of the displayed timeline make it start anew. The timeline is independent of the user's screen size.

If the computation would take too much time, the area to find the schedule of shortens, which can trigger repeating computation to cover all the timeline that we want. Finding the right schedule for the next 10 minutes must not take more than 10 seconds.

The movement of the period in test 10 is done by the displayer in response to the test configuration. The scheduler logic is the same used for every test.

test 11 must be test 10 with already placed tasks and lots of periods and tasks definitions. The 20s period allows nothing, and there is a 1 minute period that allow nothing followed right after by a 4 minute period that only allow task A. The 1-minute and 4-minute periods form a 5-minute stretch that start at $t_p$ as soon as the 20s period reaches it (which makes the 5-minute stretch teleport 20 seconds to the left and the 20s period disappears forever).

The user can simply click any where on a schedule block and $t_p$ becomes this point. If it was playing, it sets to pause. If the user clicks then drags, $t_p$ follows the mouse.
It is set to pause by default.

Reminders:
- Everything at t < $t_p$ stays frozen.
- As the idea of result shows, the whole timeline won't be filled just with task A and idling. The parameterization of $t_p$ prevents that fragmentation entirely
- The decay requirement doesn't contradict with the minimum time requirement, which must always be satisfied.