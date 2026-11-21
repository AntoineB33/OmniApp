package org.example.project.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.example.project.scheduler.domain.ElementTree
import org.example.project.scheduler.domain.EvolutionPeriod
import org.example.project.scheduler.domain.EvolutionTarget
import org.example.project.scheduler.domain.FormulaCoherence
import org.example.project.scheduler.domain.PresenceEvolution
import org.example.project.scheduler.domain.PresenceEvolutions
import org.example.project.scheduler.domain.SearchDomain
import org.example.project.scheduler.domain.TimeFormula
import org.example.project.scheduler.model.TaskId
import org.example.project.scheduler.state.SchedulerIntent
import org.example.project.scheduler.state.SchedulerState

/** The indent of a row under its action's title, as "Parents"' fields stand. */
private val ROW_INDENT = 16.dp

/** The rows' grey texts — each row's name (the evolutions' own: [evolutionsPlaceholder]). */
internal enum class PresenceRow(val placeholder: String) {
    Evolutions("presence ratio target evolutions"),
    Tasks("tasks that get it"),
    Relative("relative to"),
    Periods("time periods"),
    Formula("formula"),
    Categories("categories"),
}

/**
 * User rule 2026-11-19: **"Presence ratio target", in the group of the added tasks S, is rows only**:
 * - **the evolutions** — a field opening a Search window of every presence ratio target evolution (anomaly 2026-11-20:
 *   *"It should show all the Presence ratio target evolutions by default, since that's what this field is for"* — it
 *   excluded those involving no task of S, which its ▾ showed as excluded elements), its added elements list A showing: what is added there is what the rows below edit (and a row making a new one, for
 *   S, added to A);
 * - **the tasks that get it** — a field opening a Search window of tasks whose added elements are the tasks of A's
 *   evolutions: added there, a task gets them; dropped, it no longer does;
 * - **relative to** — a field opening the tasks above every task of A, a click making one the relative task, and at the
 *   top a row "parent distance [n]" whose field makes it each task's own ancestor n levels up;
 * - **the time periods**, **the formula** ([TimeFormula]: the ratio, 0 to 1), **the categories** (a field opening the
 *   categories, the added ones those the evolutions carry);
 * - then each incoherence ([FormulaCoherence]), a row each, while the schedulers wait.
 *
 * User rule 2026-11-20 (*"The minimum time target evolution action should be the same thing"*): "Minimum length target"
 * is the same rows over the minimum length target evolutions ([target] [EvolutionTarget.Minimum]) — less "relative to",
 * a minimum being the task's own; and (*"Add a delete button"*) beside "New", the bin, over the evolutions edited.
 */
@Composable
internal fun EvolutionRows(
    target: EvolutionTarget,
    state: SchedulerState,
    added: List<SearchDomain.Result>,
    rowId: String,
    run: (SchedulerIntent) -> Unit,
) {
    val s = SearchDomain.addedTaskIds(state, added)
    val kind = SearchDomain.evolutionKindOf(target)
    val evolutionKey = { id: String -> kind.name + "/" + id }
    val all = PresenceEvolutions.shown(state, target)
    val allKeys = all.mapTo(HashSet()) { evolutionKey(it.id) }
    val evolutionsPlaceholder = target.label + " evolutions"

    // --- 1. the evolutions, and their added elements list A
    // User rule 2026-11-20: every evolution, sorted by their involvement of S — configurations in its ▾.
    val evolutionsOpened = SearchDomain.evolutionsFieldConfig(s, target)
    var evolutionsConfig by rememberInsideConfig(embeddedSearchId("field:$rowId/$evolutionsPlaceholder"), evolutionsOpened)
    val evolutionsShown = evolutionsConfig.copy(
        kinds = setOf(kind),
        added = evolutionsConfig.added.filter { it in allKeys },
    )
    // Anomaly 2026-11-20 (*"I selected the default presence ratio target evolution, but in relative to there is nothing"*):
    // a row selected in the field's window is in its added elements (user rule 2026-11-15), so the rows below edit it as
    // they edit an added one. Held once the window closes — a press on a row below closes it — until another row is
    // selected there or the field's own list changes.
    var selectedInWindow by androidx.compose.runtime.saveable.rememberSaveable(rowId) { mutableStateOf(emptyList<String>()) }
    val a: List<PresenceEvolution> = (evolutionsShown.added + selectedInWindow).distinct()
        .mapNotNull { key -> all.firstOrNull { evolutionKey(it.id) == key } }
    fun edit(change: (PresenceEvolution) -> PresenceEvolution, editKey: String? = null) {
        val ids = a.mapTo(HashSet()) { it.id }
        // A default evolution of A (user rule 2026-11-20) is derived: edited, it is written, under its own id.
        val storedIds = state.presenceEvolutions.mapTo(HashSet()) { it.id }
        val next = state.presenceEvolutions.map { if (it.id in ids) change(it) else it } +
            a.filter { it.id !in storedIds }.map(change)
        run(SchedulerIntent.SetPresenceEvolutions(next, editKey))
    }

    Indented {
        Row(Modifier.fitsShownWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            PopupSearchField(
                placeholder = evolutionsPlaceholder + if (a.isEmpty()) "" else " · ${a.size} selected",
                state = state,
                config = evolutionsShown,
                onConfigChange = {
                    if (it.added != evolutionsConfig.added) selectedInWindow = emptyList()
                    evolutionsConfig = it
                },
                openedConfig = evolutionsOpened,
                fieldId = "$rowId/$evolutionsPlaceholder",
                modifier = Modifier.weight(1f),
                openedParts = EMBEDDED_SEARCH_SPLITS.copy(addedRemoved = false),
                // A selection replaces the one held; none (a press outside its rows, its closing) leaves it held.
                onSelectedAdded = { keys -> if (keys.isNotEmpty()) selectedInWindow = keys.filter { it in allKeys } },
            )
            // A new evolution, given to the tasks of S, and added to A.
            FrameButton("New", enabled = s.isNotEmpty()) {
                val id = PresenceEvolutions.mintId(state.presenceEvolutions.map { it.id }, target)
                run(SchedulerIntent.SetPresenceEvolutions(state.presenceEvolutions + PresenceEvolution(id, s, target = target)))
                evolutionsConfig = evolutionsConfig.copy(added = evolutionsConfig.added + evolutionKey(id))
            }
            // User rule 2026-11-20: the bin — every evolution of A deleted ([PresenceEvolutions.deleted]: a default one is
            // marked gone), and out of the field's list.
            FrameButton(if (a.size > 1) "🗑 Delete ${a.size}" else "🗑 Delete", enabled = a.isNotEmpty()) {
                val ids = a.map { it.id }
                run(SchedulerIntent.SetPresenceEvolutions(PresenceEvolutions.deleted(state, ids)))
                val keys = ids.mapTo(HashSet(), evolutionKey)
                evolutionsConfig = evolutionsConfig.copy(added = evolutionsConfig.added.filterNot { it in keys })
                selectedInWindow = selectedInWindow.filterNot { it in keys }
            }
        }
    }
    if (a.isEmpty()) {
        Indented { NoteText("Add evolutions to the list of the field above, or make a new one: the rows below edit them") }
    }
    if (a.any { PresenceEvolutions.isDefault(it, state) }) {
        Indented {
            NoteText(
                if (target == EvolutionTarget.Minimum) {
                    "The default evolution: every task of the first tree at its minimum time (the formula minimum) — the " +
                        "action \"Minimum time\" sets it. An edit here writes it as an evolution of its own, which then holds in its place"
                } else {
                    "The default evolution: every task of the first tree, relative to its root, at its priority in it (the formula " +
                        "priority) — the actions \"Priority weights\" and \"Relative priority\" set it. An edit here writes it as an " +
                        "evolution of its own, which then holds in its place"
                },
            )
        }
    }

    // --- 2. the tasks that get it
    val aTasks = a.flatMap { it.taskIds }.distinct()
    val tasksOpened = SearchDomain.Config(kinds = setOf(SearchDomain.Kind.Task))
    var tasksConfig by rememberInsideConfig(embeddedSearchId("field:$rowId/${PresenceRow.Tasks.placeholder}"), tasksOpened)
    val tasksShown = tasksConfig.copy(added = aTasks.map(SearchDomain::taskKey))
    Indented {
        PopupSearchField(
            placeholder = PresenceRow.Tasks.placeholder + if (aTasks.isEmpty()) "" else " · " + aTasks.joinToString(", ") { title(state, it) },
            state = state,
            config = tasksShown,
            onConfigChange = { next ->
                val before = tasksShown.added.toSet()
                val after = next.added.toSet()
                val gained = (after - before).mapNotNull(::taskIdOfKey)
                val lost = (before - after).mapNotNull(::taskIdOfKey).toSet()
                if (a.isNotEmpty() && (gained.isNotEmpty() || lost.isNotEmpty())) {
                    edit({ e -> e.copy(taskIds = (e.taskIds.filterNot { it in lost } + gained).distinct()) })
                }
                tasksConfig = next.copy(added = emptyList())
            },
            openedConfig = tasksOpened,
            fieldId = "$rowId/${PresenceRow.Tasks.placeholder}",
            // Its added elements are the evolutions' own (A's), not its window's: never lost on opening.
            caller = "",
            modifier = Modifier.fillMaxWidth(),
            openedParts = EMBEDDED_SEARCH_SPLITS.copy(addedRemoved = false),
            onDrop = { row -> taskIdOfKey(SearchDomain.keyOf(row))?.let { t -> edit({ e -> e.copy(taskIds = e.taskIds - t) }) } },
        )
    }

    // --- 3. relative to: the tasks above every task of A, and the parent distance — a presence ratio target's alone
    if (target == EvolutionTarget.Presence) {
    val above = remember(state.cells, state.lists, state.tasks, aTasks) { PresenceEvolutions.commonAncestors(state, aTasks) }
    val relativeOpened = SearchDomain.Config(kinds = setOf(SearchDomain.Kind.Task), sorts = SearchDomain.PICKER_SORTS, sortFollowsDefault = false)
    var relativeConfig by rememberInsideConfig(embeddedSearchId("field:$rowId/${PresenceRow.Relative.placeholder}"), relativeOpened)
    val relativeNow = a.map { PresenceEvolutions.relativeText(state, it) }.distinct()
    Indented {
        PopupSearchField(
            placeholder = PresenceRow.Relative.placeholder + when {
                a.isEmpty() -> ""
                relativeNow.size == 1 -> " · " + relativeNow.single()
                else -> " · mixed"
            },
            state = state,
            config = relativeConfig,
            onConfigChange = { relativeConfig = it },
            openedConfig = relativeOpened,
            fieldId = "$rowId/${PresenceRow.Relative.placeholder}",
            modifier = Modifier.fillMaxWidth(),
            picker = { close ->
                SearchPicker(
                    eligible = above,
                    canPick = a.isNotEmpty(),
                    onPick = { taskId ->
                        edit({ e -> e.copy(relativeTask = taskId) })
                        relativeConfig = relativeConfig.copy(query = "")
                        close()
                    },
                    onNewTask = null,
                    onCancel = close,
                    leading = { ParentDistanceRow(a) { n -> edit({ e -> e.copy(relativeTask = null, parentDistance = n) }) } },
                )
            },
        )
    }
    }

    // --- 4. the time periods
    Indented { Text(PresenceRow.Periods.placeholder, style = MaterialTheme.typography.labelMedium) }
    val periods = a.map { it.periods }.distinct().singleOrNull() ?: a.firstOrNull()?.periods.orEmpty()
    if (a.map { it.periods }.distinct().size > 1) Indented(2) { NoteText("The selected evolutions have different periods: an edit writes these on all of them") }
    if (periods.isEmpty() && a.isNotEmpty()) Indented(2) { NoteText("always") }
    periods.forEachIndexed { index, period ->
        androidx.compose.runtime.key(index, a.map { it.id }) {
            Indented(2) {
                PeriodRow(
                    from = period.fromMillis?.let { PresenceEvolutions.formatInstant(it) }.orEmpty(),
                    until = period.untilMillis?.let { PresenceEvolutions.formatInstant(it) }.orEmpty(),
                    onChange = { from, until ->
                        edit({ e -> e.copy(periods = periods.toMutableList().also { it[index] = EvolutionPeriod(from, until) }) }, "${target.name}/periods/$index")
                    },
                    onRemove = { edit({ e -> e.copy(periods = periods.filterIndexed { i, _ -> i != index }) }) },
                )
            }
        }
    }
    Indented(2) {
        FrameButton("Add a period", enabled = a.isNotEmpty()) { edit({ e -> e.copy(periods = periods + EvolutionPeriod()) }) }
    }

    // --- 5. the formula
    val formulas = a.map { it.formula }.distinct()
    Indented {
        TypedFormulaRow(
            label = PresenceRow.Formula.placeholder + if (target == EvolutionTarget.Minimum) " (minutes)" else " (the ratio, 0 to 1)",
            target = target,
            current = formulas.singleOrNull() ?: "",
            mixed = formulas.size > 1,
            enabled = a.isNotEmpty(),
            onValid = { text -> edit({ e -> e.copy(formula = text) }, "${target.name}/formula") },
        )
    }

    // --- 6. the categories
    val aKeys = a.map { evolutionKey(it.id) }
    val carried = aKeys.flatMap { ElementTree.parentsOf(state, it) }.filter { ElementTree.kindOf(it) == SearchDomain.Kind.Category }.distinct()
    val categoriesOpened = SearchDomain.Config(kinds = setOf(SearchDomain.Kind.Category))
    var categoriesConfig by rememberInsideConfig(embeddedSearchId("field:$rowId/${PresenceRow.Categories.placeholder}"), categoriesOpened)
    Indented {
        PopupSearchField(
            placeholder = PresenceRow.Categories.placeholder + if (carried.isEmpty()) "" else " · ${carried.size}",
            state = state,
            config = categoriesConfig.copy(added = carried),
            onConfigChange = { next ->
                if (aKeys.isNotEmpty()) {
                    val before = carried.toSet()
                    val after = next.added.filter { ElementTree.kindOf(it) == SearchDomain.Kind.Category }.toSet()
                    (after - before).forEach { c -> ElementTree.parentIntents(state, aKeys, c, on = true).forEach(run) }
                    (before - after).forEach { c -> ElementTree.parentIntents(state, aKeys, c, on = false).forEach(run) }
                }
                categoriesConfig = next.copy(added = emptyList())
            },
            openedConfig = categoriesOpened,
            fieldId = "$rowId/${PresenceRow.Categories.placeholder}",
            // Its added elements are the evolutions' own (A's), not its window's: never lost on opening.
            caller = "",
            modifier = Modifier.fillMaxWidth(),
            openedParts = EMBEDDED_SEARCH_SPLITS.copy(addedRemoved = false),
            onDrop = { row -> ElementTree.parentIntents(state, aKeys, SearchDomain.keyOf(row), on = false).forEach(run) },
        )
    }

    FormulaProblems(state)
}

/** User rule 2026-11-19: every incoherence of the account's formulas, a row each, and that the schedulers wait. Nothing when coherent. */
@Composable
internal fun FormulaProblems(state: SchedulerState) {
    val problems = FormulaCoherence.problems(state)
    if (problems.isEmpty()) return
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        NoteText(
            "Incoherent formulas: the schedulers do nothing until every one is resolved",
            color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.labelMedium,
        )
        for (p in problems) NoteText("• " + p.message, color = MaterialTheme.colorScheme.error)
    }
}

@Composable
private fun Indented(level: Int = 1, content: @Composable () -> Unit) {
    Box(Modifier.fillMaxWidth().padding(start = ROW_INDENT * level)) { content() }
}

/** "parent distance [n]": the relative task is each task's own ancestor n levels up. */
@Composable
private fun ParentDistanceRow(a: List<PresenceEvolution>, onSet: (Int) -> Unit) {
    val current = a.filter { it.relativeTask == null }.map { it.parentDistance }.distinct().singleOrNull()
    var draft by remember(current) { mutableStateOf(current?.toString() ?: "") }
    Row(
        Modifier.fitsShownWidth().padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("parent distance", modifier = Modifier.weight(1f))
        OutlinedTextField(
            value = draft,
            onValueChange = { typed ->
                draft = typed.filter(Char::isDigit).take(3)
                draft.toIntOrNull()?.takeIf { it >= 1 && a.isNotEmpty() }?.let(onSet)
            },
            singleLine = true,
            placeholder = { Text("n") },
            modifier = Modifier.weight(1f).leaveFocusOnOutsidePress(),
        )
    }
}

/** A period: its start and its end, each a date (blank: open); a valid edit is written at once. */
@Composable
private fun PeriodRow(from: String, until: String, onChange: (Long?, Long?) -> Unit, onRemove: () -> Unit) {
    var fromDraft by remember(from) { mutableStateOf(from) }
    var untilDraft by remember(until) { mutableStateOf(until) }
    fun commit() {
        val f = if (fromDraft.isBlank()) null else TimeFormula.FormulaPeriods.millisOf(fromDraft) ?: return
        val u = if (untilDraft.isBlank()) null else TimeFormula.FormulaPeriods.millisOf(untilDraft) ?: return
        if (f != null && u != null && u <= f) return
        onChange(f, u)
    }
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(Modifier.fitsShownWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            OutlinedTextField(
                value = fromDraft,
                onValueChange = {
                    fromDraft = it
                    commit()
                },
                singleLine = true,
                label = { Text("from") },
                placeholder = { Text("always") },
                isError = boundProblem(fromDraft) != null,
                modifier = Modifier.weight(1f).leaveFocusOnOutsidePress(),
            )
            OutlinedTextField(
                value = untilDraft,
                onValueChange = {
                    untilDraft = it
                    commit()
                },
                singleLine = true,
                label = { Text("until") },
                placeholder = { Text("forever") },
                isError = boundProblem(untilDraft) != null,
                modifier = Modifier.weight(1f).leaveFocusOnOutsidePress(),
            )
            FrameButton("Remove", onClick = onRemove)
        }
        (boundProblem(fromDraft) ?: boundProblem(untilDraft))?.let { NoteText(it, color = MaterialTheme.colorScheme.error) }
    }
}

/** The formula: written at each keystroke that reads ([TimeFormula]); what is wrong with it said under it. */
@Composable
private fun TypedFormulaRow(
    label: String,
    current: String,
    mixed: Boolean,
    enabled: Boolean,
    allowBlank: Boolean = false,
    /** A target evolution's formula: it may read the task's own value, `priority` or `minimum` (user rule 2026-11-20). */
    target: EvolutionTarget? = null,
    onValid: (String) -> Unit,
) {
    var draft by remember(current) { mutableStateOf(current) }
    val problem = TimeFormula.problem(draft, target = target)
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        OutlinedTextField(
            value = draft,
            onValueChange = {
                draft = it
                if (TimeFormula.problem(it, target = target) == null && (allowBlank || it.isNotBlank())) onValid(it.trim())
            },
            singleLine = true,
            enabled = enabled,
            label = { Text(label) },
            placeholder = { if (mixed) Text("mixed") },
            isError = problem != null,
            modifier = Modifier.fitsShownWidth().leaveFocusOnOutsidePress(),
        )
        if (problem != null) NoteText(problem, color = MaterialTheme.colorScheme.error)
        else NoteText(
            when (target) {
                EvolutionTarget.Presence -> "priority is each task's priority relative to what it is relative to, by the weights; "
                EvolutionTarget.Minimum -> "minimum is each task's minimum time, in minutes; "
                null -> ""
            } +
                "t is the instant in days; date(2026-12-01), ramp(t0, t1, a, b), step(t0, a, b), min, max, clamp, + - * / ^",
        )
    }
}

private fun boundProblem(bound: String): String? =
    if (bound.isBlank() || TimeFormula.FormulaPeriods.millisOf(bound) != null) null
    else "\"${bound.trim()}\" is no date: write 2026-12-01 or 2026-12-01 08:30"

private fun title(state: SchedulerState, id: TaskId): String = state.tasks[id]?.title.orEmpty().ifBlank { id.value }

private fun taskIdOfKey(key: String): TaskId? =
    if (ElementTree.kindOf(key) == SearchDomain.Kind.Task) TaskId(key.substringAfter('/')) else null
