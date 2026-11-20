package org.example.project.scheduler.domain

import kotlin.time.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import org.example.project.scheduler.model.TaskId
import org.example.project.scheduler.state.SchedulerState

/**
 * User rule 2026-11-19: *"If it is incoherent (for example the total of presence ratio target is over 1 at some point), a
 * message says so and the two scheduler do nothing until all incoherences are resolved."*
 *
 * **What the formulas of the instant say together**, read at every instant they may change at (their dates, their
 * periods' bounds) and between them — never at "now", so the verdict does not move as time passes and never re-plans:
 * - a formula that does not read (one a sync brought in);
 * - a presence ratio target evolution relative to a task that is not above one of its tasks, or to a parent distance
 *   one of them has no ancestor at;
 * - a presence ratio target below 0 % or above 100 %, or two evolutions giving one task a target relative to one task
 *   at once, or a task and a task inside it both given one relative to the same task;
 * - the presence ratio targets relative to one task adding up to more than 100 %, or to less with no other task under
 *   it to take the rest;
 * - two periods of one minimum length target overlapping, or a minimum below 0.
 *
 * While [problems] is not empty, [SchedulerDomain.fillSchedule] hands no task any time — the instant schedule and the
 * search alike — and the actions and the calendar say why.
 */
object FormulaCoherence {
    data class Problem(val message: String, val taskIds: Set<TaskId>)

    private const val DAY = 86_400_000L
    private const val EPS = 1e-9

    /** One slot keyed by the maps' identity: [SchedulerDomain.fillSchedule] and the UI ask on every state. */
    private class Memo(val cells: Any, val lists: Any, val tasks: Any, val evolutions: Any, val zone: String, val problems: List<Problem>)

    private var memo: Memo? = null

    fun isCoherent(state: SchedulerState): Boolean = problems(state).isEmpty()

    fun problems(state: SchedulerState, zone: TimeZone = TimeZone.currentSystemDefault()): List<Problem> {
        val known = memo
        if (known != null && known.cells === state.cells && known.lists === state.lists && known.tasks === state.tasks &&
            known.evolutions === state.presenceEvolutions && known.zone == zone.id
        ) {
            return known.problems
        }
        val computed = compute(state, zone)
        memo = Memo(state.cells, state.lists, state.tasks, state.presenceEvolutions, zone.id, computed)
        return computed
    }

    private fun compute(state: SchedulerState, zone: TimeZone): List<Problem> {
        if (state.presenceEvolutions.none { it.formula.isNotBlank() } && state.tasks.values.none { it.minimumFormula.isNotBlank() }) {
            return emptyList()
        }
        val out = ArrayList<Problem>()
        fun title(id: TaskId?) = id?.let { state.tasks[it]?.title }?.takeIf { it.isNotBlank() } ?: "?"
        fun when_(millis: Long) = Instant.fromEpochMilliseconds(millis).toLocalDateTime(zone).let {
            "${it.date} ${it.hour.toString().padStart(2, '0')}:${it.minute.toString().padStart(2, '0')}"
        }
        fun pct(v: Double) = "${kotlin.math.round(v * 1000.0) / 10.0} %"

        // --- presence ratio target evolutions
        val index = PresenceEvolutions.TreeIndex(state)
        val live = state.presenceEvolutions.filter { it.formula.isNotBlank() && it.target == EvolutionTarget.Presence }
        val parsed = HashMap<String, TimeFormula.Parsed>()
        for (e in live.sortedBy { it.id }) {
            val tasks = e.taskIds.filter { it in index.parents }
            val what = "The presence ratio target evolution of " + tasks.joinToString(", ") { "“${title(it)}”" }.ifEmpty { "no task" }
            val problem = TimeFormula.problem(e.formula, zone, target = e.target)
            val p = TimeFormula.parse(PresenceEvolutions.effectiveText(e), zone, target = e.target)
            if (problem != null || p == null) {
                out += Problem("$what does not read: ${problem ?: "not a formula"}", tasks.toSet())
                continue
            }
            parsed[e.id] = p
            for (t in tasks) {
                val above = index.ancestors(t)
                val relatives = PresenceEvolutions.relativesOf(index, e, t)
                when {
                    e.relativeTask != null && e.relativeTask !in above ->
                        out += Problem("“${title(e.relativeTask)}” is not above “${title(t)}”: its presence ratio target cannot be relative to it", setOf(t))
                    e.relativeTask == null && relatives.isEmpty() ->
                        out += Problem("“${title(t)}” has no task ${e.parentDistance} levels above it: its presence ratio target is relative to nothing", setOf(t))
                }
            }
        }
        if (parsed.isNotEmpty()) {
            val samples = samples(parsed.values)
            val reported = HashSet<String>()
            fun report(key: String, problem: () -> Problem) {
                if (reported.add(key)) out += problem()
            }
            for (at in samples) {
                // A target inside another relative to the same task is one relative to that task ([resolveNested]).
                val weights by lazy { SchedulerDomain.absoluteTaskPriorities(state, at, withEvolutions = false) }
                val targets = PresenceEvolutions.resolveNested(
                    PresenceEvolutions.targetsAt(state, index, at) { weights }.filter { it.evolutionId in parsed }, index,
                )
                for (tg in targets) {
                    if (tg.ratio < -EPS || tg.ratio > 1.0 + EPS) {
                        report("range/${tg.evolutionId}") {
                            Problem("The presence ratio target of “${title(tg.task)}” relative to “${title(tg.relative)}” is ${pct(tg.ratio)} on ${when_(at)}: a ratio is between 0 and 100 %", setOf(tg.task))
                        }
                    }
                }
                for ((rel, list) in targets.groupBy { it.relative }) {
                    val byTask = list.groupBy { it.task }
                    for ((t, same) in byTask) {
                        if (same.map { it.evolutionId }.distinct().size > 1) {
                            report("twice/$rel/$t") {
                                Problem("Two evolutions give “${title(t)}” a presence ratio target relative to “${title(rel)}” on ${when_(at)}", setOf(t))
                            }
                        }
                    }
                    val subOf = byTask.keys.associateWith { index.descendants(it) }
                    val sum = byTask.values.sumOf { same -> same.maxOf { it.ratio } }
                    if (sum > 1.0 + EPS) {
                        report("over/$rel") {
                            Problem("The presence ratio targets relative to “${title(rel)}” add up to ${pct(sum)} on ${when_(at)}: more than 100 %", byTask.keys)
                        }
                    } else if (sum < 1.0 - EPS) {
                        val inside = byTask.keys.flatMapTo(HashSet()) { subOf.getValue(it) + it }
                        val freeLeaf = index.descendants(rel).any { it !in inside && index.isLeaf(it) }
                        if (!freeLeaf) {
                            report("under/$rel") {
                                Problem("The presence ratio targets relative to “${title(rel)}” add up to ${pct(sum)} on ${when_(at)}, and no other task under it takes the rest", byTask.keys)
                            }
                        }
                    }
                }
            }
        }

        // --- minimum length target evolutions (user rule 2026-11-20): each reads, never below 0, and one at a time per task
        val minimums = state.presenceEvolutions.filter { it.formula.isNotBlank() && it.target == EvolutionTarget.Minimum }
        val minimumParsed = HashMap<String, TimeFormula.Parsed>()
        for (e in minimums.sortedBy { it.id }) {
            val tasks = e.taskIds.filter { state.tasks[it]?.title?.isNotBlank() == true }
            val what = "The minimum length target evolution of " + tasks.joinToString(", ") { "“${title(it)}”" }.ifEmpty { "no task" }
            val problem = TimeFormula.problem(e.formula, zone, target = e.target)
            val p = TimeFormula.parse(PresenceEvolutions.effectiveText(e), zone, target = e.target)
            if (problem != null || p == null) {
                out += Problem("$what does not read: ${problem ?: "not a formula"}", tasks.toSet())
                continue
            }
            minimumParsed[e.id] = p
        }
        if (minimumParsed.isNotEmpty()) {
            val reported = HashSet<String>()
            val byTask = HashMap<TaskId, MutableList<PresenceEvolution>>()
            for (e in minimums) if (e.id in minimumParsed) for (t in e.taskIds) byTask.getOrPut(t) { ArrayList() } += e
            for (at in samples(minimumParsed.values)) for ((t, list) in byTask) {
                val task = state.tasks[t]?.takeIf { it.title.isNotBlank() } ?: continue
                val values = list.mapNotNull { e -> minimumParsed.getValue(e.id).at(at, task.minimumMinutes.toDouble())?.let { e to it } }
                if (values.size > 1 && reported.add("twice/$t")) {
                    out += Problem("Two evolutions give “${task.title}” a minimum length target on ${when_(at)}", setOf(t))
                }
                values.firstOrNull { it.second < -EPS }?.let { (_, v) ->
                    if (reported.add("negative/$t")) out += Problem("The minimum length target of “${task.title}” is $v min on ${when_(at)}: below 0", setOf(t))
                }
            }
        }

        // --- minimum length targets an older build wrote on a task (lifted into evolutions on decode)
        for (task in state.tasks.values.filter { it.minimumFormula.isNotBlank() && it.title.isNotBlank() }.sortedBy { it.id.value }) {
            val what = "The minimum length target of “${task.title}”"
            val problem = TimeFormula.problem(task.minimumFormula, zone)
            val p = TimeFormula.parse(task.minimumFormula, zone)
            if (problem != null || p == null) {
                out += Problem("$what does not read: ${problem ?: "not a formula"}", setOf(task.id))
                continue
            }
            overlap(task.minimumFormula, zone)?.let { out += Problem("$what has periods that overlap from ${when_(it)}", setOf(task.id)) }
            samples(listOf(p)).firstOrNull { (p.at(it) ?: 0.0) < -EPS }?.let {
                out += Problem("$what is ${p.at(it)} min on ${when_(it)}: below 0", setOf(task.id))
            }
        }
        return out
    }

    /** The first instant two periods of [text] overlap at, or null. Open bounds are -∞ / +∞. */
    private fun overlap(text: String, zone: TimeZone): Long? {
        val periods = TimeFormula.FormulaPeriods.read(text)
        if (periods.size < 2) return null
        val spans = periods.map {
            (TimeFormula.FormulaPeriods.millisOf(it.from, zone) ?: Long.MIN_VALUE) to (TimeFormula.FormulaPeriods.millisOf(it.until, zone) ?: Long.MAX_VALUE)
        }
        var first: Long? = null
        for (i in spans.indices) for (j in i + 1 until spans.size) {
            val from = maxOf(spans[i].first, spans[j].first)
            val until = minOf(spans[i].second, spans[j].second)
            if (from < until) first = minOf(first ?: from, from)
        }
        return first
    }

    /**
     * The instants the formulas are read at: every breakpoint, the instant before it, and points between consecutive ones
     * and around the outer ones. No breakpoint at all: a fixed grid of 2020 to 2045 — never "now".
     */
    private fun samples(formulas: Collection<TimeFormula.Parsed>): List<Long> {
        val bps = formulas.flatMap { it.breakpoints }.distinct().sorted()
        val moving = formulas.any { !it.constant }
        if (bps.isEmpty()) {
            if (!moving) return listOf(0L)
            val start = 18_262L * DAY // 2020-01-01
            return (0 until 25 * 122).map { start + it * 3L * DAY }
        }
        val out = ArrayList<Long>()
        for (d in listOf(365L, 30L, 7L, 1L)) out += bps.first() - d * DAY
        for ((i, b) in bps.withIndex()) {
            out += b - 60_000L
            out += b
            val next = bps.getOrNull(i + 1) ?: continue
            if (moving) for (k in 1..8) out += b + (next - b) / 9 * k else out += b + (next - b) / 2
        }
        for (d in listOf(1L, 7L, 30L, 365L)) out += bps.last() + d * DAY
        return out
    }
}
