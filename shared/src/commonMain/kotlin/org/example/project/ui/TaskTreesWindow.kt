package org.example.project.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt
import kotlin.time.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.toLocalDateTime
import org.example.project.scheduler.model.TaskTreeId
import org.example.project.scheduler.state.TaskTreeEntry

/**
 * **All task trees**: a floating window listing every named alternative task tree of the account, over a
 * **timeline** of the ones that have been given a date.
 *
 * A dated tree is a *keyframe* of the account's priorities. Between two consecutive keyframes the scheduler
 * does not follow the tree on screen at all — it follows a continuous linear blend of the two, so the plan
 * transforms evenly from one arrangement into the next (see
 * `SchedulerDomain.blendedTaskPriorities`). This window is where those dates are set: clicking a tree opens
 * a small second window holding its date field and a bin button.
 *
 * Mirrors the other floating windows' drag-title / dismiss / raise-on-press pattern.
 */
@Composable
fun TaskTreesWindow(
    trees: List<TaskTreeEntry>,
    /** The tree the live tree currently *is*, marked in the list; null when it has never been named. */
    activeId: TaskTreeId?,
    /** Now, for the timeline's now-marker — the instant the blend is actually read at. */
    nowMillis: Long,
    timeZone: TimeZone,
    onSetDate: (TaskTreeId, Long?) -> Unit,
    onDelete: (TaskTreeId) -> Unit,
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
    // Which tree's detail window is open. Held by id, not by entry, so it survives the list changing
    // under it (a rename, a date edit); a tree deleted out from under it closes the detail below.
    var openDetail by remember { mutableStateOf<TaskTreeId?>(null) }
    val detail = trees.firstOrNull { it.id == openDetail }

    Box(modifier) {
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.surface,
            shadowElevation = 10.dp,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            modifier = Modifier
                .offset { IntOffset(offset.x.roundToInt(), offset.y.roundToInt()) }
                // requiredWidth (not width) so the window keeps its fixed width and does not adapt to the
                // app's width when the content area is narrower than it.
                .requiredWidth(520.dp)
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
                        text = "All task trees",
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

                TaskTreeTimeline(
                    dated = trees.filter { it.dateMillis != null }.sortedBy { it.dateMillis },
                    nowMillis = nowMillis,
                    timeZone = timeZone,
                    onSelect = { openDetail = it },
                )
                Box(Modifier.fillMaxWidth().height(1.dp).background(MaterialTheme.colorScheme.outlineVariant))

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        // Grows with the trees up to a cap, then scrolls — an account may hold many.
                        .heightIn(max = 320.dp)
                        .verticalScroll(rememberScrollState())
                        .padding(vertical = 8.dp),
                ) {
                    if (trees.isEmpty()) {
                        Text(
                            text = "No task tree yet — name one in the field above the tree.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
                        )
                    }
                    trees.forEach { entry ->
                        TaskTreeRow(
                            entry = entry,
                            active = entry.id == activeId,
                            selected = entry.id == openDetail,
                            timeZone = timeZone,
                            onClick = { openDetail = if (openDetail == entry.id) null else entry.id },
                        )
                    }
                }
            }
        }

        // Clicking a tree opens its own little window: the date that puts it on the timeline, and the bin.
        if (detail != null) {
            TaskTreeDetailWindow(
                entry = detail,
                timeZone = timeZone,
                onSetDate = { onSetDate(detail.id, it) },
                onDelete = {
                    onDelete(detail.id)
                    openDetail = null
                },
                onDismiss = { openDetail = null },
                onRaise = onRaise,
                // Opens down-right of the list window's own (possibly dragged) position, so it reads as
                // belonging to it rather than floating loose.
                initialOffset = offset + Offset(360f, 150f),
            )
        }
    }
}

/**
 * The timeline across the top: each dated tree as a tick with its title and date, and the now-line marking
 * where the blend is currently read. Ticks alternate above/below the axis so neighbouring labels do not
 * collide.
 *
 * The axis spans the dated trees **and `now`**, so the now-line is always on screen — which is the one
 * thing the timeline has to show, since "which two trees are we between?" is exactly what it answers.
 */
@Composable
private fun TaskTreeTimeline(
    dated: List<TaskTreeEntry>,
    nowMillis: Long,
    timeZone: TimeZone,
    onSelect: (TaskTreeId) -> Unit,
) {
    if (dated.isEmpty()) {
        Text(
            text = "No task tree has a date yet. Click one below to put it on the timeline.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 18.dp),
        )
        return
    }

    val instants = dated.mapNotNull { it.dateMillis } + nowMillis
    val min = instants.min()
    val max = instants.max()
    // A degenerate span (one dated tree, or several on the same day as `now`) would divide by zero; put
    // everything at the middle instead, which is also how it reads best.
    val span = (max - min).toDouble()

    BoxWithConstraints(Modifier.fillMaxWidth().height(132.dp).padding(horizontal = 14.dp)) {
        val track = maxWidth
        val markerWidth = 108.dp
        fun xOf(instant: Long): Float =
            if (span <= 0.0) 0.5f else ((instant - min).toDouble() / span).toFloat()

        // The axis.
        Box(
            Modifier
                .offset(y = 62.dp)
                .fillMaxWidth()
                .height(1.dp)
                .background(MaterialTheme.colorScheme.outlineVariant),
        )
        // The now-line: a full-height rule, so "between which two" is readable at a glance.
        Box(
            Modifier
                .offset(x = (track - 2.dp) * xOf(nowMillis), y = 44.dp)
                .width(2.dp)
                .height(36.dp)
                .background(MaterialTheme.colorScheme.primary),
        )
        Text(
            text = "now",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.offset(x = (track - 2.dp) * xOf(nowMillis), y = 82.dp),
        )

        dated.forEachIndexed { index, entry ->
            val fraction = xOf(entry.dateMillis ?: min)
            // The label is laid out from its tick rightwards; clamped so the last tree's label stays inside
            // the window instead of being cut off by it.
            val labelLeft = (track - markerWidth) * fraction
            val above = index % 2 == 0
            Box(
                Modifier
                    .offset(x = (track - 3.dp) * fraction, y = 56.dp)
                    .width(3.dp)
                    .height(13.dp)
                    .background(MaterialTheme.colorScheme.onSurfaceVariant),
            )
            Column(
                modifier = Modifier
                    .offset(x = labelLeft, y = if (above) 8.dp else 96.dp)
                    .width(markerWidth)
                    .clip(RoundedCornerShape(4.dp))
                    .clickable { onSelect(entry.id) }
                    .padding(horizontal = 2.dp),
            ) {
                Text(
                    text = entry.title.ifBlank { "(untitled)" },
                    style = MaterialTheme.typography.labelMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = formatTreeDate(entry.dateMillis, timeZone),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
        }
    }
}

/** One row of the list below the timeline: the tree's title, whether it is live, and its date if any. */
@Composable
private fun TaskTreeRow(
    entry: TaskTreeEntry,
    active: Boolean,
    selected: Boolean,
    timeZone: TimeZone,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                if (selected) MaterialTheme.colorScheme.secondaryContainer
                else MaterialTheme.colorScheme.surface,
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = entry.title.ifBlank { "(untitled)" },
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (active) {
            Text(
                text = "on screen",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.width(10.dp))
        }
        Text(
            text = entry.dateMillis?.let { formatTreeDate(it, timeZone) } ?: "no date",
            style = MaterialTheme.typography.labelMedium,
            color =
                if (entry.dateMillis == null) MaterialTheme.colorScheme.onSurfaceVariant
                else MaterialTheme.colorScheme.onSurface,
        )
    }
}

/**
 * The little window a tree opens: the date that puts it on the timeline, and the bin that deletes it.
 *
 * Deleting the tree that is currently live is deliberately not destructive — the reducer keeps the live
 * tree exactly as it is and merely drops its name — so the bin needs no confirmation step.
 */
@Composable
private fun TaskTreeDetailWindow(
    entry: TaskTreeEntry,
    timeZone: TimeZone,
    onSetDate: (Long?) -> Unit,
    onDelete: () -> Unit,
    onDismiss: () -> Unit,
    onRaise: () -> Unit,
    initialOffset: Offset,
) {
    var offset by remember(entry.id) { mutableStateOf(initialOffset) }
    // Raw text, so a half-typed "2026-1" is not reformatted (or rejected) on every keystroke. Re-seeded
    // when the window switches to another tree, not on every recomposition of this one.
    var dateText by remember(entry.id) {
        mutableStateOf(entry.dateMillis?.let { formatTreeDate(it, timeZone) }.orEmpty())
    }
    // Empty means "take it off the timeline", which is a valid entry rather than a parse failure.
    val parsed = parseTreeDate(dateText, timeZone)
    val valid = dateText.isBlank() || parsed != null

    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surface,
        shadowElevation = 12.dp,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier
            .offset { IntOffset(offset.x.roundToInt(), offset.y.roundToInt()) }
            .requiredWidth(300.dp)
            .raiseOnPress(onRaise),
    ) {
        Column(Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .windowDragHandle { dragAmount ->
                        offset += dragAmount
                    }
                    .padding(start = 14.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = entry.title.ifBlank { "(untitled)" },
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
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

            Column(
                modifier = Modifier.fillMaxWidth().padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = dateText,
                        onValueChange = {
                            dateText = it
                            val next = parseTreeDate(it, timeZone)
                            // Push through only what resolves: a blank field takes the tree off the
                            // timeline, a complete date puts it on, and a half-typed one changes nothing
                            // until it parses.
                            if (it.isBlank()) onSetDate(null) else if (next != null) onSetDate(next)
                        },
                        singleLine = true,
                        isError = !valid,
                        placeholder = { Text("YYYY-MM-DD", style = MaterialTheme.typography.bodySmall) },
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.width(6.dp))
                    Box(
                        modifier = Modifier.size(32.dp).clip(CircleShape).clickable(onClick = onDelete),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text("🗑", style = MaterialTheme.typography.bodyLarge)
                    }
                }
                Text(
                    text =
                        "With a date, this tree is a point on the timeline. Between two dated trees the " +
                            "scheduler follows a continuous blend of their priorities — a task missing " +
                            "from one of them counts as 0% there. Clear the field to take it off.",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** The date a tree sits at, as the `YYYY-MM-DD` the field reads and writes. */
private fun formatTreeDate(millis: Long?, timeZone: TimeZone): String =
    millis?.let { Instant.fromEpochMilliseconds(it).toLocalDateTime(timeZone).date.toString() }.orEmpty()

/** `YYYY-MM-DD` → the start of that local day, or null while the text is not (yet) a date. */
private fun parseTreeDate(text: String, timeZone: TimeZone): Long? {
    val trimmed = text.trim()
    if (trimmed.isEmpty()) return null
    return runCatching { LocalDate.parse(trimmed).atStartOfDayIn(timeZone).toEpochMilliseconds() }.getOrNull()
}
