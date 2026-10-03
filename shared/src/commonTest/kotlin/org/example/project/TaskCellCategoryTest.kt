package org.example.project

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.example.project.scheduler.domain.CategoryRules
import org.example.project.scheduler.domain.RelativePriorityDomain
import org.example.project.scheduler.model.CategoryId
import org.example.project.scheduler.model.CategoryKind
import org.example.project.scheduler.model.CellId
import org.example.project.scheduler.model.PriorityWeightPin
import org.example.project.scheduler.model.TaskId
import org.example.project.scheduler.state.SchedulerIntent
import org.example.project.scheduler.state.SchedulerReducer
import org.example.project.scheduler.state.SchedulerState

/**
 * User rule 2026-10-03: **a category is a task id category or a task cell category** (the switch), a task cell category
 * is given to ONE occurrence from the Search window, a rule is relative to the task cell at a DISTANCE above each
 * carrier (narrowed to one such cell when it names one), and a rule holding a pinned weight is warned about.
 *
 * ```
 * root ─ Book  ─ Chapter
 *      │       └ Other
 *      └ Notes ─ Read
 *              └ Skim
 *              └ Book (mirror)
 * ```
 */
class TaskCellCategoryTest {

    private fun r(s: SchedulerState, i: SchedulerIntent) = SchedulerReducer.reduce(s, i)

    private class Tree(val s: SchedulerState, val book: TaskId, val chapter: TaskId, val read: TaskId, val bookCell: CellId, val mirrorCell: CellId)

    private fun tree(): Tree {
        var s = SchedulerState.empty()
        val root = { s.lists[s.rootListId]!!.cellIds }
        val bookCell = root()[0]
        s = r(s, SchedulerIntent.SetCellTitle(bookCell, "Book"))
        val notesCell = root()[1]
        s = r(s, SchedulerIntent.SetCellTitle(notesCell, "Notes"))
        val book = s.cells[bookCell]!!.taskId!!
        val notes = s.cells[notesCell]!!.taskId!!
        val bookList = { s.lists[s.tasks[book]!!.childListId!!]!!.cellIds }
        val notesList = { s.lists[s.tasks[notes]!!.childListId!!]!!.cellIds }
        s = r(s, SchedulerIntent.SetCellTitle(bookList()[0], "Chapter"))
        s = r(s, SchedulerIntent.SetCellTitle(bookList()[1], "Other"))
        s = r(s, SchedulerIntent.SetCellTitle(notesList()[0], "Read"))
        s = r(s, SchedulerIntent.SetCellTitle(notesList()[1], "Skim"))
        val mirrorCell = notesList().last()
        s = r(s, SchedulerIntent.AssignTaskId(mirrorCell, book))
        return Tree(s, book, s.tasks.values.first { it.title == "Chapter" }.id, s.tasks.values.first { it.title == "Read" }.id, bookCell, mirrorCell)
    }

    private fun named(s: SchedulerState, title: String): CategoryId = s.categories.first { it.title == title }.id

    private fun cellOf(s: SchedulerState, taskId: TaskId): CellId = s.cells.values.first { it.taskId == taskId }.id

    private fun assertShare(expected: Double, actual: Double, what: String) =
        assertTrue(abs(expected - actual) < 1e-6, "$what: expected $expected but was $actual")

    @Test
    fun a_task_cell_category_is_given_to_one_occurrence_and_only_that_one_carries_it() {
        val t = tree()
        // Book has two occurrences: the root one and the mirror under Notes.
        var s = r(t.s, SchedulerIntent.AddCellCategory(t.mirrorCell, "spot"))
        val spot = named(s, "spot")
        assertEquals(CategoryKind.TaskCell, s.categoryById(spot)!!.kind, "a name nobody held mints a task cell category")
        assertEquals(listOf(t.mirrorCell), CategoryRules.carrierCells(s, s.categoryById(spot)!!))
        assertTrue(spot !in s.tasks[t.book]!!.categoryIds, "the task itself does not carry it")

        // The tree's own field (a task's) cannot attach it: a task cell category is never a task's.
        val refused = r(s, SchedulerIntent.AttachTaskCategory(t.book, spot))
        assertTrue(spot !in refused.tasks[t.book]!!.categoryIds)

        // Taking it off, and the second give being a no-op.
        assertTrue(r(s, SchedulerIntent.SetCellCategory(t.mirrorCell, spot, carried = true)) === s)
        s = r(s, SchedulerIntent.SetCellCategory(t.mirrorCell, spot, carried = false))
        assertTrue(s.cells[t.mirrorCell]!!.categoryIds.isEmpty())
    }

    @Test
    fun the_switch_keeps_what_carried_the_category() {
        val t = tree()
        var s = r(t.s, SchedulerIntent.AddTaskCategory(t.book, "deep"))
        val deep = named(s, "deep")
        // Task id → task cell: every populated occurrence of Book now carries it, the task no longer does.
        s = r(s, SchedulerIntent.SetCategoryKind(deep, CategoryKind.TaskCell))
        assertEquals(CategoryKind.TaskCell, s.categoryById(deep)!!.kind)
        assertEquals(setOf(t.bookCell, t.mirrorCell), CategoryRules.carrierCells(s, s.categoryById(deep)!!).toSet())
        assertTrue(deep !in s.tasks[t.book]!!.categoryIds)

        // One occurrence loses it; back to task id: the task that still has an occurrence carrying it carries it.
        s = r(s, SchedulerIntent.SetCellCategory(t.mirrorCell, deep, carried = false))
        s = r(s, SchedulerIntent.SetCategoryKind(deep, CategoryKind.TaskId))
        assertTrue(deep in s.tasks[t.book]!!.categoryIds)
        assertTrue(s.cells.values.none { deep in it.categoryIds })
        // One unit: undo takes the whole switch back.
        val undone = r(s, SchedulerIntent.Undo)
        assertEquals(CategoryKind.TaskCell, undone.categoryById(deep)!!.kind)
        assertTrue(deep in undone.cells[t.bookCell]!!.categoryIds)
    }

    @Test
    fun a_rule_on_a_task_cell_category_holds_only_the_carrying_occurrence() {
        val t = tree()
        var s = r(t.s, SchedulerIntent.AddCellCategory(t.mirrorCell, "spot"))
        val spot = named(s, "spot")
        // The mirror is 1 of 3 in Notes' list; hold it at 50 % of its parent.
        s = r(s, SchedulerIntent.SetCategoryRule(spot, null, 1, 0.5))
        assertNull(s.categoryRuleError)
        assertShare(0.5, RelativePriorityDomain.cellShare(s, t.mirrorCell), "the carrying occurrence")
        // The root occurrence of the same task id is not a carrier: still 1 of 2 in the root list.
        assertShare(0.5, RelativePriorityDomain.cellShare(s, t.bookCell), "the other occurrence is untouched")
        assertEquals(1, CategoryRules.ruleRows(s, spot).single().achieved.size)
    }

    @Test
    fun a_task_cell_rule_governs_only_the_carriers_under_its_cell() {
        val t = tree()
        var s = r(t.s, SchedulerIntent.AddTaskCategory(t.chapter, "deep"))
        s = r(s, SchedulerIntent.AddTaskCategory(t.read, "deep"))
        val deep = named(s, "deep")
        val readBefore = RelativePriorityDomain.cellShare(s, cellOf(s, t.read))
        // Relative to the task cell Book: Chapter only; Read (under Notes) is not governed.
        s = r(s, SchedulerIntent.SetCategoryRule(deep, t.bookCell, null, 0.3))
        assertNull(s.categoryRuleError)
        assertShare(0.3, RelativePriorityDomain.cellShare(s, cellOf(s, t.chapter)), "Chapter of Book")
        assertShare(readBefore, RelativePriorityDomain.cellShare(s, cellOf(s, t.read)), "Read is not governed")
        // Written through the mirror of Book, it is the same sub-list: it replaces the rule, not adds beside it.
        s = r(s, SchedulerIntent.SetCategoryRule(deep, t.mirrorCell, null, 0.4))
        assertEquals(1, s.categoryById(deep)!!.rules.size)
        assertShare(0.4, RelativePriorityDomain.cellShare(s, cellOf(s, t.chapter)), "the later rule stands")
    }

    @Test
    fun a_rule_holding_a_pinned_weight_is_warned_about() {
        val t = tree()
        var s = r(t.s, SchedulerIntent.AddTaskCategory(t.chapter, "deep"))
        val deep = named(s, "deep")
        s = r(s, SchedulerIntent.SetCategoryRule(deep, null, 1, 0.3))
        assertTrue(CategoryRules.pinnedRuleCells(s).isEmpty(), "nothing pinned, nothing to warn about")

        val chapterCell = cellOf(s, t.chapter)
        val bookList = s.cells[chapterCell]!!.parentListId
        val pinned = s.copy(priorityWeightPins = mapOf(bookList to setOf(PriorityWeightPin(chapterCell, 0))))
        val warning = CategoryRules.pinnedRuleCells(pinned).single()
        assertEquals("deep", warning.categoryTitle)
        assertEquals("Book / Chapter", warning.cellLabel)
        assertEquals("Book", warning.ancestorLabel)
    }

    @Test
    fun deleting_a_task_cell_category_takes_it_off_every_cell() {
        val t = tree()
        var s = r(t.s, SchedulerIntent.AddCellCategory(t.mirrorCell, "spot"))
        val spot = named(s, "spot")
        s = r(s, SchedulerIntent.DeleteCategory(spot))
        assertTrue(s.cells.values.none { spot in it.categoryIds })
    }
}
