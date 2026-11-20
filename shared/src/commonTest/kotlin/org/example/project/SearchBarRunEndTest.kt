package org.example.project

import kotlin.test.Test
import kotlin.test.assertEquals
import org.example.project.ui.TransientMenuHost

/**
 * User rule 2026-11-20: a Search bar's run of keystrokes ends when the user does something else — a press ANYWHERE in the
 * app, not only in the bar's window (*"A click elsewhere that leaves the keyboard in the bar doesn't end the run … That
 * seems to be a bug. Fix it."*). Every press reaches [TransientMenuHost] (its root observer, a pop-up's own), and so does
 * every wheel turn now: that is where `App` hears of them.
 */
class SearchBarRunEndTest {
    @Test
    fun every_press_and_wheel_turn_the_app_sees_is_told() {
        val host = TransientMenuHost()
        var told = 0
        host.onActivity = { told++ }
        host.onPress()
        host.onPressInPopup()
        host.onScroll()
        assertEquals(3, told, "a press in the app's window, one in a pop-up's, a wheel turn")
    }
}
