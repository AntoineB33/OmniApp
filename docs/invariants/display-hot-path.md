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
- **THE DISPLAY IS NOT POLLED. It is re-derived when the SET OF RULES says the picture changes**
  (`SchedulerDomain.displayResampleDelayMillis`), and the drawn line is not one of the things derived from
  it. The scheduler returns a set of rules and everything `App`'s body builds is read out of it, so the
  display is a **piecewise** function of the now-line: **nothing in the past is a function of the line** (the
  past is frozen — only an event, a "look away now" or a hand edit, changes it), **nothing in the future is
  either until the line crosses a boundary the rules already named**, and **what does follow the line follows
  it AFFINELY** (a pose the line drags in mode 1 at `(t_p, t_p + d]`, the panel growing behind it, a live
  band ending at it). So:
  - **The boundaries are the derived model's own bounds.** The model is built out of those instants, so it
    cannot change before the first one still ahead of the line — plus the next local midnight, the one
    boundary no panel carries (the day rollover). Sleep until it.
  - **A bound sitting ON the line is a PIN, never a boundary ahead of it** (`NOW_LINE_ANCHOR_SLACK`, 2 ms — a
    dragged pose starts at `t_p + 1`, a taken break is drawn to `t_p − 1`). Counting one as a boundary answers
    "one millisecond" and turns the sleep into a busy loop.
  - **A pin is re-derived at the DISPLAY'S OWN RESOLUTION**, not at its bound: the calendar reports how long
    the line takes to cross one pixel at the zoom in force (`onNowLineResolutionChanged` — ~75 s at the
    default zoom, ~0.6 s at the ceiling), and redrawing more often than that redraws the picture already on
    screen. Same principle as `visibleHourWindow`'s quantization: the temporal resolution follows the spatial
    one.
  - **The floor and the ceiling only BOUND that answer** (250 ms / 50 ms accelerated; 30 s). The ceiling is
    the engine's own production cadence, so a boundary this gets wrong costs a late redraw and never a wrong
    answer — no derivation can go staler than it did when the engine's tick drove it.
  - **The sampler's effect is keyed on its own TICK, never on the delay.** `App` recomposes for plenty of
    reasons that have nothing to do with the clock, and a key that moved with the answer would restart the
    sleep each time — a busy app would then never reach the end of one and the now-line would stop.
  The line's own continuity is bought separately and for free, in the draw phase (see *Calendar* above).
  Never point the two at one value again, and never put this back on a timer.
- **Nothing the now-line drags may be read to the second, and the line may not be rounded to the pixel.**
  Both are the same mistake as reading it to the minute was, one order of magnitude down each time, and both
  show up only at zoom. `recordsForDay` places every block at `hourOfDayExact` (a bound pinned to the line
  moves WITH it, so flooring it to the second makes it jump ~1.7 dp at the zoom ceiling however finely the
  display resamples), and the line, its dot and the overdue-reminder stack are placed with a **fractional**
  `graphicsLayer { translationY = nowLineOffsetPx(…) }` — `IntOffset` cannot carry a fraction, and a glide
  snapped to the pixel grid is not a glide. A block's edge is still whole-pixel `Dp` geometry; that residual
  is deliberate and ADR 0009 records what closing it would cost.
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
- **The schedule horizon is $t_{goal}$** (`SchedulerDomain.scheduleGoalEndMillis`): **the end of the timeline
  the calendar shows, or `now + 10 min` if that is further** (`SCHEDULE_GOAL_FLOOR_MILLIS`; user rule,
  2026-09-16) — `docs/scheduler_requirements.md` § *Progressive Calculation*, the instant the scheduler may stop
  at. It follows the SCROLL; a closed calendar leaves only the floor, which is all the headless task and
  wind-down cues read ahead of the line (the break windows the server is told come from the recurrence bars,
  not from `state.panels`). There is no "focused week".
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

