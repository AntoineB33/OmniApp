package org.example.project.ui

import org.example.project.scheduler.domain.SearchDomain
import org.example.project.scheduler.persistence.WindowPlacement

/**
 * User rule 2026-11-12: **there is no calendar window any more** — the calendar is an element, and a Search window holding
 * it shows the calendar in its actions section. The calendar windows a build before left open (the original `Calendar`
 * and its copies `Calendar#2`…) come back as Search windows showing the calendar: each a Search copy of its own, where
 * the calendar stood, at its size and its chrome, with the notifications it showed as chips ([WindowPlacement.config]
 * held them) — and among the windows set global where it was one. Run once, on the rows as loaded; a store with no open
 * calendar window is left as it is.
 */
object CalendarWindowMigration {
    private const val CALENDAR = "Calendar"
    private const val SEARCH = "Search"

    /** The rows to write over [placements]: each open calendar window's closed, and its Search window's. Empty: nothing. */
    fun migrate(placements: Map<String, WindowPlacement>): Map<String, WindowPlacement> {
        val calendars =
            placements.filter { (id, row) -> (id == CALENDAR || id.startsWith("$CALENDAR#")) && row.visible }
                .toList().sortedBy { (id, _) -> id.substringAfter('#', "1").toIntOrNull() ?: 0 }
        if (calendars.isEmpty()) return emptyMap()
        // A number no Search window of the device has had, closed ones included (user rule 2026-11-22, [WindowIds]).
        val floors = WindowIds.decode(placements[WindowIds.PLACEMENT_ID]?.config)
        var next = WindowIds.next(SEARCH, floors, placements.keys, first = 2)
        val used = mutableSetOf<Int>()
        val out = LinkedHashMap<String, WindowPlacement>()
        val renamed = HashMap<String, String>()
        for ((id, row) in calendars) {
            val number = next++
            used += number
            val searchId = "$SEARCH#$number"
            val config =
                SearchDomain.calendarWindowConfig(
                    SearchDomain.Config(kinds = setOf(SearchDomain.Kind.Calendar), calendarView = row.config.orEmpty()),
                )
            out[searchId] = row.copy(config = config.encode())
            out[id] = row.copy(visible = false, minimized = false)
            renamed[id] = searchId
        }
        out[WindowIds.PLACEMENT_ID] =
            (placements[WindowIds.PLACEMENT_ID] ?: WindowPlacement(x = 0f, y = 0f, visible = false))
                .copy(config = WindowIds.encode(floors + (SEARCH to used.max())))
        // A calendar set global stays global, as its Search window.
        placements[CustomMenuButtons.GLOBAL_WINDOWS_PLACEMENT_ID]?.let { row ->
            val global = CustomMenuButtons.decodeGlobalWindows(row.config)
            if (global.any { it in renamed }) {
                out[CustomMenuButtons.GLOBAL_WINDOWS_PLACEMENT_ID] =
                    row.copy(config = CustomMenuButtons.encodeGlobalWindows(global.map { renamed[it] ?: it }))
            }
        }
        return out
    }
}
