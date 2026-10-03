package org.example.project

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.example.project.scheduler.engine.SchedulerEngine
import org.example.project.scheduler.platform.GlobalShortcut
import org.example.project.scheduler.platform.NotificationClicks
import org.example.project.scheduler.platform.NotificationTarget
import org.example.project.scheduler.ui.TaskSchedulerViewModel
import org.example.project.time.AppClock

/**
 * PRD §11 (user rule 2026-10-03): a click on a notification brings the app to the front, and one about the schedule —
 * the task to do now, a screen break — opens the calendar or brings it back. What a notification is FOR is decided
 * where it is posted ([NotificationTarget]); the platforms only carry it to [NotificationClicks].
 */
class NotificationClickTargetTest {

    private fun engineWith(vm: TaskSchedulerViewModel, posted: MutableList<Pair<String, NotificationTarget>>) =
        SchedulerEngine(
            vm = vm,
            clock = object : AppClock { override fun nowMillis(): Long = 4_000L },
            scope = CoroutineScope(Dispatchers.Unconfined),
            screenActive = { true },
            speak = {},
            postNotification = { title, _, target -> posted += title to target },
            clearNotifications = {},
        )

    @Test
    fun the_screen_break_notifications_open_the_calendar_and_the_others_the_app() {
        val vm = TaskSchedulerViewModel(store = null, saveDispatcher = Dispatchers.Default)
        val posted = mutableListOf<Pair<String, NotificationTarget>>()
        val engine = engineWith(vm, posted)

        engine.restartLookAway()
        engine.announceResumeWork()
        engine.announceShortcutReceived(GlobalShortcut.ToggleAway)

        assertEquals(
            listOf(
                "Screen break" to NotificationTarget.Calendar,
                "Screen break over" to NotificationTarget.Calendar,
                "Shortcut received" to NotificationTarget.App,
            ),
            posted,
        )
    }

    @Test
    fun a_click_waits_until_it_is_answered_and_is_answered_once() {
        NotificationClicks.clicked(NotificationTarget.Calendar)
        val first = NotificationClicks.pending.value!!
        assertEquals(NotificationTarget.Calendar, first.target)
        // A newer click is not lost to the older one being answered.
        NotificationClicks.clicked(NotificationTarget.App)
        NotificationClicks.consume(first)
        val second = NotificationClicks.pending.value!!
        assertEquals(NotificationTarget.App, second.target)
        NotificationClicks.consume(second)
        assertNull(NotificationClicks.pending.value)
    }
}
