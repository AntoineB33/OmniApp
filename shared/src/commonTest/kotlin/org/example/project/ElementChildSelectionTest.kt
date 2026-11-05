package org.example.project

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.example.project.claudelogs.ClaudeLogCatalog
import org.example.project.claudelogs.ClaudeLogEntry
import org.example.project.claudelogs.ClaudeLogKind
import org.example.project.claudelogs.ClaudeLogSession
import org.example.project.scheduler.domain.SearchDomain
import org.example.project.scheduler.domain.SearchDomain.Kind
import org.example.project.scheduler.state.SchedulerState
import org.example.project.ui.ElementChildSelection
import org.example.project.ui.ShownRow
import org.example.project.ui.shownRowOrder

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

    /** Anomaly 2026-11-05: *"the up and down arrow don't work"* — they walk the rows as drawn, the open children included. */
    @Test
    fun theArrowsWalkTheOpenElementsChildrenInDrawingOrder() {
        val session = ClaudeLogSession(
            "C:/logs/p/s1.jsonl", "s1", "p", null, null,
            listOf(
                ClaudeLogEntry(ClaudeLogKind.Prompt, "first", 1_000L, null, 0),
                ClaudeLogEntry(ClaudeLogKind.Reasoning, "thinking", 2_000L, null, 1),
                ClaudeLogEntry(ClaudeLogKind.Answer, "the answer", 3_000L, null, 2),
                ClaudeLogEntry(ClaudeLogKind.Prompt, "second", 4_000L, null, 3),
                ClaudeLogEntry(ClaudeLogKind.Answer, "reply", 5_000L, null, 4),
            ),
        )
        val logs = ClaudeLogCatalog("dir", listOf(session))
        val state = SchedulerState.empty()
        val results = SearchDomain.claudeResults(Kind.ClaudePrompt, "", logs).sortedBy { it.id }
        val (first, second) = results.map(SearchDomain::keyOf)
        fun name(row: ShownRow) = row.child?.row?.name ?: results[row.index].name

        assertEquals(listOf("first", "second"), shownRowOrder(state, results, emptySet(), claudeLogs = logs).map(::name))
        val open = setOf("result/" + first)
        assertEquals(listOf("first", "thinking", "the answer", "second"), shownRowOrder(state, results, open, claudeLogs = logs).map(::name))
        // A child opened in turn: its own child follows it, then its siblings.
        val chainRow = shownRowOrder(state, results, open, claudeLogs = logs)[1].child!!
        val deeper = open + chainRow.rowKey
        assertEquals(
            listOf("first", "thinking", "the answer", "the answer", "second"),
            shownRowOrder(state, results, deeper, claudeLogs = logs).map(::name),
        )
        // Every child keeps the index of the result it stands under.
        assertEquals(listOf(0, 0, 0, 0, 1), shownRowOrder(state, results, deeper, claudeLogs = logs).map { it.index })
        assertEquals(listOf("reply"), shownRowOrder(state, results, setOf("result/" + second), claudeLogs = logs).mapNotNull { it.child?.row?.name })
    }
}
