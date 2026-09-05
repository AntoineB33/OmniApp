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

## "I'm away" is a lock the OS cannot see — 2026-09-05

The spec's mode 3 is *at least one device with the "I'm away" button clicked and all the other devices
locked*, and the button's whole point is that the machine **stays unlocked** while it is on (the user has to
leave a program running). So the OS lock history — the one source a layer had — is silent over exactly the
stretch the mode is 3 for, and the calendar drew no hatch across a period the app itself was calling *no
screen is in use*. The requirement is the other way round: that period must be **covered by a "no computer
unlocked" layer and a "no phone unlocked" layer**, because it is a period where neither is unlocked.

So the button joins the layer of **its own device's kind**, as a claim:

- `SchedulerEngine` keeps the episodes (`declaredAwaySpans` + `declaredAwaySince`, closed at the two edges
  the flag has — the button and the unlock that clears it) and `SchedulerDomain.declaredAwayRegions` reads
  them the way `displayInactivityGaps` reads the live pause: the closed ones plus the open one growing with
  the now-line and stopping there.
- They arrive as `layerRegions`' **asserted** regions, not as evidence, for two reasons. The seam filter
  would drop a declaration shorter than a minute, and the mode was 3 for that minute. And a failed lock
  query must not silence them: the button is the user's own statement, not a query that can time out.
- They belong to ONE layer. A press on the computer says nothing about the phone — which is why
  `observedNoScreenRegions` takes `computerAway`/`phoneAway` beside the two histories rather than one list.

The other devices need no equivalent: no channel carries a peer's lock history either, so a peer's layer is
already hatched across the whole window ("a device that cannot be asked was locked"). The conjunction then
comes out exactly right — an away press with every other device locked hatches **both** layers, which by this
ADR's own identity *is* a no-screen period, so the same stretch also cuts the on-screen panels it covers and
banks no record. That last part is deliberate and is the one exception to "the asserted regions are not
evidence": a screen break is not time the user was absent for, and a declared absence is.

Runtime state, like the flag itself — never persisted, never synced. After a restart the layer falls back to
whatever the OS history says.

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

One concept wearing several names:

1. a hand-added inactivity period,
2. a §17 **sleep window** (an inactivity period labelled "Sleep"),
3. **all three screen breaks**, end to end,
4. the §17 **"Before bed" hour** (2026-08-29).

Item 4 is the one that is grey without being `no task allowed`: its kind is `before bed`, whose default
resilience is `0`, so it places nothing for the same reason and is painted the same way. That is the point
worth keeping — **grey is what the calendar paints "nothing is placed here" with, and it is not a kind.** A
band that is grey still carries its own kind and its own name, which is why `decorativeBandLabel` reads a
derived band's own title rather than always answering "Inactivity".

Item 3 was two items until 2026-08-28 ("the 20-s look-away end to end" and "the closed first minute of a
5-min pose"), because a break had a *shape*: a closed head and a tail reserved for off-screen work, which
stayed hollow-blue precisely because it was not grey. `side-dev/README.md` gives all three the one kind
`no task allowed` end to end (ADR 0003), so a break is grey over its whole length and the hollow half is
gone with the shapes.

In `fillSchedule` grey is `blockedRegions` (`no task allowed` **or** `before bed` — both refuse everybody, so
both suspend a chunk rather than cutting it and are stepped over by "does the minimum fit?"); a break is
`sideRegions`, which suspends for the same reason but is collected separately because PRD §15 also decides
what a break's own end does to a resumption.

### How grey is MARKED: vertical lines, delimited — 2026-08-28

All three are drawn one way, by one modifier (`greyPeriodMarks`): **vertical lines** across the stretch, with
a line across its **top and bottom edge**. Three things this settles, each of which had shipped the other way.

- **A screen break is marked like the other two.** It used to wear a blue outline, a `●` and an accent-coloured
  title inside a grey slab (`SCREEN_BREAK_CLOSED_ALPHA`), which said it was a different sort of period from the
  inactivity band beside it. It is not: all three are `no task allowed`, so all three read the same.
- **Lines, not a wash — because a grey period may legitimately contain a task panel.** §17 projects the plan
  straight through a sleep window, and a task given a non-zero resilience to `no task allowed` works through a
  break. A filled tint repaints whatever it covers, so those panels lost their own task colour to the marking
  (that is exactly what `CalendarBlock`'s `sleepHourRanges` overlay did, and it is deleted). Lines mark the
  stretch and leave every possible task colour readable through the gaps.
- **Which is why the marking now draws OVER the panels**, like the layers and for the same reason. Behind them,
  a stretch with a block on it would show no marking at all — which is what forced the per-block tint in the
  first place.
- **The band is DELIMITED.** An inactivity period ending exactly where a sleep window starts must still read as
  two periods; a continuous pattern merges them into one stretch. The edge lines are drawn half a stroke inside
  the band, so two abutting bands draw two distinct edges rather than sharing one pixel row.

The bands stay purely decorative — no pointer input, so every block underneath keeps its hover, drag and
right-click, and the hover bubble's sections still come from `contextOverlays`.

**Grey is NOT a screen classification:** it refuses an off-screen task exactly as it refuses an on-screen one,
which is what separates it from a no-screen period. What "refuses" now means precisely: the task's resilience
to the covering period's kind is `0`. A task the user has deliberately given a non-zero resilience to
`no task allowed` may work through one — which is the *only* way anything is placed there, and is why the
band is uniform rather than split.

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
inactivity period is the grey one); and the automatic override/trim between on-screen task panels and
no-screen periods stays (it was widened, above, never narrowed).

`materializePastInactivity` did **not** stay — see *A period is never manufactured from evidence*, below.
It was kept here on the reasoning that "a past no-screen period that covered a SCHEDULED task" is a
different question from an idle stretch. It is not: both are answers about what the app OBSERVED, and
neither is a period the account owns.

## A period is never manufactured from evidence — 2026-08-29

`materializePastInactivity` turned every elapsed span where scheduled on-screen work met a no-screen period
into a real `TaskPanel` — `inactivity = true`, `periodKind = NO_TASK`, allocated id, in `state.panels`.
Three things were wrong with it, and the user's own statement of the rule is what exposed them:

> Inactivity in the calendar must be represented by vertical lines. If it appears, it is because at this
> time the rule states say all tasks have 0 resilience to some periods there, or because the user added it
> manually, or because this time was in the past beyond the memory of the app.

1. **It persisted derived state.** "The devices observed no screen here" is evidence, recomputed on every
   scan; writing it into authoritative, synced `panels` is exactly what ADR 0007's table forbids — and it
   grew without bound (218 panels on the release account by 2026-08-29, a fresh batch per `account3-deploy-
   windows.bat`, because the app banks nothing while it is being rebuilt).
2. **It changed the kind.** The evidence says `no on-screen task`; the panel it wrote said `no task
   allowed`. So an observation an off-screen task may legitimately run through became a period refusing
   everybody — and then fed `restrictivePeriodsOf` → the recurrence bars as a `NO_TASK` rest stretch.
3. **It was the second mechanism.** `observedNoScreenRegions` already answers the display side
   (`clipPanelsForObservedNoScreen`) and `observedNoScreenPeriods` the placement side. This predated both
   and duplicated them.

What the strip vacates is idle time, and the calendar already DERIVES a grey band over whatever no task
panel covers. So the fix is a deletion, and the display is unchanged. `materializePastSleep` is deliberately
kept: a sleep session is a fact the **user** asserted with the Sleep/Work toggle, not something a scan
observed.

Existing materialized panels are left where they are: a hand-added inactivity period IS a `no task allowed`
period, so they are shape-identical to the user's own and cannot be told apart on decode. Each is an object
with a Remove in its menu.

Tests: `NoScreenInactivityPanelTest`, `NoScreenEvidenceTest`, `CalendarPeriodEditTest`.

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

**The PANEL half followed on 2026-08-28.** Answering the record question left the display half of the same
sentence open, and the section above named it as accepted: the panel the suppressed record would have come
from went on being drawn straight across the two hatches, so the calendar still showed an on-screen task
running on a machine the OS reported asleep. But the identity is not about records — *a stretch carrying both
layers is a no-screen period*, and a no-screen period overrides the on-screen task panels it covers, which is
already the rule for a hand-drawn one. `SchedulerDomain.clipPanelsForObservedNoScreen` applies it to the same
`observedNoScreenRegions` the bank asks, so the panel and the record can never disagree about what was
observed. It is display-side, deliberately: the regions are the past, the fill only ever places ahead of the
now-line, and what the OS reports is not a user edit — nothing here belongs in the stored plan. Off-screen
tasks are exempt (§9 lets them run in a no-screen period), a restrictive period is never cut, and the
failed-query rule above holds unchanged — no successful own scan, no regions, no cut. What the cut vacates is
idle time and draws as a derived "Inactivity" band, by the ordinary rule.

Tests: `CalendarLayerTest` (evidence source, the assumed-unlocked default, the seam filter, the
no-screen intersection); `ObservedNoScreenPanelClipTest` (the panel half).

## Every grey period names itself — a screen break included

Fixed 2026-08-28. A screen break's band is drawn at its true duration, and its title was gated on a 13 dp
height. At any ordinary zoom no break reaches that: a 20-second look-away is a third of a device pixel tall, a
5-minute pose about five. So the bands were anonymous, and the user could not tell which of the three they
were looking at.

The hover bubble had the same cause, not a second one. The band's hover zones are mapped onto the RENDERED
height (that is deliberate — sizing them by a ~0-height true duration would leave the visible band
un-hoverable), so a 3 dp hairline was too thin to put a cursor on, the pointer fell through to whatever was
underneath, and the bubble named the sleep band instead of the break. One minimum fixes both:
`SCREEN_BREAK_MIN_HEIGHT` is now one label line and the title is unconditional.

Drawing a 20-second break 16 dp tall overstates it. That is the trade the minimum has always made (it was
3 dp before), and it costs the neighbouring block nothing but a marking: a break is marked with LINES over
whatever is beneath it, never a fill.

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

## A task panel's menu reaches the TASK, not only the panel — 2026-08-28

The panel menu's "Edit" is about **this occurrence**: its bounds, its pins, the window that lays it. Nothing
in the calendar reached the **task** behind the panel, so setting a task's resilience or reading its text
meant leaving the calendar, finding the cell in the tree by eye, and right-clicking it. Two entries close
that, on a task panel only (a period, a reminder, an alarm, a sleep band, a screen break and a layer region
are not tasks):

- **"edit task"** — the §13 edition window, **under the name the tree cell's menu now uses too**. The tree's
  entry was renamed "edit" → "edit task" in the same change, deliberately: it is one window, and two names
  for it is how two surfaces start reading as two features. The window itself is untouched — `App` already
  hoists it out of the tree onto the top layer, so the calendar only had to set `editTaskId`.
- **"go to task tree"** — select the task's **first** cell. Three decisions:
  - **"First" is the tree's own reading order**, not "some cell holding the task". `SchedulerDomain.
    firstTaskOccurrence` is the one place that says so: depth-first, **each LIST visited once** (a sub-list
    belongs to the task id, so a mirrored sub-tree is one list under many parents and re-entering it per
    occurrence is exponential — the same walk, for the same reason, as `TaskTreeSearch.matches`), skipping
    blank-titled cells, which are §4's deleted ones.
  - **It goes through `RevealCell`**, the find bar's own primitive, because the two want the identical thing:
    expand every collapsed ancestor as ONE history unit (a per-level unit would bury Ctrl+Z under the
    navigation), then select in the occurrence the jump navigated to. `firstTaskOccurrence` therefore returns
    the cell **and the ancestor chain**, which is exactly the pair the intent takes. The tree is focused
    first — that is what "going to" it means, and what re-arms its keyboard.
  - **`null` is a real answer, not an error path.** Panels are not per-tree and a panel outlives the cell that
    laid it, so it may name a **detached parent**, a task §4's blank title deleted, a task another named tree
    owns, or (a manual panel whose typed title never matched one) no task at all. All four are the same
    sentence, said in one place: *"…" is not in the task tree*, in the app's first `MessagePopup` — a sort-2
    pop-up, so one notice at a time, gone the moment anything else takes focus, with no scrim and no timer.

Consequence elsewhere: the tree's bring-the-revealed-row-into-view effect is now keyed on the **selection**
rather than on the find bar's current match. A match had stopped being the only thing that reveals a row, and
the calendar cannot reach into that composable at all; ordinary keyboard navigation lands there too and
wanted exactly the same thing. The find match stays a key so stepping onto a second hit inside a row already
on screen still re-runs — and then measures a zero delta.

Tests: `GoToTaskTreeTest` (the reading order, the mirror, the blanked task, the reveal).

## A cursor shape rides the hover tile — 2026-09-03

Hovering a panel's top or bottom edge showed the resize cursor and **lost the bubble**. Two things drawn at
one place, and only one of them could be true at a time.

The edge was a second layer: a full-width 6 dp `Box` aligned to the slice's top/bottom, carrying nothing but
`pointerHoverIcon`. But a `pointerHoverIcon` node is a **pointer-input node** — Compose stops hit-testing a
node's children as soon as one of them is hit, so the strip won against the hover tile beneath it and that
tile never saw another `Enter`/`Move`. The bubble did not "flicker": on that strip it was never reported at
all. The same shape, from the same cause, was on the Overlap-Mode **width handle**: a 10 dp `Box` over the
boundary between two width-sharing panels, holding the weight drag, blanking the bubble of both panels along
every shared edge — and showing no resize cursor at all, so the one edge in the calendar that IS dragged
sideways was the one that never said so.

The fix is the tiling rule this ADR already states, applied one step further: **the cursor is a property of
the tile, not a layer over it**. `bubbleHoverZones` takes `extraCuts` — boundaries that cut the tiling
without contributing a section — so the grab strip becomes a tile of the element's OWN tiling, carrying the
element's own section stack, with `pointerHoverIcon` on it. `CalendarHoverTiles` is that one drawing (the
block's slices and the width handle's two halves both go through it), and `blockBubbleOverlays` is the one
reading of what a panel says about itself, so the handle lying on top of a panel reports exactly what the
panel would have.

Three consequences worth keeping:

- **The strip the cursor promises is the strip the press grabs.** `edgePx` (6 dp, capped at a third of a
  short slice) is computed once and read by both the gesture and the tiles — through `rememberUpdatedState`,
  because the gesture coroutine is keyed on the block's position and outlives a zoom. It used to be computed
  inside the gesture from a captured slice height, so after a zoom the two could disagree.
- **The gesture and the right-click menu never depended on the strip.** They live on ANCESTORS of these tiles
  — the block's slice and the day column — and an ancestor stays on the hit path of whatever descendant is
  hit. That is why the resize drag and the Edit/Remove menu worked on the edge all along, and why they still
  do now that the tile there reports a bubble: `calendarTitleHover` observes at the Main pass and consumes
  nothing.
- **A width edge gets its own shape.** `horizontalResizePointerIcon` (desktop `E_RESIZE`) beside the existing
  vertical one; the platforms with no OS resize cursor keep the crosshair fallback.

Tests: `CalendarHoverTilingTest` (a cut never costs a tile its sections — the whole safety of building the
strip out of the tiling).

## Known gap

The phone's contextual menu still names only the panel it was opened on — a phone has no hover bubble, and
the touch menu was not reshaped into a section stack.
