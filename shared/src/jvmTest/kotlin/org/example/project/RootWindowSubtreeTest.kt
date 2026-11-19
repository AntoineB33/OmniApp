package org.example.project

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.unit.Density
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getAllSemanticsNodes
import androidx.compose.ui.semantics.getOrNull
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.example.project.scheduler.domain.SearchDomain
import org.example.project.scheduler.state.SchedulerIntent
import org.example.project.scheduler.state.SchedulerReducer
import org.example.project.scheduler.state.SchedulerState
import org.example.project.ui.AddedActionHandlers
import org.example.project.ui.CellEditPopupHost
import org.example.project.ui.LocalCellEditPopupHost
import org.example.project.ui.SearchRowOpeners
import org.example.project.ui.SearchSplits
import org.example.project.ui.SearchWindow
import org.example.project.scheduler.state.projectSearchSubtree

/**
 * Anomaly 2026-11-19: *"I clicked on the 'root' button in the left-side menu, expanded the root, but couldn't expand the
 * children, I couldn't select them and up/down arrow keys don't work as well, even though it is supposed to be a window
 * like any other window."* The Root button's window ([SearchDomain.rootSearchConfig]) is a Search window like any.
 */
@OptIn(androidx.compose.ui.InternalComposeUiApi::class, androidx.compose.ui.ExperimentalComposeUiApi::class)
class RootWindowSubtreeTest {

    /**
     * Anomaly 2026-11-19: *"It blinks when I select a row"*. Clicked, the cell's task joined the empty added list, whose
     * window, drawn for it, cleared the surface it shares with its window: the cell no longer counted and the row went.
     */
    @Test
    fun a_cell_clicked_first_stays_in_the_added_elements() {
        var account = org.example.project.scheduler.domain.TaskTreesAsTasks.seeded(SchedulerState.empty(), "tree-2026-11-19")
        val tree = account.lists[account.rootListId]!!.cellIds.first { account.cells[it]?.taskId != null }
        val treeTask = account.cells[tree]!!.taskId!!
        val kidCell = account.lists[account.tasks[treeTask]!!.childListId!!]!!.cellIds[0]
        account = SchedulerReducer.reduce(account, SchedulerIntent.SetCellTitle(kidCell, "Kid"))
        val popups = CellEditPopupHost()
        var live = account
        val scene = onEdt { ImageComposeScene(1200, 620, density = Density(1f)) {
            var state by remember { mutableStateOf(account) }
            var config by remember { mutableStateOf(SearchDomain.rootSearchConfig()) }
            CompositionLocalProvider(LocalCellEditPopupHost provides popups) {
                SearchWindow(
                    state = state,
                    openers = SearchRowOpeners(
                        onOpenTaskEdit = {}, onOpenCategory = {}, onOpenPeriodKind = {}, onEditAlarmOrTimer = {}, onEditReminder = {},
                    ),
                    onStartTaskNow = {},
                    onGoToTaskTree = { _, _ -> },
                    onDeepCopyCell = {},
                    actionHandlers = AddedActionHandlers(
                        onStartNow = {}, onEdit = {}, onGoToTaskTree = {}, onDeepCopyCell = {}, onOpenResilienceSearch = {},
                        onOpenKindSearch = {}, timerRun = { _, _ -> }, reminderEditor = {}, onCreate = {},
                        onDuplicate = { _, _ -> emptyList() }, onPlaceOnCalendar = {},
                    ),
                    onIntent = {
                        state = SchedulerReducer.reduce(state, it)
                        live = state
                    },
                    onDismiss = {},
                    config = config,
                    onConfigChange = { config = it },
                    onOpenConfigurations = {},
                    splits = SearchSplits(expandedResults = setOf(org.example.project.scheduler.model.WellKnownIds.ROOT_TASK.value)),
                    initialSize = Size(1200f, 620f),
                )
            }
        } }
        var time = 0L
        fun frame() = onEdt { time += 16_000_000L; scene.render(time) }
        fun settle() = repeat(6) {
            frame()
            Thread.sleep(20)
        }
        fun key(key: Key) {
            onEdt {
                scene.sendKeyEvent(KeyEvent(key = key, type = KeyEventType.KeyDown))
                scene.sendKeyEvent(KeyEvent(key = key, type = KeyEventType.KeyUp))
            }
            settle()
        }
        fun texts(title: String) = onEdt {
            scene.semanticsOwners.flatMap { it.getAllSemanticsNodes(mergingEnabled = false) }
                .filter { n -> n.config.getOrNull(SemanticsProperties.Text)?.any { it.text == title } == true }
        }
        try {
            repeat(30) {
                frame()
                Thread.sleep(30)
            }
            val treeTitle = live.tasks.getValue(treeTask).title
            val at = texts(treeTitle).first().boundsInWindow.center
            onEdt {
                scene.sendPointerEvent(androidx.compose.ui.input.pointer.PointerEventType.Move, at)
                scene.sendPointerEvent(androidx.compose.ui.input.pointer.PointerEventType.Press, at)
            }
            settle()
            onEdt { scene.sendPointerEvent(androidx.compose.ui.input.pointer.PointerEventType.Release, at) }
            repeat(4) { settle() }
            assertEquals(tree, live.searchSelection.main, "the click selected the cell")
            assertTrue(texts(treeTitle).size >= 2, "its task stays in the added elements: ${texts(treeTitle).size}")
        } finally {
            onEdt { scene.close() }
        }
    }

    @Test
    fun the_roots_children_are_walked_selected_and_opened_as_any_rows() {
        var account = org.example.project.scheduler.domain.TaskTreesAsTasks.seeded(SchedulerState.empty(), "tree-2026-11-19")
        val tree = account.lists[account.rootListId]!!.cellIds.first { account.cells[it]?.taskId != null }
        val treeTask = account.cells[tree]!!.taskId!!
        val kidCell = account.lists[account.tasks[treeTask]!!.childListId!!]!!.cellIds[0]
        account = SchedulerReducer.reduce(account, SchedulerIntent.SetCellTitle(kidCell, "Kid"))
        val popups = CellEditPopupHost()
        var live = account
        val scene = onEdt { ImageComposeScene(1200, 620, density = Density(1f)) {
            var state by remember { mutableStateOf(account) }
            var config by remember { mutableStateOf(SearchDomain.rootSearchConfig()) }
            CompositionLocalProvider(LocalCellEditPopupHost provides popups) {
                SearchWindow(
                    state = state,
                    openers = SearchRowOpeners(
                        onOpenTaskEdit = {}, onOpenCategory = {}, onOpenPeriodKind = {}, onEditAlarmOrTimer = {}, onEditReminder = {},
                    ),
                    onStartTaskNow = {},
                    onGoToTaskTree = { _, _ -> },
                    onDeepCopyCell = {},
                    actionHandlers = AddedActionHandlers(
                        onStartNow = {}, onEdit = {}, onGoToTaskTree = {}, onDeepCopyCell = {}, onOpenResilienceSearch = {},
                        onOpenKindSearch = {}, timerRun = { _, _ -> }, reminderEditor = {}, onCreate = {},
                        onDuplicate = { _, _ -> emptyList() }, onPlaceOnCalendar = {},
                    ),
                    onIntent = {
                        state = SchedulerReducer.reduce(state, it)
                        live = state
                    },
                    onDismiss = {},
                    config = config,
                    onConfigChange = { config = it },
                    onOpenConfigurations = {},
                    splits = SearchSplits(expandedResults = setOf(org.example.project.scheduler.model.WellKnownIds.ROOT_TASK.value)),
                    initialSize = Size(1200f, 620f),
                )
            }
        } }
        var time = 0L
        fun frame() = onEdt { time += 16_000_000L; scene.render(time) }
        fun settle() = repeat(6) {
            frame()
            Thread.sleep(20)
        }
        fun key(key: Key) {
            onEdt {
                scene.sendKeyEvent(KeyEvent(key = key, type = KeyEventType.KeyDown))
                scene.sendKeyEvent(KeyEvent(key = key, type = KeyEventType.KeyUp))
            }
            settle()
        }
        try {
            repeat(30) {
                frame()
                Thread.sleep(30)
            }
            var steps = 0
            while (live.searchSelection.main == null && steps < 4) {
                key(Key.DirectionDown)
                steps++
            }
            assertEquals(tree, live.searchSelection.main, "↓ on the root's row selects its first child, the tree")
            // Anomaly 2026-11-19: *"I select a task element, but nothing happens in the action section and 'added elements'
            // list section"* — the cell selected is a selected row: its task is in the added elements while it is.
            val treeTitle = live.tasks.getValue(treeTask).title
            val shown = onEdt {
                scene.semanticsOwners.flatMap { it.getAllSemanticsNodes(mergingEnabled = false) }
                    .count { n -> n.config.getOrNull(SemanticsProperties.Text)?.any { it.text == treeTitle } == true }
            }
            assertTrue(shown >= 2, "the cell in the sub-tree, and its task in the added elements: $shown")
            // …and it stays (anomaly 2026-11-19: *"It blinks when I select a row"*): frames later, still there.
            settle()
            settle()
            val still = onEdt {
                scene.semanticsOwners.flatMap { it.getAllSemanticsNodes(mergingEnabled = false) }
                    .count { n -> n.config.getOrNull(SemanticsProperties.Text)?.any { it.text == treeTitle } == true }
            }
            assertTrue(still >= 2, "still in the added elements frames later: $still")
            // On along the root's children, and back, as in the tree.
            key(Key.DirectionDown)
            assertTrue(live.searchSelection.main != tree, "↓ walks on along the root's children")
            key(Key.DirectionUp)
            assertEquals(tree, live.searchSelection.main, "↑ walks back")

        } finally {
            onEdt { scene.close() }
        }
    }

    /** The same through the reducer: a gesture in the root's sub-tree is the live tree's — opened, selected, renamed. */
    @Test
    fun the_roots_sub_tree_is_the_live_tree() {
        val s = org.example.project.scheduler.domain.TaskTreesAsTasks.seeded(SchedulerState.empty(), "tree-x")
        val root = org.example.project.scheduler.model.WellKnownIds.ROOT_TASK
        assertTrue(org.example.project.scheduler.domain.SchedulerDomain.hasLiveSubtree(s, root))
        val list = s.tasks[root]!!.childListId!!
        val tree = s.lists[list]!!.cellIds.first()
        fun inRoot(state: SchedulerState, intent: SchedulerIntent) = SchedulerReducer.reduce(state, SchedulerIntent.InSearchSubtree(intent, list, readOnly = false))
        val selected = inRoot(s, SchedulerIntent.RevealCell(tree))
        assertEquals(tree, selected.searchSelection.main)
        assertTrue(tree in inRoot(selected, SchedulerIntent.ToggleExpand(tree)).searchExpanded[""].orEmpty(), "its children open")
        assertEquals(s.lists[list]!!.parentCellId, selected.lists[list]!!.parentCellId, "the root cell's link is put back")
        // Read-only (a task cut from the tree), a look still selects: held against the projection.
        val looked = SchedulerReducer.reduce(s, SchedulerIntent.InSearchSubtree(SchedulerIntent.RevealCell(tree), list, readOnly = true))
        assertEquals(tree, looked.searchSelection.main)
    }

    /**
     * Anomaly 2026-11-19: *"I turned 'Menu keeps open rows' off, expanded some task rows, closed the window and clicked on
     * 'Task trees' again, but the task rows are expanded as when I closed the window"*. The cells a sub-tree opens are its
     * window's own: another window shows none of them, and a window opened afresh shows what it is given.
     */
    @Test
    fun the_cells_a_sub_tree_opens_are_its_windows_own() {
        val s = org.example.project.scheduler.domain.TaskTreesAsTasks.seeded(SchedulerState.empty(), "tree-x")
        val list = s.tasks[org.example.project.scheduler.model.WellKnownIds.ROOT_TASK]!!.childListId!!
        val tree = s.lists[list]!!.cellIds.first()
        val opened = SchedulerReducer.reduce(s, SchedulerIntent.InSearchSubtree(SchedulerIntent.ToggleExpand(tree), list, readOnly = false, window = "Search#5"))
        assertTrue(tree in opened.searchExpanded["Search#5"].orEmpty())
        assertTrue(opened.searchExpanded["Search#6"].isNullOrEmpty(), "another window opens none of it")
        assertTrue(tree !in opened.projectSearchSubtree(list, "Search#6").expanded)
        // Closed (or opened afresh from a button that keeps none): none.
        val closed = SchedulerReducer.reduce(opened, SchedulerIntent.SetSearchExpanded("Search#5", emptySet()))
        assertTrue(closed.searchExpanded["Search#5"].orEmpty().isEmpty())
        // What a button kept is what its window opens with, and what the window keeps with its rows.
        val rows = org.example.project.ui.ButtonExpandedRows.of(org.example.project.ui.SearchSplits(expandedCells = setOf(tree.value)))
        assertEquals(listOf(tree.value), rows.cells)
        assertEquals(setOf(tree.value), rows.applyTo(org.example.project.ui.SearchSplits()).expandedCells)
    }
}
