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
import org.example.project.scheduler.domain.PresenceEvolutions
import org.example.project.scheduler.domain.SearchDomain
import org.example.project.scheduler.domain.SearchDomain.Kind
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
 * Anomaly 2026-11-20: *"I selected the default presence ratio target evolution, but in relative to there is nothing"*.
 * Drawn off screen in the real Search window: a task of the first tree added, its "Presence ratio target" open, its
 * default evolution SELECTED (not added) in the evolutions field's window — the rows below edit it: "relative to" names
 * the first tree's root, and its window offers it.
 */
@OptIn(ExperimentalComposeUiApi::class, androidx.compose.ui.InternalComposeUiApi::class)
class PresenceEvolutionSelectionTest {

    /** Narrow, as a Search window beside others is: its pop-up is narrow, the detail beside the name long against it. */
    private val SCENE_WIDTH = 1000

    private val openers = SearchRowOpeners(onOpenTaskEdit = {}, onOpenCategory = {}, onOpenPeriodKind = {}, onEditAlarmOrTimer = {}, onEditReminder = {})
    private val handlers =
        AddedActionHandlers(
            onStartNow = {}, onEdit = {}, onGoToTaskTree = {}, onDeepCopyCell = {}, onOpenResilienceSearch = {},
            onOpenKindSearch = {}, timerRun = { _, _ -> }, reminderEditor = {}, onCreate = {},
            onDuplicate = { _, _ -> emptyList() }, onPlaceOnCalendar = {},
        )

    /** "Tree" under the root, holding "A" and "B". */
    private fun account(): SchedulerState {
        var s = SchedulerState.empty().copy(sleep = null)
        s = SchedulerReducer.reduce(s, SchedulerIntent.SetCellTitle(s.lists[s.rootListId]!!.cellIds.last(), "Tree"))
        for (title in listOf("A", "B")) {
            val tree = s.tasks.values.first { it.title == "Tree" }
            s = SchedulerReducer.reduce(s, SchedulerIntent.SetCellTitle(s.lists[tree.childListId!!]!!.cellIds.last(), title))
        }
        return s
    }

    @Test
    fun a_default_evolution_selected_in_the_field_s_window_is_what_the_rows_edit() {
        val start = account()
        val a = start.tasks.values.first { it.title == "A" }.id
        val childKey = SearchDomain.taskKey(a)
        val added = SearchDomain.resolve(start, listOf(childKey), { emptyMap() })
        val sections = SearchDomain.actionsFor(SearchDomain.addedActions("", Kind.entries.toSet(), SearchDomain.actionKindsOf(added)), added)
        val base = SearchDomain.Config(kinds = setOf(Kind.Task), added = listOf(childKey), actionsExpanded = true, actionSearch = "presence ratio target")
        val group = SearchDomain.shownActionGroups(sections, added, base).first { SearchDomain.AddedAction.TaskPresenceFormula in it.actions }
        val opened = base.copy(toggledActionRows = setOf(SearchDomain.microGroupId(group, SearchDomain.AddedAction.TaskPresenceFormula)))
        val popups = FieldSearchPopupHost()
        val menus = org.example.project.ui.TransientMenuHost()
        val frames = org.example.project.ui.WindowFrameHost()
        var live = start
        val scene = onEdt {
            ImageComposeScene(SCENE_WIDTH, 1200, density = Density(1f)) {
                var state by remember { mutableStateOf(start) }
                var config by remember { mutableStateOf(opened) }
                val registry = remember {
                    org.example.project.ui.ArrowSearches(read = { config.inside }, write = { _, inside -> config = config.copy(inside = inside) })
                }
                val onIntent = { intent: SchedulerIntent -> state = SchedulerReducer.reduce(state, intent); live = state }
                val embedded: @androidx.compose.runtime.Composable (org.example.project.ui.EmbeddedSearchRequest, Modifier) -> Unit = { request, modifier ->
                    SearchWindow(
                        state = state, openers = openers, onStartTaskNow = {}, onGoToTaskTree = { _, _ -> }, onDeepCopyCell = {},
                        actionHandlers = handlers, onIntent = onIntent, onDismiss = {}, config = request.config,
                        onConfigChange = request.onConfigChange, splits = request.splits ?: EMBEDDED_SEARCH_SPLITS,
                        embedded = true, onDrop = request.onDrop, canDrop = request.canDrop, picker = request.picker,
                        barOutside = request.barOutside, onSelectedAdded = request.onSelectedAdded, modifier = modifier,
                    )
                }
                CompositionLocalProvider(
                    LocalFieldSearchPopupHost provides popups,
                    org.example.project.ui.LocalTransientMenuHost provides menus,
                    org.example.project.ui.LocalWindowFrameHost provides frames,
                ) {
                    Box(Modifier.fillMaxSize().transientMenuDismissRoot(menus).raiseOnPress { frames.blur() }) {
                        CompositionLocalProvider(
                            LocalEmbeddedSearch provides embedded,
                            org.example.project.ui.LocalArrowSearches provides registry,
                            org.example.project.ui.LocalArrowOwner provides "Search",
                            org.example.project.ui.LocalArrowRoot provides "Search",
                        ) {
                            SearchWindow(
                                state = state, openers = openers, onStartTaskNow = {}, onGoToTaskTree = { _, _ -> }, onDeepCopyCell = {},
                                actionHandlers = handlers, onIntent = onIntent, onDismiss = {}, config = config,
                                onConfigChange = { config = it }, initialSize = Size(SCENE_WIDTH.toFloat(), 1200f),
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
        fun find(text: String): Offset =
            nodes().last { n -> n.config.getOrNull(SemanticsProperties.Text)?.any { it.text == text } == true }.boundsInWindow.center
        fun click(at: Offset) {
            onEdt {
                time += 1_000L
                scene.sendPointerEvent(PointerEventType.Move, at, timeMillis = time)
                scene.sendPointerEvent(PointerEventType.Press, at, timeMillis = time, buttons = PointerButtons(isPrimaryPressed = true), button = PointerButton.Primary)
                scene.sendPointerEvent(PointerEventType.Release, at, timeMillis = time + 30, button = PointerButton.Primary)
            }
            frames(6)
        }
        try {
            frames(6)
            assertTrue(texts().none { it.startsWith("relative to · ") }, "nothing to be relative to before a selection: ${texts()}")
            click(find("presence ratio target evolutions"))
            frames(40)
            assertTrue(popups.request != null, "the evolutions' window is open")
            val row = PresenceEvolutions.title(start, PresenceEvolutions.defaults(start).first { a in it.taskIds })
            assertTrue(row in texts(), "the default evolution is listed: ${texts()}")
            assertEquals(1, PresenceEvolutions.shown(start).size, "one default presence evolution")
            // Anomaly 2026-11-20: listed, its name was drawn at no width — the long detail beside it took the row.
            val name = nodes().last { n -> n.config.getOrNull(SemanticsProperties.Text)?.any { it.text == row } == true }.boundsInWindow
            assertTrue(name.width > 20f, "the name is drawn: $name")
            click(find(row))
            frames(6)
            assertTrue(texts().any { it == "relative to · of “Tree”" }, "the selected default is relative to the root: ${texts()}")
            // A press on a row below closes the window; the selection is still what the rows edit.
            click(find("relative to · of “Tree”"))
            frames(6)
            assertTrue(texts().any { it == "relative to · of “Tree”" }, "held once its window closed: ${texts()}")
            assertTrue(texts().count { it == "Tree" } >= 2, "the relative-to window offers the tree's root: ${texts()}")
            // User rule 2026-11-20, "Add a delete button": the bin beside "New" deletes the evolution the rows edit — the
            // default one, marked gone.
            click(find("🗑 Delete"))
            frames(6)
            assertTrue(PresenceEvolutions.shown(live).isEmpty(), "the default evolution is deleted: ${live.presenceEvolutions}")
            assertTrue(texts().none { it.startsWith("relative to · ") }, "nothing left to edit: ${texts()}")
        } finally {
            onEdt { scene.close() }
        }
    }
}
