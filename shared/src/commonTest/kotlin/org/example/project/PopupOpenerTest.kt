package org.example.project

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.example.project.ui.PopupOpener
import org.example.project.ui.PopupOpeners

/**
 * Anomaly 2026-11-20 (*"Often when I rapidly click on a down arrow, it appears and disappears in a blink"*): the one rule
 * for a press on what opens a pop-up ([PopupOpener]) — its click answers it, never the pop-up's loss of the keyboard,
 * and a quick run of clicks is one click.
 */
class PopupOpenerTest {

    @Test
    fun a_quick_run_of_clicks_is_one_click() {
        val opener = PopupOpener()
        assertTrue(opener.click(isOpen = false, closes = true), "a click opens it")
        assertTrue(opener.click(isOpen = true, closes = true), "a second click at once is the same gesture: it stays open")
        assertTrue(opener.click(isOpen = true, closes = true))
        val pinning = PopupOpener()
        assertTrue(pinning.click(isOpen = true, closes = false), "a hover pop-up: the click keeps it")
    }

    @Test
    fun a_loss_reported_on_the_opener_waits_for_its_click() {
        val opener = PopupOpener()
        var open = true
        var lost = 0
        var kept = 0
        withOpener(opener, { open }) {
            // Before the click: it waits, and the click keeping the pop-up gives the keyboard back.
            assertNotNull(PopupOpeners.keyboardLeft(opener, { lost++ }, { kept++ }))
            assertTrue(opener.click(isOpen = open, closes = false))
            assertEquals(0 to 1, lost to kept)
            // After it: answered as the click did, at once.
            assertNull(PopupOpeners.keyboardLeft(opener, { lost++ }, { kept++ }))
            assertEquals(0 to 2, lost to kept)
        }
        // Another opener, or none: the loss is what it always was.
        assertNull(PopupOpeners.keyboardLeft(opener, { lost++ }))
        assertNull(PopupOpeners.keyboardLeft(null, { lost++ }))
        assertEquals(2, lost)
    }

    @Test
    fun a_loss_with_no_click_to_answer_it_is_applied() {
        val opener = PopupOpener()
        var lost = 0
        withOpener(opener, { true }) {
            val handle = assertNotNull(PopupOpeners.keyboardLeft(opener, { lost++ }))
            PopupOpeners.expire(handle)
            assertEquals(1, lost, "no click in time")
            PopupOpeners.expire(handle)
            assertEquals(1, lost, "once")
            // The pointer leaving the opener before a click: the press was no click on it.
            assertNotNull(PopupOpeners.keyboardLeft(opener, { lost++ }))
        }
        assertEquals(2, lost)
        assertFalse(PopupOpeners.pressAnsweredBy(opener))
    }

    /** A pop-up closed: a press on its opener is no press on an open pop-up's control. */
    @Test
    fun only_an_open_pop_up_s_opener_answers_a_press() {
        val opener = PopupOpener()
        var open = false
        withOpener(opener, { open }) {
            assertFalse(PopupOpeners.pressAnsweredBy(opener))
            open = true
            assertTrue(PopupOpeners.pressAnsweredBy(opener))
            assertFalse(PopupOpeners.pressAnsweredBy(PopupOpener()), "another opener's menu")
        }
    }

    private fun withOpener(opener: PopupOpener, isOpen: () -> Boolean, block: () -> Unit) {
        opener.isOpen = isOpen
        PopupOpeners.entered(opener)
        try {
            block()
        } finally {
            PopupOpeners.exited(opener)
        }
    }
}
