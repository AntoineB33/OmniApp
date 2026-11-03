package org.example.project

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.unit.Density
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import org.example.project.scheduler.model.CellId
import org.example.project.scheduler.model.TaskId
import org.example.project.scheduler.state.CellEditMode
import org.example.project.scheduler.state.EditExitNavigation
import org.example.project.scheduler.state.SchedulerIntent
import org.example.project.scheduler.state.SchedulerReducer
import org.example.project.scheduler.state.SchedulerState
import org.example.project.ui.AddedActionHandlers
import org.example.project.ui.CellEditPopupHost
import org.example.project.ui.CellEditPopupLayer
import org.example.project.ui.CellEditPopupRequest
import org.example.project.ui.LocalCellEditPopupHost
import org.example.project.ui.LocalTransientMenuHost
import org.example.project.ui.PublishCellEditPopup
import org.example.project.ui.SearchRowOpeners
import org.example.project.ui.SearchWindow
import org.example.project.ui.TransientMenuHost
import org.example.project.ui.rememberOutsidePressEditor
import org.example.project.ui.transientMenuDismissRoot

/**
 * User rule 2026-11-02: *"a single click on an element in the search result list replaces the current task id with the
 * clicked one, and it leaves edit mode"*, and *"Clicking an option writes the option in the search bar, but the
 * completion list is still there."* Drawn off screen, in the real layer over the real Search window: the row of the tree
 * is stood for by a publication of the session it would make.
 */
class CellEditPopupTest {
    private fun <T> onEdt(block: () -> T): T {
        var result: Result<T>? = null
        javax.swing.SwingUtilities.invokeAndWait { result = runCatching(block) }
        return result!!.getOrThrow()
    }

    private fun r(state: SchedulerState, intent: SchedulerIntent) = SchedulerReducer.reduce(state, intent)

    private inner class Harness(typed: String, mode: CellEditMode? = null) {
        val apple: CellId
        val kiwi: TaskId
        var state by mutableStateOf(SchedulerState.empty())
        private var time = 0L
        private val menus = TransientMenuHost()
        private val popups = CellEditPopupHost()
        val scene: ImageComposeScene

        init {
            var s = SchedulerState.empty()
            apple = s.lists[s.rootListId]!!.cellIds.last()
            s = r(s, SchedulerIntent.SetCellTitle(apple, "Apple"))
            val pearCell = s.lists[s.rootListId]!!.cellIds.last()
            s = r(s, SchedulerIntent.SetCellTitle(pearCell, "Pear"))
            val pear = s.cells[pearCell]!!.taskId!!
            val kiwiCell = s.lists[s.tasks[pear]!!.childListId!!]!!.cellIds.last()
            s = r(s, SchedulerIntent.SetCellTitle(kiwiCell, "Kiwi"))
            kiwi = s.cells[kiwiCell]!!.taskId!!
            s = r(s, SchedulerIntent.SetCellTitle(s.lists[s.tasks[pear]!!.childListId!!]!!.cellIds.last(), "Pear tart"))
            s = r(s, SchedulerIntent.BeginEdit(apple, initialText = typed.ifEmpty { null }, mode = mode))
            state = s
            scene = onEdt {
                ImageComposeScene(900, 700, density = Density(1f)) {
                    CompositionLocalProvider(
                        LocalTransientMenuHost provides menus,
                        LocalCellEditPopupHost provides popups,
                    ) {
                        Box(Modifier.fillMaxSize().transientMenuDismissRoot(menus)) {
                            if (state.editSession != null) {
                                val region = rememberOutsidePressEditor(true) { state = r(state, SchedulerIntent.ExitEdit(EditExitNavigation.Stay)) }
                                PublishCellEditPopup(
                                    CellEditPopupRequest(
                                        state = state,
                                        namingSource = state,
                                        cellId = apple,
                                        onIntent = { state = r(state, it) },
                                        refocusField = {},
                                        anchor = { Rect(20f, 20f, 200f, 44f) },
                                        region = region,
                                    ),
                                )
                            }
                            CellEditPopupLayer(popups) { config, onConfigChange, picker, modifier ->
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
                                    onIntent = {},
                                    onDismiss = {},
                                    config = config,
                                    onConfigChange = onConfigChange,
                                    onOpenConfigurations = {},
                                    picker = picker,
                                    modifier = modifier,
                                )
                            }
                        }
                    }
                }
            }
            frames(6)
        }

        fun frames(count: Int) = repeat(count) { onEdt { time += 16L; scene.render(time * 1_000_000L) } }

        fun click(at: Offset, button: PointerButton = PointerButton.Primary) {
            onEdt {
                time += 16L
                scene.sendPointerEvent(PointerEventType.Move, at, timeMillis = time)
                scene.sendPointerEvent(PointerEventType.Press, at, timeMillis = time, button = button)
            }
            frames(1)
            onEdt { time += 16L; scene.sendPointerEvent(PointerEventType.Release, at, timeMillis = time, button = button) }
            frames(3)
        }

        /** Clicks along the rectangle, which starts under the cell, at y 44, until [done]. */
        fun clickDownTheRectangle(done: () -> Boolean) = clickAlong((96..470 step 6).map { it.toFloat() }, done)

        /** From the bottom: the results, which stand under the completion list and the "New task" row. */
        fun clickUpTheRectangle(done: () -> Boolean) = clickAlong((470 downTo 96 step 6).map { it.toFloat() }, done)

        private fun clickAlong(ys: List<Float>, done: () -> Boolean) {
            for (y in ys) {
                if (done()) return
                click(Offset(300f, y))
            }
        }

        fun close() = onEdt { scene.close() }
    }

    @Test
    fun a_click_on_a_result_replaces_the_task_and_leaves_edit_mode() {
        val h = Harness("Ki")
        try {
            h.clickUpTheRectangle { h.state.editSession == null }
            assertNull(h.state.editSession)
            assertEquals(h.kiwi, h.state.cells[h.apple]!!.taskId)
        } finally {
            h.close()
        }
    }

    @Test
    fun a_click_on_an_option_writes_it_in_the_bar_and_the_list_stays() {
        val h = Harness("Pe")
        try {
            h.clickDownTheRectangle { h.state.editSession?.draftText != "Pe" }
            assertEquals("Pear", assertNotNull(h.state.editSession, "an option leaves Edit Mode open").draftText)
            // The bar's text left the options and the other stayed: the list is still there, and still writes.
            h.clickDownTheRectangle { h.state.editSession?.draftText != "Pear" }
            assertEquals("Pear tart", assertNotNull(h.state.editSession).draftText)
        } finally {
            h.close()
        }
    }

    @Test
    fun in_rename_no_row_replaces_the_task() {
        val h = Harness("", CellEditMode.Rename)
        val before = h.state.cells[h.apple]!!.taskId
        try {
            h.clickUpTheRectangle { h.state.editSession == null }
            assertNotNull(h.state.editSession, "a press in the rectangle is a press in the edit")
            assertEquals(before, h.state.cells[h.apple]!!.taskId)
        } finally {
            h.close()
        }
    }

    @Test
    fun a_press_outside_the_rectangle_commits_and_ends_the_session() {
        val h = Harness("Ki")
        try {
            h.click(Offset(800f, 650f))
            assertNull(h.state.editSession)
        } finally {
            h.close()
        }
    }
}
