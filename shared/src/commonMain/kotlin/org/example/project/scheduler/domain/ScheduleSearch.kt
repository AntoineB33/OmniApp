package org.example.project.scheduler.domain

import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TimeMark
import kotlin.time.TimeSource

/**
 * `docs/scheduler_score.md` § *Degradation*: **how much wall time a fill may spend getting closer to the best score.**
 *
 * The rollout policy and the whole-continuation improver always run — they are bounded in steps and are what every
 * fill returns at the least. What this budget pays for is everything past them: the exhaustive search that
 * **certifies** the best continuation when it finishes ([ScheduleOptimizer.certify]), and the platform's
 * [ExternalScheduleSolver] when there is one. `docs/scheduler_requirements.md` § *Strict requirements*: *"if the best
 * possible score is reachable within the required time and acceptable computer power, it must be reached"* — so
 * the time the progressive calculation leaves a stage is spent on reaching it, not left unused.
 *
 * Wall time, deliberately: the result does not have to be the same on every device (user rule, 2026-09-17). Two
 * devices that planned the same rules differently settle it by the score (`docs/invariants/scheduler.md` § *One
 * device plans*).
 */
/**
 * Thrown to unwind a fill NOBODY IS WAITING FOR ANY MORE — see [SearchBudget.checkAbandoned].
 *
 * It is not an error: it is the one way out of a search that is already several frames deep in its own
 * recursion. Exactly one place catches it — the reducer that asked for the fill — and what it does there is
 * return the state it was given, unchanged, so nothing half-planned is ever published. (It keeps the common
 * constructor: the stackless one is JVM-only, and one stack trace per abandoned fill costs nothing.)
 */
class PlanAbandoned internal constructor() : RuntimeException()

class SearchBudget private constructor(
    private val deadline: TimeMark?,
    /**
     * Whether the answer this fill is computing is still wanted. The user's rule: *"if the scheduler was
     * already running, then it stops abruptly and runs again with the new data"* — so a fill is not merely
     * cancelled at its next publish (a coroutine `cancel()` cannot interrupt a CPU-bound walk, which is why
     * an abandoned fill used to run to the end and burn a core on an answer about data nobody holds any more):
     * it is asked, at every checkpoint it already has, whether to stop where it stands.
     */
    private val abandoned: () -> Boolean,
    /** How long the search may go on finding nothing better before it stops ([paced]); null: to the [deadline]. */
    private val patience: Patience? = null,
) {
    /**
     * The search's patience, shared by every [share] of one budget: the passes are one search, and a gain found by
     * any of them is progress for all.
     */
    private class Patience(val millis: Long) {
        var since: TimeMark = TimeSource.Monotonic.markNow()
    }

    /**
     * True once no further time may be spent (always true for [NONE]): the deadline has passed, or the search has gone
     * its patience without finding anything better ([paced]). This is DEGRADATION, not abandonment.
     */
    fun expired(): Boolean {
        val d = deadline ?: return true
        if (d.hasPassedNow()) return true
        val p = patience ?: return false
        return p.since.elapsedNow().inWholeMilliseconds >= p.millis
    }

    /**
     * True while the DEADLINE has not passed — the patience aside. Whether extra time was granted at all is asked of
     * this, never of [expired]: the patience counts from the search's start ([progressed]), and the passes before it
     * (the rollout, the improver, the platform solver's first load) are not the search. Asked of [expired], a
     * `q < 1` budget whose `q²` patience the step-bounded passes outlasted — a loaded machine, a GC, OR-Tools' native
     * load — granted NOTHING, without even reporting itself exhausted (2026-11-26, `SchedulerRunLogTest`).
     */
    fun granted(): Boolean {
        val d = deadline ?: return false
        return !d.hasPassedNow()
    }

    /**
     * The search found something better than what it held (or is starting): its patience runs again from now. Every
     * pass that lowers the score says so — the exhaustive search at a better continuation, the solver at a kept window.
     */
    fun progressed() {
        patience?.since = TimeSource.Monotonic.markNow()
    }

    /** True when the fill has been superseded and must stop where it stands. */
    fun abandoned(): Boolean = abandoned.invoke()

    /** [abandoned], as the checkpoints spell it: stop by unwinding, so nothing partial can be published. */
    fun checkAbandoned() {
        if (abandoned.invoke()) throw PlanAbandoned()
    }

    /** Millis left, 0 when [expired]. */
    fun remainingMillis(): Long {
        val d = deadline ?: return 0L
        val left = -d.elapsedNow().inWholeMilliseconds
        val patient = patience?.let { it.millis - it.since.elapsedNow().inWholeMilliseconds } ?: Long.MAX_VALUE
        return minOf(left, patient).coerceAtLeast(0L)
    }

    /** A budget ending at the earlier of this one and [millis] from now — to give one pass a share of the rest. */
    fun share(millis: Long): SearchBudget =
        if (deadline == null) SearchBudget(null, abandoned)
        else {
            // The deadline alone is shared out; the patience is the same one (above).
            val left = minOf(-deadline.elapsedNow().inWholeMilliseconds, millis).coerceAtLeast(0L)
            if (left <= 0L) SearchBudget(null, abandoned)
            else SearchBudget(TimeSource.Monotonic.markNow() + left.milliseconds, abandoned, patience)
        }

    companion object {
        /** No time past the step-bounded passes: display fills, adoptions, and the tests that pin the rollout. */
        val NONE: SearchBudget = SearchBudget(null, abandoned = { false })

        fun of(millis: Long, abandoned: () -> Boolean = { false }): SearchBudget =
            if (millis <= 0L) SearchBudget(null, abandoned)
            else SearchBudget(TimeSource.Monotonic.markNow() + millis.milliseconds, abandoned)

        /**
         * `docs/scheduler_requirements.md`, the "allocated resources quantity" hyperparameter **`q`**: a step's search
         * granted [grantMillis] (already `q` of what the pace leaves it, `SchedulerEngine.pacedStepSearchMillis`) that,
         * below `q = 1`, **stops once it has gone `q²` of its grant without finding anything better** — `pace · q³`.
         * At 1 it uses all the time the pace allows, as the requirements say; lower, it spends its time where it is
         * finding gains and gives the rest back.
         *
         * Measured 2026-11-21 on 4-, 12- and 30-task trees: the searches of 140 ten-minute steps at `q = 0.25`
         * improved the plan once, by 0.36 % of the score, and a day planned at 0.25 scored the same on the
         * simulations' measure as one planned at 0; spending every grant whole made one run to `now + 168 h` 31 min
         * of wall time and 45 min of CPU, started again at every change of the rules.
         */
        fun paced(grantMillis: Long, q: Double, abandoned: () -> Boolean = { false }): SearchBudget {
            if (grantMillis <= 0L) return SearchBudget(null, abandoned)
            val deadline = TimeSource.Monotonic.markNow() + grantMillis.milliseconds
            val share = if (q.isNaN()) 0.0 else q.coerceIn(0.0, 1.0)
            if (share >= 1.0) return SearchBudget(deadline, abandoned)
            return SearchBudget(deadline, abandoned, Patience((grantMillis * share * share).toLong()))
        }
    }
}

/**
 * What the extra passes did for one fill — read by the engine to stop paying for a search that cannot finish
 * (`docs/invariants/scheduler.md` § *Progressive Calculation*).
 */
data class SearchReport(
    /** The exhaustive search finished: nothing over the candidate lengths scores better than the result. */
    val certified: Boolean = false,
    /** The external solver found a continuation better than the rollout policy's and the improver's. */
    val solverImproved: Boolean = false,
    /** Extra time was granted and ran out before either pass could say the result is the best. */
    val exhausted: Boolean = false,
    /** Wall time the extra passes spent, millis — kept apart from what the fill's own passes cost. */
    val searchMillis: Long = 0,
)

/**
 * `docs/invariants/scheduler.md` § *The best score*: **a platform's own solver**, asked to lower the score of a
 * continuation the rollout policy and the improver already built. The desktop provides one (OR-Tools, `jvmMain`);
 * other platforms provide none ([platformScheduleSolver] is null).
 *
 * It is never trusted: whatever it returns is re-scored by [ScoreModel] and kept only when the score of the WHOLE
 * continuation goes down, and it must keep every hard constraint (resilience 0, pre-placed tasks,
 * the first run §7/§13 decided) — the optimizer checks, and drops an answer that does not.
 */
interface ExternalScheduleSolver {
    /** A short name for diagnostics. */
    val name: String

    /**
     * A continuation from [start] to [untilU] scoring lower than [incumbent], or null when none was found within
     * [budget]. [pinFirstRun]: the first run of [incumbent] was decided by §7/§13 and must be kept as it is.
     */
    fun improve(
        model: ScoreModel,
        start: ScoreCursor,
        untilU: Double,
        incumbent: List<ScheduleOptimizer.Run>,
        pinFirstRun: Boolean,
        budget: SearchBudget,
    ): List<ScheduleOptimizer.Run>?
}

/** The solver this platform provides, or null (every platform but the desktop). */
internal expect fun platformScheduleSolver(): ExternalScheduleSolver?
