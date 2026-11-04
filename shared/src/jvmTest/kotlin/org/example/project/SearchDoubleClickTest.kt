package org.example.project

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.unit.Density
import kotlin.test.Test
import kotlin.test.assertEquals
import org.example.project.quota.QuotaScenario
import org.example.project.scheduler.domain.SearchDomain
import org.example.project.scheduler.domain.SearchDomain.Kind
import org.example.project.scheduler.domain.WorkplaceOs
import org.example.project.scheduler.domain.WorkplaceSetup
import org.example.project.scheduler.domain.WorkplaceWindow
import org.example.project.scheduler.state.SchedulerReducer
import org.example.project.ui.AddedActionHandlers
import org.example.project.ui.SearchRowOpeners
import org.example.project.ui.SearchWindow

/**
 * A double-click on the rows of the Search window's results. Anomaly 2026-11-01: *"it doesn't create a new workplace set
 * when double-clicking"*: the creation row of a list holding one setup, which that setup's own double-click applies.
 */
class SearchDoubleClickTest {
    private fun <T> onEdt(block: () -> T): T {
        var result: Result<T>? = null
        javax.swing.SwingUtilities.invokeAndWait { result = runCatching(block) }
        return result!!.getOrThrow()
    }

    private val setup = WorkplaceSetup("workplace-1", "OmniApp project", WorkplaceOs.Windows11, listOf(WorkplaceWindow(listOf("C:\\code"))))

    /** Every row's middle in the first rows of the list, top to bottom: what a double-click on each did. */
    private fun doubleClickOn(y: Float): List<String> {
        val told = ArrayList<String>()
        val account = QuotaScenario.account().copy(workplaceSetups = listOf(setup))
        val scene = onEdt { ImageComposeScene(1200, 620, density = Density(1f)) {
            var state by remember { mutableStateOf(account) }
            var config by remember {
                mutableStateOf(SearchDomain.Config(kinds = setOf(Kind.WorkplaceSetup), added = listOf("WorkplaceSetup/workplace-1")))
            }
            SearchWindow(
                state = state,
                openers = SearchRowOpeners(
                    onOpenTaskEdit = {}, onOpenCategory = {}, onOpenPeriodKind = {}, onEditAlarmOrTimer = {}, onEditReminder = {},
                    onCreate = { told += "create $it" },
                    onApplyWorkplaceSetup = { told += "apply $it" },
                ),
                onStartTaskNow = {},
                onGoToTaskTree = { _, _ -> },
                onDeepCopyCell = {},
                actionHandlers = AddedActionHandlers(
                    onStartNow = {}, onEdit = {}, onGoToTaskTree = {}, onDeepCopyCell = {}, onOpenResilienceSearch = {},
                    onOpenKindSearch = {}, timerRun = { _, _ -> }, reminderEditor = {}, onCreate = {},
                    onDuplicate = { _, _ -> emptyList() }, onPlaceOnCalendar = {},
                ),
                onIntent = { state = SchedulerReducer.reduce(state, it) },
                onDismiss = {},
                config = config,
                onConfigChange = { config = it; told += "added ${it.added}" },
                onOpenConfigurations = {},
                initialSize = Size(1200f, 620f),
            )
        } }
        var time = 0L
        fun frame() = onEdt { time += 16_000_000L; scene.render(time) }
        try {
            repeat(4) { frame() }
            val at = Offset(300f, y)
            fun press(ms: Long) = onEdt {
                scene.sendPointerEvent(PointerEventType.Move, at, timeMillis = ms)
                scene.sendPointerEvent(PointerEventType.Press, at, timeMillis = ms)
            }
            fun release(ms: Long) = onEdt {
                scene.sendPointerEvent(PointerEventType.Release, at, timeMillis = ms)
            }
            press(1000); frame(); release(1050); frame(); frame()
            press(1120); frame(); release(1170); repeat(4) { frame() }
        } finally {
            onEdt { scene.close() }
        }
        return told
    }

    @Test
    fun the_creation_row_makes_one_and_a_setup_applies() {
        // Each on its own row, whichever the list's order: the creation row is never taken for a setup.
        // Down the list's first rows (where they stand moves with the header): each row does one thing alone.
        val done = (90..240 step 10).map { doubleClickOn(it.toFloat()) }.filter { it.isNotEmpty() }.toSet()
        assertEquals(setOf(listOf("create WorkplaceSetup"), listOf("apply workplace-1")), done)
    }
}
