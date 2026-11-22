package org.example.project

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getAllSemanticsNodes
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.Density
import kotlin.test.Test
import kotlin.test.assertTrue
import org.example.project.scheduler.domain.SearchDomain
import org.example.project.scheduler.state.SchedulerIntent
import org.example.project.scheduler.state.SchedulerReducer
import org.example.project.scheduler.state.SchedulerState
import org.example.project.ui.AddedActionHandlers
import org.example.project.ui.CustomMenuButton
import org.example.project.ui.LATERAL_MENU_SEARCH
import org.example.project.ui.LateralMenu
import org.example.project.ui.SearchRowOpeners
import org.example.project.ui.SearchWindow
import org.example.project.ui.customMenuRows

/**
 * Anomaly 2026-11-19: *"The left-side menu doesn't seem like a normal search window, even though I asked that only the
 * background color is the only difference in the logic."* It was drawn over an empty account, with openers and handlers
 * that did nothing. Built by the app's one builder of an embedded Search window, its list is its items THEN what its own
 * search finds in the account: a type checked in its ▾ lists that type's elements under the items, as in any window.
 */
@OptIn(androidx.compose.ui.InternalComposeUiApi::class, androidx.compose.ui.ExperimentalComposeUiApi::class)
class LateralMenuIsASearchWindowTest {
    @Test
    fun the_menu_lists_its_items_then_what_its_own_search_finds() {
        var account = SchedulerState.empty()
        val cell = account.lists[account.rootListId]!!.cellIds.last()
        account = SchedulerReducer.reduce(account, SchedulerIntent.SetCellTitle(cell, "Gardening"))
        val buttons = listOf(CustomMenuButton(id = "b0", windowId = "w0", title = "Alarms"))
        val scene = onEdt {
            ImageComposeScene(width = 188, height = 520, density = Density(1f)) {
                MaterialTheme {
                    val config = LATERAL_MENU_SEARCH.copy(kinds = setOf(SearchDomain.Kind.Option, SearchDomain.Kind.Task))
                    LateralMenu(
                        page = OmniPage.TaskScheduler,
                        onPageSelected = {},
                        config = config,
                        items = { typed ->
                            customMenuRows(
                                buttons = buttons, onClick = {}, editingId = null, onStartRename = {}, onRename = { _, _ -> },
                                onEditDone = {}, onRemove = {}, query = typed,
                            )
                        },
                        // What App gives: a Search window over the account ([App]'s embeddedSearchOf).
                        search = { request, modifier ->
                            SearchWindow(
                                state = account,
                                openers = SearchRowOpeners({}, {}, {}, {}, {}),
                                onStartTaskNow = {}, onGoToTaskTree = { _, _ -> }, onDeepCopyCell = {},
                                actionHandlers = AddedActionHandlers(
                                    onStartNow = {}, onEdit = {}, onGoToTaskTree = {}, onDeepCopyCell = {}, onOpenResilienceSearch = {},
                                    onOpenKindSearch = {}, timerRun = { _, _ -> }, reminderEditor = {}, onCreate = {},
                                    onDuplicate = { _, _ -> emptyList() }, onPlaceOnCalendar = {},
                                ),
                                onIntent = {}, onDismiss = {},
                                config = request.config, onConfigChange = request.onConfigChange, openedConfig = request.openedConfig,
                                splits = request.splits, onSplitsChange = request.onSplitsChange,
                                embedded = true, menuList = request.menuList, relevantSettings = request.relevantSettings,
                                modifier = modifier,
                            )
                        },
                    )
                }
            }
        }
        try {
            repeat(30) { i -> onEdt { scene.render((i + 1) * 16_000_000L) }; Thread.sleep(20) }
            val texts = onEdt {
                scene.semanticsOwners.flatMap { it.getAllSemanticsNodes(mergingEnabled = false) }
                    .mapNotNull { n -> n.config.getOrNull(SemanticsProperties.Text)?.joinToString { it.text } }
            }
            assertTrue("Alarms" in texts, "its item: $texts")
            assertTrue("Gardening" in texts, "and, its Task type checked, the account's task: $texts")
        } finally {
            onEdt { scene.close() }
        }
    }
}
