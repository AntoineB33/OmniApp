package org.example.project.ui

import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.example.project.scheduler.domain.SearchDomain

/** How large the Search window the calendar's "add…" / "edit…" shows on hover is; the app's window caps it. */
private val CALENDAR_MENU_SEARCH_WIDTH = 760.dp
private val CALENDAR_MENU_SEARCH_HEIGHT = 480.dp

/**
 * User rule 2026-11-21: **the Search window the calendar's right-click menu shows when "add…" or "edit…" is hovered**
 * ([HoverPopupRow]) — what can be added at [atMillis] ([SearchDomain.calendarAddConfig]: its actions section leading
 * with "Add to the calendar"), or what is on the calendar there ([SearchDomain.calendarAtConfig]). The same
 * configurations the two entries opened as a window of the app until that day, drawn by the app as every Search window
 * inside another is ([LocalEmbeddedSearch]): its search, actions and added elements, its head hidden. What it is told
 * holds while it is open, and starts afresh at the next right-click.
 */
@Composable
internal fun CalendarMenuSearch(add: Boolean, atMillis: Long) {
    val search = LocalEmbeddedSearch.current
    androidx.compose.runtime.LaunchedEffect(add, atMillis) {
        org.example.project.scheduler.platform.Diagnostics.log("[popup] calendar menu search: ${if (add) "add" else "edit"} at $atMillis drawn (search=${search != null})")
    }
    search ?: return
    val opened = remember(add, atMillis) {
        if (add) SearchDomain.calendarAddConfig(atMillis) else SearchDomain.calendarAtConfig(atMillis)
    }
    var config by remember(opened) { mutableStateOf(opened) }
    // It has an id, the window's the calendar is in and the entry's: its ▾ windows and its parts are kept under it.
    val id = embeddedSearchId(if (add) "calendar menu:add" else "calendar menu:edit")
    RegisterEmbeddedSearch(id, if (add) "add…" else "edit…", config, open = true, saved = false, set = { config = it })
    var parts by rememberKeptParts(id, SearchSplits(headerRemoved = true))
    search(
        EmbeddedSearchRequest(
            config = config,
            onConfigChange = { config = it },
            onDrop = null,
            splits = parts,
            onSplitsChange = { parts = it },
            openedConfig = opened,
        ),
        Modifier.width(CALENDAR_MENU_SEARCH_WIDTH).height(CALENDAR_MENU_SEARCH_HEIGHT),
    )
}
