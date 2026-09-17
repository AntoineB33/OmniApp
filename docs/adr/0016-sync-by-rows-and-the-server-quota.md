# 0016 — Sync by rows, history as diffs, and a test that watches the server quota

Status: **implemented 2026-09-17; not deployed** (the Supabase project is still down — see *The outage*). Invariants:
`docs/invariants/server-quota.md` (new), `sync-and-accounts.md` § *Sync by rows*, `persistence.md` § *A History
Unit is what changed* and § *One history, per-device undo*, `scheduler.md` § *One device plans*.

## The outage

On 2026-09-14 `deploy-supabase.bat` failed with `Failed to create login role: Connection terminated due to
connection timeout`; the dashboard said the database *"has completely filled its 500 MB free-tier storage limit"*,
and a restart left it refusing connections.

What filled it:

- **The whole state was ONE row**, `scheduler_snapshot.payload`, rewritten by every push: 0.78 MB of state and
  **80 MB of Undo/Redo history** on the release account. A history unit stored the tree before and after its edit —
  396 KB for a tree unit, 339 KB for a calendar one — so typing a word added megabytes to the document.
- **Postgres never updates in place.** Each rewrite left the old 80 MB version as a dead tuple until autovacuum; the
  table (and, with `replica identity full`, the WAL) held gigabytes' worth of versions between vacuums, and
  reclaimed space is only reused, never returned.
- **pg_cron logs every run** of `pause-cue-tick` in `cron.job_run_details` and never purges: 525 600 rows a year,
  about 120 MB on its own.

The request that followed: *"Some history units must be device-specific […], some must be synced […]. Such history
units should not be kilobytes of data. […] Before taking care of the Supabase problem, I want an app that passes tests
that checks that it doesn't even nearly exceed quota once it will use a real server."* Then: *"all history unit must
be synced. All devices have the same history database, by ctrl+z, ctrl+y, ctrl+shift+z and alt+arrow keys act only
for the history units associated to this device."* Margin: **10 % of each limit**. Sync shape: **rows per entity**.

## The test first

`ServerQuotaTest` runs the real ViewModel, sync engine, HTTP client and scheduler engine of a desktop and a phone
against `FakeSupabase` (a `MockEngine` answering every endpoint the app calls, with the storage triggers emulated)
through a heavy hour, and projects it to a month on three accounts and a year of storage. Against the code as it was
it measured **302 MB a year of database** (the snapshot table 179 MB, the cron log 120 MB) and **107 GB a month of
egress**, and ran out of heap decoding a 60 MB payload. After the changes below: **33 MB, 497 MB, 178 000 messages**
against budgets of 50 MB, 512 MB, 200 000.

The first versions of the fake measured only bytes sent; the outage was bytes *kept*. So a table's size is the
high-water mark of live rows plus not-yet-vacuumed dead ones, and a projection may only be bounded by a purge the
test reads out of the deployed SQL.

## What was built

1. **History units are diffs** (`HistoryDiff.kt`): per touched entry, before and after. Commit applies them exactly;
   undo and redo three-way, field by field, so undoing a unit never takes back a later change of another device.
   Old whole-snapshot units still load and are rewritten small.
2. **Per-device history**: each unit carries its device, a per-device key, `undone` and when that changed. Undo/redo
   walk this device's units only; a new edit discards only this device's redo branch.
3. **Rows per entity** (`EntityRows`, migration 20260917000000): `scheduler_entity` (one row per task, cell, list,
   panel, alarm…, plus one per other field), `history_unit` (one row per unit, trimmed to 1000 per category), both
   revisioned by one sequence; `scheduler_head`, one row per account, the only published table. The snapshot table
   is emptied and made read-only.
4. **Tombstones live a week** (`purge_scheduler_tombstones`, daily): a rename retires a task row, so tombstones
   accumulate at the pace of typing (~56 an hour in the heavy scenario, 80 MB a year per account). A device that has
   not pulled for six days reads the live rows in full instead of trusting its cursor.
5. **A push records its rows before the request** (`RowBase.pending`), so a lost answer is written again — found by
   `RowSyncTest` when the lost-acknowledgement test was rewritten against the fake: without it, deleting an entity
   whose creation was never acknowledged sent no tombstone.
6. **Traffic**: the subscriber ignores the head change it wrote (was 184 reconciles an hour, now 121); the scheduler
   peers' channel is silent when no other device is there (was 188 broadcasts an hour, now 76); the active-session
   push is skipped when unchanged; the cron log keeps a day.

## Rejected

- **A history row per unit with the old whole-tree deltas** (the user's first proposal: *"it should simply add a new
  row in the database table for history, and remove the oldest one"*). Right shape, but 1000 units × 400 KB is still
  400 MB per category — the size of a unit had to change, not only where it lives.
- **Device-local history for some units.** Asked for at first, then replaced by the rule above: one shared history,
  per-device undo.
- **Publishing `scheduler_entity`** to Realtime: one message per changed row per device — a heavy hour of edits would
  exhaust the monthly two million on its own.
- **Hard deletes instead of tombstones**: a pull by revision cannot see a row that is gone. Tombstones with a bounded
  life and a full read for a stale device cost under 2 MB per account instead.
- **Thinning the scenario to pass.** The one change made — a calendar block every 20 minutes instead of 3 — is
  because every 3 minutes is 73 000 blocks a year per account, not a plausible heavy user.

## Known limits

- The presence tick (`publish_presence` every `t_a`) is a quarter of the egress; it belongs to the pause cue.
- The egress projection is within 3 % of its budget; a new per-reconcile request will fail the test.
- `FakeSupabase`'s autovacuum and tuple sizes are models. They err toward the quota but are not Postgres.
