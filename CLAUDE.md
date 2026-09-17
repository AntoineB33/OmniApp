Kotlin Multiplatform (KMP) project targeting Windows Desktop first.

This file is the **always-loaded** layer: the commands, the safety rules, and the invariants that can bite
anywhere in the codebase. The per-subsystem invariants live in `docs/invariants/` and are indexed below —
**read the relevant one before changing that subsystem**, and note that each source directory carries a
`CLAUDE.md` pointing at the same files. The reasoning, the rejected approaches and the post-mortems behind
every rule are in `docs/adr/` (start at `docs/adr/README.md`); the dated log of what changed when, including
every Supabase and SQLite migration, is in `CHANGELOG.md`.

## Commands

- `./gradlew :shared:check` — verify syntax/compile errors after editing the `shared` module.
- `./gradlew :shared:jvmTest` — the real logic gate (see *Verification* below).
- `./gradlew :desktopApp:run` — run the desktop app to verify UI/desktop changes.

## Agent safety: do not touch the live release app

- The deployed Windows release app is the user's live runtime and uses `%USERPROFILE%\.omniapp-release`.
- Never run a dev/test desktop build against that same state dir. Doing so can write over the live local DB and trigger crashes or state corruption.
- If the user already has the release app running, prefer a separate throwaway state dir such as `%USERPROFILE%\.omniapp-dev` or `%USERPROFILE%\.omniapp-guest` for agent-driven runs.
- When testing a local build, use `-Pomniapp.stateDir=...` or a dedicated account script, never the release app's DB.
- Do not kill `org.example.project.exe` or rerun `scripts\account3-deploy-windows.bat` unless the user explicitly asks for a redeploy.
- Treat the deployed app as production-like state: keep it isolated from experiments and from agent-run builds.

## Verification

- After any change to shared Kotlin logic, run `:shared:check` before reporting it as done.
- Do not assume `expect`/`actual` declarations work until `:shared:check` passes.
- `:shared:check` is red on JS (missing platform actuals) and on
  `verifyCommonMainSchedulerDatabaseMigration` (sqlite-jdbc native lib — machine-level). So `.sqm` files are
  **not** machine-verified: write the migration `jvmTest`. Verify logic with `:shared:jvmTest` and iOS
  portability with `compileCommonMainKotlinMetadata`.
- Do not use Android-specific CLI tools to render previews.
- Diagnose calendar anomalies with `collect-diagnostics.bat`, not by asking the user to describe them.
- Diagnose *slowness* by measuring it, not by reading for it: `perf-profile.bat` for the in-app overlay and
  `:shared:jvmTest --tests "*PerfBenchmarkTest*" -i` for the headless derivation timings. `docs/PERFORMANCE.md`.
  Profile a large, realistic account with time-sim OFF — the sim clock's ~20 Hz now-line inflates every
  display-path cost by more than an order of magnitude over what a user pays.

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

## Rules that fire anywhere

These bite in code that does not look like it belongs to the subsystem that owns them. They are the short
form; the file named after each one carries the whole rule and the reasons.

- **What the server stores and serves is budgeted: a tenth of every Supabase free-tier quota, gated by
  `ServerQuotaTest`.** The project went down on 2026-09-14 with its 500 MB database full of versions of one
  document rewritten per edit. Never write a large row to change a small part of it, never keep rows that grow with
  editing without a purge the test can read, and never publish a row table to Realtime. `server-quota.md`.
- **Never add a timer-driven request, poll or heartbeat.** Everything is event-driven or boundary-driven: the
  server traffic budget (`pause-cue.md`), the unlock edge that clears "I'm away", the display resample
  (`display-hot-path.md`). A `while(true) { delay() }` that re-asks a question is almost always the wrong
  shape here.
- **Time passing must never re-plan.** Anything that wants to trigger a re-plan belongs in
  `SchedulerDomain.schedulingSignature` (or `requestReschedule`), never in a fresh dispatch site or a tick. A re-plan
  of unchanged rules rewrites a schedule the progressive calculation already made definitive — the hourly
  "staleness bound" did exactly that and was removed (2026-09-17). One bounded exception exists and is named in
  `scheduler.md`.
- **The scheduler's answer need not be the same on two devices; the score decides.** Budgets may be wall time, a
  platform may bring its own solver (the desktop's OR-Tools MIP), and plans made apart compete on the score under the
  rules in force now. Never refuse a solver or a budget "because every device must reach the same rules".
  `scheduler.md` § *The best score*, § *One device plans*.
- **Anything recomputed on every `nowMillis` tick must be bounded by the visible window, never O(total
  history)** — and what the calendar *composes* is bounded too. `display-hot-path.md`.
- **One rule, one funnel.** Nearly every regression recorded in `docs/adr/` is a second copy of a rule that
  drifted from the first: a second placement derivation, a second notification gate, a second clipboard
  parser, a second row implementation, a second reading of the lock history. Before adding a code path that
  answers a question the app already answers, find the existing one and route through it. A funnel with an
  exception list is not a funnel.
- **`SchedulerState` is reduced by more than one thread, so `dispatch` publishes by compare-and-set.** The
  engine reduces its two expensive re-plans off the frame loop (`display-hot-path.md`); a new call site that
  assigns `_state.value` directly, or a second off-thread reducer that does not re-derive on a lost race, puts
  back the bug where a 60 ms plan silently reverts the keystroke typed inside it.
- **A cost is not a bottleneck until it is multiplied by a rate and put on a thread that owes a frame.**
  `PerfBenchmarkTest` ranks per-call costs; only the overlay's ms/s ranking (or the thread the call sits on)
  turns one into a diagnosis. `docs/PERFORMANCE.md` § *What the table is and is not*.
- **Derived state is stripped from the wire and never triggers a sync push** — see the table below, which is
  the whole of the classification.
- **Test against a large, realistic DB**, not an emptied one. An empty account hides every cost and several
  whole classes of bug.
- **Prefer expressing a thing in the model that already exists** over adding a mechanism beside it. Task
  resilience, the recurrence bars, and the restrictive-period kinds each replaced a hard-coded special case;
  re-adding one is the specific mistake those models exist to prevent.
- **Never create git commits automatically.**

## State: authoritative vs. derived

→ ADR 0007.

**Persist and sync only state that cannot be recomputed from other persisted data.** Before persisting or
syncing a field, ask whether it can be re-derived; if so, recompute it. Never let an engine tick that *only*
re-derives something mark the state dirty or trigger a sync push.

| Class | Contents | Rule |
| --- | --- | --- |
| **Authoritative** | task tree (task **resilience** included), the account's **period kinds**, named task trees, the default sub-tree + its switch, user-authored/pinned panels and the periods the app conducted, chores/reminders, sleep schedule, alarms, timers (**whether one is running** included), settings, the system-wide chord bindings, the **task relations** the user kept or struck off, the account's **categories** and their **rules**, the **pins** of both priority windows, Undo/Redo history units, manual record edits | persist + sync |
| **Derived** | auto/screen-break/sleep panels, task colours, a running timer's remaining time, the dynamic periods' placement (the recurrence bars read their anchors out of the timeline), records the advance banks, the rules' repeating part (`scheduleCycle` — in memory only, not even persisted), the elected device's rules on the peers' broadcast channel (never stored, adopted as derived panels) | persisted locally, **stripped from the wire**, never trigger a push on their own |
| **Local-only view state** | focused window, tree selection, the "All tasks" window's own expansion/selection/edit session, `showScreenBreaks`/`showReminders`, WindowNav/Selection history, window placement, OS-sleep scan checkpoint, the device's work-offline choice, the refused-edit notice (`categoryRuleError`, not even persisted) | persist locally, **never sync** |

- Local view state is stripped from the fingerprint by `withLocalViewStateNeutralized()` and carried across a
  pull by `withLocalViewStateFrom`.
- Calendar zoom is Compose-only state — never persisted at all.
- The one deliberate exception: the state is replayable from history units, but history is bounded
  (`MAX_HISTORY_UNITS`), so the entity rows stay authoritative.
- Authoritative state syncs as **one row per entity** and each History Unit as **one row**; the units are shared by
  every device, but Undo/Redo act only on this device's own (`sync-and-accounts.md` § *Sync by rows*,
  `persistence.md`).

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

## Subsystem invariants — READ BEFORE EDITING

Each file is active invariants only. Open the one covering what you are about to change; open more than one
where a change spans them (they cross-reference each other).

| Read | Before touching | Mostly lives in |
| --- | --- | --- |
| `docs/invariants/scheduler.md` | the plan, the score and its search, resilience, restrictive periods, what reaches the scheduler, progressive calculation, when the plan is recomputed | `scheduler/domain/ScheduleScore.kt`, `ScheduleOptimizer.kt`, `ScheduleImprover.kt`, `ScheduleSearch.kt`, `ScheduleFill.kt`, `SchedulerDomain.kt`, `PeriodKinds.kt`, `jvmMain/.../MipScheduleSolver.kt` |
| `docs/invariants/screen-breaks.md` | the three dynamic periods, the `t_p` mode, the now-line sweep, every notification / voice cue, the Notifications switch | `scheduler/domain/DynamicPeriods.kt`, `scheduler/engine/` |
| `docs/invariants/calendar.md` | the grid, layers, grey periods, hover bubbles, the now-line, panel menus, what may be banked as a record | `ui/CalendarUi.kt` |
| `docs/invariants/display-hot-path.md` | anything on a per-tick or per-frame path, the resample delay, the schedule horizon | `ui/CalendarUi.kt`, `App.kt`, `scheduler/engine/SchedulerEngine.kt` |
| `docs/invariants/popups.md` | any pop-up window, transient or otherwise | `ui/PopupWindows.kt` |
| `docs/invariants/priorities.md` | relative priority, the weight table, the task-relations list, categories and category rules | `scheduler/domain/RelativePriority.kt`, `CategoryRules.kt`, `TaskRelations.kt` |
| `docs/invariants/task-tree.md` | the tree and its three drawings, sub-list ownership, the clipboard, find & replace, task colours, the "All tasks" window, the default sub-tree, task trees, the timeline blend | `scheduler/ui/TaskTreeView.kt`, `scheduler/state/`, `scheduler/domain/TaskColorSpace.kt` |
| `docs/invariants/sync-and-accounts.md` | accounts, partitions, the reconcile, sync by rows, the three-way merge | `scheduler/sync/` |
| `docs/invariants/server-quota.md` | anything that sends, stores or publishes on Supabase: a table, a column, a request, a Realtime stream, a cron job | `supabase/`, `scheduler/sync/`, `jvmTest/.../quota/` |
| `docs/invariants/pause-cue.md` | presence, the Edge Functions, the cron, the traffic budget | `supabase/`, `scheduler/sync/` |
| `docs/invariants/persistence.md` | history units, the store, the SQLite driver and its pragmas | `scheduler/persistence/` |
| `docs/invariants/alarms-and-timers.md` | alarms, timers, the arming loop and the ring sweep | `scheduler/domain/AlarmDomain.kt`, `TimerDomain.kt`, `ui/AlarmWindow.kt` |
| `docs/invariants/shortcuts.md` | the four system-wide chords, rebinding, the hover hints | `ui/ShortcutsWindow.kt`, `ui/ShortcutHint.kt`, platform hot-key actuals |

---

## Scripts

→ `docs/SCRIPTS.md` for the full reference (state dirs, fast-break variants, deploy gotchas, one-time setup).

| Script | Does |
| --- | --- |
| `account1-empty-and-open.bat` | remote-logout, empty (local + remote), launch as account 1 |
| `account2-open.bat` / `account2-empty.bat` | launch as account 2 (data kept) / empty it |
| `account3-deploy-windows.bat` | build + install the auto-start release, sign in as account 3 |
| `account3-deploy-windows-offline.bat` | the same, but every launch starts working completely offline |
| `account{1,2,3}-deploy-android.bat` | build/install the APK and launch signed in |
| `*-fast-break*.bat` | the same, with screen breaks retimed for cue testing |
| `deploy-supabase.bat` | migrations + both Edge Functions + `pause-cue-setup.sql` |
| `collect-diagnostics.bat` | merged cross-device diagnostics timeline |
| `perf-profile.bat` | launch with the performance overlay (time-sim off, never the release state dir) |
| `update-supabase-cli.bat` | update the CLI tool (not the DB) |

Two rules that bite:

- `deploy-supabase.bat` must keep `call supabase ...` everywhere — a bare invocation transfers control to the
  npm `.cmd` shim and silently skips steps 2–3.
- `pause-cue-setup.sql` must contain **no double-quote character at all**, comments included — the CLI
  otherwise receives a truncated query and still exits 0.





Never create git commits automatically.