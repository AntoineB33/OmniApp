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
| `tick_pause_cues()` cron (push ~1 min before, **skip when origin == last phone**) | ✅ same migration + `supabase/pause-cue-setup.sql` |
| `on_last_phone_change` trigger (cancel push to the **previous** phone) | ✅ same migration |
| Edge Function real **FCM v1 / APNs HTTP/2** send | ✅ `supabase/functions/pause-cue/index.ts` (needs secrets) |
| Client writes `pause_cue_schedule` when the next pose-end changes | ✅ `SchedulerEngine.launchPauseCueSchedule` → `SchedulerSyncEngine.publishPauseCueSchedule` |
| Client claims `account_last_phone` on startup (phones only) | ✅ `SchedulerEngine.claimLastPhoneOnStartup` |
| Client transport for `device_push_token` | ✅ `SchedulerSyncEngine.registerPushToken` (still needs a *native token* to feed it — steps 2/3) |
| Local-cue seam + push entry (`scheduleLocalPauseCue` / `onPauseCuePush` / `onPauseCueFire`) | ✅ shared seam + eligibility gate wired |
| Android: FCM receiver + AlarmManager local cue (wire the seam) | ✅ `PauseCueMessagingService` / `PauseCueAlarmReceiver` / `PauseCueScheduler`; `SchedulerHolder` wires the seam + `localPauseCueDelivery=true` |
| iOS: APNs registration + local notification cue (wire the seam) | ⛔ **TODO — step 3** |
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

## Step 3 — iOS: APNs + local notification (`iosApp`)

1. In Xcode: enable the **Push Notifications** capability and **Background Modes → Remote notifications** on
   the app target. Note the **bundle id** (→ `APNS_BUNDLE_ID`).
2. Add an `AppDelegate` (via `@UIApplicationDelegateAdaptor` in `iOSApp.swift`):
   - `didFinishLaunching` → `UNUserNotificationCenter.current().requestAuthorization([.alert,.sound])` and
     `application.registerForRemoteNotifications()`.
   - `didRegisterForRemoteNotificationsWithDeviceToken` → hex-encode the token, hand to shared
     `pauseCue.registerPushToken("apns", token)`.
   - `didReceiveRemoteNotification` (content-available background push) → read `action`/`due_at`, call the
     shared `onPauseCuePush(...)`.
3. `schedulePauseEndCue` actual (iosMain) → `UNTimeIntervalNotificationTrigger` or a `UNCalendarNotificationTrigger`
   at `due_at` whose handler runs `poseFinishEligible` then speaks via `AVSpeechSynthesizer` (the existing iOS
   `Voice` actual). `cancelPauseEndCue` → `removePendingNotificationRequests`.

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
4. **Desktop-caused schedule (requirement #2, server push):** on the **desktop** (account 1), make a change
   that moves the next pose-end (e.g. edit the sleep schedule / add a pinned panel). Desktop syncs; within a
   minute the desktop's `pause_cue_schedule` row shows `origin_device_id == desktop`. ~1 min before the
   pose-end, `tick_pause_cues()` fires → Edge logs show a `schedule` push → the phone gets the data message,
   schedules the local alarm, and speaks at the exact instant. (Kill the app first to prove it still fires.)
5. **Presence suppression:** repeat step 4 but keep the phone screen **on** through the final minute — it must
   **stay silent** (the `poseFinishEligible` gate). Turn the screen off → it speaks.
6. **Last-phone handoff / cancel (requirement #6):** sign a **second** phone into account 1 and launch it. Its
   startup reconcile claims `account_last_phone`; the `on_last_phone_change` trigger pushes `cancel` to phone
   #1 (Edge logs show a `cancel` call) → phone #1 cancels its pending alarm; only phone #2 speaks the next cue.

Watch `supabase functions logs pause-cue --project-ref <ref>` throughout — every `schedule`/`cancel` and any
`FCM …`/`APNs …` error is logged there.

## Testing B — simulated iPhone (⚠️ receipt only, not end-to-end)

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

## Notes
- Everything is inert until the two secrets exist and a phone registers a `device_push_token` row — the Edge
  Function returns `no push token for device` (200) and the cron pushes nothing.
- The 1-minute server-sync debounce (requirement #1) is already live and unrelated to the push path; it only
  governs how often a change is mirrored to `scheduler_snapshot`.
