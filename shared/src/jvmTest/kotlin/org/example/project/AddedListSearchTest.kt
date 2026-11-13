package org.example.project

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.unit.Density
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.example.project.quota.QuotaScenario
import org.example.project.scheduler.domain.SearchDomain
import org.example.project.scheduler.domain.SearchDomain.Kind
import org.example.project.ui.ADDED_LIST_SEARCH
import org.example.project.ui.AddedActionHandlers
import org.example.project.ui.SearchRowOpeners
import org.example.project.ui.SearchSplits
import org.example.project.ui.SearchWindow
import org.example.project.ui.addedListElements
import org.example.project.ui.withAddedListElements

/**
 * User rule 2026-11-13: *"Make the 'added elements' list also a Search window."* The list is the very [SearchWindow],
 * drawn inside the window whose added elements it lists.
 */
class AddedListSearchTest {
    private fun <T> onEdt(block: () -> T): T {
        var result: Result<T>? = null
        javax.swing.SwingUtilities.invokeAndWait { result = runCatching(block) }
        return result!!.getOrThrow()
    }

    /** Draws a Search window holding [added], its sections as [splits]; the splits it told, and its picture's bytes. */
    private fun draw(added: List<String>, splits: SearchSplits = SearchSplits(), width: Int = 1200, height: Int = 700): Pair<List<SearchSplits>, ByteArray> {
        val account = QuotaScenario.account()
        val told = ArrayList<SearchSplits>()
        val scene = onEdt {
            ImageComposeScene(width, height, density = Density(1f)) {
                var config by remember { mutableStateOf(SearchDomain.Config(kinds = setOf(Kind.Task, Kind.Quota), added = added)) }
                var shown by remember { mutableStateOf(splits) }
                SearchWindow(
                    state = account,
                    openers = SearchRowOpeners(
                        onOpenTaskEdit = {}, onOpenCategory = {}, onOpenPeriodKind = {}, onEditAlarmOrTimer = {}, onEditReminder = {},
                    ),
                    onStartTaskNow = {},
                    onGoToTaskTree = { _, _ -> },
                    onDeepCopyCell = {},
                    actionHandlers = AddedActionHandlers(
                        onStartNow = {}, onEdit = {}, onGoToTaskTree = {}, onDeepCopyCell = {}, onOpenResilienceSearch = {},
                        onOpenKindSearch = {}, timerRun = { _, _ -> }, reminderEditor = {}, onCreate = {},
                        onDuplicate = { _, _ -> emptyList() }, onPlaceOnCalendar = {},
                    ),
                    onIntent = {},
                    onDismiss = {},
                    config = config,
                    onConfigChange = { config = it },
                    onOpenConfigurations = {},
                    splits = shown,
                    onSplitsChange = { shown = it; told += it },
                    initialSize = Size(width.toFloat(), height.toFloat()),
                )
            }
        }
        try {
            var bytes = ByteArray(0)
            repeat(4) { onEdt { bytes = scene.render().encodeToData()!!.bytes } }
            return told.toList() to bytes
        } finally {
            onEdt { scene.close() }
        }
    }

    private val keys: List<String> by lazy {
        SearchDomain.results(QuotaScenario.account(), setOf(Kind.Task, Kind.Quota), "").map(SearchDomain::keyOf)
    }

    @Test
    fun the_list_is_drawn_with_its_elements_and_changes_what_the_window_shows() {
        assertTrue(keys.size >= 3, "the account has elements to add: $keys")
        val (_, none) = draw(emptyList())
        val (_, some) = draw(keys.take(3))
        assertTrue(!none.contentEquals(some), "three added elements are drawn")
        // Narrow and short, as a window resized to its least: laid out, never thrown.
        draw(keys.take(3), width = 420, height = 300)
        // Every element of the account at once: the list is lazy, and scrolls.
        draw(keys)
    }

    @Test
    fun the_list_keeps_the_open_rows_and_the_scroll_of_the_window_it_is_drawn_in() {
        val task = keys.first { it.startsWith(Kind.Task.name + "/") }.substringAfter('/')
        val element = "added/" + keys.first { !it.startsWith(Kind.Task.name + "/") }
        val opened = SearchSplits(expandedAdded = setOf(task), expandedElements = setOf(element, "result/kept"))
        val (told, _) = draw(keys.take(6), opened)
        val last = told.last()
        // What the list was given comes back as it was: nothing of the window's own is lost on the way.
        assertEquals(setOf(task), last.expandedAdded)
        assertEquals(setOf(element, "result/kept"), last.expandedElements)
    }

    @Test
    fun the_open_rows_are_named_by_the_list_as_any_search_window_names_its_own() {
        val window = setOf("result/Timer/a", "added/Timer/b", "added/Quota/c")
        assertEquals(setOf("result/Timer/b", "result/Quota/c"), addedListElements(window))
        // The list closed one and opened another: the window's own rows are untouched.
        assertEquals(
            setOf("result/Timer/a", "added/Timer/b", "added/Alarm/d"),
            withAddedListElements(window, setOf("result/Timer/b", "result/Alarm/d")),
        )
        assertEquals(window, withAddedListElements(window, addedListElements(window)))
    }

    @Test
    fun the_list_opens_on_every_kind_with_a_drop_button_on_each_row() {
        assertEquals(Kind.entries.toSet(), ADDED_LIST_SEARCH.kinds)
        assertTrue(ADDED_LIST_SEARCH.dropButtons)
        assertEquals("", ADDED_LIST_SEARCH.query)
    }
}
