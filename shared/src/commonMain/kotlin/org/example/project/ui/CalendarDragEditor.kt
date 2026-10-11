package org.example.project.ui

import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.dp
import kotlinx.datetime.TimeZone
import org.example.project.scheduler.domain.SearchDomain
import org.example.project.scheduler.state.SchedulerState

/**
 * **"Drag on the calendar"** (user rule 2026-10-07): *"there is the button drag with a field to select which block in
 * the calendar must be dragged. Since this Search window comes from 'edit…', this field is set by default on the task
 * the user right-clicked on. Then the user presses the mouse on the 'drag' button. The calendar appears, and period A
 * and period B follow the mouse…"*
 *
 *  - the FIELD lists the blocks the added elements have on the calendar (on the days it shows), each by its name and
 *    its hours — the app's one check-box drop-down ([CheckBoxDropDown]). Until the user picks, the ones checked are
 *    the blocks at the right-click the window was opened from ([SearchDomain.calendarDragDefaultAt]): the block the
 *    user right-clicked on, for every added element that has one there. In memory only;
 *  - the BUTTON is held, not clicked: the press takes hold of the checked blocks ([CalendarElementDrag.begin]) and
 *    brings the calendar to the front, the pointer carries them while it stays down — every one moved by the same
 *    time, so each keeps its place under the pointer — and the release puts each where it is, through the blocks' one
 *    funnel. The instant the pointer stands for is the right-click's while the blocks are the ones there; for blocks
 *    picked by hand it is the start of the earliest.
 */
@Composable
internal fun CalendarDragEditor(
    state: SchedulerState,
    added: List<SearchDomain.Result>,
    config: SearchDomain.Config,
    handlers: AddedActionHandlers,
    @Suppress("UNUSED_PARAMETER") nowMillis: () -> Long,
) {
    val tz = TimeZone.currentSystemDefault()
    val clickedAt = SearchDomain.calendarDragDefaultAt(config)
    val targets = SearchDomain.calendarDragTargets(state, added)
    val drag = LocalCalendarElementDrag.current
    val options = if (targets.isEmpty) emptyList() else drag.blocksOf(targets)
    val byKey = options.associateBy(::calendarDragKey)
    val atClick = if (clickedAt == null) emptyList() else calendarDragBlocks(options, targets, clickedAt)
    // What the user checked, kept until the window is opened on another right-click; null = the blocks right-clicked.
    var picked by remember(clickedAt) { mutableStateOf<Set<String>?>(null) }
    val checked = (picked ?: atClick.mapTo(LinkedHashSet(), ::calendarDragKey)).filterTo(LinkedHashSet()) { it in byKey }
    val held = options.filter { calendarDragKey(it) in checked }
    // The instant the pointer stands for: the right-click while its own blocks are the ones held.
    val anchor = clickedAt?.takeIf { picked == null } ?: held.minOfOrNull { it.fullStartMillis }
    val enabled = held.isNotEmpty() && anchor != null
    val currentHeld = rememberUpdatedState(held)
    val currentAnchor = rememberUpdatedState(anchor)
    val currentOnDrag = rememberUpdatedState(handlers.onDragOnCalendar)
    var coords by remember { mutableStateOf<LayoutCoordinates?>(null) }
    val color = if (enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
    fun label(key: String): String {
        val block = byKey[key] ?: return key
        val from = localOf(block.fullStartMillis, tz)
        val to = localOf(block.fullEndMillis, tz)
        fun hm(h: Int, m: Int) = h.toString().padStart(2, '0') + ":" + m.toString().padStart(2, '0')
        val hours = if (block.fullEndMillis > block.fullStartMillis) hm(from.hour, from.minute) + "–" + hm(to.hour, to.minute) else hm(from.hour, from.minute)
        return block.title.ifBlank { "(untitled)" } + "  " + from.date + " " + hours
    }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            NoteText("the block", color = MaterialTheme.colorScheme.onSurface)
            CheckBoxDropDown(
                options = options.map(::calendarDragKey),
                checked = checked,
                face =
                    when (held.size) {
                        0 -> if (options.isEmpty()) "no block" else "none"
                        1 -> label(calendarDragKey(held.single()))
                        else -> "${held.size} blocks"
                    },
                label = ::label,
                onChange = { picked = it },
                modifier = Modifier.width(260.dp),
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            HeldDragButton(held, anchor, "Drag") { handlers.onDragOnCalendar() }
            NoteText(
                when {
                    targets.isEmpty -> "No added element can be on the calendar."
                    options.isEmpty() -> "The added elements have no block on the days the calendar shows."
                    held.isEmpty() -> "Check the blocks to drag."
                    held.size == 1 -> "Hold the button: the block follows the pointer."
                    else -> "Hold the button: the ${held.size} blocks follow the pointer."
                },
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

/**
 * **The button that is HELD, not clicked** (user rule 2026-10-07): the press takes hold of [held] — the blocks as the
 * calendar draws them — at the instant [anchor] the pointer stands for ([CalendarElementDrag.begin]) and brings the
 * calendar to the front ([onDragStarted]); the pointer carries them while it stays down, every one moved by the same
 * time; the release puts each where it is, through the blocks' one funnel. Greyed and deaf with nothing to hold.
 * One button for "Blocks on the calendar" and for the drag action it replaced.
 */
@Composable
internal fun HeldDragButton(held: List<PlacedRecord>, anchor: Long?, text: String, onDragStarted: () -> Unit) {
    val drag = LocalCalendarElementDrag.current
    val enabled = held.isNotEmpty() && anchor != null
    val currentHeld = rememberUpdatedState(held)
    val currentAnchor = rememberUpdatedState(anchor)
    val currentOnDrag = rememberUpdatedState(onDragStarted)
    var coords by remember { mutableStateOf<LayoutCoordinates?>(null) }
    val color = if (enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        color = color,
        maxLines = 1,
        softWrap = false,
        modifier = Modifier
            .onGloballyPositioned { coords = it }
            .clip(RoundedCornerShape(6.dp))
            .border(1.dp, if (enabled) MaterialTheme.colorScheme.outlineVariant else color, RoundedCornerShape(6.dp))
            .pointerInput(drag) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    if (currentEvent.buttons.isSecondaryPressed) return@awaitEachGesture
                    val from = currentAnchor.value ?: return@awaitEachGesture
                    if (!drag.begin(currentHeld.value, from)) return@awaitEachGesture
                    down.consume()
                    currentOnDrag.value()
                    var released = false
                    try {
                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                            coords?.takeIf { it.isAttached }?.let { drag.moveTo(it.localToWindow(change.position)) }
                            change.consume()
                            if (!change.pressed) {
                                released = true
                                break
                            }
                        }
                    } finally {
                        // The gesture cut short (the window closed under the press) drops nothing anywhere.
                        if (released) drag.release() else drag.cancel()
                    }
                }
            }
            .padding(horizontal = 10.dp, vertical = 4.dp),
    )
}
