Kotlin Multiplatform (KMP) project targeting Windows Desktop first.

This file holds only what can bite anywhere. Per-subsystem invariants are in `docs/invariants/` (indexed below), the
reasoning and post-mortems in `docs/adr/` (start at `docs/adr/README.md`), the dated log of what changed, every Supabase
and SQLite migration included, in `CHANGELOG.md`.

## Commands

- `./gradlew checkChange` — the one gate (README § *Test your change*): `:shared:jvmTest`, every target and app shell
  this machine can build, and `startupCheck` where a release DB exists. A new check belongs in it.
- `tryDesktop` (throwaway `~/.omniapp-try`, offline unless `-Ponline`), `tryAndroid` (installs debug, never uninstalls),
  `tryWeb` — its try-it half. Keep them cross-platform and away from the release state.
- `./gradlew :shared:jvmTest --tests "..."` — the tests a change affects, while working.
- `./gradlew :desktopApp:run` — run the desktop app to verify UI/desktop changes.
- `./gradlew :shared:longTest` — the LONG scheduler simulations (minutes to hours, `-PlongScale=0.25` to shrink them).
  Not in `checkChange`: run it before reporting done a change to what the scheduler ANSWERS (the score, the search, the
  fill, the advance — `docs/invariants/scheduler.md` § *Simulations*), and read what it prints even when it passes.
- `./gradlew :shared:startupCheck` — headless start-up on a copy of the release DB (never takes the screen).
  `docs/PERFORMANCE.md` § *Start-up on a real account*.

## Agent safety: do not touch the live release app

- The deployed Windows release app is the user's live runtime and uses `%USERPROFILE%\.omniapp-release`. Never run a
  dev/test build against that state dir: it can write over the live DB and trigger crashes or state corruption.
- Agent-run builds use `-Pomniapp.stateDir=...` with a throwaway dir (`%USERPROFILE%\.omniapp-dev`, `.omniapp-guest`) or
  a dedicated account script.
- Do not kill `org.example.project.exe` or rerun `scripts\account3-deploy-windows.bat` unless the user explicitly asks
  for a redeploy.

## Git: commit and push on `1.6.0` only

User rule 2026-11-04: an agent may commit and push as it sees fit, **on the `1.6.0` branch only**. Never commit to or
push any other branch (`main` included), and never open a pull request unless asked.

## Agent safety: the screen belongs to the user

Besides the global rules, each time a screen-taking command finishes a voice tells the user they can come back.

- Batch the screen-taking work: do every compile, test and file edit first, then take the screen once. Each such
  command is its own step-away / come-back cycle, with a prompt the user must be there to approve.
- Launch the app in the foreground: a background launch has no "finished" event, so its "come back" is only said at the
  end of the turn. `perf-profile.bat`, `account1-empty-and-open.bat` and `account2-open*.bat` hand off through `start`
  and return at once, so they are background launches.

## Verification

- Before reporting a client change done, run `:shared:startupCheck`: a green `jvmTest` once shipped a release that drew
  no window for 90 s.
- After a change to shared code, run `checkChange`. Do not assume `expect`/`actual` declarations work until it passes.
- `:shared:check` is not a gate: it stays red on `verifyCommonMainSchedulerDatabaseMigration` (sqlite-jdbc native lib,
  machine-level). So `.sqm` files are **not** machine-verified: write the migration `jvmTest`.
- Diagnose calendar anomalies with `scripts\collect-diagnostics.bat`, not by asking the user to describe them.
- Diagnose *slowness* by measuring it, not by reading for it: `scripts\perf-profile.bat` for the in-app overlay,
  `:shared:jvmTest --tests "*PerfBenchmarkTest*" -i` for the headless timings, on a large account with time-sim OFF. A
  cost is not a bottleneck until it is multiplied by a rate and put on a thread that owes a frame. `docs/PERFORMANCE.md`.

## Deployment surfaces

**When reporting a change as done, name what must be redeployed for it to take effect and which script, or say "no
deploy needed".** Two independent surfaces:

1. **Supabase** (`supabase/migrations/`, Edge Functions, `pause-cue-setup.sql`'s pg_cron) →
   `scripts\deploy-supabase.bat`.
2. **The client apps** (any `shared`/desktop/Android Kotlin) → an app rebuild via `scripts\account{1,2,3}-*deploy*.bat`.

A green gate means the code compiles and its logic is correct, **not** that the change is live on a device: the running
binary is stale until rebuilt, and a migration is ignored if only the app was.

Scripts: `docs/SCRIPTS.md`. Read its gotchas before editing `deploy-supabase.bat` or `pause-cue-setup.sql`.

## Rules that fire anywhere

The short form; the file named after each carries the whole rule and the reasons.

- **What the server stores and serves is budgeted: a tenth of every Supabase free-tier quota, gated by
  `ServerQuotaTest`.** Never write a large row to change a small part of it, never keep rows that grow with editing
  without a purge the test can read, never publish a row table to Realtime. `server-quota.md`.
- **Never add a timer-driven request, poll or heartbeat.** Everything is event-driven or boundary-driven. A
  `while(true) { delay() }` that re-asks a question is almost always the wrong shape here. `pause-cue.md`,
  `display-hot-path.md`.
- **The runtime never scans the timeline.** The one interpreter (`SchedulerEngine.interpretTo`) compares the line with
  the next armed trigger and does nothing before it; never a per-tick filter, sort or search of `state.panels`, the
  pause history or the environment. `scheduler.md` § *The rules are read by a forward cursor*.
- **Time passing must never re-plan.** Whatever wants a re-plan belongs in `SchedulerDomain.schedulingSignature` (or
  `requestReschedule`), never in a fresh dispatch site or a tick. `scheduler.md` § *Rule state input evolution*.
- **The scheduler's answer need not be the same on two devices; the score decides.** Never refuse a solver or a budget
  "because every device must reach the same rules". `scheduler.md` § *The best score*, § *One device plans*.
- **Anything recomputed on every `nowMillis` tick must be bounded by the visible window, never O(total history)** — and
  what the calendar *composes* is bounded too. `display-hot-path.md`.
- **One rule, one funnel.** Nearly every regression in `docs/adr/` is a second copy of a rule that drifted from the
  first. Before adding a code path that answers a question the app already answers, find the existing one and route
  through it. A funnel with an exception list is not a funnel.
- **`dispatch` publishes `SchedulerState` by compare-and-set**, because more than one thread reduces it. Never assign
  `_state.value` directly; an off-thread reducer re-derives on a lost race. `display-hot-path.md`.
- **Test against a large, realistic DB**, not an emptied one: it hides every cost and whole classes of bug.
- **Prefer expressing a thing in the model that already exists** over adding a mechanism beside it.
- **Platform differences are decided per input event or per injected seam, never by asking which build this is.** Add
  to `docs/PLATFORMS.md` before branching.
- **No text of the app is cut by "…"** (user rule 2026-11-04): a text too long for its room wraps — never
  `TextOverflow.Ellipsis`, never a string shortened behind one (`NoTextCutByAnEllipsisTest`). A label saying more is to
  come ("add…", "Syncing…") is not cut text.

## State: authoritative vs. derived

**Persist and sync only state that cannot be recomputed from other persisted data.** Before persisting or syncing a
field, ask whether it can be re-derived; if so, recompute it. Derived state is stripped from the wire, and an engine
tick that *only* re-derives something never marks the state dirty or triggers a sync push. Local-only view state is
persisted locally and never synced. Every field's class: `persistence.md` § *State: authoritative, derived, local-only*.

### Persisted-DB compatibility

- Any change to `SchedulerState` / `SchedulerStateCodec` / the `Persisted*` types, or to reducer logic that
  writes state, **must come with a test** that loads a payload written by the *previous* shape and asserts it
  still loads correctly or is migrated on load.
- New field on a `Persisted*` type ⇒ give it a default, and extend a decode test with a payload lacking it.
- `decode` must **heal** states an older build wrote that current invariants forbid — not surface them as
  anomalies.
- Same for the SQLite schema (`Scheduler.sq` + a new `N.sqm`): reproduce the previous on-disk shape in a test
  and assert the upgrade keeps the data.

## Subsystem invariants — READ BEFORE EDITING

Each file is long: read the parts covering your change, found by searching for what you are touching. They
cross-reference each other. Source directories carry a `CLAUDE.md` pointing at the same files.

| `docs/invariants/` | Before touching |
| --- | --- |
| `scheduler.md` | the plan, the score and its search (`MipScheduleSolver` included), resilience, restrictive periods, what reaches the scheduler, progressive calculation, when the plan is recomputed, the runtime's forward cursor over the rules |
| `screen-breaks.md` | the three dynamic periods (the break machine), the `t_p` mode, the now-line sweep, every notification / voice cue, the Notifications switch |
| `calendar.md` | the grid, layers, grey periods, hover bubbles, the now-line, panel menus, what may be banked as a record |
| `display-hot-path.md` | anything on a per-tick or per-frame path (`App.kt` included), the resample delay, the schedule horizon |
| `popups.md` | any pop-up window, transient or otherwise |
| `priorities.md` | relative priority, the weight table, the task-relations list, categories and category rules |
| `task-tree.md` | the tree and its three drawings, sub-list ownership, the clipboard, find & replace, task colours, which tasks the tree holds, the Search window and a task's last path, the default sub-tree, task trees, the timeline blend |
| `sync-and-accounts.md` | accounts, partitions, the reconcile, sync by rows, the three-way merge |
| `server-quota.md` | anything that sends, stores or publishes on Supabase: a table, a column, a request, a Realtime stream, a cron job |
| `pause-cue.md` | presence, the Edge Functions, the cron, the traffic budget |
| `persistence.md` | what is persisted and synced (the state classification), history units, the store, the SQLite driver and its pragmas |
| `alarms-and-timers.md` | alarms, timers, the arming loop and the ring sweep |
| `shortcuts.md` | the four system-wide chords, rebinding, the hover hints, the platform hot-key actuals |
| `line.md` | the line engine ported from `line_manager` (edits, reversal, rule adaptation, drags), its golden file |

The Claude Code logs page keeps its invariants in `claudelogs/CLAUDE.md`.
