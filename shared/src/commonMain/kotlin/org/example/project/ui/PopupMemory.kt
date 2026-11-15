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
    private data class Stored(val fields: List<String> = emptyList(), val trees: Map<String, List<String>> = emptyMap())

    private var stored: Stored = read()?.let { text -> runCatching { json.decodeFromString(Stored.serializer(), text) }.getOrNull() } ?: Stored()

    /** Whether the field's window [id] was open. */
    fun fieldOpen(id: String): Boolean = id in stored.fields

    /** The field's window [id] opened, or closed — and with it every pop-up opened inside it. */
    fun setField(id: String, open: Boolean) {
        val next =
            if (open) stored.copy(fields = (stored.fields + id).distinct())
            else Stored(stored.fields.filterNot { it.startsWith(id) }, stored.trees.filterKeys { !it.startsWith(id) })
        save(next)
    }

    /** The categories the pinned tree [id] showed, or null where it was not left pinned. */
    fun tree(id: String): List<String>? = stored.trees[id]

    /** The tree [id] left pinned showing [path], or not ([path] null) — and then every pop-up opened inside it forgotten. */
    fun setTree(id: String, path: List<String>?) {
        val next =
            if (path != null) stored.copy(trees = stored.trees + (id to path))
            else Stored(stored.fields.filterNot { it.startsWith(id) }, stored.trees.filterKeys { !it.startsWith(id) })
        save(next)
    }

    private fun save(next: Stored) {
        if (next == stored) return
        stored = next
        write(json.encodeToString(Stored.serializer(), next))
    }

    companion object {
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
            return json.encodeToString(Stored.serializer(), Stored(stored.fields.filter(::alive), stored.trees.filterKeys(::alive)))
        }

        /** The frame id at the start of a pop-up's id: up to its first "/" or "▾". */
        fun windowOf(id: String): String = id.takeWhile { it != '/' && it != '▾' }
    }
}

val LocalPopupMemory = staticCompositionLocalOf<PopupMemory?> { null }
