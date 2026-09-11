# Screen breaks, cues and notifications

Active invariants. Reasoning and post-mortems: `docs/adr/`. Dated log: `CHANGELOG.md`.
Global rules that always apply: `CLAUDE.md`.

---

## Screen breaks — the three dynamic restrictive periods

→ ADR 0003, `side-dev/README.md` § *$t_p$ and 3 Dynamic Restrictive Period*. The three are the 20-s
look-away, the 5-min pose, the 15-min pose. Terminology: **"screen breaks"** everywhere — UI, docs, code
identifiers, persisted keys.

- **All three have the kind `no task allowed`, end to end.** There is no shape to read any more: no closed
  head, no "off-screen only" tail, no *doable during a screen break* switch. A task works through one exactly
  when it has been given a non-zero resilience to that kind — the same sentence, and the same code path, as
  any other kind. `ScreenBreakPeriod` is gone; do not reintroduce a per-break accepted set.
- **Where they fall is `DynamicPeriods`, and nothing else.** Three recurrence bars, verbatim from the README:
  after **any** dynamic period no 20 s for **20 min**; after a **≥ 5-min** rest stretch no 5 min for **1 h**;
  after a **≥ 15-min** one no 20 s for **20 min** and no 15 min for **2 h**. Where they would overlap, the
  chain collapses to its **longest member starting at the chain's earliest point**.
- **A rest stretch takes all three of its clauses**: *covered by* "no on-screen task" (or `no task allowed`,
  which refuses the on-screen tasks a fortiori), *without any task* (a period that still accepts somebody
  makes none, and a **pre-placed task IS a task** — an hour of maintenance is not a rest), and it is a
  **stretch**, not a period: two that abut make one. `blocked` and `rested` are deliberately different sets —
  any emptiness absorbs a period that would fall inside it; only the part of it that is a rest bars what
  follows.
- **The anchors are DERIVED, not stored.** There is no `lastRest`-driven grid, no 5↔15 merge, no
  "a pause re-anchors shorter pauses", no decoupled-pose special case. Every one of those said "a rest bars
  the breaks that follow it", which the bars say once — and the rests are read out of the timeline itself, so
  a live pause reaches the placement as the period it is (`liveRestPeriod`) rather than as an anchor overlay.
- **A rest has to BE on the timeline for the bars to see it, and there are exactly THREE ways it gets there**
  — `SchedulerDomain.dynamicPeriodBase`, the one funnel every caller asks through: the standing periods a
  set of panels holds (`restrictivePeriodsOf` — what the user drew, the §17 sleep windows, a break the app
  conducted), the pause this device is living through **right now** (`liveRestPeriod`), and **what the devices
  OBSERVED** (`observedNoScreenPeriods` over `SchedulerReducer.noScreenEvidence`). The third was missing until
  2026-08-29 and its absence is invisible from any one of the others: the live gap only ever holds the pause
  this device is in the middle of, a derive retires it and a restart clears it, so a pause that had simply
  **ended** left no mark at all and the bars went on counting from the last recorded break. It is
  `PeriodKinds.NO_SCREEN`, because "nobody was at a screen" is the whole of what the evidence says — which is
  also why an account with an off-screen task correctly gets no rest stretch out of it (the README's clause is
  *covered by "no on-screen task"* **without any task**). The third carries a **fourth source through the same
  flow**: a stretch the now-line SWEPT in mode 2 (`noteSweptNoScreen` — a wake from device sleep), which is
  mode 2's own rule and not an observation, so it is on the timeline the instant the app wakes rather than when
  the next lock scan lands.
- **The cue sweep, the published pause-cue due and the calendar ask through that same funnel.** They read
  `restrictivePeriodsOf(state.panels)` alone until 2026-08-29, so the instant the app ANNOUNCED a break at and
  the instant the fill PLACED one at were answers to two different timelines — the drift the whole due/place
  split exists to remove.
- **A break has a DUE and a PLACE, and each of the three is announced from the run its own rule makes
  crossable** (`screenBreakCueOccurrencesBetween`, the ONE reading the cue sweep and its self-delay share).
  The due is where the recurrence bars put it with nothing dragged (`screenBreakOccurrencesBetween`,
  `dynamicPeriodPanels`' `atLine = false`); the place is where the line leaves it (`screenBreakPanels` /
  `takenScreenBreakPanels`, `atLine = true`) — what the calendar draws and what the fill obstructs on. A
  **POSE** is announced on its DUE: in mode 1 the line pushes it, so its place is always "starting now", is
  never crossed, and a sweep keyed on it would fire at every scan. (In the away modes nothing is dragged, so
  the due IS the place — the reading is safe in every mode, which is why it is the one asked.) The **20 s
  look-away** is announced on its PLACE, because nothing drags it in any mode — its at-line start is already a
  fixed instant, and it is the one the calendar draws.
- **The two runs are NOT one run filtered, and that is why the look-away is read off the at-line one.** The
  drag re-anchors the bar it fires on, so an owed pose is a placed dynamic period in the undragged run — and
  therefore bars the 20 s for twenty minutes after itself — while at the line it has been dragged away and
  bars nothing there. Read off the undragged run, the app **drew a look-away it never announced** (account 3,
  12:54 on 2026-09-04, with a 15-min pose owed since 12:51) and would announce one where it draws none as
  soon as a dragged pose lands on the line. Both runs are still asked with the WHOLE break list and the
  answer selected afterwards: the chain merge collapses a look-away that touches a pose into the pose, so a
  spec dropped from the list moves the starts of the ones left behind.
- **The placement's origin is anchored on the NOW-LINE, quantized to the day**
  (`dynamicPlacementOriginMillis`), never on the query window's own left edge. The bars are a walk from the
  origin, so the grid is a function of it: the fill asks from `now`, the cue sweep from its scan floor and the
  calendar from the visible span, and quantizing each separately puts them in different days whenever one
  straddles a midnight — which is exactly when the two grids would part company.
- **A materialized break is never an input to its own placement.** `restrictivePeriodsOf` drops
  `screenBreak` panels for that reason; feeding last fill's output back in makes each break a blocked stretch
  that absorbs the next, and the grid walks away from itself.
- **The `t_p` mode is TWO QUESTIONS, asked in order, and BOTH are about the ACCOUNT** (`SchedulerDomain.tpMode`,
  the one place it is decided). **Which devices are unlocked** — mode 1 while any device of the account is,
  `anyDeviceUnlockedAt` reading the input once off the **account-wide pause the calendar already draws**
  (`displayInactivityGaps`, right edge inclusive because an ongoing pause's tail ends *at* the line), so the
  mode and the Inactivity band can never disagree: what the user sees is the mode. And, where none is, **has a
  device SAID it is away**: mode 3 if at least one has the "I'm away" button on, mode 2 if none has. It is
  **not** the Sleep/Work toggle (that says "gone to bed", not "no screen in use" — it was what the code read
  until 2026-08-28). The "I'm away" button reaches **both** halves: it declares its own device idle, like a
  lock does, *and* it is the second question — so on a single-device account pressing it lands in mode 3, and
  pressing it while a phone is still unlocked leaves the account in mode 1.
- **The away flag LEAVES the device it was pressed on, or the second question is unanswerable**
  (`PauseCueGateway.syncDeviceAway` → `device_away`, migration 20260904000000). The spec's rule is *at least one
  device with the button on and every other one locked*, which is a quantifier over the account; a device
  reading only its own flag would put a merely-locked peer in mode 2 while the machine the button was pressed
  on is in mode 3, and the two would then place the three dynamic periods differently. So each device writes
  its own flag and reads back one boolean — "is any device of this account away" — on every away edge and every
  sync moment, never on a timer. The engine's reading is `own || account`: the `or` is not redundancy, it is
  what makes the button take effect at the press, offline or before the round trip lands. **The display reads
  the same two facts** (`App.kt`: `userAway || accountAway`, off `SchedulerEngine.accountAway`) — one of them
  answering the mode differently from the fill is the calendar drawing the three dynamic periods where the
  schedule did not put them.
- **A mode-3 stretch is drawn as one** (ADR 0002, `docs/invariants/calendar.md`): the mode is *at least one
  device away and every other one locked*, so every device's layer covers it and the calendar hatches BOTH —
  which by the layers' own identity makes it a no-screen period. The away device is the half the OS cannot
  report (its screen stays unlocked), so the button feeds that layer itself
  (`SchedulerDomain.declaredAwayRegions`). Mode 3 and "a stretch carrying both layers" are the same set; keep
  them so.
- **Mode 2 is not mode 3, and the difference is the CUE — it is not the placement.** The requirements state the
  two in ONE clause — *"Mode 2 & 3: $now line$ must be covered by the period 'no on-screen task'"* — and a
  dynamic period's kind (`no task allowed`) covers that a fortiori, so being covered and being coverable BY ONE
  OF THE THREE are not two questions. `DynamicPeriods.lineIsCoveredAt` is the ONE predicate the placement reads
  the mode through, and **the two away modes are byte-for-byte one plan**: neither drags, the line walks
  through a break, and the break is then an ordinary fact of the frozen past. There was a second predicate here
  (`breaksAreTakenAt`, mode 3 only) and it made mode 2 place the three where mode 3 did not; it is gone. What
  tells them apart is `DynamicPeriods.breaksAreNotifiedAt`, read in exactly one place
  (`SchedulerDomain.cueCrossings`): **in mode 2 a screen break is never announced** — every screen of the
  account is locked and nobody has said they are taking a break, so there is neither anybody to tell nor a
  break being taken — while mode 1 and mode 3 both announce. The crossing is DROPPED rather than swallowed
  downstream, so nothing marks it announced and a mode that flips back inside the same scan window still says
  what it crossed; the break itself is still placed, still drawn, and still re-anchors the bars. The wind-down
  is not a screen break and is unaffected. It is also not a second reading of the lock:
  `SchedulerEngine.deviceUnlocked()` answers *may this device say anything*, per cue and per device; this
  answers *does the ACCOUNT's mode make this an announced break*, which belongs with the placement.
- **Mode 1 only: `t_p` is never covered by a POSE.** Every pose whose slot the line has SWEPT is pushed onto the
  line as the half-open `(t_p, t_p + duration]` — in discrete time `[t_p + 1, t_p + duration + 1)`
  (`Instance.coveredFromMillis`), which is how it stays an ordinary `TaskPanel`. Three things this rests on:
  the drag **re-anchors the bar at the line**, so at most one occurrence per bar is swept and the chain merge
  collapses what piled up (it is bounded — do not "fix" it by disabling the sweep, which is what
  `sweepFromMillis = t_p` was); a drag is a **move like any other**, put back through the loop so the ordinary
  rules still refuse to place it inside a stretch nobody can run in; and the **frozen past holds because of
  it** — a dragged pose is ahead of the line at every position of the line, so nothing behind the line ever
  turns from a period into a task panel.
- **A period the line DRAGS obstructs nothing in mode 1** (`SchedulerDomain.isDraggedScreenBreak`, the ONE
  reading; the mark rides the panel id, `DRAGGED_BREAK_ID_SUFFIX`, because a dynamic period's panel is derived
  and regenerated by every fill). It follows from the bullet above rather than qualifying it: a dragged pose is
  ahead of the line at *every* position of the line, so **no instant of the timeline is ever inside it** and
  planning around it is planning around something that cannot happen. The requirements say what happens
  instead in as many words — the line *"would continuously delay that period (while creating task panels in
  its passing)"* — so the fill DRAWS it and does not plan around it: only the answers about **what restricts
  the timeline** lose it, while the calendar, the cue sweep and the recurrence bars go on treating it exactly
  like any other placement (it is still what says a break is OWED, and it still re-anchors the bars off
  itself). There are **two** such answers and both must ask the predicate: `fillSchedule`'s `restrictions`,
  and `SchedulerDomain.restrictiveKindsAt` — the display's reading, which PRD §7's task picker colours its
  rows from. A reader that only asks "does this panel cover the line" finds a dragged pose (it is
  materialized as `[t_p + 1, t_p + d + 1)` when the fill runs, and the line sweeps into it before the next
  re-plan pushes it forward), which is exactly how the picker came to paint every task red under a dragged
  15-minute pose. **A third such answer must ask it too.** Until 2026-09-05 it was an
  obstacle, and that broke *No idling* on a live account: the half-open form leaves exactly `[t_p, t_p + 1)`
  free, so a fill placed **one millisecond** of work and idled for the pose's whole length, and every later
  re-plan regenerated the pose at the NEW line and left the entire stretch the line had swept with no panel at
  all — which the calendar draws as a growing grey "Inactivity" band while a device is unlocked and every task
  is free to run. **Neither away mode drags at all**, so neither has anything to drop: the line is covered in
  both, the pose elapses under it and really happens, and an on-screen task may no more run in it than in any
  other period of `no task allowed`. The filter is written as a mode-1 test rather than as "drop the dragged
  ones" so it stays true if a later mode ever drags again. A pose the user actually TAKES is a different object — a
  break the app conducted (`RecordConductedBreak`), a pre-placed period nothing drags — so nothing is lost by
  refusing to obstruct on one that never happened.
- **The 20 s LOOK-AWAY IS NEVER DRAGGED — it is assumed taken** (`DynamicPeriods.dragsAtLine`, the ONE
  predicate; the two poses are dragged, the look-away is not). Looking twenty feet away costs the user no
  working time, so the app takes it as done the moment it falls due: the period stays exactly where the
  recurrence bars put it, the line walks **through** it in mode 3 for its twenty seconds — covered, which is
  precisely what modes 1 and 2 forbid of a pose — and once the line is past, it goes on being drawn where it
  happened. A pose is the opposite case and that is the whole of the split: five or fifteen minutes away from
  the screen is something the user must actually *do*, so an untaken one is **owed** and parks at the line.
  Three things follow. Its **cue keys on its PLACE**, not on a due — the at-line run, the same one the
  calendar and the fill read, so those three cannot drift about it. It stays **DERIVED, never recorded** — `takenScreenBreakPanels`
  re-derives it from the same bars over the recorded past, which is what keeps the past frozen (the
  environment behind the line is a fact), and also why a later change to that environment can still move it:
  press "Look away now" less than twenty minutes later and the bar re-anchors off the break actually taken
  (`RecordConductedBreak`, `RestrictivePeriod.dynamic`). And the labels are **positional** (the shortest of
  the three is the look-away), so an account configured with one break has that break in the look-away's role
  — never key the exemption on a title.
- **A break is never STRETCHED to keep covering the line; the gap behind it is covered instead.** The rule the
  user stated for a line still moving in an away mode: the 20 s / 5 min / 15 min period keeps its own length,
  is frozen where it happened, and what reaches from its end to the line is the ordinary cover below — which
  the calendar draws as the derived **Inactivity** band, or as **Sleep** where the stretch is inside a §17
  window (`sleepPanels` returns a window in progress whole, past part included, and `App.kt`'s
  `pastCoveredRegions` counts a sleep band as covered so no Inactivity is derived under it). Both **oblique
  layers** hatch that stretch by their own rule and need nothing added: in mode 2 this device's OS lock scan
  hatches its own layer and a peer that cannot be asked is assumed locked, and in mode 3 the away button feeds
  its own layer, drawn dotted (`SchedulerDomain.declaredAwayRegions`).
- **A `no on-screen task` chain TAKES the break that falls due in it, and the break is drawn at the chain's
  START** (`DynamicPeriods.chainTaking`, the ONE reading) — the requirements' last bullet in this section, and
  their one sanctioned exception to the **frozen past**. The pause the user spends away IS the break they were
  told to take: the minutes already spent away count towards it, so the calendar draws the pause's first five
  (or fifteen, or twenty) as the break and whatever reaches from its end to the line as the ordinary cover
  below — the derived **Inactivity** band, or **Sleep** inside a §17 window. Clauses, each load-bearing:
  - it is a **chain**, so two periods that abut are one stretch exactly as they are for the bars;
  - it reads the **environment**, never the walk's own output — a placed dynamic period is `no task allowed`
    and would qualify as a chain of its own, where the *chain merge* is the rule instead;
  - the chain must have **taken the break**: one still reaching the now-line is one the user is inside, so the
    break is still being taken (that is the requirements' own clause, in the present tense); one the line has
    left took it exactly when it **outlasted** the break. That second half is a fact of the past and never
    changes as the line advances, which is what the frozen past requires — read as the present-tense clause
    alone, the break the user had just taken moved out of the pause the instant they came back. A chain
    **shorter** than the break took nothing: the user came back too soon, so the break is owed again;
  - a chain gives each of the three **one occurrence** and then bars them like any other rest stretch. One
    pause is one break of each kind, which is all the chain merge would leave of two placed at the same
    instant anyway — and without it the break's own re-anchor lands back inside the chain that just took it,
    is taken again, and the walk crawls forward a millisecond at a time until `MAX_STEPS` stops it;
  - it is **refused in exactly one case**, and refusing it there is mode 1's own rule rather than an exception
    to this one: a POSE pulled back far enough to reach `t_p` would cover it, which mode 1 forbids, so the drag
    is kept.
- **A rest stretch does NOT bar the break it takes** (`DynamicPeriods.barStretch`'s `spared`). The
  requirements' bar is about what comes **after** a stretch, and a break placed at the stretch's own start is
  not after it. This is the whole of why the rule above reached almost nothing until 2026-09-10: a five-minute
  pause is exactly long enough both to BE a 5-min pose and to bar one for an hour, so the bar cancelled the
  break the pause was — the occurrence was pushed an hour past the pause, nothing touched it any more, and a
  break the app had announced and the user had sat through was never drawn at all. The sparing has to be asked
  **per label against every label's current bar**, not for the label whose turn round the walk it is: a stretch
  bars labels other than that one, and both the 15-min pose's own turn and the re-anchor off a look-away placed
  in the pause kicked the 5-min bar back out of it.
- **The mode-1 drag puts an owed pose DOWN at the first stretch the line was not at a screen for**, rather than
  carrying it to `t_p`. The line drags only while it is in mode 1, and a `no on-screen task` chain behind the
  line is the timeline's own record that it was not — the user walked away, and the pose they owed is what they
  walked away to take. Asked with one mode for the whole journey (which is what the past-side re-derivation
  does: `takenScreenBreakPanels` passes the mode NOW), every pose the day owed piled onto the line and the
  pause the user actually spent taking one was left with no period in it at all. Where the chain is too short
  to take it, the drag picks it straight back up and carries on to the next one.
- A pull-back also **floors its own bar at the instant the period fell due**, or the walk can hand the label a
  bar below the slot it just left and spin on it — that guard is what keeps the loop monotone, not `MAX_STEPS`.
- **Modes 2 and 3: `t_p` is covered**, so the gap back to the last such period's end is covered as `no on-screen
  task` — which the resilient tasks may still fill (`DynamicPeriods.awayCover`). Where the app has live
  evidence, that evidence IS the cover: an **ongoing** pause is `closedEnd`, so `liveRestPeriod` covers the
  line and `awayCover` has nothing left to do; in mode 3 the pose the line is inside usually covers it too.
  **"Nothing precedes" is not a reason to answer null**: mode 2 drags the pose onto the line as
  `(t_p, t_p + d]`, which leaves `t_p` itself uncovered by construction, so the cover is then the line's own
  instant `[t_p, t_p]` — and since the fill re-expresses whatever comes back as `[now, now + 1)` anyway, the
  reach behind the line is documentation and the existence of the answer is the rule.
- **The cover is an ENVIRONMENT period, never a panel, and that distinction is the whole of why it was
  missing.** It shipped as an `Away` panel, the calendar drew a synthetic band nobody wanted, and the revert
  (2026-08-31) took the scheduling effect away with the band — so mode 2's own rule reached nothing at all and
  the fill went on starting an on-screen task AT the line while no device of the account was unlocked. It is
  built in `fillSchedule`, straight into `restrictions`; `dynamicPeriodPanels` answers what the calendar draws
  and must not carry it. Its forward reach is `[now, now + 1)` — the README's end is CLOSED, so in discrete
  time it covers the line's own instant, where any other period clipped to the line would collapse and be
  dropped.
- **The now-line NEVER JUMPS — a distant position is a JOURNEY, and waking from device sleep is walked in
  mode 2 except where the account was in MODE 3** (`SchedulerEngine.sweepNowLineTo`,
  `SchedulerDomain.sweepStepMillis`). The README says both halves in
  as many words: the line *"moves continuously forward in time"*, and *"if the device bearing the running
  process is put to sleep, then when the program wakes up, the $now line$ does a fast move forward (in epsilon
  time) in mode 2 to the current date"*. So `reportTimeGap` walks it from the pre-sleep instant to the wake,
  **one minimum execution time at a time** (the reference's `Walk._sweep_step`: the finest thing the walk can
  place, so a line that never skips a whole minimum never skips a placement it should have entered), and
  `advanceTo` is no longer called with a distant instant from anywhere. Four things it rests on:
  - **The mode is the JOURNEY's, not the arrival's** (`sweepMode`, read by the one reading `tpModeNow`). A woken
    machine is unlocked, so anything asking after the fact answers mode 1 — which is the one mode the whole
    night was not in.
  - **Mode 2's rule is what covers the swept stretch**, at once: the line must BE covered by `no on-screen
    task`, so a stretch it swept in mode 2 is covered by one (`noteSweptNoScreen`, unioned into the same
    `noScreenEvidence` funnel the OS scan publishes through, `publishNoScreenEvidence`). That is a fact about
    the mode and owes nothing to a device observation — without it the cover waited on the 10-minute lock scan
    (`NO_SCREEN_EVIDENCE_REFRESH_MILLIS`) and a **break fell due the instant the user came back**, the bars
    still counting from the last recorded break. A pause that has merely ENDED reaches them by no other route:
    `liveRestPeriod` only ever holds the one this device is in the middle of.
  - **The journey does NOT re-plan**, and that is the README's own answer for it: *"it is similar to a case
    where no CPU were available during this period and the current set of rules … is used to define the
    schedule as the $now line$ does its fast move, while no better set of rules was found"*. Every step is an
    ordinary `AdvanceSchedule` — the plan in force writes the past it passes — and the re-plan belongs to the
    landing, through `requestReschedule` like every other one.
  - **An ordinary tick is one commit**, so this costs nothing on the hot path (ADR 0009): 30 s is far inside the
    first step. The one sanctioned approximation is the stride widening to keep a very long journey inside
    `MAX_SWEEP_STEPS` — the README's *"if exact schedules cannot be found in time, approved approximation
    strategies must be used"* — and it is logged when it bites.
  - **A stretch the ACCOUNT declared away for is walked in mode 3, and the app ASKS THE SERVER for it**
    (`away_spans`, maintained by `sync_device_away` / `publish_presence` / the cron pass, never by a device's
    opinion of the mode) — the
    README's own amendment: *"When the app wakes up, it asks the server for any changes. If there was a period
    of $now line$ mode 3, then the fast forward move … will get in mode 3 at those periods, instead of always
    mode 2."* The ask belongs to the CALLER, not to `reportTimeGap`: the tick loop is already a coroutine, so
    `awaySpansFor` (this device's own record ∪ the server's, merged, time-bounded, best-effort) is asked there
    and the journey itself stays the single synchronous walk it has always been. A step never straddles a
    span's bound, or half a placement is committed in a mode that did not hold for it. Both records are
    needed: the local one covers a journey the clock made while the app ran (a debug leap), the server's the
    one case a local record cannot — the app was asleep for the whole episode, which is exactly the journey a
    wake has to walk.
- **A mode flip re-plans** (`launchTpModeReschedule` → `requestReschedule`) and that is not "time passing
  re-plans": the flip is an edge the platform announces. It cannot go in `schedulingSignature` — the mode is
  not in `SchedulerState`, being a fact about the devices and not about the account's data, which is also why
  it is never synced. The reducer reads it through the injected `SchedulerReducer.tpMode` seam.
- **The SCHEDULER RETURNS A SET OF RULES, and that set is the whole of what the server is told about where
  breaks fall** (`SchedulerDomain.poseWindowsBetween` → `publish_break_rules` → `screen_break_rule`, migration
  20260904000000). For the whole of a mode-3 episode every screen of the account is off, so nothing local is
  watching the line cross a break; the `t_b` cron moves the line over the published rules and pushes the phone
  a cue for the END of the one it is inside (`tick_pause_cues` pass (c) → `claim_mode3_break_cue`, ADR 0006).
  **The server never runs the scheduler** — its whole question is `start <= now < end`, and that reading is
  legitimate only because mode 3 is the mode nothing drags a pose in, so where the scheduler placed one is
  where it happens. `PlanWalk` stays the only copy of the rules.
  **One query, two readings**: the windows the mode-3 evaluation compares against, and the two `device_break`
  dues the walk-away gate asks about the past with, are the same `poseWindowsBetween` call projected two ways
  — never two derivations, which is how the two paths would start naming different breaks. The 20 s look-away
  is not in the set: it is assumed taken, so it is never cued, and its 20-minute cadence would rewrite the set
  for an answer nothing reads.
- **An UNLOCK clears "I'm away", and it is an EDGE, not a poll** (`SchedulerEngine.noteScreenSignal`). The
  toggle overrides the platform screen sensor, so nothing but this would ever take it off by itself — and a
  flag left standing across a return holds this device's session finalized and its presence heartbeat closed
  at a machine somebody is demonstrably sitting at. The trigger is the **lock→unlock transition of the raw
  `screenActive()` signal**, which the OS already announces (`WM_WTSSESSION_CHANGE` / `ACTION_USER_PRESENT`)
  and the engine already receives through `onPlatformActivityChanged`; the active-session beat re-reads it
  only as a backstop for a missed notification, never as the mechanism. **Never add a timer for it.** Only
  that edge clears: a lock while away leaves it on (locking is not returning), an unlock with no lock behind
  it is not a return, the first sample after start is no edge at all, and a host whose signal never flips
  (a non-Windows JVM, iOS) simply keeps the flag the user set.
- **A LOCK silences this device; "I'm away" does NOT — they are two readings of the screen, and one is not
  the other.** `SchedulerEngine.effectiveScreenActive()` answers *is anybody working at this device* — the
  active session, the `t_a` presence heartbeat, the no-screen evidence, and through them the `t_p` mode — and
  the away flag masks it, which is the whole of what the button does. `SchedulerEngine.deviceUnlocked()`
  answers *may this device say anything*, and reads the raw lock alone: a user at a lock screen can neither
  read a notification nor be spoken to, which is exactly why the break-over message for a locked device is
  the **server's** push (ADR 0006) and not this device's sweep. The button's user is routinely still at the
  machine — they left it unlocked so a program keeps running — so silencing them would take away the "task to
  do now" notification they pressed it while still able to act on. **Every §11/§15 output gates on
  `deviceUnlocked`** (the task switch, the look-away's start and its resume, a pose falling due, the
  wind-down) and **no presence site does**; the debug leap masks both, because it simulates a machine that
  went to sleep, which is a lock. Two consequences that are rules, not details: the task switch and the pose
  due are **suppressed, not spent** — the level (`lastNotifiedTaskId`) and the de-dupe (`sidePoseNotifiedDue`)
  are left untouched, so what was owed is announced at the unlock instead of being lost to a de-dupe nobody
  heard — while the look-away and the wind-down are marked either way, being crossings worth nothing late.
  **An ALARM is the one deliberate exception** (ADR 0010): a locked machine is the case it is *for*, so it
  rings ungated, and that exception is about alarms and never about a cue.
- **A break the app CONDUCTED is recorded as a period** (`RecordConductedBreak`), so the past is a fact and
  not a reconstruction. Only on completion: a manual "Look away now" that was superseded leaves no trace.
  **It is marked `TaskPanel.conductedBreak`, and that mark is load-bearing**: the README's FIRST bar keys on a
  dynamic restrictive *period* where the other two key on a rest *stretch*, and 20 seconds is far short of
  either threshold — so without it a look-away the user had just sat through barred nothing and the next
  occurrence fell straight after it. It is also what the "unless they press Look away now within twenty
  minutes" half of the look-away rule above rests on. It reaches the bars as `RestrictivePeriod.dynamic`
  through the one funnel. It is **not** `screenBreak` (which means *regenerated*, and is why
  `restrictivePeriodsOf` drops those); it is the opposite. And it is not "a short `no task allowed` period":
  a 20-second Inactivity the user drew is a **pre-placed** period, which the README bars nothing after.
- The **end** of a break is a notification, not only a voice cue — as is every other thing the app says:
  since *every notification has a voice*, "notification" and "cue" are two halves of one call and never two
  mechanisms (§ *The Notifications switch* below).
- A screen-break panel has **no Edit** (no editable object behind it). A sleep band's menu leads with Edit.

### Notification / voice-cue triggers

Must be **mathematically accurate** — a pure function of which boundary instants the clock crossed (each
fires exactly once, in order), never of how a sweep/heartbeat happens to align with the calendar.

**Every break cue keys on the START its own placement rule makes crossable** (`cueCrossings` →
`screenBreakCueOccurrencesBetween`): a **pose** on its undragged DUE, the **20 s look-away** on its AT-LINE
placement (§ *the three dynamic restrictive periods* above). The instant the line reaches that slot is the
instant the break falls due, which is exactly when the app should say so, and it is crossed once. The sweep
must be handed the **same environment the fill was** (the standing periods and the tasks), the same now-line
anchor and — because half the reading is the at-line run — the same `t_p` **mode**; asked without them the
bars answer a different timeline. The sweep's **self-delay reads the same function**, or it sleeps past the
instant it is waiting for; read off an anchor instead, it found no next boundary at all and stopped. The
pause cue's `nextScreenBreakStartMillis` is the pose half of that reading, so the server and this device key
on one instant.

**A break's start notification says what the break RUNS OUT INTO** — one function,
`SchedulerDomain.screenBreakStartNotificationMessage` over `screenBreakFollowOn`, so the cue sweep and the
manual "Look away now" cannot word one break two ways. The rule is a pair of coverage questions asked of
`state.panels`: the break's START is covered by no qualifying period and its END is (half-open, so a period
beginning at the break's own end instant IS what follows it — the case this exists for). Qualifying is what
the calendar already draws as *the user is away from the screen here* and only in the two shapes PRD §15
names: a period the **USER** drew (`ScreenBreakFollowOn.UserPeriod`) and §17's **`before bed`** hour
(`BeforeBed`). Four exclusions, each a rule: a **dynamic period** and a **conducted break** are breaks, never
the freedom after one; a **derived sleep window** is not announced because the wind-down hour always precedes
it, so a break reaching one started inside `before bed` and the first clause has already refused it; and a
break wholly inside a qualifying period says nothing at all — the user was off the screen when it began. The
window asked about is the break's own in the run **its own cue keys on**: a look-away's placed
`[start, end]`, a **pose's DUE and one duration after it** (it is announced at its due and taken from there,
so the placed at-line drag is not what the user is being told about).

Staleness is judged only by the crossing's REAL age (`BoundarySweep`, 2-s budget), never by sim distance or
scan-window position. Consecutive scans must tile the timeline with no gaps (`scanFloorMillis`), so no
crossing can be silently clipped by a clock jump.

### The Notifications switch silences the OUTPUT, never the record

The lateral menu's **Notifications** switch and `Ctrl+Shift+Alt+N` are one lever
(`SchedulerState.notificationsEnabled`, persisted + synced, not an Undo/Redo unit).

- **`SchedulerEngine.notifyUser` is the ONE funnel and the ONE place the switch is read.** Every notification
  the app posts goes through it — a break's start and end, "task to do now", the wind-down, an alarm, a
  chord's own receipt — so there is no exempt caller and no second gate. A mute with a list of exceptions is
  not a mute; never add the check anywhere else, and never post around it.
- **Every notification is SPOKEN from that same call.** A notification is written for a user who is not
  looking at OmniApp, so a silent one only reaches somebody already watching the corner it appears in. The
  funnel posts and speaks together, from one text and at one instant — which is what makes it impossible to
  say one thing and show another, and impossible for a new notification site to be silent by forgetting to
  add a cue beside it. Never speak a notification anywhere but here.
- **A phrase with a bundled recording is named, and everything else is synthesized from its own text.**
  `notifyUser`'s `cue` parameter is passed by exactly the two notifications §15 fixes word for word (the
  look-away's start and its resume), so those keep the shared Piper voice; every other phrase carries a task
  title, an alarm's label or a chord and cannot be pre-rendered, so it goes to the platform synthesizer
  ([`VoiceUtterance`], `spokenNotificationText`). Do **not** grow `VoiceCue` to try to cover them: an enum
  that cannot say a task's name is not the funnel for a notification that must.
- **The mute silences BOTH halves; the voice switch silences the voice alone.** `notificationsEnabled` is
  "cancel every notification", and the loud half is not the one it may leave running. `notificationVoiceEnabled`
  (persisted under its original key `lookAwayVoiceEnabled`) is the switch for the spoken half — with it off
  the notifications still post, silently.
- **The ONE voice with no notification behind it** is the pause-over cue an OS alarm fires on a phone whose
  user has walked away from every screen (ADR 0006): there is nobody to read anything and the app is not even
  running. Every other phrase the app speaks is a notification.
- **The log is written BEFORE the platform call, muted or not.** The History window's **Notifications** source
  answers "what did the app decide to say", which is why it was never proof of delivery — and why the switch
  can silence the interruption without touching the record.
- **Switching off also withdraws what the OS is still showing** (`cancelSystemNotifications`): a notification
  sits in Android's shade / iOS's Notification Centre until dismissed, so "cancel every notification" has to
  answer the pile already on screen too. The desktop actual is a deliberate no-op — a tray balloon cannot be
  recalled.
- **Switching back on posts one notification saying so, and that is load-bearing.** The chord's receipt is
  raised before the action, so on the un-mute press it is still muted and swallowed; this is that press's
  receipt, posted from the far side of the flip. Turning them *off* announces nothing extra — the receipt for
  that press goes out normally, just before the mute takes hold.
- **The LOCK gate is not a second mute, and that is why it is not here.** The switch answers *may the app
  speak at all*, which is one question with one funnel; `deviceUnlocked()` answers *is there anybody at this
  device to say it to*, which is asked per cue, before the funnel, and decides whether the cue happens — the
  look-away's start has always been asked it. Do not fold it into `notifyUser`: an alarm rings a locked
  machine on purpose (ADR 0010), and a funnel with an exception is what this section forbids.
- It says nothing about the **schedule**: a break still starts and ends where it did, silently.

---

