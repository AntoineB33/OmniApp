package org.example.project

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.example.project.scheduler.model.AlarmEntry
import org.example.project.scheduler.model.TimerEntry
import org.example.project.scheduler.model.TaskId
import org.example.project.scheduler.state.SchedulerIntent
import org.example.project.scheduler.state.SchedulerReducer
import org.example.project.scheduler.state.SchedulerState
import org.example.project.ui.ObjectWindowKey
import org.example.project.ui.ObjectWindows
import org.example.project.ui.WindowFrameHost
import org.example.project.ui.WindowFrameState

/**
 * PRD §7 / `popups.md`: the per-object windows. Opening one on another object opens a SECOND window — the first
 * stays; asking again for an object already open brings its window back rather than opening another; and a window
 * about an object with a stable id names itself for the head's ☆ ([ObjectWindowKey]).
 */
class ObjectWindowsTest {

    @Test
    fun opening_another_object_keeps_the_first_window() {
        val windows = ObjectWindows<String>()
        windows.open("timer-1")
        windows.open("timer-2")
        assertEquals(listOf("timer-1", "timer-2"), windows.subjects)
    }

    @Test
    fun asking_again_for_an_open_object_brings_its_window_back_instead_of_opening_another() {
        val windows = ObjectWindows<String>()
        windows.open("timer-1")
        val first = windows.open.single()
        assertEquals(0, first.presentRequests)
        windows.open("timer-1")
        assertEquals(1, windows.open.size)
        assertEquals(1, first.presentRequests)
    }

    @Test
    fun each_window_has_a_number_of_its_own_and_closes_alone() {
        val windows = ObjectWindows<String>()
        windows.open("a")
        windows.open("b")
        val (a, b) = windows.open
        assertTrue(a.number != b.number)
        a.close()
        assertEquals(listOf("b"), windows.subjects)
    }

    @Test
    fun duplicate_is_the_one_way_to_have_two_windows_on_one_object() {
        val windows = ObjectWindows<String>()
        windows.open("a")
        windows.duplicate(windows.open.single())
        assertEquals(listOf("a", "a"), windows.subjects)
    }

    @Test
    fun close_all_closes_only_what_it_names() {
        val windows = ObjectWindows<String>()
        windows.open("live:1")
        windows.open("template:1")
        windows.closeAll { it.startsWith("template") }
        assertEquals(listOf("live:1"), windows.subjects)
        windows.closeAll()
        assertEquals(emptyList(), windows.subjects)
    }

    @Test
    fun a_window_that_moved_on_is_found_and_starred_by_what_it_shows_now() {
        val windows = ObjectWindows<String> { ObjectWindowKey(ObjectWindowKey.Kind.Timer, it).encode() }
        windows.open("timer-1")
        val window = windows.open.single()
        // Its "+ New timer" moved it on to the timer it made.
        window.retarget("timer-2")
        assertEquals(ObjectWindowKey(ObjectWindowKey.Kind.Timer, "timer-2").encode(), window.menuKey)
        windows.open("timer-2")
        assertEquals(1, windows.open.size)
        assertEquals(1, window.presentRequests)
    }

    @Test
    fun a_kind_about_something_transient_has_no_star() {
        val windows = ObjectWindows<String>()
        windows.open("draft")
        assertNull(windows.open.single().menuKey)
    }

    // ----- the ☆ key ---------------------------------------------------------------------------------------

    @Test
    fun the_key_round_trips_and_refuses_what_is_not_one() {
        for (key in listOf(
            ObjectWindowKey(ObjectWindowKey.Kind.TaskEdit, "task/1"),
            ObjectWindowKey(ObjectWindowKey.Kind.PriorityWeights, "list:with:colons", template = true),
            ObjectWindowKey(ObjectWindowKey.Kind.Reminder, "reminder-3"),
        )) {
            assertEquals(key, ObjectWindowKey.decode(key.encode()))
        }
        // A lateral-menu window's frame id, a kind a later build dropped, a mangled key.
        assertNull(ObjectWindowKey.decode("Search#2"))
        assertNull(ObjectWindowKey.decode("object:Wormhole:live:1"))
        assertNull(ObjectWindowKey.decode("object:TaskEdit:sideways:1"))
    }

    @Test
    fun the_key_says_whether_its_object_is_still_there() {
        var s = SchedulerState.empty()
        val cell = s.lists[s.rootListId]!!.cellIds.first()
        s = SchedulerReducer.reduce(s, SchedulerIntent.SetCellTitle(cell, "Write"))
        val task = s.cells[cell]!!.taskId!!
        assertTrue(ObjectWindowKey(ObjectWindowKey.Kind.TaskEdit, task.value).exists(s))
        assertFalse(ObjectWindowKey(ObjectWindowKey.Kind.TaskEdit, TaskId("gone").value).exists(s))

        val withAlarm = s.copy(alarms = listOf(AlarmEntry(id = "alarm-1")))
        assertTrue(ObjectWindowKey(ObjectWindowKey.Kind.Alarm, "alarm-1").exists(withAlarm))
        assertFalse(ObjectWindowKey(ObjectWindowKey.Kind.Timer, "alarm-1").exists(withAlarm))
    }

    @Test
    fun a_button_is_named_after_its_object_and_what_it_is() {
        var s = SchedulerState.empty()
        val cell = s.lists[s.rootListId]!!.cellIds.first()
        s = SchedulerReducer.reduce(s, SchedulerIntent.SetCellTitle(cell, "Writing"))
        val task = s.cells[cell]!!.taskId!!
        s = s.copy(
            alarms = listOf(AlarmEntry(id = "alarm-1", label = "Wake up")),
            timers = listOf(TimerEntry(id = "timer-1", label = "Tea"), TimerEntry(id = "timer-2")),
        )
        assertEquals("Writing task", ObjectWindowKey(ObjectWindowKey.Kind.TaskEdit, task.value).buttonTitle(s))
        assertEquals("Writing deep copy", ObjectWindowKey(ObjectWindowKey.Kind.DeepCopy, cell.value).buttonTitle(s))
        assertEquals(
            "Writing weights",
            ObjectWindowKey(ObjectWindowKey.Kind.PriorityWeights, s.tasks[task]!!.childListId!!.value).buttonTitle(s),
        )
        assertEquals("Wake up alarm", ObjectWindowKey(ObjectWindowKey.Kind.Alarm, "alarm-1").buttonTitle(s))
        assertEquals("Tea timer", ObjectWindowKey(ObjectWindowKey.Kind.Timer, "timer-1").buttonTitle(s))
        // Unlabelled: the noun alone.
        assertEquals("Timer", ObjectWindowKey(ObjectWindowKey.Kind.Timer, "timer-2").buttonTitle(s))
    }

    // ----- the frame host keeps each window's key current ----------------------------------------------------

    @Test
    fun the_host_knows_which_window_a_key_names_even_after_it_moved_on() {
        val host = WindowFrameHost()
        host.register(WindowFrameHost.Registration("AlarmOrTimerEdit#1", "Timer", WindowFrameState("AlarmOrTimerEdit#1"), false, "k1") {})
        host.rekey("AlarmOrTimerEdit#1", "k2")
        assertEquals("k2", host.registrations.single().menuKey)
    }
}
