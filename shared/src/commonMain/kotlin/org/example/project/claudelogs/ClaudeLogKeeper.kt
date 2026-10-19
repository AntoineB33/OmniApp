package org.example.project.claudelogs

import org.example.project.scheduler.persistence.ClaudeLogStore
import org.example.project.scheduler.persistence.StoredClaudeLog
import org.example.project.scheduler.sync.ClaudeLogRow
import org.example.project.scheduler.sync.SchedulerSyncEngine

/**
 * User rule 2026-10-19 (*"when the program gets logs from the local folder, it stays in the account's memory. If the
 * folder got deleted, the app keeps the logs"*): **the one keeper of the account's logs** on this device. It holds the
 * account's archive ([ClaudeLogArchive]) in memory and in [store], adds what a folder read gives ([absorb]) and what the
 * server sends ([applySync]), drops the oldest past [limitBytes], and says what to send ([pushBatches]).
 *
 * Not thread-safe: `App` calls it from one read at a time. A [store] of null (the web) keeps the archive in memory.
 */
class ClaudeLogKeeper(
    private val store: ClaudeLogStore?,
    private val limitBytes: Long = claudeLogArchiveLimitBytes,
) {
    private var account: String? = null
    private var records: List<ClaudeLogRecord> = emptyList()
    private val onServer = HashSet<String>()
    private var cursor = 0L

    /** The account the archive is about now. */
    fun activeAccount(): String = store?.activeClaudeLogAccount() ?: (account ?: "")

    /** The archive of [account] as sessions, loaded from the store the first time it is asked of. */
    fun sessions(account: String): List<ClaudeLogSession> {
        load(account)
        return ClaudeLogArchive.sessions(records)
    }

    /**
     * The archive of [account] with the sessions a folder read gave ([read]; null = no folder, or not readable — the
     * archive is kept as it is), bounded and saved. A record read again replaces the kept one (a title it gained); one
     * no longer in the folder stays.
     */
    fun absorb(account: String, read: List<ClaudeLogSession>?): List<ClaudeLogSession> {
        load(account)
        if (read != null) replace(ClaudeLogArchive.merge(records, ClaudeLogArchive.records(read)))
        return ClaudeLogArchive.sessions(records)
    }

    /** What to send for [account]: the newest records that fit the server's bound and it does not hold, in requests. */
    fun pushBatches(account: String): List<List<ClaudeLogRow>> {
        load(account)
        return ClaudeLogArchive.batches(ClaudeLogArchive.toPush(records, onServer)).map { batch -> batch.map(::rowOf) }
    }

    /** The server revision [account]'s logs were pulled to. */
    fun cursor(account: String): Long {
        load(account)
        return cursor
    }

    /**
     * What a sync brought: the pushed rows marked as on the server, the pulled ones added. Null when it is about
     * another account than the one kept now (a switch while it ran), or pulled nothing; else the archive's sessions.
     */
    fun applySync(result: SchedulerSyncEngine.ClaudeLogSync): List<ClaudeLogSession>? {
        if (result.account != account) return null
        val pulled = result.pulled.mapNotNull(::recordOf)
        onServer += result.pushed
        pulled.mapTo(onServer) { it.entryId }
        if (result.cursor != cursor) {
            cursor = result.cursor
            store?.saveClaudeLogCursor(result.account, cursor)
        }
        replace(ClaudeLogArchive.merge(records, pulled), markedOnServer = result.pushed)
        return if (pulled.isEmpty()) null else ClaudeLogArchive.sessions(records)
    }

    private fun load(account: String) {
        if (account == this.account) return
        this.account = account
        onServer.clear()
        val stored = store?.loadClaudeLogs(account).orEmpty()
        records = stored.map { it.record }
        stored.filter { it.onServer }.mapTo(onServer) { it.record.entryId }
        cursor = store?.loadClaudeLogCursor(account) ?: 0L
    }

    /**
     * Makes [next] the archive, bounded to [limitBytes], writing to the store only the records that changed or were
     * dropped — and those of [markedOnServer] whose flag moved.
     */
    private fun replace(next: List<ClaudeLogRecord>, markedOnServer: Set<String> = emptySet()) {
        val account = account ?: return
        val bounded = ClaudeLogArchive.bounded(next, limitBytes)
        val old = records.associateBy { it.entryId }
        val keptIds = bounded.mapTo(HashSet()) { it.entryId }
        val deletes = old.keys.filter { it !in keptIds }
        val upserts = bounded.filter { old[it.entryId] != it || it.entryId in markedOnServer }
        if (upserts.isEmpty() && deletes.isEmpty()) return
        onServer.removeAll(deletes.toSet())
        records = bounded
        store?.saveClaudeLogs(account, upserts.map { StoredClaudeLog(it, it.entryId in onServer) }, deletes)
    }

    private fun rowOf(r: ClaudeLogRecord) =
        ClaudeLogRow(
            entryId = r.entryId, kind = r.kind.name, timeMillis = r.timeMillis, session = r.session,
            sessionTitle = r.sessionTitle, project = r.project, ordinal = r.ordinal, body = r.text,
        )

    private fun recordOf(row: ClaudeLogRow): ClaudeLogRecord? {
        // A kind this build does not know (a newer build's) is not shown.
        val kind = ClaudeLogKind.entries.firstOrNull { it.name == row.kind } ?: return null
        return ClaudeLogRecord(row.entryId, kind, row.timeMillis, row.session, row.sessionTitle, row.project, row.ordinal, row.body)
    }
}
