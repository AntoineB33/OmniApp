package org.example.project

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlinx.datetime.TimeZone
import org.example.project.scheduler.domain.SchedulerDomain
import org.example.project.scheduler.model.TaskId
import org.example.project.scheduler.persistence.SchedulerStateCodec
import org.example.project.scheduler.state.DEFAULT_MINIMUM_TIME_WEIGHT
import org.example.project.scheduler.state.MAX_MINIMUM_TIME_WEIGHT
import org.example.project.scheduler.state.SchedulerIntent
import org.example.project.scheduler.state.SchedulerReducer
import org.example.project.scheduler.state.SchedulerState
import org.example.project.scheduler.sync.SnapshotMerge

/**
 * User rule 2026-10-06: **the balance of the scheduler's two criteria is the account's to set**
 * (`docs/scheduler_score.md` § *The score*: `J = J_1 + w·J_2`, [SchedulerState.minimumTimeWeight]) — following the
 * priority percentages over the smallest window against holding each panel at its minimum execution time.
 */
class MinimumTimeWeightTest {
    private val MIN = 60_000L
    private val HOUR = 60 * MIN
    private val NOW = 1_000_000_000_000L

    private fun r(state: SchedulerState, intent: SchedulerIntent) = SchedulerReducer.reduce(state, intent)

    /** Tasks at the given (weight, minimum minutes), under "main". */
    private fun account(vararg tasks: Pair<Double, Int>): Pair<SchedulerState, List<TaskId>> {
        var s = SchedulerState.empty()
        tasks.forEachIndexed { i, (weight, minimum) ->
            val cell = s.lists[s.rootListId]!!.cellIds[i]
            s = r(s, SchedulerIntent.SetCellTitle(cell, "T$i"))
            s = r(s, SchedulerIntent.SetPriorityWeight(cell, 0, weight))
            s = r(s, SchedulerIntent.SetTaskMinimumTime(s.cells[cell]!!.taskId!!, minimum))
        }
        return s to tasks.indices.map { i -> s.tasks.keys.first { s.tasks[it]!!.title == "T$i" } }
    }

    /** How many of the plan's panels are short of their task's minimum time, over [hours]. */
    private fun shortPanels(state: SchedulerState, hours: Int): Int =
        SchedulerDomain.fillSchedule(state, NOW, TimeZone.UTC, horizonMillis = NOW + hours * HOUR)
            .filter { it.auto && !it.isRestrictivePeriod }
            // The last panel is cut by the horizon, not by the score.
            .sortedBy { it.startEpochMillis }.dropLast(1)
            .count { it.endEpochMillis - it.startEpochMillis < (state.tasks[it.taskId]?.minimumMinutes ?: 0) * MIN - MIN }

    @Test
    fun the_balance_is_an_account_setting_kept_in_its_bounds_persisted_and_synced() {
        val base = SchedulerState.empty()
        assertEquals(1.0, DEFAULT_MINIMUM_TIME_WEIGHT)
        assertEquals(DEFAULT_MINIMUM_TIME_WEIGHT, base.minimumTimeWeight)
        fun set(s: SchedulerState, w: Double) = r(s, SchedulerIntent.SetMinimumTimeWeight(w))
        assertEquals(0.25, set(base, 0.25).minimumTimeWeight)
        assertEquals(0.0, set(base, -3.0).minimumTimeWeight, "never negative: a panel short of its minimum is never rewarded")
        assertEquals(MAX_MINIMUM_TIME_WEIGHT, set(base, 1e9).minimumTimeWeight)
        assertEquals(DEFAULT_MINIMUM_TIME_WEIGHT, set(set(base, 4.0), Double.NaN).minimumTimeWeight, "not a number: the default")
        assertTrue(set(base, DEFAULT_MINIMUM_TIME_WEIGHT) === base, "nothing changed")

        val changed = set(base, 4.0)
        // Persisted, and SYNCED: plans made on two devices compete on one score, so the balance is the account's.
        assertEquals(4.0, SchedulerStateCodec.decode(SchedulerStateCodec.encode(changed))!!.minimumTimeWeight)
        assertNotEquals(SchedulerStateCodec.syncFingerprint(base), SchedulerStateCodec.syncFingerprint(changed))
        // The three-way merge takes the side that changed it.
        assertEquals(4.0, SnapshotMerge.mergeStates(base, changed, base).minimumTimeWeight)
        assertEquals(4.0, SnapshotMerge.mergeStates(base, base, changed).minimumTimeWeight)
    }

    @Test
    fun a_payload_written_before_the_setting_loads_at_the_default_and_a_wild_value_is_healed() {
        val previous = SchedulerStateCodec.encode(SchedulerState.empty())
        assertTrue("minimumTimeWeight" !in previous, "an untouched balance writes no field — the older shape")
        assertEquals(DEFAULT_MINIMUM_TIME_WEIGHT, SchedulerStateCodec.decode(previous)!!.minimumTimeWeight)
        val wild = SchedulerStateCodec.encode(SchedulerState.empty().copy(minimumTimeWeight = 4.0)).replace("4.0", "-7.5")
        assertTrue("-7.5" in wild)
        assertEquals(0.0, SchedulerStateCodec.decode(wild)!!.minimumTimeWeight, "kept in its bounds on the way in")
    }

    @Test
    fun another_balance_is_another_set_of_rules_and_the_default_hashes_as_it_always_did() {
        val (s, _) = account(1.0 to 30, 1.0 to 15)
        val before = SchedulerDomain.schedulingSignature(s)
        assertEquals(before, SchedulerDomain.schedulingSignature(s.copy(minimumTimeWeight = DEFAULT_MINIMUM_TIME_WEIGHT)))
        assertNotEquals(before, SchedulerDomain.schedulingSignature(s.copy(minimumTimeWeight = 0.0)), "it re-plans")
    }

    @Test
    fun at_zero_the_percentages_are_followed_alone_and_panels_are_cut_short_of_their_minimum() {
        // A crowded account: eight tasks whose minimums go from five minutes to three quarters of an hour. Holding
        // every panel at its minimum makes the lags swing wide; with no price on a short panel the plan cuts them.
        val (s, _) = account(0.5 to 5, 3.0 to 20, 1.0 to 30, 3.0 to 10, 0.5 to 45, 5.0 to 5, 5.0 to 5, 2.0 to 20)
        val atDefault = shortPanels(s, hours = 24)
        val atZero = shortPanels(s.copy(minimumTimeWeight = 0.0), hours = 24)
        val heavy = shortPanels(s.copy(minimumTimeWeight = 10.0), hours = 24)
        assertTrue(atZero > atDefault, "weight 0 cut $atZero panels short, the default $atDefault")
        assertTrue(atZero >= 5, "with no price on it, short panels are many: $atZero")
        assertTrue(heavy <= atDefault, "a heavier weight never cuts more: $heavy against $atDefault")
    }
}
