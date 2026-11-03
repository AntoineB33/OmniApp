package org.example.project.scheduler.sync

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import org.example.project.scheduler.model.WellKnownIds

/**
 * `server-quota.md` § *Near the limit, the server forgets* (user rule 2026-11-03: *"instead of deleting them, they are
 * replaced by a single row saying that they are not saved in this db anymore"*): **the row standing for the history the
 * server forgot.** `forget_old_history` (migrations 20261103010000, 20261104000000) deletes an account's oldest history
 * rows when the project nears its limit, and writes this ONE row in their place, an ordinary synced row of its own kind.
 *
 * A device holding rows the server no longer has keeps them: the rows [coveredKeys] names are read as still there,
 * unchanged, so a full read does not drop them and a reconcile does not send them back. It is never part of the state:
 * the device neither pushes it nor deletes it.
 *
 * What the server forgets is **history**, and the same predicate on both sides says what that is:
 * - a task no cell holds (it stays only for its past on the calendar, or as a detached parent for its sub-tree);
 * - **that task's sub-tree** (2026-11-04): its sub-list, the cells in it, and every task held only in those cells, and so
 *   on down. No row of the tree reaches it, so nothing shows it: a renamed parent keeps its sub-tree so that naming it
 *   again brings it back (`task-tree.md` § *A sub-list belongs to the task id*), and ServerQuotaTest measured those
 *   sub-trees as most of what the tree grows by;
 * - a calendar block that ended before [Marker.untilMillis].
 *
 * The root task, list and cell are never history.
 */
object ForgottenRows {
    const val KIND: String = "forgotten"
    const val ID: String = "history"

    /** What the server forgot, all told: the history before [untilMillis], [rows] rows of [bytes]. */
    @Serializable
    data class Marker(val untilMillis: Long = 0L, val rows: Long = 0L, val bytes: Long = 0L)

    private val json = Json { ignoreUnknownKeys = true }

    private val ROOTS =
        setOf(WellKnownIds.ROOT_TASK.value, WellKnownIds.ROOT_LIST.value, WellKnownIds.ROOT_CELL.value, WellKnownIds.ROOT_CELL_LIST.value)

    fun isMarker(key: EntityRows.Key): Boolean = key.kind == KIND

    fun marker(payload: String?): Marker? = payload?.let { runCatching { json.decodeFromString(Marker.serializer(), it) }.getOrNull() }

    /**
     * The keys of [rows] that are history the server may have forgotten under [marker]; none without one. The tree's
     * part is a walk to a fixed point: the tasks no cell holds, then their sub-lists and those lists' cells, then the
     * tasks every one of whose cells is in such a list, and again.
     */
    fun coveredKeys(rows: Map<EntityRows.Key, String>, marker: Marker?): Set<EntityRows.Key> {
        if (marker == null) return emptySet()
        val covered = HashSet<EntityRows.Key>()
        val tasks = HashMap<String, JsonObject>()
        val cellList = HashMap<String, String>()
        for ((key, payload) in rows) {
            if (key.id in ROOTS) continue
            val row = runCatching { json.parseToJsonElement(payload) as? JsonObject }.getOrNull() ?: continue
            when (key.kind) {
                "tasks" -> tasks[key.id] = row
                "cells" -> (row["parentListId"] as? JsonPrimitive)?.contentOrNull?.let { cellList[key.id] = it }
                "panels" -> if (((row["end"] as? JsonPrimitive)?.longOrNull ?: Long.MAX_VALUE) < marker.untilMillis) covered += key
            }
        }
        val listKeys = rows.keys.filter { it.kind == "lists" && it.id !in ROOTS }.mapTo(HashSet()) { it.id }
        val detachedTasks = HashSet<String>()
        val detachedLists = HashSet<String>()
        val detachedCells = HashSet<String>()
        // A task no cell holds: its `occurrences` empty, which the codec leaves out.
        fun occurrences(task: JsonObject): List<String> = (task["occurrences"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
        var grew = true
        while (grew) {
            grew = false
            for ((id, task) in tasks) {
                if (id in detachedTasks) continue
                val held = occurrences(task)
                // A cell not among the rows is not known to be detached: the task stays.
                if (held.isEmpty() || held.all { it in detachedCells }) {
                    detachedTasks += id
                    grew = true
                    val list = (task["childListId"] as? JsonPrimitive)?.contentOrNull
                    if (list != null && list in listKeys && detachedLists.add(list)) {
                        for ((cell, parent) in cellList) if (parent == list) detachedCells += cell
                    }
                }
            }
        }
        for (id in detachedTasks) covered += EntityRows.Key("tasks", id)
        for (id in detachedLists) covered += EntityRows.Key("lists", id)
        for (id in detachedCells) covered += EntityRows.Key("cells", id)
        return covered
    }
}
