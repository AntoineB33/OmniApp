# Scheduler System Specifications

### System Overview

The scheduler returns a set of rules that define the task schedule for a given timeline to satisfy constraints and two optimization criteria.

### Core Constraints & Task Allocation

#### Priority, Granularity and Compensation
* Each task has a **target priority percentage**. One optimization goal is to match these percentages across the smallest possible time window, avoiding unnecessarily large monolithic blocks (e.g., alternating two 50% tasks in 10-minute intervals rather than 1-hour intervals). The time windows must be as small as possible while still allowing for the task's minimum execution time to be respected (e.g., task A 30min 33%, task B 15min 33%, task C 15min 33% => task A 30min, task B 15min, task C 15min, task B 15min, task C 15min...).
* Pre-placed tasks or restrictive periods inevitably create priority deficits for excluded tasks. To prevent massive, disruptive overcompensation, the priority optimization uses an **exponential decay** model. The influence of the debt repayment decays over distance from the blockage.

#### Soft Minimum Execution Time
Each task has a defined minimum execution time. Another optimization goal is to reach the minimum execution time for any task appearing in the timeline. The ideal situation is that each task panels spans at least its minimum execution time without interruption.

#### Restrictive Period
Restrictive periods are objects with a start and end time, and a kind.
* Each task has a resilience value for each kind of restrictive period from 0 to 1. It is a multiplier for the task's priority percentage during that restrictive period. A resilience of 0 means the task is forbidden during that restrictive period, while a resilience of 1 means the task is unaffected.
* Multiple restrictive periods can appear at a given time t.

### Rule state evolution
* **Rule State Definition:** A rule state is the set of tasks and their associated priority percentages, minimum execution time and resilience values for every periods at a given moment in time.
* **Rule State Evolution:** When there is one defined rule state, it stays the same forever. When multiple rule states are defined for specific moments in time, that means that between two consecutive rule states, the rule state transforms evenly from the first state to the second one.


### $now line$ and 3 Dynamic Restrictive Period

* **$now line$:** Some of the rules returned by the scheduler are parameterized by two variables that can unpredictably change value anytime during the test: $now line$ (the present) and the $now line$ mode. Every t such that t <= $now line$ are the previous values of the $now line$ variable. This means that the $now line$ moves continuously forward in time.
* **frozen past:** The schedule at t < $now line$ never changes as $now line$ increases.
* **3 Dynamic Restrictive Period:** They are named the 20s, 5min and 15min periods, with the respective corresponding durations, and have the kind "no task allowed". The placement of those three dynamic restrictive periods are parametrized by $now line$ to place them anywhere in the timeline that doesn't violate the following rules:
    * After any dynamic restrictive period, no 20s period in the next **20 minutes**.
    * After any $\ge 5$-minute stretch covered by the period "no on-screen task" without any task (whether caused by dynamic periods, pre-placed restrictive periods, or a combination), no 5min period in the next 1 hour.
    * After a $\ge 15$-minute stretch covered by the period "no on-screen task" without any task, no 20s restrictive period in the next **20 minutes**, and no 15min period in the next **2 hours**.
    * If the rules above make dynamic restrictive periods overlapping, the whole chain is replaced by the longest period of the chain starting at the earliest point, and the others are removed. The rules above prevent any situation where two dynamic restrictive periods of the same length are overlapping.
* **$now line$ 2 modes:** There are two "$now line$ modes". In the tests, the switch between modes is done with a button.
    * **Mode 1:** $now line$ must not be covered by the period "no on-screen task". This means that if it reaches one of those periods, the passing of the $now line$ line creates task panels not covered by the period.
    * **Mode 2:** $now line$ must be covered by the period "no on-screen task".
* **consequence examples:** Here are direct consequences of the rules:
    * If a 20s period is placed at t, that $now line$ is in mode 1 and is reaching t, it would continuously delay 20s period (while creating task panels in its passing). The 20s period is the half-open interval $(t_p, t_p + 20\text{s}]$.
    * When the $now line$ is in mode 1 reached a 20s period and dragged it to make its end touch a 5min period, the 5min period teleports 20s backward (without including $now line$) which absorbs the 20s period, and the 20-second gap created at the end of the 5min period is filled with task panels given the set of rules parameterized by $now line$ and $now line$ mode and returned by the scheduler.
    * If $now line$ is in mode 2 and reaches the end of a 15min period, the gap between the end of the 15min period and $now line$ is covered by a period "no on-screen task", filled with tasks that have a non-zero resilience to the kind "no on-screen task", or no task if none have such resilience.

### Starting timeline
The starting timeline can have pre-placed tasks and restrictive periods. They never change except for dynamic restrictive periods or the two modes of $now line$.

### Alternative Schedules:
The returned set of rules must also give for every $now line$ the task that must be scheduled if the task scheduled by the scheduler can't be scheduled now. When it happens, a program would simply read the rules, set this new task starting at $now line$, and run the scheduler again with this new schedule (because this alternative schedule doesn't say what happens next if this alternative task is chosen).

### No idling:
Anywhere that is not covered by restrictive periods which would prevent any task from being scheduled, the scheduler must schedule a task, for any $now line$ and $now line$ mode.

### Progressive Calculation:
The scheduler doesn't need to calculate the right schedule for the entire timeline, but if the definitive schedule is found for any t < $t_1$, then 10 seconds later the definitive schedule must be found for any t < $t_1$ + 10 minutes. When the schedule is definitive for any t < $t_1$, it means that for all the next set of rules the scheduler will return until it is done, they will all indicate the same schedule rules for any t < $t_1$ (task panel scheduling parameterized by $now line$ and $now line$ mode as well as the "alternative schedule"). As time passes, the scheduler returns one set of rule after the other to satisfy this pace. If exact schedules cannot be found in time, approved approximation strategies must be used.
* **direct consequence:** If the device bearing the running process is put to sleep, then when the program wakes up, the $now line$ does a fast move forward (in epsilon time) in mode 2 to the current date. If the current date is beyond the definitive schedule, then it is similar to a case where no CPU were available during this period and the current set of rules, parameterized by $now line$ and $now line$ mode, is used to define the schedule as the $now line$ does its fast move, while no better set of rules was found.

# Strict requirements
All the requirements above must be strictly satisfied. The only acceptable degradation allowed in order to save time or computer power is getting as close as possible to the best score for the two optimization criteria, without reaching it. But if the best possible score is reachable within the required time and acceptable computer power, it must be reached.