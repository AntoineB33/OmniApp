package org.example.project

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.example.project.scheduler.domain.SchedulerDomain
import org.example.project.scheduler.domain.SearchDomain
import org.example.project.scheduler.engine.RingKind
import org.example.project.scheduler.engine.SpokenMessages
import org.example.project.scheduler.platform.GlobalShortcut

/**
 * PRD §11/§15, user spec 2026-09-26: a notification's voice is its own short sentence — what just happened or
 * what to do — never the written text read out; and the written "task to do now" carries the task's path, its
 * start dropped when it runs long.
 */
class SpokenMessagesTest {

    @Test
    fun a_chord_says_what_the_press_does_now_never_the_chord() {
        assertEquals("I'm away", SpokenMessages.shortcutReceipt(GlobalShortcut.ToggleAway, awayBefore = false, notificationsOnBefore = true))
        assertEquals("I'm back", SpokenMessages.shortcutReceipt(GlobalShortcut.ToggleAway, awayBefore = true, notificationsOnBefore = true))
        assertEquals("Notifications off", SpokenMessages.shortcutReceipt(GlobalShortcut.ToggleNotifications, false, true))
        assertEquals(SpokenMessages.SILENT, SpokenMessages.shortcutReceipt(GlobalShortcut.LookAwayNow, false, true))
        GlobalShortcut.entries.forEach { shortcut ->
            val spoken = SpokenMessages.shortcutReceipt(shortcut, awayBefore = false, notificationsOnBefore = true)
            assertTrue("Ctrl" !in spoken && "Shift" !in spoken && "Alt" !in spoken, "$shortcut says its chord: $spoken")
        }
    }

    @Test
    fun the_away_chords_written_receipt_says_which_way_it_went() {
        // User spec 2026-09-26: the Ctrl+Shift+Alt+A notification writes "I'm away" or "I'm back".
        assertEquals("I'm away", org.example.project.scheduler.engine.shortcutReceiptAction(GlobalShortcut.ToggleAway, awayBefore = false))
        assertEquals("I'm back", org.example.project.scheduler.engine.shortcutReceiptAction(GlobalShortcut.ToggleAway, awayBefore = true))
        assertEquals(
            GlobalShortcut.SwitchTask.action,
            org.example.project.scheduler.engine.shortcutReceiptAction(GlobalShortcut.SwitchTask, awayBefore = false),
        )
    }

    @Test
    fun the_other_phrases_name_what_is_happening() {
        assertEquals("Current task: Write the report", SpokenMessages.currentTask("Write the report"))
        assertEquals("Break: 5-min pose", SpokenMessages.screenBreak("5-min pose", null))
        assertEquals(
            "Break: 5-min pose, then the hour before bed",
            SpokenMessages.screenBreak("5-min pose", SchedulerDomain.ScreenBreakFollowOn.BeforeBed),
        )
        assertEquals("Alarm", SpokenMessages.ring(RingKind.Alarm, "  "))
        assertEquals("Alarm: Wake up", SpokenMessages.ring(RingKind.Alarm, "Wake up"))
        assertEquals("Time's up: Tea", SpokenMessages.ring(RingKind.Timer, "Tea"))
        assertEquals("Reminder: Water the plants", SpokenMessages.ring(RingKind.Reminder, "Water the plants"))
    }

    @Test
    fun a_long_path_loses_its_start_first() {
        val path = listOf("Life", "Work", "Clients", "Acme corporation", "Quarterly report")
        assertEquals("Life / Work", SearchDomain.shortenedPathLabel(listOf("Life", "Work"), 48))
        val short = SearchDomain.shortenedPathLabel(path, 40)
        assertEquals("… / Clients / Acme corporation / Quarterly report", SearchDomain.shortenedPathLabel(path, 50))
        assertTrue(short.length <= 40, short)
        assertTrue(short.startsWith("… / ") && short.endsWith("Quarterly report"), short)
        // A last segment too long on its own keeps its end.
        assertEquals("…report", SearchDomain.shortenedPathLabel(listOf("A", "Quarterly report"), 7))
    }
}
