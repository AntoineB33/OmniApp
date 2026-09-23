package org.example.project

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.example.project.scheduler.domain.CalendarElements
import org.example.project.scheduler.domain.PeriodKinds
import org.example.project.scheduler.model.PanelPins
import org.example.project.scheduler.model.TaskId
import org.example.project.scheduler.state.SchedulerIntent
import org.example.project.scheduler.state.SchedulerReducer
import org.example.project.scheduler.state.SchedulerState

/**
 * PRD §8: **the element window's Save — `AddCalendarElements`.**
 *
 * Three rules, and the first two are why it is one intent rather than a loop of the three single-element
 * ones:
 *  - **one Save is ONE calendar history unit**, however many things it laid;
 *  - **each element is resolved against the calendar the ones before it already changed**, so laying a
 *    period and a panel inside it in one Save leaves what drawing them one after the other would;
 *  - **an alarm is not in it at all** — it is a row of `SetAlarms`, a *Main* unit, so a Save spanning both
 *    costs one Ctrl+Z per stack and never puts a ring on the calendar's.
 */
class CalendarElementsSaveTest {

    private val MIN = 60_000L
    private val HOUR = 3_600_000L

    /** One leaf task "A" under the root, and the state holding it. */
    private fun stateWithTask(): Pair<SchedulerState, TaskId> {
        var s = SchedulerState.empty()
        val cell = s.lists[s.rootListId]!!.cellIds[0]
        s = SchedulerReducer.reduce(s, SchedulerIntent.SetCellTitle(cell, "A"))
        return s to s.tasks.keys.first { s.tasks[it]!!.title == "A" }
    }

    private fun panelDraft(taskId: TaskId, start: Long, end: Long) =
        CalendarElements.Draft(
            kind = CalendarElements.Kind.TaskPanel,
            name = "A",
            startMillis = start,
            endMillis = end,
            taskId = taskId,
            pins = PanelPins(existence = true),
        )

    private fun periodDraft(kind: String, start: Long, end: Long) =
        CalendarElements.Draft(
            kind = CalendarElements.Kind.RestrictivePeriod,
            name = PeriodKinds.periodTitle(kind),
            startMillis = start,
            endMillis = end,
            periodKind = kind,
        )

    private fun reminderDraft(at: Long, title: String = "water the plants") =
        CalendarElements.Draft(
            kind = CalendarElements.Kind.Reminder,
            name = title,
            startMillis = at,
            endMillis = at,
        )

    @Test
    fun threeElementsAreOneHistoryUnit() {
        val (s0, a) = stateWithTask()
        val before = s0.histories.calendar.units.size
        val saved =
            SchedulerReducer.reduce(
                s0,
                SchedulerIntent.AddCalendarElements(
                    listOf(
                        panelDraft(a, 9 * HOUR, 10 * HOUR),
                        periodDraft(PeriodKinds.BEFORE_BED, 22 * HOUR, 23 * HOUR),
                        reminderDraft(12 * HOUR),
                    ),
                ),
            )
        assertEquals(before + 1, saved.histories.calendar.units.size)
        // And all three really landed.
        assertEquals(3, saved.panels.count { !it.auto })
    }

    @Test
    fun anEmptyListWritesNothing() {
        val (s0, _) = stateWithTask()
        val saved = SchedulerReducer.reduce(s0, SchedulerIntent.AddCalendarElements(emptyList()))
        assertEquals(s0.histories.calendar.units.size, saved.histories.calendar.units.size)
    }

    @Test
    fun twoNewPanelsGetDistinctIds() {
        // The id allocator is carried through the fold — a loop of AddTaskPanel intents allocates against
        // the state it started from and would hand both panels the same id.
        val (s0, a) = stateWithTask()
        val saved =
            SchedulerReducer.reduce(
                s0,
                SchedulerIntent.AddCalendarElements(
                    listOf(panelDraft(a, 9 * HOUR, 10 * HOUR), panelDraft(a, 14 * HOUR, 15 * HOUR)),
                ),
            )
        val ids = saved.panels.filter { !it.auto }.map { it.id }
        assertEquals(ids.size, ids.distinct().size)
    }

    @Test
    fun aPeriodInTheSameSaveTrimsThePanelBesideIt() {
        // The override resolution is what makes this a FOLD: an `inactivity` period refuses every task, so
        // a panel laid in the same Save and covered by it must be gone, exactly as if the two had been
        // drawn one after the other.
        val (s0, a) = stateWithTask()
        val saved =
            SchedulerReducer.reduce(
                s0,
                SchedulerIntent.AddCalendarElements(
                    listOf(
                        panelDraft(a, 9 * HOUR, 10 * HOUR),
                        periodDraft(PeriodKinds.INACTIVITY, 8 * HOUR, 11 * HOUR),
                    ),
                ),
            )
        assertTrue(saved.panels.none { it.taskId == a && !it.isRestrictivePeriod })
        assertTrue(saved.panels.any { it.restrictiveKind == PeriodKinds.INACTIVITY })
    }

    @Test
    fun aReminderIsLaidAsAZeroDurationTagWithItsOwnId() {
        val (s0, _) = stateWithTask()
        val saved =
            SchedulerReducer.reduce(s0, SchedulerIntent.AddCalendarElements(listOf(reminderDraft(12 * HOUR))))
        val tag = saved.panels.single { it.chore }
        // PRD §14: a tag has no spanning time, so the minimum-length clamp every other panel gets must not
        // reach it — a reminder stretched to 5 minutes is an obstacle the scheduler would have to route around.
        assertEquals(tag.startEpochMillis, tag.endEpochMillis)
        assertTrue(tag.id.startsWith("chore-manual/"))
    }

    @Test
    fun anExistingPanelIsMovedRatherThanDuplicated() {
        val (s0, a) = stateWithTask()
        val laid =
            SchedulerReducer.reduce(
                s0,
                SchedulerIntent.AddCalendarElements(listOf(panelDraft(a, 9 * HOUR, 10 * HOUR))),
            )
        val id = laid.panels.single { !it.auto && !it.isRestrictivePeriod }.id
        val moved =
            SchedulerReducer.reduce(
                laid,
                SchedulerIntent.AddCalendarElements(
                    listOf(panelDraft(a, 14 * HOUR, 15 * HOUR).copy(existingId = id)),
                ),
            )
        val panel = moved.panels.single { !it.auto && !it.isRestrictivePeriod }
        assertEquals(id, panel.id)
        assertEquals(14 * HOUR, panel.startEpochMillis)
    }

    @Test
    fun aDraftNamingAPanelThatIsGoneIsDropped() {
        // The window may have been open while a sync removed what it was editing; re-adding it would
        // resurrect a thing the user is no longer looking at.
        val (s0, a) = stateWithTask()
        val saved =
            SchedulerReducer.reduce(
                s0,
                SchedulerIntent.AddCalendarElements(
                    listOf(panelDraft(a, 9 * HOUR, 10 * HOUR).copy(existingId = "panel/nope")),
                ),
            )
        assertEquals(s0.panels, saved.panels)
    }

    @Test
    fun anAlarmDraftLaysNoPanel() {
        val (s0, _) = stateWithTask()
        val saved =
            SchedulerReducer.reduce(
                s0,
                SchedulerIntent.AddCalendarElements(
                    listOf(
                        CalendarElements.Draft(
                            kind = CalendarElements.Kind.Alarm,
                            name = "wake up",
                            startMillis = 7 * HOUR,
                            endMillis = 7 * HOUR + 3_000L,
                        ),
                    ),
                ),
            )
        assertEquals(s0.panels, saved.panels)
        assertEquals(s0.histories.calendar.units.size, saved.histories.calendar.units.size)
    }

    @Test
    fun aPanelKeepsItsMinimumLength() {
        val (s0, a) = stateWithTask()
        val saved =
            SchedulerReducer.reduce(
                s0,
                SchedulerIntent.AddCalendarElements(listOf(panelDraft(a, 9 * HOUR, 9 * HOUR))),
            )
        val panel = saved.panels.single { !it.auto && !it.isRestrictivePeriod }
        assertTrue(panel.endEpochMillis - panel.startEpochMillis >= MIN)
        assertNotNull(panel.taskId)
    }
}
