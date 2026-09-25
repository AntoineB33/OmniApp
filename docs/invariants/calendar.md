# Calendar

Active invariants. Reasoning and post-mortems: `docs/adr/`. Dated log: `CHANGELOG.md`.
Global rules that always apply: `CLAUDE.md`.

---

## Calendar

→ ADR 0002.

- **Two orthogonal things, and keeping them orthogonal is the point.** The **layers** say who was at a
  screen; the **restrictive periods** say what may be placed. Both are markings drawn over the timeline
  without occupying it, and the marking is what names the statement: each kind's own drawing (by default `/`
  no computer unlocked, `\` no phone unlocked, `|` inactivity).
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
- **A "NO SCREEN" PERIOD SAYS NOTHING ABOUT DEVICES** (user rule, 2026-09-19): it only refuses the tasks
  with a resilience of `0` to it. It does not mean "no computer unlocked" or "no phone unlocked", asserts no
  layer (unless the account makes them its companions), and is never edited together with a layer period.
- **A stretch carrying BOTH layers is a no-screen period** — ONE-WAY: nobody at any device means no on-screen
  work can happen there, so the overlap counts as no-screen time; the converse is false (above). Identical to
  the account-wide derived pause.
  `CalendarLayerTest` pins that identity — keep it true.
- **EITHER LAYER CAN ALSO BE ASSERTED BY A PERIOD** (`PeriodKinds.NO_COMPUTER_UNLOCKED` /
  `NO_PHONE_UNLOCKED`, `SchedulerDomain.assertedLayerRanges`): the two layers as sentences the USER states
  rather than reads off a lock history. Three rules, and `LayerPeriodKindTest` holds them:
  - **which layers a kind asserts is `PeriodKindConfig.assertedLayers`, asked nowhere else** — each one-sided
    kind its own, and any other kind only the layer kinds among its COMPANIONS (see *Period companions and
    drawings* below). By default no other kind asserts a layer — `no screen` included (user rule,
    2026-09-18). The hatch and the no-screen intersection read it; the kind's default resilience reads
    `PeriodKinds.isLayerKind` (by name);
  - **a one-sided period restricts nothing by itself** (default resilience `1`): one locked screen is not "no
    screen". What restricts is the OVERLAP — `assertedNoScreenRanges` intersects the two layers' assertions
    exactly as `observedNoScreenRegions` intersects their evidence — so the two kinds never grow a scheduling
    rule of their own, they feed the one `no on-screen task` already has
    (`SchedulerDomain.companionPeriods` → the fill's `restrictions`/`dynamicBase`, `noScreenRangesFor` → the
    bank). This direction (both layers ⇒ no screen) is the layers' definition and is **not** a companion
    setting: no account setting turns it off;
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
- **AN AWAY EPISODE OUTLIVES THE PROCESS; THE BUTTON DOES NOT** (`device_away_span`, schema v13,
  `DeclaredAwayStore`). The two halves are deliberately opposite. The *flag* is a live declaration and a
  restart rightly drops it: only a lock→unlock edge clears it, so an app that re-asserted it on an unlocked
  machine would claim an absence it can never see the end of. The *episodes* are recorded FACTS and a restart
  must not drop them, because nothing can re-supply one — the machine stays unlocked throughout, which is
  the whole point of the button. They were memory-only until 2026-09-12, when a redeploy erased a 17-minute
  declared-away spell from account 3's calendar the instant it restarted the app: the layer above, the hatch
  built out of it and the §9 record bank all went quiet over a stretch `t_p` had just been in mode 3 for. The
  server's `away_spans` is not a substitute — it is the ACCOUNT's record, it is best-effort (it had been
  504-ing all session), and nothing on the display path reads it back. One row per episode keyed by its
  START, extended in place by the 30-s active-session beat and never by a timer of its own, so a kill
  mid-away lands the episode closed at its last beat exactly as a live `device_active_session` row does.
  LOCAL-ONLY, pruned to the same 24 h window the no-screen evidence answers over.
- **A HATCH THE LOCK LOG CONTRADICTS IS DOTTED — WHOEVER SAID IT**
  (`SchedulerDomain.declaredLayerRegions` -> `CalendarRecord.layerDeclared` -> `periodDrawing(dotted = …)`).
  A hatch says *no device of this kind was unlocked*; the dots say that sentence is the USER'S WORD against
  the machine's. Two things state it where the OS log disagrees, and they are **one rule**, in one funnel:
  - the **"I'm away" button** (*"the oblique lines must be dotted if at least one of the corresponding
    devices was unlocked but the I'm away button was clicked"*) — the machine stays unlocked while the
    button is on, which is the whole reason the button exists;
  - a **period the user DREW** asserting the layer over hours already elapsed (*"when the user adds a no
    screen period on a past time period where some computers were unlocked, the oblique lines for the no
    computer unlocked restrictive period must be dotted there"*) — `assertedLayerRanges`, the same value the
    hatch itself is built from, so there is no second reading of which periods assert what.
  - The answer is **the declaration MINUS this kind's lock evidence, intersected with the band drawn**. The
    evidence wins where it overlaps (the button survives a lock, a drawn period covers hours the machine
    really did sleep through, and over that slice nothing was unlocked, so the hatch is a reading again) and
    it is read through the same `layerEvidence` funnel `layerRegions`
    draws from, seam filter included — rebuilt beside it, a standby flicker too short to hatch would still
    slice a dotted band into hairlines. An **asserted** region does not win: a sleep window or a screen
    break is a promise about every screen, and a promise cannot un-unlock the machine. A `null` lock history
    ("cannot be asked", hence assumed locked) dots nothing, which is every PEER layer.
  - **Only the OBSERVED window can contradict anything**, so a declaration is clipped to
    `[since, until]` — the caller's now-line — before the evidence is subtracted. A period drawn over the
    FUTURE is not yet a claim about an observation and draws solid; one straddling the line dots only its
    elapsed half. Without that clip every projected hatch ahead of the line would dot, since there is no
    evidence out there to contradict.
  - The app's **own** promises are not declarations: a projected sleep window and a screen break are
    nobody's statement about what happened, and each already wears the outline (orange, grey) that says who
    laid it. `App.kt` hands over the away spells and the drawn periods, and nothing else.
  - **Only the LINE changes** — same slope, same spacing, same colour, same span, same bubble section. The
    both-layers identity is untouched: the dots are a drawing, not a classification.
  - **THREE MARKS, THREE QUESTIONS, AND THAT IS WHY DELETING THE DOTS DID NOT STICK.** They were removed on
    2026-09-12 under *one drawing per statement* — "a stretch a hand states is a restrictive period, and a
    period is outlined in the accent blue" — and restored the same day. The hatch says **what is claimed**
    (nobody of this kind was unlocked), the blue outline says **a hand placed this**, the dots say **the
    machine's log disagrees**. They are independent: a declared-away stretch has dots and no outline (the
    button lays no period to outline); a no-screen period over a locked night has an outline and no dots; one
    over an evening at the keyboard has both.
  - What the removal got right and the dots do not undo: the REGION is unchanged. A declaration still rides
    the **asserted** slot (so the sub-minute seam filter can never drop one) and still merges with the
    evidence beside it into one region — the dots split the region's DRAWING, which is why the `∞` marker is
    asked of the merged list. `CalendarLayerTest` pins both halves.
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
- **A period's COMPANIONS are sections of the bubble too**, over the period's own span, read from the same
  `PeriodKindConfig` the box's drawings are (`companionBubbleSections`) — so a sleep window names its
  "No screen", as does the wind-down hour. The layer kinds are left to the layer band. Until 2026-09-19 the
  sleep band's "No screen" line hung on pause *evidence* enclosing it (an inactivity gap passed by position
  into the wrong parameter), so every night without one, all the future ones included, named only "Sleep".
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
- **THE TWO ZERO-DURATION MARKERS ARE THE TOP-MOST THINGS THE DAY COLUMN DRAWS** — a §18 alarm/timer ring,
  and a §14 reminder tag over it — and that is the same rule as the one above read from the other side. Each
  is a thing the user must reach at an INSTANT: a tag is the one marker on the calendar that has to be
  **hit**, a ring is opaque and names a boundary. Everything else there is decorative (the grey marks, the
  layers, the now-line, the band labels) or reports only hover (a `ScreenBreakBand`'s tiles). So the bands
  and the band labels are emitted first, then the rings, then the tags, and nothing goes after them.
  Drawn earlier, a tag
  was covered at exactly the position that matters most — the now-line, where the overdue stack accumulates
  and where mode 1 parks an owed pose: an opaque alarm marker hid one, and a `ScreenBreakBand`'s hover tiles,
  being pointer-input nodes, won the hit test against the tag underneath so the click that checks a reminder
  off never reached it. The bubble said so — hovering a tag named the break and the two "nobody unlocked"
  layers instead of the reminder.
- **A FIX THAT LIFTS ONE OF THE TWO LEAVES THE OTHER UNDER THE BANDS, AND ONLY THE PAINT SAYS SO.** The
  2026-09-04 fix above moved the tags and left the rings where they were, below the §15 bands, so a 20-s
  look-away was painted straight across a ring. It took eight days to see, and the reason is that a ring is
  **inert**: there was no click to lose the way a tag's was, and `CalendarBubbleSection.Kind` went on ranking
  a ring ABOVE a break the whole time, so the bubble kept insisting the ring was on top. It shows at **zoom
  out**, not in: a marker is a fixed `ALARM_MARKER_HEIGHT` whatever the zoom, so the further out the calendar
  is, the more minutes its rectangle covers and the more certain a look-away is to land inside it.
  **`CalendarOverlayLayer` is the single answer now** — the bottom-to-top order of the three things drawn over
  the panels, read BOTH by the emission order and by `overlaysUnder`, which is what each of them stacks under
  its own section. Neither list is written out at a call site any more, and `CalendarOverlayLayerTest` pins
  that a layer carries exactly the layers below it — never itself, never one above it.
- **Being on top is exactly why a tag OWES the bubble what it hides.** The tag is itself a pointer-input node
  — it has to be, it is clicked — so it wins the hit test against every tile beneath it and those tiles stop
  reporting: a hovered tag named *nothing at all*. It therefore carries hover tiles of its own over its own
  drawn rectangle (`ReminderTag` → `CalendarHoverTiles`), with its own section (`reminderBubbleSection`) over
  `underReminderOverlays` — the rings, the screen breaks, plus the one `underPanelOverlays` list a
  `ScreenBreakBand` reads for the same purpose. **One list, not two readings**, for the same reason
  `blockBubbleOverlays` is shared with the width handle drawn over a block. **Three** elements are drawn over
  the panels and each stacks whatever of the other two is below it — a band adds nothing, a ring adds the
  bands, a tag adds both — and that is `overlaysUnder(CalendarOverlayLayer)`, not three lists spelled out at
  three emissions. Two rules hold it:
  - the **click lives on the ancestor** the tiles hang under, never beside them. A sibling tile layer is the
    "lid over the tile" mistake with the roles swapped — it would eat the one click on the calendar that has
    to land. `Box(clickable) { Row(the chip); CalendarHoverTiles(…) }`.
  - the tag's section names **the time the reminder is FOR**, not where the tag sits — an overdue tag rides
    the now-line and a checked one is frozen at the instant it was ticked off, and neither is the answer to
    "when is this reminder". What it hides, on the other hand, is read at where it is DRAWN (the quantized
    anchor, like every other derivation; only the placement is exact).
- **The tag is also what the app ANNOUNCES from.** A reminder alerting at its moment (PRD §11/§14) keys on
  the instant this tag is drawn for, through the cue sweep — never on a second reading of the recurrence — so
  the app cannot say a reminder is due at a moment the calendar does not draw it at. A **checked** tag is a
  completion and is never announced. `docs/invariants/alarms-and-timers.md` § *A reminder is announced by the
  SWEEP, never armed*.
- **"NOTHING IS PLACED HERE" IS A STATEMENT ABOUT THE SCHEDULER, AND IT HAS NO PAINT** — it covers an
  inactivity period, a sleep window, the §17 **"Before bed" hour** (`before bed`, whose default resilience is
  `0` like theirs — and which by default carries a `no screen` companion, as **a sleep window does too**, so
  the no-screen drawing is painted over the orange boxes of each), and **all three screen breaks end to end** (they are `no task allowed`; there is no closed
  head and no hollow tail any more). It is not a screen classification: it refuses off-screen tasks too.
  "Refuses" means the task's resilience to the covering kind is `0`, so a task given a non-zero one may work
  through a break — the only thing that is ever placed there. The calendar draws every one of them the same
  way — see the period-box rules below — so the statement is not a kind either: a band still carries its own
  kind and its own name (`decorativeBandLabel` — a derived band names itself where it has a name). **Every
  kind is MARKED by its own drawing** (see *Period companions and drawings*): a family whose whole job is to
  say what kinds of statement cover a stretch without occupying it. (Grey was a WASH until 2026-09-11, and the
  wash is what collided with every task drawn through a period. A marking made of lines does not.)
- **PERIOD COMPANIONS AND DRAWINGS** (the period edit window, user rule 2026-09-18;
  `PeriodKindStyle`/`PeriodKindConfig`, stored as `SchedulerState.periodKindStyles` — overrides only, one sync
  row per kind, not an Undo/Redo unit, like defining a kind).
  - **Companions**: *"a set of periods that are always present when this period is present"*. TRANSITIVE
    (`PeriodKindConfig.kindsOf`; a cycle is harmless). An implication, never a laid panel. Every reader asks
    the config: the scheduler (`SchedulerDomain.companionPeriods`, via `restrictivePeriodsOf` and the fill),
    the mode-1 retraction, the layers a period hatches (`assertedLayers`), the record bank
    (`assertedNoScreenRanges`) and the calendar's drawings. **Defaults** (`PeriodKinds.defaultStyle`): `sleep`
    and `before bed` carry `no screen`; `inactivity` carries **no** `no screen`; `no screen` carries **neither**
    `no computer unlocked` nor `no phone unlocked`; everything else carries nothing. Companion changes are in
    `schedulingSignature`; drawing changes are not (paint).
  - **Drawings** (`PeriodDrawing`, rendered only by `ui/PeriodDrawings.kt`'s `Modifier.periodDrawing`): a
    CLOSED set of line patterns that differ by geometry alone (same colour, stroke and 35 % alpha), so any
    number overlap legibly. Defaults are pairwise distinct across the built-ins: `|` inactivity, `—` sleep,
    `/` no computer unlocked, `\` no phone unlocked, `(` no screen, zig-zags before bed. A kind the account
    adds is given the least-worn drawing AT CREATION and stores it — never derived from the list position, or
    deleting a kind would repaint the others. Drawn as a repeated TILE, one rect per box, so the cost does not
    grow with the box's height (a night at the zoom ceiling is ~150 000 px).
  - **Who paints what**: a period box (and the sleep band) paints its kind's drawing and every companion's
    (`PeriodKindConfig.boxDrawings`) **except the two layer kinds**, whose drawing is painted by the layer band
    — the band unions a period's assertion with the OS evidence, so painting it on the box too would draw one
    statement twice. The layer bands read their drawing from the config as well (`LocalPeriodKindConfig`).
  - The §17 windows and the screen breaks assert a layer on the calendar **only if their kind carries it**
    (App.kt's per-layer `layerAssertedAll`). By default neither does.
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
  The sampler runs **only while something that moves is on screen**, so a grid scrolled to another week and a
  closed calendar ask for no frames at all. The overdue reminder stack rides the same state — and the same
  fractional placement — or it is not on the line the user sees.
- **AND EVERYTHING THE LINE DRAGS GLIDES WITH IT** (ADR 0009, 2026-09-17; `display-hot-path.md`). A pose the
  line drags in mode 1 is `(t_p, t_p + d]` — its top edge IS the line — so a band placed one pixel at a time
  beside a line placed between pixels draws the pose behind the line pushing it, which the rules never say.
  Every edge the reading of the rules marks as following the line (`withLineMotion`) is placed by
  `timelineSpan` from the same frame clock as the line, and every block, band, period box and label goes
  through that one placement.
- **AN OUTLINE SAYS WHICH SURFACE THE USER SAID IT ON, AND ITS COLOUR IS THE WHOLE OF THE ANSWER.** The
  user's words: *"things placed by rules defined in windows accessible via the left side menu of the app
  (Sleep schedule, Alarm) are outlined in ORANGE; the ones that are placed or moved through a right-click
  menu in the calendar are outlined in BLUE."* **Both are the user's hand**, which is why the question is
  not *who* — reading it as "the user placed it" is what put every daily alarm in blue (2026-09-12). One
  question, `SchedulerDomain.panelOutline`, with four answers — **three ways a block reaches the timeline,
  plus nothing**:
  - **Dynamic → `CalColors.muted` (grey)**, asked FIRST: `screenBreak || conductedBreak`, the three dynamic
    restrictive periods, which the recurrence bars place against the timeline itself. It leads because a break
    the app CONDUCTED is `auto = false` and would otherwise read as something the user drew. They are the one
    family stated on NEITHER surface — the README's bars place them.
  - **User → `CalColors.accent` (blue): stated ON THE CALENDAR.** `isUserPlaced`, which is the complement of
    what the app lays down
    itself — not `!auto && !chore && !screenBreak && !sleep && …`, which is the same list a fourth time and the
    reason a new family of generated panel would quietly acquire an outline. A panel is the user's exactly when
    it is neither `isRegeneratedPanel` (the fill's picks, the screen breaks, the derived sleep windows, the
    wind-down hours) nor a §14 tag (drawn as a chip, not a panel — its own answer below).
  - **Pattern → `CalColors.pattern` (orange): stated in a WINDOW OFF THE LEFT MENU**, as a rule the app then
    applies wherever it falls. What is left over, exactly when it is a restrictive period —
    because the only periods the app lays by itself are the ones a REPEATING rule puts there (the §17 sleep
    windows and the hours measured back from them). Add a fill-laid period family tomorrow and it is orange
    for the same reason these are.
  - **None.** The fill's own task panels, and every derived band — which has no panel behind it to ask about.
  All three outlines are `USER_PLACED_BORDER_DP`, a step thicker than the 1 dp every other block wears: a task
  panel keeps its task's own colour inside, so a colour that only *sometimes* differed from the body would
  answer neither question the panel has to answer.
- **THE TWO ZERO-DURATION FAMILIES ARE OUTLINED TOO, AND THEY LAND ON OPPOSITE SIDES OF THE RULE**
  (*"the whole added period/panel/reminder/alarm must be outlined in blue"*, then the correction: *"all daily
  alarms are outlined in blue"*). A §14 reminder tag and a §18 alarm/timer ring are outside `panelOutline`
  on purpose — a tag is a chip with its own check box, a ring is an INSTANT and neither is a panel — so each
  has its own answer in the same funnel, asked in `App.kt` beside every other record's and drawn through the
  same `outlineColor` + `USER_PLACED_BORDER_DP`:
  - **a ring is ORANGE** (`SchedulerDomain.ringOutline`): an alarm is a rule stated in the §18 window off the
    left menu, exactly as a sleep window is one stated in the sleep schedule, and it rings on days the user
    never looked at. **The calendar's menu cannot add an alarm or a timer at all** — it only edits one, by
    opening the window that owns it — so there is no blue case to distinguish;
  - **a tag is BLUE** (`SchedulerDomain.reminderTagOutline`): a reminder is added from the calendar's own
    right-click menu and edited from its "edit…" chooser. The case that makes the outline load-bearing rather
    than decorative is a **checked** tag: its fill goes muted, and the border is then the only thing left
    saying whose it is.
  - **Neither drawing may pick its own colour.** Picking one at the drawing site is exactly how the ring came
    to wear the accent: the colour is `outlineColor(record.outline)` in both, as in every other block.
- **A DERIVED INACTIVITY PERIOD IS DRAWN LIKE AN AUTHORED ONE, MINUS THE OUTLINE.** Same vertical lines, same
  label — it is the same statement — and NO outline, because the outline is the one thing that differs: it
  says who put this here, and the answer is "no one yet". The app is REPORTING an empty stretch it derived,
  not asserting one. **Editing it is what materializes it** (the period editor's Save lays a real panel), and
  the box then wears the blue outline like anything else a hand placed. That is the one moment the user has
  actually said something about the stretch, which is why it is the only moment anything is stored — ADR
  0002, and the reason `materializePastInactivity` is not coming back.
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
- **A RESTRICTIVE PERIOD OF ANY KIND HAS NO FILL AT ALL** — a marking, an outline, a label, nothing else
  (`PeriodSegmentMarking`; `CalendarBlockBody` draws task panels and only task panels now, so it has no
  `period` flag left and `isPeriodBlock` is gone). A period does not occupy the timeline the way a task panel
  does; it states
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
- **NO RESTRICTIVE PERIOD EVER SHARES THE COLUMN'S WIDTH.** Periods leave the block pipeline entirely
  (`isRestrictivePeriodRecord` — so they are out of `overlapLayout` and out of `weightHandles` by
  construction, not by a guard) and are drawn as **one full-width box per stretch**: `periodSegments` cuts
  them at every boundary and fuses back the adjacent stretches carrying the identical set, so A 10–12 with
  B 11–13 is three boxes — A, then A and B, then B — and a lone period is still one box. Each box is labelled
  with **every period in force over it, at the top left** (`periodSegmentLabel`) and outlined by the
  strongest hand among them (`periodSegmentOutline`: blue over orange over grey). Splitting the width is
  Overlap Mode's answer for panels genuinely competing for the same hours, which periods never are — they
  state things about a stretch, and several statements about one stretch are not competitors. The box is a
  DRAWING: each period stays its own object with its own bounds, kind, editor and bin, which is what the
  "edit…" chooser reaches.
- **A SHARED BOX MOVES EVERYTHING IN IT.** A box was cut at a boundary belonging to no single period, so
  there is no one period a press there could mean: `PeriodSegmentGesture` drags or resizes **every** period
  in force, by the same delta. And the gesture is emitted UNDER the panels while the marking is emitted OVER
  them — a full-width interactive box drawn on top would be a lid over every task panel inside the period
  (the "a cursor shape is never a lid over the tile" rule, read for a press), while a marking drawn
  underneath would be hidden by the very task the period admits.
- **Two overlapping periods of ONE layer-asserting kind are ONE period — their union — in the STATE.**
  A period is not an object owning a slice of the timeline the way a task panel is; it is
  the statement *no screen was in use here*, and two overlapping statements of it say one thing. Splitting
  the scheduler has always read them merged (`mergeOccupied` in `noScreenRangesFor`) and so has the layer
  assertion. `SchedulerDomain.unifyNoScreenPeriods`
  is the whole rule and it runs **before the trim** in `resolveScreenOverrides` — so the override and the
  record strip above act on the fused span, not on the span the user typed — with no exception list: it
  runs whatever panel changed, and `decode` runs it too so a state an older build wrote is healed rather
  than surfaced. Two periods that only **abut** are left alone (they already draw full-width, and each is
  still an object the menu can remove), and periods of different KINDS never fuse — a no-screen period and an
  inactivity one, or a "no computer unlocked" one and a "no phone unlocked" one, are different statements (the
  last pair's overlap is a no-screen stretch, which is a reading, not a fusion). Within a fused run the survivor is the panel the user is
  holding — it keeps its id, its pins and its weight, and only its bounds grow.
- **EVERY PERIOD IS DRAWN AS A BOX BUT THE SLEEP BAND** (`isDrawnPeriodRecord`), which draws itself (§17's
  own orange box, its own label, its own carving) and would otherwise be one statement drawn twice. The
  `no screen` kind was the second exception until 2026-09-12 — it then asserted both layers, so the two slopes
  were already painted over it (since 2026-09-18 it asserts none by default and wears its own drawing) — and
  it is **back**, because *"the whole added period/panel/reminder/alarm must be
  outlined in blue"*: the hatch and the box do not say the same thing. A hatch is *nobody of this kind was
  unlocked here*, which the app derives out of the OS log all day; the box's outline is *a hand stated this*,
  which no derived hatch can ever say. With no box, the user's own no-screen period was the one thing they
  could add to the calendar that left no trace of having been added. **Its chooser row is that period and
  nothing else** (2026-09-19): it used to stand for a `no computer unlocked` period overlapping a
  `no phone unlocked` one too, and edit all of them at once, because a no-screen period WAS "both layers". It
  is not any more — see *A "no screen" period says nothing about devices* — so the layer periods are rows of
  their own even where they overlap.
- **A period LAID or DRAGGED over the past clears the work banked under it**, for exactly the tasks it
  **refuses** — the same question the override rule asks, so the on-screen tasks' records go under a
  no-screen period and everybody's under a grey one, as two resiliences rather than as two rules. Same
  funnel as `StripNoScreenRecords` (`stripRecords`, now taking the predicate rather than an `onScreenOnly`
  flag), applied at once rather than at the next engine start; outside Undo/Redo like every write to the
  record. A **dragged** period re-applies it only where the period is the **user's** — a fill-laid break or
  sleep band moving is not the user saying they were not working.
- **EVERYTHING A GESTURE CLOSURE READS MUST BE READ LIVE** (`rememberUpdatedState`), the ARITHMETIC included.
  A `Modifier.pointerInput` whose key has not changed keeps running the lambda it started with, captures and
  all — so the day column's handler (keyed on `day`) and a block's move/resize (keyed on its entry's id and
  bounds) both outlive every ZOOM and every change to the rest of the day. The record lists were guarded from
  the start; the **scale** was not, and fresh lists with stale arithmetic is the same bug: a right-click
  converted the press at the hour height the column had when its coroutine started, which after a zoom IN is
  an hour past the end of the day — nothing there, so no rows, so the menu came up as **"add…" alone on a task
  panel the cursor was plainly inside** (and `millisAt` anchored "add…" at 23:59 for the same reason). Now
  `currentHourHeightPx` / `currentReminderHeightPx` / `currentRingHeightPx` / `currentAllBlocks` in the column
  and `currentHourHeightPx` / `currentOthers` in the block, beside the `currentEdgePx` /
  `currentSliceHeightPx` that were already there for this exact reason. The conversion itself is
  `pressHour` / `pressSpans`, pure and taking the scale as a PARAMETER so `CalendarPressScaleTest` can say
  that one pixel is two different hours at two zooms. **Guarding is not optional for a new read in one of
  those closures** — and re-keying the modifier instead is not the fix: it cancels the gesture in flight,
  leaving `dragPreview` set and the scroll lock held.
- **THE MENU NAMES THINGS, NOT EDITORS** (`calendarEditChoices`, fed by the column's `menuHitsAt`). A point
  on the timeline carries as many truths as are drawn there — a task panel inside a restrictive period under
  a layer, with a reminder tag on it — and each has an editor, so a menu whose "Edit" silently took the
  top-most block could reach only one of them. **What "edit…" opens changed on 2026-09-23** (see *THE ONE
  ADD/EDIT WINDOW* below): one element under the cursor still puts its own name in the menu and opens its own
  editor, two or more open the one element window on all of them. The table below is still the one reading of
  what is there — it ranks the rows, it names the three that are not elements, and the double-click goes
  through it. Seven rules, and `CalendarEditChoicesTest` holds them:
  - **the order is the user's list** (`CALENDAR_EDIT_ROW_ORDER`): **task, task panel, restrictive period,
    reminder, alarm, timer**. It ranks what a row **IS**, not what it reads (`editRowRank` is asked the row) —
    which is what lets a lone period wear its KIND's name and still sit in the restrictive-period slot;
  - **`sleep` is the one row outside that list, and ranks last.** Its editable object is the §17 schedule
    rather than anything the calendar lays, so it is not one of the six things the user ordered;
  - **the period chooser has its own table** (`PERIOD_CHOOSER_KIND_ORDER`): inactivity, sleep, no computer
    unlocked, no phone unlocked, no screen, before bed, then the account's kinds in hit order. Two tables, and not the
    drift ADR 0002 feared, because **they rank disjoint sets** — the first ranks the six FAMILIES a top-level
    row can name, the second ranks WHICH KIND inside the one family that has kinds, so no row is ranked by
    both and there is no question they can answer differently;
  - **a chooser of one is not a chooser**, at either level: one ELEMENT under the cursor
    (`calendarElementDrafts`) replaces "edit…" in the menu itself with that element's own row, and a lone
    restrictive period is named by its KIND rather than by a generic "restrictive period" row that would open
    a chooser of one. It is asked of the ELEMENTS and not of the rows, because a lone task panel grows two
    rows (`task` and `task panel`) and is still one thing the user right-clicked;
  - **the row names the thing in the user's words, and the KIND *is* those words** — a period row is
    `restrictiveKind` itself, with no label table in between. The 2026-09-12 rename is what removed the
    translation: `no task allowed` became `inactivity` (and `sleep`), `no on-screen task` became `no screen`,
    so the stored name is the menu row, and the `periodChoiceLabel` that used to map one to the other was
    deleted rather than kept as an identity. An account-defined kind is its own row — it was already named.
    `PeriodKinds.periodTitle` stays a separate answer, differing by CASE alone ("Inactivity"), because it is
    the title a period CARRIES on the grid, where it heads a box rather than a menu line;
  - **every name a kind has must RESOLVE BACK to it** (`periodKindNamed`): its own word, its grid title, and
    the pre-rename spellings a stored payload may still hold (`PeriodKinds.migrateStoredKind`). That is what
    stops the element window's kind field offering to CREATE a second kind differing from a built-in only in spelling;
    `PeriodKindNamingTest` holds both directions;
  - **a row is routed to the editor that already OWNS what it names** (`App.kt`'s `onEditChoice`): §13's
    window for `task`, §17's schedule for `sleep`, §18's window for `alarm`/`timer`, the one period editor
    for every kind, §14's for a reminder, the calendar edit window for a task panel. The menu never names a
    window;
  - **the three rows that are NOT elements stay entries of their own** (`CALENDAR_SIDE_EDIT_LABELS`):
    `task`, `sleep schedule` and `timer`. Each names an object the calendar does not LAY — the §13 task, the
    §17 recurring rule, the §18 countdown — so none of them can be a row of a window whose whole subject is
    what is drawn at a point, and folding their fields in would be a second editor for a thing that already
    has one. They sit beside "go to task tree", which has always been an entry outside the chooser for the
    same reason. One list of what the window holds, one of what it does not, and no row in both;
  - **the double-click goes through the same table, minus the `task` row** (`calendarBlockEditChoice`): one
    block is the one-row case, so a double-click and a chooser row can never open two different windows for
    one thing — but the gesture is ON the block, and the task behind a panel is not what was double-clicked.
- **THE `task` ROW IS ONE PER TASK, NOT ONE PER PANEL.** Two panels of one task stacked at a point are
  occurrences of that task and the row edits the task, so two rows would offer the identical window; panels of
  DIFFERENT tasks are two answers to "which task?" and stay two rows. A panel whose title names no task grows
  no `task` row at all — there is nothing to open.
- **"Remove" IS GONE: DELETING TRAVELS WITH EDITING.** Each editor carries a bin (`EditorBinButton`), absent
  where there is nothing stored to delete — a derived band, or a row the element window is only ADDING. A
  menu entry that deleted whatever happened to be top-most had exactly the defect that turned "Edit" into the
  chooser, and one funnel for "get rid of this" is the point: a thing is binned from the window that names
  it. In the element window that means **one bin PER ROW of the list**, never one for the window: the window
  names several things, so a single bin could not say which of them it was about.
- **A task panel's menu reaches the TASK as well as the panel** — the `task` row opens the §13 window, and
  **"go to task tree"** selects the task's first cell. Both are offered on a task panel only: a period, a
  reminder, an alarm, a sleep band, a screen break and a layer region are not tasks. **"go to task tree" is
  the one entry left beside the chooser that concerns a task, and it is there because it is not an EDIT** — it
  navigates, as "move" (phone) is a gesture and "add…" creates. Two things they must not become: **the §13
  window is the tree cell menu's own, under its own name** — one window for the task, so the tree's entry was
  renamed "edit" → "edit task" rather than the calendar growing a second way in (`App.kt` opens `taskEditWindows`
  from the `task` row and from nowhere else); and **"go to task tree" goes through `RevealCell`**, the find
  bar's primitive (expand the way in as ONE unit, then select), never a fresh selection path.
- **`firstTaskOccurrence` is where "the first occurrence" is decided**, and `null` is a real answer, not an
  error path — a panel outlives the cell that laid it (panels are not per-tree), so it may name a detached
  parent, a task §4's blank title deleted, or a task another tree owns. The walk is `TaskTreeSearch.matches`'
  — depth-first, **each LIST visited once** (a mirrored sub-tree is one list under many parents) — and it
  skips a blank-titled cell entirely: that cell is the deleted one, and the reveal could not expand it
  anyway. The one place that says "not in the task tree" is the handler, once, for every one of those cases.
- **THE ONE ADD/EDIT WINDOW: "add…" AND "edit…" OPEN THE SAME THING** (`CalendarElementsWindow`,
  `CalendarElements`, `CalendarElementsTest`) — a **set** of elements and their configuration grouped by who
  shares it, rather than a router to one editor at a time. The window it replaced asked *what do you want to
  add?*, handed off, and every editor asked for its own bounds again: fine for one element, wrong for
  several — laying a task panel and the period that must cover it meant two windows and the same two instants
  typed twice, and there was no way at all to say "these three things all start here". Its three sections are
  the user's own, and eight rules hold them:
  - **§1 the selection**: a search bar with the id and title menus (`EditModeMenuBlock`, the same control
    every naming field in the app uses), a drop-down of the KIND, an *add* button greyed until something is
    selected, and the list of what has been chosen. Each row of that list carries its own bin — the window
    names several things, so one bin could not say which;
  - **the four kinds are the four things a hand can put at a point**: `task` (meaning a task PANEL — a block
    of work), `restrictive period`, `alarm`, `reminder`. Not `task`-the-§13-object, not `sleep schedule`, not
    `timer`: see `CALENDAR_SIDE_EDIT_LABELS` above for why those three are entries of their own;
  - **§2 is what EVERY element in the list answers the same way**, asked once, and **§3 is everything else**,
    one section per set of elements that share it, titled by their names and kinds. The grouping is a
    **partition of the FIELDS by their owner set** (`calendarConfigSections`): two fields are one section
    exactly when the same elements own both. It is deliberately not a partition of the ELEMENTS — a panel
    shares `Start` with an alarm and `End` with a period, so an element belongs to as many sections as it has
    distinct owner sets, and giving each element one home is what makes the layout impossible rather than
    merely awkward. **The one-element case needs no branch**: every field is then shared by all, so the
    window is one untitled section and reads exactly like the single-object editor it replaces;
  - **which fields a kind owns is `fieldsOf`, asked nowhere else**, so giving a kind a new field is one line
    and no change at all to the grouping or to the window. `Start` is the one field all four have, which is
    why §2 is usually it alone: a reminder is zero-duration (PRD §14) and has no `End`;
  - **AN ALARM'S START IS THE INSTANT IT STARTS RINGING, AND ITS END IS WHERE THAT RING STOPS** (the user's
    rule). An `AlarmEntry` is a time of day on a set of weekdays, so writing a start writes
    `timeOfDayMinutes` (`alarmTimeOfDayMinutes`) and writing an end writes `soundSeconds`
    (`alarmSoundSeconds`) — **the DATE is never written**: it is only where the occurrence the user
    right-clicked was drawn. Which days it rings on is the `Rings on` field beside the start, asked in its
    own right, so a start dragged onto another day cannot quietly add a weekday. A **ring is never
    zero-length** — a silenced alarm is what the *Armed* switch says;
  - **A VALUE THE ELEMENTS DISAGREE ABOUT IS MIXED AND SHOWS NOTHING** (`sharedValue` returns null). A field
    seeded with the first element's value would have moved the other two to it the moment the user pressed
    Save without typing anything. The other half of the rule costs nothing: each draft keeps its own value
    and the only thing that copies one across a section is an edit (`applyToSection`, which writes by the
    section's **indices** — a window may hold two equal drafts, and a write matched by value would land on
    both), so an untouched window writes back exactly what it read;
  - **a bound mode is offered only where EVERY element in the section can express it** (`offeredBounds`).
    "∞" and "now" are a period's sentences — `∞ → now` is the period editor's whole reason for existing —
    and mean nothing about a ring or a tag, so a section mixing a period with any other kind offers an
    explicit instant alone;
  - **"edit…" is confined to what is at the mouse.** The window's search bar offers only those elements
    (`CalendarElementsMode.Edit`), so it can never reach past what was right-clicked — and an element struck
    off the list can be put back without reopening it. A **derived** band is an element with nothing behind
    it (`existingId` null), so saving it MATERIALIZES the period it was standing in for, exactly as the
    period editor's Save always did.
- **THE WINDOW LAYS, AND ONE SAVE COSTS ONE Ctrl+Z PER UNDO STACK IT TOUCHED — AT MOST TWO.** This is the one
  thing the old chooser's "it lays nothing" rule bought, and it had to be re-bought rather than kept: the
  window IS the placement path now. `SchedulerIntent.AddCalendarElements` carries every element that is a
  PANEL (task panels, periods, reminder tags) and commits them as **one calendar delta**, folding them
  through `resolveScreenOverrides` one at a time so each is resolved against the calendar the ones before it
  already changed — a period and the panel inside it laid together leave what drawing them one after the
  other would, and the id allocator is carried with them so two new panels can never share an id. **Alarms
  are deliberately outside it**: an alarm is a row of `SetAlarms`, a **Main** history unit, and folding one
  into the calendar's stack would leave a ring whose only undo is a Ctrl+Z aimed at the calendar. The screen
  switch is a TASK setting and rides `SetTaskResilience`, once per task and only where the value changed.
  `CalendarElementsSaveTest` holds all of it.
- **The kind of a period is CHOSEN, off `state.allPeriodKinds`** (`PeriodKindField` — the task cell's
  categories drop-down read for a single value: same rows, same naming field, same "a name the account
  already holds picks THAT one"). Defining a kind from here goes through `AddPeriodKind`, the task edit
  window's own `+` intent, never a second one. This is what the 2026-09-12 reshape bought and it is
  unchanged: the menu used to name two KINDS of period by hand ("add a no-screen period", "add an inactivity
  period"), which is a funnel with an exception list — `before bed` and every kind the account defines had no
  way onto the calendar at all, because a menu can only list the kinds somebody typed into it.
- **THE PERIOD EDITOR IS ONE WINDOW FOR EVERY KIND** (`PeriodEditWindow`), the ONE-ELEMENT case of a period
  — reached from a period's own row of the menu — it never lays a panel directly. It takes the kind as a **name**, not as an enum of the
  two the menu used to offer, which is what made the window itself a place a third kind could not be edited;
  what it *says* about the kind is `periodKindBlurb`, written out of the model (`PeriodKinds.defaultResilience`)
  rather than out of a list of cases. The kind is **shown, not changed**: re-kinding a period is a different
  edit from moving its bounds, and the bin plus a fresh add is the one way to say it. Each bound is a
  date+time, **"now"** (resolved at Save), or **"∞"** (`SchedulerDomain.OPEN_PAST_MILLIS` /
  `OPEN_FUTURE_MILLIS` — real 1900/2200 instants, never `Long.MIN_VALUE`: every consumer does plain arithmetic
  on a panel's bounds). A *derived* band HAS an editor, and saving it is what **materializes** the period it
  was standing in for — under the band's own kind, not the row's, so a §17 wind-down hour materializes as
  `before bed` and never as plain inactivity. It has no bin: there is nothing stored to delete. The case the
  editor exists for: **an inactivity period from ∞ to now** empties the recorded past.
- **A PERIOD'S PAINT IS DERIVED FROM ITS KIND, IN ONE PLACE** (`App.kt`'s `calendarRecords`). Both paints are
  now fill-less, and what the two bits still say is whether the period **asserts a layer**
  (`CalendarRecord.noScreen`, read off `PeriodKinds.isLayerKind` — its drawing is painted over it and the
  stretch is not covered past, so a derived Inactivity band may still be drawn under it) or only speaks about
  the timeline (`.inactivity` — it covers the past, so no band is derived under it). Read off the panel's two
  legacy flags instead, a period of any other kind carried neither and was drawn as a **task panel**. `CalendarRecord.restrictiveKind` / `PlacedRecord.restrictiveKind` carry the identity beside the
  paint, and that is what the chooser's row hands the editor — a kind with no flag has nothing else to be
  recognised by. The same rule on the other side: the derived §17 wind-down bands are split off **by panel
  id** (`BEFORE_BED_PANEL_ID_PREFIX`), never by kind, or a `before bed` period the user drew would lose its
  row and its bin along with the fill's own.
- **Derived inactivity periods run to the DEFINITIVE-SCHEDULE FRONT, not to the now-line**:
  `[displayFloor, max(now, nearHorizonEnd)]` minus everything already drawn, except the periods that assert a
  layer and the screen breaks. The user's rule is that the timeline is fully accounted for — every stretch is
  a task panel or a restrictive period — and the ONE place that may fail is past the instant the scheduler
  has a definitive schedule for, where there is no answer yet to give. The future's empty stretches are the
  same statement as the past's, derived from the plan instead of from what happened. Display-only on both
  sides, sub-minute remnants dropped.
- **A TASK PANEL PAST THAT SAME FRONT IS DRAWN WITH BLURRED EDGES**
  (`SchedulerDomain.definitiveScheduleFrontMillis` / `isProvisionalPanel` → `CalendarRecord.provisional` →
  `CalendarBlockBody`'s `provisional`, `ProvisionalPanelTest`). The front is one instant and it answers three
  questions at once: where the derived bands stop, where the far-week display fill takes over, and which blocks
  are unsettled. Past it the scheduler has returned nothing — what is drawn is the far-week fill, never
  retained and recomputed from scratch on the next visit — so § *Progressive Calculation*'s guarantee
  ("every later set of rules says the same for `t < t₁`") does not cover it. Only the fill's OWN panels blur:
  a pinned or hand-drawn one is § *Starting timeline* input, as fixed past the front as before it. A merged
  block asks every panel it fused, not its head, so a run straddling the front blurs whole — the guarantee is
  over `t < t₁`, and the front did not bound that run's length. The **paint** is blurred and the title is not:
  which task is planned is not what is uncertain, where it starts and ends is.
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

1. what the user DREW (`SchedulerDomain.assertedNoScreenRanges`: a period that is or carries "No screen" —
   companions included — or a computer-layer period overlapping a phone-layer one), and
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

