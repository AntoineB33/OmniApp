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
import org.example.project.scheduler.state.SchedulerState
import org.example.project.ui.AddedActionHandlers
import org.example.project.ui.SearchRowOpeners
import org.example.project.ui.SearchWindow
import org.example.project.ui.raiseOnPress
import org.example.project.ui.transientMenuDismissRoot

/**
 * User rule 2026-11-15 (*"Yes, rebuild it"*): **the ▾ configurations pop-up is the very Search window**, its list the
 * tree. Its background menu shows the real actions and added elements sections — no "No element added." placeholder —
 * and a configuration right-clicked, "add", is an added element of it, with its actions.
 */
@OptIn(ExperimentalComposeUiApi::class, androidx.compose.ui.InternalComposeUiApi::class)
class TreePopupSearchWindowTest {
    private val openers = SearchRowOpeners(onOpenTaskEdit = {}, onOpenCategory = {}, onOpenPeriodKind = {}, onEditAlarmOrTimer = {}, onEditReminder = {})
    private val handlers =
        AddedActionHandlers(
            onStartNow = {}, onEdit = {}, onGoToTaskTree = {}, onDeepCopyCell = {}, onOpenResilienceSearch = {},
            onOpenKindSearch = {}, timerRun = { _, _ -> }, reminderEditor = {}, onCreate = {},
            onDuplicate = { _, _ -> emptyList() }, onPlaceOnCalendar = {},
        )

    @Test
    fun the_configurations_pop_up_is_a_search_window_whose_configurations_can_be_added() {
        val menus = org.example.project.ui.TransientMenuHost()
        val frames = org.example.project.ui.WindowFrameHost()
        val start = SchedulerState.empty()
        // The window's configuration as last composed: its ▾ pop-up's own is kept in it ([SearchDomain.Config.inside]).
        var latest = SearchDomain.Config()
        val scene = onEdt {
            ImageComposeScene(1600, 1000, density = Density(1f)) {
                var config by remember { mutableStateOf(SearchDomain.Config(kinds = setOf(Kind.Chrono))) }
                latest = config
                val registry = remember {
                    org.example.project.ui.ArrowSearches(read = { config.inside }, write = { _, inside -> config = config.copy(inside = inside) })
                }
                CompositionLocalProvider(
                    org.example.project.ui.LocalTransientMenuHost provides menus,
                    org.example.project.ui.LocalWindowFrameHost provides frames,
                ) {
                    Box(Modifier.fillMaxSize().transientMenuDismissRoot(menus).raiseOnPress { frames.blur() }) {
                        CompositionLocalProvider(
                            org.example.project.ui.LocalArrowSearches provides registry,
                            org.example.project.ui.LocalArrowOwner provides "Search",
                            org.example.project.ui.LocalArrowRoot provides "Search",
                        ) {
                            SearchWindow(
                                state = start, openers = openers, onStartTaskNow = {}, onGoToTaskTree = { _, _ -> }, onDeepCopyCell = {},
                                actionHandlers = handlers, onIntent = {}, onDismiss = {}, config = config,
                                onConfigChange = { config = it }, initialSize = Size(1600f, 1000f),
                            )
                        }
                    }
                }
            }
        }
        var time = 0L
        fun frames(n: Int) = repeat(n) { onEdt { time += 16L; scene.render(time * 1_000_000L) } }
        fun nodes(): List<SemanticsNode> = onEdt { scene.semanticsOwners.flatMap { it.getAllSemanticsNodes(mergingEnabled = false) } }
        fun texts(): List<String> = nodes().mapNotNull { n -> n.config.getOrNull(SemanticsProperties.Text)?.joinToString { it.text } }
        fun find(text: String, first: Boolean = false): Offset {
            val matching = nodes().filter { n -> n.config.getOrNull(SemanticsProperties.Text)?.any { it.text == text } == true }
            return (if (first) matching.first() else matching.last()).boundsInWindow.center
        }
        fun press(at: Offset, secondary: Boolean) {
            onEdt {
                time += 1_000L
                val buttons = if (secondary) PointerButtons(isSecondaryPressed = true) else PointerButtons(isPrimaryPressed = true)
                val button = if (secondary) PointerButton.Secondary else PointerButton.Primary
                scene.sendPointerEvent(PointerEventType.Move, at, timeMillis = time)
                scene.sendPointerEvent(PointerEventType.Press, at, timeMillis = time, buttons = buttons, button = button)
                scene.sendPointerEvent(PointerEventType.Release, at, timeMillis = time + 30, button = button)
            }
            frames(8)
        }
        try {
            frames(6)
            // The window's own ▾, in its bar.
            // The bar's ▾: the first one after the bar ("Search" twice: the section's title, then the bar's label).
            val ordered = nodes().mapNotNull { n -> n.config.getOrNull(SemanticsProperties.Text)?.joinToString { it.text }?.let { it to n.boundsInWindow.center } }
            val barAt = ordered.withIndex().filter { it.value.first == "Search" }[1].index
            val arrow = ordered.drop(barAt).first { it.first == "▾" }.second
            press(arrow, secondary = false)
            assertTrue(texts().any { it == "Recent" }, "the tree is open: ${texts()}")
            // The pop-up's bar is the Search window's: its label, its cross, its ▾ — twice now: the window's and the pop-up's.
            assertTrue(texts().count { it == "Search" } >= 3, "the pop-up has a Search window's bar: ${texts()}")
            // A category right-clicked: "add".
            val category = "Order"
            // The first: the root's row (the flat list's configurations say "Order" too, as their path).
            press(find(category, first = true), secondary = true)
            assertTrue(texts().any { it == "add" }, "a configuration's right-click adds it: ${texts()}")
            press(find("add"), secondary = false)
            assertTrue(texts().any { it == category }, "the pop-up stays: ${texts()}")
            // The pop-up's own Search window holds it among its added elements, kept with the window it is the ▾ of.
            val ownAdded = latest.inside.values.any { it.contains("SearchConfiguration/" + SearchDomain.CONFIGURATION_NODE_PREFIX) }
            assertTrue(ownAdded, "the category is an added element of the pop-up's own search: ${latest.inside}")
        } finally {
            onEdt { scene.close() }
        }
    }
}
