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

## The schedule horizon is $t_{goal}$, and the DISPLAYED DAY SPAN is one half of it

The engine does **not** systematically materialize 168 h, and since 2026-09-04 it does not follow the displayed
span directly either: it fills to **$t_{goal}$**, the instant `docs/scheduler_requirements.md` §
*Progressive Calculation* lets the scheduler stop at — *"The scheduler can have a time $t goal$ such as when
definitive schedule is found for any t < $t goal$ the scheduler can stop."*

**$t_{goal}$ = `max(` end of the first day that does not appear in the calendar `,` end of the first day of the
week after the current week `)`** (`SchedulerDomain.scheduleGoalEndMillis`).

The calendar half is **one day past the bottom of the grid**. Open the calendar on the current week and scroll
down far enough to see the Monday of the next week, and the goal becomes the end of that next week's Tuesday —
it follows the SCROLL, a day at a time, not the week the scroll happens to have landed in.

**There is no "focused week" any more** (2026-08-20): the calendar scrolls through the days ENDLESSLY, so the grid
reports the span its scroll has landed on (`CalendarFloatingWindow(onVisibleDaysChanged = …)` → `App.kt`'s
`visibleFirstDay` / `visibleDayCount` → `visibleSpanStartMillis` / `visibleSpanEndMillis`) and the calendar half
of the goal is read off THAT — the day after the **last day displayed**. `visibleSpanEndMillis` is exclusive, so
it already *is* the start of the first day that does not appear.

`App.kt` publishes the span to the engine (`engine.setCalendarHorizon(visibleSpanEndMillis)`, null when the
calendar is closed), the engine feeds the reducer seam `SchedulerReducer.scheduleHorizonEndMillis`, and every §9
refill fills to `SchedulerDomain.scheduleHorizonEndMillis(now, displayedEnd, tz)` = the goal, with a
**cap on the CALENDAR half alone** at `now + SCHEDULE_HORIZON_MILLIS` (168 h).

So:

- **the goal is a MAX, so the calendar can only ever push it further out** — scrolling back, or closing the
  calendar, never shortens the plan below what the current week asks for, which is what the headless
  notification/cue/deadline paths read;
- **neither half moves with the CLOCK.** The current week's is an absolute staircase: it holds still for a whole
  week and then steps a week forward, so a schedule that has reached it stays complete instead of falling short
  again on every tick. The rolling `now + 24 h` horizon it replaces was what made the refill trigger fire the
  instant a fill finished, which is the shape `HORIZON_REFILL_MARGIN_MILLIS` exists to damp. The calendar half
  moves only on a scroll, which is an event the grid reports, not a tick;
- **the current week's own goal is NEVER capped** — it is up to eight days out (a Monday's goal is the end of the
  following Monday), and clipping it would leave the headless engine short of the instant the requirement names;
- **168 h is a ceiling on the calendar half, not a target**, and beyond it that week is still computed to the goal
  — for display, off the UI thread (below).

Everything the fill projects is bounded with it — `fillSchedule` passes its own `horizon` to `screenBreakPanels`
(it used to project a week of break panels whatever horizon it was filling). What is *drawn* is a different
question and stays bounded by the visible window (above): `App.kt`'s display sleep projection still uses
`max(now + 24h, visibleSpanEnd)`.

### The two engine loops that keep it honest

**`launchHorizonReschedule`** re-evaluates `horizonRefillDueMillis(panels, now, horizonInForce)` **every poll**. An
ABSOLUTE horizon's remaining span shrinks as `now` advances, so a target pinned once would fire early,
refill to no effect, and — `panels` unchanged — park the collector for good. With the goal it fires when the
staircase steps (once a week) rather than once a day; a scroll is `launchCalendarHorizonReschedule`'s job.

**`launchCalendarHorizonReschedule`** fires one refill when the scroll **grows** the goal — which, the calendar
half being one day past the bottom of the grid, is now a day at a time rather than a week at a time. Scrolling
back dispatches nothing: the goal is a MAX, so it does not shrink at all.

**Both dispatch `ExtendSchedule`, not `RefreshSchedule`** (ADR 0001 §9): a horizon that grew is not a rule change,
so the plan already on screen is kept and only its tail is materialized.

> **Consequence for the self-retrigger guard.** An extension that cannot close the gap it was triggered by returns
> the state unchanged, so this collector *parks* until something else moves `panels` (the next rule change) instead
> of spinning — the opposite failure from the 2026-07-28 hot loop, and the safe one.

Tests: `ScheduleHorizonTest`, `HorizonRefillRuleTest`.

### Beyond the 168 h ceiling: compute for display, never store

When the goal reaches past the ceiling, `App.kt` computes the plan for DISPLAY from the now-line out to
**the goal** — `SchedulerDomain.fillSchedule(state, now, horizonMillis = goalEndMillis)` (the horizon is a
parameter whose default — the 168 h ceiling — is now only for tests and max-span callers). The goal is never
nearer than the displayed span's end, so this reaches at least as far as what is on screen, and the requirement's
$t_{goal}$ is computed whether or not it is allowed into `state.panels`.

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
  rollover, and the $t_{goal}$ staircase that steps with it).
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

**What this does not do.** The pin is re-*derived* at one pixel of resolution, not placed continuously: a
dragged pose's top edge and the growing panel behind it are composition-phase geometry, unlike the now-line
itself. Making them layout-phase too would mean giving the block pipeline (`overlapLayout`, the slices, the
hover tiling, the drag/resize gesture) a layout-phase notion of a block's bounds, which is a much larger
change for a lag that is one pixel by construction. The now-line, which is the thing the eye tracks, is
already exact.
