package org.example.project.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.datetime.TimeZone
import org.example.project.scheduler.domain.SearchDomain
import org.example.project.scheduler.state.SchedulerState

/**
 * **"Blocks on the calendar"** (user rule 2026-10-11): *"replace the current button with a drop-down list showing all
 * the blocks (with check boxes and a select all). When the user clicks on one, it is added under 'Blocks on the
 * calendar' as two fields (start and end date/time). The user can edit them, which updates the calendar. A remove
 * button allows the user to remove all the blocks displayed here."*
 *
 *  - the DROP-DOWN lists every block the added elements have on the calendar ([SearchDomain.blocksOfAdded]: what a
 *    hand placed — a task's panel, a period, a reminder's tag, a ring moved there), the app's one check-box list, whose
 *    first entry is its "Select all";
 *  - each CHECKED block stands under it as its name and its start and end — a day and a time each, the fields
 *    "Add to the calendar" states its own with. An edit is written at once through the block's own intent
 *    ([SearchDomain.blockEditIntent]: the edit a drag of it would commit), so the calendar follows and Ctrl+Z walks
 *    it. A tag and a ring are an instant: one day-and-time, and an isolated ring keeps its day (an alarm rings at a
 *    time of day);
 *  - "Remove" takes the blocks shown here OFF THE CALENDAR ([SearchDomain.blockRemoveIntents]) and empties the list.
 *
 * Which blocks are checked is this Search window's ([SearchDomain.Config.pickedBlocks], local view state). It
 * replaced the "Search the blocks" button, which opened another Search window on the same blocks.
 */
@Composable
internal fun CalendarBlocksEditor(
    state: SchedulerState,
    added: List<SearchDomain.Result>,
    config: SearchDomain.Config,
    onConfigChange: (SearchDomain.Config) -> Unit,
    handlers: AddedActionHandlers,
    run: (SearchDomain.AddedCommand) -> Unit,
    nowMillis: () -> Long,
) {
    val tz = TimeZone.currentSystemDefault()
    val today = localOf(nowMillis(), tz).date
    // Anomaly 2026-10-11: a pattern repeats for ever (the Sleep schedule's nights, a repeating panel), so its
    // occurrences are listed over a stretch from today on, which grows as the list is scrolled to its bottom.
    val patternsFrom = remember { instantOf(today, 0, tz) }
    var patternDays by remember { mutableStateOf(PATTERN_DAYS_STEP) }
    val blocks = SearchDomain.blocksOfAdded(state, added, tz, patternsFrom, patternsFrom + patternDays * DAY_MILLIS)
    val endless = blocks.any(SearchDomain::blockIsOfPattern)
    val byId = blocks.associateBy { it.id }
    val checked = config.pickedBlocks.filterTo(LinkedHashSet()) { it in byId }
    val shown = blocks.filter { it.id in checked }
    fun hm(minutes: Int) = (minutes / 60).toString().padStart(2, '0') + ":" + (minutes % 60).toString().padStart(2, '0')
    // Where the calendar draws a rule's period cut, the span it is drawn over (set below, once the records are read).
    var drawnRangeOf: (SearchDomain.CalendarBlock) -> org.example.project.scheduler.model.TaskTimeRange? = { null }
    fun label(id: String): String {
        val block = byId[id] ?: return id
        val drawnAt = drawnRangeOf(block)
        val from = localOf(drawnAt?.startEpochMillis ?: block.startMillis, tz)
        val to = localOf(drawnAt?.endEpochMillis ?: block.endMillis, tz)
        val hours =
            if (block.endMillis > block.startMillis) hm(from.hour * 60 + from.minute) + "–" + hm(to.hour * 60 + to.minute)
            else hm(from.hour * 60 + from.minute)
        return block.name.ifBlank { "(untitled)" } + "  " + from.date + " " + hours
    }
    fun write(block: SearchDomain.CalendarBlock, start: Long, end: Long) {
        val intent = SearchDomain.blockEditIntent(state, block, start, end, nowMillis(), tz) ?: return
        // An occurrence of a pattern leaves it as it is edited and is a block of its own from there: it stays listed.
        val after = SearchDomain.blockIdAfterEdit(state, block)
        if (after != block.id) onConfigChange(config.copy(pickedBlocks = config.pickedBlocks - block.id + after))
        run(SearchDomain.AddedCommand.Raw(intent))
    }
    // The checked blocks as the calendar DRAWS them on the days it shows — what the drag button holds.
    val drag = LocalCalendarElementDrag.current
    val drawn = drag.columns.values.flatMap { it.records() }
    fun recordOf(block: SearchDomain.CalendarBlock): PlacedRecord? {
        val ring = SearchDomain.blockRingId(block)
        return drawn.firstOrNull { r ->
            when {
                r.layer != null -> false
                block.derivedKind == org.example.project.scheduler.domain.PeriodKinds.SLEEP ->
                    r.sleep && r.fullStartMillis < block.endMillis && block.startMillis < r.fullEndMillis
                block.derivedKind != null ->
                    !r.sleep && !r.screenBreak && r.restrictiveKind == block.derivedKind && r.fullStartMillis == block.startMillis
                ring != null -> r.alarm && r.entryId == ring
                else -> r.entryId == block.id || block.id in r.entryIds
            }
        }
    }
    val held = shown.mapNotNull(::recordOf).distinctBy(::calendarDragKey)
    // Anomaly 2026-10-11 (*"I still have the Sleep block starting today at 5:15, even though in the calendar it
    // starts at now line"*): a period a RULE lays is listed over the span the rule gives it, and the calendar draws
    // it CUT — a night gives way to a line at a screen, so tonight's starts at the line. Where the calendar draws
    // the block, the row and the list read the span it is drawn over ([PlacedRecord.fullStartMillis]), which is what
    // "on the calendar" means; off the days it shows, the rule's span is all there is to read.
    fun drawnRange(block: SearchDomain.CalendarBlock): org.example.project.scheduler.model.TaskTimeRange? {
        val kind = block.derivedKind ?: return null
        // The pieces the calendar draws of THIS occurrence: a night is cut under its own id; an hour before bed has
        // none, and is the piece of its kind inside the rule's span ([SearchDomain.blockDrawnSpan] keeps those).
        val pieces =
            drawn.filter { r ->
                r.layer == null &&
                    if (kind == org.example.project.scheduler.domain.PeriodKinds.SLEEP) r.sleep && r.entryId == block.id
                    else !r.sleep && !r.screenBreak && r.restrictiveKind == kind
            }.map { org.example.project.scheduler.model.TaskTimeRange(it.fullStartMillis, it.fullEndMillis) }
        return SearchDomain.blockDrawnSpan(block, pieces)
    }
    drawnRangeOf = ::drawnRange
    // Anomaly 2026-10-11 (*"The date/time of the start/end of a block doesn't update as it gets changed in the
    // calendar"*), its first half: WHILE a block is held on the calendar nothing is saved — the calendar draws where
    // a release would put it — so the fields read where the hand has it, not the stored span: the block the calendar
    // holds by its own gesture ([CalendarElementDrag.heldInCalendar]), or the ones a "Drag on the calendar" button
    // carries, each moved by the same time.
    fun liveRange(block: SearchDomain.CalendarBlock): org.example.project.scheduler.model.TaskTimeRange? {
        val key = recordOf(block)?.let(::calendarDragKey) ?: return null
        drag.heldInCalendar?.let { hold -> if (calendarDragKey(hold.block) == key) return hold.range }
        if (drag.blocks.any { calendarDragKey(it) == key }) {
            return org.example.project.scheduler.model.TaskTimeRange(block.startMillis + drag.deltaMillis, block.endMillis + drag.deltaMillis)
        }
        return null
    }
    // …and its second half: a release can give the block ANOTHER identity — a night of the Sleep schedule dragged
    // away is a period of the user's from there, an occurrence of a repeating panel a panel of its own, several
    // panels drawn as one block a new panel — and the row, kept by the old id, then showed nothing new (or left).
    // A checked block that is gone at the very change a block of the same element appears is that block, moved:
    // it stays checked under its new id ([SearchDomain.pickedBlocksFollowing]).
    val seen = remember { mutableStateOf<List<SearchDomain.CalendarBlock>>(emptyList()) }
    val followed = SearchDomain.pickedBlocksFollowing(config.pickedBlocks, seen.value, blocks)
    androidx.compose.runtime.SideEffect {
        seen.value = blocks
        if (followed != config.pickedBlocks) onConfigChange(config.copy(pickedBlocks = followed))
    }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            CheckBoxDropDown(
                options = blocks.map { it.id },
                checked = checked,
                face =
                    when (shown.size) {
                        0 -> if (blocks.isEmpty()) "no block" else "choose blocks…"
                        1 -> label(shown.single().id)
                        else -> "${shown.size} blocks"
                    },
                label = ::label,
                onChange = { onConfigChange(config.copy(pickedBlocks = it)) },
                modifier = Modifier.width(260.dp),
                // More of a pattern's occurrences as the list is scrolled to its bottom — up to a few years ahead.
                onNearEnd = if (endless && patternDays < PATTERN_DAYS_MAX) ({ patternDays += PATTERN_DAYS_STEP }) else null,
            )
            FrameButton(if (shown.size > 1) "Remove ${shown.size}" else "Remove", enabled = shown.isNotEmpty()) {
                SearchDomain.blockRemoveIntents(state, shown, nowMillis(), tz).forEach { run(SearchDomain.AddedCommand.Raw(it)) }
                onConfigChange(config.copy(pickedBlocks = emptySet()))
            }
            // User rule 2026-10-11: the drag is this button — held, the checked blocks follow the pointer over the
            // calendar ([HeldDragButton]); it was an action of its own ("Drag on the calendar").
            HeldDragButton(held, held.minOfOrNull { it.fullStartMillis }, "Drag on the calendar") { handlers.onDragOnCalendar() }
        }
        if (shown.isNotEmpty() && held.size < shown.size) {
            NoteText(
                if (held.isEmpty()) "To drag them, show their days on the calendar."
                else "${held.size} of the ${shown.size} are on the days the calendar shows: those are the ones dragged.",
            )
        }
        for (block in shown) {
            // What a field remembers while it is typed in belongs to the block, not to its place in the list.
            androidx.compose.runtime.key(block.id) {
                val live = liveRange(block)
                val drawnAt = drawnRange(block)
                // What an edit of ONE field keeps of the other: the span the row reads at rest.
                val restStart = drawnAt?.startEpochMillis ?: block.startMillis
                val restEnd = drawnAt?.endEpochMillis ?: block.endMillis
                val shownStart = live?.startEpochMillis ?: restStart
                val shownEnd = live?.endEpochMillis ?: restEnd
                val from = localOf(shownStart, tz)
                val instant = SearchDomain.blockIsInstant(block)
                val fixedDay = SearchDomain.blockKeepsItsDay(block)
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        block.name.ifBlank { "(untitled)" },
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        NoteText(if (instant) "at" else "starts", color = MaterialTheme.colorScheme.onSurface)
                        DayField(from.date, "day", today, enabled = !fixedDay) { date ->
                            val start = instantOf(date, from.hour * 60 + from.minute, tz)
                            write(block, start, start + (restEnd - restStart))
                        }
                        TimeOfDayField(from.hour * 60 + from.minute, enabled = true, blank = "HH:MM") { minutes ->
                            val start = instantOf(from.date, minutes, tz)
                            // The start moved: the block keeps its END (it is the start field that was edited), unless
                            // that would leave it no length — then it keeps its length.
                            val end = if (instant) start else restEnd.takeIf { it > start } ?: (start + (restEnd - restStart))
                            write(block, start, end)
                        }
                    }
                    if (!instant) {
                        val to = localOf(shownEnd, tz)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            NoteText("ends", color = MaterialTheme.colorScheme.onSurface)
                            DayField(to.date, "day", today, enabled = true) { date ->
                                write(block, restStart, instantOf(date, to.hour * 60 + to.minute, tz))
                            }
                            TimeOfDayField(to.hour * 60 + to.minute, enabled = true, blank = "HH:MM") { minutes ->
                                write(block, restStart, instantOf(to.date, minutes, tz))
                            }
                        }
                    }
                }
            }
        }
    }
}

/** How many more days of a pattern's occurrences each scroll to the bottom of the list brings, and the most listed. */
private const val PATTERN_DAYS_STEP: Int = 30
private const val PATTERN_DAYS_MAX: Int = 3 * 365
private const val DAY_MILLIS: Long = 86_400_000L
