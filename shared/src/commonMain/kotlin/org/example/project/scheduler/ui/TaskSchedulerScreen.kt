package org.example.project.scheduler.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.utf16CodePoint
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import org.example.project.scheduler.domain.SchedulerDomain
import org.example.project.scheduler.model.CellId
import org.example.project.scheduler.model.CellListId
import org.example.project.scheduler.state.CellEditMode
import org.example.project.scheduler.state.SchedulerIntent
import org.example.project.scheduler.state.SchedulerState

@Composable
fun TaskSchedulerScreen(
    modifier: Modifier = Modifier,
    vm: TaskSchedulerViewModel = viewModel { TaskSchedulerViewModel() },
) {
    val state by vm.state.collectAsState()
    val visibleOrder = SchedulerDomain.visibleCellOrder(state)
    val focusRequester = remember { FocusRequester() }

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
        )
        Spacer(Modifier.height(12.dp))

        Column(
            modifier = Modifier.verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            CellListSection(
                state = state,
                listId = state.rootListId,
                depth = 0,
                visibleOrder = visibleOrder,
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
    onIntent: (SchedulerIntent) -> Unit,
) {
    val list = state.lists[listId] ?: return
    list.cellIds.forEach { cellId ->
        val cell = state.cells[cellId] ?: return@forEach
        val title = cell.taskId?.let { state.tasks[it]?.title }.orEmpty()
        val selectable = SchedulerDomain.isSelectableCell(state, cellId)
        val isSelected =
            selectable &&
                (state.selection.main == cellId || state.selection.selected.contains(cellId))
        val isEditing = state.editSession?.cellId == cellId
        val editDraft = if (isEditing) state.editSession!!.draftText else title
        val hasChildren = cell.taskId?.let { state.tasks[it]?.childListId != null } == true
        val expanded = cellId in state.expanded

        TaskRow(
            depth = depth,
            cellId = cellId,
            displayTitle = if (isEditing) editDraft else title,
            selected = isSelected,
            selectable = selectable,
            isEditing = isEditing,
            hasChildren = hasChildren,
            expanded = expanded,
            onClick = { clicked, ctrl, shift ->
                if (!selectable) return@TaskRow
                onIntent(
                    SchedulerIntent.ClickCell(
                        cellId = clicked,
                        ctrl = ctrl,
                        shift = shift,
                        visibleOrder = visibleOrder,
                    ),
                )
            },
            onDoubleClick = {
                if (selectable && !isEditing) {
                    onIntent(SchedulerIntent.BeginEdit(cellId))
                }
            },
            onTextChange = { newText ->
                onIntent(SchedulerIntent.UpdateEditText(newText))
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
        if (suggestions.size > 1) {
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
    selected: Boolean,
    selectable: Boolean,
    isEditing: Boolean,
    hasChildren: Boolean,
    expanded: Boolean,
    onClick: (CellId, ctrl: Boolean, shift: Boolean) -> Unit,
    onDoubleClick: () -> Unit,
    onTextChange: (String) -> Unit,
    onToggleExpand: () -> Unit,
    editMenus: (@Composable () -> Unit)?,
) {
    val editFocusRequester = remember { FocusRequester() }
    LaunchedEffect(isEditing) {
        if (isEditing) editFocusRequester.requestFocus()
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = (depth * 16).dp)
            .background(
                if (selected) MaterialTheme.colorScheme.primaryContainer
                else MaterialTheme.colorScheme.surfaceVariant,
            )
            .padding(8.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(
                onClick = onToggleExpand,
                enabled = hasChildren,
            ) {
                Text(if (expanded) "▾" else "▸")
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
                OutlinedTextField(
                    modifier = Modifier
                        .weight(1f)
                        .focusRequester(editFocusRequester),
                    value = textFieldValue,
                    onValueChange = { newValue ->
                        textFieldValue = newValue
                        onTextChange(newValue.text)
                    },
                    singleLine = false,
                    label = { Text("Task") },
                )
            } else {
                Text(
                    modifier = Modifier
                        .weight(1f)
                        .then(
                            if (selectable) {
                                Modifier.combinedClickable(
                                    onClick = { onClick(cellId, false, false) },
                                    onDoubleClick = onDoubleClick,
                                )
                            } else {
                                Modifier
                            },
                        ),
                    text = displayTitle.ifEmpty { " " },
                    style = MaterialTheme.typography.bodyLarge,
                )
            }
        }
        editMenus?.invoke()
    }
}
