package org.example.project.scheduler.domain

import kotlin.time.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import org.example.project.scheduler.model.CellId
import org.example.project.scheduler.model.TaskId
import org.example.project.scheduler.state.SchedulerState

/**
 * User rule 2026-11-19: *"If it is incoherent (for example the total of presence ratio target is over 1 at some point), a
 * message says so and the two scheduler do nothing until all incoherences are resolved."*
 *
 * **What the formulas of the instant say together**, read at every instant they may change at (their dates, their
 * periods' bounds) and between them — never at "now", so the verdict does not move as time passes and never re-plans:
 * - a formula that does not read (one a sync brought in: the reducer refuses them);
 * - two periods of one formula overlapping;
 * - a presence ratio target below 0 % or above 100 %;
 * - the presence ratio targets under one parent adding up to more than 100 %, or to less with no other task there to
 *   take the rest;
 * - a minimum length target below 0.
 *
 * While [problems] is not empty, [SchedulerDomain.fillSchedule] hands no task any time — the instant schedule and the
 * search alike — and the editor and the calendar say why.
 */
object FormulaCoherence {
    data class Problem(val message: String, val taskIds: Set<TaskId>)

    private const val DAY = 86_400_000L
    private const val EPS = 1e-9

    /** One slot keyed by the maps' identity: [SchedulerDomain.fillSchedule] and the UI ask on every state. */
    private class Memo(val cells: Any, val lists: Any, val tasks: Any, val zone: String, val problems: List<Problem>)

    private var memo: Memo? = null

    fun isCoherent(state: SchedulerState): Boolean = problems(state).isEmpty()

    /**
     * The task elements a presence ratio target of [taskIds] can be relative to: the parents of their places in the tree,
     * by title. The editor's "Relative to" field.
     */
    fun parentsOf(state: SchedulerState, taskIds: Collection<TaskId>): List<TaskId> {
        val ids = taskIds.toHashSet()
        return state.cells.values.asSequence()
            .filter { it.taskId in ids && SchedulerDomain.isPopulatedCell(state, it.id) }
            .mapNotNull { SchedulerDomain.parentTaskIdOfList(state, it.parentListId) }
            .distinct()
            .sortedWith(compareBy<TaskId> { state.tasks[it]?.title.orEmpty() }.thenBy { it.value })
            .toList()
    }

    /** The places of [taskIds] directly under [parent]: what a presence ratio target relative to [parent] is written on. */
    fun placesUnder(state: SchedulerState, taskIds: Collection<TaskId>, parent: TaskId): List<CellId> {
        val ids = taskIds.toHashSet()
        return state.cells.values
            .filter { it.taskId in ids && SchedulerDomain.isPopulatedCell(state, it.id) && SchedulerDomain.parentTaskIdOfList(state, it.parentListId) == parent }
            .sortedBy { it.id.value }
            .map { it.id }
    }

    fun problems(state: SchedulerState, zone: TimeZone = TimeZone.currentSystemDefault()): List<Problem> {
        val known = memo
        if (known != null && known.cells === state.cells && known.lists === state.lists && known.tasks === state.tasks && known.zone == zone.id) {
            return known.problems
        }
        val computed = compute(state, zone)
        memo = Memo(state.cells, state.lists, state.tasks, zone.id, computed)
        return computed
    }

    private fun compute(state: SchedulerState, zone: TimeZone): List<Problem> {
        if (state.cells.values.none { it.presenceFormula.isNotBlank() } && state.tasks.values.none { it.minimumFormula.isNotBlank() }) {
            return emptyList()
        }
        val out = ArrayList<Problem>()
        fun title(id: TaskId?) = id?.let { state.tasks[it]?.title }?.takeIf { it.isNotBlank() } ?: "?"
        fun when_(millis: Long) = Instant.fromEpochMilliseconds(millis).toLocalDateTime(zone).let {
            "${it.date} ${it.hour.toString().padStart(2, '0')}:${it.minute.toString().padStart(2, '0')}"
        }
        fun pct(v: Double) = "${kotlin.math.round(v * 1000.0) / 10.0} %"

        // --- presence ratio targets, one parent's sub-list at a time
        val populatedCells = state.cells.values.filter { it.taskId != null && SchedulerDomain.isPopulatedCell(state, it.id) }
        val byList = populatedCells.groupBy { it.parentListId }
        for ((listId, cells) in byList.entries.sortedBy { it.key.value }) {
            val withFormula = cells.filter { it.presenceFormula.isNotBlank() }
            if (withFormula.isEmpty()) continue
            val parent = title(SchedulerDomain.parentTaskIdOfList(state, listId))
            val parsed = HashMap<CellId, TimeFormula.Parsed>()
            for (cell in withFormula.sortedBy { it.id.value }) {
                val what = "The presence ratio target of “${title(cell.taskId)}” under “$parent”"
                val problem = TimeFormula.problem(cell.presenceFormula, zone)
                val p = TimeFormula.parse(cell.presenceFormula, zone)
                if (problem != null || p == null) {
                    out += Problem("$what does not read: ${problem ?: "not a formula"}", setOfNotNull(cell.taskId))
                    continue
                }
                overlap(cell.presenceFormula, zone)?.let { out += Problem("$what has periods that overlap from ${when_(it)}", setOfNotNull(cell.taskId)) }
                parsed[cell.id] = p
            }
            if (parsed.isEmpty()) continue
            val freeExists = cells.any { it.presenceFormula.isBlank() }
            val ids = withFormula.mapNotNull { it.taskId }.toSet()
            val rangeReported = HashSet<CellId>()
            var overReported = false
            var underReported = false
            for (at in samples(parsed.values)) {
                var sum = 0.0
                var any = false
                for ((cellId, p) in parsed) {
                    val v = p.at(at) ?: continue
                    any = true
                    if ((v < -EPS || v > 1.0 + EPS) && rangeReported.add(cellId)) {
                        out += Problem(
                            "The presence ratio target of “${title(state.cells[cellId]?.taskId)}” under “$parent” is ${pct(v)} on ${when_(at)}: a ratio is between 0 and 100 %",
                            setOfNotNull(state.cells[cellId]?.taskId),
                        )
                    }
                    sum += v
                }
                if (!any) continue
                if (sum > 1.0 + EPS && !overReported) {
                    overReported = true
                    out += Problem("The presence ratio targets under “$parent” add up to ${pct(sum)} on ${when_(at)}: more than 100 %", ids)
                }
                if (sum < 1.0 - EPS && !freeExists && !underReported) {
                    underReported = true
                    out += Problem(
                        "The presence ratio targets under “$parent” add up to ${pct(sum)} on ${when_(at)}, and no other task under it takes the rest",
                        ids,
                    )
                }
                if (overReported && (underReported || freeExists) && rangeReported.size == parsed.size) break
            }
        }

        // --- minimum length targets
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
