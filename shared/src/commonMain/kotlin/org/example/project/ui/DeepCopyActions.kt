package org.example.project.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.example.project.scheduler.domain.SchedulerDomain
import org.example.project.scheduler.domain.SearchDomain
import org.example.project.scheduler.platform.writeSystemClipboardText
import org.example.project.scheduler.state.SchedulerIntent
import org.example.project.scheduler.state.SchedulerState

/**
 * User rule 2026-11-25 (*"Transform the deep copy window into a search window where every action is a row"*): **one row of
 * the "Deep copy" group** ([SearchDomain.DEEP_COPY_ACTIONS]) — what the deep copy window drew, each control its own row. The
 * depth and the switches are the account's settings, written as they change ([SchedulerIntent.SetDeepCopyMaxDepth],
 * [SchedulerIntent.SetDeepCopyUnlimited], [SchedulerIntent.SetCopyOptions]: one History Unit coalesced per stretch of edits);
 * "Copy to the clipboard" copies the added tasks' first live cells with them.
 */
@Composable
internal fun DeepCopyActionEditor(
    state: SchedulerState,
    action: SearchDomain.AddedAction,
    added: List<SearchDomain.Result>,
    onIntent: (SchedulerIntent) -> Unit,
) {
    val options = SchedulerDomain.CopyOptions.from(state)
    val depth = if (state.deepCopyUnlimited) SchedulerDomain.FULL_SUBTREE_DEPTH else state.deepCopyMaxDepth
    fun setOptions(next: SchedulerDomain.CopyOptions) {
        if (next != options) onIntent(SchedulerIntent.SetCopyOptions(next))
    }
    fun setDepth(value: Int) {
        val next = value.coerceIn(SchedulerDomain.DEEP_COPY_DEPTH_RANGE)
        if (next != state.deepCopyMaxDepth) onIntent(SchedulerIntent.SetDeepCopyMaxDepth(next))
    }
    when (action) {
        SearchDomain.AddedAction.DeepCopyMaxDepth -> {
            // The raw text, so the field can be emptied while typing; the account keeps its last number until one is typed.
            var draft by remember(state.deepCopyMaxDepth) { mutableStateOf(state.deepCopyMaxDepth.toString()) }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedTextField(
                    value = if (state.deepCopyUnlimited) "∞" else draft,
                    onValueChange = { raw ->
                        draft = raw.filter { it.isDigit() }.take(3)
                        draft.toIntOrNull()?.takeIf { it >= 1 }?.let(::setDepth)
                    },
                    singleLine = true,
                    enabled = !state.deepCopyUnlimited,
                    modifier = Modifier.width(88.dp),
                )
                FrameButton("+", enabled = !state.deepCopyUnlimited) { setDepth(state.deepCopyMaxDepth + 1) }
                FrameButton("−", enabled = !state.deepCopyUnlimited) { setDepth(state.deepCopyMaxDepth - 1) }
            }
        }
        SearchDomain.AddedAction.DeepCopyWholeSubtree ->
            CompactSwitch(checked = state.deepCopyUnlimited, onCheckedChange = { onIntent(SchedulerIntent.SetDeepCopyUnlimited(it)) })
        SearchDomain.AddedAction.DeepCopyIds ->
            CompactSwitch(checked = options.includeIds, onCheckedChange = { setOptions(options.copy(includeIds = it)) })
        SearchDomain.AddedAction.DeepCopyWeightTables ->
            CompactSwitch(checked = options.priorityTables, onCheckedChange = { setOptions(options.copy(priorityTables = it)) })
        SearchDomain.AddedAction.DeepCopyPercentages ->
            CompactSwitch(checked = options.includePriorityPercentages, onCheckedChange = { setOptions(options.copy(includePriorityPercentages = it)) })
        SearchDomain.AddedAction.DeepCopyMinimumTime ->
            CompactSwitch(checked = options.includeMinimumTime, onCheckedChange = { setOptions(options.copy(includeMinimumTime = it)) })
        SearchDomain.AddedAction.DeepCopyText ->
            CompactSwitch(checked = options.includeText, onCheckedChange = { setOptions(options.copy(includeText = it)) })
        SearchDomain.AddedAction.DeepCopyExcludeTitle ->
            OutlinedTextField(
                value = options.excludeTitle,
                onValueChange = { setOptions(options.copy(excludeTitle = it)) },
                placeholder = { Text("title") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        SearchDomain.AddedAction.DeepCopyPath -> {
            val cells = SearchDomain.addedTaskCells(state, added)
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                if (cells.size > 1) NoteText("The deepest of them")
                org.example.project.scheduler.ui.DeepCopyPathRow(SchedulerDomain.deepCopyPathTitles(state, cells, depth))
            }
        }
        SearchDomain.AddedAction.TaskDeepCopy -> {
            val cells = SearchDomain.addedTaskCells(state, added)
            FrameButton(if (cells.size == 1) "Copy" else "Copy ${cells.size}", enabled = cells.isNotEmpty() && depth >= 1) {
                val text = SchedulerDomain.copyCellsText(state, cells, depth, options)
                if (text.isNotEmpty()) writeSystemClipboardText(text)
            }
        }
        SearchDomain.AddedAction.DeepCopyReset ->
            FrameButton("Reset") {
                setDepth(SchedulerDomain.DEEP_COPY_DEFAULT_DEPTH)
                if (state.deepCopyUnlimited) onIntent(SchedulerIntent.SetDeepCopyUnlimited(false))
                setOptions(SchedulerDomain.CopyOptions())
            }
        else -> Unit
    }
}
