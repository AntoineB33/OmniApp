package org.example.project

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.example.project.scheduler.domain.ElementLink
import org.example.project.scheduler.domain.ElementTree
import org.example.project.scheduler.domain.SchedulerDomain
import org.example.project.scheduler.domain.SearchDomain
import org.example.project.scheduler.model.ChronoEntry
import org.example.project.scheduler.persistence.SchedulerStateCodec
import org.example.project.scheduler.state.SchedulerIntent
import org.example.project.scheduler.state.SchedulerReducer
import org.example.project.scheduler.state.SchedulerState

/**
 * User 2026-11-27 (*"Shouldn't the deep copy open a window to select what kind of deep copy I want? For example all the tree
 * with only the id for each node?"*): the "Copy the titles" switch of the "Deep copy" rows — off, a node with an id is its
 * id alone. An account setting like the other switches: persisted, healed to on for a payload written before it.
 */
class DeepCopyTitlesTest {
    private val tab = 9.toChar()
    private val nl = 10.toChar()

    @Test
    fun a_payload_written_before_the_switch_copies_titles() {
        val legacy = """
            {"rootListId":"L","lists":[{"id":"L","parentCellId":null,"cellIds":["c0"]}],
             "cells":[{"id":"c0","parentListId":"L","taskId":null}],
             "tasks":[{"id":"t0","title":"X"}],
             "copyIncludeIds":false,"copyIncludeMinimumTime":false}
        """.trimIndent()
        val decoded = assertNotNull(SchedulerStateCodec.decode(legacy))
        assertTrue(decoded.copyIncludeTitles)
        assertFalse(decoded.copyIncludeIds, "the switches it did carry are kept")
        // Turned off, it survives a save.
        val off = SchedulerReducer.reduce(decoded, SchedulerIntent.SetCopyOptions(SchedulerDomain.CopyOptions.from(decoded).copy(includeTitles = false)))
        assertFalse(off.copyIncludeTitles)
        assertFalse(assertNotNull(SchedulerStateCodec.decode(SchedulerStateCodec.encode(off))).copyIncludeTitles)
    }

    @Test
    fun titles_off_a_task_tree_is_its_ids_alone() {
        var s = SchedulerState.empty()
        val cP = s.lists[s.rootListId]!!.cellIds[0]
        s = SchedulerReducer.reduce(s, SchedulerIntent.SetCellTitle(cP, "P"))
        val p = s.cells[cP]!!.taskId!!
        val cC = s.lists[s.tasks[p]!!.childListId!!]!!.cellIds[0]
        s = SchedulerReducer.reduce(s, SchedulerIntent.SetCellTitle(cC, "C"))
        val c = s.cells[cC]!!.taskId!!
        val options = SchedulerDomain.CopyOptions(
            includeIds = true, priorityTables = false, includeText = false, includePriorityPercentages = false,
            includeMinimumTime = false, includeTitles = false,
        )
        val text = SchedulerDomain.copyCellsText(s, listOf(cP), maxDepth = 20, options = options)
        val ref = SchedulerDomain.TASK_ID_REFERENCE_PREFIX
        assertTrue(text.startsWith("$ref${p.value}$nl$tab$ref${c.value}$nl"), text)
        assertFalse(text.contains("- id:"), text)
        assertFalse(text.contains("P$nl"), text)
    }

    @Test
    fun titles_off_an_element_tree_is_its_ids_alone() {
        fun key(id: String) = "Chrono/$id"
        val s = SchedulerState.empty().copy(
            chronos = listOf("p", "a").map { ChronoEntry(id = it, label = it.uppercase()) },
            elementLinks = listOf(ElementLink(ElementTree.linkId(key("p"), key("a")), key("p"), key("a"))),
        )
        assertEquals(
            "Chrono/p$nl${tab}Chrono/a$nl",
            SearchDomain.elementsCopyText(s, listOf(key("p")), 9, includeIds = true, excludeTitle = "", includeTitles = false),
        )
        assertEquals(
            "P$nl$tab- Run: idle, 0s$nl${tab}A$nl$tab$tab- Run: idle, 0s$nl",
            SearchDomain.elementsCopyText(s, listOf(key("p")), 9, includeIds = false, excludeTitle = ""),
        )
    }
}
