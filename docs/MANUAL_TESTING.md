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
- [ ] For Android tests: `adb devices` lists the phone/emulator; `adb` on `PATH` or under SDK
      `platform-tools`.
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

*(iOS: receipt-only via injected push until a Mac build + real device exist — see the runbook. Leave
unchecked on Windows.)*

---

## 7. Background survival (Android)

- [ ] Foreground service keeps the scheduler ticking with the app backgrounded (notifications still
      fire). Note: backgrounded = **inactive** for presence/leases (§4) — the service keeps the engine
      alive, not the activity signal.
- [ ] Reboot the phone → `BootReceiver` restarts the scheduler; a due cue/notification still fires
      without manually reopening the app (`scheduler-engine-android-background` note).

---

## 8. Per-account isolation (desktop)

State dirs keep accounts from sharing data: acc1 → `~/.omniapp-acc1`, acc2 → `~/.omniapp-acc2`,
acc3 (release) → `~/.omniapp-release`, default → `~/.omniapp`.

- [ ] `account1-empty-and-open.bat` does its three things in order: (1) server-side logout of every
      account-1 app (watch a still-running peer sign itself out on its next Sync, §3), (2) empties
      **local and remote** — after relaunch it does **not** re-pull old cloud data on Sync, (3) opens the
      desktop app signed in as account 1. It does **not** deploy the Android app.
- [ ] `account2-empty.bat` empties account 2 (local + remote) without relaunching; account 1's data
      untouched.

---

## 9. Release deploy verification

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
| 7 Background survival | | |
| 8 Per-account isolation | | |
| 9 Release deploy | | |

Release tag: __________  Tester: __________  Date: __________
