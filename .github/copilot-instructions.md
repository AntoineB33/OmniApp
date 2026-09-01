# Copilot safety instructions for OmniApp

## Do not touch the live release app

- The deployed Windows release app is the user's live runtime and uses `%USERPROFILE%\.omniapp-release`.
- Never run a dev or debug desktop build against that same state dir. Doing so can overwrite the live local SQLite DB and can crash the live app or corrupt its state.
- Prefer a throwaway state dir such as `%USERPROFILE%\.omniapp-dev`, `%USERPROFILE%\.omniapp-guest`, or a dedicated per-account state dir for agent-driven testing.
- When launching the desktop app locally for testing, always pass `-Pomniapp.stateDir=...` or use the account scripts that target a separate state dir.
- Do not kill `org.example.project.exe` or re-run `scripts\account3-deploy-windows.bat` unless the user explicitly asks for a redeploy.
- Treat `account3-deploy-windows.bat` as a production-like deployment path that must stay isolated from exploratory runs.

## Safe launcher patterns

- Safe local test run example:
  - `gradlew.bat :desktopApp:run -Pomniapp.stateDir=%USERPROFILE%\.omniapp-dev`
- Safe account launch example:
  - `scripts\account2-open.bat`
- Unsafe pattern to avoid:
  - running the dev desktop app without `-Pomniapp.stateDir` while the release app is already active

## Agent workflow

- If the user has a deployed app running, do not use that app's state for experiments.
- Prefer `:shared:check` and `:shared:jvmTest` for code verification before anything GUI-related.
- If UI validation is needed, run the desktop app in a separate state dir, not the live release state.
