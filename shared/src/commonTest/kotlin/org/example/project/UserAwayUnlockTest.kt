package org.example.project

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.example.project.scheduler.engine.SchedulerEngine
import org.example.project.scheduler.ui.TaskSchedulerViewModel
import org.example.project.time.AppClock

/**
 * PRD §15, the "I'm away" button vs. the device's own lock: **an unlock turns the button off**.
 *
 * Unlocking this device is the user visibly coming back to it, and the flag left standing would go on holding
 * the active session finalized and the presence heartbeat closed — the app would be telling the server nobody
 * is at a machine somebody is demonstrably sitting at. The clearing is read off the RAW platform lock signal's
 * lock→unlock EDGE, delivered by the platform's own notification
 * ([SchedulerEngine.onPlatformActivityChanged]); nothing here polls.
 */
class UserAwayUnlockTest {

    private class Harness(private var unlocked: Boolean = true) {
        val vm = TaskSchedulerViewModel(store = null, saveDispatcher = Dispatchers.Default)
        val engine =
            SchedulerEngine(
                vm = vm,
                clock = object : AppClock { override fun nowMillis(): Long = 9_000L },
                scope = CoroutineScope(Dispatchers.Unconfined),
                screenActive = { unlocked },
                playCue = {},
            )

        /** Flip the OS session signal and deliver the notification the platform listener would. */
        fun setUnlocked(next: Boolean) {
            unlocked = next
            engine.onPlatformActivityChanged()
        }

        val away: Boolean get() = engine.userAway.value
    }

    @Test
    fun a_lock_then_unlock_turns_the_away_button_off() {
        val h = Harness()
        h.engine.setUserAway(true)
        assertTrue(h.away)

        h.setUnlocked(false)
        // Still away: locking the machine is not coming back to it.
        assertTrue(h.away, "the lock itself cleared the away flag")

        h.setUnlocked(true)
        assertFalse(h.away, "the unlock did not clear the away flag")
    }

    /**
     * The edge is what clears, not the level: repeated "the signal says unlocked" pokes — which every
     * app-foreground/activity poke is — must not undo an away the user declared at an unlocked screen.
     */
    @Test
    fun an_unlock_with_no_lock_before_it_leaves_the_flag_alone() {
        val h = Harness()
        h.engine.setUserAway(true)

        h.setUnlocked(true) // same value: no edge
        h.engine.onPlatformActivityChanged()
        assertTrue(h.away, "a poke at an already-unlocked screen cleared the away flag")
    }

    /** A device that starts locked has no edge behind it, so its first sample must clear nothing. */
    @Test
    fun the_first_sample_is_not_an_edge() {
        val h = Harness(unlocked = false)
        h.engine.setUserAway(true)

        h.engine.onPlatformActivityChanged()
        assertTrue(h.away, "the first sample was read as an unlock")

        h.setUnlocked(true)
        assertFalse(h.away, "the unlock after it did not clear the away flag")
    }

    /** Nothing clears a flag that was never set: an ordinary lock/unlock cycle leaves "I'm back" as it is. */
    @Test
    fun an_unlock_without_an_away_is_a_no_op() {
        val h = Harness()
        h.setUnlocked(false)
        h.setUnlocked(true)
        assertFalse(h.away)
    }
}
