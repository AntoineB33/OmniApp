package org.example.project.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import org.example.project.scheduler.domain.FormulaCoherence
import org.example.project.scheduler.domain.SchedulerDomain
import org.example.project.scheduler.domain.TimeFormula
import org.example.project.scheduler.model.TaskId
import org.example.project.scheduler.state.SchedulerIntent
import org.example.project.scheduler.state.SchedulerState

/** Which number of a task element [FormulaRuleEditor] writes the evolution of. */
internal enum class FormulaTarget { Presence, Minimum }

/**
 * User rule 2026-11-19, the action section of a task element: *"the user must be able to change the formula for the
 * evolution of the presence (or minimum length) ratio target"* —
 * - **the tasks it is written on**: every task selected, each removable, and a field to add others to that selection;
 * - **the task element it is relative to** (presence ratio target only): a parent of those tasks' places;
 * - **the time periods**, each a start and an end (blank: open) and **its formula** ([TimeFormula]);
 * - **what is incoherent** ([FormulaCoherence]) — while anything is, both schedulers do nothing, and this says so.
 *
 * Applied as ONE formula per place ([TimeFormula.FormulaPeriods.build]: `periods(…)` for more than one period).
 */
@Composable
internal fun FormulaRuleEditor(
    state: SchedulerState,
    target: FormulaTarget,
    initialTaskIds: List<TaskId>,
    nowMillis: Long,
    run: (SchedulerIntent) -> Unit,
) {
    // The editor's own selection, seeded from the added tasks: a task added or removed here leaves the added list as it is.
    var selection by remember(initialTaskIds) { mutableStateOf(initialTaskIds.distinct()) }
    val selected = selection.filter { state.tasks[it] != null }
    var relativeTo by remember(selected, target) { mutableStateOf<TaskId?>(null) }
    val parents = if (target == FormulaTarget.Presence) FormulaCoherence.parentsOf(state, selected) else emptyList()
    val parent = relativeTo?.takeIf { it in parents } ?: parents.firstOrNull()
    val places = parent?.let { FormulaCoherence.placesUnder(state, selected, it) }.orEmpty()

    // What the targets hold now: one formula they share, or "mixed".
    val currentTexts = when (target) {
        FormulaTarget.Presence -> places.mapNotNull { state.cells[it]?.presenceFormula }
        FormulaTarget.Minimum -> selected.mapNotNull { state.tasks[it]?.minimumFormula }
    }.distinct()
    val shared = currentTexts.singleOrNull()
    var periods by remember(shared, parent, selected, target) {
        mutableStateOf(TimeFormula.FormulaPeriods.read(shared.orEmpty()).ifEmpty { listOf(TimeFormula.Period("", "", "")) })
    }

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        // --- the tasks selected, and the field adding others
        Text("Tasks", style = MaterialTheme.typography.labelMedium)
        if (selected.isEmpty()) NoteText("No task selected")
        for (id in selected) {
            Row(Modifier.fitsShownWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(state.tasks[id]?.title.orEmpty().ifBlank { id.value }, modifier = Modifier.weight(1f))
                FrameButton("Remove") { selection = selection - id }
            }
        }
        TaskAdder(state, exclude = selected.toSet()) { selection = selection + it }

        // --- the task element the formula is relative to
        if (target == FormulaTarget.Presence) {
            Text("Relative to", style = MaterialTheme.typography.labelMedium)
            when {
                parents.isEmpty() -> NoteText("The selected tasks are in no tree")
                else -> Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    for (p in parents) {
                        Row(
                            Modifier.fitsShownWidth().clickable { relativeTo = p }.padding(vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            ToggleChip(if (p == parent) "●" else "○", p == parent) { relativeTo = p }
                            Text(state.tasks[p]?.title.orEmpty().ifBlank { p.value }, modifier = Modifier.weight(1f))
                        }
                    }
                    val missing = selected.filter { id -> places.none { state.cells[it]?.taskId == id } }
                    if (missing.isNotEmpty()) {
                        NoteText("Not directly under it, so left as they are: " + missing.joinToString(", ") { "“${state.tasks[it]?.title.orEmpty()}”" })
                    }
                }
            }
        }

        // --- the time periods and their formulas
        Text(
            if (target == FormulaTarget.Presence) "Periods and formulas (the ratio, 0 to 1)" else "Periods and formulas (minutes)",
            style = MaterialTheme.typography.labelMedium,
        )
        if (shared == null && currentTexts.isNotEmpty()) NoteText("The selected tasks hold different formulas: applying writes this one on all of them")
        periods.forEachIndexed { index, period ->
            androidx.compose.runtime.key(index) {
                PeriodRow(
                    period = period,
                    removable = periods.size > 1,
                    onChange = { changed -> periods = periods.toMutableList().also { it[index] = changed } },
                    onRemove = { periods = periods.toMutableList().also { it.removeAt(index) } },
                )
            }
        }
        FrameButton("Add a period") { periods = periods + TimeFormula.Period("", "", "") }

        val built = TimeFormula.FormulaPeriods.build(periods)
        val problem = periods.firstNotNullOfOrNull { periodProblem(it) } ?: TimeFormula.problem(built)
        val canApply = problem == null && when (target) {
            FormulaTarget.Presence -> places.isNotEmpty()
            FormulaTarget.Minimum -> selected.isNotEmpty()
        }
        fun apply(text: String) = when (target) {
            FormulaTarget.Presence -> run(SchedulerIntent.SetCellsPresenceFormula(places, text))
            FormulaTarget.Minimum -> run(SchedulerIntent.SetTasksMinimumFormula(selected, text))
        }
        Row(Modifier.fitsShownWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            FrameButton("Apply", enabled = canApply && built != shared) { apply(built) }
            FrameButton(
                if (target == FormulaTarget.Presence) "Clear (weights decide)" else "Clear (constant minimum)",
                enabled = canApply && currentTexts.any { it.isNotBlank() },
            ) { apply("") }
        }
        if (problem != null) NoteText(problem, color = MaterialTheme.colorScheme.error)
        else if (built.isNotBlank()) NoteText("Written as: $built")

        // --- what each task's number is now
        when (target) {
            FormulaTarget.Presence -> {
                val priorities = remember(state.cells, state.lists, state.tasks, nowMillis / 60_000L) { SchedulerDomain.absoluteTaskPriorities(state, nowMillis) }
                for (cellId in places) {
                    val cell = state.cells[cellId] ?: continue
                    val title = cell.taskId?.let { state.tasks[it] }?.title.orEmpty()
                    val ratio = SchedulerDomain.presenceRatioAt(cell, nowMillis)
                    NoteText(
                        "“$title” now: " + (ratio?.let { "${formatPercent(it)} of “${parent?.let { state.tasks[it] }?.title.orEmpty()}”" } ?: "its weights decide") +
                            " · ${formatPercent(cell.taskId?.let { priorities[it] } ?: 0.0)} of the whole",
                    )
                    if (cell.weightFormula.isNotBlank()) {
                        Row(Modifier.fitsShownWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            NoteText("It also has a weight formula: ${cell.weightFormula}", modifier = Modifier.weight(1f))
                            FrameButton("Clear it") { run(SchedulerIntent.SetCellWeightFormula(cellId, "")) }
                        }
                    }
                }
            }
            FormulaTarget.Minimum -> for (id in selected) {
                val task = state.tasks[id] ?: continue
                NoteText("“${task.title}” now: ${formatMinutes(SchedulerDomain.minimumMinutesAt(task, nowMillis))} min")
            }
        }
        NoteText("t is the instant in days; date(2026-12-01), ramp(t0, t1, a, b), step(t0, a, b), min, max, clamp, + - * / ^. A date is 2026-12-01 or 2026-12-01 08:30; a blank one is open.")

        FormulaProblems(state)
    }
}

/** User rule 2026-11-19: every incoherence of the account's formulas, and that the schedulers wait for it. Nothing when coherent. */
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
private fun PeriodRow(period: TimeFormula.Period, removable: Boolean, onChange: (TimeFormula.Period) -> Unit, onRemove: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(Modifier.fitsShownWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            OutlinedTextField(
                value = period.from,
                onValueChange = { onChange(period.copy(from = it)) },
                singleLine = true,
                label = { Text("From") },
                placeholder = { Text("always") },
                isError = boundProblem(period.from) != null,
                modifier = Modifier.weight(1f).leaveFocusOnOutsidePress(),
            )
            OutlinedTextField(
                value = period.until,
                onValueChange = { onChange(period.copy(until = it)) },
                singleLine = true,
                label = { Text("Until") },
                placeholder = { Text("forever") },
                isError = boundProblem(period.until) != null,
                modifier = Modifier.weight(1f).leaveFocusOnOutsidePress(),
            )
            if (removable) FrameButton("Remove", onClick = onRemove)
        }
        OutlinedTextField(
            value = period.formula,
            onValueChange = { onChange(period.copy(formula = it)) },
            singleLine = true,
            label = { Text("Formula") },
            isError = TimeFormula.problem(period.formula) != null,
            modifier = Modifier.fitsShownWidth().leaveFocusOnOutsidePress(),
        )
    }
}

/** A task to add to the editor's selection, found by its title as it is typed. */
@Composable
private fun TaskAdder(state: SchedulerState, exclude: Set<TaskId>, onAdd: (TaskId) -> Unit) {
    var query by remember { mutableStateOf("") }
    OutlinedTextField(
        value = query,
        onValueChange = { query = it },
        singleLine = true,
        label = { Text("Add a task") },
        modifier = Modifier.fitsShownWidth().leaveFocusOnOutsidePress(),
    )
    val q = query.trim()
    if (q.isEmpty()) return
    val matches = remember(state.tasks, q, exclude) {
        val inTree = SchedulerDomain.tasksInTree(state)
        state.tasks.values.asSequence()
            .filter { it.id in inTree && it.id !in exclude && it.title.contains(q, ignoreCase = true) }
            .sortedBy { it.title.length }
            .take(8)
            .toList()
    }
    if (matches.isEmpty()) NoteText("No task named so")
    for (task in matches) {
        Text(
            task.title,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.fitsShownWidth().clickable {
                onAdd(task.id)
                query = ""
            }.padding(vertical = 2.dp),
        )
    }
}

private fun boundProblem(bound: String): String? =
    if (bound.isBlank() || TimeFormula.FormulaPeriods.millisOf(bound) != null) null
    else "\"${bound.trim()}\" is no date: write 2026-12-01 or 2026-12-01 08:30"

private fun periodProblem(period: TimeFormula.Period): String? {
    boundProblem(period.from)?.let { return it }
    boundProblem(period.until)?.let { return it }
    val from = TimeFormula.FormulaPeriods.millisOf(period.from)
    val until = TimeFormula.FormulaPeriods.millisOf(period.until)
    if (from != null && until != null && until <= from) return "a period ends before it starts"
    if (period.formula.isBlank()) return "a period has no formula"
    return TimeFormula.problem(period.formula)
}

private fun formatPercent(fraction: Double): String = "${kotlin.math.round(fraction * 1000.0) / 10.0} %"

private fun formatMinutes(value: Double): String =
    if (value == kotlin.math.floor(value)) value.toLong().toString() else (kotlin.math.round(value * 100.0) / 100.0).toString()
