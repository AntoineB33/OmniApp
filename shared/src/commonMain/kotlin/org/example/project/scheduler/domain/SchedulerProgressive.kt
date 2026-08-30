package org.example.project.scheduler.domain

import kotlin.math.abs
import kotlin.time.TimeSource
import org.example.project.scheduler.model.TaskId

/**
 * `side-dev/README.md` § *Progressive Calculation* and § *Alternative Schedules* — **the scheduler's answer as
 * a SET OF RULES, and the front that makes it better and better while it is being computed.**
 *
 * The README asks for two things this file exists for, and neither of them is a schedule:
 *
 * 1. **The answer is parameterised by the now-line, not computed for one position of it.** "Some of the rules
 *    returned by the scheduler are parameterized by two variables that can unpredictably change value anytime:
 *    the now line and the now line mode." A [Regime] is that: a list of stretches whose two bounds are
 *    **affine in the now-line**, valid over a RANGE of its positions at one mode. Reading the schedule at a
 *    position inside the range is arithmetic (`a + b*(t_p - lo)`), not scheduling — which is what lets a
 *    display follow the line continuously, and what makes the answer something a device can be HANDED rather
 *    than something it must recompute for its own line.
 * 2. **The answer improves under a compute budget.** "The scheduler doesn't need to calculate the right
 *    schedule for the entire timeline, but if the definitive schedule is found for any t < t_1, then 10
 *    seconds later the definitive schedule must be found for any t < t_1 + 10 minutes... As time passes, the
 *    scheduler returns one set of rule after the other to satisfy this pace." [settle] is that: it extends
 *    [frontMillis] one LINK at a time for as long as the budget lasts, and everything below the front is
 *    definitive — later settling never rewrites it.
 *
 * These two requirements are not local increments; they are the account-wide schedule contract. The scheduler
 * does not decide from a device-local opinion: its input is the shared rule state of the account — the current
 * task weights, minimums, resilience values, restrictive periods, and the other data that defines the rule
 * state — and same-account devices must converge on that state before they can treat their local schedule as
 * authoritative. Once the rule state is synced, a better answer is a real improvement and therefore a thing a
 * device can hand to the rest of the account.
 *
 * The README's closing clause is what makes the whole shape load-bearing: *"The only acceptable degradation
 * allowed in order to save time or computer power is getting as close as possible to the best score for the
 * two optimization criteria, without reaching it. But if the best possible score is reachable within the
 * required time and acceptable computer power, it must be reached."* More compute is a BETTER answer, not the
 * same answer sooner — which is why a set of rules is worth handing between devices at all.
 *
 * ### What makes a partial answer honest
 * Each link is planned with the timeline the earlier links committed, carrying the **live walk** and the chunk
 * still in progress ([PendingChunk]) across the seam rather than reconstructing them from the drawn past. That
 * is what makes the chain the SAME schedule one long walk would have written, instead of merely a close one —
 * the resume contract of `docs/adr/0001-scheduler-model.md`, and the reason [SchedulerPlanner.runRange] is one
 * function with two callers rather than two loops that must be kept in step.
 *
 * The influence field is built ONCE per chain, at the commit point, over the whole span to the horizon —
 * `side-dev/scheduler.py`'s `field_span`. A link whose field stopped at its own edge could see neither the
 * exclusion just past it nor the tail of the one it had already walked through, and where the links happened
 * to fall would become visible in the schedule.
 *
 * ### What this is NOT
 * It is not a second copy of the scheduling rules. `PlanWalk` remains the only one; this drives it.
 */
class ProgressiveSchedule(
    private val startMillis: Long,
    private val horizonMillis: Long,
    private val plannerAt: (tpMillis: Long) -> SchedulerPlanner,
    private val contextAt: (tpMillis: Long, mode: Int) -> PlanContext,
    private val linkMillis: Long = LINK_MILLIS,
    private val lookaheadMillis: Long = LOOKAHEAD_MILLIS,
    private val maxRules: Int = SchedulerPlanner.MAX_RULES,
) {
    /** The now-line this schedule is drawn for, and its mode. */
    var tpMillis: Long = startMillis
        private set

    var mode: Int = MODE_AT_SCREEN
        private set

    /**
     * **The frozen past** — everything the line has already passed. `side-dev/README.md` § *frozen past*: the
     * schedule at `t < now line` never changes as the line increases, and here it is frozen BY CONSTRUCTION
     * rather than by a check: what is below the line is stored, and every later plan is asked only for the
     * continuation from [commitPointMillis].
     */
    var committed: List<PlannedRun> = emptyList()
        private set

    var commitPointMillis: Long = startMillis
        private set

    /**
     * **The progressive front**: the definitive schedule reaches this instant. Everything in
     * `[commitPointMillis, frontMillis)` is settled — the rules returned for it will not change however much
     * longer the scheduler runs.
     */
    var frontMillis: Long = startMillis
        private set

    private var chain: List<PlannedRun> = emptyList()
    private var chainKey: ChainKey? = null
    private var chainWalk: PlanWalk? = null
    private var chainPending: PendingChunk? = null
    private var chainAhead: List<PlanBlock> = emptyList()

    private data class ChainKey(val tpMillis: Long, val mode: Int, val commitPointMillis: Long)

    private fun key() = ChainKey(tpMillis, mode, commitPointMillis)

    // ----- progressive calculation -----------------------------------------------------------------

    /**
     * Extend the definitive schedule for as long as [budget] lasts, and answer where the front now stands.
     *
     * Call it again and it picks up where it left off; call it after the line or the mode has moved and it
     * starts a fresh chain, because the rules the line is parameterised by have changed. The pace the README
     * asks for — ten minutes of definitive schedule per ten seconds — is met with an enormous margin, one
     * [LINK_MILLIS] being four hours of timeline.
     */
    fun settle(budget: SettleBudget = SettleBudget.of(DEFAULT_BUDGET_MILLIS)): Long {
        if (chainKey != key()) resetChain()
        val planner = plannerAt(tpMillis)
        val context = contextAt(tpMillis, mode)
        var walk = chainWalk
        if (walk == null) {
            // Seed ONCE, at the commit point, so the field covers [commitPoint, horizon] and every link walks
            // inside it rather than inside its own slice.
            val seeded = planner.seedWalk(
                blocks = context.blocks,
                windows = context.windows,
                nowMillis = commitPointMillis,
                history = committed.map { it.toBlock() },
            )
            walk = seeded.walk
            chainWalk = walk
            chainPending = seeded.pending
            chainAhead = seeded.ahead
        }
        while (frontMillis < horizonMillis) {
            val end = minOf(frontMillis + linkMillis, horizonMillis)
            val placed = mutableListOf<PlannedRun>()
            val result = planner.runRange(
                walk = walk,
                ahead = chainAhead,
                windows = context.windows,
                startMillis = frontMillis,
                endMillis = end,
                pendingIn = chainPending,
                slots = mutableListOf(),
                maxRules = maxRules,
                collector = { id, from, to, alt -> placed += PlannedRun(id, from, to, alt) },
            )
            chainPending = result.pending
            chain = chain + placed
            // A link that placed nothing cannot be repeated: the walk has nothing left to say about this
            // stretch (a degenerate rule state — no schedulable task at all), so the front is moved over it
            // rather than the loop spinning on it.
            frontMillis = if (result.cursorMillis > frontMillis) result.cursorMillis else end
            chainKey = key()
            if (budget.expired()) break
        }
        chainKey = key()
        return frontMillis
    }

    private fun resetChain() {
        chain = emptyList()
        chainWalk = null
        chainPending = null
        chainAhead = emptyList()
        frontMillis = commitPointMillis
        chainKey = null
    }

    // ----- drawing ---------------------------------------------------------------------------------

    /**
     * What the walk places from the commit point on, for this position of the line. Used by [rules] to measure
     * the shape at two positions, and by [timeline] wherever the settled chain does not answer.
     */
    private fun future(tpMillis: Long, mode: Int, endMillis: Long): List<PlannedRun> {
        val planner = plannerAt(tpMillis)
        val context = contextAt(tpMillis, mode)
        val seeded = planner.seedWalk(
            blocks = context.blocks,
            windows = context.windows,
            nowMillis = commitPointMillis,
            history = committed.map { it.toBlock() },
        )
        val placed = mutableListOf<PlannedRun>()
        planner.runRange(
            walk = seeded.walk,
            ahead = seeded.ahead,
            windows = context.windows,
            startMillis = commitPointMillis,
            endMillis = endMillis,
            pendingIn = seeded.pending,
            slots = mutableListOf(),
            maxRules = maxRules,
            collector = { id, from, to, alt -> placed += PlannedRun(id, from, to, alt) },
        )
        return coalesce(placed)
    }

    /**
     * The whole timeline as it stands: the frozen past, then the settled chain, then — where the caller asks
     * beyond the front — the continuation the walk would write there.
     */
    fun timeline(
        tpMillis: Long = this.tpMillis,
        mode: Int = this.mode,
        uptoMillis: Long = horizonMillis,
    ): List<PlannedRun> {
        val settled = chainKey == ChainKey(tpMillis, mode, commitPointMillis) && chain.isNotEmpty()
        val drawn =
            if (!settled) {
                future(tpMillis, mode, uptoMillis)
            } else if (uptoMillis > frontMillis) {
                chain + future(tpMillis, mode, uptoMillis).filter { it.startMillis >= frontMillis }
            } else {
                chain
            }
        return coalesce(clip(committed + drawn, startMillis, uptoMillis))
    }

    /**
     * `side-dev/README.md` § *Alternative Schedules* **at the line**: what to run from here if the scheduled
     * task cannot be run now.
     *
     * The line in mode 1 sits at the very edge of the period it is dragging, so the instant `t_p` itself is
     * often inside a stretch that accepts nobody. The question is then about the next thing scheduled, not
     * about the emptiness the line is standing in.
     */
    fun alternativeAt(tpMillis: Long = this.tpMillis, mode: Int = this.mode): TaskId? {
        val ahead = future(tpMillis, mode, minOf(horizonMillis, tpMillis + lookaheadMillis))
        ahead.firstOrNull { it.alternativeId != null && it.startMillis <= tpMillis && tpMillis < it.endMillis }
            ?.let { return it.alternativeId }
        return ahead.firstOrNull { it.taskId != null && it.alternativeId != null && it.endMillis > tpMillis }
            ?.alternativeId
    }

    // ----- the frozen past -------------------------------------------------------------------------

    /**
     * Move the line to [toMillis], **continuously**, committing everything it passes.
     *
     * `side-dev/README.md`: the line "moves continuously forward in time" and takes every value below itself
     * on the way, so a caller asking for a distant position is asking for a journey, not a landing. The line
     * is walked there a step at a time, freezing what it passes as it passes it — in mode 1 the dragged chain
     * rides immediately ahead of the line, and a stretch planned once with that obstacle parked at the far end
     * is not the stretch the line would have written on its way through.
     *
     * **The step is one minimum execution time**: the finest thing the walk can place, so a line that never
     * skips a whole minimum never skips a placement it should have entered. An ordinary tick — a frame, a
     * second — is far inside the first step and costs exactly one commit.
     */
    fun advanceTo(toMillis: Long, mode: Int = this.mode): List<PlannedRun> {
        require(toMillis >= commitPointMillis) { "the now-line only ever moves forward" }
        var guard = 0
        while (true) {
            if (++guard > MAX_ADVANCE_STEPS) error("the now-line did not reach its target")
            val step = sweepStep()
            val stop = if (step == null) toMillis else minOf(toMillis, tpMillis + step)
            commitAt(stop, mode)
            if (stop >= toMillis) return committed
        }
    }

    /**
     * The coarsest step the line may take without skipping a slot: the smallest minimum execution time the
     * rules hold at the line.
     */
    private fun sweepStep(): Long? =
        plannerAt(tpMillis).minimumOf.values.filter { it > 0L }.minOrNull()

    /**
     * One step of the journey: draw at this position and freeze the past.
     *
     * The settled continuation is KEPT where the environment ahead of the line did not move; a dynamic period
     * that has shifted invalidates it, and the chain starts again from the new commit point.
     */
    private fun commitAt(toMillis: Long, mode: Int) {
        val before = periodsAhead(this.tpMillis, this.mode, toMillis)
        val drawn = timeline(toMillis, mode, horizonMillis)
        committed = clip(drawn, startMillis, toMillis)
        commitPointMillis = toMillis
        val after = periodsAhead(toMillis, mode, toMillis)
        if (chain.isNotEmpty() && before == after && frontMillis > toMillis) {
            chain = clip(chain.filter { it.endMillis > toMillis }, toMillis, frontMillis)
        } else {
            resetChain()
        }
        this.tpMillis = toMillis
        this.mode = mode
        chainKey = if (chain.isNotEmpty()) key() else null
    }

    /** The restrictive periods still ahead of [ofMillis] for a line at [tpMillis] — what a kept chain is
     *  checked against, since a period that has shifted invalidates everything planned under it. */
    private fun periodsAhead(tpMillis: Long, mode: Int, ofMillis: Long): List<PlanWindow> =
        contextAt(tpMillis, mode).windows.filter { it.endMillis == null || it.endMillis!! > ofMillis }

    /**
     * `side-dev/README.md` § *now line 2 modes*: the switch is an ordinary move of the line to where it
     * already is, so everything a move does — commit, keep or drop the chain — happens for it too.
     */
    fun setMode(mode: Int): List<PlannedRun> = advanceTo(tpMillis, mode)

    // ----- the rules -------------------------------------------------------------------------------

    /**
     * **The rule list at the line**: one [Regime], affine in the now-line, certified.
     *
     * The shape is fitted from two positions of the line and then CHECKED at positions it was not fitted on —
     * a rule list that reproduces the scheduler only where it was measured is not a rule list. Where the check
     * fails the range is halved, which is how a breakpoint (a period edge the plan crosses as the line moves)
     * is found without knowing where it is. Failing everything, a regime of ONE position is still a rule list,
     * just not claimed for a range: the line is never left without an answer.
     */
    fun rules(
        tpMillis: Long = this.tpMillis,
        mode: Int = this.mode,
        spanMillis: Long = DEFAULT_RULE_SPAN_MILLIS,
        budget: SettleBudget = SettleBudget.of(DEFAULT_BUDGET_MILLIS),
    ): Regime {
        val lo = tpMillis
        val end = minOf(horizonMillis, lo + lookaheadMillis)
        val past = clip(committed, startMillis, lo)
        val here = future(lo, mode, end)
        var span = spanMillis
        while (span > MIN_RULE_SPAN_MILLIS) {
            val hi = lo + span
            val fitted = fit(here, future(hi, mode, end), lo, hi)
            if (fitted != null && (1..3).all { k -> agrees(fitted, lo, lo + span * k / 4, mode, end) }) {
                return Regime(lo, hi, mode, past, fitted)
            }
            span /= 2
            if (budget.expired()) break
        }
        return Regime(
            loMillis = lo,
            hiMillis = lo,
            mode = mode,
            past = past,
            segments = here.map {
                RuleSegment(it.taskId, it.alternativeId, it.startMillis, 0.0, it.endMillis, 0.0)
            },
        )
    }

    /**
     * Pair two drawn timelines up, stretch by stretch.
     *
     * They are not the same length, and that is not a difference of SHAPE: a moving period cuts the run it
     * crosses in two, so a run that is whole at one position is a sliver, a gap and a remainder at the next —
     * and the line itself creates a panel as it passes. Every one of those is a rule whose length is zero at
     * one end of the range, so the missing side is paired with a DEGENERATE stretch at the boundary it
     * collapses onto, and the fit stays affine instead of being refused.
     */
    private fun align(a: List<PlannedRun>, b: List<PlannedRun>): List<Pair<PlannedRun, PlannedRun>>? {
        val out = mutableListOf<Pair<PlannedRun, PlannedRun>>()
        var i = 0
        var j = 0
        fun same(x: PlannedRun, y: PlannedRun) = x.taskId == y.taskId && x.alternativeId == y.alternativeId
        while (i < a.size && j < b.size) {
            when {
                same(a[i], b[j]) -> {
                    out += a[i] to b[j]
                    i++
                    j++
                }

                j + 1 < b.size && same(a[i], b[j + 1]) -> {
                    val x = a[i].startMillis
                    out += b[j].copy(startMillis = x, endMillis = x) to b[j]
                    j++
                }

                i + 1 < a.size && same(a[i + 1], b[j]) -> {
                    val y = b[j].startMillis
                    out += a[i] to a[i].copy(startMillis = y, endMillis = y)
                    i++
                }

                else -> return null
            }
        }
        return if (i != a.size || j != b.size) null else out
    }

    private fun fit(a: List<PlannedRun>, b: List<PlannedRun>, lo: Long, hi: Long): List<RuleSegment>? {
        val pairs = align(a, b) ?: return null
        val d = (hi - lo).toDouble()
        if (d <= 0.0) return null
        return pairs.map { (x, y) ->
            RuleSegment(
                taskId = x.taskId,
                alternativeId = x.alternativeId,
                start0Millis = x.startMillis,
                startSlope = (y.startMillis - x.startMillis) / d,
                end0Millis = x.endMillis,
                endSlope = (y.endMillis - x.endMillis) / d,
            )
        }
    }

    private fun agrees(segments: List<RuleSegment>, lo: Long, at: Long, mode: Int, end: Long): Boolean {
        val drawn = Regime(lo, at, mode, emptyList(), segments).draw(at)
        val actual = future(at, mode, end)
        if (drawn.size != actual.size) return false
        for (k in drawn.indices) {
            val p = drawn[k]
            val q = actual[k]
            if (p.taskId != q.taskId || p.alternativeId != q.alternativeId) return false
            if (abs(p.startMillis - q.startMillis) > FIT_EPSILON_MILLIS) return false
            if (abs(p.endMillis - q.endMillis) > FIT_EPSILON_MILLIS) return false
        }
        return true
    }

    companion object {
        /** How much timeline one settling step adds. */
        const val LINK_MILLIS: Long = 4L * 60 * 60 * 1000

        /** How far ahead the rules at the line reach. */
        const val LOOKAHEAD_MILLIS: Long = 6L * 60 * 60 * 1000

        /** `side-dev/README.md` § *now line 2 modes*: mode 1 is "a device of the account is unlocked". */
        const val MODE_AT_SCREEN: Int = 1

        const val MODE_AWAY: Int = 2

        const val DEFAULT_BUDGET_MILLIS: Long = 500L

        /** The range of now-line positions a regime is first claimed for, before halving. */
        const val DEFAULT_RULE_SPAN_MILLIS: Long = 20L * 1000

        /** Below this a regime is not claimed for a range at all. */
        const val MIN_RULE_SPAN_MILLIS: Long = 1000L

        /** A fitted bound this far from the measured one is the same bound. */
        const val FIT_EPSILON_MILLIS: Long = 1L

        private const val MAX_ADVANCE_STEPS = 1_000_000
    }
}

/** The obstacles in force for one position of the now-line: `side-dev/scheduler.py`'s `Environment`. */
data class PlanContext(
    val blocks: List<PlanBlock> = emptyList(),
    val windows: List<PlanWindow> = emptyList(),
)

/**
 * How long [ProgressiveSchedule.settle] and [ProgressiveSchedule.rules] may run.
 *
 * A seam rather than a bare wall-clock read, because `side-dev/README.md` makes compute the DIAL — "getting as
 * close as possible to the best score... without reaching it" — so how much of it there is has to be something
 * a caller decides, and something a test can state exactly instead of racing a clock.
 */
fun interface SettleBudget {
    fun expired(): Boolean

    companion object {
        /** A budget of real milliseconds, measured monotonically from the moment it is created. */
        fun of(millis: Long): SettleBudget {
            val mark = TimeSource.Monotonic.markNow()
            return SettleBudget { mark.elapsedNow().inWholeMilliseconds >= millis }
        }

        /**
         * A budget of exactly [steps] settling links — what a test asks for when it wants a stated amount of
         * compute rather than a stated amount of time.
         */
        fun ofSteps(steps: Int): SettleBudget {
            var left = steps
            return SettleBudget { --left <= 0 }
        }

        /** No budget at all: settle to the horizon. */
        val UNLIMITED: SettleBudget = SettleBudget { false }
    }
}

/**
 * One drawn stretch of the timeline: who runs, from when to when, and — `side-dev/README.md` § *Alternative
 * Schedules* — who runs from here **instead** if that task cannot be run now.
 *
 * A `null` [taskId] is an idle hole nobody could fill; a `null` [alternativeId] means there was nobody else,
 * an answer of "the same task again" being no answer at all.
 */
data class PlannedRun(
    val taskId: TaskId?,
    val startMillis: Long,
    val endMillis: Long,
    val alternativeId: TaskId? = null,
) {
    val durationMillis: Long get() = endMillis - startMillis

    fun toBlock(): PlanBlock = PlanBlock(taskId, startMillis, endMillis)
}

/**
 * One drawn stretch, with **both of its bounds affine in the now-line**: `start0 + startSlope*(t_p - lo)`.
 *
 * That is the whole of what makes a rule a rule rather than a placement — it answers for every position of the
 * line in its regime's range, not for the one it was measured at.
 */
data class RuleSegment(
    val taskId: TaskId?,
    val alternativeId: TaskId?,
    val start0Millis: Long,
    val startSlope: Double,
    val end0Millis: Long,
    val endSlope: Double,
) {
    fun startAt(offsetMillis: Long): Long = start0Millis + (startSlope * offsetMillis).toLong()

    fun endAt(offsetMillis: Long): Long = end0Millis + (endSlope * offsetMillis).toLong()
}

/**
 * **The rules over a RANGE of now-line positions** — the scheduler's actual return value.
 *
 * Reading the schedule at a position inside the range is arithmetic, not scheduling: every boundary is
 * `a + b*(t_p - lo)`. That is what `side-dev/README.md` asks for — rules parameterised by the line, not a
 * re-run per position — and it is what lets a display follow the line continuously, and what makes a regime
 * something one device can compute and another can simply READ.
 */
data class Regime(
    val loMillis: Long,
    val hiMillis: Long,
    val mode: Int,
    val past: List<PlannedRun>,
    val segments: List<RuleSegment>,
) {
    fun covers(tpMillis: Long, mode: Int): Boolean =
        mode == this.mode && tpMillis in loMillis..hiMillis

    /** The timeline at [tpMillis] — arithmetic over [segments], never a re-plan. */
    fun draw(tpMillis: Long): List<PlannedRun> {
        val d = tpMillis - loMillis
        val out = mutableListOf<PlannedRun>()
        out += past
        for (segment in segments) {
            val a = segment.startAt(d)
            val b = segment.endAt(d)
            if (b > a) out += PlannedRun(segment.taskId, a, b, segment.alternativeId)
        }
        return out
    }
}

/** Cut [runs] down to `[loMillis, hiMillis)`, dropping what falls outside it entirely. */
fun clip(runs: List<PlannedRun>, loMillis: Long, hiMillis: Long): List<PlannedRun> =
    runs.mapNotNull { run ->
        val a = maxOf(run.startMillis, loMillis)
        val b = minOf(run.endMillis, hiMillis)
        if (b > a) run.copy(startMillis = a, endMillis = b) else null
    }

/**
 * Join runs that only a seam separated.
 *
 * A link boundary of the progressive chain, or the join between the committed past and the continuation, cuts
 * a run in two without changing it. The two halves are ONE rule, and reading them as two would make where the
 * links happened to fall visible in the answer.
 *
 * The alternative kept is the one named at the run's START, which is the answer a single uninterrupted plan
 * would have given: the alternative is a function of the position it is asked at, so the resumed half names a
 * slightly fresher one, and keeping that would let a link boundary show through in the rules even though the
 * schedule is identical.
 */
fun coalesce(runs: List<PlannedRun>): List<PlannedRun> {
    val out = mutableListOf<PlannedRun>()
    for (run in runs) {
        val last = out.lastOrNull()
        if (last != null && last.taskId == run.taskId && last.endMillis == run.startMillis) {
            out[out.size - 1] = last.copy(endMillis = run.endMillis)
        } else {
            out += run
        }
    }
    return out
}
