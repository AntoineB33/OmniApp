# Pause-end voice cue delivery (1.6.0) — runbook

The end of a 5/15-minute pose is spoken by **one phone** — the *last phone that logged in to the account* —
delivered as an **OS-scheduled local notification** so it fires even when the app is killed. There is **never a
WebSocket**; the only server→device channel is the `pause-cue` Edge Function push. See ARCHITECTURE.md §8 and
PRD §15 for the design; this file is the operational runbook.

## What is already done vs. what remains

| Piece | Status |
| --- | --- |
| 10-s **user-change sync throttle** (leading-scheduled: one push 10 s after the *first* change of a burst, later changes absorbed without restarting the countdown; one of the five sync moments — login, sync button, this throttle, the deferred cue burst, the device-inactivity finalize) | ✅ `scheduler/sync/ServerPushDebounce`, wired in `TaskSchedulerViewModel` (`SERVER_PUSH_DEBOUNCE_MILLIS = 10_000`) |
| Supabase schema: `device_push_token` / `account_last_phone` / `pause_cue_schedule` | ✅ `supabase/migrations/20260703000000_init.sql` |
| `on_pause_cue_schedule_change` trigger — **immediate** push (cancel+reschedule) when a non-phone device moves the next pose-end (scenario #2), **skip when origin == last phone** | ✅ `supabase/migrations/20260704000000_pause_cue_immediate_push.sql` |
| `tick_pause_cues()` cron (push ~1 min before, **skip when origin == last phone**) — now a **backstop** to the immediate trigger | ✅ `20260703000000_init.sql` + `supabase/pause-cue-setup.sql` |
| `on_last_phone_change` trigger (cancel push to the **previous** phone) | ✅ `20260703000000_init.sql` |
| Push is **best-effort** — a client `pause_cue_schedule` / `account_last_phone` write never fails (`400`) when pg_net / the `app.settings.*` GUCs are unprovisioned; the trigger stays truly inert | ✅ `supabase/migrations/20260708000000_pause_cue_push_best_effort.sql` (all pushes routed through `public.omni_edge_push`) |
| Edge Function real **FCM v1 / APNs HTTP/2** send | ✅ `supabase/functions/pause-cue/index.ts` (needs secrets) |
| Client writes `pause_cue_schedule` at the **last responsible moment** — deferred to `min(d1,d2) − margin`, **no per-change chatter, zero writes in steady state** | ✅ `scheduler/sync/PauseCuePushScheduler` (d1/d2 compare + minute-scale margins: 2 min / ½ min), driven by `SchedulerEngine.launchPauseCueSchedule` → `SchedulerSyncEngine.publishPauseCueSchedule`; the phone's own local OS cue is still (re)scheduled **immediately** |
| Client claims `account_last_phone` on startup **and on every app-foreground** (phones only; scenario #3) | ✅ `SchedulerEngine.claimLastPhoneOnStartup` / `onAppForegrounded` (Android `MainActivity.onResume`, iOS `applicationDidBecomeActive`) |
| Client transport for `device_push_token` | ✅ `SchedulerSyncEngine.registerPushToken` (still needs a *native token* to feed it — steps 2/3) |
| Local-cue seam + push entry (`scheduleLocalPauseCue` / `onPauseCuePush` / `onPauseCueFire`) | ✅ shared seam + eligibility gate wired |
| Android: FCM receiver + AlarmManager local cue (wire the seam) | ✅ `PauseCueMessagingService` / `PauseCueAlarmReceiver` / `PauseCueScheduler`; `SchedulerHolder` wires the seam + `localPauseCueDelivery=true` |
| iOS: APNs registration + local notification cue (wire the seam) | 🟡 **Code written, UNVERIFIED** — `iosMain` actuals (`PauseCueLocal.ios` + `Voice`/`DeviceInfo`/`SystemNotifier`/`SleepHistory`) + `IosPushBridge` + Swift `AppDelegate`; needs a **Mac build** to compile the Kotlin/Native interop. The former `CalendarUi.kt` commonMain portability blocker is **fixed** (`sortedSetOf` → `mutableSetOf().sorted()`; `:shared:compileCommonMainKotlinMetadata` green), so common code no longer blocks the iOS compile. See step 3. |
| Firebase project + `google-services.json` + APNs key + secrets deployed | ⛔ **TODO — steps 4/5 (your credentials)** |

Two design decisions baked into the steps below (change them if you disagree):
1. **Presence gate is kept — but not the presence poll.** The push/alarm is the *delivery*; the phone still
   runs the existing screen-off + no-active-peer check (`SchedulerEngine.poseFinishEligible`) before it
   actually speaks, so a user at a screen is never told. The last-phone/FCM path only decides *which* phone
   gets the alarm. The peer's "active screen" that gate reads is **no longer written by a 60 s beacon**: a
   device with an active screen inside a pose publishes `device_presence` **once**, coalesced into the deferred
   pause-cue push burst below (`min(d1,d2) − margin`, right before the pose end the phone reads it at) — see
   `SchedulerEngine.publishPosePresenceIfActive`. So an idle/working session makes **zero** `device_presence`
   writes outside those bursts.
2. **Credentials assumed absent.** Code is written to be inert until you add Firebase/Apple secrets.

### Deferred, last-responsible-moment server write (no heartbeat, no per-tick chatter)

There is **no heartbeat** and the client does **not** upsert `pause_cue_schedule` on every prediction change.
`scheduler/sync/PauseCuePushScheduler` (driven by `SchedulerEngine.launchPauseCueSchedule`) compares two
instants and defers the single write to the last responsible moment:

- **d1** — the instant the **server** already holds. Seeded once at startup from the `pause_cue_schedule` row
  (`fetchPauseCueSchedule`), then tracked locally: after each push `d1 := d2`.
- **d2** — this device's **current** next rest-pose end (`nextRestPoseEndMillis`). Effectively never null —
  every app state has an upcoming pause; a (theoretical) null just means "nothing to push".

The upsert fires at **`min(d1, d2) − margin`**. The margins are **minute-scale**, not second-scale, because
delivery is the `tick_pause_cues()` cron polling **once a minute** — a push landing seconds before the due
instant can miss the very cron tick that would deliver it:

| Case | Target | Margin | Why |
| --- | --- | --- | --- |
| `d2 == d1` | — | — | **No write.** Steady state — the server is already correct. An idle session makes **zero** cue writes. |
| `d2 > d1` (cue postponed) | `d1` | **2 min** | The server still holds the *earlier* `d1`, so the last phone has a stale alarm that would speak **too early** and must be **cancelled** before the cron tick that would fire it — a full poll cycle of slack. |
| `d2 < d1`, or first publish (`d1` unknown) | `d2` | **½ min** | Nothing stale to cancel (the cue is moving *earlier*, or the server has nothing yet) — a single lead margin is enough. |

Each new prediction re-arms the single timer (cancelling the prior one); the phone's own local OS cue is still
(re)scheduled **immediately**, independent of this deferred server write. The `tick_pause_cues()` cron
(~1 min before) stays the backstop for schedules another device set. Unit-tested by
`shared/jvmTest/.../PauseCuePushSchedulerTest` (virtual clock; all four rows + re-arm + fire-inside-margin).

The same burst that fires this write also does the account's other last-responsible-moment traffic — it is
the **fourth sync moment** (ARCHITECTURE.md §8), the only one that recurs without a user action:
`refreshDerivedPauses()` (push own active-session history — including any locally-finalized session, i.e. a
walk-away — then pull the account-wide pauses) and the cross-device **presence** write
(`publishPosePresenceIfActive`, only when signed in + screen on + inside a rest pose). That replaces the old
per-60 s `device_presence` beacon: in the scenario "now-line inside a 5/15-min pose, `screenActive()` true,
signed in", the pose end is re-pinned to the now-line so `d2 = now + duration` moves constantly — **yet no
write fires per minute or per tick**. Each tick's new `d2` re-arms the *single* deferred timer with
`wait = duration − margin` (≈270 s for the 5-min pose), which is far longer than the ≤30 s advance-tick
cadence, so the timer is cancelled and re-armed long *before* it could fire. On a freshly-emptied account
(`d1` null, e.g. right after `account1-empty-and-open`) the burst therefore **never fires while the pose keeps
sliding** — so `pause_cue_schedule`, `device_presence`, `device_active_session`, and the derived-pause pull all
stay at **zero writes** for as long as the user works through the pose and changes nothing. Only when `d2`
stabilises on a real upcoming pause (the user actually rests, or the now-line moves past the pinned pose) does
the timer survive to fire, landing exactly one burst at `d2 − ½ min`. So after `account1-empty-and-open`'s
startup sync, the Supabase write count is **guaranteed constant** in this scenario. (Guaranteed by
`PauseCuePushSchedulerTest.a_pose_pinned_to_the_now_line_never_pushes`; see also **Testing D → step 5**.) An
idle session with a *stable* pose end makes no presence write at all.

---

## Step 1 — Shared Kotlin: transport + schedule/claim wiring ✅ DONE

Implemented in `shared/commonMain` + tests in `shared/jvmTest` (`./gradlew :shared:jvmTest` green):
- `RemoteSnapshotClient`: `upsertPauseCueSchedule` / `fetchPauseCueSchedule` / `claimLastPhone` /
  `upsertPushToken` (+ payloads). Covered by `PauseCueGatewayTest`.
- `PauseCueGateway` (new) implemented by `SchedulerSyncEngine`; exposed as `TaskSchedulerViewModel.pauseCue`.
- `SchedulerEngine`:
  - `launchPauseCueSchedule()` — tracks the next rest-pose end (`nextRestPoseEndMillis`, covered by
    `PauseCueScheduleTest`) and, on a **phone**, (re)schedules the local-cue seam immediately; the server
    `pause_cue_schedule` upsert (origin = self) is **deferred** to the last responsible moment by
    `PauseCuePushScheduler` (see "Deferred, last-responsible-moment server write" above). This is
    requirements #2/#5. Because d2 is effectively never null there is no clear-on-no-pose path.
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

// Read the instant the server currently holds — seeds d1 for the deferred push (PauseCuePushScheduler).
suspend fun fetchPauseCueSchedule(session: SupabaseSession): Long? {
    val res = http.get("${config.restUrl}/pause_cue_schedule") {
        authHeaders(session)
        url.parameters.append("user_id", "eq.${session.userId}"); url.parameters.append("select", "due_at")
    }
    if (!res.status.isSuccess()) throw res.toException()
    return json.decodeFromString<List<PauseCueRow>>(res.bodyAsText()).firstOrNull()?.dueAt
        ?.let { Instant.parse(it).toEpochMilliseconds() }
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
the existing `PresenceUpsert`/`GapUpsert`.

**1b. New `PauseCueGateway` interface** (sibling of `PresenceGateway`/`SleepGapGateway`), implemented by
`SchedulerSyncEngine` (which owns the session), all calls `runCatching`-wrapped and off `mutex`:
```kotlin
interface PauseCueGateway {
    val signedIn: Boolean
    val deviceId: String
    /** Write the next cue instant with origin = this device (the phone then also schedules it locally). */
    suspend fun publishPauseCueSchedule(dueAtMillis: Long)
    /** Read the instant the server currently holds — seeds d1 for the deferred push. */
    suspend fun fetchPauseCueSchedule(): Long?
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
  `nextPoseEnd = poses.map { it.endEpochMillis }.filter { it > now }.minOrNull()` (d2). On change, (phone only)
  (re)schedule the local alarm at that instant immediately; the server `publishPauseCueSchedule` upsert is
  **deferred** to `min(d1,d2) − margin` by `PauseCuePushScheduler`, not written on every change. This is
  requirements #2/#5. The cue-schedule write is a tiny per-row upsert, independent of the 10-s snapshot debounce.
- **Local alarm (phone):** add `expect fun schedulePauseEndCue(dueAtMillis: Long)` / `expect fun
  cancelPauseEndCue()` in `scheduler/platform`, actual per target (below). The alarm, when it fires, calls the
  **existing** `poseFinishEligible` gate before `speak(...)`.

**1d. Data-push handler → engine.** Native code (steps 2/3) receives `{type:pause_cue, action, due_at}` and
calls a new `SchedulerEngine.onPauseCuePush(action, dueAtMillis)` that schedules or cancels the local alarm.

**1e. Tests** (`shared/jvmTest`): next-pose-end computation; that a schedule change with origin==self writes
`origin_device_id == deviceId`; the deferred d1/d2 margins (`PauseCuePushSchedulerTest`). Run with
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
  the shared bundled-WAV voice from `Voice.android.kt` — an `AudioTrack` playing the same pre-rendered Piper
  cue as the desktop, not the device's built-in TTS), via `goAsync()`.
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
   change that moves the next pose-end (e.g. edit the sleep schedule / add a pinned panel). **The desktop does
   NOT write `pause_cue_schedule` right away** — the write is deferred to `min(d1,d2) − margin` (see
   "Deferred, last-responsible-moment server write" above and **Testing D**), so watch the row change *near the
   cue*, not at the moment of the edit. When it does write, the row shows `origin_device_id == desktop`; the
   `on_pause_cue_schedule_change` trigger fires **immediately** → Edge logs show a `schedule` push → the phone
   gets the data message, (cancels any stale alarm and) schedules the local alarm at the new instant, and speaks
   at the exact instant. (Kill the app first to prove it still fires.) `tick_pause_cues()` is the ~1-min-before
   backstop for a schedule that was already on the server. To make the deferred write arrive in seconds rather
   than up to an hour, run this under the **Testing C** time-link (accelerated clock).
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
`"<virtualNow> <speed> <inactive01>"` frame (the trailing token is the "simulate pause + leap" forced-inactivity
flag — while `1` the phone treats its screen as inactive, so a leap whose "inactive:" scope includes the phone
makes it live the pause too). The flag's `1→0` transition (leap end) is an explicit sync moment **on the phone
too**, and the leap end is sequenced so neither side derives against the other's stale *open* server row (which
`derive_pauses` presumes active through the now-line, hiding the just-simulated pause — no Inactivity band, and
the un-rested 5-min pose stays pinned to the now-line, visibly "dragged" by it): the desktop first **pushes its
own sessions** (`SchedulerEngine.pushOwnActiveSessionsAndWait`), *then* clears the flag; the phone, on the `0`
frame, pushes + derives + seeds (`refreshDerivedPausesAndWait`) and writes one `"pushed"` ack line back over the
socket; the desktop waits for the acks (`TimeLink.awaitPhoneLeapAcks`, 5 s bound) before its own post-leap
derive. The desktop **Time** panel shows the link status: **● Phone link: connected (N)**
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

## Testing D — the deferred, last-responsible-moment write (d1/d2 margins)

Goal: prove the client makes **zero** `pause_cue_schedule` writes while idle, and that when the next pose-end
moves it writes **once**, at `min(d1,d2) − margin`, with the right margin. Run it under the **Testing C**
time-link so the deferred instant arrives in seconds; keep three panes open:

- **Supabase → Table editor → `pause_cue_schedule`** (watch `due_at` / `origin_device_id` / `updated_at`).
- **`supabase functions logs pause-cue --project-ref <ref>`** (each `schedule`/`cancel` push).
- The desktop **Time** panel (drive `now`) — and, optionally, the desktop DB write log.

**Automated first.** The pure timing logic is unit-tested — run it before touching hardware:
```
./gradlew :shared:jvmTest --tests "org.example.project.PauseCuePushSchedulerTest"
```
It asserts all four table rows (steady-state no-write, postpone→cancel-margin-before-`d1`, advance→publish-margin-before-`d2`,
first publish→publish-margin), timer re-arm, and the fire-inside-the-margin immediate push, on a virtual clock.
(The test uses small illustrative fixture margins; production uses 2 min / ½ min — see the test's KDoc.)

**Manual, end-to-end.** With the time-link connected and the phone signed in as last phone:

1. **Steady state = zero writes.** Let the app sit with a stable schedule for a simulated ≥1 min. `updated_at`
   on the `pause_cue_schedule` row must **not** change and the Edge logs must show **no** new `schedule` push.
   (`d1 == d2` ⇒ no write — this is the whole point: an idle session never chatters.)
2. **Postpone (`d2 > d1`, expect a write 2 s before the OLD instant).** With a cue pending at `d1`, make a
   desktop change that pushes the next pose-end **later** (e.g. extend the current task / delete the imminent
   pose). Nothing should hit `pause_cue_schedule` yet. As the accelerated `now` approaches **`d1 − 2 s`**, watch
   the row's `due_at` jump to the new later instant and `origin_device_id == desktop`; the Edge log shows one
   `schedule` push for the new `due_at` ~1 s later. On a backgrounded phone the stale `d1` alarm is replaced
   before it fires (no early "your pause is over"). `dumpsys alarm | Select-String org.example.project` shows
   the alarm move.
3. **Advance / first publish (expect a write 1 s before the NEW instant).** From a clean row (empty
   `pause_cue_schedule`, or a cue that you now move **earlier**), the write must land at **`d2 − 1 s`**, not
   before — `due_at` appears/updates one accelerated second ahead of the cue, Edge log shows one `schedule`
   push. (First publish has `d1` unknown ⇒ same 1 s margin.)
4. **Change-inside-the-margin ⇒ immediate.** Move the pose-end to an instant already within a second of `now`.
   The write should fire **at once** (no negative wait), and the phone still speaks at the instant.
5. **Pose pinned to the now-line = zero writes (the `account1-empty-and-open` guarantee).** Empty the account
   (`account1-empty-and-open.bat`) so `pause_cue_schedule` starts **empty** (`d1` null), then park the now-line
   **inside** a 5/15-min rest pose with the screen **active** and take no action. The pose is pinned to the
   now-line, so `d2 = now + duration` climbs every tick, but the deferred timer is re-armed (`wait = duration −
   margin` ≈ 299 s) faster than it can fire. Fast-forward the accelerated clock through many ticks: after the
   one startup sync, `pause_cue_schedule`, `device_presence`, and `device_active_session` must show **no new
   writes** and the Edge log **no** `schedule` push — the row count is constant. It only breaks when `d2`
   stabilises (let the pose actually be taken, or advance `now` past it): then exactly one burst lands at
   `d2 − 1 s`. This is the case unit-tested by `a_pose_pinned_to_the_now_line_never_pushes`.

**Reading the margins from logs:** compare the `pause_cue_schedule.updated_at` (server receive time) against the
row's `due_at`. Postpone cases land ~2 s before the *previous* `due_at`; advance/first cases ~1 s before the
*new* `due_at`. Because both `nowMillis` and the delay use the same (accelerated) clock, the gap you observe is
in **accelerated** seconds — divide by the time-link speed for wall-clock.

> Caveat (documented in `PauseCuePushScheduler`): under accelerated sim the sync clock and the coroutine `delay`
> clock can diverge, so the deferred write is best-effort in sim — treat the *ordering* and *which margin* as the
> assertions, and use the unit test for exact-millisecond timing.

---

## Testing E — the `scheduler_snapshot` heartbeat is gone (idle = zero writes)

Separate from the pause-cue push path above: the whole-document `scheduler_snapshot` sync used to fire on
**every** engine tick, because a re-derived auto/side/sleep panel counted as a change (the old "known
deviation" — this is what shows up as regular `GET`/`PATCH /rest/v1/scheduler_snapshot` in the Supabase logs
of an account with no visible app open, e.g. the always-on account-3 release). It is now gated on
`SchedulerStateCodec.syncFingerprint` (the persisted snapshot minus the regenerated panels — see
`SchedulerDomain.isRegeneratedPanel`), so a tick that only re-derives those panels neither marks the state
dirty nor pushes.

**Automated first:**
```
./gradlew :shared:jvmTest --tests "org.example.project.SyncFingerprintGateTest"
```
Asserts (a) regenerated auto/side/sleep panels don't move the fingerprint while pinned panels /
reminder-checks / tree edits / manual record edits do, and (b) via the ViewModel: a `RefreshSchedule` tick and
an `AdvanceSchedule` tick that **banks an auto record** do **not** mark dirty, while a `FocusWindow` change and
a manual `RemoveRecordPeriod` do.

**Manual, end-to-end** (a signed-in desktop is enough; watch the Supabase **API logs** for the account's
`user_id`):
1. **Idle = silent.** Sign in, leave the app open with a stable schedule and **make no edits** for several
   minutes (use the **Time** panel to fast-forward `now` so many scheduler ticks fire). There must be **no**
   `PATCH /rest/v1/scheduler_snapshot` in the logs across those ticks — the now-line advances, panels
   re-derive, nothing syncs. (Before the fix this PATCHed roughly once per throttle interval.)
2. **A real edit still syncs.** Now make an authoritative change (rename a task, pin/move a calendar panel,
   check a reminder). ~10 s after the last edit of the burst a single `PATCH /rest/v1/scheduler_snapshot`
   (revision +1) appears. One editing burst → one write — and the payload is the **stripped** authoritative
   projection (no regenerated auto/side/sleep panels, no per-device view state; `SyncPayloadTest`).
3. **Auto-banked work does NOT sync on its own.** Let the now-line cross the end of an auto-scheduled work
   block (so `advanceSchedule` records `[start,end]` as completed work). There must be **no**
   `PATCH /rest/v1/scheduler_snapshot` for that — the record is *derived* (every device recomputes it by
   advancing its own now-line over the synced tree), kept only in the local DB, and it rides along with the
   next authoritative push. A **manual** record edit (`RemoveRecordPeriod` — "Remove" on a record block) *does*
   PATCH: that is a user decision no other device can deduce.

## Notes
- Everything is inert until the two secrets exist and a phone registers a `device_push_token` row — the Edge
  Function returns `no push token for device` (200) and the cron pushes nothing.
- **"Inert" means no push goes out — never a failed write.** The trigger/cron pushes route through
  `public.omni_edge_push`, which no-ops when the config is unset and swallows any `net.http_post` error in a
  subtransaction. Before this (`20260708000000`), a `pause_cue_schedule` upsert from a non-phone device
  on an account that already had a last phone would `400` when pg_net / the config was unprovisioned, because the
  AFTER-write trigger raised inside the writer's transaction. The push is best-effort; the once-a-minute
  `tick_pause_cues` cron is the delivery backstop.
- The config `omni_edge_push` reads is the **Vault secrets** `omni_edge_base_url` / `omni_service_role_key`
  (upserted by `supabase/pause-cue-setup.sql`, i.e. deploy-supabase.bat step 3), falling back to the legacy
  `app.settings.*` GUCs (`20260709020000`). It was moved off `alter database set app.settings.*` because
  database-level custom GUCs are superuser-only on Postgres 15+ — Supabase denies them with `42501`.
- The 10-second user-change debounce (requirement #1) is the push *timing*; **which** changes push is
  gated by `syncFingerprint` (Testing E). Together: an idle session makes zero `scheduler_snapshot` writes, and
  a burst of authoritative changes is mirrored once, 10 s after its last edit. The pause-cue push path
  (`pause_cue_schedule`, the fourth sync moment) is independent of both.
