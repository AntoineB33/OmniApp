package org.example.project.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
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
        // User 2026-11-27: off, a node with an id is copied as its id alone.
        SearchDomain.AddedAction.DeepCopyTitles ->
            CompactSwitch(checked = options.includeTitles, onCheckedChange = { setOptions(options.copy(includeTitles = it)) })
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
            val others = copiedElements(added)
            // The deepest of the tasks' paths and of the other elements'.
            val path = remember(state, cells, others, depth) {
                listOf(SchedulerDomain.deepCopyPathTitles(state, cells, depth), SearchDomain.elementsDeepestPath(state, others, depth))
                    .maxByOrNull { it.size }.orEmpty()
            }
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                if (cells.size + others.size > 1) NoteText("The deepest of them")
                org.example.project.scheduler.ui.DeepCopyPathRow(path)
            }
        }
        SearchDomain.AddedAction.TaskDeepCopy -> {
            val cells = SearchDomain.addedTaskCells(state, added)
            // User 2026-11-26: every element copies, a task with its cells, any other with its children.
            val others = copiedElements(added)
            val count = cells.size + others.size
            FrameButton(if (count == 1) "Copy" else "Copy $count", enabled = count > 0 && depth >= 1) {
                val text = SchedulerDomain.copyCellsText(state, cells, depth, options) +
                    SearchDomain.elementsCopyText(state, others, depth, options.includeIds, options.excludeTitle, includeTitles = options.includeTitles)
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

/**
 * User 2026-11-27 (*"Shouldn't the deep copy open a window to select what kind of deep copy I want? For example all the tree
 * with only the id for each node?"*): **the deep copy of a row of an actions section with rows under it** ([root], e.g.
 * "Contexts and states") — the pop-up its menu's "deep copy" shows: the "Deep copy" group's rows an element's deep copy has
 * ([SearchDomain.DEEP_COPY_ACTIONS] but what only a task carries), the same account settings, its own sub-tree copied
 * ([subRowsCopyText]) and its deepest path said ([subRowsDeepestPath]).
 */
@Composable
internal fun SubRowDeepCopy(root: SubRowSpec, given: SchedulerState? = null, givenIntent: ((SchedulerIntent) -> Unit)? = null) {
    // Any row's menu shows it (user 2026-11-27): the account's state and dispatch, as `App` provides them, where not given.
    val state = given ?: LocalPatternExceptionState.current ?: SchedulerState.empty()
    val onIntent: (SchedulerIntent) -> Unit = givenIntent ?: LocalPatternExceptionDispatch.current ?: {}
    val options = SchedulerDomain.CopyOptions.from(state)
    val depth = if (state.deepCopyUnlimited) SchedulerDomain.FULL_SUBTREE_DEPTH else state.deepCopyMaxDepth
    androidx.compose.material3.Surface(
        color = androidx.compose.material3.MaterialTheme.colorScheme.surfaceContainer,
        contentColor = androidx.compose.material3.MaterialTheme.colorScheme.onSurface,
        border = androidx.compose.foundation.BorderStroke(1.dp, androidx.compose.material3.MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(Modifier.width(360.dp).padding(12.dp),verticalArrangement = Arrangement.spacedBy(6.dp)) {
            for (action in SUB_ROW_DEEP_COPY_ACTIONS) {
                Text(action.label, style = androidx.compose.material3.MaterialTheme.typography.labelLarge)
                when (action) {
                    SearchDomain.AddedAction.DeepCopyPath ->
                        org.example.project.scheduler.ui.DeepCopyPathRow(remember(root, depth, options.excludeTitle) { subRowsDeepestPath(root, depth, options.excludeTitle) })
                    SearchDomain.AddedAction.TaskDeepCopy ->
                        FrameButton("Copy", enabled = depth >= 1) {
                            val text = subRowsCopyText(root, depth, options.includeIds, options.excludeTitle, options.includeTitles)
                            if (text.isNotEmpty()) writeSystemClipboardText(text)
                        }
                    else -> DeepCopyActionEditor(state, action, emptyList(), onIntent)
                }
            }
        }
    }
}

/** The "Deep copy" rows a row of an actions section has ([SubRowDeepCopy]): an element's, but what only a task carries. */
internal val SUB_ROW_DEEP_COPY_ACTIONS: List<SearchDomain.AddedAction> =
    SearchDomain.DEEP_COPY_ACTIONS.filter { it !in SearchDomain.DEEP_COPY_TASK_ACTIONS }

/** The added elements a deep copy copies by their children ([SearchDomain.elementsCopyText]): every one but a task. */
private fun copiedElements(added: List<SearchDomain.Result>): List<String> =
    added.filter { it.kind != SearchDomain.Kind.Task && SearchDomain.deepCopyable(it.kind) }.map(SearchDomain::keyOf)
