package org.example.project

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.example.project.scheduler.engine.SchedulerEngine
import org.example.project.scheduler.platform.GlobalShortcut
import org.example.project.scheduler.ui.TaskSchedulerViewModel
import org.example.project.time.AppClock

/**
 * PRD §7/§15, the system-wide chords' **receipt** ([SchedulerEngine.announceShortcutReceived]): every
 * `Ctrl+Shift+Alt+<letter>` press posts a notification saying the app got it.
 *
 * The chords are struck while OmniApp is not the focused window, and each of them can do nothing for a
 * perfectly good reason — "Look away now" with no look-away break configured, "I'm away" already away, a
 * "Switch task" the re-plan answers with the same task. Without a receipt, none of those is
 * distinguishable from a press the hook never saw, which is the failure the chords are actually prone to.
 * So the receipt is for the PRESS: it names the chord, and it does not depend on what the handler then does.
 */
class GlobalShortcutReceiptTest {

    private fun engine(vm: TaskSchedulerViewModel) =
        SchedulerEngine(
            vm = vm,
            clock = object : AppClock { override fun nowMillis(): Long = 9_000L },
            scope = CoroutineScope(Dispatchers.Unconfined),
            screenActive = { true },
            playCue = {},
        )

    @Test
    fun every_global_chord_posts_a_receipt_naming_it() {
        GlobalShortcut.entries.forEach { shortcut ->
            val vm = TaskSchedulerViewModel(store = null, saveDispatcher = Dispatchers.Default)
            engine(vm).announceShortcutReceived(shortcut)

            val entry = vm.state.value.notificationLog.single()
            assertEquals(9_000L, entry.timeMillis)
            assertEquals("Shortcut received", entry.title)
            // The chord itself is the message — two chords struck in quick succession must be tellable apart.
            assertTrue(
                entry.message.startsWith(shortcut.chord),
                "receipt for ${shortcut.name} does not name its chord: ${entry.message}",
            )
            assertTrue(
                entry.message.contains(shortcut.action),
                "receipt for ${shortcut.name} does not say what it does: ${entry.message}",
            )
        }
    }

    /**
     * The receipt belongs to the hot-key seam, not to the actions behind it: the lateral-menu buttons call
     * exactly these engine entry points, and a click in a window the user is looking at needs no confirming.
     */
    @Test
    fun driving_the_engine_seams_directly_posts_no_receipt() {
        val vm = TaskSchedulerViewModel(store = null, saveDispatcher = Dispatchers.Default)
        val engine = engine(vm)

        engine.setUserAway(true)
        engine.forceTaskSwitch()
        // No look-away break is configured, so this one returns without doing anything at all — precisely the
        // case the receipt exists for, and precisely the case that must stay silent when it is a button press.
        engine.restartLookAway()

        assertTrue(
            vm.state.value.notificationLog.none { it.title == "Shortcut received" },
            "a menu-button path posted a chord receipt: ${vm.state.value.notificationLog}",
        )
    }
}
