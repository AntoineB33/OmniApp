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

/** The six rows' grey texts — each row's name. */
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
 * - **the evolutions** — a field opening a Search window of the presence ratio target evolutions involving a task of S,
 *   its added elements list A showing: what is added there is what the rows below edit (and a row making a new one, for
 *   S, added to A);
 * - **the tasks that get it** — a field opening a Search window of tasks whose added elements are the tasks of A's
 *   evolutions: added there, a task gets them; dropped, it no longer does;
 * - **relative to** — a field opening the tasks above every task of A, a click making one the relative task, and at the
 *   top a row "parent distance [n]" whose field makes it each task's own ancestor n levels up;
 * - **the time periods**, **the formula** ([TimeFormula]: the ratio, 0 to 1), **the categories** (a field opening the
 *   categories, the added ones those the evolutions carry);
 * - then each incoherence ([FormulaCoherence]), a row each, while the schedulers wait.
 */
@Composable
internal fun PresenceEvolutionRows(
    state: SchedulerState,
    added: List<SearchDomain.Result>,
    rowId: String,
    run: (SchedulerIntent) -> Unit,
) {
    val s = SearchDomain.addedTaskIds(state, added)
    val evolutionKey = { id: String -> SearchDomain.Kind.PresenceEvolution.name + "/" + id }
    val involving = PresenceEvolutions.involving(state, s)
    val involvingKeys = involving.mapTo(HashSet()) { evolutionKey(it.id) }
    val others = PresenceEvolutions.shown(state).map { evolutionKey(it.id) }.filterNot { it in involvingKeys }.toSet()

    // --- 1. the evolutions, and their added elements list A
    val evolutionsOpened = SearchDomain.Config(kinds = setOf(SearchDomain.Kind.PresenceEvolution), filters = SearchDomain.Filters(excludedElements = others))
    var evolutionsConfig by rememberInsideConfig(embeddedSearchId("field:$rowId/${PresenceRow.Evolutions.placeholder}"), evolutionsOpened)
    val evolutionsShown = evolutionsConfig.copy(
        kinds = setOf(SearchDomain.Kind.PresenceEvolution),
        filters = evolutionsConfig.filters.copy(excludedElements = others),
        added = evolutionsConfig.added.filter { it in involvingKeys },
    )
    val a: List<PresenceEvolution> = evolutionsShown.added.mapNotNull { key -> involving.firstOrNull { evolutionKey(it.id) == key } }
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
                placeholder = PresenceRow.Evolutions.placeholder + if (a.isEmpty()) "" else " · ${a.size} selected",
                state = state,
                config = evolutionsShown,
                onConfigChange = { evolutionsConfig = it },
                openedConfig = evolutionsOpened,
                fieldId = "$rowId/${PresenceRow.Evolutions.placeholder}",
                modifier = Modifier.weight(1f),
                openedParts = EMBEDDED_SEARCH_SPLITS.copy(addedRemoved = false),
            )
            // A new evolution, given to the tasks of S, and added to A.
            FrameButton("New", enabled = s.isNotEmpty()) {
                val id = PresenceEvolutions.mintId(state.presenceEvolutions.map { it.id })
                run(SchedulerIntent.SetPresenceEvolutions(state.presenceEvolutions + PresenceEvolution(id, s)))
                evolutionsConfig = evolutionsConfig.copy(added = evolutionsConfig.added + evolutionKey(id))
            }
        }
    }
    if (a.isEmpty()) {
        Indented { NoteText("Add evolutions to the list of the field above, or make a new one: the rows below edit them") }
    }
    if (a.any { PresenceEvolutions.isDefault(it, state) }) {
        Indented {
            NoteText(
                "A default evolution is the task's absolute priority, constant: the actions \"Priority weights\" and " +
                    "\"Relative priority\" set it. An edit here writes it as an evolution of its own, which then holds in its place",
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
            modifier = Modifier.fillMaxWidth(),
            openedParts = EMBEDDED_SEARCH_SPLITS.copy(addedRemoved = false),
            onDrop = { row -> taskIdOfKey(SearchDomain.keyOf(row))?.let { t -> edit({ e -> e.copy(taskIds = e.taskIds - t) }) } },
        )
    }

    // --- 3. relative to: the tasks above every task of A, and the parent distance
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
                        edit({ e -> e.copy(periods = periods.toMutableList().also { it[index] = EvolutionPeriod(from, until) }) }, "presence/periods/$index")
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
            label = PresenceRow.Formula.placeholder + " (the ratio, 0 to 1)",
            current = formulas.singleOrNull() ?: "",
            mixed = formulas.size > 1,
            enabled = a.isNotEmpty(),
            onValid = { text -> edit({ e -> e.copy(formula = text) }, "presence/formula") },
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
            modifier = Modifier.fillMaxWidth(),
            openedParts = EMBEDDED_SEARCH_SPLITS.copy(addedRemoved = false),
            onDrop = { row -> ElementTree.parentIntents(state, aKeys, SearchDomain.keyOf(row), on = false).forEach(run) },
        )
    }

    FormulaProblems(state)
}

/**
 * User rule 2026-11-19: "Minimum length target", rows only: the time periods, then the formula (minutes) — written as ONE
 * formula of the instant on every added task ([TimeFormula.FormulaPeriods]).
 */
@Composable
internal fun MinimumFormulaRows(state: SchedulerState, taskIds: List<TaskId>, run: (SchedulerIntent) -> Unit) {
    val texts = taskIds.mapNotNull { state.tasks[it]?.minimumFormula }.distinct()
    val read = TimeFormula.FormulaPeriods.read(texts.singleOrNull().orEmpty())
    val formula = read.firstOrNull()?.formula.orEmpty()
    val spans = read.filter { it.from.isNotBlank() || it.until.isNotBlank() }.map { it.from to it.until }
    fun write(nextSpans: List<Pair<String, String>>, nextFormula: String, editKey: String?) {
        val text = if (nextFormula.isBlank()) "" else TimeFormula.FormulaPeriods.build(
            if (nextSpans.isEmpty()) listOf(TimeFormula.Period("", "", nextFormula)) else nextSpans.map { TimeFormula.Period(it.first, it.second, nextFormula) },
        )
        if (TimeFormula.problem(text) == null) run(SchedulerIntent.SetTasksMinimumFormula(taskIds, text))
    }
    Indented { Text(PresenceRow.Periods.placeholder, style = MaterialTheme.typography.labelMedium) }
    if (texts.size > 1) Indented(2) { NoteText("The added tasks have different formulas: an edit writes these on all of them") }
    if (spans.isEmpty()) Indented(2) { NoteText("always") }
    spans.forEachIndexed { index, (from, until) ->
        androidx.compose.runtime.key(index) {
            Indented(2) {
                PeriodRow(
                    from = from,
                    until = until,
                    onChange = { f, u ->
                        write(spans.toMutableList().also { it[index] = (f?.let { m -> PresenceEvolutions.formatInstant(m) }.orEmpty()) to (u?.let { m -> PresenceEvolutions.formatInstant(m) }.orEmpty()) }, formula, null)
                    },
                    onRemove = { write(spans.filterIndexed { i, _ -> i != index }, formula, null) },
                )
            }
        }
    }
    Indented(2) {
        FrameButton("Add a period", enabled = formula.isNotBlank()) { write(spans + ("" to ""), formula, null) }
    }
    Indented {
        TypedFormulaRow(
            label = PresenceRow.Formula.placeholder + " (minutes; blank: the constant minimum)",
            current = formula,
            mixed = texts.size > 1,
            enabled = taskIds.isNotEmpty(),
            allowBlank = true,
            onValid = { write(spans, it, null) },
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
private fun TypedFormulaRow(label: String, current: String, mixed: Boolean, enabled: Boolean, allowBlank: Boolean = false, onValid: (String) -> Unit) {
    var draft by remember(current) { mutableStateOf(current) }
    val problem = TimeFormula.problem(draft)
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        OutlinedTextField(
            value = draft,
            onValueChange = {
                draft = it
                if (TimeFormula.problem(it) == null && (allowBlank || it.isNotBlank())) onValid(it.trim())
            },
            singleLine = true,
            enabled = enabled,
            label = { Text(label) },
            placeholder = { if (mixed) Text("mixed") },
            isError = problem != null,
            modifier = Modifier.fitsShownWidth().leaveFocusOnOutsidePress(),
        )
        if (problem != null) NoteText(problem, color = MaterialTheme.colorScheme.error)
        else NoteText("t is the instant in days; date(2026-12-01), ramp(t0, t1, a, b), step(t0, a, b), min, max, clamp, + - * / ^")
    }
}

private fun boundProblem(bound: String): String? =
    if (bound.isBlank() || TimeFormula.FormulaPeriods.millisOf(bound) != null) null
    else "\"${bound.trim()}\" is no date: write 2026-12-01 or 2026-12-01 08:30"

private fun title(state: SchedulerState, id: TaskId): String = state.tasks[id]?.title.orEmpty().ifBlank { id.value }

private fun taskIdOfKey(key: String): TaskId? =
    if (ElementTree.kindOf(key) == SearchDomain.Kind.Task) TaskId(key.substringAfter('/')) else null
