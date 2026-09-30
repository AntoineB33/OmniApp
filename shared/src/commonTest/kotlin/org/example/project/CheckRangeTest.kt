package org.example.project

import kotlin.test.Test
import kotlin.test.assertEquals
import org.example.project.ui.CheckRange
import org.example.project.ui.CheckRangeState

/**
 * Every vertical list of check boxes: check one, shift+click another, and everything between the two is checked
 * (2026-09-30). One rule for every list — [CheckRange].
 */
class CheckRangeTest {
    private val order = listOf("a", "b", "c", "d", "e")

    private fun CheckRangeState<String>.click(checked: Set<String>, key: String, shift: Boolean = false): Set<String> {
        shiftAtPress = shift
        return toggle(order, checked, key)
    }

    @Test
    fun `checking one then shift-clicking another checks everything between, both included`() {
        val range = CheckRangeState<String>()
        var checked = range.click(emptySet(), "b")
        checked = range.click(checked, "d", shift = true)
        assertEquals(setOf("b", "c", "d"), checked)
    }

    @Test
    fun `the range runs upward as well`() {
        val range = CheckRangeState<String>()
        var checked = range.click(emptySet(), "d")
        checked = range.click(checked, "a", shift = true)
        assertEquals(setOf("a", "b", "c", "d"), checked)
    }

    @Test
    fun `boxes outside the range keep their state`() {
        val range = CheckRangeState<String>()
        var checked = range.click(setOf("e"), "a")
        checked = range.click(checked, "b", shift = true)
        assertEquals(setOf("a", "b", "e"), checked)
    }

    @Test
    fun `a range takes the state the clicked box is given, so it unchecks too`() {
        val range = CheckRangeState<String>()
        var checked = range.click(order.toSet(), "b")
        assertEquals(setOf("a", "c", "d", "e"), checked)
        checked = range.click(checked, "d", shift = true)
        assertEquals(setOf("a", "e"), checked)
    }

    @Test
    fun `a plain click toggles one box and moves the anchor`() {
        val range = CheckRangeState<String>()
        var checked = range.click(emptySet(), "a")
        checked = range.click(checked, "c")
        assertEquals(setOf("a", "c"), checked)
        checked = range.click(checked, "e", shift = true)
        assertEquals(setOf("a", "c", "d", "e"), checked, "the range starts at the LAST box clicked")
    }

    @Test
    fun `shift with no anchor, or an anchor the list no longer shows, sets the clicked box alone`() {
        assertEquals(listOf("c"), CheckRange.keysToSet(order, null, "c", shift = true))
        assertEquals(listOf("c"), CheckRange.keysToSet(order, "gone", "c", shift = true))
    }

    @Test
    fun `the shift of one press is not carried to the next click`() {
        // A click with no press of its own (the keyboard's) must not inherit the last press's Shift.
        val range = CheckRangeState<String>()
        var checked = range.click(emptySet(), "a")
        checked = range.click(checked, "c", shift = true)
        checked = range.toggle(order, checked, "e")
        assertEquals(setOf("a", "b", "c", "e"), checked)
    }
}
