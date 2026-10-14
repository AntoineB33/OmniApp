package org.example.project

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import org.example.project.scheduler.domain.CalendarNotifications
import org.example.project.scheduler.domain.SearchDomain
import org.example.project.scheduler.state.NotificationLogEntry
import org.example.project.scheduler.state.SchedulerState

/**
 * User rules 2026-10-13: "There can be several calendar windows. Add configurations in the calendar window to show a
 * chip for all the notifications that are selected in a drop-down menu. In the action section, add the button to open a
 * calendar window that shows all the notifications selected, which are all the notifications of the macro group in the
 * action section. When the unfocused notif window appears automatically … The unfocused notif window has all the
 * notification elements in the result list in the added elements list as well."
 */
class CalendarNotificationsTest {
    private val hour = 3_600_000L
    // 2026-10-13 00:00 UTC.
    private val day = 1_791_849_600_000L
    private fun entry(at: Long, title: String) = NotificationLogEntry(at, title, "")
    private val log = listOf(
        entry(day + 8 * hour, "Screen break"),
        entry(day + 9 * hour, "Task to do now"),
        entry(day + 9 * hour, "Alarm"),
        entry(day + 30 * hour, "Timer"),
    )

    @Test
    fun a_notification_keeps_its_id_while_the_log_rolls() {
        val ids = SearchDomain.notificationIds(log)
        assertEquals(listOf("${day + 8 * hour}#0", "${day + 9 * hour}#0", "${day + 9 * hour}#1", "${day + 30 * hour}#0"), ids)
        // The oldest is dropped (the log is a rolling tail) and one is posted: the others are named as they were —
        // an id that was a place in the log named another notification for each of them.
        val rolled = log.drop(1) + entry(day + 31 * hour, "Reminder")
        assertEquals(ids.drop(1), SearchDomain.notificationIds(rolled).dropLast(1))
        // …and the Search rows carry those ids.
        val rows = SearchDomain.resolve(SchedulerState.empty().copy(notificationLog = log), ids.map { "Notification/$it" })
        assertEquals(ids, rows.map { (it as SearchDomain.ItemResult).id })
    }

    @Test
    fun a_calendar_window_keeps_the_notifications_it_shows_and_draws_a_chip_for_each() {
        val ids = SearchDomain.notificationIds(log)
        val config = CalendarNotifications.WindowConfig(listOf(ids[3], ids[1], ids[2]))
        assertEquals(config, CalendarNotifications.WindowConfig.decode(config.encode()))
        // A calendar that shows none writes nothing, and a row with nothing — or with something else — shows none.
        assertEquals("", CalendarNotifications.WindowConfig().encode())
        assertEquals(CalendarNotifications.WindowConfig(), CalendarNotifications.WindowConfig.decode(null))
        assertEquals(CalendarNotifications.WindowConfig(), CalendarNotifications.WindowConfig.decode("Alarm/alarm-0"))
        // The chips: oldest first, each at its instant with its title; one gone from the log is not drawn.
        val chips = CalendarNotifications.chips(log, config.notifications + "123#0")
        assertEquals(listOf("Task to do now", "Alarm", "Timer"), chips.map { it.title })
        assertEquals(listOf(day + 9 * hour, day + 9 * hour, day + 30 * hour), chips.map { it.timeMillis })
        // By the day they fall on.
        val byDay = CalendarNotifications.byDay(chips, TimeZone.UTC)
        assertEquals(setOf(LocalDate(2026, 10, 13), LocalDate(2026, 10, 14)), byDay.keys)
        assertEquals(2, byDay.getValue(LocalDate(2026, 10, 13)).size)
        assertEquals("13/10 09:00 · Alarm", CalendarNotifications.label(chips[1], TimeZone.UTC))
    }

    @Test
    fun the_drop_down_lists_the_latest_notifications_and_every_selected_one() {
        val ids = SearchDomain.notificationIds(log)
        assertEquals(listOf("Timer", "Alarm", "Task to do now", "Screen break"), CalendarNotifications.options(log, emptyList()).map { it.title })
        // Only the last two — and the selected one, however old: it must be there to be unchecked.
        assertEquals(listOf("Timer", "Alarm", "Screen break"), CalendarNotifications.options(log, listOf(ids[0]), limit = 2).map { it.title })
        assertTrue(CalendarNotifications.options(emptyList(), listOf(ids[0])).isEmpty())
    }

    @Test
    fun the_notifications_window_holds_every_notification_it_lists_as_an_added_element_and_still_gathers() {
        val since = day + 9 * hour
        val keys = SearchDomain.notificationKeysSince(log, since)
        // Newest first, as the window lists them; what fired before the focus was lost is not among them.
        assertEquals(listOf("Notification/${day + 30 * hour}#0", "Notification/${day + 9 * hour}#0", "Notification/${day + 9 * hour}#1").toSet(), keys.toSet())
        assertEquals("Notification/${day + 30 * hour}#0", keys.first())
        // They are the rows the window shows, so they resolve as added elements.
        val state = SchedulerState.empty().copy(notificationLog = log)
        val added = SearchDomain.resolve(state, keys)
        assertEquals(3, added.size)
        assertEquals(keys.map { it.substringAfter('/') }, CalendarNotifications.idsOf(added))
        // The app adding them is not the user changing the window: it goes on gathering…
        val opened = SearchDomain.notificationsConfig(since)
        assertTrue(SearchDomain.isUntouchedNotificationsWindow(opened))
        assertTrue(SearchDomain.isUntouchedNotificationsWindow(opened.copy(added = keys)))
        // …until the user adds something else, or changes the search.
        assertFalse(SearchDomain.isUntouchedNotificationsWindow(opened.copy(added = keys + "Alarm/alarm-0")))
        assertFalse(SearchDomain.isUntouchedNotificationsWindow(opened.copy(added = keys, query = "break")))
        assertFalse(SearchDomain.isUntouchedNotificationsWindow(SearchDomain.Config(added = keys)))
    }

    @Test
    fun the_calendar_opened_under_the_notifications_window_is_that_windows_on_the_bar() {
        // User rule 2026-10-14: the calendar's tab is linked to the "unfocused notif" window's — read off what both
        // hold, so it survives a restart.
        val since = day + 9 * hour
        val keys = SearchDomain.notificationKeysSince(log, since)
        val calendar = CalendarNotifications.WindowConfig(keys.map { it.substringAfter('/') }.reversed())
        val notif = SearchDomain.notificationsConfig(since).copy(added = keys)
        val plain = SearchDomain.Config(added = keys)
        assertEquals("Search#3", CalendarNotifications.notificationsWindowOf(calendar, listOf("Search" to plain, "Search#3" to notif)))
        // Not a notifications window's chips, or none at all: under no window.
        assertEquals(null, CalendarNotifications.notificationsWindowOf(CalendarNotifications.WindowConfig(listOf(keys[0].substringAfter('/'))), listOf("Search#3" to notif)))
        assertEquals(null, CalendarNotifications.notificationsWindowOf(CalendarNotifications.WindowConfig(), listOf("Search#3" to SearchDomain.notificationsConfig(since))))
        // Something else added beside the notifications does not unlink it.
        assertEquals("Search#3", CalendarNotifications.notificationsWindowOf(calendar, listOf("Search#3" to notif.copy(added = keys + "Alarm/alarm-0"))))
    }

    @Test
    fun the_notifications_calendar_opens_from_the_first_to_the_last_with_the_now_line() {
        val chips = CalendarNotifications.chips(log, SearchDomain.notificationIds(log).drop(1))
        // The now-line after the last: the stretch runs on to it.
        assertEquals(day + 9 * hour to day + 40 * hour, CalendarNotifications.openingSpan(chips, day + 40 * hour))
        // Between them: first to last.
        assertEquals(day + 9 * hour to day + 30 * hour, CalendarNotifications.openingSpan(chips, day + 20 * hour))
        // Before the first (a clock set back): from the line.
        assertEquals(day to day + 30 * hour, CalendarNotifications.openingSpan(chips, day))
        // None shown: an ordinary opening.
        assertEquals(null, CalendarNotifications.openingSpan(emptyList(), day))
    }

    @Test
    fun a_group_of_notifications_has_the_button_that_opens_a_calendar_with_them() {
        val state = SchedulerState.empty().copy(notificationLog = log)
        val added = SearchDomain.resolve(state, SearchDomain.notificationKeysSince(log, day))
        val groups =
            SearchDomain.actionGroups(
                SearchDomain.actionsFor(SearchDomain.addedActions("", SearchDomain.Kind.entries.toSet(), SearchDomain.actionKindsOf(added)), added),
                added,
            )
        val calendar = SearchDomain.AddedAction.NotificationCalendar
        // Once, in the group of them all — the first macro group — which is the one the button reads its notifications off.
        assertEquals(listOf(added), groups.filter { calendar in it.actions }.map { it.members })
        assertEquals(groups.first(), groups.single { calendar in it.actions })
        // One notification alone has it in its own group.
        val lone = SearchDomain.actionGroups(
            SearchDomain.actionsFor(SearchDomain.addedActions("", SearchDomain.Kind.entries.toSet(), SearchDomain.actionKindsOf(added.take(1))), added.take(1)),
            added.take(1),
        )
        assertTrue(calendar in lone.single().actions)
    }
}
