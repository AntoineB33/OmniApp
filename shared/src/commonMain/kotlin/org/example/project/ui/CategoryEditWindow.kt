package org.example.project.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt
import org.example.project.scheduler.domain.CategoryRules
import org.example.project.scheduler.model.CategoryId
import org.example.project.scheduler.state.SchedulerIntent
import org.example.project.scheduler.state.SchedulerState

/**
 * PRD §5 **the category edit window** — one category, and everything it is: its name, the rules it imposes,
 * and the tasks that carry it.
 *
 * It is the task cell's categories field read the other way round, exactly as the period edit window is the
 * resilience section read the other way round. That field is *one task, every category*; this is *one
 * category, every task* — and it is the one place a category is an object in its own right, which is why it
 * is the one place a category is **deleted** and the one place a **rule** is written.
 *
 * A **rule** is the whole point of the window: *the tasks carrying this category, under that task, are worth
 * this much of it*. Three things about it are worth knowing from the outside:
 *
 *  - **the scope is a task CELL**, not a task. A task can appear several times in the tree, so "under Book"
 *    names no place when there are two of them: the window asks *under which task cell*, offers every cell
 *    by its own path, and a rule row prints that path back. What the cell then names is the sub-list its
 *    task owns, because a sub-list belongs to the task id — which is why two cells of one mirrored task are
 *    one scope and not two. `root` is the whole tree;
 *  - **at most one rule per scope.** Adding a rule about a scope that already has one replaces it: two
 *    statements about the same sub-tree would be the plainest contradiction there is, so the window never
 *    lets one be made;
 *  - **the rule is HELD, not recorded.** The app re-establishes it after every edit anywhere in the app, by
 *    scaling the carrying branches by one common factor and letting the rest of the sub-tree keep its own
 *    proportions — "adjust the priorities evenly". Each row therefore prints what the rule *gets* beside what
 *    it *asks*, and the two are equal whenever the rule is live. An edit that could not be scaled back onto
 *    the rules is refused outright, with the app's own notice saying why.
 *
 * A row that is not live says so rather than pretending: **the scope is gone** (deleted, or never in this
 * tree) or **nothing under it carries the category**. Neither is a contradiction — deleting the last carrier
 * is an ordinary edit — so the rule sleeps until the tree gives it something to govern again.
 *
 * A **sort-2** pop-up (`ui/PopupWindows.kt`): it is about ONE object, so "the window of category A" and "the
 * window of category B" are two different windows and only the one just asked for is ever meant. Like the
 * period edit window it has no Save — every field writes as it is typed.
 */
@Composable
fun CategoryEditWindow(
    state: SchedulerState,
    categoryId: CategoryId,
    onIntent: (SchedulerIntent) -> Unit,
    onDismiss: () -> Unit,
) {
    val category = state.categoryById(categoryId) ?: return
    var title by remember(categoryId) { mutableStateOf(category.title) }
    // The rule being added: which sub-tree, and how much of it. Compose-only state, like the calendar's
    // zoom — a half-typed rule is not a fact about the account until it is added. The pick is the whole
    // ROW and not its cell id, because `null` is a real answer there (the whole tree) and "nothing picked
    // yet" has to stay a different one.
    var scopeDraft by remember(categoryId) { mutableStateOf("") }
    var scopePick by remember(categoryId) { mutableStateOf<CategoryRules.ScopeEntry?>(null) }
    var newShare by remember(categoryId) { mutableStateOf("") }

    val rows = CategoryRules.ruleRows(state, categoryId)
    val carriers = CategoryRules.tasksWith(state, categoryId)

    TransientPopupLayer {
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.surface,
            shadowElevation = 12.dp,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            modifier = Modifier.transientPopupCard(onDismiss).width(420.dp),
        ) {
            Column(
                Modifier.padding(16.dp).heightIn(max = 620.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    // Renaming writes as it is typed and reaches every task at once, because everything
                    // names the category by id. A blank name is refused rather than deleting: the blank
                    // title deletes a TASK; a category is deleted by the button beside this field.
                    OutlinedTextField(
                        value = title,
                        onValueChange = {
                            title = it
                            onIntent(SchedulerIntent.RenameCategory(categoryId, it))
                        },
                        singleLine = true,
                        label = { Text("Category") },
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = {
                        onIntent(SchedulerIntent.DeleteCategory(categoryId))
                        onDismiss()
                    }) { Text("Delete") }
                }

                Text(
                    text =
                        if (carriers.isEmpty()) "No task carries this category yet."
                        else "Carried by ${carriers.size} task${if (carriers.size == 1) "" else "s"}.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                HorizontalDivider()

                Text("Rules", style = MaterialTheme.typography.labelMedium)
                Text(
                    "The tasks carrying this category, inside the sub-tree of the task cell you name, " +
                        "always come to the share you give here. The other priorities under it are " +
                        "adjusted evenly to make room, and an edit that would contradict a rule is refused.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                if (rows.isEmpty()) {
                    Text(
                        "No rule yet — this category is only a label.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                for (row in rows) {
                    Column(Modifier.fillMaxWidth()) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Text(
                                text = "under “${row.scopeLabel}”",
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.weight(1f),
                            )
                            SharePercentField(
                                value = row.rule.share,
                                onValueChange = { next ->
                                    onIntent(
                                        SchedulerIntent.SetCategoryRule(
                                            categoryId,
                                            row.rule.scopeCellId,
                                            next,
                                        ),
                                    )
                                },
                            )
                            TextButton(onClick = {
                                onIntent(
                                    SchedulerIntent.RemoveCategoryRule(categoryId, row.rule.scopeCellId),
                                )
                            }) { Text("🗑") }
                        }
                        Text(
                            text = ruleStatusLine(row),
                            style = MaterialTheme.typography.labelSmall,
                            color =
                                if (row.status == CategoryRules.Status.Held) {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                } else {
                                    MaterialTheme.colorScheme.error
                                },
                        )
                    }
                }

                HorizontalDivider()

                Text("Add a rule", style = MaterialTheme.typography.labelMedium)
                // The scope is named the way everything else in the app is named: a field with an identity
                // menu under it. There are no title suggestions here — an identity row IS the answer, since
                // a scope is one cell of the tree and not a string several cells may share. Each row is a
                // PATH for exactly that reason: a task can appear several times, and its bare title would
                // name every occurrence at once.
                OutlinedTextField(
                    value = scopeDraft,
                    onValueChange = {
                        scopeDraft = it
                        scopePick = null
                    },
                    singleLine = true,
                    label = { Text("Under which task cell") },
                    modifier = Modifier.fillMaxWidth(),
                )
                EditModeMenuBlock(
                    identityLabel = "Task cells",
                    identityRows =
                        CategoryRules.scopeEntries(state, scopeDraft).map { entry ->
                            EditMenuItem(label = entry.label, selected = entry == scopePick) {
                                scopePick = entry
                                scopeDraft = entry.label
                            }
                        },
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    OutlinedTextField(
                        value = newShare,
                        onValueChange = { newShare = it },
                        singleLine = true,
                        suffix = { Text("%") },
                        label = { Text("Share") },
                        modifier = Modifier.width(140.dp),
                    )
                    Spacer(Modifier.weight(1f))
                    val share = parsePercent(newShare)
                    TextButton(
                        enabled = scopePick != null && share != null,
                        onClick = {
                            val scope = scopePick ?: return@TextButton
                            onIntent(
                                SchedulerIntent.SetCategoryRule(
                                    categoryId,
                                    scope.cellId,
                                    share ?: return@TextButton,
                                ),
                            )
                            scopeDraft = ""
                            scopePick = null
                            newShare = ""
                        },
                    ) {
                        // Saying "Replace" is the window's way of holding the one-rule-per-scope rule in
                        // front of the user, instead of silently overwriting what is already there. It asks
                        // through the scope KEY, so pointing at another occurrence of a mirrored task says
                        // "replace" — which is what the reducer will do, the sub-tree being the same one.
                        val exists =
                            scopePick?.let { pick ->
                                val key = CategoryRules.scopeKey(state, pick.cellId)
                                category.rules.any { CategoryRules.scopeKey(state, it.scopeCellId) == key }
                            } == true
                        Text(if (exists) "Replace rule" else "Add rule")
                    }
                }

                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onDismiss) { Text("Close") }
                }
            }
        }
    }
}

/** What a rule row says under itself: that it is being held, or the reason it is asleep. */
private fun ruleStatusLine(row: CategoryRules.RuleRow): String = when (row.status) {
    CategoryRules.Status.Held -> "currently ${formatShare(row.achieved ?: 0.0)} of it"
    CategoryRules.Status.ScopeGone -> "that task cell is no longer in the tree — the rule is asleep"
    CategoryRules.Status.NoCarrier -> "no task under it carries this category — the rule is asleep"
}

/**
 * A rule's share, typed as a **percentage**, which is what it means. The text is local while it is being
 * typed so a half-typed "1" does not commit as 1 %, and only a value that parses is reported — the same
 * two-state field the resilience rows use, for the same reason.
 */
@Composable
private fun SharePercentField(value: Double, onValueChange: (Double) -> Unit) {
    var text by remember(value) { mutableStateOf(formatShareNumber(value)) }
    OutlinedTextField(
        value = text,
        onValueChange = { raw ->
            text = raw
            parsePercent(raw)?.let(onValueChange)
        },
        singleLine = true,
        suffix = { Text("%") },
        modifier = Modifier.width(96.dp),
    )
}

/** A typed percentage as a fraction in `[0, 1]`, or null while what is typed is not a number. */
private fun parsePercent(raw: String): Double? =
    raw.trim().removeSuffix("%").trim().replace(',', '.').toDoubleOrNull()
        ?.takeIf { it.isFinite() }
        ?.let { (it / 100.0).coerceIn(0.0, 1.0) }

private fun formatShare(value: Double): String = "${formatShareNumber(value)} %"

/** A fraction as a percentage with no trailing zeros — `0`, `33`, `12.5`. */
private fun formatShareNumber(value: Double): String {
    val rounded = (value * 1000.0).roundToInt() / 10.0
    return if (rounded == rounded.toInt().toDouble()) rounded.toInt().toString() else rounded.toString()
}
