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

1. **Periods:** Time windows that each forbid a set of tasks. Periods **may overlap**, and what an instant forbids is the **sum** of the lists of every period covering it — a task one of them forbids is forbidden there whatever the others say. The timeline is **not** necessarily covered: an instant no period covers forbids nothing, so everybody may run. An instant that forbids *everybody* deprives nobody relative to anybody, so it creates no compensation of its own and does not join the exclusions on either side of it into one.
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
- nothing  $t_p$ - $t_1$
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



# new requirement
Add test 12 :
Task A 50% 45 minutes
20 other tasks that share the remaining 50%, all 45 minutes
Half of the tasks are in the set “privileges” 
De 0h à 8h: no task allowed
De 23h à 8h: only tasks from “privileges”
There are the same 20s and 5min periods from test 11, in addition to the 15min period. The 4 last minutes of the 5min period and the whole 15 minutes of the 15min period only allow tasks that are in the set “privileges”.
The following rules must always be satisfied by the timeline:
 - after a ≥15-minutes stretch of only privileged allowed, or after one of the three periods aforementioned, the next 20s period is **20 minutes** later;
 - after a ≥5-minute stretch of only privileged allowed, the next 5min period is **1 hour** later;
 - after a ≥15-minute stretch of only privileged allowed, the next 15min period is **2 hours** later.
The timeline of test 12 spans over 3 days. The first 24 hours of the timeline have the three periods aforementioned. At t<0, is it a period where no task is allowed. $t_p$ starts at 24h and moves to the right.
It means that  when $t_p$ reaches the end, the three aforementioned periods are only found in the first 24 hours, because the $t_p$ line swiped them all like in test 11.
Like for all tests, finding the right schedule for the next 10 minutes must not take more than 10 seconds. That means that if the scheduling is taking time for the whole timeline of the test, the user will see the schedule change each time the scheduler finds a better set of rules to satisfy the following requirement : if the right schedule is found for t < $t_1$, then 10 seconds later the right schedule must be found for t < $t_1$ + 10 minutes.

Direct consequence: If the scheduling takes some time, when the user runs tests_displayer and immediately scrolls down to test 12 without clicking on play, the user can see the schedule still changing, while the definitive schedule grows from t=0.

If the exact schedule can’t be found at this pace, then there must be using approximations, where t and $t_1$ are positions in the displayed timeline. If so, give me the list of those approximations, so that I can validate them. Update the code.

## How test 12 answers

**The dragged break goes through the scheduler.** A break the line has reached and nothing has served is owed, so — as in test 11 — it sits at the line and slides with it, and it is handed to `Scheduler.plan` as an ordinary period. So the deficit it creates is compensated by the same exponential-decay field as any other blockage, and no task is interrupted inside its minimum by anything but idling: the atomic block is the scheduler's own, not something a display transform could break. `ProgressiveWindow.regime_at` then fits that plan into rules **affine in $t_p$**, exactly as test 10 does, so following the line is arithmetic and not scheduling. They are certified the way tests 10–11 certify theirs: at positions they were never fitted on, against the scheduler itself (worst deviation over the sweep: ~10 µs, epsilon 0.5 s).

**A break exists once.** Reaching one removes it from the grid; the timeline the past is read off (`ProgressiveWindow.past`, the chain over the environment the line has swept) holds no break after the first day at all, so nothing is ever both a hole at its due position and a period at the line. Dragging one past its grid slot cannot make the user take two.

**Absorption adds, it does not replace.** Every owed break is anchored at the line and their exclusion lists **sum**, per the overlapping-periods rule. A 15-minute privileged-only break that has taken in a 5-minute one still carries that one's opening minute of silence and the 20 s window's own — measured over the sweep the shape at the line goes `20s: nothing` → `20s + 1min: nothing, 4min: privileged only` → `… + 15min: privileged only`. "The longest governs" is the shape that comes out of the sum, not a choice between them.

**Each kind's timer starts at its own threshold.** A stretch of privileged-only time discharges a break of a given kind when it lasts at least what that kind asks for, and `test12_discharges(kind)` returns exactly the instants the grid measures that kind's next occurrence from — so the drag and the recurrence rules can never disagree about when a timer started, and no kind is late.

### The three approximations left (to validate)

They are all of the same kind — the schedule is exact near the line and provisional far from it, which is the shape the pace requirement above describes.

1. **Past `local_end`, the display draws the standing chain.** The rules at the line are sound as far as the plan's own prefix reaches, capped by the 6-hour lookahead it was shown the environment over — in practice one to six hours ahead of the line, where the requirement asks for ten minutes. Beyond that the display continues with the chain derived for the environment standing (no break reached), which was not seeded with the local rules' output: the far end is therefore the provisional part that "goes on changing", not a wrong answer claimed as final.
2. **The rules at a position are derived when the line STOPS there.** A regime costs a second or two (worst measured 3.6 s, against the 10 s budget) and is then kept, so a position visited once is free forever after. Playing the sweep crosses a regime every frame, far faster than they can be derived, so while the line is moving none are: the display carries the standing chain on as the provisional answer and the line runs smoothly, and the position the sweep is paused at (or clicked on, or dragged to) becomes exact within a frame or two. This is the "cache at anchors, recompute the local window" shape: a derivation is bounded by the bracket around $t_p$ — and by a 3-second budget past which the rules are answered for that one position exactly rather than for a range — never by the three days.
3. **The rounding epsilon.** A slot that has shrunk below it is dropped from the rules (the epsilon this document already asks for), so the drawn local part can end a sliver short of the plan's own reach; the chain takes over exactly there rather than leaving a sliver of nothing.