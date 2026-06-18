package org.example.project.scheduler.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.utf16CodePoint
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.isCtrlPressed as pointerCtrlPressed
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.isShiftPressed as pointerShiftPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import org.example.project.scheduler.domain.SchedulerDomain
import org.example.project.scheduler.domain.SchedulerDomain.VisibleOccurrence
import org.example.project.scheduler.model.CellId
import org.example.project.scheduler.model.CellListId
import org.example.project.scheduler.model.ScheduleUnitEntry
import org.example.project.scheduler.model.TaskId
import kotlin.math.roundToInt
import org.example.project.scheduler.persistence.SchedulerStore
import org.example.project.scheduler.platform.isDeadKey
import org.example.project.scheduler.platform.readSystemClipboardText
import org.example.project.scheduler.platform.writeSystemClipboardText
import org.example.project.scheduler.state.CellEditMode
import org.example.project.scheduler.state.EditExitNavigation
import org.example.project.scheduler.state.SchedulerIntent
import org.example.project.scheduler.state.SchedulerState
import org.example.project.scheduler.state.SelectionNavigate
import kotlinx.coroutines.withTimeoutOrNull

private object SheetColors {
    val grid = Color(0xFFDADCE0)
    val cellBackground = Color.White
    val selectionFill = Color(0xFFE8F0FE)
    val activeBorder = Color(0xFF1A73E8)
    val nonSelectableFill = Color(0xFFF8F9FA)
    val guideLine = Color(0xFFC7CBD1)
    val overflowArrow = Color(0xFFD93025)
    /** PRD §3 / §5: background of a cell or column while it is being drag-moved. */
    val moveDragFill = Color(0xFFCFD3D8)
}

/** Indentation step (dp) per nesting level; also the spacing between hierarchy guide-lines. */
private const val INDENT_STEP_DP = 16

/** Horizontal offset (dp) of a level's guide-line, aligned under that ancestor's expand arrow. */
private const val GUIDE_LINE_OFFSET_DP = 14

/**
 * PRD §2 Priority Display: the text column before the priority percentage is sized to the widest
 * cell text of the sublist, clamped between these bounds so the percentages of one sublist all
 * align at the same horizontal position.
 */
private val PRIORITY_COLUMN_MIN = 56.dp
private val PRIORITY_COLUMN_MAX = 280.dp

/** Fixed width of the displayed priority percentage, so the weight table columns align per row. */
private val PERCENT_COLUMN_WIDTH = 52.dp

/** Width of one weight-table column (number field + stacked +/- buttons). */
private val WEIGHT_COLUMN_WIDTH = 60.dp

/** PRD §10: width of the per-task minimum-time field (minutes input + stacked +/- buttons + unit). */
private val MIN_TIME_COLUMN_WIDTH = 72.dp

/** Renders a priority fraction (0..1) as a percentage with at most one decimal: 50%, 33.3%, 0.4%. */
private fun formatPriorityPercent(fraction: Double): String {
    val tenths = (fraction * 1000).roundToInt()
    val whole = tenths / 10
    val decimal = tenths % 10
    return if (decimal == 0) "$whole%" else "$whole.$decimal%"
}

/** Renders a weight value with a decimal comma, dropping a redundant ",0" (PRD §5 "numbers and comma"). */
private fun formatWeight(value: Double): String {
    val text = if (value == value.toLong().toDouble()) value.toLong().toString() else value.toString()
    return text.replace('.', ',')
}

/**
 * Pending drop location during a double-click & drag move. Keyed by [renderVia] as well as
 * [cellId] so the blue drop line is shown only on the dragged occurrence and not on mirrored
 * copies of the same cell expanded elsewhere (PRD §3: "the blue line and blur aren't mirrored").
 */
private data class MoveDropTarget(
    val cellId: CellId,
    val insertBefore: Boolean,
    val renderVia: CellId?,
)

@Composable
fun TaskSchedulerScreen(
    modifier: Modifier = Modifier,
    store: SchedulerStore? = null,
    vm: TaskSchedulerViewModel = viewModel { TaskSchedulerViewModel(store = store) },
) {
    val state by vm.state.collectAsState()
    val visibleOrder = SchedulerDomain.selectableVisibleOrder(state)
    val visibleOccurrences = SchedulerDomain.selectableVisibleOccurrences(state)
    // PRD §5: absolute priority percentage per task, displayed at the right of each populated cell.
    val priorities = SchedulerDomain.absoluteTaskPriorities(state)
    val focusRequester = remember { FocusRequester() }
    var moveDragActive by remember { mutableStateOf(false) }
    var moveDropTarget by remember { mutableStateOf<MoveDropTarget?>(null) }
    // PRD §5: the sub-list whose priority-weight window is open (opened by clicking a percentage in that
    // sub-list), or null when the window is closed. The window holds the weight table plus a chart of the
    // sub-list's absolute priorities; it is dismissed by clicking outside it.
    var weightTableListId by remember { mutableStateOf<CellListId?>(null) }
    // PRD §10: the cell whose minimum-time field is currently expanded into an input (clicking its
    // simple display opens it), or null when every min-time field shows as a plain label.
    var minTimeEditCellId by remember { mutableStateOf<CellId?>(null) }
    // PRD §10: the minimum-time value the open input started with, so Escape can restore it (mirroring
    // how Edit Mode's Escape reverts a cell to its pre-edit text). Null when no input is open.
    var minTimeEditOriginal by remember { mutableStateOf<Int?>(null) }
    // PRD §13: the leaf task whose "define schedule unit" floating edit window is open, or null when
    // it is closed. Opened from a cell's right-click contextual menu.
    var scheduleUnitTaskId by remember { mutableStateOf<TaskId?>(null) }
    // The task whose "see text" floating text-document window is open, or null when it is closed.
    // Opened from any populated cell's right-click contextual menu.
    var taskTextTaskId by remember { mutableStateOf<TaskId?>(null) }

    // PRD §5: the weight-table window closes if any cell enters Edit Mode. (A vanished sub-list — e.g.
    // via undo — is handled where the window is rendered.)
    LaunchedEffect(state.editSession) {
        if (state.editSession != null) weightTableListId = null
    }

    // PRD §10: the min-time input reverts to a simple display when another cell is selected or any
    // cell enters Edit Mode (mirroring the weight table).
    LaunchedEffect(state.selection.main, state.editSession) {
        val current = minTimeEditCellId
        if (current != null && (state.editSession != null || state.selection.main != current)) {
            minTimeEditCellId = null
        }
    }

    // Vertical window bounds of each visible row, reported via onGloballyPositioned. A press-drag
    // only delivers move events to the row where the pointer went down (Compose retains the hit
    // path while a button is held), so the originating row resolves the cell under the cursor from
    // these shared bounds rather than relying on per-cell hover events that never fire mid-drag.
    // Keyed by occurrence (cellId + renderVia) so a cell mirrored under several expanded parents
    // keeps a distinct band per row and the resolved drop target carries the target row's own
    // renderVia — letting the blue line land in any layer of the tree (PRD §3).
    val rowBounds =
        remember { mutableStateMapOf<VisibleOccurrence, ClosedFloatingPointRange<Float>>() }
    val resolveRowAt: (Float) -> Pair<VisibleOccurrence, Boolean>? = resolve@{ windowY ->
        var last: Pair<VisibleOccurrence, Boolean>? = null
        for (occurrence in visibleOccurrences) {
            val bounds = rowBounds[occurrence] ?: continue
            if (windowY < bounds.start) return@resolve last ?: (occurrence to true)
            val mid = (bounds.start + bounds.endInclusive) / 2f
            last = occurrence to (windowY < mid)
            if (windowY <= bounds.endInclusive) return@resolve last
        }
        last
    }

    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
    }

    LaunchedEffect(state.editSession, state.selection.main) {
        if (state.editSession == null) {
            focusRequester.requestFocus()
        }
    }

    Box(modifier = modifier.fillMaxSize()) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface)
            .focusRequester(focusRequester)
            .focusable()
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                val mod = event.isCtrlPressed || event.isMetaPressed
                if (mod && event.key == Key.Z) {
                    vm.dispatch(SchedulerIntent.Undo)
                    return@onPreviewKeyEvent true
                }
                if (mod && event.key == Key.Y) {
                    vm.dispatch(SchedulerIntent.Redo)
                    return@onPreviewKeyEvent true
                }
                // PRD §5: selection history is undone/redone independently from content history.
                if (event.isAltPressed && event.key == Key.DirectionLeft) {
                    vm.dispatch(SchedulerIntent.UndoSelection)
                    return@onPreviewKeyEvent true
                }
                if (event.isAltPressed && event.key == Key.DirectionRight) {
                    vm.dispatch(SchedulerIntent.RedoSelection)
                    return@onPreviewKeyEvent true
                }
                if (state.editSession != null) {
                    // PRD §4 Cancel: Escape abandons the session, reverting affected cells to their
                    // pre-edit text. Everything else — including Delete (forward-delete) and Ctrl+C/V/A
                    // (the field's usual copy/paste/select-all, PRD §4) — falls through to the edit field.
                    if (event.key == Key.Escape) {
                        vm.dispatch(SchedulerIntent.CancelEdit)
                        return@onPreviewKeyEvent true
                    }
                    return@onPreviewKeyEvent false
                }
                // PRD §10: while a min-time input is open it owns the keyboard. Enter/Tab/Escape act the
                // same as in a cell's Edit Mode (commit + navigate, or cancel); everything else — arrow
                // keys, Home/End, Backspace/Delete, digit entry and the field's own Ctrl+A/C/V — reaches
                // the focused BasicTextField. (Global Ctrl+Z/Y and Alt+arrow selection history above
                // still apply.)
                val minTimeCell = minTimeEditCellId
                if (minTimeCell != null) {
                    when {
                        event.key == Key.Escape -> {
                            // Cancel: restore the value the field opened with, then refocus the tree.
                            val taskId = state.cells[minTimeCell]?.taskId
                            val original = minTimeEditOriginal
                            if (taskId != null && original != null) {
                                vm.dispatch(SchedulerIntent.SetTaskMinimumTime(taskId, original))
                            }
                            minTimeEditCellId = null
                            focusRequester.requestFocus()
                            return@onPreviewKeyEvent true
                        }
                        // Enter / Shift+Tab — commit (the value is already applied live) and move up;
                        // Enter alone moves down; Tab moves into the first child (expanding it if needed).
                        event.key == Key.Enter && event.isShiftPressed -> {
                            minTimeEditCellId = null
                            vm.dispatch(SchedulerIntent.NavigateSelection(SelectionNavigate.Previous, shift = false))
                            return@onPreviewKeyEvent true
                        }
                        event.key == Key.Enter -> {
                            minTimeEditCellId = null
                            vm.dispatch(SchedulerIntent.NavigateSelection(SelectionNavigate.Next, shift = false))
                            return@onPreviewKeyEvent true
                        }
                        event.key == Key.Tab && event.isShiftPressed -> {
                            minTimeEditCellId = null
                            vm.dispatch(SchedulerIntent.NavigateSelection(SelectionNavigate.Previous, shift = false))
                            return@onPreviewKeyEvent true
                        }
                        event.key == Key.Tab -> {
                            minTimeEditCellId = null
                            vm.dispatch(SchedulerIntent.SelectFirstChild)
                            return@onPreviewKeyEvent true
                        }
                    }
                    return@onPreviewKeyEvent false
                }
                // PRD §3/§4 (not in Edit Mode): select-all and tree copy/paste.
                if (mod && event.key == Key.A) {
                    vm.dispatch(SchedulerIntent.SelectAllVisibleCells)
                    return@onPreviewKeyEvent true
                }
                if (mod && event.key == Key.C) {
                    val text = SchedulerDomain.copyTreeText(state, state.selection)
                    if (text.isNotEmpty()) {
                        vm.dispatch(SchedulerIntent.CopySelection)
                        writeSystemClipboardText(text)
                    }
                    return@onPreviewKeyEvent true
                }
                if (mod && event.key == Key.V) {
                    val text = readSystemClipboardText() ?: return@onPreviewKeyEvent false
                    vm.dispatch(SchedulerIntent.PasteTree(text))
                    return@onPreviewKeyEvent true
                }
                when (event.key) {
                    Key.DirectionUp, Key.DirectionLeft -> {
                        vm.dispatch(
                            SchedulerIntent.NavigateSelection(
                                direction = SelectionNavigate.Previous,
                                shift = event.isShiftPressed,
                            ),
                        )
                        return@onPreviewKeyEvent true
                    }
                    Key.DirectionDown, Key.DirectionRight -> {
                        vm.dispatch(
                            SchedulerIntent.NavigateSelection(
                                direction = SelectionNavigate.Next,
                                shift = event.isShiftPressed,
                            ),
                        )
                        return@onPreviewKeyEvent true
                    }
                    // PRD §4: Backspace or Delete empties the selected cells when not editing.
                    Key.Delete, Key.Backspace -> {
                        vm.dispatch(SchedulerIntent.EmptySelectedCells)
                        return@onPreviewKeyEvent true
                    }
                    Key.Enter -> {
                        val multi = state.selection.selected.size > 1
                        if (multi) {
                            vm.dispatch(
                                SchedulerIntent.CycleMainSelection(forward = !event.isShiftPressed),
                            )
                        } else {
                            val main = state.selection.main
                            if (main != null && SchedulerDomain.isSelectableCell(state, main)) {
                                vm.dispatch(SchedulerIntent.BeginEdit(main))
                            }
                        }
                        return@onPreviewKeyEvent true
                    }
                    Key.Tab -> {
                        val multi = state.selection.selected.size > 1
                        if (multi) {
                            vm.dispatch(
                                SchedulerIntent.CycleMainSelection(forward = !event.isShiftPressed),
                            )
                        } else if (event.isShiftPressed) {
                            vm.dispatch(SchedulerIntent.NavigateSelection(SelectionNavigate.Previous))
                        } else {
                            vm.dispatch(SchedulerIntent.SelectFirstChild)
                        }
                        return@onPreviewKeyEvent true
                    }
                    else -> Unit
                }
                if (event.key.isModifierKey()) return@onPreviewKeyEvent true
                val main = state.selection.main ?: return@onPreviewKeyEvent false
                if (!SchedulerDomain.isSelectableCell(state, main)) return@onPreviewKeyEvent false
                // A dead key (^, ¨, ~ …) carries no character of its own — the composed letter is
                // only delivered to a focused field. So open Edit Mode immediately with empty text;
                // the cell becomes the focused field and the following letter composes into it (e.g.
                // ^ then e → ê), instead of the bare letter being swallowed into a fresh edit.
                val typed =
                    if (event.isDeadKey()) {
                        ""
                    } else {
                        event.printableChar() ?: return@onPreviewKeyEvent false
                    }
                // PRD §8 focus: while the calendar is in focus, the tree must not hijack letter typing
                // into Edit Mode — the calendar (and its edit window) owns the keyboard then.
                if (state.calendarFocused) return@onPreviewKeyEvent false
                vm.dispatch(SchedulerIntent.BeginEdit(main, typed))
                true
            }
            .padding(12.dp),
    ) {
        Text(
            text = "Task Scheduler",
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = { vm.dispatch(SchedulerIntent.ClearSelection) },
            ),
        )
        Spacer(Modifier.height(12.dp))

        Column(
            modifier = Modifier
                .verticalScroll(rememberScrollState())
                .pointerInput(Unit) {
                    detectTapGestures {
                        vm.dispatch(SchedulerIntent.ClearSelection)
                    }
                },
        ) {
            CellListSection(
                state = state,
                listId = state.rootListId,
                renderVia = null,
                depth = 0,
                visibleOrder = visibleOrder,
                priorities = priorities,
                onTogglePriorityWeights = { listId ->
                    weightTableListId = if (weightTableListId == listId) null else listId
                },
                minTimeEditCellId = minTimeEditCellId,
                onToggleMinTimeEdit = { cellId ->
                    if (minTimeEditCellId == cellId) {
                        minTimeEditCellId = null
                    } else {
                        // Snapshot the value the field opens with so Escape can revert to it (PRD §10).
                        minTimeEditOriginal =
                            state.cells[cellId]?.taskId?.let { state.tasks[it]?.minimumMinutes } ?: 0
                        minTimeEditCellId = cellId
                    }
                },
                onOpenScheduleUnit = { taskId -> scheduleUnitTaskId = taskId },
                onOpenTaskText = { taskId -> taskTextTaskId = taskId },
                moveDragActive = moveDragActive,
                moveDropTarget = moveDropTarget,
                resolveRowAt = resolveRowAt,
                onRowBounds = { occurrence, top, bottom -> rowBounds[occurrence] = top..bottom },
                onMoveDragStart = { moveDragActive = true },
                onMoveDropHover = { target, insertBefore, via ->
                    moveDropTarget = MoveDropTarget(target, insertBefore, via)
                },
                onMoveDragEnd = {
                    val target = moveDropTarget
                    if (moveDragActive && target != null) {
                        vm.dispatch(
                            SchedulerIntent.MoveSelectedCells(
                                targetCellId = target.cellId,
                                insertBefore = target.insertBefore,
                            ),
                        )
                    }
                    moveDragActive = false
                    moveDropTarget = null
                },
                onIntent = { intent ->
                    // PRD §8 focus: a click into the tree hands focus back from the calendar, so typing
                    // resumes entering Edit Mode — even on an already-selected cell (whose selection
                    // doesn't change, so the selection-keyed refocus effect wouldn't fire) and even
                    // while the calendar window stays open.
                    if (intent is SchedulerIntent.ClickCell) {
                        // PRD §10: but when the click lands on the cell whose min-time input is open, the
                        // BasicTextField needs to keep the focus it just took — yanking it back to the root
                        // focusable here is what made the caret vanish right after clicking the field.
                        if (intent.cellId != minTimeEditCellId) {
                            focusRequester.requestFocus()
                        }
                        if (state.calendarFocused) vm.dispatch(SchedulerIntent.SetCalendarFocus(false))
                    }
                    vm.dispatch(intent)
                },
            )
        }
    }

        // PRD §13: the floating "define schedule unit" window, overlaying the tree.
        scheduleUnitTaskId?.let { taskId ->
            val task = state.tasks[taskId]
            if (task == null) {
                scheduleUnitTaskId = null
            } else {
                ScheduleUnitEditWindow(
                    initialEntries = task.scheduleUnit,
                    minimumMinutes = task.minimumMinutes,
                    onSave = { entries ->
                        vm.dispatch(SchedulerIntent.SetScheduleUnit(taskId, entries))
                        scheduleUnitTaskId = null
                    },
                    onDismiss = { scheduleUnitTaskId = null },
                )
            }
        }

        // The floating "see text" window — a free-form text document for the task, overlaying the tree.
        taskTextTaskId?.let { taskId ->
            val task = state.tasks[taskId]
            if (task == null) {
                taskTextTaskId = null
            } else {
                TaskTextWindow(
                    taskTitle = task.title,
                    initialText = task.text,
                    onSave = { text ->
                        vm.dispatch(SchedulerIntent.SetTaskText(taskId, text))
                        taskTextTaskId = null
                    },
                    onDismiss = { taskTextTaskId = null },
                )
            }
        }

        // PRD §5: the floating priority-weight window — a chart of the sub-list's absolute priorities on
        // the left, the editable weight table on the right. Opened by clicking a percentage in the tree.
        weightTableListId?.let { listId ->
            if (state.lists[listId] == null) {
                weightTableListId = null
            } else {
                PriorityWeightWindow(
                    state = state,
                    listId = listId,
                    priorities = priorities,
                    onIntent = { vm.dispatch(it) },
                    onDismiss = { weightTableListId = null },
                )
            }
        }
    }
}

private fun androidx.compose.ui.input.key.Key.isModifierKey(): Boolean =
    when (this) {
        Key.ShiftLeft,
        Key.ShiftRight,
        Key.CtrlLeft,
        Key.CtrlRight,
        Key.AltLeft,
        Key.AltRight,
        Key.MetaLeft,
        Key.MetaRight,
        -> true
        else -> false
    }

private fun androidx.compose.ui.input.key.KeyEvent.printableChar(): String? {
    if (isCtrlPressed || isMetaPressed) return null
    if (key.isModifierKey()) return null
    if (key == Key.Enter || key == Key.Tab || key == Key.Escape || key == Key.Backspace) return null
    if (key == Key.DirectionUp || key == Key.DirectionDown ||
        key == Key.DirectionLeft || key == Key.DirectionRight
    ) {
        return null
    }
    val codePoint = utf16CodePoint
    if (!codePoint.isValidTextCodePoint()) return null
    return Char(codePoint).toString()
}

/** Rejects control codes and Unicode non-characters (e.g. U+FFFF from bare Shift on desktop). */
private fun Int.isValidTextCodePoint(): Boolean {
    if (this <= 0x1F) return false
    if (this in 0x7F..0x9F) return false
    if (this in 0xFDD0..0xFDEF) return false
    if ((this and 0xFFFE) == 0xFFFE) return false
    return true
}

@Composable
private fun CellListSection(
    state: SchedulerState,
    listId: CellListId,
    renderVia: CellId?,
    depth: Int,
    visibleOrder: List<CellId>,
    priorities: Map<TaskId, Double>,
    onTogglePriorityWeights: (CellListId) -> Unit,
    minTimeEditCellId: CellId?,
    onToggleMinTimeEdit: (CellId) -> Unit,
    onOpenScheduleUnit: (TaskId) -> Unit,
    onOpenTaskText: (TaskId) -> Unit,
    moveDragActive: Boolean,
    moveDropTarget: MoveDropTarget?,
    resolveRowAt: (Float) -> Pair<VisibleOccurrence, Boolean>?,
    onRowBounds: (VisibleOccurrence, Float, Float) -> Unit,
    onMoveDragStart: () -> Unit,
    onMoveDropHover: (CellId, Boolean, CellId?) -> Unit,
    onMoveDragEnd: () -> Unit,
    onIntent: (SchedulerIntent) -> Unit,
) {
    val list = state.lists[listId] ?: return

    // PRD §2 Priority Display: align this sublist's percentages at one horizontal position — the
    // widest cell text in the list, clamped to [MIN, MAX].
    val textMeasurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val bodyStyle = MaterialTheme.typography.bodyMedium
    val cellTextPx: Map<CellId, Int> =
        list.cellIds.associateWith { id ->
            val title = state.cells[id]?.taskId?.let { state.tasks[it]?.title }.orEmpty()
            if (title.isEmpty()) 0 else textMeasurer.measure(title, bodyStyle).size.width
        }
    val priorityColumnWidth: Dp =
        with(density) { (cellTextPx.values.maxOrNull() ?: 0).toDp() }
            .coerceIn(PRIORITY_COLUMN_MIN, PRIORITY_COLUMN_MAX)
    val priorityColumnPx = with(density) { priorityColumnWidth.toPx() }

    list.cellIds.forEach { cellId ->
        val cell = state.cells[cellId] ?: return@forEach
        val title = cell.taskId?.let { state.tasks[it]?.title }.orEmpty()
        val selectable = SchedulerDomain.isSelectableCell(state, cellId)
        val showHighlight =
            SchedulerDomain.shouldShowSelectionHighlight(state.selection, cellId, renderVia)
        val isMainSelection = selectable && showHighlight && state.selection.main == cellId
        val isInSelectionRange = selectable && showHighlight
        val isEditing =
            state.editSession?.let { it.cellId == cellId && it.renderVia == renderVia } ?: false
        val editDraft = if (isEditing) state.editSession!!.draftText else title
        val hasChildren = SchedulerDomain.hasExpandableSubTree(state, cellId)
        val expanded = cellId in state.expanded

        val priorityLabel =
            cell.taskId?.let { priorities[it] }?.let(::formatPriorityPercent)

        val isInActiveSelection = SchedulerDomain.isInActiveSelection(state.selection, cellId)
        val canMoveFromCell =
            isInActiveSelection &&
                SchedulerDomain.canDragMoveSelection(state, state.selection)
        // Blur the cells being dragged. Scope it to this occurrence via the render-via–aware
        // highlight so mirrored copies of the same cell stay sharp (PRD §3: blur isn't mirrored).
        val isBeingMoved =
            moveDragActive && isInSelectionRange &&
                SchedulerDomain.canDragMoveSelection(state, state.selection)

        TaskRow(
            depth = depth,
            cellId = cellId,
            renderVia = renderVia,
            displayTitle = if (isEditing) editDraft else title,
            isMainSelection = isMainSelection,
            isInSelectionRange = isInSelectionRange,
            selectable = selectable,
            isEditing = isEditing,
            hasChildren = hasChildren,
            expanded = expanded,
            moveDropBefore =
                moveDropTarget?.cellId == cellId &&
                    moveDropTarget.renderVia == renderVia &&
                    moveDropTarget.insertBefore,
            moveDropAfter =
                moveDropTarget?.cellId == cellId &&
                    moveDropTarget.renderVia == renderVia &&
                    !moveDropTarget.insertBefore,
            canMoveFromCell = canMoveFromCell,
            isBeingMoved = isBeingMoved,
            priorityLabel = priorityLabel,
            priorityColumnWidth = priorityColumnWidth,
            textOverflow = (cellTextPx[cellId] ?: 0) > priorityColumnPx,
            minMinutes = cell.taskId?.let { state.tasks[it]?.minimumMinutes } ?: 0,
            minTimeEditing = minTimeEditCellId == cellId,
            // PRD §13: the "define schedule unit" menu only appears for a populated leaf cell (a task
            // with no child task); null hides it (empty cells, the root/main, and parent tasks).
            onDefineScheduleUnit =
                cell.taskId
                    ?.takeIf { selectable && SchedulerDomain.isLeafTask(state, it) }
                    ?.let { taskId -> { onOpenScheduleUnit(taskId) } },
            // "See text" appears for any populated cell (leaf or parent); null for empty / root-main cells.
            onSeeText =
                cell.taskId
                    ?.takeIf { selectable }
                    ?.let { taskId -> { onOpenTaskText(taskId) } },
            onTogglePriorityWeights = { onTogglePriorityWeights(listId) },
            onSetMinTime = { minutes ->
                cell.taskId?.let { onIntent(SchedulerIntent.SetTaskMinimumTime(it, minutes)) }
            },
            onActivateMinTime = {
                // Select this cell so the input persists (PRD §10: it reverts when another cell is
                // selected) and typing is routed to the field instead of entering Edit Mode.
                onIntent(
                    SchedulerIntent.ClickCell(
                        cellId = cellId,
                        ctrl = false,
                        shift = false,
                        visibleOrder = visibleOrder,
                        renderVia = renderVia,
                        forceClearMulti = true,
                    ),
                )
                onToggleMinTimeEdit(cellId)
            },
            onClick = { clicked, ctrl, shift, forceClearMulti ->
                if (!selectable) return@TaskRow
                onIntent(
                    SchedulerIntent.ClickCell(
                        cellId = clicked,
                        ctrl = ctrl,
                        shift = shift,
                        visibleOrder = visibleOrder,
                        renderVia = renderVia,
                        forceClearMulti = forceClearMulti,
                    ),
                )
            },
            onDragSelect = { anchor, hover ->
                onIntent(
                    SchedulerIntent.DragSelectCells(
                        anchorCellId = anchor,
                        hoverCellId = hover,
                        visibleOrder = visibleOrder,
                        renderVia = renderVia,
                    ),
                )
            },
            moveDragActive = moveDragActive,
            resolveRowAt = resolveRowAt,
            onRowBounds = onRowBounds,
            onMoveDragStart = onMoveDragStart,
            onMoveDropHover = { target, insertBefore, via ->
                onMoveDropHover(target, insertBefore, via)
            },
            onMoveDragEnd = onMoveDragEnd,
            onDoubleClick = {
                if (selectable && !isEditing) {
                    onIntent(SchedulerIntent.BeginEdit(cellId))
                }
            },
            onTextChange = { newText ->
                onIntent(SchedulerIntent.UpdateEditText(newText))
            },
            onExitEdit = { navigation ->
                onIntent(SchedulerIntent.ExitEdit(navigation))
            },
            onToggleExpand = {
                if (hasChildren) onIntent(SchedulerIntent.ToggleExpand(cellId))
            },
            editMenus =
                if (isEditing) {
                    {
                        EditModeMenus(
                            state = state,
                            cellId = cellId,
                            draftText = editDraft,
                            onIntent = onIntent,
                        )
                    }
                } else {
                    null
                },
        )

        if (expanded && hasChildren) {
            val childListId = state.tasks[cell.taskId]!!.childListId!!
            CellListSection(
                state = state,
                listId = childListId,
                renderVia = cellId,
                depth = depth + 1,
                visibleOrder = visibleOrder,
                priorities = priorities,
                onTogglePriorityWeights = onTogglePriorityWeights,
                minTimeEditCellId = minTimeEditCellId,
                onToggleMinTimeEdit = onToggleMinTimeEdit,
                onOpenScheduleUnit = onOpenScheduleUnit,
                onOpenTaskText = onOpenTaskText,
                moveDragActive = moveDragActive,
                moveDropTarget = moveDropTarget,
                resolveRowAt = resolveRowAt,
                onRowBounds = onRowBounds,
                onMoveDragStart = onMoveDragStart,
                onMoveDropHover = onMoveDropHover,
                onMoveDragEnd = onMoveDragEnd,
                onIntent = onIntent,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EditModeMenus(
    state: SchedulerState,
    cellId: CellId,
    draftText: String,
    onIntent: (SchedulerIntent) -> Unit,
) {
    val session = state.editSession ?: return
    var modeMenuExpanded by remember(session.cellId) { mutableStateOf(false) }
    val modeLabel =
        when (session.mode) {
            CellEditMode.ChangeTask -> "Change Task"
            CellEditMode.Rename -> "Rename"
        }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        ExposedDropdownMenuBox(
            expanded = modeMenuExpanded,
            onExpandedChange = { modeMenuExpanded = it },
        ) {
            OutlinedTextField(
                modifier = Modifier
                    .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable)
                    .widthIn(max = 220.dp),
                value = modeLabel,
                onValueChange = {},
                readOnly = true,
                singleLine = true,
                label = { Text("Mode") },
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = modeMenuExpanded) },
                colors = ExposedDropdownMenuDefaults.outlinedTextFieldColors(),
            )
            ExposedDropdownMenu(
                expanded = modeMenuExpanded,
                onDismissRequest = { modeMenuExpanded = false },
            ) {
                DropdownMenuItem(
                    text = { Text("Change Task") },
                    onClick = {
                        onIntent(SchedulerIntent.SetEditMode(CellEditMode.ChangeTask))
                        modeMenuExpanded = false
                    },
                )
                DropdownMenuItem(
                    text = { Text("Rename") },
                    onClick = {
                        onIntent(SchedulerIntent.SetEditMode(CellEditMode.Rename))
                        modeMenuExpanded = false
                    },
                )
            }
        }

        if (session.mode == CellEditMode.ChangeTask) {
            // Only the in-progress "New task" draft is hidden (it's already the "New task" row itself). A
            // picked existing task must stay listed so it can render as selected (purple) — excluding it
            // here would drop it from the entries and leave [changeTaskMenuSelectedIndex] unable to match.
            val excludeFromMenu = session.newTaskDraftId
            val taskEntries =
                SchedulerDomain.changeTaskMenuEntries(
                    state,
                    cellId,
                    draftText,
                    excludeTaskId = excludeFromMenu,
                )
            if (taskEntries.size > 1) {
                val selectedIndex =
                    SchedulerDomain.changeTaskMenuSelectedIndex(
                        taskEntries,
                        session.selectedAssignTaskId,
                    )
                Text(
                    text = "Tasks",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                taskEntries.forEachIndexed { index, entry ->
                    TaskMenuRow(
                        label = entry.label,
                        selected = index == selectedIndex,
                        enabled = entry.assignable,
                        onClick = {
                            if (entry.taskId == null) {
                                onIntent(SchedulerIntent.SelectCreateAssignTask)
                            } else {
                                onIntent(SchedulerIntent.PickTaskFromMenu(entry.taskId))
                            }
                        },
                    )
                }
            }
        }

        val suggestions = SchedulerDomain.titleSuggestions(state, draftText)
        if (suggestions.isNotEmpty()) {
            Text(
                text = "Title suggestions",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            suggestions.take(8).forEach { suggestion ->
                TaskMenuRow(
                    label = suggestion,
                    onClick = { onIntent(SchedulerIntent.PickTitleSuggestion(suggestion)) },
                )
            }
        }
    }
}

@Composable
private fun TaskMenuRow(
    label: String,
    selected: Boolean = false,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    Text(
        modifier = Modifier
            .fillMaxWidth()
            .then(
                if (enabled) Modifier.clickable(onClick = onClick)
                else Modifier
            )
            .padding(vertical = 4.dp, horizontal = 8.dp),
        text = label,
        style =
            if (selected) MaterialTheme.typography.bodyMedium
            else MaterialTheme.typography.bodySmall,
        color =
            when {
                !enabled -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                selected -> MaterialTheme.colorScheme.primary
                else -> MaterialTheme.colorScheme.onSurface
            },
    )
}

/** Steps [value] by [delta], clamps to [0, maxValue], and rounds off binary-float noise. */
private fun stepWeight(value: Double, delta: Double, maxValue: Double): Double {
    val next = (value + delta).coerceIn(0.0, maxValue)
    return (next * 10000).roundToInt() / 10000.0
}

/**
 * PRD §5 priority weight: one weight-table cell — a number input (digits and a decimal comma) with
 * the increment/decrement buttons stacked vertically to its right. Each step adds/removes [step]
 * (1 for cells, 0.1 for the header row); the value is clamped to `[0, maxValue]`.
 */
@Composable
private fun WeightInputCell(
    value: Double,
    onSet: (Double) -> Unit,
    modifier: Modifier = Modifier,
    maxValue: Double = Double.POSITIVE_INFINITY,
    step: Double = 1.0,
) {
    var text by remember(value) { mutableStateOf(formatWeight(value)) }
    Row(
        modifier = modifier.width(WEIGHT_COLUMN_WIDTH).padding(horizontal = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BasicTextField(
            value = text,
            onValueChange = { raw ->
                val cleaned = raw.filter { it.isDigit() || it == ',' || it == '.' }
                text = cleaned
                cleaned.replace(',', '.').toDoubleOrNull()?.let { onSet(it.coerceIn(0.0, maxValue)) }
            },
            singleLine = true,
            textStyle = MaterialTheme.typography.bodySmall.copy(
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.End,
            ),
            cursorBrush = SolidColor(SheetColors.activeBorder),
            modifier = Modifier
                .weight(1f)
                .border(1.dp, SheetColors.grid)
                .padding(horizontal = 4.dp, vertical = 3.dp),
        )
        Column {
            WeightStepButton(label = "▲", onClick = { onSet(stepWeight(value, step, maxValue)) })
            WeightStepButton(label = "▼", onClick = { onSet(stepWeight(value, -step, maxValue)) })
        }
    }
}

@Composable
private fun WeightStepButton(label: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(width = 16.dp, height = 11.dp)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.primary,
        )
    }
}

/**
 * PRD §10 Minimum time: a task's minimum time (in minutes) shown to the right of the priority-weight
 * display — and to the right of the weight table when it is open, so it shifts as columns change. An
 * integer input field with the increment/decrement buttons stacked to its right, mirroring the weight
 * fields; the value is clamped to ≥ 0.
 */
@Composable
private fun MinTimeInputCell(
    minutes: Int,
    onSet: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    var text by remember(minutes) { mutableStateOf(minutes.toString()) }
    Row(
        modifier = modifier.width(MIN_TIME_COLUMN_WIDTH).padding(horizontal = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BasicTextField(
            value = text,
            onValueChange = { raw ->
                val cleaned = raw.filter { it.isDigit() }
                text = cleaned
                onSet(cleaned.toIntOrNull()?.coerceAtLeast(0) ?: 0)
            },
            singleLine = true,
            textStyle = MaterialTheme.typography.bodySmall.copy(
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.End,
            ),
            cursorBrush = SolidColor(SheetColors.activeBorder),
            modifier = Modifier
                .weight(1f)
                .border(1.dp, SheetColors.grid)
                .padding(horizontal = 4.dp, vertical = 3.dp),
        )
        Text(
            text = "m",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 2.dp),
        )
        Column {
            WeightStepButton(label = "▲", onClick = { onSet(minutes + 1) })
            WeightStepButton(label = "▼", onClick = { onSet((minutes - 1).coerceAtLeast(0)) })
        }
    }
}

/**
 * PRD §10 Minimum time (resting state): a plain "{n}m" label occupying the same column as
 * [MinTimeInputCell]. Clicking it expands the field into the editable input (mirroring how clicking
 * the absolute-priority percentage reveals the weight table).
 */
@Composable
private fun MinTimeDisplayCell(
    minutes: Int,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .width(MIN_TIME_COLUMN_WIDTH)
            .padding(horizontal = 2.dp)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            )
            .padding(horizontal = 4.dp, vertical = 3.dp),
        contentAlignment = Alignment.CenterEnd,
    ) {
        Text(
            text = "${minutes}m",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * Vertical blue line marking where a dragged column will be dropped (PRD §5). It fills the height
 * of whichever row it sits in (with a minimum) so the per-row segments stack into one continuous
 * line running all the way down the column.
 */
@Composable
private fun ColumnDropLine() {
    Box(
        modifier = Modifier
            .padding(horizontal = 1.dp)
            .fillMaxHeight()
            .heightIn(min = 22.dp)
            .width(2.dp)
            .background(SheetColors.activeBorder),
    )
}

/**
 * PRD §5: grab handle sitting above a weight-table column. It is a thick, raised bar with a grip
 * pattern so the user can tell it can be grabbed (drag to reorder) and right-clicked (column menu).
 */
@Composable
private fun ColumnDragHandle(active: Boolean) {
    val accent = if (active) SheetColors.activeBorder else SheetColors.guideLine
    Box(
        modifier = Modifier
            .width(WEIGHT_COLUMN_WIDTH)
            .padding(horizontal = 2.dp)
            .height(20.dp)
            .background(
                if (active) SheetColors.moveDragFill else SheetColors.nonSelectableFill,
                RoundedCornerShape(4.dp),
            )
            .border(1.dp, accent, RoundedCornerShape(4.dp)),
        contentAlignment = Alignment.Center,
    ) {
        // Grip pattern (three short bars) — the conventional "draggable" affordance.
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            repeat(3) {
                Box(
                    modifier = Modifier
                        .width(22.dp)
                        .height(2.dp)
                        .background(accent, RoundedCornerShape(1.dp)),
                )
            }
        }
    }
}

/**
 * PRD §5 priority weight table header: a row of grab handles sits right above the header row of
 * editable column weights (each weight clamped 0..1, step 0.1). Grabbing a handle and dragging
 * reorders that column — it gets a grey background and a vertical blue line shows the drop
 * position, with the move committed on release. Right-clicking a handle reveals "Add column to the
 * right", "Reset to default" and (unless it is the only column) "Delete column".
 */
@Composable
private fun WeightTableHeader(
    depth: Int,
    leadingWidth: Dp,
    weightColumns: List<Double>,
    draggedColumn: Int?,
    dropIndex: Int?,
    onDraggedColumnChange: (Int?) -> Unit,
    onDropIndexChange: (Int?) -> Unit,
    onSetColumnWeight: (Int, Double) -> Unit,
    onAddColumn: (Int) -> Unit,
    onResetColumn: (Int) -> Unit,
    onDeleteColumn: (Int) -> Unit,
    onMoveColumn: (Int, Int) -> Unit,
) {
    val columnBounds = remember { mutableStateMapOf<Int, ClosedFloatingPointRange<Float>>() }

    fun resolveDrop(windowX: Float): Int {
        for (c in weightColumns.indices) {
            val bounds = columnBounds[c] ?: continue
            val mid = (bounds.start + bounds.endInclusive) / 2f
            if (windowX < mid) return c
        }
        return weightColumns.size
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = (depth * INDENT_STEP_DP).dp + 4.dp, top = 2.dp, bottom = 2.dp),
    ) {
        // PRD §5: handle row — grab a handle to drag-reorder its column, right-click for the menu.
        Row(verticalAlignment = Alignment.CenterVertically) {
            Spacer(Modifier.width(leadingWidth))
            weightColumns.forEachIndexed { column, _ ->
                if (draggedColumn != null && dropIndex == column) ColumnDropLine()
                var menuOpen by remember { mutableStateOf(false) }
                Box(
                    modifier = Modifier
                        .background(
                            if (draggedColumn == column) SheetColors.moveDragFill else Color.Transparent,
                        )
                        .pointerInput(column, weightColumns.size) {
                            val slop = viewConfiguration.touchSlop
                            awaitPointerEventScope {
                                while (true) {
                                    // Wait for a button press. Inspecting the Press event's
                                    // `buttons` directly is the commonMain-safe way to tell a
                                    // right-click from a left-click (awaitFirstDown can't).
                                    var press = awaitPointerEvent()
                                    while (press.type != PointerEventType.Press) {
                                        press = awaitPointerEvent()
                                    }
                                    if (press.buttons.isSecondaryPressed) {
                                        // PRD §5: right-click opens the column menu. Consume so the
                                        // freshly opened popup isn't dismissed by the same click.
                                        press.changes.forEach { it.consume() }
                                        menuOpen = true
                                        continue
                                    }
                                    // Left press → drag-reorder this column. Track the live drop
                                    // target locally (the hoisted state, captured at launch, would
                                    // be stale inside this long-running gesture) and mirror it out
                                    // through the callbacks so the whole column re-renders.
                                    val down = press.changes.first()
                                    down.consume()
                                    var started = false
                                    var traveled = 0f
                                    var localDrop: Int? = null
                                    while (true) {
                                        val event = awaitPointerEvent()
                                        if (!event.changes.any { it.pressed }) {
                                            if (started) localDrop?.let { onMoveColumn(column, it) }
                                            onDraggedColumnChange(null)
                                            onDropIndexChange(null)
                                            break
                                        }
                                        val change =
                                            event.changes.firstOrNull { it.id == down.id }
                                                ?: event.changes.first()
                                        traveled += change.positionChange().getDistance()
                                        if (!started && traveled > slop) {
                                            started = true
                                            onDraggedColumnChange(column)
                                        }
                                        if (started) {
                                            change.consume()
                                            val windowX =
                                                (columnBounds[column]?.start ?: 0f) + change.position.x
                                            localDrop = resolveDrop(windowX)
                                            onDropIndexChange(localDrop)
                                        }
                                    }
                                }
                            }
                        },
                ) {
                    ColumnDragHandle(active = draggedColumn == column)
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(
                            text = { Text("Add column to the right") },
                            onClick = {
                                menuOpen = false
                                onAddColumn(column + 1)
                            },
                        )
                        DropdownMenuItem(
                            text = { Text("Reset to default") },
                            onClick = {
                                menuOpen = false
                                onResetColumn(column)
                            },
                        )
                        if (weightColumns.size > 1) {
                            DropdownMenuItem(
                                text = { Text("Delete column") },
                                onClick = {
                                    menuOpen = false
                                    onDeleteColumn(column)
                                },
                            )
                        }
                    }
                }
            }
            if (draggedColumn != null && dropIndex == weightColumns.size) ColumnDropLine()
        }
        // PRD §5: header row of editable column weights, aligned above the cell rows.
        Row(verticalAlignment = Alignment.CenterVertically) {
            Spacer(Modifier.width(leadingWidth))
            weightColumns.forEachIndexed { column, weight ->
                if (draggedColumn != null && dropIndex == column) ColumnDropLine()
                Box(
                    modifier = Modifier
                        .onGloballyPositioned { coords ->
                            val x = coords.positionInWindow().x
                            columnBounds[column] = x..(x + coords.size.width)
                        }
                        .background(
                            if (draggedColumn == column) SheetColors.moveDragFill else Color.Transparent,
                        ),
                ) {
                    // PRD §5: a column header weight can only span 0..1 and steps by 0.1.
                    WeightInputCell(
                        value = weight,
                        onSet = { onSetColumnWeight(column, it) },
                        maxValue = 1.0,
                        step = 0.1,
                    )
                }
            }
            if (draggedColumn != null && dropIndex == weightColumns.size) ColumnDropLine()
        }
    }
}

/** Width of the task-title column inside the priority-weight window. */
private val WEIGHT_WINDOW_TITLE_WIDTH = 160.dp

/**
 * PRD §5 priority-weight window: a floating window opened by clicking a sub-list's absolute priority
 * percentage. Its left side is the editable weight table (a draggable/reorderable column header plus a
 * weight input per cell per column); its right side is a circular (pie) chart of the absolute priority
 * percentages of every task in the sub-list. Dismissed by clicking outside it.
 */
@Composable
private fun PriorityWeightWindow(
    state: SchedulerState,
    listId: CellListId,
    priorities: Map<TaskId, Double>,
    onIntent: (SchedulerIntent) -> Unit,
    onDismiss: () -> Unit,
) {
    val list = state.lists[listId] ?: return
    // The rows of the table / chart are the populated cells of the sub-list (those with a priority).
    val populated =
        list.cellIds.filter { id -> state.cells[id]?.taskId?.let { priorities[it] != null } == true }
    var draggedColumn by remember(listId) { mutableStateOf<Int?>(null) }
    var columnDropIndex by remember(listId) { mutableStateOf<Int?>(null) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.25f))
            // Tap (not clickable) to dismiss so a Space/Enter in a field can't close the window.
            .pointerInput(Unit) { detectTapGestures { onDismiss() } },
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.surface,
            shadowElevation = 12.dp,
            border = BorderStroke(1.dp, SheetColors.grid),
            // Bound the window to the screen (so the chart on the right is never pushed off-screen) and
            // swallow taps so clicking inside doesn't reach the dismissing scrim.
            modifier = Modifier
                .widthIn(max = 760.dp)
                .heightIn(max = 600.dp)
                .pointerInput(Unit) { detectTapGestures { } },
        ) {
            Row(Modifier.padding(16.dp), verticalAlignment = Alignment.Top) {
                // The table takes the remaining width and scrolls if it is wider/taller than the window,
                // leaving the fixed-width chart column always visible on the right.
                Column(
                    Modifier
                        .weight(1f)
                        .verticalScroll(rememberScrollState())
                        .horizontalScroll(rememberScrollState()),
                ) {
                    WeightTableHeader(
                        depth = 0,
                        leadingWidth = WEIGHT_WINDOW_TITLE_WIDTH,
                        weightColumns = list.weightColumns,
                        draggedColumn = draggedColumn,
                        dropIndex = columnDropIndex,
                        onDraggedColumnChange = { draggedColumn = it },
                        onDropIndexChange = { columnDropIndex = it },
                        onSetColumnWeight = { c, w -> onIntent(SchedulerIntent.SetPriorityColumnWeight(listId, c, w)) },
                        onAddColumn = { i -> onIntent(SchedulerIntent.AddPriorityColumn(listId, i)) },
                        onResetColumn = { c -> onIntent(SchedulerIntent.ResetPriorityColumn(listId, c)) },
                        onDeleteColumn = { c -> onIntent(SchedulerIntent.DeletePriorityColumn(listId, c)) },
                        onMoveColumn = { f, t -> onIntent(SchedulerIntent.MovePriorityColumn(listId, f, t)) },
                    )
                    populated.forEach { cellId ->
                        val cell = state.cells[cellId] ?: return@forEach
                        val title = cell.taskId?.let { state.tasks[it]?.title }.orEmpty()
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.width(WEIGHT_WINDOW_TITLE_WIDTH).padding(horizontal = 4.dp)) {
                                Text(
                                    text = title.ifEmpty { "(untitled)" },
                                    style = MaterialTheme.typography.bodyMedium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            for (column in list.weightColumns.indices) {
                                if (draggedColumn != null && columnDropIndex == column) ColumnDropLine()
                                Box(
                                    modifier = Modifier.background(
                                        if (draggedColumn == column) SheetColors.moveDragFill else Color.Transparent,
                                    ),
                                ) {
                                    WeightInputCell(
                                        value = cell.priorityWeights.getOrElse(column) { 1.0 },
                                        onSet = { value -> onIntent(SchedulerIntent.SetPriorityWeight(cellId, column, value)) },
                                    )
                                }
                            }
                            if (draggedColumn != null && columnDropIndex == list.weightColumns.size) ColumnDropLine()
                        }
                    }
                }
                Spacer(Modifier.width(16.dp))
                PriorityChart(
                    titles = populated.map { id -> state.cells[id]?.taskId?.let { state.tasks[it]?.title }.orEmpty() },
                    fractions = populated.map { id -> state.cells[id]?.taskId?.let { priorities[it] } ?: 0.0 },
                    modifier = Modifier.width(220.dp).verticalScroll(rememberScrollState()),
                )
            }
        }
    }
}

/** Distinct slice color for the [index]-th task in a priority pie chart, spread around the hue wheel. */
private fun priorityChartColor(index: Int, count: Int): Color {
    val hue = if (count <= 0) 0f else (index.toFloat() / count) * 360f
    return Color.hsv(hue, 0.55f, 0.85f)
}

/**
 * PRD §5: a circular (pie) chart of the absolute priority percentages [fractions] (0..1) of the tasks
 * [titles] in a sub-list. Each slice's sweep is proportional to its fraction relative to the sub-list
 * total, followed by a colour-keyed legend giving each task's title and percentage.
 */
@Composable
private fun PriorityChart(
    titles: List<String>,
    fractions: List<Double>,
    modifier: Modifier = Modifier,
) {
    val total = fractions.sum().coerceAtLeast(1e-9)
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = "Priorities",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (titles.isEmpty()) {
            Text(
                text = "(no tasks)",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            return@Column
        }
        Canvas(
            modifier = Modifier
                .align(Alignment.CenterHorizontally)
                .size(140.dp),
        ) {
            val diameter = size.minDimension
            val topLeft = Offset((size.width - diameter) / 2f, (size.height - diameter) / 2f)
            val arcSize = Size(diameter, diameter)
            var startAngle = -90f
            fractions.forEachIndexed { i, fraction ->
                val sweep = (fraction / total).toFloat() * 360f
                drawArc(
                    color = priorityChartColor(i, fractions.size),
                    startAngle = startAngle,
                    sweepAngle = sweep,
                    useCenter = true,
                    topLeft = topLeft,
                    size = arcSize,
                )
                startAngle += sweep
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            titles.forEachIndexed { i, title ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier
                            .size(10.dp)
                            .background(priorityChartColor(i, titles.size), RoundedCornerShape(2.dp)),
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = title.ifEmpty { "(untitled)" },
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = formatPriorityPercent(fractions[i]),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun TaskRow(
    depth: Int,
    cellId: CellId,
    renderVia: CellId?,
    displayTitle: String,
    isMainSelection: Boolean,
    isInSelectionRange: Boolean,
    selectable: Boolean,
    isEditing: Boolean,
    hasChildren: Boolean,
    expanded: Boolean,
    moveDropBefore: Boolean,
    moveDropAfter: Boolean,
    canMoveFromCell: Boolean,
    isBeingMoved: Boolean,
    priorityLabel: String?,
    priorityColumnWidth: Dp,
    textOverflow: Boolean,
    minMinutes: Int,
    minTimeEditing: Boolean,
    /** PRD §13: non-null only for a leaf cell — opens its "define schedule unit" window via right-click. */
    onDefineScheduleUnit: (() -> Unit)?,
    /** Non-null for any populated cell — opens its "see text" document window via right-click. */
    onSeeText: (() -> Unit)?,
    /** PRD §5: clicking the percentage opens the sub-list's priority-weight window. */
    onTogglePriorityWeights: () -> Unit,
    onSetMinTime: (Int) -> Unit,
    onActivateMinTime: () -> Unit,
    onClick: (CellId, ctrl: Boolean, shift: Boolean, forceClearMulti: Boolean) -> Unit,
    onDragSelect: (anchor: CellId, hover: CellId) -> Unit,
    moveDragActive: Boolean,
    resolveRowAt: (Float) -> Pair<VisibleOccurrence, Boolean>?,
    onRowBounds: (VisibleOccurrence, Float, Float) -> Unit,
    onMoveDragStart: () -> Unit,
    onMoveDropHover: (CellId, Boolean, CellId?) -> Unit,
    onMoveDragEnd: () -> Unit,
    onDoubleClick: () -> Unit,
    onTextChange: (String) -> Unit,
    onExitEdit: (EditExitNavigation) -> Unit,
    onToggleExpand: () -> Unit,
    editMenus: (@Composable () -> Unit)?,
) {
    val editFocusRequester = remember { FocusRequester() }
    // Whether this cell's right-click contextual menu ("define schedule unit" / "see text") is showing.
    var contextMenuOpen by remember(cellId) { mutableStateOf(false) }
    val hasContextMenu = onDefineScheduleUnit != null || onSeeText != null
    // Layout coordinates of this row, used to convert in-row pointer positions to window space so
    // the originating row can map an ongoing drag to the cell currently under the cursor.
    val rowCoordinates = remember { mutableStateOf<LayoutCoordinates?>(null) }
    val currentResolveRowAt by rememberUpdatedState(resolveRowAt)
    LaunchedEffect(isEditing) {
        if (isEditing) editFocusRequester.requestFocus()
    }

    val cellBackground =
        when {
            // PRD §3: a cell being drag-moved gets a grey background (not mirrored elsewhere).
            isBeingMoved -> SheetColors.moveDragFill
            isInSelectionRange || isEditing -> SheetColors.selectionFill
            !selectable -> SheetColors.nonSelectableFill
            else -> SheetColors.cellBackground
        }
    val cellBorder =
        if (isMainSelection || isEditing) {
            Modifier.border(2.dp, SheetColors.activeBorder)
        } else {
            Modifier.border(1.dp, SheetColors.grid)
        }
    val textStyle = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.onSurface)

    val currentCanMoveFromCell by rememberUpdatedState(canMoveFromCell)

    @OptIn(ExperimentalComposeUiApi::class)
    fun selectionPointerModifier(): Modifier {
        if (!selectable || isEditing) return Modifier
        return Modifier
            .onGloballyPositioned { coords ->
                rowCoordinates.value = coords
                val top = coords.positionInWindow().y
                onRowBounds(VisibleOccurrence(cellId, renderVia), top, top + coords.size.height)
            }
            // Keyed only by cellId so selection-driven flags (which change during the gesture's own
            // clicks) never restart and cancel an in-progress drag; freshness comes from the
            // rememberUpdatedState snapshots above.
            .pointerInput(cellId) {
                val doubleTapTimeout = viewConfiguration.doubleTapTimeoutMillis
                val touchSlop = viewConfiguration.touchSlop

                fun windowYOf(change: PointerInputChange): Float? =
                    rowCoordinates.value
                        ?.takeIf { it.isAttached }
                        ?.localToWindow(change.position)?.y

                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    down.consume()
                    val modifiers = currentEvent.keyboardModifiers
                    val ctrl = modifiers.pointerCtrlPressed
                    val shift = modifiers.pointerShiftPressed

                    onClick(cellId, ctrl, shift, false)

                    // Ctrl / Shift clicks never begin a drag — just wait for release.
                    if (ctrl || shift) {
                        waitForUpOrCancellation()
                        return@awaitEachGesture
                    }

                    // First press: dragging past the touch slop selects a range from this cell
                    // (the anchor) to the cell under the cursor (PRD §3 Single Click & Drag).
                    var dragged = false
                    var traveled = 0f
                    while (true) {
                        val event = awaitPointerEvent()
                        if (!event.changes.any { it.pressed }) break
                        val change =
                            event.changes.firstOrNull { it.id == down.id } ?: event.changes.first()
                        traveled += change.positionChange().getDistance()
                        if (traveled > touchSlop) {
                            dragged = true
                            change.consume()
                            windowYOf(change)?.let { currentResolveRowAt(it) }?.let { (occ, _) ->
                                onDragSelect(cellId, occ.cellId)
                            }
                        }
                    }
                    if (dragged) return@awaitEachGesture

                    // No drag: a second press within the timeout makes it a double-click. The first
                    // press kept any existing multi-selection intact (so a double-click & drag can
                    // still move it); now that this resolves as a plain single click, reset the
                    // Selected Cells List down to the clicked cell (PRD §3 Single Click).
                    val secondDown =
                        withTimeoutOrNull(doubleTapTimeout) {
                            awaitFirstDown(requireUnconsumed = false)
                        }
                    if (secondDown == null) {
                        onClick(cellId, false, false, true)
                        return@awaitEachGesture
                    }
                    secondDown.consume()

                    // Double-click on a non-movable selection (e.g. a disjoint Ctrl multi-select)
                    // can't be dragged anywhere, so it just enters Edit Mode (PRD §4).
                    if (!currentCanMoveFromCell) {
                        onClick(cellId, false, false, true)
                        waitForUpOrCancellation()
                        onDoubleClick()
                        return@awaitEachGesture
                    }

                    // Double-click & drag on a movable selection (one cell or a contiguous block):
                    // dragging past the slop blurs the cells and tracks the blue drop line, and
                    // release commits the move. Without a drag it falls through to Edit Mode (PRD §3).
                    var moveStarted = false
                    var moveTraveled = 0f
                    while (true) {
                        val event = awaitPointerEvent()
                        if (!event.changes.any { it.pressed }) {
                            if (moveStarted) {
                                onMoveDragEnd()
                            } else {
                                onClick(cellId, false, false, true)
                                onDoubleClick()
                            }
                            break
                        }
                        val change =
                            event.changes.firstOrNull { it.id == secondDown.id }
                                ?: event.changes.first()
                        moveTraveled += change.positionChange().getDistance()
                        if (!moveStarted && moveTraveled > touchSlop) {
                            moveStarted = true
                            onMoveDragStart()
                        }
                        if (moveStarted) {
                            change.consume()
                            windowYOf(change)?.let { currentResolveRowAt(it) }
                                ?.let { (occ, before) ->
                                    onMoveDropHover(occ.cellId, before, occ.renderVia)
                                }
                        }
                    }
                }
            }
    }

    Column(modifier = Modifier.fillMaxWidth()) {
        if (moveDropBefore) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = (depth * INDENT_STEP_DP).dp)
                    .height(2.dp)
                    .background(SheetColors.activeBorder),
            )
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                // PRD §2: guide-lines on the left illustrate the parent-child hierarchy. One
                // vertical line is drawn in the indentation gutter under each expanded ancestor's
                // arrow; they only appear beneath expanded cells (collapsed cells hide their rows).
                .drawBehind {
                    val step = INDENT_STEP_DP.dp.toPx()
                    val offset = GUIDE_LINE_OFFSET_DP.dp.toPx()
                    val stroke = 1.dp.toPx()
                    for (level in 0 until depth) {
                        val x = level * step + offset
                        drawLine(
                            color = SheetColors.guideLine,
                            start = Offset(x, 0f),
                            end = Offset(x, size.height),
                            strokeWidth = stroke,
                        )
                    }
                }
                .padding(start = (depth * INDENT_STEP_DP).dp)
                .defaultMinSize(minHeight = 28.dp)
                .background(cellBackground)
                .then(cellBorder)
                .then(selectionPointerModifier())
                .then(contextMenuModifier(hasContextMenu) { contextMenuOpen = true })
                .padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Right-click contextual menu: "define schedule unit" (leaf cells, PRD §13) and "see text"
            // (any populated cell).
            if (hasContextMenu) {
                DropdownMenu(
                    expanded = contextMenuOpen,
                    onDismissRequest = { contextMenuOpen = false },
                ) {
                    if (onDefineScheduleUnit != null) {
                        DropdownMenuItem(
                            text = { Text("define schedule unit") },
                            onClick = {
                                contextMenuOpen = false
                                onDefineScheduleUnit()
                            },
                        )
                    }
                    if (onSeeText != null) {
                        DropdownMenuItem(
                            text = { Text("see text") },
                            onClick = {
                                contextMenuOpen = false
                                onSeeText()
                            },
                        )
                    }
                }
            }
            Box(
                modifier = Modifier
                    .size(20.dp)
                    .then(
                        if (hasChildren) {
                            Modifier.clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                                onClick = onToggleExpand,
                            )
                        } else {
                            Modifier
                        }
                    ),
                contentAlignment = Alignment.Center,
            ) {
                if (hasChildren) {
                    Text(
                        text = if (expanded) "▾" else "▸",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (isEditing) {
                var textFieldValue by remember(cellId) { mutableStateOf(TextFieldValue()) }
                SideEffect {
                    if (!isEditing) {
                        textFieldValue = TextFieldValue()
                        return@SideEffect
                    }
                    if (textFieldValue.text != displayTitle) {
                        textFieldValue =
                            TextFieldValue(
                                text = displayTitle,
                                selection = TextRange(displayTitle.length),
                            )
                    }
                }
                // PRD §2: the same priority text column and red overflow arrow apply in Edit Mode.
                Box(
                    modifier = Modifier
                        .width(priorityColumnWidth)
                        .defaultMinSize(minHeight = 20.dp),
                    contentAlignment = Alignment.CenterStart,
                ) {
                BasicTextField(
                    modifier = Modifier
                        .fillMaxWidth()
                        .defaultMinSize(minHeight = 20.dp)
                        .focusRequester(editFocusRequester)
                        .onPreviewKeyEvent { event ->
                            if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                            if (event.key == Key.Delete && !event.isCtrlPressed && !event.isMetaPressed) {
                                return@onPreviewKeyEvent false
                            }
                            when {
                                event.key == Key.Enter &&
                                    (event.isCtrlPressed || event.isMetaPressed) -> {
                                    val selection = textFieldValue.selection
                                    val insertAt = selection.min
                                    val newText =
                                        buildString {
                                            append(textFieldValue.text.substring(0, insertAt))
                                            append('\n')
                                            append(textFieldValue.text.substring(selection.max))
                                        }
                                    textFieldValue =
                                        TextFieldValue(
                                            text = newText,
                                            selection = TextRange(insertAt + 1),
                                        )
                                    onTextChange(newText)
                                    true
                                }
                                event.key == Key.DirectionUp -> {
                                    val lineStart = textFieldValue.text.lastIndexOf('\n', textFieldValue.selection.min - 1)
                                    if (lineStart < 0) {
                                        textFieldValue =
                                            textFieldValue.copy(
                                                selection = TextRange(0),
                                            )
                                        true
                                    } else {
                                        false
                                    }
                                }
                                event.key == Key.DirectionDown -> {
                                    val text = textFieldValue.text
                                    val cursor = textFieldValue.selection.max
                                    val nextBreak = text.indexOf('\n', cursor)
                                    if (nextBreak < 0) {
                                        textFieldValue =
                                            textFieldValue.copy(
                                                selection = TextRange(text.length),
                                            )
                                        true
                                    } else {
                                        false
                                    }
                                }
                                event.key == Key.Enter && event.isShiftPressed -> {
                                    onExitEdit(EditExitNavigation.Up)
                                    true
                                }
                                event.key == Key.Enter -> {
                                    onExitEdit(EditExitNavigation.Down)
                                    true
                                }
                                event.key == Key.Tab && event.isShiftPressed -> {
                                    onExitEdit(EditExitNavigation.Up)
                                    true
                                }
                                event.key == Key.Tab -> {
                                    onExitEdit(EditExitNavigation.TabToChild)
                                    true
                                }
                                else -> false
                            }
                        },
                    value = textFieldValue,
                    onValueChange = { newValue ->
                        textFieldValue = newValue
                        onTextChange(newValue.text)
                    },
                    textStyle = textStyle,
                    cursorBrush = SolidColor(SheetColors.activeBorder),
                    decorationBox = { innerTextField ->
                        Box(
                            modifier = Modifier.fillMaxWidth(),
                            contentAlignment = Alignment.CenterStart,
                        ) {
                            innerTextField()
                        }
                    },
                )
                    if (textOverflow) {
                        Text(
                            modifier = Modifier
                                .align(Alignment.CenterEnd)
                                .background(cellBackground),
                            text = "▸",
                            style = MaterialTheme.typography.bodySmall,
                            color = SheetColors.overflowArrow,
                        )
                    }
                }
                Spacer(Modifier.weight(1f))
            } else {
                // PRD §2 Priority Display: the text occupies a column whose width is shared by the
                // whole sublist (so percentages line up); the percentage sits just after it. When
                // the text exceeds the column it is clipped and a little red arrow marks the
                // hidden overflow on the right.
                Box(
                    modifier = Modifier
                        .width(priorityColumnWidth)
                        .defaultMinSize(minHeight = 20.dp),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    Text(
                        modifier = Modifier.fillMaxWidth(),
                        text = displayTitle.ifEmpty { " " },
                        style = textStyle,
                        softWrap = false,
                        overflow = TextOverflow.Clip,
                    )
                    if (textOverflow) {
                        Text(
                            modifier = Modifier
                                .align(Alignment.CenterEnd)
                                .background(cellBackground),
                            text = "▸",
                            style = MaterialTheme.typography.bodySmall,
                            color = SheetColors.overflowArrow,
                        )
                    }
                }
                // PRD §5: the percentage occupies a fixed-width column; clicking it opens the sub-list's
                // priority-weight window (the editable table plus a chart of the sub-list's priorities).
                Box(
                    modifier = Modifier
                        .width(PERCENT_COLUMN_WIDTH)
                        .then(
                            if (priorityLabel != null) {
                                Modifier.clickable(
                                    interactionSource = remember { MutableInteractionSource() },
                                    indication = null,
                                    onClick = onTogglePriorityWeights,
                                )
                            } else {
                                Modifier
                            }
                        )
                        .padding(start = 8.dp),
                ) {
                    if (priorityLabel != null) {
                        Text(
                            text = priorityLabel,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                // PRD §10: the task's minimum-time field sits just to the right of the percentage. It
                // shows as a plain label until clicked, then expands into an input field with
                // increment/decrement arrows.
                if (priorityLabel != null) {
                    if (minTimeEditing) {
                        MinTimeInputCell(minutes = minMinutes, onSet = onSetMinTime)
                    } else {
                        MinTimeDisplayCell(minutes = minMinutes, onClick = onActivateMinTime)
                    }
                }
                Spacer(Modifier.weight(1f))
            }
        }
        if (moveDropAfter) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = (depth * INDENT_STEP_DP).dp)
                    .height(2.dp)
                    .background(SheetColors.activeBorder),
            )
        }
        if (isEditing) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = (depth * INDENT_STEP_DP).dp)
                    .background(SheetColors.cellBackground)
                    .border(1.dp, SheetColors.grid)
                    .padding(8.dp),
            ) {
                editMenus?.invoke()
            }
        }
    }
}

/**
 * A right-click (secondary button) on a cell opens its contextual menu ("define schedule unit" / "see
 * text"). Returns a no-op modifier when [enabled] is false (cells with no menu items — empty / root-main),
 * so only eligible cells react. [onOpen] flips the row's local menu-visible flag.
 */
@OptIn(ExperimentalComposeUiApi::class)
private fun contextMenuModifier(
    enabled: Boolean,
    onOpen: () -> Unit,
): Modifier {
    if (!enabled) return Modifier
    return Modifier.pointerInput(Unit) {
        awaitPointerEventScope {
            while (true) {
                var press = awaitPointerEvent()
                while (press.type != PointerEventType.Press) {
                    press = awaitPointerEvent()
                }
                if (press.buttons.isSecondaryPressed) {
                    // Consume so the freshly opened menu isn't dismissed by this same click.
                    press.changes.forEach { it.consume() }
                    onOpen()
                }
            }
        }
    }
}

/**
 * PRD §13 Edition Window: the floating "define schedule unit" editor. Lists the entries vertically —
 * each a title field plus a spanning-time field with increment/decrement buttons, a bin (remove) and a
 * plus (insert above); a single trailing plus appends. The Save button is disabled while the summed
 * spanning times exceed [minimumMinutes] ([SchedulerDomain.canSaveScheduleUnit]).
 */
@Composable
private fun ScheduleUnitEditWindow(
    initialEntries: List<ScheduleUnitEntry>,
    minimumMinutes: Int,
    onSave: (List<ScheduleUnitEntry>) -> Unit,
    onDismiss: () -> Unit,
) {
    var entries by remember { mutableStateOf(initialEntries) }
    val sum = SchedulerDomain.scheduleUnitSumMinutes(entries)
    val canSave = SchedulerDomain.canSaveScheduleUnit(entries, minimumMinutes)

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.25f))
            // Tap (not clickable) to dismiss: a focused clickable also fires on Space/Enter, which would
            // close the window while typing in a field.
            .pointerInput(Unit) { detectTapGestures { onDismiss() } },
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.surface,
            shadowElevation = 12.dp,
            border = BorderStroke(1.dp, SheetColors.grid),
            // Swallow taps so clicking inside the window doesn't reach the dismissing scrim.
            modifier = Modifier.width(360.dp).pointerInput(Unit) { detectTapGestures { } },
        ) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Define schedule unit", style = MaterialTheme.typography.titleSmall)

                entries.forEachIndexed { index, entry ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        OutlinedTextField(
                            value = entry.title,
                            onValueChange = { newTitle ->
                                entries = entries.toMutableList().also {
                                    it[index] = entry.copy(title = newTitle)
                                }
                            },
                            singleLine = true,
                            modifier = Modifier.weight(1f),
                        )
                        OutlinedTextField(
                            value = entry.spanMinutes.toString(),
                            onValueChange = { raw ->
                                val parsed = raw.filter { it.isDigit() }.toIntOrNull() ?: 0
                                entries = entries.toMutableList().also {
                                    it[index] = entry.copy(spanMinutes = parsed)
                                }
                            },
                            singleLine = true,
                            modifier = Modifier.width(72.dp),
                        )
                        Column {
                            WeightStepButton("+") {
                                entries = entries.toMutableList().also {
                                    it[index] = entry.copy(spanMinutes = entry.spanMinutes + 1)
                                }
                            }
                            WeightStepButton("−") {
                                entries = entries.toMutableList().also {
                                    it[index] = entry.copy(spanMinutes = (entry.spanMinutes - 1).coerceAtLeast(0))
                                }
                            }
                        }
                        // Bin: remove this pair.
                        TextButton(onClick = {
                            entries = entries.toMutableList().also { it.removeAt(index) }
                        }) { Text("🗑") }
                        // Plus: insert a new pair above this one.
                        TextButton(onClick = {
                            entries = entries.toMutableList().also { it.add(index, ScheduleUnitEntry("", 0)) }
                        }) { Text("+") }
                    }
                }

                // Trailing single plus: append a new pair at the end of the list.
                TextButton(onClick = { entries = entries + ScheduleUnitEntry("", 0) }) {
                    Text("+ add step")
                }

                Text(
                    text = "Total: $sum min (max $minimumMinutes)",
                    style = MaterialTheme.typography.labelSmall,
                    color =
                        if (canSave) MaterialTheme.colorScheme.onSurfaceVariant
                        else MaterialTheme.colorScheme.error,
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                ) {
                    TextButton(onClick = onDismiss) { Text("Cancel") }
                    Spacer(Modifier.width(8.dp))
                    // PRD §13: Save is not clickable while the spans exceed the task's minimum time.
                    TextButton(enabled = canSave, onClick = { onSave(entries) }) { Text("Save") }
                }
            }
        }
    }
}

/**
 * The floating "see text" window: a free-form, multi-line text document attached to a task. Opened from a
 * populated cell's right-click menu. Edits are kept local until Save; tapping the scrim cancels.
 *
 * The scrim dismisses on a pointer tap (not [Modifier.clickable]) on purpose: a focused `clickable` also
 * fires on Space/Enter, so typing a space in the editor would otherwise close the window. The text field
 * auto-focuses so keystrokes land in it immediately.
 */
@Composable
private fun TaskTextWindow(
    taskTitle: String,
    initialText: String,
    onSave: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var text by remember { mutableStateOf(initialText) }
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { focusRequester.requestFocus() }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.25f))
            .pointerInput(Unit) { detectTapGestures { onDismiss() } },
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.surface,
            shadowElevation = 12.dp,
            border = BorderStroke(1.dp, SheetColors.grid),
            // Swallow taps so clicking inside the window doesn't reach the dismissing scrim.
            modifier = Modifier.width(420.dp).pointerInput(Unit) { detectTapGestures { } },
        ) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = taskTitle.ifBlank { "Text" },
                    style = MaterialTheme.typography.titleSmall,
                )
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 220.dp).focusRequester(focusRequester),
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                ) {
                    TextButton(onClick = onDismiss) { Text("Cancel") }
                    Spacer(Modifier.width(8.dp))
                    TextButton(onClick = { onSave(text) }) { Text("Save") }
                }
            }
        }
    }
}
