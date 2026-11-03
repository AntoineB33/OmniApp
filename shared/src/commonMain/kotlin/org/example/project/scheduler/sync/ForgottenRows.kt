package org.example.project.scheduler.sync

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull

/**
 * `server-quota.md` § *Near the limit, the server forgets* (user rule 2026-11-03: *"instead of deleting them, they are
 * replaced by a single row saying that they are not saved in this db anymore"*): **the row standing for the history the
 * server forgot.** `forget_old_history` (migration 20261103010000) deletes an account's oldest history rows when the
 * project nears its limit, and writes this ONE row in their place, an ordinary synced row of its own kind.
 *
 * A device holding rows the server no longer has keeps them: the rows it [covers] are read as still there, unchanged,
 * so a full read does not drop them and a reconcile does not send them back. It is never part of the state: the device
 * neither pushes it nor deletes it.
 *
 * What the server forgets is **history**, and the same predicate on both sides says what that is: a task no cell holds
 * (it stays only for its past on the calendar) and a calendar block that ended before [Marker.untilMillis].
 */
object ForgottenRows {
    const val KIND: String = "forgotten"
    const val ID: String = "history"

    /** What the server forgot, all told: the history before [untilMillis], [rows] rows of [bytes]. */
    @Serializable
    data class Marker(val untilMillis: Long = 0L, val rows: Long = 0L, val bytes: Long = 0L)

    private val json = Json { ignoreUnknownKeys = true }

    fun isMarker(key: EntityRows.Key): Boolean = key.kind == KIND

    fun marker(payload: String?): Marker? = payload?.let { runCatching { json.decodeFromString(Marker.serializer(), it) }.getOrNull() }

    /** Whether the row [key] holding [payload] is history the server may have forgotten under [marker]. */
    fun covers(key: EntityRows.Key, payload: String?, marker: Marker?): Boolean {
        if (marker == null || payload == null) return false
        val row = runCatching { json.parseToJsonElement(payload) as? JsonObject }.getOrNull() ?: return false
        return when (key.kind) {
            // A task no cell holds: its `occurrences` empty, which the codec leaves out.
            "tasks" -> (row["occurrences"] as? JsonArray).isNullOrEmpty()
            "panels" -> ((row["end"] as? JsonPrimitive)?.longOrNull ?: Long.MAX_VALUE) < marker.untilMillis
            else -> false
        }
    }
}
