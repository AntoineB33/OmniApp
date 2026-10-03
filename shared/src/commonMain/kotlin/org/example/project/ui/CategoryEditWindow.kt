package org.example.project.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import org.example.project.scheduler.model.CellId
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
 * A window about ONE object (`docs/invariants/popups.md`), so "the window of category A" and "the
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
    state.categoryById(categoryId) ?: return
    val frame = rememberWindowFrameState(CATEGORY_EDIT_FRAME_ID)

    TransientPopupLayer(frame.id) {
        AppWindowFrame(
            title = "Category",
            state = frame,
            onClose = onDismiss,
            defaultWidth = 420.dp,
            defaultHeight = 620.dp,
            claimsKeyboard = true,
            modifier = Modifier.align(Alignment.Center),
        ) {
            Column(
                Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                CategoryEditor(state, categoryId, onIntent, onDeleted = onDismiss)
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onDismiss) { Text("Close") }
                }
            }
        }
    }
}

/**
 * Everything [CategoryEditWindow] holds about one category — its name and Delete, the tasks carrying it, its rules
 * and the field that adds one — without the frame: the window's content, and (user rule 2026-10-01) the Search
 * window's action on each added category, which replaced the window for the account's categories. [onDeleted] runs
 * after its Delete.
 */
@Composable
fun CategoryEditor(
    state: SchedulerState,
    categoryId: CategoryId,
    onIntent: (SchedulerIntent) -> Unit,
    onDeleted: () -> Unit = {},
) {
    val category = state.categoryById(categoryId) ?: return
    var title by remember(categoryId) { mutableStateOf(category.title) }
    // The rule being added: which sub-tree, and how much of it. Compose-only state, like the calendar's
    // zoom — a half-typed rule is not a fact about the account until it is added. The pick is the whole
    // ROW and not its cell id, because `null` is a real answer there (the whole tree) and "nothing picked
    // yet" has to stay a different one.
    var scopePick by remember(categoryId) { mutableStateOf<CategoryRules.ScopeEntry?>(null) }
    var newShare by remember(categoryId) { mutableStateOf("") }

    val rows = CategoryRules.ruleRows(state, categoryId)
    val carriers = CategoryRules.tasksWith(state, categoryId)

    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
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
                        onDeleted()
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
                ScopeField(state, CellId("category/${categoryId.value}/rule-scope"), scopePick) { scopePick = it }
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
    }
}

/**
 * User rule 2026-10-02: **the rules of every added category, as ONE list** — a row per scope
 * ([CategoryRules.sharedRuleRows]). The share field is empty unless they all give that scope the same share; a share
 * typed there is every category's rule about it, and the bin takes the rule off every one that has it.
 */
@Composable
internal fun CategoriesRulesEditor(state: SchedulerState, categoryIds: List<CategoryId>, onIntent: (SchedulerIntent) -> Unit) {
    val rows = CategoryRules.sharedRuleRows(state, categoryIds)
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (rows.isEmpty()) {
            Text(
                if (categoryIds.size == 1) "No rule yet — this category is only a label." else "No rule yet — these categories are only labels.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        for (row in rows) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Column(Modifier.weight(1f)) {
                    Text("under “${row.scopeLabel}”", style = MaterialTheme.typography.bodySmall)
                    if (row.holders < categoryIds.size) {
                        Text(
                            "a rule of ${row.holders} of the ${categoryIds.size} categories",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                SharedSharePercentField(
                    value = row.share,
                    onValueChange = { next -> categoryIds.forEach { onIntent(SchedulerIntent.SetCategoryRule(it, row.scopeCellId, next)) } },
                )
                TextButton(onClick = {
                    categoryIds.forEach { onIntent(SchedulerIntent.RemoveCategoryRule(it, row.scopeCellId)) }
                }) { Text("🗑") }
            }
        }
    }
}

/** [SharePercentField] over several rules: empty while they differ (or one is missing), and only a parsed value writes. */
@Composable
private fun SharedSharePercentField(value: Double?, onValueChange: (Double) -> Unit) {
    var text by remember(value) { mutableStateOf(value?.let(::formatShareNumber).orEmpty()) }
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

/**
 * User rule 2026-10-02: **"Add a rule", once for every added category** — the scope named the way the single
 * category's editor names it (a field with the task cells' paths under it), a share, and one button that gives the
 * rule to each of them (replacing the one a category already has about that sub-tree).
 */
@Composable
internal fun CategoriesAddRule(state: SchedulerState, categoryIds: List<CategoryId>, onIntent: (SchedulerIntent) -> Unit) {
    var scopePick by remember { mutableStateOf<CategoryRules.ScopeEntry?>(null) }
    var newShare by remember { mutableStateOf("") }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        ScopeField(state, CellId("search/categories-add-rule-scope"), scopePick) { scopePick = it }
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
                enabled = categoryIds.isNotEmpty() && scopePick != null && share != null,
                onClick = {
                    val scope = scopePick ?: return@TextButton
                    val value = share ?: return@TextButton
                    categoryIds.forEach { onIntent(SchedulerIntent.SetCategoryRule(it, scope.cellId, value)) }
                    scopePick = null
                    newShare = ""
                },
            ) { Text(if (categoryIds.size == 1) "Add rule" else "Add rule to ${categoryIds.size}") }
        }
    }
}

/**
 * A rule's **"Under which task cell"**: a [NamingCell] (user rule 2026-10-03 — every field naming an element is one),
 * whose identity rows are the tree's cells, each named by its PATH: a task can appear several times, and its bare
 * title would name every occurrence at once. No title suggestions — an identity row IS the answer, a scope being one
 * cell of the tree and not a string several cells may share. Emptying it forgets the pick.
 */
@Composable
private fun ScopeField(state: SchedulerState, cellId: CellId, pick: CategoryRules.ScopeEntry?, onPick: (CategoryRules.ScopeEntry?) -> Unit) {
    Text("Under which task cell", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    NamingCell(
        cellId = cellId,
        shown = pick?.label.orEmpty(),
        identityLabel = "Task cells",
        identity = { draft -> CategoryRules.scopeEntries(state, draft).map { NamingRow(scopeKey(it), it.label) } },
        suggestions = { emptyList() },
        onPick = { key -> onPick(CategoryRules.scopeEntries(state, "").firstOrNull { scopeKey(it) == key }) },
        selectedKey = pick?.let(::scopeKey),
        onCleared = { onPick(null) },
        width = 360.dp,
    )
}

/** A scope's [NamingRow] key: its cell's id, or a key no cell id can be for the whole tree. */
private fun scopeKey(entry: CategoryRules.ScopeEntry): String = entry.cellId?.value ?: ROOT_SCOPE_KEY

/** The whole tree's scope key: an id no cell is ever minted with. */
private const val ROOT_SCOPE_KEY = "(root)"

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
internal fun parsePercent(raw: String): Double? =
    raw.trim().removeSuffix("%").trim().replace(',', '.').toDoubleOrNull()
        ?.takeIf { it.isFinite() }
        ?.let { (it / 100.0).coerceIn(0.0, 1.0) }

private fun formatShare(value: Double): String = "${formatShareNumber(value)} %"

/** A fraction as a percentage with no trailing zeros — `0`, `33`, `12.5`. */
internal fun formatShareNumber(value: Double): String {
    val rounded = (value * 1000.0).roundToInt() / 10.0
    return if (rounded == rounded.toInt().toDouble()) rounded.toInt().toString() else rounded.toString()
}

/** The category window's frame id, before its number (`CategoryEdit#2`) — see [ObjectWindowKey.Kind.frameBase]. */
const val CATEGORY_EDIT_FRAME_ID: String = "CategoryEdit"
