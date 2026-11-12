package org.example.project

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.example.project.scheduler.domain.SchedulerDomain
import org.example.project.scheduler.domain.SearchDomain
import org.example.project.scheduler.model.CellId
import org.example.project.scheduler.model.TaskId
import org.example.project.scheduler.state.EditExitNavigation
import org.example.project.scheduler.state.SchedulerIntent
import org.example.project.scheduler.state.SchedulerReducer
import org.example.project.scheduler.state.SchedulerState
import org.example.project.ui.cellEditPopupOffset

/** User rule 2026-11-02: the rectangle of a task cell in Edit Mode, and the search bar's completion list. */
class CellEditPopupRulesTest {
    private val popup = IntSize(300, 200)
    private val room = IntSize(1000, 800)

    @Test
    fun the_rectangle_stands_below_the_cell() {
        assertEquals(IntOffset(100, 130), cellEditPopupOffset(Rect(100f, 100f, 250f, 130f), popup, room))
    }

    @Test
    fun above_when_there_is_not_enough_room_below() {
        assertEquals(IntOffset(100, 500), cellEditPopupOffset(Rect(100f, 700f, 250f, 730f), popup, room))
    }

    @Test
    fun on_the_left_when_neither_below_nor_above_fit() {
        val tall = IntSize(300, 500)
        assertEquals(IntOffset(300, 300), cellEditPopupOffset(Rect(600f, 400f, 750f, 430f), tall, IntSize(1000, 800)))
    }

    @Test
    fun on_the_right_when_the_left_has_no_room_either() {
        val tall = IntSize(300, 500)
        assertEquals(IntOffset(250, 300), cellEditPopupOffset(Rect(100f, 400f, 250f, 430f), tall, IntSize(1000, 800)))
    }

    @Test
    fun fitting_nowhere_it_stands_below_as_far_as_the_room_lets_it() {
        assertEquals(IntOffset(0, 0), cellEditPopupOffset(Rect(10f, 100f, 90f, 130f), IntSize(100, 200), IntSize(100, 200)))
    }

    private fun r(state: SchedulerState, intent: SchedulerIntent) = SchedulerReducer.reduce(state, intent)

    private class Fixture(val state: SchedulerState, val apple: CellId, val pear: TaskId, val kiwi: TaskId)

    /** Apple and Pear at the top level, Kiwi under Pear. */
    private fun fixture(): Fixture {
        var s = SchedulerState.empty()
        val apple = s.lists[s.rootListId]!!.cellIds.last()
        s = r(s, SchedulerIntent.SetCellTitle(apple, "Apple"))
        val pearCell = s.lists[s.rootListId]!!.cellIds.last()
        s = r(s, SchedulerIntent.SetCellTitle(pearCell, "Pear"))
        val pear = s.cells[pearCell]!!.taskId!!
        val kiwiCell = s.lists[s.tasks[pear]!!.childListId!!]!!.cellIds.last()
        s = r(s, SchedulerIntent.SetCellTitle(kiwiCell, "Kiwi"))
        return Fixture(s, apple, pear, s.cells[kiwiCell]!!.taskId!!)
    }

    @Test
    fun the_list_is_what_the_reducer_would_take() {
        val f = fixture()
        val assignable = SchedulerDomain.assignableTaskIds(f.state, f.apple)
        assertTrue(f.kiwi in assignable)
        assertFalse(f.pear in assignable, "a task of the cell's own list")
        for (task in f.state.tasks.keys) {
            assertEquals(SchedulerDomain.canAssignTaskId(f.state, f.apple, task), task in assignable, task.value)
        }
    }

    @Test
    fun a_pick_replaces_the_task_and_leaves_edit_mode() {
        val f = fixture()
        var s = r(f.state, SchedulerIntent.BeginEdit(f.apple, initialText = "K"))
        s = r(s, SchedulerIntent.PickTaskFromMenu(f.kiwi))
        s = r(s, SchedulerIntent.ExitEdit(EditExitNavigation.Stay))
        assertNull(s.editSession)
        assertEquals(f.kiwi, s.cells[f.apple]!!.taskId)
    }

    @Test
    fun the_completion_list_is_off_by_default() {
        assertNull(SearchDomain.Config().completion)
        assertEquals(emptyList(), SearchDomain.completionOptions(fixture().state, null, ""))
    }

    @Test
    fun an_option_is_listed_once_and_never_the_text_of_the_bar() {
        var s = fixture().state
        // A second task of the same title: one option.
        s = r(s, SchedulerIntent.SetCellTitle(s.lists[s.rootListId]!!.cellIds.last(), "Kiwi"))
        val all = SearchDomain.completionOptions(s, SearchDomain.Completion.TaskTitles, "")
        assertEquals(all.distinct(), all)
        assertEquals(1, all.count { it == "Kiwi" })
        val typed = SearchDomain.completionOptions(s, SearchDomain.Completion.TaskTitles, "kiwi")
        assertFalse(typed.any { it.equals("kiwi", ignoreCase = true) })
        assertEquals(listOf("Apple"), SearchDomain.completionOptions(s, SearchDomain.Completion.TaskTitles, "ppl"))
    }

    @Test
    fun a_configuration_stored_before_the_list_has_none_and_the_new_one_comes_back() {
        assertNull(SearchDomain.Config.decode("""{"query":"x"}""")!!.completion)
        val on = SearchDomain.Config(completion = SearchDomain.Completion.TaskTitles)
        assertEquals(SearchDomain.Completion.TaskTitles, SearchDomain.Config.decode(on.encode())!!.completion)
    }
    /**
     * User rule 2026-11-04: the Edit Mode's Search window lists task elements alone, sorted by their title, with the
     * creation task element first — whatever is typed — and no "New task" row of its own.
     */
    @Test
    fun the_list_is_the_creation_row_then_the_tasks_by_title() {
        val f = fixture()
        val eligible = SchedulerDomain.assignableTaskIds(f.state, f.apple)
        val found = SearchDomain.results(f.state, setOf(SearchDomain.Kind.Task), "", sorts = SearchDomain.PICKER_SORTS)
        val rows = SearchDomain.pickerRows(f.state, found, eligible, creation = true)
        assertEquals(SearchDomain.Kind.Creation, rows.first().kind)
        assertEquals(SearchDomain.Kind.Task.name, (rows.first() as SearchDomain.ItemResult).id)
        val titles = rows.drop(1).map { it.name }
        assertTrue(rows.drop(1).all { it is SearchDomain.TaskResult && it.taskId in eligible })
        assertEquals(titles.sortedBy { it.lowercase() }, titles, "by title")
        // Typed text the creation row's name does not hold: still first.
        val typed = SearchDomain.results(f.state, setOf(SearchDomain.Kind.Task), "Ki", sorts = SearchDomain.PICKER_SORTS)
        assertEquals(listOf("New task", "Kiwi"), SearchDomain.pickerRows(f.state, typed, eligible, creation = true).map { it.name })
        // Rename: no creation row.
        assertTrue(SearchDomain.pickerRows(f.state, found, eligible, creation = false).none { it.kind == SearchDomain.Kind.Creation })
    }

    /**
     * User rule 2026-11-04: the search bar's match case and match whole word are FILTERS: an element that does not match
     * with them is no result, and only what matches with them is highlighted.
     */
    @Test
    fun match_case_and_whole_word_are_filters_of_the_results_and_the_highlights() {
        val f = fixture()
        fun titles(query: String, filters: SearchDomain.Filters) =
            SearchDomain.results(f.state, setOf(SearchDomain.Kind.Task), query, filters = filters, match = filters.queryOptions)
                .filterIsInstance<SearchDomain.TaskResult>().map { it.title }.toSet()
        val plain = SearchDomain.Filters()
        val case = plain.copy(matchCase = true)
        val word = plain.copy(wholeWord = true)
        assertEquals(setOf("Pear"), titles("pear", plain))
        assertEquals(emptySet(), titles("pear", case))
        assertEquals(setOf("Pear"), titles("Pear", case))
        assertEquals(setOf("Pear"), titles("pear", word))
        assertEquals(emptySet(), titles("pea", word))
        assertEquals(setOf("Pear"), titles("pea", plain))
        // Filters: counted with the others, and listed among the filters that are on.
        assertEquals(0, plain.activeCount)
        assertEquals(2, plain.copy(matchCase = true, wholeWord = true).activeCount)
        assertTrue(case.isOn(SearchDomain.Setting.MatchCase) && word.isOn(SearchDomain.Setting.WholeWord))
        // Highlighted: only what matches with them.
        assertEquals(listOf(0..3, 9..12), SearchDomain.matchRanges("Pear and pear", "pear"))
        assertEquals(listOf(9..12), SearchDomain.matchRanges("Pear and pear", "pear", case.queryOptions))
        assertEquals(listOf(10..12), SearchDomain.matchRanges("Pearl and pea", "pea", word.queryOptions))
        // Anomaly 2026-11-04: both on, "A at " highlighted the lone "A" of "A run takes…": the query is one phrase.
        val both = plain.copy(matchCase = true, wholeWord = true).queryOptions
        assertEquals(emptyList(), SearchDomain.matchRanges("A run takes the state of the line at c", "A at ", both))
        assertEquals(listOf(9..12), SearchDomain.matchRanges("Look for A at noon", "A at ", both))
        assertFalse(SearchDomain.matchesWith(SearchDomain.ItemResult(SearchDomain.Kind.Alarm, "a", "A run at", ""), "A at ", both))
        assertTrue(SearchDomain.matchesWith(SearchDomain.ItemResult(SearchDomain.Kind.Alarm, "a", "Wake A at six", ""), "A at ", both))
        // Kept with the window's configuration, and absent (off) from one stored before them.
        val on = SearchDomain.Config(filters = plain.copy(matchCase = true, wholeWord = true))
        assertEquals(on, SearchDomain.Config.decode(on.encode()))
        val older = SearchDomain.Config().encode()
        assertFalse("matchCase" in older || "wholeWord" in older, "off writes nothing: the shape before them")
        assertEquals(SearchDomain.Config(), SearchDomain.Config.decode(older))
    }
    /**
     * User rule 2026-11-04: the edited cell has the configurations arrow after its cross; what it sets is the Edit Mode's
     * Search window's, kept from one Edit Mode to the next, the text being the cell's own.
     */
    @Test
    fun the_edit_modes_search_keeps_its_configuration_and_the_text_is_the_cells() {
        val host = org.example.project.ui.CellEditPopupHost()
        assertEquals(SearchDomain.PICKER_SORTS, host.withDraft("Ap").sorts, "by title by default")
        assertEquals("Ap", host.withDraft("Ap").query)
        val drafts = mutableListOf<String>()
        val filtered = host.withDraft("Ap").copy(filters = SearchDomain.Filters(matchCase = true))
        host.change(filtered, "Ap") { drafts += it }
        assertEquals(emptyList(), drafts, "the text unchanged: nothing written to the cell")
        assertTrue(host.withDraft("Pe").filters.matchCase, "kept for the next Edit Mode")
        assertEquals("", host.searchConfig.query, "the text is never kept: it is the cell's")
        host.change(host.withDraft("Ap").copy(query = "Apple"), "Ap") { drafts += it }
        assertEquals(listOf("Apple"), drafts, "a new text goes to the cell")
    }
}
