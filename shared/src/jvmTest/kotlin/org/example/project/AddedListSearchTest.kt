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
import org.example.project.ui.transientMenuDismissRoot
import androidx.compose.ui.semantics.getAllSemanticsNodes
import androidx.compose.ui.semantics.getOrNull

/**
 * User rule 2026-11-13: *"Make the 'added elements' list also a Search window."* The list is the very [SearchWindow],
 * drawn inside the window whose added elements it lists.
 */
class AddedListSearchTest {

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

    /**
     * Anomaly 2026-11-14: *"Sorting and filters from the ▾ of the added elements list do nothing."* The list's rows are
     * given, and answer its configuration as a searched list does ([SearchDomain.results]' `given`).
     */
    @Test
    fun the_given_rows_answer_the_kinds_the_bar_the_filters_and_the_sorting() {
        val account = QuotaScenario.account()
        // Not in the order a search lists them: the order added is the list's own.
        val added = keys.reversed()
        val rows = SearchDomain.resolve(account, added, { emptyMap() })
        fun listed(config: SearchDomain.Config) =
            SearchDomain.results(account, config.searchedKinds, config.query, { emptyMap() }, config.filters, config.sorts, given = rows)
                .map(SearchDomain::keyOf)
        assertEquals(added, listed(ADDED_LIST_SEARCH), "as it opens: every element, in the order added")
        // A kind unchecked leaves the list.
        val kind = rows.first().kind
        val ofKind = listed(ADDED_LIST_SEARCH.copy(kinds = setOf(kind)))
        assertEquals(rows.filter { it.kind == kind }.map(SearchDomain::keyOf), ofKind)
        assertTrue(ofKind.isNotEmpty())
        assertEquals(added - ofKind.toSet(), listed(ADDED_LIST_SEARCH.copy(kinds = Kind.entries.toSet() - kind)))
        // The bar keeps the names answering it, and offers to make nothing.
        val name = rows.first().name
        assertTrue(SearchDomain.keyOf(rows.first()) in listed(ADDED_LIST_SEARCH.copy(query = name)))
        assertEquals(emptyList(), listed(ADDED_LIST_SEARCH.copy(query = "no element is named so")))
        // A sorting method sorts.
        val byName = listed(ADDED_LIST_SEARCH.copy(sorts = listOf(SearchDomain.SortMethod(null, SearchDomain.SortKey.Name))))
        assertEquals(rows.sortedBy { it.name.lowercase() }.map { it.name }, byName.map { key -> rows.first { SearchDomain.keyOf(it) == key }.name })
        // A filter filters: an excluded element leaves.
        val without = listed(ADDED_LIST_SEARCH.copy(filters = ADDED_LIST_SEARCH.filters.copy(excludedElements = setOf(added.first()))))
        assertEquals(added.drop(1), without)
    }

    @Test
    fun the_list_opens_on_every_kind_with_a_drop_button_on_each_row() {
        assertEquals(Kind.entries.toSet(), ADDED_LIST_SEARCH.kinds)
        assertTrue(ADDED_LIST_SEARCH.dropButtons)
        assertEquals("", ADDED_LIST_SEARCH.query)
    }

    /**
     * Anomaly 2026-11-15 (*"right-click on the parent in the list, select 'add' … but the added elements list is empty"*):
     * the global root, listed by a "list of parents", was added and named by nothing. Every row a list shows is named by
     * its key ([SearchDomain.resolve] reads the rows [SearchDomain.kindRows] gives the search).
     */
    @Test
    fun every_row_a_list_shows_is_named_by_its_key_the_global_root_included() {
        var s = org.example.project.scheduler.state.SchedulerState.empty()
        val cell = s.lists[s.rootListId]!!.cellIds.last()
        s = org.example.project.scheduler.state.SchedulerReducer.reduce(s, org.example.project.scheduler.state.SchedulerIntent.SetCellTitle(cell, "Top"))
        val top = SearchDomain.taskKey(s.tasks.values.first { it.title == "Top" }.id)
        val list = SearchDomain.parentsListConfig(listOf(top))
        val parents = SearchDomain.results(s, list.searchedKinds, "", { emptyMap() }, list.filters, list.sorts).map(SearchDomain::keyOf)
        val root = SearchDomain.taskKey(org.example.project.scheduler.model.WellKnownIds.ROOT_TASK)
        assertTrue(root in parents, "the list of parents of a task under the root lists the root: $parents")
        assertEquals(parents, SearchDomain.resolve(s, parents, { emptyMap() }).map(SearchDomain::keyOf), "added, each is named")
        // The whole account, every kind: what a search lists, its added elements can hold.
        val account = QuotaScenario.account()
        val all = SearchDomain.results(account, Kind.entries.toSet() - Kind.Creation, "", { emptyMap() }, withRoot = true).map(SearchDomain::keyOf)
        assertEquals(all, SearchDomain.resolve(account, all, { emptyMap() }).map(SearchDomain::keyOf))
    }

    /**
     * User rule 2026-11-15: *"Every selected row must appear in the added elements list. If a row is not selected anymore
     * and the user didn't right-click it and select add or add and remove others, then it is removed from the added
     * elements list."*
     */
    @OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class, androidx.compose.ui.InternalComposeUiApi::class)
    @Test
    fun a_selected_row_is_in_the_added_elements_while_it_is_selected() {
        val account = QuotaScenario.account().copy(
            chronos = listOf(org.example.project.scheduler.model.ChronoEntry(id = "a", label = "Clock A"), org.example.project.scheduler.model.ChronoEntry(id = "b", label = "Clock B")),
        )
        var config = SearchDomain.Config(kinds = setOf(Kind.Chrono))
        val menus = org.example.project.ui.TransientMenuHost()
        val scene = onEdt {
            ImageComposeScene(1200, 700, density = Density(1f)) {
                var shown by remember { mutableStateOf(config) }
                androidx.compose.runtime.CompositionLocalProvider(org.example.project.ui.LocalTransientMenuHost provides menus) {
                    androidx.compose.foundation.layout.Box(androidx.compose.ui.Modifier.transientMenuDismissRoot(menus)) {
                        SearchWindow(
                            state = account,
                            openers = SearchRowOpeners(onOpenTaskEdit = {}, onOpenCategory = {}, onOpenPeriodKind = {}, onEditAlarmOrTimer = {}, onEditReminder = {}),
                            onStartTaskNow = {}, onGoToTaskTree = { _, _ -> }, onDeepCopyCell = {},
                            actionHandlers = AddedActionHandlers(
                                onStartNow = {}, onEdit = {}, onGoToTaskTree = {}, onDeepCopyCell = {}, onOpenResilienceSearch = {},
                                onOpenKindSearch = {}, timerRun = { _, _ -> }, reminderEditor = {}, onCreate = {},
                                onDuplicate = { _, _ -> emptyList() }, onPlaceOnCalendar = {},
                            ),
                            onIntent = {}, onDismiss = {}, config = shown,
                            onConfigChange = { shown = it; config = it }, onOpenConfigurations = {},
                            initialSize = Size(1200f, 700f),
                        )
                    }
                }
            }
        }
        var time = 0L
        fun frames(n: Int = 6) = repeat(n) { onEdt { time += 16L; scene.render(time * 1_000_000L) } }
        fun nodes() = onEdt { scene.semanticsOwners.flatMap { it.getAllSemanticsNodes(mergingEnabled = false) } }
        fun texts(): List<String> = nodes().mapNotNull { n -> n.config.getOrNull(androidx.compose.ui.semantics.SemanticsProperties.Text)?.joinToString { it.text } }
        fun at(text: String) = nodes().first { n -> n.config.getOrNull(androidx.compose.ui.semantics.SemanticsProperties.Text)?.any { it.text == text } == true }.boundsInWindow.center
        fun press(at: androidx.compose.ui.geometry.Offset, secondary: Boolean = false) {
            val buttons = if (secondary) androidx.compose.ui.input.pointer.PointerButtons(isSecondaryPressed = true) else androidx.compose.ui.input.pointer.PointerButtons(isPrimaryPressed = true)
            val button = if (secondary) androidx.compose.ui.input.pointer.PointerButton.Secondary else androidx.compose.ui.input.pointer.PointerButton.Primary
            onEdt {
                time += 1_000L
                scene.sendPointerEvent(androidx.compose.ui.input.pointer.PointerEventType.Move, at, timeMillis = time)
                scene.sendPointerEvent(androidx.compose.ui.input.pointer.PointerEventType.Press, at, timeMillis = time, buttons = buttons, button = button)
                scene.sendPointerEvent(androidx.compose.ui.input.pointer.PointerEventType.Release, at, timeMillis = time + 30, button = button)
            }
            frames()
        }
        fun count(): String? = texts().firstOrNull { it.startsWith("Added elements") }
        try {
            frames()
            val names = listOf("Clock A", "Clock B")
            assertEquals("Added elements", count(), "nothing selected, nothing added")
            press(at(names[0]))
            assertEquals("Added elements  ·  1", count(), "the selected row is listed: ${texts()}")
            // Another row selected: the first, never added, leaves.
            press(at(names[1]))
            assertEquals("Added elements  ·  1", count())
            assertTrue(config.added.isEmpty(), "selecting adds nothing to the configuration")
            // "add" keeps it once it is no longer selected.
            press(at(names[1]), secondary = true)
            press(at("add"))
            assertEquals(1, config.added.size, "added: ${config.added}")
            press(at(names[0]))
            assertEquals("Added elements  ·  2", count(), "the added row and the selected one: ${texts()}")
        } finally {
            onEdt { scene.close() }
        }
    }
}

