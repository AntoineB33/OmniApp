package org.example.project.scheduler.domain

import org.example.project.scheduler.model.TaskId
import org.example.project.scheduler.model.TaskRelationKey
import org.example.project.scheduler.model.TaskRelationMark
import org.example.project.scheduler.model.WellKnownIds
import org.example.project.scheduler.state.SchedulerState

/**
 * PRD §5 the **task relations** window (the lateral menu's *Task relations* button): every *pair* of a task
 * and the task its priority was expressed **relative to**, in four sections.
 *
 * The tree shows one number per task — its share of the whole account. A *relation* is the other question
 * the priority machinery keeps asking and then forgetting: *how much of THIS sub-tree is that task?* The
 * user reaches it from two places — an **optional row** of a sub-list's priority-weight table (PRD §5), and
 * the **relative-priority window**'s `t_r` drop-down — and both leave a pair behind them. This window is
 * where those pairs are collected, so the ones that matter can be kept and the rest recognised as noise.
 *
 * The four sections are a precedence, applied in this order (a pair is in exactly one of them):
 *
 * 1. [Section.Kept] — the user filed it here by hand ([TaskRelationMark.kept]). It is the only section
 *    nothing puts a pair into on its own, and the only one whose button *removes* rather than promotes.
 * 2. [Section.Edited] — the user acted on the pair: it is a live optional row of the target sub-list's
 *    weight table, or the last relative-priority window opened on it left its percentage changed.
 * 3. [Section.Opened] — the relative-priority window has been opened on it and left it as it was (never
 *    touched, or typed at and put back).
 * 4. [Section.Broken] — the pair cannot be read any more: one of the two tasks is gone, or the task no
 *    longer has any occurrence under the target. **Broken outranks the other three**, section 1 included:
 *    it is a *status*, not an origin, and a pair the user filed by hand is exactly the one they most need
 *    to be told has stopped meaning anything. A broken pair the user had kept still shows section 1's
 *    button (see [Row.kept]), so filing it and un-filing it stay the same gesture wherever it is drawn.
 *
 * A **hidden** mark ([TaskRelationMark.hidden]) drops the pair from every section — that is what section
 * 1's button does — and touching the pair again from the relative-priority window brings it back.
 *
 * Nothing here is stored that can be recomputed: the weight-table half of section 2 is read straight off
 * `CellList.optionalTaskIds`, so a row the user removes from a table stops being listed by itself, and the
 * whole of section 4 is a question asked of the live tree.
 */
object TaskRelationsDomain {

    /** Which of the window's four sections a pair belongs to. Declaration order is display order. */
    enum class Section { Kept, Edited, Opened, Broken }

    /** Why a [Section.Broken] pair can no longer be read. */
    enum class Break {
        /** The task itself is gone — deleted (PRD §4 blanks its title) or never there at all. */
        TaskGone,

        /** The target sub-tree's task is gone the same way. */
        TargetGone,

        /** Both still exist, but the task has no occurrence under the target any more. */
        Moved,
    }

    /** One line of the window: the pair, where it sits, and everything the row has to say about it. */
    data class Row(
        val key: TaskRelationKey,
        val section: Section,
        /** The task's title, or the tree's own placeholder for a blank one. */
        val taskTitle: String,
        /** The target's title; the root list is named `root`, as the relative-priority drop-down names it. */
        val targetTitle: String,
        /** True while the pair is filed in section 1 — which is what decides its button either way. */
        val kept: Boolean,
        /** It is a live optional row of the target sub-list's priority-weight table. */
        val inWeightTable: Boolean,
        /** The last relative-priority window opened on it left its percentage changed. */
        val retargeted: Boolean,
        /** Null unless [section] is [Section.Broken]. */
        val broken: Break?,
    )

    /**
     * Every pair the window lists, section by section, then by title. Ties fall back to the ids so the order
     * cannot depend on a map's iteration order (the same rule the "All tasks" list follows).
     */
    fun rows(state: SchedulerState): List<Row> {
        val fromTables = weightTableRelations(state)
        val keys = state.taskRelations.keys + fromTables
        val rows = mutableListOf<Row>()
        for (key in keys) {
            val mark = state.taskRelations[key]
            if (mark?.hidden == true) continue
            val kept = mark?.kept == true
            val retargeted = mark?.retargeted == true
            val inWeightTable = key in fromTables
            val broken = breakOf(state, key)
            val section =
                when {
                    broken != null -> Section.Broken
                    kept -> Section.Kept
                    inWeightTable || retargeted -> Section.Edited
                    else -> Section.Opened
                }
            rows.add(
                Row(
                    key = key,
                    section = section,
                    taskTitle = label(state, key.taskId),
                    targetTitle = label(state, key.relativeTo),
                    kept = kept,
                    inWeightTable = inWeightTable,
                    retargeted = retargeted,
                    broken = broken,
                ),
            )
        }
        return rows.sortedWith(
            compareBy(
                { it.section.ordinal },
                { it.taskTitle.lowercase() },
                { it.targetTitle.lowercase() },
                { it.key.taskId.value },
                { it.key.relativeTo.value },
            ),
        )
    }

    /**
     * The pairs section 2 derives rather than stores: every task the user added by hand as an **optional
     * row** of a sub-list's priority-weight table, paired with that sub-list's own parent task.
     *
     * Derived on purpose — the user removing the row from the table is what takes the pair off this list,
     * with nothing to keep in step (CLAUDE.md § *State*).
     */
    fun weightTableRelations(state: SchedulerState): Set<TaskRelationKey> {
        val result = mutableSetOf<TaskRelationKey>()
        for ((listId, list) in state.lists) {
            if (list.optionalTaskIds.isEmpty()) continue
            val parentTask = SchedulerDomain.parentTaskIdOfList(state, listId) ?: continue
            for (taskId in list.optionalTaskIds) {
                if (taskId == parentTask) continue
                result.add(TaskRelationKey(taskId, parentTask))
            }
        }
        return result
    }

    /**
     * Why [key] can no longer be read, or null while it still can. The three answers are the user's own
     * wording — *the task or the relational target doesn't exist anymore, or moved* — and the last of them
     * is asked of the tree the same way the relative-priority window itself asks it
     * ([RelativePriorityDomain.occurrenceChains]), so a pair is broken here exactly when that window would
     * open on "no occurrence of this task under …".
     */
    fun breakOf(state: SchedulerState, key: TaskRelationKey): Break? = when {
        !exists(state, key.taskId) -> Break.TaskGone
        !exists(state, key.relativeTo) -> Break.TargetGone
        key.taskId == key.relativeTo -> Break.Moved
        RelativePriorityDomain.occurrenceChains(state, key.taskId, key.relativeTo).isEmpty() -> Break.Moved
        else -> null
    }

    /**
     * PRD §4: the blank title is what deletes, so a titled task is a live one — and the root is always there
     * (it is the tree itself, not a row in it).
     */
    private fun exists(state: SchedulerState, taskId: TaskId): Boolean =
        taskId == WellKnownIds.MAIN_TASK || state.tasks[taskId]?.title?.isNotBlank() == true

    /** How a pair's two halves are named — the same two answers the relative-priority window prints. */
    fun label(state: SchedulerState, taskId: TaskId): String =
        if (taskId == WellKnownIds.MAIN_TASK) {
            ROOT_LABEL
        } else {
            state.tasks[taskId]?.title.orEmpty().ifBlank { UNTITLED_LABEL }
        }

    /** The relative-priority drop-down's own name for the root list. */
    const val ROOT_LABEL: String = "root"

    /** The tree's own placeholder for a cell whose task has no title. */
    const val UNTITLED_LABEL: String = "(untitled)"
}
