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
class SearchBudget private constructor(private val deadline: TimeMark?) {
    /** True once no further time may be spent (always true for [NONE]). */
    fun expired(): Boolean = deadline?.hasPassedNow() ?: true

    /** Millis left, 0 when [expired]. */
    fun remainingMillis(): Long {
        val d = deadline ?: return 0L
        val left = -d.elapsedNow().inWholeMilliseconds
        return left.coerceAtLeast(0L)
    }

    /** A budget ending at the earlier of this one and [millis] from now — to give one pass a share of the rest. */
    fun share(millis: Long): SearchBudget =
        if (deadline == null) NONE else of(minOf(remainingMillis(), millis.coerceAtLeast(0L)))

    companion object {
        /** No time past the step-bounded passes: display fills, adoptions, and the tests that pin the rollout. */
        val NONE: SearchBudget = SearchBudget(null)

        fun of(millis: Long): SearchBudget =
            if (millis <= 0L) NONE else SearchBudget(TimeSource.Monotonic.markNow() + millis.milliseconds)
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
 * continuation goes down, and it must keep every hard constraint (§ *No idling*, resilience 0, pre-placed tasks,
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
