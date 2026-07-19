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
* **Manual testing:** Behaviour the automated suite cannot reach — real persistence, cross-device sync convergence, presence, OS-scheduled push cues, the background service, per-account isolation — has a step-by-step release checklist in `docs/MANUAL_TESTING.md` (keyed to the `scripts/account*` entry points; subsystem detail delegated to `docs/PAUSE_CUE_DELIVERY.md`).

## 7. Debug Tooling
Debug tooling exists to *exercise the real app under controlled conditions*, never to replace its logic. **Mandate:** any debug control that simulates an event (time passing, a device sleep, etc.) must drive the **same Intents and code paths** the production app uses for that event — it may differ only in the *source* of the trigger; downstream handling must be the production logic, shared, not copied. Controls are gated by `DebugFlags`, set once at startup from the platform entry point and **off by default**, so a packaged/release build never shows the debug tooling. The desktop dev `run` task enables it via the `omniapp.timeSim` system property; `createDistributable` (release) does not, so it ships off.

This is currently realized only by the Task Scheduler's time-simulation panel; its behaviour is specified in `docs/PRD_TaskScheduler.md` (§16).
## 8. Cross-Device Sync, Live Presence & Push Delivery (1.6.0 WebSocket model)
Cross-device sync is **offline-first**: the local SQLite database (§4) stays the source of truth and the server holds a mirror. The backend is **Supabase** (Postgres + Auth + Realtime + Edge Functions); the client talks to PostgREST / GoTrue over HTTPS for the rare event-driven writes, and — the 1.6.0 pivot — **holds a WebSocket connection to the server whenever the device is in use**. This supersedes the earlier "five sync moments" REST-only design (login/logout reconciles, the 10-s change throttle, the deferred pause-cue burst, the inactivity finalize) and its server machinery (`derive_pauses` RPC, `pause_cue_schedule` + `tick_pause_cues` cron, `device_presence`, last-phone-only cue): all retired.

* **The WebSocket is the activity signal.** An active client (signed in + active per its platform rule) holds a Phoenix-channel WebSocket on Supabase Realtime (`realtime:presence:<user_id>`, `RealtimePresenceClient`) and publishes `{ device_id, kind, next_break_end_ms }` as its presence state. The connection itself is the liveness signal — there is no per-beat REST traffic and no polling of any kind. Platform rules for "in use":
  * **Desktop:** the app is running, signed in, and the screen is interactive (observed activity heartbeat, local only).
  * **Android:** a **foreground service** (started at boot by `BootReceiver`) keeps the engine alive headlessly; at **first startup** the app asks the user **once** to disable battery optimization and enable autostart. The service listens to the **lock/unlock events** and holds the WebSocket **only while the phone is unlocked** — locking the phone drops the connection, unlocking re-establishes it. *(Implementation status: the current build gates phone activity on the app being foregrounded — `AndroidForegroundTracker` one-minute leases; the lock/unlock-gated always-on service is the specified target of this revision.)*
* **Snapshot sync is button-only.** `TaskSchedulerViewModel.syncNow()` (the manual Sync button) is the only thing that reconciles `scheduler_snapshot` (LWW push-if-dirty / pull). Sign-in does not pull and sign-out does not push. Local edits persist to SQLite on the ~400 ms debounce and `markDirty()` the engine; an authoritative change moves `SchedulerStateCodec.syncFingerprint` but never pushes on its own. The wire payload is the **authoritative projection** (reconstructibility rule, `CLAUDE.md`): regenerated panels and local-only view state are stripped (`SyncPayloadTest`); a puller regenerates the schedule and keeps its own view state.
* **Device connection history → derived no-screen periods.** A device's active sessions (its connection windows; `device_active_session`, with a `kind` column naming the device) are physical facts, so they are authoritative — pushed/pulled per-row inside the same button reconcile (`SchedulerSyncEngine.syncActiveSessions`). Over the pulled union of every device's windows each client derives, over the last 168 h, the **no-screen periods** — the complement of the union (PRD §8/§15): decorative calendar panels, the input to the screen-break seeding, carved around the nightly Sleep windows. **Manually added no-screen periods are user data and are never replaced by the derivation.** A **past no-screen period that covered a scheduled task** banks no record — the stretch becomes a **past inactivity period** (a real panel; the app "must not assume anything", PRD §9). Live peer activity between Sync presses reaches a device only through the listener/push channel, never the calendar (Sync-bounded staleness).
* **Smooth in-app reschedule while connected.** If devices still hold open WebSockets when a screen break was scheduled to start, no cue is fired and nothing is pushed: each connected app **smoothly re-flows its own schedule** — the break slides on the now-line (PRD §15 now-line clamp) and the §9 fill re-derives locally.
* **The external listener fires the break cue.** pg_cron / an Edge Function cannot read Realtime Presence, so a small always-on Node worker (`/listener`) watches every account's presence channel as the service role. When an account has **no** connected device AND is **not** sleeping (`account_state`), it checks the server DB for a **screen break waiting at the current time**; if one is waiting, it invokes the `pause-cue` Edge Function, which sends a **push notification (FCM/APNs) to all the account's phones** telling them to schedule the exact local vocal message for the **end of the break** — the waiting break's length after the start of the all-disconnected window (e.g. four 15-minute breaks pending since the last one taken ⇒ the message fires 15 minutes after every device disconnected). The phone schedules an OS alarm/local notification and speaks (`onPauseCuePush` / `onPauseCueFire`); a reconnection before the fire suppresses it. *(Implementation status: the current listener pushes ~1 s before the presence-published `next_break_end_ms`; computing the waiting break server-side from the synced schedule, and fanning out to all phones rather than the registered one, are the specified targets.)*
* **Sleep/Work toggle → `account_state`.** The left-menu toggle (`SchedulerState.sleepingUntilMillis`, `SetSleepMode`) writes the account's mode to `account_state` immediately (`publishAccountState`) so the listener suppresses the cue while the user is deliberately away; it persists across restart until the scheduled wake passes (`SchedulerEngine.resolveSleepModeOnStartup`).
* **Remaining event-driven REST** (tiny, never timer-driven): the Sync-button reconcile, the Sleep/Work `account_state` write, the phone's push-token registration (startup/foreground), and the login force-logout check (`account_logout`) inside a reconcile. The Edge-Function push (server→phone) is the only server→device channel besides the WebSocket itself.
* **Verification status:** the client, the Supabase schema and the listener compile and the pure pieces are unit-tested (`RealtimePhoenixTest`, `SleepModeTest`, `PauseCueGatewayTest`); the **live** path (client WS ↔ Realtime ↔ listener ↔ Edge ↔ phone) still needs on-device confirmation against a real project — see `/listener/README.md` and `docs/PAUSE_CUE_DELIVERY.md` (parts of that runbook still describe the retired cron/last-phone machinery).
