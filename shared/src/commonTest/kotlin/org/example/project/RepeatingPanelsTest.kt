package org.example.project

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Instant
import org.example.project.scheduler.domain.PanelRepeats
import org.example.project.scheduler.domain.PeriodKinds
import org.example.project.scheduler.domain.SchedulerDomain
import org.example.project.scheduler.model.PanelPins
import org.example.project.scheduler.model.PanelRepeat
import org.example.project.scheduler.model.SleepSchedule
import org.example.project.scheduler.model.TaskPanel
import org.example.project.scheduler.persistence.SchedulerStateCodec
import org.example.project.scheduler.state.SchedulerIntent
import org.example.project.scheduler.state.SchedulerReducer
import org.example.project.scheduler.state.SchedulerState

/**
 * `docs/scheduler_requirements.md` § *Priority, Granularity and Compensation*: *"The timeline is infinite forward and
 * backward, and the pre-placed tasks and restrictive periods can be in infinite patterns."* — a panel carrying a
 * [PanelRepeat] recurs every so many days at the same local time, for ever; its occurrences are derived
 * ([PanelRepeats]) wherever they are asked for, and the scheduler, the recurrence bars and the calendar all see them.
 */
class RepeatingPanelsTest {
    private val MIN = 60_000L
    private val HOUR = 60 * MIN
    private val DAY = 24 * HOUR
    private val NOW = 1_700_000_000_000L // 2023-11-14 22:13 UTC

    private fun twoTasks(): SchedulerState {
        var s = SchedulerState.empty()
        listOf("A", "B").forEachIndexed { i, title ->
            val row = s.lists[s.rootListId]!!.cellIds.let { it.getOrNull(i) ?: it.last() }
            s = SchedulerReducer.reduce(s, SchedulerIntent.SetCellTitle(row, title))
        }
        return s.copy(sleep = SleepSchedule(sleepDurationMinutes = 0))
    }

    @Test
    fun occurrences_recur_at_the_same_local_hour_across_a_daylight_saving_change_and_stop_at_until() {
        val paris = TimeZone.of("Europe/Paris")
        // 2023-10-27 09:00 Paris (UTC+2); the clocks go back on the 29th.
        val start = 1_698_390_000_000L
        val base = TaskPanel("panel/7", null, "No screen", start, start + HOUR, noScreen = true, repeat = PanelRepeat(1, start + 5 * DAY))
        val occurrences = PanelRepeats.occurrences(base, start, start + 30 * DAY, paris)
        assertEquals(listOf(1L, 2L, 3L, 4L), occurrences.map { PanelRepeats.indexOf(it.id) }, "until stops the pattern")
        for (o in occurrences) {
            val local = Instant.fromEpochMilliseconds(o.startEpochMillis).toLocalDateTime(paris)
            assertEquals(9, local.hour, "every occurrence starts at 09:00 local: $local")
            assertEquals(HOUR, o.endEpochMillis - o.startEpochMillis)
            assertEquals("panel/7", PanelRepeats.baseIdOf(o.id))
        }
        // Asking again over a list that already holds some never doubles them.
        val once = PanelRepeats.expand(listOf(base), start, start + 30 * DAY, paris)
        assertEquals(once, PanelRepeats.expand(once, start, start + 30 * DAY, paris))
    }

    @Test
    fun a_repeating_pre_placed_block_and_period_hold_every_day_the_plan_reaches() {
        val tz = TimeZone.UTC
        var s = twoTasks()
        val b = s.tasks.values.single { it.title == "B" }.id
        val blockStart = NOW + 2 * HOUR
        val block =
            TaskPanel(
                "panel/b", b, "B", blockStart, blockStart + HOUR, pinned = true, pins = PanelPins(existence = true),
                repeat = PanelRepeat(1),
            )
        val periodStart = NOW + 5 * HOUR
        val period =
            TaskPanel(
                "panel/p", null, "Inactivity", periodStart, periodStart + HOUR, inactivity = true,
                periodKind = PeriodKinds.INACTIVITY, pins = PanelPins(existence = true), repeat = PanelRepeat(1),
            )
        s = s.copy(panels = listOf(block, period))
        val horizon = NOW + 4 * DAY
        val panels = SchedulerDomain.fillSchedule(s, NOW, timeZone = tz, horizonMillis = horizon)
        for (day in 0 until 3) {
            val bFrom = blockStart + day * DAY
            val pFrom = periodStart + day * DAY
            assertTrue(
                panels.filter { it.auto }.none { it.startEpochMillis < bFrom + HOUR && it.endEpochMillis > bFrom },
                "day $day: nothing is scheduled over the pre-placed block's occurrence",
            )
            assertTrue(
                panels.filter { it.auto }.none { it.startEpochMillis < pFrom + HOUR && it.endEpochMillis > pFrom },
                "day $day: nothing runs inside the repeating inactivity period",
            )
        }
        // The pattern is a rule: setting it re-plans.
        assertTrue(
            SchedulerDomain.schedulingSignature(s) != SchedulerDomain.schedulingSignature(s.copy(panels = listOf(block.copy(repeat = null), period))),
        )
        // The occurrences are derived: never synced, never an input of the signature.
        assertTrue(panels.filter(PanelRepeats::isOccurrence).all(SchedulerDomain::isRegeneratedPanel))
    }

    @Test
    fun the_pattern_round_trips_and_a_payload_written_before_it_decodes_as_a_panel_that_happens_once() {
        val s = twoTasks().copy(
            panels = listOf(TaskPanel("panel/0", null, "No screen", NOW, NOW + HOUR, noScreen = true, repeat = PanelRepeat(7, NOW + 90 * DAY))),
        )
        val decoded = assertNotNull(SchedulerStateCodec.decode(SchedulerStateCodec.encode(s)))
        assertEquals(PanelRepeat(7, NOW + 90 * DAY), decoded.panels.single().repeat)
        val older =
            """
            {"rootListId":"L","lists":[{"id":"L","parentCellId":null,"cellIds":["c0"]}],
             "cells":[{"id":"c0","parentListId":"L","taskId":null}],
             "tasks":[],
             "panels":[{"id":"panel/0","title":"No screen","start":0,"end":3600000,"noScreen":true}]}
            """.trimIndent()
        assertNull(assertNotNull(SchedulerStateCodec.decode(older)).panels.single().repeat)
    }

    @Test
    fun an_occurrence_dragged_by_hand_leaves_its_pattern_as_an_exception() {
        // docs/scheduler_input_requirements.md: "When an orange outlined block/chip becomes blue outlined, that means
        // that the configuration that created this orange outlined block/chip gets an 'exception' info (e.g., timer
        // every day at 10h, with an exception for the occurrence of tomorrow that got dragged 1h later)."
        val zone = TimeZone.currentSystemDefault()
        val s = twoTasks().copy(
            panels = listOf(
                TaskPanel(
                    "panel/0", null, "No screen", NOW, NOW + HOUR, noScreen = true, periodKind = PeriodKinds.NO_SCREEN,
                    pins = PanelPins(existence = true), repeat = PanelRepeat(1),
                ),
            ),
            nextPanelCounter = 1,
        )
        val base = s.panels.single()
        val tomorrow = assertNotNull(PanelRepeats.occurrence(base, 1, zone))
        val after =
            SchedulerReducer.reduce(
                s,
                SchedulerIntent.UpdateTaskPanel(
                    tomorrow.id, null, "No screen", tomorrow.startEpochMillis + HOUR, tomorrow.endEpochMillis + HOUR,
                    PanelPins(existence = true),
                ),
            )
        // The pattern stands where it was, with one exception.
        val pattern = after.panels.single { it.id == "panel/0" }
        assertEquals(NOW, pattern.startEpochMillis)
        assertEquals(NOW + HOUR, pattern.endEpochMillis)
        assertEquals(PanelRepeat(1, skipped = setOf(1L)), pattern.repeat)
        // The dragged occurrence is a panel of its own, the user's (blue), an hour later, and repeats nothing.
        // (Behind the line, what stands where it was is chosen from its edges — another panel of the same unit.)
        val own = after.panels.single { it.id != "panel/0" && it.restrictiveKind == PeriodKinds.NO_SCREEN }
        assertEquals(tomorrow.startEpochMillis + HOUR, own.startEpochMillis)
        assertEquals(tomorrow.endEpochMillis + HOUR, own.endEpochMillis)
        assertNull(own.repeat)
        assertTrue(SchedulerDomain.isUserPlaced(own))
        assertEquals(PeriodKinds.NO_SCREEN, own.restrictiveKind)
        // Every other occurrence is where it was; tomorrow's is laid by the pattern no more.
        val later = PanelRepeats.occurrences(pattern, NOW, NOW + 4 * DAY, zone)
        assertEquals(listOf(2L, 3L), later.mapNotNull { PanelRepeats.indexOf(it.id) })
        assertNull(PanelRepeats.occurrence(pattern, 1, zone))
        assertEquals(
            PanelRepeats.occurrence(base, 2, zone)?.startEpochMillis,
            PanelRepeats.occurrence(pattern, 2, zone)?.startEpochMillis,
        )
        // The exception is a rule (the plan depends on it), persisted, and one unit: one Ctrl+Z gives the day back.
        assertTrue(SchedulerDomain.schedulingSignature(s) != SchedulerDomain.schedulingSignature(after))
        assertEquals(pattern.repeat, assertNotNull(SchedulerStateCodec.decode(SchedulerStateCodec.encode(after))).panels.single { it.id == "panel/0" }.repeat)
        val undone = SchedulerReducer.reduce(after.copy(focusedWindow = org.example.project.scheduler.state.HistoryWindow.Calendar), SchedulerIntent.Undo)
        assertEquals(s.panels, undone.panels)
        // A second drag of the same block moves that block alone.
        val again =
            SchedulerReducer.reduce(
                after,
                SchedulerIntent.UpdateTaskPanel(own.id, null, "No screen", own.startEpochMillis + HOUR, own.endEpochMillis + HOUR, PanelPins(existence = true)),
            )
        assertEquals(pattern, again.panels.single { it.id == "panel/0" })
        // A payload written before the exceptions existed decodes as a pattern with none.
        val older =
            """
            {"rootListId":"L","lists":[{"id":"L","parentCellId":null,"cellIds":["c0"]}],
             "cells":[{"id":"c0","parentListId":"L","taskId":null}],
             "tasks":[],
             "panels":[{"id":"panel/0","title":"No screen","start":0,"end":3600000,"noScreen":true,"repeatEveryDays":1}]}
            """.trimIndent()
        assertEquals(PanelRepeat(1), assertNotNull(SchedulerStateCodec.decode(older)).panels.single().repeat)
    }

    @Test
    fun a_new_cadence_said_on_an_occurrence_is_about_the_pattern_and_a_removal_is_an_exception() {
        val s = twoTasks().copy(
            panels = listOf(
                TaskPanel(
                    "panel/0", null, "No screen", NOW, NOW + HOUR, noScreen = true, periodKind = PeriodKinds.NO_SCREEN,
                    pins = PanelPins(existence = true), repeat = PanelRepeat(1),
                ),
            ),
        )
        val third = assertNotNull(PanelRepeats.occurrence(s.panels.single(), 3, TimeZone.currentSystemDefault()))
        // Saved from the edit window with another cadence, thirty minutes later and an hour and a half long: the
        // whole pattern moves, stretches and takes the cadence.
        val moved =
            SchedulerReducer.reduce(
                s,
                SchedulerIntent.UpdateTaskPanel(
                    third.id, null, "No screen", third.startEpochMillis + 30 * MIN, third.startEpochMillis + 120 * MIN,
                    PanelPins(existence = true), repeatEveryDays = 2,
                ),
            ).panels.single { it.id == "panel/0" }
        assertEquals(NOW + 30 * MIN, moved.startEpochMillis)
        assertEquals(NOW + 120 * MIN, moved.endEpochMillis)
        assertEquals(PanelRepeat(2), moved.repeat)
        // Setting the pattern from the edit window: 0 stops it.
        val once =
            SchedulerReducer.reduce(
                s,
                SchedulerIntent.UpdateTaskPanel("panel/0", null, "No screen", NOW, NOW + HOUR, PanelPins(existence = true), repeatEveryDays = 0),
            ).panels.single { it.id == "panel/0" }
        assertNull(once.repeat)
        // Deleting an occurrence is a removal exception: that one is gone, the pattern and every other one stay
        // (user rule 2026-10-10). Deleting the panel that carries the pattern deletes the pattern.
        val removed = SchedulerReducer.reduce(s, SchedulerIntent.RemoveTaskPanel(third.id))
        val kept = removed.panels.single { it.id == "panel/0" }
        assertEquals(PanelRepeat(1, skipped = setOf(3L)), kept.repeat)
        assertEquals(NOW, kept.startEpochMillis)
        assertNull(PanelRepeats.occurrence(kept, 3, TimeZone.currentSystemDefault()))
        assertNotNull(PanelRepeats.occurrence(kept, 2, TimeZone.currentSystemDefault()))
        assertNotNull(PanelRepeats.occurrence(kept, 4, TimeZone.currentSystemDefault()))
        assertEquals(
            setOf(3L, 5L),
            SchedulerReducer.reduce(removed, SchedulerIntent.RemoveTaskPanels(listOf("repeat/panel/0/5"))).panels.single().repeat?.skipped,
        )
        assertTrue(SchedulerReducer.reduce(removed, SchedulerIntent.RemoveTaskPanel("panel/0")).panels.isEmpty())
        // A period laid with a pattern carries it.
        val laid = SchedulerReducer.reduce(twoTasks(), SchedulerIntent.AddRestrictivePeriod(PeriodKinds.NO_SCREEN, NOW, NOW + HOUR, 7))
        assertEquals(PanelRepeat(7), laid.panels.single { it.isRestrictivePeriod }.repeat)
    }
}
