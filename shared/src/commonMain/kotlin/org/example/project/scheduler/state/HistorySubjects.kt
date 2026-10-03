package org.example.project.scheduler.state

import org.example.project.scheduler.model.TaskId

/**
 * The keys of the History Units of what `App` keeps outside the state ([ExternalDelta.key]): the one statement of
 * them, read by `App` when it records such a unit and by [subject] when the Search window asks what one changed.
 */
object ExternalKeys {
    /** The lateral menu's buttons the user made. */
    const val MENU: String = "menu"

    /** A Search window's configuration, followed by its frame id (`search/Search`, `search/Search#2`). */
    const val SEARCH: String = "search/"

    /** A window's layout, followed by its frame id. */
    const val WINDOW: String = "window/"
}

/**
 * PRD §6 (user rule 2026-10-03): **what a History Unit CHANGED** — the Search window's "Changes" filter for history
 * units. The window a unit was MADE in ([HistoryUnit.window], the "Made in" filter) is where the user was; this is
 * what the change is about, which the two windows of the search configuration share, for one: a change to a
 * Search window's configuration is one whether it was made in that window, in its Configuration Search window or in
 * its Added elements configurations window.
 */
enum class HistorySubject(val label: String) {
    Tree("tasks of the tree"),
    Expansion("expansion"),
    Selection("selection"),
    Focus("window focus"),
    Calendar("calendar"),
    Records("work records"),
    Sleep("sleep schedule"),
    Alarms("alarms"),
    Timers("timers"),
    Chronos("chronos"),
    TaskTrees("task trees"),
    DefaultSubtree("default sub-tree"),
    Shortcuts("keyboard shortcuts"),
    Settings("account settings"),
    SearchConfiguration("search configuration"),
    WindowLayout("window layout"),
    MenuButtons("lateral menu buttons"),
    Other("other"),
}

/** What this unit's change is about ([HistorySubject]) — read off the delta, never stored. */
val Delta.subject: HistorySubject
    get() =
        when (this) {
            is TreeMutationDelta, is EmptyCellsDelta -> HistorySubject.Tree
            is SetExpandedDelta, is ToggleExpandDelta -> HistorySubject.Expansion
            is SetSelectionDelta, is ViewSelectionDelta, is WindowSelectionDelta -> HistorySubject.Selection
            is FocusDelta -> HistorySubject.Focus
            is PanelDelta -> HistorySubject.Calendar
            is RecordDelta -> HistorySubject.Records
            is SleepDelta -> HistorySubject.Sleep
            is AlarmsDelta -> HistorySubject.Alarms
            is TimersDelta -> HistorySubject.Timers
            is ChronosDelta -> HistorySubject.Chronos
            is TaskTreeDelta -> HistorySubject.TaskTrees
            is DefaultSubtreeDelta -> HistorySubject.DefaultSubtree
            is ShortcutBindingDelta -> HistorySubject.Shortcuts
            is SettingsDelta -> HistorySubject.Settings
            is ExternalDelta ->
                when {
                    key.startsWith(ExternalKeys.SEARCH) -> HistorySubject.SearchConfiguration
                    key.startsWith(ExternalKeys.WINDOW) -> HistorySubject.WindowLayout
                    key == ExternalKeys.MENU -> HistorySubject.MenuButtons
                    else -> HistorySubject.Other
                }
            else -> HistorySubject.Other
        }

/**
 * PRD §6 (user rule 2026-10-03): **whether this unit changed the task [taskId]** — the Search window's "Changed task"
 * filter. A change TO the task: its own fields, a cell of it added, removed or re-pointed, a block or a record of it, a
 * setting that reached it (a deleted kind takes every task's value for it), the default sub-tree's edit of the live
 * tree, a tree switch that brought it in or took it out. A selection or an expansion of its cell is not one: it
 * changes how the task is LOOKED at.
 */
fun Delta.touchesTask(taskId: TaskId): Boolean =
    when (this) {
        is TreeMutationDelta -> diff.touchesTask(taskId)
        is EmptyCellsDelta -> diff.touchesTask(taskId)
        is PanelDelta -> changes.before.values.any { it.taskId == taskId } || changes.after.values.any { it.taskId == taskId }
        is RecordDelta -> taskId in changes.tasks
        is DefaultSubtreeDelta -> live.touchesTask(taskId) || tree.touchesTask(taskId)
        is TaskTreeDelta -> diff.live.touchesTask(taskId)
        is SettingsDelta ->
            tree?.touchesTask(taskId) == true ||
                panels.before.values.any { it.taskId == taskId } || panels.after.values.any { it.taskId == taskId }
        else -> false
    }

/** A tree diff keeps only what differs, so a task is touched when it is in it or a cell that changed points at it. */
private fun TreeDiff.touchesTask(taskId: TaskId): Boolean =
    taskId in tasks.touched ||
        cells.before.values.any { it.taskId == taskId } || cells.after.values.any { it.taskId == taskId }
