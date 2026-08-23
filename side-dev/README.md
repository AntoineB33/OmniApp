# Scheduler System Specifications

### System Overview

The scheduler allocates tasks along an infinite timeline, starting from $t_{now}$. Its primary goal is to distribute tasks according to their assigned **priority percentages** while adapting dynamically to environmental constraints, user interactions, and time-based rules. 

To handle an infinite timeline, the scheduler outputs a **finite list of parameterized rules** (e.g., prefixes and repeating cycles) rather than an endless sequence. This allows downstream systems to compute the schedule for any timeframe with $O(1)$ complexity.

### Core Constraints & Task Allocation

#### 1. Priority & Granularity
Each task has a **target priority percentage**. The scheduler must match these percentages across the smallest possible time window, avoiding unnecessarily large monolithic blocks (e.g., alternating two 50% tasks in 10-minute intervals rather than 1-hour intervals).

#### 2. Soft Minimum Execution Time
Each task has a defined minimum execution time, which acts as a heavily weighted tendency rather than a strict atomic lock. 
* **Completion Pull:** When a task begins, there is a very strong algorithmic tendency to continue scheduling it until its minimum time is reached. This tendency weakens as the task approaches its minimum time.
* **Interruption Decay:** If a task is cut short before reaching its minimum time (e.g., due to a restrictive period), the tendency to resume this specific task decays progressively as $t$ increases.

#### 3. Restrictive Periods
Periods are predefined time windows that forbid specific sets of tasks.
* Periods can overlap. The restrictions of overlapping periods are additive (if Period A forbids Task 1, and Period B forbids Task 2, their overlap forbids both).
* Uncovered instants forbid nothing.
* Instants that forbid *all* tasks do not create priority deficits, as all tasks are deprived equally.

#### 4. Compensation via Exponential Decay
Pre-placed tasks or restrictive periods inevitably create priority deficits for excluded tasks. The scheduler compensates for this by scheduling deprived tasks immediately before or after a blockage. To prevent massive, disruptive overcompensation, this mechanism uses an **exponential decay** model. The influence of the debt repayment decays over distance from the blockage.

### Interaction & Performance Requirements

* **The Playhead ($t_p$):** $t_p$ represents the current evaluation cursor. **Everything at $t < t_p$ is frozen and immutable.**
* **User Control:** The user can click anywhere on the schedule to move $t_p$ to that point. Dragging the cursor updates $t_p$ continuously. The system defaults to a paused state.
* **Performance Benchmark:** The scheduler must be fast. Calculating the definitive schedule for the next 10 minutes must take **no more than 10 seconds**.
* **Progressive Calculation:** If processing the entire timeline is too demanding, the scheduler must calculate short segments iteratively (e.g., if the schedule is solved for $t < t_1$, 10 seconds later it must be solved for $t < t_1 + \text{10 minutes}$). If exact schedules cannot be found in time, approved approximation strategies must be used.

---

### Test Cases

The following tests define the expected behavior of the scheduler under various constraints. In the testing display, the "resulting share" is calculated as the percentage of a task's presence across the drawn timeline, excluding periods where no tasks are allowed.

#### Test 10: Dynamic Rules & $t_p$ Parameterization
* **Tasks:** Task A (50%, 10 min minimum), Task B (50%, 10 min minimum).
* **Environment:** A 20-second period that only allows Task A moves continuously to the right. 
* **Requirement:** The schedule must change dynamically as $t_p$ moves, outputting algebraic rules parameterized by $t_p$. The timeline before $t_p$ remains frozen. Task B may be interrupted during its first 10 minutes by idling periods.

#### Test 11: Period Transitions & Pre-placed Tasks
* **Tasks:** Inherited from Test 10, plus randomly pre-placed tasks.
* **Environment:** 
  * A moving 20-second period that allows *nothing*.
  * A static 5-minute stretch (1 minute of *nothing*, followed by 4 minutes of *Task A only*).
* **Requirement:** As soon as the moving 20-second period collides with the start of the 5-minute stretch at $t_p$, the 20-second period disappears permanently, and the 5-minute stretch shifts 20 seconds to the left.

#### Test 12: Conditional Period Generation over 72 Hours
* **Tasks:** Task A (50%, 45 min minimum) + 20 other tasks sharing the remaining 50% (all 45 min minimum). Half of the 20 tasks belong to a "Privileged" set.
* **Environment Constraints (Daily):**
  * 00:00 to 08:00: No tasks allowed.
  * 23:00 to 08:00: Only "Privileged" tasks allowed.
* **Dynamic Period Triggers:** 
  Includes the 20-second and 5-minute periods from Test 11, plus a new 15-minute period. The final 4 minutes of the 5-minute period, and the entirety of the 15-minute period, only allow Privileged tasks.
  * After a $\ge 15$-minute stretch of *Privileged only*, the next 20-second period is delayed by **20 minutes**.
  * After a $\ge 5$-minute stretch of *Privileged only*, the next 5-minute period is delayed by **1 hour**.
  * After a $\ge 15$-minute stretch of *Privileged only*, the next 15-minute period is delayed by **2 hours**.
* **Execution:** Spans 3 days. $t_p$ starts at $0$. Once the system finds the schedule up to 24 hours, $t_p$ teleports to 24 hours and moves right. The dynamic periods will only appear in the first 24 hours due to the $t_p$ sweep. 

#### Test 13: Sliding Priorities
* **Configuration:** Identical to Test 12.
* **Requirement:** Priority percentages slide continuously from one state at 24h to a new state at 48h. For $t < \text{24h}$, priorities match the 24h state. The scheduler must satisfy the exact priorities active at the precise moment of $t_p$.

#### Test 14: Extended Timeline Setup
* **Configuration:** Task A (50%, 45 min minimum) + 20 other tasks sharing the remaining 50% (all 45 min minimum).
* **Environment:** 8-day timeline. No tasks are allowed between 23:00 and 08:00 daily.