package org.example.project

import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.example.project.scheduler.domain.PeriodKinds
import org.example.project.scheduler.domain.SchedulerDomain
import org.example.project.scheduler.model.TaskPanel
import org.example.project.scheduler.model.TaskTimeRange
import org.example.project.scheduler.state.SchedulerIntent
import org.example.project.scheduler.state.SchedulerReducer
import org.example.project.scheduler.state.SchedulerState

/**
 * Account 3, 2026-10-03, 11:27:36: a "Look away now" the app CONDUCTED was drawn with a task panel inside it and no
 * "no screen", no "no computer unlocked" / "not on a computer" over it. It was recorded as an `inactivity` period —
 * which carries nothing — while the automatic look-away is a "20s screen break" that allows no task and lays its
 * "no screen" where it is drawn; and recorded work was only ever cut out of the BANKED breaks, which a conducted one
 * is not. A conducted look-away is a 20 s screen break like the ones the app places
 * (`docs/invariants/screen-breaks.md` § *"Look away now" is a conducted 20 s break*).
 */
class ConductedBreakIsABreakTest {
    private val SEC = 1_000L
    private val T0 = 1_700_000_000_000L

    @BeforeTest
    @AfterTest
    fun resetSeams() {
        SchedulerReducer.frozenScreenBreaks = { null }
        SchedulerReducer.noScreenEvidence = { emptyList() }
    }

    private fun withATask(): Pair<SchedulerState, org.example.project.scheduler.model.TaskId> {
        val empty = SchedulerState.empty()
        val s = SchedulerReducer.reduce(empty, SchedulerIntent.SetCellTitle(empty.lists[empty.rootListId]!!.cellIds[0], "Work"))
        return s to s.cells.getValue(empty.lists[empty.rootListId]!!.cellIds[0]).taskId!!
    }

    private fun conducted(s: SchedulerState) = s.panels.single { it.conductedBreak }

    @Test
    fun a_conducted_look_away_is_of_the_20s_break_kind() {
        val (s, _) = withATask()
        val after = SchedulerReducer.reduce(s, SchedulerIntent.RecordConductedBreak("look 20 feet away", T0, T0 + 20 * SEC))
        assertEquals(PeriodKinds.BREAK_20S, conducted(after).restrictiveKind)
    }

    @Test
    fun one_an_older_build_recorded_as_inactivity_is_healed_to_the_20s_break_kind() {
        val legacy =
            TaskPanel(
                "7", null, "look 20 feet away", T0, T0 + 20 * SEC,
                inactivity = true, periodKind = PeriodKinds.INACTIVITY, conductedBreak = true,
            )
        assertEquals(PeriodKinds.BREAK_20S, legacy.restrictiveKind)
        // A 20-second span the user DREW stays what it is.
        assertEquals(PeriodKinds.INACTIVITY, legacy.copy(conductedBreak = false).restrictiveKind)
    }

    /** CLAUDE.md § *Persisted-DB compatibility*: what an older build wrote — the 11:27:36 break — loads healed. */
    @Test
    fun a_payload_an_older_build_wrote_loads_with_the_work_out_of_the_break() {
        val (s0, task) = withATask()
        val legacy =
            TaskPanel(
                "7", null, "look 20 feet away", T0, T0 + 20 * SEC,
                inactivity = true, periodKind = PeriodKinds.INACTIVITY, conductedBreak = true,
            )
        val written =
            s0.copy(
                panels = s0.panels + legacy,
                tasks = s0.tasks + (task to s0.tasks.getValue(task).copy(record = listOf(TaskTimeRange(T0 - 60 * SEC, T0 + 60 * SEC)))),
            )
        val payload = org.example.project.scheduler.persistence.SchedulerStateCodec.encodeSnapshot(written)
        assertTrue(payload.statePayload.contains("\"inactivity\""), "the payload holds the old kind")
        val loaded = org.example.project.scheduler.persistence.SchedulerStateCodec.decodeSnapshot(payload)!!
        assertEquals(PeriodKinds.BREAK_20S, conducted(loaded).restrictiveKind)
        assertEquals(
            listOf(TaskTimeRange(T0 - 60 * SEC, T0), TaskTimeRange(T0 + 20 * SEC, T0 + 60 * SEC)),
            loaded.tasks.getValue(task).record,
        )
    }

    @Test
    fun work_already_recorded_through_the_break_is_cut_out_of_it() {
        val (s0, task) = withATask()
        // The line banked the run in progress while the break ran — before the break was a fact.
        val s = s0.copy(tasks = s0.tasks + (task to s0.tasks.getValue(task).copy(record = listOf(TaskTimeRange(T0 - 60 * SEC, T0 + 60 * SEC)))))
        val after = SchedulerReducer.reduce(s, SchedulerIntent.RecordConductedBreak("look 20 feet away", T0, T0 + 20 * SEC))
        assertEquals(
            listOf(TaskTimeRange(T0 - 60 * SEC, T0), TaskTimeRange(T0 + 20 * SEC, T0 + 60 * SEC)),
            after.tasks.getValue(task).record,
        )
    }

    @Test
    fun work_banked_after_the_break_is_recorded_around_it() {
        val (s0, task) = withATask()
        // The plan ran the task straight across the break (the plan is not redone for those twenty seconds).
        val run = TaskPanel("auto/1", task, "Work", T0 - 60 * SEC, T0 + 60 * SEC, auto = true)
        var s = s0.copy(panels = s0.panels + run)
        s = SchedulerReducer.reduce(s, SchedulerIntent.RecordConductedBreak("look 20 feet away", T0, T0 + 20 * SEC))
        s = SchedulerReducer.reduce(s, SchedulerIntent.AdvanceSchedule(T0 + 61 * SEC))
        val record = s.tasks.getValue(task).record
        assertTrue(record.isNotEmpty(), "the run was banked")
        assertTrue(
            record.none { it.startEpochMillis < T0 + 20 * SEC && T0 < it.endEpochMillis },
            "work was recorded inside the conducted break: $record",
        )
    }

    @Test
    fun a_conducted_look_away_states_its_no_screen_and_so_lays_the_layers() {
        val (s0, _) = withATask()
        val s = SchedulerReducer.reduce(s0, SchedulerIntent.RecordConductedBreak("look 20 feet away", T0, T0 + 20 * SEC))
        val config = s.periodKindConfig
        val stated = SchedulerDomain.statedKindRegions(s.panels, config)
        assertTrue(
            stated[PeriodKinds.NO_SCREEN].orEmpty().any { it.startEpochMillis <= T0 && it.endEpochMillis >= T0 + 20 * SEC },
            "no \"no screen\" over the conducted break: $stated",
        )
        // The account's "when no screen then …" rule lays a layer over it — the same answer a placed break gets.
        val placed = SchedulerDomain.statedKindRegions(emptyList(), config, breaks = listOf(TaskTimeRange(T0, T0 + 20 * SEC)))
        for (layer in SchedulerDomain.ActivityLayer.entries) {
            for (kind in listOf(PeriodKinds.layerKind(layer), PeriodKinds.fakeLayerKind(layer))) {
                assertEquals(placed[kind].orEmpty(), stated[kind].orEmpty(), "$kind differs from a placed break's")
            }
        }
        assertTrue(
            SchedulerDomain.ActivityLayer.entries.any { layer -> stated[PeriodKinds.layerKind(layer)].orEmpty().isNotEmpty() || stated[PeriodKinds.fakeLayerKind(layer)].orEmpty().isNotEmpty() },
            "no layer laid over the conducted break: $stated",
        )
    }
}
