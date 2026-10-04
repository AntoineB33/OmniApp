package org.example.project.scheduler.state

import org.example.project.scheduler.domain.PeriodKinds
import org.example.project.scheduler.domain.SchedulerDomain
import org.example.project.scheduler.domain.SearchDomain
import org.example.project.scheduler.model.TaskPanel
import org.example.project.scheduler.platform.GlobalShortcut
import org.example.project.scheduler.platform.GlobalShortcutBindings

/**
 * The keys of the History Units of what `App` keeps outside the state ([ExternalDelta.key]): the one statement of
 * them, read by `App` when it records such a unit and by [changedElements] when the Search window asks what one changed.
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
 * One element of the app a History Unit changed, named as the Search window names its own rows ([SearchDomain.keyOf]):
 * its [kind] and its [id] there — a task's id, an alarm's, a period's kind, a window's frame id.
 */
data class ChangedElement(val kind: SearchDomain.Kind, val id: String) {
    /** The Search window's key of the element: what the "Changed element" filter holds. */
    val key: String get() = kind.name + "/" + id
}

/**
 * PRD §6 (user rule 2026-10-03): **the elements of the app this unit CHANGED** — the Search window's "Changed element
 * types" and "Changed element" filters for history units. Read off the delta, never stored. A change TO an element: a
 * task's fields, a cell re-pointed at it, a block or record of it; an alarm, timer, chrono, category, task tree or
 * shortcut edited, added or removed; a period kind (its drawing, the kind itself, a period of it on the calendar, the
 * sleep schedule for `sleep`); a reminder tag; a window's layout or — for a Search window — its configuration, whichever
 * window the change was made from. A selection, an expansion or a move of the focus changes how something is LOOKED at,
 * not the thing, so it names none.
 */
val Delta.changedElements: Set<ChangedElement>
    get() = buildSet {
        when (val delta = this@changedElements) {
            is TreeMutationDelta -> addTasksOf(delta.diff)
            is EmptyCellsDelta -> addTasksOf(delta.diff)
            is DefaultSubtreeDelta -> addTasksOf(delta.live)
            is TaskTreeDelta -> {
                val trees = delta.diff.added.map { it.id } + delta.diff.removed.map { it.id } + delta.diff.changed.map { it.id } +
                    listOfNotNull(delta.diff.activeBefore, delta.diff.activeAfter).takeIf { delta.diff.activeBefore != delta.diff.activeAfter }.orEmpty()
                trees.forEach { add(ChangedElement(SearchDomain.Kind.TaskTree, it.value)) }
                addTasksOf(delta.diff.live)
            }
            is PanelDelta -> {
                addPanels(delta.changes.before.values + delta.changes.after.values)
                delta.records.tasks.forEach { add(ChangedElement(SearchDomain.Kind.Task, it.value)) }
            }
            is RecordDelta -> delta.changes.tasks.forEach { add(ChangedElement(SearchDomain.Kind.Task, it.value)) }
            is SleepDelta -> add(ChangedElement(SearchDomain.Kind.RestrictivePeriod, PeriodKinds.SLEEP))
            is AlarmsDelta -> delta.changes.touched.forEach { add(ChangedElement(SearchDomain.Kind.Alarm, it)) }
            is TimersDelta -> delta.changes.touched.forEach { add(ChangedElement(SearchDomain.Kind.Timer, it)) }
            is ChronosDelta -> delta.changes.touched.forEach { add(ChangedElement(SearchDomain.Kind.Chrono, it)) }
            is QuotasDelta -> delta.changes.touched.forEach { add(ChangedElement(SearchDomain.Kind.Quota, it)) }
            is ShortcutBindingDelta ->
                GlobalShortcut.entries
                    .filter { GlobalShortcutBindings.chordOf(delta.before, it) != GlobalShortcutBindings.chordOf(delta.after, it) }
                    .forEach { add(ChangedElement(SearchDomain.Kind.Shortcut, it.name)) }
            is SettingsDelta -> {
                delta.kinds?.let { (b, a) -> ((a - b.toSet()) + (b - a.toSet())).forEach { add(ChangedElement(SearchDomain.Kind.RestrictivePeriod, it)) } }
                delta.styles.touched.forEach { add(ChangedElement(SearchDomain.Kind.RestrictivePeriod, it)) }
                delta.categories.touched.forEach { add(ChangedElement(SearchDomain.Kind.Category, it.value)) }
                delta.tree?.let { addTasksOf(it) }
                addPanels(delta.panels.before.values + delta.panels.after.values)
            }
            is ExternalDelta ->
                when {
                    delta.key.startsWith(ExternalKeys.SEARCH) ->
                        add(ChangedElement(SearchDomain.Kind.Window, delta.key.removePrefix(ExternalKeys.SEARCH)))
                    delta.key.startsWith(ExternalKeys.WINDOW) ->
                        add(ChangedElement(SearchDomain.Kind.Window, delta.key.removePrefix(ExternalKeys.WINDOW)))
                }
            else -> Unit
        }
    }

/** A tree diff keeps only what differs: the tasks in it, and the tasks the cells that changed point at. */
private fun MutableSet<ChangedElement>.addTasksOf(diff: TreeDiff) {
    diff.tasks.touched.forEach { add(ChangedElement(SearchDomain.Kind.Task, it.value)) }
    (diff.cells.before.values + diff.cells.after.values).forEach { cell ->
        cell.taskId?.let { add(ChangedElement(SearchDomain.Kind.Task, it.value)) }
    }
}

/** A block names its task, a reminder tag its reminder, a period its kind. */
private fun MutableSet<ChangedElement>.addPanels(panels: Collection<TaskPanel>) {
    for (panel in panels) {
        panel.taskId?.let { add(ChangedElement(SearchDomain.Kind.Task, it.value)) }
        when {
            panel.chore -> SchedulerDomain.reminderIdOfChorePanel(panel.id)?.let { add(ChangedElement(SearchDomain.Kind.Reminder, it)) }
            panel.isRestrictivePeriod -> add(ChangedElement(SearchDomain.Kind.RestrictivePeriod, panel.restrictiveKind))
        }
    }
}
