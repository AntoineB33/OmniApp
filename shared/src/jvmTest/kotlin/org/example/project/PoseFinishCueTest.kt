package org.example.project

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.example.project.scheduler.engine.SchedulerEngine

/**
 * PRD §15: the gate for the phone's "your pause is over" voice cue. It must fire only on the **phone**, only
 * when a session exists, only when this device's screen is off, and only when no peer reports an active
 * screen — flipping any one of those off must silence the cue (so a user actually at a screen is never told).
 */
class PoseFinishCueTest {
    private fun eligible(
        isPhone: Boolean = true,
        signedIn: Boolean = true,
        screenActive: Boolean = false,
        peersActive: Boolean = false,
    ) = SchedulerEngine.poseFinishEligible(isPhone, signedIn, screenActive, peersActive)

    @Test fun phone_with_no_active_screen_anywhere_speaks() = assertTrue(eligible())

    @Test fun desktop_never_speaks() = assertFalse(eligible(isPhone = false))

    @Test fun signed_out_does_not_speak() = assertFalse(eligible(signedIn = false))

    @Test fun own_active_screen_suppresses() = assertFalse(eligible(screenActive = true))

    @Test fun an_active_peer_suppresses() = assertFalse(eligible(peersActive = true))
}
