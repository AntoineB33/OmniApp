package org.example.project.ui

import androidx.compose.runtime.staticCompositionLocalOf
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Anomaly 2026-11-15 (*"I pinned the window …, I restart the app, and all the windows disappeared... Only the first one,
 * the default search window, remains"*): **the pop-ups left open come back after a restart, as the windows do** — a
 * field's Search window open ([fieldOpen], by the field's window id, [PopupSearchField]) and a configurations tree left
 * PINNED, with the categories it showed ([tree], by its ▾ window id, [ConfigurationTreeArrow]). What they showed of
 * themselves is kept by their id where every Search window's parts are ([rememberKeptParts]). A pop-up opened inside
 * another has an id that starts with that one's: a pop-up closed forgets every one opened inside it.
 *
 * Local-only view state (`persistence.md`), on the [PLACEMENT_ID] placement row: [read] what it holds, [write] it back.
 */
class PopupMemory(read: () -> String?, private val write: (String) -> Unit) {
    @Serializable
    private data class Stored(
        val fields: List<String> = emptyList(),
        val trees: Map<String, List<String>> = emptyMap(),
        /**
         * New 2026-11-16 ("Recent › Elsewhere › Looked into the children"): by ▾ window id, the categories of its tree
         * looked into — opened and stayed on — and when, in epoch ms. Absent = none.
         */
        val lookedInto: Map<String, Map<String, Long>> = emptyMap(),
    )

    private var stored: Stored = read()?.let { text -> runCatching { json.decodeFromString(Stored.serializer(), text) }.getOrNull() } ?: Stored()

    /** Whether the field's window [id] was open. */
    fun fieldOpen(id: String): Boolean = id in stored.fields

    /** The field's window [id] opened, or closed — and with it every pop-up opened inside it. */
    fun setField(id: String, open: Boolean) {
        val next =
            if (open) stored.copy(fields = (stored.fields + id).distinct())
            else stored.copy(fields = stored.fields.filterNot { it.startsWith(id) }, trees = stored.trees.filterKeys { !it.startsWith(id) })
        save(next)
    }

    /**
     * User rule 2026-11-16 (*"looked into the children"*): the ▾ window [arrow] looked into its tree's category [node]
     * at [atMillis]. Kept bounded: the [LOOKED_INTO_KEPT] last of each ▾ window, the [LOOKED_INTO_WINDOWS] ▾ windows
     * that looked last.
     */
    fun lookedInto(arrow: String, node: String, atMillis: Long) {
        val own = ((stored.lookedInto[arrow] ?: emptyMap()) + (node to atMillis))
            .entries.sortedByDescending { it.value }.take(LOOKED_INTO_KEPT).associate { it.key to it.value }
        val all = (stored.lookedInto + (arrow to own))
            .entries.sortedByDescending { e -> e.value.values.maxOrNull() ?: 0L }.take(LOOKED_INTO_WINDOWS).associate { it.key to it.value }
        save(stored.copy(lookedInto = all))
    }

    /** The categories every ▾ window but [arrow] looked into, each at the last time one did ([lookedInto]). */
    fun lookedIntoElsewhere(arrow: String?): Map<String, Long> {
        val out = HashMap<String, Long>()
        for ((id, nodes) in stored.lookedInto) {
            if (id == arrow) continue
            for ((node, at) in nodes) if (at > (out[node] ?: Long.MIN_VALUE)) out[node] = at
        }
        return out
    }

    /** The categories the pinned tree [id] showed, or null where it was not left pinned. */
    fun tree(id: String): List<String>? = stored.trees[id]

    /** The tree [id] left pinned showing [path], or not ([path] null) — and then every pop-up opened inside it forgotten. */
    fun setTree(id: String, path: List<String>?) {
        val next =
            if (path != null) stored.copy(trees = stored.trees + (id to path))
            else stored.copy(fields = stored.fields.filterNot { it.startsWith(id) }, trees = stored.trees.filterKeys { !it.startsWith(id) })
        save(next)
    }

    private fun save(next: Stored) {
        if (next == stored) return
        stored = next
        write(json.encodeToString(Stored.serializer(), next))
    }

    companion object {
        /** How many categories looked into each ▾ window keeps, and how many ▾ windows are kept ([lookedInto]). */
        const val LOOKED_INTO_KEPT: Int = 30
        const val LOOKED_INTO_WINDOWS: Int = 20

        /** The placement row it is kept on. */
        const val PLACEMENT_ID: String = "OpenPopups"

        private val json = Json { ignoreUnknownKeys = true }

        /**
         * What was kept, but the pop-ups of windows no longer open ([windowOpen], by frame id): a window of that id opened
         * later is another window, and none of its pop-ups is open.
         */
        fun kept(text: String?, windowOpen: (String) -> Boolean): String? {
            val stored = text?.let { runCatching { json.decodeFromString(Stored.serializer(), it) }.getOrNull() } ?: return text
            fun alive(id: String) = windowOpen(windowOf(id))
            // What was looked into is history, not a pop-up open: it outlives the window.
            return json.encodeToString(Stored.serializer(), stored.copy(fields = stored.fields.filter(::alive), trees = stored.trees.filterKeys(::alive)))
        }

        /** The frame id at the start of a pop-up's id: up to its first "/" or "▾". */
        fun windowOf(id: String): String = id.takeWhile { it != '/' && it != '▾' }
    }
}

val LocalPopupMemory = staticCompositionLocalOf<PopupMemory?> { null }
