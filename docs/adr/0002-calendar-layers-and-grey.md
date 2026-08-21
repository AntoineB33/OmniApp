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
  If you touch either side, keep it true.

## A layer is read from the DEVICE'S OS HISTORY, not from the app

The seam is `deviceLockedIntervals(since, until)` (`SleepHistory.kt`): the stretches THIS device was
not unlocked, from its OS **lock/unlock** record where the platform exposes one, else its
**sleep/awake** record — the spec's own stated fallback.

On Windows the fallback is forced:

- Security 4800/4801 need the audit policy on AND an elevated reader.
- `Microsoft-Windows-Winlogon/Operational` only logs which notification SUBSCRIBER ran (811/812).
- The TerminalServices log records logon/logoff/disconnect but not lock.

So the jvm actual reads the same non-elevated **Kernel-Power 42/506 → 1/131/507** timeline the rest of
that file uses. On a Modern-Standby machine that is closer to lock/unlock than it sounds — 506 fires at
screen-off.

Ahead of the now-line nothing is observed, so only the ASSERTED regions hatch (`layerAsserted` in
`App.kt`: §17 sleep windows, §15 screen breaks, the user's own no-screen periods), and those hold for
both layers whatever any history says.

## A device that cannot be asked WAS UNLOCKED

`null` from the seam ⇒ the layer is simply not drawn. The user's example: *"if I run the app on a
computer and the data of the phone is not available because it is the first time I run the app, then it
is considered that the phone was always unlocked in the past."*

**This is the OPPOSITE default from `derivePauses`** ("no screen unless a device reported activity"),
and the difference is the whole point:

| Question | Source | Silence means |
| --- | --- | --- |
| "Was anybody working?" (`derivePauses`) | the app's own heartbeats | an answer — nobody was |
| "Was this device usable?" (a layer) | the OS | the question was never asked |

Only THIS device can be asked (there is no channel carrying a peer's lock history), so every other kind
gets `null`. `null` and an empty list are deliberately different answers, and the jvm actual tells them
apart with an `'OK'` sentinel line, since a successful query with no cycles prints nothing either.

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

### What did NOT change

Both contextual-menu options stay (a no-screen period is "a period asserting both layers", an
inactivity period is the grey one); the automatic override/trim between on-screen task panels and
no-screen periods stays; and `materializePastInactivity` stays — a past no-screen period that covered a
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

## Known and accepted: a task panel can sit UNDER the computer hatch

On the reference machine the OS reports standby over stretches the schedule banked records for (e.g.
10:25→10:43 on 2026-08-20). That is the device's record and the plan's assumption disagreeing, and the
device wins — it is the source the spec names.

The deeper question it exposes is whether `AdvanceSchedule` should bank a record at all over OS-reported
standby (PRD §9's "assume nothing" rule currently keys on no-screen PANELS, not on the OS history). That
is a scheduler change, not a display one, and was left alone.

Tests: `CalendarLayerTest` (evidence source, the assumed-unlocked default, the seam filter, the
no-screen intersection).

## Known inconsistency, deliberate and unresolved

The OS lock history feeds the calendar's LAYERS only. The engine's own pause derivation
(`inactivityGaps` → rest-pose seeding, sleep-band carving, and the §15 break-serving pause) still reads
the `device_active_session` rows — so the calendar and the break cadence answer "was the user away?"
from two different sources.

Unifying them means deciding that a break is served by the OS saying the screen was locked, which
changes cue timing and touches the engine/sync path — out of scope for a display fix. **Decide it
before adding anything else that reads one and not the other.**

## Known gap

A layer is non-interactive, so it has no hover bubble on desktop and no entry in the phone's contextual
menu — the two slopes are currently unlabelled on screen.
