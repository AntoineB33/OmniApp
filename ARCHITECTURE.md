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
* **UI Testing:** Compose Multi-platform UI testing (e.g., verifying pixel-perfect rendering or semantic nodes) is deferred. The current mandate is to test the *State*, allowing the UI to remain a simple, stateless reflection of that data.

## 7. Debug Tooling
Debug tooling exists to *exercise the real app under controlled conditions*, never to replace its logic. **Mandate:** any debug control that simulates an event (time passing, a device sleep, etc.) must drive the **same Intents and code paths** the production app uses for that event — it may differ only in the *source* of the trigger; downstream handling must be the production logic, shared, not copied. Controls are gated by `DebugFlags`, set once at startup from the platform entry point and **off by default**, so a packaged/release build never shows the debug tooling. The desktop dev `run` task enables it via the `omniapp.timeSim` system property; `createDistributable` (release) does not, so it ships off.

This is currently realized only by the Task Scheduler's time-simulation panel; its behaviour is specified in `docs/PRD_TaskScheduler.md` (§16).

## 8. Cross-Device Sync & Push Delivery
Cross-device sync is **offline-first**: the local SQLite database (§4) stays the source of truth and the server holds a mirror. The backend is **Supabase** (Postgres + Auth + Edge Functions); the client talks to PostgREST / GoTrue directly over HTTPS. **There is never a WebSocket connection to the server** — another device's changes are pulled on demand (on app start, or via the manual "fetch from server" button), never streamed live over a socket. The only server→device channel is the Edge-Function push described below.

* **Server-sync debounce (1 minute) — implemented.** Distinct from the small *local-save* debounce of §4, every change that must update the **server** DB is pushed on a **1-minute** debounce (`scheduler/sync/ServerSyncThrottle`, wired in `TaskSchedulerViewModel`; the local SQLite write keeps its own small debounce, and `markDirty()` still fires immediately so a change is durable even before its push goes out). Batching and edge behaviour:
  * **Same-turn batching:** all changes made within one event-loop turn land in the *same* debounce iteration, because the push request is issued at the **end of the turn** (after the turn's state settles), never per-change.
  * **Idle leading edge:** the debounce interval is measured *from the previous push*, so a change arriving **≥ 1 minute after** the last push is sent **immediately**, while a burst inside the minute coalesces into one deferred push. Pulls (startup / manual "sync now" / focus) are *not* throttled — only the post-save push is.
* **Pause-end voice cue delivery (FCM / APNs) — server + schema scaffolded; native push is a follow-up.** The server half is `supabase/migrations/` (`device_push_token` / `account_last_phone` / `pause_cue_schedule` + the `tick_pause_cues()` cron and last-phone-change trigger) and the `supabase/functions/pause-cue/` Edge Function; the actual FCM/APNs send and each phone's push-token registration still need Firebase/Apple credentials and native SDK wiring. The end of a 5/15-minute pose (`docs/PRD_TaskScheduler.md` §15) is announced by a spoken cue on **a phone** — the **last phone that logged in to the account**. Because a backgrounded phone can't reliably run an in-app timer, the cue is delivered as an **OS-scheduled local notification**:
  * When a change on the **phone itself** moves the next pause-end cue, the phone schedules it locally (an **FCM** data message on Android / **APNs** on iOS) and cancels the previously scheduled one.
  * When the change originates on **another device** (e.g. the desktop), that device syncs to the server; **one minute before** the cue is due, a Supabase **Edge Function** pushes the last phone (FCM/APNs) to schedule the cue at the exact instant. The server **skips** this push when the pending next-cue schedule is itself due to a change *the phone* made (the phone has already scheduled it).
* **Last-phone tracking.** On app start a device calls the server to pull changes; when the caller is a **phone**, that call also registers it as the account's **last logged-in phone**. If the previous last-phone was a different device, the server sends *it* an Edge push to **cancel** its scheduled pause-end cue, so only one phone ever speaks.