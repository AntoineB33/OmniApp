# Changelog

Dated history extracted from `CLAUDE.md`. `CLAUDE.md` holds only the active invariants; the *why* behind each
decision lives in `docs/adr/`. This file answers "when did this change, and what did it replace?"

Newest first within each section.

---

## 1.6.0 — spec deltas and their status

Check here before assuming the code matches the docs.

### "All tasks" is the task tree, drawn a third time — 2026-08-29

→ PRD §7. `shared` (`ui/TaskListWindow.kt`, `scheduler/state/TaskListProjection.kt`,
`scheduler/state/SchedulerIntent.kt` + `SchedulerReducer.kt` + `SchedulerState.kt`,
`scheduler/ui/TaskTreeView.kt` + `TaskSchedulerScreen.kt`, `scheduler/domain/SchedulerDomain.kt`,
`App.kt`). Client rebuild — no Supabase deploy.

Asked for: *"In the All tasks window, it must be task cells."* The window listed each task as a plain
`Text` row with two numbers beside it — a second, much poorer implementation of a row the app already has,
with no menu, no Edit Mode, no sub-tree and no keyboard. It is now the **same `TaskTreeView`** the account's
tree and the §4 template are drawn by, over `projectTaskList(rootCells)`: the **live** state re-rooted at a
synthetic list (`TASK_LIST_ROOT_ID`) holding, in the sorter's order, the first occurrence cell of every
listed task, with its intents wrapped in `SchedulerIntent.InTaskList(inner, rootCells)`.

The rows are **real cells of the live tree**, never synthetic ones — that is what makes an edit there an
edit to the tree with no translation, and what keeps the occurrence counts and percentages honest (both are
read off `state.cells`). Re-rooting is the whole of the projection, so the visible order, `Ctrl+A`, the
arrow keys and Ctrl+F's walk follow the window's rows for free.

New in the window, and each one a consequence of the root being the *sorter's* order rather than the tree's:

- **"go to task tree"** on every row's §13 menu — the calendar panel's entry, under its own name and through
  the same `RevealCell` primitive. `App.kt`'s handler was hoisted so both surfaces call one function;
  `TaskCellMenuActions.onGoToTaskTree` is null in the tree (you are already there) and in the template.
- **"Collapse all"**, beside the sorter, closing every row the window has open.
- **"collapse subtree"** in a task cell's right-click menu, closing the clicked row and every expanded descendant in its own branch.
- **Nothing may be moved into the root.** The blue drop line never appears at root level
  (`TaskTreeView`'s `allowRootDrop`), and `reduceInTaskList` refuses such a `MoveSelectedCells` as the
  backstop.
- **A root row has no Mode selector — it is always renaming.** `SchedulerIntent.BeginEdit` gained an
  optional `mode`, set by `reduceInTaskList` (the one place that knows which cells are roots);
  `EditModeMenus` hides the selector to match. A cell that is not one of the rows still opens on §4's
  default.
- **"Update order"**, which appears exactly while an edit has moved a row's figure enough to re-sort the
  list. The displayed order is pinned (Compose-only, like the sorter), so editing a row cannot re-sort the
  list from under the cursor; a task created since is appended and one deleted drops out.

Three things the re-rooting could have broken, and how each is held:

- `pruneDetachedTree` now seeds `WellKnownIds.MAIN_LIST` **as well as** `state.rootListId`. A real root cell
  that is not the first occurrence of its task is reachable from neither the synthetic root nor a detached
  parent, so the first edit boundary in the window would have pruned it out of the tree. Every other caller
  is unaffected — for them the two ids are the same list.
- The **colours** are solved over the live state (`TaskTreeView`'s new `colorSource`), not the projection,
  whose ring would be ordered by the sorter. ADR 0013's identity — one hue per task across the tree, this
  list and the calendar — holds by construction, and the shared `TaskHueMemo.account` cache is not thrashed.
- The **synthetic list never escapes**: `withTaskListCapturedFrom` drops it, so it is in no history delta,
  no persisted payload and nothing on the wire.

The window's expansion, selection and edit session are its own (`taskListExpanded` / `taskListSelection` /
`taskListEditSession`) — local view state, never persisted or synced, so a row open here is not a row open
in the tree and moving the caret here can never enqueue a push. One gesture is **one** Main history unit
(`TreeMutationDelta`, "All tasks"), like the template window's. `SchedulerDomain.firstTaskOccurrences` is
the new one-walk plural of `firstTaskOccurrence` (one walk, not one per row — ADR 0009's display hot path).

Pinned by `TaskListRowsTest`; `TaskListWindowTest` still pins the domain's ordering unchanged.

---

### A grey period is never manufactured from evidence — 2026-08-29

→ ADR 0002. `shared` (`scheduler/state/SchedulerReducer.kt`, `scheduler/platform/WindowsPowerLog.kt`).
Client rebuild — no Supabase deploy.

Reported as: *"I run `scriptsccount3-deploy-windows.bat`, and I see a gray panel in the calendar as an
inactivity layer."* The deploy stops the running app, builds for minutes, reinstalls and relaunches; the app
banks nothing while it is down, and on restart `materializePastInactivity` wrote every elapsed span where
scheduled on-screen work met a no-screen period into `state.panels` as a real `no task allowed` period.
218 of them had accumulated on the release account.

Deleted, with its two call-site helpers. Grey appears for exactly three reasons — a covering period every
task has 0 resilience to, a period the user drew, or past beyond the app's memory — and "the devices
observed no screen" is none of them. It is evidence, so it stays derived: the calendar already paints the
vacated stretch as a derived grey band, and the display is unchanged. Existing materialized panels are left
in place (a hand-added inactivity period is the same object, so decode cannot tell them apart);
`materializePastSleep` stays, because a sleep session is a fact the user asserted with the toggle.

Alongside it, `WindowsPowerLog.debounce` made its cancellation **provisional**. A bounce cancelled as jitter
used to leave a genuine later wake with nothing to pair against, losing the absence outright — two wakes
with no sleep between them is not something the machine can do, so the earlier one is the spurious half.
Observed the same day: `506`@15:12:40, `507`@15:12:41, `507`@15:26:53 lost a real 14-minute standby, which
the §9 record bank then banked straight through as time at the desk. The restored pair is still re-tested
against the minimum dwell, so brief flips stay jitter. This reverses
`a_bounce_that_leaves_an_unmatched_wake_claims_nothing`.

---

### The now-line carries its seconds — 2026-08-29

→ ADR 0002, PRD §8. `shared` (`ui/CalendarUi.kt`). Client rebuild to see it — no Supabase deploy.

Three reported calendar anomalies, one cause. Zoomed in far enough for a 20-second look-away to be visible,
the calendar showed: a "no phone unlocked" hatch running past the now-line (*"how can it know in advance
there won't be any phone unlocked?"*), an Inactivity band after the now-line, and an "Open: Device" line on
a panel that looked entirely future.

- **The current-time indicator was drawn at `hour + minute / 60`**, i.e. at the top of the current minute,
  while every band, panel, layer region and device segment around it is placed to the second
  (`recordsForDay`). At zoom 1 a minute is 0.7 dp and the gap never showed; at the zoom `MAX_CALENDAR_ZOOM`
  exists for, a minute is well over a hundred pixels, so the line sat up to 59 s above where it belonged and
  everything the calendar had placed truthfully read as being on the wrong side of it. In the report the
  line was drawn at 16:41:00 with the real instant at 16:41:47: the layer ended exactly at `now`, the grey
  band ended at 16:41:16 (before it), and 30 s of the "planning" panel really had elapsed.
- **`LocalTime.hourOfDay()` is now the one reading**, used by the indicator, by the lock-to-now centring
  fraction (which must centre on the line the user can see) and by the reminder stack's now-line anchor.
  Nothing about the layers, the bands or the record bank changed — they were already right.

### A device is named by the first session row that states a kind — 2026-08-29

→ ADR 0002, PRD §8. `shared` (`ui/CalendarUi.kt`). Client rebuild to see it — no Supabase deploy.

The hover bubble's "Open: …" line called a month-old desktop install **"Device"**. `ActiveSessionRecord.kind`
post-dates the earliest sessions (schema v8), and the label was taken from the device's *oldest* row — blank
on any account older than that column — so an install that has been recording `"desktop"` ever since stayed
anonymous forever. A kind is a fact about the DEVICE, not about one session: `deviceLabels` now takes the
first row that actually states one, keeping the first-appearance order so the `Phone 2` / `Phone 3` numbering
never shifts. Spelled once and shared by `deviceActivitySegments` and `DeviceActivityIndex`, which
`CalendarDisplayEquivalenceTest` already pins together.

### The hour before bed is covered by the period "before bed" — 2026-08-29

→ ADR 0001, PRD §17. `shared` (`scheduler/domain/PeriodKinds.kt`, `scheduler/domain/SchedulerDomain.kt`,
`scheduler/engine/SchedulerEngine.kt`, `scheduler/state/SchedulerReducer.kt`, `ui/CalendarUi.kt`, `App.kt`).
Client rebuild to see it — no Supabase deploy.

User spec: *"The hour before bed is covered by the period 'before bed'."*

- **`PeriodKinds.BEFORE_BED` (`"before bed"`) is a third BUILT-IN kind.** The §17 wind-down was a hard-coded
  extension of the sleep obstacle — a second mechanism for "where may this task run", which is exactly what
  the resilience model exists to prevent. It is now one period of one kind:
  `SchedulerDomain.beforeBedPanels` lays `[bedtime − 1 h, bedtime)` for every §17 sleep window, and the hour
  is empty for the one reason any period empties a stretch — every task's default resilience to the kind is
  `0`. **A task given a value above zero now works through the wind-down**, which nothing could do before.
- **Derived from `sleepPanels`, never from a second reading of the schedule**, so the hour drifts with the
  wake time it is measured back from. `before-bed/{wake day}` is cut and regenerated by every fill, is an
  `isRegeneratedPanel` (out of the sync fingerprint and out of `schedulingSignature`), and is drawn as a
  grey band labelled **"Before bed"** with no Edit and no Remove — the "Sleep" band beside it is where the
  schedule that produces both is edited.
- **The wind-down cue keys on the period's own start.** `SchedulerEngine`'s `windDownInstants` reads the
  panels the fill laid instead of recomputing `sleep.start − 1 h`, so the notification and the band cannot
  drift apart.
- Built in, so it is neither definable again nor deletable (`isUserDefined` gates both), and an older
  payload holding a *user-defined* kind of that name collapses into it on decode with every task's override
  intact. Editable, unlike `no task allowed`: the task edit window shows it a row, and it has its own
  `PeriodKindEditWindow` (minus Delete).
- **Not `coversNoScreen`**: the user is still at a screen in that hour (PRD §17 lets the screen breaks fall
  in it), so the wind-down absorbs a dynamic period like any other emptiness but never counts as a **rest**
  that bars the breaks after it.
- Two general readings were tightened on the way: the reducer's `isTaskPanel` is now
  `!panel.isRestrictivePeriod` (a period of a kind with no legacy flag is a period too),
  `clipPlanForPinnedScreenBreak` never cuts a restrictive period of any kind, and a derived grey band now
  names itself (`decorativeBandLabel`) instead of always reading "Inactivity".
- Behaviour change on the plan: work now stops at **bedtime − 1 h** and the hour is a suspending region, so a
  chunk that meets it resumes after the night with its minimum intact.
  `SchedulerSleepTest.the_work_plan_stops_at_the_wind_down_hour_and_resumes_after_the_sleep_window` and the
  new `BeforeBedPeriodTest` pin it.

### A period is an object: its own edit window, and a new one now restricts — 2026-08-29

→ ADR 0001. `shared` (`scheduler/domain/PeriodKinds.kt`, `scheduler/domain/SchedulerDomain.kt`,
`scheduler/state/SchedulerIntent.kt`, `scheduler/state/SchedulerReducer.kt`,
`scheduler/ui/TaskSchedulerScreen.kt`, `App.kt`). Client rebuild to see it — no Supabase deploy.

User spec: *"a `+` button must allow the user to add a new period, which adds it to every other task with the
default value 0. Every displayed period must have a button to edit the period. This button opens a type 2
window with a button to delete this period, and a list of all the tasks with boxes to check, and their
resilience value to this period. A button allows the user to select all. When at least one task is selected, a
field appears at the top. If all selected tasks have the same resilience value, the field shows this value,
otherwise it is blank."*

- **`PeriodKinds.defaultResilience` is now `0` for every kind but `no on-screen task`** (it was `1` for
  everything but `no task allowed`). A restrictive period restricts: a kind the user has just defined turns
  **everybody** away until its own window lets somebody back in. Still nothing written to any task — absence
  *is* the default, so defining a kind stays free and a task created later carries the same answer.
  `no on-screen task` keeps its `1` because "on screen" is a `0` against it.
  - **Behaviour change on existing data, deliberately un-migrated:** a task that never overrode a
    user-defined kind was unaffected by periods of that kind and is now forbidden in them. Nothing decodes
    wrong (a stored `1.0` is now the override and is honoured; a stored `0.0` is dropped as redundant) — the
    plan simply follows the new default.
- **The task edit window's resilience section**: the "Add" button is a **`+`**, and each row's `×` is
  replaced by a **`✎`** onto the period's own window. Deleting a period moved into that window.
- **`PeriodKindEditWindow`** (sort 2, raised in `App`): the kind's name, **Delete** for a user-defined kind,
  every schedulable **leaf** with a check box and a percentage (`SchedulerDomain.periodKindTaskRows`),
  **Select all / Select none**, and — while anything is checked — a **bulk field** holding the shared value or
  **blank** where the selection disagrees (`SchedulerDomain.commonResilience`, `null` being a real answer).
- **`SchedulerIntent.SetPeriodResilience(taskIds, kind, value)`** is the one write for both the bulk field and
  each row: one gesture, **one** Undo/Redo unit, and no unit at all when it moves nobody. A fan-out of
  `SetTaskResilience` would have cost twenty `Ctrl+Z` to put back one edit.
- Opening the period window dismisses the task edit window it came from — the sort-2 price, not a bug.
- Pinned by `PeriodKindWindowTest` (rows, the blank field, the single history unit, the clamp/overrides-only
  rule, delete) and by the updated `TaskResilienceTest` (the new default, and the decode heal).

### An observed pause barred no screen break — FIXED 2026-08-29

→ ADR 0003. `shared` (`scheduler/domain/SchedulerDomain.kt`, `scheduler/engine/SchedulerEngine.kt`,
`scheduler/state/SchedulerReducer.kt`, `App.kt`). Client rebuild to see it — no Supabase deploy.

Reported anomaly: both calendar layers said nobody was unlocked from 12:15 to 12:28, and a **5-minute pose
was owed at 12:40** — thirteen minutes into the hour `side-dev/README.md` bars it in (*"after any ≥ 5-minute
stretch covered by 'no on-screen task' without any task, no 5min period in the next 1 hour"*).

- Cause: the recurrence bars read their rest stretches out of the **timeline** (ADR 0003 — there is no stored
  `lastRest`), and only two things ever put a pause on that timeline: a period the user drew, and
  `liveRestPeriod` — the pause **this device is in the middle of**, off `inactiveSince`/`activeSince`. A pause
  that had *ended* was in neither: a derive retires the live tail, and the 12:38 restart cleared it outright.
  So the bars walked a timeline in which the user had been at the screen without interruption since the
  placement origin.
- Fix: **what the devices observed is the third source**, `SchedulerDomain.observedNoScreenPeriods` over the
  `observedNoScreenRegions` the §9 record bank and the calendar's panel clipping already read — one reading of
  "nobody was at a screen here", now with three consumers instead of two. The kind is `no on-screen task`, not
  `no task allowed`: that is the whole of what the evidence says, so an account with an off-screen task
  correctly gets no rest stretch out of the same span.
- `SchedulerDomain.dynamicPeriodBase` is the one funnel the three parts are assembled in, and the **cue sweep,
  the published pause-cue due and the calendar now go through it too** — they asked with
  `restrictivePeriodsOf(state.panels)` alone, so the instant the app announced a break at and the instant the
  fill placed one at were answers to two different timelines.
- `SchedulerEngine.noScreenEvidence` exposes the engine's single cached scan (24 h window, coarse bucket, off
  the tick — ADR 0009) so the display shares it rather than deriving a second answer.
- Pinned by `DynamicPeriodsTest`: the observed pause bars the 5 min period for an hour, an off-screen task
  keeps the same span from being a rest, `fillSchedule` is actually handed the evidence, and the funnel
  carries all three sources.

### A ring lasts 3 s by default — CHANGED 2026-08-29

→ ADR 0010. `shared` (`scheduler/model/TaskModels.kt`). Client rebuild — no Supabase deploy.

- The Alarms window's **Rings for** field now opens on **3 s** instead of 30 s, for alarms and timers alike.
  A timer's ring length is the alarm's field unchanged (`AlarmEntry.DEFAULT_ALARM_SOUND_SECONDS` is the one
  constant both read), so the two could not have been given different defaults without splitting it.
- Existing rows are untouched — `soundSeconds` is persisted per row; only a new row, and a legacy payload
  written before the field existed, take the new default.

### A panel's label was written over the day's date — FIXED 2026-08-29

→ ADR 0002. `shared` (`ui/CalendarUi.kt`). Client rebuild to see it — no Supabase deploy.

Reported anomaly: the in-grid day-date badge ("Sat 30", drawn at every day boundary scrolled into view)
and the "Inactivity" label of the big band opening at that boundary were two texts drawn at the same
point, on top of each other.

- **The panel's label gives way, the badge does not move and no panel is stretched** — the same rule the
  short screen-break band already followed for its own name (`SCREEN_BREAK_LABEL_MIN_HEIGHT`). A panel
  opening within `DAY_DATE_BADGE_HEIGHT` of midnight writes its label BELOW the badge where it has the room
  for a whole label line there, and writes none where it has not: the zoom is what grows a short panel until
  it names itself, exactly as it is for a 20-second look-away.
- `panelLabelTopInset` is the one place that answers it, applied by the grey bands ("Sleep"/"Inactivity"),
  the screen-break bands and the task panels alike; `showsDayDate` is false for the grid's top row, whose
  date is written in the header above the viewport and overlaps nothing.

### The dragged screen break drew over the task panel — FIXED 2026-08-29

→ ADR 0003/0001. `shared` (`scheduler/domain/SchedulerDomain.kt`); new case in `SchedulerSchedulerTest`.
Client rebuild to see it — no Supabase deploy.

Reported anomaly: a 20 s look-away drawn on top of a task panel. `SchedulerDomain.clipPlanForPinnedScreenBreak`
— the display clip that keeps "a period accepting no task" true between fills — was a **no-op in mode 1**, i.e.
whenever any device of the account is unlocked.

- **An off-by-one against the half-open dragged period.** Mode 1 leaves the instant $t_p$ itself free, so the
  period the line drags is `(t_p, t_p + duration]`, which in the app's discrete time starts at `t_p + 1`
  (`DynamicPeriods.Instance.coveredFromMillis`). The clip's chain walk seeded its cursor at `t_p` and asked
  for a band with `start <= t_p`, so it never found the one band it exists to cut, returned the plan
  untouched, and the owed look-away parked at the now-line was drawn straight over the auto panel the fill had
  placed there. The walk now seeds one millisecond past the line; the transitive hop is unchanged.
- **Known, still open:** the drag also **re-anchors the 20 s bar at the line**, so every *later* occurrence of
  that bar slides with the now-line while the materialized plan does not. The clip is scoped to the chain
  covering the line ("the disturbed slot is the one the cursor is in"), which is why a second overlap can show
  up one bar ahead until the next re-plan. Reproduced on account 3's state at 2026-08-29 11:50:52: the display
  grid put a 20 s at 12:11:12 where the stored plan ran `planning` 11:56:00 → 12:13:50.

### A running timer marks the calendar — SHIPPED 2026-08-29

→ ADR 0010. `shared` (`scheduler/domain/TimerDomain.kt`, `ui/CalendarUi.kt`, `ui/AlarmWindow.kt`, `App.kt`);
new cases in `TimerTest`. Client rebuild to see it — no Supabase deploy.

Reverses ADR 0010's *"deliberately not on the calendar"*. A timer's whole content is an instant it will go off
at, and the calendar is where the app says when things go off.

- **The alarms' marker, one bit apart.** `CalendarRecord.alarm` stays "this is a ring"; the new
  `CalendarRecord.timer` / `PlacedRecord.timer` beside it says which sort — mirroring `ArmedAlarm.timer`, and
  deciding the icon (⏳ / ⏰) and nothing else. Everything else the alarm marker has (fixed height, the
  stacking sweep, inertness, and the exclusions from `blockRecords` / `allBlocks` / the drag snap set / the
  task-panel menu entries) is inherited rather than re-stated.
- **Drawn at most once, and only while running.** `TimerDomain.occurrencesInWindow` bounds the projection by
  the displayed span like the alarms' (ADR 0009) and is a filter, not a per-day walk: the instant is stored,
  so an idle or paused row has none, and the ring resets the row so nothing is left behind.
- **A nameless timer is named by its DURATION**, where a nameless alarm falls back to its time of day.
  `TimerDomain.formatDuration` / `formatCountdown` is that one spelling; the Alarms window's countdown column
  now delegates to it instead of holding a private copy.

### The two `t_p` modes, wired through — SHIPPED 2026-08-28

→ `side-dev/README.md` § *$t_p$ and 3 Dynamic Restrictive Period* / ADR 0003. `shared`
(`scheduler/domain/DynamicPeriods.kt`, `scheduler/domain/SchedulerDomain.kt`,
`scheduler/state/SchedulerReducer.kt`, `scheduler/engine/SchedulerEngine.kt`, `App.kt`); new `TpModeTest`,
rewritten assertions in `DynamicPeriodsTest`, `ScreenBreakWindowTest`, `RestPosePresenceWindowTest`,
`SchedulerCalendarTest`, `NotificationLogTest`. Client rebuild to see it — no Supabase deploy.

Both modes were implemented in `DynamicPeriods` and tested there, and **neither reached the app**. This wires
them.

- **The mode is which devices are unlocked**: mode 1 while any device of the account is unlocked, mode 2
  otherwise. `SchedulerDomain.tpMode` decides it once, `anyDeviceUnlockedAt` reads the input once — off the
  account-wide pause the calendar already draws (`displayInactivityGaps`, right edge inclusive because an
  ongoing pause's tail ends *at* the line), so the mode and the Inactivity band cannot disagree. **Replaces**
  the read of `SchedulerState.sleepingUntilMillis` (the Sleep/Work toggle), which said "gone to bed", not "no
  screen in use", and was the only thing either mode had ever been keyed on in the app. The reducer reads it
  through a new injected `SchedulerReducer.tpMode` seam, beside `liveRestGap` / `noScreenEvidence`; the
  display reads the same function over the same three engine flows.
- **A mode flip re-plans** (`SchedulerEngine.launchTpModeReschedule` → `requestReschedule`). Not a tick: the
  flip is an edge the OS announces. It cannot go in `schedulingSignature` — the mode is a fact about the
  devices, not about the account's data, and so is never synced.
- **Mode 1 drags again.** `SchedulerDomain.dynamicPeriodPanels` passed `sweepFromMillis = tpMillis`, which
  made the drag fire only on exact equality — the line swept nothing, ever. It now passes the placement
  origin wherever `tpMillis` really is the line (a new `atLine` flag; every other caller — the cue's dues, the
  pause cue's next break, a navigated week — keeps the bars' undragged answer). So an owed period sits on the
  line as `(t_p, t_p + duration]`, realized in discrete time as `[t_p + 1, t_p + duration + 1)`
  (`Instance.coveredFromMillis`) so it stays an ordinary `TaskPanel`. **Consequence, deliberately accepted:**
  while a device stays unlocked and no rest happens, the owed chain parks at the now-line and no task is
  scheduled under it, and a stretch the line crossed in mode 1 draws task panels rather than breaks.
- **The drag is bounded by the bars themselves**, which is why the fear that motivated `sweepFromMillis =
  tpMillis` was unfounded: pushing a period onto the line re-anchors its own bar there, so at most one
  occurrence per bar is swept and the chain merge collapses what piled up into the longest. A drag is also a
  *move like any other* — it goes back through the loop, so the ordinary rules still refuse to place it inside
  a stretch nobody can run in.
- **The cue keys on the DUE, not on where the period sits** — `screenBreakOccurrencesBetween`, the placement
  asked with the line left out, and the one reading of it. This is a partial reversal of "nothing slides, so
  the cue may key on the drawn start" (2026-08-27): nothing slides *on its own*, but a period the line is
  pushing is always "starting now", is never crossed, and a sweep keyed on it fires at every scan — the
  2026-07-12 "spammed every frame" failure. `nextScreenBreakStartMillis` (the pause cue's `break_due_ms`) is
  the same reading, so the server and the client still key on one instant.
- **Mode 2's cover reaches the app**: `DynamicPeriods.awayCover` split out of `periods()`, emitted by
  `dynamicPeriodPanels` as an `Away` panel of kind `no on-screen task` — so a task resilient to that kind may
  work through it and an on-screen one may not. Where the app already has live evidence (this device's
  ongoing pause) that evidence *is* the cover: `liveRestPeriod` is now `closedEnd` while the pause is ongoing,
  so it covers the now-line and `awayCover` finds nothing left to do.
- **Bars are measured from the last instant a period COVERS** (`coveredUntilMillis`), not from
  `start + duration`. A dragged period ends one millisecond later than its nominal end, and a bar read off the
  nominal end put the next 20-second period at 19 min 59.999 s.

### Timers, in the Alarms window — SHIPPED 2026-08-28

→ PRD §18 / ADR 0010. `shared` (`scheduler/model/TaskModels.kt`, new
`scheduler/domain/TimerDomain.kt`, `scheduler/engine/SchedulerEngine.kt`, `scheduler/state/*`,
`scheduler/persistence/SchedulerStateCodec.kt`, `scheduler/sync/SnapshotMerge.kt`, `ui/AlarmWindow.kt`,
`App.kt`) and `androidApp` (`AlarmClockScheduler`, `AlarmClockReceiver`, `AlarmRingService`,
`SchedulerHolder`); new `TimerTest` + `TimerEngineTest`. Client rebuild to see it — no Supabase deploy.

- **The Alarms window has a second section: Timers.** A row is a duration (`SS` / `M:SS` / `H:MM:SS`, default
  5:00, max 24 h), a label, a live countdown, start / pause / reset, and the same *Rings for* and *Vibrate*
  fields an alarm has. It rings the same acoustic-guitar loop for the same length, through the same
  notification funnel, titled *Timer*.
- **A timer is an alarm at an ABSOLUTE instant, and that is the whole difference.** An alarm's due instant is
  derived from the local calendar per ringing day; a timer's is stored, fixed the moment it was started. So
  everything downstream — the phone's OS arming, the desktop's now-line sweep, `onAlarmFire`, the ring — is
  the alarms' machinery unchanged rather than a parallel one. `TimerDomain` carries no time zone at all.
- **One OS alarm slot, so one arming loop and one sweep.** `launchAlarmArming` now combines both lists and
  arms the soonest of the two; `launchAlarmSweep` merges both crossing streams in boundary order
  (`ringCrossingsBetween`). A second arming loop would not have added a ring — it would have overwritten the
  first's. `ArmedAlarm.timer` says which list a ring came from, and travels with it into the phone's OS intent.
- **`endsAtMillis` is authoritative and synced; the remaining time is derived and stored nowhere.** Starting a
  timer on the desktop is therefore what makes the phone ring at its end — the same promise PRD §18 already
  made for alarms — while a counting-down timer writes nothing and cannot move the sync fingerprint on a tick.
  Three run states live in two nullable fields of which at most one is non-null, healed in one place
  (`TimerDomain.healed`) from decode, from the merge and from the reducer.
- **No on/off switch and no repeat switch**: a timer that is not running is already not due, and a timer is a
  one-off by nature — having rung it *resets* to its full duration, where a one-off alarm *disarms* itself
  because it has a switch to leave off.
- The countdown reads down off the window's own 250 ms poll of the app clock (only while the window is open
  and something is running), because the engine's now-line advances once per 30 s production tick. Editing a
  row's settings mid-countdown cannot disturb the instant it is due at. A timer draws nothing on the calendar,
  deliberately.

### A Notifications switch, and a chord to silence them — SHIPPED 2026-08-28

→ PRD §7/§11. `shared` only (`scheduler/platform/GlobalHotkey.kt`, `scheduler/platform/SystemNotifier*.kt`,
`scheduler/engine/SchedulerEngine.kt`, `scheduler/state/*`, `scheduler/persistence/SchedulerStateCodec.kt`,
`scheduler/sync/SnapshotMerge.kt`, `ui/CalendarUi.kt`, `App.kt`); new `NotificationMuteTest`, two new
`KeyboardShortcutsCatalogTest` cases. Client rebuild to see it — no Supabase deploy.

- **The lateral menu has a "Notifications" switch, and `Ctrl+Shift+Alt+N` is the same lever from the
  keyboard.** Off silences every notification the app posts — a break's start and end, "task to do now", the
  wind-down, an alarm, and the system-wide chords' own receipts. There is no exempt caller: they all funnel
  through `SchedulerEngine.notifyUser`, which is the one place the switch is read, and a mute that let one
  class through would not be a mute.
- **It silences the interruption, never the record.** The notification log is appended before the platform
  call, so the History window's Notifications column still lists everything the app decided to say while it
  was muted (which is also why that column was never proof of delivery).
- **Switching off also withdraws what the OS is already showing** (`cancelSystemNotifications`) — Android's
  shade and iOS's Notification Centre hold a notification until it is dismissed, so "cancel every
  notification" has to answer the pile already on screen as well as the ones still to come. The desktop
  actual is a deliberate no-op: a tray balloon cannot be recalled once shown.
- **Switching back ON posts one notification saying so.** The chord's ordinary receipt is raised *before* the
  action, so on the un-mute press it is still muted and swallowed — which would leave the one press whose
  whole subject is notifications as the only one the user cannot see landing. This is that press's receipt,
  posted from the far side of the flip.
- The switch is the fourth **rebindable** system-wide chord and the first *switch* to name a chord on hover;
  it is persisted + synced (an account preference, like the look-away voice switch beside it) and is not an
  Undo/Redo unit. It says nothing about the voice cues, and nothing about the schedule: a break still starts
  and ends where it did, silently.

### An unlock turns "I'm away" off — SHIPPED 2026-08-28

→ PRD §15, ARCHITECTURE.md §8. `shared` only (`scheduler/engine/SchedulerEngine.kt`); new
`UserAwayUnlockTest`. Client rebuild to see it — no deploy otherwise.

- **"I'm away" now clears itself when this device is unlocked.** The toggle overrides the platform screen
  sensor, so until now nothing but a second press ever took it off: leaving with the button pressed, locking
  the machine and coming back to it left the app declaring an absence that had visibly ended — the active
  session stayed finalized and the presence heartbeat stayed closed at a desk somebody was sitting at, which
  is exactly the state the server's cue gate reads.
- **The trigger is an EDGE, and it needed no polling.** The lock and the unlock are notifications the OS
  already sends and the engine already receives (`WM_WTSSESSION_CHANGE` on Windows, `ACTION_SCREEN_OFF` /
  `ACTION_USER_PRESENT` on Android, both arriving at `onPlatformActivityChanged`) — the same signal the button
  is overriding. `SchedulerEngine.noteScreenSignal` reads the lock→unlock transition of the RAW
  `screenActive()` sample off it; the active-session beat calls it too, but only as a backstop for a missed
  notification, not as a cadence of its own. No timer was added.
- Only that edge clears. A lock while away leaves the flag on (locking the machine is not coming back to it),
  an unlock with no lock behind it is not a return, and the first sample after start is no edge — an engine
  that starts on a locked device has no phantom transition to answer. A host with no session signal (a
  non-Windows JVM, a failed native install, iOS) never flips it and keeps the flag as the user set it, the
  same degradation `isScreenActive` already has there.

### "no task allowed" leaves the edit window; every screen break names itself; both layers cut the panel — SHIPPED 2026-08-28

→ `side-dev/README.md` § *Restrictive Period*, PRD §8/§9/§15, ADR 0002/0003. `shared` only
(`scheduler/domain/PeriodKinds.kt`, `scheduler/domain/SchedulerDomain.kt`, `scheduler/ui/TaskSchedulerScreen.kt`,
`ui/CalendarUi.kt`, `App.kt`); new `ObservedNoScreenPanelClipTest`, two new `TaskResilienceTest` cases. Client
rebuild to see it — no deploy otherwise.

- **The task edit window no longer offers a resilience to "no task allowed".** It is the one kind whose value
  is not the task's to pick: by its own name it accepts nobody, so its multiplier is always `0`. The rule is
  one predicate, `PeriodKinds.isResilienceEditable`, read by the window's row loop (and by App's save loop, so
  the intent can never be raised for it). It governs the **window** only — `resilienceFor` still answers for
  the kind everywhere, and an override an older payload wrote still decodes, syncs and is obeyed by the walk.
- **Anomaly: a screen break showed no name, on the calendar or in the hover bubble.** Both halves were one
  cause. The band's title was gated on a 13 dp height and the band is drawn at its true duration — a
  20-second look-away is a third of a device pixel, a 5-minute pose about five — so no break ever reached the
  gate; and the hover zones are mapped onto that same rendered height, so the hairline was too thin to put a
  cursor on and the bubble named the sleep band underneath instead. `SCREEN_BREAK_MIN_HEIGHT` is now one label
  line (16 dp) and the title is unconditional, ellipsised when the column is too narrow.
  `SCREEN_BREAK_LABEL_MIN_HEIGHT` is gone.
- **Anomaly: an on-screen task's panel crossed a stretch where neither a computer nor a phone was unlocked.**
  ADR 0002 already says a stretch carrying BOTH layers *is* a "no on-screen task" period, and a no-screen
  period overrides the on-screen task panels it covers — but only the record half of that was implemented
  (§9 refuses to bank there; the panel went on being drawn). `SchedulerDomain.clipPanelsForObservedNoScreen`
  is the other half, cutting on-screen task panels out of `observedNoScreenRegions` — the same set the record
  bank asks, so the two can never disagree. Off-screen tasks are exempt (§9 lets them run in a no-screen
  period), a restrictive period is never cut, and a **failed** own lock scan is not evidence (no regions, no
  cut) — the calendar's own "assumed locked" default would otherwise erase a whole displayed past on one
  PowerShell hiccup. Display-side, like the §15 plan clip: the regions are the past, the fill only places
  ahead of the now-line. What the cut vacates draws as a derived "Inactivity" band.
- The calendar's OS lock-history scan moved **above** the record assembly in `App.kt` (it is now an input to
  the panels, not only to the hatching). Same query, same quantized window, same off-UI-thread dispatch.

### A calendar task panel reaches its task: "edit task" / "go to task tree" — SHIPPED 2026-08-28

→ PRD §8 / §13, ADR 0002. `shared` only (`SchedulerDomain.kt`, `ui/CalendarUi.kt`, `ui/PopupWindows.kt`,
`scheduler/ui/TaskSchedulerScreen.kt`, `scheduler/ui/TaskTreeView.kt`, `App.kt`); new `GoToTaskTreeTest`.
Client rebuild to see it — no deploy otherwise.

- **A task panel's right-click menu now has two entries about the TASK**, beside the "Edit" that is about the
  panel: **"edit task"** opens the §13 edition window, and **"go to task tree"** selects the task's first cell
  in the tree. Offered on a task panel only — a period, a reminder, an alarm, a sleep band, a screen break and
  a layer region are not tasks.
- **The tree cell menu's "edit" was RENAMED "edit task"** — the calendar opens the very same window, and two
  names for one window is how two surfaces start reading as two features.
- **"First occurrence" is the tree's own reading order**, decided once in `SchedulerDomain.firstTaskOccurrence`:
  depth-first, each **list** visited once (a mirrored sub-tree is one list under many parents), skipping
  blank-titled cells — §4's deleted ones, which the reveal could not expand anyway. It returns the cell **and
  the ancestor chain**, which is exactly what `RevealCell` takes, so the jump reuses the find bar's primitive
  (expand the way in as ONE history unit, then select) rather than growing a second selection path. The tree
  is then focused, which is what "going to" it means and what re-arms its keyboard.
- **`null` is a real answer**: a panel outlives the cell that laid it (panels are not per-tree), so it may name
  a detached parent, a task §4's blank title deleted, a task another named tree owns, or no task at all. All
  four say the same thing — **an error message**, in the app's first `MessagePopup`, a sort-2 pop-up (one
  notice at a time, gone when anything else takes focus, no scrim and no timer).
- **The tree's bring-the-row-into-view effect is now keyed on the SELECTION, not on the find bar's current
  match.** A match is no longer the only thing that reveals a row, and the calendar cannot reach into that
  composable; ordinary keyboard navigation lands there too and wanted the same thing.

### A button that has a keyboard shortcut names it on hover — SHIPPED 2026-08-28

→ ADR 0011 § *A button that has a chord names it on hover*. `shared` only (new `ui/ShortcutHint.kt`,
`ui/CalendarUi.kt`, `ui/KeyboardShortcuts.kt`, `ui/TaskTreeFindBar.kt`, `scheduler/ui/TaskSchedulerScreen.kt`,
`App.kt`); `KeyboardShortcutsCatalogTest` extended. Client rebuild to see it — no deploy otherwise.

- **Hovering a control that duplicates a chord shows that chord in an info bubble.** `ShortcutHint` is the one
  place such a bubble is drawn; a control with no chord passes `null` and is a plain `Box`. Today: the lateral
  menu's "Look away now" / "Switch task" / "I'm away" / "I'm back", the find bar's ↓ / ↑ / ✕ / Replace, and the
  deep-copy window's "copy".
- **The three system-wide chords are read LIVE off the account's bindings** —
  `GlobalShortcutBindings.chordOf(state.shortcutBindings, …)`, the same lookup the keyboard-shortcuts window,
  the receipt notification and the diagnostics use. `App.kt` hands `LateralMenu` the very map it hands
  `installGlobalHotkeys`, so a rebinding reaches the bubble with no second resolution and a bubble can never
  print a chord the app is not claiming.
- **The fixed per-surface chords are spelled once**, in the new `ControlChords`, read by the button and by
  `KeyboardShortcutCatalog` both; the test fails if a hinted constant stops being listed in the window.
- **The bubble is placed below the control with a 6 dp gap, non-interactive and non-focusable** — a tooltip the
  cursor can reach steals the hover and flickers (ADR 0002's "catch the bubble"), and a focusable one would eat
  the click. Hover is read with `Modifier.onPointerEventCompat`, promoted from private in `CalendarUi.kt` to
  internal rather than copied. Touch-only devices get no bubble, which is the correct no-op.

### Task colours: the LEAVES own the circle — SHIPPED 2026-08-28

→ ADR 0013. Replaces the *one colour space handed down the tree* rule shipped the day before.
`shared` only (`TaskColorSpace.kt`, new `ui/TaskHueMemo.kt`, `TaskColorPalette.kt`, the three call sites);
`TaskColorSpaceTest` rewritten. Client rebuild to see it.

- **The tasks with an empty sub-tree are placed first and spread as far apart as they can be** — `n` of them
  take the `n` hues `i/n`, the arrangement maximising the smallest distance between any two. Their order
  around the circle is the tree's depth-first order, so "the closer in the tree, the closer in the colour
  space" costs nothing: every order spreads them equally well.
- **Every other task then takes the point of its OWN sub-tree's arc furthest from every colour already given
  out**, one at a time, most constrained first. The arc is the smallest stretch holding the leaves below it,
  widened by half a ring step at each end (without which the parent of a single leaf has nowhere to go). The
  maxima are exactly the gap midpoints plus the arc's ends, so the search is an enumeration, not a sample.
- **The collision the old rule had by construction is gone.** A cell's colour used to be the *average of its
  own arc*, so a child sitting in the middle of that arc got the parent's very hue (`Book` and `Draft` in the
  test fixture). `TaskHue` still carries the depth and the palette still spends it on lightness, but it is now
  a legibility cue for neighbouring hues, not the thing that separates two tasks.
- **Ties are settled toward the PREVIOUS answer** — the ring's rotation and each parent's pick both. The
  circle has no origin and a gap has two equally distant halves, so ties are the normal case; broken
  arbitrarily they repaint the whole tree on every edit. `hues(state, previous)` is a fixed point of itself.
- **New `TaskHueMemo`, one per tree**: `TaskHueMemo.account` is shared by the task tree and the calendar (so
  the two cannot derive different colours for one task), the PRD §4 template gets its own. It caches on
  `cells`/`lists`/`tasks` — the ADR 0009 per-tick guard, unchanged in effect — and `rememberTaskHues` adds a
  **400 ms debounce**, the first composition still answered at once.
- Nothing persisted or synced changed: colours stay derived.

### Grey periods are marked with VERTICAL LINES, delimited — SHIPPED 2026-08-28

→ ADR 0002 § *How grey is MARKED*. `ui/CalendarUi.kt` only; no logic change, no test change.

- **`greyPeriodMarks` is the one place a grey period becomes something to paint.** Inactivity periods, sleep
  windows and all three screen breaks go through it, so all three are marked identically. The screen break's
  blue outline, its `●` and its accent-coloured title are gone (`SCREEN_BREAK_CLOSED_ALPHA` deleted): they said
  a break was a different sort of period from the inactivity band beside it, and it is not — all three are
  `no task allowed`.
- **Vertical lines, not a filled tint** (`SLEEP_BAND_ALPHA` deleted). A grey period can legitimately hold a
  task panel — §17 projects the plan straight through a sleep window, and a task with a non-zero resilience to
  `no task allowed` works through a break — and a wash repainted those panels in grey, taking their task
  colour. Lines leave every possible task colour readable through the gaps.
- **The marking moved OVER the panels**, alongside the layers and for the same reason: behind them, a stretch
  with a block on it showed no marking at all. That is what had forced `CalendarBlock`'s per-block
  `sleepHourRanges` grey overlay, which is deleted with its parameter and its plumbing.
- **Each band is DELIMITED** — an edge line across its top and bottom, drawn half a stroke inside the band —
  so an inactivity period ending exactly where a sleep window starts still reads as two periods rather than
  one merged stretch.
- The bands stay non-interactive (no pointer input), the visible-window culling (ADR 0009) is unchanged, and
  the hover bubble's sections still come from `contextOverlays`.

### The scheduler and the calendar are rebuilt on `side-dev/README.md`'s RESILIENCE model — 2026-08-28

The README says a restrictive period is a start, an end and a **kind**, and that each task has a **resilience**
to each kind: a multiplier in `[0, 1]` on its priority percentage for as long as a period of that kind lasts.
The app had a boolean world instead — an on-screen flag, a *doable during a break* flag, a per-break accepted
set — and this replaces all of it. → ADR 0001 §4, ADR 0003, ADR 0012.

- **`Task.resilience` (overrides only) replaces `onScreen` / `doableDuringBreak`.** An absent kind takes
  `PeriodKinds.defaultResilience` — `1` for every kind except `no task allowed`. Two README sentences fall out
  of that: a kind the user has just defined restricts nobody (defining one writes nothing to any task), and
  "on screen" is exactly a `0` against `no on-screen task`. `Task.onScreen` survives as a derived reading.
  A payload written by an older build is migrated on decode from its `onScreen` flag; encode still writes that
  flag, so an older build reads the new payload.
- **The user defines period kinds in the task edit window** (`SchedulerState.periodKinds`, authoritative +
  synced). The two built-ins are never in that list; `state.allPeriodKinds` is the one reading of "every kind a
  task can be resilient to". Removing a kind takes every task's override and every panel laid with it.
- **`weightsAt` / `localSharesOf` / `serveWeighted`.** The walk races on the effective weights and charges
  service against them (the reference's `v += served / w[name]`), which is what makes a multiplier mean "half
  the percentage for as long as the period lasts". The steady cycle inherits those effective shares; built on
  the nominal ones a standing period halving one side still answered a flat 50/50. The influence field became
  fractional to match (`deprivationsOf`: `Σ (1 − mult)·length`).
- **A behaviour change worth knowing: an off-screen task is no longer CONFINED to no-screen periods.** A period
  multiplies what it covers and says nothing about the timeline it does not, so "only tasks that need a screen
  may run everywhere else" is not expressible in this model — and the README never asks for it.
- **`SchedulerPlanner.plan()` phase 1 is now a literal port of `scheduler.py`'s `Walk.run`**, checked
  slot-for-slot. The sanctioned divergences are named in ADR 0001 §6: zero-priority tasks stay last-resort
  candidates, `Fraction` → `Double` millis, and `fillSchedule` keeps PRD §15's suspension. The atomic block is
  no longer one of them.

### Screen breaks: three recurrence bars replace the `lastRest` anchor engine — 2026-08-28

`side-dev/README.md` § *$t_p$ and 3 Dynamic Restrictive Period*, ported as `DynamicPeriods`. → ADR 0003.

- **All three breaks are `no task allowed`, end to end.** `ScreenBreakPeriod`, `ScreenBreak.shape`,
  `screenBreakOpenStartMillis`, `SCREEN_BREAK_CLOSED_HEAD_MILLIS` and the calendar's **hollow** band are gone
  with the shapes; a break draws as one grey span. A task works through one exactly when it has a non-zero
  resilience to that kind.
- **Where they fall is the three bars**: after any dynamic period no 20 s for 20 min; after a ≥ 5-min rest
  stretch no 5 min for 1 h; after a ≥ 15-min one no 20 s for 20 min and no 15 min for 2 h. Overlapping ones
  collapse to the chain's longest member at its earliest point.
- **The anchors are DERIVED.** `ScreenBreak.lastRestMillis` is deleted, and with it `seedScreenBreaksFromGaps`,
  `serveElapsedScreenBreaks`, `serveShorterBreaks`, `pastScreenBreaksFromPauses`, `advanceRestsForward`,
  `screenBreakNextStart`, `isScreenBreakOverdue`, `reachedRestPoseDueByTitle`, `simulateScreenBreaks`, the
  decoupled-pose case, the dense-projection cap, the engine's two seeders and `reduceReportDeviceSleep`'s
  anchor advance. Rest stretches are read out of the timeline itself; a live pause reaches the placement as
  the period it is (`liveRestPeriod`).
- **Nothing slides, so every cue keys on the DRAWN start** (`cueCrossings` → `screenBreakOccurrencesBetween`).
  What is announced and what is drawn are one instant by construction. The sweep must be handed the same
  environment the fill was, and its self-delay reads the next placed start.
- **The placement origin is anchored on the now-line, quantized to the day** (`dynamicPlacementOriginMillis`),
  never on each query window's own left edge — otherwise the fill, the cue sweep and the calendar walk
  different grids whenever one straddles a midnight.
- **A materialized break is never an input to its own placement**: `restrictivePeriodsOf` drops `screenBreak`
  panels, or each break becomes a blocked stretch absorbing the next.
- **A conducted break is recorded as a period** (`SchedulerIntent.RecordConductedBreak`), at its exact span —
  not through `AddInactivityPeriod`, which rounds up to a minute. Only on completion.
- **`device_break.break_due_ms` is now the pose's next placed START** (`nextScreenBreakStartMillis`) instead of
  the anchored due `lastRest + interval`. The anchored form existed *because* the drawn start rode the now-line
  and could not be written event-driven; the bars pin it, so server and client key on one instant. No Supabase
  change: the column's meaning is unchanged ("when does this break next come due"), only the client's
  derivation of it.
- **The debug fast-break knobs lose `pauseThresholdMs`** (`ScreenBreak.pauseThresholdMillis` /
  `qualifyingPauseMillis` / `DebugFlags.breakPauseThresholdMillisOverride`, the `OMNIAPP_*_PAUSE_THRESHOLD_MS`
  script variables and the `omniapp_break_pause_threshold_ms` Android extra). It decoupled a break from the
  pause that *anchored* it, and nothing is anchored any more. Duration and interval remain.
- **Tests:** `DynamicPeriodsTest` and `TaskResilienceTest` are new; `ScreenBreakHollowTest` →
  `ScreenBreakKindTest` and `RestPoseNotificationRuleTest` → `ScreenBreakCueRuleTest` (same rules, new
  mechanism); `SchedulerScreenFlagsTest` and `LiveRestPlacementTest` are deleted with what they pinned.

**Deploy:** client rebuild only (`account{1,2,3}-*deploy*.bat`). No migration, no Edge Function change.

### The Windows lock-history query reads the whole power history (`WindowsPowerLog`) — FIXED 2026-08-27

- **The ask:** is the app's detection of the Windows lock/unlock history as good as
  `computer/Get-SleepCycles.ps1`? It was not. Same technique — non-elevated `Get-WinEvent`, one merged
  timeline, pair down→up — but on a strict subset of the events, with no debouncing and one unhandled
  window edge. All three `SleepHistory.jvm.kt` actuals now share `WindowsPowerLog`, so the layer, the
  record-bank evidence, the screen-break seed and the exact pause recorder can no longer disagree about
  whether the user was there.
- **Shutdown and boot are now absences.** The old query watched Kernel-Power `42`/`506` → `1`/`131`/`507`
  and nothing else, so a machine switched OFF overnight — which writes `109`/`13`/`6006` and `12`/`6005`,
  on two other providers — produced no pair at all and read as time at the desk. `6008` (power loss) is
  stamped at the next boot, so its real instant is taken from the record's own properties.
- **Each provider is asked for its OWN ids**, because `1` is Kernel-Power's "resumed" *and*
  Kernel-General's "the system time has changed". Verified against the author's own log: with a flat id
  list a sleep at 01:19:44 pairs with the 01:19:45 clock resync into a one-second absence and the genuine
  09:18 resume has nothing left to close — the eight-hour night vanishes. `Get-SleepCycles.ps1` has this
  bug; the app now does not. The reference script's `6008` correction is also inert, as it reads
  `ReplacementStrings` (a `Get-EventLog` property) off a `Get-WinEvent` record.
- **A 60-second debounce**, as in the reference script: a flip that did not hold cancels the transition it
  undid, a repeat of the state already held is dropped, and the timeline strictly alternates. Kills the
  three-second "locked" slivers a standby bounce used to emit. Sub-minute locks become invisible —
  accepted, being the scale the derived grey bands already drop.
- **The window's opening state is asked for.** `StartTime`/`EndTime` still bound the query (ADR 0009), but
  `PRIOR_EVENTS` events from before it now establish what state it opens in; a window beginning
  mid-absence used to drop its unmatched wake and report the whole lead-in as present. The trailing edge
  was already clipped.
- **The `OK` sentinel got stricter**: printed only when every error was `NoMatchingEventsFound`, so a log
  this process may not read now answers "cannot tell" (`null`) instead of "never locked". That
  distinction is load-bearing — a failed query must never become evidence in the record bank.
- Measured on a 7-day window: 740 ms for six queries. `WindowsPowerLogTest` pins the vocabulary, the
  debounce, both window edges, the sentinel and the provider partition. **No deploy surface but the client
  apps** — rebuild via `account{1,2,3}-*deploy*.bat`.

### The keyboard-shortcuts window can rebind the system-wide chords — SHIPPED 2026-08-26

- **The ask:** make the keyboard-shortcuts window able to customize the shortcuts.
- **Scope, and why it stops where it does.** Only the **three system-wide chords** are rebindable. They are
  the only shortcuts in the app that can collide with anything *outside* it — a system-wide claim is first
  come, first served, so a chord another application already owns is simply unusable until the user can move
  it (the window's own claim line has been reporting exactly that failure). Every other entry in the window is
  a Compose `onPreviewKeyEvent` branch scoped to a surface: nothing to collide with, and nothing that reads
  the branches back, so making those rebindable would mean re-routing ~40 hardcoded branches through a lookup
  for a failure that cannot happen there. The rest of the window stays a reference list.
- **`GlobalShortcut` now carries a `defaultBinding`, not a `chord`.** The live chord is
  `GlobalShortcutBindings.chordOf(state.shortcutBindings, shortcut)`, and the window, the receipt notification
  and the diagnostics all print *that*. The old `chord` property is gone so nothing can advertise a chord the
  app is not claiming.
- **`ShortcutBinding` = a `ShortcutKey` + Ctrl/Shift/Alt.** `ShortcutKey` is a **closed** set (A–Z, 0–9,
  F1–F12) whose entry names are the persisted form: a chord has to survive the snapshot, the sync wire and
  every platform actual's own naming, and a layout-dependent key would give an AZERTY user a chord the QWERTY
  peer sharing that account has not got. No Win/Meta flag — Windows reserves it.
- **`SchedulerState.shortcutBindings` holds the OVERRIDES only.** An untouched shortcut is absent and follows
  its shipped chord, so a default changed in a later build still reaches every account that never rebound it,
  and **"Reset" removes the entry** rather than writing today's default in. Persisted **and synced** (the
  chords are the account's, so they follow the user to every machine).
- **A rebinding IS an Undo/Redo unit** — `ShortcutBindingDelta`, one Main unit, unlike the account settings
  beside it (`deepCopyMaxDepth`, the copy switches). It is one deliberate gesture on something whose effect is
  invisible from where the user is sitting when the chord is struck. Both sides carry the whole override map,
  because a reset is a *removal* and a delta saying only "X is now Y" could not put one back.
- **Two rules, stated once** (`GlobalShortcutBindings.rejection`, which the reducer refuses on and the window
  quotes): **at least two of Ctrl/Shift/Alt** — the claim swallows the chord session-wide, so one modifier
  would take Ctrl+C or Alt+F4 away from every application the user runs and none at all would eat their
  typing — and **no two shortcuts on one chord**. Consequence, accepted: swapping two chords needs a third in
  between; stealing the other shortcut's chord silently would be worse.
- **Rebinding is a capture, not a text field**, and the capture **stands the OS claim down**
  (`setGlobalHotkeyCapture`, a new expect/actual). Without it the chords the app already owns would be the one
  set of chords it could never hear: the hook swallows them and `RegisterHotKey` consumes them underneath,
  before Compose is handed the key. On Windows the flag short-circuits the hook and empties the hot-key table;
  it is balanced on a chord taken, Escape, focus lost, or the window closing.
- **`installGlobalHotkeys(bindings, onShortcut)`** — the seam was already "claim once, re-point the callback";
  a later call now also **re-registers the chords**, which is how a rebinding lands with no restart
  (`App.kt`'s `LaunchedEffect` is keyed on the bindings). `RegisterHotKey(NULL, …)` belongs to the thread that
  made it, so the UI thread posts `WM_OMNIAPP_RECONFIGURE` to the hot-key loop instead of touching the table;
  the hook just re-reads a volatile field. The hook's modifier check is now an **exact** match against the
  binding (a modifier the chord does not ask for must be up), with the AltGr pass-through preserved.
- **Two healing paths, because the collision is reachable without either device causing it.** Merging per
  shortcut can land two shortcuts on one chord when each device rebound a *different* one onto it —
  `SnapshotMerge.repair` drops the collision back to the default; and decode drops any stored row this build
  cannot name or that today's rules refuse, so the claim never holds a chord the window would not let the user
  set.
- Tests: `GlobalShortcutRebindTest` (new — the rules, overrides-only, the history unit, the codec round trip,
  a pre-1.6.0 payload, decode healing), plus new cases in `SnapshotMergeTest` and updates to
  `KeyboardShortcutsCatalogTest`. **Redeploy:** client app rebuild (`account{1,2,3}-*deploy*.bat`); no
  Supabase change (the bindings ride the existing whole-document snapshot).

### Every system-wide chord posts a receipt notification — SHIPPED 2026-08-26

- **The ask:** pressing `Ctrl+Shift+Alt+<letter>` should raise a notification, so the user can tell the app
  actually received the press.
- **Why it was needed.** The three chords are struck precisely while OmniApp is *not* the focused window, so
  the app shows the user nothing; and each of them can legitimately do nothing visible — "Look away now" with
  no look-away break configured returns silently, "I'm away" is a no-op on a same-value call, "Switch task"
  only announces if the re-plan starts a different task. That made "the hook never saw the press" (another
  application swallowing it underneath us, Windows dropping a hook that overran `LowLevelHooksTimeout`, a
  claim that came back `Unavailable`) indistinguishable from "received, nothing to do".
- **`SchedulerEngine.announceShortcutReceived(shortcut)`** posts `Shortcut received` / `<chord> — <action>`
  through the ordinary `notifyUser` path, so it also lands in the Diagnostics timeline and the History
  window's Notifications column. It names the **chord** so two presses in quick succession are tellable
  apart.
- **A receipt for the PRESS, not the effect:** called at the `installGlobalHotkeys` seam in `App.kt`, first
  and unconditionally, before the `when` that dispatches the action. Deliberately *not* inside the engine
  seams themselves — the lateral-menu buttons drive those same seams, and a click in a window the user is
  looking at needs no confirming (`GlobalShortcutReceiptTest` pins both halves).
- The keyboard-shortcuts window's System-wide note now says the receipt is expected.
- Desktop-only, like the chords. **Redeploy:** client app rebuild (`account{1,2,3}-*deploy*.bat`).

### The default sub-tree window IS the task tree (`scheduler/ui/TaskTreeView.kt`) — SHIPPED 2026-08-26

- **Reported anomaly:** right-clicking a row in the "Default sub-tree" window opened no contextual menu. The
  cause was not a broken handler but the absence of one: the window was a **hand-rolled re-implementation** of
  the task tree (its own row composable, its own title field, its own selection/edit/collapse state), and the
  tree's `contextMenuModifier` + `DropdownMenu` had never been copied into it. The same gap silently cost it
  multi-selection, drag-move, Ctrl+C/X/V, Ctrl+F and the min-time field.
- **The tree is now ONE composable, drawn twice.** `CellListSection` / `TaskRow` / `contextMenuModifier` /
  `EditModeMenus` moved out of `TaskSchedulerScreen.kt` into `TaskTreeView.kt`, which both the account's tree
  and the template window call. The window gets the **full five-entry §13 menu** — *start this task now*,
  *edit*, *copy*, *deep copy*, *add default sub-tree* — and every other tree gesture, by construction.
- **What made that possible: the template became a real tree.**
  `SchedulerState.defaultSubtree` changed from `List<DefaultSubtreeNode>` (a tree of *titles*) to
  `DefaultSubtreeTemplate` — a `TreeSnapshot` in the same shape a `TaskTreeEntry` stores, plus its expansion
  and the per-cell switch set. Four of the five menu entries need a real `Task` to act on, and "edit" writes a
  screen switch / schedule unit / text that the old node type had nowhere to put.
- **`state/DefaultSubtreeProjection.kt` is the seam.** `projectDefaultSubtree()` hands the tree component a
  state whose tree IS the template, with the live tree merged underneath so a bound row resolves and the
  ordinary Change Task menu can offer live tasks. `defaultSubtreePriorities()` deliberately computes the
  percentages on the template's cells **alone** — `absoluteTaskPriorities` iterates every cell it is given.
  `withDefaultSubtreeCapturedFrom()` folds the reduced projection back, keeping only what is reachable from
  the template's root and **discarding the live half**, so nothing dispatched in that window can reach the
  real tree.
- **New intents:** `InDefaultSubtree(inner)` wraps every tree intent the window raises (Undo/Redo excepted —
  they belong to the app's stacks) and lands as **one** `DefaultSubtreeDelta` Main history unit;
  `SetDefaultSubtreeCellBound` flips a row's switch. `SetDefaultSubtree` is gone.
- **Visible changes.** The percentage and minimum-time columns, previously suppressed as meaningless, are now
  shown and meaningful: the percentage is the row's share *within the template*, and the graft carries the
  minimum time, the task fields and each sub-list's weight table across. The switch takes a column of its own
  after them and now toggles **both** ways (a row always has a task to point at). A bound row's borrowed
  sub-tree is drawn by the tree as the ordinary mirror it is, rather than by a bespoke greyed renderer.
- **Migration.** A payload holding the pre-1.6.0 `defaultSubtree` node array is built into the real tree on
  decode (`migrateDefaultSubtreeNodes`), minting ids past the account's own counters; a bound node keeps its
  binding and joins `boundCells`. That shape is still read and never written again. An **empty** template is
  written as nothing at all, so "written before the feature existed" and "empty" decode the same way.
  `SnapshotMerge` still resolves the template as one whole value.
- Known consequence, and it is the tree's own rule: a bound row's title lives on the task it points at, so
  deleting that task empties the row — and the blank title then deletes it, exactly as it would in the tree.

### Two sorts of pop-up window (`ui/PopupWindows.kt`) — SHIPPED 2026-08-26

- **The sort of a pop-up is no longer a per-call-site decision.** Sort 1 opens on the top layer and then lets
  whatever is focused next stack on top of it, staying open — that is `App`'s `windowStack`. Sort 2 opens on
  the top layer and leaves the moment anything else takes focus.
- **The test is whether it could have several instances open at once**: a pop-up about ONE object (a task, a
  cell, a sub-list, a calendar block, a period, a reminder, a history unit, a tree entry) is sort 2. Which
  makes sort 1 exactly the ten lateral-menu windows, and every other pop-up in the app sort 2.
- **Replaces three ad-hoc tiers**: the managed `windowStack`; the two priority windows at `zIndex(50f)` with a
  bespoke outside-press interceptor in `App`; and eight full-screen-scrim modals at `zIndex(100f)`. The
  interceptor is now the one app-root `transientPopupDismissRoot` (Initial pass, consumes nothing) feeding a
  `TransientPopupHost`, and every scrim is gone.
- **What visibly changes.** A sort-2 pop-up no longer blocks the app behind it, so the press that dismisses it
  also does its normal job — one click to close the task-edit window *and* focus the calendar, where the scrim
  cost two. At most one sort-2 pop-up is open at a time, by construction (`open` dismisses the others) rather
  than by each opener remembering to close its predecessor — the priority pair's hand-written mutual exclusion
  was the only place that had ever been done. Dismissal still discards a half-typed edit, exactly as clicking
  the old scrim did.
- **`TaskEditWindow` / `DeepCopyWindow` are raised out of `TaskSchedulerScreen` into `App`** (hoisted like the
  priority windows already were, via `onSetEditTask` / `onSetDeepCopyCell`). Declared inside the tree they drew
  *under* any floating window stacked over it, so "appears at the top layer" was simply false for them.
- `TransientPopupHostTest` pins the rules: outside press dismisses, inside press does not, a pop-up that has
  not laid out yet is not "inside", opening one closes the one already open, and `close` (Save/Cancel) never
  calls back into a composable that is already gone.
- Client-only: needs an app rebuild (`account{1,2,3}-*deploy*.bat`); no Supabase deploy.

### "start this task now" on a task cell (PRD §13) — SHIPPED 2026-08-26

- **New entry at the top of the task cell's right-click menu**: "start this task now" asks the schedule to put
  that task at the now-line. It is the mirror image of §7's "Switch task" button — one refuses the task the
  now-line is on, the other names the one it must be on — and both are the same lever read from opposite ends.
- **The model shape is the switch's, deliberately.** A `ForcedTaskStart(task, at)` is recorded (authoritative:
  persisted + synced, merged as one whole value, not an Undo/Redo unit) and the fill puts that task in the
  **first slot it places**, charged through `PlanWalk.serve` exactly like a slot the walk had chosen — so only
  that first slot is the user's answer and the schedule after it is the one the walk would have gone on with.
  No new scheduling rule, and nothing in `schedulingSignature`: the press re-plans inside its own reducer
  (`reduceForceTaskStart`), for the same reason the refusal does.
- **Liveness is the refusal's own predicate** (`SchedulerDomain.liveForcedStartTask`): outstanding until some
  *other* task has been served past `at` — for a refusal that means "the plan started something else", for a
  request "the plan has moved on". So a re-plan in between (a rule change, the hourly staleness refresh) keeps
  the user on the task they asked for, and the advance tick drops the marker once it is spent, exactly as it
  already did for `forcedSwitch`.
- **Answered in phase 1 and in phase 2.** A timeline nothing disturbs (no screen breaks, no fixed blocks)
  freezes before phase 1 places anything and builds the plan from the analytic cycle, so the request is placed
  there too — before the settle loop, which then squares up from the walk state it left.
- Offered only on a **schedulable leaf** (a parent task is a grouping §9 never places), and it names ONE task
  however many cells are selected — unlike "copy", "start *this* task" has no meaning for a block. Asking for a
  task also clears an outstanding refusal *of that same task*.
- `ForcedTaskStartTest` pins the whole contract (the pick, the one-slot scope, the two no-ops, the liveness,
  the advance-tick drop, the interaction with an outstanding refusal, and the decode of a payload written
  before the entry existed).
- Client-only: needs an app rebuild (`account{1,2,3}-*deploy*.bat`); no Supabase deploy.

### A freshly minted sub-list is never shown expanded (PRD §4) — FIXED 2026-08-26

- **Anomaly**: typing a title into an empty task cell and pressing Enter unfolded the new task onto nothing but
  its bare placeholder child row.
- **Cause**: `SchedulerState.expanded` is keyed by **cell** id, while a sub-list belongs to the **task**. A cell
  that had been expanded and was then emptied (PRD §4 *Deletion*, which takes its task's sub-list with it) kept
  its stale `expanded` entry — nothing prunes it, because the cell itself is still there and still reachable —
  so the next task typed into that same cell inherited an expansion the user never asked for. Not the default
  sub-tree: with the template off, the graft is a no-op and `endEditSession` adds nothing.
- **Fix**: `applySetCellTitle` drops the cell from `expanded` where it mints the task's sub-list — the one
  place the entry can go stale is the one place it is cleared. A rename does not mint a sub-list, so it still
  keeps its children on screen; the default-subtree graft still re-adds the cell in `endEditSession` once it
  has rows to show, and `AddDefaultSubtree`, Tab-into-child and the arrow are unaffected.
- `SchedulerReducerTest` pins both halves (the retyped cell stays folded, the renamed one stays open).
- Client-only: needs an app rebuild (`account{1,2,3}-*deploy*.bat`); no Supabase deploy.

### "All tasks" — the flat, sortable list of every task in the tree (PRD §7) — SHIPPED 2026-08-26

- **New lateral-menu button, "All tasks"**, opening a floating window (`ui/TaskListWindow.kt`) that lists every
  task of the LIVE tree with two columns: its **number of occurrences** and its **absolute priority
  percentage**. A mirrored task is **one row** — the tree's shape is exactly what the window is there to see
  past — carrying the cell count and the priority summed over all its chains.
- **A sorter configuration at the top**: which of the two figures orders the list (`TaskListSort.Occurrences` /
  `TaskListSort.Priority`) and which direction (highest first, top to bottom / lowest first, bottom to top).
- **The ordering lives in the domain** (`SchedulerDomain.taskListEntries`), not in the composable, so the rows
  are testable and the tie-break is one rule: equal figures fall back to the title then the id, so the order is
  total and cannot shuffle between recompositions — and the tie-break is **not** reversed with the direction.
- **The rows are counted off `state.cells`**, exactly as `absoluteTaskPriorities` and
  `RelativePriority.occurrenceChains` count them, so the two columns can never disagree about what an
  occurrence is. A task deleted by blanking its title (§4) is therefore gone from the list even while its
  records keep it alive, and a detached parent is not listed either — it is not in the tree.
- **The percentage is `absoluteTaskPriorities`, not `blendedTaskPriorities`** — the same identity the tree's own
  percentage column keeps: this window reads the arrangement on screen, which is what the user is editing, not
  the keyframe blend the scheduler is following. `formatPriorityPercent` was made `internal` and reused rather
  than copied, so the two readouts round identically.
- **The sorter is Compose-only state**, like the calendar's zoom and the §4 find bar: how a list is ordered on
  screen is a way of looking at the tree, never a fact about it — not persisted, not synced, no history unit.
  Only the window's own placement/visibility persists, like every other floating window's.
- `TaskListWindowTest` pins the mirrored row, the agreement with the tree's percentages, both sort keys, both
  directions, the tie-break and the deleted-task exclusion.
- Client-only: needs an app rebuild (`account{1,2,3}-*deploy*.bat`); no Supabase deploy.

### "Switch task" — the button that refuses the task the now-line is on (PRD §7, ADR 0011) — SHIPPED 2026-08-26

- **New lateral-menu button, "Switch task", with the system-wide chord `Ctrl+Shift+Alt+Z`.** It refuses the
  task the now-line is sitting on, so the plan starts a different one from that instant. The chord is claimed
  the same way the other two are (`GlobalShortcut.SwitchTask`, one more `Chord` row in the Windows actual, one
  more branch in `App.kt`'s `installGlobalHotkeys`) — it is wanted precisely while the user is inside the work
  they have decided to get off, which is when OmniApp is not the focused window.
- **It is expressed as the walk's `last`, not as a ban.** `SchedulerState.forcedSwitch`
  (`ForcedTaskSwitch(taskId, atMillis)`) is handed to `PlanWalk.setLast` by `fillSchedule`. Reusing the
  never-twice-in-a-row rule is what keeps the refusal cheap and correct: the refused task's virtual clock is
  untouched (so it loses none of its share and returns at the second slot), and it inherits the rule's own
  escape — a task nothing else can replace still runs rather than the period being left empty. A now-line on
  no task at all is a no-op.
- **It is honoured until it is granted, and read off the recorded past.**
  `SchedulerDomain.liveForcedSwitchTask` keeps the refusal live until some *other* task has actually been
  served past `atMillis`, so the re-plans that happen in between (a rule change, the hourly staleness bound, a
  pulled snapshot) cannot quietly hand the same task back, and a chain of re-plans still equals one long plan.
  `advanceSchedule` drops the marker at the tick that banks that other task's work.
- **Deliberately NOT in `schedulingSignature`.** `SchedulerIntent.ForceTaskSwitch` re-plans inside its own
  reducer (the press *is* the calculation event, like `RemoveRecordPeriod`). In the signature, the tick that
  drops the spent marker would fire a second, un-refused re-plan.
- Authoritative: persisted (two flat scalars in the codec, absent ⇒ no refusal) and synced (whole-value
  `pickNullable` in `SnapshotMerge` — never a task from one device paired with an instant from the other);
  not an Undo/Redo unit. `ForcedTaskSwitchTest` pins the pick, the sole-candidate escape, the no-op, how long
  the refusal lives, the advance-tick GC and the codec compatibility.
- Client-only: needs an app rebuild (`account{1,2,3}-*deploy*.bat`); no Supabase deploy.

### The period editor, and what a grey period clears (PRD §8, ADR 0002) — SHIPPED 2026-08-24

- **"Add a no-screen period" / "add an inactivity period" open an editor instead of laying a fixed hour.**
  `ui/CalendarUi.kt`'s `PeriodEditWindow` (one window for both kinds, `CalendarPeriodKind`) gives each bound
  three forms: a **date and time**, **"now"** (resolved at Save, not at open), or **"∞"**. Save is disabled
  while a field is half-typed or the period runs backwards. The same window is now a period's **"Edit"** — a
  hand-added inactivity period has one at last (a *derived* grey band still has none: no panel behind it) —
  which retired `ManualEntryEditWindow`'s `timesOnly` mode.
- **"∞" is `SchedulerDomain.OPEN_PAST_MILLIS` / `OPEN_FUTURE_MILLIS`** (1900 / 2200), real instants rather
  than `Long.MIN_VALUE`/`MAX_VALUE`: every consumer does ordinary arithmetic on a panel's bounds and a
  saturating sentinel would overflow the first `end - start`. `isOpenPast` / `isOpenFuture` recognize them,
  and the hover bubble prints "∞" for either end.
- **A grey period now overrides everything it covers.** An inactivity period trims/deletes **every** task
  panel under it (a no-screen period still only the on-screen ones), and any task panel trims it in turn.
- **A laid or dragged period clears the RECORDS under its elapsed part** — the on-screen tasks' for a
  no-screen period, everybody's for a grey one. This is `StripNoScreenRecords`' §9 rule (`stripRecords`,
  now parameterized by `onScreenOnly`) applied at once instead of at the next engine start. Outside
  Undo/Redo, like every write to the record.
- The point of all four: **an inactivity period from ∞ to now** declares the whole recorded past empty and
  clears it in one gesture. Tests: `CalendarPeriodEditTest`.

### The clipboard: whole-sub-tree Ctrl+C, and three switches on the deep-copy window (PRD §4/§13, ADR 0012) — SHIPPED 2026-08-24

Four deltas, one gesture family.

- **The weight TABLE of every copied sub-list is pinned as carried.** The parent node writes the sub-list's
  `- sub-list weight columns:` header and each child writes its own `- priority weights:` row, so a pasted
  sub-tree rebuilds the tables rather than only the titles. New round-trip test through a two-column sub-list
  (`the_weight_table_of_every_copied_sub_list_travels_and_pastes_back`) — restoring the rows without the header
  would silently re-normalize every percentage at the destination.
- **`Ctrl+C` / `Ctrl+X` copy the ENTIRE sub-tree again** (`SchedulerDomain.FULL_SUBTREE_DEPTH`). For one day the
  chord copied to the account's `deepCopyMaxDepth`, which made that number mean two things: a depth set for one
  deep copy afterwards truncated every later `Ctrl+C`, with nothing on screen saying so. The three gestures now
  divide purely by how much — menu "copy" = the cell, "deep copy" = the window's number, the chord = all of it —
  and `deepCopyMaxDepth` is the window's own number and nothing else's.
- **The deep-copy window gained three switches** (`SchedulerDomain.CopyOptions`, `SchedulerIntent.SetCopyOptions`,
  `SchedulerState.copyIncludeIds` / `copyPriorityTables` / `copyIncludeText`): copy the task **ids**, copy the
  **priority weight tables** (off ⇒ the cell's **percentage of its sub-list** instead), copy the task **text**.
  Like the depth they are **one answer for the whole account** — persisted + synced, not Undo/Redo units, written
  back when the window copies — so the menu's "copy" and the chords obey them. `reset` restores all three.
- **The percentage form is stored as the node's single weight.** `copiedSubtree` writes `rowWeights = [share]`
  with the default one-column header and the renderer prints `- priority in its sub-list: 37.5 %`; the parser
  reads it back into that same weight. So the reducer's paste path is untouched, a sub-list of shares rebuilds
  those shares, and the copy-time rounding (two decimals of a percent) makes a second round trip a no-op.

One consequence worth stating: **ids off makes the payload foreign by construction**. The default-subtree paste
gate is "did the app write this text?", answered by the id — so a copy taken with ids off pastes as new tasks and
*is* seeded with the §7 template, exactly as typing those titles would be. That is the switch's meaning, not a
leak in the gate.

Client rebuild only (`account{1,2,3}-*deploy*.bat`); the three new fields are ordinary scalars in the snapshot,
so nothing server-side changed and a payload written before them decodes with all three on.

### Find & replace in the task tree (PRD §4) — SHIPPED 2026-08-24

`Ctrl + F` opens a VS Code-shaped bar in the tree's top-right corner: query field, match counter, **Match
Case** / **Match Whole Word**, `↑` / `↓`, close — and, behind the chevron, the replacement field with
**Replace** / **Replace All**. New `TaskTreeSearch` (pure), `SchedulerIntent.RevealCell` /
`ReplaceTaskTitles`, `SetExpandedDelta` (+ its `PersistedDelta.SetExpanded` mirror), `TaskTreeFindBar`.

Three decisions worth keeping:

- **The walk covers the whole tree, and visits each *list* once.** A search over
  `selectableVisibleOccurrences` would have missed every collapsed row — most of the account. Visiting each
  list once is what keeps a mirrored sub-tree (one list, many parents) from being re-walked per occurrence.
  Each match carries the path that reached it, which is what the reveal expands.
- **Revealing a match is ONE history unit** (`SetExpandedDelta` over the whole expansion set), not one
  `ToggleExpandDelta` per level. Typing in the query field deliberately does not jump to the first hit
  either: every jump is a selection unit, and `Alt + ←` would otherwise have to walk back one per keystroke.
- **Replace is a rename, keyed by task.** It runs through `applySetCellTitle` — the primitive Rename mode
  uses — so occurrences, the title index and the tombstone rule behave identically, and a task mirrored under
  three parents is renamed once, not three times. A replacement that empties a title deletes by §4's ordinary
  rule.

No deploy needed beyond a client rebuild (`account{1,2,3}-*deploy*.bat`); nothing server-side changed.

### The default sub-tree, the clipboard, and a menu entry to ask for it (PRD §4/§7/§13, ADR 0012) — SHIPPED 2026-08-24

Two reports, same day.

**1. Pasting foreign text seeded nothing.** With a template defined and the §7 switch on: copy text from
another app, select an empty task cell *without* entering Edit Mode, Ctrl+V, expand — empty sub-tree.
`graftDefaultSubtree` was only ever called from `setCellTitleDelta` and `endEditSession`, and a paste onto a
selected cell opens no Edit session. Documented at the time as deliberate ("paste … deliberately never
graft"), which was the wrong call: §7 grafts under every task the user **creates**, and pasting a title onto
an empty cell creates one exactly as typing it does.

**2. A deep-copied sub-tree pasted elsewhere seeded too.** The first fix gated on `PasteIdentity.Fresh`, which
still caught a copied id the target list cannot honour (`canAssignTaskId` refuses a duplicate sibling) — so a
pasted clone came back carrying the template. The gate is now the clipboard's **id**, not the identity:

- **no id** (another app's tab-indented list, or a pre-1.6.0 clipboard) ⇒ seeded. `graftDefaultSubtree`'s
  existing empty-sub-list guard means only a bare new leaf gets it — in a forest, every minted leaf, the same
  as typing those titles by hand.
- **any id** (Mirror, Restore, or a Fresh clone) ⇒ never. A copy of a sub-tree comes back as itself, and
  `Ctrl+X` → `Ctrl+V` still returns a leaf exactly as it was cut.

The pasted cell is **not** auto-expanded (unlike the end-of-session graft) — one paste can mint many leaves.

**New: "add default sub-tree" in the cell's right-click menu** (`SchedulerIntent.AddDefaultSubtree`), the
explicit gesture the narrowed gate leaves room for. Unlike the automatic graft it ignores the on/off switch and
does not care whether the task is new. It acts on `contextMenuCopyTargets` (the whole block inside a
multi-selection, as "copy" does), commits one Main history unit, and expands every cell it walked. Shown only
where a template exists.

**3. It applied beside the existing children, not to them.** Third report, same day. The first cut appended
the template after whatever the cell already parented. Corrected: it lands on the **leaves** of the sub-tree
the cell roots — a cell that parents nothing being its own leaf, so the plain and deep cases are one rule. A
template says how a piece of work breaks down, so asking for it on a cell already broken down asks for it on
the pieces. `defaultSubtreeApplicationTargets` resolves them off the state **before** anything is written (a
filled leaf gains children, and re-walking the mutated state would seed the rows just written where the task is
mirrored) and visits each **task id** once (one sub-list serves every occurrence; the id set is also the cycle
guard).

Covered by eleven `DefaultSubtreeTest` cases. No Supabase deploy; **client rebuild required**
(`account{1,2,3}-*deploy*.bat`).

### The no-screen record rule reads the OS, not just hand-drawn panels (PRD §9/§12, ADR 0002) — SHIPPED 2026-08-24

**Diagnosis (account 3).** Past task panels for on-screen tasks sat under BOTH calendar layers — which is, by the
layers' own identity, a no-screen period. Both hatches were individually right: the account has one desktop and
no phone, so the phone layer is `null` ⇒ assumed locked over the whole past; and the Kernel-Power 42/506 record
put the machine in Modern Standby for **98 h of the 168 h window**. What was wrong was underneath them —
**43.4 h of recorded "work" across 206 records**, banked while the OS said the screen was off. Modern Standby is
S0: the process keeps running and the advance tick keeps banking, so nothing but the OS log knows.

**Root cause.** §9's "assume nothing happened" guard (`appendRecordOutsideNoScreen`) took its no-screen ranges
from `state.panels.filter { it.noScreen }`, and the ONLY producer of such a panel is the §8 contextual-menu
action `AddNoScreenPeriod`. Account 3 had zero, so the guard short-circuited on `noScreenRanges.isEmpty()` and
every elapsed auto panel banked unconditionally. The bank read neither the OS lock history (which the layers
read) nor the derived pauses (which the engine reads) — only what the user had drawn by hand.

**The fix, in three parts:**

1. `SchedulerDomain.observedNoScreenRegions` — the two layers' EVIDENCE halves intersected, i.e. the same
   "a stretch carrying both layers is a no-screen period" identity the calendar draws, read for the scheduler.
   Asserted regions (sleep windows, screen breaks) are deliberately NOT folded in: a break *suspends* a chunk
   rather than cutting it (§15), so including them would silently stop recording across every break.
2. `SchedulerReducer.noScreenEvidence`, a seam beside `liveRestGap` — the engine scans the OS lock history on a
   coarse 10-minute bucket over a bounded 24 h window and injects the result; every banking path unions it with
   the hand-drawn panels (`noScreenRangesFor`). The panels are an assertion and still hold; the evidence is what
   fires when nobody drew one.
3. `StripNoScreenRecords` — a one-shot pass at engine start that applies the same rule retroactively over the
   displayed 168 h, carving the covered spans out of every ON-SCREEN task's record and materializing them as
   "Inactivity" panels. Off-screen tasks are untouched (§9 allows them to run in a no-screen period, so their
   records are true). Idempotent: once carved there is nothing left to subtract, and it returns the same state
   instance. On account 3 it removes 43.4 h spread across 20 tasks, so relative priorities barely move.

Unlike the tick that banks records, the strip **syncs**: `Task.record` is authoritative and the three-way merge
UNIONS it, so a deletion that stayed local would be resurrected by the next peer that still had the span.

**Two traps found while building it, both worth keeping in mind:**

- **A failed query is not evidence.** `null` from `deviceLockedIntervals` means "assumed locked throughout" —
  the right default for the calendar, the exact opposite of what the bank needs, where one PowerShell timeout
  would blanket the window as no-screen and suppress every record. The OWN scan must SUCCEED to say anything;
  the PEER's null keeps its assumed-locked meaning. Silence about a device we cannot reach is a rule; silence
  from the one we can reach is a failure.
- **The read must not run on the engine's dispatcher.** It spawns a PowerShell process and waits up to 20 s;
  calling it inline stalled the advance tick and every sweep behind it (it broke `ScheduleStalenessRuleTest`).
  It is now `withContext(Dispatchers.Default)`, as `App.kt` already did for its own layer scan.

Still open, and now sharper: the engine's pause derivation still reads `device_active_session` while the layers
and this guard read the OS. Three sources answered "was the user away?"; this makes it two.

Tests: `ObservedNoScreenRegionsTest` (the intersection, the assumed-locked null, empty ≠ null, the 90 s seam
filter, clipping at the now-line), `NoScreenEvidenceTest` (banking against evidence with no panel drawn, the
union with a drawn panel, off-screen tasks exempt, the empty-seam default, and the strip's carve/idempotence).

### The weight window charts the sub-list, and has a Cancel (PRD §5, ADR 0004) — SHIPPED 2026-08-24

**The pie chart on the right now shows each task's percentage *within the sub-list*** — the share the table on
the left actually hands out (`RelativePriorityDomain.cellShare`) — instead of the task's absolute priority (its
share of the whole tree). The slices never moved: their sweeps were already normalized by the sub-list total, so
only the legend's numbers were reading against a different denominator than the table they sat next to. The
heading says "Priorities in this list".

**A Cancel button puts the whole table back to what it was when the window opened** — every column header and
every cell's weight row, in one step, not one edit back. The window captures that table on the composition that
opens it (`remember(listId)`) and keeps it across every edit it makes, so Cancel always returns to the start; the
button is disabled while the table still matches. It dispatches one `RestorePriorityWeights` intent, reduced as
an ordinary `priorityTreeDelta` labelled "Cancel weight edits", **which is what makes Ctrl+Z undo the cancel**.
A cancel that would change nothing is a no-op and records no empty history unit. Only that one sub-list's weights
are rewritten: a cell that has since moved to another list is left to its new table, and list membership is never
touched (Cancel undoes weight edits, not tree edits).

Client-only: needs an app rebuild (`account{1,2,3}-*deploy*.bat`). No Supabase deploy. Verified by
`:shared:jvmTest` (`SchedulerReducerTest.cancel_restores_the_weight_table_the_window_opened_on`,
`a_cancel_that_changes_nothing_records_no_history_unit`,
`a_cells_share_of_its_own_sub_list_is_independent_of_its_parents`).

### The clipboard carries the task id, Ctrl+C is deep, Ctrl+X cuts (PRD §4/§13, ADR 0012) — SHIPPED 2026-08-23

Four changes to the copy/paste seam, the same day the format above shipped.

**Every copied node now writes its task id** (`- id: task/user/41`, the first attribute line), so a paste lands on
the **same task** instead of a clone of it. `SchedulerReducer.pasteNodeInto` resolves it three ways: the id names a
live titled task this cell may hold ⇒ the cell is pointed at **that** task (a mirror — its own sub-tree shows under
it, and the clipboard's children and fields are not applied over it); the id is free (it was cut, or the payload
predates this tree) ⇒ the task is rebuilt **under that id**, with `SchedulerState.reserveTaskId` walking the id
counter past it; no id, or one the tree cannot honour (it would duplicate a task inside one sub-list) ⇒ a fresh
task, as before. An id of any other shape (`task/root`, anything not `task/user/<n>`) is rejected at parse time, so
paste stays a no-op for foreign text.

**Ctrl+V replaces the cell it lands on.** It used to rename the target cell's task to the copied title and write
the copied children over the existing ones. The cell is now re-pointed at the pasted task; the task that was there
keeps its title and, with a populated sub-list, stays a detached parent its id brings back.

**Ctrl+C is a deep copy and never opens a window**, and **Ctrl+X** is that copy plus the PRD §4 deletion of the
same cells (one history unit, labelled "Cut", so one Ctrl+Z puts the sub-tree back — and the ids it freed are
exactly what a later Ctrl+V restores).

**The deep-copy depth is one number for the whole account** (`SchedulerState.deepCopyMaxDepth`, default 20,
persisted **and synced**, healed into 1..999 on decode; a payload written before it decodes to 20). The deep-copy
window opens on it and saves it when it copies — cancelling leaves it alone — and Ctrl+C / Ctrl+X then use it
without asking. The menu's plain "copy" is still depth 1.

Client-only: needs an app rebuild (`account{1,2,3}-*deploy*.bat`). No Supabase deploy. Verified by
`:shared:jvmTest` (`TaskCellCopyTest`, `SchedulerReducerTest`, `DefaultSubtreeTest`); the depth window is UI,
checked by running the desktop app.

### The clipboard text is readable, and "deep copy" asks how deep (PRD §4/§13, ADR 0012) — SHIPPED 2026-08-23

**The copy format was rewritten for a human reader.** A copy already carried everything the cell's edit window
holds, but in a shape written for the parser alone: per-line flags (`w=1.0,0.3`, `h=…`, `ns=1`), three appendices
**keyed by task title**, a bare **form-feed** line between them, and each task's text escaped onto one line as
`line one\nline two`. Pasted anywhere but back into OmniApp it was unreadable. It is now prose — a tab-indented
title line per task, one `- <field>: <value>` line per thing it holds a level deeper, the schedule unit as one
`- <step>: <n> min` line per step, and the task text **verbatim** in its own indented block:

```
Deep work
	- minimum time: 45 min
	- can be done during a no-screen period: yes
	- schedule unit:
		- warm up: 5 min
		- run: 25 min
	- text:
		the note, exactly as it was typed
	Reading
		- minimum time: 30 min
```

Everything at its default is omitted, so an untouched task is a title and its minimum time. The fields moved off
the title-keyed appendices **onto the node** (two tasks sharing a name no longer share a minimum time or a text),
a title that reads like an attribute line is escaped (`\- text:`), and paste stays as strict as before — an
unknown attribute, an unparseable value, a real tab in a title or an indent jump is still a no-op, while a plain
tab-indented title tree still pastes. The pre-1.6.0 form-feed shape is still **read** (a clipboard outlives a
rebuild), never written.

**The contextual copies act on the selection.** Right-clicking one of a dozen selected root cells and choosing
"deep copy" copied a single line: PRD §13 describes the menu on "the cell" and the first implementation took
that literally, while §4's Ctrl+C copies the selection — the same gesture to anyone using them.
`SchedulerDomain.contextMenuCopyTargets` now resolves a right-click **inside** a multi-selection to the whole
ordered block (the very one `copyTreeText` uses, so the menu and the chord cannot drift) and a right-click
outside it to that cell alone; the depth window carries the block and names how many cells are going.

**"deep copy" now asks for a maximum depth first.** A new window carries the depth (default **20**, restored by
its **reset** button, the copied cell counting as the first level) and prints **one path** from that cell down to
the deepest level the depth reaches, so the number reads as a place in the tree. The branch is picked by height
measured over the *whole* depth asked for, so raising the number extends the path instead of switching branches;
the line is held at its deep end with a draggable horizontal scrollbar for the parents that fall off the left.
**Enter** or **copy** copies and closes. The menu's plain "copy" is now simply depth 1.

Client-only: needs an app rebuild (`account{1,2,3}-*deploy*.bat`). No Supabase deploy. Verified by
`:shared:jvmTest` (`TaskCellCopyTest`, `SchedulerReducerTest`); the window itself is UI, checked by running the
desktop app.

### System-wide chords are swallowed, `Ctrl+Shift+Alt+E`, and a shortcuts window (PRD §7/§15, ADR 0011) — SHIPPED 2026-08-23

Three changes to the same seam (`scheduler/platform/GlobalHotkey.kt`):

**The chord is now claimed first come, first served.** Pressing `Ctrl+Shift+Alt+A` in Google Docs flipped the
away flag *and* opened Docs' comments pane — one press, two actions. The claim was a plain `RegisterHotKey`,
which consumes the keystroke only for the message queue: an application with its own `WH_KEYBOARD_LL` hook is
called **before** the hot-key table and can act on a press the hot-key then eats. The app now installs a
low-level keyboard hook of its own and returns non-zero for its chords, so the key is consumed at the head of
the chain and nothing else in the session is handed it. `RegisterHotKey` is kept underneath as the fallback (a
swallowed key never reaches the hot-key table, so the two cannot both fire); which claim is in force is
published as `GlobalHotkeys.claim` and logged at startup. The hook has to do two things `RegisterHotKey` did
for free — suppress auto-repeat, and pass **AltGr** through so `Shift+AltGr+E` still types its character on an
AZERTY layout — and it must never block, being on the path of every keystroke in the session.

**`Ctrl+Shift+Alt+E` takes the 20-second look-away**, from wherever the user is working. The seam went from one
`installGlobalAwayHotkey(onPressed)` callback to `installGlobalHotkeys { shortcut -> … }` over a `GlobalShortcut`
enum, which is now the only list of chords in the codebase.

**A "Keyboard shortcuts" window** (the lateral menu's last button) lists every chord the app answers to,
grouped by surface (`KeyboardShortcutCatalog`, `ui/ShortcutsWindow.kt`). The system-wide block is derived from
`GlobalShortcut` — so the window can never advertise a chord the app does not claim — and carries the claim
line, because "nothing happened" and "something else happened too" are otherwise undiagnosable.

Client-only: needs an app rebuild (`account{1,2,3}-*deploy*.bat`). No Supabase deploy. The hook itself is
Windows-native, so it is verified on the machine, not by a test.

### The hover bubble is a stack of sections (PRD §8, ADR 0002) — SHIPPED 2026-08-23

Hovering a **layer** now names it. The bubble was one title + one optional "under" line, which meant the
elements the calendar draws across each other overwrote one another's reports — and a layer, being a
non-interactive overlay, reported nothing at all.

It is now a list of sections, one per thing true at the instant under the cursor, ordered by the user's
rule, top to bottom: `task = break > inactivity = sleep > no computer unlocked = no phone unlocked`. Equal
ranks are ties kept in collection order. One exclusion, also the user's: **when there is a break, there
can't be a task** — a break suspends the chunk it lands in, so the panel spans it, but the user is not on
that task.

The layer itself still registers no pointer input. Its section rides whatever the cursor is over, plus a
new bottom-most hover pickup under every panel and band for the stretches nothing else claims. The grey
sleep/inactivity bands became pure drawing (their hover children are gone), and `decorativeHoverZones` was
replaced by the general `bubbleHoverZones` tiler.

Client-only: needs an app rebuild (`account{1,2,3}-*deploy*.bat`). No Supabase deploy.

### A device that cannot be asked was LOCKED (PRD §8, ADR 0002) — SHIPPED 2026-08-23

The layer default is **reversed**. `deviceLockedIntervals` returning `null` — "no device of this kind can tell"
— now hatches the whole asked past `[displayFloor, now]` for that layer, where it used to draw nothing.

The original spec sentence recorded in ADR 0002 said the unavailable device is *"considered to have been always
**unlocked** in the past"*; the user corrected the word after noticing that a desktop with no app installed on
the phone left the calendar largely unhatched. So the default now matches `derivePauses` rather than opposing
it: a device nobody can vouch for was not in use. On a one-device account the `\` phone layer therefore covers
the whole displayed past, and the "both layers ⇒ no-screen period" identity collapses to the computer's own
locked stretches.

`null` and an **empty list** stay different answers — an empty list is the OS saying "never locked" and still
draws nothing. What is new is a **third** state: "not asked yet". The first scan spawns a PowerShell process,
so treating pending as "cannot be asked" would flash a full-window hatch at every launch; `App.kt` gates the
own layer on `lockHistoryScanned` and draws nothing until the first answer lands (a later re-scan keeps
showing the previous answer while it runs).

Display-only: nothing in the scheduler reads a layer. Tests: `CalendarLayerTest`. Client rebuild
(`account{1,2,3}-*deploy*.bat`) — no Supabase surface.

### Only a conducted break is drawn in the past (PRD §15, ADR 0003) — SHIPPED 2026-08-22

The calendar's past side now shows **only the 20-second look-away**, and only when it ran whole.

**The 5- and 15-minute poses draw nothing in the past.** A pose used to vouch for exactly one occurrence, the
one ending at its anchor. But nothing about a pose ever happens in the app — it is only recognized after the
fact from an observed pause — and that pause is already on the calendar as what it really was: the two device
layers, the no-screen period, the derived "Inactivity" band. The pose band restated one fact as a second
object and gave it the break's nominal 5/15 min in place of the pause's real extent (an anchor seeded from a
night's sleep drew a tidy 5-min pose at the end of the night).

**A look-away that started but did not finish is erased.** `lastRestMillis` is an END, so nothing may move it
at a break's start — and the manual "Look away now" did exactly that, stamping the anchor at the press. That
drew a 20-s break over the 20 s *before* the manual one (the tail of the run the press had just interrupted,
offset by however late the press came), while the manual break itself — the one that actually happened — was
never drawn at all, since nothing moved the anchor when it ended. `SchedulerEngine.restartLookAway` now
dispatches on **completion**, to `resumeAt`, forward-only. A superseded run leaves no trace; a completed one
stays drawn where it happened and pushes the next occurrence an interval past its end. While the manual break
runs, the cue sweep swallows the automatic look-away start it stands in for (that due is still a crossable
boundary until the anchor moves).

Tests: `ManualLookAwayTest`, `ScreenBreakWindowTest`. No deploy needed beyond a client rebuild
(`account{1,2,3}-*deploy*.bat`) — client-only display + engine change, no Supabase surface.

### Default sub-tree under every newly created task (PRD §4/§7) — SHIPPED 2026-08-22

A new lateral-menu button, **"Default sub-tree"**, opens a floating window holding one per-account template
tree; the **switch to its left** says whether the policy is currently applied. While it is on, typing a title
into an empty cell no longer produces a bare leaf: the template is grafted under the task that naming just
created.

**A template node's `taskId` IS the row's switch** — `null` means "New id" (mint a brand-new task every time
the template is applied), a value means "point at this one task". There is no second boolean, and that is the
point: "picking an existing task turns the switch off", "turning the switch on re-selects New id", and "the
switch cannot be turned off while New id is selected" all fall out of the single field instead of being three
rules something has to keep consistent. The row's menu is the ordinary §4 naming block
(`SchedulerDomain.defaultSubtreeTaskMenuEntries`), so the window looks and behaves like every other field in
the app that names an object.

**A bound row contributes the bound task's own sub-tree, not the template's children.** A sub-list belongs to
the task id, not to the cell (that is what mirroring is), so the template cannot give a mirrored task different
children. Its template children are *kept* rather than deleted — turning the switch back on brings them back,
the same retention rule detached parents got the day before.

**The graft fires once, at `endEditSession`**, and only when the session actually **created** a task
(`taskId !in session.treeBefore.tasks`). Two rejected placements: inside `applySetCellTitle`, which the paste
path and the edit-session's own per-keystroke re-naming both call (a template would have been re-grafted on
every letter, and pasted trees would have been seeded); and gated on "the cell was empty", which cannot tell
creating a task from *reusing* one — reuse mirrors a task whose sub-tree already comes with the id, so there is
nothing to seed. It builds the rows by driving `applySetCellTitle` / `applyAssignTaskId` rather than writing
cells itself, so occurrences, `childTaskIds`, the title index and PRD §4 auto-expansion stay owned by the code
that already owns them. Riding the session's single "Edit" unit means one `Ctrl + Z` takes the seeded sub-tree
back with the title that pulled it in.

A binding the live tree cannot honour — the task was deleted, belongs to another task tree, or would duplicate
a task inside the sub-tree (`canAssignTaskId`) — falls back to a new task with the row's title, so a row never
silently disappears. A template is account-wide while a task id lives in one task tree, so this is the ordinary
case, not an edge one.

State: `SchedulerState.defaultSubtree` + `defaultSubtreeEnabled`, authoritative (persisted **and** synced, JSON
payload only — no SQLite schema change), resolved as one whole value by `SnapshotMerge` (interleaving two
devices' node insertions would produce a template neither of them drew). Both decode to "no template, switch
off" for payloads written before the feature, and `decode` runs `normalizeDefaultSubtree` so a blank-titled or
oddly-bound node from an older/hand-edited payload is healed rather than reaching the graft. Deliberately
**not** in `schedulingSignature`: a template schedules nothing until it is applied to a real cell.
Tests: `DefaultSubtreeTest`. Deploy: client rebuild only.

**The window is the task tree, plus one little switch per non-empty cell** (revised 2026-08-22, same day). The
first cut drew the template as its own thing — a column of always-on `OutlinedTextField`s, a bin button per row,
a caption naming the bound task — which read as a different feature from the tree it is a template *of*. It now
renders through the task sheet's own chrome, extracted to `ui/TaskSheetChrome.kt` (`SheetColors`,
`INDENT_STEP_DP`, `taskSheetGuideLines`, `TaskSheetExpandArrow`) and imported by both `TaskSchedulerScreen` and
`DefaultSubtreeWindow`, so there is one copy of the look rather than two that drift. The gestures came with it:
click to select, double-click **or simply typing** to open Edit Mode in place, `Enter`/`Shift+Enter`/`Tab`
navigation, `Ctrl+Enter` for a line break, `Backspace`/`Delete` to empty a row. The bin button is gone — the
blank title is what deletes, here as in the tree — and so is the caption: a bound row now **draws** the task's
own sub-tree beneath it (`SchedulerDomain.taskSubtreeOutline`, depth-capped), greyed and uneditable, the way the
tree draws a cell nothing may be done to. Only two columns are dropped, because a template has nothing to put in
them: the priority percentage (§5 — no tree, so no absolute priority) and the minimum time (§10 — no real task
yet); the switch takes the percentage's column at its width so both trees line up. The switch itself is drawn
compact rather than as a Material `Switch`, which measures taller than a 28 dp task-sheet row and would have
made the template's rows a different height from the tree's. Deploy: client rebuild only.

**Asking for a sub-tree ends the edit session** (fixed 2026-08-22, same day). Seeding at `endEditSession` has
a visible corner: the expand arrow of the cell you are *still typing in* opened the freshly named task onto
nothing but its empty placeholder, and the template only turned up after a click elsewhere had ended the
session for it. `SchedulerIntent.ToggleExpand` now forces the exit first (PRD §4 *Forced Exit*, as clicking
another cell already did) and applies the toggle only where the graft's own auto-expand did not already leave
the cell in the state the click asked for. Seeding per keystroke was rejected again for the same reason as
before, plus a new one: mid-session the "New task" draft can still be swapped for an existing id, and a draft
that had already been seeded would survive that swap as a **detached parent** — a titled task with a populated
sub-list no cell points at — leaving one junk sub-tree behind per abandoned draft. Tests: `DefaultSubtreeTest`
(`expanding_the_cell_being_edited_seeds_it_instead_of_opening_onto_nothing`, plus the arrow of *another* cell
and the collapse case). Deploy: client rebuild only.

Known scope limit: the template is one per account and shared by every task tree (§6); there is no per-tree
template, and no way to re-apply it to tasks that already exist.

### Calendar display indexes (PRD §8, ADR 0009) — SHIPPED 2026-08-22

The two remaining per-frame derivations named by the culling entry below were the ones it did *not* land:
`CalendarDisplayEquivalenceTest` was committed against a `recordsByDay` / `DeviceActivityIndex` that did not
exist, so `:shared:jvmTest` had not compiled since. Both now exist and are used.

`recordsByDay(records, firstDay, dayCount, tz)` places the whole visible span in one pass: each record's date
range is read once and it is dropped into the buckets of the days it touches, clipped to the span, so nothing
off-screen is built. It used to be one `recordsForDay` scan of every record in the account **per column**
(`DAY_COLUMNS × rowCount` of them). `DeviceActivityIndex(sessions)` builds the label table, the "known since"
floor and the start-ordered sessions once, and answers each panel by walking only the sessions that can
overlap it (binary search + a prefix maximum of the end instants); the per-panel form rebuilt the whole table
for every record on every observed now-line.

Cost only — both are pinned against the previous definitions (`recordsForDay`, `deviceActivitySegments`, kept
as the readable references) over randomized histories by `CalendarDisplayEquivalenceTest`. No scheduler,
state, persistence or wire change. Deploy: client rebuild only.

### Detached parent tasks survive a task-id change (PRD §4) — SHIPPED 2026-08-21

Re-pointing a cell at another task id used to **delete** the task it left the moment it lost its last cell
(`purgeOrphanTasks`), and the next edit boundary then collected its whole sub-tree (`pruneDetachedTree`), so
"change the id, then set the previous id back" came back with an empty sub-list — the sub-tree was gone, with
Undo as the only way back. The sub-list belongs to the **task id**, not to the cell (that is what makes mirrored
sub-trees work), so a titled task that keeps a populated sub-list is now retained cell-less as a **detached
parent** (`SchedulerDomain.isDetachedParentTask`): `purgeOrphanTasks` keeps it and `pruneDetachedTree` seeds its
reachability walk with its sub-list. Assigning that id back to any cell restores the sub-tree — the same thing
that already happened when the task kept a second occurrence elsewhere.

**Deletion is unchanged and is what bounds the retention:** emptying a cell (PRD §4 *Deletion*) blanks its task's
title, and a blank-titled task is never a detached parent, so the sub-tree still goes. That is also what keeps a
peer's deletion sticking through `SnapshotMerge.repair` (the merged task is either absent or blank-titled). A
*childless* task reassigned away is still purged.

Also PRD §4 *Presentation*, which the label had never implemented: a task **no cell points at** is now named in
the Change Task menu by its child titles instead of a path off the denormalized `Task.childTaskIds` — that path
survived the detachment and read as a live location the task no longer had. `childTitlesLabel` reads the shared
child list structurally (the source of truth `isLeafTask` uses), so a sub-tree that arrived by paste or by a move
is named too. No state, persistence or wire change (detached lists/cells were already persisted as whole maps and
`decode` does not prune). Tests: `SchedulerReducerTest`.

Known scope limit: a detached parent is reachable only through the Change Task menu — there is no view listing
them and no way to delete one without re-attaching it to a cell first.

### Calendar viewport culling (PRD §8, ADR 0009) — SHIPPED 2026-08-21

`DayColumn` emits UI nodes only for the hours inside the scroll viewport (`visibleHourWindow` → `HourWindow`,
quantized to one viewport-height of travel so the scroll does not recompose per pixel); the hour gutter is culled
the same way. Fixes "the app is sluggish while the calendar is open" — all the floating windows share one Compose
scene, so the calendar's ~1,700 composed records were being redrawn on every frame any other window animated.
2.2× / 3.7× / 11.6× fewer records composed at zoom 1 / 2.5 / 8 on a real account. Display-only: no scheduler,
state, persistence or wire change. Tests: `RollingCalendarTest`.

### No-screen / inactivity calendar entities (PRD §8) — SHIPPED 2026-07-19

`TaskPanel.noScreen` / `TaskPanel.inactivity` user-authored panels (authoritative, persisted + synced, old
payloads decode with the flags defaulted); the "add a no-screen period" / "add an inactivity period" contextual
menu options (1-hour default span at the click, then drag/resize); a no-screen period gets **Edit** too — a
times-only edit window, `ManualEntryEditWindow(timesOnly)` — while an inactivity period stays Remove-only (no task
behind it); the automatic **override/trim** both ways (`SchedulerReducer.resolveScreenOverrides`, wired into
add/update/move/resize/replace). Off-screen tasks and inactivity periods conflict with nothing and may overlap.
Tests: `NoScreenInactivityPanelTest`.

**Rendering revised 2026-08-20 to the two-LAYER model** (ADR 0002): the derived "No screen" band is gone and the
derived "Inactivity" band became the PAST-GAP grey band. A hand-added no-screen panel is now "a period asserting
both layers" (a faint outlined region, no pattern of its own); a hand-added inactivity panel is a solid GREY block,
and grey now means the scheduler places nothing there.

### Screen-switch enforcement (PRD §9) — SHIPPED 2026-07-19

`SchedulerDomain.fillSchedule` classifies the timeline by the no-screen periods (they are *not* occupancy
obstacles): on-screen tasks only outside them, off-screen tasks only inside them (none ⇒ never scheduled). A chunk
crossing a screen-zone edge is truncated like a pinned obstacle, unlike the screen-break resume.

- **Revised 2026-08-04.** The break's accepted set was `doableDuringBreak` alone, over the break's WHOLE length,
  which let an ON-screen task be scheduled inside a screen break and made the 20-s look-away an exclusion of every
  non-break-doable task (a spurious influence field every 20 min, forever). Replaced by the periods mapping in
  ADR 0001 §4.
- **Revised again 2026-08-09** to the three test-11 shapes: look-away accepts nobody; the 5-min pose is a closed
  first minute then `!onScreen && doableDuringBreak`; the 15-min pose is one open period accepting every
  `!onScreen` task.

Tests: `NoScreenInactivityPanelTest` (the closed head, the look-away, the on-screen refusal,
`the_15min_pose_accepts_every_off_screen_task_from_its_very_first_second`,
`the_5min_pose_still_refuses_an_off_screen_task_that_is_not_break_doable`),
`SchedulerPlanTest.a_15min_pose_at_the_now_line_is_one_open_period_accepting_the_off_screen_tasks`,
`SchedulerScreenFlagsTest`.

### Past no-screen ⇒ past inactivity (PRD §9/§12) — SHIPPED 2026-07-19

The schedule-advance (and `ReportDeviceSleep`) bank **no record** over a no-screen period for an on-screen task
(`appendRecordOutsideNoScreen` — the app assumes nothing happened), and the covered span is **materialized as a
real "Inactivity" panel** (`materializePastInactivity` in `SchedulerReducer` — outside Undo/Redo like the record
bank, never a syncable change on its own, skips spans an inactivity panel already covers, drops sub-minute
slivers).

Also: the calendar menu on a **sleep band** leads with Edit (opens the §17 sleep-schedule window, no Remove/move);
a **screen-break panel** deliberately has NO Edit (user-confirmed 2026-07-19; PRD §8 reworded to match).

### Phone activity = lock/unlock-gated heartbeat (PRD §15 / ARCHITECTURE §8) — SHIPPED 2026-07-19

Heartbeat replaced the WebSocket 2026-07-23; on-device verification pending.

`isScreenActive()` on Android is `AndroidUnlockTracker.unlocked` (SCREEN_OFF / SCREEN_ON / USER_PRESENT dynamic
receiver + Keyguard/PowerManager initial state; no keyguard ⇒ SCREEN_ON is the unlock). The device runs its `t_a`
presence tick while unlocked and, at lock, stops it and reports the screen-off straight to the Edge Function
(`notifyScreenOff`).

An unlock/lock flip and every app-foreground call `SchedulerEngine.onPlatformActivityChanged()` (an immediate beat
sample) so the tick resumes / the screen-off report goes out within moments, not at the next minute beat. Same on
the desktop via `DesktopSessionTracker` (JNA session lock/unlock); a restart after an abrupt kill resumes the tick
iff `isScreenActive()` says the device is unlocked.

One-time first-startup prompt (`MainActivity.maybePromptKeepAliveOnce`, flag in SharedPreferences): Doze exemption
(`REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`, added to the manifest) + best-effort OEM autostart settings
(MIUI/Huawei/ColorOS/Vivo). `AndroidForegroundTracker` remains only for the resume poke.

**iOS gap:** `isScreenActive()` there is still a hardcoded `false`. Wiring it to
`UIApplication.isProtectedDataAvailable` needs the Mac build that the iOS push code is already waiting on.

### Server-side break push (PRD §15) — SHIPPED 2026-07-19

Moved off the listener onto pg_cron 2026-07-23; moved off the `t_a` beat onto the event-driven `device_break` row
2026-07-26; live verification pending. See ADR 0006.

### Phone calendar gestures (PRD §8) — SHIPPED 2026-07-19

On-device verification pending. Pinch zoom already existed; added **double-tap-and-drag** zoom (exponential in drag
distance, anchored at the tap; a clean double-tap-release is left unconsumed), the **double-tap-and-release
contextual menu** (day column; opens on the block under the tap or empty space), the **panel info at the top** of
the touch menu (title + times — no hover bubble on a phone), and the **"move" menu option** (arms a move; the next
touch drag previews via the shared `dragPreview` overlay with the desktop snap rules and commits on release; a bare
tap cancels).

Touch presses on blocks are ignored entirely (`PointerType.Touch` early-return in `CalendarBlock`), so a
single-finger drag scrolls the grid and all block interaction goes through the menu.

### Terminology rename: "side task" → "screen break"

The eye-care breaks are named **"screen breaks"** everywhere (PRD §15) — UI, docs, code identifiers (`ScreenBreak`,
`screenBreak*`, `showScreenBreaks`, `DEFAULT_SCREEN_BREAKS`, `simulateScreenBreaks`, …) and the persisted JSON keys
(`screenBreak` panel flag, `showScreenBreaks`).

**Persisted-DB compatibility:** old on-disk/synced DBs still load — the codec maps the legacy keys onto the new
fields with `@JsonNames("sideTask")` / `@JsonNames("showSideTasks")` (encode writes the new keys; decode accepts
either). Covered by
`NoScreenInactivityPanelTest.codec_decodes_old_screen_break_key_names_as_screen_breaks`.

The screen-break config list itself is not persisted (hardcoded `DEFAULT_SCREEN_BREAKS`), so it needed no
migration.

The internal panel-id slug `side/{i}/{start}` was **deliberately left as-is** — it is a derived id, regenerated
every fill and stripped from the wire, so renaming it would gain nothing and only risk id-matching breakage.

---

## Scheduler model — the chain of corrections

Full rationale in ADR 0001. Each entry replaced the one above it.

| Date | Change |
| --- | --- |
| 2026-08-21 | **A claim is the lag counted in the task's own slots** (`(V−v)·p/m`), not the raw virtual clock. Test 14's 50 % task was getting 35 % (5 % of day one). Tests 1–10 byte-identical, so it only bites where the raw clock was wrong. Left open: a seeding defect at resumptions in a pool of interchangeable tasks (3 of 46 fail `check_resume_contract`). |
| 2026-08-20 | **The chunk scale is one ROUND** (`p·m_rival/(1−p)`), not one period (`p·T`). The old cap was a two-task coincidence and permitted a 15 h monolith of A. The lift (boost) and the cap (round) must be asked separately, or the atomic block loses its boost. `steady_cycle` had the same bug by another route. |
| 2026-08-20 | **Kotlin port caught up** — `SchedulerPlan.kt` was ~5 reference changes behind `scheduler_logic.py` (CLAUDE.md claimed 3 of them shipped, with tests that did not exist). Full backlog ported and pinned slot-for-slot against dumped reference output. |
| 2026-08-19 | **The forgetting is replayed over the past** (`_replay_clocks`), edge by edge. The seeding replayed `served/p` flat while the walk relaxes, so a re-plan never continued the walk. Now enforced by `check_resume_contract`. |
| 2026-08-18 | **The lookback window is measured in SCHEDULABLE time**, not wall time. Test 12's 50 % task was getting 2 %. |
| 2026-08-18 | **`last` reads `_last_run`, not `_head`.** A resumed plan refused the very task the timeline left off with, so the rightful pick lost a slot at every break (21 % instead of 39 %). |
| 2026-08-05 | Ported the reference's rewrite: a window bounds only the tasks it turns away; the atomic block (`_head`/`run_served`/`pending`). |
| 2026-08-04 | **The debt+decay model lasted 3 days.** `test.py` was rewritten and the app ported its WFQ virtual clock + capped exponential influence field (`SchedulerPlan.kt`); `SchedulerDebt.kt` DELETED. Trigger became a debounced `schedulingSignature` change, plus (later that day) an hourly staleness bound. |
| earlier | An EDF fill (`deadline = m/p`); helpers `edfPeriodMillis` / `nextTask` deleted. |

Also removed along the way: the per-tick `screenBreakDue → RefreshSchedule` in `dispatchScheduleAdvance` (it
churned the whole plan continuously while the user was away).

---

## Screen breaks

| Date | Change |
| --- | --- |
| 2026-08-05 | **Every screen break slides.** The 20-s look-away now pins to the now-line like a rest pose — an untaken break is OWED, not "assumed done". All cues key on the fixed due. |
| 2026-08-05 | **Three things serve a break** (a conducted look-away serves itself; a pose that happened serves every shorter break; a real pause ≥ 15 min). Fixes the reported *one look-away cue per session, then silence forever*. |
| 2026-08-05 | The look-away's `pauseThresholdMillis` went from 0 (i.e. 20 s, so any brief step away restarted the 20-minute clock) to 15 min. |
| 2026-08-05 | Every break recurs an interval after it **ENDS**, not after it starts. |
| 2026-08-05 | `DebugFlags.screenBreakOverrides` — all three breaks retimable independently on desktop; the legacy unprefixed properties became a named view onto the `5min_break` entry. |

---

## Sync

| Date | Change |
| --- | --- |
| 2026-07-30 | `scheduler_snapshot.writer_device_id` — the lost-acknowledgement repair. A push whose response was lost left the remote +1 revision, and the next reconcile pulled the device's OWN write over newer edits. Shipped with an `HttpTimeout` on the snapshot client and `Diagnostics` logging of every reconcile failure + LWW drop. |
| 2026-07-28 | **Startup reconcile.** A restored session reconciled nothing at launch, so the first edit's own auto-push fetch LWW-pulled over it. Also: `writer_device_id` is NULL on pre-fix revisions, so those are permanently unprotected. |
| 2026-07-28 | Realtime auto-pull **verified live** — but it never replays what it missed while disconnected, so every (re)subscribe now reconciles too. |
| 2026-07-22 | **Reversal of button-only:** local→remote auto-push (500 ms debounce) + remote→local auto-pull (`RealtimeSnapshotSubscriber` `postgres_changes`, migration `20260722000000`). `SchedulerSyncEngine` made `open` for the deterministic `BidirectionalSyncTest` double. |
| — | **Three-way merge** (`SnapshotMerge`, schema v10 / `9.sqm`) replaced whole-doc LWW; LWW survives only as the no-ancestor / undecodable fallback. |

Retired along the way: the five-sync-moments model, the startup remote-activity adoption
(`purgeLegacyAdoptedRows` heals old DBs), and the external Realtime-presence listener.

---

## Supabase migrations

| Migration | What it did |
| --- | --- |
| `20260713000000` | Dropped `pause_cue_schedule` / `derive_pauses` / `device_*`; added `account_state` for the listener era. |
| `20260716000000` | `kind` column on the remote active-session rows. |
| `20260721000000` | Device↔account exclusivity for **push tokens**. |
| `20260722000000` | `scheduler_snapshot` into the `supabase_realtime` publication with `replica identity full`. |
| `20260723000000` | `device_heartbeat` table + re-added `pause_cue_schedule`. The pg_cron pivot; the Fly.io `/listener` and the presence WebSocket were deleted. |
| `20260724000000` | The `t_a`/`t_b` model: `app_config` (t_a) + `break_config` (per-break length + vocal message) + `publish_presence()` (returns t_a) + `evaluate_pause_cue()` (decide + claim) + a `tick_pause_cues()` that only detects. |
| `20260725000000` | The **overdue gate** — a cue fires only when the account went idle with a break DUE. |
| `20260726000000` | Split the presence row in two: `device_heartbeat` keeps `{user_id, device_id, beat_at, data_payload_sent}` (`kind` / `next_break_*` dropped); new **`device_break`** table + `publish_next_break()` RPC. `overdue_break_at_last_beat()` factored out. **Changes `publish_presence`'s signature — Supabase AND every app must be redeployed.** |
| `20260727000000` | Extended device↔account exclusivity to `device_heartbeat` + `device_break`. |
| `20260728000000` | `device_break` became **account-keyed with just the two due instants** (`device_id` / `kind` / `break_kind` / `break_len_ms` dropped); the claim flag moved out into an account-keyed **`data_payload_sent`** table; break LENGTH moved server-side. **`publish_next_break`'s signature changed — redeploy Supabase AND every app.** |
| `20260729000000` | Split delivery in two: `pause-cue` (**e1**, clean lock, decides, anchors at `now()`) and `pause-cue-cron` (**e2**, cron decided, anchors at `t2`). `omni_edge_push` gained the function name (1-arg form dropped); shared `_shared/push.ts`. **Supabase-only redeploy.** |
| `20260730000000` | `scheduler_snapshot.writer_device_id` (nullable). **Apply before/with the app rebuild, or every fetch 400s.** |

`account_logout` (the remote force-logout marker) is applied by `deploy-supabase.bat`.

**Remaining follow-ups:** the Edge Functions' `FCM_` / `APNS_` secrets (`supabase secrets set`, project-wide so one
set covers both) and native phone push-token registration. Full runbook: `docs/PAUSE_CUE_DELIVERY.md`.

---

## Local SQLite schema

| Version | File | What it did |
| --- | --- | --- |
| v7 | — | `sleep_scan_checkpoint` table (local-only OS-sleep scan progress, in its own table so `sync_meta` writes can't clobber it). |
| v8 | `7.sqm` | `kind` column on `device_active_session`. |
| v9 | `8.sqm` | **Per-account partitioning** of `app_state` / `history_unit` / `history_pointer`; `account_sync` table (per-account revision baseline / `dirty` / logout baseline). Pre-v9 rows are filed under the account that was signed in when written, or into the `''` partition the first guest account adopts. |
| v10 | `9.sqm` | `account_sync.base_payload` — the merge's common ancestor. |

---

## Other dated decisions

| Date | Change |
| --- | --- |
| 2026-08-20 | **The calendar's two layers** replaced the "Inactivity" + "No screen" band pair; GREY became "the scheduler places nothing here". Sleep and hand-added inactivity periods now block the fill (previously sleep deliberately did not). Third iteration of the layer source — the first two readings shipped and were both wrong (ADR 0002). |
| 2026-08-20 | **No "focused week" any more** — the calendar scrolls endlessly and the schedule horizon follows the displayed day span in both directions (ADR 0009). |
| 2026-08-20 | The day-row sizing bug: `requiredHeight` silently centred every row, showing the wrong hours (≈8 h off) and hiding the now-line. |
| 2026-08-20 | **Relative priority** — the percentage's own right-click menu, the pin semantics, `RelativePriorityDomain` (ADR 0004). |
| 2026-08-06 | An alarm's **days** became part of the alarm (synced), not of the device; `repeatDaily` → `repeats`. |
| 2026-08-01 | **The desktop rings alarms too**, off the now-line rather than an armed OS alarm. PRD §18 used to say it never rings. |
| 2026-07-28 | **Horizon refill self-retrigger** — the release app was a tray icon with no window; `launchHorizonReschedule` re-fired with zero delay forever, pegging the EDT. Fixed with a refill margin + rate floor. |
| 2026-07-24 | The presence model reshaped to the user's `t_a`/`t_b` spec (ADR 0006). |
| 2026-07-23 | The Fly.io `/listener` and the presence WebSocket removed; the pause cue moved onto a pg_cron tick. The user reasoned the listener was a "false solution" (also heartbeat + poll), so no host warranted it. |
| 2026-07-19 | The whole 1.6.0 delta batch above went code-complete. |
| 2026-07-09 | The startup remote-activity adoption retired — it fabricated activity over genuine pauses. |
| — | `android:allowBackup="false"` — OS auto-restore silently resurrected a "wiped" install's DB. |
