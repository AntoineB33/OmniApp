Kotlin Multiplatform (KMP) project targeting Windows Desktop first.

This file is **active invariants only** — the things you must not break. The reasoning, the rejected
approaches and the post-mortems behind each rule are in `docs/adr/` (start at `docs/adr/README.md`); the
dated log of what changed when, including every Supabase and SQLite migration, is in `CHANGELOG.md`.
Before changing a subsystem, read its ADR.

## Commands

- `./gradlew :shared:check` — verify syntax/compile errors after editing the `shared` module.
- `./gradlew :shared:jvmTest` — the real logic gate (see *Verification* below).
- `./gradlew :desktopApp:run` — run the desktop app to verify UI/desktop changes.

## Verification

- After any change to shared Kotlin logic, run `:shared:check` before reporting it as done.
- Do not assume `expect`/`actual` declarations work until `:shared:check` passes.
- `:shared:check` is red on JS (missing platform actuals) and on
  `verifyCommonMainSchedulerDatabaseMigration` (sqlite-jdbc native lib — machine-level). So `.sqm` files are
  **not** machine-verified: write the migration `jvmTest`. Verify logic with `:shared:jvmTest` and iOS
  portability with `compileCommonMainKotlinMetadata`.
- Do not use Android-specific CLI tools to render previews.
- Diagnose calendar anomalies with `collect-diagnostics.bat`, not by asking the user to describe them.

## Deployment surfaces

**Always state what must be redeployed for a change to take effect, and which script.** Two independent
surfaces:

1. **Supabase** (`supabase/migrations/`, Edge Functions, `pause-cue-setup.sql`'s pg_cron) →
   `deploy-supabase.bat`.
2. **The client apps** (any `shared`/desktop/Android Kotlin) → an app rebuild via
   `account{1,2,3}-*deploy*.bat`. The running binary is stale until then.

When reporting a change as done, name the surface(s) it needs — or say "no deploy needed" (e.g. a test-only
change).

A `:shared:check` / `:shared:jvmTest` pass means the code compiles and its logic is correct. It does **not**
mean the change is live on device. Never conflate the two. The common failure: a client-only fix silently
"doesn't work" because the old binary is still running, or a migration change is ignored because only the app
was rebuilt.

---

## State: authoritative vs. derived

→ ADR 0007.

**Persist and sync only state that cannot be recomputed from other persisted data.** Before persisting or
syncing a field, ask whether it can be re-derived; if so, recompute it. Never let an engine tick that *only*
re-derives something mark the state dirty or trigger a sync push.

| Class | Contents | Rule |
| --- | --- | --- |
| **Authoritative** | task tree, named task trees, the default sub-tree + its switch, user-authored/pinned panels, chores/reminders, sleep schedule, alarms, settings, Undo/Redo history units, manual record edits | persist + sync |
| **Derived** | auto/screen-break/sleep panels, records the advance banks | persisted locally, **stripped from the wire**, never trigger a push on their own |
| **Local-only view state** | focused window, tree selection, `showScreenBreaks`/`showReminders`, WindowNav/Selection history, window placement, OS-sleep scan checkpoint | persist locally, **never sync** |

- Local view state is stripped from the fingerprint by `withLocalViewStateNeutralized()` and carried across a
  pull by `withLocalViewStateFrom`.
- Calendar zoom is Compose-only state — never persisted at all.
- The one deliberate exception: the whole-state snapshot is replayable from history units, but history is
  bounded (`MAX_HISTORY_UNITS`), so the snapshot stays authoritative.

### Persisted-DB compatibility

- Any change to `SchedulerState` / `SchedulerStateCodec` / the `Persisted*` types, or to reducer logic that
  writes state, **must come with a test** that loads a payload written by the *previous* shape and asserts it
  still loads correctly or is migrated on load.
- New field on a `Persisted*` type ⇒ give it a default, and extend a decode test with a payload lacking it.
- `decode` must **heal** states an older build wrote that current invariants forbid — not surface them as
  anomalies.
- Same for the SQLite schema (`Scheduler.sq` + a new `N.sqm`): reproduce the previous on-disk shape in a test
  and assert the upgrade keeps the data.

---

## Scheduler

→ ADR 0001. The model is `side-dev/README.md`; `side-dev/scheduler_logic.py` is the reference and
`SchedulerPlan.kt` its port. `SchedulerDomain.fillSchedule` is a driver over that port.

- **`PlanWalk` is the ONLY copy of the scheduling rules.** `SchedulerPlanner.plan()` and `fillSchedule` are
  both thin drivers over it. Keep them in step (the one sanctioned divergence is the atomic block, below).
- **The pick is a function of the walk state at the cursor.** It cannot be answered by a point query — do not
  reintroduce an EDF/deadline-style shortcut.
- **A claim is the lag counted in the task's own slots**: `claim = (V − v)·p/m`. Do not simplify back to
  `min v`.
- **A chunk's scale is one ROUND**, `c = p·m_rival/(1 − p)`, floored at the task's minimum. The lift (boost)
  and the cap (round) are asked **separately**.
- **`last` is never picked twice in a row** unless it is the only candidate.
- **PRD §7 "Switch task" IS that same `last`, not a ban.** The button (and `Ctrl+Shift+Alt+Z`) records a
  `ForcedTaskSwitch(task, at)` and the fill hands it to `walk.setLast`, so the refused task keeps its clock and
  its share and is an ordinary candidate again from the second slot — and a task nothing can replace still
  runs. Do not give it a rule of its own, and do not put it in `schedulingSignature`: the press re-plans inside
  its own reducer, or dropping the spent marker would fire a second, un-refused re-plan. It stays live until
  **another task has actually been served past `at`** (`liveForcedSwitchTask`, read off the recorded past, so
  the resume contract holds); the advance tick drops it then.
- **PRD §13 "start this task now" is the SAME lever from the other end.** The task cell's menu records a
  `ForcedTaskStart(task, at)` and the fill puts that task in the **first slot it places** — charged like any
  other pick, so only that slot is the user's answer. Same liveness predicate as the refusal
  (`liveForcedStartTask`: outstanding until another task has been served past `at`), same reason it is not in
  `schedulingSignature`, same drop by the advance tick. Offered on a schedulable **leaf** only; asking for a
  task clears an outstanding refusal *of that same task*. It is answered in phase 1 **and** in phase 2 — a
  timeline nothing disturbs freezes before phase 1 places anything, and the request must not vanish there.
- **The clock replay walks the past EDGE BY EDGE**, applying `relax` where the walk applies it. Its window is
  two `minPeriod`s measured in **schedulable** time, never wall time.
- **Only obstacles still AHEAD build the influence field.** The boost is capped (`maxBoost` = 6) and decays to
  a finite range. An exclusion that refuses everybody, or one shorter than the deprived task's own minimum,
  creates **no** field.
- **Excluded tasks are translated as a group, never clamped individually** — clamping destroys their ranking.
- **A window bounds only the tasks it turns away.** "Does the minimum fit?" counts instants the task may
  actually run; an interval nobody may run in suspends, one somebody else may run in ends.
- **A task about to start must be able to finish** before it would lengthen a rival's ban. A task already
  running has no such choice.
- **The resume contract:** a chain of re-plans is the SAME schedule as one long plan. Anything new the walk
  carries must be reconstructible from the history, or this breaks silently.
- **Do not answer a sliding period by re-planning per tick.** It is a display clip
  (`clipPlanForPinnedScreenBreak`), cutting what a break **refuses** — not what it covers.

### What reaches the scheduler

Only two things: **pre-placed blocks** (pinned/manual panels ahead of `now`, the kept head on an extension,
the served past) and **periods** (§9 screen zones, §15 screen breaks). Nothing else, by any other route.

| Region | Accepts |
| --- | --- |
| Grey (inactivity period, §17 sleep window) | nobody |
| No-screen period | only tasks needing no screen |
| Everywhere else | only tasks needing a screen |
| 20-s look-away | nobody, end to end |
| 5-min pose | closed first minute, then `!onScreen && doableDuringBreak` |
| 15-min pose | every `!onScreen` task, from its first second |

- `doableDuringBreak` **implies** `!onScreen` — enforced in the reducer, healed on decode.
- **Do not re-unify the two poses.** They are deliberately different shapes.
- Both readings of a shape go through `SchedulerDomain.screenBreakOpenStartMillis` — closed parts draw solid,
  open parts draw **hollow**.
- A gap too short for any minimum is left **empty**, never filled with a sub-minimum sliver (PRD §10).
- A screen break and every grey period **suspend** a chunk rather than cutting it, and do not count against
  "does the minimum fit?" (PRD §15/§17). A screen-zone edge is the other kind — it cuts.

### `fillSchedule` deliberately does NOT adopt the atomic block's `pending` rule

PRD §15/§9 want the break's own accepted set to fill a period an on-screen chunk is suspended across.
Applying the atomic block there would leave every break and every no-screen zone permanently empty.
`plan()` is the reference-conformance surface; `fillSchedule` is the app.

### When the plan is recomputed

- **`SchedulerDomain.schedulingSignature(state)`** is everything the plan is a function of except `now`.
  `launchRuleChangeReschedule` watches it (1 s debounce) and is the only rule-watching dispatcher of
  `RefreshSchedule`.
- **Anything new that wants to re-plan belongs in the signature** (or in `requestReschedule`), not in a fresh
  dispatch site.
- **Time passing must never re-plan continuously.** The advance tick only banks records; horizon growth and
  calendar navigation dispatch `ExtendSchedule` (keeps the head, appends the tail).
- Exactly two sanctioned quantized exceptions: the hourly **staleness bound**
  (`SCHEDULE_STALENESS_MILLIS` = 1 h, a bound re-armed by `requestReschedule`, not a tick) and the **task-tree
  blend cursor** (ADR 0008).
- The signature excludes records deliberately, so `RemoveRecordPeriod` refills inside its own reducer.

---

## Screen breaks

→ ADR 0003. The three are the 20-s look-away, the 5-min pose, the 15-min pose.
Terminology: **"screen breaks"** everywhere — UI, docs, code identifiers, persisted keys.

- **The boundary a trigger keys on must be a fixed instant derived from the rules** — never a position the
  placement/projection recomputes every frame.
- **Every break slides**: `screenBreakNextStart = maxOf(lastRest + interval, now)`. An untaken break is
  *owed*, not "assumed done".
- **Consequently no break has a drawn start anything may key on.** All cues key on the stable due
  `lastRest + interval`, a level test `now >= due` deduped on `due`
  (`reachedScreenBreakDueByTitle`). Never on the panel start, never on "the now-line is inside a drawn panel".
- Two shadows: the longest reached pose absorbs the shorter (5↔15 merge); a look-away is dropped when its due
  is at or after the earliest reached pose's due. A look-away due strictly *before* still fires, in order.
- **Three events serve a break** — a look-away the app conducted (poses excluded), a pose that happened
  (serves every shorter break), or a real pause ≥ 15 min. Nothing else; never assume a break was taken.
- **Every break recurs an interval after it ENDS.**
- **Only a break the app CONDUCTED is drawn in the past — so only the 20-s look-away**, read off the ANCHORS
  and never off the projection grid, chaining backward a cycle at a time. A **5-/15-min pose draws nothing in
  the past**: it is never conducted, only recognized from a pause the calendar already draws as itself.
- **A look-away that started but did not finish is erased.** The anchor is an END, so it moves only on
  completion — the manual "Look away now" supersedes the run in progress and is itself drawn only once its own
  20 s are up. Never move the anchor at a break's start.
- The **end** of a break is a notification, not only a voice cue.
- A screen-break panel has **no Edit** (no editable object behind it). A sleep band's menu leads with Edit.

### Notification / voice-cue triggers

Must be **mathematically accurate** — a pure function of which boundary instants the clock crossed (each
fires exactly once, in order), never of how a sweep/heartbeat happens to align with the calendar.

Staleness is judged only by the crossing's REAL age (`BoundarySweep`, 2-s budget), never by sim distance or
scan-window position. Consecutive scans must tile the timeline with no gaps (`scanFloorMillis`), so no
crossing can be silently clipped by a clock jump.

---

## Calendar

→ ADR 0002.

- **Two orthogonal things, and keeping them orthogonal is the point.** The **layers** say who was at a
  screen; **grey** says whether anything is scheduled.
- **A layer is read from the DEVICE'S OS HISTORY** (`deviceLockedIntervals`), never from the app's own
  sessions or from banked panels. Both of those were shipped and both were wrong.
- **A device that cannot be asked was LOCKED** (`null` ⇒ the layer hatches the whole asked past; an empty
  list ⇒ nothing drawn — the same default as `derivePauses`, and `null` and an empty list stay different
  answers). "Not asked yet" is a third state: the own layer draws nothing until its first scan lands.
- **A stretch carrying BOTH layers is a no-screen period**, identical to the account-wide derived pause.
  `CalendarLayerTest` pins that identity — keep it true.
- Layers are non-interactive overlays: they displace nothing and register no pointer input. A layer is
  *named* by the hover bubble anyway — its section rides whatever the cursor is over, or the bottom-most
  hover pickup where that is nothing.
- **The hover bubble is a STACK of sections**, one per thing true at the instant under the cursor, ordered
  `task = break > inactivity = sleep > no computer unlocked = no phone unlocked` (equal ranks are ties, kept
  in collection order). **When there is a break there can't be a task.** Both rules live in
  `orderedBubbleSections`, applied in the one funnel `Modifier.calendarTitleHover` — never at a call site.
- **Hover is TILED, never nested**: two reporters at one position race (the parent's Move wins). Cut the
  element at every covering section's boundary (`bubbleHoverZones`) and give each tile one reporter.
- **GREY = the scheduler places nothing here** — inactivity period, sleep window, the look-away end to end,
  the pose's closed head. It is not a screen classification: it refuses off-screen tasks too. A pose's open
  tail and the 15-min pose are **not** grey.
- **Grey refuses everybody on the calendar too, not only in the fill.** A hand-added inactivity period
  overrides **every** task panel it covers (a no-screen period only the on-screen ones — §9 lets an
  off-screen task run inside one), and any task panel overrides it in turn.
- **A period LAID or DRAGGED over the past clears the work banked under it** — the on-screen tasks' records
  for a no-screen period, everybody's for a grey one. Same rule as `StripNoScreenRecords` (`stripRecords`,
  `onScreenOnly`), applied at once rather than at the next engine start; outside Undo/Redo like every write
  to the record.
- **Both "add a … period" entries open the PERIOD EDITOR** (`PeriodEditWindow`, one window for both kinds) —
  they never lay a panel directly. Each bound is a date+time, **"now"** (resolved at Save), or **"∞"**
  (`SchedulerDomain.OPEN_PAST_MILLIS` / `OPEN_FUTURE_MILLIS` — real 1900/2200 instants, never
  `Long.MIN_VALUE`: every consumer does plain arithmetic on a panel's bounds). It is also a period's "Edit";
  a *derived* grey band has none. The case it exists for: **an inactivity period from ∞ to now** empties the
  recorded past.
- Derived grey bands are `[displayFloor, now]` minus everything already drawn, except no-screen periods and
  screen breaks. Display-only, sub-minute remnants dropped.
- Known and accepted: a task panel can sit under the computer hatch (the OS wins) — but it banks **no
  record**, see below.
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
  it (§15), so folding breaks/sleep windows in would stop recording across every break.
- **The scan never runs on the engine's dispatcher** (ADR 0009): it is a process launch with a 20 s timeout,
  and inline it stalls the advance tick and every sweep behind it. 10-minute bucket, bounded 24 h window.
- `StripNoScreenRecords` applies the same rule retroactively, once at engine start. Idempotent, and unlike the
  tick it **syncs** — `Task.record` is authoritative and the merge UNIONS it, so a local-only deletion would be
  resurrected by a peer.

### Display hot path

→ ADR 0009.

- **Anything recomputed on every `nowMillis` tick must be bounded by the visible window, never O(total
  history).** Under sim the now-line ticks ~20×/s; an O(history) recompute pegs the UI thread and the window
  is created but never shown — which looks exactly like "the app won't open".
- **What the calendar COMPOSES is bounded by the visible window too.** A day row is one whole day tall while
  the viewport is not, so every `DayColumn` culls its output to `visibleHourWindow(...)`: a record scrolled
  out of view emits no UI node. This is a frame cost, not a tick cost — every floating window shares one
  Compose scene, so whatever the calendar keeps in the tree is redrawn on every frame *anything* in the app
  animates (dragging the reminders window was the reported symptom).
- **The cull window is QUANTIZED (`visibleHourWindow`), and must stay so.** Culling makes composition a
  function of the scroll; read unquantized, it would recompose every column on every scrolled pixel and cost
  more than it saves. The day-rows are still *placed* by the layout-phase `offset { … }` read of `offsetPx`.
- **Cull the EMISSION, never the list.** `overlapLayout` widths, the reminder/alarm stacking sweeps,
  hit-testing, the contextual menu and the drag snap set all still see the whole day — a partner scrolled out
  of view must still narrow the block on screen. A block mid-gesture is exempt: its slices hold the gesture.
- **Test against a large, realistic DB**, not just an emptied one — an empty account hides the cost entirely.
- **The schedule horizon follows the displayed day span**, clamped into [24 h, 168 h]. 168 h is a ceiling,
  not a target. There is no "focused week".
- Beyond the ceiling, the far fill runs off the UI thread keyed **only on the span** and is **never stored in
  `state.panels`**.
- Horizon growth dispatches `ExtendSchedule`, not `RefreshSchedule`.
- Day rows are `wrapContentHeight(Alignment.Top, unbounded = true).height(dayHeight)` — both halves
  load-bearing. `requiredHeight` silently centres the row and shows the wrong hours.

---

## Pop-up windows

`ui/PopupWindows.kt`. There are **two sorts, and the sort is not a choice** — it follows from what the
pop-up is about.

- **Sort 1, a window.** Opens on the top layer, then behaves like every other window: whatever is focused
  next stacks on top of it, and it stays open until it is closed. `App`'s `windowStack`.
- **Sort 2, a transient pop-up.** Opens on the top layer and **leaves the moment anything else takes
  focus**.

**The test is whether it could have several instances open at once.** A pop-up about ONE object — a task,
a cell, a sub-list, a calendar block, a period, a reminder, a history unit, a tree entry — is sort 2,
because "the edit window of task A" and "the edit window of task B" are two different windows and the user
only ever means the one they just asked for. A pop-up there is exactly one of is sort 1. So sort 1 is
precisely `windowStack` (Calendar, Reminders, History, Sleep, Alarms, TaskTrees, TaskList, DefaultSubtree,
Shortcuts, TimeSim) and every other pop-up in the app is sort 2.

- **At most one sort-2 pop-up is open at a time** — `TransientPopupHost.open` dismisses the others, so the
  invariant holds by construction and not by every opener remembering to close its predecessor.
- **A sort-2 pop-up is NOT modal: no scrim, blocks nothing.** The press that dismisses it still does its
  normal job (focusing the calendar, selecting a cell). The full-screen scrims that shipped before ate that
  press, which cost a second click and made "it leaves when something else is focused" unobservable.
- **Dismissal discards** whatever was half-typed in it. That is the sort's price, not an oversight — the
  old scrim click did the same.
- **One observer, at the app root** (`transientPopupDismissRoot`), watching the **Initial** pass without
  consuming. Never a per-pop-up outside-press handler. Presses inside a `DropdownMenu`/`Popup` draw in their
  own layer and never reach it, which is what keeps a pop-up's own menus from closing it.
- **A sort-2 pop-up must be drawn where it can be on top.** The tree's `TaskEditWindow` / `DeepCopyWindow`
  are raised out of `TaskSchedulerScreen` into `App` for that reason (inside the tree they drew *under* any
  floating window stacked over it); `ReminderConstraintEditWindow` uses a `Popup` for the same reason.

---

## Priorities

→ ADR 0004 (relative priority), ADR 0008 (the timeline blend).

- Right-clicking a cell's **percentage** opens "relative priority" / "priority weights" — not the row's §13
  menu. It consumes the press; the row's handler skips consumed presses.
- Relative priority with `t_r == MAIN` is **exactly** `absoluteTaskPriorities`. Keep that identity.
- Editing scales **percentages** by one common factor, solved by bisection on the measured tree — not by a
  closed form.
- **A pin means "hold this percentage", not "leave this weight alone"** — a pinned cell's weight may have to
  rise. Solve each sub-list over every chain cell, pinned included.
- Pins are authoritative + synced, and **not** an Undo/Redo unit.
- **The weight window's chart is the readout of the table beside it**: each task's share of THAT sub-list
  (`cellShare`), never its absolute priority. The slice sweeps were always normalized by the list total; only
  the legend's number was reading against the whole tree.
- **Its Cancel restores the table the window OPENED on** — every header and every weight row, in one step,
  never one edit back — as one ordinary `priorityTreeDelta`, which is what makes Ctrl+Z undo the cancel. A
  cancel that changes nothing records no unit. It rewrites that one sub-list's weights and nothing else: a cell
  that has since moved lists keeps its new table, and membership is never touched.

### The task-tree timeline

- A dated tree is a **keyframe**; between two, the scheduler follows a linear blend, not the tree on screen.
- Identity is the `TaskId`; a task absent from a keyframe is **0 %** there. The leaves are the **union** of
  both keyframes.
- `datedTaskTrees` **flushes** the active tree first. Dated trees are in `schedulingSignature`; undated ones
  deliberately are not.
- **The one sanctioned exception to "time never re-plans"** — and only because the cursor is **quantized**
  (`TASK_TREE_BLEND_STEPS` = 100). Do not reintroduce an unquantized/per-tick form.

### A sub-list belongs to the task id, not to the cell

- Every cell pointing at a task shows the **same** sub-tree — that is what mirroring is. So re-pointing a cell at
  another id must **not** delete the task it left: a titled, cell-less task that still holds a populated sub-list
  is a **detached parent** (`SchedulerDomain.isDetachedParentTask`), kept by `purgeOrphanTasks` and seeded into
  `pruneDetachedTree`'s walk. Assigning that id back restores its sub-tree.
- **The blank title is what deletes.** Emptying a cell (PRD §4 *Deletion*) blanks its task's title, and a
  blank-titled task is never a detached parent — that single rule is what still collects an emptied parent's
  sub-tree, and what keeps a peer's deletion sticking through `SnapshotMerge.repair`. Do not make the retention
  key on anything else.
- **Expansion is keyed by the CELL but the sub-list belongs to the TASK**, so a cell's `expanded` entry goes
  stale the moment it is given a different task's (or a brand-new, empty) sub-list. `applySetCellTitle` drops
  the cell where it **mints** that sub-list — a freshly minted sub-list is never shown expanded. A rename mints
  nothing and keeps its children on screen; the graft re-adds the cell in `endEditSession` once it has rows.
- A task **no cell points at** is named in the Change Task menu by its child titles, never by a path (PRD §4):
  `shortestTaskTreePath` reads the denormalized `Task.childTaskIds`, which outlives the detachment.

### Copying a cell

→ ADR 0012. One format for the tree's Ctrl+C / Ctrl+X and both contextual-menu entries: `renderCopiedNodes`
writes it, `parseTreeText` reads it, and nothing else parses a clipboard.

- **The clipboard text is for a PERSON to read**, not only for the app to parse: a tab-indented title line per
  task, its fields as named `- <field>: <value>` lines one level deeper, the task text **verbatim** in its own
  indented block. Do not re-pack it into flags and appendices for a shorter payload — that shape shipped, and it
  put a form-feed and a `\n`-escaped note in the user's clipboard.
- **A copy carries everything the cell's Edit window holds** (the screen switch, the schedule unit, the text)
  plus its minimum time and its weight row, so Ctrl+V restores the task and not just its title.
- **The priority-weight TABLE of every sub-list the copy walks travels with it** — the parent node carries the
  sub-list's weight columns, each child carries its own value row. A copy that restored the rows without the
  header would re-normalize every percentage at the destination.
- **It carries the task id too**, so a paste lands on the SAME task, not a clone. Three identities
  (`PasteIdentity`): the id names a live *titled* task this cell may hold ⇒ **mirror** it (a sub-list belongs to
  the task id, so its own sub-tree shows and the clipboard's children/fields are never written over it); the id is
  free ⇒ **restore** the task under that very id, and `reserveTaskId` walks the counter past it; no id, or one
  `canAssignTaskId` refuses ⇒ a **fresh** task, as before. An id that is not the `task/user/<n>` the app mints is
  rejected at parse time — never build a task over `task/root`/`task/main`.
- **Ctrl+V REPLACES the cell it lands on**: the cell is re-pointed at the pasted task (never a rename of the task
  that was there), which leaves that task a detached parent its id can bring back.
- **The attribute names ARE the format**: they exist once (the `ATTR_*` constants) and the parser matches those
  same constants. A second copy is how a writer and a reader drift apart.
- Fields belong to the **node**, never to the title — two tasks sharing a name must not share a minimum time.
  A title that reads like an attribute line is escaped (`\- text:`).
- **Paste stays a no-op for foreign text**: an unknown attribute, an unparseable value, a real tab inside a
  title, or an indent jump ⇒ `null` ⇒ the reducer returns the state unchanged. A plain tab-indented title tree
  still pastes, with its min-times left null.
- The pre-1.6.0 form-feed shape is still **read** (a clipboard outlives a rebuild), never written.
- **The menu and Ctrl+C must agree about what "the cell" is**: a right-click INSIDE a multi-selection copies the
  whole block (`contextMenuCopyTargets`), exactly as Ctrl+C does; outside one, that cell alone. Copying only the
  cell under the cursor while a dozen sat selected is what shipped and was wrong.
- **The three gestures divide by how much, and nothing else**: the menu's "copy" is the cell alone (depth 1),
  "deep copy" is the window's number, **Ctrl+C is the ENTIRE sub-tree** (`FULL_SUBTREE_DEPTH`) and **Ctrl+X is
  that copy plus the §4 deletion** of the same cells (one history unit, "Cut" — which is what frees the ids a
  later Ctrl+V restores). Do not re-point the chord at the account depth: a number set for one deep copy would
  then silently truncate every later Ctrl+C.
- **The account's `deepCopyMaxDepth`** (default/reset 20, persisted + synced, not an Undo/Redo unit) is the
  **deep-copy window's** number — the window opens on it and writes it back when it copies.
- **What a copy carries is the account's too** — `CopyOptions`: `copyIncludeIds`, `copyPriorityTables`,
  `copyIncludeText` (all default on, persisted + synced, not Undo/Redo units), the deep-copy window's three
  switches. They govern **every** copy, the menu's "copy" and Ctrl+C/Ctrl+X included; scoped to the window they
  would be unreachable from the everyday gesture.
  - Tables off ⇒ the weight lines are replaced by `- priority in its sub-list: <n> %`, `cellShare` stored as the
    node's **single weight** (so the paste path is untouched and the shares rebuild themselves), rounded at copy
    time so a second round trip changes nothing.
  - Ids off ⇒ the payload is foreign **by construction**: it pastes as new tasks and IS seeded with the §7
    template. That is the switch's meaning, not a leak in the gate.
- **only "deep copy" opens the window**, which prints one path down to the depth. That path
  follows the deepest branch measured over the **whole** depth asked for — measured over the remainder, every
  branch ties and the path jumps around as the number changes — and, over several copied cells, starts from
  whichever of them reaches furthest.

### Find & replace (Ctrl+F)

→ PRD §4. `TaskTreeSearch` is the whole of it; the bar (`ui/TaskTreeFindBar.kt`) is Compose-only state, like
the calendar's zoom — a search is a way of looking at the tree, never a fact about it.

- **The walk covers the WHOLE tree, and visits each LIST once.** A find over the visible rows would miss
  every collapsed one; and a sub-list belongs to the task id, so a mirrored sub-tree is *one* list under
  many parents — re-walking it per occurrence is exponential. Each match carries the path that reached it.
- **A match is a range inside one title**, and a mirrored task is a row of its own — but **Replace All is
  keyed by TASK**, once each: replacing means renaming (`applySetCellTitle`, the Rename-mode primitive), so
  every cell pointing at the task follows. A replacement that empties a title deletes by §4's ordinary rule.
- **Revealing a match is ONE history unit** (`SetExpandedDelta` over the whole expansion set), never one
  `ToggleExpandDelta` per level. And **typing does not jump** — every jump is a selection unit, and
  `Alt+←` would have to walk back one per keystroke. The shading is the live feedback.
- The bar is a **sibling** of the tree, not a child, so the tree's `onPreviewKeyEvent` never sees what is
  typed in it — and the tree's selection-keyed refocus effect must skip while the bar holds the keyboard.

### The "All tasks" list

→ PRD §7. `SchedulerDomain.taskListEntries` is the whole of it; `ui/TaskListWindow.kt` only draws it.

- **It is a readout of the LIVE tree**: `absoluteTaskPriorities` (the identity the tree's own percentage column
  keeps), never `blendedTaskPriorities`. `formatPriorityPercent` is shared with the tree — a second copy is how
  two readouts of one number start disagreeing at the first decimal.
- **A mirrored task is ONE row.** Occurrences are counted off `state.cells` through `isPopulatedCell`, exactly
  as `absoluteTaskPriorities` and `RelativePriority.occurrenceChains` count them, so the two columns can never
  disagree about what an occurrence is. A blank-titled (deleted) task and a detached parent are not in the list.
- **Ties fall back to the title then the id, and the tie-break is NOT reversed with the direction** — otherwise
  a block of tasks sharing one percentage re-shuffles every time the arrow is flipped.
- **The sorter is Compose-only state**, like the calendar's zoom and the find bar: an ordering is a way of
  looking at the tree, never a fact about it. Not persisted, not synced, no history unit.

### The default sub-tree

PRD §4/§7: one per account, grafted under every task the user **creates**. Off by default
(`defaultSubtreeEnabled`), authoritative, and outside `schedulingSignature` — a template schedules nothing
until it is applied to a real cell.

- **The template IS a real task tree** (`DefaultSubtreeTemplate`: a `TreeSnapshot` in the same shape a
  `TaskTreeEntry` stores, rooted at the same `WellKnownIds`). Do not turn it back into a tree of titles — a
  template row must be a real `Task`, or four of the five §13 menu entries have nothing to act on and "edit"
  has nowhere to write.
- **The window IS the task tree — the same code, not the same look.** `scheduler/ui/TaskTreeView.kt` is the
  ONE tree, drawn twice: once by `TaskSchedulerScreen` over the account's state, once by
  `ui/DefaultSubtreeWindow.kt` over `projectDefaultSubtree()`. So it has every gesture, Ctrl+F included, and
  the **full five-entry §13 menu**. A second implementation is what shipped before, and it silently lacked the
  menu entirely. Add a tree feature in `TaskTreeView` and both get it.
- **Nothing is dropped but the switch is added**: the percentage (the row's share **within the template**) and
  the minimum time are both shown and both meaningful, and the switch is one more column after them. Do not
  add a bin button: **the blank title is what deletes**, here as in the tree.
- **Two projections, and the split is the point** (`state/DefaultSubtreeProjection.kt`):
  `projectDefaultSubtree()` merges the live tree UNDER the template so a bound row resolves and the ordinary
  Change Task menu can offer live tasks; `defaultSubtreePriorities()` uses the template's cells/lists **alone**
  because `absoluteTaskPriorities` iterates every cell it is given and would otherwise divide the template's
  shares by the whole account. Ids cannot collide (child lists are `{taskId}/children`, cells come off a shared
  counter) except at the root, which the template shadows.
- **The fold back keeps only what is reachable from the template's root**, stopping at a task the live tree
  owns — a mirror belongs to the live tree, and copying it in would start it going stale. The live half of the
  projection is **discarded**, which is what makes it impossible for anything dispatched in that window
  (`purgeOrphanTasks` included) to damage the real tree. The id **counters** are the one thing written back to
  both sides.
- **Every intent the window raises is wrapped in `InDefaultSubtree`** — except Undo/Redo, which belong to the
  app's stacks where the window's own `DefaultSubtreeDelta` units are waiting. One gesture is **one** Main
  unit; the inner reductions' units evaporate with the projection.
- **`defaultSubtreeIsEmpty` lives on the STATE, not on the template.** A bound row's title lives on the *live*
  task it points at, so asking the template alone calls it untitled and skips a template that is anything but
  empty.
- **A node's switch is `boundCells`.** Off ⇒ every grafted cell mirrors the row's own `taskId`; on (the
  default) ⇒ a fresh task per graft, carrying the row's title, fields, minimum time and weight row.
- **A row pointing at an existing task shows that task's OWN sub-tree** — a sub-list belongs to the task id —
  drawn by the tree as the ordinary mirror it is. Do not "fix" this by writing into the bound task's sub-list.
- The chrome still lives in **one** place — `ui/TaskSheetChrome.kt` (`SheetColors`, `INDENT_STEP_DP`,
  `taskSheetGuideLines`, `TaskSheetExpandArrow`, `TaskSheetTitleBounds`).
- **It fires once, at `endEditSession`**, and only when the session **created** the task (`taskId !in
  session.treeBefore.tasks`). Not per keystroke (each one re-runs the naming), and not when the session reused
  an existing task (its sub-tree already came with the id). A sub-list that already holds a cell is never
  re-seeded.
- **Asking for a sub-tree while a cell is being edited ends that session first** (`ToggleExpand` is a PRD §4
  Forced Exit, like clicking another cell). Otherwise the arrow opens the just-named task onto its bare
  placeholder. The toggle itself is skipped when the graft's auto-expand already answered the click.
- **The graft drives `applySetCellTitle` / `applyAssignTaskId`**, so occurrences, `childTaskIds`, the title
  index and auto-expansion stay owned by the code that already owns them. Never a second copy of those rules.
- **A seeded row must never seed in turn** — that is an unbounded cascade, not a deeper template. The graft
  calls those primitives *directly*, never the `SetCellTitle` intent, so it descends only through the
  template's own children and stops at its leaves. Never route it through the reducer's intent path.
- A binding the live tree cannot honour (a task only the template knows, deleted, another task tree, or
  `canAssignTaskId` says no) falls back to a new task.
- **Only a paste of FOREIGN text seeds** — the gate is the clipboard's **id**, not `PasteIdentity`. An id
  means the app wrote that text, so what is landing is a task's own content: a copied sub-tree comes back as
  itself whether it lands as a Mirror, a Restore, or a Fresh clone (`canAssignTaskId` refused the id here).
  Only a payload with **no id at all** — another app's tab-indented list, or a pre-1.6.0 clipboard — is a task
  the user is creating. `graftDefaultSubtree`'s empty-sub-list guard then keeps the clipboard's own children
  from being seeded over. The other internal `applySetCellTitle` callers still never graft.
- **The §13 menu's "add default sub-tree" is the explicit answer** (`AddDefaultSubtree`), and it is
  deliberately unlike the graft: it ignores the on/off switch (that switch governs the *automatic* graft, and
  this is the asking) and it does not care whether the task is new. It acts on `contextMenuCopyTargets` — the
  whole block inside a multi-selection, exactly as "copy" does — as one Main history unit. Offered only where a
  template exists.
- **It lands on the LEAVES of the sub-tree, never beside them** (`defaultSubtreeApplicationTargets`): a cell
  that parents nothing is its own leaf, so the plain case and the deep case are one rule. A template says how
  a piece of work breaks down, so asking for it on a cell already broken down asks for it on the pieces.
  Every cell walked is expanded, or rows landing at the bottom would be invisible.
- **The targets are read off the state BEFORE anything is written.** Filling a leaf gives it children; a
  traversal of the state it is mutating would meet that task again (mirrored elsewhere in the same sub-tree),
  find it no longer a leaf, and seed the rows it just wrote — the cascade, by another route. A task is visited
  **once, by id**: one sub-list serves every occurrence, and the id set doubles as the cycle guard.

### Task trees are live alternatives, not backups

- `SelectTaskTree` **flushes** the live tree into the entry being left before loading the target.
- Stored snapshots **keep records** (`captureTreeWithRecords`); id counters take the **max** of both sides.
- All three mutation intents commit one `TaskTreeDelta` into the **Main** history.
- Identity-menu rows are told apart by `TaskTreeMenuEntry.Kind`, **never by `id == null`**.
- The first-startup tree is seeded **structurally**, not through `CreateTaskTree` — a default is not a user
  action and must record no history unit.
- Known scope limit: panels are not per-tree.

---

## Accounts

→ ADR 0007.

- **The app is ALWAYS connected to an account. There is no signed-out mode** — do not reintroduce one. "Not
  connected" in the UI only ever means "on a guest account".
- A guest account is a real account with no credentials (GoTrue anonymous sign-in). Requires
  `enable_anonymous_sign_ins` on the project (a manual/`config push` step — `db push` does not apply auth
  settings).
- **"Create account" CLAIMS the current guest account** (`PUT /auth/v1/user`) — it must never create a second
  one.
- Sign-out and remote force-logout both land on a **fresh guest account**.
- **Local data is partitioned per account** (schema v9). **Switching accounts deletes nothing**, and no data
  may smear between partitions. Partitions are never merged.
- The revision baseline / `dirty` / logout baseline are per account too. `saveSyncMeta` refuses to write
  bookkeeping when the save changes the active account.
- Device-level facts (device id, sleep-scan checkpoint, window placement, recorded active sessions) are
  **not** account-scoped.
- The force-logout check **fails open**, so an unapplied migration never wedges syncing.

---

## Sync

→ ADR 0005. Whole-document `scheduler_snapshot`, versioned by `revision`, **automatic in both directions**.
The Sync button is a force-now fallback only — do not reintroduce a button-only model.

- **All snapshot paths funnel through the one mutex-guarded `SchedulerSyncEngine.reconcile()`.**
- Local→remote: a fingerprint-moving edit enqueues a **500 ms debounced** push. Derived/tick reschedules
  leave the fingerprint unchanged and never enqueue one.
- Remote→local: `RealtimeSnapshotSubscriber` (`postgres_changes`) **pokes** `reconcile()`; it never applies
  the event body itself.
- **Streaming is not synchronization.** `postgres_changes` has no cursor to resume from, so **every
  (re)subscribe must reconcile** as a catch-up.
- **Every launch reconciles once**, and **every account change reconciles once**. Without the startup one,
  the first edit's own push fetch LWW-pulls over that edit.
- Echo prevention is layered: the revision guard, `applyRemoteSnapshot` bypassing dispatch/save, and the
  debounce.
- Active-session rows ride **every** reconcile — never a timer or beat. Peer activity is reconcile-bounded.

### Conflicts are MERGED

A three-way merge over a recorded common ancestor (`account_sync.base_payload`, written by everything that
advances the revision).

- Added on one side ⇒ kept. Deleted on one side and untouched ⇒ deleted. Different fields ⇒ both applied.
  Same field ⇒ **remote wins**. Delete vs. edit ⇒ **edit wins**.
- `Task.record` is **unioned**; panels/chores/alarms resolve as **whole objects**; id counters take the
  **max**; ordered lists merge membership then follow the remote's order.
- **The result is repaired, not trusted** (`SnapshotMerge.repair` + `pruneDetachedTree`).
- **The merge is applied locally AND pushed** on top of the remote revision.
- **The Undo/Redo history is NOT merged** — the local stack is kept.
- **LWW survives only as the fallback** (no ancestor, or an undecodable snapshot). It is not the policy.

### A device never pulls its own write back

`writer_device_id` + "exactly one revision ahead + still dirty + our device id" ⇒ adopt the revision, then
push on top. A null writer falls back to a plain pull, so revisions stamped by pre-fix binaries are
permanently unprotected.

---

## Pause cue

→ ADR 0006. Runbook: `docs/PAUSE_CUE_DELIVERY.md`. **The live path still needs on-device confirmation.**

- Presence is a `t_a` `publish_presence` RPC while signed in **and unlocked**; the row is
  `{account, device, server-stamped time}` and **nothing else**. `t_a` is server-owned and returned by the RPC.
- **Nothing that changes with the schedule rides the presence tick.**
- **Two Edge Functions, both must be deployed.** `pause-cue` (e1) handles the clean lock and **decides**,
  anchoring at `now()`. `pause-cue-cron` (e2) handles the dirty kill; the cron already decided, so e2 only
  claims/computes/pushes, anchoring at `t2 = max(beat_at) + t_a/2`. Do not re-anchor the cron path to
  detection time.
- Both share `_shared/push.ts` and `build_pause_cue()`, so only the anchor may differ.
- **Only an OVERDUE break fires a cue** — judged against the account's own newest `beat_at`, never the cron's
  `now()`. Between the two dues the **longest overdue** governs.
- **Idleness is judged account-wide** (`max(beat_at)`), never per row.
- `device_break` is **account-keyed, holds only the two due instants, and is written only on change**
  (retried with backoff). The break LENGTH is the server's (`break_config.length_ms`).
- **`break_due_ms` is the fixed due `lastRest + interval`**, never the drawn start. An already-due pose
  publishes the constant `ALREADY_DUE_MILLIS`; an unanchored pose (`lastRestMillis == 0`) is not published.
- A device belongs to exactly **one** account — every per-device table needs a server-side eviction trigger
  **paired with** a client re-assertion when the row is written event-driven.
- The Sleep/Work toggle writes `account_state` immediately, which suppresses the cue.

### Traffic budget

Steady state is the `t_a` RPC while unlocked — a DB write, not an Edge invocation, and nothing while locked.
Everything else is event-driven REST (reconcile, `account_state`, push-token registration, the
`publish_next_break` change write, the screen-off call, the logout check). **Never add a timer-driven
request.**

Free-plan metering is **egress** + Edge invocations, not request count — request count is the wrong axis to
optimize.

---

## Alarms

→ ADR 0010. **No server involvement, by design** — an alarm's instant is known in advance, so local arming
rings offline/dozing/app-killed. The pause cue needs the server only because its *timing* depends on
cross-device presence.

- The **days** are part of the alarm and are synced. An empty set never rings and is not the default.
- The phone arms its own OS exact alarm (soonest only; the receiver arms the next). The desktop **rings off
  the now-line** via an ordinary boundary sweep — it cannot arm what it isn't running for.
- The sweep **self-delays to the next ring**, de-dupes on **(id, instant)**, and has no screen-active gate.
- The boundary is `LocalDateTime(day, hh:mm).toInstant(tz)` — **not** `startOfDay + minutes`, which skews on
  DST days.
- Every ring is drawn on the calendar as an inert zero-duration marker, projected over the displayed span
  only.
- The tone is synthesized in commonMain (`AlarmTone.loopPcm()`, deterministic) so every device rings
  identically with no loadable resource. Android falls back to the system alarm ringtone if the PCM track
  fails — an alarm must never fail silently. The desktop uses its own thread, never the voice-cue worker.

---

## System-wide keyboard shortcuts

→ ADR 0011. Three chords, `Ctrl+Shift+Alt+A` ("I'm away" / "I'm back"), `Ctrl+Shift+Alt+E` ("Look away now")
and `Ctrl+Shift+Alt+Z` ("Switch task"), claimed from the OS because each is pressed precisely when OmniApp is
**not** the focused window. Never a Compose key handler.

- **The chord must be SWALLOWED, not merely observed.** `RegisterHotKey` is not first-come, first-served: an
  application with its own low-level hook is called before the hot-key table, so one press fired two actions
  (Google Docs' comments pane opened alongside the away toggle). The claim is a `WH_KEYBOARD_LL` hook returning
  non-zero; `RegisterHotKey` stays underneath purely as the fallback, and the two cannot double-fire.
- **Nothing that can block runs inside the hook** — it is on the critical path of every keystroke in the
  session, and Windows silently unhooks a callback that exceeds `LowLevelHooksTimeout`. Log and call the engine
  on the dispatch thread.
- The hook must handle what `RegisterHotKey` handled for us: **auto-repeat** (latch the down transition; swallow
  the up only for a down we swallowed) and **AltGr** (right-Alt arrives as synthetic left-Ctrl + right-Alt, so
  `Shift+AltGr+E` must pass through or the hook eats typed text).
- **Every press posts a RECEIPT** (`SchedulerEngine.announceShortcutReceived`): a "Shortcut received"
  notification naming the chord, raised at the `installGlobalHotkeys` seam **before** the action and whatever
  the action then does. The chord is struck with another window in front, and each one can legitimately do
  nothing visible — so "the app never got it" and "the app got it and had nothing to do" are otherwise the
  same experience. It belongs to the hot-key seam, never to the engine seams behind it: the lateral-menu
  buttons drive those same seams and a click needs no confirming.
- **`GlobalShortcut` is the only list of chords.** The platform actual registers it and the keyboard-shortcuts
  window prints it; never a second copy. `GlobalHotkeys.claim` says which claim the OS granted, and the window
  shows it — "nothing happened" and "something else happened too" are otherwise undiagnosable.
- Desktop-only (Android/iOS report `Unsupported`), and best-effort: a refused chord leaves the app running with
  the lateral-menu buttons, never a failed start.
- The lateral menu's **Keyboard shortcuts** window lists every chord in the app (`KeyboardShortcutCatalog`). The
  per-surface entries are prose — add a chord and its entry in the same change.

---

## Scripts

→ `docs/SCRIPTS.md` for the full reference (state dirs, fast-break variants, deploy gotchas, one-time setup).

| Script | Does |
| --- | --- |
| `account1-empty-and-open.bat` | remote-logout, empty (local + remote), launch as account 1 |
| `account2-open.bat` / `account2-empty.bat` | launch as account 2 (data kept) / empty it |
| `account3-deploy-windows.bat` | build + install the auto-start release, sign in as account 3 |
| `account{1,2,3}-deploy-android.bat` | build/install the APK and launch signed in |
| `*-fast-break*.bat` | the same, with screen breaks retimed for cue testing |
| `deploy-supabase.bat` | migrations + both Edge Functions + `pause-cue-setup.sql` |
| `collect-diagnostics.bat` | merged cross-device diagnostics timeline |
| `update-supabase-cli.bat` | update the CLI tool (not the DB) |

Two rules that bite:

- `deploy-supabase.bat` must keep `call supabase ...` everywhere — a bare invocation transfers control to the
  npm `.cmd` shim and silently skips steps 2–3.
- `pause-cue-setup.sql` must contain **no double-quote character at all**, comments included — the CLI
  otherwise receives a truncated query and still exits 0.
