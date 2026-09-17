# ADR 0009 — Display hot path, schedule horizon, and the rolling calendar

**Status:** active. **Invariant summary:** see `CLAUDE.md` → *Display hot path*.

## Hot-path display derivations must scale with the SCREEN

Anything `App.kt` recomputes on every `nowMillis` tick (the calendar bands, records, screen-break / reminder /
sleep projections) must be bounded by the visible window, **never O(total account history)**.

### Why it fails silently

Under time-sim the now-line ticks ~20×/s (`ADVANCE_DISPLAY_MILLIS_ACCEL` = 50 ms, forced on even at 1×), so an
O(history) recompute pegs the AWT/Compose UI thread. Because Compose only shows a window after its first frame
renders, the window is **created but never shown** on a large-but-valid DB — which looks exactly like "the app
won't open". A freshly-emptied account hides the cost entirely.

**Test against a large, realistic DB, not just an emptied one.** This is a data-VOLUME concern (a valid DB that
is simply big), distinct from persisted-DB FORMAT compatibility (ADR 0007).

### The mitigations

- The display is not recomputed on a clock tick at all any more — it is recomputed at the boundaries the set
  of rules names (`SchedulerDomain.displayResampleDelayMillis`); see the two sections below.
- The deeper structural fix is to memoize the fixed-PAST portion with `remember(<real inputs>)` and recompute only
  the live tail.

See the `timesim-large-account-ui-overload` note.

## The plan is reduced off the frame loop, and what that costs (2026-09-06)

`PerfBenchmarkTest` puts one `fillSchedule` at **25 ms for 6 tasks and 80 ms for 50** — by a wide margin the
most expensive thing the app computes, and deliberately so: it runs on a debounced rule change, an hourly
staleness bound and a horizon roll, never on a tick, which is the rule the rest of this ADR exists to protect.

What was missed is *where* it ran. `SchedulerEngine`'s scope is a **main-thread** scope on both hosts — on
desktop `rememberCoroutineScope()`, i.e. the composition's, and on Android the foreground service's
`Dispatchers.Main`. That is the right home for nearly everything the engine does, because nearly everything it
does is a cheap edge (a cue, a notification, a lock flip). It was the wrong home for the one thing that is not.
So every re-plan spent 25-80 ms on the frame loop — four dropped frames, landing exactly 1 s after the user
stopped typing, which is when the rule-change debounce fires.

### Why not move the engine

Moving the whole engine to `Dispatchers.Default` would have taken the tray icon, the notification sink, the
alarm ring and the platform activity listener off the main thread with it, for no benefit — none of them is
expensive, and some of them are AWT. One dispatcher is named for one job instead: `planDispatcher`, used by
`dispatchPlan` for `RefreshSchedule` and `ExtendSchedule` and by nothing else. It defaults to `null` (reduce
inline), so a headless host or a virtual-time test is never made asynchronous behind its back.

The rule does not move with it. The same intents go through the same `SchedulerReducer`, so there is still one
definition of what a re-plan is — which is the point, because the in-reducer re-plans (`ForceTaskSwitch`,
`ForceTaskStart`, `SetSleepSchedule`, `RemoveRecordPeriod`) must stay synchronous: they are direct answers to a
press and have to be in the state before it returns.

### The price: two threads on one state

`TaskSchedulerViewModel.dispatch` was a read-modify-write (`val current = _state.value` … `_state.value = next`)
that was safe only because everything reduced on one thread. It is not any more. A 60 ms plan that snapshots
`current`, then has a keystroke commit under it, then assigns, **reverts the keystroke** — a second after it was
typed, with no error and no pattern the user can describe. It is the exact shape of the lost-ack and
startup-reconcile clobbers in ADR 0007, and it would have been diagnosed as one of those.

So the publish is `MutableStateFlow.compareAndSet` and the loser re-reduces against the winner's state. This is
correct rather than merely lucky: a re-plan is a pure function of the state it reads, so re-running it against a
newer state is exactly what should have happened, and the loop terminates because every retry is caused by a
commit that actually occurred. The retries are **counted, not bounded** (`reduce.contended`) — a bound would
have to choose between dropping the intent (a calendar that no longer matches the tree until the hourly bound)
and clobbering the winner (the bug), and both are worse than a second derivation. `PlanConcurrencyTest` runs the
real race on real threads and fails if the race did not happen.

### What was NOT changed, and why

The same report that prompted this named three other bottlenecks. Measured:

- `encodeSnapshot` — 2.2 ms, already on `Dispatchers.Default` (never the UI thread), already memoized per
  history unit. Nothing to fix.
- `App`'s ~45 body derivations — **0.7 ms per recomposition, all of them together.** Memoizing them would buy a
  twentieth of a frame in exchange for `remember` keys that must be exactly the derivation's inputs forever
  after; get one key wrong and the display shows a stale past, which is the failure this whole ADR is about.
  Not worth it at that price. (The interesting question there is not the arithmetic but whether fresh list
  instances defeat Compose's skipping of the calendar subtree — that is a *recomposition* measurement, which the
  in-app overlay takes and a headless benchmark cannot.)
- `overlapLayout` — 0.24 ms for a typical day, 1.2 ms for 100 blocks, asked three times per `DayColumn`. Cheap
  enough not to matter and cheap enough to cache, so it is `remember`ed on its block list. That is a tidy-up,
  not a fix.

The general lesson, now in `docs/PERFORMANCE.md`: a per-call cost is not a bottleneck until it is multiplied by
a rate and placed on a thread that owes somebody a frame. A benchmark table ranks costs; only the overlay's
ms-per-second ranking, or the thread the call sits on, turns one into a diagnosis.

## A continuously moving now-line is a LAYOUT cost, not a tick cost (2026-09-05)

**Symptom.** Zoomed far in, the now-line advanced in visible jerks instead of gliding. The question behind the
report was the right one: *is a continuously moving line simply expensive?*

**Cause — one value doing two jobs.** `App.kt` sampled `nowMillis` on the Compose frame clock
(`withFrameNanos`, 2026-09-01) so that the line would follow the clock. But `nowMillis` is what the whole of
`App`'s body derives from — the sleep and screen-break projections, the derived grey bands, the layer regions,
the reminder horizon, $t_{goal}$, the cull windows — so every frame re-ran that entire O(visible window) pass,
sixty times a second, to move one line. And it still did not move smoothly: the placement read
`LocalTime.hourOfDay`, which floors at the second, so at the zoom ceiling (6144 dp per hour) the line jumped
~1.7 dp once a second.

**Decision — split the two.**

- **Derived from the clock ⇒ quantized** (250 ms at first; superseded the same day by the boundary rule
  below, which is the real answer).
- **The line ⇒ sampled per frame, read in the DRAW phase.** `CalendarUi.rememberNowLineHour` samples the
  exact clock on `withFrameNanos` into a state read only from `Modifier.graphicsLayer { translationY = … }`,
  and the placement uses `hourOfDayExact` (a `Double`; a `Float` around 24 quantizes at ~7 ms all by itself).
  Re-drawing a layer recomposes nothing, re-measures nothing and re-places nothing.
- **The sampler runs only while the line is on screen.** It is gated on the same cull window everything else
  in the column is (`nowLineOnScreen`), so a column that is not today's, a grid scrolled to another week, and
  a closed calendar ask for no frames at all — which is the honest answer to the energy question: the frame a
  moving line costs is unavoidable, the recomposition behind it and the frames when nothing is moving are not.

### The two orders of magnitude that were still missing (2026-09-05, same report reopened)

The fix above was reported as *still* stepping — the line, and the pose the line drags — and the report was
right on both counts. "The lag is one pixel by construction" was a claim about the RESAMPLE, and it was
quietly answering a different question from the one asked. Two more quantizations sat under it:

- **The bands were still floored to the second.** `recordsForDay` placed every block at
  `hour + minute/60 + second/3600`, so a bound PINNED to the line — the pose at `(t_p, t_p + d]`, the panel
  ending at `t_p` — advanced one second at a time however finely the display resampled. At the zoom ceiling
  that is ~1.7 dp, which is what "when zoomed in enough, it is not just one pixel" was measuring. Blocks now
  read `hourOfDayExact` too; the residual is the `Float`'s own step (~7 ms ≈ 0.01 dp at that ceiling), so
  `PlacedRecord` did not have to become `Double` and the whole block pipeline was left alone.
- **The line was rounded to the pixel grid.** `Modifier.offset { IntOffset(…) }` cannot express a fraction of
  a pixel, so a clock sampled at 60 Hz reached the screen as *hold still, then jump one whole pixel* — every
  ~75 s at zoom 1, about twice a second at the ceiling. **One pixel is not "imperceptible": a discrete jump
  is the single thing peripheral vision is best at.** `nowLineOffsetPx` returns a `Float` and the line, its
  dot and the overdue-reminder stack are placed with `graphicsLayer { translationY = … }`, so Skia
  anti-aliases the crossing. That is the end of the road — below one pixel a display has only intensity left,
  and using it is exactly what "continuous motion on a discrete grid" means.

**What is still quantized, and honestly so.** A BLOCK's edge is composition-phase `Dp` geometry
(`Modifier.offset(y = hourHeight * startHour)`, which rounds to the pixel at placement) recomputed on the
quantized display instant. So the pose the line drags now steps by exactly one pixel — the pixel grid, no
longer a second of time — where the line beside it glides. Closing that last pixel means giving the block
pipeline (`overlapLayout`, the slices, the hover tiling, the drag/resize gesture) per-frame float geometry, or
carrying the affine "follows the line" rule into the draw phase as a `graphicsLayer` translation on pinned
bands. The second is tractable for a band whose WHOLE span is pinned (a rigid translation) and is not for one
with a single pinned edge (the panel growing behind the pose changes shape, not position) — and doing only
the first would open a one-pixel seam between the pose and the panel above it, which is worse than the step.
Left undone deliberately; this note is the record of the trade, not of a limit.

The overdue reminder tags stack on the same state, because CLAUDE.md's rule is that the stack's anchor and the
line read one instant. What each of the two halves decides is worth stating: the quantized instant decides what
**exists** (is there a line on this column, is it in view, which tags are overdue); the frame-sampled one
decides only **where** those go.

**This changes nothing in the engine.** `docs/scheduler_requirements.md`'s *"the $now line$ moves continuously
forward in time"* is a statement about the scheduler's now-line, and that one is already walked and never
teleported (`SchedulerEngine.sweepNowLineTo`, one `SchedulerDomain.sweepStepMillis` at a time, pinned by
`NowLineSweepTest`). What was fixed here is only how the calendar DRAWS it.

## The calendar culls to the viewport (2026-08-21)

The tick rule above is about how OFTEN work runs. This is about how MUCH is standing in the tree between ticks
— a different cost, found from a different symptom.

**Symptom.** With the calendar open, dragging the reminders window around made the whole app sluggish; with the
calendar closed the same drag was smooth. Nothing was recomputing: the drag's offset is local state read in a
layout-phase `offset { … }`, so it recomposes nothing.

**Cause.** The floating windows are not OS windows — they are siblings in one `Box` in `App.kt`, so the whole
app is ONE Compose scene on one UI thread, and Compose Desktop re-runs the draw pass over the entire visible
node tree every frame (no per-node display list). Every frame the drag requested therefore redrew the whole
calendar. On a real account that is ~122 panels/day × the 14 day-cells the grid composes (`DAY_COLUMNS` ×
`rollingRowCount`) ≈ 1,700 records, each a `BoxWithConstraints` (a `SubcomposeLayout`) plus slices, a `Text`,
a drag `pointerInput` and `calendarTitleHover`'s three more — order 20k nodes, most of them scrolled out of
sight because a day row is 24 h tall and the viewport is not.

**Fix.** `visibleHourWindow(row, offsetPx, dayHeightPx, viewportPx)` → an `HourWindow` per day-row; `DayColumn`
emits nothing outside it. Measured on a real account: 2.2× fewer records composed at zoom 1, 3.7× at zoom 2.5,
11.6× at zoom 8.

### The two rules that keep it honest

**The window is quantized, outward.** Culling makes composition a function of the scroll — read raw, it would
recompose all 14 columns on every scrolled pixel and lose more than it wins. The window is snapped outward to a
quantum of one viewport-height of travel (clamped to [1 h, 6 h]), so the columns recompose about once per
screenful scrolled at any zoom, and *snapping outward* is what makes culling invisible: the window always covers
at least what is on screen, so nothing pops in late. 6 h is the measured knee — a 2 h ceiling buys 2.7× instead
of 2.2× at zoom 1 for three times the recompositions. The derived state is read inside the gutter/column
content lambdas, not in `WeekView`'s body, so a quantum crossing recomposes those and nothing above them.

**Only the emission is culled, never the lists.** `overlapLayout` still sees the whole day (a block's width
comes from what it overlaps, so a partner off screen must still narrow the one on screen); the reminder and
alarm stacking sweeps still run end to end (each slot depends on the one above it); hit-testing, the contextual
menu and the drag snap set still see every block. One exemption: a block mid-drag stays mounted wherever the
drag carries it, because its slices are what hold the gesture.

Tests: `RollingCalendarTest` — the safety property (the window never clips anything on screen, swept over every
offset × zoom × viewport × row), the quantization bound, and that it actually culls.

## The schedule horizon is $t_{goal}$: the end of the displayed timeline, floored at ten minutes

The engine does **not** systematically materialize 168 h: it fills to **$t_{goal}$**, the instant
`docs/scheduler_requirements.md` § *Progressive Calculation* lets the scheduler stop at — *"The scheduler can have
a time $t goal$ such as when definitive schedule is found for any t < $t goal$ the scheduler can stop."*

**$t_{goal}$ = the end of the timeline the calendar shows, or `now + 10 min` if that is further**
(`SchedulerDomain.scheduleGoalEndMillis`, `SCHEDULE_GOAL_FLOOR_MILLIS`; user rule, 2026-09-16).

> **History.** From 2026-09-04 to 2026-09-16 it was `max(` end of the first day that does not appear in the
> calendar `,` end of the first day of the week after the current week `)`: one day past the grid, floored at a
> weekly staircase so that the headless engine always held up to eight days of plan. The user replaced it with
> the rule above: the scheduler covers what is shown, and with nothing shown only the next ten minutes, which is
> all the headless task cue, the §17 wind-down cue and the schedule-unit deadlines read ahead of the line. The
> §15 break windows the server is told are asked of the recurrence bars directly, never of `state.panels`, so
> they never depended on the weekly floor. The weekly half also made every rule change on a closed calendar
> plan a week — seconds of work on a large account — for a picture nobody was looking at.

**There is no "focused week"** (2026-08-20): the calendar scrolls through the days ENDLESSLY, so the grid reports
the span its scroll has landed on (`CalendarFloatingWindow(onVisibleDaysChanged = …)` → `App.kt`'s
`visibleFirstDay` / `visibleDayCount` → `visibleSpanStartMillis` / `visibleSpanEndMillis`), and the goal is read
off THAT exclusive end.

`App.kt` publishes the span to the engine (`engine.setCalendarHorizon(visibleSpanEndMillis)`, null when the
calendar is closed), the engine feeds the reducer seam `SchedulerReducer.scheduleHorizonEndMillis`, and every §9
refill fills to `SchedulerDomain.scheduleHorizonEndMillis(now, displayedEnd)`:

- **the floor is DOUBLED there.** The floor rolls with the line, so a plan that reached `now + 10 min` would be
  short again one millisecond later, and the refill trigger — which rewrites the very `panels` it watches — would
  fire at every tick: the 2026-07-28 hot loop again. A fill reaching `now + 20 min` comes due once the line has
  moved ten minutes, while ten minutes of plan are still ahead of it;
- **a calendar end past `now + SCHEDULE_HORIZON_MILLIS` (168 h) is capped there**, and beyond it that week is
  still computed to the goal — for display, off the UI thread (below). The cap rolls too, so it keeps the
  `HORIZON_REFILL_MARGIN_MILLIS` (1 h) of slack: a capped week is extended once an hour.

**A fill cuts the previous plan's auto panels past its own horizon.** Until 2026-09-16 a panel starting past the
horizon survived whatever it was. That was rarely visible while the goal was at least a week, but with the floor a
fill that stops short of an older, longer plan is routine (close the calendar, change a rule), and the progressive
first stage (1 h) is one too: the old panels past the cut answered rules the fill may have replaced, and when one
happened to abut the new tail, the next extension read it as materialized (`firstFreeMoment`) and kept the old
plan as definitive. Panels the fill does not own (pinned, hand-drawn, periods, reminders) still survive.

Everything the fill projects is bounded with it — `fillSchedule` passes its own `horizon` to `screenBreakPanels`
(it used to project a week of break panels whatever horizon it was filling). What is *drawn* is a different
question and stays bounded by the visible window (above): `App.kt`'s display sleep projection still uses
`max(now + 24h, visibleSpanEnd)`.

### The two engine loops that keep it honest

**`launchHorizonReschedule`** re-evaluates `horizonRefillDueMillis(panels, now, displayedEnd)` on every pass and
sleeps until that instant (never longer than one poll, so a scroll or a clock-speed change is noticed). It is due
when the plan covers only one floor ahead of the line, or when the calendar shows further than the plan reaches.
With the calendar closed it fires once per ten minutes.

**`launchCalendarHorizonReschedule`** fires one refill when the scroll reaches past the plan. Scrolling back
dispatches nothing: what is materialized stays until the next fill.

**Both dispatch `ExtendSchedule`, not `RefreshSchedule`** (ADR 0001 §9): a horizon that grew is not a rule change,
so the plan already on screen is kept and only its tail is materialized.

> **Consequence for the self-retrigger guard.** An extension that cannot close the gap it was triggered by returns
> the state unchanged, so this collector *parks* until something else moves `panels` (the next rule change) instead
> of spinning — the opposite failure from the 2026-07-28 hot loop, and the safe one.

Tests: `ScheduleHorizonTest`, `HorizonRefillRuleTest`, `ScheduleCycleTest`,
`SchedulerFillTest.a_re_plan_shorter_than_the_old_plan_drops_the_old_plan_beyond_it`.

### Beyond the 168 h ceiling: unroll for display, never store

When the goal reaches past the ceiling, `App.kt` computes the plan for DISPLAY out to **the goal** —
`SchedulerDomain.fillSchedule(state, now, horizonMillis = goalEndMillis, keepExistingUntilMillis = …)`, an
**extension** of the materialized plan (the horizon parameter's default — the 168 h ceiling — is only for tests and
max-span callers). The goal is never nearer than the displayed span's end, so this reaches at least as far as what
is on screen.

### The far week is the rules repeating (2026-09-16)

The user's rule: *"The set of rules resulting from the scheduler can make a pattern that repeats to infinity. A
limit should prevent the set of rules to become too heavy. When the user goes far into the future, it doesn't add
much in memory as the displayed schedule only listens to the set of rules."* `docs/scheduler_score.md` §
*The rules repeat* defines it; what the implementation learned on the way:

- **Calendar days do not repeat; the schedulable clock does.** Measured on the requirements' example (A 30, B 15,
  C 15 min, production breaks, a sleep schedule), no two consecutive days were alike — the 90-min rotation slides
  30 min against a 15.5-h waking day — while the task-run sequence on the schedulable clock was exactly periodic
  (5 runs), breaks or no breaks, because nights and breaks refuse everybody and are not on the clock. So the cycle
  is a run sequence, and the wall-time picture is that sequence walked over the environment.
- **Stage boundaries break the repetition.** The same account planned in progressive stages never repeated: each
  extension resumes mid-run with a lookahead cut at the stage's end (a 17-min B). So detection only looks inside
  one fill, and once a cycle is found every later extension unrolls it — which also makes the plan past that point
  the same as one long fill.
- **Compare task runs, not optimizer runs.** The alternative-schedule bisection splits a run at an instant that is
  resolved only to a minute and moved between copies (56 s into A in one, into B in the next); the runs themselves
  were identical.
- **The horizon bends more than the last run.** On a 3-day fill the 4th run from the end was already off (C 18 min),
  so detection slides its window back up to one period and eight more runs.
- **Exact repetition is the exception on a real account.** Six equal tasks with minimums 45/30/20/60/15/25 min did
  not repeat within 8 days (runs of 15, 18, 22 min for one task). That is what the limit is for: the search stops at
  168 h, and a fill reaching it without a repetition repeats the window of its last runs whose shares come closest
  to the targets. On that account the far weeks (days 10–30) stayed within ~3 points of each task's share, against
  ~2 for a real search, and a 30-day view went from ~165 ms to ~40 ms. Rejected: searching further for an exact
  period (it may not exist, and cost grows with it), and a day- or week-aligned repetition (it does not repeat,
  above).

It runs inside a `LaunchedEffect` keyed **only on the displayed span** (not `nowMillis`, so it doesn't rerun every
tick), on `Dispatchers.Default`. So a distant day "simply takes time to display" behind a **"Calculating…"** header
hint instead of freezing the window.

The result is **never stored in `state.panels`** — only swapped in as the `mergePanelsForDisplay` block source for
that render. So scrolling back to a near day just uses the near panels again and the far fill is dropped; the
"erase far weeks" behaviour falls out for free, with no retained multi-week memory.

The placement itself is O(n) (`DynamicPeriods.instances` walks the bars forward, never rescanning what it already placed), and
`MAX_SCHEDULE_PANELS` scales with the horizon span (~1 chunk / 30 s) so the far weeks aren't clipped.

**Limitation:** an authoritative edit while sitting on a far span doesn't refresh the far fill until the scroll
moves again (keyed on the span only). Acceptable — distant days are rarely edited.

## The rolling grid

`WeekView` keeps the name but is a rolling timeline:

- an UNBOUNDED `offsetPx` driven by `Modifier.scrollable` (no `ScrollState`, so no range to run out of and no clamp
  at "the top");
- held inside one day by rolling whole days into `anchorDay` (`rollingDayShift`), with column `i` drawing
  `anchorDay + i` and each row one day further down (`rollingDayAt`: under day d sits d+1, above it d−1);
- rows placed by a LAYOUT-phase `offset { … }` read, so scrolling re-lays-out instead of recomposing;
- `rollingRowCount` bounds the columns composed to the VIEWPORT — never to how far from today the user has
  scrolled.

### A day row is sized `wrapContentHeight(Alignment.Top, unbounded = true).height(dayHeight)`

**Both halves are load-bearing.** A row is one whole day tall while the scroll viewport is not, so the row must be
measured against the viewport's own bounded constraints and then overflow it.

| Modifier | What goes wrong |
| --- | --- |
| `Modifier.height` alone | ENFORCES the incoming constraints and clamps the row to the viewport (the hour Column then measures its trailing boxes at zero height) |
| `Modifier.requiredHeight` | ignores them — but then **CENTERS** the over-tall content in the clamped slot, silently adding `(viewport − dayHeight) / 2` to every row's position |

> **Post-mortem (2026-08-20).** The centering is invisible in the code and nearly invisible on screen: gutter and
> columns shift TOGETHER, so the grid still looks self-consistent and merely shows the **wrong hours** (≈8 h off at
> zoom 1 in a 726 px viewport). The reported symptom was the **now-line missing**, because its column was pushed off
> the top of the view while the lock faithfully scrolled to where the now-line mathematically was.
>
> **Diagnosis note:** the lock's pure math and `RollingCalendarTest` were green throughout. What caught it was
> comparing a node's real `positionInWindow()` against `viewport + offset { }`. When a Compose layout "paints the
> wrong place", probe the placement, not the state.

Measuring unbounded and aligning TOP is the only combination that gives a full-height row placed exactly where
`offsetPx` says.

### The now-line lock

A title-bar switch (`lockNowLine`, default off) that holds the now-line at the MIDDLE of the viewport. While on, an
effect re-applies `nowLineCenterOffset` on every clock tick / zoom step / viewport resize, and `applyZoom` pivots on
the now-line instead of the cursor (a pinch's pan component is dropped with it).

It is released by a **scroll** (any non-zero `scrollable` delta) and by a **date pick** — both are "take me
elsewhere", and without the second the jump would be pulled straight back on the next tick.

Purely local Compose state like the zoom: never persisted, never synced.

Tests: `RollingCalendarTest`.

## The display is a PIECEWISE function of the now-line, so it is not polled at all (2026-09-05)

The quantization above answered *how often can we afford to recompute?* That is the wrong question, and the
user's follow-up said why: **nothing in the past is affected by the now-line moving** (the past is frozen — a
20 s break is removed by pressing "look away now", a period is changed by hand; both are EVENTS), and the
future panels *"simply follow the instructions of the set of rules given by the scheduler, telling what
happens between periods of time"*. `docs/scheduler_requirements.md` says exactly that: the scheduler returns a
**set of rules**. Between two of its boundaries there is nothing to recompute, at any rate.

**Decision — the display's clock is the rule set's own boundaries.**

`SchedulerDomain.displayResampleDelayMillis(bounds, now, tz, millisPerPixel)` is the whole rule, and `App`
sleeps on its answer instead of ticking.

- **The boundaries are the derived model's own bounds.** Every panel, band, marker and layer region the
  calendar was just handed is built out of instants; the model therefore cannot change before the first of
  them still ahead of the line. That is a sound over-approximation *by construction* — it can name a boundary
  that turns out to change nothing, but it cannot miss one, because there is nothing in the model that is not
  made of those instants. Plus the next local midnight, which is the one boundary no panel carries (the day
  rollover; until 2026-09-16 also the $t_{goal}$ weekly staircase, since replaced by the calendar end and a
  ten-minute floor).
- **A bound sitting ON the line is a PIN, not a boundary.** This is the load-bearing distinction, not
  bookkeeping: mode 1 pushes an owed pose onto the line as the half-open `(t_p, t_p + d]`, so its start is
  literally `t_p + 1`, and a taken break is drawn to `t_p − 1`. Counted as boundaries they answer "one
  millisecond" and the sleep becomes a busy loop measuring a picture that has not moved.
  `NOW_LINE_ANCHOR_SLACK` is two milliseconds — those two cases and nothing else.
- **A pin is re-derived at the display's own RESOLUTION.** What is pinned follows the line affinely — the
  dragged pose slides at rate 1, the panel behind it grows at rate 1 — so the only reason to redraw it is that
  it has moved far enough to see. The calendar reports how long the line takes to cross one pixel at the zoom
  in force (`onNowLineResolutionChanged`; the zoom is Compose-only state that lives there): ~75 s at the
  default zoom, ~0.6 s at the ceiling. This is the same principle as `visibleHourWindow`'s quantization one
  section up — *the temporal resolution follows the spatial one* — and it is what makes the answer to "must a
  dragged panel cost four recomputations a second?" a flat no: at the zoom the user normally sits at, it costs
  one every thirty seconds, because that is the fastest the screen could show a difference.
- **The floor and ceiling only bound the answer** (250 ms, 50 ms under acceleration; 30 s). The ceiling is
  deliberately the engine's own production cadence: a boundary this rule gets wrong costs a late redraw and
  never a wrong answer, and nothing here can go staler than it did when the engine's tick drove the display.
- **The sampler's effect is keyed on its own tick, never on the delay.** `App` recomposes for plenty of
  reasons unrelated to the clock (a peer's sync landing, an edit); a key that moved with the answer would
  restart the sleep each time, and a busy enough app would never reach the end of one — the now-line would
  simply stop. The delay is read through `rememberUpdatedState` so each iteration still sleeps on the freshest
  answer.

**What this does not do** *(superseded 2026-09-17 — see the next section)*. The pin is re-*derived* at one
pixel of resolution, not placed continuously: a
dragged pose's top edge and the growing panel behind it are composition-phase geometry, unlike the now-line
itself. Making them layout-phase too would mean giving the block pipeline (`overlapLayout`, the slices, the
hover tiling, the drag/resize gesture) a layout-phase notion of a block's bounds, which is a much larger
change for a lag that is one pixel by construction. The now-line, which is the thing the eye tracks, is
already exact.

## Everything that follows the line moves continuously (2026-09-17)

**Symptom.** The user's report against `docs/scheduler_requirements.md`: *"the panels don't move as smoothly as
the current now line, which makes situations where the now line must drag a panel visually incorrect."* In mode
1 the pose the line drags is `(t_p, t_p + d]` — its top edge IS the line — and the calendar drew it one pixel
at a time beside a line that glided, so the band sat visibly behind the line that is supposed to be pushing it.
The note above had called that residual acceptable. It is not: it draws something the rules do not say.

**The question behind it** was the right one: *can't the movement of those rectangles and text be
mathematically continuous, with the screen's pixels the only rounding, and the set of rules read once with
every smooth movement known in advance?* Yes — because the section above already established the property that
makes it possible: between two boundaries the display is **piecewise AFFINE** in the line, and every bound is
either fixed (`t = a`) or follows the line (`t = now + a`).

**Decision — read the motion once, draw it on the frame clock.**

- **The motion is READ, not declared.** The display derivation (`App.kt`'s `deriveCalendarDisplay`, one
  function) is read twice: at the line and one millisecond later. `withLineMotion` matches the two readings by
  position and marks each edge that moved by exactly the probe as following the line
  (`CalendarRecord.startFollowsLine` / `endFollowsLine`). Declaring it at the source was rejected: the edges
  that follow the line come out of a dozen domain functions (the dragged pose, the plan clipped around it, the
  live inactivity tail, the live sleep band, the layers ending at the line, the floor of the derived bands…),
  and a flag threaded through all of them is a second copy of each one's rule. The reading cannot drift from the
  rules because it IS the rules, asked twice. A record whose two readings straddle a boundary (a different
  count, a different record, an edge moving by anything but the probe) is left still — drawn exactly as before
  — and the fixed-bound pin rule below still re-derives it per pixel until a reading recovers its motion. The
  second reading is taken only while the calendar is open and only when the first reading or the instant
  changed, so a recomposition for an unrelated reason (a keystroke) pays for one derivation, not two.
- **The boundaries include the motion's own.** `displayResampleDelayMillis` takes the following edges as
  offsets from the line: one meets a fixed bound at `bound − offset` (a dragged pose reaching the next period —
  the requirements' 5 min → 15 min merge) and crosses into the next day's column at the midnight after its
  position. Those instants are known from the reading, so they are boundaries; a following edge is never a pin
  and never a busy loop. The per-pixel resample survives only for a FIXED bound sitting on the line, which is
  now exactly the "motion not read" case.
- **Three clocks, one instant.** `WeekView` samples the exact clock on the frame clock (`rememberFrameNowMillis`)
  while anything that moves is on screen — the line on today's column, or a record that follows the line in a
  cull window. From it:
  - the **composition** reads the line stepped to the pixel (`lineCompositionMillis`), and only in a column that
    holds something following the line: its records are advanced along the line (`advancedAlongLine`), and
    hit-testing, hover tiles, the overlap slices and whether a label fits are answered for that position. None
    of those can tell two positions under a pixel apart, so they recompose once per pixel of travel — ~75 s at
    the default zoom, ~0.6 s at the ceiling — and every other column not at all;
  - the **layout phase** gets the rest of the pixel (`lineDriftHours`), and `timelineSpan` — the one placement
    every block slice, screen-break band, period box, sleep outline, layer band and band label goes through —
    places a node with a following edge BETWEEN pixels: laid out on whole pixels covering the exact span, its
    layer translated by the fraction and scaled by `height / ceil(height)` about its top, so both edges land
    exactly where the rules put them and everything inside (title, outline, hover tiles, hit area) rides along.
    The scale differs from one by under a pixel over the whole element; a rigidly moving pose keeps one
    constant scale and only glides. A node with no following edge keeps the whole-pixel `Dp` placement, which
    is what keeps still text crisp;
  - the **line** and the overdue-reminder stack read the frame clock in their `graphicsLayer`, as before.

  The now-line lock re-centres on the composition clock from an effect (`snapshotFlow`), so a locked grid
  follows the line once per pixel without the grid recomposing.

**The contract, and its test.**
`CalendarLineMotionTest.extrapolating_one_reading_draws_what_reading_the_rules_again_would_until_the_next_boundary`
reads the real rules (`screenBreakPanels` in mode 1 over a plan made once, `clipPlanForPinnedScreenBreak`) at
one instant, extrapolates the reading, and requires it to equal a fresh reading at several instants up to the
boundary the reading itself predicts. If it did not, the calendar would be drawing a motion the rules do not
describe.

**What is still quantized, honestly.** The block a hand is dragging is placed by the hand (its preview overlay
and the held period box keep their pixel geometry). A record whose motion was not read (a boundary inside the
probe) steps per pixel until the next reading. The column-wide hover pickup and the weight handles are
composition geometry, at most one pixel out of step, and invisible. And a hidden kink — a slope change the
derivation makes at an instant that is not one of its own output bounds — would be drawn wrong until the next
reading, bounded by the 30 s ceiling; none is known, and the contract test is where one would show.
