package org.example.project

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.example.project.scheduler.domain.BankedBreak
import org.example.project.scheduler.domain.BreakMachine
import org.example.project.scheduler.domain.DynamicPeriods
import org.example.project.scheduler.domain.FrozenScreenBreaks
import org.example.project.scheduler.domain.PeriodKinds
import org.example.project.scheduler.domain.SchedulerDomain
import org.example.project.scheduler.engine.SchedulerEngine
import org.example.project.scheduler.model.SleepSchedule
import org.example.project.scheduler.model.TaskTimeRange
import org.example.project.scheduler.persistence.FrozenScreenBreakStore
import org.example.project.scheduler.state.SchedulerIntent
import org.example.project.scheduler.state.SchedulerReducer
import org.example.project.scheduler.state.SchedulerState
import org.example.project.scheduler.ui.TaskSchedulerViewModel
import org.example.project.time.AppClock

/**
 * Anomaly 2026-10-06: *"I tried to drag a past 15min screen break but I couldn't."* — and the user's rule of
 * 2026-10-07: *"Any block can be dragged, it then gets a blue outline."* A screen break is no panel: behind the line
 * it is this device's banked record, ahead of it it is where the machine the line carries puts it. One rule moves it
 * on either side ([SchedulerDomain.placeBreakByHand]), `docs/scheduler_requirements.md` § *frozen past*: *"The
 * schedule at t < now line never changes … with only two exceptions: When the user or a program wants to rewrite
 * history"*.
 */
class MovedPastBreakTest {
    private val SEC = 1_000L
    private val MIN = 60 * SEC
    private val HOUR = 60 * MIN
    private val T0 = 1_700_000_000_000L
    private val L20 = DynamicPeriods.LABEL_20S
    private val L15 = DynamicPeriods.LABEL_15MIN

    @AfterTest
    fun resetSeams() {
        SchedulerReducer.frozenScreenBreaks = { null }
        SchedulerReducer.tpMode = { DynamicPeriods.MODE_AT_SCREEN }
    }

    private val specs = SchedulerDomain.dynamicPeriodSpecs(SchedulerDomain.DEFAULT_SCREEN_BREAKS)
    private val lookAway = BankedBreak(L20, T0 - 3 * HOUR, T0 - 3 * HOUR + 20 * SEC)
    private val pose = BankedBreak(L15, T0 - 30 * MIN, T0 - 15 * MIN)

    /** A machine at the line with nothing due for a while: every bar well ahead. */
    private val machine =
        BreakMachine.State(
            atMillis = T0,
            baseMode = DynamicPeriods.MODE_ON_BREAK,
            bars = mapOf(L20 to T0 + 10 * MIN, DynamicPeriods.LABEL_5MIN to T0 + 3 * HOUR, L15 to T0 + 2 * HOUR),
        )
    private val record = FrozenScreenBreaks(listOf(lookAway, pose), T0, T0, machine = machine)

    private fun place(b: BankedBreak, to: Long, now: Long = T0, from: FrozenScreenBreaks? = record) =
        SchedulerDomain.placeBreakByHand(from, b.label, b.startMillis, b.endMillis, to, now)

    // ---- Behind the line -------------------------------------------------------------------------------------------

    @Test
    fun a_past_break_put_elsewhere_in_the_past_is_banked_there_as_the_users() {
        val step = assertNotNull(place(lookAway, T0 - 10 * MIN))
        val moved = BankedBreak(L20, T0 - 10 * MIN, T0 - 10 * MIN + 20 * SEC, byHand = true)
        assertEquals(listOf(pose, moved), step.record.breaks, "moved whole, the record kept sorted")
        assertEquals(listOf(moved), step.added)
        assertEquals(listOf(lookAway), step.removed)
        assertEquals(record.untilMillis, step.record.untilMillis, "the front is the line's, not the hand's")
    }

    @Test
    fun a_break_the_user_put_there_is_outlined_in_blue_and_one_the_line_banked_in_grey() {
        val step = assertNotNull(place(pose, T0 - 2 * HOUR))
        val panels = SchedulerDomain.takenScreenBreakPanels(SchedulerDomain.DEFAULT_SCREEN_BREAKS, T0 - 4 * HOUR, T0, step.record)
        assertEquals(
            listOf(SchedulerDomain.PanelOutline.Dynamic, SchedulerDomain.PanelOutline.User),
            panels.sortedBy { it.startEpochMillis }.map(SchedulerDomain::panelOutline),
        )
    }

    @Test
    fun a_past_break_dragged_across_the_line_stays_behind_it_against_the_line() {
        val step = assertNotNull(place(pose, T0 - 5 * MIN))
        assertEquals(BankedBreak(L15, T0 - 15 * MIN, T0, byHand = true), step.added.single())
    }

    @Test
    fun a_move_that_cannot_be_is_refused() {
        // Over another banked break: two breaks are never taken at once.
        assertNull(place(lookAway, pose.startMillis + MIN))
        // The break the line is still inside is the line's own.
        assertNull(place(pose, T0 - 2 * HOUR, now = pose.endMillis - MIN))
        // The break the line drags (`]line, line + d]`, not banked) too.
        assertNull(SchedulerDomain.placeBreakByHand(record, L15, T0, T0 + 15 * MIN, T0 + HOUR, T0))
        // One put back where it was, and no record at all.
        assertNull(place(pose, pose.startMillis))
        assertNull(place(pose, T0 - 2 * HOUR, from = null))
    }

    // ---- Ahead of the line -----------------------------------------------------------------------------------------

    private fun next(state: BreakMachine.State, label: String) = BreakMachine.dueOf(state, label, emptyList())

    @Test
    fun a_break_ahead_put_later_is_not_laid_where_it_was_and_falls_due_where_it_was_put() {
        val due = assertNotNull(next(machine, L15))
        val step = assertNotNull(SchedulerDomain.placeBreakByHand(record, L15, due, due + 15 * MIN, due + HOUR, T0))
        assertEquals(record.breaks, step.record.breaks, "nothing is banked: it has not been taken")
        val placed = assertNotNull(step.record.machine)
        assertEquals(due + HOUR, next(placed, L15), "what stands where it was cannot be the 15 min break")
        // The line gets there: the break starts where the hand put it, and is banked as the user's.
        val events = ArrayList<BreakMachine.Event>()
        val after = BreakMachine.advance(placed, due + HOUR + MIN, emptyList(), specs, events)
        val started = events.filterIsInstance<BreakMachine.Event.Started>().single { it.label == L15 }
        assertEquals(due + HOUR, started.startMillis)
        assertTrue(started.byHand)
        assertTrue(after.placed.isEmpty(), "the placement is spent once its occurrence started")
    }

    @Test
    fun a_break_ahead_put_earlier_falls_due_there() {
        val due = assertNotNull(next(machine, L15))
        val step = assertNotNull(SchedulerDomain.placeBreakByHand(record, L15, due, due + 15 * MIN, T0 + 30 * MIN, T0))
        assertEquals(T0 + 30 * MIN, next(assertNotNull(step.record.machine), L15))
    }

    @Test
    fun a_break_put_again_is_still_the_occurrence_it_was() {
        val due = assertNotNull(next(machine, L15))
        val once = assertNotNull(SchedulerDomain.placeBreakByHand(record, L15, due, due + 15 * MIN, due + HOUR, T0))
        val twice =
            assertNotNull(SchedulerDomain.placeBreakByHand(once.record, L15, due + HOUR, due + HOUR + 15 * MIN, due + 2 * HOUR, T0))
        val placed = assertNotNull(twice.record.machine)
        assertEquals(listOf(BreakMachine.HandPlaced(L15, due, due + 2 * HOUR)), placed.placed)
        assertEquals(due + 2 * HOUR, next(placed, L15))
    }

    @Test
    fun a_break_ahead_put_behind_the_line_was_taken_there_and_a_past_one_put_ahead_is_un_banked() {
        val due = assertNotNull(next(machine, L15))
        val taken = assertNotNull(SchedulerDomain.placeBreakByHand(record.copy(breaks = listOf(lookAway)), L15, due, due + 15 * MIN, T0 - HOUR, T0))
        assertEquals(BankedBreak(L15, T0 - HOUR, T0 - 45 * MIN, byHand = true), taken.added.single())

        val owed = assertNotNull(place(pose, T0 + 40 * MIN))
        assertEquals(listOf(lookAway), owed.record.breaks)
        assertEquals(listOf(pose), owed.removed)
        assertEquals(listOf(BreakMachine.HandPlaced(L15, T0, T0 + 40 * MIN)), owed.record.machine?.placed)
    }

    @Test
    fun a_machine_state_an_older_build_wrote_has_no_placement() {
        val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
        val old = """{"atMillis":$T0,"baseMode":${DynamicPeriods.MODE_AT_SCREEN},"bars":{"$L20":${T0 + MIN}}}"""
        assertEquals(emptyList(), json.decodeFromString(BreakMachine.State.serializer(), old).placed)
    }

    // ---- The engine: the record's one writer ----------------------------------------------------------------------

    private fun oneTask(): SchedulerState {
        var s = SchedulerState.empty()
        s = SchedulerReducer.reduce(s, SchedulerIntent.SetCellTitle(s.lists[s.rootListId]!!.cellIds[0], "Work"))
        return s.copy(sleep = SleepSchedule(sleepDurationMinutes = 0), screenBreaks = SchedulerDomain.DEFAULT_SCREEN_BREAKS)
    }

    private class MemoryStore(var frozen: FrozenScreenBreaks?) : FrozenScreenBreakStore {
        val added = mutableListOf<BankedBreak>()
        val removed = mutableListOf<BankedBreak>()

        override fun loadFrozenScreenBreaks() = frozen
        override fun saveFrozenScreenBreaks(
            added: List<BankedBreak>,
            untilMillis: Long,
            lineMillis: Long,
            pruneBeforeMillis: Long,
            removed: List<BankedBreak>,
            machine: BreakMachine.State?,
        ) {
            this.added += added
            this.removed += removed
        }
    }

    @Test
    fun the_engine_rewrites_the_record_clears_the_work_under_the_break_and_rebuilds_the_bars() = runTest {
        val scheduler = testScheduler
        var s = oneTask().copy(automaticSchedule = false)
        val work = s.tasks.values.single { it.title == "Work" }.id
        // Work recorded over the hour the break is dragged into.
        s = s.copy(tasks = s.tasks + (work to s.tasks.getValue(work).copy(record = listOf(TaskTimeRange(T0 - 2 * HOUR, T0 - HOUR)))))
        val carried =
            machine.copy(baseMode = DynamicPeriods.MODE_AT_SCREEN, bars = machine.bars + (L15 to T0 + 5 * HOUR) + (DynamicPeriods.LABEL_5MIN to T0 + 30 * MIN))
        val store = MemoryStore(record.copy(machine = carried))
        val clock = object : AppClock { override fun nowMillis(): Long = T0 + scheduler.currentTime }
        val vm = TaskSchedulerViewModel(initial = s, store = null, saveDispatcher = Dispatchers.Default)
        val engine =
            SchedulerEngine(
                vm = vm,
                clock = clock,
                scope = backgroundScope,
                screenActive = { true },
                speak = {},
                postNotification = { _, _, _ -> },
                clearNotifications = {},
                lockedIntervalsQuery = { _, _ -> emptyList() },
                frozenBreakStore = store,
            )
        engine.start()
        advanceTimeBy(5 * SEC)
        runCurrent()
        store.added.clear()
        store.removed.clear()

        // The 15 min break of half an hour ago is dragged an hour back.
        engine.placeBreakByHand(PeriodKinds.BREAK_15MIN, pose.startMillis, pose.endMillis, pose.startMillis - HOUR)
        runCurrent()

        val moved = BankedBreak(L15, pose.startMillis - HOUR, pose.endMillis - HOUR, byHand = true)
        assertEquals(listOf(lookAway, moved), engine.frozenBreaks.value?.breaks)
        assertEquals(listOf(moved), store.added, "the store is told what was banked")
        assertEquals(listOf(pose), store.removed, "and what was un-banked")
        assertEquals(
            listOf(TaskTimeRange(T0 - 2 * HOUR, moved.startMillis), TaskTimeRange(moved.endMillis, T0 - HOUR)),
            vm.state.value.tasks.getValue(work).record.sortedBy { it.startEpochMillis },
            "a banked break and recorded work never overlap",
        )
        // History rewritten behind the line: the bars are what the history NOW says.
        val bar = engine.frozenBreaks.value?.machine?.bars?.get(L15)
        assertEquals(
            BreakMachine.rebuildFromHistory(carried, emptyList(), specs, banked = listOf(lookAway, pose)).bars.getValue(L15) - HOUR,
            bar,
            "a 15 min break moved an hour back is owed an hour sooner",
        )
    }
}
