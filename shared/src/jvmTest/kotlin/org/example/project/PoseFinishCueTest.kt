package org.example.project

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.example.project.scheduler.engine.SchedulerEngine

/**
 * PRD §15: the gate for the phone's "your pause is over" voice cue at the OS alarm's fire time. It must fire
 * only on the **phone**, only when a session exists, and only when this device's screen is off — flipping any
 * one of those off must silence the cue. (Whether any OTHER device is active was already decided by the
 * external Realtime listener before it pushed this phone, so the fire-time gate no longer checks peers.)
 */
class PoseFinishCueTest {
    private fun eligible(
        isPhone: Boolean = true,
        signedIn: Boolean = true,
        screenActive: Boolean = false,
    ) = SchedulerEngine.poseFinishEligible(isPhone, signedIn, screenActive)

    @Test fun phone_with_screen_off_speaks() = assertTrue(eligible())

    @Test fun desktop_never_speaks() = assertFalse(eligible(isPhone = false))

    @Test fun signed_out_does_not_speak() = assertFalse(eligible(signedIn = false))

    @Test fun own_active_screen_suppresses() = assertFalse(eligible(screenActive = true))
}
