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
## 8. Cross-Device Sync, Live Activity & Push Delivery (pg_cron heartbeat model)
Cross-device sync is **offline-first**: the local SQLite database (§4) stays the source of truth and the server holds a mirror. The backend is **Supabase** (Postgres + Auth + Realtime + Edge Functions); the client talks to PostgREST / GoTrue over HTTPS for its writes, holds **one** Realtime WebSocket (the snapshot auto-pull) while signed in, and writes an activity **heartbeat row** while in use. This supersedes both the earlier "five sync moments" REST-only design and the intermediate **Realtime-presence + external Fly.io `/listener`** model (removed 2026-07-23): pg_cron cannot read Realtime Presence, but it can poll a `device_heartbeat` table — which is what the listener was doing internally anyway (a heartbeat + a 10-s tick), so the always-on worker was dropped in favour of a `tick_pause_cues()` cron. Retired machinery from the older designs (`derive_pauses` RPC, `device_presence`, last-phone-only cue) stays retired.

* **A heartbeat table is the activity signal (polled by pg_cron).** An active client (signed in + active per its platform rule) UPSERTs a `device_heartbeat` row every ~10 s (`DeviceHeartbeatPublisher`) carrying `{ device_id, kind, next_break_start_ms, next_break_len_ms, next_break_end_ms }`; the server stamps `beat_at` (trigger). A clean lock/inactive transition flips the row `closed = true` (the explicit "device locked" signal, detected next tick); a dirty lock lets `beat_at` go stale, caught by a ~25 s window. On the free plan this is DB write traffic (not Realtime messages, not Edge invocations), fine for a handful of devices. Platform rules for "in use":
  * **Desktop:** the app is running, signed in, and the screen is interactive (observed activity heartbeat, local only).
  * **Android:** a **foreground service** (started at boot by `BootReceiver`) keeps the engine alive headlessly; at **first startup** the app asks the user **once** to disable battery optimization and enable autostart. Lock/unlock events gate whether the device counts as active (a lock flips the heartbeat `closed`). *(Implementation status: the current build also uses `AndroidForegroundTracker` one-minute leases for the resume poke.)*
* **Snapshot sync is bidirectional and automatic (whole-document LWW).** All three trigger paths funnel through the one mutex-guarded `SchedulerSyncEngine.reconcile()` (push-if-dirty / pull-if-remote-newer, versioned by `revision`):
  * **Local → remote auto-push.** An authoritative edit persists to SQLite on the ~400 ms save debounce and, when it actually moves `SchedulerStateCodec.syncFingerprint`, `markDirty()`s the engine **and** emits on a `MutableSharedFlow` that `TaskSchedulerViewModel` collects with a **500 ms `debounce`** → `reconcile()` (pushes only the pending change). Derived/tick reschedules leave the fingerprint unchanged and so never enqueue a push (reconstructibility rule).
  * **Remote → local auto-pull.** While signed in, `RealtimeSnapshotSubscriber` holds a Supabase Realtime **`postgres_changes`** subscription on this account's `scheduler_snapshot` row (the **only** live WebSocket the client holds now, authorized by the user JWT through the row's RLS). A server-side change (a peer pushed) **pokes `reconcile()`**, which pulls; the subscriber never trusts the event body. Requires the table to be in the `supabase_realtime` publication with `replica identity full` (migration `20260722000000`).
  * **Manual Sync button** (`syncNow()`) remains as a force-now fallback; sign-in points the subscription at the account and sign-out clears it — neither forces an immediate whole-document reconcile.
  * **Echo prevention** is layered: the `revision` guard no-ops a device's own just-pushed change (remote revision already equals `lastKnownRevision`), a pulled snapshot is applied straight to `_state` (not via the edit path) and resets `lastSyncedFingerprint` so it never enqueues a push-back, and the 500 ms debounce coalesces bursts.
  * The wire payload is still the **authoritative projection** (reconstructibility rule, `CLAUDE.md`): regenerated panels and local-only view state are stripped (`SyncPayloadTest`); a puller regenerates the schedule and keeps its own view state. Covered by `BidirectionalSyncTest` (VM wiring) + `SchedulerSyncEngineTest` (reconcile semantics). *(Live-verification status: the `postgres_changes` WebSocket path shares the presence path's "needs on-device confirmation against a real project" caveat.)*
* **Device connection history → derived no-screen periods.** A device's active sessions (its connection windows; `device_active_session`, with a `kind` column naming the device) are physical facts, so they are authoritative — pushed/pulled per-row inside the same button reconcile (`SchedulerSyncEngine.syncActiveSessions`). Over the pulled union of every device's windows each client derives, over the last 168 h, the **no-screen periods** — the complement of the union (PRD §8/§15): decorative calendar panels, the input to the screen-break seeding, carved around the nightly Sleep windows. **Manually added no-screen periods are user data and are never replaced by the derivation.** A **past no-screen period that covered a scheduled task** banks no record — the stretch becomes a **past inactivity period** (a real panel; the app "must not assume anything", PRD §9). There is no live peer-activity channel to other clients; peers learn of activity at the next Sync (Sync-bounded staleness).
* **Smooth in-app reschedule while active.** If devices are still active (fresh heartbeats) when a screen break was scheduled to start, no cue is fired and nothing is pushed: each active app **smoothly re-flows its own schedule** — the break slides on the now-line (PRD §15 now-line clamp) and the §9 fill re-derives locally.
* **A pg_cron tick fires the break cue (no external worker).** `tick_pause_cues()` runs every ~10 s (scheduled in `supabase/pause-cue-setup.sql`; table + function in migration `20260723000000`). When an account has **no** fresh, non-closed heartbeat AND is **not** sleeping (`account_state`), it computes the pause end **server-side** from the published break window — `d = max(idleSince, poseStart) + poseLength`, `idleSince = max(beat_at)` (e.g. four 15-minute breaks pending ⇒ the message fires 15 minutes after the account went idle) — and invokes the `pause-cue` Edge Function via `omni_edge_push`, which sends a **push (FCM/APNs) to all the account's phones** (`device_id:'*'`). It pushes **early** (as soon as idle is detected, recorded in `pause_cue_schedule` so it does not re-push), because the ~10 s cron cannot hit a precise lead and the phone arms its own exact local alarm at `due_at`; a device becoming active again cancels a still-future pushed cue. The phone schedules the OS alarm and speaks (`onPauseCuePush` / `onPauseCueFire`).
* **Sleep/Work toggle → `account_state`.** The left-menu toggle (`SchedulerState.sleepingUntilMillis`, `SetSleepMode`) writes the account's mode to `account_state` immediately (`publishAccountState`) so `tick_pause_cues()` suppresses the cue while the user is deliberately away; it persists across restart until the scheduled wake passes (`SchedulerEngine.resolveSleepModeOnStartup`).
* **Event-driven + heartbeat traffic**: event-driven REST is the Sync-button reconcile, the Sleep/Work `account_state` write, the phone's push-token registration (startup/foreground), and the login force-logout check (`account_logout`) inside a reconcile. The one steady-state timer traffic is the ~10 s `device_heartbeat` UPSERT while active (a DB write). The Edge-Function push (server→phone) is the only server→device channel besides the snapshot WebSocket.
* **Verification status:** the client and the Supabase schema compile and the pure pieces are unit-tested (`RealtimePhoenixTest`, `SleepModeTest`, `PauseCueGatewayTest`); the **live** path (client heartbeat UPSERT ↔ `tick_pause_cues()` cron ↔ Edge ↔ phone) still needs on-device confirmation against a real project — see `docs/PAUSE_CUE_DELIVERY.md` (the runbook for that verification).
