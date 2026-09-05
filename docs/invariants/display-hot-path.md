# Display hot path

Active invariants. Reasoning and post-mortems: `docs/adr/`. Dated log: `CHANGELOG.md`.
Global rules that always apply: `CLAUDE.md`.

---

### Display hot path

→ ADR 0009.

- **Anything recomputed on every `nowMillis` tick must be bounded by the visible window, never O(total
  history).** Under sim the now-line ticks ~20×/s; an O(history) recompute pegs the UI thread and the window
  is created but never shown — which looks exactly like "the app won't open".
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
    boundary no panel carries (the day rollover and the $t_{goal}$ staircase). Sleep until it.
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
- **Cull the EMISSION, never the list.** `overlapLayout` widths, the reminder/alarm stacking sweeps,
  hit-testing, the contextual menu and the drag snap set all still see the whole day — a partner scrolled out
  of view must still narrow the block on screen. A block mid-gesture is exempt: its slices hold the gesture.
- **Test against a large, realistic DB**, not just an emptied one — an empty account hides the cost entirely.
- **The schedule horizon is $t_{goal}$** (`SchedulerDomain.scheduleGoalEndMillis`): **max(end of the first day
  that does not appear in the calendar; end of the first day of the week after the current week)** —
  `docs/scheduler_requirements.md` § *Progressive Calculation*, the instant the scheduler may stop at. The
  calendar half is **one day past the bottom of the grid**, so it follows the SCROLL and not the week the scroll
  is in: seeing the next week's Monday makes the goal the end of that week's Tuesday. Neither half moves with
  the clock — the current week's is an absolute **staircase** that steps once a week, so a plan that reached it
  stays complete instead of falling short on every tick; the calendar's moves only on a scroll, which is an
  event. It is a **max**, so scrolling back never shortens it and a closed calendar still gets the current
  week's. Only the CALENDAR half is capped (168 h, `scheduleHorizonEndMillis`) to keep a far week out of the
  persisted state — the current week's own goal is up to eight days out and is never clipped. There is no
  "focused week".
- Beyond the ceiling, the far fill runs off the UI thread keyed **only on the span** and is **never stored in
  `state.panels`**.
- Horizon growth dispatches `ExtendSchedule`, not `RefreshSchedule`.
- Day rows are `wrapContentHeight(Alignment.Top, unbounded = true).height(dayHeight)` — both halves
  load-bearing. `requiredHeight` silently centres the row and shows the wrong hours.

---

