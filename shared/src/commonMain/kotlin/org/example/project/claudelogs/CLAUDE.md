Invariants for this directory. Paths are from the repo root; global rules are in `CLAUDE.md`.

The Claude Code logs page (`OmniPage.ClaudeLogs`) is the Task Scheduler page's own code — its lateral menu, window bar
and Search window — over `SearchDomain`'s three logs kinds (`CLAUDE_KINDS`). Never draw a second page or a second search
for it: a new need is a kind, a filter, a sort or an action of `SearchDomain`, like any other kind's.

- **Read-only over the folder.** Nothing here writes into the logs.
- **What a folder gave stays in the account's memory** (user rule 2026-10-19): `ClaudeLogKeeper` is the one keeper —
  the archive per account (`ClaudeLogStore`, the `claude_log_entry` table, 18.sqm), kept after the folder is gone,
  bounded per device (`claudeLogArchiveLimitBytes`, a platform capability) and dropping the oldest first. The page
  lists the archive, not the folder (`ClaudeLogArchive.sessions`).
- **The server keeps the newest 400 KB per account** (`ClaudeLogArchive.SERVER_LIMIT_BYTES`, the trigger of migration
  20261019000000 — the two figures must agree, `ServerQuotaTest` fails otherwise). A device sends only the newest that
  fit, never what the server holds already, in requests of at most `PUSH_BATCH_BYTES`; rows are written once
  (`ignore-duplicates`), never updated. The sync is asked when the logs are read (`SchedulerSyncEngine.syncClaudeLogs`),
  never by a reconcile, never on a timer, and nothing is published to Realtime.
- **One reading of a log** (`ClaudeLogParser`) and **one text match** (`ClaudeLogSearch`); the rows are
  `SearchDomain.claudeResults`'s, handed the logs as `App`'s windows are handed in (`ClaudeLogCatalog`).
- **Read when asked, never on a timer** (the page first shown, the directory set, Load/Refresh); `ClaudeLogIndex`
  re-reads only the files whose size or date changed. Reads, and the first build of each kind's rows, run off the UI
  thread; a keystroke only filters (`ClaudeLogCatalog.memo`, `ClaudeLogEntry.searchText`).
- **What the page itself keeps is local-only view state**: the directory (`ClaudeLogsSettings`, the `ClaudeLogs` row) and its
  menu (`CustomMenuButtons.CLAUDE_LOGS_PLACEMENT_ID`), both in `App`'s `VIEW_ROWS`. A Search window says its page in
  its configuration (`SearchDomain.Config.page`).
- The file system is an injected capability (`claudeLogFilesSupported`, `docs/PLATFORMS.md`).

Verify with `./gradlew :shared:jvmTest --tests "*ClaudeLogsTest*"`.
