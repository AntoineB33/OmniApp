# Server quota: what the app may cost Supabase

Active invariants. Reasoning and post-mortems: `docs/adr/` (ADR 0016). Dated log: `CHANGELOG.md`.
Global rules that always apply: `CLAUDE.md`.

---

## The budget

→ ADR 0016. On 2026-09-14 the project filled its 500 MB free-tier database and went down.

**A heavy month on three accounts must use less than a TENTH of every free-tier quota** (user rule, 2026-09-17).
`ServerQuotaTest` is the gate and `FreeTierQuota` holds the figures (read 2026-09-17):

| Quota | Free tier | Budget (10 %) |
| --- | --- | --- |
| Database size (projected over a year) | 500 MB | 50 MB |
| Egress / month | 5 GB | 512 MB |
| Realtime messages / month | 2 M | 200 000 |
| Largest Realtime message | 256 KB | 25.6 KB |
| Peak Realtime connections | 200 | 20 |
| Edge Function invocations / month | 500 000 | 50 000 |

"Heavy": a desktop and a phone on each of 3 accounts, 10 active hours a day, 30 days a month; every minute a title
typed character by character, five selection changes and a window switch; an expand every 5 minutes, a calendar
block every 20, a minimum-time change every 10. The account is the release account's shape (220 tasks, 440 cells).

- **`ServerQuotaTest` runs the app's own code** — `TaskSchedulerViewModel`, `SchedulerSyncEngine`,
  `RemoteSnapshotClient`, `SchedulerEngine` with its coordinator — against `FakeSupabase`, a Ktor `MockEngine` that
  answers every endpoint the app calls and emulates the triggers that decide what is stored. Only the sockets are
  replaced. A fake written for the test's convenience would measure the fake.
- **`FakeSupabase` counts what Postgres keeps, not what was sent**: a table's disk size is the high-water mark of its
  live rows PLUS the dead versions every UPDATE and DELETE leaves until autovacuum. That is the mechanism of the
  outage. Rows are sized as uncompressed JSON plus a tuple header; every estimate errs toward the quota.
- **A projection may only be bounded by something the server really does**, and the test reads that bound from the
  deployed files so that deleting it fails the test: the cron-log purge from `pause-cue-setup.sql`, the tombstone
  purge from the migrations AND its schedule in `pause-cue-setup.sql`, the history trim (1000 per category), the
  forgetting of old history (`forget_old_history` AND its schedule), the break rules replaced whole (`publish_break_rules`).
- **A failure prints the per-table, per-call and per-stream breakdown.** Fix the call that costs; do not raise the
  budget or thin the scenario to pass. Changing the scenario needs a reason about real use written next to it.

## Near the limit, the server forgets — HISTORY BUILT 2026-11-03/04, NOT DEPLOYED; THE VISIBLE TREE'S SUB-LISTS NOT BUILT

User rules 2026-11-03: *"When there is a new row in the db, it is sent (with a debounce) to the server to be added there
too. When a db is reaching its defined memory limit (lower for the free Supabase server), then the oldest rows are
deleted."*; *"If memory become really close to the limit … then there should be restrictions for any other tables too.
For example, in the task tree, a sub list with many task ids should be replaced by a row that says there are tasks there
not rememberred. Those should be tasks that are not recently modified"*; and, of the requirement that every device can
get the whole database from the server: *"Then modify this requirement."*

**The server is not a complete copy of the account.** Near its limit it forgets, and a device that never held what it
forgot cannot get it from there (a new device, a reinstall). That loss is accepted. The 10 % budget above is still the
limit, and nothing here may exceed it.

- **Each database has its limit**: the server's, which is the lower one (a share of the 10 % budget per account, so one
  account's growth never forgets another's rows), and each device's own, which is larger.
- **The restrictions tighten as the database fills, each table in turn**, never all at once and never at the edge: they
  start at a threshold below the limit and work in batches down to a lower one, because Postgres reuses the space of a
  deleted row rather than giving it back (the outage's mechanism).
  1. **The debounce grows**, up to days near the limit. It saves traffic; it frees no space.
  2. **The oldest history rows go first**: history units, past records and periods, the tasks no cell holds and their
     sub-trees, logs. Oldest by the time a row is about, not by when it was written.
  3. **Then the other tables**: in the task tree, a sub-list of tasks nobody modified recently is replaced by ONE row
     saying that tasks are there and not remembered. A recently modified task is never forgotten, so what a device is
     working on reaches every other device whole. NOT BUILT: measured 2026-11-04, the tree a row reaches does not grow
     in the heavy year (each rename replaces a task, its sub-list and its cell), so nothing here is needed to hold the
     tenth. Built, a device without the rows must keep the list's cell ids as it read them: `SnapshotMerge.repair` drops
     a cell id no row backs, and a list pushed back that way would take the cells from every device that holds them.
- **The server does the forgetting**, where the size and every account are seen (a scheduled job, like
  `trim_claude_log_entries`), and `ServerQuotaTest` must read that bound off the migration.
  BUILT for history (2026-11-03): `forget_old_history` (migration 20261103010000, daily in pause-cue-setup.sql). It reads
  the LIVE rows, never `pg_database_size`, which a delete does not shrink: read off the file, it would forget another
  batch each day until no history was left. From 90 % of the limit (the tenth, or an account's lower setting) down to
  70 %, oldest first, rows untouched for 30 days: a task no cell holds, a block ended before the cut. Deleted with no
  tombstone, and each account gets ONE row of kind `forgotten` (`ForgottenRows`) in their place, as the user asked:
  *"instead of deleting them, they are replaced by a single row saying that they are not saved in this db anymore."*
  `ForgottenHistoryTest`. `ServerQuotaTest` projects it honestly: only the history's growth is bounded, into the room
  the rest leaves, and that growth is at most what the table gained in the hour (a row that turns into history was
  there before it did).
  WIDENED 2026-11-04 (migration 20261104000000, the same job): **a task no cell holds takes its sub-tree with it** — its
  sub-list, the cells in it, every task held only there, and on down, walked to a fixed point on both sides
  (`ForgottenRows.coveredKeys`, the one predicate; the SQL spells it the same way). No row of the tree reaches such a
  sub-tree, so nothing shows it: it is the sub-tree a renamed parent keeps so that naming it again brings it back
  (`task-tree.md`). The root task, list and cell are never history. The cut can fall inside a sub-tree; a device that
  never held it then reads a task whose sub-list is gone, and nothing it shows changes.
- **Measured 2026-11-04, the heavy year holds the tenth**: database 45.0 MB a year (the rest 35.2 MB: `history_unit`
  22.0 at its 1000-unit bound, `device_active_session` 3.7, `scheduler_entity` without its history; the history 60.7 MB,
  9.8 MB of it kept), egress 499.4 MB a month. What took it there, from 91.0 MB and 513.7 MB on 2026-11-03:
  - the tree's growth was sub-trees of renamed parents (three sub-lists and their cells an hour), now history;
  - the hour's calendar blocks end within it, so the projection counts a block ending within a day as history;
  - `screen_break_rule` is ONE set of future periods, replaced whole by `publish_break_rules` (16.4 MB was its live size
    moving with the set, projected as growth): its high-water is its year, and the test reads the replacement off the
    migration;
  - the device tag in minted ids is 4 base-36 characters, not 8 hex (`sync-and-accounts.md`);
  - a device no longer reads its own active-session rows back (`device_id=neq.` on the pull), which it then threw away.
  The test account is built with tagged ids and no history of its own: built bare, the hour measured lengthened every id
  it renamed, a one-off the year then repeated.
- **What the server forgot is not a deletion** (`sync-and-accounts.md` § *Conflicts are MERGED*): a device holding those
  rows keeps them and does not push them back unless it edits them, and a device without them shows the "not remembered"
  row. A device's own limit forgets on its own side the same way.
- **What is forgotten, and how full each database is, is said on the Settings page** with the current usage of the
  Supabase free tier and a refresh button (an asked request, never a poll; sent even while the device works offline,
  user rule 2026-11-04, `sync-and-accounts.md` § *Working offline*). BUILT 2026-11-03: the settings page's menu
  holds `MenuControl.SupabaseUsage` and `MenuControl.MemoryLimits` (`ServerBudget`, `ServerUsageSettingsTest`,
  `ProjectUsageRefreshTest`).
  - **The database is the server's own measure** (`project_usage`, migration 20261103000000): the project's size and this
    account's rows, one call per press of Refresh. **Egress is what this device's logged calls drew this month**, a lower
    bound (the log keeps its newest calls, other devices draw too). Realtime messages and Edge Function calls are not
    visible from the app, and the panel says so.
  - **The limits are ONE figure for the project** (user rule 2026-11-03: *"the app must not exceeds 10% of any usage
    limits on any project"*): the project database's limit is an account setting, synced, clamped to a tenth of the
    free tier however it is typed or stored (`ServerBudget.serverLimitOf`); this device's is its own (`LOCAL_ONLY`).
    Until the trimming is built they bound nothing, and the fields say so.

## What keeps it there

- **Nothing the app stores grows with editing, except through a bound.** Entity rows are rewritten in place (one row
  per entity); a tombstone lives a week; history is 1000 units per category; the pg_cron log a day. A new table that
  accumulates rows needs its bound, and the bound needs to be in the projection.
- **The tasks a keystroke makes are rows only where the line was on them** (`task-tree.md`): one row per letter typed
  on the task the line is on, while no others survive the session; none reach the server before it ends
  (`sync-and-accounts.md`, no reconcile during an edit). Like every task with a record, they have no purge.
- **The budget's measure was flattered by a bug until 2026-11-03**: two devices minted the same ids, and the merge
  fused or lost the tasks they made, so fewer rows were left to project. With ids unique per device the heavy hour
  leaves about four tasks no cell holds (a parent renamed in Change Task keeps its sub-tree; a task the line was on keeps
  its time), and `ServerQuotaTest` is over the database budget until the server forgets (§ *Near the limit*). The longer
  ids also weigh on the hour measured, which the projection then repeats for a year though it happens once per id.
- **The calendar's hidden levels are rows like the panels they are pieces of, and bounded on the client**
  (`TimelineLevels.purged`: 90 days behind the line, 500 pieces; `calendar.md` § *tm_levels*). A piece is one small
  row, written when a block is placed over another and deleted when it is given back.
- **The Claude Code logs are bounded on the server, not by the client's good will**: `trim_claude_log_entries`
  (migration 20261101000000) keeps an account's newest 300 KB, counted by the server's own `size_bytes`, and
  `ServerQuotaTest` reads that bound off the migration and fails when it disagrees with `ClaudeLogArchive.SERVER_LIMIT_BYTES`.
  Each device keeps the rest itself. Rows are inserted once and never updated; the table is not published. Raising the
  bound needs room in the budget first (2026-11-01: lowered from 400 KB when the logs began to go up as they are written;
  every trim then leaves dead rows until autovacuum, and the heavy month came to 50.14 MB). A write of Claude Code's is
  event-driven and throttled (`PUSH_WINDOW_MILLIS`), with no pull: `ServerQuotaTest` runs twice the user's busiest hour.
- **Never rewrite a large row to change a small part of it.** That is the outage. A value that changes with editing
  goes in its own row, sized by what changed (`sync-and-accounts.md` § *Sync by rows*).
  So a long text the user writes is rows too: a life plan tab is one row per line (`LifePlanDomain.withText`), and typing
  in a line rewrites that line. A single line is still one row, however long it is typed.
- **The links between elements grow by hand alone** (`ElementLink`, `sync-and-accounts.md`): one small row per link, made by a press and never by typing or by time, so they have no purge. A link to an element deleted stays, for an undo.
- **A History Unit is a diff of what changed**, never a copy of the tree (`persistence.md` § *A History Unit is what
  changed*; `HistoryUnitSizeTest`: under 2 KB).
- **Only `scheduler_head` is in the Realtime publication for sync**: one small message per push per connected device.
  Publishing a row table costs one message per changed row per device.
- **A device ignores the head change it wrote itself** (`RealtimePhoenix.changeWriterDeviceId`): reconciling on its
  own echo is a whole round of requests at every edit.
- **The scheduler peers' channel is silent when nobody else is there** (`scheduler.md` § *One device plans*): a
  device nobody is using plans for itself without a word, and a device that has heard from no other one since its
  last unanswered probe leads alone without probing. A device hands its plan to a leader (`PeerMessage.Counter`) at
  most ONCE per election and only when that plan was made alone — never as a reply to every set of rules.
- **An unchanged value is not re-sent**: the active-session push is skipped when this device's own rows are those it
  last pushed.
- **No timer-driven request** (`CLAUDE.md`). The presence tick (`t_a`) is the pause cue's own, bounded, exception. The
  wait after a Claude Code write is a throttle on an event, not a timer: no write, no wait.
