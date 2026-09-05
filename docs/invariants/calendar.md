# Calendar

Active invariants. Reasoning and post-mortems: `docs/adr/`. Dated log: `CHANGELOG.md`.
Global rules that always apply: `CLAUDE.md`.

---

## Calendar

→ ADR 0002.

- **Two orthogonal things, and keeping them orthogonal is the point.** The **layers** say who was at a
  screen; **grey** says whether anything is scheduled.
- **A layer is read from the DEVICE'S OS HISTORY** (`deviceLockedIntervals`), never from the app's own
  sessions or from banked panels. Both of those were shipped and both were wrong.
- **`WindowsPowerLog` is the ONLY reading of that history** — the ids, the debounce, the pairing, the query.
  All three `SleepHistory` actuals go through it; a second copy is how the layer and the record bank start
  disagreeing about whether the user was there. Four rules it exists to hold: **a shut-down machine logs no
  sleep event** (so the boot/shutdown ids are in the set, or a power-off overnight reads as time at the
  desk); **an id means nothing without its provider** (`1` is Kernel-Power "resumed" *and* Kernel-General
  "the system time has changed" — each provider is asked for its own ids, and the sets are disjoint); and **a
  flip shorter than a minute is jitter**, cancelling the transition it undid, so the timeline strictly
  alternates — but the cancellation is **provisional**: a later event repeating the state the bounce claimed
  to have returned to proves the return never happened, so the pair is restored and re-tested (two wakes with
  no sleep between them is not something the machine can do; dropping the pair lost a real standby and banked
  records straight through it). Both window edges are handled: an open absence clips to `until`, and the state the window
  *opens* in comes from events fetched BEFORE it. A fourth rule is about the QUERY rather than the
  answer: **the child's stdout is DRAINED while the process runs**, never after it exits — a pipe holds
  4 KB on Windows and a process that fills it blocks until somebody reads, so waiting first deadlocks the
  moment the answer outgrows the buffer, and the timeout that follows is indistinguishable from "the log
  cannot be read". It can only get worse, because the answer grows with the log: the 168 h window crossed
  4 KB on 2026-08-30 and every scan hung for a week, hatching both layers over the whole displayed past
  while `observedNoScreenRegions` stayed empty — so the panels the pairing exists to cut survived.
- **A device that cannot be asked was LOCKED** (`null` ⇒ the layer hatches the whole asked past; an empty
  list ⇒ nothing drawn — the same default as `derivePauses`, and `null` and an empty list stay different
  answers). "Not asked yet" is a third state: the own layer draws nothing until its first scan lands.
- **A stretch carrying BOTH layers is a no-screen period**, identical to the account-wide derived pause.
  `CalendarLayerTest` pins that identity — keep it true.
- **"I'm away" hatches its own device's layer** (`SchedulerEngine.declaredAwaySpans`/`declaredAwaySince` →
  `SchedulerDomain.declaredAwayRegions`, ADR 0002). The machine stays UNLOCKED while the button is on, so the
  OS log is silent over exactly the stretch the now-line is in mode 3 for, and the requirement is that such a
  stretch carries both layers. It rides the **asserted** slot, not the evidence one — the seam filter would
  drop a declaration shorter than a minute, and a failed lock query must not silence the user's own statement
  — and it belongs to the layer of **its own kind**: a press on the computer says nothing about the phone
  (hence `observedNoScreenRegions`' `computerAway`/`phoneAway`). A peer needs no equivalent: its layer is
  already hatched whole ("a device that cannot be asked was locked"), so an away press with every other device
  locked comes out as both layers — which is what makes mode 3 and "a no-screen period" the same set.
- Layers are non-interactive overlays: they displace nothing and register no pointer input. A layer is
  *named* by the hover bubble anyway — its section rides whatever the cursor is over, or the bottom-most
  hover pickup where that is nothing.
- **The hover bubble is a STACK of sections**, one per thing true at the instant under the cursor, ordered
  `task = break > inactivity = sleep > no computer unlocked = no phone unlocked` (equal ranks are ties, kept
  in collection order). **When there is a break there can't be a task.** Both rules live in
  `orderedBubbleSections`, applied in the one funnel `Modifier.calendarTitleHover` — never at a call site.
- **Hover is TILED, never nested**: two reporters at one position race (the parent's Move wins). Cut the
  element at every covering section's boundary (`bubbleHoverZones`) and give each tile one reporter.
- **A CURSOR SHAPE rides the hover tile; it is never a lid over it.** A Box carrying only
  `pointerHoverIcon` is still a pointer-input node, so it wins the hit test against the tile underneath and
  that tile stops receiving Enter/Move — the bubble blinks out on exactly the edge the user is aiming at.
  So a resize strip is a **cut of the element's own tiling** (`bubbleHoverZones`' `extraCuts`,
  `CalendarHoverTiles`), carrying the same sections as the rest of it, never a second layer. Three surfaces
  do this and they are the whole of it: a panel's true **top** and **bottom** grab strips (the vertical
  resize cursor, `RESIZE_EDGE_DP` — an interior slice edge is not one, it moves the block) and, where
  overlapping panels **share the column's width**, the boundary between two of them (the horizontal resize
  cursor, the Overlap-Mode `WeightHandle`, whose two halves each report the neighbour they lie on through
  the block's own `blockBubbleOverlays`).
- **The strip the cursor promises is the strip the press grabs** — one `edgePx`, read by the gesture and by
  the tiles (`rememberUpdatedState`, because the gesture coroutine outlives a zoom). A cursor over a strip
  that would not start a resize is a lie the user only finds out by pressing.
- **The drag/resize gesture and the right-click menu are unaffected by all of this**, and that is structural,
  not luck: both live on **ancestors** of the tiles (the block's slice, the day column), and an ancestor stays
  on the hit path of whatever descendant is hit. `calendarTitleHover` never consumes.
- **A REMINDER TAG IS THE TOP-MOST THING THE DAY COLUMN DRAWS**, and that is the same rule as the one above
  read from the other side. It is the one marker on the calendar the user has to be able to **hit**; every
  other element there is decorative (the grey marks, the layers, the now-line, the band labels, an alarm
  ring) or reports only hover. So the tags are emitted LAST and nothing goes after them. Drawn earlier they
  were covered at exactly the position that matters most — the now-line, where the overdue stack accumulates
  and where mode 1 parks an owed pose: an opaque alarm marker hid one, and a `ScreenBreakBand`'s hover tiles,
  being pointer-input nodes, won the hit test against the tag underneath so the click that checks a reminder
  off never reached it. The bubble said so — hovering a tag named the break and the two "nobody unlocked"
  layers instead of the reminder.
- **GREY = the scheduler places nothing here** — inactivity period, sleep window, the §17 **"Before bed"
  hour** (`before bed`, whose default resilience is `0` like theirs), and **all three screen
  breaks end to end** (they are `no task allowed`; there is no closed head and no hollow tail any more). It is
  not a screen classification: it refuses off-screen tasks too. "Refuses" means the task's resilience to the
  covering kind is `0`, so a task given a non-zero one may work through a break — the only thing that is ever
  placed there. Grey is what the calendar PAINTS "nothing is placed here" with; it is not a kind, and a band
  that is grey still carries its own kind and its own name (`decorativeBandLabel` — a derived band names
  itself where it has a name).
- **A band spans its TRUE duration and is NEVER stretched to hold its own name.** A break drawn taller than it
  lasts covers the task panel it abuts, which reads on the calendar as a task running through the break. So the
  band's floor is a hairline (`SCREEN_BREAK_MIN_HEIGHT`) and the NAME is what gives way: it is drawn only where
  the rendered band is at least one label line tall (`SCREEN_BREAK_LABEL_MIN_HEIGHT`). Which of the three a band
  is, is the only thing its name says, and the hover bubble still says it at any height the cursor can reach.
- **No two texts share a point: the PANEL's label is what gives way to the DAY'S DATE.** Every day boundary
  scrolled into the grid is named by its own badge ("Sat 30"), so a panel opening at midnight would write its
  label into that same corner. `panelLabelTopInset` is the one answer, and it is the band rule above by
  another route: the badge is never moved and no panel is ever stretched — a panel starting within
  `DAY_DATE_BADGE_HEIGHT` of midnight writes its label BELOW the badge where it has a whole label line of
  room there, and writes none where it has not, the zoom being what brings a short one back. It is applied
  by the grey bands, the screen-break bands and the task panels alike; the grid's TOP row passes
  `showsDayDate = false`, its date being written in the header above the viewport.
- **The ZOOM is the other half of that rule.** A 20-s look-away is 0.27 dp tall at zoom 1f, so the in-bound
  (`MAX_CALENDAR_ZOOM`) must be high enough to bring the shortest of the three over a label line and under a
  cursor — that is what the ceiling is for, and it is why the band may be left un-named at an ordinary zoom.
  The effective cap is `maxCalendarZoom(dayHeightPxAtZoom1)`, which lowers it on a display where a whole day row
  would exceed what a Compose constraint can represent; **every** zoom path clamps through it, the fits
  (`calendarSpanZoom` / `wholeDayZoom`) included.
- **Everything the grid places carries the FULL INSTANT, the NOW-LINE included** (`LocalTime.hourOfDayExact`,
  read by `recordsForDay`). The zoom ceiling is what makes it load-bearing: where a minute is hundreds of
  pixels, reading a time to the minute does not round it, it MOVES it by up to 59 s. The indicator was drawn
  at `hour + minute / 60` and so sat at the top of the current minute, which put the line on the wrong side of
  every band the calendar had placed truthfully — a layer region ending at `now` read as a claim about the
  future, a grey band ending before `now` as scheduled emptiness after it, and the elapsed half of the panel
  the line sits in as entirely unelapsed. **The SECOND is that same mistake one decade down, and it bites the
  bands that FOLLOW the line**: the pose the line drags is `(t_p, t_p + d]`, so its top edge is the line, and
  floored to the second it lurched ~1.7 dp at the ceiling however finely the display resampled. `Float` is
  still fine for a block (its own step around hour 24 is ~7 ms ≈ 0.01 dp there); only the line needs `Double`.
  The lock's centring fraction and the reminder stack's anchor read the same instant, or the line is not the
  one on screen.
- **The NOW-LINE ITSELF goes two steps further: it is placed below the second AND between pixels, in the DRAW
  phase, off its own frame sampler** (`hourOfDayExact`, `nowLineOffsetPx`, `rememberNowLineHour`) —
  `docs/scheduler_requirements.md` § *$now line$*: *"the $now line$ moves continuously forward in time"*. The
  split is the whole of it, and it is the same one ADR 0009 makes everywhere else: **what is DERIVED from the
  clock** (every band, panel, projection and cull) is a function of the app's **quantized** display instant,
  because each new value re-runs an O(visible window) pass; **the line** is one number, so it is sampled on
  the frame clock and read back from a `Modifier.graphicsLayer { translationY = … }`, which re-draws it every
  frame while recomposing, re-measuring and re-placing nothing. **`translationY` is a `Float` and that is the
  point** — `offset { IntOffset(…) }` snapped the line to the pixel grid, and a value that glides rounded to
  the grid does not glide: it holds still and jumps a whole pixel (~75 s of travel at zoom 1, ~0.5 s at the
  ceiling). One pixel is not imperceptible when it is the only thing moving. Sub-pixel placement hands the
  crossing to Skia's anti-aliasing, which is the whole of what continuous motion means on a discrete grid.
  The sampler runs **only while the line is on screen**, so a column that is not today's, a grid scrolled to
  another week and a closed calendar all ask for no frames at all. The overdue reminder stack rides the same
  state — and the same fractional placement — or it is not on the line the user sees.
- **All three are MARKED one way: vertical lines, delimited** (`greyPeriodMarks`, the one place a grey period
  becomes something to paint). A screen break is drawn exactly like the inactivity band beside it — no blue
  outline, no `●`, no accent title: they are the same kind of period. **Lines, never a fill**, because a grey
  period may legitimately hold a task panel (§17 projects the plan through a sleep window; a resilient task
  works through a break) and a wash repaints it — which is why the marking is drawn **over** the panels, like
  the layers, and why `CalendarBlock` has no grey tint of its own. **Delimited** = an edge line top and bottom,
  so an inactivity period abutting a sleep window still reads as two periods and not one stretch.
- **Grey refuses everybody on the calendar too, not only in the fill.** A hand-added inactivity period
  overrides **every** task panel it covers (a no-screen period only the on-screen ones — §9 lets an
  off-screen task run inside one), and any task panel overrides it in turn.
- **A period LAID or DRAGGED over the past clears the work banked under it** — the on-screen tasks' records
  for a no-screen period, everybody's for a grey one. Same rule as `StripNoScreenRecords` (`stripRecords`,
  `onScreenOnly`), applied at once rather than at the next engine start; outside Undo/Redo like every write
  to the record.
- **A task panel's menu reaches the TASK as well as the panel.** "Edit" is the panel (this occurrence's
  bounds and pins); **"edit task"** opens the §13 window and **"go to task tree"** selects the task's first
  cell. Both are offered on a task panel only — a period, a reminder, an alarm, a sleep band, a screen break
  and a layer region are not tasks. Two things they must not become: **"edit task" is the tree cell menu's
  own entry, under its own name** — one window for the task, so the tree's entry was renamed "edit" → "edit
  task" rather than the calendar inventing a second name for it; and **"go to task tree" goes through
  `RevealCell`**, the find bar's primitive (expand the way in as ONE unit, then select), never a fresh
  selection path.
- **`firstTaskOccurrence` is where "the first occurrence" is decided**, and `null` is a real answer, not an
  error path — a panel outlives the cell that laid it (panels are not per-tree), so it may name a detached
  parent, a task §4's blank title deleted, or a task another tree owns. The walk is `TaskTreeSearch.matches`'
  — depth-first, **each LIST visited once** (a mirrored sub-tree is one list under many parents) — and it
  skips a blank-titled cell entirely: that cell is the deleted one, and the reveal could not expand it
  anyway. The one place that says "not in the task tree" is the handler, once, for every one of those cases.
- **Both "add a … period" entries open the PERIOD EDITOR** (`PeriodEditWindow`, one window for both kinds) —
  they never lay a panel directly. Each bound is a date+time, **"now"** (resolved at Save), or **"∞"**
  (`SchedulerDomain.OPEN_PAST_MILLIS` / `OPEN_FUTURE_MILLIS` — real 1900/2200 instants, never
  `Long.MIN_VALUE`: every consumer does plain arithmetic on a panel's bounds). It is also a period's "Edit";
  a *derived* grey band has none. The case it exists for: **an inactivity period from ∞ to now** empties the
  recorded past.
- Derived grey bands are `[displayFloor, now]` minus everything already drawn, except no-screen periods and
  screen breaks. Display-only, sub-minute remnants dropped.
- **A stretch carrying both layers OVERRIDES the on-screen task panels it covers**
  (`clipPanelsForObservedNoScreen`), because it *is* a `no on-screen task` period — the same rule a hand-drawn
  "No screen" panel follows, and the same set §9 refuses to bank a record over
  (`observedNoScreenRegions`, asked once and read by both). Only the bank half shipped, so the calendar went on
  drawing an on-screen task straight across a machine the OS reported asleep. Off-screen tasks are exempt (§9
  lets them run there), a period is never cut (it is what the cut is made of), and a **failed** own scan is not
  evidence — no regions, no cut. Display-side, like `clipPlanForPinnedScreenBreak`: the regions are the past,
  the fill only places ahead of the now-line, and what the OS reports is not a user edit. What the cut vacates
  is idle time and draws as a derived "Inactivity" band.
- **A grey period is NEVER manufactured from evidence.** Grey appears for exactly three reasons: a covering
  period every task has 0 resilience to, a period the **user** drew, or past beyond the app's memory. "The
  devices observed no screen here" is none of them — it is evidence, recomputed every scan, and it stays
  DERIVED. `materializePastInactivity` wrote it into `state.panels` as a real `no task allowed` period and is
  gone (ADR 0002): it persisted and synced an observation, it grew without bound (218 panels on the release
  account — a fresh batch per `account3-deploy-windows.bat`, which stops the app for the whole build so
  nothing is banked), and it silently upgraded a `no on-screen task` observation into a period refusing the
  off-screen tasks too. What a strip vacates is idle time the calendar already derives a band for, so the
  deletion changed no pixel. `materializePastSleep` is the deliberate counter-case: a sleep session is a fact
  the **user** asserted with the Sleep/Work toggle, not something a scan observed.
- **Known inconsistency:** layers **and the §9 record bank** read the OS lock history; the engine's pause
  derivation still reads `device_active_session`. Decide this before adding anything else that reads one and
  not the other.

### What may be banked as a record

→ ADR 0002. **An on-screen task banks NO record over a no-screen period**, and "no-screen period" has two
sources that are UNIONED, never one or the other:

1. the user's hand-drawn "No screen" panels (`AddNoScreenPeriod` — the only producer of `noScreen = true`), and
2. **what the devices observed** — both layers' OS lock/standby evidence intersected
   (`SchedulerDomain.observedNoScreenRegions`), injected by the engine through `SchedulerReducer.noScreenEvidence`.

Source 2 exists because source 1 alone is silent on any account where the user never drew a panel — which let
43 h of "work" bank over a machine the OS reported asleep (account 3, 2026-08-24). Do not narrow the guard back
to panels.

- **An off-screen task is exempt**: §9 lets it run in a no-screen period, so its record over one is true.
- **A failed lock query is NOT evidence.** `null` means "assumed locked throughout" — right for the calendar,
  catastrophic for the bank, where one timeout would suppress every record. The OWN scan must SUCCEED to say
  anything; a PEER's null keeps its assumed-locked meaning.
- **The asserted regions are deliberately NOT evidence.** A screen break suspends a chunk rather than cutting
  it (§15), so folding breaks/sleep windows in would stop recording across every break. **The "I'm away"
  stretches are the one exception** (`computerAway`/`phoneAway`): a break is not time the user was absent
  for, a declared absence is — and it is the same statement the scan is trying to make, from the user
  instead of the OS.
- **The scan never runs on the engine's dispatcher** (ADR 0009): it is a process launch with a 20 s timeout,
  and inline it stalls the advance tick and every sweep behind it. 10-minute bucket, bounded 24 h window.
- `StripNoScreenRecords` applies the same rule retroactively, once at engine start. Idempotent, and unlike the
  tick it **syncs** — `Task.record` is authoritative and the merge UNIONS it, so a local-only deletion would be
  resurrected by a peer.

