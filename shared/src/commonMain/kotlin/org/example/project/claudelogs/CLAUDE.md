Invariants for this directory. Paths are from the repo root; global rules are in `CLAUDE.md`.

The Claude Code logs page (`OmniPage.ClaudeLogs`) is the Task Scheduler page's own code — its lateral menu, window bar
and Search window — over `SearchDomain`'s three logs kinds (`CLAUDE_KINDS`). Never draw a second page or a second search
for it: a new need is a kind, a filter, a sort or an action of `SearchDomain`, like any other kind's.

- **Read-only over the folder.** Nothing here writes into the logs.
- **What was first read of an entry is what is kept** (user rule 2026-11-01, `ClaudeLogArchive.merge`): a log deleted,
  edited or cut by hand changes nothing kept and adds no second copy. An entry is told by its id, else by its session,
  instant and kind (`Identity`), which do not move when lines are cut. Only its title, project and path can follow.
- **What a folder gave stays in the account's memory** (user rule 2026-10-19): `ClaudeLogKeeper` is the one keeper —
  the archive per account (`ClaudeLogStore`, the `claude_log_entry` table, 18.sqm), kept after the folder is gone,
  bounded per device (`claudeLogArchiveLimitBytes`, a platform capability) and dropping the oldest first. The page
  lists the archive, not the folder (`ClaudeLogArchive.sessions`).
- **The server keeps the newest 300 KB per account** (`ClaudeLogArchive.SERVER_LIMIT_BYTES`, the trigger of migration
  20261101000000 — the two figures must agree, `ServerQuotaTest` fails otherwise). A device sends only the newest that
  fit, never what the server holds already, in requests of at most `PUSH_BATCH_BYTES`; rows are written once
  (`ignore-duplicates`), never updated. The sync is asked when the logs are read (`SchedulerSyncEngine.syncClaudeLogs`),
  and by a write of Claude Code's (below), never by a reconcile, never on a timer, and nothing is published to Realtime.
- **While the app is open, what Claude Code writes goes up as it is written** (user rule 2026-11-01): on the system's
  event (`watchClaudeLogs`, a capability: the desktop's), whatever page is shown, the first write of a burst starting a
  wait of `PUSH_WINDOW_MILLIS`; then what changed is read, from where each log was left (`ClaudeLogTails`,
  `ClaudeLogIndex.changed`), added to the archive (`ClaudeLogKeeper.absorbChanged`) and sent in one request, with no pull.
  Nothing new, no request. Another device still sees it at its next read: live would mean Realtime on a row table.
- **One reading of a log** (`ClaudeLogParser`) and **one text match** (`ClaudeLogSearch`); the rows are
  `SearchDomain.claudeResults`'s, handed the logs as `App`'s windows are handed in (`ClaudeLogCatalog`).
- **Between two prompts, one reasoning chain and one answer** (user rule 2026-10-31, `foldedTurns`, read by
  `ClaudeLogCatalog.entries` alone): the last text is the answer, all the rest one chain. A reading, not a rewrite: the
  parser, the archive and the server keep the log's own entries, so old logs read the same and nothing is re-pushed.
- **Read when asked or written, never on a timer** (the page first shown, the directory set, Load/Refresh, a write);
  `ClaudeLogIndex` reads only the files whose size or date changed, and those from where it stopped. Reads, and the first build of each kind's rows, run off the UI
  thread; a keystroke only filters (`ClaudeLogCatalog.memo`, `ClaudeLogEntry.searchText`).
- **What the page itself keeps is local-only view state**: the directory (`ClaudeLogsSettings`, the `ClaudeLogs` row) and its
  menu (`CustomMenuButtons.CLAUDE_LOGS_PLACEMENT_ID`), both in `App`'s `VIEW_ROWS`. A Search window says its page in
  its configuration (`SearchDomain.Config.page`).
- The file system is an injected capability (`claudeLogFilesSupported`, `docs/PLATFORMS.md`).

Verify with `./gradlew :shared:jvmTest --tests "*ClaudeLogsTest*"`.
