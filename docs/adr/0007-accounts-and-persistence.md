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

---

## The desktop database is opened WAL, with a busy timeout

**Post-mortem, 2026-09-02.** The release app on Windows started showing a fatal `Error` box reading
`[SQLITE_BUSY] The database file is locked (database is locked)`. Nothing in the schema, the migrations or
the account partitions was wrong; the two DRIVER DEFAULTS were.

**A file-backed `JdbcSqliteDriver` opens one connection PER THREAD.** SQLDelight picks its connection
manager off the URL: an in-memory URL gets a `StaticConnectionManager` (one shared connection), and every
file URL gets a `ThreadedConnectionManager` holding a `ThreadLocal<Connection>`. Every jvmTest builds its
store `IN_MEMORY`, so the whole test suite exercises the single-connection manager and the app is the only
thing that ever runs the threaded one. That is why this could ship: the app is a multi-connection SQLite
client of its own file and no test ever was.

The writers are genuinely concurrent and genuinely on different threads:

| Writer | Thread |
| --- | --- |
| the debounced `store.save` | a `Dispatchers.Default` worker (`TaskSchedulerViewModel.saveScope`) |
| `applyRemoteSnapshot`'s `store.save` | whichever worker the reconcile landed on |
| `flush()` at close | the UI thread |
| `saveActiveSessions` / `saveSleepGaps` / `saveSyncMeta` / `saveCheckpoint` | the engine's and sync engine's own |

Against that, the defaults are exactly wrong. `journal_mode` defaults to `delete`, where a writer takes an
EXCLUSIVE lock on the whole file for the length of its transaction and turns away every other connection,
readers included. `busy_timeout` defaults to **3 s** (sqlite-jdbc's `SQLiteConfig`), after which the loser
does not wait — it THROWS. Several `store.save` call sites are unguarded, so the throw escapes and the
packaged jpackage launcher renders it as a fatal `Error` box: a lock contention reads to the user as a crash.

**What made it cross 3 s is `save()` itself.** It rewrites the *whole* Undo/Redo history — delete-all plus
re-insert of every unit — alongside the state blob, in one transaction. On the release account that is
**~72 MB per save** (a 1.4 MB `app_state` payload plus 70.5 MB across 2 219 `history_unit` rows, of which
Main alone is 66.8 MB over its 1 000-unit cap). So the failure is a function of how long the account has
been used: the transaction grows with the history until it outlives the timeout, and then any sibling write
landing inside it kills the app. Nothing about the crash announces that, which is why it looked sudden.

The fix is `FileSchedulerStore.connectionProperties()`, on the `Properties` the driver hands to every
connection it opens:

- **`journal_mode=WAL`** — readers never block the writer and the writer never blocks readers, so only two
  concurrent WRITES contend at all. This is a persistent property of the file, which is what lets a test
  assert it through a second, plainly-configured connection.
- **`busy_timeout=30000`** — the remaining writer-vs-writer contention becomes a wait instead of an
  exception. Per-connection, so it cannot be observed from outside; `DesktopStoreConcurrencyTest` pins it
  by holding the write lock for longer than the old 3 s default and asserting the store's write still lands.
- **`synchronous=NORMAL`** — the sanctioned WAL companion: a commit no longer fsyncs, only a checkpoint does.

The schema create/migrate the `JdbcSqliteDriver` schema overload runs at construction is itself a write
transaction, so it is covered by the same timeout — which matters because the release launcher's
`taskkill` returns before the dying instance's file handle is gone. `release-launch-acc3.bat` now WAITS for
the image to leave the process list before starting the replacement, rather than racing it.

See the `sqlite-busy-desktop-connections` note.

---

## Adding a History Unit is ONE INSERT

**2026-09-02, the sequel to the section above.** WAL and the busy timeout stopped the crash; they did not
make a 72 MB write cheap. `save()` still rewrote the whole Undo/Redo history on every 400 ms save debounce —
that is, on every burst of typing.

### Why it was O(whole history)

A `history_unit` row was keyed by `(account_id, category, ordinal)`, where `ordinal` is the unit's **dense
index** in its category's list. That looks harmless until you notice which end the cap evicts from:
`MAX_HISTORY_UNITS` is 1000, the Main history on the release account was **full**, and
`dropOldestUntainted` drops from the **FRONT**. So every new unit shifted all 1000 indices by one, no row's
key survived, and the only correct implementation was `DELETE` everything + re-`INSERT` everything.

That is a persistence bug wearing a data-model costume: a History Unit is an entity and it had no identity,
only a position.

### What replaced it

Four changes, and the first three are all needed before the win is real:

1. **The row key is a stable `seq`** — allocated once when the unit is first written, never renumbered.
   `HistoryRow.ordinal` stays the dense index the app speaks in (a `history_pointer` indexes it); `load()`
   derives it back from the seq order. New units are appended above the highest seq, so a kept row's seq is
   always below anything added after it and the order needs no renumbering to hold.

2. **A save diffs the DIGEST, not the deltas.** `selectHistoryDigests` reads every column but `delta`; the
   incoming list is aligned against the stored one from its first unit onwards, whatever falls outside the
   matched run is deleted, and whatever the match did not cover is appended. That covers the three things
   that can happen to a history list, and it degrades safely: a run that fails to align is simply rewritten.
   Identity is `(timeMillis, chronoId, debugTainted, length, hash)` — never the hash alone. A false
   *non*-match costs one rewritten row; a false match would keep a stale delta, and would need two different
   deltas committed in the same millisecond, at the same tie-break index, with the same taint flag, the same
   length and a 64-bit collision.

3. **`delta` is the last column of the table.** This was measured, not assumed. SQLite reaches a column by
   walking the record from the front, and a delta of tens of KB lives in overflow pages — so with the digest
   columns appended *after* `delta` (which is all `ALTER TABLE ADD COLUMN` can do), the digest scan pulled
   the whole chain in and cost **36 ms** per save against a 54 MB history, against **3 ms** for the same scan
   over the columns before `delta`. That is why 10.sqm REBUILDS the table instead of altering it.

4. **The serialized delta is memoized on the unit** (`HistoryUnit.encodedDelta`). Without this the other
   three buy almost nothing: `SchedulerStateCodec.encodeSnapshot` is called on every save **and again for
   every `syncFingerprint`**, and it serialized all 1000 deltas each time — the store is handed the strings
   already built, so no cleverness in the store could have avoided it. A unit is immutable once committed
   and the reducer carries the same unit *object* through every state copy, so the JSON is produced once per
   unit, ever; it is seeded on load from the row just read, so a launch re-serializes nothing either.

   Found in the same measurement: `encodeSnapshot` built the histories into `PersistedState` and then
   `.copy(histories = null)` threw them away — 40 ms a call for nothing. It now asks for
   `toPersisted(withHistories = false)`.

### Measured

1000 units, ~54 MB of delta text, one unit added (the cap evicting the head and appending one):

| | before | after |
| --- | --- | --- |
| `encodeSnapshot` | 267 ms, ×2 per save | ~2 ms |
| the DB write | ~500–870 ms | ~25–40 ms |

Rehearsed against a **copy of the real 106 MB release DB** (2 223 rows, 1 000 of them Main), which is
where the one-time costs show:

| | |
| --- | --- |
| the v10 -> v11 table rebuild, at the first launch after the deploy | 842 ms |
| the first save (healing 2 223 carried-up digests) | 1 405 ms |
| **every save after that, one unit added** | **81 ms** |

The remaining ~54 ms of the encode is the `app_state` payload itself (1.4 MB of JSON), not the history.

### The digest is not backfilled, it is healed

Neither digest column is filled in by 10.sqm. `delta_hash` is a Kotlin function SQL cannot compute, and
SQL's `length()` counts **code points** where Kotlin's `String` counts **UTF-16 units** — a backfilled length
would disagree with the app's on any delta holding an emoji, and every such row would read as "a different
unit". So a carried-up row holds `-1` / `NULL`, meaning *not evidence*: it matches on the remaining columns
and the first save that reuses it writes the two integers in. No delta is rewritten for the healing, and no
account pays a full rewrite at upgrade.

### What is still whole-document

The **wire** payload. `syncFingerprint` is the entire `PersistedSnapshot`, history included, so a push still
ships the whole document (~20 MB on the release account, as `account_sync.base_payload` shows). That is
ADR 0005's deliberate design, not an oversight of this change, and it is why the push is debounced at 500 ms
and gated on the fingerprint actually moving.

See the `history-unit-incremental-write` note.
