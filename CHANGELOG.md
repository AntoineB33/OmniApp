# Changelog

Dated history extracted from `CLAUDE.md`. `CLAUDE.md` holds only the active invariants; the *why* behind each
decision lives in `docs/adr/`. This file answers "when did this change, and what did it replace?"

Newest first within each section.

---

## 1.6.0 — spec deltas and their status

Check here before assuming the code matches the docs.

### No-screen / inactivity calendar entities (PRD §8) — SHIPPED 2026-07-19

`TaskPanel.noScreen` / `TaskPanel.inactivity` user-authored panels (authoritative, persisted + synced, old
payloads decode with the flags defaulted); the "add a no-screen period" / "add an inactivity period" contextual
menu options (1-hour default span at the click, then drag/resize); a no-screen period gets **Edit** too — a
times-only edit window, `ManualEntryEditWindow(timesOnly)` — while an inactivity period stays Remove-only (no task
behind it); the automatic **override/trim** both ways (`SchedulerReducer.resolveScreenOverrides`, wired into
add/update/move/resize/replace). Off-screen tasks and inactivity periods conflict with nothing and may overlap.
Tests: `NoScreenInactivityPanelTest`.

**Rendering revised 2026-08-20 to the two-LAYER model** (ADR 0002): the derived "No screen" band is gone and the
derived "Inactivity" band became the PAST-GAP grey band. A hand-added no-screen panel is now "a period asserting
both layers" (a faint outlined region, no pattern of its own); a hand-added inactivity panel is a solid GREY block,
and grey now means the scheduler places nothing there.

### Screen-switch enforcement (PRD §9) — SHIPPED 2026-07-19

`SchedulerDomain.fillSchedule` classifies the timeline by the no-screen periods (they are *not* occupancy
obstacles): on-screen tasks only outside them, off-screen tasks only inside them (none ⇒ never scheduled). A chunk
crossing a screen-zone edge is truncated like a pinned obstacle, unlike the screen-break resume.

- **Revised 2026-08-04.** The break's accepted set was `doableDuringBreak` alone, over the break's WHOLE length,
  which let an ON-screen task be scheduled inside a screen break and made the 20-s look-away an exclusion of every
  non-break-doable task (a spurious influence field every 20 min, forever). Replaced by the periods mapping in
  ADR 0001 §4.
- **Revised again 2026-08-09** to the three test-11 shapes: look-away accepts nobody; the 5-min pose is a closed
  first minute then `!onScreen && doableDuringBreak`; the 15-min pose is one open period accepting every
  `!onScreen` task.

Tests: `NoScreenInactivityPanelTest` (the closed head, the look-away, the on-screen refusal,
`the_15min_pose_accepts_every_off_screen_task_from_its_very_first_second`,
`the_5min_pose_still_refuses_an_off_screen_task_that_is_not_break_doable`),
`SchedulerPlanTest.a_15min_pose_at_the_now_line_is_one_open_period_accepting_the_off_screen_tasks`,
`SchedulerScreenFlagsTest`.

### Past no-screen ⇒ past inactivity (PRD §9/§12) — SHIPPED 2026-07-19

The schedule-advance (and `ReportDeviceSleep`) bank **no record** over a no-screen period for an on-screen task
(`appendRecordOutsideNoScreen` — the app assumes nothing happened), and the covered span is **materialized as a
real "Inactivity" panel** (`materializePastInactivity` in `SchedulerReducer` — outside Undo/Redo like the record
bank, never a syncable change on its own, skips spans an inactivity panel already covers, drops sub-minute
slivers).

Also: the calendar menu on a **sleep band** leads with Edit (opens the §17 sleep-schedule window, no Remove/move);
a **screen-break panel** deliberately has NO Edit (user-confirmed 2026-07-19; PRD §8 reworded to match).

### Phone activity = lock/unlock-gated heartbeat (PRD §15 / ARCHITECTURE §8) — SHIPPED 2026-07-19

Heartbeat replaced the WebSocket 2026-07-23; on-device verification pending.

`isScreenActive()` on Android is `AndroidUnlockTracker.unlocked` (SCREEN_OFF / SCREEN_ON / USER_PRESENT dynamic
receiver + Keyguard/PowerManager initial state; no keyguard ⇒ SCREEN_ON is the unlock). The device runs its `t_a`
presence tick while unlocked and, at lock, stops it and reports the screen-off straight to the Edge Function
(`notifyScreenOff`).

An unlock/lock flip and every app-foreground call `SchedulerEngine.onPlatformActivityChanged()` (an immediate beat
sample) so the tick resumes / the screen-off report goes out within moments, not at the next minute beat. Same on
the desktop via `DesktopSessionTracker` (JNA session lock/unlock); a restart after an abrupt kill resumes the tick
iff `isScreenActive()` says the device is unlocked.

One-time first-startup prompt (`MainActivity.maybePromptKeepAliveOnce`, flag in SharedPreferences): Doze exemption
(`REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`, added to the manifest) + best-effort OEM autostart settings
(MIUI/Huawei/ColorOS/Vivo). `AndroidForegroundTracker` remains only for the resume poke.

**iOS gap:** `isScreenActive()` there is still a hardcoded `false`. Wiring it to
`UIApplication.isProtectedDataAvailable` needs the Mac build that the iOS push code is already waiting on.

### Server-side break push (PRD §15) — SHIPPED 2026-07-19

Moved off the listener onto pg_cron 2026-07-23; moved off the `t_a` beat onto the event-driven `device_break` row
2026-07-26; live verification pending. See ADR 0006.

### Phone calendar gestures (PRD §8) — SHIPPED 2026-07-19

On-device verification pending. Pinch zoom already existed; added **double-tap-and-drag** zoom (exponential in drag
distance, anchored at the tap; a clean double-tap-release is left unconsumed), the **double-tap-and-release
contextual menu** (day column; opens on the block under the tap or empty space), the **panel info at the top** of
the touch menu (title + times — no hover bubble on a phone), and the **"move" menu option** (arms a move; the next
touch drag previews via the shared `dragPreview` overlay with the desktop snap rules and commits on release; a bare
tap cancels).

Touch presses on blocks are ignored entirely (`PointerType.Touch` early-return in `CalendarBlock`), so a
single-finger drag scrolls the grid and all block interaction goes through the menu.

### Terminology rename: "side task" → "screen break"

The eye-care breaks are named **"screen breaks"** everywhere (PRD §15) — UI, docs, code identifiers (`ScreenBreak`,
`screenBreak*`, `showScreenBreaks`, `DEFAULT_SCREEN_BREAKS`, `simulateScreenBreaks`, …) and the persisted JSON keys
(`screenBreak` panel flag, `showScreenBreaks`).

**Persisted-DB compatibility:** old on-disk/synced DBs still load — the codec maps the legacy keys onto the new
fields with `@JsonNames("sideTask")` / `@JsonNames("showSideTasks")` (encode writes the new keys; decode accepts
either). Covered by
`NoScreenInactivityPanelTest.codec_decodes_old_screen_break_key_names_as_screen_breaks`.

The screen-break config list itself is not persisted (hardcoded `DEFAULT_SCREEN_BREAKS`), so it needed no
migration.

The internal panel-id slug `side/{i}/{start}` was **deliberately left as-is** — it is a derived id, regenerated
every fill and stripped from the wire, so renaming it would gain nothing and only risk id-matching breakage.

---

## Scheduler model — the chain of corrections

Full rationale in ADR 0001. Each entry replaced the one above it.

| Date | Change |
| --- | --- |
| 2026-08-21 | **A claim is the lag counted in the task's own slots** (`(V−v)·p/m`), not the raw virtual clock. Test 14's 50 % task was getting 35 % (5 % of day one). Tests 1–10 byte-identical, so it only bites where the raw clock was wrong. Left open: a seeding defect at resumptions in a pool of interchangeable tasks (3 of 46 fail `check_resume_contract`). |
| 2026-08-20 | **The chunk scale is one ROUND** (`p·m_rival/(1−p)`), not one period (`p·T`). The old cap was a two-task coincidence and permitted a 15 h monolith of A. The lift (boost) and the cap (round) must be asked separately, or the atomic block loses its boost. `steady_cycle` had the same bug by another route. |
| 2026-08-20 | **Kotlin port caught up** — `SchedulerPlan.kt` was ~5 reference changes behind `scheduler_logic.py` (CLAUDE.md claimed 3 of them shipped, with tests that did not exist). Full backlog ported and pinned slot-for-slot against dumped reference output. |
| 2026-08-19 | **The forgetting is replayed over the past** (`_replay_clocks`), edge by edge. The seeding replayed `served/p` flat while the walk relaxes, so a re-plan never continued the walk. Now enforced by `check_resume_contract`. |
| 2026-08-18 | **The lookback window is measured in SCHEDULABLE time**, not wall time. Test 12's 50 % task was getting 2 %. |
| 2026-08-18 | **`last` reads `_last_run`, not `_head`.** A resumed plan refused the very task the timeline left off with, so the rightful pick lost a slot at every break (21 % instead of 39 %). |
| 2026-08-05 | Ported the reference's rewrite: a window bounds only the tasks it turns away; the atomic block (`_head`/`run_served`/`pending`). |
| 2026-08-04 | **The debt+decay model lasted 3 days.** `test.py` was rewritten and the app ported its WFQ virtual clock + capped exponential influence field (`SchedulerPlan.kt`); `SchedulerDebt.kt` DELETED. Trigger became a debounced `schedulingSignature` change, plus (later that day) an hourly staleness bound. |
| earlier | An EDF fill (`deadline = m/p`); helpers `edfPeriodMillis` / `nextTask` deleted. |

Also removed along the way: the per-tick `screenBreakDue → RefreshSchedule` in `dispatchScheduleAdvance` (it
churned the whole plan continuously while the user was away).

---

## Screen breaks

| Date | Change |
| --- | --- |
| 2026-08-05 | **Every screen break slides.** The 20-s look-away now pins to the now-line like a rest pose — an untaken break is OWED, not "assumed done". All cues key on the fixed due. |
| 2026-08-05 | **Three things serve a break** (a conducted look-away serves itself; a pose that happened serves every shorter break; a real pause ≥ 15 min). Fixes the reported *one look-away cue per session, then silence forever*. |
| 2026-08-05 | The look-away's `pauseThresholdMillis` went from 0 (i.e. 20 s, so any brief step away restarted the 20-minute clock) to 15 min. |
| 2026-08-05 | Every break recurs an interval after it **ENDS**, not after it starts. |
| 2026-08-05 | `DebugFlags.screenBreakOverrides` — all three breaks retimable independently on desktop; the legacy unprefixed properties became a named view onto the `5min_break` entry. |

---

## Sync

| Date | Change |
| --- | --- |
| 2026-07-30 | `scheduler_snapshot.writer_device_id` — the lost-acknowledgement repair. A push whose response was lost left the remote +1 revision, and the next reconcile pulled the device's OWN write over newer edits. Shipped with an `HttpTimeout` on the snapshot client and `Diagnostics` logging of every reconcile failure + LWW drop. |
| 2026-07-28 | **Startup reconcile.** A restored session reconciled nothing at launch, so the first edit's own auto-push fetch LWW-pulled over it. Also: `writer_device_id` is NULL on pre-fix revisions, so those are permanently unprotected. |
| 2026-07-28 | Realtime auto-pull **verified live** — but it never replays what it missed while disconnected, so every (re)subscribe now reconciles too. |
| 2026-07-22 | **Reversal of button-only:** local→remote auto-push (500 ms debounce) + remote→local auto-pull (`RealtimeSnapshotSubscriber` `postgres_changes`, migration `20260722000000`). `SchedulerSyncEngine` made `open` for the deterministic `BidirectionalSyncTest` double. |
| — | **Three-way merge** (`SnapshotMerge`, schema v10 / `9.sqm`) replaced whole-doc LWW; LWW survives only as the no-ancestor / undecodable fallback. |

Retired along the way: the five-sync-moments model, the startup remote-activity adoption
(`purgeLegacyAdoptedRows` heals old DBs), and the external Realtime-presence listener.

---

## Supabase migrations

| Migration | What it did |
| --- | --- |
| `20260713000000` | Dropped `pause_cue_schedule` / `derive_pauses` / `device_*`; added `account_state` for the listener era. |
| `20260716000000` | `kind` column on the remote active-session rows. |
| `20260721000000` | Device↔account exclusivity for **push tokens**. |
| `20260722000000` | `scheduler_snapshot` into the `supabase_realtime` publication with `replica identity full`. |
| `20260723000000` | `device_heartbeat` table + re-added `pause_cue_schedule`. The pg_cron pivot; the Fly.io `/listener` and the presence WebSocket were deleted. |
| `20260724000000` | The `t_a`/`t_b` model: `app_config` (t_a) + `break_config` (per-break length + vocal message) + `publish_presence()` (returns t_a) + `evaluate_pause_cue()` (decide + claim) + a `tick_pause_cues()` that only detects. |
| `20260725000000` | The **overdue gate** — a cue fires only when the account went idle with a break DUE. |
| `20260726000000` | Split the presence row in two: `device_heartbeat` keeps `{user_id, device_id, beat_at, data_payload_sent}` (`kind` / `next_break_*` dropped); new **`device_break`** table + `publish_next_break()` RPC. `overdue_break_at_last_beat()` factored out. **Changes `publish_presence`'s signature — Supabase AND every app must be redeployed.** |
| `20260727000000` | Extended device↔account exclusivity to `device_heartbeat` + `device_break`. |
| `20260728000000` | `device_break` became **account-keyed with just the two due instants** (`device_id` / `kind` / `break_kind` / `break_len_ms` dropped); the claim flag moved out into an account-keyed **`data_payload_sent`** table; break LENGTH moved server-side. **`publish_next_break`'s signature changed — redeploy Supabase AND every app.** |
| `20260729000000` | Split delivery in two: `pause-cue` (**e1**, clean lock, decides, anchors at `now()`) and `pause-cue-cron` (**e2**, cron decided, anchors at `t2`). `omni_edge_push` gained the function name (1-arg form dropped); shared `_shared/push.ts`. **Supabase-only redeploy.** |
| `20260730000000` | `scheduler_snapshot.writer_device_id` (nullable). **Apply before/with the app rebuild, or every fetch 400s.** |

`account_logout` (the remote force-logout marker) is applied by `deploy-supabase.bat`.

**Remaining follow-ups:** the Edge Functions' `FCM_` / `APNS_` secrets (`supabase secrets set`, project-wide so one
set covers both) and native phone push-token registration. Full runbook: `docs/PAUSE_CUE_DELIVERY.md`.

---

## Local SQLite schema

| Version | File | What it did |
| --- | --- | --- |
| v7 | — | `sleep_scan_checkpoint` table (local-only OS-sleep scan progress, in its own table so `sync_meta` writes can't clobber it). |
| v8 | `7.sqm` | `kind` column on `device_active_session`. |
| v9 | `8.sqm` | **Per-account partitioning** of `app_state` / `history_unit` / `history_pointer`; `account_sync` table (per-account revision baseline / `dirty` / logout baseline). Pre-v9 rows are filed under the account that was signed in when written, or into the `''` partition the first guest account adopts. |
| v10 | `9.sqm` | `account_sync.base_payload` — the merge's common ancestor. |

---

## Other dated decisions

| Date | Change |
| --- | --- |
| 2026-08-20 | **The calendar's two layers** replaced the "Inactivity" + "No screen" band pair; GREY became "the scheduler places nothing here". Sleep and hand-added inactivity periods now block the fill (previously sleep deliberately did not). Third iteration of the layer source — the first two readings shipped and were both wrong (ADR 0002). |
| 2026-08-20 | **No "focused week" any more** — the calendar scrolls endlessly and the schedule horizon follows the displayed day span in both directions (ADR 0009). |
| 2026-08-20 | The day-row sizing bug: `requiredHeight` silently centred every row, showing the wrong hours (≈8 h off) and hiding the now-line. |
| 2026-08-20 | **Relative priority** — the percentage's own right-click menu, the pin semantics, `RelativePriorityDomain` (ADR 0004). |
| 2026-08-06 | An alarm's **days** became part of the alarm (synced), not of the device; `repeatDaily` → `repeats`. |
| 2026-08-01 | **The desktop rings alarms too**, off the now-line rather than an armed OS alarm. PRD §18 used to say it never rings. |
| 2026-07-28 | **Horizon refill self-retrigger** — the release app was a tray icon with no window; `launchHorizonReschedule` re-fired with zero delay forever, pegging the EDT. Fixed with a refill margin + rate floor. |
| 2026-07-24 | The presence model reshaped to the user's `t_a`/`t_b` spec (ADR 0006). |
| 2026-07-23 | The Fly.io `/listener` and the presence WebSocket removed; the pause cue moved onto a pg_cron tick. The user reasoned the listener was a "false solution" (also heartbeat + poll), so no host warranted it. |
| 2026-07-19 | The whole 1.6.0 delta batch above went code-complete. |
| 2026-07-09 | The startup remote-activity adoption retired — it fabricated activity over genuine pauses. |
| — | `android:allowBackup="false"` — OS auto-restore silently resurrected a "wiped" install's DB. |
