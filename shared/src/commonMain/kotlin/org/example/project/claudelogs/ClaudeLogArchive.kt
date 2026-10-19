package org.example.project.claudelogs

/**
 * User rule 2026-10-19: *"when the program gets logs from the local folder, it stays in the account's memory. If the
 * folder got deleted, the app keeps the logs. The limit of the memory is adapted for each device or server."*
 *
 * **One entry as the account keeps it**: what [ClaudeLogParser] read, flattened with the session it belongs to so it
 * can be stored and synced as one row. [entryId] is the entry's Search row id ([ClaudeLogCatalog.idOf]), stable while
 * the log is. What an answer replied to is not kept: it is the prompt before it in its session, put back by
 * [ClaudeLogArchive.sessions] — a copy of the prompt on every answer would double what is stored.
 *
 * [path] is where the log was read on THIS device; a row pulled from another device has none.
 */
data class ClaudeLogRecord(
    val entryId: String,
    val kind: ClaudeLogKind,
    val timeMillis: Long?,
    /** The log file's name without `.jsonl`: what groups the records back into sessions. */
    val session: String,
    val sessionTitle: String?,
    val project: String,
    val ordinal: Int,
    val text: String,
    val path: String = "",
) {
    /** What the record counts for against a limit ([ClaudeLogArchive.sizeOf]). */
    val sizeBytes: Long by lazy { ClaudeLogArchive.sizeOf(text, sessionTitle, project) }
}

/**
 * **The account's archive of the logs**: every record read off a folder or pulled from the server, kept after the
 * folder is gone, bounded per device ([claudeLogArchiveLimitBytes]) and on the server ([SERVER_LIMIT_BYTES]). Both
 * bounds drop the OLDEST records first.
 */
object ClaudeLogArchive {
    /**
     * What the server keeps of an account's logs: its newest records up to this many bytes, counted as [sizeOf] counts
     * them. The trigger `trim_claude_log_entries` (migration 20261019000000) does it; this is the same figure, so a
     * device never sends what the server would drop at once. `server-quota.md`: three accounts at this bound, and the
     * room a trim's deleted rows take until autovacuum, fit in what the 10 % database budget had left (2026-10-19).
     */
    const val SERVER_LIMIT_BYTES: Long = 400L * 1024

    /** At most this much is sent in one request, so a trim never has more than this to delete at once. */
    const val PUSH_BATCH_BYTES: Long = 32L * 1024

    /**
     * What a row costs besides its texts — its ids, its numbers, Postgres' tuple header — rounded up. The server's
     * `size_bytes` column adds the same.
     */
    const val ROW_OVERHEAD_BYTES: Long = 300L

    fun sizeOf(text: String, sessionTitle: String?, project: String): Long =
        utf8Length(text) + (sessionTitle?.let(::utf8Length) ?: 0L) + utf8Length(project) + ROW_OVERHEAD_BYTES

    /** The records of [sessions], as the account keeps them. */
    fun records(sessions: List<ClaudeLogSession>): List<ClaudeLogRecord> =
        sessions.flatMap { session ->
            val name = sessionName(session)
            session.entries.map { entry ->
                ClaudeLogRecord(
                    entryId = ClaudeLogCatalog.idOf(session, entry),
                    kind = entry.kind,
                    timeMillis = entry.timeMillis,
                    session = name,
                    sessionTitle = session.title,
                    project = session.project,
                    ordinal = entry.ordinal,
                    text = entry.text,
                    path = session.path,
                )
            }
        }

    /** The log file's name a session is grouped by, as [ClaudeLogCatalog.idOf] reads it. */
    fun sessionName(session: ClaudeLogSession): String =
        session.path.substringAfterLast('/').substringAfterLast('\\').removeSuffix(".jsonl")

    /**
     * [kept] with [incoming] added: one record per id, the incoming one where both hold it (a log read again may have
     * gained its title, or a path on this device). A record [kept] holds and [incoming] does not stays — that is what
     * keeps a log whose folder was deleted.
     */
    fun merge(kept: List<ClaudeLogRecord>, incoming: List<ClaudeLogRecord>): List<ClaudeLogRecord> {
        if (incoming.isEmpty()) return kept
        val byId = LinkedHashMap<String, ClaudeLogRecord>(kept.size + incoming.size)
        for (r in kept) byId[r.entryId] = r
        for (r in incoming) {
            val before = byId[r.entryId]
            // A pulled record has no path here: the one this device read the log at is kept.
            byId[r.entryId] = if (before != null && r.path.isEmpty()) r.copy(path = before.path) else r
        }
        return byId.values.toList()
    }

    /** The newest records of [records] whose sizes add up to at most [limitBytes]; the rest are dropped. */
    fun bounded(records: List<ClaudeLogRecord>, limitBytes: Long): List<ClaudeLogRecord> {
        var total = 0L
        for (r in records) total += r.sizeBytes
        if (total <= limitBytes) return records
        val kept = HashSet<String>()
        var used = 0L
        for (r in newestFirst(records)) {
            if (used + r.sizeBytes > limitBytes) break
            used += r.sizeBytes
            kept += r.entryId
        }
        return records.filter { it.entryId in kept }
    }

    /**
     * What this device sends the server: of [records], the newest that fit in [SERVER_LIMIT_BYTES] and are not
     * [onServer] already (what was pushed or pulled before). An older one is not sent: the server would drop it.
     */
    fun toPush(records: List<ClaudeLogRecord>, onServer: Set<String>): List<ClaudeLogRecord> =
        bounded(records, SERVER_LIMIT_BYTES).filter { it.entryId !in onServer }

    /** [records] in requests of at most [PUSH_BATCH_BYTES] each (one record larger than that goes alone). */
    fun batches(records: List<ClaudeLogRecord>): List<List<ClaudeLogRecord>> {
        val out = ArrayList<List<ClaudeLogRecord>>()
        var current = ArrayList<ClaudeLogRecord>()
        var used = 0L
        for (r in records) {
            if (current.isNotEmpty() && used + r.sizeBytes > PUSH_BATCH_BYTES) {
                out += current
                current = ArrayList()
                used = 0L
            }
            current += r
            used += r.sizeBytes
        }
        if (current.isNotEmpty()) out += current
        return out
    }

    /**
     * [records] grouped back into sessions, each in its log's order, an answer or a reasoning chain given the prompt
     * before it as what it replied to. Sessions come in the order their first record does.
     */
    fun sessions(records: List<ClaudeLogRecord>): List<ClaudeLogSession> =
        records.groupBy { it.session }.map { (name, list) ->
            val ordered = list.sortedBy { it.ordinal }
            var lastPrompt: String? = null
            val entries =
                ordered.map { r ->
                    val replyTo = if (r.kind == ClaudeLogKind.Prompt) null else lastPrompt
                    if (r.kind == ClaudeLogKind.Prompt) lastPrompt = r.text
                    ClaudeLogEntry(r.kind, r.text, r.timeMillis, replyTo, r.ordinal)
                }
            val first = ordered.first()
            val path = ordered.firstOrNull { it.path.isNotEmpty() }?.path.orEmpty()
            ClaudeLogSession(
                // A session pulled from another device: its name stands for the file, so its ids stay its own.
                path = path.ifEmpty { "$name.jsonl" },
                sessionId = name,
                folder = lastFolderOf(first.project),
                cwd = first.project,
                title = ordered.lastOrNull { it.sessionTitle != null }?.sessionTitle,
                entries = entries,
                project = first.project,
            ).also { it.entries.forEach { e -> e.searchText } }
        }

    private fun newestFirst(records: List<ClaudeLogRecord>): List<ClaudeLogRecord> =
        records.sortedWith(compareByDescending<ClaudeLogRecord> { it.timeMillis ?: Long.MIN_VALUE }.thenByDescending { it.ordinal })

    private fun utf8Length(s: String): Long {
        var n = 0L
        var i = 0
        while (i < s.length) {
            val c = s[i]
            n += when {
                c.code < 0x80 -> 1
                c.code < 0x800 -> 2
                c.isHighSurrogate() && i + 1 < s.length && s[i + 1].isLowSurrogate() -> { i++; 4 }
                else -> 3
            }
            i++
        }
        return n
    }

    /** The folder a session is shown under when no folder of this device holds it: its project's last folder. */
    private fun lastFolderOf(project: String): String = project.trimEnd('/', '\\').substringAfterLast('/').substringAfterLast('\\')
}
