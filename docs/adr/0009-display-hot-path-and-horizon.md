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

- The observed now-line is quantized under sim (`DISPLAY_NOW_QUANTUM_MILLIS`, read through a `derivedStateOf`) to
  cap recompute frequency.
- The deeper structural fix is to memoize the fixed-PAST portion with `remember(<real inputs>)` and recompute only
  the live tail.

See the `timesim-large-account-ui-overload` note.

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

## The schedule horizon follows the DISPLAYED DAY SPAN, in both directions

The engine does **not** systematically materialize 168 h.

**There is no "focused week" any more** (2026-08-20): the calendar scrolls through the days ENDLESSLY, so the grid
reports the span its scroll has landed on (`CalendarFloatingWindow(onVisibleDaysChanged = …)` → `App.kt`'s
`visibleFirstDay` / `visibleDayCount` → `visibleSpanStartMillis` / `visibleSpanEndMillis`) and everything below
follows THAT. A day-by-day version of the same rule, not a new one.

`App.kt` publishes it to the engine (`engine.setCalendarHorizon(visibleSpanEndMillis)`, null when the calendar is
closed), the engine feeds the reducer seam `SchedulerReducer.scheduleHorizonEndMillis`, and every §9 refill fills
to `SchedulerDomain.scheduleHorizonEndMillis(now, displayedEnd)` = the displayed span clamped into
**[`MIN_SCHEDULE_HORIZON_MILLIS` = 24 h, `SCHEDULE_HORIZON_MILLIS` = 168 h]`**.

So:

- sitting on today computes ~a week of columns and **no later day**;
- scrolling back into the past falls to the 24 h floor;
- a closed calendar keeps only the 24 h floor the headless notification/cue/deadline paths need;
- **168 h is a ceiling, not a target**.

Everything the fill projects is bounded with it — `fillSchedule` passes its own `horizon` to `screenBreakPanels`
(it used to project a week of break panels whatever horizon it was filling), and `App.kt`'s display sleep
projection uses `max(now + 24h, visibleSpanEnd)`.

### The two engine loops that keep it honest

**`launchHorizonReschedule`** re-evaluates `horizonRefillDueMillis(panels, now, horizonInForce)` **every poll**. An
ABSOLUTE week-end horizon's remaining span shrinks as `now` advances, so a target pinned once would fire early,
refill to no effect, and — `panels` unchanged — park the collector for good.

**`launchCalendarHorizonReschedule`** fires one refill when the scroll **grows** the horizon. A shrink dispatches
nothing: the plan already covers the smaller span.

**Both dispatch `ExtendSchedule`, not `RefreshSchedule`** (ADR 0001 §9): a horizon that grew is not a rule change,
so the plan already on screen is kept and only its tail is materialized.

> **Consequence for the self-retrigger guard.** An extension that cannot close the gap it was triggered by returns
> the state unchanged, so this collector *parks* until something else moves `panels` (the next rule change) instead
> of spinning — the opposite failure from the 2026-07-28 hot loop, and the safe one.

Tests: `ScheduleHorizonTest`, `HorizonRefillRuleTest`.

### Beyond the 168 h ceiling: compute for display, never store

When the scrolled-to span reaches past the ceiling, `App.kt` computes the plan for DISPLAY from the now-line out to
it — `SchedulerDomain.fillSchedule(state, now, horizonMillis = visibleSpanEndMillis)` (the horizon is a parameter
whose default — the 168 h ceiling — is now only for tests and max-span callers).

It runs inside a `LaunchedEffect` keyed **only on the displayed span** (not `nowMillis`, so it doesn't rerun every
tick), on `Dispatchers.Default`. So a distant day "simply takes time to display" behind a **"Calculating…"** header
hint instead of freezing the window.

The result is **never stored in `state.panels`** — only swapped in as the `mergePanelsForDisplay` block source for
that render. So scrolling back to a near day just uses the near panels again and the far fill is dropped; the
"erase far weeks" behaviour falls out for free, with no retained multi-week memory.

The placement itself is O(n) (`simulateScreenBreaks` tracks open pauses instead of scanning all placed panels), and
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
