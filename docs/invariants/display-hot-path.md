# Display hot path

Active invariants. Reasoning and post-mortems: `docs/adr/`. Dated log: `CHANGELOG.md`.
Global rules that always apply: `CLAUDE.md`.

---

### Display hot path

→ ADR 0009.

- **Anything recomputed on every `nowMillis` tick must be bounded by the visible window, never O(total
  history).** Under sim the now-line ticks ~20×/s; an O(history) recompute pegs the UI thread and the window
  is created but never shown — which looks exactly like "the app won't open".
- **THE PLAN IS NEVER REDUCED ON THE FRAME LOOP.** One `fillSchedule` is 25-80 ms on a real account
  (`PerfBenchmarkTest`, and it grows with the task count), and both hosts run the engine on a main-thread
  scope — the composition's on desktop, the foreground service's on Android — because everything else it does
  is a cheap edge that belongs there. So `SchedulerEngine` reduces its two expensive plan intents
  (`RefreshSchedule`, `ExtendSchedule`) on `planDispatcher` (`Dispatchers.Default`) and nothing else moves:
  same intent, same reducer, one definition of a re-plan.
  - **Only the engine's own triggers may go off-thread.** The reducer's in-line re-plans — `ForceTaskSwitch`,
    `ForceTaskStart`, a sleep-schedule edit, a removed record — are direct answers to a press and must be in
    the state before it returns.
  - **Two threads therefore reduce against `SchedulerState`, so `dispatch` publishes by COMPARE-AND-SET** and
    re-reduces when it loses. A plain assignment lets a 60 ms plan publish a state derived from before the
    keystroke that landed inside it — the keystroke silently reverts, seconds after it was typed, and reads
    exactly like the sync clobbers in ADR 0007. `PlanConcurrencyTest` is that guard; `reduce.contended` in the
    overlay counts the retries, and more than a trickle of them means the re-plan is being asked for far too
    often, which is a rule-change bug upstream and not something to fix at the publish.
- **THE DERIVATION IS HELD ON WHAT IT READS, and a state change that touches none of it costs nothing**
  (`App.kt`'s `CalendarDisplayMemo`, 2026-09-21). `App`'s body re-runs for every state change there is — an
  engine tick, a sync, a log row, a keystroke in the tree — and the derivation is ~25 ms of the frame it
  lands in, plus the recomposition every equal-but-new record list forces on the calendar. The memo's key
  names every value the derivation reads and nothing else: a value it reads left out freezes the calendar on
  the reading taken before that value moved, an extra one only costs a miss. It has **two slots** because the
  calendar reads the derivation twice — at the line and one millisecond later ([withLineMotion]) — and one
  slot would make the two readings evict each other.
  - **The halves that do not read a task's TITLE are held separately** (the recurrence bars' environment, the
    past and forward screen-break placement, the reminder regeneration, the derived pauses). A rename
    rewrites `tasks`, so the whole-derivation memo misses on every letter typed into a cell; none of those is
    a function of a title, and `planTasksOf`'s value — priority, minimum, resilience, no title — is what
    carries the tasks into them.
  - **A record is read over the VISIBLE WINDOW, never over the account's history.** `task.record` is every
    stretch of it ever worked (2333 on the release account against the ~150 a week draws), and clipping and
    wrapping all of them per reading was the same rule broken one level down.
  - **A RENAME IS RE-LABELLED, NEVER RE-DERIVED** (`CalendarDisplayCache`). A rename moves exactly two of the
    derivation's inputs — the task's title and the titles of the panels the reducer renames with it — and no
    edge at all, so when the only difference is task titles the held reading has its records re-labelled from
    the tasks instead of being derived again (~0.5 ms against ~15 ms). That is what lets the user's two rules
    hold together: the calendar's titles change in the frame the letter lands in, and the letter does not pay
    for a placement nobody moved. The shortcut is refused the moment a title that no task owns moves (a
    renamed screen break, a hand-drawn period), because only the derivation knows where those are named from.
    It is pinned against deriving again by `CalendarRelabelEquivalenceTest`.
- **A ROW THE USER DID NOT TOUCH MUST NOT RE-COMPOSE.** Compose skips a row only when it can see that its
  arguments are unchanged, and it compares an argument it cannot prove stable BY INSTANCE — so a callback
  that closes over the whole `SchedulerState`, over the visible order, or over a `Cell`, and any holder of
  callbacks rebuilt inline (the row's contextual menu), is a changed argument on every state change, for
  every row. All ~44 visible rows re-composed for every tick, sync and keystroke until 2026-09-21. The state
  is read inside those callbacks through a `rememberUpdatedState` holder, and per-row holders are
  `remember`ed on what they offer. `recompose.TaskRow` ÷ `recompose.App` is the number: ~1 per changed row,
  not one per row on screen.
- **THE DISPLAY IS NOT POLLED. It is re-derived when the SET OF RULES says the picture changes**
  (`SchedulerDomain.displayResampleDelayMillis`), and the drawn line is not one of the things derived from
  it. The scheduler returns a set of rules and everything `App`'s body builds is read out of it, so the
  display is a **piecewise** function of the now-line: **nothing in the past is a function of the line** (the
  past is frozen — only an event, a "look away now" or a hand edit, changes it), **nothing in the future is
  either until the line crosses a boundary the rules already named**, and **what does follow the line follows
  it AFFINELY** (a pose the line drags in mode 1 at `(t_p, t_p + d]`, the panel growing behind it, a live
  band ending at it). So:
  - **The motion of every edge is READ, never declared** (`withLineMotion`): `App`'s one derivation
    (`deriveCalendarDisplay`) is read at the line and one millisecond later, and an edge that moved by exactly
    that millisecond follows the line (`CalendarRecord.startFollowsLine` / `endFollowsLine`). Never thread a
    "this follows the line" flag through the domain functions — that is a second copy of each one's rule.
    The second reading is taken only while the calendar is open and only when the first reading or the
    instant changed. Records whose readings straddle a boundary are left still, never guessed.
  - **The boundaries are the derived model's own bounds** — its FIXED ones, plus the instants an edge that
    follows the line meets a fixed one (`bound − offset`) or crosses a midnight into another row. The model
    cannot change shape before the first of those still ahead of the line — plus the next local midnight, the
    one boundary no panel carries (the day rollover). Sleep until it.
  - **An edge that follows the line is never a boundary and never a pin: it is DRAWN** (see the next rule). A
    FIXED bound sitting ON the line (`NOW_LINE_ANCHOR_SLACK`, 2 ms) is a pin whose motion was not read, never a
    boundary ahead of it — counting one answers "one millisecond" and turns the sleep into a busy loop — and it
    alone is re-derived at the display's own resolution (`onNowLineResolutionChanged`: ~75 s per pixel at the
    default zoom, ~0.6 s at the ceiling) until a reading recovers its motion.
  - **The floor and the ceiling only BOUND that answer** (250 ms / 50 ms accelerated; 30 s). The ceiling is
    the engine's own production cadence, so a boundary this gets wrong costs a late redraw and never a wrong
    answer — no derivation can go staler than it did when the engine's tick drove it.
  - **The sampler's effect is keyed on its own TICK, never on the delay.** `App` recomposes for plenty of
    reasons that have nothing to do with the clock, and a key that moved with the answer would restart the
    sleep each time — a busy app would then never reach the end of one and the now-line would stop.
  What moves is bought separately, on the frame clock (next rule). Never point the two at one value again, and
  never put this back on a timer.
- **EVERYTHING THAT FOLLOWS THE LINE MOVES CONTINUOUSLY, and the screen's pixels are the only rounding**
  (ADR 0009, 2026-09-17). Nothing the line drags may be read to the second (`recordsForDay` places at
  `hourOfDayExact`), and nothing that moves may be rounded to the pixel. `WeekView` samples the exact clock on
  the frame clock (`rememberFrameNowMillis`) only while something that moves is on screen, and three readers
  share it:
  - the **composition** reads it stepped to the pixel (`lineCompositionMillis`) and ONLY in a column holding a
    record that follows the line: that column's records are advanced along the line (`advancedAlongLine`), so
    hit-testing, hover tiles, the overlap slices and label fits are one pixel at most out of step, and every
    other column recomposes not at all;
  - the **layout phase** adds the rest of the pixel (`lineDriftHours`) in `timelineSpan` — THE placement for
    every block slice, screen-break band, period box, sleep outline, layer band and band label. A node with a
    following edge is laid out on whole pixels and its layer translated by the fraction and scaled by
    `height / ceil(height)` about its top, so both edges are exact. A node with no following edge keeps the
    whole-pixel `Dp` path, which keeps still text crisp. Never place one of those elements with a bare
    `offset(y = …)` again: it would step beside everything that glides;
  - the **line**, its dot and the overdue-reminder stack read it in a fractional
    `graphicsLayer { translationY = nowLineOffsetPx(…) }` — `IntOffset` cannot carry a fraction.
  The contract is `CalendarLineMotionTest`: extrapolating one reading equals reading the rules again at every
  instant before the boundary the reading predicts.
- **What the calendar COMPOSES is bounded by the visible window too.** A day row is one whole day tall while
  the viewport is not, so every `DayColumn` culls its output to `visibleHourWindow(...)`: a record scrolled
  out of view emits no UI node. This is a frame cost, not a tick cost — every floating window shares one
  Compose scene, so whatever the calendar keeps in the tree is redrawn on every frame *anything* in the app
  animates (dragging the reminders window was the reported symptom).
- **The cull window is QUANTIZED (`visibleHourWindow`), and must stay so.** Culling makes composition a
  function of the scroll; read unquantized, it would recompose every column on every scrolled pixel and cost
  more than it saves. The day-rows are still *placed* by the layout-phase `offset { … }` read of `offsetPx`.
- **The calendar's per-column derivations are cached on their inputs.** `overlapLayout` / `weightHandles` are
  pure functions of a day's blocks and are asked three times per `DayColumn`, while the column recomposes for
  every state change `App`'s body sees — a keystroke in the task tree included. Each site is
  `remember(blocks)`, so an unrelated recomposition pays nothing. Keep the key the block list itself: keyed on
  anything narrower, a cached slicing outlives the blocks it describes.
- **Cull the EMISSION, never the list.** `overlapLayout` widths, the reminder/alarm stacking sweeps,
  hit-testing, the contextual menu and the drag snap set all still see the whole day — a partner scrolled out
  of view must still narrow the block on screen. A block mid-gesture is exempt: its slices hold the gesture.
- **Test against a large, realistic DB**, not just an emptied one — an empty account hides the cost entirely.
- **The schedule horizon is $t_{goal}$** (`SchedulerDomain.scheduleGoalEndMillis`): **the LATEST of the end of
  the current week, the end of the timeline the calendar shows, and `now + `**`SCHEDULE_GOAL_FLOOR_MILLIS`
  (user rule, 2026-09-18) — `docs/scheduler_requirements.md` § *Progressive Calculation*, the instant the
  scheduler may stop at. It follows the SCROLL and it follows the WEEK, so a closed calendar no longer leaves
  only the floor: the week the user is living in is planned whether or not a window shows it, and the goal steps
  forward at each rollover instead of drifting with the line. The floor is what is left in a week's last ten
  minutes, and what the headless task and wind-down cues read ahead of the line (the break windows the server is
  told come from the recurrence bars, not from `state.panels`). There is no "focused week".
- **A fill may also stop for the other two reasons the rule names** — the set of rules growing too heavy (the
  168 h ceiling) and `SchedulerEngine.PLAN_CALCULATION_LIMIT_MILLIS`. The second one leaves `panels` SHORT of
  $t_{goal}$ on purpose, and `extensionStoodDown` is what stops the watcher below from treating that as the gap
  it exists to close (`scheduler.md` § *Progressive Calculation*).
- **The floor ROLLS, so a fill never aims at the goal itself.** It fills to `scheduleHorizonEndMillis` — the
  goal with the floor **doubled** and a calendar end capped at 168 h — and `horizonRefillDueMillis` comes due
  when the plan covers only one floor ahead of the line. So a closed calendar costs one small extension per ten
  minutes, never one per tick; the capped far week one per `HORIZON_REFILL_MARGIN_MILLIS`. A fill aimed at the
  rolling instant itself is the 2026-07-28 self-retrigger (`HorizonRefillRuleTest`).
- **A fill cuts the previous plan's auto panels past its own horizon too.** With the ten-minute floor a fill
  that stops short of an older, longer plan is routine; the old panels answer rules the fill may have replaced,
  and one abutting the new tail was read by the next extension as materialized and kept as definitive
  (`SchedulerFillTest.a_re_plan_shorter_than_the_old_plan_drops_the_old_plan_beyond_it`).
- Beyond the ceiling, the far fill runs off the UI thread keyed **only on the span** and is **never stored in
  `state.panels`**. It is an **extension** of the materialized plan (`keepExistingUntilMillis`), so where the rules
  repeat (`state.scheduleCycle`, `scheduler.md` § *The rules repeat*) it unrolls them rather than searching — a
  30-day view of a 6-task account went from ~165 ms to ~40 ms (2026-09-16, JVM test) — and past the 168 h limit it
  never searches at all.
- Horizon growth dispatches `ExtendSchedule`, not `RefreshSchedule`.
- Day rows are `wrapContentHeight(Alignment.Top, unbounded = true).height(dayHeight)` — both halves
  load-bearing. `requiredHeight` silently centres the row and shows the wrong hours.

---

