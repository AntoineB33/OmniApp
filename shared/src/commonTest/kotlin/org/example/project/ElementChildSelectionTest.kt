package org.example.project

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.example.project.ui.ElementChildSelection

/** Anomaly 2026-11-05: *"the children are not selectable"* — the children under an element's arrow are selected as rows are. */
class ElementChildSelectionTest {
    private val prompt = "result>ClaudePrompt/p"
    private val chain = "result>ClaudePrompt/p>ClaudeReasoning/r"
    private val answer = "result>ClaudePrompt/p>ClaudeAnswer/a"
    private val other = "added>Timer/t>Timer/u"

    private fun selection(): Pair<ElementChildSelection, MutableList<Pair<List<String>, Boolean>>> {
        val added = ArrayList<Pair<List<String>, Boolean>>()
        val s = ElementChildSelection()
        s.elements[chain] = "ClaudeReasoning/r"
        s.elements[answer] = "ClaudeAnswer/a"
        s.elements[other] = "Timer/u"
        s.onAdd = { keys, replacing -> added += keys to replacing }
        return s to added
    }

    @Test
    fun aClickSelectsAChildAndTakesTheListsSelection() {
        val (s, _) = selection()
        var taken = 0
        s.onTake = { taken++ }
        s.click(listOf(chain, answer), answer)
        assertEquals(setOf(answer), s.selected)
        assertEquals(answer, s.main)
        assertEquals(1, taken)
        s.click(listOf(chain, answer), chain)
        assertEquals(setOf(chain), s.selected, "a plain click selects the one row")
    }

    @Test
    fun ctrlAddsAcrossParentsShiftTakesTheSiblingsRangeAndAddAddsTheirElements() {
        val (s, added) = selection()
        s.click(listOf(chain, answer), chain)
        s.pressModifiers.ctrl = true
        s.click(listOf(other), other)
        assertEquals(setOf(chain, other), s.selected)
        s.pressModifiers.ctrl = false
        s.click(listOf(chain, answer), chain)
        s.pressModifiers.shift = true
        s.click(listOf(chain, answer), answer)
        assertEquals(setOf(chain, answer), s.selected)
        s.add(replacing = false)
        assertEquals(listOf(listOf("ClaudeReasoning/r", "ClaudeAnswer/a") to false), added)
    }

    @Test
    fun aRightClickKeepsTheSelectionItLandsInAndAPressElsewhereLeavesNone() {
        val (s, _) = selection()
        s.click(listOf(chain, answer), chain)
        s.pressModifiers.ctrl = true
        s.click(listOf(chain, answer), answer)
        s.pressModifiers.ctrl = false
        s.secondaryClick(chain)
        assertEquals(setOf(chain, answer), s.selected)
        s.secondaryClick(other)
        assertEquals(setOf(other), s.selected)
        s.clear()
        assertTrue(s.selected.isEmpty())
        assertEquals(null, s.main)
        assertTrue(prompt !in s.selected)
    }
}
