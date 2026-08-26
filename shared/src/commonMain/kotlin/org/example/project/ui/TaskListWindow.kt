package org.example.project.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt
import org.example.project.scheduler.domain.SchedulerDomain
import org.example.project.scheduler.ui.formatPriorityPercent

/** Width of the "occurrences" column — wide enough for a task mirrored a few hundred times. */
private val OCCURRENCES_COLUMN_WIDTH = 96.dp

/** Width of the percentage column, sized on the widest form the formatter produces ("100%", "33.3%"). */
private val PRIORITY_COLUMN_WIDTH = 72.dp

/** Width of the sorter bar's leading labels, so its two rows of chips line up. */
private val SORTER_LABEL_WIDTH = 60.dp

/**
 * **All tasks**: a floating window listing every task the tree holds, flat, under a sorter configuration.
 *
 * The tree answers "how is my work broken down"; this window answers the two questions the tree's *shape*
 * hides — which tasks come back most often across the whole tree (their number of occurrences, mirrors
 * included), and which ones actually carry the priority. Both figures are read straight off the tree on
 * screen ([SchedulerDomain.taskListEntries]), so a row's percentage is the same number that task shows in
 * the tree.
 *
 * The sorter configuration is **Compose-only state**, like the calendar's zoom and the PRD §4 find bar: how
 * a list is ordered on screen is a way of looking at the tree, never a fact about it, so it is neither
 * persisted nor synced and records no history unit.
 *
 * Mirrors the other floating windows' drag-title / dismiss / raise-on-press pattern.
 */
@Composable
fun TaskListWindow(
    /** The live tree's tasks, already ordered — the caller hands [sort]/[descending] to the domain. */
    entries: List<SchedulerDomain.TaskListEntry>,
    sort: SchedulerDomain.TaskListSort,
    onSortChange: (SchedulerDomain.TaskListSort) -> Unit,
    /** True while the largest figure leads (top to bottom); false puts the smallest first. */
    descending: Boolean,
    onDirectionChange: (Boolean) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    /** Initial position relative to centered; staggered per window so they open in a clickable cascade. */
    initialOffset: Offset = Offset.Zero,
    /** Persists the window's new drag position when a drag gesture ends (local-only geometry). */
    onOffsetChange: (Offset) -> Unit = {},
    /** Raise this window to the top of the layers — fired on a press anywhere inside it. */
    onRaise: () -> Unit = {},
) {
    var offset by remember { mutableStateOf(initialOffset) }

    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surface,
        shadowElevation = 10.dp,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = modifier
            .offset { IntOffset(offset.x.roundToInt(), offset.y.roundToInt()) }
            // requiredWidth (not width) so the window keeps its fixed width whatever the content area is.
            .requiredWidth(470.dp)
            // Raise on press AFTER the offset so the hit region tracks the (possibly dragged) window.
            .raiseOnPress(onRaise),
    ) {
        Column(Modifier.fillMaxWidth()) {
            // Title bar doubles as the drag handle for moving the window.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .windowDragHandle(onDragEnd = { onOffsetChange(offset) }) { dragAmount ->
                        offset += dragAmount
                    }
                    .padding(start = 14.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "All tasks",
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f),
                )
                Box(
                    modifier = Modifier.size(28.dp).clip(CircleShape).clickable(onClick = onDismiss),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        "✕",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Box(Modifier.fillMaxWidth().height(1.dp).background(MaterialTheme.colorScheme.outlineVariant))

            TaskListSorterBar(
                sort = sort,
                onSortChange = onSortChange,
                descending = descending,
                onDirectionChange = onDirectionChange,
            )
            Box(Modifier.fillMaxWidth().height(1.dp).background(MaterialTheme.colorScheme.outlineVariant))

            // Column headings, on the same widths as the rows below them.
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "Task",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = "Occurrences",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.End,
                    modifier = Modifier.width(OCCURRENCES_COLUMN_WIDTH),
                )
                Text(
                    text = "Priority",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.End,
                    modifier = Modifier.width(PRIORITY_COLUMN_WIDTH),
                )
            }
            Box(Modifier.fillMaxWidth().height(1.dp).background(MaterialTheme.colorScheme.outlineVariant))

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    // Grows with the tasks up to a cap, then scrolls — an account may hold hundreds.
                    .heightIn(max = 460.dp)
                    .verticalScroll(rememberScrollState())
                    .padding(vertical = 6.dp),
            ) {
                if (entries.isEmpty()) {
                    Text(
                        text = "No task yet — name one in the tree.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
                    )
                }
                entries.forEach { entry ->
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = entry.title,
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            text = entry.occurrences.toString(),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.End,
                            modifier = Modifier.width(OCCURRENCES_COLUMN_WIDTH),
                        )
                        Text(
                            text = formatPriorityPercent(entry.priority),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.End,
                            modifier = Modifier.width(PRIORITY_COLUMN_WIDTH),
                        )
                    }
                }
            }
        }
    }
}

/**
 * The sorter configuration at the top of the window: which figure orders the list, and which way round.
 *
 * The direction is a single toggle rather than a second pair of chips — there are only ever two answers, and
 * naming them by the arrow *and* the sentence ("top to bottom" / "bottom to top") says which end of the list
 * the big numbers land at without the user having to press it and see.
 */
@Composable
private fun TaskListSorterBar(
    sort: SchedulerDomain.TaskListSort,
    onSortChange: (SchedulerDomain.TaskListSort) -> Unit,
    descending: Boolean,
    onDirectionChange: (Boolean) -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "Sort by",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.width(SORTER_LABEL_WIDTH),
            )
            SorterChip(
                label = "Occurrences",
                selected = sort == SchedulerDomain.TaskListSort.Occurrences,
                onClick = { onSortChange(SchedulerDomain.TaskListSort.Occurrences) },
            )
            Spacer(Modifier.width(8.dp))
            SorterChip(
                label = "Priority %",
                selected = sort == SchedulerDomain.TaskListSort.Priority,
                onClick = { onSortChange(SchedulerDomain.TaskListSort.Priority) },
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "Order",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.width(SORTER_LABEL_WIDTH),
            )
            SorterChip(
                label = if (descending) {
                    "↓  Highest first (top to bottom)"
                } else {
                    "↑  Lowest first (bottom to top)"
                },
                selected = false,
                onClick = { onDirectionChange(!descending) },
            )
        }
    }
}

/** One chip of the sorter bar — the selected one is filled, like the lateral menu's active button. */
@Composable
private fun SorterChip(label: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(
                if (selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent,
            )
            .border(
                1.dp,
                if (selected) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.outlineVariant,
                RoundedCornerShape(8.dp),
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 6.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            color = if (selected) {
                MaterialTheme.colorScheme.onSecondaryContainer
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
        )
    }
}
