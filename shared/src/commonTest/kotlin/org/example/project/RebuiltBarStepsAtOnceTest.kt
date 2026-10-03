package org.example.project

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.example.project.scheduler.domain.BankedBreak
import org.example.project.scheduler.domain.BreakMachine
import org.example.project.scheduler.domain.DynamicPeriods
import org.example.project.scheduler.domain.FrozenScreenBreaks
import org.example.project.scheduler.domain.SchedulerDomain
import org.example.project.scheduler.engine.SchedulerEngine
import org.example.project.scheduler.model.SleepSchedule
import org.example.project.scheduler.persistence.FrozenScreenBreakStore
import org.example.project.scheduler.state.SchedulerIntent
import org.example.project.scheduler.state.SchedulerReducer
import org.example.project.scheduler.state.SchedulerState
import org.example.project.scheduler.ui.TaskSchedulerViewModel
import org.example.project.time.AppClock

/**
 * Account 3, 2026-10-03, 11:07: a re-plan rebuilt the break machine from history and the 20 s bar fell into the past —
 * a look-away due AT the line. The calendar showed the plan's 20 s gap for it at once, but the break was only entered
 * (banked, drawn, spoken) twenty seconds later, when the next panel edge happened to wake the cue sweep: the rebuild
 * cleared the machine's armed trigger and woke nothing (`docs/invariants/screen-breaks.md` § *A re-run of the scheduler
 * drops what was deduced*: "the rebuild arms the next step, which moves the line on to the clock").
 *
 * On virtual time, so every wake-up is the engine's own. Nothing here steps the line by hand ([ReplanHealsDeducedBarTest]
 * does, which is how it never saw this).
 */
class RebuiltBarStepsAtOnceTest {
    private val SEC = 1_000L
    private val MIN = 60 * SEC
    private val HOUR = 60 * MIN
    private val T0 = 1_700_000_000_000L

    @AfterTest
    fun resetSeams() {
        SchedulerReducer.frozenScreenBreaks = { null }
        SchedulerReducer.tpMode = { DynamicPeriods.MODE_AT_SCREEN }
    }

    private class MemoryStore(var frozen: FrozenScreenBreaks?) : FrozenScreenBreakStore {
        override fun loadFrozenScreenBreaks() = frozen
        override fun saveFrozenScreenBreaks(
            added: List<BankedBreak>,
            untilMillis: Long,
            lineMillis: Long,
            pruneBeforeMillis: Long,
            removed: List<BankedBreak>,
            machine: BreakMachine.State?,
        ) {}
    }

    @Test
    fun a_look_away_a_rebuild_makes_due_is_entered_and_announced_where_the_rebuild_made_it_due() = runTest {
        val scheduler = testScheduler
        var s = SchedulerState.empty()
        s = SchedulerReducer.reduce(s, SchedulerIntent.SetCellTitle(s.lists[s.rootListId]!!.cellIds[0], "Work"))
        // The automatic schedule starts OFF, so nothing plans (and nothing rebuilds the machine) until the engine has
        // settled and the cue sweep has run — the order of account 3's launch, where "Task to do now" was said first.
        s = s.copy(
            sleep = SleepSchedule(sleepDurationMinutes = 0),
            screenBreaks = SchedulerDomain.DEFAULT_SCREEN_BREAKS,
            automaticSchedule = false,
        )
        // History: the last screen break ended 25 minutes ago, so it bars the next look-away 20 minutes after — past.
        val lastLookAway = BankedBreak(DynamicPeriods.LABEL_20S, T0 - 2 * HOUR, T0 - 2 * HOUR + 20 * SEC)
        val lastPose = BankedBreak(DynamicPeriods.LABEL_5MIN, T0 - 30 * MIN, T0 - 25 * MIN)
        val machine =
            BreakMachine.State(
                atMillis = T0,
                baseMode = DynamicPeriods.MODE_AT_SCREEN,
                bars = mapOf(
                    // The deduced bar: nothing in history sets it, so the re-plan's rebuild drops it into the past.
                    DynamicPeriods.LABEL_20S to T0 + 10 * MIN,
                    DynamicPeriods.LABEL_5MIN to lastPose.endMillis + HOUR,
                    DynamicPeriods.LABEL_15MIN to T0 + 2 * HOUR,
                ),
            )
        val posted = mutableListOf<Pair<Long, String>>()
        val clock = object : AppClock { override fun nowMillis(): Long = T0 + scheduler.currentTime }
        val vm = TaskSchedulerViewModel(initial = s, store = null, saveDispatcher = Dispatchers.Default)
        val engine =
            SchedulerEngine(
                vm = vm,
                clock = clock,
                scope = backgroundScope,
                screenActive = { true },
                speak = {},
                postNotification = { title, _, _ -> posted += clock.nowMillis() to title },
                clearNotifications = {},
                lockedIntervalsQuery = { _, _ -> emptyList() },
                frozenBreakStore = MemoryStore(FrozenScreenBreaks(listOf(lastLookAway, lastPose), T0, T0, machine = machine)),
            )
        engine.start()
        advanceTimeBy(5 * SEC)
        runCurrent()
        assertEquals(
            T0 + 10 * MIN, engine.frozenBreaks.value?.machine?.bars?.get(DynamicPeriods.LABEL_20S),
            "nothing rebuilt the machine while the schedule was off",
        )

        // The schedule is switched on: the deferred re-plan runs, and each set of rules found rebuilds the machine from
        // history — the 20 s bar falls into the past. Find the instant it does.
        vm.dispatch(SchedulerIntent.SetAutomaticSchedule(true))
        var rebuiltAt: Long? = null
        while (rebuiltAt == null && scheduler.currentTime < 5 * MIN) {
            advanceTimeBy(100)
            runCurrent()
            val bar = engine.frozenBreaks.value?.machine?.bars?.get(DynamicPeriods.LABEL_20S)
            if (bar != null && bar < T0) rebuiltAt = clock.nowMillis()
        }
        assertNotNull(rebuiltAt, "no re-plan rebuilt the 20 s bar from history")

        // From there, the look-away is due at the line: the machine must enter it and the cue must be said at once —
        // not at whatever instant some other trigger happens to wake the engine.
        advanceTimeBy(500)
        runCurrent()
        val entered = engine.frozenBreaks.value?.breaks?.lastOrNull { it.label == DynamicPeriods.LABEL_20S }
        assertNotNull(entered)
        assertTrue(
            entered.startMillis >= rebuiltAt - 200 && entered.startMillis <= rebuiltAt + 200,
            "the look-away was entered at ${entered.startMillis - T0} ms, the rebuild made it due at ${rebuiltAt - T0} ms",
        )
        val cue = posted.firstOrNull { it.second == "Screen break" }
        assertNotNull(cue, "the look-away was entered but never announced: $posted")
        assertEquals(entered.startMillis, cue.first, "announced where it was entered")
    }
}
