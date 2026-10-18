Invariants for this directory. Paths are from the repo root; global rules are in `CLAUDE.md`.

The Claude Code logs page (`OmniPage.ClaudeLogs`) is the Task Scheduler page's own code — its lateral menu, window bar
and Search window — over `SearchDomain`'s three logs kinds (`CLAUDE_KINDS`). Never draw a second page or a second search
for it: a new need is a kind, a filter, a sort or an action of `SearchDomain`, like any other kind's.

- **Read-only.** Nothing here writes into the logs, and nothing reaches the server.
- **One reading of a log** (`ClaudeLogParser`) and **one text match** (`ClaudeLogSearch`); the rows are
  `SearchDomain.claudeResults`'s, handed the logs as `App`'s windows are handed in (`ClaudeLogCatalog`).
- **Read when asked, never on a timer** (the page first shown, the directory set, Load/Refresh); `ClaudeLogIndex`
  re-reads only the files whose size or date changed. Reads, and the first build of each kind's rows, run off the UI
  thread; a keystroke only filters (`ClaudeLogCatalog.memo`, `ClaudeLogEntry.searchText`).
- **What the page keeps is local-only view state**: the directory (`ClaudeLogsSettings`, the `ClaudeLogs` row) and its
  menu (`CustomMenuButtons.CLAUDE_LOGS_PLACEMENT_ID`), both in `App`'s `VIEW_ROWS`. A Search window says its page in
  its configuration (`SearchDomain.Config.page`).
- The file system is an injected capability (`claudeLogFilesSupported`, `docs/PLATFORMS.md`).

Verify with `./gradlew :shared:jvmTest --tests "*ClaudeLogsTest*"`.
