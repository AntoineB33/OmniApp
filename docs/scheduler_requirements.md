# Scheduler System Specifications

### System Overview

The scheduler returns a set of rules defining the timeline task schedule to satisfy constraints and two optimization criteria. 

#### Rule Structure:
* **Event-Driven / Cursor-Based Evaluation:** Rules must be structured as sequential local branches and trigger boundaries (e.g., active `if...then...else...` clauses for the current interval, paired with an alarm/trigger for the next transition at $t$, at $now line$ mode switch, at history being rewritten by a program etc...).
* **No Global Lookups:** The runtime interpreter must never execute timeline-wide filtering, dynamic sorting, or interval-tree traversals. As the $now\ line$ moves forward, it simply evaluates the active local condition and advances a forward cursor to the next armed trigger point.

### Core Constraints & Task Allocation

#### Priority, Granularity and Compensation
* Each task has a **target priority percentage**. One optimization goal is to match these percentages across the smallest possible time window, avoiding unnecessarily large monolithic blocks (e.g., alternating two 50% tasks in 10-minute intervals rather than 1-hour intervals). The time windows must be as small as possible while still allowing for the task's minimum execution time to be respected (e.g., task A 30min 33%, task B 15min 33%, task C 15min 33% => task A 30min, task B 15min, task C 15min, task B 15min, task C 15min...).
* Pre-placed tasks or restrictive periods can create priority deficits for excluded tasks. To prevent massive, disruptive overcompensation and ensure the timeline rapidly returns to a normal schedule, the priority optimization uses an **exponential decay** model. 
* The influence of debt repayment decays over the distance from the blockage (both backward and forward in time). Because the influence decays exponentially with distance, a long blockage's compensation is bounded rather than proportional to its length. The goal is to avoid repaying debts indefinitely in favor of stabilizing the schedule.
* In an ideal situation where the timeline is cleared from pre-placed tasks or restrictive periods, this exponential decay is not needed.
* The timeline is infinite forward and backward, and the pre-placed tasks and restrictive periods can be in infinite patterns.

#### Soft Minimum Execution Time
Each task has a defined minimum execution time. Another optimization goal is to reach the minimum execution time for any task appearing in the timeline. The ideal situation is that each task panels spans at least its minimum execution time without interruption.

#### Restrictive Period
* The user can define restrictive periods, which are types of periods that can be placed in any time intervals on the timeline. The user can place them manually, or define rules to place them automatically. The user can also define that a period must be accompanied by others, for example whenever there is period A, then there is also period B.
* Each task has a resilience value for each kind of restrictive period from 0 to 1. It is a multiplier for the task's priority percentage during that restrictive period. A resilience of 0 means the task is forbidden during that restrictive period. For example, for the "no screen" period, "homework" has a resilience of 0.5, "video games" has a resilience of 0, and "read a book" has a resilience of 1. If "no screen" occupies the whole timeline and every other task has resilience 1 to "no screen", then "homework" would require twice as much presence.

### $now line$
* **$now line$:** Some of the rules returned by the scheduler are parameterized by two variables that can unpredictably change value anytime during the test: $now line$ (the present) and the $now line$ mode. Every t such that t <= $now line$ are the previous values of the $now line$ variable. This means that the $now line$ moves continuously forward in time.
* **$now line$ 3 modes:**
    * **Mode 1:** $now line$ must not be covered by the period "no screen".
    * **Mode 2 & 3:** $now line$ must be covered by the period "no screen".
        * **Mode 2:** $now line$ is in both "no computer unlocked" and "no phone unlocked" periods.
        * **Mode 3:** $now line$ is in either "not on a computer" or "not on a phone" periods. "not on a computer" can't be with "no computer unlocked", same with the phone.
    * **Mode switching:** The current $now line$ mode can be decided anytime by a program, but can't go in mode 1 during a "20s screen break" restrictive period. When $now line$ enters a "20s screen break" restrictive period in mode 1, it gets in mode 3, and when it leaves it, it gets in mode 1 unless the user wanted it to stay in mode 3, or unless it is in mode 2.
* **frozen past:** The schedule at t < $now line$ never changes as $now line$ increases, with only two exceptions:
    * When the user or a program wants to rewrite history.
    * When a "screen break" period needs to get removed to satisfy its restrictive period rules.

### Default restrictive periods
* **existing periods:** By default, there are already defined periods with automatic placement rules. For example, the "no screen" periods, the "sleep" period that is always accompanied by the "no screen" period, is placed at the same times every day, and allows no task (a task has a 0 resilience to it by default). The "before bed" period that is always placed on the hour before a "sleep" period. There are also the three "screen breaks" periods.
* **screen breaks:** There are the 20s, 5min and 15min screen break periods, always accompanied by the "no screen" period. The 20s screen break allows no task. The 5min break is accompanied by two periods: the first minute that allow no tasks and the 4 next minutes. The three screen breaks are placed everywhere in the timeline as earliest as possible where it doesn't violate the frozen past rule and the rules below.
    * A screen break period lasts as long as its name implies.
    * The $now line$ must be in mode 1 or 3 before entering the 20s break.
    * After the end of a 20s break, no 20s break in the next **20 minutes**.
    * After a $\ge 5$-minute of "no screen", no 5min break in the next **1 hour**.
    * After a $\ge 15$-minute of "no screen", no 15min break in the next **2 hours**.
    * Where the five rules above allow a continuous chain of breaks, then the interval of the whole chain only contains one screen break, which is the longest screen break of the chain brought to the start of the interval.
    * In a "no screen" period, if $t_b$ is in a screen break, where $t_b$ is the start of a screen break, and that $now line$ < $t_b$, then this screen break must now start at max($now line$, $t_s$), where $t_s$ is the start of the continuous "no screen" period, even if it contradicts with the five first screen break rules.
    * A 20s break can only be crossed by the $now line$ in mode 3.

### Example behaviors
Here are some example situations resulting from the rules described above.
* If the $now line$ reaches a 20s break in mode 2, this 20s break becomes ]$now line$; $now line$ + 20s], because the $now line$ must be in mode 1 or 3 before entering the 20s break. 
* If the $now line$ reaches a 5min break in mode 1, this 5min break becomes ]$now line$; $now line$ + 5min].
* When the $now line$ is in mode 1 and has dragged a 5min break until the break's end edge touches a 15min break, the 15min break teleports 5 minutes backward, starting right after $now line$, the 5min break is removed, and the 5-minute gap created at the end of the 15min break is filled with task panels given the set of rules output parameterized by $now line$ and $now line$ mode and returned by the scheduler.
* If $now line$ is in mode 2 and reaches the end of a 15min break, the gap between the end of the 15min break and $now line$ is covered by a period "no screen", filled with tasks that have a non-zero resilience to the kind "no screen", or no task if none have such resilience.
* When the $now line$ is in mode 1 and reaches a "no screen" period that extends to [t1;t2], I want the "no screen" period to become ]$now line$;t2] when $now line$ is in [t1;t2[. When $now line$ >= t2, then this "no screen" period is removed.
* If the $now line$ reaches a "no screen" period in mode 1, if it is accompanied by a 20s break then it gets in mode 3, otherwise the "no screen" period now starts at $now line$ not included and gets retracted as time goes by.
* If the $now line$ reaches a 20s break in mode 2, then the 20s break is dragged by the $now line$. It starts at $now line$ not included.

### Alternative Schedules:
The returned set of rules output must also give for every $now line$ the task that must be scheduled if the task scheduled by the scheduler is refused by the user. When it happens, a program would simply read the rules, set this alternative task starting at [$now line$, $now line$+d], with d defined beforehand (like 10 minutes), and run the scheduler again with this new schedule.

### Rule state input evolution
* **Rule State Input Definition:** A rule state input is the set of tasks and their associated priority percentages, minimum execution time and resilience values for every periods at a given moment in time.
* **Rule State Input Evolution:** The rule state input can change continuously through transition, or via discrete switches on the timeline. For example, if there is a switch from RSI A to RSI B at $t_s$, then the perfect schedule for the period with t < $t_s$ is the part where t < $t_s$ in the perfect schedule if RSI A is the only RSI in the whole timeline. Backward compensation is still applied, .The perfect schedule for the period with t > $t_s$ is the part where t > $t_s$ in the perfect schedule if RSI B is the only RSI in the whole timeline, with the currently best schedule found for t < $t_s$ as a frozen past. Since RSI change at $t_s$ represents a fundamental shift in optimizations and constraints, backward compensation from RSI B must not leak into RSI A. Instead, hypothetical backward compensation from RSI A at t > $t_s$ must leak into t < $t_s$. It also means that a new rule state should dynamically "catch up" or compensate for how the past deviated from its newly established goals. If the transition was continuous, then the same logic is taken to infinitesimals. 
* **When not the same task ids:** If a task id is missing in the other rule state input, then in the continuous evolution of the rule state input the priority starts or ends at 0, but the minimum execution time and the resilience value don't change.


### Progressive Calculation:
The scheduler doesn't need to calculate the right schedule for the entire timeline, but if the definitive schedule is found for any t < $t_1$, then 10 seconds later the definitive schedule must be found for any t < $t_1$ + 10 minutes. When the schedule is definitive for any t < $t_1$, it means that for all the next set of rules output the scheduler will return until it is done, they will all indicate the same schedule rules for any t < $t_1$ (task panel scheduling parameterized by $now line$ and $now line$ mode as well as the "alternative schedule"). As time passes, the scheduler returns one set of rule output after the other to satisfy this pace. If exact schedules cannot be found in time, approved approximation strategies must be used.
The scheduler can have a time $t goal$ such as when definitive schedule is found for any t < $t goal$ the scheduler can stop. It will also stop if the set of rules output became too heavy, or if it calculated for too long.
* **first 10s:** When there is a change that will make the scheduler engine run from scratch, it must firstly check if in the next 10 seconds there are gaps with no task and if there are tasks that can be scheduled in those gaps. If so, then almost instantly, a new set of rules is returned and the first 10 seconds are definitive.
* **direct consequence:** If the device bearing the running process is put to sleep, then when the program wakes up, the $now line$ does a fast move forward (in epsilon time) in mode 2 to the current date. If the current date is beyond the definitive schedule, then it is similar to a case where no CPU were available during this period and the current set of rules output, parameterized by $now line$ and $now line$ mode, is used to define the schedule as the $now line$ does its fast move, while no better set of rules output was found.

### Strict Requirements
All of the above requirements must be strictly adhered to, with only two acceptable exceptions:

1. Get as close as possible to the optimal score for both optimization criteria, without actually reaching it, in order to save time or computing power, or if necessary to maintain the required pace. However, if the optimal score is achievable within the given time and with acceptable computing power, it must be achieved.

2. Other limits may be imposed to conserve memory, computing power, or CPU usage over time, as appropriate. For example, a limit may be imposed on the memory for the frozen timeline history.

Even if the optimization score is not perfect for all infinite paths, the scheduler game search for the best set of rules output with its limited time and CPU resources and came out with a valid result, which is the expected behavior.

### Use of the set of rules output

* A re-run of the scheduler engine would make it take a screenshot of the schedule without what is deduced from the current set of rules but not saved in history, and find a good schedule from the current rule state inputs. The calendar stays with the previous set of rules, until the scheduler finds one. Then, it removes everything deduced from the previous set of rules but not saved in history, and apply the set of rules input. It will do it again when a better set of rules input is found.





