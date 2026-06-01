Kotlin Multiplatform (KMP) project targeting Windows Desktop first.

## Commands
- `./gradlew :shared:check` — verify syntax/compile errors after editing the `shared` module
- `./gradlew :desktopApp:run` — run the desktop app to verify UI/desktop changes

## Rules
- Do not use Android-specific CLI tools to render previews.
- Do not assume `expect`/`actual` declarations work until `:shared:check` passes.
- After any change to shared Kotlin logic, run `:shared:check` before reporting it as done.