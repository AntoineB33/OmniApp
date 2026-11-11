package org.example.project.scheduler.domain

/**
 * `docs/scheduler_requirements.md` § *Two paces*, **the instant schedule**: what the calendar shows where the
 * scheduler's steps have not made the schedule definitive (the displayed part of the timeline past the front), and
 * the first 10 seconds of a run from scratch. It *"prevents displaying obviously wrong schedules"*: no rule broken, the
 * target ratios and the minimum lengths roughly kept, nobody left where a task may run — at once, with no search.
 *
 * **One policy, the score's own** (ported from line_manager's `Model.ruled` and `Instant._rolled`, 2026-11-11): the
 * base policy of the search ([ScheduleOptimizer]'s, the task most behind its target counted in its own minimum, for
 * what is left of its minimum), with what the instant schedule must never show:
 * - no panel of a pre-placed task's own task against either side of it, where another task may be there (the block
 *   the user placed would look longer than they made it);
 * - what is left before a pre-placed task, too short for any task's minimum, given to the panel before it rather than
 *   left as a sliver;
 * - a panel open at the start short of its minimum finished first;
 * - nobody never, where a task may run.
 *
 * **Rolled by parts, each read by the score from its own start** ([PART_MILLIS], with [LOOK_MILLIS] after it): the
 * score discounts what is ahead over `Θ`, so read once from the start of a long span it judges only its first hours.
 * On each part the policy is tried at [OFFSETS] (how early a task falls due: `(L + offset·M)/f`), and the one the
 * score puts lowest on the part and its look-ahead (closed by [ScoreModel.lowerBound]) is kept; the next part is
 * read on the cursor the kept one left. Nothing here depends on time: the same model and cursor give the same runs.
 */
class InstantSchedule(val model: ScoreModel) {

    /**
     * The runs from [start] to [untilU] on [model]'s schedulable clock. [forcedFirst] / [refusedFirst] / [among]: what
     * the first run may be ([ScheduleOptimizer.firstOptions]).
     */
    fun plan(
        start: ScoreCursor,
        untilU: Double,
        forcedFirst: Int = -1,
        refusedFirst: Int = -1,
        among: Set<Int> = emptySet(),
    ): List<ScheduleOptimizer.Run> {
        val out = ArrayList<ScheduleOptimizer.Run>()
        val main = start.copy()
        var first = true
        var guard = 0
        while (main.u < untilU - ScoreModel.EPS && guard++ < MAX_PARTS) {
            val partEnd = minOf(untilU, main.u + PART_MILLIS)
            val lookEnd = minOf(model.uEnd, partEnd + LOOK_MILLIS)
            var best: List<ScheduleOptimizer.Run>? = null
            var bestCost = Double.POSITIVE_INFINITY
            for (offset in OFFSETS) {
                val trial = model.rebase(main)
                val runs = ruled(trial, maxOf(lookEnd, partEnd), offset, first, forcedFirst, refusedFirst, among)
                model.settle(trial)
                val cost = trial.cost + model.lowerBound(trial, trial.u + TAIL_MILLIS)
                if (best == null || cost < bestCost) {
                    best = runs
                    bestCost = cost
                }
            }
            val chosen = best ?: break
            val before = main.u
            for (r in chosen) {
                if (r.fromU >= partEnd - ScoreModel.EPS) break
                val to = minOf(r.toU, partEnd)
                if (to <= main.u + ScoreModel.EPS) continue
                model.serve(main, r.task, to)
                out += ScheduleOptimizer.Run(r.task, r.fromU, to, -1)
            }
            if (main.u <= before + ScoreModel.EPS) break
            first = false
        }
        return ScheduleOptimizer.coalesce(out)
    }

    /** The ruled base policy from [cursor] to [untilU] (see the class); the cursor is served as it goes. */
    private fun ruled(
        cursor: ScoreCursor,
        untilU: Double,
        offset: Double,
        firstPart: Boolean,
        forcedFirst: Int,
        refusedFirst: Int,
        among: Set<Int>,
    ): List<ScheduleOptimizer.Run> {
        val out = ArrayList<ScheduleOptimizer.Run>()
        val m = model
        val least = (0 until m.n).filter { m.share[it] > 0.0 }.minOfOrNull { m.minimum[it] } ?: ScoreModel.MIN_WINDOW_MILLIS
        var afterUser = false
        var firstRun = firstPart
        var guard = 0
        fun serve(task: Int, to: Double) {
            val from = cursor.u
            if (to <= from + ScoreModel.EPS) return
            m.serve(cursor, task, to)
            out += ScheduleOptimizer.Run(task, from, to, -1)
        }
        while (cursor.u < untilU - ScoreModel.EPS && guard++ < MAX_DECISIONS) {
            val u = cursor.u
            val fixed = m.fixedAt(u)
            if (fixed >= 0) { // (the user's: as it is)
                serve(fixed, minOf(untilU, m.fixedEnd(u)))
                afterUser = true
                firstRun = false
                continue
            }
            var cands = m.candidatesAt(u)
            if (cands.isEmpty()) {
                serve(ScoreModel.IDLE, minOf(untilU, m.nextEdge(u)))
                afterUser = false
                continue
            }
            if (firstRun) {
                cands = ScheduleOptimizer.firstOptions(cands, forcedFirst, refusedFirst, among).filter { it >= 0 }.ifEmpty { cands }
            }
            // the next block of the user's, and its task
            val uF = m.nextFixedStart(u).takeIf { it < m.uEnd - ScoreModel.EPS }
            val blockTask = uF?.let { m.fixedAt(it + ScoreModel.EPS) } ?: -1
            fun room(i: Int): Double {
                var e = m.runLimit(i, u)
                if (uF != null) e = minOf(e, uF)
                return minOf(e, untilU) - u
            }
            fun touches(i: Int): Boolean {
                if (afterUser && i == cursor.run) return true
                if (i == blockTask && uF != null && u + room(i) >= uF - ScoreModel.EPS) {
                    return uF - (u + minOf(m.minimum[i], room(i))) < least
                }
                return false
            }
            val pick = cands.filter { !touches(it) }.ifEmpty { cands }
            // a panel open at the start short of its minimum: finished first (the part before cut it at its end)
            val open = cursor.run
            if (!afterUser && open >= 0 && open in pick && cursor.runLen < m.minimum[open] - ScoreModel.EPS && room(open) > ScoreModel.EPS) {
                serve(open, u + minOf(m.minimum[open] - cursor.runLen, room(open)))
                firstRun = false
                continue
            }
            val target = m.targetAt(u)
            var best = -1
            var bestKey = Double.POSITIVE_INFINITY
            for (j in pick) {
                val key = (m.estimateLag(cursor, j, u) + offset * m.minimum[j]) / maxOf(target[j], 1e-12)
                if (best < 0 || key < bestKey) {
                    best = j
                    bestKey = key
                }
            }
            val r = room(best)
            var h = minOf(maxOf(m.minimum[best], ScoreModel.MIN_WINDOW_MILLIS), r)
            if (uF != null && blockTask != best && u + r >= uF - ScoreModel.EPS && uF - (u + h) > ScoreModel.EPS && uF - (u + h) < least) {
                h = uF - u // (the rest before the block: this panel's)
            }
            if (h <= ScoreModel.EPS) h = if (r > ScoreModel.EPS) r else minOf(untilU, m.nextEdge(u)) - u
            serve(best, u + h)
            afterUser = false
            firstRun = false
        }
        return out
    }

    companion object {
        /** A part of the rolling pass, read by the score from its own start. */
        const val PART_MILLIS: Double = 2 * 3_600_000.0

        /** What a part is scored over past its end, before the closed tail. */
        const val LOOK_MILLIS: Double = 2 * 3_600_000.0

        /** How far past a trial the lags are closed by [ScoreModel.lowerBound]. */
        const val TAIL_MILLIS: Double = 7 * 24 * 3_600_000.0

        /** The phases the policy is tried at on each part (how early a task falls due). */
        val OFFSETS: DoubleArray = doubleArrayOf(0.25, 0.5, 0.75)

        private const val MAX_PARTS: Int = 1_000_000
        private const val MAX_DECISIONS: Int = 100_000
    }
}
