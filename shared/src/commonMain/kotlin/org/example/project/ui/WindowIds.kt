package org.example.project.ui

import org.example.project.scheduler.state.HistoryWindow
import org.example.project.scheduler.ui.DEEP_COPY_FRAME_ID
import org.example.project.scheduler.ui.PRIORITY_WEIGHTS_FRAME_ID
import org.example.project.scheduler.ui.RELATIVE_PRIORITY_FRAME_ID
import org.example.project.scheduler.ui.TASK_EDIT_FRAME_ID

/**
 * User rule 2026-11-22: **a window's id is never reused.** A window that existed is never really lost — the History Units
 * made in it name it (`window` + `instance`), and the Search window lists it as an element — so a number one window had
 * is never given to another, even once the first is closed. Before, a copy took the first number no OPEN copy held
 * (`Search#2` again, as soon as the first `Search#2` closed), and every object window counted from 1 again at each start.
 *
 * The one funnel every window number goes through. [next] answers the first number above every one the base has had:
 * the floor kept on the device ([PLACEMENT_ID], local-only like every placement row, never synced — a window is the
 * device's) and every id still known (the placement rows, the ids the history names), so a store that predates the
 * floor, or lost it, still never hands back an id something names.
 */
object WindowIds {
    /** The placement row the floors are kept on: `Search=7;objects=12`. A view row, never a History Unit. */
    const val PLACEMENT_ID: String = "WindowIds"

    /**
     * The one counter of every per-object window ([ObjectWindows]): their frame ids are `<frameBase>#<n>`, and a single
     * counter over all of them keeps `n` unique whatever the base (the alarm, timer and chrono windows share theirs).
     */
    const val OBJECTS: String = "objects"

    /** The floors [encode] wrote; anything malformed is skipped. */
    fun decode(text: String?): Map<String, Int> =
        text.orEmpty().split(';').mapNotNull { part ->
            val base = part.substringBefore('=', "")
            val n = part.substringAfter('=', "").toIntOrNull()
            if (base.isEmpty() || n == null) null else base to n
        }.toMap()

    fun encode(floors: Map<String, Int>): String =
        floors.entries.sortedBy { it.key }.joinToString(";") { it.key + "=" + it.value }

    /**
     * The number of [id] as a window of [base]: `Search#3` → 3, `Search` → 1 (the original is the first), and null for an
     * id of another base. Only the number right after the base counts (`TaskEdit#3#5` drawn inside a copy is 3's).
     */
    fun numberOf(id: String, base: String): Int? {
        if (id == base) return 1
        if (!id.startsWith("$base#")) return null
        return id.removePrefix("$base#").substringBefore('#').substringBefore('/').toIntOrNull()
    }

    /**
     * The first number for a new window of [base]: above the stored floor, above every id of [known], and at least
     * [first]. [known] is whatever names a window: for the per-object windows, ids of any base ([OBJECTS]).
     */
    fun next(base: String, floors: Map<String, Int>, known: Iterable<String>, first: Int, bases: Collection<String> = listOf(base)): Int {
        var top = floors[base] ?: 0
        for (id in known) for (b in bases) numberOf(id, b)?.let { if (it > top) top = it }
        return maxOf(first, top + 1)
    }

    /**
     * The frame-id bases of the windows that are not lateral-menu windows, by the window a History Unit names. A
     * lateral-menu window's base is its own name, the same in both enums. The one table both directions read: `App`'s
     * stamp (the window a frame id IS) and [frameIdOf] (the frame id a unit was made in). The first base of a list is
     * the one [frameIdOf] answers — the notices share one [HistoryWindow], so a unit made in one names the first.
     */
    val OBJECT_BASES: Map<HistoryWindow, List<String>> =
        mapOf(
            HistoryWindow.TaskEdit to listOf(TASK_EDIT_FRAME_ID),
            HistoryWindow.CategoryEdit to listOf(CATEGORY_EDIT_FRAME_ID),
            HistoryWindow.PriorityWeights to listOf(PRIORITY_WEIGHTS_FRAME_ID),
            HistoryWindow.RelativePriority to listOf(RELATIVE_PRIORITY_FRAME_ID),
            HistoryWindow.DeepCopy to listOf(DEEP_COPY_FRAME_ID),
            HistoryWindow.AlarmDefaults to listOf(ALARM_DEFAULTS_FRAME_ID),
            HistoryWindow.ReminderDefaults to listOf(REMINDER_DEFAULTS_FRAME_ID),
            HistoryWindow.CalendarElements to listOf("CalendarElements"),
            HistoryWindow.CalendarEntry to listOf("CalendarEntryEdit"),
            HistoryWindow.CalendarPeriod to listOf("CalendarPeriodEdit"),
            HistoryWindow.CalendarReminder to listOf("CalendarReminderEdit"),
            HistoryWindow.ReminderConstraint to listOf("ReminderConstraintEdit"),
            HistoryWindow.HistoryEntryInfo to listOf("HistoryEntryInfo"),
            HistoryWindow.Notice to listOf("Notice", "AppNotice", "CategoryRuleNotice"),
        )

    /** What a task tree's detail window's frame id starts with; its instance is `/<id>`. */
    const val TASK_TREE_DETAIL: String = "TaskTreeDetail"

    /** The windows no frame is: the tree, the task trees of old, the left-side menu. */
    private val FRAMELESS: Set<HistoryWindow> = setOf(HistoryWindow.Tree, HistoryWindow.TaskTrees, HistoryWindow.Menu)

    /** The window a non-lateral frame id IS, and its instance (what follows the base), or null. */
    fun objectWindowOfFrame(frameId: String): Pair<HistoryWindow, String>? {
        if (frameId.startsWith("$TASK_TREE_DETAIL/")) return HistoryWindow.TaskTreeDetail to frameId.removePrefix(TASK_TREE_DETAIL)
        val base = frameId.substringBefore('#')
        val window = OBJECT_BASES.entries.firstOrNull { base in it.value }?.key ?: return null
        return window to frameId.removePrefix(base)
    }

    /**
     * The frame id of the window a History Unit was made in — [window] and its [instance] (`Search` + `#3`) — the id of
     * that window's [org.example.project.scheduler.domain.SearchDomain.Kind.Window] row. Null for what no frame is.
     */
    fun frameIdOf(window: HistoryWindow, instance: String): String? {
        if (window in FRAMELESS) return null
        if (window == HistoryWindow.TaskTreeDetail) return TASK_TREE_DETAIL + instance
        return (OBJECT_BASES[window]?.first() ?: window.name) + instance
    }
}
