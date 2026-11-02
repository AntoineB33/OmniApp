package org.example.project

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import javax.swing.SwingUtilities
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.example.project.scheduler.domain.SearchDomain
import org.example.project.scheduler.model.ChronoEntry
import org.example.project.scheduler.model.TimerEntry
import org.example.project.scheduler.state.SchedulerState
import org.example.project.scheduler.ui.TASK_ROW_MIN_HEIGHT
import org.example.project.ui.AddedActionHandlers
import org.example.project.ui.MenuActionItem

/**
 * Anomaly 2026-11-02: *"when the added elements list is several timers, the Lowest countdown group title is overlapped
 * by the text of the group when expanded"*. An action's editor may draw several blocks one after the other, and both
 * callers that held it in a `Box` stacked them on one another: "Lowest countdown" and "Highest count" (their title line
 * under what follows it) in the actions section, and every such action as an item of the lateral menu.
 */
class ActionEditorLayoutTest {
    private fun <T> onEdt(block: () -> T): T {
        var out: Result<T>? = null
        SwingUtilities.invokeAndWait { out = runCatching(block) }
        return out!!.getOrThrow()
    }

    private val state =
        SchedulerState.empty().copy(
            timers = listOf(TimerEntry(id = "timer-0", label = "Tea"), TimerEntry(id = "timer-1", label = "Eggs")),
            chronos = listOf(ChronoEntry(id = "chrono-0", label = "Run"), ChronoEntry(id = "chrono-1", label = "Walk")),
        )

    /** How far below the top of the lateral menu's item [action] over [kind]'s elements the run of the first one stands. */
    private fun runOffset(action: SearchDomain.AddedAction, kind: SearchDomain.Kind): Float {
        val added = SearchDomain.results(state, setOf(kind), "")
        val config = SearchDomain.Config(added = added.map(SearchDomain::keyOf)).encode()
        var top: Float? = null
        var run: Float? = null
        val handlers = AddedActionHandlers(
            onStartNow = {}, onEdit = {}, onGoToTaskTree = {}, onDeepCopyCell = {}, onOpenResilienceSearch = {},
            onOpenKindSearch = {},
            timerRun = { _, _ -> Box(Modifier.size(40.dp).onGloballyPositioned { run = it.positionInRoot().y }) },
            reminderEditor = {}, onCreate = {}, onDuplicate = { _, _ -> emptyList() }, onPlaceOnCalendar = {},
        )
        val content: @Composable () -> Unit = {
            MaterialTheme {
                Box(Modifier.fillMaxSize().onGloballyPositioned { top = it.positionInRoot().y }) {
                    MenuActionItem(state, action.name, action.label, config, {}, handlers, {}, { 0L }, {})
                }
            }
        }
        val scene = onEdt { ImageComposeScene(800, 600, density = Density(1f), content = content) }
        try {
            repeat(3) { onEdt { scene.render() } }
        } finally {
            onEdt { scene.close() }
        }
        return assertNotNull(run) - assertNotNull(top)
    }

    /** The item's title, then two rows — the buttons, the drop-down — before the run: one row when they are stacked. */
    private val twoRowsDown = 16f + 4f + TASK_ROW_MIN_HEIGHT.value * 1.5f + 6f

    @Test
    fun theLowestCountdownsButtonsDoNotLieOnWhatFollowsThem() {
        val offset = runOffset(SearchDomain.AddedAction.TimerLowestCountdown, SearchDomain.Kind.Timer)
        assertTrue(offset >= twoRowsDown, "the run stands $offset below the top: its two rows lie on one another")
    }

    @Test
    fun theHighestCountsButtonsDoNotLieOnWhatFollowsThem() {
        val offset = runOffset(SearchDomain.AddedAction.ChronoHighestCount, SearchDomain.Kind.Chrono)
        assertTrue(offset >= twoRowsDown, "the run stands $offset below the top: its two rows lie on one another")
    }
}
