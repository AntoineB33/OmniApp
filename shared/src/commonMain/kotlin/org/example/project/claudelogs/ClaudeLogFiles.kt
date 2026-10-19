package org.example.project.claudelogs

/**
 * Whether this platform can read Claude Code's logs off the file system — an injected capability of the build's own
 * source set (`docs/PLATFORMS.md`), not a question about which build this is.
 */
expect val claudeLogFilesSupported: Boolean

/**
 * The session logs under [directory]: its own `*.jsonl` (a project's folder) and its sub-folders' (the `projects`
 * folder, one sub-folder per project). Null when [directory] is not a readable folder or the platform reads none.
 */
expect fun listClaudeLogFiles(directory: String): List<ClaudeLogFile>?

/** Every line of [path], in order; nothing when it cannot be read. */
expect fun forEachClaudeLogLine(path: String, onLine: (String) -> Unit)

/**
 * Where Claude Code keeps its logs on this device — `<home>/.claude/projects` — when that folder exists; null where it
 * does not or the platform cannot tell. The directory field suggests it ([ClaudeLogsSettings.directoryCompletion]).
 */
expect fun defaultClaudeLogsDirectory(): String?

/**
 * How much of the account's logs this device keeps ([ClaudeLogArchive]), oldest dropped first — user rule 2026-10-19:
 * *"the limit of the memory is adapted for each device"*. A capability of the build's own source set, like
 * [claudeLogFilesSupported]: what the platform's storage can spare.
 */
expect val claudeLogArchiveLimitBytes: Long
