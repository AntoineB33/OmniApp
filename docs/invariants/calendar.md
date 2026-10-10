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
  layer (unless a rule of the account makes it bring them), and is never edited together with a layer period.
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
    bank). Since 2026-09-30 that direction is a **combination rule** (below), on by default and editable;
  - **the implied period is subtracted where an explicit "No screen" period already covers**, because the plan
    MULTIPLIES every covering kind's resilience (`PeriodKinds.multiplier`): counting the stretch twice would
    square it and halve the share of anybody sitting strictly between 0 and 1. A `0` and a `1` would not have
    noticed.
- **THE FOUR LAYERS: REAL AND FAKE, PER DEVICE KIND** (`docs/scheduler_requirements.md` § *$now line$ 3 modes*,
  2026-09-30). Each device kind has a real layer ("no computer unlocked", read off the OS) and a **fake** one
  ("not on a computer", `PeriodKinds.NOT_ON_A_COMPUTER` / `NOT_ON_A_PHONE`): the device is
  unlocked and the user said nobody is at it — the "I'm away" button, or a period drawn of the fake kind. **The two
  never overlap** (`SchedulerDomain.fakeLayerRegions`: the real layer wins), and a lock ends the button
  (`screen-breaks.md`). The fake layer is its OWN band (`CalendarRecord.layerFake`), in the fake kind's drawing —
  by default the real slope dotted (`PeriodDrawing.DottedRisingObliques` / `DottedFallingObliques`), the look the
  away stretches had before. A peer's fake layer is not drawn: nothing carries a peer's layers (its real one is
  assumed locked whole), and the mode reads the account's away flag.
- **COMBINATION RULES** (`PeriodCombination`, `PeriodKindConfig.combinations`, `SchedulerState.periodCombinations`;
  the period edit window's *Combinations* section, user rules 2026-09-30 / 2026-10-01): wherever a rule's **"When"
  formula** holds, its **"then" formula** is made true over that stretch — **always derived, never stored**. Both sides
  are formulas (`PeriodFormulaToken`, read ONLY by `PeriodFormula`): period selector fields — each the shared check-box
  drop-down (`CheckBoxDropDown`), holding where every kind checked in it is — joined by `and` (overlap) / `or`
  (union), with brackets; "When" also has `not`, which holds **wherever there is none on the whole infinite
  timeline** (`PeriodFormula.TIMELINE`, a quarter of the Long range each way). `not` binds tightest, then `and`; a `(`
  left open closes at the end, anything else unreadable brings nothing. The editor only appends whole steps and `⌫`
  undoes one (`canAppendOperator` / `canOpen` (also `not`) / `canClose` / `removeLast`). No `not` in "then" (user
  choice).
  - **A "then" with an `or` is a PLACEMENT** (`PeriodCombination.isPlacement`; user rule: *"the period to the left of
    'or' is placed automatically when the user manually adds the period in the calendar, except when the 'or'
    condition is already verified on the timeline"*): its "When" is read over the periods the USER STATED only,
    each with what it carries (`closeRegions`' `manual`, closed by `kindsOf` — `SchedulerDomain.isUserStated`: a blue
    or ORANGE outline, so the Sleep schedule's windows and wind-down hours count, user report 2026-10-01; carried as
    `RestrictivePeriod.manual`), and over that stretch a field is laid, `and` lays both sides, `A or B` lays A only
    where neither A nor B already is. A break (grey, the app's) or a no-screen stretch the layers make never fires
    one. Consequence of the default: every sleep window and wind-down hour hatches both layers. That replaces the
    2026-09-18 rule "a sleep window carries no layer".
  - **A period a RULE laid states nothing where the line crossed it in MODE 1** (`statedKindRegions`' `atScreenPast`
    = this device's known-unlocked past minus the away spells; user report 2026-10-01: *"I didn't hit the I'm away
    button, so the now line must be in mode 1 and the sleep period must retract to the now line"*). An orange period
    (`PanelOutline.Pattern`) that is or carries "no screen" retracted there (`scheduler.md` § *A mode-1 line
    retracts*), so no "not on a computer" band is derived from a sleep window behind the line unless the button was
    on. A period the user DREW (blue) keeps its whole span — the rule just below. **The band is cut by the same
    function** (`SchedulerDomain.retractOverAtScreenPast`, in `App.kt` over the Sleep bands and the plan's orange
    periods): the Sleep band and the wind-down hour stop at the line while it is at a screen, and what is left
    behind it is only what was crossed locked or away. The Sleep toggle's live band is not cut. (2026-09-28 →
    2026-10-01 the band was drawn whole.)
  - **The one exception to "the left of `or`": where the OS KNOWS the left is not there, the right is laid**
    (`closeRegions`' `knownAbsent`; user rule 2026-10-01: *"if 'then' derives somewhere in the past a ('no computer
    unlocked' or 'not on a computer') where there is no 'no computer unlocked', then 'not on a computer' is placed
    because the OS knows that one computer was unlocked. Same for the phone."*). Fed only with
    `SchedulerDomain.knownUnlockedRegions` — this device's own layer, the asked past minus its locks (same seam
    rule), and only once its history was read; a peer's layer and an unread history are never "known unlocked".
    So a "No screen" drawn (or a sleep window lying) over hours this computer was really unlocked shows the
    dotted "not on a computer" band there. (A "no computer unlocked" period drawn directly over them is shown the
    same way, by `declaredLayerRegions`: dotted obliques are "not on a computer", whoever said it.)
  - **Plain rules** (no `not` in "When", no `or` in "then") are what a kind CARRIES (`kindsOf`, transitive) and close
    to a fixpoint; the others run after, once each in the account's order, each followed by the plain closure again
    (stratified, so a `not` reads the timeline the rules before it left).
  - **One closure** reads the rules (`PeriodKindConfig.closeRegions`) for every question of "which kinds are here":
    the scheduler's companion periods (`companionPeriods`), the bank's drawn no-screen (`assertedNoScreenRanges`),
    the devices' observed no-screen (`observedNoScreenRegions`) and the calendar's layer hatch over the user's own
    periods (`statedKindRegions`, computed once per derivation in `App.kt`, the "I'm away" spells passed in as the
    fake layer so an `or` sees them as already verified). `assertedLayers(kind)` (plain only) stays for the sleep
    windows and breaks, which never place.
  - **Defaults** (`PeriodKinds.DEFAULT_COMBINATIONS`): `(no computer unlocked or not on a computer) and (no phone
    unlocked or not on a phone)` → no screen (`LAYERS_RULE`; it replaced four one-field rules of the same meaning,
    which `decode` collapses into it where an older edited list still holds all four untouched); its reverse for
    drawn periods, `when no screen then (no computer unlocked or not on a computer) and (no phone unlocked or not on
    a phone)` (`NO_SCREEN_LAYERS_RULE`) — so a "No screen" period the user draws hatches both real layers except over
    a fake (or real) one already there; then the old default companion sets (above).
  - Stored only once edited (one `field` row, an emptied list included; an edited list is never given a later
    default); older shapes are still written beside the formulas where a rule still has them (`kinds`/`implies`,
    `then` as and-joined fields, `thenFormula` the full one); `decode` drops kinds the account does not hold and rules
    whose formulas do not parse; in `schedulingSignature`.
  - **Not built yet**: editing a derived period by hand to make it a stored, blue-outlined one (user rule: "then" is
    derived *"except when modified by the user"*).- **The "I'm away" stretches are this device's FAKE layer** (`SchedulerEngine.declaredAwaySpans`/`declaredAwaySince` →
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
  START, extended in place by the advance tick (`SchedulerEngine.boundOpenSession`; the phone's lease beat on a phone)
  and never by a timer of its own, so a kill
  mid-away lands the episode closed at its last beat exactly as a live `device_active_session` row does.
  LOCAL-ONLY, pruned to the same 24 h window the no-screen evidence answers over.
- **A HATCH THE LOCK LOG CONTRADICTS IS "NOT ON A COMPUTER" (OR PHONE) — WHOEVER SAID IT**
  (`SchedulerDomain.declaredLayerRegions` -> drawn as the FAKE band, `CalendarRecord.layerFake`). **"Not on a
  computer" IS the dotted oblique lines** (user, 2026-10-01): a stretch the user says nobody was at while the OS
  saw the device unlocked is that kind, under its name and its drawing — never a dotted copy of the real layer
  (`CalendarRecord.layerDeclared` and `periodDrawing(dotted = …)` were deleted that day; dashes come only from
  the dotted drawings themselves). Two things state it where the OS log disagrees, and they are **one rule**, in
  one funnel:
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
  - The both-layers identity is untouched: the fake layer brings "no screen" with the real one, by the same
    default rule (`LAYERS_RULE`).
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
  **The ranks are ONE declaration, `CalendarBubbleRank` (domain), with three readers**: the bubble
  (`CalendarBubbleSection.Kind.rank`), the placement of the column's texts (`calendarLabelSlots`) and the
  Search window's whole-list sort **"calendar bubble order"** (`SearchDomain.SortKey.CalendarBubble`), which
  the calendar's "edit…" turns on, dominant (`calendarAtConfig`, `withCalendarBubbleSort`). Never restate a
  rank at a reader.
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
  row per kind; an Undo/Redo unit since 2026-10-01, like defining a kind — `SettingsDelta`).
  - **Companions**: *"a set of periods that are always present when this period is present"*. Since 2026-10-01
    **a companion set is a combination rule** `when <kind> then …` (`PeriodKinds.companionRule`; the window's
    "Always present with it" section was removed, user rule: *"it can be done by the combinations config"*), so
    there is one mechanism, not two. What a kind carries is every rule its period satisfies ALONE, TRANSITIVE
    (`PeriodKindConfig.kindsOf`; a cycle is harmless). An implication, never a laid panel. Every reader asks
    the config: the scheduler (`SchedulerDomain.companionPeriods`, via `restrictivePeriodsOf` and the fill),
    the mode-1 retraction, the layers a period hatches (`assertedLayers`), the record bank
    (`assertedNoScreenRanges`) and the calendar's drawings. **Defaults** (the tail of
    `PeriodKinds.DEFAULT_COMBINATIONS`): `sleep`, `before bed` and the two break kinds carry `no screen`;
    `inactivity` carries **no** `no screen`; `no screen` carries **neither** layer; everything else carries
    nothing. Rule changes are in `schedulingSignature`; drawing changes are not (paint). A payload written
    before 2026-10-01 has its sets folded into rules on `decode` (`PersistedPeriodKindStyle.folded` /
    `PersistedPeriodCombinations.folded` mark the new shape).
  - **Drawings** (`PeriodDrawing`, rendered only by `ui/PeriodDrawings.kt`'s `Modifier.periodDrawing`): a
    CLOSED set of line patterns that differ by geometry alone (same colour, stroke and 35 % alpha), so any
    number overlap legibly. Defaults (`PeriodKinds.defaultStyle`): **inactivity, sleep, no screen and before
    bed wear NO drawing** (user rule 2026-10-02) — label, and outline when authored, until the account picks a
    pattern; `/` no computer unlocked, `\` no phone unlocked (dotted for the two "not on a …"), `|` the breaks. A
    kind the account adds is given the least-worn drawing (`PeriodDrawing.patterns`) AT CREATION and stores it.
    **`None` ("No drawing") is the one member that is no pattern**: a kind left to its outline and label alone —
    those four defaults, or CHOSEN in the drawing selector, never handed out to a new kind — and the one renderer
    paints nothing for it. An override is stored only where it differs from the default, so an account that never
    chose a drawing for one of the four follows the default. A drawing is stored — never derived from the list position, or
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
- **NO TWO TEXTS SHARE A POINT, AND NO MARKER IS DRAWN OVER ONE** (user rule 2026-10-02: *"text must never be
  overlapped by anything"*). `calendarLabelSlots` is the one answer for every text a column writes at the top
  of an element — a task panel's title, a period's / the sleep band's label, a break's name — against each
  other and against what is drawn over them: the day's date badge ("Sat 30"; the grid's TOP row passes
  `showsDayDate = false`, its date being in the header), the §14 tags, the §18 rings and the BOTTOM LINE of
  every outlined period box (a text it would cross goes below it). **A label is written only where its whole
  line lies inside its own element — pushed or not**: a period a few dp tall names itself in the bubble
  alone. Labels are taken top
  to bottom; **at the very same point, in the hover bubble's own order** (`CalendarBubbleSection.Kind.rank` —
  a task's title keeps the point, the period's label goes below it). Each is written at the first clear place
  at or below its element's top, so the one that gives way goes BELOW the other. It is the band rule above by
  another route: nothing is moved to make room and no element is stretched — a label pushed past its
  element's bottom is not written, the zoom being what brings it back. A wrapping panel title is capped at
  the lines that fit before the next text below (`CalendarLabelSlot.maxLines`). It is re-asked whenever a
  position changes (the drag preview's `liveRecords`, a record the now-line carries, the zoom), so a text is
  always placed for where its element is NOW. Never add a label that computes its own inset.
  `CalendarLabelSlotsTest`.
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
    opening the window that owns it. **The one blue case is a ring the user DRAGGED** (2026-10-02): a ring
    is moved by a mouse **double click whose second press is kept down and dragged** on `AlarmMarker` (a plain
    press-and-drag moves nothing, so a ring is never moved by accident), committed on release through the
    blocks' `onCommitBounds`, which `App` routes to `SearchDomain.calendarRingMoveIntent`. **Blue means an
    ISOLATED block the user edited; orange means one laid by a pattern the user defined** — so dragging ONE
    ring of an alarm never moves the alarm's other days: the rule keeps its time and skips that date
    (`AlarmEntry.skippedEpochDays`), and the dragged ring becomes a row of its own that rings on that one
    date (`AlarmEntry.onlyOnEpochDay`, `isolated`), drawn blue. Both are read by the ONE occurrence test
    `AlarmEntry.ringsOn(date)`, so the calendar, the cue sweep and the OS arming agree. A timer rings once by
    nature: it is put on the clock to end there and marked (`TimerEntry.calendarPlaced`). All three fields
    are authoritative (persisted + synced), and the Alarms window carries them through its rows untouched —
    it rebuilds every entry from its row, so a field it does not hold is a field it erases. The gesture sits on the node that does NOT move — only
    the drawing follows the drag — or the pointer's local position moves with it and the drag stalls;
  - **a tag is BLUE** (`SchedulerDomain.reminderTagOutline`): a reminder is added from the calendar's own
    right-click menu and edited from its "edit…" chooser. The case that makes the outline load-bearing rather
    than decorative is a **checked** tag: its fill goes muted, and the border is then the only thing left
    saying whose it is.
  - **Neither drawing may pick its own colour.** Picking one at the drawing site is exactly how the ring came
    to wear the accent: the colour is `outlineColor(record.outline)` in both, as in every other block.
- **A DERIVED INACTIVITY PERIOD IS DRAWN LIKE AN AUTHORED ONE, MINUS THE OUTLINE.** Same drawing (none by default), same
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
- **WHILE ANYTHING IS DRAGGED, THE COLUMN IS DRAWN THE WAY A RELEASE WOULD LEAVE IT — AND ONLY THE RELEASE
  SAVES IT** (2026-10-02). A moved block keeps its length whatever it is carried over
  (`draggedBlockBounds`; the no-overlap snap `placeDraggedEntry` is gone) and the preview answers with the
  reducer's own rules, not with a picture of its own:
  - over another **task panel** it **shares the width** — a move always commits `allowOverlap`
    (`blockGestureOverlaps`), and the preview gives the dragged block the weight the commit will seed
    (`SchedulerDomain.seedOverlapWeight`, which counts task panels only: a period is never one of the `n`);
  - over a **period the user drew that refuses its task** the period **retracts** (`periodsForBlockDrag` →
    `shownPeriods`), and a dragged period box retracts the task panels it refuses (`blocksForPeriodDrag`).
    The question is `SchedulerDomain.periodRefuses` and the arithmetic `SchedulerDomain.retractAround` — the
    very two `resolveScreenOverrides` commits with. `LocalPeriodRefusal` carries the first to the column.

  - the **DERIVED Inactivity bands** are redrawn too (`SchedulerDomain.inactivityBandsAfterMove`, inside
    `periodsForBlockDrag`): they are "whatever nothing covers", computed in `App` from the stored state, so
    a preview that left them alone showed NO idle stretch where the block had been and an untouched one where
    it was going — until the release recomputed both. The stretch the block leaves is idle wherever no other
    block, sleep window or non-screen period of the user's still covers it; the idle stretch it enters gives
    way. A block past the definitive-schedule front (`provisional`) vacates nothing: no band is derived there.

  **A released drag's preview stays until the records show its commit** (`ReleasedDrag`, anomaly 2026-10-05): the
  state a release commits reaches the column a frame or more after the release, so dropping the preview at the
  release drew the block back where it was picked up for that long. Never clear `dragPreview` at a release directly
  — `endDragPreview` does, when the records change (or after `RELEASED_DRAG_WAIT_MILLIS` for a commit that changed
  nothing).
  Every retraction is computed from the bounds **at rest**, never from the preview's previous answer: that is
  the whole of "what the drag retracted grows back as the drag recedes", and of "a second drag does not
  restore what the first release saved". The gesture half of a period box keeps reading `drawnPeriods` —
  those nodes may hold a press and must not change under it; only the marking, the labels and `PanelDecor`
  read `shownPeriods`. A **resize** still stops at its neighbours (`clampResize`) unless Overlap Mode is armed.
  Not previewed: the re-plan the release triggers (scheduler-laid panels under the block are redrawn by it),
  the same-task merge, and the fusion of two overlapping no-screen periods.
- **A SCREEN BREAK CUTS A HOLE IN THE DRAWING OF EVERY TASK BLOCK IT REFUSES — AND THE HOLE IS NEVER
  STORED** (2026-10-02; `layoutWithBreakHoles`, applied to `overlapLayout`'s answer at rest and in the
  preview). *"When there is a break, there can't be a task"* was only true of the fill's own panels, which it
  splits around the breaks; a panel placed by hand runs THROUGH them (`resolveScreenOverrides` never trims a
  dynamic period) and the band was merely drawn over it. Now the block's slices are cut where a break lies
  over it, by the one question (`periodRefuses`) asked of the break's own kind (`PlacedRecord.breakKind`,
  kept apart from `restrictiveKind` so a break never becomes a period the chooser can open): nobody is
  resilient to the 20-second look-away (`inactivity`), and a task given a resilience to a 5- or 15-minute
  break is drawn straight through it. Because it is cut from where the break is NOW, the hole follows a
  break the now-line carries and leaves no trace on a block dragged across one. The pieces are still ONE
  block: every one of them holds the same gesture, so dragging any piece drags the whole panel, and only the
  first and last carry the resize strips. With `showScreenBreaks` off there are no bands and no holes.
- **THE PREVIEW WEARS THE FACE THE BLOCK WEARS AT REST** (`CalendarPanelFace`, one composable for
  `CalendarBlock` and the preview overlay). The overlay had its own copy of the drawing, left behind at the
  30 % wash when task colours became opaque — so a dragged panel turned pale, lost its outline and read as a
  ghost of itself. The dragged block is drawn with the blue outline the release gives it.
- **A BLOCK OR A PERIOD BOX IS MOVED ONLY BY A DOUBLE CLICK WHOSE SECOND PRESS IS HELD AND DRAGGED**
  (2026-10-02, the rule a ring already followed): a plain press-and-drag moves nothing, so nothing is moved
  by a click that slipped. A second press released without a drag still opens the block's editor. The slop
  only decides *whether* it is a drag — the travel is counted from the first pixel, so there is no radius in
  which the block lags the pointer. A **resize** starts at the first press: the grab strip and its cursor
  already make it deliberate. (Phone: unchanged — the menu's "move".)
- **A RIGHT-CLICK DURING A BLOCK DRAG SUSPENDS IT** (`HeldBlockDrag`, hoisted to the column): the block's
  gesture ends without committing and without clearing `dragPreview`, and the column — which sees the same
  event next — opens a menu of two rows. "cancel" drops the preview; "resume drag" hands the block back to
  the pointer, which carries it with **no button held** until a click releases it (that release is the
  commit), another right-click reopening the menu. The carried drag runs on the column's **Initial** pass and
  consumes, so nothing under the pointer takes the click that is meant to drop the block; dismissing the menu
  without choosing resumes, because that loses nothing. The bounds are `draggedBlockBounds` in both the
  block's gesture and the column's — a second copy of that arithmetic is how the two would drift.
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
  construction, not by a guard) and are drawn as **one full-width box per PERIOD, over its own hours, with its
  own outline** (user rule 2026-10-05; `periodSegments`, `periodSegmentOutline`). A period added 10–12 over a period
  11–13 is a blue outline round 10–12 and the other's outline still round 11–13: two boxes overlapping from 11 to 12.
  They were cut at every boundary until then — three boxes (A, then "A, B", then B), the middle one in the strongest
  hand's outline — which drew the period just added as two boxes and broke the outline of the one already there.
  **Do not cut a period's box at another period's boundary again.** Splitting the width is Overlap Mode's answer
  for panels genuinely competing for the same hours, which periods never are. Each box is labelled with its
  period's title at the top left (`calendarLabelSlots` keeps two labels apart). The box is a DRAWING: each period
  stays its own object with its own bounds, kind, editor and bin, which is what the "edit…" chooser reaches.
- **A HELD BLOCK REMEMBERS ITS LENGTH** (user rule 2026-10-08: *"when held the block always remembers its length
  while avoiding appearing where it would break the requirements"*; `HeldBlockKeepsLengthTest`). Two cases, one rule:
  - **On the line, where the line cannot be on it** (a task panel under a mode-3 line, a "no screen" period under a
    mode-1 one): the block is `[…, line[ ∪ ]line, …]` — the line takes its own instant and nothing more, so the block
    stands where the hand has it and the release stores that span. `LocalPeriodAtLine` hands a period back as it is.
    (2026-10-05 → 10-08 a period that is or carries "no screen" was cut to `]line; its end]` under a mode-1 line,
    shortened as it was carried across and was gone as its end reached it: `SchedulerDomain.periodAtLine`, deleted.
    `HeldPeriodCut` no longer has a cut to draw.) What the line then CROSSES of the stored period gives way as it is
    crossed — `scheduler.md` § *A period the user stated gives way to a mode-1 line*.
  - **In a screen break that refuses its task** (*"the 15min break retracts the held task panel, which comes out on
    the other side to keep its length"*): a MOVE takes the span `SchedulerDomain.spanKeepingLength` gives — from the
    first instant the break does not hold, on until the panel has stood for its length outside the breaks
    (`draggedBlockBounds`'s `refused`, from `refusingBreaks`: the question `layoutWithBreakHoles` asks). The panel
    stays ONE object whose span holds the break; the drawing cuts the hole. The length it remembers is what STANDS of
    it at rest (`standingLength`), so a panel left across a break and picked up again is not longer for it. A resize
    is not lengthened: the hand is saying where the edge is. The Search window's "Drag on the calendar"
    (`CalendarElementDrag.targets`) goes through the same function.
  Never clamp a drag at the line or at a break, and never shorten a held block to make it fit.
- **A BOX IS ONE PERIOD, AND MOVES THAT PERIOD.** `PeriodSegmentGesture` drags or resizes the period its box is
  (it moved every period in force over a shared box while boxes were cut). Where two boxes overlap, the
  later-starting one is on top and takes the press; the other is reached from the part of it that sticks out.
  And the gesture is emitted UNDER the panels while the marking is emitted OVER
  them — a full-width interactive box drawn on top would be a lid over every task panel inside the period
  (the "a cursor shape is never a lid over the tile" rule, read for a press), while a marking drawn
  underneath would be hidden by the very task the period admits.
- **THE TIMELINE IS THE OVERLAP OF THE `tm_levels`; PLACING A BLOCK DESTROYS NOTHING** (`docs/scheduler_input_requirements.md`,
  2026-10-10; `TimelineLevels`, `SchedulerReducer.settleLevels`, `TimelineLevelsTest`). *"When a blue/orange outlined
  block is positioned at t_r, then what was there before is added at t_r to the lowest tm_level where nothing is at
  t_r. What is in the timeline is the result of the overlap of those tm_levels, applying them from top to bottom,
  ignoring those that are incompatible with the higher tm_levels."*
  - **Every block a hand placed stands on a level** (`TaskPanel.tmLevel`). The block being positioned — laid, dragged,
    resized, edited — is raised above every block its span overlaps (`TimelineLevels.raised`).
  - **`state.panels` is the overlap as it stands; `state.hiddenPanels` is what the levels hide.** A block is shown
    where it can share the stretch with the levels above it and HIDDEN where it cannot (`HiddenPanel`: the piece, its
    level, the block it is a piece of — `TaskPanel.tmOrigin`). Hidden pieces are on no timeline: the scheduler, the
    cues, the layers and the drawing read `state.panels` and nothing else, as before.
  - **Incompatible is the override rule's one question** (`SchedulerDomain.periodRefuses`), asked of the periods in
    force above — the combination rules' included (`PeriodKindConfig.closeRegions`: "no computer unlocked" under a
    task standing with "no phone unlocked" is the level ignored, the document's first example) — plus: under a period
    of ITS OWN kind a period says nothing new, so that part is hidden (*"the included block loses its blue/orange
    outlines"*: 9h–12h and 12h–13h, where the two used to be fused into one 9h–13h period by
    `unifyNoScreenPeriods`; that fusion now only heals payloads on decode).
  - **A task panel positioned over another task's panel HIDES it, unless Shift is held** (*"Dragging task A into task
    B hides task B, unless shift is pressed, which makes the two task panels share the width"*; `TaskPanel.tmShare`,
    said at every positioning from the intent's `allowOverlap`; `blockGestureOverlaps` = Shift at the release, or
    Overlap Mode armed with `O`). Until 2026-10-10 a move always shared the width. Two panels on ONE level — everything
    an older build left overlapping — go on sharing it. The Search window's "add, keeping everything" filter follows:
    a task added over another task's panel takes it off the timeline.
  - **One funnel, every calendar commit** (`commitPanels` → `settleLevels` → `TimelineLevels.settle`, over the
    stretches the edit touched and every block reaching into them): a block moved away, resized or REMOVED gives back
    what it stood over, as the object it was (its own id where it is whole again). `resolveScreenOverrides` settles
    once before, so what stands behind the line where a period was is chosen from a timeline that already has the
    pieces back; what it still TRIMS is the scheduler's own panels — the bottom level, laid again by the next fill.
  - **Authoritative**: persisted (`PersistedState.hiddenPanels`, `PersistedPanel.tmLevel` / `tmOrigin`; absent from an
    older payload = level 0, nothing hidden), synced one row per piece, merged piece by piece, and in the edit's own
    history unit (`PanelDelta.hidden`), so one Ctrl+Z puts the block and what it covered back.
  - **WORK banked under a period the user lays over the past is HIDDEN, never stripped** (user rule 2026-10-10, the
    requirements' *frozen past*: *"According to the 'frozen past' rules, the schedule at t < now line never changes
    unless explicitly rewritten. Stripping it violates this rule; it must be preserved."*; `settleRecords`,
    `HiddenPanel.record`). While the period stands, the stretch is out of the task's record and on the level under it;
    wherever the period no longer stands — moved, shrunk, removed, undone — it is back in the record as it was, joined
    to what it was cut from. In the edit's own history unit (`PanelDelta.records`), so Ctrl+Z walks it; the plan is
    asked again on the spot (records are outside `schedulingSignature`). The periods are read as the line left them
    (`afterCrossings`). `stripRecordsUnderPeriod` still runs after the commit and now finds nothing under a period of
    the user's; the engine-start strip of what the OS log says was no screen time is unchanged.
  - **Bounded** (`TimelineLevels.purged`, at every calendar commit): a hidden BLOCK piece wholly more than 90 days
    behind the line is dropped, and never more than 500 are kept (`server-quota.md`). Hidden WORK is never dropped: it
    is the record itself, moved, and no larger than it was there.
  - **Not levels**: a repeating panel (a pattern — neither cut nor cutting), what a rule lays (sleep windows, the hour
    before bed, the screen breaks: derived at every fill, a placed panel is placed through them), reminder tags.
  - **What the hole rule lays where a dragged block stood wears NO outline** (anomaly 2026-10-10: a past 15-minute
    break dragged away left *"an 'Inactivity' blue outlined block filling the vacated time interval"*;
    `TaskPanel.tmFill`, `SchedulerDomain.panelOutline`, `VacatedPeriodFillTest`). It is the bottom level's — stored,
    since nothing else remembers the choice (`PersistedPanel.tmFill`, default false), but nobody's statement, so
    neither blue nor user-stated — until a hand edits it. A fill an older build laid is stored as a hand-placed
    period and stays blue.
  - **A level is an ORDER here, not a row**: the document puts a block that hides nothing on the bottom level, and
    this raises every positioned block above what it overlaps. Nothing observable differs, and the order is what its
    first example needs (of two compatible periods, the one dragged later is the one that stays under a task).
  - **Open**: where a block includes a block of ANOTHER kind, the included block still wears its whole outline; the
    hole rule's wider "incompatible" (*"but also of task share"*) is not checked against `vacatedPastFill`; a part of
    a block cannot be selected and dragged on its own (the document's second example).
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
  flag), applied at once rather than at the next engine start; outside Undo/Redo (an open gap: undoing the
  period does not bring the stripped work back). A **dragged** period re-applies it only where the period is the **user's** — a fill-laid break or
  sleep band moving is not the user saying they were not working.
- **ANY BLOCK CAN BE DRAGGED — EXCEPT A SCREEN BREAK AHEAD OF THE LINE — AND IT THEN GETS A BLUE OUTLINE**
  (`docs/scheduler_input_requirements.md`, 2026-10-10: *"All blocks can be dragged, except screen breaks at t > now
  line"*: the band's `movable` and `calendarMoveOf` both refuse a break that has not wholly elapsed; user rule 2026-10-07; anomaly 2026-10-06 *"I
  tried to drag a past 15min screen break but I couldn't"*). The blocks that had no object behind them — a screen
  break, the Sleep band, the hour before bed — were the exception list; there is none now:
  - **One gesture**: `Modifier.periodBoxGesture`, the period box's own (a double click whose second press is held and
    dragged; a resize from a grab strip), worn by `PeriodSegmentGesture` (period boxes, and the Sleep band, which is
    emitted with them under the panels) and by `ScreenBreakBand` (on the band, which is drawn on top; no resize — a
    break lasts as long as its name implies). Never a second copy of it.
  - **One preview**: the held block is a `PeriodSegment` of its kind (`sleepSegments`, `breakSegments`,
    `heldPeriods`), so the column draws it where the hand has it — whole, on the line too (a held block remembers
    its length) — with the task panels it refuses retracted under it and growing back as it leaves — exactly a
    period box's. The band's own drawing stands aside while it is held.
  - **One release**: `onCommitBounds`. `commitBoundsIntent` answers `PlaceDerivedPeriod` for an occurrence a RULE
    lays (`derivedPeriodKind`: the schedule's Sleep window `sleep/{wake day}`, its hour before bed): the night
    leaves the schedule (`SleepSchedule.skippedWakeEpochDays` / `skippedBeforeBedEpochDays` — the way a dragged ring
    leaves its alarm) and the period is laid by `reduceAddRestrictivePeriod`, the panel a fill had laid for that
    night leaving in the same unit. Two units, both in the CALENDAR history: one Ctrl+Z takes the period away, the
    next gives the night back. A Sleep window the past recorded (a stored panel) goes through `UpdateTaskPanel`,
    which makes it the user's `sleep` period. A **screen break** is not the state's: `App` hands it to
    `SchedulerEngine.placeBreakByHand` (`screen-breaks.md` § *A break is put by hand*).
  - **What stands where it was is never the period again**: the schedule lays no window (and no hour before bed) for
    a skipped night; the machine lays no break of that role between where one was picked up and where it was put.
  - Whether a break band may be held is asked AT THE PRESS (`movable`: not the one the line is in or drags), never
    read in composition — a read of the line there would recompose every past column with it.
  - `DraggedSleepWindowTest`, `MovedPastBreakTest`. Open: a dragged Sleep window has no hour before bed of its own
    (that hour is derived from the schedule's windows only); the wake alarm and the quota's nights still read the
    schedule's hours for a skipped night.
- **THE SEARCH WINDOW'S "DRAG ON THE CALENDAR" HOLDS BLOCKS FROM ANOTHER WINDOW** (user rule 2026-10-07;
  `CalendarElementDrag`, `LocalCalendarElementDrag`, `CalendarDragEditor`, `CalendarElementDragTest`). The press is
  on a button of the Search window, so the drag cannot be a gesture of the calendar's: the holder is what the two
  share. Each day column on screen LENDS it the instant a window position is on it (`millisAt`, the column's own
  reading), the records it draws, and `onCommitBounds`. **The field selects the blocks**: the app's one check-box
  drop-down over every block the added elements have on the days shown (`blocksOf`), checked by default on the ones
  at the right-click the window was opened from (`calendarDragBlocks` — the reading "edit…" lists them by). **How
  they follow**: every block by the same time — the instant under the pointer less the instant they were chosen at
  (the right-click's; the earliest block's start for blocks picked by hand) — so each keeps its place under the
  pointer; off every column they stay. **The release** is each block through `onCommitBounds`, at `targets()`: a
  period where the hand has it, a task panel over the span that keeps its length across the breaks refusing it.
- **HELD AND RELEASED LOOK THE SAME; THE ONE DIFFERENCE IS THAT WHAT THE HELD BLOCK REMOVED IS REMEMBERED** (user
  rule 2026-10-07: *"The only difference there must be between keeping the mouse click and having released it is that
  whatever got removed when the dragged element got there is remembered if the mouse click is not released. If period A
  is dragged to period B and period B doesn't have to retract, then it doesn't. If it must retract, it retracts."*).
  **By the release's own code, for every drag** (`App`: `calendarMoveOf`, `calendarMovePreview`;
  `HeldDragPreviewTest`). What a release does is said ONCE (`calendarMoveOf`: the intent, or the engine's break
  move); `onCommitBounds` runs it, and while anything is held the same moves are run through the same reducers on a
  copy of the stored state — nothing saved, the plan not asked again — and `deriveCalendarDisplay` draws THAT state.
  So a period over a period fuses or not exactly as the reducer says, a task under a period gives way, the "no
  screen" a sleep period carries (its layers) stands where the period is held, the place it left shows what the
  edges choose — and **"remembered" is the stored state itself**: every step of the drag is asked of it again, so
  what was removed is back the moment the block moves on. (Released, it is no longer gone for good either: what a
  placed block covers of another placed block is kept on a hidden level — *THE TIMELINE IS THE OVERLAP OF THE
  `tm_levels`*, above.)
  - **From the Search window** the held state is what the calendar is given (`shownState`, `shownFrozenBreaks`).
  - **In the calendar** the column tells `App` what it holds (`CalendarElementDrag.heldInCalendar`: a period box or
    band from `movedPeriods`, a task panel from `dragPreview`, where the hand has it, to the minute) and the week
    view draws the released calendar in a second column per day (`LocalHeldCalendarRecords`, `heldRecordsPerDay`) —
    UNDER the column that holds the press, which stays with the records at rest and is not seen
    (`graphicsLayer { alpha }`): **a gesture's nodes must not move under the press**, so the released records are
    never fed to the column holding it. The drawn-only columns are given a holder of their own and report nothing.
  - The column's older drawing-only previews (`blocksForPeriodDrag`, `periodsForBlockDrag`, `HeldPeriodCut`…) are
    still computed under it and seen only where the release would do nothing (a period the line leaves nothing of).
    They are a second reading of what a release leaves: do not extend them — remove them when the gesture no longer
    needs their bounds.
  - Cost: a reduce and a calendar derivation per minute the hand moves the block by, and two columns per day, only
    while something is held. Not yet measured on a large account.
- **WHAT STANDS WHERE A DRAGGED PERIOD WAS IS CHOSEN FROM ITS EDGES** (user rule 2026-10-07;
  `SchedulerDomain.vacatedPastFill`, `SchedulerReducer.withVacatedPastFilled`, `VacatedPeriodFillTest`). Behind the
  line only — ahead of it the plan fills. What is at an edge is the kinds of the periods stated there and the task
  whose work is recorded (or placed) there; the period at its NEW place is what is at the edge it touches. **The same
  at both edges, and not the period again** (it does not carry the kind that left, nor both layers where a "no
  screen" left): that is what is put — its kinds laid over the span where no period already states them across it,
  its task's work given back (less the breaks banked there). **Anything else: a period of `inactivity` alone**; bare
  edges put nothing. One funnel for the three ways a period leaves a span: a stored period moved
  (`reduceUpdateTaskPanel` — BOTH ends moved; a resize is not a drag elsewhere, and a repeating period is a pattern that leaves no one span), a Sleep window or hour before bed
  dragged (`reduceAddRestrictivePeriod`'s `vacated`), a screen break (`FillVacatedBreak`). In the SAME unit as the
  move for the first two (`PanelDelta`'s `records` half): one Ctrl+Z puts the period back and takes the fill away.
  **The layers count at an edge** (`SchedulerReducer.layerKindsAt`, injected by `App` over the layer bands the
  calendar draws — the devices' own history included): the user's example is *"'no phone unlocked' and not ('no
  computer unlocked' or 'not on a computer') and task A"*. A layer both edges carry is laid as that layer's period
  where the calendar does not already draw it across the span; what the rules only derive from the layers is never
  laid; and both layers at both edges are the "no screen" that left — inactivity.
- **A REMINDER'S CHIP IS DRAGGED LIKE A RING** (user rule 2026-10-07 — *"the block (or chip) being dragged is the one
  at the top of the priority rank"*; `clickOrDoubleClickHeldDrag`, `DraggedReminderTagTest`). The press already went
  to the top of the rank everywhere else (tag, ring, break, panel, period — the order they are drawn in); the tag was
  the one top of the rank with no move. Its node has a click too, so the check-off is told once the double-tap
  window has passed with no second press — a drag never checks the tag on its way (a touch tap is immediate). The
  release goes through `onCommitBounds`, where `App` saves the tag the way its edit window does
  (`AddCalendarElements`, the tag's own draft at the new instant) and PINS it. A tag the state holds no panel for (one
  projected past the schedule's window) is not moved — the same limit its edit window has.
- **A PAST BLOCK DRAGGED OFF ITS RECORD IS ONE UNDO** (`reducePinRecord`, 2026-10-04): the record period leaving
  and the panel arriving are one `PanelDelta` (its `records` half), so Ctrl+Z puts the block back where it was.
  The record used to leave outside the history, and undoing the drag made the block vanish.
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
- **"add…" OPENS THE SEARCH WINDOW OF WHAT CAN GO AT THE RIGHT-CLICK** (user rule 2026-10-01; `App.openCalendarAddSearch`,
  `SearchDomain.calendarAddConfig`). **It opens with the type selector deployed** (user rule 2026-10-01), new window or
  reused one, so the types are picked right away: `App.searchKindsToDeploy` names the window (`openNewWindow` returns
  the frame id it opened or brought back) and the window's `CheckBoxDropDown(deploy)` opens the list once, a frame
  after it is laid out, then takes the request back — a one-shot, never reopened by recomposition. "edit…" does not.
  It no longer opens the element window below ("edit…" still does). The Search
  window's **calendar filter** — a GLOBAL setting of the Search configurations window (`Setting.CalendarAdd`: a switch,
  a `YYYY-MM-DD HH:MM` position, and "Set to the right-click", offered only while `Config.calendarClickMillis` says the
  window came from the calendar) — keeps only `SearchDomain.calendarAddable` rows: a placeable task, WHATEVER
  PERIOD STANDS THERE (anomaly 2026-10-07: what the periods refuse is the "without removing anything" state's
  question, `task-tree.md` § the three states); any kind
  of period; a reminder; an **alarm** and a **timer** (user rule 2026-10-01 — a timer only while the instant is ahead
  of the clock and within its longest run); a "creation" row of a kind the calendar lays. Its action **"Add to the
  calendar"** lays the added ones at its START (`Config.placement`, user rule 2026-10-05: a day and a time, the
  filter's position until one is given — `SearchDomain.placementStart`; beside it, and beside the END when that is a
  day and a time, "Now" and — only while `Config.calendarClickMillis` says the window came from a right-click on the
  calendar — "Right-click time", user rule 2026-10-07) through `SearchDomain.calendarDrafts` — the element window's
  own seeding (`CalendarElements.seeded`) and Save (`saveCalendarElementIntents`); an alarm is the window's edit of that
  alarm (`existingId`): it rings at that time of day, that weekday added to its days, switched on — and a timer is put
  on the clock to END there (`calendarTimerIntents`: reset, time left = instant − now, started; one `SetTimers`).
  A NEW alarm is laid by the alarms' "creation" row once it is among the added elements (`calendarDrafts` ->
  `calendarAlarmDraft`), like every other thing the action lays — never by a button of its own: "New alarm here" stood
  there whatever was added, beside a lone task too (anomaly 2026-10-07). ONE such window: a later "add…" moves it to the
  new right-click, keeping what it holds.
- **The blue and the orange outlines are above every other outline** (user rule 2026-10-05; `outlineOnTop`, the day
  column's **outline pass**). The column's LAST drawing before the labels and the two markers is every `User` and
  `Pattern` outline again — a sleep window's, a period box's (`PeriodSegmentMarking(outlineOnly)`, a held box
  included), a hand-placed panel's slices — as a border and nothing else: no fill, no marking, **no pointer input**
  (the "never a lid" rule: what is underneath keeps its hover, drag and right-click). Under the panels a period's
  blue was covered and redrawn in a contrast colour that was not blue, and a panel's own was under the grey outline
  of a break. So a sleep band and a period box no longer draw a top outline in their own (under-panel) pass, and
  `PanelDecor` redraws for contrast only an outline that STAYS under the panel — a grey one
  (`PanelDecorBand.outlined`). Grey and a block's plain border stay with their element. A new outlined thing drawn
  under something else owes the pass a line; one drawn topmost (a ring, a tag) does not.
- **A task panel is opaque, in its task's colour, and redraws what crosses it for contrast** (user rule 2026-10-01;
  `CalendarBlockBody(opaque)`, `PanelDecor`). The 30 % wash is gone for a task panel (it merged the colours
  `TaskColorCurve` keeps apart); the title and the plain border take `TaskPalette.foreground`. The column's
  decorations that run across it — the period markings and outlines, the sleep bands', the layer hatches, the hour and
  graduation lines — are drawn UNDER the panels (`zIndex(-1f)`) in their own colour, and each panel redraws the parts
  that cross it in the contrast colour, its tiles aligned on the column so a line crossing the panel's edge does not
  break. A block with no task colour (a period-only no-screen / inactivity block, an orphan panel) keeps the wash, the
  markings showing through. Not redrawn: the day boundaries and the now-line / alarm / reminder markers, which stay
  above everything.
- **"edit…" (and "edit [element]") OPEN THE SEARCH WINDOW OF WHAT IS AT THE RIGHT-CLICK** (user rule 2026-10-01;
  `App.openCalendarSearch(add = false)`, `SearchDomain.calendarAtConfig`): the GLOBAL filter "Is on the calendar at"
  (`Setting.CalendarAt` — a switch, a position, "Set to the right-click") keeps the keys `SearchDomain.calendarElementsAt`
  gives: a task one of whose boxes or records covers the instant, every kind of period covering it (with what each
  carries), and — the marks with no length, which the calendar hit-tests by their drawn height — a reminder's tag, an
  armed alarm's ring and a running timer's end within `CALENDAR_MARK_TOLERANCE_MILLIS` (15 min). "add…" and "edit…"
  share ONE such window: each right-click moves it, its filter switched to the entry's. **"edit…" or "edit [element]" is
  decided by the THINGS at the cursor** (`calendarThingsAt`, anomaly 2026-10-01): a task (however many boxes), a kind of
  period, a reminder, an alarm, a timer — **the LAYER bands included**, which are in no hit list (drawn across the
  column, they displace nothing), so a task over "no phone unlocked" is two things and reads "edit…". The Search filter
  sees the same bands: `App` keeps the last drawn `layerRecords` (`CalendarLayersHolder`) and hands their kinds in
  (`SearchDomain.results(layerKindsAt)`). A double-click on a block still
  opens that block's own editor. The element window below is opened by neither entry any more.
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
  evidence — no regions, no cut. Display-side: the regions are the past,
  the fill only places ahead of the now-line, and what the OS reports is not a user edit. What the cut vacates
  is idle time and draws as a derived "Inactivity" band. **A panel the user PLACED is never cut by it**
  (`SchedulerDomain.isUserPlaced`, user rule 2026-10-02): a hand placement is the user's word that the work
  happened there, it keeps its length, and the derived band gives way instead — at rest exactly as the drag's
  preview draws it (`inactivityBandsAfterMove`). Cutting it was the anomaly "a past panel dragged there is cut on
  release": the preview drew the release the reducer makes, and this display clip then cut what was saved.
- **A SCREEN BREAK'S BUBBLE NAMES THE "NO SCREEN" IT IS ACCOMPANIED BY** (2026-10-02, `contextOverlays`): over
  each break band's own span, its kind's companions (`companionBubbleSections(breakKind)`) and always "No screen"
  — the requirements' *"always accompanied by the 'no screen' period"*, the 20 s look-away included, whose kind
  (`inactivity`) carries nothing by rule.
- **NO PERIOD DRAWING IN A BLOCK TITLE'S TEXT AREA** (2026-10-02, `CalendarBlockBody`): what a task panel redraws
  over itself (`panelDecor` — patterns, outlines, layer hatches, hour lines) stops at its title: the title's box, as
  wide as the words and not the block, is painted the block's own colour. Only where the block is opaque and carries
  a decor; the title is no longer `fillMaxWidth`.
- **"edit…" LISTS A SCREEN BREAK** (2026-10-03): the breaks are not `state.panels` (banked locally behind the
  line, predicted ahead), so the Search window's "is on the calendar at" is handed them with the layer bands
  (`CalendarLayersHolder.breaks` ← `displaySidePanels`, `SearchDomain.calendarBreaksAt` / `calendarBreakKinds`): the
  break's own kind ("20s screen break", "5min screen break", "15min screen break"), what it carries, and "no
  screen". The right-click's instant is rounded to the minute, so a break STARTING within the minute is there too
  (a 20 s break was never found at the instant itself), and the layers it lays are read where the break is.
- **A SCREEN BREAK LAYS THE LAYERS** (2026-10-02, reversing "a break is the app's, so it brings nothing"): the
  "no screen" every drawn break is accompanied by counts as STATED (`statedKindRegions(breaks = …)`, `App`'s
  `displaySidePanels` while `showScreenBreaks` is on), so the account's "when no screen then …" rule
  (`NO_SCREEN_LAYERS_RULE`) hatches both layers over it — "not on a …" (dotted) where the OS saw that device
  unlocked, which is a past 20 s break crossed in mode 3. DISPLAY ONLY: the scheduler's periods and
  `isUserStated` are unchanged, and a materialized break is still never an input to its own placement. The hatch
  of a break the line drags is laid at the display's quantized instant.
- **WHAT THE RULES DERIVE FROM THE LAYERS IS NAMED IN THE BUBBLE, AND THE LAYERS GIVE WAY TO A PLACED PANEL IT
  REFUSES** (2026-10-02). Where "no computer unlocked" and "no phone unlocked" both fall, the account's rules
  say "no screen" (`SchedulerDomain.kindsDerivedFromLayers`, i.e. `PeriodKindConfig.closeRegions` over the
  bands — never a hard-coded intersection). It has no drawing (the two hatches are the drawing), so the hover
  bubble names it (`derivedLayerPeriods` → `contextOverlays`), except over a stretch a stated period or sleep
  band already names that kind for. A task panel the **user placed** that a derived kind refuses keeps its
  length and the **layer bands are cut under it** — so the derived period is gone there too, "all three
  retract" (`SchedulerDomain.layerRetractionCuts`, `layerBandsAroundPlaced`, `placedPanelSpans`). One layer
  alone refuses nobody and is not cut. At rest the column cuts by its blocks outlined as the user's; during a
  block drag `shownLayerBands` cuts by the dragged block at the preview's bounds, from the bands AT REST, so
  they give way as the block arrives and grow back as it leaves. **The cut is taken where the panel STANDS**
  (anomaly 2026-10-08): a screen break that refuses the panel's task holes its drawing (`layoutWithBreakHoles`),
  and `placedPanelSpans` leaves the same holes out, asking the same question of the same break kind — so under
  the 15-minute pose the line carries, where a held panel is retracted, both layers are still drawn. A panel a
  break covers end to end is retracted WHOLE — one slice of no height at its start, kept so the hand still has
  something to hold — and takes nothing from the layers; it is never drawn entire inside the break. `App` still hands the column the UNCUT bands;
  its Search filter (`CalendarLayersHolder.kindsAt`) asks the same two functions at the one instant.
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
  The other past Sleep is **frozen by the line** (`docs/scheduler_requirements.md` § *frozen past*; 2026-10-05): the
  part of a scheduled Sleep period the line crossed in mode 2 or 3, covered by "no screen", stays behind it; the part
  crossed in mode 1, where its "no screen" retracts at the line, does not. `SchedulerEngine.freezeSleepBehindLine`,
  called by the one interpreter — live, on a wake's fast move, on the catch-up of a stretch the app did not run in —
  with two armed triggers: the end of the Sleep period, and the mode edge back to 1. The start of the away stretch is
  in memory; a new process takes it back from the persisted break machine (`BreakMachine.State.stretchStart`), so a
  process ended at a locked computer loses none of it. Never put back a second writer
  beside it (the step it replaced read the active sessions for what "this session" had seen, and a computer shut
  down every night never recorded one night). An account this device never ran has no stretch to walk, so an emptied
  account still assumes no past sleep. `PastSleepAfterShutdownTest`.
- **Known inconsistency:** layers **and the §9 record bank** read the OS lock history; the engine's pause
  derivation still reads `device_active_session`. Decide this before adding anything else that reads one and
  not the other.

### Day and week display modes

User spec 2026-10-01. `ui/CalendarUi.kt` (`CalendarDisplayMode`, `columnStepPx`, `columnDayShift`,
`columnOffsetPx`); the configuration section's **Display** field; `CalendarDisplayModeTest`.

- **ONE grid, and the mode is one number.** Every column is the same endless vertical timeline read further
  along than its left neighbour: by a whole day in **week** mode, by the **viewport's height** in **day** mode
  — so the timeline reaching the bottom of a column resumes at the top of the column to its right. Never a
  second grid, a second `DayColumn` or a second scroll: column `c` draws `anchorDay + columnDayShift(c)` at
  `columnOffsetPx(c)`, and week mode is the case where those are `c` and `offsetPx` (said outright, not
  computed, so no rounding can draw a column a day off).
- **A scroll is the one `offsetPx`**, as before: scrolling down moves time forward in every column at once, so
  the timeline leaves at the top of the leftmost column and enters at the bottom of the rightmost.
- **A zoom never moves the columns**: the step is the viewport's height, which the zoom does not change. The
  focal point is the pointer's height **plus its column's step** (`applyZoom`'s `focalColumn`), so the instant
  under the pointer stays under it.
- **Locked, the line is at the middle of the LEFTMOST column** (`centerOnNowLine`'s day-mode branch — an
  absolute placement; the week's "nearest occurrence" rule has nothing to choose between here). The same lock
  serves "Locked on task".
- **Each day-mode column has its own time gutter and draws its own day boundaries** — its hours and its
  midnights are not its neighbours'. That is why there are fewer columns than seven: as many as fit at
  `DAY_MODE_COLUMN_MIN_WIDTH`. The cull windows are per column for the same reason (`hourWindows[column][row]`).
- **`columnShifts` is a derived state**, so in day mode the columns recompose when a column's top crosses a
  midnight and not per scrolled pixel; the rows are still placed by a layout-phase read (`display-hot-path.md`).
- **A date pick in day mode** anchors on the picked day's midnight and zooms so the whole day spans the columns.
- Compose-only view state held by `App` (it survives closing the window, not a restart): never persisted,
  never synced.

### "Go to calendar" and the lock on a task

User spec 2026-09-26. `scheduler/domain/CalendarLockDomain.kt`; the task cell menu's entry
(`TaskCellMenuItems`, `LocalCalendarGoTo`); the calendar's "Locked on task" switch.

- **ONE lock, held on one instant.** "Locked on task" is "Lock to now" with another instant at the middle of the
  view (`WeekView(lockTaskMillis)`): the same centring, released the same ways (a scroll, a date pick). The two
  are one or the other — "go to calendar" and the switch turn "Lock to now" off, and "Lock to now" turns the task
  lock off. Never a second centring mechanism.
- **Space turns "Lock to now" on, and does nothing else** (user rule 2026-10-02). It is read on the way back UP
  (`onKeyEvent` on the calendar frame), so a field being edited takes its own space first. So **no control of the
  configuration section may take the keyboard** (`leavesKeyboardToCalendar`): a focused `clickable`/`Switch`
  consumes Space as a press of itself, which made Space flip whichever switch was clicked last (2026-10-03). A new
  control in that section opts out the same way.
- **The focused calendar HOLDS the keyboard, however it was focused** (`AppWindowFrame(keyboardFocus = …)`): the frame
  hands the calendar's key node the focus every time the frame host makes it the focused window — its window-bar tab,
  a lateral-menu button, `Shift+Alt` navigation, the launch — not only on a press inside it. Its tab showed purple
  while Space went nowhere until a click inside (2026-10-03). Any window with key handlers of its own passes its node
  the same way.
- **The instant is the middle of the task's panel CLOSEST to the now-line** (a scheduled or pinned panel, a
  PROVISIONAL one — the far-week plan the calendar draws past the definitive-schedule front by the rules in force,
  not settled yet, but a panel on the calendar all the same — or a recorded period; the one the line is inside
  wins), re-read whenever the panels or the display's now move — so the
  lock follows a panel the line drags or a new set of rules moves. With no panel of it yet but one to come (a
  schedulable leaf with a priority above zero) it is the **definitive-schedule front**
  (`definitiveScheduleFrontMillis`), and the switch and the menu entry show a loading mark, until a panel appears.
- **The entry is offered only where a panel exists or can come** (`CalendarLockDomain.reach`, asked of the live
  state as the menu opens). One entry for every surface drawing a task cell's menu — provided once by `App`, never
  threaded through each tree drawing.
- Compose-only view state (`App`: the task, on/off), like the now-line lock: never persisted, never synced.

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

