package org.example.project

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerButtons
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getAllSemanticsNodes
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.Density
import kotlin.test.Test
import org.example.project.scheduler.domain.SearchDomain
import org.example.project.scheduler.state.SchedulerState
import org.example.project.ui.*

@OptIn(ExperimentalComposeUiApi::class, InternalComposeUiApi::class)
/**
 * Anomaly 2026-11-27: *"down arrow > 'Types' > 'Types' field, then I click on 'deselect all', but the window disappears."*
 * Types turning on put "Currently on" above the leaf; the tree's rows were composed by place, so the field's open list
 * was lost with its row's state. They are keyed by what they are ([SearchDomain.configurationRowId]).
 */
class TypesDeselectAllKeepsListTest {
    private val openers = SearchRowOpeners(onOpenTaskEdit = {}, onOpenCategory = {}, onOpenPeriodKind = {}, onEditAlarmOrTimer = {}, onEditReminder = {})
    private val handlers = AddedActionHandlers(
        onStartNow = {}, onEdit = {}, onGoToTaskTree = {}, onDeepCopyCell = {}, onOpenResilienceSearch = {},
        onOpenKindSearch = {}, timerRun = { _, _ -> }, reminderEditor = {}, onCreate = {},
        onDuplicate = { _, _ -> emptyList() }, onPlaceOnCalendar = {},
    )

    @Test
    fun deselect_all_in_the_types_field_keeps_its_list_open() {
        val state = SchedulerState.empty()
        var inside = mapOf<String, String>()
        val registry = ArrowSearches(read = { inside }, write = { _, all -> inside = all })
        registry.set("Search", ArrowSearches.ARROW, SearchDomain.CONFIGURATION_SEARCH.copy(query = "Types"))
        var config by mutableStateOf(SearchDomain.Config(kinds = SearchDomain.Kind.entries.toSet()))
        val embedded: @androidx.compose.runtime.Composable (EmbeddedSearchRequest, Modifier) -> Unit = { request, modifier ->
            SearchWindow(
                state = state, openers = openers, onStartTaskNow = {}, onGoToTaskTree = { _, _ -> }, onDeepCopyCell = {},
                actionHandlers = handlers, onIntent = {}, onDismiss = request.onDismiss, config = request.config,
                onConfigChange = request.onConfigChange, openedConfig = request.openedConfig,
                splits = request.splits, onSplitsChange = request.onSplitsChange, treeList = request.treeList,
                embedded = true, modifier = modifier,
            )
        }
        val menus = TransientMenuHost()
        var dismissed = 0
        val scene = onEdt {
            ImageComposeScene(1400, 900, density = Density(1f)) {
                MaterialTheme {
                    CompositionLocalProvider(
                        LocalTransientMenuHost provides menus,
                        LocalWindowFrameHost provides WindowFrameHost(),
                        LocalEmbeddedSearch provides embedded,
                        LocalArrowSearches provides registry,
                        LocalArrowOwner provides "Search",
                        LocalArrowRoot provides "Search",
                    ) {
                        Box(Modifier.fillMaxSize().transientMenuDismissRoot(menus)) {
                            ConfigurationTreeWindow(
                                state = state, config = config, openedConfig = null, onConfigChange = { config = it },
                                nowMillis = { 0L }, windows = emptyList(), claudeLogs = org.example.project.claudelogs.ClaudeLogCatalog.EMPTY,
                                onPickOnCalendar = {}, windowId = "ConfigSearch", onDismiss = { dismissed++ },
                            )
                        }
                    }
                }
            }
        }
        var time = 0L
        fun frames() = repeat(10) { onEdt { time += 16L; scene.render(time * 1_000_000L) } }
        fun nodes() = onEdt { scene.semanticsOwners.flatMap { it.getAllSemanticsNodes(mergingEnabled = false) } }
        fun texts() = nodes().mapNotNull { n -> n.config.getOrNull(SemanticsProperties.Text)?.joinToString { it.text } }
        fun click(text: String, last: Boolean = false) {
            val ns = nodes().filter { n -> n.config.getOrNull(SemanticsProperties.Text)?.any { it.text.startsWith(text) } == true }
            val at = (if (last) ns.last() else ns.first()).boundsInWindow.center
            onEdt {
                scene.sendPointerEvent(PointerEventType.Move, at)
                scene.sendPointerEvent(PointerEventType.Press, at, buttons = PointerButtons(isPrimaryPressed = true), button = PointerButton.Primary)
                scene.sendPointerEvent(PointerEventType.Release, at, buttons = PointerButtons(), button = PointerButton.Primary)
            }
            frames()
        }
        try {
            frames()
            click("every kind")
            kotlin.test.assertTrue("Deselect all" in texts(), "the list is open")
            click("Deselect all", last = true)
            kotlin.test.assertTrue(config.kinds.isEmpty(), "every type unchecked")
            kotlin.test.assertTrue("Currently on" in texts(), "Types is now on")
            kotlin.test.assertTrue("Select all" in texts(), "the list is still open: ${texts()}")
            kotlin.test.assertEquals(0, dismissed)
        } finally {
            onEdt { scene.close() }
        }
    }
}
