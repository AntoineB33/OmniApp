# Changelog

Dated history extracted from `CLAUDE.md`. `CLAUDE.md` holds only the active invariants; the *why* behind each
decision lives in `docs/adr/`. This file answers "when did this change, and what did it replace?"

Newest first within each section.

---

## 1.6.0 — spec deltas and their status

Check here before assuming the code matches the docs.

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
