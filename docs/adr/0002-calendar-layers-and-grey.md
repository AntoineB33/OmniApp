# ADR 0002 — The calendar's two layers, and what GREY means

**Status:** active (spec settled 2026-08-20). **Invariant summary:** see `CLAUDE.md` → *Calendar*.

The old "Inactivity" + "No screen" BAND pair is gone, replaced by two orthogonal things, and keeping
them orthogonal is the whole point — one says **who was at a screen** (the layers), the other says
**whether anything is scheduled** (grey).

The PAST is fully tiled: every elapsed stretch is either a task panel or a grey period. A stretch where
the user sat at the computer doing nothing scheduled is grey but NOT hatched; one spent away from every
device is both.

## The two layers

`SchedulerDomain.ActivityLayer`, `CalendarRecord.layer` → the `layerBands` overlay in `CalendarUi`.

- An oblique-line hatch for **"no computer unlocked"** (`/`) and one in the OPPOSITE direction for
  **"no phone unlocked"** (`\`).
- They are **not panels** — drawn ACROSS the day column OVER the blocks, displacing nothing and
  registering no pointer input (a plain drawing `Box`), so every block underneath keeps its
  hover/drag/right-click.
- **A stretch carrying BOTH is a no-screen period.** The user's definition: "when no computer and no
  phone is unlocked at the same time, then it is a no-screen period". It is exactly the account-wide
  pause the app already derived — what §9 places the off-screen tasks in and §15 counts as a
  break-serving pause.
- `CalendarLayerTest` pins that identity: `intersect(layerA, layerB) == derivePauses(all sessions)`.
  If you touch either side, keep it true. With an unaskable phone the intersection collapses to the
  computer's own locked stretches — which is what the assumed-LOCKED default means.

## A layer is read from the DEVICE'S OS HISTORY, not from the app

The seam is `deviceLockedIntervals(since, until)` (`SleepHistory.kt`): the stretches THIS device was
not unlocked, from its OS **lock/unlock** record where the platform exposes one, else its
**sleep/awake** record — the spec's own stated fallback.

On Windows the fallback is forced:

- Security 4800/4801 need the audit policy on AND an elevated reader.
- `Microsoft-Windows-Winlogon/Operational` only logs which notification SUBSCRIBER ran (811/812).
- The TerminalServices log records logon/logoff/disconnect but not lock.

So the jvm actual reads the non-elevated **power history** of the System event log. On a Modern-Standby
machine that is closer to lock/unlock than it sounds — 506 fires at screen-off.

### What "away" is made of — 2026-08-27

`WindowsPowerLog` is the one place that says which events mean the device went away, and all three
`SleepHistory` actuals read it (the layer, the record-bank evidence, the screen-break seed, the exact
pause recorder). Three things it has to get right, each of which was wrong while the query watched only
Kernel-Power's sleep pair:

- **A machine that is SHUT DOWN logs no sleep event at all.** `42`/`506` never fire; the clean shutdown
  is `109`/`13`/`6006` and the boot is `12`/`6005`, across two other providers. A query watching only the
  sleep pair reports an overnight power-off as time the user was present — which draws no hatch, and,
  worse, leaves `observedNoScreenRegions` with no evidence, so on-screen tasks bank records straight
  through hours the machine was off. That is the failure this ADR's evidence union exists to prevent,
  reached by another door. `6008` (power loss) is stamped at the NEXT BOOT, so the query substitutes the
  crash time out of the record's own properties.
- **An id means nothing without its provider.** `1` is Kernel-Power's "the system has resumed" and ALSO
  Kernel-General's "the system time has changed", which Windows writes a second after almost every sleep.
  Asking three providers for one flat id list therefore turns every clock resync into a wake: on the
  author's own log a sleep at 01:19:44 paired with the time-change at 01:19:45 into a one-second absence,
  the genuine resume eight hours later had nothing left to close, and the night read as time at the desk.
  Each provider is asked for **its own** ids; the three sets are disjoint, which is what lets the query's
  output stay one `millis,id` line per event.
- **Jitter is not a state change.** A flip shorter than a minute cancels the transition it undid, and a
  repeat of the state already held is dropped, so the timeline strictly alternates. Without it a `506`/
  `507` bounce becomes a real three-second "locked" interval. The price is that a genuine sub-minute lock
  is invisible — the same scale the derived grey bands already drop as remnants.

The window's two edges are asymmetric and both are handled: an absence still open at `untilMillis` is
clipped to it, and the state the window OPENS in comes from a few events fetched from BEFORE it — without
them a window beginning mid-absence drops its unmatched wake and reports the whole lead-in as present.

Ahead of the now-line nothing is observed, so only the ASSERTED regions hatch (`layerAsserted` in
`App.kt`: §17 sleep windows, §15 screen breaks, the user's own no-screen periods), and those hold for
both layers whatever any history says.

## A device that cannot be asked WAS LOCKED

`null` from the seam ⇒ the layer hatches the **whole asked past**, `[displayFloor, now]`. The user's
example: *"if I run the app on a computer and the data of the phone is not available because it is the
first time I run the app, then it is considered that the phone was always locked in the past."*

**This is the SAME default as `derivePauses`** ("no screen unless a device reported activity"): a device
nobody can vouch for was not in use. Where the two still differ is only in what they read — the OS's
record of the device versus the app's own heartbeats.

Only THIS device can be asked (there is no channel carrying a peer's lock history), so every other kind
gets `null`, and a computer with no phone on the account therefore draws `\` over its whole displayed
past. `null` and an empty list stay deliberately different answers — an empty list is the OS saying "this
device was never locked", which draws **nothing** — and the jvm actual tells them apart with an `'OK'`
sentinel line, since a successful query with no cycles prints nothing either.

**"Not asked yet" is a third state and is not `null`.** The first lock-history scan is a PowerShell
process launch, so the own layer would otherwise flash a full-window hatch at every launch until it
lands. `App.kt` gates it on `lockHistoryScanned`: before the first answer the own layer draws nothing,
and a later re-scan keeps showing the previous answer while it runs.

> Reversed on 2026-08-23. The first spec sentence said "unlocked"; the user corrected the word after
> noticing that a phone with no app installed left the desktop calendar unhatched. Everything below about
> *where* the evidence comes from is unchanged — only the meaning of silence flipped.

### Two earlier readings, both shipped and both wrong

Kept here because the next person will be tempted by them again.

1. **Deriving a layer as the complement of the account's `device_active_session` rows.** On account 2
   that painted an unbroken 168 h of "nobody unlocked", because the app had only been open ~15 minutes
   that week — the sessions say when the APP ran, not when the DEVICE was usable.
2. **Patching that by counting a banked on-screen task panel as evidence the computer was unlocked.**
   A heuristic standing in for the real source; now removed.

Do not reintroduce either. If a layer looks wrong, the question is what the OS says.

## Cost and filtering

`deviceLockedIntervals` spawns a PowerShell process, so it runs in a `LaunchedEffect` on
`Dispatchers.Default` keyed on:

- `displayFloorMillis` (grows as the user scrolls back — "if the user looks further in the past it will
  ask the device"), and
- a coarse `nowMillis / LOCK_HISTORY_REFRESH_MILLIS` bucket (10 min), so standby entered while the
  calendar is open is picked up without a query per tick.

**Never on the display cadence** — see ADR 0009 (hot-path rule).

**The evidence half is seam-filtered at `MIN_INACTIVITY_BAND_MILLIS`, the asserted half is not.** A
Modern-Standby machine dips in and out for seconds all day and would draw hairlines of hatch, while a
20-second look-away is a real claim and must keep its hatch.

## GREY = the scheduler places nothing here

One concept wearing four names:

1. a hand-added inactivity period,
2. a §17 **sleep window** (an inactivity period labelled "Sleep"),
3. the **20-s look-away** end to end,
4. the **closed first minute of a 5-min pose**.

The last two are drawn grey INSIDE the break's blue outline (`SCREEN_BREAK_CLOSED_ALPHA`), no longer a
solid blue slab. In `fillSchedule` all of it is `blockedRegions` → `accepted = emptyList()`.

**Grey is NOT a screen classification:** it refuses an off-screen task exactly as it refuses an
on-screen one, which is what separates it from a no-screen period. Conversely a pose's OPEN tail and the
15-min pose are **not** grey — they are periods RESERVED for off-screen work, and stay hollow-blue.

### The two behaviour changes this brought

1. A hand-added **inactivity period now blocks the fill** (it used to classify nothing at all).
2. **Sleep now blocks the fill.** The code deliberately did the opposite ("rendered as Sleep bands, but
   NO LONGER task obstacles… so a user working at night still sees the priority-ordered plan"), which
   had drifted from PRD §17, whose own words are "a task that meets it is split and resumes at wake, not
   charged for the sleep time — like a screen break".

Grey periods therefore join the screen breaks in `suspendRegions` / `suspendStarts`: they SUSPEND a run
rather than cutting it, so they do not count against "does the minimum fit?" and a chunk may start with
less than its minimum left before bedtime. (A screen-zone edge is still the other kind — somebody else
may run past it, so it cuts.)

**Known seam:** a grey period excludes everybody and so builds no influence field, so the walk may
freeze into its phase-2 cycle right after one and drop the suspended remainder. The resume is
structural, not exact.

Tests: `NoScreenInactivityPanelTest.fill_places_nothing_inside_a_hand_added_inactivity_period` /
`…an_inactivity_period_refuses_an_off_screen_task_too` /
`…a_run_meeting_an_inactivity_period_is_suspended_by_it_rather_than_ended_by_it`;
`SchedulerSleepTest.fill_schedule_places_no_task_inside_a_sleep_window` /
`…stops_at_the_sleep_window_and_resumes_after_it`.

### Grey overrides on the CALENDAR too, not only in the fill — 2026-08-24

"The scheduler places nothing here" was enforced forward (the fill) but not backward (what is already
drawn). A hand-added inactivity period conflicted with nothing: it was laid straight over task panels and
over banked records, so the calendar could show a grey period and, underneath it, the work it says did not
happen.

The override table is now:

| the panel being laid / moved | overrides |
| --- | --- |
| inactivity period (grey) | **every** task panel it covers — on-screen and off-screen |
| no-screen period | the **on-screen** task panels only (§9 lets an off-screen task run inside one) |
| on-screen task panel | no-screen periods **and** inactivity periods |
| off-screen task panel | inactivity periods only |

and the same asymmetry decides whose **records** the period clears: a no-screen period strips the on-screen
tasks' records under its elapsed part, a grey one strips everybody's. That strip is exactly
`StripNoScreenRecords`' rule (§9's "assume nothing happened"), moved from "once, at the next engine start"
to "the moment the period is laid or dragged" — the retroactive pass is still the same code (`stripRecords`),
called with `onScreenOnly` flipped for grey. It stays outside Undo/Redo, like every other write to the
record.

**Why this became reachable:** the two menu entries used to lay a fixed hour at the click, so the widest
period anybody could draw was a drag across the visible grid. They now open the **period editor**, whose two
bounds can each be a date-and-time, "now", or **∞** (`SchedulerDomain.OPEN_PAST_MILLIS` /
`OPEN_FUTURE_MILLIS` — real instants in 1900 / 2200, not saturating sentinels, because every consumer does
plain arithmetic on a panel's bounds). "An inactivity period from ∞ to now" is then a one-gesture way to
declare the whole recorded past empty, which is what the user asked for and what forced the question of what
a grey period does to what is already there.

Tests: `CalendarPeriodEditTest`.

### What did NOT change

Both contextual-menu options stay (a no-screen period is "a period asserting both layers", an
inactivity period is the grey one); the automatic override/trim between on-screen task panels and
no-screen periods stays (it was widened, above, never narrowed); and `materializePastInactivity` stays — a past no-screen period that covered a
SCHEDULED task still banks no record and materializes a real grey panel. That is about a task that was
scheduled; the "draw nothing" rule is about an idle stretch where nothing was.

## The derived grey bands

`SchedulerDomain.derivedInactivityBands` → `pastInactivityRecords` in `App.kt`, drawn by `CalendarUi`'s
`inactivityBands` alongside the sleep bands.

The user's rule: *"the areas in the past that don't have a task panel should have a grey panel either
labelled inactivity or sleep"* — so the derived band is `[displayFloor, now]` MINUS everything already
drawn over it.

**Deliberately NOT subtracted:**

- a **no-screen period** (not a task panel and not grey — it is the both-layers period, so idle time
  inside one is still idle);
- a **screen break** (its band draws over whatever is underneath).

Sub-minute remnants are dropped (`MIN_INACTIVITY_BAND_MILLIS` = 90 s) — the seam between two adjacent
panels is not a pause, and drawing it litters the day with slivers.

Display-only (no `entryId`): not removable, not draggable, unlike a hand-added inactivity panel.

> A first pass read the spec's *"idling periods with no task panels are simply represented with no task
> period"* as "draw nothing". It means "drawn as a period that is not a task".

## A task panel under the computer hatch — the deeper question, now ANSWERED

On the reference machine the OS reports standby over stretches the schedule banked records for (e.g.
10:25→10:43 on 2026-08-20). That is the device's record and the plan's assumption disagreeing, and the
device wins — it is the source the spec names.

The deeper question this exposed — whether `AdvanceSchedule` should bank a record at all over OS-reported
standby — was left alone here as "a scheduler change, not a display one". **It was answered on 2026-08-24,
and the answer is no.** Account 3 showed why it could not stay open: past panels for on-screen tasks sat
under BOTH layers, which by the identity above IS a no-screen period, and underneath them were 43.4 h of
recorded "work" across 206 records. §9's "assume nothing" rule keyed on no-screen PANELS, and the only
producer of one is the §8 menu action — so on an account where the user had never drawn one, the rule
never fired at all.

`SchedulerDomain.observedNoScreenRegions` now reads the two layers' evidence halves and intersects them;
the engine injects that through `SchedulerReducer.noScreenEvidence`, and every banking path unions it with
the hand-drawn panels. A one-shot `StripNoScreenRecords` applies the same rule to what older builds already
stored. See CHANGELOG 1.6.0.

Two things that bit, and will bite again:

- **A failed query is not evidence.** `null` means "assumed locked throughout", which is right for the
  calendar and catastrophic for the record bank — one PowerShell timeout would suppress every record. The
  OWN scan must SUCCEED to say anything; the PEER's null keeps its assumed-locked meaning. Silence about a
  device we cannot reach is a rule; silence from the one we can reach is a failure.
- **The scan must not run on the engine's dispatcher** (ADR 0009). It is a process launch with a 20 s
  timeout; inline, it stalls the advance tick and every sweep behind it.

What is still NOT folded in: the asserted regions. A screen break suspends a chunk rather than cutting it
(§15), so treating breaks and sleep windows as no-screen evidence would silently stop recording across
every one of them. Only the OS evidence and the user's own drawn periods reach the bank.

Tests: `CalendarLayerTest` (evidence source, the assumed-unlocked default, the seam filter, the
no-screen intersection).

## Known inconsistency, deliberate and unresolved

The OS lock history feeds the calendar's LAYERS **and, since 2026-08-24, the §9 record bank**. The
engine's own pause derivation (`inactivityGaps` → rest-pose seeding, sleep-band carving, and the §15
break-serving pause) still reads the `device_active_session` rows — so the calendar and the break cadence
answer "was the user away?" from two different sources.

It used to be three: the bank answered from neither, reading only hand-drawn panels. That is what let
43 h of work bank over a sleeping machine, and closing it moved the bank onto the OS's side of the split.

Unifying them means deciding that a break is served by the OS saying the screen was locked, which
changes cue timing and touches the engine/sync path — out of scope for a display fix. **Decide it
before adding anything else that reads one and not the other.**

## The hover bubble is a STACK of sections

Closing the "the two slopes are unlabelled on screen" gap turned the bubble inside out.

The calendar deliberately draws its elements across each other — a task inside a sleep window, a screen
break over that task, the two layers hatched over all of it. The bubble used to be one element's title plus
at most one "under" line, so those reports raced and overwrote each other: hovering a task inside a sleep
window named the task and lost the window; a layer named nothing at all.

It is now a list of `CalendarBubbleSection`s, one per thing true at the instant under the cursor, ordered
by `CalendarBubbleSection.Kind.rank` — the user's ordering, top to bottom:

    task = break > inactivity = sleep > no computer unlocked = no phone unlocked

Equal ranks are deliberate **ties**, kept in collection order by a stable sort (so a sleep band's own "No
screen" line still follows the band). One exclusion: **when there is a break there can't be a task**. A §15
break *suspends* the chunk it lands in rather than cutting it, so the task's panel genuinely spans the
break — but the user is not on that task during it, and naming it would be a lie. `orderedBubbleSections`
is both rules, and the only place they live; `Modifier.calendarTitleHover` is the single funnel every
report passes through, so nothing can report an unordered stack.

### A layer still registers no pointer input

The invariant holds: the drawn hatch is a bare `drawBehind` Box, displacing nothing and hit-testing
nothing, so every block underneath keeps its hover, drag and right-click. What carries a layer's section is
whatever the cursor is *actually* over — and where that is nothing (an idle past stretch draws no band at
all any more), a bottom-most **pickup** tiling under every panel, band and marker. Anything drawn above
wins its own hover; only the stretches nothing else claims fall through.

The grey sleep/inactivity bands lost their hover children entirely for the same reason: they are pure
drawing now, and their sections ride the pickup or the block on top.

### Tiling, never nesting

Two hover reporters at one position race — a parent's `Move` overwrites the child's report. So every
hoverable element is cut at each covering section's boundary (`bubbleHoverZones`), exactly as
`deviceHoverZones` already cut a block at each device-set change; the two compose (a block's own overlays
are the device zones, re-tiled against the context). Emission is still culled to the visible window
(ADR 0009).

Tests: `CalendarBubbleSectionTest` (the ordering, the ties, the break/task exclusion).

## Known gap

The phone's contextual menu still names only the panel it was opened on — a phone has no hover bubble, and
the touch menu was not reshaped into a section stack.
