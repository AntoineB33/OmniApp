# OmniApp (v1.6.0)

OmniApp is a versatile, cross-platform productivity application designed to provide a seamless, unified experience across Windows, macOS, Linux, Android, iOS, and Web. Built utilizing Kotlin Multi-platform (KMP) and Compose Multi-platform, OmniApp guarantees "one code for all platforms" wherever feasible.

## 🌟 Core Philosophy

- **Desktop-First Development:** Core operations and complex UI interactions (like the infinite hierarchical task tree) are validated and perfected on Windows Desktop before being tailored for touch or web interfaces.
- **Test-Driven Reliability:** Strict adherence to TDD. All state changes, selection rules, and history mechanics are validated via unit tests against the ViewModels/State holders _before_ any UI implementation.
- **Unified Navigation:** A persistent top-left dropdown menu drives seamless context switching between application modules (e.g., Task Scheduler).

## 🛠 Tech Stack

- **Language:** Kotlin
- **Framework:** Kotlin Multi-platform (KMP)
- **UI:** Compose Multi-platform
- **Architecture:** MVI (Model-View-Intent)
- **Persistence:** SQLDelight

## 📂 Project Structure

This is a KMP project targeting Android, iOS, Web, and Desktop (JVM).

- `/iosApp`: Contains the iOS application entry point and SwiftUI wrapper code.
- `/shared/src`: Core shared logic and UI for the Compose Multi-platform applications.
  - `commonMain`: Truly platform-agnostic code (Domain, Data, MVI State Holders, and shared UI).
  - `iosMain` / `jvmMain` / `androidMain` / `wasmJsMain`: Platform-specific integrations and actualizations (e.g., specific file system APIs, platform crypto).
- `/supabase`: The server half of cross-device sync — SQL migrations (applied via the CLI, never pasted by hand), the two pause-cue Edge Functions (`pause-cue`, called by a device reporting its own clean lock; `pause-cue-cron`, called by the cron when a device died without reporting), and `pause-cue-setup.sql` (the `tick_pause_cues()` pg_cron job that fires the pause-end voice cue over a `device_heartbeat` table — see `docs/PAUSE_CUE_DELIVERY.md`).

## 🚀 Getting Started

Use the run configurations provided by the run widget in your IDE's toolbar, or utilize the following Gradle commands:

### Running the apps

- **Desktop app (Primary Target):**
  - Standard run: `./gradlew :desktopApp:run`
  - Hot reload: `./gradlew :desktopApp:hotRun --auto`
- **Android app:** `./gradlew :androidApp:assembleDebug`
- **Web app:**
  - Wasm target (faster, modern browsers): `./gradlew :webApp:wasmJsBrowserDevelopmentRun`
  - JS target (slower, older browsers): `./gradlew :webApp:jsBrowserDevelopmentRun`
- **iOS app:** Open the `iosApp` directory in Xcode and run it from there.

### Running Core Logic Tests

Ensure state holders, MVI intents, and the Undo/Redo engine are fully tested before touching the Compose UI. The following commands execute these shared business logic tests across different platform targets to ensure identical behavior across JVM, Native, and JS/Wasm compilers. **Note: These are headless state tests, not UI tests.**

- **Desktop (JVM) target tests:** `./gradlew :shared:jvmTest`
- **Android target tests:** `./gradlew :shared:testAndroidHostTest`
- **Web targets tests:**
  - Wasm: `./gradlew :shared:wasmJsTest`
  - JS: `./gradlew :shared:jsTest`
- **iOS (Native) target tests:** `./gradlew :shared:iosSimulatorArm64Test`

### Development & release scripts (Windows)

> **Accounts:** the app is **always connected to an account** (PRD §5). Started normally it creates a **guest account** — a real account with no email/password, so only the device that created it can use it — and the "Create account" button turns *that* account into a normal one by giving it credentials, keeping all its data. Signing out lands on a fresh guest account; signing in to another account switches which account's data is shown and deletes nothing. The scripts below skip guest creation: they open the app already signed in to the account they name. Requires **anonymous sign-ins enabled** on the Supabase project (`enable_anonymous_sign_ins` in `supabase/config.toml` / the Dashboard toggle).

Helper scripts in `scripts/` (Windows batch / PowerShell) open the app already signed in to a Supabase account, empty an account's data, or deploy the release build. Each account uses its **own isolated state dir** (a separate local SQLite DB) so accounts never share data — set by `-Pomniapp.stateDir` / `OMNIAPP_STATE_DIR`; running with no override uses the default `%USERPROFILE%\.omniapp`:

| Account | State dir | Used by |
| --- | --- | --- |
| Account 1 | `%USERPROFILE%\.omniapp-acc1` | `account1-empty-and-open.bat` |
| Account 2 | `%USERPROFILE%\.omniapp-acc2` | `account2-open.bat` / `account2-empty.bat` |
| Account 3 (release) | `%USERPROFILE%\.omniapp-release` | `account3-deploy-windows.bat` / `account3-deploy-android.bat` |

- **`account1-empty-and-open.bat`** — three things, in order: signs **every** app on account 1 out server-side (the `account_logout` marker, so a still-running peer can't re-seed the data), empties account 1's data (**local DB and the remote Supabase rows**), then launches `:desktopApp:run` signed in as account 1.
- **`account2-open.bat`** — launches signed in as account 2, **preserving** its data.
- **`account2-empty.bat`** — empties account 2 (local + remote); does **not** relaunch.
- **`account1-deploy-android.bat`** / **`account2-deploy-android.bat`** — build a **debug** APK, wipe the phone's local app data (uninstall + reinstall over adb — not `pm clear`, which some OEM ROMs deny), and launch it auto-signed-in as account 1 / account 2. Remote data is preserved.
- **`account3-deploy-windows.bat`** — builds a self-contained release app image (bundled JRE, via `:desktopApp:createDistributable`), installs it outside the project tree, registers it to start at Windows login, and auto-signs-in as account 3 against the release DB (left untouched by updates). Time simulation is off in this build.
- **`account3-deploy-android.bat`** — builds, signs, installs, and launches the release APK auto-signed-in as account 3 (a BootReceiver keeps the scheduler running across reboots).
- **`deploy-supabase.bat`** — applies `supabase/migrations/`, deploys **both** pause-cue Edge Functions (`pause-cue` for a device's own clean lock, `pause-cue-cron` for the cron's dirty-kill backstop), and runs `supabase/pause-cue-setup.sql`. Idempotent; re-run after any schema edit (needs a one-time `supabase login` + `supabase link`).
- **`collect-diagnostics.bat [stateDir]`** — prints the merged cross-device diagnostics timeline (script markers + desktop log + Android log pulled over adb). The first tool to reach for when a calendar/sync anomaly shows up.
- **`update-supabase-cli.bat`** — updates the standalone Supabase CLI binary in place (note: an npm-global `supabase` may shadow it on `PATH` — see CLAUDE.md).
- **`setup-piper.ps1`** — installs the local Piper neural text-to-speech voice used for the spoken screen-break cues; without it the app falls back to the system speech voice.

Credentials live in **`scripts/accounts.env`** (gitignored — copy `scripts/accounts.env.example` and fill it in). One-time setup (create the accounts, empty-script RLS) is described in `CLAUDE.md` under "Account scripts".

Internal helpers that are **not run by hand** live in `scripts/internal/`:
- **`account_db_admin.py`** — creates accounts (`signup`), signs an account's apps out remotely (`logout`), and empties an account's data (local DB + the remote Supabase rows); driven by the account scripts.
- **`deploy-android-debug.bat`** — the shared body of the two debug Android deploy scripts.
- **`merge_diagnostics.py`** — merges the desktop + Android diagnostics logs into the one timeline `collect-diagnostics.bat` prints.
- **`kill-app-by-match.bat`** — stops only **one** account's running instance (matched by its state dir) so the other account's app — and the Gradle daemon — keep running.
- **`load-accounts-env.bat`** — parses `accounts.env` into the calling script's environment.
- **`release-launch-acc3.bat`** — the small launcher the Windows Startup shortcut points at: it sets the release DB and account-3 credentials, then starts the installed release app. Deployed to the install root by `account3-deploy-windows.bat`.

---

_For more information on Kotlin Multi-platform, visit the [official documentation](https://www.jetbrains.com/help/kotlin-multiplatform-dev/get-started.html)._
