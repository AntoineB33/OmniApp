# Manual testing — release checklist

The automated suite (`:shared:jvmTest`, cross-target `commonTest`) covers state mechanics only (see
`ARCHITECTURE.md` §6). Everything below is behaviour it **cannot** reach: real persistence, cross-device
sync convergence, presence, OS-scheduled push cues, the background service, and per-account isolation.
Run this before tagging a release; run the section that touches whatever you changed before merging.

Each subsystem that has its own detailed runbook is **linked, not duplicated** — do the deep steps there,
tick the box here.

---

## 0. Prerequisites (one-time / per-machine)

- [ ] `scripts/accounts.env` exists (copied from `accounts.env.example`, filled in). Gitignored.
- [ ] Accounts created: `python scripts/internal/account_db_admin.py signup <user> <pass>` for accounts 1, 2, 3.
      Supabase project has **email confirmation disabled** (usernames map to `<user>@omniapp.local`).
- [ ] Server schema + pause-cue setup deployed: `scripts\deploy-supabase.bat` (idempotent; re-run after schema edits).
- [ ] For Android tests: `adb devices` lists the phone/emulator; `adb` on `PATH` or under SDK `platform-tools`.
- [ ] For push tests only: Edge Function `FCM_*` / `APNS_*` secrets set and a `device_push_token` row appears
      after first launch — otherwise the whole push path is inert (see `docs/PAUSE_CUE_DELIVERY.md` → Notes).

---

## 1. Automated gate (must be green first)

- [ ] `./gradlew :shared:check` — compile/syntax across targets.
      (Known: JS can be red on missing platform actuals; if so confirm via `:shared:jvmTest` +
      `compileCommonMainKotlinMetadata` for iOS portability — see the `shared-check-jvmtest-gate` note.)
- [ ] `./gradlew :shared:jvmTest` — full state-engine suite passes.
- [ ] No persisted-DB migration regressions: if this release touched `SchedulerState` / any `Persisted*` type,
      confirm a decode test loads a **previous-shape** payload and heals/renders it (CLAUDE.md → Persisted-DB
      compatibility). A blank checkbox here means the release is not ready.

---

## 2. Desktop smoke test (single account)

Launch: `scripts\account2-open.bat` (account 2, data preserved) — or `account1-empty-and-open.bat` for a
clean slate. Default dev run enables debug tooling (`omniapp.timeSim`).

- [ ] App window opens, calendar + task tree render, no crash on load.
- [ ] Create a list → cell → task; give it a title and weight. It appears in the tree.
- [ ] Complete some work (record a block); a green calendar block appears labelled with the task title
      (not "(untitled)" — see the `calendar-untitled-tombstone` note).
- [ ] Undo (Ctrl+Z) / Redo across each category (edit / selection / calendar); focus routing behaves
      (`scheduler-history-architecture` note).
- [ ] Add a manual calendar panel, edit it, drag/resize, remove it (PRD §8–§12).
- [ ] Close and relaunch → the task tree, records, and pinned panels are exactly as left (local SQLite persisted).
- [ ] Auto/side/sleep panels regenerate on load (they are derived, not persisted — reconstructibility rule).

---

## 3. Cross-device sync — two accounts / two windows

The core thing only manual testing catches: **there is no realtime socket**, changes are pulled on start or
via the manual fetch button (`ARCHITECTURE.md` §8).

Open both: `scripts\account1-empty-and-open.bat` (clean account 1) **and** `scripts\account2-open.bat`.

- [ ] Edit a task on account 1 → within the 1-minute server-sync debounce it pushes (watch the sync chip).
- [ ] On account 2, hit **fetch from server** → the edit from account 1 appears. (Pulls are not throttled;
      only the post-save push is — a change ≥1 min after the last push goes immediately.)
- [ ] Make a change on **each** side, then fetch on both → last-writer-wins converges (Phase-1 whole-doc LWW,
      `scheduler-sync-architecture` note). No lost tree, no duplicate rows.
- [ ] Kill account 1 mid-edit before its debounce fires → the local SQLite still has the change on relaunch
      (`markDirty()` is immediate even before the push).
- [ ] Refresh-token longevity: leave a session idle >1h, then make a change → it still syncs, no
      "400 refresh token not found" (`sync-refresh-token-rotation` note).

---

## 4. Inactivity bands (server-derived pauses)

A *pause* = a window when **no** account device was active (app running, signed in, screen interactive).
Derived server-side (`derive_pauses` RPC), never stored (`server-derived-pauses` note).

- [ ] With one account signed in on one device, close it for a few minutes, reopen → a greyed **"Inactivity"**
      band covers the gap (leading gap before first activity included; trailing gap to `now` excluded).
- [ ] Two devices signed into the **same** account overlapping in time → **no** band for the overlap (one was
      active). A gap where **both** were closed → band appears after fetch.
- [ ] Freshly emptied account (`account1-empty-and-open.bat`) shows the whole past as one pause on first load.

---

## 5. Android deploy — debug (on-device)

Both debug scripts share one app install and wipe local data on deploy (remote preserved) — the Android
analog of the desktop per-account state dirs (CLAUDE.md → Account scripts).

- [ ] `scripts\account1-deploy-android.bat` — builds debug APK, installs over adb, `pm clear`, launches
      auto-signed-in as account 1. App opens, syncs down account 1's data.
- [ ] `scripts\account2-deploy-android.bat` — same for account 2; confirms the local wipe + re-sign-in swaps
      accounts cleanly (no account-1 data bleed).
- [ ] If a signature clash with the account-3 release build occurs → uninstall + clean install (debug is
      debug-signed, release is release-signed).

---

## 6. Pause-end voice cue (push delivery)

Full procedures live in **`docs/PAUSE_CUE_DELIVERY.md`** — do them there, tick here. Inert unless §0 push
prerequisites are met. Presence + cue design: `scheduler-presence-pose-cue` note.

- [ ] **Testing C — accelerated end-to-end** (recommended path): desktop under time-sim drives a plugged-in
      debug phone via the time-link (port 47615). Time panel shows **● Phone link: connected**. A rest pose
      arrives in seconds; the phone actually **speaks** at pose end.
- [ ] Scenario #1 (phone-origin reschedule): no server push (origin == last phone).
- [ ] Scenario #2 (desktop postpones the pose-end): immediate `schedule` Edge push; phone re-arms and speaks
      at the new instant. Kill the app first to prove it still fires from the OS alarm.
- [ ] Scenario #3 (handoff): foreground a **second** phone → first phone gets the `cancel` push and stays
      silent; only the last phone speaks. Verify the foreground path, not just cold start.
- [ ] Presence suppression: keep a phone screen **on** through the final minute → stays silent; screen off → speaks.
- [ ] Watch `supabase functions logs pause-cue` throughout — every `schedule`/`cancel` and any FCM/APNs error is there.

*(iOS: receipt-only via injected push — Testing B — until a Mac build + real device exist. Leave unchecked on Windows.)*

---

## 7. Background survival (Android)

- [ ] Foreground service keeps the scheduler ticking with the app backgrounded (notifications still fire).
- [ ] Reboot the phone → `BootReceiver` restarts the scheduler; a due cue/notification still fires without
      manually reopening the app (`scheduler-engine-android-background` note).

---

## 8. Per-account isolation (desktop)

State dirs keep accounts from sharing data: acc1 → `~/.omniapp-acc1`, acc2 → `~/.omniapp-acc2`,
acc3 (release) → `~/.omniapp-release`, default → `~/.omniapp`.

- [ ] Emptying account 1 (`account1-empty-and-open.bat`) wipes **local and remote** — after relaunch it does
      **not** re-pull old cloud data (a local-only wipe would re-sync the old snapshot).
- [ ] `account2-empty.bat` empties account 2 (local + remote) without relaunching; account 1's data untouched.

---

## 9. Release deploy verification

Do these last, against the real release artifacts — they use their **own** state dirs and are left untouched
by dev runs.

- [ ] `scripts\account3-deploy-windows.bat` — builds the release app image, installs outside the tree,
      registers Windows-login auto-start, auto-signs-in as account 3 (`~/.omniapp-release`). Debug tooling is
      **off** in this build (no time-sim panel) — confirm it's absent.
- [ ] `scripts\account3-deploy-android.bat` — builds/signs/installs the release APK, launches auto-signed-in
      as account 3; `BootReceiver` survives reboot.
- [ ] After a Windows logout/login cycle, the account-3 desktop app auto-starts and its release DB is intact.

---

## Sign-off

| Section | Result | Notes |
|---|---|---|
| 1 Automated gate | | |
| 2 Desktop smoke | | |
| 3 Cross-device sync | | |
| 4 Inactivity bands | | |
| 5 Android debug | | |
| 6 Pause cue | | |
| 7 Background survival | | |
| 8 Per-account isolation | | |
| 9 Release deploy | | |

Release tag: __________  Tester: __________  Date: __________
