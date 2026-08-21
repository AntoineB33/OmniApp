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
| **Authoritative** | task tree, named task trees, user-authored/pinned panels, chores/reminders, sleep schedule, alarms, settings, Undo/Redo history units, manual record edits | persist + sync |
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
- **Past breaks are read off the ANCHORS, not the projection grid.** A look-away chains backward; a pose
  vouches for exactly one occurrence.
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
- **A device that cannot be asked was UNLOCKED** (`null` ⇒ layer not drawn). This is the opposite default
  from `derivePauses`, deliberately: `null` and an empty list are different answers.
- **A stretch carrying BOTH layers is a no-screen period**, identical to the account-wide derived pause.
  `CalendarLayerTest` pins that identity — keep it true.
- Layers are non-interactive overlays: they displace nothing and register no pointer input.
- **GREY = the scheduler places nothing here** — inactivity period, sleep window, the look-away end to end,
  the pose's closed head. It is not a screen classification: it refuses off-screen tasks too. A pose's open
  tail and the 15-min pose are **not** grey.
- Derived grey bands are `[displayFloor, now]` minus everything already drawn, except no-screen periods and
  screen breaks. Display-only, sub-minute remnants dropped.
- Known and accepted: a task panel can sit under the computer hatch (the OS wins).
- **Known inconsistency:** layers read the OS lock history; the engine's pause derivation still reads
  `device_active_session`. Decide this before adding anything else that reads one and not the other.

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
- A task **no cell points at** is named in the Change Task menu by its child titles, never by a path (PRD §4):
  `shortestTaskTreePath` reads the denormalized `Task.childTaskIds`, which outlives the detachment.

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
