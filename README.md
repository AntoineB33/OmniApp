# OmniApp (v0.1.0)

OmniApp is a versatile, cross-platform productivity application designed to provide a seamless, unified experience across Windows, macOS, Linux, Android, iOS, and Web. Built utilizing Kotlin Multiplatform (KMP) and Compose Multiplatform, OmniApp guarantees "one code for all platforms" wherever feasible.

## 🌟 Core Philosophy

- **Desktop-First Development:** Core operations and complex UI interactions (like the infinite hierarchical task tree) are validated and perfected on Windows Desktop before being tailored for touch or web interfaces.
- **Test-Driven Reliability:** Strict adherence to TDD. All state changes, selection rules, and history mechanics are validated via unit tests against the ViewModels/State holders _before_ any UI implementation.
- **Unified Navigation:** A persistent top-right dropdown menu drives seamless context switching between application modules (e.g., Task Scheduler).

## 🛠 Tech Stack

- **Language:** Kotlin
- **Framework:** Kotlin Multiplatform (KMP)
- **UI:** Compose Multiplatform
- **Architecture:** MVI (Model-View-Intent)
- **Persistence:** SQLDelight

## 📂 Project Structure

This is a KMP project targeting Android, iOS, Web, and Desktop (JVM).

- `/iosApp`: Contains the iOS application entry point and SwiftUI wrapper code.
- `/shared/src`: Core shared logic and UI for the Compose Multiplatform applications.
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

---

_For more information on Kotlin Multiplatform, visit the [official documentation](https://www.jetbrains.com/help/kotlin-multiplatform-dev/get-started.html)._
