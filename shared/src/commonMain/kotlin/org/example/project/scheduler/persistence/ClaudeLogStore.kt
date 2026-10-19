package org.example.project.scheduler.persistence

import org.example.project.claudelogs.ClaudeLogRecord

/** One kept log record, and whether the server is known to hold it (this device pushed it, or pulled it). */
data class StoredClaudeLog(val record: ClaudeLogRecord, val onServer: Boolean)

/**
 * User rule 2026-10-19: **the Claude Code logs the account keeps** (`claudelogs/ClaudeLogArchive`), on this device —
 * ACCOUNT-SCOPED like the state, so each account keeps its own and a switch deletes nothing. Every call names the
 * account it is about ([activeClaudeLogAccount] read once by the caller), so a read that started before a switch never
 * writes into the account switched to. `App` reads it with `store as? ClaudeLogStore`; a store without it (the web's)
 * keeps the logs in the page's memory only.
 */
interface ClaudeLogStore {
    /** The account whose logs are kept now: the signed-in one, else the unclaimed partition. */
    fun activeClaudeLogAccount(): String

    fun loadClaudeLogs(account: String): List<StoredClaudeLog>

    /** Writes [upserts] and deletes the records whose ids are in [deletes], in one transaction. */
    fun saveClaudeLogs(account: String, upserts: List<StoredClaudeLog>, deletes: Collection<String>)

    /** The highest server revision pulled for [account]; 0 before the first pull. */
    fun loadClaudeLogCursor(account: String): Long

    fun saveClaudeLogCursor(account: String, revision: Long)
}
