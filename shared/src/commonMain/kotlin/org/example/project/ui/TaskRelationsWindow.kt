package org.example.project.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt
import org.example.project.scheduler.domain.TaskRelationsDomain
import org.example.project.scheduler.state.SchedulerIntent
import org.example.project.scheduler.state.SchedulerState

/**
 * **Task relations**: a floating window listing every *pair* of a task and the task its priority was
 * expressed **relative to**, in the four sections [TaskRelationsDomain] decides.
 *
 * The tree's own percentage answers "how much of my whole life is this task". A relation answers the other
 * question the priority machinery keeps asking and then forgetting — *how much of THIS sub-tree is it?* —
 * and the app raises it in two places: an **optional row** of a sub-list's priority-weight table, and the
 * **relative-priority window**'s `t_r` drop-down. Both leave a pair behind them, and until this window
 * existed nothing gathered them up.
 *
 * So the sections are, in order: the pairs the user **kept** here by hand, the ones they **acted on** (a
 * weight-table row, or a percentage they actually changed), the ones they only **opened**, and the ones that
 * have **broken** since — the task or the target is gone, or the task no longer sits under the target.
 *
 * Every row carries one button, and which one it is follows from a single fact, `kept`: a pair already in
 * section 1 offers **✕** (strike it off the list entirely), every other row offers **keep** (file it in
 * section 1). That holds in section 4 too, where a kept-and-broken pair is drawn — it is one gesture and its
 * inverse, not a property of the section the row happens to be under.
 *
 * The **✕ also removes the priority-weight-table row** the pair was made of, where that is how it got here:
 * an optional row of the target sub-list's table *is* the relation, so "this is not a relation of mine" has
 * to reach it. This window is therefore the one place a row comes back out of a table, which is why the row
 * says so before it goes.
 *
 * Almost read-only about the tree: filing a pair changes no priority, so nothing here re-plans and the marks
 * are no Undo/Redo unit — only that table row's removal is one, exactly as its creation was. The pairs
 * themselves are authoritative synced state (`SchedulerState.taskRelations`).
 *
 * Mirrors the other floating windows' drag-title / dismiss / raise-on-press pattern.
 */
@Composable
fun TaskRelationsWindow(
    /** The live state. Both the pairs and whether each still resolves are read off it here. */
    state: SchedulerState,
    onIntent: (SchedulerIntent) -> Unit,
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

    // Keyed on what the rows actually read rather than on the whole state, which is replaced by every
    // engine tick (records live on the tasks) — ADR 0009. Section 4 asks the tree for every pair's
    // occurrence chains, which is a walk of the cells apiece, so this must not run per tick.
    val rows =
        remember(state.cells, state.lists, state.tasks, state.taskRelations) {
            TaskRelationsDomain.rows(state)
        }

    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surface,
        shadowElevation = 10.dp,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = modifier
            .offset { IntOffset(offset.x.roundToInt(), offset.y.roundToInt()) }
            // requiredWidth (not width) so the window keeps its fixed width and does not adapt to the
            // app's width when the content area is narrower than it.
            .requiredWidth(560.dp)
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
                    text = "Task relations",
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

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    // Grows with the pairs up to a cap, then scrolls — an account may hold many.
                    .heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState())
                    .padding(vertical = 8.dp),
            ) {
                if (rows.isEmpty()) {
                    Text(
                        text =
                            "No task relation yet. One appears here as soon as a task is added to a " +
                                "priority-weight table, or a cell's percentage is right-clicked for " +
                                "\"relative priority\".",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
                    )
                }
                // Every section is drawn, empty ones included: the four are a fixed frame the user reads
                // the list against, and a section that vanishes when it empties makes the numbering move.
                // (An account with no relation at all shows the sentence above and no frame.)
                val bySection = rows.groupBy { it.section }
                for (section in if (rows.isEmpty()) emptyList() else TaskRelationsDomain.Section.entries) {
                    val inSection = bySection[section].orEmpty()
                    SectionHeader(section, inSection.size)
                    if (inSection.isEmpty()) {
                        Text(
                            text = "(none)",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 22.dp, vertical = 4.dp),
                        )
                    }
                    inSection.forEach { row ->
                        TaskRelationRow(
                            row = row,
                            onKeep = {
                                onIntent(
                                    SchedulerIntent.KeepTaskRelation(row.key.taskId, row.key.relativeTo),
                                )
                            },
                            onDrop = {
                                onIntent(
                                    SchedulerIntent.DropTaskRelation(row.key.taskId, row.key.relativeTo),
                                )
                            },
                        )
                    }
                }
            }
        }
    }
}

/** A section's number, name and the one line saying what puts a pair into it. */
@Composable
private fun SectionHeader(section: TaskRelationsDomain.Section, count: Int) {
    Column(Modifier.fillMaxWidth().padding(start = 14.dp, end = 14.dp, top = 10.dp, bottom = 2.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "${section.ordinal + 1}. ${sectionTitle(section)}",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = "($count)",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            text = sectionSubtitle(section),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private fun sectionTitle(section: TaskRelationsDomain.Section): String = when (section) {
    TaskRelationsDomain.Section.Kept -> "Kept"
    TaskRelationsDomain.Section.Edited -> "Edited"
    TaskRelationsDomain.Section.Opened -> "Opened"
    TaskRelationsDomain.Section.Broken -> "Broken"
}

private fun sectionSubtitle(section: TaskRelationsDomain.Section): String = when (section) {
    TaskRelationsDomain.Section.Kept -> "Put here by you."
    TaskRelationsDomain.Section.Edited ->
        "A row you added to a priority-weight table, or a relative priority you changed."
    TaskRelationsDomain.Section.Opened -> "Opened in the relative-priority window and left as it was."
    TaskRelationsDomain.Section.Broken -> "The task or the target is gone, or the task moved out from under it."
}

/**
 * One pair. The two titles read as `task  ›  target`, the same left-to-right reading the relative-priority
 * window's occurrence chains have, with the line below saying why the pair is listed and — in section 4 —
 * what broke.
 */
@Composable
private fun TaskRelationRow(
    row: TaskRelationsDomain.Row,
    onKeep: () -> Unit,
    onDrop: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = row.taskTitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                Text(
                    text = "  ›  ",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = row.targetTitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
            }
            Text(
                text = rowReason(row),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.width(10.dp))
        // ONE button, and which one it is follows from `kept` alone — never from the section, so a broken
        // pair the user had filed still offers the way back out of section 1.
        RelationButton(
            label = if (row.kept) "✕" else "keep",
            onClick = if (row.kept) onDrop else onKeep,
        )
    }
}

/** Says why the pair is on the list — and, for a broken one, what broke. */
private fun rowReason(row: TaskRelationsDomain.Row): String {
    row.broken?.let {
        return when (it) {
            TaskRelationsDomain.Break.TaskGone -> "the task no longer exists"
            TaskRelationsDomain.Break.TargetGone -> "the target no longer exists"
            TaskRelationsDomain.Break.Moved -> "the task is no longer under the target"
        }
    }
    val reasons = buildList {
        // A kept pair's ✕ takes this row out of the table with it, so the row says so while it can still
        // be read — the strike-off is the only gesture in the app that removes one.
        if (row.inWeightTable) {
            add(
                if (row.kept) "a row of the target's priority-weight table — ✕ removes it from the table too"
                else "a row of the target's priority-weight table",
            )
        }
        if (row.retargeted) add("its relative priority was changed")
        if (isEmpty()) add("the relative-priority window was opened on it")
    }
    return reasons.joinToString(", ")
}

/** A row's single small action button; the same chrome as the chain-cell pin in the relative-priority window. */
@Composable
private fun RelationButton(label: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 4.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
