package org.example.project

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.example.project.scheduler.domain.PeriodKinds
import org.example.project.scheduler.domain.PlanBlock
import org.example.project.scheduler.domain.PlanTask
import org.example.project.scheduler.domain.RestrictivePeriod
import org.example.project.scheduler.domain.ScheduleFill
import org.example.project.scheduler.domain.SchedulerDomain
import org.example.project.scheduler.domain.ScoreModel
import org.example.project.scheduler.model.TaskId

/**
 * `docs/scheduler_requirements.md` § *Priority, Granularity and Compensation*: *"The timeline is infinite forward
 * and backward"*, and `docs/scheduler_score.md`: *"`L_i(x)` is determined by the frozen past"*.
 *
 * How far back the frozen past is replayed is [ScheduleFill.pastLookbackMillis], and it is measured in the score's
 * own unit — the longest task window `Theta`, on the schedulable clock — not in wall time. A flat 168 h of wall
 * time is what shipped, and it made every task rare enough for `tau_i` to exceed it start each re-plan from a lag
 * of zero.
 */
class SchedulePastLookbackTest {
    private val MIN = 60_000L
    private val HOUR = 60 * MIN
    private val DAY = 24 * HOUR
    private val WEEK = 7 * DAY

    /** Wednesday 2026-09-02, 10:00 UTC. */
    private val NOW = 1_788_343_200_000L

    private val main = TaskId("MAIN")
    private val rare = TaskId("RARE")

    /** RARE at 0.15 % with a 30-minute minimum: `tau = 30min/0.0015` = 13.9 days, twice the old ceiling. */
    private fun tasks(rarePercent: Double = 0.15, rareMinimumMinutes: Long = 30) = listOf(
        PlanTask(main, 100.0 - rarePercent, 30 * MIN),
        PlanTask(rare, rarePercent, rareMinimumMinutes * MIN),
    )

    /** MAIN throughout, back past every window these tests replay over, but for one block of RARE. */
    private fun history(from: Long, to: Long): List<PlanBlock> = listOf(
        PlanBlock(main, NOW - 200 * DAY, from),
        PlanBlock(rare, from, to),
        PlanBlock(main, to, NOW),
    )

    private fun fill(lookback: Long, ts: List<PlanTask>, history: List<PlanBlock>) =
        ScheduleFill.run(
            ScheduleFill.Input(
                startMillis = NOW,
                horizonMillis = NOW + WEEK,
                lookbackMillis = lookback,
                ruleState = ts,
                periods = emptyList(),
                blocks = emptyList(),
                history = history,
            ),
        )

    /** RARE's lag at the line, in minutes, when the past is replayed from `NOW - lookback`. */
    private fun lagOfRare(lookback: Long, ts: List<PlanTask>, history: List<PlanBlock>): Double {
        val model = ScoreModel(ts, emptyList(), emptyList(), NOW - lookback, NOW + WEEK)
        return ScheduleFill.replay(model, history, NOW).lag[model.indexOf.getValue(rare)] / MIN
    }

    private fun lookbackFor(ts: List<PlanTask>, periods: List<RestrictivePeriod> = emptyList()): Long =
        ScheduleFill.pastLookbackMillis(
            tasks = ts,
            periods = periods,
            nowMillis = NOW,
            windows = SchedulerDomain.SCHEDULE_PAST_LOOKBACK_WINDOWS,
            floorMillis = SchedulerDomain.SCHEDULE_PAST_LOOKBACK_FLOOR_MILLIS,
            capMillis = SchedulerDomain.SCHEDULE_PAST_LOOKBACK_CAP_MILLIS,
        )

    /**
     * The regression. A 0.15 % task pre-placed for three whole days, ending nine days ago, is over-served by
     * thirty-three hours. Under the old flat 168 h ceiling none of that was replayed: the task read as *starved*
     * and was placed at the now-line. The window the rule now gives reaches past the block, so it is not.
     */
    @Test
    fun a_block_older_than_a_week_still_counts_as_served() {
        val ts = tasks()
        val served = history(NOW - 12 * DAY, NOW - 9 * DAY)
        val lookback = lookbackFor(ts)
        assertTrue(lookback > 12 * DAY, "the window reaches past a block 12 days back: ${lookback / DAY}d")

        val lag = lagOfRare(lookback, ts, served)
        assertTrue(lag > 30 * 60, "RARE reads as over-served: $lag min")
        assertNull(fill(lookback, ts, served).placements.firstOrNull { it.taskId == rare }, "RARE must not be placed")

        // What the flat ceiling did, kept as the thing this rule exists to prevent.
        val old = SchedulerDomain.SCHEDULE_PAST_LOOKBACK_FLOOR_MILLIS
        assertTrue(lagOfRare(old, ts, served) < 0.0, "the old ceiling read RARE as starved")
        assertNotNull(fill(old, ts, served).placements.firstOrNull { it.taskId == rare }, "…and placed it")
    }

    /**
     * The same rule from the other end: a task that runs once every twenty days, served nineteen days ago. The lag
     * the ceiling gave was a third short of the truth, always in the same direction — the task looks less owed than
     * it is, so it comes later than the rules say.
     */
    @Test
    fun a_lag_older_than_a_week_is_not_re_seeded_at_zero() {
        val ts = tasks()
        val served = history(NOW - 19 * DAY, NOW - 19 * DAY + 30 * MIN)
        val truth = lagOfRare(120 * DAY, ts, served)
        val now = lagOfRare(lookbackFor(ts), ts, served)
        val old = lagOfRare(SchedulerDomain.SCHEDULE_PAST_LOOKBACK_FLOOR_MILLIS, ts, served)
        // Four windows leave `e^(-4)` of the lag at the cutoff behind — under a minute here, against the six the
        // ceiling lost.
        assertTrue(abs(now - truth) < 1.0, "the lag is the full-history one: $now vs $truth min")
        assertTrue(old - truth > 4.0, "the old ceiling read RARE as far less owed: $old vs $truth min")
    }

    /** An ordinary account's `Theta` is hours, so four windows of it are well under the floor: nothing changes. */
    @Test
    fun an_ordinary_account_gets_the_floor() {
        val even = listOf(PlanTask(TaskId("A"), 50.0, 30 * MIN), PlanTask(TaskId("B"), 50.0, 30 * MIN))
        assertEquals(SchedulerDomain.SCHEDULE_PAST_LOOKBACK_FLOOR_MILLIS, lookbackFor(even))
        // Thirty tasks at an even share: Theta = 30 * 30min = 15h, four of them 2.5 days. Still the floor.
        val many = (1..30).map { PlanTask(TaskId("T$it"), 1.0, 30 * MIN) }
        assertEquals(SchedulerDomain.SCHEDULE_PAST_LOOKBACK_FLOOR_MILLIS, lookbackFor(many))
    }

    /** A leaf at a near-zero share has an enormous `tau`; the cap is what stops it costing total history. */
    @Test
    fun a_near_zero_share_is_held_at_the_cap() {
        val ts = tasks(rarePercent = 0.0001)
        assertEquals(SchedulerDomain.SCHEDULE_PAST_LOOKBACK_CAP_MILLIS, lookbackFor(ts))
    }

    /**
     * `docs/invariants/scheduler.md`: *everything the score measures is on the SCHEDULABLE clock*. A night buys the
     * window no lag, so the walk reaches past it: the span returned holds at least `4*Theta` of schedulable time,
     * whatever the account sleeps.
     */
    @Test
    fun the_window_is_measured_on_the_schedulable_clock() {
        val ts = tasks()
        val nights = (1..120).map {
            val wake = NOW - it * DAY
            RestrictivePeriod(wake - 8 * HOUR, wake, PeriodKinds.SLEEP, "Sleep")
        }
        val bare = lookbackFor(ts)
        val slept = lookbackFor(ts, nights)
        assertTrue(slept > bare, "eight hours a night pushes the window back: ${slept / DAY}d vs ${bare / DAY}d")
        // A third of every day is off the clock, so the window grows by about half again.
        assertTrue(slept >= bare * 4 / 3, "…by the sleep it has to reach past: ${slept / DAY}d")

        val model = ScoreModel(ts, emptyList(), ScheduleFill.windowsFor(nights, ts, NOW - slept, NOW), NOW - slept, NOW)
        val need = SchedulerDomain.SCHEDULE_PAST_LOOKBACK_WINDOWS * ScoreModel.thetaOf(ts)
        assertTrue(model.uAt(NOW) >= need - MIN, "the span holds 4*Theta of schedulable time: ${model.uAt(NOW)} vs $need")
    }
}
