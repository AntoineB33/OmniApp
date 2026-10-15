package org.example.project

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.example.project.scheduler.domain.CalendarNotifications
import org.example.project.ui.SecondsByDay

/**
 * User rule 2026-10-15: *"Add a configuration in the calendar that shows every recorded action on Windows with horizontal
 * lines"* — the seconds with an action, as they are kept ([SecondsByDay]), and the calendar window's switch.
 */
class InputActivityTest {
    private val day = 20_376L // 2025-10-15, a UTC day number
    private val d0 = day * SecondsByDay.SECONDS_PER_DAY

    private fun SecondsByDay.read(from: Long, until: Long): List<Long> = buildList { forEachSecond(from, until) { add(it) } }

    @Test
    fun a_second_is_kept_once_and_read_back_in_order_within_and_across_days() {
        val s = SecondsByDay()
        val marked = listOf(d0 + 5, d0 + 63, d0 + 64, d0 + 86_399, d0 + 86_400, d0 + 86_400 + 3_600)
        for (second in marked.shuffled()) assertTrue(s.add(second))
        assertFalse(s.add(d0 + 63), "already marked")
        assertEquals(marked, s.read(d0, d0 + 2 * 86_400))
        // A window cuts at both ends, in the middle of a word too.
        assertEquals(listOf(d0 + 63, d0 + 64), s.read(d0 + 6, d0 + 65))
        assertEquals(listOf(d0 + 64), s.read(d0 + 64, d0 + 65))
        assertEquals(listOf(d0 + 86_399, d0 + 86_400), s.read(d0 + 86_000, d0 + 86_401), "across midnight")
        assertEquals(emptyList(), s.read(d0 + 65, d0 + 65), "an empty window")
        assertEquals(emptyList(), s.read(d0 - 86_400, d0), "a day with nothing")
    }

    @Test
    fun a_day_survives_its_file_and_a_damaged_file_is_ignored() {
        val s = SecondsByDay()
        listOf(d0, d0 + 1_000, d0 + 86_399).forEach { s.add(it) }
        val bytes = s.encodeDay(day)!!
        assertEquals(SecondsByDay.BYTES_PER_DAY, bytes.size)
        val back = SecondsByDay()
        back.decodeDay(day, bytes)
        assertEquals(listOf(d0, d0 + 1_000, d0 + 86_399), back.read(d0, d0 + 86_400))
        back.decodeDay(day + 1, ByteArray(10))
        assertEquals(setOf(day), back.dayNumbers, "a file of another length is not a day")
        back.dropBefore(day + 1)
        assertEquals(emptySet(), back.dayNumbers)
        assertEquals(null, SecondsByDay().encodeDay(day))
    }

    @Test
    fun the_calendar_windows_switch_is_kept_beside_its_notifications() {
        val both = CalendarNotifications.WindowConfig(listOf("a", "b"), showsActions = true)
        assertEquals(both, CalendarNotifications.WindowConfig.decode(both.encode()))
        val actionsOnly = CalendarNotifications.WindowConfig(showsActions = true)
        assertEquals(actionsOnly, CalendarNotifications.WindowConfig.decode(actionsOnly.encode()))
        // Written before the switch existed: the notifications, the switch off. Nothing chosen writes nothing.
        assertEquals(CalendarNotifications.WindowConfig(listOf("x")), CalendarNotifications.WindowConfig.decode("notifications=x"))
        assertEquals("", CalendarNotifications.WindowConfig().encode())
        assertEquals(CalendarNotifications.WindowConfig(), CalendarNotifications.WindowConfig.decode("something else"))
    }
}
