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
import org.example.project.scheduler.model.CategoryRule
import org.example.project.scheduler.model.CellId
import org.example.project.scheduler.model.TaskId
import org.example.project.scheduler.model.WellKnownIds
import org.example.project.scheduler.persistence.SchedulerStateCodec
import org.example.project.scheduler.state.SchedulerIntent
import org.example.project.scheduler.state.SchedulerReducer
import org.example.project.scheduler.state.SchedulerState
import org.example.project.scheduler.state.HistoryCategory

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

    /**
     * The release account's root list, in miniature: two weight columns whose headers are `0.9` and `1.0`
     * — so the FIRST is worth 90 % of the list and the second the remaining 10 % (PRD §5) — with "Book"
     * holding its value in the first, and "Notes" (which carries the category) and a third cell holding
     * theirs only in the second. Notes can be moved within that second column, but it can never be worth
     * more than the 10 % the column itself is worth, however large its weight grows.
     */
    private fun twoColumnFixture(): Fixture {
        val f = fixture()
        val rootList = f.state.rootListId
        val otherRootCell = f.state.lists[rootList]!!.cellIds[2]
        var s = SchedulerReducer.reduce(f.state, SchedulerIntent.SetCellTitle(otherRootCell, "Misc"))
        s = SchedulerReducer.reduce(s, SchedulerIntent.AddPriorityColumn(rootList, 1))
        s = SchedulerReducer.reduce(s, SchedulerIntent.SetPriorityColumnWeight(rootList, 0, 0.9))
        s = SchedulerReducer.reduce(s, SchedulerIntent.SetPriorityColumnWeight(rootList, 1, 1.0))
        for ((cellId, weights) in listOf(
            f.bookCell to listOf(1.0, 0.0),
            f.notesCell to listOf(0.0, 1.0),
            otherRootCell to listOf(0.0, 1.0),
        )) {
            weights.forEachIndexed { column, value ->
                s = SchedulerReducer.reduce(s, SchedulerIntent.SetPriorityWeight(cellId, column, value))
            }
        }
        s = SchedulerReducer.reduce(s, SchedulerIntent.AddTaskCategory(f.notes, "deep"))
        return Fixture(
            state = s,
            book = f.book,
            notes = f.notes,
            chapter = f.chapter,
            other = f.other,
            read = f.read,
            skim = f.skim,
            bookCell = f.bookCell,
            notesCell = f.notesCell,
        )
    }

    /** PRD §5: the whole tree is the one scope that is not a cell. */
    private val ROOT: CellId? = null

    private fun categoryNamed(state: SchedulerState, title: String): CategoryId =
        state.categories.first { it.title == title }.id

    /** What [categoryId] holds of the ancestor sub-list [relative] names at [distance] — its ONE governed group. */
    private fun shareOf(state: SchedulerState, categoryId: CategoryId, relative: CellId?, distance: Int?): Double =
        RelativePriorityDomain.chainsProduct(state, chainsOf(state, categoryId, relative, distance))

    private fun chainsOf(state: SchedulerState, categoryId: CategoryId, relative: CellId?, distance: Int?): List<List<CellId>> =
        CategoryRules.ruleGroups(state, state.categoryById(categoryId)!!, CategoryRule(relative, distance, 0.0)).single().chains

    private fun assertShare(expected: Double, actual: Double, what: String) {
        assertTrue(abs(expected - actual) < 1e-6, "$what: expected $expected but was $actual")
    }

    /** User rule 2026-10-02: the rules of several categories read as ONE list, a row per scope. */
    @Test
    fun the_rules_of_several_categories_are_one_row_per_scope_with_the_share_they_all_give() {
        val f = fixture()
        var s = SchedulerReducer.reduce(f.state, SchedulerIntent.AddTaskCategory(f.chapter, "deep"))
        s = SchedulerReducer.reduce(s, SchedulerIntent.AddTaskCategory(f.read, "light"))
        val deep = categoryNamed(s, "deep")
        val light = categoryNamed(s, "light")
        s = SchedulerReducer.reduce(s, SchedulerIntent.SetCategoryRule(deep, ROOT, null, 0.25))
        s = SchedulerReducer.reduce(s, SchedulerIntent.SetCategoryRule(light, ROOT, null, 0.25))
        s = SchedulerReducer.reduce(s, SchedulerIntent.SetCategoryRule(deep, f.bookCell, null, 0.5))

        val rows = CategoryRules.sharedRuleRows(s, listOf(deep, light))
        assertEquals(listOf(ROOT, f.bookCell), rows.map { it.relativeToCellId })
        assertEquals(0.25, rows[0].share, "both give the whole tree 25 %")
        assertEquals(2, rows[0].holders)
        assertEquals(null, rows[1].share, "only one of the two has a rule under Book: the field is empty")
        assertEquals(1, rows[1].holders)
        // Read alone, the category's own rule shows.
        assertEquals(0.5, CategoryRules.sharedRuleRows(s, listOf(deep)).single { it.relativeToCellId == f.bookCell }.share)
        // Two shares on one scope that differ: empty.
        val differing = s.copy(
            categories = s.categories.map { c ->
                if (c.id == light) c.copy(rules = c.rules.map { it.copy(share = 0.1) }) else c
            },
        )
        assertEquals(null, CategoryRules.sharedRuleRows(differing, listOf(deep, light)).first().share)
    }

    // ----- distance 1 relative to no cell: each carrier's own sub-list (replaced "Share of its sub-list") --------

    private fun shareOfCell(state: SchedulerState, taskId: TaskId): Double =
        RelativePriorityDomain.cellShare(state, state.cells.values.first { it.taskId == taskId }.id)

    @Test
    fun a_rule_at_distance_one_relative_to_no_cell_holds_every_carrier_at_that_share_of_its_own_sub_list() {
        val f = fixture()
        var s = SchedulerReducer.reduce(f.state, SchedulerIntent.AddTaskCategory(f.chapter, "deep"))
        s = SchedulerReducer.reduce(s, SchedulerIntent.AddTaskCategory(f.read, "deep"))
        val deep = categoryNamed(s, "deep")

        s = SchedulerReducer.reduce(s, SchedulerIntent.SetCategoryRule(deep, ROOT, 1, 0.2))
        assertNull(s.categoryRuleError)
        // Each carrier is 20 % of ITS list — Chapter of Book's, Read of Notes's — one group per sub-list.
        assertShare(0.2, shareOfCell(s, f.chapter), "Chapter in Book")
        assertShare(0.2, shareOfCell(s, f.read), "Read in Notes")
        assertShare(0.8, shareOfCell(s, f.other), "Other keeps the rest")
        assertEquals(2, CategoryRules.ruleRows(s, deep).single().achieved.size, "two sub-lists governed")

        // …and HELD: an unrelated edit in Book's list does not move it off 20 %.
        val otherCell = s.cells.values.first { it.taskId == f.other }.id
        s = SchedulerReducer.reduce(s, SchedulerIntent.SetPriorityWeight(otherCell, 0, 9.0))
        assertShare(0.2, shareOfCell(s, f.chapter), "Chapter in Book after Other's weight moved")
    }

    @Test
    fun two_carriers_of_one_sub_list_are_counted_together() {
        val f = fixture()
        var s = SchedulerReducer.reduce(f.state, SchedulerIntent.SetCellTitle(f.state.lists[f.state.tasks[f.book]!!.childListId!!]!!.cellIds[2], "Third"))
        s = SchedulerReducer.reduce(s, SchedulerIntent.AddTaskCategory(f.chapter, "deep"))
        s = SchedulerReducer.reduce(s, SchedulerIntent.AddTaskCategory(f.other, "deep"))
        s = SchedulerReducer.reduce(s, SchedulerIntent.SetCategoryRule(categoryNamed(s, "deep"), ROOT, 1, 0.6))
        assertShare(0.6, shareOfCell(s, f.chapter) + shareOfCell(s, f.other), "Chapter and Other together")
    }

    /** The last resort: a row with a 0 in the column that is worth 90 % cannot be SCALED past 10 %. */
    @Test
    fun a_share_no_factor_reaches_is_reached_by_adding_to_the_row() {
        val f = twoColumnFixture()
        val deep = categoryNamed(f.state, "deep")
        val s = SchedulerReducer.reduce(f.state, SchedulerIntent.SetCategoryRule(deep, ROOT, 1, 0.5))
        assertShare(0.5, shareOfCell(s, f.notes), "Notes of the root list")
        // The term reached the column the row had nothing in.
        assertTrue(s.cells[f.notesCell]!!.priorityWeights[0] > 0.0)
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
        assertShare(0.25, shareOf(s, deep, ROOT, null), "before the rule")

        s = SchedulerReducer.reduce(s, SchedulerIntent.SetCategoryRule(deep, ROOT, null, 0.33))
        assertNull(s.categoryRuleError, "a rule this tree can hold must not be refused")
        assertShare(0.33, shareOf(s, deep, ROOT, null), "the user's own example")
        // The rule is a statement about the tree, so the tree itself now says it.
        assertShare(0.33, SchedulerDomain.absoluteTaskPriorities(s)[f.chapter]!!, "the percentage on the row")
    }

    @Test
    fun the_rest_of_the_scope_keeps_its_own_proportions_while_it_makes_room() {
        val f = fixture()
        var s = SchedulerReducer.reduce(f.state, SchedulerIntent.AddTaskCategory(f.chapter, "deep"))
        val deep = categoryNamed(s, "deep")
        s = SchedulerReducer.reduce(s, SchedulerIntent.SetCategoryRule(deep, ROOT, null, 0.5))

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
        s = SchedulerReducer.reduce(s, SchedulerIntent.SetCategoryRule(deep, ROOT, null, 0.4))

        // Raise a completely unrelated leaf's weight. Without the settle this would dilute the category.
        val notesList = s.tasks[f.notes]!!.childListId!!
        val readCell = s.lists[notesList]!!.cellIds.first { s.cells[it]?.taskId == f.read }
        s = SchedulerReducer.reduce(s, SchedulerIntent.SetPriorityWeight(readCell, 0, 7.0))

        assertNull(s.categoryRuleError)
        assertShare(0.4, shareOf(s, deep, ROOT, null), "after an unrelated edit")
    }

    @Test
    fun two_carriers_under_one_scope_are_counted_together() {
        val f = fixture()
        var s = SchedulerReducer.reduce(f.state, SchedulerIntent.AddTaskCategory(f.chapter, "deep"))
        s = SchedulerReducer.reduce(s, SchedulerIntent.AddTaskCategory(f.read, "deep"))
        val deep = categoryNamed(s, "deep")
        s = SchedulerReducer.reduce(s, SchedulerIntent.SetCategoryRule(deep, ROOT, null, 0.6))

        val priorities = SchedulerDomain.absoluteTaskPriorities(s)
        assertShare(0.6, priorities[f.chapter]!! + priorities[f.read]!!, "the two carriers together")
    }

    @Test
    fun a_rule_scoped_on_a_task_governs_that_sub_tree_only() {
        val f = fixture()
        var s = SchedulerReducer.reduce(f.state, SchedulerIntent.AddTaskCategory(f.chapter, "deep"))
        val deep = categoryNamed(s, "deep")
        s = SchedulerReducer.reduce(s, SchedulerIntent.SetCategoryRule(deep, f.bookCell, null, 0.75))

        assertNull(s.categoryRuleError)
        assertShare(0.75, shareOf(s, deep, f.bookCell, null), "inside Book")
        // Book itself still holds half the tree: a rule about a sub-tree says nothing about the tree above it.
        assertShare(0.5, RelativePriorityDomain.relativePriority(s, f.book, WellKnownIds.ROOT_TASK), "Book")
    }

    @Test
    fun a_task_cell_rule_counts_the_top_most_carrier_and_a_distance_rule_each_against_its_own_parent() {
        val f = fixture()
        // Book itself carries the category, and so does Chapter inside it.
        var s = SchedulerReducer.reduce(f.state, SchedulerIntent.AddTaskCategory(f.book, "deep"))
        s = SchedulerReducer.reduce(s, SchedulerIntent.AddTaskCategory(f.chapter, "deep"))
        val deep = categoryNamed(s, "deep")
        // Relative to the whole tree: the walk stops at Book, whose whole sub-tree is counted once.
        assertEquals(1, chainsOf(s, deep, ROOT, null).size, "the walk stops at the top-most carrier")
        assertShare(0.5, shareOf(s, deep, ROOT, null), "Book's whole sub-tree, once")
        // At parent distance 1 each is a share of its OWN parent's sub-list: Book of the root list, Chapter of Book's.
        val groups = CategoryRules.ruleGroups(s, s.categoryById(deep)!!, CategoryRule(null, 1, 0.0))
        assertEquals(listOf(null, f.bookCell), groups.map { it.ancestor })
        // At distance 2 only Chapter is that deep, and its chain runs through Book.
        assertEquals(listOf(f.bookCell, s.cells.values.first { it.taskId == f.chapter }.id), chainsOf(s, deep, ROOT, 2).single())
    }

    // ----- a contradiction is refused ------------------------------------------------------------

    @Test
    fun two_rules_at_one_scope_asking_for_more_than_all_of_it_are_refused_and_change_nothing() {
        val f = fixture()
        var s = SchedulerReducer.reduce(f.state, SchedulerIntent.AddTaskCategory(f.chapter, "deep"))
        s = SchedulerReducer.reduce(s, SchedulerIntent.AddTaskCategory(f.read, "shallow"))
        val deep = categoryNamed(s, "deep")
        val shallow = categoryNamed(s, "shallow")
        s = SchedulerReducer.reduce(s, SchedulerIntent.SetCategoryRule(deep, ROOT, null, 0.7))

        val before = s
        val after = SchedulerReducer.reduce(s, SchedulerIntent.SetCategoryRule(shallow, ROOT, null, 0.6))

        assertNotNull(after.categoryRuleError, "the user must be told")
        assertEquals(before.cells, after.cells, "no priority may move")
        assertEquals(before.categories, after.categories, "the refused rule must not be written")
        // ...and the first rule is still being held.
        assertShare(0.7, shareOf(after, deep, ROOT, null), "the surviving rule")
    }

    @Test
    fun two_categories_covering_one_task_under_the_same_scope_are_refused() {
        val f = fixture()
        var s = SchedulerReducer.reduce(f.state, SchedulerIntent.AddTaskCategory(f.chapter, "deep"))
        s = SchedulerReducer.reduce(s, SchedulerIntent.AddTaskCategory(f.chapter, "shallow"))
        val deep = categoryNamed(s, "deep")
        val shallow = categoryNamed(s, "shallow")
        s = SchedulerReducer.reduce(s, SchedulerIntent.SetCategoryRule(deep, ROOT, null, 0.3))

        val after = SchedulerReducer.reduce(s, SchedulerIntent.SetCategoryRule(shallow, ROOT, null, 0.3))
        assertNotNull(after.categoryRuleError, "overlapping claims cannot both be honoured")
        assertEquals(s.categories, after.categories)
    }

    @Test
    fun a_rule_asking_for_all_of_a_scope_that_holds_something_else_is_refused() {
        val f = fixture()
        var s = SchedulerReducer.reduce(f.state, SchedulerIntent.AddTaskCategory(f.chapter, "deep"))
        val deep = categoryNamed(s, "deep")
        val after = SchedulerReducer.reduce(s, SchedulerIntent.SetCategoryRule(deep, f.bookCell, null, 1.0))

        assertNotNull(after.categoryRuleError, "Other would be left with nothing")
        assertEquals(s.categories, after.categories)
    }

    @Test
    fun a_rule_asking_for_less_than_a_scope_every_task_of_which_carries_it_is_refused() {
        val f = fixture()
        var s = SchedulerReducer.reduce(f.state, SchedulerIntent.AddTaskCategory(f.chapter, "deep"))
        s = SchedulerReducer.reduce(s, SchedulerIntent.AddTaskCategory(f.other, "deep"))
        val deep = categoryNamed(s, "deep")
        val after = SchedulerReducer.reduce(s, SchedulerIntent.SetCategoryRule(deep, f.bookCell, null, 0.5))

        assertNotNull(after.categoryRuleError, "there is nothing under Book to hold the other half")
        assertEquals(s.categories, after.categories)
    }

    /**
     * PRD §5: a factor can only scale what is already there, so a cell left at **0** in a weight column can
     * never be scaled into it and its share is capped at the absolute weight of the columns it does carry a
     * value in. The release account met that cap: every root cell but two sat at 0 in a first column worth
     * 90 % of the list, which put "50 % of root" out of reach of any of them. The solve now ADDS a common
     * term instead, which reaches the missing column and lands the rule.
     */
    @Test
    fun a_share_no_factor_can_reach_is_reached_by_adding_to_the_weights_instead() {
        val f = twoColumnFixture()
        val deep = categoryNamed(f.state, "deep")
        // No factor can get there: Notes is at 0 in the column worth 90 % of the list.
        assertTrue(
            RelativePriorityDomain.cellShare(f.state, f.notesCell) < 0.11,
            "the fixture must start under the 10 % the second column is worth",
        )

        val after = SchedulerReducer.reduce(f.state, SchedulerIntent.SetCategoryRule(deep, ROOT, 1, 0.5))

        assertNull(after.categoryRuleError, "adding can reach it, so nothing may be refused")
        assertShare(0.5, shareOf(after, deep, ROOT, 1), "the rule")
        assertTrue(
            after.cells[f.notesCell]!!.priorityWeights[0] > 0.0,
            "the term must have reached the column the cell was absent from",
        )
    }

    /** The addition reaches EVERY column, so the cell is no longer capped and the next edit is a factor. */
    @Test
    fun once_added_to_the_cell_is_in_every_column_and_re_establishing_is_an_ordinary_factor() {
        val f = twoColumnFixture()
        val deep = categoryNamed(f.state, "deep")
        val added = SchedulerReducer.reduce(f.state, SchedulerIntent.SetCategoryRule(deep, ROOT, 1, 0.5))
        val row = added.cells[f.notesCell]!!.priorityWeights

        assertTrue(row.all { it > 0.0 }, "every column must carry a value now: $row")
        // …so a plain factor can now move it anywhere, which is what a re-establishment uses.
        val moved = RelativePriorityDomain.setChainsShare(
            added,
            chainsOf(added, deep, ROOT, 1),
            0.7,
        )
        assertShare(0.7, shareOf(moved, deep, ROOT, 1), "the factor alone")
        val ratios = moved.cells[f.notesCell]!!.priorityWeights.indices.map { i ->
            moved.cells[f.notesCell]!!.priorityWeights[i] / row[i]
        }
        assertTrue(
            abs(ratios.max() - ratios.min()) < 1e-6,
            "a factor scales the whole row by ONE number, so every ratio must match: $ratios",
        )
    }

    /**
     * Multiplying is the preferred move because it keeps every ratio the user set, so it must still be what
     * happens wherever it lands — the fallback may only ever pick up what a factor could not do.
     */
    @Test
    fun a_share_a_factor_can_reach_is_still_reached_by_the_factor() {
        val f = twoColumnFixture()
        val deep = categoryNamed(f.state, "deep")
        val before = f.state.cells[f.notesCell]!!.priorityWeights

        val after = SchedulerReducer.reduce(f.state, SchedulerIntent.SetCategoryRule(deep, ROOT, 1, 0.08))

        assertNull(after.categoryRuleError, "8 % is inside the 10 % the second column is worth")
        assertShare(0.08, shareOf(after, deep, ROOT, 1), "the rule")
        assertEquals(
            0.0,
            after.cells[f.notesCell]!!.priorityWeights[0],
            "a factor leaves a 0 a 0 — nothing may be added when multiplying suffices",
        )
        assertTrue(
            after.cells[f.notesCell]!!.priorityWeights[1] > before[1],
            "the reachable column is what moved",
        )
    }

    /** A pin holds a PERCENTAGE, and it goes on holding it across an addition just as across a factor. */
    @Test
    fun a_pinned_link_holds_its_percentage_while_the_rest_of_the_chain_is_added_to() {
        val f = twoColumnFixture()
        // Chapter sits under Book, which is itself at 0 in the second column: the chain needs an addition.
        var s = SchedulerReducer.reduce(f.state, SchedulerIntent.AddTaskCategory(f.chapter, "wide"))
        val wide = categoryNamed(s, "wide")
        val chapterCell = chainsOf(s, wide, ROOT, null).single().last()
        val heldBefore = RelativePriorityDomain.cellShare(s, chapterCell)

        s = RelativePriorityDomain.setChainsShare(
            s,
            chainsOf(s, wide, ROOT, null),
            0.5,
            pinned = setOf(chapterCell),
        )

        assertShare(0.5, shareOf(s, wide, ROOT, null), "the ask")
        assertShare(heldBefore, RelativePriorityDomain.cellShare(s, chapterCell), "the pinned link")
    }

    @Test
    fun the_notice_is_local_only_state_and_the_next_dismissal_clears_it() {
        val f = fixture()
        var s = SchedulerReducer.reduce(f.state, SchedulerIntent.AddTaskCategory(f.chapter, "deep"))
        val deep = categoryNamed(s, "deep")
        s = SchedulerReducer.reduce(s, SchedulerIntent.SetCategoryRule(deep, f.bookCell, null, 1.0))
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
        s = SchedulerReducer.reduce(s, SchedulerIntent.SetCategoryRule(deep, ROOT, null, 0.4))

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
        s = SchedulerReducer.reduce(s, SchedulerIntent.SetCategoryRule(deep, f.notesCell, null, 0.5))

        // Notes never held a carrier, so the rule was asleep to begin with...
        assertEquals(CategoryRules.Status.NoCarrier, CategoryRules.ruleRows(s, deep).single().status)

        s = SchedulerReducer.reduce(s, SchedulerIntent.SetCellTitle(f.notesCell, ""))
        assertEquals(CategoryRules.Status.ScopeGone, CategoryRules.ruleRows(s, deep).single().status)
        assertNull(s.categoryRuleError)
    }

    // ----- the scope is a CELL, because a task can appear several times ---------------------------

    /**
     * The task cell picker offers the whole tree ("root", the anomaly of 2026-10-03: it was missing) and every cell a
     * carrier sits under — ONLY those, so a rule cannot be written about a cell no carrier sits under — one task id
     * once per path it is reached by.
     */
    @Test
    fun the_task_cell_picker_offers_root_and_every_cell_a_carrier_sits_under_once_per_path() {
        val f = fixture()
        // Mirror Book under Notes: one more CELL of Book, so Chapter sits under Book along two paths.
        val mirrorCell = f.state.lists[f.state.tasks[f.notes]!!.childListId!!]!!.cellIds.last()
        var s = SchedulerReducer.reduce(f.state, SchedulerIntent.AssignTaskId(mirrorCell, f.book))
        s = SchedulerReducer.reduce(s, SchedulerIntent.AddTaskCategory(f.chapter, "deep"))
        val deep = categoryNamed(s, "deep")

        val labels = CategoryRules.taskCellEntries(s, listOf(deep), "").map { it.label }
        assertEquals(SchedulerDomain.ROOT_LABEL, labels.first(), "the whole tree first")
        assertEquals(setOf(SchedulerDomain.ROOT_LABEL, "Book", "Notes", "Notes / Book"), labels.toSet())
        assertTrue(labels.none { it.endsWith("Chapter") || it.endsWith("Read") }, "nothing no carrier sits under:\n$labels")
        assertEquals(listOf(null), CategoryRules.taskCellEntries(s, listOf(deep), "root").map { it.cellId }, "typing root finds it")
        // Each row is a task element with ITS path: Book twice, the same task id under two different paths.
        val books = CategoryRules.taskCellEntries(s, listOf(deep), "").filter { it.taskId == f.book }
        assertEquals(2, books.size)
        assertEquals(setOf(listOf(SchedulerDomain.ROOT_LABEL), listOf(SchedulerDomain.ROOT_LABEL, "Notes")), books.map { it.parentPath }.toSet())

        assertTrue(CategoryRules.distanceReached(s, listOf(deep), 2))
        assertTrue(!CategoryRules.distanceReached(s, listOf(deep), 4), "nothing is that deep")
    }

    @Test
    fun two_cells_of_one_mirrored_task_are_one_scope_so_a_rule_about_the_second_replaces_the_first() {
        val f = fixture()
        val mirrorCell = f.state.lists[f.state.tasks[f.notes]!!.childListId!!]!!.cellIds.last()
        var s = SchedulerReducer.reduce(f.state, SchedulerIntent.AssignTaskId(mirrorCell, f.book))
        s = SchedulerReducer.reduce(s, SchedulerIntent.AddTaskCategory(f.chapter, "deep"))
        val deep = categoryNamed(s, "deep")

        s = SchedulerReducer.reduce(s, SchedulerIntent.SetCategoryRule(deep, f.bookCell, null, 0.6))
        s = SchedulerReducer.reduce(s, SchedulerIntent.SetCategoryRule(deep, mirrorCell, null, 0.4))

        // A sub-list belongs to the task id, so both cells show ONE sub-tree: two rules about it would be
        // two statements about one thing, which is exactly what "at most one rule per scope" forbids.
        val rule = s.categoryById(deep)!!.rules.single()
        assertEquals(mirrorCell, rule.relativeToCellId)
        assertShare(0.4, rule.share, "the later rule is the one that stands")
        assertShare(0.4, shareOf(s, deep, f.bookCell, null), "read through either cell")
        assertShare(0.4, shareOf(s, deep, mirrorCell, null), "read through either cell")
    }

    @Test
    fun a_rule_sleeps_when_the_cell_it_names_goes_even_though_the_task_stays() {
        val f = fixture()
        val mirrorCell = f.state.lists[f.state.tasks[f.notes]!!.childListId!!]!!.cellIds.last()
        var s = SchedulerReducer.reduce(f.state, SchedulerIntent.AssignTaskId(mirrorCell, f.book))
        s = SchedulerReducer.reduce(s, SchedulerIntent.AddTaskCategory(f.chapter, "deep"))
        val deep = categoryNamed(s, "deep")
        s = SchedulerReducer.reduce(s, SchedulerIntent.SetCategoryRule(deep, mirrorCell, null, 0.5))
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
    fun a_rule_row_names_what_it_is_relative_to_by_the_cell_s_own_path() {
        val f = fixture()
        var s = SchedulerReducer.reduce(f.state, SchedulerIntent.AddTaskCategory(f.chapter, "deep"))
        val deep = categoryNamed(s, "deep")
        s = SchedulerReducer.reduce(s, SchedulerIntent.SetCategoryRule(deep, f.bookCell, null, 0.5))
        s = SchedulerReducer.reduce(s, SchedulerIntent.SetCategoryRule(deep, ROOT, 2, 0.25))
        assertEquals(listOf("of “Book”", "of the parent 2 levels up"), CategoryRules.ruleRows(s, deep).map { it.label })
    }

    // ----- persistence ----------------------------------------------------------------------------

    @Test
    fun categories_their_rules_and_the_ids_on_the_tasks_survive_a_round_trip() {
        val f = fixture()
        var s = SchedulerReducer.reduce(f.state, SchedulerIntent.AddTaskCategory(f.chapter, "deep"))
        s = SchedulerReducer.reduce(s, SchedulerIntent.AddTaskCategory(f.read, "deep"))
        val deep = categoryNamed(s, "deep")
        s = SchedulerReducer.reduce(s, SchedulerIntent.SetCategoryRule(deep, ROOT, null, 0.6))

        val decoded = SchedulerStateCodec.decodeSnapshot(SchedulerStateCodec.encodeSnapshot(s))
        assertNotNull(decoded)
        assertEquals(s.categories, decoded.categories)
        assertEquals(s.nextCategoryCounter, decoded.nextCategoryCounter)
        assertEquals(listOf(deep), decoded.tasks[f.chapter]!!.categoryIds)
        assertShare(0.6, shareOf(decoded, deep, ROOT, null), "the rule after a reload")
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

    /** A rule written before the switch (no distance) IS a task cell rule, and loads as one. */
    @Test
    fun a_rule_written_before_the_switch_loads_as_a_task_cell_rule() {
        val f = fixture()
        var s = SchedulerReducer.reduce(f.state, SchedulerIntent.AddTaskCategory(f.chapter, "deep"))
        val deep = categoryNamed(s, "deep")
        s = SchedulerReducer.reduce(s, SchedulerIntent.SetCategoryRule(deep, f.bookCell, null, 0.6))
        val previous = SchedulerStateCodec.encode(s)
        assertTrue(!previous.contains("\"distance\""), "a task cell rule writes no distance — the older shape:\n$previous")
        val decoded = assertNotNull(SchedulerStateCodec.decode(previous))
        assertEquals(CategoryRule(f.bookCell, null, 0.6), decoded.categoryById(deep)!!.rules.single())
        // …and a parent distance rule round-trips with its distance.
        s = SchedulerReducer.reduce(s, SchedulerIntent.SetCategoryRule(deep, null, 1, 0.4))
        val again = assertNotNull(SchedulerStateCodec.decodeSnapshot(SchedulerStateCodec.encodeSnapshot(s)))
        assertEquals(s.categoryById(deep)!!.rules.toSet(), again.categoryById(deep)!!.rules.toSet())
    }

    @Test
    fun a_payload_written_when_a_rule_s_scope_was_a_task_loads_with_that_task_s_first_cell() {
        val f = fixture()
        var s = SchedulerReducer.reduce(f.state, SchedulerIntent.AddTaskCategory(f.chapter, "deep"))
        val deep = categoryNamed(s, "deep")
        s = SchedulerReducer.reduce(s, SchedulerIntent.SetCategoryRule(deep, f.bookCell, null, 0.6))
        // The PREVIOUS shape, byte for byte: a rule named a task and knew nothing of cells.
        val previous = SchedulerStateCodec.encode(s).replace(Regex("\"scopeCellId\":\\s*\"[^\"]*\",\\s*"), "")
        assertTrue(previous.contains("\"scopeTaskId\""), "the older shape is what is being loaded:\n$previous")
        val decoded = assertNotNull(SchedulerStateCodec.decode(previous))
        assertEquals(f.bookCell, decoded.categories.single().rules.single().relativeToCellId)
        assertShare(0.6, shareOf(decoded, deep, f.bookCell, null), "the rule after the migration")
    }

    /** A category written before kinds existed is a task id one; a task cell category and its cells round-trip. */
    @Test
    fun the_kind_and_the_cells_carrying_a_task_cell_category_survive_a_round_trip() {
        val f = fixture()
        val chapterCell = f.state.cells.values.first { it.taskId == f.chapter }.id
        var s = SchedulerReducer.reduce(f.state, SchedulerIntent.AddCellCategory(chapterCell, "spot"))
        val spot = categoryNamed(s, "spot")
        val decoded = assertNotNull(SchedulerStateCodec.decodeSnapshot(SchedulerStateCodec.encodeSnapshot(s)))
        assertEquals(org.example.project.scheduler.model.CategoryKind.TaskCell, decoded.categoryById(spot)!!.kind)
        assertEquals(listOf(spot), decoded.cells[chapterCell]!!.categoryIds)

        val older = SchedulerStateCodec.encode(s).replace(Regex(",\\s*\"kind\":\\s*\"TaskCell\""), "")
        assertEquals(org.example.project.scheduler.model.CategoryKind.TaskId, SchedulerStateCodec.decode(older)!!.categoryById(spot)!!.kind)
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

    // ----- PRD §7 the categories window: the account's own list ----------------------------------

    @Test
    fun the_window_creates_a_category_no_task_carries() {
        val f = fixture()
        // The whole reason the window can create one: until it existed, a category could only be minted by
        // giving it to a task, so one carrying nothing could neither be made nor reached.
        val s = SchedulerReducer.reduce(f.state, SchedulerIntent.CreateCategory("  deep  "))

        assertEquals(1, s.categories.size)
        assertEquals("deep", s.categories.single().title, "the name is trimmed, as everywhere else")
        assertTrue(
            s.tasks.values.none { it.categoryIds.isNotEmpty() },
            "creating a category attaches it to nothing",
        )
    }

    @Test
    fun creating_a_category_under_a_name_the_account_already_holds_changes_nothing() {
        val f = fixture()
        val s = SchedulerReducer.reduce(f.state, SchedulerIntent.AddTaskCategory(f.chapter, "deep"))
        // Same create-or-attach rule as the task cell's field, minus the attach half: the account is a set
        // of named objects, and the id exists precisely so one name never means two of them.
        val again = SchedulerReducer.reduce(s, SchedulerIntent.CreateCategory("DEEP"))

        assertEquals(1, again.categories.size)
        assertEquals(s, again, "a name already held is a no-op, not a second category")
        assertEquals(s, SchedulerReducer.reduce(s, SchedulerIntent.CreateCategory("   ")))
    }

    @Test
    fun creating_a_category_is_a_unit_and_undoing_it_takes_it_away() {
        val f = fixture()
        // User rule 2026-10-01: a History Unit for almost every user action — an account setting included.
        val s = SchedulerReducer.reduce(f.state, SchedulerIntent.CreateCategory("deep"))
        val main = { st: SchedulerState -> st.histories.forCategory(HistoryCategory.Main).units.size }
        assertEquals(main(f.state) + 1, main(s))
        val undone = SchedulerReducer.reduce(s, SchedulerIntent.Undo)
        assertTrue(undone.categories.none { it.title == "deep" })
        assertTrue(SchedulerReducer.reduce(undone, SchedulerIntent.Redo).categories.any { it.title == "deep" })
    }

    @Test
    fun the_window_lists_every_category_in_title_order_with_what_it_is_doing() {
        val f = fixture()
        var s = SchedulerReducer.reduce(f.state, SchedulerIntent.CreateCategory("zeal"))
        s = SchedulerReducer.reduce(s, SchedulerIntent.AddTaskCategory(f.chapter, "deep"))
        s = SchedulerReducer.reduce(s, SchedulerIntent.AddTaskCategory(f.read, "deep"))
        val deep = categoryNamed(s, "deep")
        s = SchedulerReducer.reduce(s, SchedulerIntent.SetCategoryRule(deep, null, null, 0.5))

        val rows = CategoryRules.overview(s)
        // Title order, not minting order — the list is where a category is found by the name it is known by.
        assertEquals(listOf("deep", "zeal"), rows.map { it.category.title })
        assertEquals(2, rows[0].carriers)
        assertEquals(1, rows[0].rules.size)
        assertEquals(0, rows[0].dormant)
        // A category carried by nothing is still a row: that is exactly the one the tree cannot show.
        assertEquals(0, rows[1].carriers)
        assertEquals(emptyList(), rows[1].rules)
    }

    @Test
    fun the_window_counts_a_rule_that_governs_nothing_as_asleep() {
        val f = fixture()
        var s = SchedulerReducer.reduce(f.state, SchedulerIntent.AddTaskCategory(f.chapter, "deep"))
        val deep = categoryNamed(s, "deep")
        s = SchedulerReducer.reduce(s, SchedulerIntent.SetCategoryRule(deep, f.notesCell, null, 0.5))

        // The rule is about Notes' sub-tree, which nothing under it carries — dormant, not contradictory.
        val row = CategoryRules.overview(s).single { it.category.id == deep }
        assertEquals(1, row.rules.size)
        assertEquals(1, row.dormant)
        assertEquals(CategoryRules.Status.NoCarrier, row.rules.single().status)
    }
}
