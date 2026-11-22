package org.example.project

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getAllSemanticsNodes
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.Density
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.example.project.scheduler.domain.SearchDomain
import org.example.project.scheduler.state.SchedulerState
import org.example.project.ui.AddedActionHandlers
import org.example.project.ui.ArrowSearches
import org.example.project.ui.ConfigurationTreeWindow
import org.example.project.ui.EmbeddedSearchRequest
import org.example.project.ui.LocalArrowOwner
import org.example.project.ui.LocalArrowRoot
import org.example.project.ui.LocalArrowSearches
import org.example.project.ui.LocalEmbeddedSearch
import org.example.project.ui.LocalTransientMenuHost
import org.example.project.ui.LocalWindowFrameHost
import org.example.project.ui.SearchRowOpeners
import org.example.project.ui.SearchWindow
import org.example.project.ui.TransientMenuHost
import org.example.project.ui.WindowFrameHost
import org.example.project.ui.transientMenuDismissRoot

/**
 * Anomaly 2026-11-22: *"I go to down arrow, right-click, show header, then drag the header and the window dragged becomes
 * something else: the header becomes blue, the content becomes a layout that was supposed to be obsolete a long time
 * ago."* The window a ▾ pop-up becomes ([ConfigurationTreeWindow]) is drawn off screen as `App` draws it: it is the
 * pop-up — the configurations tree, its own search the ▾'s — and not the configurations window by group and type.
 */
@OptIn(ExperimentalComposeUiApi::class, InternalComposeUiApi::class)
class ConfigurationTreeWindowTest {

    private val openers = SearchRowOpeners(onOpenTaskEdit = {}, onOpenCategory = {}, onOpenPeriodKind = {}, onEditAlarmOrTimer = {}, onEditReminder = {})
    private val handlers =
        AddedActionHandlers(
            onStartNow = {}, onEdit = {}, onGoToTaskTree = {}, onDeepCopyCell = {}, onOpenResilienceSearch = {},
            onOpenKindSearch = {}, timerRun = { _, _ -> }, reminderEditor = {}, onCreate = {},
            onDuplicate = { _, _ -> emptyList() }, onPlaceOnCalendar = {},
        )

    @Test
    fun the_window_a_tree_pop_up_becomes_is_the_tree_with_the_pop_up_s_search() {
        val state = SchedulerState.empty()
        // What was typed in the ▾ pop-up's own bar, kept under its id as `App` keeps it.
        var inside = mapOf<String, String>()
        val registry = ArrowSearches(read = { inside }, write = { _, all -> inside = all })
        registry.set("Search", ArrowSearches.ARROW, SearchDomain.CONFIGURATION_SEARCH.copy(query = "sort"))
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
                                state = state, config = SearchDomain.Config(), openedConfig = null, onConfigChange = {},
                                nowMillis = { 0L }, windows = emptyList(), claudeLogs = org.example.project.claudelogs.ClaudeLogCatalog.EMPTY,
                                onPickOnCalendar = {}, windowId = "ConfigSearch", onDismiss = {},
                            )
                        }
                    }
                }
            }
        }
        try {
            var time = 0L
            repeat(8) { onEdt { time += 16L; scene.render(time * 1_000_000L) } }
            val texts = onEdt {
                scene.semanticsOwners.flatMap { it.getAllSemanticsNodes(mergingEnabled = false) }
                    .mapNotNull { n -> n.config.getOrNull(SemanticsProperties.Text)?.joinToString { it.text } } +
                    scene.semanticsOwners.flatMap { it.getAllSemanticsNodes(mergingEnabled = false) }
                        .mapNotNull { n -> n.config.getOrNull(SemanticsProperties.EditableText)?.text }
            }
            // The tree, narrowed by the pop-up's own search.
            assertTrue("Sorting" in texts, "the tree's 'Sorting' category, which its search 'sort' finds: $texts")
            assertTrue("sort" in texts, "its bar holds what was typed in the pop-up's: $texts")
            assertFalse("Display" in texts, "the pop-up's search narrows it, as it narrowed the pop-up: $texts")
            // Not the configurations window by group and type.
            assertFalse("Only what applies to the results" in texts, "not the configurations window: $texts")
            assertFalse("Search configurations" in texts, "not the configurations window's title: $texts")
        } finally {
            onEdt { scene.close() }
        }
    }
}
