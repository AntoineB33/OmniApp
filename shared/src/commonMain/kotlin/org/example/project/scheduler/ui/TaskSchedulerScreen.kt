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
import org.example.project.scheduler.model.CellId
import org.example.project.scheduler.state.SchedulerIntent

@Composable
fun TaskSchedulerScreen(
    modifier: Modifier = Modifier,
    vm: TaskSchedulerViewModel = viewModel { TaskSchedulerViewModel() },
) {
    val state by vm.state.collectAsState()

    val rootList = state.lists[state.rootListId]
    val visible = rootList?.cellIds ?: emptyList()

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
            visible.forEach { cellId ->
                val cell = state.cells[cellId] ?: return@forEach
                val title = cell.taskId?.let { state.tasks[it]?.title }.orEmpty()
                val isSelected = state.selection.main == cellId || state.selection.selected.contains(cellId)

                TaskRow(
                    cellId = cellId,
                    title = title,
                    selected = isSelected,
                    onClick = { clicked, ctrl, shift ->
                        vm.dispatch(
                            SchedulerIntent.ClickCell(
                                cellId = clicked,
                                ctrl = ctrl,
                                shift = shift,
                                visibleOrder = visible,
                            )
                        )
                    },
                    onTitleChange = { newTitle ->
                        vm.dispatch(SchedulerIntent.SetCellTitle(cellId, newTitle))
                    },
                    onToggleExpand = {
                        vm.dispatch(SchedulerIntent.ToggleExpand(cellId))
                    }
                )
            }
        }
    }
}

@Composable
private fun TaskRow(
    cellId: CellId,
    title: String,
    selected: Boolean,
    onClick: (CellId, ctrl: Boolean, shift: Boolean) -> Unit,
    onTitleChange: (String) -> Unit,
    onToggleExpand: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                if (selected) MaterialTheme.colorScheme.primaryContainer
                else MaterialTheme.colorScheme.surfaceVariant
            )
            .padding(8.dp)
            .clickable { onClick(cellId, false, false) },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onToggleExpand) {
            Text("▸")
        }
        OutlinedTextField(
            modifier = Modifier.weight(1f),
            value = title,
            onValueChange = onTitleChange,
            singleLine = false,
            label = { Text("Task") },
        )
    }
}

