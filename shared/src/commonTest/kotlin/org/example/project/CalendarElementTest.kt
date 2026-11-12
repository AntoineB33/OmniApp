package org.example.project

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.example.project.scheduler.domain.CalendarNotifications
import org.example.project.scheduler.domain.SearchDomain
import org.example.project.scheduler.persistence.WindowPlacement
import org.example.project.ui.CalendarWindowMigration
import org.example.project.ui.CustomMenuButtons

/**
 * User rule 2026-11-12: **there is no calendar window** — the calendar is an element, and a Search window holding it
 * shows the calendar in its actions section; the "unfocused notif" window is one Search window.
 */
class CalendarElementTest {

    private fun open(config: SearchDomain.Config, id: String) = SearchDomain.isActionRowOpen(config, id, top = id.startsWith("element/"))

    @Test
    fun a_calendar_window_is_a_search_window_showing_the_calendar_in_its_actions() {
        val config = SearchDomain.calendarWindowConfig()
        assertTrue(SearchDomain.showsCalendar(config))
        assertEquals(listOf(SearchDomain.CALENDAR_KEY), config.added)
        val group = "element/" + SearchDomain.CALENDAR_KEY
        assertTrue(open(config, group), "the calendar element's group is open")
        assertTrue(open(config, "action/$group/" + SearchDomain.AddedAction.CalendarGrid.name), "and its calendar")
        assertFalse(open(config, "action/$group/" + SearchDomain.AddedAction.CalendarDay.name), "the rest at its default")
        // Asked for again, nothing doubles.
        assertEquals(config, SearchDomain.calendarWindowConfig(config))
    }

    @Test
    fun a_windows_view_of_the_calendar_is_its_configuration_and_an_older_one_has_none() {
        val config = SearchDomain.calendarWindowConfig().copy(calendarDay = "2026-11-20", calendarDayMode = true, calendarView = "actions")
        val back = assertNotNull(SearchDomain.Config.decode(config.encode()))
        assertEquals(config.calendarDay, back.calendarDay)
        assertEquals(config.calendarDayMode, back.calendarDayMode)
        assertEquals(config.calendarView, back.calendarView)
        // Written before the calendar element: the opening day, the account's display, nothing shown.
        val older = assertNotNull(SearchDomain.Config.decode("""{"query":"x"}"""))
        assertNull(older.calendarDay)
        assertNull(older.calendarDayMode)
        assertEquals("", older.calendarView)
        assertFalse(SearchDomain.showsCalendar(older))
    }

    @Test
    fun the_unfocused_notif_window_is_one_search_window() {
        val config = SearchDomain.notificationsWindowConfig(1_000L, listOf("5#0", "9#0"))
        assertTrue(SearchDomain.isNotificationsWindow(config), "its search section lists the notifications")
        assertTrue(SearchDomain.showsCalendar(config), "its calendar is in its actions section")
        assertEquals(listOf("5#0", "9#0"), CalendarNotifications.WindowConfig.decode(config.calendarView).notifications)
        assertTrue(SearchDomain.isUntouchedNotificationsWindow(config))
        // The day picked, the display and the chips are the calendar's view: it still gathers.
        assertTrue(SearchDomain.isUntouchedNotificationsWindow(config.copy(calendarDay = "2026-11-12", calendarView = "")))
        // An element added beside the calendar, or another search: the user's now.
        assertFalse(SearchDomain.isUntouchedNotificationsWindow(config.copy(added = config.added + "Task/t1")))
        assertFalse(SearchDomain.isUntouchedNotificationsWindow(config.copy(query = "x")))
    }

    @Test
    fun the_calendar_windows_an_older_build_left_open_come_back_as_search_windows() {
        val chips = CalendarNotifications.WindowConfig(listOf("5#0")).encode()
        val placements =
            mapOf(
                "Calendar" to WindowPlacement(x = 10f, y = 20f, width = 900f, height = 500f, visible = true),
                "Calendar#2" to WindowPlacement(x = 30f, y = 40f, visible = true, minimized = true, config = chips),
                "Calendar#3" to WindowPlacement(x = 0f, y = 0f, visible = false),
                "Search#2" to WindowPlacement(x = 0f, y = 0f, visible = true),
                CustomMenuButtons.GLOBAL_WINDOWS_PLACEMENT_ID to
                    WindowPlacement(x = 0f, y = 0f, visible = false, config = CustomMenuButtons.encodeGlobalWindows(listOf("Calendar#2", "Search#2"))),
            )
        val out = CalendarWindowMigration.migrate(placements)
        assertFalse(out.getValue("Calendar").visible)
        assertFalse(out.getValue("Calendar#2").visible)
        assertNull(out["Calendar#3"], "a closed one is left alone")
        // Search#2 is taken: the calendars are Search#3 and Search#4, where and as they stood.
        val first = out.getValue("Search#3")
        assertEquals(10f to 900f, first.x to first.width)
        assertTrue(SearchDomain.showsCalendar(assertNotNull(SearchDomain.Config.decode(first.config))))
        val second = out.getValue("Search#4")
        assertTrue(second.minimized)
        assertEquals(chips, assertNotNull(SearchDomain.Config.decode(second.config)).calendarView, "its chips kept")
        assertEquals(
            listOf("Search#4", "Search#2"),
            CustomMenuButtons.decodeGlobalWindows(out.getValue(CustomMenuButtons.GLOBAL_WINDOWS_PLACEMENT_ID).config),
        )
        // Run again over what it wrote: nothing more.
        assertTrue(CalendarWindowMigration.migrate(placements + out).isEmpty())
    }
}
