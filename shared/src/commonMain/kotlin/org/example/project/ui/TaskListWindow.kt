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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt
import org.example.project.scheduler.domain.SchedulerDomain
import org.example.project.scheduler.domain.TitleSimilarity
import org.example.project.scheduler.model.CellId
import org.example.project.scheduler.model.CellListId
import org.example.project.scheduler.model.TaskId
import org.example.project.scheduler.state.SchedulerIntent
import org.example.project.scheduler.state.SchedulerState
import org.example.project.scheduler.state.projectTaskList
import org.example.project.scheduler.ui.TaskTreeView

/** Width of the sorter bar's leading labels, so its rows of chips line up. */
private val SORTER_LABEL_WIDTH = 60.dp

/**
 * **All tasks**: a floating window listing every task the tree holds, flat, under a sorter configuration.
 *
 * The tree answers "how is my work broken down"; this window answers the questions the tree's *shape* hides —
 * which tasks come back most often across the whole tree (their number of occurrences, mirrors included),
 * which ones actually carry the priority, and **which ones the user has written down twice** under two
 * spellings of one name ([TitleSimilarity]). Every figure is read straight off the tree on
 * screen ([SchedulerDomain.taskListEntries]), so a row's percentage is the same number that task shows in
 * the tree.
 *
 * **The rows ARE task cells** — the same [TaskTreeView] the account's own tree and the PRD §4 template are
 * drawn by, over the state [projectTaskList] makes: the live tree re-rooted at a synthetic list holding, in
 * the sorter's order, the first occurrence cell of every listed task. So a row has the tree's chrome, its
 * percentage and minimum-time columns, its Edit Mode, its selection and keyboard, its drag-move, its
 * Ctrl+C/X/V and Ctrl+F, and its full §13 contextual menu — plus **"go to task tree"**, the calendar panel's
 * own entry, which is the question a flat list of the tree's cells naturally raises. Expanding a row shows
 * that task's own sub-tree, because a sub-list belongs to the task id.
 *
 * Four things follow from the root being the *sorter's* order rather than the tree's, and they are the whole
 * of what this window does differently:
 *  - **nothing can be moved into the root.** The blue drop line never appears at root level, so a sub cell
 *    can be dragged anywhere but there (and the reducer refuses such a move as a backstop);
 *  - **a root row has no Mode selector** — it is always renaming. The row IS the task; "change task" there
 *    could only re-point a cell the user is not looking at;
 *  - **"collapse all"** puts every row it opened back, since the flat list is what the window is for;
 *  - **the order is held still while it is edited**, and an **"update order"** button appears as soon as an
 *    edit has moved a row's figure enough to re-sort the list. Re-sorting under the cursor mid-edit is what
 *    that button exists to prevent; pressing it adopts the fresh order.
 *
 * The sorter configuration is **Compose-only state**, like the calendar's zoom and the PRD §4 find bar: how
 * a list is ordered on screen is a way of looking at the tree, never a fact about it, so it is neither
 * persisted nor synced and records no history unit. So is the pinned order the "update order" button
 * adopts — it is that same ordering, one edit behind.
 *
 * Mirrors the other floating windows' drag-title / dismiss / raise-on-press pattern.
 */
@Composable
fun TaskListWindow(
    /** The live state. Both the rows and their figures are read off it here. */
    state: SchedulerState,
    sort: SchedulerDomain.TaskListSort,
    onSortChange: (SchedulerDomain.TaskListSort) -> Unit,
    /** True while the largest figure leads (top to bottom); false puts the smallest first. */
    descending: Boolean,
    onDirectionChange: (Boolean) -> Unit,
    /** Raw dispatch. This window decides what to wrap in [SchedulerIntent.InTaskList] and what to pass on. */
    onIntent: (SchedulerIntent) -> Unit,
    /** Whether this window currently holds the app's focus, i.e. whether its rows own the keyboard. */
    focused: Boolean,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    /** PRD §5/§13: the sort-2 pop-ups the rows open, hoisted to the app so they land on the top layer. */
    onSetWeightWindow: (CellListId?) -> Unit = {},
    onSetRelativeWindow: (CellId?) -> Unit = {},
    onSetEditTask: (TaskId?) -> Unit = {},
    onSetDeepCopyCell: (CellId?) -> Unit = {},
    /** PRD §8 "go to task tree": focus the tree and reveal the task's first cell. */
    onGoToTaskTree: (TaskId) -> Unit = {},
    /** Initial position relative to centered; staggered per window so they open in a clickable cascade. */
    initialOffset: Offset = Offset.Zero,
    /** Persists the window's new drag position when a drag gesture ends (local-only geometry). */
    onOffsetChange: (Offset) -> Unit = {},
    /** Raise this window to the top of the layers — fired on a press anywhere inside it. */
    onRaise: () -> Unit = {},
) {
    var offset by remember { mutableStateOf(initialOffset) }

    // The figures and the order they imply. Keyed on what they actually read rather than on the whole
    // state, which is replaced by every engine tick (records live on the tasks) — ADR 0009. `sort` is a key
    // because it decides which figures are measured at all, not only how they are ordered.
    val entries =
        remember(state.cells, state.lists, state.tasks, sort, descending) {
            SchedulerDomain.taskListEntries(state, sort, descending)
        }
    val freshOrder = remember(entries) { entries.map { it.taskId } }

    // The order actually on screen. It is PINNED, so that editing a row — which is what the rows being task
    // cells is for — cannot re-sort the list from under the cursor: the pin is adopted when the window opens
    // and whenever the sorter itself changes, and "update order" is what adopts it again.
    var pinnedOrder by remember { mutableStateOf(freshOrder) }
    LaunchedEffect(sort, descending) { pinnedOrder = freshOrder }
    // A task created since the pin is appended rather than hidden (the list must still hold every task), and
    // one deleted since drops out. Both make the pinned order differ from the fresh one, which is exactly
    // when the button should offer to re-sort.
    val displayedOrder =
        remember(pinnedOrder, freshOrder) {
            val live = freshOrder.toSet()
            val pinned = pinnedOrder.toSet()
            pinnedOrder.filter { it in live } + freshOrder.filter { it !in pinned }
        }
    val orderOutdated = displayedOrder != freshOrder

    // One walk of the tree for every row's cell, not one walk per row (ADR 0009: the display hot path).
    val firstOccurrences =
        remember(state.cells, state.lists, state.tasks) { SchedulerDomain.firstTaskOccurrences(state) }
    val rootCells: List<CellId> =
        remember(displayedOrder, firstOccurrences) {
            displayedOrder.mapNotNull { firstOccurrences[it]?.cellId }
        }
    val occurrenceCounts = remember(entries) { entries.associate { it.taskId to it.occurrences } }
    // Only populated under the similarity sort — the domain measures it only when that sort asks, so a row
    // shows the figure exactly while it is the one ordering the list.
    val similarities =
        remember(entries) { entries.mapNotNull { e -> e.similarity?.let { e.taskId to it } }.toMap() }

    val projected =
        remember(
            state.cells,
            state.lists,
            state.tasks,
            state.taskListExpanded,
            state.taskListSelection,
            state.taskListEditSession,
            rootCells,
        ) { state.projectTaskList(rootCells) }
    // The percentages the rows show: the LIVE tree's absolute priorities, the identity the tree's own
    // percentage column keeps — never the projection's, whose root is this window's synthetic list.
    val priorities =
        remember(state.cells, state.lists, state.tasks) { SchedulerDomain.absoluteTaskPriorities(state) }

    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surface,
        shadowElevation = 10.dp,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = modifier
            .offset { IntOffset(offset.x.roundToInt(), offset.y.roundToInt()) }
            // requiredWidth (not width) so the window keeps its fixed width whatever the content area is.
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
                // Only offered once there is something to close: the flat list is what the window is for, so
                // a row left open is a row hiding the next one.
                onCollapseAll =
                    if (state.taskListExpanded.isEmpty()) {
                        null
                    } else {
                        { onIntent(SchedulerIntent.CollapseTaskListRows) }
                    },
                // Only offered when an edit has actually moved a row: pressing it otherwise would say
                // nothing, and a button permanently on screen says nothing either.
                onUpdateOrder = if (orderOutdated) ({ pinnedOrder = freshOrder }) else null,
            )
            Box(Modifier.fillMaxWidth().height(1.dp).background(MaterialTheme.colorScheme.outlineVariant))

            if (rootCells.isEmpty()) {
                Text(
                    text = "No task yet — name one in the tree.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                )
            } else {
                TaskTreeView(
                    state = projected,
                    priorities = priorities,
                    onIntent = { intent -> onIntent(intent.forTaskList(rootCells)) },
                    keyboardActive = focused,
                    modifier = Modifier
                        .fillMaxWidth()
                        // Grows with the tasks up to a cap, then scrolls — an account may hold hundreds.
                        .heightIn(max = 460.dp)
                        .padding(vertical = 8.dp, horizontal = 12.dp),
                    onSetWeightWindow = onSetWeightWindow,
                    onSetRelativeWindow = onSetRelativeWindow,
                    onSetEditTask = onSetEditTask,
                    onSetDeepCopyCell = onSetDeepCopyCell,
                    onGoToTaskTree = onGoToTaskTree,
                    // The root's order is the sorter's: nothing may be dropped into it, and its rows are
                    // always renaming (see the class note).
                    allowRootDrop = false,
                    rootRenameOnly = true,
                    // The window's own raise-on-press is what focuses it, so the rows claim no app-wide
                    // focus.
                    refocusWindow = null,
                    // Colours are solved over the LIVE tree, not the projection: a task must be the same
                    // colour here, in the tree and on the calendar (ADR 0013), and the projection's root is
                    // ordered by the sorter.
                    colorSource = state,
                    rowTrailing = { cellId ->
                        // The window's own figures, in the order the sorter offers them: how alike this
                        // row's title is to another task's (only while that is the sort — see `similarities`)
                        // and how many cells point at this row's task, mirrors included.
                        val taskId = projected.cells[cellId]?.taskId
                        taskId?.let { similarities[it] }?.let { TaskSimilarityFigure(it) }
                        taskId?.let { occurrenceCounts[it] }?.let { TaskOccurrenceCount(it) }
                    },
                )
            }
        }
    }
}

/**
 * Which intents raised inside the "All tasks" window act on **that window's rows** and which act on the app.
 *
 * Nearly everything is wrapped: the rows are the task tree's, so its intents have to be reduced against the
 * projection re-rooted at this window's list, or the arrow keys, `Ctrl+A` and Ctrl+F would all walk the
 * tree's order instead and an edit would move the tree's caret. The exceptions are the app-wide ones the tree
 * happens to raise — Undo/Redo walk the app's history stacks, where this window's own units are waiting, and
 * window focus is not a fact about any tree.
 */
private fun SchedulerIntent.forTaskList(rootCells: List<CellId>): SchedulerIntent =
    when (this) {
        is SchedulerIntent.Undo,
        is SchedulerIntent.Redo,
        is SchedulerIntent.UndoSelection,
        is SchedulerIntent.RedoSelection,
        is SchedulerIntent.FocusWindow,
        -> this
        else -> SchedulerIntent.InTaskList(this, rootCells)
    }

/**
 * The "occurrences" figure, drawn at the end of a row — how many cells point at that task, mirrors included.
 *
 * Subdued and narrow on purpose: it rides the tree's ordinary row, so it must not change the row's height
 * (the one thing a second drawing of the tree must never do) nor compete with the title and the percentage.
 */
@Composable
private fun TaskOccurrenceCount(count: Int) {
    Text(
        text = "×$count",
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 8.dp),
    )
}

/**
 * The "similar titles" figure, drawn at the end of a row — the best score this task's title reaches against
 * any other listed task, and, in brackets, how many tasks it reaches it against.
 *
 * Both halves are printed because both order the list: percentages alone would leave a block of equally-alike
 * tasks looking arbitrarily ordered, when in fact the bracket is what ranks them. A task nothing is alike to
 * prints its `0 %` and no bracket — there is no task to count.
 */
@Composable
private fun TaskSimilarityFigure(similarity: TitleSimilarity) {
    Text(
        text = if (similarity.matches == 0) {
            "≈${similarity.best}%"
        } else {
            "≈${similarity.best}% (${similarity.matches})"
        },
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 8.dp),
    )
}

/**
 * The sorter configuration at the top of the window: which figure orders the list, and which way round —
 * plus the two buttons the rows being task cells brought with them.
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
    /** Close every row the window has open, or null while none is. */
    onCollapseAll: (() -> Unit)?,
    /** Adopt the order the current figures imply, or null while the list is already in it. */
    onUpdateOrder: (() -> Unit)?,
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
            Spacer(Modifier.width(8.dp))
            SorterChip(
                label = "Similar titles",
                selected = sort == SchedulerDomain.TaskListSort.Similarity,
                onClick = { onSortChange(SchedulerDomain.TaskListSort.Similarity) },
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
        if (onCollapseAll != null || onUpdateOrder != null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Spacer(Modifier.width(SORTER_LABEL_WIDTH))
                onCollapseAll?.let { collapse ->
                    SorterChip(label = "Collapse all", selected = false, onClick = collapse)
                    Spacer(Modifier.width(8.dp))
                }
                onUpdateOrder?.let { update ->
                    // Filled, unlike the plain chips beside it: it is the one button here that says the list
                    // is no longer in the order the sorter asks for.
                    SorterChip(label = "↻  Update order", selected = true, onClick = update)
                }
            }
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
