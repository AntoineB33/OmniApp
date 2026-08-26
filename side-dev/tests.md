
### Test Cases

The following tests define the expected behavior of the scheduler under various constraints.

In the testing display, the "resulting share" is calculated as the percentage of a task's presence across the drawn timeline, excluding periods where no tasks are allowed.

One instance of the same scheduler per test.

The tests that take a long time to be done are in the set of tests that appear as the first test in the display window when selected in the top menu. When selected the scheduler runs from a saved progression if it has been selected before but couldn't finish. When not selected the scheduler doesn't run.

A button allows to copy the test configuration and the resulting set of rules to the clipboard in a readable format. The test configuration is all the rule states and the starting timeline (including pre-placed tasks and restrictive periods that are not the three dynamic periods).


* **User Control:** The user can click anywhere on the schedule to move $t_p$ to that point. Dragging the cursor updates $t_p$ continuously. The system defaults to a paused state.

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
* **Requirement:** Priority percentages slide continuously from one state at 24h to a new state at 48h. For $t < \text{24h}$, the first state is applied. At a specific t between the two states is applied a state that is the state of the transformation from the first state to the second one . The scheduler must satisfy the exact priorities active at the precise moment of $t_p$.

#### Test 14: Extended Timeline Setup
* **Configuration:** Task A (50%, 45 min minimum) + 20 other tasks sharing the remaining 50% (all 45 min minimum).
* **Environment:** 8-day timeline. No tasks are allowed between 23:00 and 08:00 daily.