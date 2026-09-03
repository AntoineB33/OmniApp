package org.example.project.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt
import org.example.project.scheduler.domain.CategoryRules
import org.example.project.scheduler.model.CategoryId
import org.example.project.scheduler.state.SchedulerIntent
import org.example.project.scheduler.state.SchedulerState

/**
 * PRD §5/§7 **Categories**: the floating window the lateral menu's *Categories* button opens — every
 * category the account holds, and the second place one is created.
 *
 * The task cell's field ([TaskCategoryCell]) is *one task, every category*; the category edit window
 * ([CategoryEditWindow]) is *one category, every task*; this is the question neither of them asks — **which
 * categories does this account have at all?** Until it existed a category could only be reached through a
 * task that happened to carry it, so one carried by nothing (its last carrier deleted, or a rule written
 * before the tasks it is about) was invisible and unreachable, and the only way to define one was to give it
 * to a task first.
 *
 * So the window is two things and no more:
 *
 *  - **the list.** One row per category, in title order — the order the user can predict, which minting
 *    order is not ([CategoryRules.overview]). Each row says what the category is *doing*: how many tasks
 *    carry it, and what its rules ask, the asleep ones counted out. The row's **✎** opens
 *    [CategoryEditWindow], which stays the one place a category is renamed, given a rule, or **deleted** —
 *    exactly the pair a task cell's category row makes with it, and the pair the resilience row makes with
 *    the period edit window. There is no bin here: a delete takes the label off every task carrying it and
 *    every rule about it, so it belongs where all of them can be seen;
 *  - **the naming field**, which is the task cell's "add" option: an [EditModeMenuBlock] with no Mode
 *    selector, whose **identity** rows are the account's own categories and whose **title suggestions** only
 *    fill the field. What differs is where an identity row leads, because there is no task here to attach
 *    anything to: picking an existing category **opens it** (that is the answer to "I meant this one"), and
 *    only a name the account has not got is created ([SchedulerIntent.CreateCategory]).
 *
 * A **sort-1** window (`ui/PopupWindows.kt`): there is exactly one of it, so it stacks and stays open until
 * it is closed, like every other lateral-menu window. The category window a row opens is sort 2 and is about
 * ONE category, which is why pressing ✎ on another row replaces it rather than stacking beside it.
 *
 * Read-only about the tree in the sense that matters: nothing here moves a priority, and — like defining a
 * kind of restrictive period — creating a category records no Undo/Redo unit. The categories themselves are
 * authoritative synced state.
 */
@Composable
fun CategoriesWindow(
    /** The live state: the account's categories, and the tree their rules are measured against. */
    state: SchedulerState,
    onIntent: (SchedulerIntent) -> Unit,
    /** Opens the row's own (sort-2) [CategoryEditWindow] — the one place a category is edited or deleted. */
    onOpenCategoryEdit: (CategoryId) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    /** Initial position relative to centered; staggered per window so they open in a clickable cascade. */
    initialOffset: Offset = Offset.Zero,
    /** Persists the window's new drag position when a drag gesture ends (local-only geometry). */
    onOffsetChange: (Offset) -> Unit = {},
    /** Raise this window to the top of the layers — fired on a press anywhere inside it. */
    onRaise: () -> Unit = {},
) {
    var offset by remember { mutableStateOf(initialOffset) }
    // What is being typed in the naming field. Compose-only state, like a cell's own draft title: a
    // half-typed name is not a category until the button below commits it.
    var draft by remember { mutableStateOf("") }

    // Keyed on what the rows actually read rather than on the whole state, which the engine tick replaces
    // every second (records live on the tasks) — ADR 0009. Each rule is a walk of the tree, so this must
    // not run per tick.
    val rows =
        remember(state.cells, state.lists, state.tasks, state.categories) { CategoryRules.overview(state) }
    val typed = draft.trim()
    val existing = state.categories.firstOrNull { it.title.equals(typed, ignoreCase = true) }

    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surface,
        shadowElevation = 10.dp,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = modifier
            .offset { IntOffset(offset.x.roundToInt(), offset.y.roundToInt()) }
            // requiredWidth (not width) so the window keeps its fixed width and does not adapt to the
            // app's width when the content area is narrower than it.
            .requiredWidth(460.dp)
            // Raise on press AFTER the offset so the hit region tracks the (possibly dragged) window.
            .raiseOnPress(onRaise),
    ) {
        Column(Modifier.fillMaxWidth()) {
            // Title bar doubles as the drag handle for moving the window.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .windowDragHandle(onDragEnd = { onOffsetChange(offset) }) { dragAmount ->
                        offset += dragAmount
                    }
                    .padding(start = 14.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "Categories",
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f),
                )
                Box(
                    modifier = Modifier.size(28.dp).clip(CircleShape).clickable(onClick = onDismiss),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        "✕",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Box(Modifier.fillMaxWidth().height(1.dp).background(MaterialTheme.colorScheme.outlineVariant))

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    // Grows with the categories up to a cap, then scrolls — an account may hold many.
                    .heightIn(max = 460.dp)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                if (rows.isEmpty()) {
                    Text(
                        text =
                            "No category yet. Name one below, or give a task one from its categories " +
                                "field in the tree — both reach this same list.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                for (row in rows) {
                    CategoryRow(row = row, onEdit = { onOpenCategoryEdit(row.category.id) })
                }

                HorizontalDivider()

                // The naming field: the task cell's "add" option, minus the task. Its identity rows OPEN a
                // category instead of attaching it — there is nothing here to attach one to — which is also
                // what keeps a name the account already holds from being minted a second time.
                Text("Add a category", style = MaterialTheme.typography.labelMedium)
                OutlinedTextField(
                    value = draft,
                    onValueChange = { draft = it },
                    singleLine = true,
                    label = { Text("Name") },
                    modifier = Modifier.fillMaxWidth(),
                )
                EditModeMenuBlock(
                    identityLabel = "Categories",
                    identityRows =
                        CategoryRules.menuEntries(state, draft, emptyList()).map { category ->
                            EditMenuItem(
                                label = category.title,
                                selected = category.title.equals(typed, ignoreCase = true),
                            ) { onOpenCategoryEdit(category.id) }
                        },
                    // A suggestion only FILLS the field, as it does in a cell — the button below is what
                    // commits, and the identity row above is what points at one that already exists.
                    suggestions =
                        CategoryRules.titleSuggestions(state, draft).map { suggestion ->
                            EditMenuItem(suggestion) { draft = suggestion }
                        },
                )
                if (typed.isNotEmpty()) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (existing != null) {
                            Text(
                                text = "“${existing.title}” already exists — open it above.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.weight(1f),
                            )
                        } else {
                            Spacer(Modifier.weight(1f))
                        }
                        TextButton(
                            enabled = existing == null,
                            onClick = {
                                onIntent(SchedulerIntent.CreateCategory(typed))
                                draft = ""
                            },
                        ) { Text("Create") }
                    }
                }
            }
        }
    }
}

/**
 * One category: its name, the line saying what it is doing, and the **✎** onto its own window. No bin — a
 * category is deleted where every task carrying it and every rule about it can be seen at once.
 */
@Composable
private fun CategoryRow(row: CategoryRules.OverviewRow, onEdit: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = row.category.title,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = rowSummary(row),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.width(10.dp))
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(6.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .clickable(onClick = onEdit)
                .padding(horizontal = 8.dp, vertical = 4.dp),
        ) {
            Text(
                text = "✎",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * What a row says under the name: the two questions the list is for — *is anything carrying it* and *is it
 * claiming anything* — with the asleep rules counted out, a rule that governs nothing being exactly what the
 * user cannot see from the tree.
 */
private fun rowSummary(row: CategoryRules.OverviewRow): String {
    val carriers = when (row.carriers) {
        0 -> "carried by no task"
        1 -> "carried by 1 task"
        else -> "carried by ${row.carriers} tasks"
    }
    val rules = when {
        row.rules.isEmpty() -> "no rule"
        row.dormant == 0 && row.rules.size == 1 -> "1 rule"
        row.dormant == 0 -> "${row.rules.size} rules"
        else -> "${row.rules.size} rule${if (row.rules.size == 1) "" else "s"}, ${row.dormant} asleep"
    }
    return "$carriers · $rules"
}
