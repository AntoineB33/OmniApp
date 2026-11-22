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
 * Anomaly 2026-11-16: *"there are two rows selected, one in the search result list, one in the action section"*. A row
 * selected in the results, then one in the actions section: the window shows ONE selected row — the section's — and
 * the result row stays what the actions act on (still listed). A result row selected again takes it back.
 */
@OptIn(ExperimentalComposeUiApi::class, androidx.compose.ui.InternalComposeUiApi::class)
class OneSelectionInSearchWindowTest {

    private val openers = SearchRowOpeners(onOpenTaskEdit = {}, onOpenCategory = {}, onOpenPeriodKind = {}, onEditAlarmOrTimer = {}, onEditReminder = {})
    private val handlers =
        AddedActionHandlers(
            onStartNow = {}, onEdit = {}, onGoToTaskTree = {}, onDeepCopyCell = {}, onOpenResilienceSearch = {},
            onOpenKindSearch = {}, timerRun = { _, _ -> }, reminderEditor = {}, onCreate = {},
            onDuplicate = { _, _ -> emptyList() }, onPlaceOnCalendar = {},
        )

    @Test
    fun a_row_selected_in_the_actions_section_is_the_window_s_one_selection() {
        val start = SchedulerState.empty().copy(chronos = listOf(ChronoEntry(id = "a", label = "Clock")))
        val opened = SearchDomain.Config(kinds = setOf(Kind.Chrono))
        val popups = FieldSearchPopupHost()
        val menus = org.example.project.ui.TransientMenuHost()
        val frames = org.example.project.ui.WindowFrameHost()
        val scene = onEdt {
            ImageComposeScene(1400, 900, density = Density(1f)) {
                var state by remember { mutableStateOf(start) }
                var config by remember { mutableStateOf(opened) }
                val registry = remember {
                    org.example.project.ui.ArrowSearches(read = { config.inside }, write = { _, inside -> config = config.copy(inside = inside) })
                }
                val onIntent = { intent: SchedulerIntent -> state = SchedulerReducer.reduce(state, intent) }
                val embedded: @androidx.compose.runtime.Composable (org.example.project.ui.EmbeddedSearchRequest, Modifier) -> Unit = { request, modifier ->
                    SearchWindow(
                        state = state, openers = openers, onStartTaskNow = {}, onGoToTaskTree = { _, _ -> }, onDeepCopyCell = {},
                        actionHandlers = handlers, onIntent = onIntent, onDismiss = {}, config = request.config,
                        onConfigChange = request.onConfigChange, splits = EMBEDDED_SEARCH_SPLITS,
                        embedded = true, onDrop = request.onDrop, canDrop = request.canDrop, picker = request.picker,
                        barOutside = request.barOutside, modifier = modifier,
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
                                onConfigChange = { config = it }, initialSize = Size(1400f, 900f),
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
        fun selected(): List<SemanticsNode> = nodes().filter { it.config.getOrNull(SemanticsProperties.Selected) == true }
        fun at(text: String, first: Boolean): Offset {
            val matching = nodes().filter { n -> n.config.getOrNull(SemanticsProperties.Text)?.any { it.text == text } == true }
            return (if (first) matching.first() else matching.last()).boundsInWindow.center
        }
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
            // The result row "Clock" (the first: the results stand left of the actions).
            click(at("Clock", first = true))
            assertEquals(1, selected().size, "the result row alone: ${texts()}")
            val resultRow = selected().single().boundsInWindow
            // Its actions are listed; the section's first row selected by a click on its title.
            val groupTitle = nodes()
                .filter { n -> n.boundsInWindow.left > resultRow.right && n.config.getOrNull(SemanticsProperties.Text) != null }
                .filter { n -> n.config.getOrNull(SemanticsProperties.Text)!!.any { "Clock" in it.text } }
                .minByOrNull { it.boundsInWindow.top }
            assertTrue(groupTitle != null, "the actions section lists the selected row's group: ${texts()}")
            click(groupTitle.boundsInWindow.center)
            val after = selected()
            assertEquals(1, after.size, "one row selected in the window, not one per section: ${texts()}")
            assertTrue(after.single().boundsInWindow.left > resultRow.right, "the section's row is the one shown selected")
            assertTrue(texts().any { "Clock" in it && it != "Clock" }, "the result row's actions are still listed: ${texts()}")
            // Back on the result row: it alone again.
            click(at("Clock", first = true))
            assertEquals(1, selected().size, "the result row alone again: ${texts()}")
            assertTrue(selected().single().boundsInWindow.right <= resultRow.right + 1f, "the result row is the one shown selected")
        } finally {
            onEdt { scene.close() }
        }
    }
}
