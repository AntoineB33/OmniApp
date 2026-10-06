package org.example.project

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.example.project.scheduler.domain.PeriodKinds
import org.example.project.scheduler.domain.SchedulerDomain
import org.example.project.scheduler.model.TaskId
import org.example.project.ui.EDIT_LABEL_ALARM
import org.example.project.ui.EDIT_LABEL_REMINDER
import org.example.project.ui.EDIT_LABEL_RESTRICTIVE_PERIOD
import org.example.project.ui.EDIT_LABEL_SLEEP_SCHEDULE
import org.example.project.ui.EDIT_LABEL_TASK
import org.example.project.ui.EDIT_LABEL_TASK_PANEL
import org.example.project.ui.EDIT_LABEL_TIMER
import org.example.project.ui.PlacedRecord
import org.example.project.ui.calendarBlockEditChoice
import org.example.project.ui.calendarEditChoices
import org.example.project.ui.isDrawnPeriodRecord
import org.example.project.ui.isRestrictivePeriodRecord
import org.example.project.ui.periodSegmentLabel
import org.example.project.ui.periodSegmentOutline
import org.example.project.ui.periodSegments

/**
 * PRD §8: the calendar's contextual menu names THINGS, not editors.
 *
 * EVERY edit entry the menu used to carry ("Edit", "edit task") is one "edit…" chooser whose rows are
 * whatever the cursor is actually on, in the one order the user gave — **task, task panel, restrictive
 * period, reminder, alarm, timer** — and a chooser of one is not a chooser (the single row replaces "edit…"
 * in the menu itself, and a lone restrictive period is named by its KIND rather than by the generic row that
 * would have opened a chooser of one, while still ranking in the restrictive-period slot).
 *
 * `sleep` is the one row outside that list: its editable object is the §17 schedule rather than something the
 * calendar lays, so it ranks after the six.
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
        noScreen = PeriodKinds.isLayerKind(kind),
        inactivity = !PeriodKinds.isLayerKind(kind),
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
        restrictiveKind = PeriodKinds.SLEEP,
        fullStartMillis = 0L,
        fullEndMillis = 0L,
    )

    /** Nothing under the cursor ⇒ no edit row at all; the menu is then "add…" alone. */
    @Test
    fun emptySpaceOffersNothing() {
        assertEquals(emptyList(), calendarEditChoices(emptyList()))
    }

    /**
     * One thing ⇒ one row, and the caller writes it straight into the menu in place of "edit…". A panel whose
     * title names no task is that case: there is no task behind it for the first row to edit.
     */
    @Test
    fun oneThingIsOneRow() {
        val rows = calendarEditChoices(listOf(panel("p", taskId = null)))
        assertEquals(listOf(EDIT_LABEL_TASK_PANEL), rows.map { it.label })
        assertEquals(listOf("p"), rows.single().records.map { it.entryId })
        assertTrue(rows.single().children.isEmpty())
    }

    /**
     * The user's first row: **the TASK behind the panel**. It used to be an "edit task" entry beside the
     * chooser, which is an edit the order did not govern — so it is a row, and the first one.
     */
    @Test
    fun theTaskBehindAPanelIsTheFirstRow() {
        val rows = calendarEditChoices(listOf(panel("p")))
        assertEquals(listOf(EDIT_LABEL_TASK, EDIT_LABEL_TASK_PANEL), rows.map { it.label })
        assertEquals(TaskId("t-p"), rows.first().records.single().taskId)
    }

    /**
     * **One task, one row, however many of its panels are stacked at the point.** They are occurrences of one
     * task and the row edits the task, so two of them would be two rows opening the identical window; panels
     * of DIFFERENT tasks are two answers to "which task?" and stay two rows.
     */
    @Test
    fun stackedPanelsOfOneTaskAreOneTaskRow() {
        val same = calendarEditChoices(listOf(panel("a", taskId = TaskId("t")), panel("b", taskId = TaskId("t"))))
        assertEquals(
            listOf(EDIT_LABEL_TASK, EDIT_LABEL_TASK_PANEL, EDIT_LABEL_TASK_PANEL),
            same.map { it.label },
        )
        assertEquals(listOf("a", "b"), same.first().records.map { it.entryId })

        val distinct = calendarEditChoices(listOf(panel("a"), panel("b")))
        assertEquals(2, distinct.count { it.label == EDIT_LABEL_TASK })
    }

    /**
     * A DOUBLE-CLICK opens the thing it landed on, never the task behind it: the gesture is on the panel, and
     * the task is asked for by name in the chooser. Same table either way, so the two can never disagree.
     */
    @Test
    fun aDoubleClickOpensTheBlockNotItsTask() {
        assertEquals(EDIT_LABEL_TASK_PANEL, calendarBlockEditChoice(panel("p"))?.label)
        // Same rule one step further out: a sleep band IS a `sleep` period, and the §17 SCHEDULE is the
        // rule behind it — so the band's own row is what the gesture landed on, not the schedule's.
        assertEquals(PeriodKinds.SLEEP, calendarBlockEditChoice(sleepBand())?.label)
        assertEquals(null, calendarBlockEditChoice(period("b", PeriodKinds.INACTIVITY).copy(screenBreak = true)))
    }

    /** The user's order, whatever order the hit test happened to find the things in. */
    @Test
    fun rowsAreInTheGivenOrder() {
        val hits =
            listOf(
                sleepBand(),
                marker("timer", alarm = true, timer = true),
                marker("rem", reminder = true),
                period("inact", PeriodKinds.INACTIVITY),
                marker("alarm", alarm = true),
                panel("p"),
            )
        assertEquals(
            listOf(
                EDIT_LABEL_TASK,
                EDIT_LABEL_TASK_PANEL,
                // TWO periods are under the cursor — the inactivity one and the sleep band, which is a
                // `sleep` period like any other — so they collapse into the one generic row that opens
                // their own chooser. (A LONE period wears its kind's name instead: the test below.)
                EDIT_LABEL_RESTRICTIVE_PERIOD,
                EDIT_LABEL_REMINDER,
                EDIT_LABEL_ALARM,
                EDIT_LABEL_TIMER,
                // Not one of the six the user ordered: an unlisted row ranks last.
                EDIT_LABEL_SLEEP_SCHEDULE,
            ),
            calendarEditChoices(hits).map { it.label },
        )
        // And the chooser that row opens ranks the two kinds in PERIOD_CHOOSER_KIND_ORDER.
        assertEquals(
            listOf(PeriodKinds.INACTIVITY, PeriodKinds.SLEEP),
            calendarEditChoices(hits).single { it.label == EDIT_LABEL_RESTRICTIVE_PERIOD }
                .children.map { it.label },
        )
    }

    /**
     * A LONE period is named by its kind and opens its editor directly — there is no "restrictive period" row
     * that would open a chooser of one — and it lands where the user put that kind in the order.
     */
    @Test
    fun oneperiodIsNamedByItsKind() {
        val rows = calendarEditChoices(listOf(panel("p", taskId = null), period("bb", PeriodKinds.BEFORE_BED)))
        assertEquals(listOf(EDIT_LABEL_TASK_PANEL, PeriodKinds.BEFORE_BED), rows.map { it.label })
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
                period("inact", PeriodKinds.INACTIVITY),
            )
        val rows = calendarEditChoices(hits)
        assertEquals(listOf(EDIT_LABEL_RESTRICTIVE_PERIOD), rows.map { it.label })
        val branch = rows.single()
        assertTrue(branch.records.isEmpty(), "a row either edits something or opens a chooser, never both")
        assertEquals(
            listOf(PeriodKinds.INACTIVITY, PeriodKinds.BEFORE_BED, "deep work"),
            branch.children.map { it.label },
        )
    }

    /**
     * 2026-09-19: a "no screen" period no longer means "no computer unlocked" and "no phone unlocked", so the
     * "no screen" row is that period and nothing else, and the two layer periods are two rows of their own
     * even where they overlap — no third row edits them together.
     */
    @Test
    fun noScreenRowIsTheNoScreenPeriodAlone() {
        val stated = calendarEditChoices(listOf(period("ns", PeriodKinds.NO_SCREEN)))
        assertEquals(listOf(PeriodKinds.NO_SCREEN), stated.map { it.label })
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
            listOf(PeriodKinds.NO_COMPUTER_UNLOCKED, PeriodKinds.NO_PHONE_UNLOCKED),
            pair.single().children.map { it.label },
        )
        assertEquals(listOf(listOf("c"), listOf("f")), pair.single().children.map { c -> c.records.map { it.entryId } })
    }

    /** One locked screen is not "no screen": a lone one-sided period grows no conjunction row. */
    @Test
    fun oneSidedPeriodAloneIsNotNoScreen() {
        val rows = calendarEditChoices(listOf(period("c", PeriodKinds.NO_COMPUTER_UNLOCKED)))
        assertEquals(listOf(PeriodKinds.NO_COMPUTER_UNLOCKED), rows.map { it.label })
    }

    /**
     * A sleep band is TWO rows, because it is two objects: the `sleep` PERIOD the calendar lays — a kind
     * like any other since the 2026-09-12 split of `no task allowed` — and the §17 SCHEDULE that laid it.
     * Exactly as `task` and `task panel` are two rows over one block.
     *
     * The band draws no period BOX over itself for the same reason it once grew no row at all: §17 already
     * draws it, and a second drawing of one statement is the thing these rules exist to prevent.
     */
    @Test
    fun aSleepBandIsItsPeriodAndTheScheduleBehindIt() {
        val rows = calendarEditChoices(listOf(sleepBand()))
        assertEquals(listOf(PeriodKinds.SLEEP, EDIT_LABEL_SLEEP_SCHEDULE), rows.map { it.label })
        assertTrue(!isDrawnPeriodRecord(sleepBand()))
    }

    /**
     * **A period row reads as its KIND** — the account's own kinds included, which is how it always was for
     * those. There is no label table left that could disagree with the kinds, because since the 2026-09-12
     * rename the kinds ARE the words the menus say ([PeriodKinds.INACTIVITY] is `inactivity`); the
     * `periodChoiceLabel` that used to translate the stored name into the user's is gone rather than turned
     * into an identity, since a funnel nobody can spell two ways needs no funnel.
     *
     * The one name still computed apart is the title a period CARRIES on the grid
     * ([PeriodKinds.periodTitle]), and it differs from its kind by CASE alone — capitalized because it heads
     * a box. A row is a menu line and is not.
     */
    @Test
    fun aPeriodRowIsNamedByItsKind() {
        listOf(
            PeriodKinds.INACTIVITY,
            PeriodKinds.BEFORE_BED,
            PeriodKinds.NO_COMPUTER_UNLOCKED,
            PeriodKinds.NO_PHONE_UNLOCKED,
            "deep work",
        ).forEach { kind ->
            assertEquals(listOf(kind), calendarEditChoices(listOf(period("p", kind))).map { it.label }, kind)
            assertTrue(PeriodKinds.periodTitle(kind).equals(kind, ignoreCase = true), kind)
        }
        // The one row that is not a period one-for-one names the kind it stands for, by the same rule.
        assertEquals(
            listOf(PeriodKinds.NO_SCREEN),
            calendarEditChoices(listOf(period("ns", PeriodKinds.NO_SCREEN))).map { it.label },
        )
    }

    /**
     * **A "no screen" period is a box like every other kind** — *"the whole added period/panel/reminder/alarm
     * must be outlined in blue"*.
     *
     * It was the one kind excluded, on the grounds that it asserts both layers and so is already shown by
     * the two oblique hatches. But a hatch and a box answer different questions: the app derives hatches out
     * of the OS lock log all day, and the only thing that can say *a HAND stated this stretch* is the blue
     * outline — so a hand-added no-screen period was the single thing the user could add to the calendar
     * that left no mark of having been added. The hatch is the statement, the outline is the hand, and
     * `periodDrawing`'s dots are the third and last mark (the lock log disagrees).
     *
     * The §17 sleep band stays the one exception, for the reason it always was: it draws itself.
     */
    @Test
    fun everyPeriodKindIsABoxExceptTheSleepBandAndTheUsersIsBlue() {
        val ns = period("ns", PeriodKinds.NO_SCREEN)
        assertTrue(isRestrictivePeriodRecord(ns))
        assertTrue(isDrawnPeriodRecord(ns))
        val segments = periodSegments(listOf(ns).filter(::isDrawnPeriodRecord))
        assertEquals(1, segments.size)
        assertEquals(PeriodKinds.periodTitle(PeriodKinds.NO_SCREEN), periodSegmentLabel(segments[0]))
        assertEquals(SchedulerDomain.PanelOutline.User, periodSegmentOutline(segments[0]))
        // Every other kind is drawn too, the two one-sided layer kinds included — the user's example is that
        // a hand-extended "no computer unlocked" stretch draws a blue-outlined box over the oblique lines.
        listOf(PeriodKinds.INACTIVITY, PeriodKinds.BEFORE_BED, PeriodKinds.NO_COMPUTER_UNLOCKED, "deep work")
            .forEach { kind -> assertTrue(isDrawnPeriodRecord(period("p", kind)), kind) }
    }

    /** A §15 screen break is not a period row: §15 owns the three dynamic periods and places them itself. */
    @Test
    fun aScreenBreakIsNotAPeriodRow() {
        val brk = period("b", PeriodKinds.INACTIVITY).copy(screenBreak = true)
        assertEquals(emptyList(), calendarEditChoices(listOf(brk)))
    }

    // --- the drawing half -------------------------------------------------------------------------------

    /**
     * Anomaly 2026-10-05: a dragged task panel jumped back where it was picked up for an instant at the release. The
     * preview now waits for the records to show the commit.
     */
    @Test
    fun aReleasedDragsPreviewWaitsUntilTheRecordsShowItsCommit() {
        // The bounds a release is told apart by are the block's whole ones, not the hours of the day it is drawn in.
        fun placed(id: String, startHour: Float, endHour: Float) =
            period(id, "deep work", startHour, endHour)
                .copy(fullStartMillis = (startHour * 3_600_000f).toLong(), fullEndMillis = (endHour * 3_600_000f).toLong())
        val atRest = placed("p", 10f, 12f)
        val released = org.example.project.ui.ReleasedDrag.of(atRest)
        val other = placed("q", 14f, 15f)
        // The state has not moved yet — the records are re-derived, equal, as time passes: still waiting.
        assertFalse(released.arrivedIn(listOf(other, atRest)))
        assertFalse(released.arrivedIn(listOf(atRest.copy())))
        // The commit is drawn: the block stands elsewhere…
        assertTrue(released.arrivedIn(listOf(other, placed("p", 11f, 13f))))
        // …or under another id (a scheduler panel a hand moved becomes the user's own), or not at all.
        assertTrue(released.arrivedIn(listOf(other, placed("p2", 11f, 13f))))
        assertTrue(released.arrivedIn(listOf(other)))
    }

    /** A period is ONE box, over its own hours. */
    @Test
    fun onePeriodIsOneBox() {
        val segments = periodSegments(listOf(period("a", PeriodKinds.INACTIVITY, 10f, 12f)))
        assertEquals(1, segments.size)
        assertEquals(10f to 12f, segments.single().let { it.startHour to it.endHour })
    }

    /**
     * User rule 2026-10-05: a period A added 10–12 where a period A 11–13 already was is a blue outline round 10–12,
     * and the outline the other one had still round 11–13 — never three boxes cut at 11 and at 12.
     */
    @Test
    fun overlappingPeriodsAreEachTheirOwnBoxWithTheirOwnOutline() {
        val added = period("new", "deep work", 10f, 12f, SchedulerDomain.PanelOutline.User).copy(title = "A")
        val already = period("old", "deep work", 11f, 13f, SchedulerDomain.PanelOutline.Pattern).copy(title = "A")
        val segments = periodSegments(listOf(already, added))
        assertEquals(listOf(10f to 12f, 11f to 13f), segments.map { it.startHour to it.endHour }, "in the timeline's order")
        assertEquals(
            listOf(SchedulerDomain.PanelOutline.User, SchedulerDomain.PanelOutline.Pattern),
            segments.map(::periodSegmentOutline),
        )
        assertEquals(listOf("A", "A"), segments.map(::periodSegmentLabel))
        assertTrue(segments.all { it.records.size == 1 })
        // Of two kinds too: A 10–12 and B 11–13 are A and B, not A, "A, B" and B.
        val b = period("b", PeriodKinds.INACTIVITY, 11f, 13f).copy(title = "B")
        assertEquals(listOf("A", "B"), periodSegments(listOf(added, b)).map(::periodSegmentLabel))
    }

    /** Every box is full width — the periods never enter the shared-width layout at all — and names its period. */
    @Test
    fun periodsOverTheSameHoursAreAsManyBoxes() {
        val a = period("a", PeriodKinds.INACTIVITY, 9f, 12f).copy(title = "A")
        val b = period("b", "deep work", 9f, 12f).copy(title = "B")
        val c = period("c", PeriodKinds.BEFORE_BED, 9f, 12f).copy(title = "C")
        assertEquals(listOf("A", "B", "C"), periodSegments(listOf(a, b, c)).map(::periodSegmentLabel))
        // A period of no length draws nothing.
        assertEquals(emptyList(), periodSegments(listOf(period("z", "deep work", 9f, 9f))))
    }

    /**
     * A box wears its own period's outline, which is the user's layer example: an evidence hour draws unoutlined
     * and the hour they extended it by draws blue.
     */
    @Test
    fun eachBoxWearsItsOwnPeriodsOutline() {
        val derived = period("d", PeriodKinds.INACTIVITY, 10f, 12f, SchedulerDomain.PanelOutline.None)
        val byRule = period("r", PeriodKinds.BEFORE_BED, 10f, 12f, SchedulerDomain.PanelOutline.Pattern)
        val byHand = period("h", "deep work", 11f, 12f, SchedulerDomain.PanelOutline.User)
        val segments = periodSegments(listOf(derived, byRule, byHand))
        assertEquals(
            listOf(SchedulerDomain.PanelOutline.None, SchedulerDomain.PanelOutline.Pattern, SchedulerDomain.PanelOutline.User),
            segments.map(::periodSegmentOutline),
        )
    }

    @Test
    fun a_task_over_a_layer_band_is_two_things_so_the_menu_says_edit_with_dots() {
        // Anomaly 2026-10-01: a right-click on a task inside a "no phone unlocked" band offered "edit task panel" — the
        // layer band is in no hit list, so the count saw one thing.
        val panel = PlacedRecord("Read", 9f, 10f, scheduled = true, entryId = "panel/1", taskId = TaskId("task/1"))
        val band = PlacedRecord(
            "No phone unlocked", 8f, 12f, scheduled = false,
            layer = SchedulerDomain.ActivityLayer.NoPhoneUnlocked,
        )
        assertEquals(setOf("Task/task/1"), org.example.project.ui.calendarThingsAt(listOf(panel), emptyList()))
        assertEquals(
            setOf("Task/task/1", "RestrictivePeriod/" + PeriodKinds.NO_PHONE_UNLOCKED),
            org.example.project.ui.calendarThingsAt(listOf(panel), listOf(band)),
        )
        // A fake band is its fake kind; two boxes of one task are one thing.
        val fake = band.copy(layer = SchedulerDomain.ActivityLayer.NoComputerUnlocked, layerFake = true)
        val second = panel.copy(entryId = "panel/2")
        assertEquals(
            setOf("Task/task/1", "RestrictivePeriod/" + PeriodKinds.NOT_ON_A_COMPUTER),
            org.example.project.ui.calendarThingsAt(listOf(panel, second), listOf(fake)),
        )
    }
}
