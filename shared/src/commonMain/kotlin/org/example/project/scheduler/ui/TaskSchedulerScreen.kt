package org.example.project.scheduler.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
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
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.utf16CodePoint
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.isCtrlPressed as pointerCtrlPressed
import androidx.compose.ui.input.pointer.isShiftPressed as pointerShiftPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import org.example.project.scheduler.domain.SchedulerDomain
import org.example.project.scheduler.model.CellId
import org.example.project.scheduler.model.CellListId
import org.example.project.scheduler.persistence.SchedulerStore
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
}

/** Indentation step (dp) per nesting level; also the spacing between hierarchy guide-lines. */
private const val INDENT_STEP_DP = 16

/** Horizontal offset (dp) of a level's guide-line, aligned under that ancestor's expand arrow. */
private const val GUIDE_LINE_OFFSET_DP = 14

/** Blur radius (dp) applied to cells while they are being drag-moved (PRD §3 Double Click & Drag). */
private val MOVE_DRAG_BLUR_DP = 2.dp

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
    val focusRequester = remember { FocusRequester() }
    var moveDragActive by remember { mutableStateOf(false) }
    var moveDropTarget by remember { mutableStateOf<MoveDropTarget?>(null) }

    // Vertical window bounds of each visible row, reported via onGloballyPositioned. A press-drag
    // only delivers move events to the row where the pointer went down (Compose retains the hit
    // path while a button is held), so the originating row resolves the cell under the cursor from
    // these shared bounds rather than relying on per-cell hover events that never fire mid-drag.
    val rowBounds = remember { mutableStateMapOf<CellId, ClosedFloatingPointRange<Float>>() }
    val resolveRowAt: (Float) -> Pair<CellId, Boolean>? = resolve@{ windowY ->
        var last: Pair<CellId, Boolean>? = null
        for (cellId in visibleOrder) {
            val bounds = rowBounds[cellId] ?: continue
            if (windowY < bounds.start) return@resolve last ?: (cellId to true)
            val mid = (bounds.start + bounds.endInclusive) / 2f
            last = cellId to (windowY < mid)
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

    Column(
        modifier = modifier
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
                if (mod && event.key == Key.C) {
                    val titles = SchedulerDomain.copyTitlesFromSelection(state, state.selection)
                    vm.dispatch(SchedulerIntent.CopySelection)
                    writeSystemClipboardText(SchedulerDomain.formatClipboardText(titles))
                    return@onPreviewKeyEvent true
                }
                if (mod && event.key == Key.V) {
                    val text = readSystemClipboardText() ?: return@onPreviewKeyEvent false
                    vm.dispatch(
                        SchedulerIntent.PasteTitles(SchedulerDomain.parseClipboardText(text)),
                    )
                    return@onPreviewKeyEvent true
                }
                if (state.editSession != null) {
                    if (event.key == Key.Delete && !event.isCtrlPressed) {
                        vm.dispatch(SchedulerIntent.CancelEdit)
                        return@onPreviewKeyEvent true
                    }
                    return@onPreviewKeyEvent false
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
                    Key.Delete -> {
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
                val typed = event.printableChar() ?: return@onPreviewKeyEvent false
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
                moveDragActive = moveDragActive,
                moveDropTarget = moveDropTarget,
                resolveRowAt = resolveRowAt,
                onRowBounds = { cellId, top, bottom -> rowBounds[cellId] = top..bottom },
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
                onIntent = vm::dispatch,
            )
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
    moveDragActive: Boolean,
    moveDropTarget: MoveDropTarget?,
    resolveRowAt: (Float) -> Pair<CellId, Boolean>?,
    onRowBounds: (CellId, Float, Float) -> Unit,
    onMoveDragStart: () -> Unit,
    onMoveDropHover: (CellId, Boolean, CellId?) -> Unit,
    onMoveDragEnd: () -> Unit,
    onIntent: (SchedulerIntent) -> Unit,
) {
    val list = state.lists[listId] ?: return
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
            onMoveDropHover = { target, insertBefore ->
                onMoveDropHover(target, insertBefore, renderVia)
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
            val excludeFromMenu = session.selectedAssignTaskId ?: session.newTaskDraftId
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

@Composable
private fun TaskRow(
    depth: Int,
    cellId: CellId,
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
    onClick: (CellId, ctrl: Boolean, shift: Boolean, forceClearMulti: Boolean) -> Unit,
    onDragSelect: (anchor: CellId, hover: CellId) -> Unit,
    moveDragActive: Boolean,
    resolveRowAt: (Float) -> Pair<CellId, Boolean>?,
    onRowBounds: (CellId, Float, Float) -> Unit,
    onMoveDragStart: () -> Unit,
    onMoveDropHover: (CellId, Boolean) -> Unit,
    onMoveDragEnd: () -> Unit,
    onDoubleClick: () -> Unit,
    onTextChange: (String) -> Unit,
    onExitEdit: (EditExitNavigation) -> Unit,
    onToggleExpand: () -> Unit,
    editMenus: (@Composable () -> Unit)?,
) {
    val editFocusRequester = remember { FocusRequester() }
    // Layout coordinates of this row, used to convert in-row pointer positions to window space so
    // the originating row can map an ongoing drag to the cell currently under the cursor.
    val rowCoordinates = remember { mutableStateOf<LayoutCoordinates?>(null) }
    val currentResolveRowAt by rememberUpdatedState(resolveRowAt)
    LaunchedEffect(isEditing) {
        if (isEditing) editFocusRequester.requestFocus()
    }

    val cellBackground =
        when {
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
                onRowBounds(cellId, top, top + coords.size.height)
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
                            windowYOf(change)?.let { currentResolveRowAt(it) }?.let { (target, _) ->
                                onDragSelect(cellId, target)
                            }
                        }
                    }
                    if (dragged) return@awaitEachGesture

                    // No drag: a second press within the timeout makes it a double-click.
                    val secondDown =
                        withTimeoutOrNull(doubleTapTimeout) {
                            awaitFirstDown(requireUnconsumed = false)
                        } ?: return@awaitEachGesture
                    secondDown.consume()

                    // Plain double-click on a single / non-movable cell enters Edit Mode (PRD §4).
                    if (!currentCanMoveFromCell) {
                        onClick(cellId, false, false, true)
                        waitForUpOrCancellation()
                        onDoubleClick()
                        return@awaitEachGesture
                    }

                    // Double-click & drag on a movable selection: dragging past the slop blurs the
                    // cells and tracks the blue drop line; release commits the move (PRD §3).
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
                                ?.let { (target, before) -> onMoveDropHover(target, before) }
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
                // PRD §3: the dragged cells are blurred while a double-click & drag move is active.
                .then(if (isBeingMoved) Modifier.blur(MOVE_DRAG_BLUR_DP) else Modifier)
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
                .padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
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
                BasicTextField(
                    modifier = Modifier
                        .weight(1f)
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
                                event.key == Key.Escape -> {
                                    onExitEdit(EditExitNavigation.Stay)
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
            } else {
                Text(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .defaultMinSize(minHeight = 20.dp),
                    text = displayTitle.ifEmpty { " " },
                    style = textStyle,
                )
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
