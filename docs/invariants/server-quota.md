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
  purge from the migrations AND its schedule in `pause-cue-setup.sql`, the history trim (1000 per category).
- **A failure prints the per-table, per-call and per-stream breakdown.** Fix the call that costs; do not raise the
  budget or thin the scenario to pass. Changing the scenario needs a reason about real use written next to it.

## What keeps it there

- **Nothing the app stores grows with editing, except through a bound.** Entity rows are rewritten in place (one row
  per entity); a tombstone lives a week; history is 1000 units per category; the pg_cron log a day. A new table that
  accumulates rows needs its bound, and the bound needs to be in the projection.
- **Never rewrite a large row to change a small part of it.** That is the outage. A value that changes with editing
  goes in its own row, sized by what changed (`sync-and-accounts.md` § *Sync by rows*).
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
- **No timer-driven request** (`CLAUDE.md`). The presence tick (`t_a`) is the pause cue's own, bounded, exception.
