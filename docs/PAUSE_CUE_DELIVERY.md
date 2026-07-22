# Pause-end voice cue delivery (1.6.0) — runbook

When **every** device on the account has gone inactive while a screen break is waiting to be served (and
the account is not deliberately away — the Sleep/Work toggle), a **phone speaks at the break's end** to
call the user back. Because a backgrounded phone cannot run an in-app timer, the cue is delivered as a
push-triggered **OS-scheduled local notification/alarm**, so it fires even when the app is killed.

The delivery chain (ARCHITECTURE.md §8, PRD §15):

```
active clients ──(UPSERT device_heartbeat every ~10s: {device_id, kind, next_break_*})──▶ Supabase Postgres
                                                                                            │ polled every ~10s
                                                                       pg_cron: tick_pause_cues()
                                                                       (no fresh heartbeat + not sleeping)
                                                                                            │ omni_edge_push
                                                                              POST pause-cue Edge Function
                                                                                            │ FCM / APNs (device '*')
                                                                phone: onPauseCuePush → OS alarm → speaks
                                                                       (onPauseCueFire, eligibility-gated)
```

**This is a pg_cron + heartbeat-table design (2026-07-23), which replaced the external Fly.io `/listener`.**
The listener existed only because pg_cron / an Edge Function **cannot read Realtime Presence**. But the
listener was itself a heartbeat-and-poll process (a `published_at_ms` liveness stamp + a 10-s `evaluate()`
tick), so moving the same heartbeat onto a plain `device_heartbeat` table lets pg_cron do the poll
centrally — and drops the always-on host entirely. Detecting a locked phone is fundamentally a
heartbeat-timeout problem either way; this just puts the timeout in Postgres.

> **Note — the only live Realtime channel is document sync.** Cross-device **document sync** rides a Realtime
> `postgres_changes` subscription on the `scheduler_snapshot` row (`RealtimeSnapshotSubscriber`), which
> auto-pulls a peer's push (ARCHITECTURE.md §8). Nothing in this cue-delivery path uses a WebSocket any more —
> the heartbeat is a PostgREST UPSERT and the cue decision is a cron job.

## Status — done vs. remaining

| Piece | Status |
| --- | --- |
| Client heartbeat: signed-in + active clients UPSERT `device_heartbeat` every ~10 s with `{device_id, kind, next_break_start_ms, next_break_len_ms, next_break_end_ms}` (driven from the engine's active-session beat); a clean lock flips `closed = true` | ✅ `DeviceHeartbeatPublisher` / `SchedulerEngine.updatePresence()`; `PauseCueGatewayTest` |
| Sleep/Work toggle → `account_state` (cron suppression; persists across restart until the scheduled wake) | ✅ `SetSleepMode` / `publishAccountState` / `resolveSleepModeOnStartup`; `SleepModeTest` |
| Server cron: `tick_pause_cues()` polls `device_heartbeat` every ~10 s, computes the pause end server-side, fires `pause-cue` to all phones when idle + not sleeping | ✅ migration `20260723000000` + `pause-cue-setup.sql` (`cron.schedule('pause-cue-tick','10 seconds', …)`) |
| Supabase schema: `device_heartbeat`, `pause_cue_schedule`, `account_state`, `device_push_token`, `account_last_phone` | ✅ migrations up to `20260723000000`, applied by `deploy-supabase.bat` |
| `pause-cue` Edge Function: real FCM v1 / APNs HTTP/2 sends, fan-out to `device_id:'*'` | ✅ `supabase/functions/pause-cue/index.ts` (**needs secrets**, below) |
| Phone client: FCM receiver + exact alarm + spoken cue, eligibility-gated at fire time | ✅ `PauseCueMessagingService` / `PauseCueScheduler` / `PauseCueAlarmReceiver` → `SchedulerEngine.onPauseCuePush` / `onPauseCueFire` / `poseFinishEligible`; `PauseCueGatewayTest` |
| Phone claims `account_last_phone` at startup and on every app-foreground; push-token registration | ✅ `SchedulerEngine.claimLastPhoneOnStartup` / `onAppForegrounded`; `SchedulerSyncEngine.registerPushToken` (needs a native token → Firebase step) |
| iOS: APNs registration + local-notification cue | 🟡 code written (`iosMain` actuals + `IosPushBridge` + Swift `AppDelegate`), **needs a Mac build** to compile/verify |
| Firebase project + `google-services.json` + APNs key + Edge secrets | ⛔ **TODO — your credentials** |
| **Live end-to-end verification** (client heartbeat ↔ `tick_pause_cues()` cron ↔ Edge ↔ phone) | ⛔ **TODO — this runbook's purpose** |

**Requires sub-minute pg_cron**, which Supabase supports (pg_cron ≥ 1.5 accepts an interval string like
`'10 seconds'`). If a project is pinned to an older pg_cron, fall back to `'* * * * *'` (1-minute) in
`pause-cue-setup.sql` — the cue then fires with up to ~1 min of extra latency.

---

## Step 1 — Firebase / Apple credentials (your accounts)

**Firebase (Android):**
1. console.firebase.google.com → add project → add an Android app with package `org.example.project`.
2. Download `google-services.json` → `androidApp/`. (The build is guarded: without the file the app still
   compiles and runs, the push path is just inert.)
3. Project settings → Service accounts → **Generate new private key** → this JSON is `FCM_SERVICE_ACCOUNT`.

**APNs (iOS):**
1. Apple Developer → Keys → new key with **APNs** enabled → download the `.p8` (`APNS_KEY`), note the
   **Key ID** (`APNS_KEY_ID`) and **Team ID** (`APNS_TEAM_ID`).
2. `APNS_BUNDLE_ID` = the app bundle id; `APNS_HOST` = `api.sandbox.push.apple.com` for dev builds,
   `api.push.apple.com` for TestFlight/App Store.

---

## Step 2 — Deploy the server half (this is the whole server; there is no worker to host)

```bash
# Edge Function secrets (one line each; keep the .p8 newlines or use \n):
supabase secrets set FCM_SERVICE_ACCOUNT="$(cat firebase-service-account.json)"
supabase secrets set APNS_KEY="$(cat AuthKey_XXXX.p8)" APNS_KEY_ID=XXXXXXXXXX APNS_TEAM_ID=YYYYYYYYYY \
                     APNS_BUNDLE_ID=org.example.project APNS_HOST=api.sandbox.push.apple.com

scripts\deploy-supabase.bat   # db push + functions deploy pause-cue + pause-cue-setup.sql
```

`deploy-supabase.bat` is idempotent. Step (3) of it runs `pause-cue-setup.sql`, which provisions the Vault
secrets `omni_edge_base_url` / `omni_service_role_key` (read by `omni_edge_push`) and **schedules** the
`pause-cue-tick` cron at `'10 seconds'`. Verify the job exists:

```sql
select jobname, schedule, active from cron.job where jobname = 'pause-cue-tick';
```

---

## Testing A — Android (true end-to-end)

The live-path verification. Prereqs: steps 1–2 done, and an Android device set up per
`docs/MANUAL_TESTING.md` **Appendix A** — physical phone (§A1) or Play-image emulator (§A2); everything
below is adb-based and identical on either. `adb devices` lists the device.

1. **Deploy + sign in:** `scripts\account1-empty-and-open.bat` (desktop, account 1 — logs out other
   apps, empties local+remote, opens the desktop app), then `scripts\account1-deploy-android.bat`
   (debug phone, same account).
2. **Token + last-phone registered:** Supabase → Table editor → `device_push_token` has an `fcm` row for
   the phone; `account_last_phone` holds the phone's `device_id`. Re-foregrounding the app re-claims it
   (`onAppForegrounded`).
3. **Heartbeats visible:** with the phone foregrounded and the desktop open, `device_heartbeat` has a fresh
   (recent `beat_at`, `closed = false`) row per device with its `next_break_*`. Background/lock the phone →
   its row flips `closed = true` (clean) or its `beat_at` stops advancing (dirty); close the desktop →
   likewise.
4. **The cue fires:** with a screen break pending (walk the schedule forward, or just wait for the next
   pose), background/lock **everything**. Watch `pause_cue_schedule` gain a row (`pushed_at` set) within
   ~10 s of the last device going idle. Edge log (`supabase functions logs pause-cue`): the FCM send. Phone:
   schedules the exact alarm (`adb shell dumpsys alarm | grep org.example.project`), then **speaks at the
   break's end**. **Kill the app before the fire instant** to prove the OS-alarm path.
5. **Reconnect suppresses:** repeat, but re-foreground a device before the cue instant → the next tick sees a
   fresh heartbeat, deletes the `pause_cue_schedule` row and pushes a `cancel`; the phone stays silent. The
   eligibility gate (`poseFinishEligible`) is the last line of defence: a device already back at a screen is
   never told.
6. **Sleep mode suppresses:** press the left menu's **Sleep** toggle → `account_state` shows
   `mode='sleeping'` + `wake_at`; going all-inactive fires no cue. Press **Work** → cues resume.
7. **Desktop-only account:** with no phone registered the Edge Function answers "no push token" (200) and
   nothing errors — the cron still evaluates the account.

**Android runtime prerequisites:** `google-services.json` present at build time, and on Android 12+ the
**Alarms & reminders** special-access grant (else the alarm falls back to inexact).

## Testing B — iOS

Blocked on a **Mac build** first (Kotlin/Native iOS compiles only on macOS; expect interop fixups in the
`iosMain` actuals, then add the **Push Notifications** capability + **Background Modes → Remote
notifications** in Xcode). Device setup — physical iPhone or Simulator — is `docs/MANUAL_TESTING.md`
**Appendix A** (§A3/§A4). The test itself — schedule, cancel, speak — is the same on either; only the
**delivery leg** differs by capability (§A5):

- A **physical iPhone** (dev build, `APNS_HOST=api.sandbox.push.apple.com`) receives the real
  server→APNs push — run Testing A's steps 4–6 with it as the target phone: true end-to-end.
- The **Simulator cannot receive a network APNs push** — inject one locally instead (Xcode 14+), which
  tests the app-side path only (⚠ receipt-only, not the server→APNs leg):

```json
{ "Simulator Target Bundle": "org.example.project",
  "aps": { "content-available": 1 },
  "type": "pause_cue", "action": "schedule", "due_at": "2026-07-19T18:00:00Z" }
```

`xcrun simctl push booted org.example.project schedule.apns` → the app schedules the local notification
and speaks at `due_at`. Inject the same with `"action": "cancel"` → the pending notification is removed.

> iOS caveat: iOS cannot run app code at a local notification's fire time, so the eligibility gate is
> **not** re-checked at delivery there — best-effort on iOS only.

## Testing C — accelerated cross-device (the desktop→phone time-link)

Time acceleration lives only on the desktop (`omniapp.timeSim`); the **time-link** streams the desktop's
accelerated clock to a plugged-in Android **debug** app so both share one `now` and a pose arrives in
seconds instead of hours.

**How it works** (`shared/.../scheduler/debug/TimeLink*` + `androidApp/.../TimeLinkClient.kt`): the
desktop runs a loopback TCP server on port **47615** and keeps `adb reverse tcp:47615 tcp:47615` set up;
the debuggable Android app dials `127.0.0.1:47615` and re-anchors its `SimAppClock` from each
`"<virtualNow> <speed> <inactive01>"` frame. The trailing flag is the **"simulate pause + leap"**
forced-inactivity marker; its `1→0` transition (leap end) is sequenced so neither side derives against the
other's stale *open* session row: the desktop first pushes its own sessions
(`pushOwnActiveSessionsAndWait`), then clears the flag; the phone pushes + re-derives and acks
(`"pushed"`); the desktop waits (`awaitPhoneLeapAcks`, 5 s bound) before its own post-leap derive.
Transport is adb-only and debuggable-only — nothing runs in a release build.

**Steps:**
1. Plug in the phone (USB debugging on) — `adb devices` lists it. Deploy: `scripts\account1-deploy-android.bat`.
2. Launch the desktop under time-sim (`scripts\account1-empty-and-open.bat`). The **Time** panel flips to
   **● Phone link: connected (1)** within seconds. If it stays amber, check `adb` is reachable (or run
   `adb reverse tcp:47615 tcp:47615` yourself).
3. Set speed **60×** or hit **simulate pause + leap → 5 min** with the "inactive:" scope of your choice →
   the phone's now-line and calendar advance in lockstep; the rested breaks, derived pauses and resumed
   schedule appear identically on both.
4. **Reset to real time** when done — the `adb reverse` is removed on dispose and the phone's clock falls
   back to real time.

**Caveats:** the phone adopts the desktop's wall time, so keep both machines NTP-synced; accelerated state
can reach Supabase via a Sync press, so use a throwaway account. And the **server cue path runs on real
wall clock**: `tick_pause_cues()` compares `beat_at` (server `now()`) against the client-published break
window, so under a diverged sim clock the push timing is meaningless — verify the *client* mechanics
(schedule, alarm, speech) under the time-link, but do the **cue-timing** verification (Testing A) at 1×
real time.

## Notes

- Everything push-side is **inert, never failing**, until the secrets exist and a phone registers a
  `device_push_token` row — the Edge Function returns `no push token for device` (200), and `omni_edge_push`
  is a no-op until the Vault secrets are provisioned.
- The cron decides *when* and *who*; the push credentials stay in the Edge Function; the client decides
  *whether to actually speak* (`poseFinishEligible`) — three separable failure domains. When a cue
  misbehaves, check in order: the `pause_cue_schedule` / `device_heartbeat` tables (did the cron see idle
  and push?) → `supabase functions logs pause-cue` (did the send happen?) → `scripts\collect-diagnostics.bat`
  (the phone logs every posted cue, and every crossing swallowed as stale).
- With no account signed in, the cue path is simply inactive; the in-app look-away/pose cues (which need
  no server) still work.
