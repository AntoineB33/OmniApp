# ADR 0003 — Screen breaks: the three dynamic restrictive periods

**Status:** active (rewritten 2026-08-27 to `side-dev/README.md` § *$t_p$ and 3 Dynamic Restrictive
Period*; the two `t_p` modes wired through 2026-08-28; the wake from device sleep made a mode-2 journey of the
line 2026-09-03). **Invariant summary:** see `CLAUDE.md` → *Screen breaks*.

Terminology: the eye-care breaks are named **"screen breaks"** everywhere (PRD §15) — UI, docs, code
identifiers (`ScreenBreak`, `screenBreak*`, `showScreenBreaks`, `DEFAULT_SCREEN_BREAKS`, …) and persisted
JSON keys. The legacy "side task" names were fully renamed; see `CHANGELOG.md` for the mapping.

The three breaks: the **20-s look-away**, the **5-min pose**, the **15-min pose**.

---

## What they are

**All three are restrictive periods of the kind `no task allowed`, end to end.** That is the README's own
sentence, and it settles what used to be three separate questions:

| Was | Is |
| --- | --- |
| the look-away accepted nobody | it accepts whoever is resilient to `no task allowed` — by default, nobody |
| the 5-min pose was a closed first minute, then the `doableDuringBreak` tasks | one span of one kind |
| the 15-min pose accepted every `!onScreen` task from its first second | the same one span of the same kind |

So there is no *shape* to read (`ScreenBreakPeriod` is gone, and with it
`screenBreakOpenStartMillis` and the "hollow tail" the calendar drew off it), and no `doableDuringBreak`
switch for a shape to read. A task works through a break exactly when it has been given a non-zero
resilience to `no task allowed` — the same sentence, and the same code path, as any other kind (ADR 0001).

**Do not reintroduce a per-break accepted set.** Every rule that needed one is now a resilience, and a
second mechanism for "where may this task run" is the thing the resilience model exists to prevent.

---

## Where they fall: the three recurrence bars

`DynamicPeriods` is the whole of it — a port of `side-dev/scheduler.py`'s `DynamicPlanner`.

- after **any** dynamic period, no 20 s period for **20 minutes**;
- after any **≥ 5-minute** stretch covered by "no on-screen task" *without any task*, no 5 min period for
  **1 hour**;
- after a **≥ 15-minute** such stretch, no 20 s period for **20 minutes** and no 15 min period for
  **2 hours**.

Where the bars would make two periods overlap — only the `t_p` drag can — **the whole chain is replaced by
its longest member starting at the chain's earliest point**. Touching counts as chaining: that is the
README's own example, a 20 s dragged until its end meets a 5 min is absorbed and the 5 min teleports 20
seconds backward.

### A rest stretch takes all three of its clauses

1. **covered by "no on-screen task"** — a period of that kind, or `no task allowed`, which turns the
   on-screen tasks away a fortiori. An emptiness of some *other* kind (a period nobody happens to be
   resilient to) is not one: the README names the kind, and only that kind rests eyes.
2. **without any task** — a period that still accepts somebody makes no stretch at all (the no-idling rule
   puts a task there), and neither does a pre-placed block: **a pre-placed task IS a task**, so an hour of
   maintenance bars nothing. The user was at the screen the whole time.
3. **a stretch, not a period** — two that abut make one (`growStretch`).

`blocked` and `rested` are deliberately two different sets. Everywhere nothing can be placed a dynamic
period is pointless and is pushed past (`blocked`); only the part of that which is a rest stretch bars what
comes after it (`rested`).

The timeline is taken to **start rested**, so the first 20 s may fall one bar after the origin, the first
5 min an hour after it and the first 15 min two hours after it.

---

## What this replaced, and why none of it comes back

The retired engine was a per-break `lastRest` anchor plus a grid simulation, carrying:

- a **cadence grid** per break, re-derived from its anchor;
- the **5↔15 merge** (the longer pose absorbs the shorter);
- **"a pause re-anchors shorter pauses"**, so a look-away always landed 20 min after a pose ended;
- **absorption** of an occurrence falling inside an already-placed longer one;
- a **decoupled-pose** special case for the fast-break debug shape, which could not self-recur;
- an explicit **cap** so a sub-minute interval could not flood the projection.

Every one of those is a way of saying *a rest bars the breaks that follow it*, which the bars say **once**.
The merge and the absorption are the chain rule; the re-anchoring and the decoupled pose are the rest-stretch
clause; the cap is the 20-minute bar, which no configuration can undercut.

It also makes the anchors **derived rather than stored** (CLAUDE.md's authoritative-vs-derived rule): the
placement reads the rest stretches out of the timeline itself, so the recorded past reaches it as periods and
a live pause reaches it as the period it is (`liveRestPeriod`) rather than as an overlay on every break's
anchor.

---

## A break has a DUE, and in mode 1 it also slides — and the cue keys on the due

Two instants, and keeping them apart is the point:

- **The due** — where the recurrence bars put the period. A fixed instant derived from the rules, crossed
  once, and therefore the only thing a trigger may key on. `screenBreakOccurrencesBetween` is the one reading
  of it (`dynamicPeriodPanels` with `atLine = false`), and the pause cue's `nextScreenBreakStartMillis` and
  the local `cueCrossings` both go through it.
- **Where the period sits** — the due, unless the line is dragging it. `screenBreakPanels` /
  `takenScreenBreakPanels` (`atLine = true`) is that reading: it is what the calendar draws and what the fill
  treats as an obstacle.

They are the same instant except under the mode-1 drag — which only a **pose** is subject to — and that is
exactly why the cue may not key on the second one: a period the line is pushing is always "starting now", so it
is never crossed, and a sweep keyed on it would announce a break at every scan for as long as one is owed. (That is not hypothetical — it is the
2026-07-12 "spammed every frame" incident, from the era when breaks slid unconditionally.)

This is a **partial reversal** of the intermediate rule "nothing slides, so the cue may key on the drawn
start". Nothing slides *on its own* — the bars pin every due — but the README's mode 1 does slide the period
the line has reached, so the two questions came apart again and now have two functions instead of one flag.

The rule that survives every revision of this ADR, unchanged and load-bearing:

> **The boundary a trigger keys on must be a fixed instant derived from the rules** — never a position the
> placement recomputes every frame.

Two consequences the implementation must honour:

- **The sweep must be handed the same environment the fill was** — the standing restrictive periods and the
  tasks — and the same now-line anchor. Asked without them the bars answer a different timeline, and the app
  announces a break at an instant the calendar does not draw one at.
- **The sweep's self-delay reads the next placed start too.** Read off an anchor, it went looking for
  `lastRest + interval`, which the bars no longer put anything at: the sweep found no next boundary and
  stopped altogether.

### Who the cue is FOR: a locked device says nothing, an "away" one still speaks

The boundary decides *when*; whether anybody is there to hear it is a separate question, and the engine now
answers it with a separate reading of the screen.

- `effectiveScreenActive()` — **is anybody working at this device.** The active session, the `t_a` presence
  heartbeat, the no-screen evidence, and through them the `t_p` mode above. The "I'm away" button masks it;
  that is the whole of what the button does.
- `deviceUnlocked()` — **may this device say anything.** The raw platform lock alone. Every §11/§15 output
  gates on it: the task-switch notice, the look-away's start and its resume, a pose falling due, the
  wind-down.

Only the second one silences. A user at a lock screen can neither read a notification nor be spoken to —
which is the whole reason the break-over message for a locked device is the **server's** push (ADR 0006) and
not this device's sweep. The button is not that: its user is routinely still at the machine, having left it
unlocked so a program keeps running, and the "task to do now" notification is exactly what they are still
able to act on. Reading one flag for both questions took it away from them.

Two details that are rules:

- **Suppressed is not spent.** The task switch and a pose due are left unmarked when the device is locked
  (`lastNotifiedTaskId`, `sidePoseNotifiedDue` untouched), so what was owed is said at the unlock instead of
  being lost to a de-dupe nobody heard. The look-away start and the wind-down are marked either way: they are
  crossings worth nothing late.
- **An alarm has no such gate** (ADR 0010). A locked machine is the case an alarm is *for*, and that
  exception is about alarms, never about a cue.

A break due while the user is *away* is a different matter again, and it needs no gate: the pause covers the
line, so the bars place no period inside it — there is nothing to announce, by the placement rule and not by
a mute.

### The placement origin is anchored on the now-line

The bars are a walk from an origin forward, so the grid they produce is a **function of that origin**: two
questions asked with origins ten minutes apart get two different grids. The fill asks from `now`, the cue
sweep from its scan floor ten minutes behind it, the calendar from the visible span's start.

`dynamicPlacementOriginMillis` therefore quantizes to the start of the UTC day before **an anchor every
caller shares — the now-line**, not the window's own left edge. Quantizing each window separately puts them
in different days whenever one straddles a midnight, which is precisely when the two grids part company.

And a **materialized break is never an input to its own placement** (`restrictivePeriodsOf` drops
`screenBreak` panels): feeding last fill's output back in makes each break a blocked stretch that absorbs the
next, and the grid walks away from itself on every pass.

---

## The two `t_p` modes

**The mode is which devices are unlocked, and nothing else: mode 1 while ANY device of the account is
unlocked, mode 2 otherwise.** `SchedulerDomain.tpMode` is the one place it is decided and
`anyDeviceUnlockedAt` the one place the input is read.

It is *not* the Sleep/Work toggle, which is what the code read until 2026-08-28 — that toggle says the user
has gone to bed, not that no screen is in use, and a machine left unlocked while its owner naps is squarely
mode 1. It is not the "I'm away" button on its own either: that button reaches the mode the same way a lock
does, by declaring this device idle, which is precisely why an unlock clears it
(`SchedulerEngine.noteScreenSignal`).

"Unlocked" is read off the **account-wide pause the calendar already draws** — `displayInactivityGaps`: the
derived gaps (the complement of every device's active intervals, this device's own rows plus the peers' the
last reconcile pulled) plus the live tail of the pause this device is observing now. The line being inside one
of those *is* "no device is unlocked". One reading, so the mode and the Inactivity band can never say
different things: **what the user sees is the mode.** The right edge is tested inclusively — an ongoing pause's
tail ends *at* the now-line, and a half-open test would report the device unlocked at the one instant being
asked about. Peers arrive with reconcile-bounded staleness and the live tail is a local presumption a later
derive shrinks; that is the same bound the band itself carries.

- **Mode 1 (a device is unlocked).** `t_p` may not be covered, so every **pose** whose slot the line has
  **swept** — travelled continuously through, from where its motion began up to here — is pushed onto the line
  and becomes the half-open `(t_p, t_p + duration]`. In the app's discrete millisecond time that is
  `[t_p + 1, t_p + duration + 1)`, which is how it becomes an ordinary `TaskPanel` with no extra field:
  `Instance.coveredFromMillis`. The line goes on delaying it, placing tasks where it stood, so a stretch
  crossed at the screen holds task panels and no pose. **The 20 s look-away is exempt** — see below.
- **Mode 2 (no device unlocked).** `t_p` must be covered, so the gap between the last such period's end and
  the line is covered as **`no on-screen task`** — not `no task allowed` — which is what the README's own
  example asks for: the gap is filled with the tasks resilient to that kind, and left empty if none are.
  `DynamicPeriods.awayCover` is the whole of it, built by `fillSchedule` straight into its restriction set.
  It is an **environment period, never a panel**, and the difference is why it went missing for two days: it
  shipped as an `Away` panel, the calendar drew a synthetic band nobody wanted, and the revert (2026-08-31)
  took the scheduling effect away with the band — mode 2's rule then reached nothing, and the fill started an
  on-screen task at the line while no device of the account was unlocked. `dynamicPeriodPanels` answers what
  the calendar draws; the cover is not that list's business.

Three things that fall out, and each is load-bearing:

- **The drag is bounded, and not by a special case.** Pushing a period onto the line re-anchors its own bar
  there, so at most one occurrence per bar can be swept; the chain merge then collapses what piled up into the
  longest of them. One period is owed at the line, never four hours of them. The earlier code passed
  `sweepFromMillis = t_p` — disabling the drag entirely — out of exactly that fear.
- **A drag is a move like any other.** It puts the period back through the loop, so the ordinary rules get
  their say at the new position: the line may be standing inside a stretch nobody can run in (a hand-drawn
  inactivity period, a night), and a period must no more fall inside one for having been dragged than for
  having been placed there.
- **The frozen past holds *because* of the drag, not despite it.** A dragged pose is ahead of the line at
  every position of the line, so the elapsed timeline never held it and nothing behind the line ever changes
  from a period into a task panel — it was a task panel all along. That is the README's "the passing of the
  `t_p` line creates task panels not covered by the period", read exactly.

### The look-away is assumed taken; only a pose is owed

**The 20 s look-away is never dragged** (`DynamicPeriods.dragsAtLine` — the one predicate, keyed on the
positional bar label, never on a title). Everything above about mode 1 is about the two poses.

The reason is not a scheduling one, it is what the break *is*. Looking twenty feet away for twenty seconds
costs the user no working time and needs no decision: they are still at the desk, still on the same task, and
the app has already told them to do it. So the app assumes they are doing it. The occurrence stays exactly
where the recurrence bars put it, the now-line walks **through** it — covered by `no task allowed` for those
twenty seconds, which is mode 2's condition and precisely what mode 1 forbids of a pose — and comes out the
other side. Behind the line the break is still there, drawn where it happened.

A pose is the opposite case, and the contrast is the whole of the split: five or fifteen minutes away from the
screen is something the user has to actually *do*, so an untaken one is **owed**, not spent, and parks at the
line until a real rest discharges it.

Three consequences:

- **The due and the place are one instant** for the look-away, so the cue, the calendar and the fill cannot
  drift about it. That is not a licence to collapse the two readings: the split above is what keeps a *pose's*
  cue honest.
- **It stays derived, never recorded.** `takenScreenBreakPanels` asks the same bars over the recorded past, so
  the past stays frozen for the reason it always did — the environment behind the line is a fact. Which is
  also why the past placement can still move when that environment does: press **Look away now** less than
  twenty minutes later and the 20 s bar re-anchors off the break actually taken (`RecordConductedBreak`, which
  reaches the bars as `RestrictivePeriod.dynamic`).
- **A break the app conducted still bars what follows it**, unchanged — the README's first bar keys on any
  dynamic restrictive period, and now the ones the line simply crossed are on the timeline too, firing it as
  the walk's own placements always did.

Where the app has live evidence that nobody is at a screen, that evidence *is* mode 2's cover: an **ongoing**
pause is `closedEnd`, so `liveRestPeriod` covers the now-line and `awayCover` finds nothing left to do. A pause
the user has come back from stops at the return, exclusive, like every other period.

### The line never jumps, so waking from device sleep is a journey — walked in mode 2

The README's *Progressive Calculation* section states the consequence outright: *"If the device bearing the
running process is put to sleep, then when the program wakes up, the $now line$ does a fast move forward (in
epsilon time) in mode 2 to the current date. If the current date is beyond the definitive schedule, then it is
similar to a case where no CPU were available during this period and the current set of rules, parameterized by
$now line$ and $now line$ mode, is used to define the schedule as the $now line$ does its fast move, while no
better set of rules was found."*

The engine used to land instead of travel — `reportTimeGap` was one `advanceTo(sleepEnd)` — and three things
followed from that, of which only the first is about tidiness:

1. **A jump is not a motion the model admits.** Mode 1's whole rule is *what the line has swept*, so a line
   that can arrive without having swept is a line the drag cannot be defined against. The walk
   (`SchedulerEngine.sweepNowLineTo`) restores it, stepping by one minimum execution time
   (`SchedulerDomain.sweepStepMillis` — the reference's `Walk._sweep_step`, the finest thing the walk can place,
   so a line that never skips a whole minimum never skips a placement it should have entered). An ordinary tick
   is far inside the first step and costs exactly one commit, which is what keeps this off ADR 0009's budget;
   the cost is paid only where the line really does cover ground.
2. **The mode belongs to the journey, not to the arrival.** A machine that has just woken reports *unlocked*,
   so anything asking afterwards answers mode 1 — the one mode the suspension was not in. `sweepMode` holds the
   journey's mode for as long as the journey lasts and `tpModeNow` reads it, so the single reading of the mode
   stays single.
3. **Mode 2's cover is a fact about the mode, not an observation to wait for.** Mode 2 says the line *is*
   covered by `no on-screen task`; a stretch swept in mode 2 is therefore covered by one, and
   `noteSweptNoScreen` says so at the instant of the wake — into the same `noScreenEvidence` funnel the OS lock
   scan publishes through (`publishNoScreenEvidence` publishes the union, so a scan that reads nothing cannot
   un-say the mode). Before this, the app learned about the night only when `launchNoScreenEvidenceScan` next
   ran: a **10-minute** bucket behind a process launch. A pause that has merely *ended* reaches the recurrence
   bars by no other route — `liveRestPeriod` holds only the pause this device is in the middle of — so for up to
   ten minutes after every wake the bars still counted from the last recorded break and the owed chain was
   dragging at the line, which is the user-visible half of this: a break falling due the instant you come back
   from a night's sleep.

The journey deliberately does **not** re-plan, and that is the README's own answer for a current date beyond
the definitive schedule: each step is an ordinary `AdvanceSchedule`, so the set of rules in force writes the
past the line passes, and the re-plan belongs to the landing (`requestReschedule`). The one approximation is
the stride widening that keeps a very long journey inside `MAX_SWEEP_STEPS` — the README's *"if exact schedules
cannot be found in time, approved approximation strategies must be used"* — logged when it bites.

`ProgressiveSchedule.advanceTo` has been the faithful port of the reference's own continuous walk since it was
written, and it is not what the app runs on: the live path is the engine's tick. A port nothing calls does not
satisfy anything.

**Consequence, deliberately accepted.** While a device stays unlocked and no rest happens, the owed chain
parks at the now-line and the fill schedules no task under it — "you owe a break" is a period, not a hint. It
clears when the app conducts a break and records it (a pre-placed period, so never dragged), or when the user
actually goes away and the mode flips.

A **mode flip re-plans** (`SchedulerEngine.launchTpModeReschedule` → `requestReschedule`). That is not "time
passing re-plans": the flip is an edge the platform announces — a lock, an unlock, the button — and a mode that
does not change costs nothing. It cannot live in `schedulingSignature` instead, because the mode is not in
`SchedulerState`: it is a fact about the devices, not about the account's data, which is also why it is never
synced.

---

## The past is a recorded fact

A break the app **conducted** — only the 20-s look-away is ever conducted — is recorded as a period
(`SchedulerIntent.RecordConductedBreak`) where it happened. Only on completion: a manual "Look away now" that
was superseded by a second press, or that the app stopped mid-run, leaves no trace at all. That asymmetry is
now structural rather than a property of an anchor that only moves on completion.

Everything else in the past is simply the placement asked about a window that has already gone by — the bars
are deterministic over the recorded environment, so the past placement *is* the placement, asked **at the
line** (`takenScreenBreakPanels`, `atLine = true`) because that is where the two modes are read from. So a
stretch the line crossed in mode 1 holds none of the three, and a break shows in the past when it really was
one: the stretch was crossed in mode 2, or the app conducted it and recorded it as above.

---

## What is still true

- **Every break recurs after it ENDS**, not after it starts — the bars are all measured from a period's end.
- The **end** of a break is a notification, not only a voice cue.
- A screen-break panel has **no Edit** (there is no editable object behind it). A sleep band's menu leads
  with Edit.
- Staleness is judged only by a crossing's REAL age (`BoundarySweep`, 2-s budget), never by sim distance or
  scan-window position, and consecutive scans tile the timeline (`scanFloorMillis`) so no crossing can be
  clipped by a clock jump.
- A dynamic period **suspends** a chunk rather than cutting it (PRD §15), and does not count against "does
  the minimum fit?".
