# Pause-end voice cue delivery (1.6.0) — runbook

When **every** device on the account has gone inactive while a screen break is waiting to be served (and
the account is not deliberately away — the Sleep/Work toggle), a **phone speaks at the break's end** to
call the user back. Because a backgrounded phone cannot run an in-app timer, the cue is delivered as a
push-triggered **OS-scheduled local notification/alarm**, so it fires even when the app is killed.

The delivery chain (ARCHITECTURE.md §8, PRD §15). Two constants name the whole design: **`t_a`** — how often an
unlocked device writes its presence row (10 s by default, per-account, changeable over HTTP) — and **`t_b`** —
how often the server polls for accounts that stopped (1 min by default).

```
unlocked clients ──(publish_presence RPC every t_a: {device_id} only) ──▶ device_heartbeat {user, device, beat_at}
        │                       └────────────────────────────────────▶ data_payload_sent {user, false}
        │                                                                            │
        └──(publish_next_break RPC, ONLY when the two dues change: ─────▶ device_break│  (the break table)
            {break_5min_due_ms, break_15min_due_ms} — account-keyed)                  │
                                                                                     │
   CLEAN LOCK (the app is alive)            DIRTY KILL (the phone "exploded")
   screen-off ──▶ POST pause-cue ("e1")     nothing is sent ──▶ polled every t_b:
        stop the t_a tick, own user JWT       pg_cron tick_pause_cues() — one fast grouped query:
                    │                         every beat older than 2·t_a, unclaimed, not sleeping,
                    │ rpc evaluate_pause_cue()  a break overdue at the last beat
                    │   DECIDES + CLAIMS                    │ omni_edge_push (pg_net, service role)
                    │   cue at now() + break_length         ▼
                    │                         POST pause-cue-cron Edge Function ("e2")
                    │                                       │ rpc claim_pause_cue()
                    │                                       │   CLAIMS + computes directly
                    │                                       │   cue at t2 + break_length
                    └───────────────┬───────────────────────┘
                                    │ FCM / APNs, high-priority DATA, all phones
                     phone: onPauseCuePush → OS alarm at due_at
                            → speaks (onPauseCueFire, eligibility-gated)
```

**Two detections, two Edge Functions, one difference: what is known about the walk-away instant.**

* **The clean lock — e1, `pause-cue`.** The device listens to the OS lock event, so it sends one HTTP request at
  the instant the screen goes off. That request *is* the walk-away, so the cue is **`now() + break_length`** —
  nothing to estimate. e1 is "the Edge Function that decides whether a data payload must be sent": it re-checks
  liveness (excluding the caller, whose row is necessarily still fresh), the Sleep/Work mode, the claim and the
  overdue break, computes `break_length`, sets `data_payload_sent = true` and pushes.
* **The dirty kill — e2, `pause-cue-cron`.** No such request is ever sent. The `t_b` cron finds an account whose
  presence rows are *all* as old as or older than `2·t_a` and hands it to e2 over pg_net. **The cron made the
  decision** (idle, unclaimed, awake, a break overdue at the last beat), so e2 directly computes `break_length`,
  sends the data payload and sets `data_payload_sent = true`. Here the walk-away must be estimated:
  **`t2 = <the presence row's time> + t_a/2`**, the expected midpoint of the tick interval the device
  disappeared in. Because `t2` comes from the server-stamped row (not from when the poll happened to notice),
  `t_b` is pure *detection* latency: making the cron slower never moves the cue, it only delays arming it.

**Exactly once, either way.** Both paths claim the episode with the *same conditional* update
(`data_payload_sent = true where not data_payload_sent`), so a lock report that raced a tick — or two ticks —
push one cue between them; the loser finds nothing to claim and sends nothing. A device coming back clears the
flag on its next `t_a` beat, re-arming the next episode. What each path computes is shared
(`build_pause_cue(user, anchor)`), so the two can never disagree about which break governs, how long it is, or
what the phone is told to say — only the anchor differs, which is exactly the thing that legitimately differs.

**Only an OVERDUE break fires a cue (migration `20260725000000`).** "Your pause is over" must be spoken only when
the account went idle with a rest break actually DUE — the user walked away *to take* the break — never when it
simply went idle (a lunch break, an errand). So `evaluate_pause_cue` considers only breaks that were
**overdue** at the account's last beat — a published due `<= max(beat_at)` (+ a `t_a` clock-skew slack),
factored into `overdue_break_at_last_beat()` so the cron pre-filter and the decision cannot drift apart.
Each due is the pose's **next placed START** — the instant the recurrence bars put it at, which is also what the
calendar draws and what the local cue sweep fires on (see `restPoseDueMillisByKey` /
`RestPosePresenceWindowTest`; it was the anchored `lastRest + interval` until 2026-08-28, because a pose used to
slide along the now-line — ADR 0003). An already-due pose publishes the constant `0`, a not-yet-due one its real
future instant, and a pose the environment suspends indefinitely (a night, an open-ended inactivity period) has
no placed occurrence inside the search window and is not published at all.
Between the two dues the **longest overdue** break governs (`max(5min,15min)`) — resting 15 min discharges a
5-min pose due at the same instant. If none is overdue the account is claimed (so it is not re-evaluated every
tick) but owes **no** cue. The reference is the account's own newest `beat_at` — the last moment we saw *any* of
its devices — **not** the later cron `now()`, so an *upcoming* break whose instant merely elapsed after
walk-away does not fire. (That reference is also why the break row can be account-keyed: idleness is judged
account-wide anyway.)
The `t_b` tick pre-filters on the same overdue predicate, so an account with nothing overdue is never handed to
e2 at all ("if none in the past, pg_net is not used").

**Why the dirty kill needs a path of its own.** A clean screen-off arms the cue at the lock instant instead of
up to `t_b + 2·t_a` later, and times it exactly. A dirty kill makes no such call — that is precisely what the
`t_a` tick going stale is the backstop for, and why the cron path can never be removed. On restart the app
checks whether the device is locked and resumes the tick if it is not.

**Idleness is account-wide, not per row** (`max(beat_at)` over the account's rows): firing off a single stale
row would speak "your pause is over" while the user is sitting at the *other* device, and would fan the same
push out once per stale row.

**Which rows the client writes, and what each holds** (migration `20260728000000`). Exactly two, and each is
kept to what its job needs:

| Row | Written | Contents |
| --- | --- | --- |
| `device_heartbeat` | every `t_a`, from the moment the device is signed in **and** unlocked | the account, the device, and the server-stamped time of the upsert |
| `data_payload_sent` | the same call as the beat | the account and `false` (the claim flag; `evaluate_pause_cue()` / `claim_pause_cue()` set it `true`) |
| `device_break` | only when the app calculates a **different** pair of dues | the account and when each of the two screen breaks next comes due |

The break row is account-keyed because both dues are `lastRest + interval` over the *synced* screen-break
config — every device of the account computes the same pair, so a per-device row was N copies of one fact. It
carries **no length**: the cue instant is `t2 + length`, and the length belongs to the break *kind*, which the
server owns (`break_config.length_ms`, else the kind's 5/15-min default). The debug fast-break knobs therefore
no longer shorten the cue delay by themselves — the `*-fast-break.bat` scripts set `break_config.length_ms` for
the account (via `account_db_admin.py break-length`) so a 5-second break still speaks 5 seconds after the
walk-away. (`t2 + length` on the cron path; `now() + length` on the clean-lock path, which is what the
fast-break scripts exercise — the phone is locked deliberately, so the app reports it.)

**This is a pg_cron + presence-table design (2026-07-23), which replaced the external Fly.io `/listener`.**
The listener existed only because pg_cron / an Edge Function **cannot read Realtime Presence**. But the
listener was itself a heartbeat-and-poll process (a `published_at_ms` liveness stamp + a 10-s `evaluate()`
tick), so moving the same heartbeat onto a plain table lets pg_cron do the poll centrally — and drops the
always-on host entirely. Detecting a locked phone is fundamentally a heartbeat-timeout problem either way;
this just puts the timeout in Postgres.

> **Note — the only live Realtime channel is document sync.** Cross-device **document sync** rides a Realtime
> `postgres_changes` subscription on the `scheduler_snapshot` row (`RealtimeSnapshotSubscriber`), which
> auto-pulls a peer's push (ARCHITECTURE.md §8). Nothing in this cue-delivery path uses a WebSocket any more —
> the presence tick is a PostgREST RPC and the cue decision is a cron job.

## Status — done vs. remaining

| Piece | Status |
| --- | --- |
| Client `t_a` tick: signed-in + unlocked clients call `publish_presence` every `t_a` with `{device_id}` (upserting the presence row **and** the account's `data_payload_sent = false`) and adopt the `t_a` the RPC returns | ✅ `DeviceHeartbeatPublisher` / `SchedulerEngine.updatePresence()`; `PresenceTickTest`, `PauseCueGatewayTest` |
| Client break write: `publish_next_break(break_5min_due_ms, break_15min_due_ms)` **only when the two dues change** (retried until it lands, never cleared on lock) | ✅ migrations `20260726000000` + `20260728000000`; `PresenceTickTest`, `PauseCueGatewayTest`, `RestPosePresenceWindowTest` |
| Device↔account exclusivity: a beat evicts the same device's presence rows under any other account, and an account left with no presence rows loses its break + claim rows; the client re-asserts its break on every activation | ✅ migrations `20260727000000` + `20260728000000`; `PresenceTickTest` |
| Clean screen-off: stop the tick + call **e1** directly (excluding this device), which times the cue at `now() + break_length`; restart-after-kill resumes the tick iff the device is unlocked | ✅ `DeviceHeartbeatPublisher` → `SchedulerSyncEngine.notifyScreenOff`; `isScreenActive()` (`DesktopSessionTracker` / `AndroidUnlockTracker`) sampled on the first activity beat |
| Sleep/Work toggle → `account_state` (cue suppression; persists across restart until the scheduled wake) | ✅ `SetSleepMode` / `publishAccountState` / `resolveSleepModeOnStartup`; `SleepModeTest` |
| Server `t_b` job: `tick_pause_cues()` runs one fast grouped query — idle for `2·t_a`, unclaimed, awake, an **overdue** break — and hands those accounts to **e2** | ✅ migrations `20260724000000` → `20260729000000` + `pause-cue-setup.sql` (`cron.schedule('pause-cue-tick','* * * * *', …)`) |
| Clean-lock decision: `evaluate_pause_cue()` re-checks liveness, fires only on an **overdue** break (a published due `<= max(beat_at)`), claims, computes `now() + break_length` | ✅ migrations `20260724000000` + `20260725000000` (overdue gate) + `20260728000000` (account-keyed) + `20260729000000` (walk-away anchor) |
| Dirty-kill push: `claim_pause_cue()` claims and computes `t2 + break_length` **without re-deciding** (the cron decided); both paths share `build_pause_cue(user, anchor)` | ✅ migration `20260729000000` |
| HTTP-changeable config: `t_a` (`app_config.tick_seconds`) and each break's length + vocal message (`break_config`) | ✅ migration `20260724000000`; see **Step 3** below |
| Supabase schema: `device_heartbeat`, `device_break`, `data_payload_sent`, `app_config`, `break_config`, `pause_cue_schedule`, `account_state`, `device_push_token`, `account_last_phone` | ✅ migrations up to `20260728000000`, applied by `deploy-supabase.bat` |
| `pause-cue` Edge Function ("e1", clean lock): user-JWT-only, evaluate + claim + fan out to every phone | ✅ `supabase/functions/pause-cue/index.ts` (**needs secrets**, below) |
| `pause-cue-cron` Edge Function ("e2", dirty kill): service-role-only, claim + compute + fan out; also the cron's `cancel` pass | ✅ `supabase/functions/pause-cue-cron/index.ts` (same secrets — they are project-wide) |
| Shared delivery half (token lookup + real FCM v1 / APNs HTTP/2 sends), so a delivery change can never apply to only one path | ✅ `supabase/functions/_shared/push.ts` |
| Phone client: FCM receiver + exact alarm + spoken cue (the configured `voice_cue`), eligibility-gated at fire time | ✅ `PauseCueMessagingService` / `PauseCueScheduler` / `PauseCueAlarmReceiver` → `SchedulerEngine.onPauseCuePush` / `onPauseCueFire` / `poseFinishEligible` |
| Phone claims `account_last_phone` at startup and on every app-foreground; push-token registration | ✅ `SchedulerEngine.claimLastPhoneOnStartup` / `onAppForegrounded`; `SchedulerSyncEngine.registerPushToken` (needs a native token → Firebase step) |
| iOS: APNs registration + local-notification cue; lock detection via `isProtectedDataAvailable` | 🟡 push code written (`iosMain` actuals + `IosPushBridge` + Swift `AppDelegate`), **needs a Mac build**; `isScreenActive()` on iOS is still a hardcoded `false` — wire it to `UIApplication.isProtectedDataAvailable` in that same pass |
| Firebase project + `google-services.json` + APNs key + Edge secrets | ⛔ **TODO — your credentials** |
| **Live end-to-end verification** (client `t_a` tick ↔ lock → e1 ↔ phone, and `tick_pause_cues()` ↔ e2 ↔ phone) | ⛔ **TODO — this runbook's purpose** |

`t_b` uses a plain 1-minute schedule, so no pg_cron version floor applies. Sub-minute values (`'10 seconds'`)
need pg_cron ≥ 1.5, which Supabase has; lowering `t_b` only shortens *detection*, never the cue instant — the
one case where it matters is a debug break shorter than a minute (see the caveat under Step 3).

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

scripts\deploy-supabase.bat   # db push + functions deploy pause-cue AND pause-cue-cron + pause-cue-setup.sql
```

> **Windows: do NOT paste the `$(cat …)` lines into PowerShell or cmd.** Neither expands `$( )`, so the secret
> is stored as the literal text `$(cat firebase-service-account.json)`. Nothing complains — the value is set,
> the functions deploy, the whole decision path runs — and delivery fails only at the last step, inside
> `_shared/push.ts`'s `JSON.parse(FCM_SERVICE_ACCOUNT)`. The tell is a `502 push failed: Unexpected token '$',
> "$(cat …"... is not valid JSON` in the phone's `diagnostics.log` (or in `supabase functions logs pause-cue`).
> Observed 2026-07-27; cost a full test cycle. On Windows, write the value to a temp env file instead — a
> secret's value may not span lines, so the JSON has to be flattened first:
>
> ```powershell
> $one = ((Get-Content -Raw firebase-service-account.json) -replace "\r?\n","").Trim()
> [IO.File]::WriteAllText("$env:TEMP\fcm.env", "FCM_SERVICE_ACCOUNT=$one", (New-Object Text.UTF8Encoding $false))
> supabase secrets set --env-file "$env:TEMP\fcm.env"
> ```
>
> Verify without waiting for a real pause — a `cancel` fan-out exercises the exact same credential path and is
> inert on the phone:
>
> ```powershell
> curl.exe -s -X POST "https://<ref>.supabase.co/functions/v1/pause-cue-cron" `
>   -H "Authorization: Bearer $env:SUPABASE_SERVICE_ROLE_KEY" -H "Content-Type: application/json" `
>   -d '{\"user_id\":\"<account uuid>\",\"action\":\"cancel\"}'
> ```
>
> `FCM 404 … UNREGISTERED` means the credential is good and only the token is stale; `Unexpected token '$'`
> means the secret is still the unexpanded string.

The secrets above are **project-wide**, so setting them once covers both Edge Functions. Both must be deployed:
`tick_pause_cues()` reaches `pause-cue-cron` by name and the app reaches `pause-cue` by name, so deploying only
one silently disables that half of the delivery (the other half keeps working, which is exactly how it hides).

`deploy-supabase.bat` is idempotent. Step (3) of it runs `pause-cue-setup.sql`, which provisions the Vault
secrets `omni_edge_base_url` / `omni_service_role_key` (read by `omni_edge_push`) and **schedules** the
`pause-cue-tick` cron (`t_b`) at `'* * * * *'` (every minute). Verify the job exists:

```sql
select jobname, schedule, active from cron.job where jobname = 'pause-cue-tick';
```

---

## Step 3 — Tuning `t_a` and the breaks over HTTP (no redeploy)

`t_a` and each break's length + vocal message are **per-account rows**, guarded by own-row RLS — so "changed by
an HTTP request" is a plain authenticated PostgREST upsert. Both take effect within one tick / one cron pass;
nothing is rebuilt or redeployed. (`t_b` is not in this set: it is the cron schedule, changed in
`pause-cue-setup.sql` + `deploy-supabase.bat`.)

```bash
# A signed-in access token (the same one the app uses):
TOKEN=$(curl -s "$SUPABASE_URL/auth/v1/token?grant_type=password" -H "apikey: $ANON_KEY" \
        -H 'Content-Type: application/json' \
        -d '{"email":"acc1@omniapp.local","password":"..."}' | jq -r .access_token)
USER=$(curl -s "$SUPABASE_URL/auth/v1/user" -H "apikey: $ANON_KEY" -H "Authorization: Bearer $TOKEN" | jq -r .id)
H=(-H "apikey: $ANON_KEY" -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json'
   -H 'Prefer: resolution=merge-duplicates')

# t_a → 5 s (every device re-paces itself on its next tick; the server's idle window follows as 2·t_a = 10 s).
curl -X POST "$SUPABASE_URL/rest/v1/app_config" "${H[@]}" \
     -d "{\"user_id\":\"$USER\",\"tick_seconds\":5}"

# The 5-minute break: cue 90 s after the account goes idle, with a different bundled vocal message.
curl -X POST "$SUPABASE_URL/rest/v1/break_config" "${H[@]}" \
     -d "{\"user_id\":\"$USER\",\"break_kind\":\"5min_break\",\"length_ms\":90000,
          \"voice_cue\":\"resume_work\",\"voice_message\":\"Resume your work\"}"
```

- `break_kind` is `5min_break` or `15min_break` — the stable `ScreenBreak.key` the client publishes in its
  presence row, so the two sides agree on which break is waiting even when the debug fast-break knobs have
  rewritten its drawn duration.
- `length_ms` **null / no row** ⇒ the server uses the length the client published (the app's own drawn break).
  Setting it overrides the cue timing from the server alone, without touching the calendar.
- `voice_cue` names a cue **bundled in the app** (`look_away`, `resume_work`, `pause_over` — the pre-rendered
  WAVs of `VoiceCue`); an unknown id falls back to `pause_over`. The phone plays a clip, it does not synthesize
  arbitrary text, so `voice_message` is carried for logging and as the TTS fallback where a platform has one.
- **Caveat — a break shorter than `t_b`.** The cue *instant* never depends on `t_b`, but the *push* does: on the
  **cron** path, with a break length under a minute, e2 can only arm an alarm that is already past (the phone
  then speaks immediately, which is deliberate — see `build_pause_cue`'s note). For sub-minute debug breaks rely
  on the **clean screen-off** path, which is unaffected — it fires at the lock instant and anchors there — or
  lower the cron to `'10 seconds'` in `pause-cue-setup.sql`.

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
3. **The `t_a` tick is visible:** with the phone foregrounded and the desktop open, `device_heartbeat` has a
   row per device — `{ user_id, device_id, beat_at }` and nothing else — whose `beat_at` advances every `t_a`,
   and the account's `data_payload_sent` row stays `false`. The breaks the account is waiting on are in
   `device_break` instead — one row for the account, holding `break_5min_due_ms` + `break_15min_due_ms` — whose
   `updated_at` does **not** move on the tick: it changes only when a due does. Lock
   the phone → its `beat_at` stops advancing; close the desktop → likewise. (Optional: bump `t_a` per Step 3 and watch the row's cadence change without restarting anything.)
4. **The cue fires (clean lock → e1):** with a screen break pending (walk the schedule forward, or just wait for
   the next pose), lock **everything**. The last device's clean screen-off calls **e1** immediately. Watch the
   account's `data_payload_sent` row flip to `true` (the claim) and `pause_cue_schedule` gain a row with the
   computed `due_at` + `break_kind`. Edge log (`supabase functions logs pause-cue`): the FCM send. Phone:
   schedules the exact alarm (`adb shell dumpsys alarm | grep org.example.project`), then **speaks at
   `<lock instant> + break_length`** — `due_at` should be within a second of when you pressed the button.
   **Kill the app before the fire instant** to prove the OS-alarm path.
4b. **The cue fires (dirty kill → e2):** repeat, but instead of locking cleanly, force-stop the app
   (`adb shell am force-stop org.example.project`) with the desktop already closed, so no lock report is ever
   sent. Nothing happens for up to `t_b + 2·t_a`; then `supabase functions logs pause-cue-cron` shows the send
   and `due_at` lands at `<last beat> + t_a/2 + break_length`. This is the path a phone that died exercises, and
   it is the only one that proves the cron half — a clean lock never reaches it.
   **Verify this one in the Edge log, not by listening.** `am force-stop` puts the app in Android's *stopped
   state*, and the OS delivers no FCM message to a stopped app until the user launches it again — so the
   detection + decide + send half runs correctly and the phone still stays silent. (The payload is queued and
   arrives when the app is next opened, at which point its `due_at` is long past and the alarm fires at once.)
   That is faithful to what it models: a phone that truly died hears nothing either, and the point of e2 is the
   fan-out to the account's *other* phones. To hear it, kill the app the way the OS does — swipe it from
   Recents, or let the OEM battery manager reap it — instead of force-stopping it.
5. **Reconnect suppresses:** repeat, but unlock a device before the cue instant → its next tick clears
   the account's `data_payload_sent`, and the next `t_b` pass sees a fresh row, deletes the `pause_cue_schedule` row and
   pushes a `cancel`; the phone stays silent. The eligibility gate (`poseFinishEligible`) is the last line of
   defence: a device already back at a screen is never told.
5b. **One cue per episode:** while everything stays locked, no further push goes out however many `t_b` passes
   run — the claim (`data_payload_sent = true`) is what guarantees it. Unlock and re-lock → a new cue.
6. **Sleep mode suppresses:** press the left menu's **Sleep** toggle → `account_state` shows
   `mode='sleeping'` + `wake_at`; going all-inactive fires no cue. Press **Work** → cues resume.
7. **Desktop-only account:** with no phone registered either Edge Function answers "no push token" (200) and
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
wall clock**: both `now()` (e1) and `beat_at + t_a/2` (e2) are server instants, so under a diverged sim
clock the push timing is meaningless — verify the *client* mechanics (schedule, alarm, speech) under the
time-link, but do the **cue-timing** verification (Testing A) at 1× real time.

## Notes

- Everything push-side is **inert, never failing**, until the secrets exist and a phone registers a
  `device_push_token` row — the Edge Function returns `no push token for device` (200), and `omni_edge_push`
  is a no-op until the Vault secrets are provisioned.
- **Who decides what:** on the clean lock, **e1 + `evaluate_pause_cue()`** decide; on the dirty kill, the **cron**
  decides and **e2 + `claim_pause_cue()`** only claim/compute/send. The push credentials stay in the Edge
  Functions; the client decides *whether to actually speak* (`poseFinishEligible`) — separable failure domains.
  When a cue misbehaves, check in order: `device_heartbeat` (are the `t_a` ticks arriving, and did they stop when
  the device locked?) → `device_break` (are the two dues the ones you expect, and is at least one of them in the
  past?) → `pause_cue_schedule` / `data_payload_sent` (did anything get claimed and pushed?) →
  `supabase functions logs pause-cue` **and** `supabase functions logs pause-cue-cron` (which path ran, and did
  the send happen?) → `scripts\collect-diagnostics.bat` (the phone logs every posted cue, and every crossing
  swallowed as stale). Which of the two logs is empty tells you immediately whether the lock report went out.
- **Failure modes of the claim.** Both functions claim *before* sending: if the function is unreachable or the
  RPC fails, nothing is claimed and the next `t_b` pass retries — the safe direction. If the RPC succeeds but the
  FCM/APNs send then fails, that episode's cue is lost (the claim is already committed); the Edge log shows the
  502 and the next idle episode is unaffected.
- **The one thing the split gives up.** The cron scans, then pg_net dispatches asynchronously; e2 does not
  re-test liveness in between, because the cron's `having` clause *is* the decision. An account that comes back
  inside that window is cued anyway, and the cron's cancel pass retracts it within one `t_b` — the same backstop
  that has always covered "the user came back before the break ended".
- **Nothing here runs while the device is locked**, which is the point: the `t_a` tick only ticks on an unlocked,
  signed-in device, so its battery cost is a ~200-byte HTTPS call every 10 s of *active use* and zero otherwise.
  For one phone + one computer that is also well inside the Supabase free plan — DB writes, not Edge
  invocations; e1 is invoked once per idle episode, not once per tick.
- The app is always connected to an account (core PRD §5), guest accounts included, so this path is live for every launch; only a device that has not managed to create its account yet has it inactive. In that case the in-app look-away/pose cues (which need
  no server) still work.
