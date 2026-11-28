package org.example.project

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
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
import org.example.project.scheduler.state.SchedulerIntent
import org.example.project.scheduler.state.SchedulerReducer
import org.example.project.scheduler.state.SchedulerState
import org.example.project.ui.AddedActionHandlers
import org.example.project.ui.EMBEDDED_SEARCH_SPLITS
import org.example.project.ui.LocalEmbeddedSearch
import org.example.project.ui.SearchRowOpeners
import org.example.project.ui.SearchWindow

/**
 * Anomaly 2026-11-19 (user, the same day: *"↑ on its first child must go to the row above, which is the parent"*): the
 * actions section's "Children" draws a task's children as the tree's cells under the action row. ↓ on that row reaches
 * the first child, ↑ on the first child the row again — as under an expanded result row and in the tree.
 */
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class, androidx.compose.ui.InternalComposeUiApi::class)
class ChildrenActionWalkTest {

    private val openers = SearchRowOpeners(onOpenTaskEdit = {}, onOpenCategory = {}, onOpenPeriodKind = {}, onEditAlarmOrTimer = {}, onEditReminder = {})
    private val handlers =
        AddedActionHandlers(
            onStartNow = {}, onEdit = {}, onGoToTaskTree = {}, onDeepCopyCell = {}, onOpenResilienceSearch = {},
            onOpenKindSearch = {}, timerRun = { _, _ -> }, reminderEditor = {}, onCreate = {},
            onDuplicate = { _, _ -> emptyList() }, onPlaceOnCalendar = {},
        )

    @Test
    fun the_arrows_walk_between_the_children_action_and_its_tree() {
        // "AI" with one child, "Kid".
        var start = SchedulerState.empty()
        val aiCell = start.lists[start.rootListId]!!.cellIds.last()
        start = SchedulerReducer.reduce(start, SchedulerIntent.SetCellTitle(aiCell, "AI"))
        val ai = start.cells[aiCell]!!.taskId!!
        val kidCell = start.lists[start.tasks[ai]!!.childListId!!]!!.cellIds[0]
        start = SchedulerReducer.reduce(start, SchedulerIntent.SetCellTitle(kidCell, "Kid"))

        // "AI" added, its "Children" action open.
        val key = SearchDomain.taskKey(ai)
        val added = SearchDomain.resolve(start, listOf(key), { emptyMap() })
        val sections = SearchDomain.actionsFor(SearchDomain.addedActions("", Kind.entries.toSet(), SearchDomain.actionKindsOf(added)), added)
        val base = SearchDomain.Config(kinds = setOf(Kind.Chrono), added = listOf(key), actionsExpanded = true, actionSearch = "children")
        val opened = withActionRowOpen(base, sections, added, SearchDomain.AddedAction.TaskChildren)

        var live = start
        val scene = onEdt {
            ImageComposeScene(1400, 900, density = Density(1f)) {
                var state by remember { mutableStateOf(start) }
                var config by remember { mutableStateOf(opened) }
                val onIntent = { intent: SchedulerIntent ->
                    state = SchedulerReducer.reduce(state, intent)
                    live = state
                }
                val embedded: @androidx.compose.runtime.Composable (org.example.project.ui.EmbeddedSearchRequest, Modifier) -> Unit = { request, modifier ->
                    SearchWindow(
                        state = state, openers = openers, onStartTaskNow = {}, onGoToTaskTree = { _, _ -> }, onDeepCopyCell = {},
                        actionHandlers = handlers, onIntent = onIntent, onDismiss = {}, config = request.config,
                        onConfigChange = request.onConfigChange, splits = EMBEDDED_SEARCH_SPLITS,
                        embedded = true, onDrop = request.onDrop, canDrop = request.canDrop, picker = request.picker,
                        barOutside = request.barOutside, modifier = modifier,
                    )
                }
                CompositionLocalProvider(LocalEmbeddedSearch provides embedded) {
                    SearchWindow(
                        state = state, openers = openers, onStartTaskNow = {}, onGoToTaskTree = { _, _ -> }, onDeepCopyCell = {},
                        actionHandlers = handlers, onIntent = onIntent, onDismiss = {}, config = config,
                        onConfigChange = { config = it }, initialSize = Size(1400f, 900f),
                    )
                }
            }
        }
        var time = 0L
        fun frames(n: Int) = repeat(n) {
            onEdt { time += 16L; scene.render(time * 1_000_000L) }
            Thread.sleep(15)
        }
        fun nodes(merged: Boolean = false): List<SemanticsNode> =
            onEdt { scene.semanticsOwners.flatMap { it.getAllSemanticsNodes(mergingEnabled = merged) } }
        fun textOf(n: SemanticsNode) = n.config.getOrNull(SemanticsProperties.Text)?.joinToString { it.text }
        fun find(text: String): Offset = nodes().first { textOf(it) == text }.boundsInWindow.center
        fun click(at: Offset) {
            onEdt {
                time += 1_000L
                scene.sendPointerEvent(PointerEventType.Move, at, timeMillis = time)
                scene.sendPointerEvent(PointerEventType.Press, at, timeMillis = time, buttons = PointerButtons(isPrimaryPressed = true), button = PointerButton.Primary)
                scene.sendPointerEvent(PointerEventType.Release, at, timeMillis = time + 30, button = PointerButton.Primary)
            }
            frames(8)
        }
        fun key(key: Key) {
            onEdt {
                scene.sendKeyEvent(KeyEvent(key = key, type = KeyEventType.KeyDown))
                scene.sendKeyEvent(KeyEvent(key = key, type = KeyEventType.KeyUp))
            }
            frames(8)
        }
        /** Whether the action row "Children" is drawn selected. */
        fun childrenRowSelected(): Boolean {
            val all = nodes()
            val label = all.filter { textOf(it) == "Children" }.map { it.boundsInWindow.center }
            return all.any { n ->
                n.config.getOrNull(SemanticsProperties.Selected) == true && label.any { n.boundsInWindow.contains(it) }
            }
        }
        try {
            frames(20)
            assertTrue(nodes().any { textOf(it) == "Kid" }, "the action shows AI's child")
            // The action row selected, the actions' list holding the keyboard.
            click(find("Children"))
            assertTrue(childrenRowSelected(), "the click selected the action row")
            // ↓ on it: its first child.
            key(Key.DirectionDown)
            assertEquals(kidCell, live.searchSelection.main, "↓ on 'Children' selects AI's first child")
            assertTrue(!childrenRowSelected(), "the row no longer shows as selected")
            // ↑ on the first child: the row above, its parent.
            key(Key.DirectionUp)
            assertTrue(childrenRowSelected(), "↑ on the first child selected the action row again")
        } finally {
            onEdt { scene.close() }
        }
    }
}
