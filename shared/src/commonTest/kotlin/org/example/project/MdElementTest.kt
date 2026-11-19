package org.example.project

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.example.project.scheduler.domain.ElementTree
import org.example.project.scheduler.domain.MdDomain
import org.example.project.scheduler.domain.SearchDomain
import org.example.project.scheduler.model.TaskId
import org.example.project.scheduler.persistence.SchedulerStateCodec
import org.example.project.scheduler.state.SchedulerIntent
import org.example.project.scheduler.state.SchedulerReducer
import org.example.project.scheduler.state.SchedulerState

/**
 * User rule 2026-11-19: *"Add the md element type. In the action section for an element (like a task element), the user can
 * add md elements. It replaces the current text of a task element. Association between elements doesn't mean one is the
 * child of the other."*
 */
class MdElementTest {
    private fun r(s: SchedulerState, i: SchedulerIntent) = SchedulerReducer.reduce(s, i)

    private fun run(s: SchedulerState, intents: List<SchedulerIntent>) = intents.fold(s, ::r)

    private fun stateWithOneTask(): Pair<SchedulerState, TaskId> {
        var s = SchedulerState.empty()
        val c0 = s.lists[s.rootListId]!!.cellIds[0]
        s = r(s, SchedulerIntent.SetCellTitle(c0, "Solo"))
        return s to s.tasks.keys.first { s.tasks[it]!!.title == "Solo" }
    }

    @Test
    fun an_element_adds_an_md_element_associated_with_it_and_no_parent_or_child_comes_of_it() {
        val (s0, solo) = stateWithOneTask()
        val added = SearchDomain.resolve(s0, listOf(SearchDomain.taskKey(solo)), { emptyMap() })
        val s = run(s0, SearchDomain.addedIntents(s0, added, SearchDomain.AddedCommand.MdAdd("# Groceries\n- milk"), 0L))
        val md = s.mdElements.single()
        assertEquals(listOf(SearchDomain.taskKey(solo)), md.of)
        assertEquals("Groceries", MdDomain.title(md))
        val mdKey = SearchDomain.Kind.Md.name + "/" + md.id
        assertTrue(mdKey !in ElementTree.childrenOf(s, SearchDomain.taskKey(solo)), "an association is no child")
        assertTrue(ElementTree.parentsOf(s, mdKey).isEmpty(), "nor a parent")
        // Edited as it is typed: one History Unit for the field.
        val typed = listOf("# Groceries\n- milk\n- eggs", "# Groceries\n- milk\n- eggs!").fold(s) { acc, text ->
            run(acc, SearchDomain.addedIntents(acc, added, SearchDomain.AddedCommand.MdSetText(md.id, text, "md/" + md.id), 0L))
        }
        assertEquals("# Groceries\n- milk\n- eggs!", typed.mdElements.single().text)
        assertEquals(s.mdElements, r(typed, SchedulerIntent.Undo).mdElements)
        // Dissociated, it stays an md element of its own.
        val alone = run(s, SearchDomain.addedIntents(s, added, SearchDomain.AddedCommand.MdDissociate(md.id), 0L))
        assertEquals(emptyList(), alone.mdElements.single().of)
    }

    @Test
    fun an_md_element_is_found_by_its_whole_text() {
        val (s0, solo) = stateWithOneTask()
        val s = withMd(s0, solo, "Title line\nthe word zebra is deep inside")
        val found = SearchDomain.itemResults(s, SearchDomain.Kind.Md, "zebra")
        assertEquals(listOf("Title line"), found.map { it.name })
        assertTrue(SearchDomain.itemResults(s, SearchDomain.Kind.Md, "giraffe").isEmpty())
    }

    @Test
    fun the_md_elements_round_trip_the_store() {
        val (s0, solo) = stateWithOneTask()
        val s = withMd(s0, solo, "remember this")
        val decoded = assertNotNull(SchedulerStateCodec.decode(SchedulerStateCodec.encode(s)))
        assertEquals(s.mdElements, decoded.mdElements)
    }

    /** CLAUDE.md § *Persisted-DB compatibility*: the text an older build stored on a task is lifted into an md element. */
    @Test
    fun a_task_text_an_older_build_wrote_is_lifted_into_an_md_element_associated_with_it() {
        val json =
            """
            {"rootListId":"L","lists":[{"id":"L","parentCellId":null,"cellIds":["c0"]}],
             "cells":[{"id":"c0","parentListId":"L","taskId":"t0"}],
             "tasks":[{"id":"t0","title":"X","text":"buy milk\ncall Bob"},{"id":"t1","title":"Y"}]}
            """.trimIndent()
        val decoded = assertNotNull(SchedulerStateCodec.decode(json))
        assertTrue(decoded.tasks.values.all { it.text.isEmpty() }, "no task keeps a text")
        val md = decoded.mdElements.single()
        assertEquals(MdDomain.liftedId(TaskId("t0")), md.id, "a function of the task: two devices lift it into one row")
        assertEquals("buy milk\ncall Bob", md.text)
        assertEquals(listOf(SearchDomain.taskKey(TaskId("t0"))), md.of)
        // Decoded again (as another device would, once synced), nothing is lifted twice.
        val again = assertNotNull(SchedulerStateCodec.decode(SchedulerStateCodec.encode(decoded)))
        assertEquals(decoded.mdElements, again.mdElements)
    }

    /** A paste bringing a task's text makes its md element in the SAME History Unit, which goes through the store. */
    @Test
    fun a_pasted_text_is_an_md_element_and_one_undo_takes_both_back() {
        var s = SchedulerState.empty()
        val cell = s.lists[s.rootListId]!!.cellIds[0]
        s = r(s, SchedulerIntent.ClickCell(cellId = cell, ctrl = false, shift = false, visibleOrder = org.example.project.scheduler.domain.SchedulerDomain.selectableVisibleOrder(s)))
        val pasted = r(s, SchedulerIntent.PasteTree("P\n\t- text:\n\thello md"))
        val task = pasted.cells[cell]!!.taskId!!
        assertEquals("", pasted.tasks[task]!!.text)
        assertEquals(listOf("hello md"), MdDomain.associatedWith(pasted, SearchDomain.taskKey(task)).map { it.text })
        val undone = r(pasted, SchedulerIntent.Undo)
        assertTrue(undone.mdElements.isEmpty() && undone.cells[cell]!!.taskId == null, "one Ctrl+Z")
        val stored = assertNotNull(SchedulerStateCodec.decode(SchedulerStateCodec.encode(pasted)))
        assertTrue(r(stored, SchedulerIntent.Undo).mdElements.isEmpty(), "the unit is read back from the store whole")
    }

    @Test
    fun a_payload_from_before_md_elements_has_none() {
        val json =
            """
            {"rootListId":"L","lists":[{"id":"L","parentCellId":null,"cellIds":["c0"]}],
             "cells":[{"id":"c0","parentListId":"L","taskId":null}],
             "tasks":[{"id":"t0","title":"X"}]}
            """.trimIndent()
        assertTrue(assertNotNull(SchedulerStateCodec.decode(json)).mdElements.isEmpty())
    }
}
