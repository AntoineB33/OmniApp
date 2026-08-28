package org.example.project.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.delay
import org.example.project.scheduler.domain.TaskColorSpace
import org.example.project.scheduler.model.TaskId
import org.example.project.scheduler.state.SchedulerState

/**
 * **The previous colour solution, and the one place a new one is computed.**
 *
 * [TaskColorSpace.hues] is pure, and it takes the answer it should stay close to. Somebody has to hold that
 * answer between two edits, and there must be exactly ONE holder per tree: the tie-breaks are settled
 * against it, so two surfaces each remembering their own would settle a tie differently and the task tree's
 * cell and the calendar's panel would start disagreeing about what colour a task is — the very thing
 * [TaskPalette] exists to prevent.
 *
 * So the memo also **caches the answer** for the tree it last computed: the second surface to ask about a
 * given tree is handed the identical map rather than an independently derived one. The identity holds by
 * construction and not by two call sites being kept in step.
 *
 * The cache key is `cells`/`lists`/`tasks` and nothing else — the advance tick replaces the state object
 * every second (records live on the tasks), and re-walking the tree on each of those is exactly the
 * per-tick cost ADR 0009's display hot path forbids.
 *
 * There is one instance per tree the app draws: [account] for the account's own tree (shared by the task
 * tree and the calendar), and a private one for the PRD §4 default-sub-tree template, whose state is a
 * different tree entirely.
 */
class TaskHueMemo {

    private var key: Key? = null
    private var last: Map<TaskId, TaskColorSpace.TaskHue> = emptyMap()

    /**
     * This tree's colours: the cached answer if it has already been computed, otherwise a fresh one, solved
     * to stay as close as it can to the answer before it.
     */
    fun hues(state: SchedulerState): Map<TaskId, TaskColorSpace.TaskHue> {
        val asked = Key(state)
        if (asked == key) return last
        last = TaskColorSpace.hues(state, previous = last)
        key = asked
        return last
    }

    /** What a colour is a function of. Everything else about a [SchedulerState] leaves the answer alone. */
    private data class Key(val cells: Any?, val lists: Any?, val tasks: Any?) {
        constructor(state: SchedulerState) : this(state.cells, state.lists, state.tasks)
    }

    companion object {
        /** The account's own tree — asked by both the task tree and the calendar, so they cannot diverge. */
        val account: TaskHueMemo = TaskHueMemo()

        /**
         * How long the tree must hold still before the colours follow it. A structural edit is rarely a
         * single event — typing a title, pasting a sub-tree or dragging a block of cells walks through a
         * dozen intermediate trees, and repainting every row at each of them is both a flicker and a walk
         * of the whole tree per keystroke.
         */
        const val DEBOUNCE_MILLIS: Long = 400L
    }
}

/**
 * Each task's place in the colour space, recomputed when the tree changes and **not before it settles**.
 *
 * The first composition is answered at once (an uncoloured tree flashing into colour is worse than a late
 * repaint); every later change waits [TaskHueMemo.DEBOUNCE_MILLIS] of quiet. Both surfaces reading one
 * [memo] watch the same key and wait the same time, so they land on the same frame — and even if they did
 * not, the memo hands them the same map.
 */
@Composable
internal fun rememberTaskHues(
    state: SchedulerState,
    memo: TaskHueMemo = TaskHueMemo.account,
): Map<TaskId, TaskColorSpace.TaskHue> {
    var hues by remember(memo) { mutableStateOf(memo.hues(state)) }
    LaunchedEffect(memo, state.cells, state.lists, state.tasks) {
        delay(TaskHueMemo.DEBOUNCE_MILLIS)
        hues = memo.hues(state)
    }
    return hues
}
