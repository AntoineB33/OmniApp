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

### Rule state evolution

* **Rule State Definition:** A rule state is the set of tasks and their associated priority percentages and minimum execution time at a given moment in time.
* **Rule State Evolution:** When there is one defined rule state, it stays the same forever. When multiple rule states are defined for specific moments in time, that means that between two consecutive rule states, the rule state transforms evenly from the first state to the second one.

### Dynamic Period

Includes the 20-second and 5-minute periods from Test 11, plus a new 15-minute period. The final 4 minutes of the 5-minute period, and the entirety of the 15-minute period, only allow Privileged tasks.
* After a $\ge 15$-minute stretch of *Privileged only*, the next 20-second period is delayed by **20 minutes**.
* After a $\ge 5$-minute stretch of *Privileged only*, the next 5-minute period is delayed by **1 hour**.
* After a $\ge 15$-minute stretch of *Privileged only*, the next 15-minute period is delayed by **2 hours**.



### Progressive Calculation:
The scheduler doesn't need to calculate the right schedule for the entire timeline, but if the definitive schedule is found for t < $t_1$, then 10 seconds later the definitive schedule must be found for t < $t_1$ + 10 minutes. As time passes, the scheduler returns one set of rule after the other to satisfy this pace. If exact schedules cannot be found in time, approved approximation strategies must be used. When the definitive schedule is found at a given time t, that means that up to t the definitive choice for the task panels has been made, as well as for when the 