package org.example.project.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import org.example.project.scheduler.model.CellId
import org.example.project.scheduler.model.CellListId
import org.example.project.scheduler.model.TaskId
import org.example.project.scheduler.state.SchedulerIntent
import org.example.project.scheduler.state.SchedulerState
import org.example.project.scheduler.state.defaultSubtreePriorities
import org.example.project.scheduler.state.isTitledDefaultSubtreeRow
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
    /** PRD §5/§13: the per-object windows the tree opens, hoisted to the app so they land on the top layer. */
    onSetWeightWindow: (CellListId?) -> Unit = {},
    onSetRelativeWindow: (CellId?) -> Unit = {},
    onSetEditTask: (TaskId?) -> Unit = {},
    /** PRD §5: opens a category's own edit window — hoisted to the app like [onSetEditTask]. */
    onSetEditCategory: (org.example.project.scheduler.model.CategoryId?) -> Unit = {},
    onSetDeepCopyCell: (CellId?) -> Unit = {},
    /** Initial position relative to centered; staggered per window so they open in a clickable cascade. */
    initialOffset: Offset = Offset.Zero,
    /** Initial size in px; `Size.Zero` opens the window at its default size. */
    initialSize: Size = Size.Zero,
    /** Persists the window's new position/size when a move or resize gesture ends (local-only geometry). */
    onGeometryChange: (Offset, Size) -> Unit = { _, _ -> },
    /** Raise this window to the top of the layers — fired on a press anywhere inside it. */
    onRaise: () -> Unit = {},
) {
    val frame = rememberWindowFrameState("DefaultSubtree", initialOffset, initialSize)

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

    AppWindowFrame(
        title = "Default sub-tree",
        state = frame,
        onClose = onDismiss,
        defaultWidth = 560.dp,
        defaultHeight = 560.dp,
        modifier = modifier,
        onRaise = onRaise,
        onGeometryChange = onGeometryChange,
    ) {
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
                // Follows the height the window was given (it is resizable), then scrolls — an edited
                // row's menus are tall.
                .weight(1f)
                .padding(vertical = 8.dp, horizontal = 12.dp),
            onSetWeightWindow = onSetWeightWindow,
            onSetRelativeWindow = onSetRelativeWindow,
            onSetEditTask = onSetEditTask,
            onSetEditCategory = onSetEditCategory,
            onSetDeepCopyCell = onSetDeepCopyCell,
            // The window's own raise-on-press is what focuses it, so the tree claims no app-wide focus.
            refocusWindow = null,
            // A Change Task row is NAMED from the live tree: the path says which of the account's tasks of
            // that title the row points at, and the projection roots at the template, where a live task has
            // no path at all (or the template's own, which says nothing about where it lives).
            namingSource = state,
            // The template is its OWN tree, so it gets its own colour solution: sharing the account's
            // memo would make each of the two trees the "previous answer" the other's ties are settled
            // against, and the cached answer would be thrown away on every recomposition of either.
            hueMemo = remember { TaskHueMemo() },
            rowTrailing = { cellId ->
                // PRD §4: every titled row OF THE TEMPLATE carries the switch. Not the rows drawn under a
                // bound one — those are the live tree's, and a switch is a fact about a template cell — and
                // not an empty row, which keeps pointing at its blank task once emptying it took the list's
                // trailing placeholder with it ([isTitledDefaultSubtreeRow]).
                if (state.isTitledDefaultSubtreeRow(cellId)) {
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
        is SchedulerIntent.UndoPosition,
        is SchedulerIntent.RedoPosition,
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
private fun DefaultSubtreeRowSwitch(checked: Boolean, onToggle: () -> Unit) = InfoHint(
    text =
        if (checked) {
            "ON — new task: every cell built from this row gets its own brand new task. " +
                "Turn off to make them all mirror the task this row points at."
        } else {
            "OFF — mirror: every cell built from this row is the task this row points at. " +
                "Turn on to give each one its own brand new task."
        },
    modifier = Modifier.padding(start = 8.dp),
) {
    Box(
        modifier = Modifier
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
