# OmniApp Global Architecture

## 1. Architectural Pattern: MVI (Model-View-Intent)
OmniApp utilizes a strict unidirectional MVI architecture. This ensures predictable state management across complex, highly interactive UIs like the Task Scheduler.

* **Model (State):** Immutable data classes representing the absolute truth of the UI. State is held in shared ViewModels.
* **View (Compose):** Stateless, declarative Compose Multi-platform functions. The View observes the State and renders it. It has no decision-making power.
* **Intent (Action/Event):** User interactions (clicks, typing, keyboard shortcuts) are dispatched as Intents to the ViewModel. The ViewModel processes the Intent, executes business logic, and emits a new immutable State.

## 2. Kotlin Multi-platform (KMP) Structure
The codebase follows a "Shared UI, Native Shell" philosophy.

* `shared/commonMain`: Contains 95% of the code. This includes the Domain logic, local database interactions, MVI ViewModels, and the Compose Multi-platform UI.
* `shared/[platform]Main`: Contains `expect`/`actual` implementations for platform-specific APIs (e.g., file system paths, platform-specific thread dispatchers, or lifecycle hooks).
* `[Platform]App`: The lightweight entry points. 
  * `desktopApp`: The primary JVM execution environment.
  * `androidApp`: The Android wrapper (MainActivity).
  * `iosApp`: The SwiftUI wrapper (hosting the Compose UIViewController).
  * `webApp`: The WASM/JS entry points.

## 3. Desktop-First Execution
OmniApp prioritizes Desktop interactions first. 
* Mouse events (hover, drag, right-click) and keyboard modifiers (`Ctrl`, `Shift`, `Tab`) are handled via Compose Multi-platform's `PointerEvent` and `KeyEvent` APIs in `commonMain`.
* When porting to touch interfaces (Android/iOS), adapter logic maps touch gestures (long-press, swipe) to the underlying MVI intents originally designed for mouse/keyboard.

## 4. Local Persistence & Offline-First
OmniApp is entirely offline-capable.
* **Framework:** Local database operations utilize a multi-platform SQL wrapper (SQLDelight); the desktop target uses the SQLite JDBC driver, web targets fall back to browser local-storage.
* **Load + debounced save:** State is loaded from the database once at startup; while running, the in-memory MVI State is the single source of truth. Every mutation updates that State immediately and is written back to the database on a small debounce (the store is not a reactive `Flow` source — the ViewModel does not collect DB queries live).
* **Continuous Sync:** All mutations (including Undo/Redo history units, stored one row per unit) are saved to the local database on that debounce, with a flush on close, to minimize data loss on unexpected exits.

## 5. History Architecture (Undo/Redo Engine)
The Undo/Redo mechanism operates on **Delta-based `HistoryUnits`** kept in one shared, persisted timeline (see §4 and `docs/PRD_TaskScheduler.md` §6 for the authoritative spec).
* Instead of saving full state snapshots (which would cause memory bloat with an infinite tree), the engine stores `HistoryUnits`.
* A `HistoryUnit` carries a `Delta` that is applied to move the state forward/back and inverted for the opposite direction, so the same unit serves both undo and redo.
* The timeline is **grouped into categories** (Edit Mode, Selection, Calendar, Main "the rest", Window-nav), **each with its own pointer** that walks only its own units — there is no single global pointer.
  * `Ctrl + Z` / `Ctrl + Y` walk the category of the **currently focused window** (the tree's edits, or the focused floating window's — e.g. the calendar's).
  * `Alt + Left` / `Alt + Right` walk the **selection-state** changes (task tree only).
  * A new mutation immediately discards the `HistoryUnits` ahead of that category's pointer (branch truncation).

## 6. Testing Strategy (TDD)
Behavior-Driven Development (BDD) and Test-Driven Development (TDD) are strictly enforced, focusing entirely on state mechanics.

* **ViewModel State Testing (Primary):** No UI code is merged without passing tests for the MVI Intent-to-State transitions. 
* **Scope:** Tests must validate selection mechanics, tree nested logic, and history delta generation entirely in memory using Kotlin's `runTest` API.
* **Target Execution:** Core tests reside in `shared/commonTest`. These same tests are executed across all platform targets (JVM, iOS Native, Wasm/JS) via Gradle to guarantee the state engine compiles and runs flawlessly on every architecture.
* **UI Testing (Out of Scope for v0.1.0):** Compose Multi-platform UI testing (e.g., verifying pixel-perfect rendering or semantic nodes) is deferred. The current mandate is to test the *State*, allowing the UI to remain a simple, stateless reflection of that data.

## 7. Debug Tooling
Debug tooling exists to *exercise the real app under controlled conditions*, never to replace its logic. **Mandate:** any debug control that simulates an event (time passing, a device sleep, etc.) must drive the **same Intents and code paths** the production app uses for that event — it may differ only in the *source* of the trigger; downstream handling must be the production logic, shared, not copied. Controls are gated behind compile-time flags (`DebugFlags`) so they are dead-code-eliminated from release builds.

This is currently realized only by the Task Scheduler's time-simulation panel; its behaviour is specified in `docs/PRD_TaskScheduler.md` (§16).