# OmniApp Global Architecture

## 1. Architectural Pattern: MVI (Model-View-Intent)
OmniApp utilizes a strict unidirectional MVI architecture. This ensures predictable state management across complex, highly interactive UIs like the Task Scheduler.

* **Model (State):** Immutable data classes representing the absolute truth of the UI. State is held in shared ViewModels.
* **View (Compose):** Stateless, declarative Compose Multiplatform functions. The View observes the State and renders it. It has no decision-making power.
* **Intent (Action/Event):** User interactions (clicks, typing, keyboard shortcuts) are dispatched as Intents to the ViewModel. The ViewModel processes the Intent, executes business logic, and emits a new immutable State.

## 2. Kotlin Multiplatform (KMP) Structure
The codebase follows a "Shared UI, Native Shell" philosophy.

* `shared/commonMain`: Contains 95% of the code. This includes the Domain logic, local database interactions, MVI ViewModels, and the Compose Multiplatform UI.
* `shared/[platform]Main`: Contains `expect`/`actual` implementations for platform-specific APIs (e.g., file system paths, platform-specific thread dispatchers, or lifecycle hooks).
* `[Platform]App`: The lightweight entry points. 
  * `desktopApp`: The primary JVM execution environment.
  * `androidApp`: The Android wrapper (MainActivity).
  * `iosApp`: The SwiftUI wrapper (hosting the Compose UIViewController).
  * `webApp`: The WASM/JS entry points.

## 3. Desktop-First Execution
OmniApp prioritizes Desktop interactions first. 
* Mouse events (hover, drag, right-click) and keyboard modifiers (`Ctrl`, `Shift`, `Tab`) are handled via Compose Multiplatform's `PointerEvent` and `KeyEvent` APIs in `commonMain`.
* When porting to touch interfaces (Android/iOS), adapter logic maps touch gestures (long-press, swipe) to the underlying MVI intents originally designed for mouse/keyboard.

## 4. Local Persistence & Offline-First
OmniApp is entirely offline-capable.
* **Framework:** Local database operations utilize a multiplatform SQL wrapper (e.g., SQLDelight).
* **Reactivity:** Database queries return asynchronous flows (via Kotlin Coroutines/Flow). The ViewModels collect these flows to maintain real-time sync between the DB and the UI State.
* **Continuous Sync:** All mutations in the state (including Undo/Redo history units) are immediately serialized and saved to the local database with a small debounce, ensuring zero data loss on unexpected exits.

## 5. History Architecture (Undo/Redo Engine)
The Undo/Redo mechanism operates on a **Delta-based History Graph** managed globally.
* Instead of saving full state snapshots (which would cause memory bloat with an infinite tree), the engine stores `HistoryUnits`.
* A `HistoryUnit` contains the inverse operation (Delta) required to undo a specific Intent.
* The state holds a `historyPointer`. 
  * `Ctrl + Z` moves the pointer backward, applying the inverse Delta.
  * `Ctrl + Y` moves the pointer forward, applying the forward Delta.
  * New Intents immediately clear all `HistoryUnits` ahead of the current pointer.

## 6. Testing Strategy (TDD)
Behavior-Driven Development (BDD) and Test-Driven Development (TDD) are strictly enforced, focusing entirely on state mechanics.

* **ViewModel State Testing (Primary):** No UI code is merged without passing tests for the MVI Intent-to-State transitions. 
* **Scope:** Tests must validate selection mechanics, tree nested logic, and history delta generation entirely in memory using Kotlin's `runTest` API.
* **Target Execution:** Core tests reside in `shared/commonTest`. These same tests are executed across all platform targets (JVM, iOS Native, Wasm/JS) via Gradle to guarantee the state engine compiles and runs flawlessly on every architecture.
* **UI Testing (Out of Scope for v0.1.0):** Compose Multiplatform UI testing (e.g., verifying pixel-perfect rendering or semantic nodes) is deferred. The current mandate is to test the *State*, allowing the UI to remain a simple, stateless reflection of that data.