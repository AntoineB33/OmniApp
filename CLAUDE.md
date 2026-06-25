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