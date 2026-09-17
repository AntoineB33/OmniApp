package org.example.project.scheduler.domain

import org.example.project.scheduler.model.AlternativeSpan
import org.example.project.scheduler.model.CycleRun
import org.example.project.scheduler.model.RulePlacement
import org.example.project.scheduler.model.ScheduleCycle
import org.example.project.scheduler.model.TaskId

/**
 * `docs/scheduler_score.md`: **the schedule the rules give, from the now-line to the
 * horizon** — the driver [SchedulerDomain.fillSchedule] hands OmniApp's world to.
 *
 * It maps that world onto the score's inputs and back:
 * - the **rule state** `R(x)` at each position of the line ([Input.ruleState]);
 * - the **restrictive periods** (every kind, past and future), turned into multiplier windows for the rule state
 *   in force;
 * - the **pre-placed tasks** ([Input.blocks]);
 * - the **frozen past** ([Input.history]) — replayed on the schedulable clock to give every lag and the run in
 *   progress at the line. A panel the line is standing in is part of it, which is why the chunk in progress
 *   continues without a rule of its own: stopping it short costs its shortfall.
 *
 * ### The rule state
 * The rules at a position `x` of the line are computed with `R(x)`, held for the whole continuation — the reading
 * under which the requirements' two-scenario example holds. Inside a transition the engine re-plans at every
 * decision boundary the line reaches ([SchedulerDomain.taskTreeBlendDecisionKey]), so every decision the frozen
 * past records was taken with the rule state at its own instant.
 */
internal object ScheduleFill {

    class Input(
        /** Where placement starts: the now-line, or the end of the head an extension keeps. */
        val startMillis: Long,
        val horizonMillis: Long,
        /** How far back the frozen past and the deprivations are read. */
        val lookbackMillis: Long,
        /** `R(x)` at the now-line, in the tie-break order. */
        val ruleState: List<PlanTask>,
        /** Every restrictive period, whatever its kind. */
        val periods: List<RestrictivePeriod>,
        /** The pre-placed tasks (a `null` task: a block owned by nobody). */
        val blocks: List<PlanBlock>,
        /** What has been served before [startMillis], per task. */
        val history: List<PlanBlock>,
        /** PRD §13: the task the first placed run must be. */
        val forcedFirst: TaskId? = null,
        /** PRD §7: the task the first placed run may not be while anybody else may run. */
        val refusedFirst: TaskId? = null,
        /**
         * `docs/scheduler_score.md` § *The rules repeat*: the repeating part of the rules an earlier fill returned.
         * An EXTENSION passes it, and where it still holds the tail is unrolled from it instead of searched.
         */
        val cycle: ScheduleCycle? = null,
        /**
         * `docs/scheduler_score.md` § *The rules repeat*, the limit: the search never plans past this instant. A fill
         * reaching it returns a repetition — the exact one, or the closest approximate one — and what lies beyond is
         * that repetition unrolled. Null: no limit (the search reaches the horizon, and only an exact repetition is
         * returned).
         */
        val repeatBeyondMillis: Long? = null,
        /**
         * `docs/invariants/scheduler.md` § *One device plans*: the runs the account's elected device placed for these
         * rules. When given, nothing is searched: each is laid over THIS device's environment from [startMillis] to
         * the horizon, and [cycle] is returned as the repeating part.
         */
        val adopted: List<RulePlacement>? = null,
    )

    /** How far a peer's first run may start after the line and still be taken as starting on it (clock skew). */
    const val ADOPT_SKEW_MILLIS: Long = 60_000

    /**
     * [adopted] laid from [startMillis] to [endMillis]: each run cut at the alternative's changes and carried through
     * the model's wall stretches, so a stretch nobody may run in on THIS device — a break or a period the peer did not
     * have — is never given to a task. A run of a task this device does not know is dropped. The first run is pulled
     * back onto the line when the peer's clock put it up to [ADOPT_SKEW_MILLIS] later, so the line is never idle.
     */
    private fun layAdopted(
        model: ScoreModel,
        adopted: List<RulePlacement>,
        startMillis: Long,
        endMillis: Long,
        out: MutableList<Triple<TaskId, Pair<Long, Long>, TaskId?>>,
    ) {
        val sorted = adopted.filter { model.indexOf.containsKey(it.taskId) && it.endMillis > startMillis }.sortedBy { it.startMillis }
        for ((k, p) in sorted.withIndex()) {
            val start =
                if (k == 0 && p.startMillis > startMillis && p.startMillis - startMillis <= ADOPT_SKEW_MILLIS) startMillis
                else maxOf(p.startMillis, startMillis)
            val end = minOf(p.endMillis, endMillis)
            if (end <= start) continue
            val cuts = (listOf(start) + p.alternativeSpans.map { it.fromMillis }.filter { it in (start + 1) until end } + end).sorted()
            for (i in 0 until cuts.size - 1) {
                val a = cuts[i]
                val b = cuts[i + 1]
                val alternative = p.alternativeSpans.lastOrNull { it.fromMillis <= a }?.taskId ?: p.alternativeTaskId
                for (w in model.wallIntervals(model.uAt(a), model.uAt(b))) {
                    if (!w.fixed) out += Triple(p.taskId, w.startMillis to w.endMillis, alternative)
                }
            }
        }
    }

    /** One placed panel: who, when, and the alternative schedule inside it. */
    data class Placement(
        val taskId: TaskId,
        val startMillis: Long,
        val endMillis: Long,
        val alternative: TaskId?,
        val alternativeSpans: List<AlternativeSpan>,
    )

    /** What a fill returns: the placements, and the repeating part of the rules when they repeat. */
    class Result(val placements: List<Placement>, val cycle: ScheduleCycle?)

    fun run(input: Input): Result {
        val from = input.startMillis - input.lookbackMillis
        val to = input.horizonMillis
        if (to <= input.startMillis) return Result(emptyList(), input.cycle)
        val raw = ArrayList<Triple<TaskId, Pair<Long, Long>, TaskId?>>()
        val tasks = input.ruleState
        if (tasks.isEmpty()) return Result(emptyList(), null)
        val ruleStateHash = tasks.hashCode()

        // `docs/invariants/scheduler.md` § *One device plans*: another device of the account searched; lay its runs.
        input.adopted?.let { adopted ->
            val model = ScoreModel(tasks, input.blocks, windowsFor(input.periods, tasks, from, to), from, to)
            layAdopted(model, adopted, input.startMillis, to, raw)
            return Result(group(raw), input.cycle)
        }

        // The rules already repeat: unroll them, if nothing they did not see has changed.
        val cycle = input.cycle
        if (cycle != null && cycle.ruleStateHash == ruleStateHash && input.forcedFirst == null && input.refusedFirst == null &&
            cycle.anchorMillis <= input.startMillis
        ) {
            val modelFrom = minOf(from, cycle.anchorMillis)
            val model = ScoreModel(tasks, input.blocks, windowsFor(input.periods, tasks, modelFrom, to), modelFrom, to)
            val unrolled = unroll(model, cycle, model.uAt(input.startMillis))
            if (unrolled != null) {
                emit(model, unrolled.first, raw)
                return Result(group(raw), unrolled.second)
            }
        }

        val model = ScoreModel(tasks, input.blocks, windowsFor(input.periods, tasks, from, to), from, to)
        val cursor = replay(model, input.history, input.startMillis)
        val forcedFirst = input.forcedFirst?.let { model.indexOf[it] } ?: -1
        val refusedFirst = input.refusedFirst?.let { model.indexOf[it] } ?: -1
        // Past the limit the rules may not grow: the search stops there, and what lies beyond is the repetition —
        // exact when the runs repeat, the closest approximate one when they do not.
        val limit = input.repeatBeyondMillis
        val approximate = limit != null && to >= limit
        val searchUntil = if (approximate) model.uAt(limit!!) else model.uEnd
        val plan = ScheduleOptimizer(model).plan(cursor, searchUntil, forcedFirst = forcedFirst, refusedFirst = refusedFirst)
        var settled = settle(model, plan.runs, ruleStateHash, approximate)
        if (settled.second == null && searchUntil < model.uEnd - ScoreModel.EPS) {
            // Nothing repeats here (the environment ahead is not uniform): the search has to reach the horizon itself.
            settled = ScheduleOptimizer(model).plan(cursor, model.uEnd, forcedFirst = forcedFirst, refusedFirst = refusedFirst).runs to null
        }
        emit(model, settled.first, raw)
        return Result(group(raw), settled.second)
    }

    // ----- `docs/scheduler_score.md` § *The rules repeat* ----------------------------------------------

    /** The longest repetition looked for, in task runs — the limit that keeps a set of rules from growing heavy. */
    const val MAX_CYCLE_RUNS: Int = 64

    /** How many repetitions in a row make a cycle. */
    const val CYCLE_COPIES: Int = 3

    /**
     * The runs at the end of a continuation, which see the horizon and are never part of a repetition — at least
     * [HORIZON_RUNS], and the search slides back up to one period and [HORIZON_SLACK_RUNS] more, because how far back
     * the horizon bends the search depends on the lookahead, not on a fixed count.
     */
    private const val HORIZON_RUNS: Int = 2
    private const val HORIZON_SLACK_RUNS: Int = 8

    /** Two run lengths this close are one length (the clock is in millis). */
    private const val SAME_RUN_MILLIS: Double = 1.0

    /** The piece [u] is in when it is uniform to the model's end (no pre-placed task, no later edge), else -1. */
    private fun uniformPieceToEnd(model: ScoreModel, u: Double): Int {
        val p = model.pieceAt(u)
        if (p < 0 || model.pieceFixed[p] != -1) return -1
        return if (model.pieceUStart[p] + model.pieceULen[p] >= model.uEnd - ScoreModel.EPS) p else -1
    }

    /**
     * One task's uninterrupted run: [ScheduleOptimizer.Run]s `from until to` of [runs], which the alternative
     * annotation may have split. Repetition is judged on these — the task and how long it runs — because where the
     * bisection places a split inside a run is resolved only to a minute and need not land on the same instant in
     * every copy.
     */
    private class TaskRun(val from: Int, val to: Int, val task: Int, val fromU: Double, val toU: Double)

    private fun taskRuns(runs: List<ScheduleOptimizer.Run>): List<TaskRun> {
        val out = ArrayList<TaskRun>()
        var i = 0
        while (i < runs.size) {
            var j = i + 1
            while (j < runs.size && runs[j].task == runs[i].task && kotlin.math.abs(runs[j].fromU - runs[j - 1].toU) <= ScoreModel.EPS) j++
            out += TaskRun(i, j, runs[i].task, runs[i].fromU, runs[j - 1].toU)
            i = j
        }
        return out
    }

    /**
     * The continuation [runs] as the rules return it: when it settles into a repetition — the smallest sequence of
     * at most [MAX_CYCLE_RUNS] task runs seen [CYCLE_COPIES] times in a row, back to back, over a stretch whose
     * environment is uniform to the end — everything after the last copy is that copy again (the runs the search
     * laid against the horizon are replaced), and the last copy is returned as the cycle.
     *
     * When it does not and [approximate] is set — the fill has reached the materialization ceiling, the limit on how
     * heavy the rules may grow — the repetition is the APPROXIMATE one of [approximateCopy] instead.
     */
    private fun settle(
        model: ScoreModel,
        runs: List<ScheduleOptimizer.Run>,
        ruleStateHash: Int,
        approximate: Boolean,
    ): Pair<List<ScheduleOptimizer.Run>, ScheduleCycle?> {
        val groups = taskRuns(runs)
        // The first run is the line's own decision (a forced start, a run in progress) and the last ones see the
        // horizon; neither can be part of what repeats.
        val last = groups.size - HORIZON_RUNS
        for (p in 1..MAX_CYCLE_RUNS) {
            if (last - CYCLE_COPIES * p < 1) break
            fun repeatsUntil(end: Int): Boolean = (end - (CYCLE_COPIES - 1) * p until end).all { i ->
                val a = groups[i]
                val b = groups[i - p]
                a.task == b.task && a.task >= 0 &&
                    kotlin.math.abs((a.toU - a.fromU) - (b.toU - b.fromU)) <= SAME_RUN_MILLIS &&
                    kotlin.math.abs(a.fromU - groups[i - 1].toU) <= ScoreModel.EPS
            }
            val end = (last downTo maxOf(1 + CYCLE_COPIES * p, last - p - HORIZON_SLACK_RUNS)).firstOrNull { repeatsUntil(it) } ?: continue
            return repeatCopy(model, runs, groups, end - CYCLE_COPIES * p, end - p, end, ruleStateHash, exact = true) ?: (runs to null)
        }
        if (!approximate) return runs to null
        val window = approximateCopy(model, groups, last - HORIZON_SLACK_RUNS) ?: return runs to null
        return repeatCopy(model, runs, groups, window.first, window.first, window.last + 1, ruleStateHash, exact = false) ?: (runs to null)
    }

    /**
     * The runs with everything from task run [end] on replaced by task runs `copyFrom until end` repeated, and that
     * copy as the cycle — or null when the environment from task run [uniformFrom] to the end is not uniform, or a
     * task of the copy may not run there.
     */
    private fun repeatCopy(
        model: ScoreModel,
        runs: List<ScheduleOptimizer.Run>,
        groups: List<TaskRun>,
        uniformFrom: Int,
        copyFrom: Int,
        end: Int,
        ruleStateHash: Int,
        exact: Boolean,
    ): Pair<List<ScheduleOptimizer.Run>, ScheduleCycle>? {
        val piece = uniformPieceToEnd(model, groups[uniformFrom].fromU)
        if (piece < 0 || model.pieceUStart[piece] > groups[uniformFrom].fromU + ScoreModel.EPS) return null
        val copy = runs.subList(groups[copyFrom].from, groups[end - 1].to)
        if (copy.any { it.task < 0 || !model.permitted(it.task, model.pieceUStart[piece]) }) return null
        val cycle =
            ScheduleCycle(
                anchorMillis = model.wallStartAt(copy[0].fromU),
                runs = copy.map { r ->
                    CycleRun(model.taskId(r.task), r.toU - r.fromU, if (r.alternative >= 0) model.taskId(r.alternative) else null)
                },
                ruleStateHash = ruleStateHash,
                environmentHash = model.pieceMult[piece].contentHashCode(),
                exact = exact,
            )
        val tail = unrollRuns(model, cycle, copy.map { it.task }, copy.map { it.alternative }, copy[0].fromU, groups[end].fromU)
        return (runs.subList(0, groups[end].from) + tail) to cycle
    }

    /**
     * `docs/scheduler_score.md` § *The rules repeat*, the limit: **the task runs ending at [end] that, repeated, come
     * closest to the target shares** — a window between `Θ` and `2Θ` of schedulable time (`Θ` being the window in
     * which every task can be matched), scored by the squared gap between each task's share of the window and its
     * target at the window's start, plus one whole unit when the window would repeat into the same task it ends
     * with. Null when no such window exists before [end].
     */
    private fun approximateCopy(model: ScoreModel, groups: List<TaskRun>, end: Int): IntRange? {
        if (end < 2) return null
        val theta = model.theta
        var best: IntRange? = null
        var bestScore = Double.POSITIVE_INFINITY
        val served = DoubleArray(model.n)
        var start = end - 1
        while (start >= 1 && end - start <= MAX_CYCLE_RUNS) {
            val g = groups[start]
            if (g.task < 0 || kotlin.math.abs(groups[start + 1].fromU - g.toU) > ScoreModel.EPS && start + 1 < end) break
            served[g.task] += g.toU - g.fromU
            val length = groups[end - 1].toU - g.fromU
            if (length > 2.0 * theta) break
            if (length >= theta) {
                val target = model.targetAt(g.fromU)
                var score = 0.0
                for (i in 0 until model.n) {
                    val d = served[i] / length - target[i]
                    score += d * d
                }
                if (groups[end - 1].task == g.task) score += 1.0
                if (score < bestScore - ScheduleOptimizer.TIE) {
                    bestScore = score
                    best = start until end
                }
            }
            start--
        }
        return best
    }

    /**
     * The runs [cycle] gives from [fromU] to the model's end, with the cycle rebased onto its last repetition
     * starting inside them — or null when it no longer holds here: its tasks are gone, its anchor is outside the
     * model, or the environment from the anchor on is not the uniform one it repeated over.
     */
    fun unroll(model: ScoreModel, cycle: ScheduleCycle, fromU: Double): Pair<List<ScheduleOptimizer.Run>, ScheduleCycle>? {
        if (cycle.runs.isEmpty() || cycle.lengthMillis <= ScoreModel.EPS) return null
        if (cycle.anchorMillis < model.fromMillis || cycle.anchorMillis >= model.toMillis) return null
        val tasks = cycle.runs.map { model.indexOf[it.taskId] ?: return null }
        val alternatives = cycle.runs.map { r -> r.alternativeTaskId?.let { model.indexOf[it] ?: return null } ?: -1 }
        val anchorU = model.uAt(cycle.anchorMillis)
        val piece = uniformPieceToEnd(model, anchorU)
        if (piece < 0 || model.pieceMult[piece].contentHashCode() != cycle.environmentHash) return null
        if (tasks.any { !model.permitted(it, anchorU) }) return null
        val runs = unrollRuns(model, cycle, tasks, alternatives, anchorU, fromU)
        // Rebase onto the last whole repetition that starts before the model's end, so the next unroll walks no
        // further than one extension.
        val length = cycle.lengthMillis
        val reps = kotlin.math.floor((model.uEnd - anchorU) / length).toLong().coerceAtLeast(0L)
        val lastStart = anchorU + reps * length
        val rebased = if (lastStart < model.uEnd - ScoreModel.EPS && reps > 0) cycle.copy(anchorMillis = model.wallStartAt(lastStart)) else cycle
        return runs to rebased
    }

    /** The runs of [cycle] (anchored at [anchorU]) from [fromU] to the model's end. */
    private fun unrollRuns(
        model: ScoreModel,
        cycle: ScheduleCycle,
        tasks: List<Int>,
        alternatives: List<Int>,
        anchorU: Double,
        fromU: Double,
    ): List<ScheduleOptimizer.Run> {
        val out = ArrayList<ScheduleOptimizer.Run>()
        val length = cycle.lengthMillis
        var reps = kotlin.math.floor((fromU - anchorU) / length).toLong().coerceAtLeast(0L)
        if (anchorU + reps * length > fromU + ScoreModel.EPS) reps--
        var u = anchorU + reps.coerceAtLeast(0L) * length
        var k = 0
        while (u < model.uEnd - ScoreModel.EPS) {
            val to = u + cycle.runs[k].lengthMillis
            if (to > fromU + ScoreModel.EPS) {
                out += ScheduleOptimizer.Run(tasks[k], maxOf(u, fromU), minOf(to, model.uEnd), alternatives[k])
            }
            u = to
            k = (k + 1) % cycle.runs.size
        }
        return out
    }

    /** The placements of [runs]: their wall stretches outside pre-placed tasks, each with its alternative. */
    private fun emit(model: ScoreModel, runs: List<ScheduleOptimizer.Run>, out: MutableList<Triple<TaskId, Pair<Long, Long>, TaskId?>>) {
        for (r in runs) {
            val id = model.taskId(r.task)
            val alt = if (r.alternative >= 0) model.taskId(r.alternative) else null
            for (w in model.wallIntervals(r.fromU, r.toU)) {
                if (w.fixed) continue
                out += Triple(id, w.startMillis to w.endMillis, alt)
            }
        }
    }

    /** Fuse contiguous stretches of one task into one placement, keeping where the alternative changes. */
    private fun group(raw: List<Triple<TaskId, Pair<Long, Long>, TaskId?>>): List<Placement> {
        val out = ArrayList<Placement>()
        for ((id, span, alt) in raw.sortedBy { it.second.first }) {
            val last = out.lastOrNull()
            if (last != null && last.taskId == id && last.endMillis == span.first) {
                val inForce = last.alternativeSpans.lastOrNull()?.taskId ?: last.alternative
                val spans = if (inForce == alt) last.alternativeSpans else last.alternativeSpans + AlternativeSpan(span.first, alt)
                out[out.size - 1] = last.copy(endMillis = span.second, alternativeSpans = spans)
            } else {
                out += Placement(id, span.first, span.second, alt, emptyList())
            }
        }
        return out
    }

    /**
     * The frozen past on the schedulable clock: every lag and the run in progress at [atMillis], from lag zero at
     * the model's start. Schedulable time the past gave to no task counts as nobody's service.
     */
    fun replay(model: ScoreModel, history: List<PlanBlock>, atMillis: Long): ScoreCursor {
        val cursor = model.cursor(0.0)
        val end = model.uAt(atMillis)
        for (b in history.sortedWith(compareBy({ it.startMillis }, { it.endMillis }))) {
            val s = maxOf(b.startMillis, model.fromMillis)
            val e = minOf(b.endMillis, atMillis)
            if (e <= s) continue
            val us = model.uAt(s)
            val ue = model.uAt(e)
            if (ue <= cursor.u + ScoreModel.EPS) continue
            if (us > cursor.u + ScoreModel.EPS) model.serve(cursor, -1, us)
            val task = b.taskId?.let { model.indexOf[it] } ?: -1
            model.serve(cursor, task, ue)
        }
        if (end > cursor.u + ScoreModel.EPS) model.serve(cursor, -1, end)
        return model.rebase(cursor)
    }

    /**
     * The restrictive periods as multiplier windows for [tasks]: the timeline is cut at every period edge and each
     * stretch carries the SET of kinds covering it, so two overlapping periods of one kind are one restriction and
     * two of different kinds multiply ([PeriodKinds.multiplier]).
     */
    fun windowsFor(periods: List<RestrictivePeriod>, tasks: List<PlanTask>, fromMillis: Long, toMillis: Long): List<PlanWindow> {
        val relevant = periods.filter { it.kind.isNotEmpty() && it.endMillis > fromMillis && it.startMillis < toMillis }
        if (relevant.isEmpty()) return emptyList()
        val edges = HashSet<Long>()
        for (p in relevant) {
            edges += maxOf(p.startMillis, fromMillis)
            edges += minOf(p.endMillis, toMillis)
        }
        val sorted = edges.sorted()
        val byStart = relevant.sortedBy { it.startMillis }
        val active = ArrayList<RestrictivePeriod>()
        var next = 0
        val out = ArrayList<PlanWindow>()
        for (k in 0 until sorted.size - 1) {
            val a = sorted[k]
            val b = sorted[k + 1]
            while (next < byStart.size && byStart[next].startMillis <= a) active += byStart[next++]
            active.removeAll { it.endMillis <= a }
            val kinds = active.mapTo(HashSet()) { it.kind }
            if (kinds.isEmpty()) continue
            out += PlanWindow.of(a, b, kinds, tasks)
        }
        return out
    }

}
