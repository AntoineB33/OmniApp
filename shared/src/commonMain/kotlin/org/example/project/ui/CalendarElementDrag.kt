package org.example.project.ui

import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import org.example.project.scheduler.domain.PeriodKinds
import org.example.project.scheduler.domain.SchedulerDomain
import org.example.project.scheduler.domain.SearchDomain
import org.example.project.scheduler.model.TaskTimeRange

/**
 * User rule 2026-10-07, the Search window's **"Drag on the calendar"** action: *"they … click on the 'drag' button
 * while keeping the click pressed, which puts focus on the calendar and the chosen blocks are following the mouse until
 * the mouse click is released."*
 *
 * The press is held by a button of ANOTHER window, so the drag cannot be a gesture of the calendar's: this is what the
 * two share. The button says which blocks and where the pointer is ([begin], [moveTo], [release]); every day column on
 * screen says what it draws and which instant a point of the window is ([columns]) and lends the blocks' ONE release
 * ([commitBounds], the column's `onCommitBounds`). Nothing here places anything by itself.
 *
 * **While held, the calendar is drawn the way a release would leave it** — `App` reads [targets] and draws the state
 * the release's own moves make of the stored one, nothing saved: the blocks stand where the pointer has them (cut
 * where a mode-1 line leaves them, [atLine]), what comes with them stands there too, and what they make disappear
 * disappears — and is back as soon as they move on, because every step starts again from the stored state.
 *
 * *"Period A and period B follow the mouse such that the mouse is always 30 minutes after the start of period A, and
 * 1h30 after the start of period B"*: every block is moved by the same time — the instant under the pointer less the
 * instant the blocks were chosen at ([deltaMillis]).
 *
 * [heldInCalendar] is the other thing a column tells `App`: the block IT holds by its own gesture — a task panel, a
 * period box, a band — and where the hand has it. The calendar is then drawn as that release would leave it too
 * ([LocalHeldCalendarRecords]): **held and released look the same; the one difference is that what the held block
 * removed is remembered** — it is in the stored state, which every step of the drag is asked of again.
 */
class CalendarElementDrag {
    /** What one day column lends: the instant a window position is on it (null off it), and the blocks it draws. */
    class Column(val timeAt: (Offset) -> Long?, val records: () -> List<PlacedRecord>)

    /** The day columns on screen, by their midnight. Written as they are composed and laid out. */
    val columns = mutableMapOf<Long, Column>()

    /** The blocks' one release — a day column's `onCommitBounds` (they all hold the same one). */
    var commitBounds: (PlacedRecord, Long, Long, Boolean) -> Unit = { _, _, _, _ -> }

    /** Where the line leaves a period put at a span (`LocalPeriodAtLine`); null = nowhere. Given by `App`. */
    var atLine: (kind: String, range: TaskTimeRange) -> TaskTimeRange? = { _, range -> range }

    /** Does a period of this kind refuse this task (`LocalPeriodRefusal`)? Given by `App`. */
    var refuses: (org.example.project.scheduler.model.TaskId?, String) -> Boolean = { _, _ -> true }

    /** The blocks held, as they stood when the press began. Empty while nothing is dragged. */
    var blocks: List<PlacedRecord> by mutableStateOf(emptyList())
        private set

    /** How far the pointer has them from where they stood. */
    var deltaMillis: Long by mutableStateOf(0L)
        private set

    /** A block a day column holds by its own gesture: exactly where the hand has it, as its release would put it. */
    data class Held(val block: PlacedRecord, val range: TaskTimeRange, val allowOverlap: Boolean)

    /** The block a day column holds by its own gesture, or null. */
    var heldInCalendar: Held? by mutableStateOf(null)

    private var anchorMillis: Long = 0L

    /** Every block the elements [targets] have on the columns on screen — what the action's field lists. */
    fun blocksOf(targets: SearchDomain.CalendarDragTargets): List<PlacedRecord> =
        calendarDragBlocks(columns.values.flatMap { it.records() }, targets, atMillis = null)

    /** The press: hold [held], chosen at [atMillis] — the instant the pointer stands for. False where there is none. */
    fun begin(held: List<PlacedRecord>, atMillis: Long): Boolean {
        anchorMillis = atMillis
        deltaMillis = 0L
        blocks = held
        return held.isNotEmpty()
    }

    /** The pointer is at [windowPosition]: over a day column, the blocks follow; off every one, they stay. */
    fun moveTo(windowPosition: Offset) {
        if (blocks.isEmpty()) return
        val at = columns.values.firstNotNullOfOrNull { it.timeAt(windowPosition) } ?: return
        deltaMillis = at - anchorMillis
    }

    /**
     * Where each held block is put: moved by [deltaMillis], and — a period — over what a mode-1 line leaves of it
     * there. A period the line leaves nothing of is not in the list: it stays where it was.
     */
    fun targets(): List<Pair<PlacedRecord, TaskTimeRange>> {
        val delta = deltaMillis
        if (delta == 0L) return emptyList()
        val breaks = columns.values.flatMap { it.records() }.filter { it.screenBreak }
        return blocks.mapNotNull { block ->
            val kind = calendarPeriodKindOf(block)
            // A task panel keeps its length across the breaks that refuse it, as under the column's own gesture.
            val moved = draggedBlockBounds(block, edge = null, delta, armed = false, others = emptyList(), refusingBreaks(block, breaks, refuses))
            val at = if (kind == null) moved else atLine(kind, moved)
            at?.let { block to it }
        }
    }

    /** The release: every held block is committed where it is put ([targets]), each through the blocks' one funnel. */
    fun release() {
        val puts = targets()
        cancel()
        puts.forEach { (block, at) ->
            // A period is free to overlap anything; a task panel keeps the width it had.
            commitBounds(block, at.startEpochMillis, at.endEpochMillis, !isTaskPanelRecord(block))
        }
    }

    /** The press ended without a release (the gesture was cancelled): nothing moves. */
    fun cancel() {
        blocks = emptyList()
        deltaMillis = 0L
    }
}

/**
 * User rule 2026-10-07: *"The only difference there must be between keeping the mouse click and having released it is
 * that whatever got removed when the dragged element got there is remembered if the mouse click is not released."*
 *
 * **The calendar as the release of the block a day column holds would leave it** — every record of it, derived by
 * `App` from the state the release's own moves make of the stored one — or null while no column holds anything. The
 * week view then DRAWS this one, and the columns that hold the press stay under the pointer, unseen, with the records
 * at rest: a gesture's nodes must not move under the press.
 */
val LocalHeldCalendarRecords = compositionLocalOf<List<CalendarRecord>?> { null }

/** The app's one [CalendarElementDrag]: provided by `App` round the calendar and the Search windows alike. */
val LocalCalendarElementDrag = compositionLocalOf { CalendarElementDrag() }

/** The kind of period the drawn block [r] is — the Sleep band's, a break's own, a period box's — or null for none. */
internal fun calendarPeriodKindOf(r: PlacedRecord): String? =
    when {
        r.sleep -> PeriodKinds.SLEEP
        r.screenBreak -> r.breakKind.ifBlank { PeriodKinds.INACTIVITY }
        r.alarm || r.reminder || r.layer != null -> null
        else -> r.restrictiveKind.takeIf { it.isNotBlank() }
    }

/** What tells one drawn block from another in the action's field: which block it is, and when. */
internal fun calendarDragKey(r: PlacedRecord): String = calendarBlockKey(r) + "@" + r.fullStartMillis + "-" + r.fullEndMillis

/**
 * **The blocks the elements [targets] have on the calendar**, among the [records] the day columns draw — each once (a
 * block crossing midnight is drawn on two days), in the timeline's order. With [atMillis], only those AT that instant:
 * a task's panel or record and a period covering it; a screen break covering it or starting within
 * [SearchDomain.CALENDAR_BREAK_WINDOW_MILLIS] after it; a reminder's tag and a ring within
 * [SearchDomain.CALENDAR_MARK_TOLERANCE_MILLIS] of it — the reading the calendar's "edit…" lists them by
 * ([SearchDomain.calendarElementsAt]). A layer band is no block.
 */
internal fun calendarDragBlocks(
    records: List<PlacedRecord>,
    targets: SearchDomain.CalendarDragTargets,
    atMillis: Long?,
): List<PlacedRecord> {
    fun covers(r: PlacedRecord) = atMillis == null || (r.fullStartMillis <= atMillis && atMillis < r.fullEndMillis)
    fun near(r: PlacedRecord) =
        atMillis == null || kotlin.math.abs(r.fullStartMillis - atMillis) <= SearchDomain.CALENDAR_MARK_TOLERANCE_MILLIS
    fun held(r: PlacedRecord): Boolean =
        when {
            r.layer != null -> false
            r.alarm -> r.entryId in targets.ringIds && near(r)
            r.reminder -> r.entryId?.let(SchedulerDomain::reminderIdOfChorePanel) in targets.reminderIds && near(r)
            r.screenBreak ->
                r.breakKind in targets.periodKinds &&
                    (covers(r) || (atMillis != null && r.fullStartMillis - atMillis in 0..SearchDomain.CALENDAR_BREAK_WINDOW_MILLIS))
            r.sleep -> PeriodKinds.SLEEP in targets.periodKinds && covers(r)
            r.restrictiveKind.isNotBlank() -> r.restrictiveKind in targets.periodKinds && covers(r)
            else -> r.taskId != null && r.taskId in targets.taskIds && covers(r)
        }
    return records.filter(::held).distinctBy(::calendarDragKey).sortedBy { it.fullStartMillis }
}
