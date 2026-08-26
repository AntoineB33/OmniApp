# Scheduler System Specifications

### System Overview

The scheduler returns a set of rules that define the task schedule for a given timeline to satisfy constraints and two optimization criteria.

### Core Constraints & Task Allocation

#### Priority & Granularity
Each task has a **target priority percentage**. One optimization goal is to match these percentages across the smallest possible time window, avoiding unnecessarily large monolithic blocks (e.g., alternating two 50% tasks in 10-minute intervals rather than 1-hour intervals). The time windows must be as small as possible while still allowing for the task's minimum execution time to be respected (e.g., task A 30min 33%, task B 15min 33%, task C 15min 33% => task A 30min, task B 15min, task C 15min, task B 15min, task C 15min...).

#### Soft Minimum Execution Time
Each task has a defined minimum execution time. Another optimization goal is to reach the minimum execution time for any task appearing in the timeline. The ideal situation is that each task panels spans at least its minimum execution time without interruption.

#### Restrictive Period
Restrictive periods are objects with a start and end time, and a kind.
* Each task has a resilience value for each kind of restrictive period from 0 to 1. It is a multiplier for the task's priority percentage during that restrictive period. A resilience of 0 means the task is forbidden during that restrictive period, while a resilience of 1 means the task is unaffected.
* Multiple restrictive periods can appear at a given time t.

#### Compensation via Exponential Decay
Pre-placed tasks or restrictive periods inevitably create priority deficits for excluded tasks. The scheduler compensates for this by scheduling deprived tasks immediately before or after a blockage. To prevent massive, disruptive overcompensation, this mechanism uses an **exponential decay** model. The influence of the debt repayment decays over distance from the blockage.

### Rule state evolution

* **Rule State Definition:** A rule state is the set of tasks and their associated priority percentages and minimum execution time at a given moment in time.
* **Rule State Evolution:** When there is one defined rule state, it stays the same forever. When multiple rule states are defined for specific moments in time, that means that between two consecutive rule states, the rule state transforms evenly from the first state to the second one.


### $t_p$ and 3 Dynamic Restrictive Period

* **$t_p$:** Some of the rules returned by the scheduler are parameterized by a variable $t_p$ (the present) that can change value anytime during the test. $t_p$ >= $t_pstart$, where $t_pstart$ is a constant. Every t such that $t_pstart$ <= t <= $t_p$ are the previous values of the $t_p$ variable. This means that the $t_p$ moves continuously forward in time.
* **frozen past:** The schedule at t < $t_p$ never changes as $t_p$ increases.
* **3 Dynamic Restrictive Period:** They are named the 20s, 5min and 15min periods, with the respective corresponding durations, and have the kind "no task allowed". Some task are marked "s". The placement of those three dynamic restrictive periods are parametrized by $t_p$ to place them anywhere in the timeline that doesn't violate the following rules:
    * They can't overlap with another dynamic restrictive period.
    * After a 20s or 5min period, no 20s period in the next **20 minutes**.
    * After a $\ge 5$-minute stretch of a period that doesn't allow task "s" without any task, no 5min period in the next **1 hour**.
    * After a $\ge 15$-minute stretch of a period that doesn't allow task "s" without any task, no 20s restrictive period in the next **20 minutes**, and no 15min period in the next **2 hours**.
    * if the end of a 20s period touches a 5min or 15min period, they now starts at the start of the 20s period, and the 20s period is removed. Same thing for a 5min period touching a 15min period.
* **$t_p$ 2 modes:** There are two "$t_p$ modes". In the tests, the switch between modes is done with a button.
    * **Mode 1:** at $t_p$ there must be no period that doesn't allow task "s". This means that if it reaches one of those periods, the passing of the $t_p$ line creates task panels.
    * **Mode 2:** at $t_p$ there must be a a period that doesn't allow task "s".
* **consequence examples:** Here are direct consequences of the rules:
    * If a 20s period is placed at t, that $t_p$ is in mode 1 and is reaching t, it would continuously delay 20s period (while creating task panels in its passing).
    * When a 5min period time shifts backward by 20 seconds because the $t_p$ is in mode 1 reached a 20s period and dragged it to make its end touch the 5min period, the 20-second gap created at the end of the 5min period is filled with task panels given the set of rules parameterized by $t_p$ and returned by the scheduler.

### Starting timeline
The starting timeline can have pre-placed tasks and restrictive periods. They never change except for dynamic restrictive periods or the two modes of $t_p$.

### Alternative Schedules:
The returned set of rules must also give for every $t_p$ the task that must be scheduled if the task scheduled by the scheduler can't be scheduled now. When it happens, a program would simply read the rules, set this new task starting at $t_p$, and run the scheduler again with this new schedule.

### Progressive Calculation:
The scheduler doesn't need to calculate the right schedule for the entire timeline, but if the definitive schedule is found for any t < $t_1$, then 10 seconds later the definitive schedule must be found for any t < $t_1$ + 10 minutes. When the schedule is definitive for any t < $t_1$, it means that for all the next set of rules the scheduler will return until it is done, they will all indicate the same schedule rules for any t < $t_1$ (task panel scheduling parameterized by $t_p$ as well as the "alternative schedule"). As time passes, the scheduler returns one set of rule after the other to satisfy this pace. If exact schedules cannot be found in time, approved approximation strategies must be used.