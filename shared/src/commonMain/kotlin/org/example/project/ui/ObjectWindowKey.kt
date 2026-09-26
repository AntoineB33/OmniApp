package org.example.project.ui

import org.example.project.scheduler.model.CategoryId
import org.example.project.scheduler.model.CellId
import org.example.project.scheduler.model.CellListId
import org.example.project.scheduler.model.TaskId
import org.example.project.scheduler.state.SchedulerState
import org.example.project.scheduler.state.projectDefaultSubtree
import org.example.project.scheduler.ui.DEEP_COPY_FRAME_ID
import org.example.project.scheduler.ui.PERIOD_KIND_EDIT_FRAME_ID
import org.example.project.scheduler.ui.PRIORITY_WEIGHTS_FRAME_ID
import org.example.project.scheduler.ui.RELATIVE_PRIORITY_FRAME_ID
import org.example.project.scheduler.ui.TASK_EDIT_FRAME_ID

/**
 * PRD §7: what a lateral-menu button made from a **per-object** window's ☆ names — the kind of window and the
 * object it is about — so `App` can open that window again on that object, after a restart included.
 *
 * Only the windows about an object with a stable id have one ([Kind]); a window about something transient (a
 * calendar edit draft, "elements at" a spot, a notice) has nothing a button could come back to. [template] says
 * which tree a tree window's object lives in — the account's or the default sub-tree's (PRD §4): the same id
 * means different things in the two.
 *
 * Kept, like every user-made button, on the device only ([CustomMenuButton.windowId] holds [encode]).
 */
data class ObjectWindowKey(val kind: Kind, val id: String, val template: Boolean = false) {
    /**
     * [noun] is what a button made from such a window calls it, after the object's name ([buttonTitle]).
     * [frameBase] is the window's frame id before its number (`TaskEdit#3`): the id its placement row is kept
     * under across restarts, the window's own constant.
     */
    enum class Kind(val noun: String, val frameBase: String) {
        TaskEdit("task", TASK_EDIT_FRAME_ID),
        CategoryEdit("category", CATEGORY_EDIT_FRAME_ID),
        PeriodKindEdit("period", PERIOD_KIND_EDIT_FRAME_ID),
        PriorityWeights("weights", PRIORITY_WEIGHTS_FRAME_ID),
        RelativePriority("relative priority", RELATIVE_PRIORITY_FRAME_ID),
        DeepCopy("deep copy", DEEP_COPY_FRAME_ID),
        Alarm("alarm", AlarmWindowSubject.FRAME_ID),
        Timer("timer", AlarmWindowSubject.FRAME_ID),
        Chrono("chrono", AlarmWindowSubject.FRAME_ID),
        Reminder("reminder", REMINDER_EDIT_FRAME_ID),

        /** The account's default configuration of a new alarm / timer / reminder: one each, always there. */
        AlarmDefaults("default alarm", ALARM_DEFAULTS_FRAME_ID),
        TimerDefaults("default timer", ALARM_DEFAULTS_FRAME_ID),
        ReminderDefaults("default reminder", REMINDER_DEFAULTS_FRAME_ID),
    }

    fun encode(): String = PREFIX + kind.name + ":" + (if (template) TEMPLATE else LIVE) + ":" + id

    /**
     * Whether the object is still there in [state] — the account's, projected onto the default sub-tree for a
     * [template] key. A button whose object is gone is not shown (and comes back with it, after an undo), and
     * the answer is the same one each window gives when it closes itself on a vanished object.
     */
    fun exists(state: SchedulerState): Boolean {
        val tree = if (template) state.projectDefaultSubtree() else state
        return when (kind) {
            Kind.TaskEdit -> tree.tasks[TaskId(id)] != null
            Kind.CategoryEdit -> tree.categoryById(CategoryId(id)) != null
            Kind.PeriodKindEdit -> id in tree.allPeriodKinds
            Kind.PriorityWeights -> tree.lists[CellListId(id)] != null
            Kind.RelativePriority -> tree.cells[CellId(id)]?.taskId != null
            Kind.DeepCopy -> tree.cells[CellId(id)] != null
            Kind.Alarm -> state.alarms.any { it.id == id }
            Kind.Timer -> state.timers.any { it.id == id }
            Kind.Chrono -> state.chronos.any { it.id == id }
            Kind.Reminder -> state.chores.any { it.id == id }
            Kind.AlarmDefaults, Kind.TimerDefaults, Kind.ReminderDefaults -> true
        }
    }

    /**
     * PRD §7: the name a button made from this window's ☆ starts with — the object's own name followed by what it
     * is ("Tea timer", "Pay rent reminder", "Writing task"). A weight table is named after the task whose sub-list
     * it weighs, a relative priority and a deep copy after the task in their cell. An object with no name (a
     * timer left unlabelled) is called by the noun alone, capitalized ("Timer").
     */
    fun buttonTitle(state: SchedulerState): String {
        val tree = if (template) state.projectDefaultSubtree() else state
        fun taskOfCell(cellId: String) = tree.cells[CellId(cellId)]?.taskId?.let { tree.tasks[it]?.title }
        val name = when (kind) {
            Kind.TaskEdit -> tree.tasks[TaskId(id)]?.title
            Kind.CategoryEdit -> tree.categoryById(CategoryId(id))?.title
            Kind.PeriodKindEdit -> id
            Kind.PriorityWeights -> tree.tasks.values.firstOrNull { it.childListId == CellListId(id) }?.title
            Kind.RelativePriority, Kind.DeepCopy -> taskOfCell(id)
            Kind.Alarm -> state.alarms.firstOrNull { it.id == id }?.label
            Kind.Timer -> state.timers.firstOrNull { it.id == id }?.label
            Kind.Chrono -> state.chronos.firstOrNull { it.id == id }?.label
            Kind.Reminder -> state.chores.firstOrNull { it.id == id }?.title
            // Named by the noun alone: "Default alarm".
            Kind.AlarmDefaults, Kind.TimerDefaults, Kind.ReminderDefaults -> null
        }?.trim().orEmpty()
        return if (name.isEmpty()) kind.noun.replaceFirstChar { it.uppercaseChar() } else "$name ${kind.noun}"
    }

    companion object {
        private const val PREFIX = "object:"
        private const val TEMPLATE = "template"
        private const val LIVE = "live"

        /** [encode]'s reverse; null for anything else — a lateral-menu window's frame id, or a kind this build lacks. */
        fun decode(key: String): ObjectWindowKey? {
            if (!key.startsWith(PREFIX)) return null
            val parts = key.removePrefix(PREFIX).split(":", limit = 3)
            if (parts.size != 3) return null
            val kind = Kind.entries.firstOrNull { it.name == parts[0] } ?: return null
            val template = when (parts[1]) {
                TEMPLATE -> true
                LIVE -> false
                else -> return null
            }
            return ObjectWindowKey(kind, parts[2], template)
        }
    }
}

/**
 * An object of a task tree, and WHICH tree — the account's own, or the default sub-tree's (PRD §4). The windows
 * a tree cell opens (its weight table, relative priority, edit task, category, deep copy, and the period kind the
 * edit window opens) read the tree they were opened from and send their intents back into it, and several of
 * them can stand open at once, so each carries its own answer.
 */
data class TreeObject<T>(val id: T, val template: Boolean = false)
