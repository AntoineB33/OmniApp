package org.example.project

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.example.project.scheduler.domain.DynamicPeriods
import org.example.project.scheduler.domain.SchedulerDomain
import org.example.project.scheduler.model.ScreenBreak
import org.example.project.scheduler.model.TaskId
import org.example.project.scheduler.state.SchedulerIntent
import org.example.project.scheduler.state.SchedulerReducer
import org.example.project.scheduler.state.SchedulerState

/**
 * `side-dev/README.md` § *$t_p$ 2 modes*, **wired end to end** — the mode is decided in one place and reaches
 * the plan through one seam.
 *
 * The rule is the user's: mode 1 while any device of the account is unlocked, mode 2 otherwise. It used to be
 * read off the Sleep/Work toggle (`SchedulerState.sleepingUntilMillis`), which is a statement about the night
 * and not about a screen — and mode 2's own cover was computed by `DynamicPeriods.periods` and then thrown
 * away, because `SchedulerDomain.dynamicPeriodPanels` called `instances` instead. Neither mode reached the
 * app at all. What is pinned here is that both now do.
 *
 * Where the mode COMES FROM ([SchedulerDomain.anyDeviceUnlockedAt]) is pinned in [DynamicPeriodsTest]; this is
 * about where it GOES.
 */
class TpModeTest {

    private val MIN = 60_000L
    private val HOUR = 60 * MIN
    private val NOW = 1_000_000_000_000L

    @AfterTest
    fun resetSeam() {
        // A global var: leaving one installed would put every later test in the wrong mode.
        SchedulerReducer.tpMode = { DynamicPeriods.MODE_AT_SCREEN }
    }

    /** One on-screen task with a 45-minute minimum, and the account's three breaks. */
    private fun oneTask(): Pair<SchedulerState, TaskId> {
        var s = SchedulerState.empty()
        val c0 = s.lists[s.rootListId]!!.cellIds[0]
        s = SchedulerReducer.reduce(s, SchedulerIntent.SetCellTitle(c0, "Solo"))
        val solo = s.tasks.keys.first { s.tasks[it]!!.title == "Solo" }
        s = SchedulerReducer.reduce(s, SchedulerIntent.SetTaskMinimumTime(solo, 45))
        s = SchedulerReducer.reduce(
            s,
            SchedulerIntent.SetScreenBreaks(
                listOf(ScreenBreak("5min", intervalMillis = HOUR, durationMillis = 5 * MIN, restBreak = true)),
            ),
        )
        return s to solo
    }

    @Test
    fun the_default_is_mode_one_and_it_keeps_every_dynamic_period_off_the_line() {
        // Mode 1 is what a shell with no device signal assumes — somebody is at a screen — and it is what the
        // reducer's seam defaults to. Its rule: `t_p` is never covered.
        val (s, _) = oneTask()
        val filled = SchedulerReducer.reduce(s, SchedulerIntent.RefreshSchedule(NOW))
        val periods = filled.panels.filter { it.screenBreak }
        assertTrue(periods.isNotEmpty(), "there must be periods for this to be about")
        assertTrue(
            periods.none { it.startEpochMillis <= NOW && NOW < it.endEpochMillis },
            "mode 1: no dynamic period may cover the line — it is pushed ahead of it instead",
        )
        // …and the one the line has swept is owed AT the line, which is where the drag leaves it.
        assertEquals(
            NOW + 1,
            periods.minOf { it.startEpochMillis },
            "the swept chain sits on the line, as the half-open (t_p, t_p + duration]",
        )
    }

    @Test
    fun the_seam_is_what_the_fill_reads_so_mode_two_stops_the_dragging() {
        // The seam the engine injects over the lock signal. In mode 2 nothing is swept — the line is not at a
        // screen — so the three sit where the recurrence bars put them, which is the bars' own answer.
        val (s, _) = oneTask()
        SchedulerReducer.tpMode = { DynamicPeriods.MODE_AWAY }
        val away = SchedulerReducer.reduce(s, SchedulerIntent.RefreshSchedule(NOW)).panels
            .filter { it.screenBreak }
            .map { it.startEpochMillis }
        val bars = SchedulerDomain.screenBreakOccurrencesBetween(
            s.screenBreaks, NOW, away.max(), anchorMillis = NOW,
        ).map { it.startEpochMillis }
        assertTrue(bars.isNotEmpty())
        assertTrue(away.containsAll(bars), "mode 2 places the three where the bars do: $away vs $bars")
    }

    @Test
    fun the_mode_no_longer_follows_the_sleep_work_toggle() {
        // The regression this replaces. The Sleep/Work toggle says the user has gone to bed, not that no
        // device is unlocked — a machine left unlocked while its owner naps is still mode 1 — so the plan must
        // be the same either way, and only the seam may change it.
        val (s, _) = oneTask()
        val working = SchedulerReducer.reduce(s, SchedulerIntent.RefreshSchedule(NOW)).panels
            .filter { it.screenBreak }.map { it.startEpochMillis }
        val sleeping =
            SchedulerReducer.reduce(
                s.copy(sleepingUntilMillis = NOW + 8 * HOUR),
                SchedulerIntent.RefreshSchedule(NOW),
            ).panels.filter { it.screenBreak }.map { it.startEpochMillis }
        assertEquals(working, sleeping)
    }
}
