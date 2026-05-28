package org.example.project.scheduler.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import org.example.project.scheduler.domain.SchedulerDomain
import org.example.project.scheduler.model.CellId
import org.example.project.scheduler.model.CellListId
import org.example.project.scheduler.state.SchedulerIntent
import org.example.project.scheduler.state.SchedulerState

@Composable
fun TaskSchedulerScreen(
    modifier: Modifier = Modifier,
    vm: TaskSchedulerViewModel = viewModel { TaskSchedulerViewModel() },
) {
    val state by vm.state.collectAsState()
    val visibleOrder = SchedulerDomain.visibleCellOrder(state)

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface)
            .padding(12.dp),
    ) {
        Text(
            text = "Task Scheduler",
            style = MaterialTheme.typography.titleLarge,
        )
        Spacer(Modifier.height(12.dp))

        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
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
        val hasChildren = cell.taskId?.let { state.tasks[it]?.childListId != null } == true
        val expanded = cellId in state.expanded

        TaskRow(
            depth = depth,
            cellId = cellId,
            title = title,
            selected = isSelected,
            selectable = selectable,
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
            onTitleChange = { newTitle ->
                if (selectable) onIntent(SchedulerIntent.SetCellTitle(cellId, newTitle))
            },
            onToggleExpand = {
                if (hasChildren) onIntent(SchedulerIntent.ToggleExpand(cellId))
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

@Composable
private fun TaskRow(
    depth: Int,
    cellId: CellId,
    title: String,
    selected: Boolean,
    selectable: Boolean,
    hasChildren: Boolean,
    expanded: Boolean,
    onClick: (CellId, ctrl: Boolean, shift: Boolean) -> Unit,
    onTitleChange: (String) -> Unit,
    onToggleExpand: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = (depth * 16).dp)
            .background(
                if (selected) MaterialTheme.colorScheme.primaryContainer
                else MaterialTheme.colorScheme.surfaceVariant,
            )
            .padding(8.dp)
            .then(
                if (selectable) {
                    Modifier.clickable { onClick(cellId, false, false) }
                } else {
                    Modifier
                },
            ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(
            onClick = onToggleExpand,
            enabled = hasChildren,
        ) {
            Text(if (expanded) "▾" else "▸")
        }
        OutlinedTextField(
            modifier = Modifier.weight(1f),
            value = title,
            onValueChange = onTitleChange,
            enabled = selectable,
            singleLine = false,
            label = { Text("Task") },
        )
    }
}
