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
    private fun <T> onEdt(block: () -> T): T {
        var result: Result<T>? = null
        javax.swing.SwingUtilities.invokeAndWait { result = runCatching(block) }
        return result!!.getOrThrow()
    }

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

    private fun checkParentListed(listFirst: Boolean = false, child: (SchedulerState) -> String) {
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
        val scene = onEdt {
            ImageComposeScene(1400, 900, density = Density(1f)) {
                var state by remember { mutableStateOf(start) }
                var config by remember { mutableStateOf(opened) }
                val onIntent = { intent: SchedulerIntent ->
                    state = SchedulerReducer.reduce(state, intent)
                    live = state
                }
                CompositionLocalProvider(
                    LocalFieldSearchPopupHost provides popups,
                    org.example.project.ui.LocalTransientMenuHost provides menus,
                    org.example.project.ui.LocalWindowFrameHost provides frames,
                    LocalEmbeddedSearch provides { request, modifier ->
                        SearchWindow(
                            state = state, openers = openers, onStartTaskNow = {}, onGoToTaskTree = { _, _ -> }, onDeepCopyCell = {},
                            actionHandlers = handlers, onIntent = onIntent, onDismiss = {}, config = request.config,
                            onConfigChange = request.onConfigChange, onOpenConfigurations = {}, splits = EMBEDDED_SEARCH_SPLITS,
                            embedded = true, onDrop = request.onDrop, canDrop = request.canDrop, picker = request.picker,
                            barOutside = request.barOutside, modifier = modifier,
                        )
                    },
                ) {
                    Box(Modifier.fillMaxSize().transientMenuDismissRoot(menus).raiseOnPress { frames.blur() }) {
                        SearchWindow(
                            state = state, openers = openers, onStartTaskNow = {}, onGoToTaskTree = { _, _ -> }, onDeepCopyCell = {},
                            actionHandlers = handlers, onIntent = onIntent, onDismiss = {}, config = config,
                            onConfigChange = { config = it }, onOpenConfigurations = {}, initialSize = Size(1400f, 900f),
                        )
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
    fun the_list_opened_before_the_parent_is_added_lists_it_after() = checkParentListed(listFirst = true) { "Chrono/a" }

    @Test
    fun the_list_opened_before_a_task_parent_is_added_lists_it_after() =
        checkParentListed(listFirst = true) { s -> SearchDomain.taskKey(s.tasks.values.first { it.title == "New task 1" }.id) }
}
