package org.example.project.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.example.project.scheduler.domain.CategoryRules
import org.example.project.scheduler.model.CategoryId
import org.example.project.scheduler.model.CategoryKind
import org.example.project.scheduler.model.CellId
import org.example.project.scheduler.model.TaskId
import org.example.project.scheduler.state.SchedulerIntent
import org.example.project.scheduler.state.SchedulerState

/** The width of the task row's categories column, sized like the percentage and min-time ones beside it. */
private val CATEGORY_COLUMN_WIDTH = 132.dp

/** What the column reads when the task carries nothing yet — an invitation, not a value. */
private const val EMPTY_LABEL = "+ category"

/**
 * PRD §5 **the task cell's categories field**: the column, after the minimum time, that says which
 * categories this task carries — and the drop-down that is the only place they are added and removed.
 *
 * User rule 2026-11-12: the drop-down is a **Search window** of the account's task categories, each a check box ticked where
 * this task carries it — a click attaches it or takes it off *this task* (the category itself is the account's and
 * survives); a **right-click** opens [CategoryEditWindow] — *this category, every task and every rule*, which is where a
 * rule is written and where the category is deleted; a name the bar holds that no category has is a **"create"** row,
 * which makes it (so a name already taken attaches THAT one rather than minting a second). "Select all" attaches every
 * category listed. Until then it held the carried ones with a bin and a ✎, and a naming cell to add one.
 */
@Composable
fun TaskCategoryCell(
    state: SchedulerState,
    taskId: TaskId,
    onIntent: (SchedulerIntent) -> Unit,
    onOpenCategoryEdit: (CategoryId) -> Unit,
) {
    var open by remember(taskId) { mutableStateOf(false) }
    val carried = state.tasks[taskId]?.categoryIds.orEmpty().mapNotNull { state.categoryById(it) }

    // A tight row ([LocalTightRowColumns]): as wide as what it says, never wider than the column.
    val tight = LocalTightRowColumns.current
    Box(modifier = if (tight) Modifier.widthIn(max = CATEGORY_COLUMN_WIDTH) else Modifier.width(CATEGORY_COLUMN_WIDTH)) {
        Text(
            modifier = Modifier
                .then(if (tight) Modifier else Modifier.fillMaxWidth())
                .clip(RoundedCornerShape(4.dp))
                .clickable { open = true }
                .padding(horizontal = 6.dp, vertical = 2.dp),
            text = carried.joinToString(", ") { it.title }.ifEmpty { EMPTY_LABEL },
            style = MaterialTheme.typography.bodySmall,
            color = onTaskCell(
                if (carried.isEmpty()) MaterialTheme.colorScheme.outline
                else MaterialTheme.colorScheme.onSurfaceVariant,
            ),
        )
        // User rule 2026-11-12: the drop-down is a Search window of the account's task categories, ticked where this task
        // carries them: a click attaches or takes one off THIS task (the category is the account's and survives), a right-click
        // opens its window ([CategoryEditWindow]: every task and every rule, where it is deleted), and a name nobody holds is
        // offered as a row that makes it. Typing in the bar narrows the rows.
        val categories = state.categories.filter { it.kind == CategoryKind.TaskId }
            .sortedWith(compareBy({ it.id !in carried.map { c -> c.id } }, { it.title.lowercase() }))
        OptionSearchMenu(
            open = open,
            onDismiss = { open = false },
            options = SearchOptions(
                options = categories.map { it.id.value to it.title },
                checked = carried.mapTo(HashSet()) { it.id.value },
                onClick = { key ->
                    val id = CategoryId(key)
                    if (carried.any { it.id == id }) onIntent(SchedulerIntent.RemoveTaskCategory(taskId, id))
                    else onIntent(SchedulerIntent.AttachTaskCategory(taskId, id))
                },
                onSelectAll = { keys ->
                    val ids = keys.map(::CategoryId)
                    val all = ids.all { id -> carried.any { it.id == id } }
                    ids.forEach { id ->
                        if (all) onIntent(SchedulerIntent.RemoveTaskCategory(taskId, id))
                        else if (carried.none { it.id == id }) onIntent(SchedulerIntent.AttachTaskCategory(taskId, id))
                    }
                },
                onSolo = { key ->
                    open = false
                    onOpenCategoryEdit(CategoryId(key))
                },
                onCreate = { name ->
                    open = false
                    onIntent(SchedulerIntent.AddTaskCategory(taskId, name))
                },
            ),
        )
    }
}
