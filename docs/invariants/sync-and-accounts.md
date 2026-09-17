# Accounts and sync

Active invariants. Reasoning and post-mortems: `docs/adr/`. Dated log: `CHANGELOG.md`.
Global rules that always apply: `CLAUDE.md`.

---

## Accounts

→ ADR 0007.

- **The app is ALWAYS connected to an account. There is no signed-out mode** — do not reintroduce one. "Not
  connected" in the UI only ever means "on a guest account".
- A guest account is a real account with no credentials (GoTrue anonymous sign-in). Requires
  `enable_anonymous_sign_ins` on the project (a manual/`config push` step — `db push` does not apply auth
  settings).
- **"Create account" CLAIMS the current guest account** (`PUT /auth/v1/user`) — it must never create a second
  one.
- Sign-out and remote force-logout both land on a **fresh guest account**.
- **Local data is partitioned per account** (schema v9). **Switching accounts deletes nothing**, and no data
  may smear between partitions. Partitions are never merged.
- The revision baseline / `dirty` / logout baseline are per account too. `saveSyncMeta` refuses to write
  bookkeeping when the save changes the active account.
- Device-level facts (device id, sleep-scan checkpoint, window placement, recorded active sessions) are
  **not** account-scoped.
- The force-logout check **fails open**, so an unapplied migration never wedges syncing.
- **The desktop driver opens ONE CONNECTION PER THREAD, so the pragmas are load-bearing.** A file-backed
  `JdbcSqliteDriver` uses SQLDelight's `ThreadedConnectionManager` (only an in-memory URL gets a single
  shared connection), and the app writes from the save debounce, the reconcile, the UI thread's `flush()`
  and the engine's own stores — all at once, on different connections. So
  `FileSchedulerStore.connectionProperties()` sets **`journal_mode=WAL`**, **`busy_timeout`** and
  **`synchronous=NORMAL`** on the `Properties` every connection is opened with, and there is no second
  place a connection is made. The defaults are `delete` + a **3 s** timeout that THROWS rather than waits:
  `save()` rewrites the whole Undo/Redo history in one transaction (~72 MB on the release account), which
  outlives 3 s, and the `[SQLITE_BUSY] The database file is locked` that follows escapes an unguarded save
  site and reaches the user as the packaged launcher's fatal `Error` box. **Every jvmTest store is
  `IN_MEMORY`**, so no ordinary test exercises the threaded manager — `DesktopStoreConcurrencyTest` is the
  one that does; keep it.

---

## Sync

→ ADR 0005 (the merge, the triggers), ADR 0016 (rows, not a document). **Automatic in both directions**; the Sync
button is a force-now fallback only — do not reintroduce a button-only model.

- **All sync paths funnel through the one mutex-guarded `SchedulerSyncEngine.reconcile()`.**
- Local→remote: a fingerprint-moving edit enqueues a **500 ms debounced** push. Derived/tick reschedules
  leave the fingerprint unchanged and never enqueue one.
- Remote→local: `RealtimeSnapshotSubscriber` (`postgres_changes` on **`scheduler_head`**) **pokes** `reconcile()`;
  it never applies the event body itself, and **it ignores a head change this device wrote**
  (`RealtimePhoenix.changeWriterDeviceId`).
- **Streaming is not synchronization.** `postgres_changes` has no cursor to resume from, so **every
  (re)subscribe must reconcile** as a catch-up.
- **The same socket carries a second channel: the scheduler peers' private broadcast** (`realtime:scheduler:<userId>`,
  `SchedulerPeerChannel`, `scheduler.md` § *One device plans*). It is not sync: nothing on it is a row, nothing is
  replayed. A refused join (migration 20260916000000 not applied) leaves the head subscription running and every
  device planning for itself — do not treat it as an auth failure.
- **Every launch reconciles once**, and **every account change reconciles once**.
- Active-session rows ride **every** reconcile — never a timer or beat — and are not re-sent when this device's
  own rows are those it last pushed.

### Sync by rows

→ ADR 0016, `server-quota.md`. Migration 20260917000000. **Never sync the state as one row again**: a document
rewritten on every edit is what filled the database.

- **`scheduler_entity` holds one row per synced entity** — `EntityRows.split` of the wire payload: every array of
  objects with unique string ids (tasks, cells, lists, panels, alarms, reminders…) gives one row per element, keyed
  `(kind, id)`; every other top-level field is one `field` row. `EntityRows.join` is its exact inverse. **The
  histories are not entities.** A new top-level collection needs nothing: it splits by the same rule.
- **Every write takes the next `revision`** from one sequence (a trigger). A pull asks for `revision > cursor` written
  by another device (`device_id <> me`), in pages of 1000.
- **A deletion is a tombstone** (`deleted = true`, no payload), and **a tombstone lives a week**
  (`purge_scheduler_tombstones`, scheduled daily by `pause-cue-setup.sql`). So **a device that last pulled more than
  six days ago does not trust its cursor** (`FULL_PULL_AFTER_MILLIS`): it reads every live row and takes an entity
  absent from them as gone. Lengthen the retention and the threshold together, never one alone.
- **The server's rows are the base + what was pulled**; the device's own rows are what it holds. A reconcile then
  pushes every row that differs and a tombstone for every row it no longer has — an edit writes the rows it touched
  and nothing else. **A device that has no edits takes the pulled state as is; one with edits merges it** (below).
- **A push records what it is about to write BEFORE the request** (`RowBase.pending`). Its answer may be lost after
  the server applied it; the next reconcile then writes each of those rows again as the device now holds it — or a
  tombstone. Without that, deleting an entity whose creation was never acknowledged sends nothing, and the server
  keeps it forever (`RowSyncTest`).
- **`scheduler_head` is the only published table**: one row per account, bumped by a statement trigger on
  `scheduler_entity` with the writing device. Never add `scheduler_entity` or `history_unit` to the publication.
- **`scheduler_snapshot` is emptied, read-only and unpublished.** An older build's push fails there, harmlessly.
- **History units are rows too** (`history_unit`, `persistence.md` § *One history, per-device undo*): a device
  pushes its own units changed since its last push (a commit, or an undo/redo flipping `undone`), marks the redo
  branch a new edit discarded `dropped` (and empties its delta), and pulls the other devices' units by revision.
  The server keeps the newest 1000 per category (a trigger), like each device.

### Typing saves and pushes, both debounced

Every keystroke is an intent (`UpdateEditText`), and `dispatch` schedules a save for **every** intent that
changes state. Two debounces, and neither is a throttle — a burst of typing collapses into one of each:

1. **400 ms** (`SAVE_DEBOUNCE_MILLIS`) → `store.save(encodeSnapshot(state))`, the local SQLite write.
2. **500 ms** (`AUTO_PUSH_DEBOUNCE_MILLIS`) → `reconcile()`, after the save, and only when the edit moved
   `syncFingerprint` (the reconstructibility rule — a tick-only reschedule pushes nothing).

**The push is REST; the WEBSOCKET is how peers hear about it.** `reconcile()` writes the rows over HTTP, the
trigger bumps `scheduler_head`, and each peer's `RealtimeSnapshotSubscriber` receives that change and pokes its own
reconcile.

**A mid-edit keystroke saves but does not push, and that is correct**: the edit session is local-only view
state (it is not in the encoded snapshot at all), so the fingerprint does not move until the title is
committed at `endEditSession`. Do not "fix" that by syncing the draft — a half-typed title is not an
authoritative change, and peers must not see one.

### Conflicts are MERGED

A three-way merge over a recorded common ancestor (`account_sync.base_payload`, the `RowBase`: the rows as the
server holds them after the last reconcile).

- Added on one side ⇒ kept. Deleted on one side and untouched ⇒ deleted. Different fields ⇒ both applied.
  Same field ⇒ **remote wins**. Delete vs. edit ⇒ **edit wins**.
- `Task.record` is **unioned**; panels/chores/alarms resolve as **whole objects**; id counters take the
  **max**; ordered lists merge membership then follow the remote's order.
- **The result is repaired, not trusted** (`SnapshotMerge.repair` + `pruneDetachedTree`).
- **The merge is applied locally AND pushed** — as the rows it changed.
- **History is not merged, it is shared**: every unit is its device's, and a pulled unit is added beside the local
  ones (`MergePeerHistory`).

### Working offline

A device can be switched to work **completely offline** (the button beside the sync chip). It is a device-level,
local-only choice (`network_mode`, schema 14 — never synced, never a History Unit, not per account), remembered
across launches; a launch with `omniapp.startOffline` / `OMNIAPP_START_OFFLINE` starts offline whatever was chosen,
without overwriting the choice (`account3-deploy-windows-offline.bat` installs account 3 that way).

- **Nothing leaves the device.** `RemoteSnapshotClient.offline` refuses every request with
  `WorkingOfflineException` before it is sent — that is the hard gate — and the engine does not even ask: no
  reconcile, no guest account, no sign-in, no presence / break rows, no Realtime auth, so no socket.
- **Edits keep being saved and marked `dirty`**; nothing is lost and nothing is queued beside the dirty flag.
- **Going online reconnects exactly as a launch does** (`TaskSchedulerViewModel.connect`): a restored session
  reconciles, which pushes the offline edits merged with what the peers did; no session runs the launch's sign-in
  or creates the guest. Edits made offline *before any account existed* stay in the unclaimed partition, which only
  a guest adopts — the usual rule, not an exception.

### A device never pulls its own write back

A pull excludes this device's rows (`device_id <> me`) and every write is an idempotent upsert keyed by the entity
or the unit, so the lost-acknowledgement case the document sync needed `writer_device_id` for cannot pull an old
push over newer edits; `RowBase.pending` covers the rest (above).

---
