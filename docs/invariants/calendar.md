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
- **EITHER LAYER CAN ALSO BE ASSERTED BY A PERIOD** (`PeriodKinds.NO_COMPUTER_UNLOCKED` /
  `NO_PHONE_UNLOCKED`, `SchedulerDomain.assertedLayerRanges`): the two layers as sentences the USER states
  rather than reads off a lock history. Three rules, and `LayerPeriodKindTest` holds them:
  - **which layers a kind asserts is `PeriodKinds.assertedLayers`, asked nowhere else** — `no on-screen task`
    asserts both, each one-sided kind its own, every other kind none. The hatch, the no-screen intersection
    and the kind's default resilience all read it;
  - **a one-sided period restricts nothing by itself** (default resilience `1`): one locked screen is not "no
    screen". What restricts is the OVERLAP — `assertedNoScreenRanges` intersects the two layers' assertions
    exactly as `observedNoScreenRegions` intersects their evidence — so the two kinds never grow a scheduling
    rule of their own, they feed the one `no on-screen task` already has
    (`impliedNoScreenPeriods` → the fill's `restrictions`/`dynamicBase`, `noScreenRangesFor` → the bank);
  - **the implied period is subtracted where an explicit "No screen" period already covers**, because the plan
    MULTIPLIES every covering kind's resilience (`PeriodKinds.multiplier`): counting the stretch twice would
    square it and halve the share of anybody sitting strictly between 0 and 1. A `0` and a `1` would not have
    noticed.
- **"I'm away" hatches its own device's layer** (`SchedulerEngine.declaredAwaySpans`/`declaredAwaySince` →
  `SchedulerDomain.declaredAwayRegions`, ADR 0002). The machine stays UNLOCKED while the button is on, so the
  OS log is silent over exactly the stretch the now-line is in mode 3 for, and the requirement is that such a
  stretch carries both layers. It rides the **asserted** slot, not the evidence one — the seam filter would
  drop a declaration shorter than a minute, and a failed lock query must not silence the user's own statement
  — and it belongs to the layer of **its own kind**: a press on the computer says nothing about the phone
  (hence `observedNoScreenRegions`' `computerAway`/`phoneAway`). A peer needs no equivalent: its layer is
  already hatched whole ("a device that cannot be asked was locked"), so an away press with every other device
  locked comes out as both layers — which is what makes mode 3 and "a no-screen period" the same set.
- **A declared stretch is drawn DOTTED, and only the LINE changes** (`SchedulerDomain.declaredLayerRegions` →
  `CalendarRecord.layerDeclared` → `obliqueHatch(dotted = …)`). PRD §8: *"the periods where $now line$ mode goes
  to 3, the oblique lines of no computer unlocked are dotted if there was at least one computer unlocked with the
  app having the I'm away button clicked"*, and the same for the phone's slope. A device of that kind really was
  **unlocked** there — that is what the button is for — so the hatch is a claim, not a reading. Three rules
  hold it together, and each has a test in `CalendarLayerTest`: the LOCK EVIDENCE wins where it overlaps (the
  same clipped, seam-filtered evidence `layerRegions` draws — read through the one `layerEvidence` funnel, or a
  sub-minute standby flicker slices a dotted band into hairlines); an ASSERTED region does NOT (a sleep window
  or a break is a promise about every screen and cannot un-unlock the machine the button was pressed on); and
  `null` — the peer's assumed-locked layer — dots nothing. `App.kt` emits one record per stretch of each kind,
  same title and same layer — so the bubble still names the layer ONCE (the time beside it reads the hovered
  piece), the both-layers/no-screen identity is untouched (**the dots are a drawing, not a classification**),
  and the ∞-start is asked of the MERGED regions, so splitting a band can never move it.
- Layers are non-interactive overlays: they displace nothing and register no pointer input. A layer is
  *named* by the hover bubble anyway — its section rides whatever the cursor is over, or the bottom-most
  hover pickup where that is nothing. (An **alarm/timer ring** is inert in the other sense — it registers no
  *click* — but it is opaque, so unlike a layer it carries hover tiles of its own; see below.)
- **The hover bubble is a STACK of sections**, one per thing true at the instant under the cursor, ordered
  `reminder > alarm/timer ring > task = break > inactivity = sleep > no computer unlocked = no phone unlocked`
  (equal ranks are ties, kept in collection order). **When there is a break there can't be a task.** Both rules
  live in `orderedBubbleSections`, applied in the one funnel `Modifier.calendarTitleHover` — never at a call
  site. The **two zero-duration markers lead** it for the same reason they are emitted last: a §14 reminder tag
  and a §18 alarm/timer ring are the top-most things the column draws, so each is what the cursor is on and
  each is what hides everything below it (the tag over the ring, which is the order they are drawn in).
- **The bubble's times are written TO THE SECOND** (`HH:MM:SS`), through the one funnel `bubbleTimeRange` —
  `placedTimeRange`, the block/break/no-screen lines and the phone menu's panel info all read it, and the two
  zero-duration sections (`reminderBubbleSection`, `alarmBubbleSection`) share its `formatHms`. The bubble is
  the one surface that answers *when exactly is this*, and a minute cannot say it: a 20-second look-away
  (§15) truncates to a range whose two ends are equal, and every derived band is cut at the millisecond a
  device locked, so two abutting ones read as overlapping. An "∞" bound is the ABSENCE of a time, so it stays
  a dash-and-∞ at any precision. The **editors keep `formatHm`** — their fields parse `H:mm` and commit on
  the minute — so an edited period's seconds go to `:00`, which is a statement about the edit, not the bubble.
- **AN INERT ELEMENT OWES THE BUBBLE WHAT IT HIDES JUST AS MUCH AS A CLICKABLE ONE**, and it is the case that
  goes unnoticed. A §14 tag is a pointer-input node, so leaving it silent showed at once as a bubble naming
  *nothing*; a §18 ring registers no input at all, so the tiles under it went on reporting and the bubble
  named the task panel the ring was sitting on — right-looking, and never once mentioning the ring the cursor
  was on. That shipped, and it is what `alarmBubbleSection` + `AlarmMarker`'s own `CalendarHoverTiles` fix.
  The test is opacity, not interactivity: if it is drawn over something, it names it.
- **A ring's section names the INSTANT, its tiles ride the DRAWN rectangle** — the same split as the reminder
  tag's, and for the same reason: coinciding rings are pushed downward by the stacking sweep, so a marker can
  sit below its own time. `alarmPlacements` is that sweep, derived **once** and read by both the marker and
  `alarmOverlays`; a second copy is how the bubble starts naming a ring where the calendar does not draw it.
- **Hover is TILED, never nested**: two reporters at one position race (the parent's Move wins). Cut the
  element at every covering section's boundary (`bubbleHoverZones`) and give each tile one reporter.
- **A CURSOR SHAPE rides the hover tile; it is never a lid over it.** A Box carrying only
  `pointerHoverIcon` is still a pointer-input node, so it wins the hit test against the tile underneath and
  that tile stops receiving Enter/Move — the bubble blinks out on exactly the edge the user is aiming at.
  So a resize strip is a **cut of the element's own tiling** (`bubbleHoverZones`' `extraCuts`,
  `CalendarResizeStrip`, `CalendarHoverTiles`), carrying the same sections as the rest of it, never a second
  layer. Three surfaces do this and they are the whole of it: a panel's true **top** and **bottom** grab
  strips (`RESIZE_EDGE_DP` — an interior slice edge is not one, it moves the block) and, where overlapping
  panels **share the column's width**, the boundary between two of them (the Overlap-Mode `WeightHandle`,
  whose two halves each report the neighbour they lie on through the block's own `blockBubbleOverlays`).
- **The strip the cursor promises is the strip the press grabs** — one `edgePx`, read by the gesture and by
  the tiles (`rememberUpdatedState`, because the gesture coroutine outlives a zoom). A cursor over a strip
  that would not start a resize is a lie the user only finds out by pressing.
- **THE SHAPE NAMES THE SIDE, so there are four of them and not two** (`PanelResizeEdge`,
  `panelResizePointerIcon`): a double arrow along the axis the side moves in, with a **line perpendicular
  to it on that side**. The plain OS double arrows cannot say *which* of the two edges on an axis a press
  would take, so the four glyphs are **drawn** on desktop (`PlatformCursor.jvm.kt`, one canonical path
  rotated about the hot spot, built once — this is read from a hover path) and fall back to a crosshair
  everywhere else. The hot spot is the glyph's centre because a grab strip is measured **inwards** from its
  edge, which is what lands the bar on the edge itself. `verticalResizePointerIcon` /
  `horizontalResizePointerIcon` stay the plain OS arrows and are now **only** the window frame's
  (`WindowFrame`), where there is no second panel across the line for a bar to point at.
- **One reading per side, shared by the shape and by the press**: `panelResizeEdgeOf` turns the gesture's
  `CalendarEdge` into the strip's shape, and `weightHandleEdge` turns *which half of a `WeightHandle`* into
  it — the hover tiles split that handle by layout order and the drag splits it by pointer x, and a second
  reading is how one half would start wearing the other's arrow. A handle half lies **over** a neighbour,
  so what it moves is that neighbour's edge facing the boundary: the LEFT half takes the left panel's
  **right** edge.
- **The shape is held for the whole press, not just the hover** (`resizingEdge` in `WeekView`): a drag
  leaves the few-dp strip within the first millimetre, so only an ancestor of every tile can keep showing
  it, and only with `overrideDescendants`. Nothing is added at rest — the state is null and the modifier
  is absent — so this never becomes the lid the rule above forbids.
- **A COLUMN BORDER IS NOT A PANEL SIDE.** `weightHandles` emits a boundary only **between** two panels, so
  a panel at the far left of its day column has no strip on its left: no shape, and no press that could
  resize it from there (the far-right panel likewise, and a lone full-width panel has neither). There is
  nothing on the other side of a column border to take width from, so an edge there could only fight the
  column. This is a property of what `weightHandles` produces, not a guard laid over it —
  `CalendarResizeEdgeTest` is what holds it to that.
- **The drag/resize gesture and the right-click menu are unaffected by all of this**, and that is structural,
  not luck: both live on **ancestors** of the tiles (the block's slice, the day column), and an ancestor stays
  on the hit path of whatever descendant is hit. `calendarTitleHover` never consumes.
- **A REMINDER TAG IS THE TOP-MOST THING THE DAY COLUMN DRAWS**, and that is the same rule as the one above
  read from the other side. It is the one marker on the calendar the user has to be able to **hit**; every
  other element there is decorative (the grey marks, the layers, the now-line, the band labels) or reports
  only hover (a `ScreenBreakBand`'s tiles, an alarm/timer ring's). So the tags are emitted LAST and nothing
  goes after them. Drawn earlier they
  were covered at exactly the position that matters most — the now-line, where the overdue stack accumulates
  and where mode 1 parks an owed pose: an opaque alarm marker hid one, and a `ScreenBreakBand`'s hover tiles,
  being pointer-input nodes, won the hit test against the tag underneath so the click that checks a reminder
  off never reached it. The bubble said so — hovering a tag named the break and the two "nobody unlocked"
  layers instead of the reminder.
- **Being on top is exactly why a tag OWES the bubble what it hides.** The tag is itself a pointer-input node
  — it has to be, it is clicked — so it wins the hit test against every tile beneath it and those tiles stop
  reporting: a hovered tag named *nothing at all*. It therefore carries hover tiles of its own over its own
  drawn rectangle (`ReminderTag` → `CalendarHoverTiles`), with its own section (`reminderBubbleSection`) over
  `underReminderOverlays` — the screen breaks, the `alarmOverlays`, plus the one `underPanelOverlays` list a
  `ScreenBreakBand` reads for the same purpose. **One list, not two readings**, for the same reason
  `blockBubbleOverlays` is shared with the width handle drawn over a block. **Three** elements are drawn over
  the panels and each stacks whatever of the other two is below it: the §18 markers add nothing, a
  `ScreenBreakBand` adds `alarmOverlays`, a tag adds both. Two rules hold it:
  - the **click lives on the ancestor** the tiles hang under, never beside them. A sibling tile layer is the
    "lid over the tile" mistake with the roles swapped — it would eat the one click on the calendar that has
    to land. `Box(clickable) { Row(the chip); CalendarHoverTiles(…) }`.
  - the tag's section names **the time the reminder is FOR**, not where the tag sits — an overdue tag rides
    the now-line and a checked one is frozen at the instant it was ticked off, and neither is the answer to
    "when is this reminder". What it hides, on the other hand, is read at where it is DRAWN (the quantized
    anchor, like every other derivation; only the placement is exact).
- **"NOTHING IS PLACED HERE" IS A STATEMENT ABOUT THE SCHEDULER, AND IT HAS NO PAINT** — it covers an
  inactivity period, a sleep window, the §17 **"Before bed" hour** (`before bed`, whose default resilience is
  `0` like theirs), and **all three screen breaks end to end** (they are `no task allowed`; there is no closed
  head and no hollow tail any more). It is not a screen classification: it refuses off-screen tasks too.
  "Refuses" means the task's resilience to the covering kind is `0`, so a task given a non-zero one may work
  through a break — the only thing that is ever placed there. The calendar draws every one of them the same
  way, as an empty box outlined in the colour of whoever placed it, so the statement is not a kind either: a
  band still carries its own kind and its own name (`decorativeBandLabel` — a derived band names itself where
  it has a name). (Until 2026-09-11 this was GREY, a vertical-line marking painted across the stretch.)
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
- **AN OUTLINE SAYS WHO PUT THE BLOCK THERE, AND ITS COLOUR IS THE WHOLE OF THE ANSWER.** One question,
  `SchedulerDomain.panelOutline`, with four answers — **three ways a block reaches the timeline, plus
  nothing**:
  - **Dynamic → `CalColors.muted` (grey)**, asked FIRST: `screenBreak || conductedBreak`, the three dynamic
    restrictive periods, which the recurrence bars place against the timeline itself. It leads because a break
    the app CONDUCTED is `auto = false` and would otherwise read as something the user drew.
  - **User → `CalColors.accent` (blue).** `isUserPlaced`, which is the complement of what the app lays down
    itself — not `!auto && !chore && !screenBreak && !sleep && …`, which is the same list a fourth time and the
    reason a new family of generated panel would quietly acquire an outline. A panel is the user's exactly when
    it is neither `isRegeneratedPanel` (the fill's picks, the screen breaks, the derived sleep windows, the
    wind-down hours) nor a §14 tag (drawn as a chip, not a panel).
  - **Pattern → `CalColors.pattern` (orange).** What is left over, exactly when it is a restrictive period —
    because the only periods the app lays by itself are the ones a REPEATING rule puts there (the §17 sleep
    windows and the hours measured back from them). Add a fill-laid period family tomorrow and it is orange
    for the same reason these are.
  - **None.** The fill's own task panels, and every derived band — which has no panel behind it to ask about.
  All three outlines are `USER_PLACED_BORDER_DP`, a step thicker than the 1 dp every other block wears: a task
  panel keeps its task's own colour inside, so a colour that only *sometimes* differed from the body would
  answer neither question the panel has to answer.
- **A DERIVED INACTIVITY BAND IS NOTHING BUT ITS TITLE.** No outline (nobody placed it) and no marking of its
  own: the app is not asserting an empty stretch there, it is REPORTING one it derived from the panels around
  it, and the absence of anything drawn is what says so. It is the one thing on the calendar whose whole
  drawing is the label — which is also why the label path is separate from the band path in `DayColumn`, and
  why a band with no outline is skipped outright there rather than drawn empty.
- **NOTHING ON THE CALENDAR WEARS A CHECK BOX.** The pin box is gone (with `SetPanelPinned`, its only intent):
  on a period it could only ever state a fact, and on a task panel it was a second control on a surface whose
  marks are otherwise all read-only. `pins.existence` is unchanged and has two ways in — the edit window and
  the drag/resize gesture below.
- **The two switch chords place a block here like any other hand** (`shortcuts.md`, `scheduler.md`): PRD §7's
  `Ctrl+Shift+Alt+Z` / `+T` lay an **epsilon-long panel at the now-line** on the task the user has just
  decided to do (`SchedulerReducer.placeSwitchEntry`), `auto = false` with the existence pin — so it wears the
  blue outline by the rule above, and the calendar is told **nothing** about chords or pickers. That is the
  test of the rule: a new way of putting a block on the timeline must reach the drawing through
  `panelOutline`, never through a flag of its own.
- **The Existence pin is one field with two ways in — the edit window and the gesture below** (both write
  `pins.existence` through the same `derivePinned`). Unpinning is a **rule change**: `pinned` is in
  `schedulingSignature`, so the watcher is what re-plans (never a dispatch site of its own), and the fill then stops seeing the panel
  — cut where it lies ahead of the line, kept where it has wholly elapsed, truncated AT the line where it
  straddles (`scheduler.md` § *frozen past*).
- **A DRAG OR A RESIZE IS THAT PIN** (`SchedulerDomain.pinsAfterHandPlacement`, applied at the one
  `onCommitBounds`). The gesture says *this occurrence, here*, and a panel the fill may still wipe cannot say
  it: handing the reducer the panel's own (empty) pins made a dragged auto panel user-authored **and
  unpinned**, which is precisely the shape the fill deletes — so the re-plan the edit itself triggers undid
  the drag, silently. The other three pins are untouched: a drag is a statement about existence, not about
  position, span or distance.
- **A hand-drawn period carries `pins.existence` but never `pinned`** (`derivePinned`'s period-aware
  overload), or `isSchedulerFixed` would enter it in the walk's **pre-placed blocks** — a list of blocks owned
  by a task — on top of the period it already is.
- **A RESTRICTIVE PERIOD OF ANY KIND HAS NO FILL AT ALL** — outline, label, nothing else (`CalendarBlockBody`'s
  `period` flag, `isPeriodBlock`). A period does not occupy the timeline the way a task panel does; it states
  something about it, so whatever it covers must read straight through it: a task resilient to its kind working
  through it, the grid, and — for a period that asserts a LAYER — the oblique lines painted over it. Those are
  ASSERTED regions, so `layerRegions` does not clip them to the now-line and both slopes are painted over a
  no-screen period in the future exactly as in the past; a tint under them would be a third statement nothing
  means. **Grey is no longer a period's paint**: it is what the app paints emptiness it DERIVED (see below).
- **NOTHING IS MARKED OR TINTED ANY MORE: `greyPeriodMarks` is gone and no drawing replaced it.** Every
  period is an empty outlined box and every derived band is a label, so there is no longer any wash, hatch or
  line drawn across a stretch to say "nothing is scheduled here" — which is what a task working through a
  period (a resilient task inside a break, the plan projected through a sleep window) kept colliding with. The
  outlines are still drawn **over** the panels, like the layers, so a period that contains a block still reads
  as containing it. Two abutting periods read as two because each closes its own box; two abutting derived
  bands read as one stretch with two labels, which is what they are.
- **A PERIOD AND A TASK PANEL COMPETE EXACTLY WHEN THE PERIOD REFUSES THE TASK** — one question
  (`SchedulerReducer.periodRefuses`: resilience to the kind is `0`), asked in both directions at the one
  `resolveScreenOverrides`. Whichever the user just laid, dragged or resized takes the other. The familiar
  cases fall out of it and are no longer written down separately: a grey period overrides **every** task
  panel it covers, a no-screen period only the on-screen ones (§9 lets an off-screen task run inside one),
  and a task panel overrides both in turn. **A period a task is resilient to leaves that task's panel
  alone** — a period scales a share for as long as it lasts, it does not evict whoever it admits — which is
  the answer the old flag-spelling could not give. The periods it may take are the **user's**
  (`isUserPlaced`): a screen break, a sleep window and a wind-down hour are `no task allowed` too, but a
  hand-placed panel is placed *through* them (§15/§17), never over their corpses.
- **Two overlapping periods of ONE layer-asserting kind are ONE period — their union — and never two blocks
  sharing the column's width.** A period is not an object owning a slice of the timeline the way a task panel is; it is
  the statement *no screen was in use here*, and two overlapping statements of it say one thing. Splitting
  the width is Overlap Mode's answer for panels genuinely competing for the same hours, which these are not:
  the scheduler has always read them merged (`mergeOccupied` in `noScreenRangesFor`) and so has the layer
  assertion, so the display was the only place they were still two. `SchedulerDomain.unifyNoScreenPeriods`
  is the whole rule and it runs **before the trim** in `resolveScreenOverrides` — so the override and the
  record strip above act on the fused span, not on the span the user typed — with no exception list: it
  runs whatever panel changed, and `decode` runs it too so a state an older build wrote is healed rather
  than surfaced. Two periods that only **abut** are left alone (they already draw full-width, and each is
  still an object the menu can remove), and periods of different KINDS never fuse — a no-screen period and an
  inactivity one, or a "no computer unlocked" one and a "no phone unlocked" one, are different statements (the
  last pair's overlap is a no-screen stretch, which is a reading, not a fusion). Within a fused run the survivor is the panel the user is
  holding — it keeps its id, its pins and its weight, and only its bounds grow.
- **A period LAID or DRAGGED over the past clears the work banked under it**, for exactly the tasks it
  **refuses** — the same question the override rule asks, so the on-screen tasks' records go under a
  no-screen period and everybody's under a grey one, as two resiliences rather than as two rules. Same
  funnel as `StripNoScreenRecords` (`stripRecords`, now taking the predicate rather than an `onScreenOnly`
  flag), applied at once rather than at the next engine start; outside Undo/Redo like every write to the
  record. A **dragged** period re-applies it only where the period is the **user's** — a fill-laid break or
  sleep band moving is not the user saying they were not working.
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
- **THE MENU HAS ONE "add…" ENTRY, AND IT OPENS A CHOOSER** (`CalendarAddWindow`) — task panel, restrictive
  period, or reminder. It replaced four entries, two of which ("add a no-screen period", "add an inactivity
  period") named a KIND of period by hand: that is a funnel with an exception list, and the exception was
  visible — `before bed` and every kind the account defines had no way onto the calendar at all, because a
  menu can only list the kinds somebody typed into it. Three rules hold it:
  - **the kind is CHOSEN, off `state.allPeriodKinds`** (`PeriodKindField` — the task cell's categories
    drop-down read for a single value: same rows, same naming field, same "a name the account already holds
    picks THAT one". Defining a kind from here goes through `AddPeriodKind`, the task edit window's own `+`
    intent, never a second one);
  - **the chooser lays nothing.** Each choice opens the editor that already owns that object — the calendar
    edit window, `PeriodEditWindow`, `ReminderEditWindow` — so "nothing is placed until Save" stays one rule
    for all three, and the chooser can never acquire a placement path of its own;
  - **a REMINDER is the third choice because it was the fourth entry.** It is not a panel of any sort (PRD §14:
    a zero-duration tag with an id), so it is a peer of the two panel families here, not a kind of period.
    Reducing four entries to one that could not reach it would simply have lost it.
- **THE PERIOD EDITOR IS ONE WINDOW FOR EVERY KIND** (`PeriodEditWindow`), reached from the chooser and from a
  period's own "Edit" — it never lays a panel directly. It takes the kind as a **name**, not as an enum of the
  two the menu used to offer, which is what made the window itself a place a third kind could not be edited;
  what it *says* about the kind is `periodKindBlurb`, written out of the model (`PeriodKinds.defaultResilience`)
  rather than out of a list of cases. The kind is **shown, not changed**: re-kinding a period is a different
  edit from moving its bounds, and "Remove" plus a fresh add is the one way to say it. Each bound is a
  date+time, **"now"** (resolved at Save), or **"∞"** (`SchedulerDomain.OPEN_PAST_MILLIS` /
  `OPEN_FUTURE_MILLIS` — real 1900/2200 instants, never `Long.MIN_VALUE`: every consumer does plain arithmetic
  on a panel's bounds). A *derived* grey band has no "Edit". The case it exists for: **an inactivity period
  from ∞ to now** empties the recorded past.
- **A PERIOD'S PAINT IS DERIVED FROM ITS KIND, IN ONE PLACE** (`App.kt`'s `calendarRecords`). Both paints are
  now fill-less, and what the two bits still say is whether the period **asserts a layer**
  (`CalendarRecord.noScreen`, read off `PeriodKinds.assertedLayers` — the hatch is painted over it and the
  stretch is not covered past, so a derived Inactivity band may still be drawn under it) or only speaks about
  the timeline (`.inactivity` — it covers the past, so no band is derived under it). Read off the panel's two
  legacy flags instead, a period of any other kind carried neither and was drawn as a **task panel**. `CalendarRecord.restrictiveKind` / `PlacedRecord.restrictiveKind` carry the identity beside the
  paint, and that is what "Edit" hands the editor — a kind with no flag has nothing else to be recognised by.
  The same rule on the other side: the derived §17 wind-down bands are split off **by panel id**
  (`BEFORE_BED_PANEL_ID_PREFIX`), never by kind, or a `before bed` period the user drew would lose its Edit
  and its Remove along with the fill's own.
- Derived "Inactivity" bands are `[displayFloor, now]` minus everything already drawn, except the periods
  that assert a layer and the screen breaks. Display-only, sub-minute remnants dropped, and drawn as a label
  and nothing else.
- **A stretch carrying both layers OVERRIDES the on-screen task panels it covers**
  (`clipPanelsForObservedNoScreen`), because it *is* a `no on-screen task` period — the same rule a hand-drawn
  "No screen" panel follows, and the same set §9 refuses to bank a record over
  (`observedNoScreenRegions`, asked once and read by both). Only the bank half shipped, so the calendar went on
  drawing an on-screen task straight across a machine the OS reported asleep. Off-screen tasks are exempt (§9
  lets them run there), a period is never cut (it is what the cut is made of), and a **failed** own scan is not
  evidence — no regions, no cut. Display-side, like `clipPlanForPinnedScreenBreak`: the regions are the past,
  the fill only places ahead of the now-line, and what the OS reports is not a user edit. What the cut vacates
  is idle time and draws as a derived "Inactivity" band.
- **AN EMPTY PERIOD IS NEVER MANUFACTURED FROM EVIDENCE.** The calendar says "nothing is placed here" for
  exactly three reasons: a covering period every task has 0 resilience to, a period the **user** drew, or past
  beyond the app's memory. "The
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

1. what the user DREW (`SchedulerDomain.assertedNoScreenRanges`: a "No screen" period, or a computer-layer
   period overlapping a phone-layer one), and
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

