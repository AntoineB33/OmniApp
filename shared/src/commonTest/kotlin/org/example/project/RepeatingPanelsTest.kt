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
    fun editing_or_removing_an_occurrence_edits_or_removes_the_pattern() {
        val s = twoTasks().copy(
            panels = listOf(
                TaskPanel(
                    "panel/0", null, "No screen", NOW, NOW + HOUR, noScreen = true, periodKind = PeriodKinds.NO_SCREEN,
                    pins = PanelPins(existence = true), repeat = PanelRepeat(1),
                ),
            ),
        )
        val third = assertNotNull(PanelRepeats.occurrence(s.panels.single(), 3, TimeZone.currentSystemDefault()))
        // Dragged thirty minutes later and made an hour and a half long: the whole pattern moves and stretches.
        val moved =
            SchedulerReducer.reduce(
                s,
                SchedulerIntent.UpdateTaskPanel(
                    third.id, null, "No screen", third.startEpochMillis + 30 * MIN, third.startEpochMillis + 120 * MIN,
                    PanelPins(existence = true),
                ),
            ).panels.single { it.id == "panel/0" }
        assertEquals(NOW + 30 * MIN, moved.startEpochMillis)
        assertEquals(NOW + 120 * MIN, moved.endEpochMillis)
        assertEquals(PanelRepeat(1), moved.repeat, "a drag keeps the pattern")
        // Setting the pattern from the edit window: 0 stops it.
        val once =
            SchedulerReducer.reduce(
                s,
                SchedulerIntent.UpdateTaskPanel("panel/0", null, "No screen", NOW, NOW + HOUR, PanelPins(existence = true), repeatEveryDays = 0),
            ).panels.single { it.id == "panel/0" }
        assertNull(once.repeat)
        // Deleting an occurrence deletes the pattern.
        val removed = SchedulerReducer.reduce(s, SchedulerIntent.RemoveTaskPanel(third.id))
        assertTrue(removed.panels.none { it.id == "panel/0" })
        // A period laid with a pattern carries it.
        val laid = SchedulerReducer.reduce(twoTasks(), SchedulerIntent.AddRestrictivePeriod(PeriodKinds.NO_SCREEN, NOW, NOW + HOUR, 7))
        assertEquals(PanelRepeat(7), laid.panels.single { it.isRestrictivePeriod }.repeat)
    }
}
