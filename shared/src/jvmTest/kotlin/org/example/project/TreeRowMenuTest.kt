package org.example.project

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
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
import kotlin.test.assertTrue
import org.example.project.scheduler.domain.SearchDomain
import org.example.project.scheduler.state.SchedulerState
import org.example.project.scheduler.ui.ADD_REPLACING_LABEL
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
 * User 2026-11-27: *"In the default search window, in down arrow of the search section, I right-click a row, and I get
 * only 'add'... Shouldn't I also get 'add and remove', 'deep copy' etc...?"* — **a row of the ▾ tree is the Search
 * window's result row** (`TreeRows`): its right-click opens the one row menu, a category's and a configuration's alike.
 */
@OptIn(ExperimentalComposeUiApi::class, InternalComposeUiApi::class)
class TreeRowMenuTest {

    private val openers = SearchRowOpeners(onOpenTaskEdit = {}, onOpenCategory = {}, onOpenPeriodKind = {}, onEditAlarmOrTimer = {}, onEditReminder = {})
    private val handlers =
        AddedActionHandlers(
            onStartNow = {}, onEdit = {}, onGoToTaskTree = {}, onDeepCopyCell = {}, onOpenResilienceSearch = {},
            onOpenKindSearch = {}, timerRun = { _, _ -> }, reminderEditor = {}, onCreate = {},
            onDuplicate = { _, _ -> emptyList() }, onPlaceOnCalendar = {},
        )

    /** The menu a right-click on the tree's row named [row] opens, the tree narrowed by [query]: every text then shown. */
    private fun menuOf(row: String, query: String): List<String> {
        val state = SchedulerState.empty()
        var inside = mapOf<String, String>()
        val registry = ArrowSearches(read = { inside }, write = { _, all -> inside = all })
        registry.set("Search", ArrowSearches.ARROW, SearchDomain.CONFIGURATION_SEARCH.copy(query = query))
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
            fun frames() = repeat(8) { onEdt { time += 16L; scene.render(time * 1_000_000L) } }
            fun nodes() = onEdt { scene.semanticsOwners.flatMap { it.getAllSemanticsNodes(mergingEnabled = false) } }
            fun texts() = nodes().mapNotNull { n -> n.config.getOrNull(SemanticsProperties.Text)?.joinToString { it.text } }
            frames()
            val at: Offset = nodes().first { n -> n.config.getOrNull(SemanticsProperties.Text)?.any { it.text == row || it.text.endsWith(": $row") } == true }.boundsInWindow.center
            onEdt {
                time += 1_000L
                scene.sendPointerEvent(PointerEventType.Move, at, timeMillis = time)
                scene.sendPointerEvent(PointerEventType.Press, at, timeMillis = time, buttons = PointerButtons(isSecondaryPressed = true), button = PointerButton.Secondary)
                scene.sendPointerEvent(PointerEventType.Release, at, timeMillis = time + 30, button = PointerButton.Secondary)
            }
            frames()
            return texts()
        } finally {
            onEdt { scene.close() }
        }
    }

    private fun assertTheOneRowMenu(texts: List<String>) {
        for (entry in listOf("add", ADD_REPLACING_LABEL, "copy id (ctrl c)", "copy path", "deep copy")) {
            assertTrue(entry in texts, "the row menu's \"$entry\": $texts")
        }
    }

    @Test
    fun a_category_of_the_tree_has_the_one_row_menu() = assertTheOneRowMenu(menuOf("Order", "sort"))

    @Test
    fun a_configuration_of_the_tree_has_the_one_row_menu() = assertTheOneRowMenu(menuOf("Match case", "match case"))
}
