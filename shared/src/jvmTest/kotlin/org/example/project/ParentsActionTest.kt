package org.example.project

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerButtons
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getAllSemanticsNodes
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.Density
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.example.project.scheduler.domain.SearchDomain
import org.example.project.scheduler.domain.SearchDomain.Kind
import org.example.project.scheduler.model.ChronoEntry
import org.example.project.scheduler.state.SchedulerIntent
import org.example.project.scheduler.state.SchedulerReducer
import org.example.project.scheduler.state.SchedulerState
import org.example.project.ui.AddedActionHandlers
import org.example.project.ui.EMBEDDED_SEARCH_SPLITS
import org.example.project.ui.FieldSearchPopupHost
import org.example.project.ui.FieldSearchPopupLayer
import org.example.project.ui.LocalEmbeddedSearch
import org.example.project.ui.LocalFieldSearchPopupHost
import org.example.project.ui.SearchRowOpeners
import org.example.project.ui.SearchWindow
import org.example.project.ui.raiseOnPress
import org.example.project.ui.transientMenuDismissRoot

/**
 * Anomaly 2026-11-13: *"I clicked New task 2 in add a parent, then clicked list of parents and the list was empty."* Drawn
 * off screen in the real Search window: an element added, its "Parents" opened, a task picked in "add a parent" — then
 * "list of parents" lists it.
 */
@OptIn(ExperimentalComposeUiApi::class, androidx.compose.ui.InternalComposeUiApi::class)
class ParentsActionTest {

    private val openers = SearchRowOpeners(onOpenTaskEdit = {}, onOpenCategory = {}, onOpenPeriodKind = {}, onEditAlarmOrTimer = {}, onEditReminder = {})
    private val handlers =
        AddedActionHandlers(
            onStartNow = {}, onEdit = {}, onGoToTaskTree = {}, onDeepCopyCell = {}, onOpenResilienceSearch = {},
            onOpenKindSearch = {}, timerRun = { _, _ -> }, reminderEditor = {}, onCreate = {},
            onDuplicate = { _, _ -> emptyList() }, onPlaceOnCalendar = {},
        )

    private fun account(): SchedulerState {
        var s = SchedulerState.empty().copy(chronos = listOf(ChronoEntry(id = "a", label = "Clock")))
        for (title in listOf("New task 1", "New task 2")) {
            val cell = s.lists[s.rootListId]!!.cellIds.last()
            s = SchedulerReducer.reduce(s, SchedulerIntent.SetCellTitle(cell, title))
        }
        return s
    }

    private fun checkParentListed(
        listFirst: Boolean = false,
        thenAdd: Boolean = false,
        nested: Boolean = false,
        header: Boolean = false,
        child: (SchedulerState) -> String,
    ) {
        val start = account()
        val childKey = child(start)
        var live = start
        val added = SearchDomain.resolve(start, listOf(childKey), { emptyMap() })
        val sections = SearchDomain.actionsFor(SearchDomain.addedActions("", Kind.entries.toSet(), SearchDomain.actionKindsOf(added)), added)
        val base = SearchDomain.Config(kinds = setOf(Kind.Chrono), added = listOf(childKey), actionsExpanded = true, actionSearch = "parents")
        val group = SearchDomain.shownActionGroups(sections, added, base).first { SearchDomain.AddedAction.ElementParents in it.actions }
        val opened = base.copy(toggledActionRows = setOf(SearchDomain.microGroupId(group, SearchDomain.AddedAction.ElementParents)))
        val popups = FieldSearchPopupHost()
        // What `App` puts around every window: the one outside-press observer and the frames' focus, a press on the
        // background dropping it ([org.example.project.ui.WindowFrameHost.blur]).
        val menus = org.example.project.ui.TransientMenuHost()
        val frames = org.example.project.ui.WindowFrameHost()
        // What `App` does with a pop-up becoming a window: here, what it was asked.
        val detached = ArrayList<Pair<SearchDomain.Config, org.example.project.ui.DetachedAs>>()
        val detacher = org.example.project.ui.PopupDetacher(
            canDetach = { false },
            detach = { _, _, _ -> },
            detachSearch = { config, _, how -> detached += config to how },
        )
        val scene = onEdt {
            ImageComposeScene(1400, 900, density = Density(1f)) {
                var state by remember { mutableStateOf(start) }
                var config by remember { mutableStateOf(opened) }
                // What `App` keeps the windows drawn inside this one with: its configuration's `inside`.
                val registry = remember {
                    org.example.project.ui.ArrowSearches(read = { config.inside }, write = { _, inside -> config = config.copy(inside = inside) })
                }
                val onIntent = { intent: SchedulerIntent ->
                    state = SchedulerReducer.reduce(state, intent)
                    live = state
                }
                val embedded: @androidx.compose.runtime.Composable (org.example.project.ui.EmbeddedSearchRequest, Modifier) -> Unit = { request, modifier ->
                    SearchWindow(
                        state = state, openers = openers, onStartTaskNow = {}, onGoToTaskTree = { _, _ -> }, onDeepCopyCell = {},
                        actionHandlers = handlers, onIntent = onIntent, onDismiss = {}, config = request.config,
                        onConfigChange = request.onConfigChange, onOpenConfigurations = {}, splits = EMBEDDED_SEARCH_SPLITS,
                        embedded = true, onDrop = request.onDrop, canDrop = request.canDrop, picker = request.picker,
                        barOutside = request.barOutside, modifier = modifier,
                    )
                }
                CompositionLocalProvider(
                    LocalFieldSearchPopupHost provides popups,
                    org.example.project.ui.LocalTransientMenuHost provides menus,
                    org.example.project.ui.LocalWindowFrameHost provides frames,
                    org.example.project.ui.LocalPopupDetacher provides detacher,
                ) {
                    Box(Modifier.fillMaxSize().transientMenuDismissRoot(menus).raiseOnPress { frames.blur() }) {
                        // As `App` gives it: around the window alone — the pop-ups' layer stands outside it, and its
                        // fields' pop-ups carry it on (anomaly 2026-11-15).
                        CompositionLocalProvider(
                            LocalEmbeddedSearch provides embedded,
                            org.example.project.ui.LocalArrowSearches provides registry,
                            org.example.project.ui.LocalArrowOwner provides "Search",
                            org.example.project.ui.LocalArrowRoot provides "Search",
                        ) {
                        SearchWindow(
                            state = state, openers = openers, onStartTaskNow = {}, onGoToTaskTree = { _, _ -> }, onDeepCopyCell = {},
                            actionHandlers = handlers, onIntent = onIntent, onDismiss = {}, config = config,
                            onConfigChange = { config = it }, onOpenConfigurations = {}, initialSize = Size(1400f, 900f),
                        )
                        }
                        FieldSearchPopupLayer(popups)
                    }
                }
            }
        }
        var time = 0L
        fun frames(n: Int) = repeat(n) { onEdt { time += 16L; scene.render(time * 1_000_000L) } }
        fun nodes(): List<SemanticsNode> = onEdt { scene.semanticsOwners.flatMap { it.getAllSemanticsNodes(mergingEnabled = false) } }
        fun texts(): List<String> = nodes().mapNotNull { n -> n.config.getOrNull(SemanticsProperties.Text)?.joinToString { it.text } }
        fun find(text: String, first: Boolean = false): Offset =
            (if (first) nodes().first { n -> n.config.getOrNull(SemanticsProperties.Text)?.any { it.text == text } == true } else nodes().last { n -> n.config.getOrNull(SemanticsProperties.Text)?.any { it.text == text } == true }).boundsInWindow.center
        fun click(at: Offset) {
            onEdt {
                time += 1_000L
                scene.sendPointerEvent(PointerEventType.Move, at, timeMillis = time)
                scene.sendPointerEvent(PointerEventType.Press, at, timeMillis = time, buttons = PointerButtons(isPrimaryPressed = true), button = PointerButton.Primary)
                scene.sendPointerEvent(PointerEventType.Release, at, timeMillis = time + 30, button = PointerButton.Primary)
            }
            frames(6)
        }
        fun rightClick(at: Offset) {
            onEdt {
                time += 1_000L
                scene.sendPointerEvent(PointerEventType.Move, at, timeMillis = time)
                scene.sendPointerEvent(PointerEventType.Press, at, timeMillis = time, buttons = PointerButtons(isSecondaryPressed = true), button = PointerButton.Secondary)
                scene.sendPointerEvent(PointerEventType.Release, at, timeMillis = time + 30, button = PointerButton.Secondary)
            }
            frames(6)
        }
        fun escape() {
            onEdt {
                scene.sendKeyEvent(androidx.compose.ui.input.key.KeyEvent(androidx.compose.ui.input.key.Key.Escape, androidx.compose.ui.input.key.KeyEventType.KeyDown))
                scene.sendKeyEvent(androidx.compose.ui.input.key.KeyEvent(androidx.compose.ui.input.key.Key.Escape, androidx.compose.ui.input.key.KeyEventType.KeyUp))
            }
            frames(6)
        }
        try {
            frames(6)
            if (listFirst) {
                // The list opened once while it is empty, then left.
                click(find("list of parents"))
                escape()
            }
            click(find("add a parent"))
            assertTrue("New task 2" in texts(), "the tasks are offered: ${texts()}")
            click(find("New task 2", first = true))
            val parent = SearchDomain.taskKey(live.tasks.values.first { it.title == "New task 2" }.id)
            assertTrue(parent in org.example.project.scheduler.domain.ElementTree.parentsOf(live, childKey), "the parent was added")
            val before = texts().count { it == "New task 2" }
            click(find("list of parents"))
            frames(6)
            assertTrue(popups.request != null, "the list's window is open")
            assertTrue(texts().count { it == "New task 2" } > before, "the list of parents lists it: ${texts()}")
            if (thenAdd) {
                // Anomaly 2026-11-15: the parent right-clicked, "add", then the window's background menu, "Show the added
                // elements" — the list must hold it.
                rightClick(find("New task 2"))
                click(find("add"))
                assertTrue(popups.request != null, "the list's window is still open: ${texts()}")
                // Under its one row: the list's background.
                rightClick(find("New task 2") + Offset(0f, 120f))
                assertTrue(texts().any { it == "Show the added elements" }, "the background menu: ${texts()}")
                click(find("Show the added elements"))
                frames(6)
                assertTrue(texts().any { it.startsWith("Added elements") && it.endsWith("1") }, "the added elements hold the parent: ${texts()}")
            }
            if (header) {
                // User rule 2026-11-15: *"I select 'show the header', but the header is not the usual header"* — the
                // window's buttons; its maximize makes it a Search window of the app, maximized, and it closes.
                rightClick(find("New task 2") + Offset(0f, 120f))
                click(find("Show the header"))
                frames(6)
                assertTrue(texts().contains("⧉"), "the window's head: ${texts()}")
                click(find("▢"))
                frames(6)
                assertEquals(listOf(org.example.project.ui.DetachedAs.Maximized), detached.map { it.second })
                assertTrue(childKey in detached.single().first.filters.parentsOf, "the list's own configuration")
                assertTrue(popups.requests.isEmpty(), "the pop-up is the window now")
            }
            if (nested) {
                // Anomaly 2026-11-15: in the list's window, "Show the actions", the parent added there, its "Parents"
                // action open, its own "list of parents" clicked — a second window opens over the first.
                rightClick(find("New task 2"))
                click(find("add"))
                rightClick(find("New task 2") + Offset(0f, 120f))
                // User rule 2026-11-15: its field is its bar — the background menu does not offer to hide it.
                assertTrue(texts().none { it == "Hide the search bar" }, "no hiding the field: ${texts()}")
                click(find("Show the actions"))
                frames(6)
                assertTrue(texts().any { it.startsWith("Actions on the added elements") }, "the actions show: ${texts()}")
                // Each row closed by default: the parent's group, then its "Parents" action.
                // Its arrow, on its line: a click on the title selects the row.
                fun openLast(text: String) {
                    val title = nodes().last { n -> n.config.getOrNull(SemanticsProperties.Text)?.any { it.text == text } == true }.boundsInWindow
                    val arrow = nodes()
                        .filter { n -> n.config.getOrNull(SemanticsProperties.Text)?.any { it.text == "▸" } == true }
                        .map { it.boundsInWindow }
                        .filter { kotlin.math.abs(it.center.y - title.center.y) < 12f && it.center.x < title.center.x }
                        .maxByOrNull { it.center.x }
                    click((arrow ?: title).center)
                }
                val parentsRows = texts().count { it == "Parents" }
                openLast("Task: New task 2")
                // The pop-up is short: its actions list scrolled down to the action.
                val over = find("Children")
                repeat(60) {
                    if (texts().count { it == "Parents" } > parentsRows) return@repeat
                    onEdt {
                        time += 100L
                        scene.sendPointerEvent(PointerEventType.Move, over, timeMillis = time)
                        scene.sendPointerEvent(PointerEventType.Scroll, over, scrollDelta = Offset(0f, 1f), timeMillis = time)
                    }
                    frames(3)
                }
                assertTrue(texts().count { it == "Parents" } > parentsRows, "the group opened: ${texts()}")
                openLast("Parents")
                assertTrue(texts().count { it == "list of parents" } >= 2, "the nested field shows: ${texts()}")
                // Brought fully into the short pop-up's view: a press must land on it, not past the pop-up's edge.
                repeat(4) {
                    onEdt {
                        time += 100L
                        scene.sendPointerEvent(PointerEventType.Scroll, over, scrollDelta = Offset(0f, 1f), timeMillis = time)
                    }
                    frames(3)
                }
                click(find("list of parents"))
                frames(6)
                assertEquals(2, popups.requests.size, "a second window over the first: ${texts()}")
            }
        } finally {
            onEdt { scene.close() }
        }
    }

    @Test
    fun a_task_picked_as_a_chrono_s_parent_is_in_its_list_of_parents() = checkParentListed { "Chrono/a" }

    @Test
    fun a_task_picked_as_a_task_s_parent_is_in_its_list_of_parents() =
        checkParentListed { s -> SearchDomain.taskKey(s.tasks.values.first { it.title == "New task 1" }.id) }

    @Test
    fun a_parent_added_from_the_list_of_parents_is_in_its_added_elements() = checkParentListed(thenAdd = true) { "Chrono/a" }

    /**
     * Anomaly 2026-11-15: *"clicking the list of parents opens a window, where I select 'show the actions' in the
     * right-click menu, find the list of parents field, click it, but it doesn't open a window"*.
     */
    @Test
    fun the_list_of_parents_inside_a_list_of_parents_opens_its_window() =
        checkParentListed(nested = true) { s -> SearchDomain.taskKey(s.tasks.values.first { it.title == "New task 1" }.id) }

    @Test
    fun the_list_of_parents_shows_a_window_s_head_and_becomes_a_window() = checkParentListed(header = true) { "Chrono/a" }

    @Test
    fun the_list_opened_before_the_parent_is_added_lists_it_after() = checkParentListed(listFirst = true) { "Chrono/a" }

    @Test
    fun the_list_opened_before_a_task_parent_is_added_lists_it_after() =
        checkParentListed(listFirst = true) { s -> SearchDomain.taskKey(s.tasks.values.first { it.title == "New task 1" }.id) }
}
