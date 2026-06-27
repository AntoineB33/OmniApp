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

## Account scripts
The desktop DB is a SQLite file (`scheduler-state.db`; one `app_state` row + per-unit `history_unit` rows) under a per-run **state dir**, so accounts don't share data: account 1 → `~/.omniapp-acc1`, account 2 → `~/.omniapp-acc2`, account 3 (the auto-start release) → `~/.omniapp-release`. Running without an override (`-Pomniapp.stateDir` / `OMNIAPP_STATE_DIR`) uses the default `~/.omniapp`.

The `scripts/` entry points open the app already signed in to a Supabase account (non-interactive auto-login by username), empty an account's data, or deploy the auto-start release/Android build:
- `account1-empty-and-open.bat` — empty account 1 (local DB **and** the remote Supabase snapshot/presence rows), then launch logged in as account 1.
- `account2-open.bat` — launch logged in as account 2 (data preserved).
- `account2-empty.bat` — empty account 2 (local + remote); does not relaunch.
- `account3-deploy-windows.bat` — build the release app image, install it outside the tree, register it to auto-start at Windows login, and auto-sign-in as account 3 (release DB `~/.omniapp-release`, left untouched by updates).
- `account3-deploy-android.bat` — build/sign/install the release APK and launch it auto-signed-in as account 3 (BootReceiver keeps the scheduler running across reboots).

Credentials live in `scripts/accounts.env` (gitignored; copy `accounts.env.example`). Emptying covers both local and remote because a local-only wipe just re-pulls the old cloud data on next sync. One-time setup: create the accounts with `python scripts/internal/account_db_admin.py signup <user> <pass>` — usernames map to `<user>@omniapp.local`, so the Supabase project must have **email confirmation disabled** — and add an own-row **DELETE RLS policy** to `scheduler_snapshot` + `device_presence` (SQL in that script) so the empty scripts can clear remote data. Auto-login is wired in `shared/.../scheduler/sync/StartupLogin.kt` + `TaskSchedulerViewModel`; see the `per-account-scripts` note.