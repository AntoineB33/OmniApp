package org.example.project.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt
import org.example.project.scheduler.model.CellId
import org.example.project.scheduler.model.CellListId
import org.example.project.scheduler.model.TaskId
import org.example.project.scheduler.state.SchedulerIntent
import org.example.project.scheduler.state.SchedulerState
import org.example.project.scheduler.state.defaultSubtreePriorities
import org.example.project.scheduler.state.projectDefaultSubtree
import org.example.project.scheduler.ui.TaskTreeView

/**
 * PRD §4/§7 **Default sub-tree**: the floating window where the user draws the sub-tree that appears under
 * every task they create — i.e. under every cell they type a title into while the lateral-menu switch beside
 * this window's button is on.
 *
 * **It IS the task tree** — not a copy of it, the same [TaskTreeView] the account's own tree is drawn by,
 * over the state [projectDefaultSubtree] makes of the template. So it has the task tree's rows and chrome,
 * its percentage and minimum-time columns, its Edit Mode and naming menus, its selection and keyboard, its
 * drag-move, its Ctrl+C/X/V and Ctrl+F, and — the reason this window exists in this shape at all — its full
 * PRD §13 right-click contextual menu: *start this task now*, *edit*, *copy*, *deep copy*, *add default
 * sub-tree*. The one thing added is a **switch per non-empty row**, in its own column after the minimum time.
 *
 * That is possible because the template is a **real tree of real tasks**
 * ([org.example.project.scheduler.state.DefaultSubtreeTemplate]), so a template row has a task for the menu
 * to act on: "edit task" writes a screen switch, a schedule unit and a text onto it, and the graft carries those
 * across along with the row's minimum time and its sub-list's weight table.
 *
 * **The switch** is the node's binding, as it always was:
 *  - **on** (the default) — the row brings a **brand new task id** each time the template is applied, so
 *    every cell built from it is its own task;
 *  - **off** — every cell built from the row **mirrors** the one task the row points at. Pointing a row at an
 *    existing task is the tree's ordinary Change Task menu, in Edit Mode, exactly as anywhere else; a row
 *    pointed at a live task shows that task's own sub-tree, because a sub-list belongs to the task id.
 *
 * Every intent the tree raises is wrapped in [SchedulerIntent.InDefaultSubtree], which is what makes it land
 * on the template instead of the account's tree — except Undo/Redo, which belong to the app's own stacks
 * where the template's history units are waiting.
 *
 * Mirrors the other floating windows' drag-title / dismiss / raise-on-press pattern.
 */
@Composable
fun DefaultSubtreeWindow(
    /** The live state; the template is projected out of it here. */
    state: SchedulerState,
    /** Whether the policy is currently applied (the lateral-menu switch) — shown here, toggled there. */
    enabled: Boolean,
    /** Raw dispatch. This window decides what to wrap and what to pass through. */
    onIntent: (SchedulerIntent) -> Unit,
    /** Whether this window currently holds the app's focus, i.e. whether its tree owns the keyboard. */
    focused: Boolean,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    /** PRD §5/§13: the sort-2 pop-ups the tree opens, hoisted to the app so they land on the top layer. */
    onSetWeightWindow: (CellListId?) -> Unit = {},
    onSetRelativeWindow: (CellId?) -> Unit = {},
    onSetEditTask: (TaskId?) -> Unit = {},
    onSetDeepCopyCell: (CellId?) -> Unit = {},
    /** Initial position relative to centered; staggered per window so they open in a clickable cascade. */
    initialOffset: Offset = Offset.Zero,
    /** Persists the window's new drag position when a drag gesture ends (local-only geometry). */
    onOffsetChange: (Offset) -> Unit = {},
    /** Raise this window to the top of the layers — fired on a press anywhere inside it. */
    onRaise: () -> Unit = {},
) {
    var offset by remember { mutableStateOf(initialOffset) }

    // The template as a tree the task-tree component can draw, and the percentages its rows show — the
    // shares WITHIN the template, which is why they do not come from the projection (see
    // DefaultSubtreeProjection.kt). Keyed on what they actually read rather than on the whole state, which
    // is replaced by every engine tick.
    val projected =
        remember(
            state.cells,
            state.lists,
            state.tasks,
            state.defaultSubtree,
            state.defaultSubtreeSelection,
            state.defaultSubtreeEditSession,
            state.focusedWindow,
        ) { state.projectDefaultSubtree() }
    val priorities =
        remember(state.tasks, state.defaultSubtree) { state.defaultSubtreePriorities() }

    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surface,
        shadowElevation = 10.dp,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = modifier
            .offset { IntOffset(offset.x.roundToInt(), offset.y.roundToInt()) }
            // requiredWidth (not width) so the window keeps its fixed width and does not adapt to the app's
            // width when the content area is narrower than it.
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
                    text = "Default sub-tree",
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

            Text(
                text =
                    "This tree appears under every task you create — whenever you type a title into an " +
                        "empty cell. It is the task tree: edit it the same way, right-click a row for the " +
                        "same menu. A row's switch is ON when it brings a brand new task id, OFF when every " +
                        "cell built from it mirrors the task the row points at.",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
            )
            Text(
                text =
                    if (enabled) {
                        "Currently applied."
                    } else {
                        "Not applied — turn on the switch beside the lateral-menu button to use it."
                    },
                style = MaterialTheme.typography.labelMedium,
                color =
                    if (enabled) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 14.dp).padding(bottom = 8.dp),
            )
            Box(Modifier.fillMaxWidth().height(1.dp).background(MaterialTheme.colorScheme.outlineVariant))

            TaskTreeView(
                state = projected,
                priorities = priorities,
                onIntent = { intent -> onIntent(intent.forDefaultSubtree()) },
                keyboardActive = focused,
                modifier = Modifier
                    .fillMaxWidth()
                    // Grows with the template up to a cap, then scrolls — an edited row's menus are tall.
                    .heightIn(max = 460.dp)
                    .padding(vertical = 8.dp, horizontal = 12.dp),
                onSetWeightWindow = onSetWeightWindow,
                onSetRelativeWindow = onSetRelativeWindow,
                onSetEditTask = onSetEditTask,
                onSetDeepCopyCell = onSetDeepCopyCell,
                // The window's own raise-on-press is what focuses it, so the tree claims no app-wide focus.
                refocusWindow = null,
                // The template is its OWN tree, so it gets its own colour solution: sharing the account's
                // memo would make each of the two trees the "previous answer" the other's ties are settled
                // against, and the cached answer would be thrown away on every recomposition of either.
                hueMemo = remember { TaskHueMemo() },
                rowTrailing = { cellId ->
                    // PRD §4: every non-empty row carries the switch — an empty cell has no task behind it.
                    if (projected.cells[cellId]?.taskId != null) {
                        DefaultSubtreeRowSwitch(
                            checked = cellId !in state.defaultSubtree.boundCells,
                            onToggle = {
                                onIntent(
                                    SchedulerIntent.SetDefaultSubtreeCellBound(
                                        cellId = cellId,
                                        bound = cellId !in state.defaultSubtree.boundCells,
                                    ),
                                )
                            },
                        )
                    }
                },
            )
        }
    }
}

/**
 * Which intents raised inside the template window act on the **template** and which act on the app.
 *
 * Nearly everything is wrapped: the tree's own intents have to land on the template's tree, not the
 * account's. The exceptions are the app-wide ones the tree happens to raise — Undo/Redo walk the app's
 * history stacks, where this window's own [org.example.project.scheduler.state.DefaultSubtreeDelta] units
 * are waiting, so wrapping them would replay a template unit against a projection; and window focus is not a
 * fact about any tree.
 */
private fun SchedulerIntent.forDefaultSubtree(): SchedulerIntent =
    when (this) {
        is SchedulerIntent.Undo,
        is SchedulerIntent.Redo,
        is SchedulerIntent.UndoSelection,
        is SchedulerIntent.RedoSelection,
        is SchedulerIntent.FocusWindow,
        // Already about the template, by name.
        is SchedulerIntent.SetDefaultSubtreeCellBound,
        -> this
        else -> SchedulerIntent.InDefaultSubtree(this)
    }

/**
 * PRD §4: the little switch a non-empty template row carries, in its own column after the minimum time.
 * Compact on purpose — a Material `Switch` measures taller than a task-sheet row and would make the
 * template's rows a different height from the tree's, which is the one thing this window must not do.
 *
 * On means "a brand new task id at every graft"; off means "every cell built from this row mirrors the task
 * the row points at". Unlike the old template editor the switch toggles **both** ways: a row always has a
 * task now, so turning it off never lacks something to point at.
 */
@Composable
private fun DefaultSubtreeRowSwitch(checked: Boolean, onToggle: () -> Unit) {
    Box(
        modifier = Modifier
            .padding(start = 8.dp)
            .size(width = 26.dp, height = 14.dp)
            .clip(RoundedCornerShape(7.dp))
            .background(if (checked) SheetColors.activeBorder else SheetColors.guideLine)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onToggle,
            ),
        contentAlignment = if (checked) Alignment.CenterEnd else Alignment.CenterStart,
    ) {
        Box(
            modifier = Modifier
                .padding(horizontal = 2.dp)
                .size(10.dp)
                .clip(CircleShape)
                .background(Color.White),
        )
    }
}
