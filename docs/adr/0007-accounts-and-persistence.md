# ADR 0007 — Accounts, persistence, and what is authoritative vs. derived

**Status:** active. **Invariant summary:** see `CLAUDE.md` → *Accounts* and *State*.

## The app is ALWAYS connected to an account

PRD §5, ARCHITECTURE.md §8.0. There is **no signed-out mode**, and no code may reintroduce one. A device
with no account creates a **guest account** and connects to it; "not connected" in the UI only ever means
"on a guest account".

### A guest account is a real account with no credentials

It is GoTrue's **anonymous sign-in** (`RemoteSnapshotClient.signUpGuest()` → `POST /auth/v1/signup` with an
empty body) — same snapshot row, same presence/break/push rows, same RLS, same everything. It simply has no
email/password, so no *other* device can sign in to it (it is told from other guests by the device id in its
device rows).

Created by `SchedulerSyncEngine.ensureAccount()`, called at startup **and at the head of every
`reconcile()`**, so an offline first launch keeps working locally and retries every sync moment.

**Requires `enable_anonymous_sign_ins` on the Supabase project** (`supabase/config.toml` + the Dashboard
toggle — `supabase db push` does NOT apply auth settings, so this is a manual / `config push` step).

### "Create account" CLAIMS the current guest account

It must never create a second one. `SchedulerSyncEngine.createAccount()` on a guest issues
`PUT /auth/v1/user` (`updateCredentials`) so the SAME account gains the email + password: same user id, same
data, same devices, nothing copied.

Only when the active account already has credentials (or there is no account at all) does it fall back to a
fresh `signUp` + switch.

Regression guard:
`SchedulerSyncEngineTest.creating_an_account_on_a_guest_claims_the_same_account_rather_than_making_a_new_one`
asserts the auth calls are `signup` then `/user`.

### Sign-out lands on a fresh guest account

`signOutToGuest()`, and so does a remote force-logout (`account_logout`) — `reconcile()` re-`ensureAccount()`s
after the forced sign-out, *outside* the engine mutex, having pushed nothing to the account it left.

The `/scripts` entry points skip guest creation: startup credentials take the sign-in branch in the
ViewModel's init, so `account{1,2,3}-*` open directly on their account.

### Remote force-logout

`account_logout` is the marker. Each login records the account's current `logout_at` as a per-login baseline
in `sync_meta.acknowledged_logout_at`, and every `reconcile()` re-reads it and signs the device out (pushing
nothing) when the marker advanced past that baseline.

The check **fails open** (missing table / transport error ⇒ "not logged out"), so an unapplied migration never
wedges syncing.

## Local data is partitioned PER ACCOUNT

Schema v9, `8.sqm`. `app_state` / `history_unit` / `history_pointer` are keyed by `account_id`, and
`SqlDelightSchedulerStore` reads and writes only the active account's partition (`sync_meta.user_id`).

**Switching accounts deletes nothing** (the user's rule): the account being left keeps its local copy and gets
it back verbatim when it signs in again, and no data can smear into the account being entered.

Data written before any account existed lives in the `''` partition and is **adopted** by the first guest
account (`adoptUnclaimedAccountData`). Partitions are never merged.

### The revision baseline / `dirty` / logout baseline are per account too

`account_sync` table. A `revision` is an optimistic-concurrency baseline against ONE account's row — carried
across a switch, whole-doc LWW would push this device's tree over the other account's snapshot.

`saveSyncMeta` therefore refuses to write the bookkeeping in hand when the save CHANGES the active account.

### Device-level facts are NOT account-scoped

The device id, the OS-sleep scan checkpoint, window placement, and the recorded active sessions ("screen time
on this device is exactly the same" across a switch — the user's words).

**Known consequence:** peer rows pulled from a previous account stay in the local `device_active_session`
table (re-pullable data, not user data).

### The switch itself

The ViewModel reacts in `watchAccountChanges`: flush into the account being left via
`engine.beforeAccountSwitch`, load the new partition, re-point the Realtime subscription, reconcile once. An
account change is the ONLY thing besides the button/auto-push that forces a reconcile.

## Persisted-DB compatibility

- Any change to the persisted state model (`SchedulerState` / `SchedulerStateCodec`, the `PersistedPanel` /
  `Persisted*` types) or to reducer logic that writes state must come with a test that decides whether
  **existing on-disk DBs must be changed** — i.e. loads a representative payload written by the *previous*
  shape and asserts it either still loads and renders correctly, or is migrated/repaired on load.
- Old DBs can hold data an older build wrote that current invariants forbid; `decode` must **heal** such
  states, not surface them as anomalies.
  - Reference case: a blank-titled task that still has records rendered every past calendar block as
    "(untitled)" (see the `calendar-untitled-tombstone` note). The fix is data-level, not UI — the test must
    catch the bad persisted shape, not just the rendering.
- Adding a field to a `Persisted*` type: give it a default so payloads written before the field decode
  cleanly, and add/extend a decode test that loads a payload lacking it.
- The same applies to the SQLite **schema** (`Scheduler.sq` + a new `N.sqm`): reproduce the previous on-disk
  shape in a test and assert the upgrade keeps the data.
  - Reference: `SchedulerStoreTest.upgrades_pre_account_partition_v8_db_of_a_signed_in_device` /
    `…_signed_out_device_into_the_guest_account` — v8→v9 files each existing row under the account that was
    signed in when it was written, or into the `''` partition the first guest account adopts.

## The reconstructibility rule

**Persist and sync only state that cannot be recomputed from other persisted data.** Anything derivable is
recomputed (on load / on the next now-advance) instead of being stored or pushed.

Before persisting or syncing a field, ask whether it can be re-derived from the inputs below. If so, recompute
it — and never let an engine tick that *only* re-derives it mark the state dirty or trigger a sync push.

### Authoritative (persist + sync)

The task tree (lists/cells/tasks, titles, weights); the **named alternative task trees** (ADR 0008);
**user-authored / pinned** calendar panels; chores/reminders; the sleep schedule; the **alarms** (PRD §18);
settings; the Undo/Redo **history units**; and **manual** edits to completed-work **records** (`task.record`)
— the `RemoveRecordPeriod` / `PinRecordAsPanel` intents.

### Derived (must NOT count as a syncable change)

The automatic schedule — the auto / screen-break / sleep **panels** `SchedulerDomain.fillSchedule` regenerates
— **and the completed-work records the schedule-advance banks** as auto panels elapse (`AdvanceSchedule` /
`RefreshSchedule`) or as a device sleep cuts them (`ReportDeviceSleep`).

All of it is a pure function of `now` + the task tree + the sleep/screen-break config + device-sleep history,
so every device recomputes the same records by advancing its own now-line over the synced tree. An
always-running device must **not** `scheduler_snapshot`-write all day just from time passing.

Records are still persisted **locally** (kept across a load; `fillSchedule` doesn't recreate the past) and
still ride along with the next authoritative push (records are part of the task tree and are NOT
reconstructible on a fresh device). They simply never trigger a push **on their own**.

The regenerated **panels** are **stripped from the wire payload entirely** — the pushed snapshot is the
authoritative projection (`SchedulerStateCodec.syncFingerprint`, bound as the sync engine's `localSnapshot`),
and a puller regenerates them on its next reschedule (`SyncPayloadTest`).

Screen-break config is likewise hardcoded (`DEFAULT_SCREEN_BREAKS`, seeded in `prepareLoadedState`), not
persisted — this rule already applied.

### Local-only view state (persist locally, NEVER sync)

The per-device UI view: the **focused window** (PRD §7 window navigation), the **tree selection** (highlighted
cells; cleared as a side effect of navigating away), the calendar **"Screen breaks" / "Reminders" display
switches** (`showScreenBreaks` / `showReminders`), and the `WindowNav` / `Selection` **history** that records
those moves.

It is only useful to the local app — switching it must not write the remote `scheduler_snapshot`, and a pulled
remote snapshot must not adopt another device's value.

- `SchedulerState.withLocalViewStateNeutralized()` strips it from the sync fingerprint (so changing it is never
  an authoritative change).
- `applyRemoteSnapshot` carries the local values across a pull via `withLocalViewStateFrom`.
- These fields no longer ride along in the pushed snapshot at all: the wire payload is the neutralized
  authoritative projection, so a push ships canonical defaults in their place and the puller keeps its own
  values.

Calendar **zoom** is likewise local, but lives only in Compose UI state (`CalendarZoomActions`) and is never
persisted at all — so no gating is needed.

Window / reminder-window **placement** (geometry + visibility) is a separate local-only SQLite table
(`WindowPlacementStore`), already outside the synced snapshot entirely.

The **OS-sleep-log scan checkpoint** (`sleep_scan_checkpoint` table / `SleepScanCheckpointStore`, schema v7) is
local-only persisted state — device scan progress, *not* reconstructible but *not* synced either. It is kept in
its **own** table (not a `sync_meta` column) so the sync engine's whole-row `sync_meta` writes can't clobber it.

### Deliberate exception

The whole-state snapshot is itself replayable from all the history units — but only while history is within
`MAX_HISTORY_UNITS` (older units are evicted). Because history is bounded, the snapshot is kept as the
authoritative base and is persisted/synced anyway.

## Sleep-panel carving and the live retraction

The calendar's scheduled §17 "Sleep" band is **carved** wherever the account/device was active (the user worked
through a scheduled sleep window), so it shows gaps.

This is a pure **display** transform (`SchedulerDomain.carveSleepPanels` in `App.mergePanelsForDisplay`) built
from already-derived inputs — the account-wide pauses' complement over `[now − 168h, now]` **plus** this
device's live open session `[activeSince, now]` (`SchedulerEngine.activeSince`). It persists/syncs **nothing**.

Crucially the *live* retraction is a **continuously-changing** value (the open session extends toward the
now-line every display tick), and it makes **zero** server writes: `activeSince` moves only on a structural
session open/finalize, and the `device_active_session` rows travel **only inside a reconcile**.

So a device the user keeps working on through the night shows the band retracting live; peers learn of that
past activity at the next Sync press on both sides (live activity is Realtime presence only).

The conservative rule (empty `inactivityGaps` ⇒ carve nothing) keeps the startup transient / store-less web
install / future windows solid.

### 1.6.0 revision — past sleep is a recorded fact, not a projection

The sleep SCHEDULE is projected for display only from `now` FORWARD. **The past is never assumed slept** (an
emptied DB's past simply carries both "nobody unlocked" layers and no panel, per the user's spec).

Past "Sleep" is instead **persisted**:

- `SchedulerEngine.maybeMaterializePastSleep` banks a materialized "Sleep" panel (allocated id, NOT a derived
  `sleep/{day}` one — `SchedulerReducer.materializePastSleep`, `MaterializePastSleep` intent, outside
  Undo/Redo like the record bank) when a scheduled sleep window fully elapses **while this session ran**
  (`sessionStartMillis` lower-bounds the candidate span so a fresh/empty account never retroactively
  materializes the whole 168 h) and the account was inactive there
  (`intersectRegions(scheduledSleep, inactivityGaps)`).
- The Sleep/Work **toggle** also writes one: `SchedulerState.sleepingSinceMillis` (persisted, decodes null on
  old DBs) stamps the session start, a live "Sleep" band grows to the now-line while the toggle is on, and
  `reduceSetSleepMode(null)` (Work press / wake lapse via the tick) finalizes `[since, now]`.

`fillSchedule` keeps materialized sleep (`!id.startsWith("sleep/")`) across reschedules; `isRegeneratedPanel`
still strips all `sleep` panels from the sync wire, so materialized past sleep is **local-only** (persists
across restart, not synced — like the derived bands).

The display also re-derives the layer regions over `min(now−168h, visibleSpanStart)` so any **past day fills on
demand** (empty ⇒ one open-ended region), and the earliest such region renders **"∞"** as its start
(`SchedulerDomain.derivedBandsOpenStart` + `CalendarRecord.openStart` / `hmOrInfinity`) when nothing precedes
it.

See the `past-sleep-and-open-start` note.
