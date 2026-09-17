package org.example.project.scheduler.sync

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * `docs/invariants/sync-and-accounts.md` § *Sync by rows*: **the synced state as rows, one per entity.**
 *
 * The state that syncs is the authoritative projection's payload (`SchedulerStateCodec.syncFingerprint`), a JSON
 * object. It is split generically, so a field the model gains syncs without this file knowing it:
 *
 * - a top-level ARRAY whose every element is an object with an `id` — the tasks, cells, lists, panels, alarms,
 *   timers, task trees, categories… — is one row per element: kind = the field name, id = the element's id;
 * - every other top-level field (a scalar, an object, an array without ids) is one row of kind [FIELD_KIND].
 *
 * An edit then writes the rows it changed. [join] is the inverse, and what it builds decodes through the codec
 * like any payload, with every heal and migration.
 */
object EntityRows {
    const val FIELD_KIND: String = "field"

    data class Key(val kind: String, val id: String)

    private val json = Json { ignoreUnknownKeys = true }

    /** The fields that never sync as rows: the history rides its own table. */
    private val EXCLUDED = setOf("histories")

    fun split(payload: String): Map<Key, String> {
        val root = runCatching { json.parseToJsonElement(payload) as? JsonObject }.getOrNull() ?: return emptyMap()
        val out = LinkedHashMap<Key, String>()
        for ((field, value) in root) {
            if (field in EXCLUDED) continue
            val ids = (value as? JsonArray)?.map { element -> ((element as? JsonObject)?.get("id") as? JsonPrimitive)?.takeIf { it.isString }?.content }
            if (ids != null && ids.isNotEmpty() && ids.all { it != null } && ids.toSet().size == ids.size) {
                (value as JsonArray).forEachIndexed { index, element -> out[Key(field, ids[index]!!)] = element.toString() }
            } else {
                out[Key(FIELD_KIND, field)] = value.toString()
            }
        }
        return out
    }

    /**
     * The payload [rows] describe. [arrayKinds] names the kinds that are arrays of entities even when no row of them
     * is left (every task deleted), so the field decodes as an empty list rather than as absent; any kind that has
     * rows is an array anyway.
     */
    fun join(rows: Map<Key, String>): String {
        val fields = LinkedHashMap<String, kotlinx.serialization.json.JsonElement>()
        val arrays = LinkedHashMap<String, MutableList<kotlinx.serialization.json.JsonElement>>()
        for ((key, text) in rows) {
            val element = runCatching { json.parseToJsonElement(text) }.getOrNull() ?: continue
            if (key.kind == FIELD_KIND) fields[key.id] = element
            else arrays.getOrPut(key.kind) { ArrayList() } += element
        }
        for ((kind, elements) in arrays) fields[kind] = JsonArray(elements)
        return JsonObject(fields).toString()
    }
}
