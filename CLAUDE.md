Kotlin Multiplatform (KMP) project targeting Windows Desktop first.

## Commands
- `./gradlew :shared:check` — verify syntax/compile errors after editing the `shared` module
- `./gradlew :desktopApp:run` — run the desktop app to verify UI/desktop changes

## Rules
- Do not use Android-specific CLI tools to render previews.
- Do not assume `expect`/`actual` declarations work until `:shared:check` passes.
- After any change to shared Kotlin logic, run `:shared:check` before reporting it as done.

## Persisted-DB compatibility
- Any change to the persisted state model (`SchedulerState` / `SchedulerStateCodec`, the `PersistedPanel`/`Persisted*` types) or to reducer logic that writes state must come with a test that decides whether **existing on-disk DBs must be changed** — i.e. loads a representative payload written by the *previous* shape and asserts it either still loads and renders correctly, or is migrated/repaired on load.
- Old DBs can hold data an older build wrote that current invariants forbid; `decode` must heal such states, not surface them as anomalies. Reference case: a blank-titled task that still has records rendered every past calendar block as "(untitled)" (see the `calendar-untitled-tombstone` note). The fix is data-level, not UI — the test must catch the bad persisted shape, not just the rendering.
- Adding a field to a `Persisted*` type: give it a default so payloads written before the field decode cleanly, and add/extend a decode test that loads a payload lacking it.

## What is authoritative vs. derived (reconstructibility rule)
Persist and sync **only state that cannot be recomputed from other persisted data.** Anything derivable is recomputed (on load / on the next now-advance) instead of being stored or pushed. Before persisting or syncing a field, ask whether it can be re-derived from the inputs below; if so, recompute it rather than storing it, and never let an engine tick that *only* re-derives it mark the state dirty or trigger a sync push.
- **Authoritative (persist + sync):** the task tree (lists/cells/tasks, titles, weights), completed-work **records** (`task.record`), **user-authored / pinned** calendar panels, chores/reminders, the sleep schedule, settings, and the Undo/Redo **history units**.
- **Derived (must NOT count as a syncable change):** the automatic schedule — the auto / side-task / sleep **panels** `SchedulerDomain.fillSchedule` regenerates. They are a pure function of `now` + the task tree + the sleep/side-task config + device-sleep history, so the engine rebuilds them on load and on every now-advance. Side-task config is likewise hardcoded (`DEFAULT_SIDE_TASKS`, seeded in `prepareLoadedState`), not persisted — that is this rule already applied.
- **Deliberate exception:** the whole-state snapshot is itself replayable from all the history units — but only while history is within `MAX_HISTORY_UNITS` (older units are evicted). Because history is bounded, the snapshot is kept as the authoritative base and is persisted/synced anyway.

**Known deviation (to fix):** the regenerated auto/side/sleep panels currently *are* written into `PersistedPanel` and pushed on every now-advance, so an idle session syncs ~once per scheduler tick (the per-tick sync-chip chatter). The intended state is that engine-tick reschedules neither mark the state dirty nor push; only the authoritative changes above do.

## Account scripts
The desktop DB is a SQLite file (`scheduler-state.db`; one `app_state` row + per-unit `history_unit` rows) under a per-run **state dir**, so accounts don't share data: account 1 → `~/.omniapp-acc1`, account 2 → `~/.omniapp-acc2`, account 3 (the auto-start release) → `~/.omniapp-release`. Running without an override (`-Pomniapp.stateDir` / `OMNIAPP_STATE_DIR`) uses the default `~/.omniapp`.

The `scripts/` entry points open the app already signed in to a Supabase account (non-interactive auto-login by username), empty an account's data, or deploy the auto-start release/Android build:
- `account1-empty-and-open.bat` — empty account 1 (local DB **and** the remote Supabase snapshot/presence rows), then launch logged in as account 1.
- `account2-open.bat` — launch logged in as account 2 (data preserved).
- `account2-empty.bat` — empty account 2 (local + remote); does not relaunch.
- `account3-deploy-windows.bat` — build the release app image, install it outside the tree, register it to auto-start at Windows login, and auto-sign-in as account 3 (release DB `~/.omniapp-release`, left untouched by updates).
- `account3-deploy-android.bat` — build/sign/install the release APK and launch it auto-signed-in as account 3 (BootReceiver keeps the scheduler running across reboots).
- `deploy-supabase.bat` — three steps, so nothing is copy-pasted into the Dashboard SQL Editor: (1) `supabase db push` applies `supabase/migrations/`; (2) `supabase functions deploy pause-cue`; (3) if `SUPABASE_SERVICE_ROLE_KEY` is in accounts.env, `supabase db query --linked` applies `supabase/pause-cue-setup.sql` (pg_cron/pg_net + `app.settings.*` + the cron job), injecting the secret key from accounts.env (never committed). All idempotent; re-run after editing. Needs a one-time `supabase login` + `supabase link`.
- `update-supabase-cli.bat` — update the standalone Supabase CLI **tool** (not the DB) to the latest GitHub release, in place under `%LOCALAPPDATA%\supabase\bin`. The CLI was installed as a raw binary (no scoop/winget/npm), so this is how it stays current. `-Force` reinstalls regardless of version.

The Supabase schema lives in `supabase/migrations/` (applied by the CLI, not pasted by hand). The project-level pause-cue setup (extensions, cron, `app.settings.*`) is `supabase/pause-cue-setup.sql`, applied by step 3 above via `supabase db query`. The `pause-cue` Edge Function now performs the real FCM/APNs sends; the remaining follow-ups are the Edge Function's `FCM_/APNS_` secrets (`supabase secrets set`) and native phone push-token registration + the client's `pause_cue_schedule`/`account_last_phone` writes — until those exist the push-cue tables are inert. Full runbook (client wiring, Firebase/APNs setup, physical-Android + simulated-iPhone test steps): `docs/PAUSE_CUE_DELIVERY.md`.

Credentials live in `scripts/accounts.env` (gitignored; copy `accounts.env.example`). Emptying covers both local and remote because a local-only wipe just re-pulls the old cloud data on next sync. One-time setup: create the accounts with `python scripts/internal/account_db_admin.py signup <user> <pass>` — usernames map to `<user>@omniapp.local`, so the Supabase project must have **email confirmation disabled** — and apply the schema (`deploy-supabase.bat`, or paste the migration into the SQL Editor). Its `for all` RLS policies already grant the own-row DELETE the empty scripts need; only add a narrower DELETE policy if you replaced them. Auto-login is wired in `shared/.../scheduler/sync/StartupLogin.kt` + `TaskSchedulerViewModel`; see the `per-account-scripts` note.