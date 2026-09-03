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
        /** A rule's scope is a CELL, so the fixture hands out the two that own a sub-tree. */
        val bookCell: CellId,
        val notesCell: CellId,
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
            bookCell = bookCell,
            notesCell = notesCell,
        )
    }

    /** PRD §5: the whole tree is the one scope that is not a cell. */
    private val ROOT: CellId? = null

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
        assertShare(0.25, CategoryRules.shareOf(s, deep, ROOT), "before the rule")

        s = SchedulerReducer.reduce(s, SchedulerIntent.SetCategoryRule(deep, ROOT, 0.33))
        assertNull(s.categoryRuleError, "a rule this tree can hold must not be refused")
        assertShare(0.33, CategoryRules.shareOf(s, deep, ROOT), "the user's own example")
        // The rule is a statement about the tree, so the tree itself now says it.
        assertShare(0.33, SchedulerDomain.absoluteTaskPriorities(s)[f.chapter]!!, "the percentage on the row")
    }

    @Test
    fun the_rest_of_the_scope_keeps_its_own_proportions_while_it_makes_room() {
        val f = fixture()
        var s = SchedulerReducer.reduce(f.state, SchedulerIntent.AddTaskCategory(f.chapter, "deep"))
        val deep = categoryNamed(s, "deep")
        s = SchedulerReducer.reduce(s, SchedulerIntent.SetCategoryRule(deep, ROOT, 0.5))

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
        s = SchedulerReducer.reduce(s, SchedulerIntent.SetCategoryRule(deep, ROOT, 0.4))

        // Raise a completely unrelated leaf's weight. Without the settle this would dilute the category.
        val notesList = s.tasks[f.notes]!!.childListId!!
        val readCell = s.lists[notesList]!!.cellIds.first { s.cells[it]?.taskId == f.read }
        s = SchedulerReducer.reduce(s, SchedulerIntent.SetPriorityWeight(readCell, 0, 7.0))

        assertNull(s.categoryRuleError)
        assertShare(0.4, CategoryRules.shareOf(s, deep, ROOT), "after an unrelated edit")
    }

    @Test
    fun two_carriers_under_one_scope_are_counted_together() {
        val f = fixture()
        var s = SchedulerReducer.reduce(f.state, SchedulerIntent.AddTaskCategory(f.chapter, "deep"))
        s = SchedulerReducer.reduce(s, SchedulerIntent.AddTaskCategory(f.read, "deep"))
        val deep = categoryNamed(s, "deep")
        s = SchedulerReducer.reduce(s, SchedulerIntent.SetCategoryRule(deep, ROOT, 0.6))

        val priorities = SchedulerDomain.absoluteTaskPriorities(s)
        assertShare(0.6, priorities[f.chapter]!! + priorities[f.read]!!, "the two carriers together")
    }

    @Test
    fun a_rule_scoped_on_a_task_governs_that_sub_tree_only() {
        val f = fixture()
        var s = SchedulerReducer.reduce(f.state, SchedulerIntent.AddTaskCategory(f.chapter, "deep"))
        val deep = categoryNamed(s, "deep")
        s = SchedulerReducer.reduce(s, SchedulerIntent.SetCategoryRule(deep, f.bookCell, 0.75))

        assertNull(s.categoryRuleError)
        assertShare(0.75, CategoryRules.shareOf(s, deep, f.bookCell), "inside Book")
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

        val chains = CategoryRules.chainsFor(s, deep, ROOT)
        assertEquals(1, chains.size, "the walk stops at the top-most carrier")
        assertShare(0.5, CategoryRules.shareOf(s, deep, ROOT), "Book's whole sub-tree, once")
    }

    // ----- a contradiction is refused ------------------------------------------------------------

    @Test
    fun two_rules_at_one_scope_asking_for_more_than_all_of_it_are_refused_and_change_nothing() {
        val f = fixture()
        var s = SchedulerReducer.reduce(f.state, SchedulerIntent.AddTaskCategory(f.chapter, "deep"))
        s = SchedulerReducer.reduce(s, SchedulerIntent.AddTaskCategory(f.read, "shallow"))
        val deep = categoryNamed(s, "deep")
        val shallow = categoryNamed(s, "shallow")
        s = SchedulerReducer.reduce(s, SchedulerIntent.SetCategoryRule(deep, ROOT, 0.7))

        val before = s
        val after = SchedulerReducer.reduce(s, SchedulerIntent.SetCategoryRule(shallow, ROOT, 0.6))

        assertNotNull(after.categoryRuleError, "the user must be told")
        assertEquals(before.cells, after.cells, "no priority may move")
        assertEquals(before.categories, after.categories, "the refused rule must not be written")
        // ...and the first rule is still being held.
        assertShare(0.7, CategoryRules.shareOf(after, deep, ROOT), "the surviving rule")
    }

    @Test
    fun two_categories_covering_one_task_under_the_same_scope_are_refused() {
        val f = fixture()
        var s = SchedulerReducer.reduce(f.state, SchedulerIntent.AddTaskCategory(f.chapter, "deep"))
        s = SchedulerReducer.reduce(s, SchedulerIntent.AddTaskCategory(f.chapter, "shallow"))
        val deep = categoryNamed(s, "deep")
        val shallow = categoryNamed(s, "shallow")
        s = SchedulerReducer.reduce(s, SchedulerIntent.SetCategoryRule(deep, ROOT, 0.3))

        val after = SchedulerReducer.reduce(s, SchedulerIntent.SetCategoryRule(shallow, ROOT, 0.3))
        assertNotNull(after.categoryRuleError, "overlapping claims cannot both be honoured")
        assertEquals(s.categories, after.categories)
    }

    @Test
    fun a_rule_asking_for_all_of_a_scope_that_holds_something_else_is_refused() {
        val f = fixture()
        var s = SchedulerReducer.reduce(f.state, SchedulerIntent.AddTaskCategory(f.chapter, "deep"))
        val deep = categoryNamed(s, "deep")
        val after = SchedulerReducer.reduce(s, SchedulerIntent.SetCategoryRule(deep, f.bookCell, 1.0))

        assertNotNull(after.categoryRuleError, "Other would be left with nothing")
        assertEquals(s.categories, after.categories)
    }

    @Test
    fun a_rule_asking_for_less_than_a_scope_every_task_of_which_carries_it_is_refused() {
        val f = fixture()
        var s = SchedulerReducer.reduce(f.state, SchedulerIntent.AddTaskCategory(f.chapter, "deep"))
        s = SchedulerReducer.reduce(s, SchedulerIntent.AddTaskCategory(f.other, "deep"))
        val deep = categoryNamed(s, "deep")
        val after = SchedulerReducer.reduce(s, SchedulerIntent.SetCategoryRule(deep, f.bookCell, 0.5))

        assertNotNull(after.categoryRuleError, "there is nothing under Book to hold the other half")
        assertEquals(s.categories, after.categories)
    }

    @Test
    fun the_notice_is_local_only_state_and_the_next_dismissal_clears_it() {
        val f = fixture()
        var s = SchedulerReducer.reduce(f.state, SchedulerIntent.AddTaskCategory(f.chapter, "deep"))
        val deep = categoryNamed(s, "deep")
        s = SchedulerReducer.reduce(s, SchedulerIntent.SetCategoryRule(deep, f.bookCell, 1.0))
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
        s = SchedulerReducer.reduce(s, SchedulerIntent.SetCategoryRule(deep, ROOT, 0.4))

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
        s = SchedulerReducer.reduce(s, SchedulerIntent.SetCategoryRule(deep, f.notesCell, 0.5))

        // Notes never held a carrier, so the rule was asleep to begin with...
        assertEquals(CategoryRules.Status.NoCarrier, CategoryRules.ruleRows(s, deep).single().status)

        s = SchedulerReducer.reduce(s, SchedulerIntent.SetCellTitle(f.notesCell, ""))
        assertEquals(CategoryRules.Status.ScopeGone, CategoryRules.ruleRows(s, deep).single().status)
        assertNull(s.categoryRuleError)
    }

    // ----- the scope is a CELL, because a task can appear several times ---------------------------

    @Test
    fun the_picker_offers_cells_by_their_path_so_two_occurrences_of_one_task_are_told_apart() {
        val f = fixture()
        // Mirror Book under Notes: one more CELL, the same task and the same sub-list.
        val mirrorCell = f.state.lists[f.state.tasks[f.notes]!!.childListId!!]!!.cellIds.last()
        val s = SchedulerReducer.reduce(f.state, SchedulerIntent.AssignTaskId(mirrorCell, f.book))

        val labels = CategoryRules.scopeEntries(s, "").map { it.label }
        assertTrue(labels.contains("Book"), "the occurrence under root:\n$labels")
        assertTrue(labels.contains("Notes / Book"), "the occurrence under Notes:\n$labels")
        // Each LIST is entered once, and only from the cell that owns it: the mirror is a row of its own,
        // but the sub-tree under it is not offered a second time.
        assertEquals(
            listOf("Book / Chapter"),
            labels.filter { it.endsWith("Chapter") },
            "the mirrored sub-tree is walked once:\n$labels",
        )
        // ...and the root is always the first offer.
        assertNull(CategoryRules.scopeEntries(s, "").first().cellId)
    }

    @Test
    fun two_cells_of_one_mirrored_task_are_one_scope_so_a_rule_about_the_second_replaces_the_first() {
        val f = fixture()
        val mirrorCell = f.state.lists[f.state.tasks[f.notes]!!.childListId!!]!!.cellIds.last()
        var s = SchedulerReducer.reduce(f.state, SchedulerIntent.AssignTaskId(mirrorCell, f.book))
        s = SchedulerReducer.reduce(s, SchedulerIntent.AddTaskCategory(f.chapter, "deep"))
        val deep = categoryNamed(s, "deep")

        s = SchedulerReducer.reduce(s, SchedulerIntent.SetCategoryRule(deep, f.bookCell, 0.6))
        s = SchedulerReducer.reduce(s, SchedulerIntent.SetCategoryRule(deep, mirrorCell, 0.4))

        // A sub-list belongs to the task id, so both cells show ONE sub-tree: two rules about it would be
        // two statements about one thing, which is exactly what "at most one rule per scope" forbids.
        val rule = s.categoryById(deep)!!.rules.single()
        assertEquals(mirrorCell, rule.scopeCellId)
        assertShare(0.4, rule.share, "the later rule is the one that stands")
        assertShare(0.4, CategoryRules.shareOf(s, deep, f.bookCell), "read through either cell")
        assertShare(0.4, CategoryRules.shareOf(s, deep, mirrorCell), "read through either cell")
    }

    @Test
    fun a_rule_sleeps_when_the_cell_it_names_goes_even_though_the_task_stays() {
        val f = fixture()
        val mirrorCell = f.state.lists[f.state.tasks[f.notes]!!.childListId!!]!!.cellIds.last()
        var s = SchedulerReducer.reduce(f.state, SchedulerIntent.AssignTaskId(mirrorCell, f.book))
        s = SchedulerReducer.reduce(s, SchedulerIntent.AddTaskCategory(f.chapter, "deep"))
        val deep = categoryNamed(s, "deep")
        s = SchedulerReducer.reduce(s, SchedulerIntent.SetCategoryRule(deep, mirrorCell, 0.5))
        assertEquals(CategoryRules.Status.Held, CategoryRules.ruleRows(s, deep).single().status)

        // PRD §4: the blank title is what deletes — and it deletes the OCCURRENCE the user pointed at.
        s = SchedulerReducer.reduce(s, SchedulerIntent.SetCellTitle(mirrorCell, ""))

        assertNull(s.categoryRuleError, "an ordinary deletion must not be refused")
        assertEquals(
            CategoryRules.Status.ScopeGone,
            CategoryRules.ruleRows(s, deep).single().status,
            "the place the rule was written about is gone, so the rule sleeps",
        )
    }

    @Test
    fun a_rule_row_names_its_scope_by_the_cell_s_own_path() {
        val f = fixture()
        var s = SchedulerReducer.reduce(f.state, SchedulerIntent.AddTaskCategory(f.chapter, "deep"))
        val deep = categoryNamed(s, "deep")
        val chapterCell = s.cells.values.first { it.taskId == f.chapter }.id
        s = SchedulerReducer.reduce(s, SchedulerIntent.SetCategoryRule(deep, chapterCell, 0.0))

        assertEquals("Book / Chapter", CategoryRules.ruleRows(s, deep).single().scopeLabel)
    }

    // ----- persistence ----------------------------------------------------------------------------

    @Test
    fun categories_their_rules_and_the_ids_on_the_tasks_survive_a_round_trip() {
        val f = fixture()
        var s = SchedulerReducer.reduce(f.state, SchedulerIntent.AddTaskCategory(f.chapter, "deep"))
        s = SchedulerReducer.reduce(s, SchedulerIntent.AddTaskCategory(f.read, "deep"))
        val deep = categoryNamed(s, "deep")
        s = SchedulerReducer.reduce(s, SchedulerIntent.SetCategoryRule(deep, ROOT, 0.6))

        val decoded = SchedulerStateCodec.decodeSnapshot(SchedulerStateCodec.encodeSnapshot(s))
        assertNotNull(decoded)
        assertEquals(s.categories, decoded.categories)
        assertEquals(s.nextCategoryCounter, decoded.nextCategoryCounter)
        assertEquals(listOf(deep), decoded.tasks[f.chapter]!!.categoryIds)
        assertShare(0.6, CategoryRules.shareOf(decoded, deep, ROOT), "the rule after a reload")
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

    @Test
    fun a_payload_written_when_a_rule_s_scope_was_a_task_loads_with_that_task_s_first_cell() {
        val f = fixture()
        var s = SchedulerReducer.reduce(f.state, SchedulerIntent.AddTaskCategory(f.chapter, "deep"))
        val deep = categoryNamed(s, "deep")
        s = SchedulerReducer.reduce(s, SchedulerIntent.SetCategoryRule(deep, f.bookCell, 0.6))

        // The PREVIOUS shape, byte for byte: a rule named a task and knew nothing of cells.
        val previous = SchedulerStateCodec.encode(s).replace(
            Regex("\"scopeCellId\": \"[^\"]*\",\\s*"),
            "",
        )
        assertTrue(previous.contains("\"scopeTaskId\""), "the older shape is what is being loaded:\n$previous")
        assertTrue(!previous.contains("scopeCellId"), "...and it holds no cell at all:\n$previous")

        val decoded = SchedulerStateCodec.decode(previous)
        assertNotNull(decoded)
        // "Under Book" becomes "under Book's first occurrence" — the same cell "go to task" lands on.
        assertEquals(f.bookCell, decoded.categories.single().rules.single().scopeCellId)
        assertShare(0.6, CategoryRules.shareOf(decoded, deep, f.bookCell), "the rule after the migration")
    }

    @Test
    fun a_payload_whose_rule_named_the_root_task_loads_as_the_whole_tree() {
        val f = fixture()
        var s = SchedulerReducer.reduce(f.state, SchedulerIntent.AddTaskCategory(f.chapter, "deep"))
        val deep = categoryNamed(s, "deep")
        s = SchedulerReducer.reduce(s, SchedulerIntent.SetCategoryRule(deep, ROOT, 0.33))

        val previous = SchedulerStateCodec.encode(s).replace(
            Regex("\"scopeCellId\": \"[^\"]*\",\\s*"),
            "",
        )
        val decoded = SchedulerStateCodec.decode(previous)
        assertNotNull(decoded)
        assertNull(decoded.categories.single().rules.single().scopeCellId, "task/main is the whole tree")
        assertShare(0.33, CategoryRules.shareOf(decoded, deep, ROOT), "the user's own example, reloaded")
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
