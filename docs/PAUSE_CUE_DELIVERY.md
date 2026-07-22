# Pause-end voice cue delivery (1.6.0) — runbook

When **every** device on the account has gone inactive while a screen break is waiting to be served (and
the account is not deliberately away — the Sleep/Work toggle), a **phone speaks at the break's end** to
call the user back. Because a backgrounded phone cannot run an in-app timer, the cue is delivered as a
push-triggered **OS-scheduled local notification/alarm**, so it fires even when the app is killed.

The delivery chain (ARCHITECTURE.md §8, PRD §15):

```
active clients ──(Realtime presence WebSocket: {device_id, kind, next_break_end_ms})──▶ Supabase Realtime
                                                                                            │ watched by
                                                                     /listener (always-on Node worker,
                                                                      service role; reads account_state)
                                                                                            │ when 0 devices
                                                                                            │ present + not sleeping
                                                                              POST pause-cue Edge Function
                                                                                            │ FCM / APNs
                                                                phone: onPauseCuePush → OS alarm → speaks
                                                                       (onPauseCueFire, eligibility-gated)
```

There is **no cron, no `pause_cue_schedule` table, no polling**: the retired pg_cron `tick_pause_cues()`
machinery was dropped by migration `20260713000000` and `pause-cue-setup.sql` unschedules any leftover
cron job. The listener exists because pg_cron / an Edge Function **cannot read Realtime Presence** —
presence lives in the Realtime service, not Postgres.

> **Note — two independent Realtime channels.** The presence WebSocket described here (`RealtimePresenceClient`,
> topic `realtime:presence:<user_id>`) carries *only* the pause-cue liveness/break data. Cross-device **document
> sync** rides a **separate** Realtime `postgres_changes` subscription on the `scheduler_snapshot` row
> (`RealtimeSnapshotSubscriber`), which auto-pulls a peer's push (ARCHITECTURE.md §8). They share the transport
> but are otherwise unrelated; nothing in this cue-delivery path depends on the snapshot subscription.

## Status — done vs. remaining

| Piece | Status |
| --- | --- |
| Client presence publishing: signed-in + active clients hold the Phoenix WS on `realtime:presence:<user_id>` and publish `{device_id, kind, next_break_end_ms}` (driven from the engine's active-session beat) | ✅ `RealtimePresenceClient` / `SchedulerEngine.updatePresence()`; `RealtimePhoenixTest` |
| Sleep/Work toggle → `account_state` (listener suppression; persists across restart until the scheduled wake) | ✅ `SetSleepMode` / `publishAccountState` / `resolveSleepModeOnStartup`; `SleepModeTest` |
| `/listener` worker: watches every account's presence channel, fires `pause-cue` ~1 s before the published `next_break_end_ms` when 0 devices present and not sleeping | ✅ `/listener/index.js` (see `/listener/README.md`) — **deploy is manual, below** |
| Supabase schema: `account_state`, `device_push_token`, `account_last_phone`, drops of the retired machinery | ✅ migrations up to `20260716000000`, applied by `deploy-supabase.bat` |
| `pause-cue` Edge Function: real FCM v1 / APNs HTTP/2 sends | ✅ `supabase/functions/pause-cue/index.ts` (**needs secrets**, below) |
| Phone client: FCM receiver + exact alarm + spoken cue, eligibility-gated at fire time | ✅ `PauseCueMessagingService` / `PauseCueScheduler` / `PauseCueAlarmReceiver` → `SchedulerEngine.onPauseCuePush` / `onPauseCueFire` / `poseFinishEligible`; `PauseCueGatewayTest` |
| Phone claims `account_last_phone` at startup and on every app-foreground; push-token registration | ✅ `SchedulerEngine.claimLastPhoneOnStartup` / `onAppForegrounded`; `SchedulerSyncEngine.registerPushToken` (needs a native token → Firebase step) |
| iOS: APNs registration + local-notification cue | 🟡 code written (`iosMain` actuals + `IosPushBridge` + Swift `AppDelegate`), **needs a Mac build** to compile/verify |
| Firebase project + `google-services.json` + APNs key + Edge secrets | ⛔ **TODO — your credentials** |
| Listener deployed to an always-on host | ⛔ **TODO** |
| **Live end-to-end verification** (client WS ↔ Realtime ↔ listener ↔ Edge ↔ phone) | ⛔ **TODO — this runbook's purpose** |

**Spec deltas (PRD §15, specified but not yet implemented):** the listener should fan out to **all** the
account's phones and compute the waiting break **server-side** (message at disconnect-start + waiting-break
length); today it pushes to the single `account_last_phone` ~1 s before the client-published
`next_break_end_ms`. Phone activity should be a **lock/unlock-gated** socket held by the foreground
service; today it is app-foreground one-minute leases (`AndroidForegroundTracker`).

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

## Step 2 — Deploy the server half

```bash
# Edge Function secrets (one line each; keep the .p8 newlines or use \n):
supabase secrets set FCM_SERVICE_ACCOUNT="$(cat firebase-service-account.json)"
supabase secrets set APNS_KEY="$(cat AuthKey_XXXX.p8)" APNS_KEY_ID=XXXXXXXXXX APNS_TEAM_ID=YYYYYYYYYY \
                     APNS_BUNDLE_ID=org.example.project APNS_HOST=api.sandbox.push.apple.com

scripts\deploy-supabase.bat   # db push + functions deploy pause-cue + pause-cue-setup.sql
```

`deploy-supabase.bat` is idempotent; it also **unschedules** the retired `pause-cue-tick` cron if the
project still has one.

---

## Step 3 — Deploy the listener

Any always-on Node ≥ 18 host (Render / Fly.io / Railway free tier — or a local `npm start` terminal for
testing). From `/listener`:

| env var | value |
| --- | --- |
| `SUPABASE_URL` | `https://<ref>.supabase.co` |
| `SUPABASE_SERVICE_ROLE_KEY` | the project's **service-role** key. Secret — never commit it. |
| `EDGE_BASE_URL` | optional; defaults to `${SUPABASE_URL}/functions/v1` |

```bash
cd listener && npm install && npm start
```

It enumerates the accounts via the admin API (rescanned every 60 s), subscribes to each presence channel,
reads `account_state` / `account_last_phone`, and **never writes the DB**. Its stdout is the primary
debugging surface: every presence join/leave and every cue schedule/cancel decision is logged.

---

## Testing A — Android (true end-to-end)

The live-path verification. Prereqs: steps 1–3 done, and an Android device set up per
`docs/MANUAL_TESTING.md` **Appendix A** — physical phone (§A1) or Play-image emulator (§A2); everything
below is adb-based and identical on either. `adb devices` lists the device.

1. **Deploy + sign in:** `scripts\account1-empty-and-open.bat` (desktop, account 1 — logs out other
   apps, empties local+remote, opens the desktop app), then `scripts\account1-deploy-android.bat`
   (debug phone, same account).
2. **Token + last-phone registered:** Supabase → Table editor → `device_push_token` has an `fcm` row for
   the phone; `account_last_phone` holds the phone's `device_id`. Re-foregrounding the app re-claims it
   (`onAppForegrounded`).
3. **Presence visible:** with the phone foregrounded and the desktop open, the listener log shows both
   devices present with their `next_break_end_ms`. Background the phone → it leaves within ~1 min (the
   foreground lease); close the desktop → it leaves.
4. **The cue fires:** with a screen break pending (walk the schedule forward, or just wait for the next
   pose), background/lock **everything**. Listener log: "0 devices present" → a `schedule` POST to
   `pause-cue` ~1 s before `next_break_end_ms`. Edge log (`supabase functions logs pause-cue`): the FCM
   send. Phone: schedules the exact alarm (`adb shell dumpsys alarm | grep org.example.project`), then
   **speaks at the break's end**. **Kill the app before the fire instant** to prove the OS-alarm path.
5. **Reconnect suppresses:** repeat, but re-foreground a device before the cue instant → listener cancels
   (a `cancel` push if one was already scheduled); the phone stays silent. The eligibility gate
   (`poseFinishEligible`) is the last line of defence: a device already back at a screen is never told.
6. **Sleep mode suppresses:** press the left menu's **Sleep** toggle → `account_state` shows
   `mode='sleeping'` + `wake_at`; going all-inactive fires no cue. Press **Work** → cues resume.
7. **Desktop-only account:** with no phone registered the Edge Function answers "no push token" (200) and
   nothing errors — the listener still watches the account.

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
can reach Supabase via a Sync press, so use a throwaway account. And the **live listener path does not
accelerate**: the listener's timer runs on real wall clock against the presence-published
`next_break_end_ms`, so under a diverged sim clock the push timing is meaningless — verify the *client*
mechanics (schedule, alarm, speech) under the time-link, but do the **listener** verification (Testing A)
at 1× real time.

## Notes

- Everything push-side is **inert, never failing**, until the secrets exist and a phone registers a
  `device_push_token` row — the Edge Function returns `no push token for device` (200).
- The listener decides *when* and *who*; the push credentials stay in the Edge Function; the client
  decides *whether to actually speak* (`poseFinishEligible`) — three separable failure domains. Check
  their three logs in that order when a cue misbehaves: listener stdout → `supabase functions logs
  pause-cue` → `scripts\collect-diagnostics.bat` (the phone logs every posted cue, and every crossing
  swallowed as stale).
- With no account signed in, the cue path is simply inactive; the in-app look-away/pose cues (which need
  no server) still work.
