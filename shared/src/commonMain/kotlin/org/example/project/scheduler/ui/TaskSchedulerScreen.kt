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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
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
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isCtrlPressed as pointerCtrlPressed
import androidx.compose.ui.input.pointer.isPrimaryPressed
import androidx.compose.ui.input.pointer.isShiftPressed as pointerShiftPressed
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import org.example.project.scheduler.domain.SchedulerDomain
import org.example.project.scheduler.model.CellId
import org.example.project.scheduler.model.CellListId
import org.example.project.scheduler.persistence.SchedulerStore
import org.example.project.scheduler.state.CellEditMode
import org.example.project.scheduler.state.EditExitNavigation
import org.example.project.scheduler.state.SchedulerIntent
import org.example.project.scheduler.state.SchedulerState
import kotlinx.coroutines.withTimeoutOrNull

private object SheetColors {
    val grid = Color(0xFFDADCE0)
    val cellBackground = Color.White
    val selectionFill = Color(0xFFE8F0FE)
    val activeBorder = Color(0xFF1A73E8)
    val nonSelectableFill = Color(0xFFF8F9FA)
}

@Composable
fun TaskSchedulerScreen(
    modifier: Modifier = Modifier,
    store: SchedulerStore? = null,
    vm: TaskSchedulerViewModel = viewModel { TaskSchedulerViewModel(store = store) },
) {
    val state by vm.state.collectAsState()
    val visibleOrder = SchedulerDomain.visibleCellOrder(state)
    val focusRequester = remember { FocusRequester() }
    var dragAnchor by remember { mutableStateOf<CellId?>(null) }
    var moveDragActive by remember { mutableStateOf(false) }
    var moveDropTarget by remember { mutableStateOf<Pair<CellId, Boolean>?>(null) }

    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface)
            .focusRequester(focusRequester)
            .focusable()
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                if (state.editSession != null) {
                    if (event.key == Key.Delete && !event.isCtrlPressed) {
                        vm.dispatch(SchedulerIntent.CancelEdit)
                        return@onPreviewKeyEvent true
                    }
                    return@onPreviewKeyEvent false
                }
                if (event.key == Key.Enter || event.key == Key.Delete) {
                    if (!event.isCtrlPressed && !event.isMetaPressed) {
                        vm.dispatch(SchedulerIntent.EmptySelectedCells)
                        return@onPreviewKeyEvent true
                    }
                }
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
                depth = 0,
                visibleOrder = visibleOrder,
                dragAnchor = dragAnchor,
                moveDragActive = moveDragActive,
                moveDropTarget = moveDropTarget,
                onDragAnchorChange = { dragAnchor = it },
                onMoveDragStart = {
                    moveDragActive = true
                    dragAnchor = null
                },
                onMoveDropHover = { target, insertBefore ->
                    moveDropTarget = target to insertBefore
                },
                onMoveDragEnd = {
                    val target = moveDropTarget
                    if (moveDragActive && target != null) {
                        vm.dispatch(
                            SchedulerIntent.MoveSelectedCells(
                                targetCellId = target.first,
                                insertBefore = target.second,
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

private fun androidx.compose.ui.input.key.KeyEvent.printableChar(): String? {
    if (isCtrlPressed || isMetaPressed) return null
    if (key == Key.Enter || key == Key.Tab || key == Key.Escape || key == Key.Backspace) return null
    val codePoint = utf16CodePoint
    if (codePoint <= 0x1F) return null
    return Char(codePoint).toString()
}

@Composable
private fun CellListSection(
    state: SchedulerState,
    listId: CellListId,
    depth: Int,
    visibleOrder: List<CellId>,
    dragAnchor: CellId?,
    moveDragActive: Boolean,
    moveDropTarget: Pair<CellId, Boolean>?,
    onDragAnchorChange: (CellId?) -> Unit,
    onMoveDragStart: () -> Unit,
    onMoveDropHover: (CellId, Boolean) -> Unit,
    onMoveDragEnd: () -> Unit,
    onIntent: (SchedulerIntent) -> Unit,
) {
    val list = state.lists[listId] ?: return
    list.cellIds.forEach { cellId ->
        val cell = state.cells[cellId] ?: return@forEach
        val title = cell.taskId?.let { state.tasks[it]?.title }.orEmpty()
        val selectable = SchedulerDomain.isSelectableCell(state, cellId)
        val isMainSelection = selectable && state.selection.main == cellId
        val isInSelectionRange =
            selectable &&
                (isMainSelection || state.selection.selected.contains(cellId))
        val isEditing = state.editSession?.cellId == cellId
        val editDraft = if (isEditing) state.editSession!!.draftText else title
        val hasChildren = cell.taskId?.let { state.tasks[it]?.childListId != null } == true
        val expanded = cellId in state.expanded

        val isInActiveSelection = SchedulerDomain.isInActiveSelection(state.selection, cellId)
        val canMoveFromCell =
            isInActiveSelection &&
                SchedulerDomain.isSequentialSelectionInSameList(state, state.selection)

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
            moveDropBefore = moveDropTarget?.first == cellId && moveDropTarget.second,
            moveDropAfter = moveDropTarget?.first == cellId && !moveDropTarget.second,
            canMoveFromCell = canMoveFromCell,
            onClick = { clicked, ctrl, shift, forceClearMulti ->
                if (!selectable) return@TaskRow
                onIntent(
                    SchedulerIntent.ClickCell(
                        cellId = clicked,
                        ctrl = ctrl,
                        shift = shift,
                        visibleOrder = visibleOrder,
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
                    ),
                )
            },
            dragAnchor = dragAnchor,
            moveDragActive = moveDragActive,
            onDragAnchorChange = onDragAnchorChange,
            onMoveDragStart = onMoveDragStart,
            onMoveDropHover = onMoveDropHover,
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
                depth = depth + 1,
                visibleOrder = visibleOrder,
                dragAnchor = dragAnchor,
                moveDragActive = moveDragActive,
                moveDropTarget = moveDropTarget,
                onDragAnchorChange = onDragAnchorChange,
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
    onClick: (CellId, ctrl: Boolean, shift: Boolean, forceClearMulti: Boolean) -> Unit,
    onDragSelect: (anchor: CellId, hover: CellId) -> Unit,
    dragAnchor: CellId?,
    moveDragActive: Boolean,
    onDragAnchorChange: (CellId?) -> Unit,
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
    val density = LocalDensity.current
    val rowHeightPx = with(density) { 28.dp.toPx() }
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

    @OptIn(ExperimentalComposeUiApi::class)
    fun selectionPointerModifier(): Modifier {
        if (!selectable || isEditing) return Modifier
        return Modifier
            .pointerInput(cellId, canMoveFromCell, moveDragActive) {
                val doubleTapTimeout = viewConfiguration.doubleTapTimeoutMillis
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    down.consume()
                    val modifiers = currentEvent.keyboardModifiers
                    val ctrl = modifiers.pointerCtrlPressed
                    val shift = modifiers.pointerShiftPressed

                    onClick(cellId, ctrl, shift, false)
                    if (!ctrl && !shift && !moveDragActive) {
                        onDragAnchorChange(cellId)
                    }

                    val up = waitForUpOrCancellation()
                    if (up != null && !ctrl && !shift) {
                        val secondDown =
                            withTimeoutOrNull(doubleTapTimeout) {
                                awaitFirstDown(requireUnconsumed = false)
                            }
                        if (secondDown != null) {
                            onDragAnchorChange(null)
                            if (canMoveFromCell) {
                                onMoveDragStart()
                                do {
                                    val event = awaitPointerEvent()
                                    if (!event.changes.any { it.pressed }) break
                                } while (true)
                                onMoveDragEnd()
                            } else {
                                onClick(cellId, false, false, true)
                                waitForUpOrCancellation()
                                onDoubleClick()
                            }
                        }
                    }
                    onDragAnchorChange(null)
                }
            }
            .onPointerEvent(PointerEventType.Move) { event ->
                if (moveDragActive && event.buttons.isPrimaryPressed) {
                    val y = event.changes.firstOrNull()?.position?.y ?: return@onPointerEvent
                    onMoveDropHover(cellId, y < rowHeightPx / 2f)
                    return@onPointerEvent
                }
                val anchor = dragAnchor ?: return@onPointerEvent
                if (event.buttons.isPrimaryPressed && anchor != cellId) {
                    onDragSelect(anchor, cellId)
                }
            }
    }

    Column(modifier = Modifier.fillMaxWidth()) {
        if (moveDropBefore) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = (depth * 16).dp)
                    .height(2.dp)
                    .background(SheetColors.activeBorder),
            )
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = (depth * 16).dp)
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
                            if (event.key == Key.Delete && !event.isCtrlPressed) {
                                return@onPreviewKeyEvent false
                            }
                            when {
                                event.key == Key.Enter && event.isCtrlPressed -> false
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
                    .padding(start = (depth * 16).dp)
                    .height(2.dp)
                    .background(SheetColors.activeBorder),
            )
        }
        if (isEditing) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = (depth * 16).dp)
                    .background(SheetColors.cellBackground)
                    .border(1.dp, SheetColors.grid)
                    .padding(8.dp),
            ) {
                editMenus?.invoke()
            }
        }
    }
}
