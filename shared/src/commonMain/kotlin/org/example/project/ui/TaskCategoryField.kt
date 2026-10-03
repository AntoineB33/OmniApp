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
import androidx.compose.material3.DropdownMenu
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
 * The drop-down holds exactly two things, in this order:
 *
 *  - **one row per category the task carries**, each with a **bin** (take it off *this task*; the category
 *    itself is the account's and survives) and a **✎** onto [CategoryEditWindow] — *this category, every
 *    task and every rule*, which is where a rule is written and where the category is deleted. The pair is
 *    deliberately the resilience row's pair (`✎` onto the period's own window, the row itself being about
 *    one task): a category and a kind of period are both objects the tree merely *refers* to;
 *  - **the "add" option, which is a task cell entering Edit Mode** — the [NamingCell] every field naming an
 *    element is, its menus drawn only once it is entered, with the **identity** rows (the account's
 *    categories, so a name already taken attaches THAT one rather than minting a second under the same
 *    spelling, then a "Create" row for a name nobody holds) and the **title suggestions**. What it has not
 *    got is the **Mode selector** — a cell chooses between renaming its task
 *    and pointing at another, and neither question exists here: naming a category IS pointing at it.
 *
 * The drop-down closes on the gesture that changes something, because every one of them is a whole answer:
 * removing a category, opening its window, attaching one. Typing is the exception — the field stays open
 * while it narrows the rows under it, exactly as a cell's does.
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

    Box(modifier = Modifier.width(CATEGORY_COLUMN_WIDTH)) {
        Text(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(4.dp))
                .clickable { open = true }
                .padding(horizontal = 6.dp, vertical = 2.dp),
            text = carried.joinToString(", ") { it.title }.ifEmpty { EMPTY_LABEL },
            style = MaterialTheme.typography.bodySmall,
            color = onTaskCell(
                if (carried.isEmpty()) MaterialTheme.colorScheme.outline
                else MaterialTheme.colorScheme.onSurfaceVariant,
            ),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            Column(
                modifier = Modifier
                    .widthIn(min = 240.dp, max = 320.dp)
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text("Categories", style = MaterialTheme.typography.labelMedium)
                if (carried.isEmpty()) {
                    Text(
                        "This task carries none yet.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                for (category in carried) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        Text(
                            text = category.title,
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        // The bin is about THIS TASK. Deleting the category itself is the ✎ window's
                        // button, for the same reason the period edit window owns "Delete period".
                        TextButton(onClick = {
                            open = false
                            onIntent(SchedulerIntent.RemoveTaskCategory(taskId, category.id))
                        }) { Text("🗑") }
                        TextButton(onClick = {
                            open = false
                            onOpenCategoryEdit(category.id)
                        }) { Text("✎") }
                    }
                }

                HorizontalDivider()

                // The "add" option: a task cell in Edit Mode, minus the Mode selector — the [NamingCell] every field
                // naming an element is (user rule 2026-10-03), so its menus show only once it is entered. Its
                // identity rows are the account's categories (a name already taken attaches THAT one rather than
                // minting a second under the same spelling), then a "Create" row for a name nobody holds.
                Text("Add a category", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                NamingCell(
                    cellId = CellId("task-categories/${taskId.value}"),
                    shown = "",
                    identityLabel = "Categories",
                    identity = { draft ->
                        val typed = draft.trim()
                        CategoryRules.menuEntries(state, draft, carried.map { it.id }).map { NamingRow(it.id.value, it.title) } +
                            listOfNotNull(
                                namingCreateRow(typed).takeIf {
                                    typed.isNotEmpty() && state.categories.none { it.title.equals(typed, ignoreCase = true) }
                                },
                            )
                    },
                    suggestions = { draft -> CategoryRules.titleSuggestions(state, draft) },
                    onPick = { key ->
                        open = false
                        val created = namingCreatedName(key)
                        if (created != null) onIntent(SchedulerIntent.AddTaskCategory(taskId, created))
                        else onIntent(SchedulerIntent.AttachTaskCategory(taskId, CategoryId(key)))
                    },
                    width = 296.dp,
                )
            }
        }
    }
}
