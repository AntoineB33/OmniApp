package org.example.project.ui

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.example.project.scheduler.state.HistoryChord
import org.example.project.scheduler.state.HistorySource
import org.example.project.scheduler.state.HistoryWindow

/**
 * User rule 2026-10-31: **what the app as a whole was showing when it closed** — the feature page, whether the lateral
 * menu was slid away, and which window stood over which. Local-only view state, never synced and never a History Unit,
 * on a placement row of its own ([PLACEMENT_ID]; no window has that frame id).
 */
@Serializable
data class AppViewState(
    /** `OmniPage`'s name. One this build does not know reads as the first page. */
    val page: String = "",
    val menuCollapsed: Boolean = false,
    /** Every open window by frame id, bottom first, on any page ([WindowFrameHost.rememberedOrder]). */
    val windowOrder: List<String> = emptyList(),
) {
    fun encode(): String = JSON.encodeToString(serializer(), this)

    companion object {
        const val PLACEMENT_ID: String = "AppView"

        /** More windows than anyone keeps open: what bounds the row if frame ids it cannot tell are closed pile up. */
        const val MAX_WINDOW_ORDER: Int = 200

        private val JSON = Json { ignoreUnknownKeys = true; encodeDefaults = false }

        /** [encode]'s reverse; nothing stored, or nothing readable, is an app that was never closed. */
        fun decode(text: String?): AppViewState =
            text?.let { runCatching { JSON.decodeFromString(serializer(), it) }.getOrNull() } ?: AppViewState()
    }
}

/**
 * The History window's filter on its placement row's `config` (user rule 2026-10-31), so a window that comes back after
 * a restart lists what it listed. Strings, so a value this build does not know reads as "any" rather than failing the
 * whole filter. No History Unit: a window's row is recorded by its layout alone.
 */
object HistoryFilterStore {
    @Serializable
    private data class Stored(
        val filterBySource: Boolean = false,
        val window: String? = null,
        val chords: List<String>? = null,
        val source: String? = null,
        val query: String = "",
    )

    private val JSON = Json { ignoreUnknownKeys = true; encodeDefaults = false }

    /** Null for the filter a window opens with: nothing to keep. */
    fun encode(filter: HistoryFilterConfig): String? =
        if (filter == HistoryFilterConfig()) {
            null
        } else {
            JSON.encodeToString(
                Stored.serializer(),
                Stored(filter.filterBySource, filter.window?.name, filter.chords?.map { it.name }, filter.source?.name, filter.query),
            )
        }

    fun decode(text: String?): HistoryFilterConfig {
        val stored = text?.let { runCatching { JSON.decodeFromString(Stored.serializer(), it) }.getOrNull() } ?: return HistoryFilterConfig()
        return HistoryFilterConfig(
            filterBySource = stored.filterBySource,
            window = HistoryWindow.entries.firstOrNull { it.name == stored.window },
            chords = stored.chords?.mapNotNull { name -> HistoryChord.entries.firstOrNull { it.name == name } }?.toSet(),
            source = HistorySource.entries.firstOrNull { it.name == stored.source },
            query = stored.query,
        )
    }
}
