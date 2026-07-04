# Pause-end voice cue delivery (1.6.0) — runbook

The end of a 5/15-minute pose is spoken by **one phone** — the *last phone that logged in to the account* —
delivered as an **OS-scheduled local notification** so it fires even when the app is killed. There is **never a
WebSocket**; the only server→device channel is the `pause-cue` Edge Function push. See ARCHITECTURE.md §8 and
PRD §15 for the design; this file is the operational runbook.

## What is already done vs. what remains

| Piece | Status |
| --- | --- |
| 1-min **server-sync debounce** (batch per event-loop turn, immediate when idle ≥1 min) | ✅ `scheduler/sync/ServerSyncThrottle`, wired in `TaskSchedulerViewModel` (`SERVER_SYNC_INTERVAL_MILLIS = 60_000`) |
| Supabase schema: `device_push_token` / `account_last_phone` / `pause_cue_schedule` | ✅ `supabase/migrations/20260703000000_init.sql` |
| `on_pause_cue_schedule_change` trigger — **immediate** push (cancel+reschedule) when a non-phone device moves the next pose-end (scenario #2), **skip when origin == last phone** | ✅ `supabase/migrations/20260704000000_pause_cue_immediate_push.sql` |
| `tick_pause_cues()` cron (push ~1 min before, **skip when origin == last phone**) — now a **backstop** to the immediate trigger | ✅ `20260703000000_init.sql` + `supabase/pause-cue-setup.sql` |
| `on_last_phone_change` trigger (cancel push to the **previous** phone) | ✅ `20260703000000_init.sql` |
| Edge Function real **FCM v1 / APNs HTTP/2** send | ✅ `supabase/functions/pause-cue/index.ts` (needs secrets) |
| Client writes `pause_cue_schedule` when the next pose-end changes | ✅ `SchedulerEngine.launchPauseCueSchedule` → `SchedulerSyncEngine.publishPauseCueSchedule` |
| Client claims `account_last_phone` on startup **and on every app-foreground** (phones only; scenario #3) | ✅ `SchedulerEngine.claimLastPhoneOnStartup` / `onAppForegrounded` (Android `MainActivity.onResume`, iOS `applicationDidBecomeActive`) |
| Client transport for `device_push_token` | ✅ `SchedulerSyncEngine.registerPushToken` (still needs a *native token* to feed it — steps 2/3) |
| Local-cue seam + push entry (`scheduleLocalPauseCue` / `onPauseCuePush` / `onPauseCueFire`) | ✅ shared seam + eligibility gate wired |
| Android: FCM receiver + AlarmManager local cue (wire the seam) | ✅ `PauseCueMessagingService` / `PauseCueAlarmReceiver` / `PauseCueScheduler`; `SchedulerHolder` wires the seam + `localPauseCueDelivery=true` |
| iOS: APNs registration + local notification cue (wire the seam) | 🟡 **Code written, UNVERIFIED** — `iosMain` actuals (`PauseCueLocal.ios` + `Voice`/`DeviceInfo`/`SystemNotifier`/`SleepHistory`) + `IosPushBridge` + Swift `AppDelegate`; needs a **Mac build** to compile the Kotlin/Native interop. The former `CalendarUi.kt` commonMain portability blocker is **fixed** (`sortedSetOf` → `mutableSetOf().sorted()`; `:shared:compileCommonMainKotlinMetadata` green), so common code no longer blocks the iOS compile. See step 3. |
| Firebase project + `google-services.json` + APNs key + secrets deployed | ⛔ **TODO — steps 4/5 (your credentials)** |

Two design decisions baked into the steps below (change them if you disagree):
1. **Presence gate is kept.** The push/alarm is the *delivery*; the phone still runs the existing
   screen-off + no-active-peer check (`SchedulerEngine.poseFinishEligible`) before it actually speaks, so a
   user at a screen is never told. The last-phone/FCM path only decides *which* phone gets the alarm.
2. **Credentials assumed absent.** Code is written to be inert until you add Firebase/Apple secrets.

---

## Step 1 — Shared Kotlin: transport + schedule/claim wiring ✅ DONE

Implemented in `shared/commonMain` + tests in `shared/jvmTest` (`./gradlew :shared:jvmTest` green):
- `RemoteSnapshotClient`: `upsertPauseCueSchedule` / `deletePauseCueSchedule` / `claimLastPhone` /
  `upsertPushToken` (+ payloads). Covered by `PauseCueGatewayTest`.
- `PauseCueGateway` (new) implemented by `SchedulerSyncEngine`; exposed as `TaskSchedulerViewModel.pauseCue`.
- `SchedulerEngine`:
  - `launchPauseCueSchedule()` — on every change of the next rest-pose end (`nextRestPoseEndMillis`, covered by
    `PauseCueScheduleTest`) publishes `pause_cue_schedule` (origin = self) on any device, clears it when no pose
    remains, and on a **phone** also calls the local-cue seam. This is requirements #2/#5.
  - `claimLastPhoneOnStartup()` — phones claim `account_last_phone` at launch (requirement #6).
  - `scheduleLocalPauseCue: (Long?) -> Unit` constructor seam (schedule at instant / cancel on null) and
    `onPauseCuePush(action, dueAtMillis)` public entry — **both default to no-op**; steps 2/3 supply the real
    AlarmManager / UNUserNotificationCenter bodies and pass them at construction (`App.kt` / `SchedulerHolder.kt`).

**The only remaining shared decision:** the fire handler that the native seam schedules must call
`SchedulerEngine.poseFinishEligible(...)` before `speak(...)` so the presence/screen-off gate is honored. That
handler lives in native code (steps 2/3). The original in-app `launchPoseFinishVoiceCue` still runs; once the
native alarm path works, drop it or guard it to avoid a double-speak (see the note at the end).

<details><summary>Reference: what was added to <code>RemoteSnapshotClient</code></summary>

**`RemoteSnapshotClient`** — three PostgREST primitives (mirror the existing presence/gap ones):

```kotlin
// Upsert the next pause-end cue instant. origin_device_id lets the cron skip the push when the phone itself set it.
suspend fun upsertPauseCueSchedule(session: SupabaseSession, dueAtIso: String, originDeviceId: String) {
    val res = http.post("${config.restUrl}/pause_cue_schedule") {
        authHeaders(session)
        header("Prefer", "resolution=merge-duplicates,return=minimal")
        contentType(ContentType.Application.Json)
        setBody(json.encodeToString(PauseCueUpsert(session.userId, dueAtIso, originDeviceId)))
    }
    if (!res.status.isSuccess()) throw res.toException()
}

suspend fun deletePauseCueSchedule(session: SupabaseSession) {
    val res = http.delete("${config.restUrl}/pause_cue_schedule") {
        authHeaders(session); url.parameters.append("user_id", "eq.${session.userId}")
    }
    if (!res.status.isSuccess()) throw res.toException()
}

// Claim this device as the account's last-logged-in phone. The account_last_phone UPDATE fires the DB
// trigger that pushes 'cancel' to the previous phone.
suspend fun claimLastPhone(session: SupabaseSession, deviceId: String) {
    val res = http.post("${config.restUrl}/account_last_phone") {
        authHeaders(session)
        header("Prefer", "resolution=merge-duplicates,return=minimal")
        contentType(ContentType.Application.Json)
        setBody(json.encodeToString(LastPhoneUpsert(session.userId, deviceId)))
    }
    if (!res.status.isSuccess()) throw res.toException()
}

suspend fun upsertPushToken(session: SupabaseSession, deviceId: String, kind: String, platform: String, token: String) {
    val res = http.post("${config.restUrl}/device_push_token") {
        authHeaders(session)
        header("Prefer", "resolution=merge-duplicates,return=minimal")
        contentType(ContentType.Application.Json)
        setBody(json.encodeToString(PushTokenUpsert(session.userId, deviceId, kind, platform, token)))
    }
    if (!res.status.isSuccess()) throw res.toException()
}
```
Add the `@Serializable` payloads (`PauseCueUpsert(user_id, due_at, origin_device_id)`,
`LastPhoneUpsert(user_id, device_id)`, `PushTokenUpsert(user_id, device_id, kind, platform, token)`) next to
the existing `PresenceUpsert`/`GapUpsert`. Import `io.ktor.client.request.delete`.

**1b. New `PauseCueGateway` interface** (sibling of `PresenceGateway`/`SleepGapGateway`), implemented by
`SchedulerSyncEngine` (which owns the session), all calls `runCatching`-wrapped and off `mutex`:
```kotlin
interface PauseCueGateway {
    val signedIn: Boolean
    val deviceId: String
    /** Write the next cue instant with origin = this device (the phone then also schedules it locally). */
    suspend fun publishPauseCueSchedule(dueAtMillis: Long)
    /** No upcoming pose: clear the row so the cron stops pushing. */
    suspend fun clearPauseCueSchedule()
    /** Phone startup: become the account's last phone (fires the cancel-push to the previous phone). */
    suspend fun claimLastPhone()
    /** Register this device's FCM/APNs token so the Edge Function can reach it. */
    suspend fun registerPushToken(platform: String, token: String)
}
```
Implement in `SchedulerSyncEngine` using `withAuth(current) { client.upsertPauseCueSchedule(it, Instant.fromEpochMilliseconds(dueAtMillis).toString(), meta().deviceId) }` etc. Expose it from the ViewModel like
`sleepGaps`/`presence` (`val pauseCue: PauseCueGateway? get() = syncEngine`) and pass into `SchedulerEngine`.

**1c. `SchedulerEngine`** — three hooks:
- **Startup (phones):** in `start()`, after `pullSleepGaps()`, add `claimLastPhoneIfPhone()` →
  `if (deviceKind == DeviceKind.Phone) scope.launch { runCatching { pauseCue?.claimLastPhone() } }`. This is
  requirement #6 (the startup reconcile already runs in the ViewModel; this piggybacks the last-phone claim).
- **Next-pose-end tracking:** add a `collectLatest` over `(_nowMillis, panels)` that computes
  `nextPoseEnd = poses.map { it.endEpochMillis }.filter { it > now }.minOrNull()`. On change: if non-null,
  `pauseCue?.publishPauseCueSchedule(nextPoseEnd)` **and** (phone only) schedule the local alarm at that
  instant + cancel the previous; if null, `pauseCue?.clearPauseCueSchedule()` + cancel the local alarm. This
  is requirements #2/#5. Because the write goes through the same 60-s throttle path only for the *snapshot*,
  call the cue-schedule write directly (it is a tiny per-row upsert, not the whole-doc push).
- **Local alarm (phone):** add `expect fun schedulePauseEndCue(dueAtMillis: Long)` / `expect fun
  cancelPauseEndCue()` in `scheduler/platform`, actual per target (below). The alarm, when it fires, calls the
  **existing** `poseFinishEligible` gate before `speak(...)`.

**1d. Data-push handler → engine.** Native code (steps 2/3) receives `{type:pause_cue, action, due_at}` and
calls a new `SchedulerEngine.onPauseCuePush(action, dueAtMillis)` that schedules or cancels the local alarm.

**1e. Tests** (`shared/jvmTest`): next-pose-end computation; that a schedule change with origin==self writes
`origin_device_id == deviceId`; that clearing on no-pose calls `clearPauseCueSchedule`. Run with
`./gradlew :shared:jvmTest` (per the `shared-check-jvmtest-gate` note, `:shared:check` is red on JS/Native for
an unrelated CalendarUi issue — verify with `:shared:jvmTest`).

> Note vs. shipped code: `registerPushToken(kind, platform, token)` (kind included); the phone's local cue uses
> a `scheduleLocalPauseCue: (Long?) -> Unit` **constructor seam** (schedule at instant / cancel on null) rather
> than a global `expect fun` — steps 2/3 pass the platform lambda at construction in `App.kt`/`SchedulerHolder.kt`.

</details>

---

## Step 2 — Android: Firebase Cloud Messaging + AlarmManager ✅ DONE

Implemented in `androidApp` and verified with `./gradlew :androidApp:assembleDebug` (builds **without** a
`google-services.json` — the push is then inert, not a build failure):
- `gradle/libs.versions.toml` / root `build.gradle.kts`: `firebase-bom` + `firebase-messaging` libs, the
  `googleServices` plugin declared `apply false`.
- `androidApp/build.gradle.kts`: the Firebase deps (always present so the code compiles) + a **guarded**
  `apply(plugin = "com.google.gms.google-services")` that runs only when `androidApp/google-services.json`
  exists.
- `AndroidManifest.xml`: `SCHEDULE_EXACT_ALARM` + `USE_EXACT_ALARM`, the `PauseCueMessagingService`
  (`MESSAGING_EVENT`), and the `PauseCueAlarmReceiver`.
- `PauseCueMessagingService` — `onNewToken` → `pauseCue.registerPushToken("phone","fcm",token)`;
  `onMessageReceived` → `engine.onPauseCuePush(action, dueAtMillis)`.
- `PauseCueScheduler` — the `scheduleLocalPauseCue` seam: `setExactAndAllowWhileIdle` (falls back to inexact
  when exact-alarm isn't granted) / `cancel`, one fixed-request-code `PendingIntent`.
- `PauseCueAlarmReceiver` — fires at the instant → `engine.onPauseCueFire()` (the presence/screen-off gate +
  TTS from `Voice.android.kt`), via `goAsync()`.
- `SchedulerHolder` — wires the seam, sets `localPauseCueDelivery = true` (so the in-app cue is replaced, no
  double-speak), and registers the FCM token on `ensure` (guarded, so it's a no-op without Firebase).

**Runtime prerequisites (device):** a `google-services.json` from your Firebase project (step 4) in
`androidApp/`, and on Android 12+ the **Alarms & reminders** special-access grant (or the alarm falls back to
inexact). Without `google-services.json` the local (phone-origin, requirement #5) alarm still works; only the
server→phone push (requirement #2) needs Firebase.

---

## Step 3 — iOS: APNs + local notification (`iosApp`) 🟡 CODE WRITTEN, UNVERIFIED

> **Why unverified:** Kotlin/Native iOS targets can only be compiled on **macOS + Xcode** — not on the Windows
> dev machine where this was written. So none of the iOS Kotlin below has been compiled; expect Objective-C
> interop fixups on the first Mac build. It is also gated by a **pre-existing blocker** (see 3a).

**3a. Prerequisite blocker — `CalendarUi.kt` Native portability. ✅ FIXED.** `shared/.../ui/CalendarUi.kt`
used JVM-only `sortedSetOf` (backed by `java.util.TreeSet`) in `overlapLayout`/`weightHandles`, which does not
exist on Kotlin/Native or Kotlin/JS, so **every** Native/JS target failed the commonMain compile. It is now
`mutableSetOf<Float>()` + `.sorted()` (behaviour-identical: distinct boundaries, ascending). Verified on Windows
with `./gradlew :shared:compileCommonMainKotlinMetadata` (green). commonMain no longer blocks the iOS compile.

> The JS target still fails for an **unrelated, pre-existing** reason — `Voice`/`DeviceInfo`/`SleepHistory`/
> `SystemNotifier` have no `jsMain` actual (JS was never wired for these platform features). iOS is unaffected:
> its `iosMain` actuals exist. Don't chase the JS errors when checking iOS portability — check the metadata task.

**3b. What was written (all in the repo, compile-verified only on JVM/Android, not Native):**
- `shared/src/iosMain/.../scheduler/platform/`:
  - `PauseCueLocal.ios.kt` — `scheduleLocalPauseCuePlatform(dueAtMillis)` schedules/cancels a
    `UNTimeIntervalNotificationTrigger` (id `omniapp-pause-cue`, cancel-and-reschedule);
    `localPauseCueDeliveryPlatform = true`; `installPauseCuePushBridge(...)` → `IosPushBridge`.
  - `IosPushBridge.kt` — the Swift-callable object (`IosPushBridge.shared`): `registerToken(token)` /
    `deliverPush(action, dueAtIso)`, backed by the callbacks `App()` installs.
  - The previously-**missing** actuals that blocked iOS at all: `Voice.ios.kt` (`AVSpeechSynthesizer`),
    `DeviceInfo.ios.kt` (`currentDeviceKind = Phone`, `isScreenActive = false`), `SystemNotifier.ios.kt`
    (`UNUserNotificationCenter`), `SleepHistory.ios.kt` (no OS sleep log → `null`/empty).
- `shared/commonMain/App.kt` — passes `scheduleLocalPauseCue = ::scheduleLocalPauseCuePlatform` +
  `localPauseCueDelivery = localPauseCueDeliveryPlatform` to the engine it builds, and calls
  `installPauseCuePushBridge { registerApnsToken → pauseCue.registerPushToken("phone","apns",token);
  onRemotePush → onPauseCuePush(action, parse(due_at)) }`. No-op on non-iOS.
- `iosApp/iosApp/AppDelegate.swift` (new) + `iOSApp.swift` (`@UIApplicationDelegateAdaptor`) —
  requests authorization, `registerForRemoteNotifications`, hex-encodes the APNs token →
  `IosPushBridge.shared.registerToken`, routes `{type:pause_cue, action, due_at}` background pushes →
  `IosPushBridge.shared.deliverPush`, and (scenario #3) calls `IosPushBridge.shared.notifyForegrounded()` from
  `applicationDidBecomeActive` → `SchedulerEngine.onAppForegrounded()` re-claims the account's last phone.

**3c. Remaining Mac-only steps to make it run:**
1. Fix 3a (port `CalendarUi.kt`), then `./gradlew :shared:compileKotlinIosSimulatorArm64` and fix any interop
   errors in the `iosMain` actuals above (method/property names, enum constants).
2. In Xcode: add **Push Notifications** capability and **Background Modes → Remote notifications** to the
   `iosApp` target; confirm `AppDelegate.swift` is a member of the target (if the project uses explicit file
   references rather than a synchronized folder group, add it). Note the **bundle id** (→ `APNS_BUNDLE_ID`).
3. For audio to play while backgrounded / ringer silent, configure an `AVAudioSession` (playback category) and
   add **Background Modes → Audio** — otherwise rely on the notification sound (the default cue has one).

> **iOS gate caveat:** iOS cannot run app code at a local notification's fire time, so unlike Android the
> presence/screen-off gate (`poseFinishEligible`) is **not** re-checked at delivery — the system just plays the
> scheduled notification. The server-side origin check still suppresses redundant pushes; the eligibility gate
> is best-effort on iOS only.

---

## Step 4 — Firebase / Apple credentials (your accounts)

**Firebase (Android):**
1. console.firebase.google.com → add project → add an Android app with package `org.example.project`.
2. Download `google-services.json` → `androidApp/`.
3. Project settings → Service accounts → **Generate new private key** → this JSON is `FCM_SERVICE_ACCOUNT`.

**APNs (iOS):**
1. Apple Developer → Certificates, IDs & Profiles → **Keys** → new key with **APNs** enabled → download the
   `.p8` (`APNS_KEY`), note the **Key ID** (`APNS_KEY_ID`) and your **Team ID** (`APNS_TEAM_ID`).
2. `APNS_BUNDLE_ID` = the app bundle id; `APNS_HOST` = `api.sandbox.push.apple.com` for dev builds,
   `api.push.apple.com` for TestFlight/App Store.

---

## Step 5 — Deploy the server half

```bash
# Edge Function secrets (one line each; keep the .p8 newlines or use \n):
supabase secrets set FCM_SERVICE_ACCOUNT="$(cat firebase-service-account.json)"
supabase secrets set APNS_KEY="$(cat AuthKey_XXXX.p8)" APNS_KEY_ID=XXXXXXXXXX APNS_TEAM_ID=YYYYYYYYYY \
                     APNS_BUNDLE_ID=org.example.project APNS_HOST=api.sandbox.push.apple.com

scripts\deploy-supabase.bat   # db push + functions deploy pause-cue + pause-cue-setup.sql (cron/pg_net/GUCs)
```
`deploy-supabase.bat` already deploys the function and installs the cron; the secrets above are the only manual
`supabase secrets set` follow-up.

---

## Testing A — physical Android (true end-to-end)

Use time-simulation so you don't wait for a real pose. Two accounts / two devices make the cross-device push
observable; a single phone exercises the local-schedule path.

1. **Build & install** the debug app on the phone (`scripts/account3-deploy-android.bat`, or
   `./gradlew :androidApp:installDebug`), sign in to account 1.
2. **Token registered:** in Supabase → Table editor → `device_push_token`, confirm a `fcm` row for the phone.
   In `account_last_phone`, confirm the phone's `device_id` after launch (step 6/requirement).
3. **Phone-caused schedule (requirement #5, no server push):** on the phone, advance sim time so the next
   `restBreak` pose is imminent. Confirm `pause_cue_schedule.origin_device_id == the phone` and that the phone
   speaks *"Your pause is over…"* at the pose end. The cron should **not** push (origin == last phone) — check
   the Edge Function logs (`supabase functions logs pause-cue`) show no `schedule` call for this cue.
4. **Desktop-caused schedule (scenario/requirement #2, server push):** on the **desktop** (account 1), make a
   change that moves the next pose-end (e.g. edit the sleep schedule / add a pinned panel). Desktop syncs; the
   desktop's `pause_cue_schedule` row shows `origin_device_id == desktop`. The `on_pause_cue_schedule_change`
   trigger fires **immediately** → Edge logs show a `schedule` push → the phone gets the data message,
   (cancels any stale alarm and) schedules the local alarm at the new instant, and speaks at the exact instant.
   (Kill the app first to prove it still fires.) `tick_pause_cues()` re-affirms the same push ~1 min before as a
   backstop (idempotent — same instant, same alarm id). To see the immediate trigger matter, set the pose-end
   *earlier*, let the phone schedule, then *postpone* it on the desktop: without the immediate push the phone
   would speak at the old, earlier instant.
5. **Presence suppression:** repeat step 4 but keep the phone screen **on** through the final minute — it must
   **stay silent** (the `poseFinishEligible` gate). Turn the screen off → it speaks.
6. **Last-phone handoff / cancel (scenario/requirement #3):** sign a **second** phone into account 1 and launch
   it. Its startup — and every later app-foreground (`onAppForegrounded`, Android `onResume` / iOS
   `applicationDidBecomeActive`) — claims `account_last_phone`; the `on_last_phone_change` trigger pushes
   `cancel` to phone #1 (Edge logs show a `cancel` call) → phone #1 cancels its pending alarm; only phone #2
   speaks the next cue. Verify the **foreground** (not just cold-start) path too: with both apps already running,
   background phone #2 and foreground phone #1 → phone #1 re-claims and phone #2 is cancelled.

Watch `supabase functions logs pause-cue --project-ref <ref>` throughout — every `schedule`/`cancel` and any
`FCM …`/`APNs …` error is logged there.

## Testing B — simulated iPhone (⚠️ receipt only, not end-to-end)

**Prerequisite:** the iOS app must first *compile and run*, which requires a **Mac build** — step 3a
(`CalendarUi.kt` Native portability) is now fixed, so on a Mac the remaining work is only the step 3c interop
fixups + Xcode capabilities before any of this is reachable.

**Hard limitation:** the iOS **Simulator cannot receive a network APNs push** from Supabase — it has no APNs
connection. It *can* receive a **locally-injected** push (Xcode 14+/iOS 16+), which is enough to test the
app-side schedule/cancel + speak path, just not the server→APNs leg.

1. Run the app in the Simulator, sign in. `registerForRemoteNotifications` returns a token on modern
   simulators; if `device_push_token` gets an `apns` row, the *real* server push is still dropped by APNs for a
   simulator target — proceed with local injection.
2. Create `schedule.apns`:
   ```json
   { "Simulator Target Bundle": "org.example.project",
     "aps": { "content-available": 1 },
     "type": "pause_cue", "action": "schedule", "due_at": "2026-07-03T18:00:00Z" }
   ```
   Inject it: `xcrun simctl push booted org.example.project schedule.apns` (or drag the file onto the
   Simulator). Confirm the app schedules the local notification and speaks *"Your pause is over…"* at `due_at`
   (set `due_at` a minute out; screen off / no active peer so the gate passes).
3. Cancel: inject the same with `"action": "cancel"` → confirm the pending local notification is removed and no
   cue speaks.
4. For a **true** server→APNs end-to-end iOS test you need a **real device** with a dev build and
   `APNS_HOST=api.sandbox.push.apple.com`; the flow then mirrors Testing A.

---

## Testing C — accelerated cross-device (the desktop→phone time-link)

**Problem it solves:** time acceleration lives only on the desktop (`omniapp.timeSim`; the Android build runs on
the real wall clock). An accelerated desktop clock produces `due_at` values far in the *real* future, so pure
time-accel is great for watching the push get *emitted* but never makes a phone *speak soon*. The **time-link**
streams the desktop's accelerated clock to a plugged-in Android debug app so both share one `now` — the phone's
schedule, `pause_cue_schedule` writes, alarms and cue actually run in accelerated time.

**How it works** (`shared/.../scheduler/debug/TimeLink*` + `androidApp/.../TimeLinkClient.kt`): the desktop (under
time-sim) runs a loopback TCP server on port **47615** and keeps `adb reverse tcp:47615 tcp:47615` set up; the
debuggable Android app dials `127.0.0.1:47615` and re-anchors its `SimAppClock` (`SimAppClock.adopt`) from each
`"<virtualNow> <speed>"` frame. The desktop **Time** panel shows the link status: **● Phone link: connected (N)**
or an amber **⚠ Phone link: not connected — acceleration is desktop-only** (warn-only; the controls stay usable
so single-device desktop sim still works). Transport is adb-only and debuggable-only — nothing runs in a release
build; there is still never a WebSocket to the server.

**Steps:**
1. Plug in the phone (USB with debugging on, or an Android-Studio emulator) — confirm `adb devices` lists it.
   Deploy the debug app: `scripts\account1-deploy-android.bat`.
2. Launch the desktop under time-sim: `scripts\account1-empty-and-open.bat` (`:run` sets `omniapp.timeSim=true`).
   The **Time** panel should flip to **● Phone link: connected (1)** within a few seconds. If it stays amber,
   check `adb devices` and that `adb` is on `PATH` or under the SDK `platform-tools` (the server auto-locates it;
   otherwise run `adb reverse tcp:47615 tcp:47615` yourself).
3. On the desktop panel, set speed **60×** (or hit **simulate pause + leap → 5min**). Watch the phone: its
   now-line / calendar advance in lockstep, and a rest pose arrives in seconds instead of an hour.
4. Now the whole of **Testing A** runs accelerated and end-to-end on the real phone:
   - **Scenario #2 (desktop postpones):** make a desktop change that moves the next pose-end → `pause_cue_schedule`
     shows `origin_device_id == desktop`, Edge logs show the **immediate** `schedule` push, and because the phone
     now shares the accelerated clock it schedules the alarm at an instant that is genuinely seconds away →
     **it speaks**. `adb shell dumpsys alarm | Select-String org.example.project` shows the alarm move.
   - **Scenario #1 (phone-origin):** the phone reschedules locally with no server push (origin == last phone).
   - **Scenario #3 (handoff):** foreground a second phone → the previous phone gets the `cancel` push.
5. When done, hit **reset to real time** on the panel (or close the desktop) — the link's `adb reverse` is removed
   on dispose, and the phone's clock falls back to real time (the `SimAppClock` stops being re-anchored).

**Caveats:** the phone adopts the desktop's wall time as authoritative, so a large clock skew between the two
machines shifts the phone's schedule by that skew (keep both NTP-synced). Accelerated state can sync to Supabase
via the LWW snapshot, so use a throwaway/test account. Two phones need a per-device `adb -s <serial> reverse
tcp:47615 tcp:47615` (the auto-reconcile targets the default device). On Android, history-unit timestamps stay on
real time by design — only the schedule/`now` the cue path depends on is driven.

---

## Notes
- Everything is inert until the two secrets exist and a phone registers a `device_push_token` row — the Edge
  Function returns `no push token for device` (200) and the cron pushes nothing.
- The 1-minute server-sync debounce (requirement #1) is already live and unrelated to the push path; it only
  governs how often a change is mirrored to `scheduler_snapshot`.
