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

## Dev DB scripts
The desktop dev DB lives at `~/.omniapp/scheduler-state.db` (SQLite; one `app_state` row + per-unit `history_unit` rows). The `scripts/` helpers:
- `dev-restart.bat` — kill the app, wait, then relaunch **preserving** the live DB.
- `dev-empty.bat` — kill the app, wait, then **delete** the live DB (`scheduler-state.db` + `-wal`/`-shm`), leaving an empty state; does not relaunch. (Piper models and the duplicate DB are kept.)
- `dev-reset.bat` — launch with an **isolated** empty DB in a separate dir (`~/.omniapp-reset`); never touches the live DB.

### Adapted-duplicate workflow
When a code change needs the persisted DB to be **changed to adapt to the development changes** (a migration or a data fix), do not mutate the live DB in place. Instead keep the changed copy as a **duplicate** next to the live one: `~/.omniapp/scheduler-state.duplicate.db`. It is a copy of the live DB with the adaptation applied.

On every run, `dev-restart.bat` checks whether that duplicate exists. If it does, it compares the **date of the last `history_unit`** in each DB (via `scripts/internal/db_duplicate_check.py`). If the **live** DB's last history unit is newer than the duplicate's, it means the user has added history units since the duplicate was adapted — `dev-restart.bat` tells the user and asks whether to continue (Y/N). This is the cue that the DB should be re-adapted to the current development changes so those new history units are incorporated into the duplicate **before** running `dev-restart.bat` again. If no duplicate exists, the check is skipped.