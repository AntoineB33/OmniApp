# side-dev — the scheduler model

Side developments: ideas of logic worked out here in Python (`uv run test.py`) before being ported
to the app. `test.py` is the reference implementation of the rules below; its Kotlin port is
`SchedulerPlan.kt` (`SchedulerPlanner` + `PlanWalk`), checked against it by `SchedulerPlanTest`.

---

There are tasks. Each task has a minimum time and a priority percentage. The scheduler must place panels of tasks in a timeline from t_now to +infinity. When the scheduler places a task, it cannot then place another task until the placed one reaches its minimum time.
The scheduler must satisfy as much as possible the priority percentages, in a scale as small as possible.
Example: task A 50% 10min and task B 50% 10min
wrong: task A 1h, then task B 1h and so on...
right: task A 10min, then task B 10min and so on...

Since the timeline to fill is infinite, the scheduler doesn't give an infinite list, but a finite list of rules to fill the timeline with a O(1) complexity. This list is used to construct the schedule when displaying it from t=0 to t=x.
Example: task A 50% 10min and task B 50% 10min
result: list of three elements:
- task A 10min
- task B 10min
- repeat

The starting timeline has already placed tasks.

The timeline is formed of periods. Each period defines a set of tasks it accepts.

For example, task A 50% 10min and task B 50% 10min. If there is task A 1h already placed, I want a greater presence of task B right before and right after this long task A.
But if this task A is 100h, the presence of task B right before and right after would be even greater, but not proportionally greater. There should be some kind of exponential decay of its influence to the left and to the right. The same mechanism should also happen for a period that doesn't allow a task for a long time.

Limits and Fallbacks:
To ensure the rule list remains manageable, the scheduler enforces a strict maximum rule limit (e.g., 50 rules).

    Coarse Cycle Fallback: If a fine-grained cycle requires too many rules, the scheduler may fall back to a coarse grouping (one large block per task), overriding the "scale as small as possible" requirement.

    Truncated Timelines: If the rule limit is reached before a stable cycle can be calculated, the scheduler may drop the cycle entirely and return only the calculated prefix, leaving the remaining timeline unassigned.


Test 10 must have a 20 second period that allows only one task, and that continuously moves to the right. For only this specific moving period, the scheduler must add in its set of rules the rules for the dynamic schedule.
Example: task A 50% 10min and task B 50% 10min, a 20s period at t=0 that only allows task A moves slowly to the right.
idea of result: 
if t < 9min40, such as t is the starting time of the 20s period, then:
Cycle:
- task A 10min
- task B 10min
- repeat
if t is between 9min40 and 10min, then:
Prefix:
- task A 10min + t - 9min40
- task B 10min + debt repayment depending on t and decay parameter
[more rules in the prefix if the repayment still takes effect]
Cycle:
- task A 10min
- task B 10min
- repeat
Period: 20min
if t is between 10min and 19min40, then:
Cycle:
- task B 10min
- task A 10min
[or reverse]
- repeat
Period: 20min
if t is between 19min40 and 20min, then:
Prefix:
- task B 10min + debt repayment depending on t and decay parameter
- task A 10min + t - 19min40 - some space for the debt repayment of task B right before
- task B 10min + debt repayment depending on t and decay parameter
[more rules in the prefix if the repayment still takes effect]
Cycle:
- task A 10min
- task B 10min
[or reverse]
- repeat
Period: 20min
if t is ...


My idea of a result might not be exact, considering the debt repayment mechanism.

The associated example test must have this 20s period moving continuously to the right and the displayed schedule must update in real-time following the set of rules (not by triggering new calculations). If the 20s period reaches the end of the displayed timeline make it start anew.
