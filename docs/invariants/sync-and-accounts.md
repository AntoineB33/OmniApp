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

→ ADR 0005. Whole-document `scheduler_snapshot`, versioned by `revision`, **automatic in both directions**.
The Sync button is a force-now fallback only — do not reintroduce a button-only model.

- **All snapshot paths funnel through the one mutex-guarded `SchedulerSyncEngine.reconcile()`.**
- Local→remote: a fingerprint-moving edit enqueues a **500 ms debounced** push. Derived/tick reschedules
  leave the fingerprint unchanged and never enqueue one.
- Remote→local: `RealtimeSnapshotSubscriber` (`postgres_changes`) **pokes** `reconcile()`; it never applies
  the event body itself.
- **Streaming is not synchronization.** `postgres_changes` has no cursor to resume from, so **every
  (re)subscribe must reconcile** as a catch-up.
- **Every launch reconciles once**, and **every account change reconciles once**. Without the startup one,
  the first edit's own push fetch LWW-pulls over that edit.
- Echo prevention is layered: the revision guard, `applyRemoteSnapshot` bypassing dispatch/save, and the
  debounce.
- Active-session rows ride **every** reconcile — never a timer or beat. Peer activity is reconcile-bounded.

### Typing saves and pushes, both debounced

Every keystroke is an intent (`UpdateEditText`), and `dispatch` schedules a save for **every** intent that
changes state. Two debounces, and neither is a throttle — a burst of typing collapses into one of each:

1. **400 ms** (`SAVE_DEBOUNCE_MILLIS`) → `store.save(encodeSnapshot(state))`, the local SQLite write.
2. **500 ms** (`AUTO_PUSH_DEBOUNCE_MILLIS`) → `reconcile()`, after the save, and only when the edit moved
   `syncFingerprint` (the reconstructibility rule — a tick-only reschedule pushes nothing).

**The push is REST; the WEBSOCKET is how peers hear about it.** `reconcile()` writes the
`scheduler_snapshot` row over HTTP and each peer's `RealtimeSnapshotSubscriber` receives the
`postgres_changes` event and pokes its own reconcile. There is no client→server socket write, and adding one
would not make it faster.

**A mid-edit keystroke saves but does not push, and that is correct**: the edit session is local-only view
state (it is not in the encoded snapshot at all), so the fingerprint does not move until the title is
committed at `endEditSession`. Do not "fix" that by syncing the draft — a half-typed title is not an
authoritative change, and peers must not see one.

### Conflicts are MERGED

A three-way merge over a recorded common ancestor (`account_sync.base_payload`, written by everything that
advances the revision).

- Added on one side ⇒ kept. Deleted on one side and untouched ⇒ deleted. Different fields ⇒ both applied.
  Same field ⇒ **remote wins**. Delete vs. edit ⇒ **edit wins**.
- `Task.record` is **unioned**; panels/chores/alarms resolve as **whole objects**; id counters take the
  **max**; ordered lists merge membership then follow the remote's order.
- **The result is repaired, not trusted** (`SnapshotMerge.repair` + `pruneDetachedTree`).
- **The merge is applied locally AND pushed** on top of the remote revision.
- **The Undo/Redo history is NOT merged** — the local stack is kept.
- **LWW survives only as the fallback** (no ancestor, or an undecodable snapshot). It is not the policy.

### A device never pulls its own write back

`writer_device_id` + "exactly one revision ahead + still dirty + our device id" ⇒ adopt the revision, then
push on top. A null writer falls back to a plain pull, so revisions stamped by pre-fix binaries are
permanently unprotected.

---

