# OmniApp (v0.1.0)

OmniApp is a versatile, cross-platform productivity application designed to provide a seamless, unified experience across Windows, macOS, Linux, Android, iOS, and Web. Built utilizing Kotlin Multi-platform (KMP) and Compose Multi-platform, OmniApp guarantees "one code for all platforms" wherever feasible.

## 🌟 Core Philosophy

- **Desktop-First Development:** Core operations and complex UI interactions (like the infinite hierarchical task tree) are validated and perfected on Windows Desktop before being tailored for touch or web interfaces.
- **Test-Driven Reliability:** Strict adherence to TDD. All state changes, selection rules, and history mechanics are validated via unit tests against the ViewModels/State holders _before_ any UI implementation.
- **Unified Navigation:** A persistent top-right dropdown menu drives seamless context switching between application modules (e.g., Task Scheduler).

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

Helper scripts in `scripts/` (Windows batch / PowerShell). They keep **three separate databases** so the release you use day-to-day is never disturbed by active development:

| Database | Path | Used by |
| --- | --- | --- |
| Release | `%USERPROFILE%\.omniapp-release` | the installed release |
| Debug | `%USERPROFILE%\.omniapp` | `dev-restart.bat` |
| Debug (emptied) | `%USERPROFILE%\.omniapp-reset` (wiped each run) | `dev-reset.bat` |

- **`dev-restart.bat`** — kills the running desktop app and relaunches `:desktopApp:run`, **preserving** the debug DB.
- **`dev-reset.bat`** — same, but launches against an isolated DB that is **wiped first**, for a clean-slate test run.
- **`release-deploy.bat`** — builds a self-contained release app image (bundled JRE, via `:desktopApp:createDistributable`), installs it to `%LOCALAPPDATA%\OmniApp` (outside the project, so active development can't affect the running release), registers it to start at Windows login, and launches it. Re-run any time to update the release from current code — the release DB is preserved. Time simulation is off in this build.
- **`setup-piper.ps1`** — installs the local Piper neural text-to-speech voice used for the spoken side-task cues; without it the app falls back to the system speech voice.

Internal helpers that are **not run by hand** live in `scripts/internal/`:
- **`release-launch.bat`** — the small launcher the Startup shortcut points at: it sets the release DB and starts the installed app. Deployed to the install root by `release-deploy.bat`.
- **`db_duplicate_check.py`** — compares the live debug DB against an adapted `scheduler-state.duplicate.db` by last history-unit date; called by `dev-restart.bat`.

---

_For more information on Kotlin Multi-platform, visit the [official documentation](https://www.jetbrains.com/help/kotlin-multiplatform-dev/get-started.html)._
