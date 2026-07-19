# Manual testing — release checklist (1.6.0 sync model)

The automated suite (`:shared:jvmTest`, cross-target `commonTest`) covers state mechanics only (see
`ARCHITECTURE.md` §6). Everything below is behaviour it **cannot** reach: real persistence, the
**button-only** cross-device sync, Realtime presence, the external listener + push cue, the background
service, and per-account isolation. Run this before tagging a release; run the section that touches
whatever you changed before merging.

Every step is written as **do this → expect that**. If the expectation does not hold, stop and collect the
evidence (see "Spotting problems" below) before touching anything else.

**The sync model under test (ARCHITECTURE.md §8):** snapshot sync happens **only** when the user presses
the Sync button — sign-in does **not** pull, sign-out does **not** push, there is no change-triggered push
and no timer. Live cross-device activity is a **Realtime presence WebSocket**; the pause-end voice cue is
fired by the external `/listener` worker through the `pause-cue` Edge Function. If you observe any REST
traffic outside a Sync press, a Sleep/Work toggle, or a phone push-token/last-phone write, that is a bug.

**The Sync button:** click the **☁ status chip** (top of the app) → the sync dialog opens → press
**"Fetch from server"**. That one button is `TaskSchedulerViewModel.syncNow()`: it pushes the local
snapshot if dirty, pulls the remote one (last-writer-wins), and merges the device active-session rows both
ways. "Press Sync" below always means this.

## Spotting problems

- **`scripts\collect-diagnostics.bat [stateDir]`** (default `~/.omniapp-acc1`) prints the merged
  cross-device diagnostics timeline: the `[script]` markers, the desktop app's log, and the Android debug
  app's log (pulled over adb). Every sign-in/out, reconcile outcome, derived-pause refresh, posted
  notification, voice cue (including crossings swallowed as stale), and the exact Inactivity bands the
  calendar renders are in there. **Use it instead of describing a calendar anomaly from memory.**
- **Supabase Dashboard → Logs** counts every HTTP request the apps made (see §3a).
- **Listener logs** (wherever `/listener` is deployed — Render/Fly/Railway console, or the local
  `npm start` terminal) show every presence join/leave and every cue schedule/cancel decision.
- **`supabase functions logs pause-cue`** shows every push the Edge Function actually sent and any
  FCM/APNs error.

---

## 0. Prerequisites (one-time / per-machine)

- [ ] `scripts/accounts.env` exists (copied from `accounts.env.example`, filled in). Gitignored.
- [ ] Accounts created: `python scripts/internal/account_db_admin.py signup <user> <pass>` for accounts
      1, 2, 3. Supabase project has **email confirmation disabled** (usernames map to `<user>@omniapp.local`).
- [ ] Server schema deployed: `scripts\deploy-supabase.bat` (idempotent; re-run after schema edits). It
      applies `supabase/migrations/`, deploys the `pause-cue` Edge Function, and runs
      `pause-cue-setup.sql` (which **unschedules** the retired `pause-cue-tick` cron).
- [ ] For Android tests: an Android device set up per **Appendix A** (physical phone or emulator —
      the checklists never care which); `adb devices` lists it; `adb` on `PATH` or under SDK
      `platform-tools`.
- [ ] For iPhone tests: an iOS device set up per **Appendix A** (physical iPhone or Simulator), which
      needs a **Mac** with Xcode 15+ and a JDK 17+ (everything iOS builds only on macOS —
      Kotlin/Native constraint). Where your device type lacks a capability (matrix, §A5), use the
      documented fallback or leave that box unchecked — the test steps themselves are the same.
- [ ] For push-cue tests only (§6): Edge Function `FCM_*` / `APNS_*` secrets set, the `/listener` worker
      deployed and running, and a `device_push_token` row appears after the phone's first launch —
      otherwise the whole push path is inert (see `docs/PAUSE_CUE_DELIVERY.md`).

---

## 1. Automated gate (must be green first)

- [ ] `./gradlew :shared:check` — compile/syntax across targets.
      (Known: JS can be red on missing platform actuals; if so confirm via `:shared:jvmTest` +
      `compileCommonMainKotlinMetadata` for iOS portability — see the `shared-check-jvmtest-gate` note.)
- [ ] `./gradlew :shared:jvmTest` — full state-engine suite passes.
- [ ] No persisted-DB migration regressions: if this release touched `SchedulerState` / any `Persisted*`
      type, confirm a decode test loads a **previous-shape** payload and heals/renders it (CLAUDE.md →
      Persisted-DB compatibility). A blank checkbox here means the release is not ready.

---

## 2. Desktop smoke test (single account)

Launch: `scripts\account2-open.bat` (account 2, data preserved) — or `account1-empty-and-open.bat` for a
clean slate (it first signs account 1's other apps out server-side, empties local + remote, then opens
the desktop app signed in as account 1). Default dev run enables debug tooling (`omniapp.timeSim`).

- [ ] App window opens, calendar + task tree render, no crash on load.
- [ ] Create a list → cell → task; give it a title and weight → it appears in the tree with its priority
      percentage.
- [ ] Complete some work (record a block) → a green calendar block appears labelled with the task title
      (never "(untitled)" — see the `calendar-untitled-tombstone` note).
- [ ] Undo (Ctrl+Z) / Redo across each category (edit / selection / calendar); focus routing behaves
      (`scheduler-history-architecture` note).
- [ ] Add a manual calendar panel, edit it, drag/resize, remove it (PRD §8–§12).
- [ ] Close and relaunch → the task tree, records, and pinned panels are exactly as left (local SQLite,
      ~400 ms save debounce — **no** sync involved).
- [ ] Auto/side/sleep panels regenerate on load (they are derived, not persisted — reconstructibility rule).

---

## 3. Cross-device sync — button-only (one account, two devices)

The core thing only manual testing catches: **nothing syncs until the user presses Sync** — on either
side. Use **one account on two devices**: desktop via `scripts\account1-empty-and-open.bat`, phone via
`scripts\account1-deploy-android.bat` (debug APK, wiped + auto-signed-in as account 1).

- [ ] **Force-logout on empty.** Have the phone app running signed in as account 1, then run
      `account1-empty-and-open.bat` → the phone does **not** immediately react (no timer traffic). Press
      **Sync on the phone** → it signs itself **out** (the `account_logout` marker advanced) instead of
      re-seeding the just-emptied server with its old data. Server tables stay empty.
- [ ] **An edit does not push on its own.** On the desktop, create a task and rename it. Wait ≥1 min.
      Check Supabase Logs → **no** `scheduler_snapshot` write appeared. The chip does not flip to
      "Syncing…" by itself.
- [ ] **Push on Sync.** Press Sync on the desktop → chip shows "☁ Syncing…" then "☁ Synced"; Supabase
      shows exactly one `scheduler_snapshot` write. The pushed payload is the stripped authoritative
      projection (no regenerated auto/side/sleep panels — `SyncPayloadTest` covers the shape; here just
      confirm the row's JSON has no ocean of derived panels).
- [ ] **Pull on Sync.** Redeploy/relaunch the phone (or sign it back in) → it shows **local** (empty)
      state, not the desktop's data — sign-in does not pull. Press **Sync on the phone** → the desktop's
      task appears.
- [ ] **LWW convergence.** Edit a different task title on each side, press Sync on side A, then Sync on
      side B, then Sync on side A again → both sides show the same state (last writer wins, whole-doc;
      no lost tree, no duplicates).
- [ ] **Sign-out is a pure session drop.** Make an edit on the desktop, sign out **without** pressing
      Sync → no push happens. Press Sync on the phone → the edit is absent. Sign the desktop back in and
      press Sync → now the phone's next Sync shows it.
- [ ] **Kill mid-edit.** Kill the desktop app right after an edit (before pressing anything) → on
      relaunch the edit is still there (the ~400 ms local save debounce is independent of sync).
- [ ] **Refresh-token longevity.** Leave a session idle >1 h, then press Sync → it works, no
      "400 refresh token not found" (`sync-refresh-token-rotation` note).
- [ ] **Sync never wedges.** During each Sync press, the chip returns to "☁ Synced" (or "☁ Sync error")
      within seconds — a chip stuck on "☁ Syncing…" means a hung reconcile holding the engine mutex
      (see the `sync-stuck-syncing-mutex-wedge` note) and is a release blocker.

### 3a. Counting API requests (Supabase logs)

The point of the button-only model: **an idle signed-in app makes zero REST requests.** Verify it:

- [ ] Source = **API Gateway / Edge** (`edge_logs`) — one row per HTTP request (`path`, `method`,
      `status_code`). Do **not** add PostgREST logs (they double-count). Scope the time-range picker to
      just the run (free-plan retention is 1 day — query soon after).
- [ ] Leave the desktop signed in with a stable schedule for ~10 min, no edits, no Sync → filter
      `request.path like '/rest/v1/%'` → **no new rows** in that window (the presence WebSocket is not
      REST and does not appear here; token refresh under `/auth/v1/%` is allowed).
- [ ] Press Sync once → a small burst: the `scheduler_snapshot` read/write, the `device_active_session`
      merge, the `account_logout` check. Nothing recurs afterwards.
- [ ] Exact grouped count — **Logs Explorer**:
      ```sql
      select request.path as path, request.method as method,
             response.status_code as status, count(*) as n
      from edge_logs
      cross join unnest(metadata) as m
      cross join unnest(m.request) as request
      cross join unnest(m.response) as response
      where request.path like '/rest/v1/%'
      group by path, method, status order by n desc
      ```
- [ ] **Free-plan impact:** filtering is view-only. The free plan meters egress, Edge Function
      invocations, Realtime messages, MAU, DB size — not raw request count. See **Reports → Usage**.

---

## 4. Device activity — Inactivity bands, device bubble, sleep carving

Activity facts: the desktop is "active" while the app runs signed-in with an interactive screen
(heartbeat-extended sessions); the phone is "active" while the app is **foregrounded** (one-minute
leases — a backgrounded/killed app reads inactive within ≤1 min). Sessions ride **only** the Sync-button
reconcile; Inactivity bands are derived **locally** over own + pulled peer rows.

- [ ] **Own gap.** Sign in on the desktop, close the app for ~5 min, reopen → a greyed **"Inactivity"**
      band covers the gap exactly (leading, interior and trailing gaps all count; the fresh open session
      keeps the band off the now-line).
- [ ] **Live tail.** Lock the desktop / walk away with the app open → the Inactivity band grows live
      behind the now-line (`live-inactivity-tail` note); come back → it stops growing and stays until a
      derive retires it.
- [ ] **Peer coverage removes the band.** Desktop and phone signed into account 1, phone foregrounded
      while the desktop is closed → after pressing Sync on both sides, the desktop-closed window shows
      **no** band (the phone covered it). A window where **both** were inactive shows the band on both
      calendars after each pressed Sync.
- [ ] **Staleness is Sync-bounded.** Before the second device presses Sync, it may still presume the
      peer inactive/active for the un-synced stretch — that is by design. After Sync on both sides, both
      calendars agree.
- [ ] **Phone lease granularity.** Background the phone app (home button, don't kill) → within ~1 min
      its activity ends (visible after the next Sync as the session's end). Foreground it → activity
      resumes. Over-reporting by ≤1 min is the spec's granularity.
- [ ] **Freshly emptied account** (`account1-empty-and-open.bat`) shows the whole past as one Inactivity
      pause on first load — and **stays** that way (no phantom activity resurrected from an old install;
      `allowBackup=false` guards the Android side — `empty-account-whole-past-inactivity` note).
- [ ] **Device bubble.** Hover a **past task panel** on the desktop → the bubble names which devices
      were open ("Open: …") over that stretch; a **dashed horizontal separator** splits the panel where
      the device set changed (`DeviceActivitySegmentsTest` covers the pure logic; here confirm the
      rendering and that hover zones tile without overlap).
- [ ] **Sleep-band carving.** Work through (or simulate, §16 time panel) a scheduled Sleep window on the
      desktop → the "Sleep" band shows a gap over the active stretch, retracting live at the now-line.
      The peer shows the same gap only after **both** sides pressed Sync. Nights with no activity
      evidence stay solid. Nothing is pushed by the carving itself (check §3a: zero writes).

---

## 5. Android deploy — debug (on-device)

Both debug scripts share one app install and wipe local data on deploy by **uninstall + reinstall**
(remote preserved) — *not* `pm clear` (MIUI/HyperOS denies it), and the wipe is only real because
`android:allowBackup="false"` (do not turn backup back on). Shared body:
`internal\deploy-android-debug.bat`.

- [ ] `scripts\account1-deploy-android.bat` — builds the debug APK, uninstalls + reinstalls over adb,
      launches auto-signed-in as account 1 → app opens signed in; local state starts empty until a Sync
      press pulls.
- [ ] `scripts\account2-deploy-android.bat` — same for account 2 → confirms the wipe + re-sign-in swaps
      accounts cleanly (no account-1 data bleed).
- [ ] If a signature clash with the account-3 release build occurs → uninstall + clean install (debug is
      debug-signed, release is release-signed).

---

## 6. Realtime presence + pause-end voice cue (listener path)

This is the **live path that is still unverified end-to-end** (client WS ↔ Supabase Realtime ↔
`/listener` ↔ `pause-cue` Edge Function ↔ FCM/APNs ↔ phone). Full procedures live in
**`docs/PAUSE_CUE_DELIVERY.md`** — do them there, tick here. Inert unless §0 push prerequisites are met.

- [ ] **Presence appears.** With the listener running, foreground the signed-in phone / open the signed-in
      desktop → the listener log shows the device join the account's presence channel (with
      `device_id`, `kind`, `next_break_end_ms`). Background the phone / close the desktop → it leaves.
- [ ] **Cue fires when everyone is gone.** With a screen break pending, background/lock every device on
      the account → listener log shows "0 devices present", then a `schedule` POST to `pause-cue` ~1 s
      before the published `next_break_end_ms`; the phone receives the push, schedules an OS alarm, and
      **speaks at the break's end** — kill the app before the fire instant to prove the OS alarm path.
- [ ] **Reconnect suppresses.** Repeat, but re-foreground a device before the cue instant → the listener
      cancels; the phone stays silent.
- [ ] **Sleep mode suppresses.** Press the left menu's **Sleep** toggle (it flips to "Work") → the
      `account_state` row shows `mode='sleeping'` with `wake_at`; going all-inactive now fires **no**
      cue. Press **Work** (or let the scheduled wake pass, surviving an app restart —
      `resolveSleepModeOnStartup`) → cues resume.
- [ ] **Desktop-only account.** An account with no phone registered gets no push (Edge logs
      "no push token", 200) — and nothing errors.
- [ ] Watch `supabase functions logs pause-cue` throughout — every `schedule`/`cancel` and any FCM/APNs
      error is there.

*(iOS devices run the same steps; where network push isn't available on your device type, tick the push
boxes **receipt-only** via the injected push — Appendix §A5/§A4.)*

---

## 7. Three devices at once — desktop + Android + iPhone (one account)

§3/§4/§6 verified device **pairs**; this section runs one account signed in **simultaneously** on the
desktop, an Android phone, and an iPhone, and checks the invariants still hold with three peers. It is
also the only place the iOS client gets exercised at all.

**What the iPhone can and cannot do today** (read before writing a bug report): the iOS client
participates in **snapshot sync** (interactive sign-in + the Sync button) and **receives the pause-cue
push** (physical device only), but it is **never an active peer** — `isScreenActive()` on iOS is
hardwired `false` (best-effort "cannot tell", `DeviceInfo.ios.kt`), so the iPhone never opens activity
sessions, never joins the presence channel, and never suppresses the cue by being foregrounded. Expect
it to behave like a signed-in-but-always-inactive device throughout §4/§6-style checks. Also: the iOS
build is still 🟡 (written, never compiled — needs a Mac; see the runbook status table), so expect
interop fixups on the first build.

Device setup — physical or virtual, Android and iPhone — is the side tutorial in **Appendix A**; the run
below is device-agnostic and identical either way (§A5 lists the few capabilities a virtual device lacks).

### The three-device run

Get all three signed in to account 1: desktop via `scripts\account1-empty-and-open.bat`, Android via
`scripts\account1-deploy-android.bat` (device per §A1/§A2), iPhone per §A3/§A4 (with one Sync pressed
after sign-in so it holds the data). Have the listener running and its log visible.

- [ ] **Three-way LWW convergence.** Give each device a distinct edit (rename a different task on
      each). Press Sync desktop → Android → iPhone → desktop → Android → all **three** show the same
      state (whole-doc last-writer-wins; no lost tree, no duplicates).
- [ ] **Edits stay local until each device's own Sync.** After the desktop edit + desktop Sync, the
      Android and iPhone still show their pre-edit state until **their own** Sync press (sign-in never
      pulls, nothing pushes on a timer) — and Supabase Logs show `scheduler_snapshot` writes only at
      the Sync presses.
- [ ] **Presence shows exactly two devices.** With all three foregrounded/unlocked, the listener log
      shows the **desktop and Android** joined — and no iOS entry. The iPhone's absence is expected
      (see the capability note above), not a bug.
- [ ] **Activity bands with three devices.** Create a window where only the iPhone was "in use" (apps
      closed on desktop + Android) → after Sync all around, all three calendars show that window as
      **Inactivity** (the iPhone contributes no sessions). A window covered by the desktop **or** the
      Android shows no band on any of the three calendars — the iPhone renders the same bands as the
      others purely from pulled peer rows.
- [ ] **Device bubble.** Hover a past task panel on the desktop → "Open: …" names the desktop/Android
      stretches; the iPhone never appears in the set (no sessions).
- [ ] **Cue fan-out reaches both phones.** With a screen break pending, background/lock the desktop
      and the Android (the iPhone's state is irrelevant — it is never present) → listener log:
      "0 devices present" → one `pause-cue` POST with `device_id:'*'` → the Edge Function fans out to
      **every** `device_push_token` row: the Android (FCM) **and** the iPhone (APNs) both schedule and
      both speak at the break's end. (No network-push capability on your iOS device type? Tick
      receipt-only via the injected push — §A5/§A4.)
- [ ] **Suppression is desktop/Android-only.** Repeat, but re-foreground the **iPhone** before the cue
      instant → nothing is cancelled (it is not a presence device) and both phones still fire.
      Re-foreground the **Android or desktop** instead → the listener cancels; both phones stay silent.
      (iOS cancel is best-effort: iOS cannot re-run the eligibility gate at fire time — runbook caveat.)
- [ ] **Three-way force-logout.** With all three signed in, run `account1-empty-and-open.bat` → the
      Android and the iPhone each keep their session until their next Sync press, at which point each
      signs itself **out** (the `account_logout` marker) without re-seeding the emptied server.

---

## 8. Background survival (Android)

Run this section on a **physical** phone — OEM battery-killer behaviour, reboot survival on real ROMs
and real-world Doze timing are capabilities the emulator lacks (§A5).

- [ ] Foreground service keeps the scheduler ticking with the app backgrounded (notifications still
      fire). Note: backgrounded = **inactive** for presence/leases (§4) — the service keeps the engine
      alive, not the activity signal.
- [ ] Reboot the phone → `BootReceiver` restarts the scheduler; a due cue/notification still fires
      without manually reopening the app (`scheduler-engine-android-background` note).

---

## 9. Per-account isolation (desktop)

State dirs keep accounts from sharing data: acc1 → `~/.omniapp-acc1`, acc2 → `~/.omniapp-acc2`,
acc3 (release) → `~/.omniapp-release`, default → `~/.omniapp`.

- [ ] `account1-empty-and-open.bat` does its three things in order: (1) server-side logout of every
      account-1 app (watch a still-running peer sign itself out on its next Sync, §3), (2) empties
      **local and remote** — after relaunch it does **not** re-pull old cloud data on Sync, (3) opens the
      desktop app signed in as account 1. It does **not** deploy the Android app.
- [ ] `account2-empty.bat` empties account 2 (local + remote) without relaunching; account 1's data
      untouched.

---

## 10. Release deploy verification

Do these last, against the real release artifacts — they use their **own** state dirs and are left
untouched by dev runs.

- [ ] `scripts\account3-deploy-windows.bat` — builds the release app image, installs outside the tree,
      registers Windows-login auto-start, auto-signs-in as account 3 (`~/.omniapp-release`). Debug
      tooling is **off** in this build (no time-sim panel) — confirm it's absent.
- [ ] `scripts\account3-deploy-android.bat` — builds/signs/installs the release APK, launches
      auto-signed-in as account 3; `BootReceiver` survives reboot.
- [ ] After a Windows logout/login cycle, the account-3 desktop app auto-starts and its release DB is
      intact.
- [ ] The always-on account-3 desktop makes **zero** `scheduler_snapshot` writes from time passing
      (§3a check over a quiet hour) — derived reschedules never mark dirty.

---

## Sign-off

| Section | Result | Notes |
|---|---|---|
| 1 Automated gate | | |
| 2 Desktop smoke | | |
| 3 Button-only sync | | |
| 4 Activity / Inactivity | | |
| 5 Android debug | | |
| 6 Presence + pause cue | | |
| 7 Three devices at once | | |
| 8 Background survival | | |
| 9 Per-account isolation | | |
| 10 Release deploy | | |

Release tag: __________  Tester: __________  Date: __________

---

## Appendix A — Device setup tutorial (Android & iPhone, physical or virtual)

Side reference for every section that needs a phone (§3–§8, the runbook's Testing A/B). Set up whichever
device you have, then run the checklists unchanged — **no test step branches on physical vs. virtual.**
The only differences are missing *capabilities*, collected in the matrix (§A5): where your device type
lacks one, use the documented fallback (ticking the box **receipt-only**) or leave the box unchecked
with the device type noted.

### A1. Android — physical phone

1. On the phone: Settings → Developer options → **USB debugging** on. Plug in over USB; accept the
   "Allow USB debugging?" prompt (tick "Always allow from this computer").
2. `adb devices` lists the phone in the `device` state (not `unauthorized`/`offline` — the deploy
   script diagnoses both and prompts to retry).
3. `scripts\account1-deploy-android.bat` → builds the debug APK, **uninstall + reinstall** (wipes local
   data — see §5), launches auto-signed-in as account 1.
4. On first launch accept the notification permission and the one-time keep-alive prompt (Doze
   exemption + OEM autostart). Android 12+: grant **Alarms & reminders**, else the pause-cue alarm
   falls back to inexact.
5. Push prereq (§0): `google-services.json` present at build time → after launch the Supabase
   `device_push_token` table gains an `fcm` row for this device.

### A2. Android — emulator (Android Studio AVD)

1. Android Studio → Device Manager → **Create Device** → pick a **Google Play** (or Google APIs)
   system image, API 34+. A plain AOSP image has no Play services — FCM pushes silently never arrive.
   (No Google account sign-in is needed on the emulator; FCM token registration works without one.)
2. Boot it → `adb devices` shows `emulator-5554`.
3. If a physical phone is attached at the same time, target the emulator explicitly:
   `set ANDROID_SERIAL=emulator-5554` in the terminal before running the script (adb honors the env
   var; without it the script's bare `adb install` fails with "more than one device/emulator").
4. `scripts\account1-deploy-android.bat` — identical from here on: the deploy, the diagnostics pull
   (`collect-diagnostics.bat`) and the time-link (runbook Testing C) are all adb-based, so the
   emulator behaves like hardware.
5. "Background/lock the phone" steps: use the emulator toolbar's Home / power buttons — the foreground
   lease reads inactive within ≤1 min just like hardware.

### A3. iPhone — physical device

One-time on the Mac: clone the repo, install a JDK 17+ and Xcode 15+ (everything iOS builds only on
macOS — Kotlin/Native constraint).

1. Open `iosApp/iosApp.xcodeproj` in Xcode. The Kotlin build phase runs
   `./gradlew :shared:embedAndSignAppleFrameworkForXcode` — the first build is slow, and (first time
   ever) may need `iosMain` interop fixups.
2. Signing & Capabilities: select your Apple Developer team; confirm **Push Notifications** and
   **Background Modes → Remote notifications** are present (add them if missing — the runbook's
   Testing B notes them).
3. Select the plugged-in iPhone as the run target → Run. If prompted on the phone, trust the developer
   profile (Settings → General → VPN & Device Management).
4. **Sign in in-app** — there is no script auto-login on iOS (`StartupLogin.ios.kt` returns `null`):
   tap the **☁ Sign in** chip → email `account1@omniapp.local`, password from `scripts/accounts.env` →
   Sign in. Accept the notification-permission prompt.
5. Push prereq (§0): `APNS_*` secrets set with `APNS_HOST=api.sandbox.push.apple.com` (an Xcode dev
   build uses the APNs **sandbox**) → `device_push_token` gains an `apns` row for the iPhone.
6. Press **Sync** → the account's data appears (sign-in alone does not pull — §3).

### A4. iPhone — Simulator

1. Same Xcode project and Mac prerequisites as §A3; pick any iPhone Simulator as the run target → Run.
   Sign in in-app exactly as §A3 step 4 and press Sync.
2. **Network-push fallback** (the one capability the Simulator lacks — §A5): inject the push locally
   (Xcode 14+), which exercises the app-side schedule/cancel + speak path only, never the server→APNs
   leg. Save the JSON payload from `docs/PAUSE_CUE_DELIVERY.md` (Testing B) as `schedule.apns`, then
   `xcrun simctl push booted org.example.project schedule.apns` → the app schedules the local
   notification and speaks at `due_at`. Inject the same with `"action": "cancel"` → the pending
   notification is removed. Tick the corresponding push boxes as **receipt-only**.

### A5. Capability matrix — where device type still matters

| Capability | Physical Android | Emulator (Play image) | Physical iPhone | iOS Simulator |
| --- | --- | --- | --- | --- |
| Network push delivery (server → device) | ✅ FCM | ✅ FCM | ✅ APNs sandbox (dev build) | ⛔ inject locally (§A4 step 2), receipt-only |
| OEM battery-killers / autostart, reboot survival on real ROMs, real Doze (§8) | ✅ | ⛔ hardware only | — | — |
| Presence / activity sessions | ✅ | ✅ | ⛔ platform-wide: iOS is never an active peer (§7) | ⛔ same |
| Script auto-login | ✅ | ✅ | ⛔ sign in in-app (§A3 step 4) | ⛔ same |

Everything else — sign-in, Sync push/pull, LWW convergence, force-logout, calendar rendering — works
identically on all four, so run those legs on whatever device is at hand.
