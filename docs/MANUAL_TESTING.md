# Manual testing — release checklist (1.6.0 sync model)

The automated suite (`:shared:jvmTest`, cross-target `commonTest`) covers state mechanics only (see
`ARCHITECTURE.md` §6). Everything below is behaviour it **cannot** reach: real persistence, the
**bidirectional auto-sync** cross-device sync, the device-heartbeat activity signal, the pg_cron pause cue,
the background service, and per-account isolation. Run this before tagging a release; run the section that
touches whatever you changed before merging.

Every step is written as **do this → expect that**. If the expectation does not hold, stop and collect the
evidence (see "Spotting problems" below) before touching anything else.

**The sync model under test (ARCHITECTURE.md §8):** snapshot sync is **bidirectional and automatic** —
an authoritative local edit auto-pushes ~500 ms after you stop editing, and a peer's push auto-pulls over a
Realtime `postgres_changes` subscription within ~a second. The manual **Sync button** remains as a
force-now fallback — the same reconcile runs on its own at **startup**, on an **account change**, ~500 ms
after an edit, and whenever the Realtime channel pokes or (re)subscribes, and each of those also merges the
**device activity rows** (§4). Cross-device *activity* is a **`device_heartbeat`** row each active device UPSERTs
every ~10 s (the account, the device and the time — the two screen breaks it is waiting on live in the
account-keyed **`device_break`**, written only when one of their due instants changes); the pause-end voice cue is fired by the **`tick_pause_cues()` pg_cron** job through the
`pause-cue` Edge Function. An **idle** signed-in app (no edits, no peer changes) makes no snapshot REST
traffic — the pull is driven by the one Realtime WebSocket — but an **active** app does write the ~10 s
heartbeat; the snapshot push only fires on an authoritative change.

**The Sync button (force-now fallback):** click the **☁ status chip** (top of the app) → the sync dialog
opens → press **"Fetch from server"**. That button is `TaskSchedulerViewModel.syncNow()`: it pushes the
local snapshot if dirty, pulls the remote one (last-writer-wins), and merges the device active-session rows both
ways. "Press Sync" below always means this. It does **exactly what an automatic reconcile does** — it only
forces the timing, so wherever a step says "press Sync" you are collapsing a wait, not enabling an exchange.

## Spotting problems

- **`scripts\collect-diagnostics.bat [stateDir]`** (default `~/.omniapp-acc1`) prints the merged
  cross-device diagnostics timeline: the `[script]` markers, the desktop app's log, and the Android debug
  app's log (pulled over adb). Every sign-in/out, reconcile outcome, derived-pause refresh, posted
  notification, voice cue (including crossings swallowed as stale), and the exact Inactivity bands the
  calendar renders are in there. **Use it instead of describing a calendar anomaly from memory.**
- **Supabase Dashboard → Logs** counts every HTTP request the apps made (see §3a).
- **`device_heartbeat` / `device_break` / `data_payload_sent` / `pause_cue_schedule` tables** (Supabase →
  Table editor) show which devices are fresh (recent `beat_at`), when the account's two screen breaks are next
  due (`device_break.break_5min_due_ms` / `break_15min_due_ms`, written only when one of them changes),
  whether the current idle episode has been claimed (`data_payload_sent`), and whether the cron has pushed a
  cue (`pause_cue_schedule` row with `pushed_at`).
- **`supabase functions logs pause-cue`** (the device's own clean-lock report) and **`… logs pause-cue-cron`**
  (the cron's backstop for a device that died without reporting) show every push actually sent and any
  FCM/APNs error. Which of the two is empty tells you at once which path delivered the cue.

---

## 0. Prerequisites (one-time / per-machine)

- [ ] `scripts/accounts.env` exists (copied from `accounts.env.example`, filled in). Gitignored.
- [ ] Accounts created: `python scripts/internal/account_db_admin.py signup <user> <pass>` for accounts
      1, 2, 3. Supabase project has **email confirmation disabled** (usernames map to `<user>@omniapp.local`)
      **and anonymous sign-ins enabled** (Dashboard → Authentication → Sign In / Providers, mirrored by
      `enable_anonymous_sign_ins` in `supabase/config.toml`) — without the latter no **guest account** can
      be created, so a launch without script credentials stays local-only (§11).
- [ ] Server schema deployed: `scripts\deploy-supabase.bat` (idempotent; re-run after schema edits). It
      applies `supabase/migrations/`, deploys **both** Edge Functions (`pause-cue` + `pause-cue-cron`),
      and runs `pause-cue-setup.sql` (which **schedules** the `pause-cue-tick` cron at `'* * * * *'`).
- [ ] For Android tests: an Android device set up per **Appendix A** (physical phone or emulator —
      the checklists never care which); `adb devices` lists it; `adb` on `PATH` or under SDK
      `platform-tools`.
- [ ] For iPhone tests: an iOS device set up per **Appendix A** (physical iPhone or Simulator), which
      needs a **Mac** with Xcode 15+ and a JDK 17+ (everything iOS builds only on macOS —
      Kotlin/Native constraint). Where your device type lacks a capability (matrix, §A5), use the
      documented fallback or leave that box unchecked — the test steps themselves are the same.
- [ ] For push-cue tests only (§6): Edge Function `FCM_*` / `APNS_*` secrets set, the `pause-cue-tick`
      cron scheduled (via `deploy-supabase.bat`), and a `device_push_token` row appears after the phone's
      first launch — otherwise the whole push path is inert (see `docs/PAUSE_CUE_DELIVERY.md`).

---

## 1. Automated gate (must be green first)

- [ ] `./gradlew :shared:check` — compile/syntax across targets.
      (Known: JS can be red on missing platform actuals; if so confirm via `:shared:jvmTest` +
      `compileCommonMainKotlinMetadata` for iOS portability — see the `shared-check-jvmtest-gate` note.)
- [ ] `./gradlew :shared:jvmTest` — full state-engine suite passes.
- [ ] No persisted-DB migration regressions: if this release touched `SchedulerState` / any `Persisted*`
      type, confirm a decode test loads a **previous-shape** payload and heals/renders it (CLAUDE.md →
      State → Persisted-DB compatibility). A blank checkbox here means the release is not ready.

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
- [ ] **A task panel's menu reaches its task (PRD §8).** Right-click a task panel:
  - [ ] **"edit task"** opens the §13 edition window on that task — the same window the tree cell's own
        **"edit task"** opens (that entry is no longer called "edit").
  - [ ] **"go to task tree"** selects the task's first cell, expanding whatever hid it, scrolling it into
        view, and handing the tree the focus. On a mirrored task it is the FIRST row that is selected.
  - [ ] Neither entry appears on an inactivity/no-screen period, a sleep band, a screen break, a reminder
        tag or an alarm marker.
  - [ ] Delete the task from the tree (empty its cell) with its panel still on the calendar, then
        "go to task tree" on that panel → a message says it is not in the task tree; nothing is selected.
        Pressing anywhere else dismisses the message and still does its normal job.
- [ ] **Timers (PRD §18, the Alarms window's second section).** Open **Alarms** → *Timers*:
  - [ ] **+ Add timer** → a row at 5:00, idle. Set it to `0:20`, press **Start** → the countdown reads down
        every second; press **Pause** → it holds; **Start** (now *Resume*) → it continues from there, not
        from the top; **Reset** → back to 0:20 and idle.
  - [ ] Type a label while it counts down → the countdown does **not** jump (editing settings must never
        disturb the instant it is due at). A duration typed mid-countdown applies at the next start.
  - [ ] Let one run out → the guitar loop rings for the configured seconds, the notification is titled
        **Timer**, and the row is back at its full duration (a timer *resets*; it has no on/off switch).
  - [ ] With a timer running, close and relaunch → it is still running, with the right time left (the end
        instant is persisted, the countdown is derived from it).
  - [ ] An alarm due sooner than a running timer, and the reverse → whichever is sooner rings first, and both
        ring (one OS slot, one merged sweep).
- [ ] Close and relaunch → the task tree, records, and pinned panels are exactly as left (local SQLite,
      ~400 ms save debounce — **no** sync involved).
- [ ] Auto/side/sleep panels regenerate on load (they are derived, not persisted — reconstructibility rule).
- [ ] **System-wide chords (Windows only, ADR 0011).** Open the lateral menu's **Keyboard shortcuts** window:
      the system-wide block must read *"Claimed exclusively"*. Then, with **another application focused**
      (a browser on a Google Doc is the case this was written for):
  - [ ] `Ctrl+Shift+Alt+A` toggles "I'm away" / "I'm back" in OmniApp **and the focused application does
        nothing at all** (Docs must NOT open its comments pane — that regression is the whole point of the hook).
  - [ ] `Ctrl+Shift+Alt+E` starts the 20-second look-away (notification + voice cue), again with the focused
        application unaffected. Pressing it again mid-break restarts it.
  - [ ] Holding either chord down fires **once**, not repeatedly.
  - [ ] On an AZERTY layout, `Shift+AltGr+E` still types its character (the hook must pass AltGr through).
  - [ ] `diagnostics.log` shows `global hotkeys: claim=Exclusive` at startup and one `global hotkey pressed`
        line per press (`scripts\collect-diagnostics.bat`).
- [ ] **The Notifications switch cancels every notification (PRD §11).** With the lateral menu's
      **Notifications** switch on, hover it → the bubble names the live chord (`Ctrl+Shift+Alt+N` unless
      rebound). Then:
  - [ ] Switch it **off** → any OmniApp notification still on screen disappears (Android/iOS; a desktop tray
        balloon fades on its own and is expected to stay).
  - [ ] With it off, strike `Ctrl+Shift+Alt+E` from another application → **no** notification appears (not the
        break's, not the "Shortcut received" receipt) — but the History window's **Notifications** column
        lists both, and `diagnostics.log` marks each `[suppressed: notifications off]`.
  - [ ] With another application focused, strike `Ctrl+Shift+Alt+N` → a **"Notifications on"** notification
        appears (that is the un-mute press's own receipt) and notifications resume.
  - [ ] The switch and the chord are one lever: flipping either moves the other, and the setting survives a
        relaunch and reaches a second device signed in to the same account.
- [ ] **"I'm away" is turned off by an unlock (PRD §15).** Press **I'm away** (the button reads *I'm back*),
      then lock the session (`Win+L`) — it must still read *I'm back* — and log back in: the button must be
      back to **I'm away** within a moment, with no click, and `diagnostics.log` must show
      `desktop session unlock` followed by `"I'm away" cleared: this device was unlocked`. Locking and
      unlocking *without* having pressed the button must change nothing.

---

## 3. Cross-device sync — bidirectional auto-sync (one account, two devices)

The core thing only manual testing catches: **an edit on one device reaches the other automatically**,
without pressing Sync — the push fires ~500 ms after you stop editing, the pull arrives over the Realtime
`postgres_changes` subscription within ~a second. Use **one account on two devices**: desktop via
`scripts\account1-empty-and-open.bat`, phone via `scripts\account1-deploy-android.bat` (debug APK, wiped +
auto-signed-in as account 1). Requires migration `20260722000000` applied (`deploy-supabase.bat`).

- [ ] **Force-logout on empty.** Have the phone app running signed in as account 1, then run
      `account1-empty-and-open.bat` → on its next reconcile the phone signs itself **out** (the
      `account_logout` marker advanced) instead of re-seeding the just-emptied server with its old data.
      Server tables stay empty.
- [ ] **Auto-push.** On the desktop, create a task and rename it. **Without pressing anything**, within
      ~1–2 s Supabase Logs show exactly one `scheduler_snapshot` write and the chip flickers
      "Syncing…" → "Synced". The pushed payload is the stripped authoritative projection (no regenerated
      auto/side/sleep panels — `SyncPayloadTest` covers the shape; here just confirm the row's JSON has no
      ocean of derived panels).
- [ ] **Auto-push coalesces.** Type a rapid burst of edits (rename several times quickly) → **one**
      `scheduler_snapshot` write after you stop, not one per keystroke (the 500 ms debounce).
- [ ] **Auto-pull.** With both devices signed in and idle, make an edit on the desktop → the phone shows
      it within ~a second **without** any Sync press (the Realtime subscription poked a pull). Confirm the
      change appears; no user action on the phone.
- [ ] **No echo storm.** After the two devices converge, watch Supabase Logs for ~30 s → traffic goes
      quiet. There is **no** ping-pong of writes (a pulled change never pushes back — the `revision` guard
      and the reset sync baseline).
- [ ] **Idle is quiet.** Two signed-in apps with no edits → **no** `scheduler_snapshot` writes at all over
      minutes (the pull is WebSocket-driven; the push only fires on an authoritative change).
- [ ] **Concurrent edits MERGE under a conflict.** Take one device briefly offline (kill its network), then
      **add a different task row on each side**, and bring it back → both sides converge on the same state
      **containing BOTH new rows** (three-way merge, not a winner). No lost tree, no duplicates, no cell left
      dangling. Repeat with each side renaming a *different* task (both renames stick) and with one side
      deleting a row the other did not touch (the deletion sticks). Only when both edit **the same field**
      does one win — the remote's value. `diagnostics.log` shows `reconcile: MERGED local changes with remote
      revision N`; a line saying it fell back to the last-write-wins pull instead means no merge ancestor was
      recorded yet (expected exactly once on a DB upgraded from schema v9 — retry the check afterwards).
- [ ] **Manual Sync still works.** Press the Sync button → it still force-reconciles (push-if-dirty /
      pull), a harmless no-op when already converged.
- [ ] **Kill mid-edit.** Kill the desktop app right after an edit (before the ~500 ms push fires) → on
      relaunch the edit is still there (the ~400 ms local save debounce is independent of sync) and it
      auto-pushes on the next launch.
- [ ] **Refresh-token longevity.** Leave a session idle >1 h, then make an edit → the auto-push works, no
      "400 refresh token not found" (`sync-refresh-token-rotation` note).
- [ ] **Sync never wedges.** Each reconcile (auto or button) returns the chip to "☁ Synced" (or
      "☁ Sync error") within seconds — a chip stuck on "☁ Syncing…" means a hung reconcile holding the
      engine mutex (see the `sync-stuck-syncing-mutex-wedge` note) and is a release blocker.

### 3a. Counting API requests (Supabase logs)

The point of the auto-sync model: **an idle signed-in app makes no snapshot REST requests** (the pull is
carried by the already-open WebSocket; a push fires only on an authoritative edit). Verify it:

- [ ] Source = **API Gateway / Edge** (`edge_logs`) — one row per HTTP request (`path`, `method`,
      `status_code`). Do **not** add PostgREST logs (they double-count). Scope the time-range picker to
      just the run (free-plan retention is 1 day — query soon after).
- [ ] Leave the desktop signed in with a stable schedule for ~10 min, no edits, no Sync → filter
      `request.path like '/rest/v1/scheduler_snapshot%'` → **no new rows** in that window (an idle app makes
      no snapshot traffic; the pull is Realtime-driven, the push only fires on an authoritative change). The
      `/rest/v1/rpc/publish_presence` call recurs every ~10 s **while the app is active** — expected, and the
      one steady-state write; token refresh under `/auth/v1/%` is allowed.
- [ ] In the same window, `/rest/v1/rpc/publish_next_break` appears **zero** times while the schedule is
      untouched (it is written only when one of the two break due instants changes), and exactly once shortly
      after you edit the schedule in a way that moves either break.
- [ ] Press Sync once → a small burst: the `scheduler_snapshot` read/write, the `device_active_session`
      merge, the `account_logout` check. Nothing recurs afterwards. **The same burst appears at every
      automatic reconcile** (startup, an account change, ~500 ms after an edit, a Realtime poke or
      (re)subscribe) — it is one reconcile's traffic, not the button's; what makes the idle case quiet is
      that none of those triggers fires, not that the exchange requires a press.
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
leases — a backgrounded/killed app reads inactive within ≤1 min). Sessions ride **every reconcile**, not
just the button's: `syncActiveSessions` runs at the tail of `reconcile()`, so startup, an account change, the
~500 ms auto-push after an edit, a Realtime poke and a (re)subscribe catch-up all exchange them too. What
they never do is ride a *timer* — two idle apps that neither edit nor restart exchange nothing, which is the
only case where a Sync press is still needed. Inactivity bands are derived **locally** over own + pulled peer
rows, and re-derive right after each reconcile.

- [ ] **Own gap.** Sign in on the desktop, close the app for ~5 min, reopen → a greyed **"Inactivity"**
      band covers the gap exactly (leading, interior and trailing gaps all count; the fresh open session
      keeps the band off the now-line).
- [ ] **Live tail.** Lock the desktop / walk away with the app open → the Inactivity band grows live
      behind the now-line (`live-inactivity-tail` note); come back → it stops growing and stays until a
      derive retires it.
- [ ] **Peer coverage removes the band.** Desktop and phone signed into account 1, phone foregrounded
      while the desktop is closed → after each side has reconciled (make an edit on each, or press Sync to
      force it), the desktop-closed window shows **no** band (the phone covered it). A window where **both**
      were inactive shows the band on both calendars once each has reconciled.
- [ ] **An ordinary edit carries the sessions too.** With both devices idle, edit a task title on the phone
      (nothing else) → its ~500 ms auto-push reconcile also pushes its activity rows; the desktop's own next
      reconcile (its next edit, or a Realtime poke from that push) then shows the corrected bands **without
      anyone pressing Sync**.
- [ ] **Staleness is reconcile-bounded, not Sync-bounded.** Until a device has reconciled, it may still
      presume the peer inactive/active for the un-synced stretch — by design. A reconcile happens at startup,
      on an account change, after any edit, and on a Realtime poke/(re)subscribe; only two apps that are
      *both* idle and never restarted stay stale, and the Sync button is the manual collapse for that case.
- [ ] **Phone lease granularity.** Background the phone app (home button, don't kill) → within ~1 min
      its activity ends (visible on the peer after the next reconcile on each side as the session's end).
      Foreground it → activity resumes. Over-reporting by ≤1 min is the spec's granularity.
- [ ] **Freshly emptied account** (`account1-empty-and-open.bat`) shows the whole past as one Inactivity
      pause on first load — and **stays** that way (no phantom activity resurrected from an old install;
      `allowBackup=false` guards the Android side — `empty-account-whole-past-inactivity` note).
- [ ] **Device bubble.** Hover a **past task panel** on the desktop → the bubble names which devices
      were open ("Open: …") over that stretch; a **dashed horizontal separator** splits the panel where
      the device set changed (`DeviceActivitySegmentsTest` covers the pure logic; here confirm the
      rendering and that hover zones tile without overlap).
- [ ] **Sleep-band carving.** Work through (or simulate, §16 time panel) a scheduled Sleep window on the
      desktop → the "Sleep" band shows a gap over the active stretch, retracting live at the now-line.
      The peer shows the same gap only after **both** sides have reconciled (any trigger; press Sync to
      force it). Nights with no activity
      evidence stay solid. Nothing is pushed by the carving itself (check §3a: zero writes).

---

## 4b. The two `t_p` modes — an owed break slides at the screen, happens when you leave

The mode is **which devices are unlocked** (mode 1 = at least one; mode 2 = none), read off the same
account-wide pause §4 draws as its Inactivity band — so the two can never disagree, and the band is how you
read the mode off the screen. Enable the screen-break display switch in the calendar window first, and use a
fast-break script (`*-fast-break*.bat`) so the bars fire in seconds rather than in hours.

- [ ] **Mode 1: the owed period parks at the now-line and nothing is scheduled under it.** Sit at the
      unlocked desktop past a break's due → the band sits **on** the now-line and stays there as the line
      advances (it never falls behind it), and the plan shows **no task** under it. That is "you owe a break"
      as a period, not a hint — it is the README's mode 1 and it is intended.
- [ ] **…and the past behind it is task panels, not breaks.** Scroll back over the stretch you just worked
      through → no break bands in it. A period the line reached was pushed ahead of it and never happened.
- [ ] **The cue still fires once, at the due.** Each break is still announced (notification + voice cue)
      exactly once as the line reaches its slot — **not** repeatedly while the period is parked. Repeats here
      mean something started keying on the drawn start instead of the due (the 2026-07-12 failure).
- [ ] **Serving it clears it.** Use "Look away now" (`Ctrl+Shift+Alt+E`) and let it complete → the conducted
      break is recorded as a real period where it happened, the 20-second bar re-arms from it, and the line is
      free again. A 5- or 15-minute period is cleared only by an actual rest of that length.
- [ ] **Mode 2: leaving makes the break happen.** Press "I'm away" (`Ctrl+Shift+Alt+A`) or lock the machine →
      the Inactivity band opens at the now-line, the mode flips, the plan **re-plans once** (not per tick) and
      the periods sit where the bars put them instead of on the line.
- [ ] **Unlocking returns to mode 1**, because the unlock clears "I'm away" (§15) and closes the pause. One
      re-plan, then the owed period is back on the line.
- [ ] **The Sleep/Work toggle changes none of this.** Switching to Sleep with the machine unlocked must leave
      the placement identical — it says the user has gone to bed, not that no screen is in use. (It was what
      the mode was wrongly read from until 2026-08-28.)
- [ ] **A peer keeps you in mode 1.** With the desktop locked and the phone foregrounded, once both sides
      have reconciled the desktop's Inactivity band is removed over that stretch and the placement is mode 1
      again. Staleness here is reconcile-bounded, exactly as in §4.

---

## 5. Android deploy — debug (on-device)

Both debug scripts share one app install and wipe local data on deploy by **uninstall + reinstall**
(remote preserved) — *not* `pm clear` (MIUI/HyperOS denies it), and the wipe is only real because
`android:allowBackup="false"` (do not turn backup back on). Shared body:
`internal\deploy-android-debug.bat`.

- [ ] `scripts\account1-deploy-android.bat` — builds the debug APK, uninstalls + reinstalls over adb,
      launches auto-signed-in as account 1 → app opens signed in; local state starts empty and the
      sign-in's own reconcile pulls the account's data (no Sync press needed).
- [ ] `scripts\account2-deploy-android.bat` — same for account 2 → confirms the wipe + re-sign-in swaps
      accounts cleanly (no account-1 data bleed).
- [ ] If a signature clash with the account-3 release build occurs → uninstall + clean install (debug is
      debug-signed, release is release-signed).

---

## 6. Device heartbeat + pause-end voice cue (pg_cron path)

These are the **live paths that are still unverified end-to-end** (clean lock ↔ `pause-cue` Edge Function ↔
FCM/APNs ↔ phone, and client `device_heartbeat` UPSERT ↔ `tick_pause_cues()` pg_cron ↔ `pause-cue-cron` Edge
Function ↔ FCM/APNs ↔ phone). Full procedures live in
**`docs/PAUSE_CUE_DELIVERY.md`** — do them there, tick here. Inert unless §0 push prerequisites are met.

**Expected latency, so you don't call a slow tick a failure.** A **clean lock** is reported by the device the
instant its screen goes off, so e1 arms the cue immediately. A **dirty kill** is found only by the cron: an
account is eligible once its newest `beat_at` is older than `2·t_a` (20 s by default) and is picked up by the
next `t_b` pass (`'* * * * *'` — **1 minute**), i.e. up to **~80 s** after the walk-away. Either way the cue
*instant* is anchored on the walk-away (`now()` for e1, `max(beat_at) + t_a/2` for e2) plus `break_length`, so
detection latency delays the arming, never the speaking.

- [ ] **Heartbeats appear.** Foreground the signed-in phone / open the signed-in desktop → `device_heartbeat`
      gains a fresh row per device (recent `beat_at`, and the row holds nothing but the account, the device and
      that time), the account's `data_payload_sent` row reads `false`, and `device_break` holds ONE row for the
      account with `break_5min_due_ms` + `break_15min_due_ms`. Background/lock the phone → its `beat_at`
      stops advancing while the `device_break` row stays put (that is deliberate — it records the state the
      user walked away in); close the desktop → likewise.
- [ ] **Cue fires when everyone is gone — clean lock (e1), armed at once.** With a screen break pending,
      **lock** every device on the account (screen off, app alive) → the last one POSTs `pause-cue`
      immediately, so `pause_cue_schedule` gains a row with `pushed_at` within **a second or two**, the cue
      instant is `lock + break_length`, and the phone schedules an OS alarm and **speaks at the break's end**
      — kill the app before the fire instant to prove the OS alarm path.
- [ ] **Cue fires when everyone is gone — dirty kill (e2), up to ~80 s later.** Repeat, but make the last
      device die without reporting (`adb shell am force-stop org.example.project`) → detection now waits for
      the cron: the account must beat older than **`2·t_a`** (20 s at the default `t_a` = 10 s) and be caught
      by the next **`t_b`** pass, which `pause-cue-setup.sql` schedules at `'* * * * *'` — **one minute**. So
      the push is armed up to **`t_b + 2·t_a` ≈ 80 s** after the walk-away. This is *detection* latency only:
      the cue instant is `t2 + break_length` with `t2 = max(beat_at) + t_a/2`, so a late tick never moves when
      the phone speaks — only how late it learns of it. Verify in `supabase functions logs pause-cue-cron`;
      a **force-stopped** app cannot receive FCM until relaunched, so expect it computed and silent (to hear
      it, kill the app from Recents instead).
- [ ] **Reconnect suppresses.** Repeat, but re-foreground a device before the cue instant → the next `t_b`
      pass (so within ~1 min) deletes the `pause_cue_schedule` row and pushes a `cancel`; the phone stays silent.
- [ ] **Sleep mode suppresses.** Press the left menu's **Sleep** toggle (it flips to "Work") → the
      `account_state` row shows `mode='sleeping'` with `wake_at`; going all-inactive now fires **no**
      cue. Press **Work** (or let the scheduled wake pass, surviving an app restart —
      `resolveSleepModeOnStartup`) → cues resume.
- [ ] **Desktop-only account.** An account with no phone registered gets no push (Edge logs
      "no push token", 200) — and nothing errors.
- [ ] Watch `supabase functions logs pause-cue` **and** `… logs pause-cue-cron` throughout — every
      `schedule`/`cancel` and any FCM/APNs error is in one of the two, and which one identifies the path.

*(iOS devices run the same steps; where network push isn't available on your device type, tick the push
boxes **receipt-only** via the injected push — Appendix §A5/§A4.)*

---

## 7. Three devices at once — desktop + Android + iPhone (one account)

§3/§4/§6 verified device **pairs**; this section runs one account signed in **simultaneously** on the
desktop, an Android phone, and an iPhone, and checks the invariants still hold with three peers. It is
also the only place the iOS client gets exercised at all.

**What the iPhone can and cannot do today** (read before writing a bug report): the iOS client
participates in **snapshot sync** (interactive sign-in + bidirectional auto-sync, with the Sync button as fallback) and **receives the pause-cue
push** (physical device only), but it is **never an active peer** — `isScreenActive()` on iOS is
hardwired `false` (best-effort "cannot tell", `DeviceInfo.ios.kt`), so the iPhone never opens activity
sessions, never writes a heartbeat, and never suppresses the cue by being foregrounded. Expect
it to behave like a signed-in-but-always-inactive device throughout §4/§6-style checks. Also: the iOS
build is still 🟡 (written, never compiled — needs a Mac; see the runbook status table), so expect
interop fixups on the first build.

Device setup — physical or virtual, Android and iPhone — is the side tutorial in **Appendix A**; the run
below is device-agnostic and identical either way (§A5 lists the few capabilities a virtual device lacks).

### The three-device run

Get all three signed in to account 1: desktop via `scripts\account1-empty-and-open.bat`, Android via
`scripts\account1-deploy-android.bat` (device per §A1/§A2), iPhone per §A3/§A4 (signing in reconciles on
its own, so the data arrives without a press). Have the Supabase `device_heartbeat` / `pause_cue_schedule` tables
and `supabase functions logs pause-cue` visible.

- [ ] **Three-device convergence.** Give each device a distinct edit (rename a different task on
      each), one after another. They converge on their own; pressing Sync desktop → Android → iPhone →
      desktop → Android just forces the ordering — either way all **three** end on the same state, and since
      the edits are independent **all three renames survive** (three-way merge). No lost tree, no duplicates.
- [ ] **Edits propagate without any press.** Edit on the desktop and touch nothing else → the Android and
      the iPhone show it within ~a second or two (auto-push, then each peer's Realtime poke → pull). Supabase
      Logs show **one** `scheduler_snapshot` write per edit burst, at the edit — not at a Sync press. A device
      that was closed/offline during the edit catches up at its next launch or reconnect, not at a press.
- [ ] **Heartbeats show exactly two devices.** With all three foregrounded/unlocked, `device_heartbeat`
      has fresh rows for the **desktop and Android** — and no iOS row. The iPhone's absence is expected
      (see the capability note above), not a bug.
- [ ] **Activity bands with three devices.** Create a window where only the iPhone was "in use" (apps
      closed on desktop + Android) → after Sync all around, all three calendars show that window as
      **Inactivity** (the iPhone contributes no sessions). A window covered by the desktop **or** the
      Android shows no band on any of the three calendars — the iPhone renders the same bands as the
      others purely from pulled peer rows.
- [ ] **Device bubble.** Hover a past task panel on the desktop → "Open: …" names the desktop/Android
      stretches; the iPhone never appears in the set (no sessions).
- [ ] **Cue fan-out reaches both phones.** With a screen break pending, background/lock the desktop
      and the Android (the iPhone's state is irrelevant — it never writes a heartbeat) → the last device's
      clean lock POSTs `pause-cue` at once (or, if it was killed instead, the cron POSTs `pause-cue-cron`
      within `t_b + 2·t_a`) → the Edge Function fans out to **every**
      `device_push_token` row: the Android (FCM) **and** the iPhone (APNs) both schedule and both speak at
      the break's end. (No network-push capability on your iOS device type? Tick receipt-only via the
      injected push — §A5/§A4.)
- [ ] **Suppression is desktop/Android-only.** Repeat, but re-foreground the **iPhone** before the cue
      instant → nothing is cancelled (it writes no heartbeat) and both phones still fire. Re-foreground the
      **Android or desktop** instead → the next `t_b` tick cancels (within ~1 min); both phones stay silent. (iOS cancel is
      best-effort: iOS cannot re-run the eligibility gate at fire time — runbook caveat.)
- [ ] **Three-way force-logout.** With all three signed in, run `account1-empty-and-open.bat` → the
      Android and the iPhone each keep their session until their **next reconcile**, at which point each signs
      itself **out** (the `account_logout` marker is re-read on every reconcile) without re-seeding the emptied
      server. The script's wipe of the `scheduler_snapshot` row pokes each subscribed peer over Realtime, so
      expect it within seconds; the marker itself is on no Realtime channel, so a bare logout bump would instead
      land at the peer's next edit, launch or Sync press.

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
      account-1 app (watch a still-running peer sign itself out on its next reconcile — the wipe pokes it
      over Realtime, §3), (2) empties **local and remote** — after relaunch it does **not** re-pull old
      cloud data, (3) opens the desktop app signed in as account 1. It does **not** deploy the Android app.
- [ ] `account2-empty.bat` empties account 2 (local + remote) without relaunching; account 1's data
      untouched.

---

## 11. Accounts — always connected, guest accounts, claiming (PRD §5)

Run this one **without** the account scripts, so the app takes the ordinary first-launch path:
`./gradlew :desktopApp:run -Pomniapp.stateDir=%USERPROFILE%\.omniapp-guest` (use a throwaway state dir;
delete it to redo the first-launch case).

- [ ] **First launch is connected.** The account chip reads **"Guest"** (not "Sign in"), a
      `scheduler_snapshot` row exists for a new user id, and `diagnostics.log` shows
      `guest account created (<id>)`. Nothing in the UI offers to work "without an account".
- [ ] **A guest account is an ordinary account.** Edits persist and push (chip goes Syncing → Guest),
      presence rows appear for it, and the §6 break/pause-cue machinery behaves as on a normal account.
- [ ] **Claiming keeps the data.** Put some tasks in, open the chip → type an email + password →
      **Create account**. The chip becomes "Synced", the tasks are **still there**, and the user id is
      **unchanged** (same `scheduler_snapshot` row, no second row) — the guest account was claimed, not
      replaced. Signing in to that same email from the other device now shows the same tree (§3).
- [ ] **Sign-out lands on a new guest account**, never on "signed out": the chip returns to "Guest", the
      calendar is empty (a brand-new account), and a *new* user id appears server-side.
- [ ] **Nothing is deleted by switching.** After the sign-out above, sign back in to the claimed account:
      its data comes back verbatim (and the guest account left behind keeps its own). Same in reverse
      between two named accounts on one state dir.
- [ ] **This device's screen time is unaffected by switching** — the Inactivity bands / device-activity
      bubble (§4) still show this device's own past sessions after an account change.
- [ ] **Offline first launch degrades gracefully.** Disconnect the network, launch with a fresh state
      dir: the chip reads "No account", the app is fully usable, and when the network returns the next
      sync moment creates the guest account and **adopts** the work done offline (it is not lost).
- [ ] **Upgrade path (existing install).** Launch a build from before this change against a state dir,
      make edits, then launch the new build on the same dir: the data is still there (schema v8 → v9
      files it under the signed-in account, or under the guest account that adopts it if it was signed
      out).

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
| 3 Bidirectional auto-sync | | |
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
6. The account's data appears on its own — signing in is an account change, which reconciles once (§3). Press
   **Sync** only to force it.

### A4. iPhone — Simulator

1. Same Xcode project and Mac prerequisites as §A3; pick any iPhone Simulator as the run target → Run.
   Sign in in-app exactly as §A3 step 4 (the sign-in pulls on its own — §A3 step 6).
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
