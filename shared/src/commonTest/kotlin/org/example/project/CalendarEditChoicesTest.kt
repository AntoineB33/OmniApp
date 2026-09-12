package org.example.project

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.example.project.scheduler.domain.PeriodKinds
import org.example.project.scheduler.domain.SchedulerDomain
import org.example.project.scheduler.model.TaskId
import org.example.project.ui.EDIT_LABEL_ALARM
import org.example.project.ui.EDIT_LABEL_BEFORE_BED
import org.example.project.ui.EDIT_LABEL_INACTIVITY
import org.example.project.ui.EDIT_LABEL_NO_COMPUTER
import org.example.project.ui.EDIT_LABEL_NO_PHONE
import org.example.project.ui.EDIT_LABEL_NO_SCREEN
import org.example.project.ui.EDIT_LABEL_REMINDER
import org.example.project.ui.EDIT_LABEL_RESTRICTIVE_PERIOD
import org.example.project.ui.EDIT_LABEL_SLEEP
import org.example.project.ui.EDIT_LABEL_TASK_PANEL
import org.example.project.ui.EDIT_LABEL_TIMER
import org.example.project.ui.PlacedRecord
import org.example.project.ui.calendarEditChoices
import org.example.project.ui.isDrawnPeriodRecord
import org.example.project.ui.isRestrictivePeriodRecord
import org.example.project.ui.periodChoiceLabel
import org.example.project.ui.periodSegmentLabel
import org.example.project.ui.periodSegmentOutline
import org.example.project.ui.periodSegments

/**
 * PRD §8: the calendar's contextual menu names THINGS, not editors.
 *
 * Every edit entry the menu used to carry ("Edit", "edit task") is one "edit…" chooser whose rows are
 * whatever the cursor is actually on, in the one order the user gave — and a chooser of one is not a chooser
 * (the single row replaces "edit…" in the menu itself, and a lone restrictive period is named by its KIND
 * rather than by the generic row that would have opened a chooser of one).
 *
 * The second half of the file is the other rule of the same reshape: overlapping restrictive periods are
 * drawn as ONE box per stretch with every title in it, never as panels sharing the column's width — which is
 * exactly why the chooser has to exist to tell them apart again.
 */
class CalendarEditChoicesTest {

    private fun panel(
        id: String,
        title: String = id,
        startHour: Float = 9f,
        endHour: Float = 10f,
        taskId: TaskId? = TaskId("t-$id"),
    ) = PlacedRecord(
        title = title,
        startHour = startHour,
        endHour = endHour,
        scheduled = false,
        manual = true,
        entryId = id,
        entryIds = listOf(id),
        taskId = taskId,
        fullStartMillis = 0L,
        fullEndMillis = 0L,
    )

    private fun period(
        id: String,
        kind: String,
        startHour: Float = 9f,
        endHour: Float = 10f,
        outline: SchedulerDomain.PanelOutline = SchedulerDomain.PanelOutline.User,
    ) = PlacedRecord(
        title = PeriodKinds.periodTitle(kind),
        startHour = startHour,
        endHour = endHour,
        scheduled = false,
        manual = true,
        entryId = id,
        entryIds = listOf(id),
        restrictiveKind = kind,
        noScreen = PeriodKinds.assertedLayers(kind).isNotEmpty(),
        inactivity = PeriodKinds.assertedLayers(kind).isEmpty(),
        outline = outline,
        fullStartMillis = 0L,
        fullEndMillis = 0L,
    )

    private fun marker(id: String, reminder: Boolean = false, alarm: Boolean = false, timer: Boolean = false) =
        PlacedRecord(
            title = id,
            startHour = 9.5f,
            endHour = 9.5f,
            scheduled = false,
            entryId = id,
            reminder = reminder,
            alarm = alarm,
            timer = timer,
            fullStartMillis = 0L,
            fullEndMillis = 0L,
        )

    private fun sleepBand() = PlacedRecord(
        title = "Sleep",
        startHour = 0f,
        endHour = 7f,
        scheduled = false,
        sleep = true,
        inactivity = true,
        restrictiveKind = PeriodKinds.NO_TASK,
        fullStartMillis = 0L,
        fullEndMillis = 0L,
    )

    /** Nothing under the cursor ⇒ no edit row at all; the menu is then "add…" alone. */
    @Test
    fun emptySpaceOffersNothing() {
        assertEquals(emptyList(), calendarEditChoices(emptyList()))
    }

    /** One thing ⇒ one row, and the caller writes it straight into the menu in place of "edit…". */
    @Test
    fun oneThingIsOneRow() {
        val rows = calendarEditChoices(listOf(panel("p")))
        assertEquals(listOf(EDIT_LABEL_TASK_PANEL), rows.map { it.label })
        assertEquals(listOf("p"), rows.single().records.map { it.entryId })
        assertTrue(rows.single().children.isEmpty())
    }

    /** The user's order, whatever order the hit test happened to find the things in. */
    @Test
    fun rowsAreInTheGivenOrder() {
        val hits =
            listOf(
                sleepBand(),
                marker("timer", alarm = true, timer = true),
                marker("rem", reminder = true),
                period("inact", PeriodKinds.NO_TASK),
                marker("alarm", alarm = true),
                panel("p"),
            )
        assertEquals(
            listOf(
                EDIT_LABEL_TASK_PANEL,
                EDIT_LABEL_INACTIVITY,
                EDIT_LABEL_REMINDER,
                EDIT_LABEL_ALARM,
                EDIT_LABEL_TIMER,
                EDIT_LABEL_SLEEP,
            ),
            calendarEditChoices(hits).map { it.label },
        )
    }

    /**
     * A LONE period is named by its kind and opens its editor directly — there is no "restrictive period" row
     * that would open a chooser of one — and it lands where the user put that kind in the order.
     */
    @Test
    fun oneperiodIsNamedByItsKind() {
        val rows = calendarEditChoices(listOf(panel("p"), period("bb", PeriodKinds.BEFORE_BED)))
        assertEquals(listOf(EDIT_LABEL_TASK_PANEL, EDIT_LABEL_BEFORE_BED), rows.map { it.label })
        val bb = rows.last()
        assertEquals(PeriodKinds.BEFORE_BED, bb.periodKind)
        assertTrue(bb.children.isEmpty())
    }

    /** Several periods ⇒ the generic row, whose own chooser names each one by kind, in the same order. */
    @Test
    fun severalPeriodsBranchIntoTheirOwnChooser() {
        val hits =
            listOf(
                period("bb", PeriodKinds.BEFORE_BED),
                period("deep", "deep work"),
                period("inact", PeriodKinds.NO_TASK),
            )
        val rows = calendarEditChoices(hits)
        assertEquals(listOf(EDIT_LABEL_RESTRICTIVE_PERIOD), rows.map { it.label })
        val branch = rows.single()
        assertTrue(branch.records.isEmpty(), "a row either edits something or opens a chooser, never both")
        assertEquals(
            listOf(EDIT_LABEL_INACTIVITY, EDIT_LABEL_BEFORE_BED, "deep work"),
            branch.children.map { it.label },
        )
    }

    /**
     * The user's rule: editing a "no screen" period IS editing both one-sided layer periods. So the row
     * stands for either spelling and carries every record behind it — the edit is applied to all of them.
     */
    @Test
    fun noScreenRowStandsForBothSpellings() {
        val stated = calendarEditChoices(listOf(period("ns", PeriodKinds.NO_SCREEN)))
        assertEquals(listOf(EDIT_LABEL_NO_SCREEN), stated.map { it.label })
        assertEquals(listOf("ns"), stated.single().records.map { it.entryId })

        val pair =
            calendarEditChoices(
                listOf(
                    period("c", PeriodKinds.NO_COMPUTER_UNLOCKED),
                    period("f", PeriodKinds.NO_PHONE_UNLOCKED),
                ),
            )
        assertEquals(listOf(EDIT_LABEL_RESTRICTIVE_PERIOD), pair.map { it.label })
        assertEquals(
            listOf(EDIT_LABEL_NO_COMPUTER, EDIT_LABEL_NO_PHONE, EDIT_LABEL_NO_SCREEN),
            pair.single().children.map { it.label },
        )
        val noScreen = pair.single().children.last()
        assertEquals(listOf("c", "f"), noScreen.records.map { it.entryId })
        assertEquals(PeriodKinds.NO_SCREEN, noScreen.periodKind)
    }

    /** One locked screen is not "no screen": a lone one-sided period grows no conjunction row. */
    @Test
    fun oneSidedPeriodAloneIsNotNoScreen() {
        val rows = calendarEditChoices(listOf(period("c", PeriodKinds.NO_COMPUTER_UNLOCKED)))
        assertEquals(listOf(EDIT_LABEL_NO_COMPUTER), rows.map { it.label })
    }

    /** A sleep band is its own row (its editable object is the §17 schedule), never a period row. */
    @Test
    fun sleepIsNotAPeriodRow() {
        val rows = calendarEditChoices(listOf(sleepBand()))
        assertEquals(listOf(EDIT_LABEL_SLEEP), rows.map { it.label })
    }

    /** The two built-in kinds whose internal name says something else read in the user's words. */
    @Test
    fun kindLabelsAreTheUsersWords() {
        assertEquals(EDIT_LABEL_INACTIVITY, periodChoiceLabel(PeriodKinds.NO_TASK))
        assertEquals(EDIT_LABEL_NO_SCREEN, periodChoiceLabel(PeriodKinds.NO_SCREEN))
        assertEquals(EDIT_LABEL_BEFORE_BED, periodChoiceLabel(PeriodKinds.BEFORE_BED))
        assertEquals(EDIT_LABEL_NO_COMPUTER, periodChoiceLabel(PeriodKinds.NO_COMPUTER_UNLOCKED))
        assertEquals(EDIT_LABEL_NO_PHONE, periodChoiceLabel(PeriodKinds.NO_PHONE_UNLOCKED))
        assertEquals("deep work", periodChoiceLabel("deep work"))
    }

    /**
     * A "no screen" period is SHOWN by the presence of both layer hatches and by nothing else — so it is not
     * one of the boxes — but it is still a menu target, or it could be neither edited nor binned.
     */
    @Test
    fun aNoScreenPeriodIsAMenuTargetButNotABox() {
        val ns = period("ns", PeriodKinds.NO_SCREEN)
        assertTrue(isRestrictivePeriodRecord(ns))
        assertTrue(!isDrawnPeriodRecord(ns))
        assertEquals(emptyList(), periodSegments(listOf(ns).filter(::isDrawnPeriodRecord)))
        // Every other kind IS drawn, the two one-sided layer kinds included — the user's example is that a
        // hand-extended "no computer unlocked" stretch draws a blue-outlined box over the oblique lines.
        listOf(PeriodKinds.NO_TASK, PeriodKinds.BEFORE_BED, PeriodKinds.NO_COMPUTER_UNLOCKED, "deep work")
            .forEach { kind -> assertTrue(isDrawnPeriodRecord(period("p", kind)), kind) }
    }

    /** A §15 screen break is not a period row: §15 owns the three dynamic periods and places them itself. */
    @Test
    fun aScreenBreakIsNotAPeriodRow() {
        val brk = period("b", PeriodKinds.NO_TASK).copy(screenBreak = true)
        assertEquals(emptyList(), calendarEditChoices(listOf(brk)))
    }

    // --- the drawing half -------------------------------------------------------------------------------

    /** A lone period is ONE box, not a run of them: the cut is a consequence of overlap and nothing else. */
    @Test
    fun onePeriodIsOneBox() {
        val segments = periodSegments(listOf(period("a", PeriodKinds.NO_TASK, 10f, 12f)))
        assertEquals(1, segments.size)
        assertEquals(10f to 12f, segments.single().let { it.startHour to it.endHour })
    }

    /** The user's own example: A 10–12 and B 11–13 draw three boxes — A, then A and B, then B. */
    @Test
    fun overlappingPeriodsCutIntoThreeBoxes() {
        val a = period("a", PeriodKinds.NO_TASK, 10f, 12f).copy(title = "A")
        val b = period("b", "deep work", 11f, 13f).copy(title = "B")
        val segments = periodSegments(listOf(a, b))
        assertEquals(
            listOf(10f to 11f, 11f to 12f, 12f to 13f),
            segments.map { it.startHour to it.endHour },
        )
        assertEquals(listOf("A", "A, B", "B"), segments.map(::periodSegmentLabel))
    }

    /** Every box is full width — the periods never enter the shared-width layout at all. */
    @Test
    fun aBoxNamesEveryPeriodInForce() {
        val a = period("a", PeriodKinds.NO_TASK, 9f, 12f).copy(title = "A")
        val b = period("b", "deep work", 9f, 12f).copy(title = "B")
        val c = period("c", PeriodKinds.BEFORE_BED, 9f, 12f).copy(title = "C")
        val segments = periodSegments(listOf(a, b, c))
        assertEquals(1, segments.size)
        assertEquals("A, B, C", periodSegmentLabel(segments.single()))
    }

    /**
     * The outline answers "did a hand state any of this?", which is the user's layer example: an evidence
     * hour draws unoutlined and the hour they extended it by draws blue.
     */
    @Test
    fun theStrongestHandOutlinesTheBox() {
        val derived = period("d", PeriodKinds.NO_TASK, 10f, 12f, SchedulerDomain.PanelOutline.None)
        val byRule = period("r", PeriodKinds.BEFORE_BED, 10f, 12f, SchedulerDomain.PanelOutline.Pattern)
        val byHand = period("h", "deep work", 11f, 12f, SchedulerDomain.PanelOutline.User)
        val segments = periodSegments(listOf(derived, byRule, byHand))
        assertEquals(
            listOf(SchedulerDomain.PanelOutline.Pattern, SchedulerDomain.PanelOutline.User),
            segments.map(::periodSegmentOutline),
        )
    }
}
