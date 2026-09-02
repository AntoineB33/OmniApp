package org.example.project

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.example.project.scheduler.domain.CategoryRules
import org.example.project.scheduler.domain.RelativePriorityDomain
import org.example.project.scheduler.domain.SchedulerDomain
import org.example.project.scheduler.model.CategoryId
import org.example.project.scheduler.model.CellId
import org.example.project.scheduler.model.TaskId
import org.example.project.scheduler.model.WellKnownIds
import org.example.project.scheduler.persistence.SchedulerStateCodec
import org.example.project.scheduler.state.SchedulerIntent
import org.example.project.scheduler.state.SchedulerReducer
import org.example.project.scheduler.state.SchedulerState

/**
 * PRD §5 **categories and their rules**: the field on a task cell, and the standing statement its edit
 * window writes — *the tasks carrying this category under that task are worth this much of it*.
 *
 * The two halves of the feature this pins are the two the app promises: the rule is **held** (the priorities
 * are adjusted evenly after every edit so it stays true) and a contradiction is **refused** (the edit does
 * not half-happen; the state comes back untouched with a message).
 */
class CategoryRulesTest {

    /**
     * ```
     * root ─ Book  ─ Chapter
     *      │       └ Other
     *      └ Notes ─ Read
     *              └ Skim
     * ```
     * Four leaves under two parents, every one of them with a sibling, so a share can be moved without any
     * cell being an only child (which holds 100 % of its parent whatever its weight).
     */
    private class Fixture(
        val state: SchedulerState,
        val book: TaskId,
        val notes: TaskId,
        val chapter: TaskId,
        val other: TaskId,
        val read: TaskId,
        val skim: TaskId,
    )

    private fun fixture(): Fixture {
        var s = SchedulerState.empty()
        val rootCells = { s.lists[s.rootListId]!!.cellIds }

        val bookCell = rootCells()[0]
        s = SchedulerReducer.reduce(s, SchedulerIntent.SetCellTitle(bookCell, "Book"))
        val notesCell = rootCells()[1]
        s = SchedulerReducer.reduce(s, SchedulerIntent.SetCellTitle(notesCell, "Notes"))

        val book = s.cells[bookCell]!!.taskId!!
        val bookList = s.tasks[book]!!.childListId!!
        val chapterCell = s.lists[bookList]!!.cellIds[0]
        s = SchedulerReducer.reduce(s, SchedulerIntent.SetCellTitle(chapterCell, "Chapter"))
        val otherCell = s.lists[bookList]!!.cellIds[1]
        s = SchedulerReducer.reduce(s, SchedulerIntent.SetCellTitle(otherCell, "Other"))

        val notes = s.cells[notesCell]!!.taskId!!
        val notesList = s.tasks[notes]!!.childListId!!
        val readCell = s.lists[notesList]!!.cellIds[0]
        s = SchedulerReducer.reduce(s, SchedulerIntent.SetCellTitle(readCell, "Read"))
        val skimCell = s.lists[notesList]!!.cellIds[1]
        s = SchedulerReducer.reduce(s, SchedulerIntent.SetCellTitle(skimCell, "Skim"))

        return Fixture(
            state = s,
            book = book,
            notes = notes,
            chapter = s.cells[chapterCell]!!.taskId!!,
            other = s.cells[otherCell]!!.taskId!!,
            read = s.cells[readCell]!!.taskId!!,
            skim = s.cells[skimCell]!!.taskId!!,
        )
    }

    private fun categoryNamed(state: SchedulerState, title: String): CategoryId =
        state.categories.first { it.title == title }.id

    private fun assertShare(expected: Double, actual: Double, what: String) {
        assertTrue(abs(expected - actual) < 1e-6, "$what: expected $expected but was $actual")
    }

    // ----- the field: naming a category is pointing at it ----------------------------------------

    @Test
    fun a_title_the_account_already_holds_attaches_that_category_rather_than_minting_a_second() {
        val f = fixture()
        var s = SchedulerReducer.reduce(f.state, SchedulerIntent.AddTaskCategory(f.chapter, "deep"))
        s = SchedulerReducer.reduce(s, SchedulerIntent.AddTaskCategory(f.read, "Deep"))

        assertEquals(1, s.categories.size, "the same name must not mint two categories")
        val deep = categoryNamed(s, "deep")
        assertEquals(listOf(deep), s.tasks[f.chapter]!!.categoryIds)
        assertEquals(listOf(deep), s.tasks[f.read]!!.categoryIds)
    }

    @Test
    fun the_bin_takes_the_category_off_the_task_and_leaves_the_category_alone() {
        val f = fixture()
        var s = SchedulerReducer.reduce(f.state, SchedulerIntent.AddTaskCategory(f.chapter, "deep"))
        val deep = categoryNamed(s, "deep")
        s = SchedulerReducer.reduce(s, SchedulerIntent.RemoveTaskCategory(f.chapter, deep))

        assertEquals(emptyList(), s.tasks[f.chapter]!!.categoryIds)
        assertNotNull(s.categoryById(deep), "removing it from a task must not delete the account's category")
    }

    @Test
    fun deleting_the_category_takes_its_id_off_every_task_carrying_it() {
        val f = fixture()
        var s = SchedulerReducer.reduce(f.state, SchedulerIntent.AddTaskCategory(f.chapter, "deep"))
        s = SchedulerReducer.reduce(s, SchedulerIntent.AddTaskCategory(f.read, "deep"))
        val deep = categoryNamed(s, "deep")
        s = SchedulerReducer.reduce(s, SchedulerIntent.DeleteCategory(deep))

        assertNull(s.categoryById(deep))
        assertEquals(emptyList(), s.tasks[f.chapter]!!.categoryIds)
        assertEquals(emptyList(), s.tasks[f.read]!!.categoryIds)
    }

    @Test
    fun renaming_a_category_reaches_every_task_at_once_because_they_name_it_by_id() {
        val f = fixture()
        var s = SchedulerReducer.reduce(f.state, SchedulerIntent.AddTaskCategory(f.chapter, "deep"))
        s = SchedulerReducer.reduce(s, SchedulerIntent.AddTaskCategory(f.read, "deep"))
        val deep = categoryNamed(s, "deep")
        s = SchedulerReducer.reduce(s, SchedulerIntent.RenameCategory(deep, "focus"))

        assertEquals("focus", s.categoryById(deep)!!.title)
        assertEquals(listOf(deep), s.tasks[f.chapter]!!.categoryIds)
        assertEquals(listOf(deep), s.tasks[f.read]!!.categoryIds)
    }

    // ----- the rule is HELD ----------------------------------------------------------------------

    @Test
    fun a_rule_pulls_the_carrying_tasks_onto_its_share_and_holds_them_there() {
        val f = fixture()
        var s = SchedulerReducer.reduce(f.state, SchedulerIntent.AddTaskCategory(f.chapter, "deep"))
        val deep = categoryNamed(s, "deep")

        // Chapter is 1 of 2 under Book, which is 1 of 2 under root: 25 % of the tree to begin with.
        assertShare(0.25, CategoryRules.shareOf(s, deep, WellKnownIds.MAIN_TASK), "before the rule")

        s = SchedulerReducer.reduce(s, SchedulerIntent.SetCategoryRule(deep, WellKnownIds.MAIN_TASK, 0.33))
        assertNull(s.categoryRuleError, "a rule this tree can hold must not be refused")
        assertShare(0.33, CategoryRules.shareOf(s, deep, WellKnownIds.MAIN_TASK), "the user's own example")
        // The rule is a statement about the tree, so the tree itself now says it.
        assertShare(0.33, SchedulerDomain.absoluteTaskPriorities(s)[f.chapter]!!, "the percentage on the row")
    }

    @Test
    fun the_rest_of_the_scope_keeps_its_own_proportions_while_it_makes_room() {
        val f = fixture()
        var s = SchedulerReducer.reduce(f.state, SchedulerIntent.AddTaskCategory(f.chapter, "deep"))
        val deep = categoryNamed(s, "deep")
        s = SchedulerReducer.reduce(s, SchedulerIntent.SetCategoryRule(deep, WellKnownIds.MAIN_TASK, 0.5))

        val priorities = SchedulerDomain.absoluteTaskPriorities(s)
        assertShare(0.5, priorities[f.chapter]!!, "the carrier")
        // Read and Skim were equal before and are equal after: "adjusted evenly" is one common factor over
        // the carrying branches, never a re-weighting of everybody else against each other.
        assertShare(priorities[f.read]!!, priorities[f.skim]!!, "the untouched siblings")
        assertShare(1.0, priorities.values.filter { it > 0.0 }.sum() - priorities[f.book]!! - priorities[f.notes]!!, "the leaves still fill the tree")
    }

    @Test
    fun an_edit_elsewhere_re_establishes_the_rule_instead_of_drifting_off_it() {
        val f = fixture()
        var s = SchedulerReducer.reduce(f.state, SchedulerIntent.AddTaskCategory(f.chapter, "deep"))
        val deep = categoryNamed(s, "deep")
        s = SchedulerReducer.reduce(s, SchedulerIntent.SetCategoryRule(deep, WellKnownIds.MAIN_TASK, 0.4))

        // Raise a completely unrelated leaf's weight. Without the settle this would dilute the category.
        val notesList = s.tasks[f.notes]!!.childListId!!
        val readCell = s.lists[notesList]!!.cellIds.first { s.cells[it]?.taskId == f.read }
        s = SchedulerReducer.reduce(s, SchedulerIntent.SetPriorityWeight(readCell, 0, 7.0))

        assertNull(s.categoryRuleError)
        assertShare(0.4, CategoryRules.shareOf(s, deep, WellKnownIds.MAIN_TASK), "after an unrelated edit")
    }

    @Test
    fun two_carriers_under_one_scope_are_counted_together() {
        val f = fixture()
        var s = SchedulerReducer.reduce(f.state, SchedulerIntent.AddTaskCategory(f.chapter, "deep"))
        s = SchedulerReducer.reduce(s, SchedulerIntent.AddTaskCategory(f.read, "deep"))
        val deep = categoryNamed(s, "deep")
        s = SchedulerReducer.reduce(s, SchedulerIntent.SetCategoryRule(deep, WellKnownIds.MAIN_TASK, 0.6))

        val priorities = SchedulerDomain.absoluteTaskPriorities(s)
        assertShare(0.6, priorities[f.chapter]!! + priorities[f.read]!!, "the two carriers together")
    }

    @Test
    fun a_rule_scoped_on_a_task_governs_that_sub_tree_only() {
        val f = fixture()
        var s = SchedulerReducer.reduce(f.state, SchedulerIntent.AddTaskCategory(f.chapter, "deep"))
        val deep = categoryNamed(s, "deep")
        s = SchedulerReducer.reduce(s, SchedulerIntent.SetCategoryRule(deep, f.book, 0.75))

        assertNull(s.categoryRuleError)
        assertShare(0.75, CategoryRules.shareOf(s, deep, f.book), "inside Book")
        // Book itself still holds half the tree: a rule about a sub-tree says nothing about the tree above it.
        assertShare(0.5, RelativePriorityDomain.relativePriority(s, f.book, WellKnownIds.MAIN_TASK), "Book")
    }

    @Test
    fun a_carrier_takes_its_whole_sub_tree_and_a_nested_carrier_is_not_counted_twice() {
        val f = fixture()
        // Book itself carries the category, and so does Chapter inside it.
        var s = SchedulerReducer.reduce(f.state, SchedulerIntent.AddTaskCategory(f.book, "deep"))
        s = SchedulerReducer.reduce(s, SchedulerIntent.AddTaskCategory(f.chapter, "deep"))
        val deep = categoryNamed(s, "deep")

        val chains = CategoryRules.chainsFor(s, deep, WellKnownIds.MAIN_TASK)
        assertEquals(1, chains.size, "the walk stops at the top-most carrier")
        assertShare(0.5, CategoryRules.shareOf(s, deep, WellKnownIds.MAIN_TASK), "Book's whole sub-tree, once")
    }

    // ----- a contradiction is refused ------------------------------------------------------------

    @Test
    fun two_rules_at_one_scope_asking_for_more_than_all_of_it_are_refused_and_change_nothing() {
        val f = fixture()
        var s = SchedulerReducer.reduce(f.state, SchedulerIntent.AddTaskCategory(f.chapter, "deep"))
        s = SchedulerReducer.reduce(s, SchedulerIntent.AddTaskCategory(f.read, "shallow"))
        val deep = categoryNamed(s, "deep")
        val shallow = categoryNamed(s, "shallow")
        s = SchedulerReducer.reduce(s, SchedulerIntent.SetCategoryRule(deep, WellKnownIds.MAIN_TASK, 0.7))

        val before = s
        val after = SchedulerReducer.reduce(s, SchedulerIntent.SetCategoryRule(shallow, WellKnownIds.MAIN_TASK, 0.6))

        assertNotNull(after.categoryRuleError, "the user must be told")
        assertEquals(before.cells, after.cells, "no priority may move")
        assertEquals(before.categories, after.categories, "the refused rule must not be written")
        // ...and the first rule is still being held.
        assertShare(0.7, CategoryRules.shareOf(after, deep, WellKnownIds.MAIN_TASK), "the surviving rule")
    }

    @Test
    fun two_categories_covering_one_task_under_the_same_scope_are_refused() {
        val f = fixture()
        var s = SchedulerReducer.reduce(f.state, SchedulerIntent.AddTaskCategory(f.chapter, "deep"))
        s = SchedulerReducer.reduce(s, SchedulerIntent.AddTaskCategory(f.chapter, "shallow"))
        val deep = categoryNamed(s, "deep")
        val shallow = categoryNamed(s, "shallow")
        s = SchedulerReducer.reduce(s, SchedulerIntent.SetCategoryRule(deep, WellKnownIds.MAIN_TASK, 0.3))

        val after = SchedulerReducer.reduce(s, SchedulerIntent.SetCategoryRule(shallow, WellKnownIds.MAIN_TASK, 0.3))
        assertNotNull(after.categoryRuleError, "overlapping claims cannot both be honoured")
        assertEquals(s.categories, after.categories)
    }

    @Test
    fun a_rule_asking_for_all_of_a_scope_that_holds_something_else_is_refused() {
        val f = fixture()
        var s = SchedulerReducer.reduce(f.state, SchedulerIntent.AddTaskCategory(f.chapter, "deep"))
        val deep = categoryNamed(s, "deep")
        val after = SchedulerReducer.reduce(s, SchedulerIntent.SetCategoryRule(deep, f.book, 1.0))

        assertNotNull(after.categoryRuleError, "Other would be left with nothing")
        assertEquals(s.categories, after.categories)
    }

    @Test
    fun a_rule_asking_for_less_than_a_scope_every_task_of_which_carries_it_is_refused() {
        val f = fixture()
        var s = SchedulerReducer.reduce(f.state, SchedulerIntent.AddTaskCategory(f.chapter, "deep"))
        s = SchedulerReducer.reduce(s, SchedulerIntent.AddTaskCategory(f.other, "deep"))
        val deep = categoryNamed(s, "deep")
        val after = SchedulerReducer.reduce(s, SchedulerIntent.SetCategoryRule(deep, f.book, 0.5))

        assertNotNull(after.categoryRuleError, "there is nothing under Book to hold the other half")
        assertEquals(s.categories, after.categories)
    }

    @Test
    fun the_notice_is_local_only_state_and_the_next_dismissal_clears_it() {
        val f = fixture()
        var s = SchedulerReducer.reduce(f.state, SchedulerIntent.AddTaskCategory(f.chapter, "deep"))
        val deep = categoryNamed(s, "deep")
        s = SchedulerReducer.reduce(s, SchedulerIntent.SetCategoryRule(deep, f.book, 1.0))
        assertNotNull(s.categoryRuleError)

        // It must never reach a peer: the fingerprint is taken over the neutralized state.
        assertEquals(
            SchedulerStateCodec.syncFingerprint(s.copy(categoryRuleError = null)),
            SchedulerStateCodec.syncFingerprint(s),
        )
        assertNull(SchedulerReducer.reduce(s, SchedulerIntent.DismissCategoryRuleError).categoryRuleError)
    }

    // ----- dormant rules are not contradictions ---------------------------------------------------

    @Test
    fun deleting_the_last_carrier_puts_the_rule_to_sleep_rather_than_refusing_the_deletion() {
        val f = fixture()
        var s = SchedulerReducer.reduce(f.state, SchedulerIntent.AddTaskCategory(f.chapter, "deep"))
        val deep = categoryNamed(s, "deep")
        s = SchedulerReducer.reduce(s, SchedulerIntent.SetCategoryRule(deep, WellKnownIds.MAIN_TASK, 0.4))

        // PRD §4: the blank title is what deletes.
        val chapterCell: CellId =
            s.cells.values.first { it.taskId == f.chapter }.id
        s = SchedulerReducer.reduce(s, SchedulerIntent.SetCellTitle(chapterCell, ""))

        assertNull(s.categoryRuleError, "an ordinary deletion must not be refused")
        assertEquals(
            CategoryRules.Status.NoCarrier,
            CategoryRules.ruleRows(s, deep).single().status,
        )
    }

    @Test
    fun a_rule_whose_scope_is_gone_sleeps_and_says_so() {
        val f = fixture()
        var s = SchedulerReducer.reduce(f.state, SchedulerIntent.AddTaskCategory(f.chapter, "deep"))
        val deep = categoryNamed(s, "deep")
        s = SchedulerReducer.reduce(s, SchedulerIntent.SetCategoryRule(deep, f.notes, 0.5))

        // Notes never held a carrier, so the rule was asleep to begin with...
        assertEquals(CategoryRules.Status.NoCarrier, CategoryRules.ruleRows(s, deep).single().status)

        val notesCell = s.cells.values.first { it.taskId == f.notes }.id
        s = SchedulerReducer.reduce(s, SchedulerIntent.SetCellTitle(notesCell, ""))
        assertEquals(CategoryRules.Status.ScopeGone, CategoryRules.ruleRows(s, deep).single().status)
        assertNull(s.categoryRuleError)
    }

    // ----- persistence ----------------------------------------------------------------------------

    @Test
    fun categories_their_rules_and_the_ids_on_the_tasks_survive_a_round_trip() {
        val f = fixture()
        var s = SchedulerReducer.reduce(f.state, SchedulerIntent.AddTaskCategory(f.chapter, "deep"))
        s = SchedulerReducer.reduce(s, SchedulerIntent.AddTaskCategory(f.read, "deep"))
        val deep = categoryNamed(s, "deep")
        s = SchedulerReducer.reduce(s, SchedulerIntent.SetCategoryRule(deep, WellKnownIds.MAIN_TASK, 0.6))

        val decoded = SchedulerStateCodec.decodeSnapshot(SchedulerStateCodec.encodeSnapshot(s))
        assertNotNull(decoded)
        assertEquals(s.categories, decoded.categories)
        assertEquals(s.nextCategoryCounter, decoded.nextCategoryCounter)
        assertEquals(listOf(deep), decoded.tasks[f.chapter]!!.categoryIds)
        assertShare(0.6, CategoryRules.shareOf(decoded, deep, WellKnownIds.MAIN_TASK), "the rule after a reload")
    }

    @Test
    fun a_payload_written_before_categories_existed_still_loads() {
        // The previous shape: the same state with neither field, which is exactly what an older build wrote.
        val f = fixture()
        val snapshot = SchedulerStateCodec.encodeSnapshot(f.state)
        val decoded = SchedulerStateCodec.decodeSnapshot(snapshot)
        assertNotNull(decoded)
        assertEquals(emptyList(), decoded.categories)
        assertEquals(0, decoded.nextCategoryCounter)
        assertEquals(emptyList(), decoded.tasks[f.chapter]!!.categoryIds)
    }

    // ----- the clipboard --------------------------------------------------------------------------

    @Test
    fun a_copied_cell_carries_its_categories_and_a_paste_lands_them_by_name() {
        val f = fixture()
        var s = SchedulerReducer.reduce(f.state, SchedulerIntent.AddTaskCategory(f.chapter, "deep"))
        val deep = categoryNamed(s, "deep")

        val chapterCell = s.cells.values.first { it.taskId == f.chapter }.id
        // Ids off, so the payload is foreign by construction and pastes as a NEW task — which is the path
        // the categories have to travel by name. With the id on, the paste mirrors that very task and the
        // labels come along with it whatever this attribute says.
        val text = SchedulerDomain.copyCellsText(
            s,
            listOf(chapterCell),
            maxDepth = 1,
            options = SchedulerDomain.CopyOptions(includeIds = false),
        )
        assertTrue(text.contains("- category: deep"), "the clipboard text is for a person to read:\n$text")

        val target = s.lists[s.rootListId]!!.cellIds.last()
        s = SchedulerReducer.reduce(
            s,
            SchedulerIntent.ClickCell(target, ctrl = false, shift = false, visibleOrder = listOf(target)),
        )
        s = SchedulerReducer.reduce(s, SchedulerIntent.PasteTree(text))
        val pasted = s.cells[target]?.taskId
        assertNotNull(pasted)
        assertEquals(listOf(deep), s.tasks[pasted]!!.categoryIds, "the same category, not a second one")
        assertEquals(1, s.categories.size, "a paste must not mint a second category under one name")
    }
}
