package org.example.project

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import org.example.project.scheduler.domain.DynamicPeriods
import org.example.project.scheduler.domain.PeriodKindConfig
import org.example.project.scheduler.domain.PeriodKinds
import org.example.project.scheduler.domain.SchedulerDomain
import org.example.project.scheduler.model.SleepSchedule
import org.example.project.scheduler.model.TaskPanel
import org.example.project.scheduler.model.TaskTimeRange
import org.example.project.scheduler.state.SchedulerIntent
import org.example.project.scheduler.state.SchedulerReducer
import org.example.project.scheduler.state.SchedulerState

/**
 * `docs/scheduler_requirements.md` § *$now line$ 3 modes* (**mode 1**) and § *No idling*, over a §17 SLEEP
 * WINDOW the user is still awake inside.
 *
 * The reported anomaly (account 3, 00:43 on 2026-09-18): bedtime 23:15, so the night's window is
 * 23:15 → 07:45; the user was at the machine, mode 1; the calendar carved the Sleep band back to the now-line
 * exactly as PRD §17 says it should — and left the stretch behind the line with **no band and no task at
 * all**, because the carve was display-only and the fill went on handing the whole window to the plan as an
 * obstacle admitting nobody.
 *
 * Two requirements broken at once, which is why it is one test:
 *  - § *No idling* — *"anywhere that is not covered by restrictive periods which would PREVENT ANY TASK from
 *    being scheduled, the scheduler must schedule a task, for any $now line$ and $now line$ mode"*. In mode 1
 *    the only period left covering the line is a LAYER one ("no phone unlocked": the phone is locked, this
 *    machine is not), whose resilience defaults to `1` and which therefore prevents nobody.
 *  - § *mode 1* — *"$now line$ must not be covered by the period 'no on-screen task'. This means that if it
 *    reaches one of those periods, the passing of the $now line$ line creates task panels not covered by the
 *    period."* A sleep window IS one of those periods ([PeriodKindConfig.impliedKinds], the other half of the same
 *    report: *a sleep period must always be with a no screen period*).
 *
 * The rule the app must answer with is the requirements' own shape — *task A from 00:40 to $now line$, until
 * 01:25*: the panel's right edge is the line and it GROWS out of the line's passing, while the band ahead of
 * the line is untouched (the user will still go to bed; a lock at 01:00 flips the mode with nothing to undo).
 */
class SleepWindowNoIdlingTest {

    private val tz = TimeZone.UTC
    private val MIN = 60_000L
    private val HOUR = 60 * MIN

    private fun utc(day: Int, hour: Int, minute: Int): Long =
        LocalDateTime(2026, 9, day, hour, minute).toInstant(tz).toEpochMilliseconds()

    /** Wake 07:45, 8h30 of sleep ⇒ the reported account's 23:15 → 07:45 window. */
    private val sleep = SleepSchedule(wakeMinutes = 7 * 60 + 45, goalWakeMinutes = 7 * 60 + 45, sleepDurationMinutes = 510)

    /** 00:43 on the 18th — inside the night that began at 23:15 on the 17th. */
    private val NOW = utc(18, 0, 43)

    /** Two ordinary on-screen tasks, the production breaks, and the account's sleep schedule. */
    private fun account(): SchedulerState {
        var s = SchedulerState.empty()
        listOf("Alpha", "Beta").forEachIndexed { i, title ->
            val row = s.lists[s.rootListId]!!.cellIds.let { it.getOrNull(i) ?: it.last() }
            s = SchedulerReducer.reduce(s, SchedulerIntent.SetCellTitle(row, title))
        }
        return s.copy(screenBreaks = SchedulerDomain.DEFAULT_SCREEN_BREAKS, sleep = sleep)
    }

    private fun fill(
        state: SchedulerState,
        now: Long = NOW,
        mode: Int = DynamicPeriods.MODE_AT_SCREEN,
        horizon: Long = now + 12 * HOUR,
    ) = SchedulerDomain.fillSchedule(state, now, timeZone = tz, horizonMillis = horizon, tpMode = mode)

    private fun sleepBands(panels: List<TaskPanel>) = panels.filter { it.restrictiveKind == PeriodKinds.SLEEP }

    private fun workAt(panels: List<TaskPanel>, millis: Long) =
        panels.filter { it.auto && it.taskId != null && it.startEpochMillis <= millis && it.endEpochMillis > millis }

    // ----- the fixture really is the reported situation -------------------------------------------

    @Test
    fun the_line_sits_inside_a_sleep_window() {
        val window = SchedulerDomain.sleepPanels(sleep, NOW - 12 * HOUR, NOW + 12 * HOUR, tz)
            .single { it.startEpochMillis <= NOW && it.endEpochMillis > NOW }
        assertEquals(utc(17, 23, 15), window.startEpochMillis, "the night begins at 23:15")
        assertEquals(utc(18, 7, 45), window.endEpochMillis, "and runs to the 07:45 wake")
    }

    // ----- anomaly 2: a sleep window is a no-screen period ----------------------------------------

    @Test
    fun a_sleep_window_implies_a_no_screen_period() {
        // *"a sleep period, which must always be with a no screen period"*. A night is the plainest stretch
        // there is of nobody being at a screen — and the wind-down hour leading into it already said so, which
        // is what made the omission visible: the hour was hatched and the night it ran into was not.
        val config = PeriodKindConfig.DEFAULT
        assertEquals(setOf(PeriodKinds.NO_SCREEN), config.impliedKinds(PeriodKinds.SLEEP))
        // …and (2026-09-18) "no screen" is by default not accompanied by the layer periods, so it hatches none.
        assertEquals(emptySet(), config.assertedLayers(PeriodKinds.SLEEP))
        // It is an implication, not a second kind: `sleep` still admits nobody by its own name and still
        // refuses to have a resilience written against it.
        assertEquals(0.0, PeriodKinds.defaultResilience(PeriodKinds.SLEEP))
        assertTrue(!PeriodKinds.isResilienceEditable(PeriodKinds.SLEEP))
        // And the implied period really reaches the scheduler's period list over the window's own span.
        val window = SchedulerDomain.sleepPanels(sleep, NOW - 12 * HOUR, NOW + 12 * HOUR, tz)
            .single { it.startEpochMillis <= NOW && it.endEpochMillis > NOW }
        val implied =
            SchedulerDomain.restrictivePeriodsOf(listOf(window), config).filter { it.kind == PeriodKinds.NO_SCREEN }
        assertEquals(1, implied.size, "one companion no-screen period over the window: $implied")
        assertEquals(window.startEpochMillis, implied.single().startMillis)
        assertEquals(window.endEpochMillis, implied.single().endMillis)
    }

    @Test
    fun inactivity_implies_nothing_because_it_says_nothing_about_screens() {
        // The control for the rule above, and the one that keeps mode 1 off a period the user drew: grey says
        // the timeline is EMPTY there, which is a different fact from nobody being at a screen.
        val config = PeriodKindConfig.DEFAULT
        assertEquals(emptySet(), config.impliedKinds(PeriodKinds.INACTIVITY))
        assertTrue(!config.isOrImpliesNoScreen(PeriodKinds.INACTIVITY))
        assertTrue(config.isOrImpliesNoScreen(PeriodKinds.SLEEP))
        assertTrue(config.isOrImpliesNoScreen(PeriodKinds.BEFORE_BED))
        assertTrue(config.isOrImpliesNoScreen(PeriodKinds.NO_SCREEN))
        assertTrue(!config.isOrImpliesNoScreen("deep work"), "an account's own kind is never retracted")
    }

    // ----- anomaly 1: the line may not sit on nothing ---------------------------------------------

    @Test
    fun mode_one_schedules_a_task_at_a_line_inside_a_sleep_window() {
        val panels = fill(account())
        val atLine = workAt(panels, NOW)
        assertEquals(
            1,
            atLine.size,
            "§ No idling: a task must be at the line, the only period covering it being one that prevents " +
                "nobody. Got: ${panels.filter { it.startEpochMillis <= NOW && it.endEpochMillis > NOW }.map { it.title }}",
        )
    }

    @Test
    fun the_stretch_the_line_sweeps_inside_the_window_becomes_one_growing_task_panel() {
        // The reported rule, end to end: plan at 00:40, let the line run to 00:43, re-plan. Every millisecond
        // the line swept has to have come out of the passing as a task panel — *"task A from 00:40 to $now
        // line$"* — or the calendar draws the hole the §17 carve opened with nothing in it.
        val s = account()
        val start = utc(18, 0, 40)
        val first = fill(s, start)
        assertTrue(workAt(first, start).isNotEmpty(), "the case needs work at the line to begin with")
        val second = fill(s.copy(panels = first), NOW)

        val covered = SchedulerDomain.derivedInactivityBands(
            second.filterNot { it.screenBreak || it.isRestrictivePeriod }
                .map { TaskTimeRange(it.startEpochMillis, it.endEpochMillis) },
            start,
            NOW,
        )
        assertEquals(emptyList(), covered, "no stretch the line swept may be left with no task panel in it")
    }

    @Test
    fun the_band_ahead_of_the_line_is_untouched_on_the_display() {
        // The other half of the same rule: the retraction is what the PASSING of the line does, so what the
        // user SEES of it reaches only as far as the line has gone. The user will still go to bed — a lock at
        // 01:00 flips the mode with nothing to undo — so the night ahead goes on reading as sleep.
        //
        // The plan itself runs across it, and must: it has to name which task holds and until when, and the
        // fill runs at a rule change rather than on time passing, so the line needs panels to be swept INTO
        // between two fills. What hides them ahead of the line is the display clip, exactly as for the plan
        // under a pinned screen break.
        val panels = fill(account())
        val band = sleepBands(panels).single { it.startEpochMillis <= NOW && it.endEpochMillis > NOW }
        assertEquals(utc(18, 7, 45), band.endEpochMillis, "the window keeps its own end")
        assertTrue(
            panels.any {
                it.auto && it.taskId != null && it.startEpochMillis > NOW && it.startEpochMillis < band.endEpochMillis
            },
            "the PLAN runs across the retracted night, or the line has nothing to be swept into",
        )

        val drawn = SchedulerDomain.clipPlanForRetractedPeriod(panels, sleepBands(panels), NOW, DynamicPeriods.MODE_AT_SCREEN, PeriodKindConfig.DEFAULT)
        val ahead = drawn.filter { it.auto && it.taskId != null && it.startEpochMillis > NOW }
        assertTrue(
            ahead.none { it.startEpochMillis < band.endEpochMillis },
            "nothing may be DRAWN into the night ahead of the line: " +
                "${ahead.filter { it.startEpochMillis < band.endEpochMillis }.map { it.title }}",
        )
        // …the plan past the wake is still drawn, so the horizon is reached as usual…
        assertTrue(
            ahead.any { it.startEpochMillis >= band.endEpochMillis },
            "the plan for after the wake must still be on the calendar",
        )
        // …and the clip reaches FORWARD only, so the task at the line survives it. A clip reaching behind the
        // line would put the empty stretch straight back (§ *No idling*).
        assertEquals(1, workAt(drawn, NOW).size, "the task at the line survives the display clip")
    }

    // ----- the controls ---------------------------------------------------------------------------

    @Test
    fun the_away_modes_do_not_retract_the_window_at_all() {
        // Modes 2 and 3 state the OPPOSITE clause — *"$now line$ must be covered by the period 'no on-screen
        // task'"* — so a sleep window the line is inside is an ordinary stretch of the timeline there and
        // nothing may run in it. This is the user genuinely asleep, which is the case the window is for.
        for (mode in listOf(DynamicPeriods.MODE_AWAY, DynamicPeriods.MODE_ON_BREAK)) {
            val panels = fill(account(), mode = mode)
            assertEquals(
                emptyList(),
                workAt(panels, NOW),
                "mode $mode must leave the line covered by the sleep window",
            )
            val band = sleepBands(panels).single { it.startEpochMillis <= NOW && it.endEpochMillis > NOW }
            assertTrue(
                panels.none {
                    it.auto && it.taskId != null &&
                        it.startEpochMillis < band.endEpochMillis && it.endEpochMillis > band.startEpochMillis
                },
                "and no task may be planned anywhere inside it in mode $mode",
            )
        }
    }

    @Test
    fun a_window_the_line_has_not_reached_is_never_retracted() {
        // The clause is about the line being COVERED. A night still ahead is an ordinary pre-placed
        // restrictive period, in mode 1 like any other, and the § *Starting timeline* rule that pre-placed
        // periods never change is what it rests on.
        val daytime = utc(18, 12, 0)
        val panels = fill(account(), now = daytime, horizon = daytime + 24 * HOUR)
        val nextNight = sleepBands(panels).single { it.startEpochMillis > daytime }
        assertEquals(utc(18, 23, 15), nextNight.startEpochMillis)
        assertTrue(
            panels.none {
                it.auto && it.taskId != null &&
                    it.startEpochMillis < nextNight.endEpochMillis && it.endEpochMillis > nextNight.startEpochMillis
            },
            "tomorrow night must stay empty",
        )
    }

    @Test
    fun the_display_clip_leaves_no_hole_behind_the_line_as_it_advances() {
        // The reported rule, end to end, over what the user actually SEES: plan at 00:40, let the line run to
        // 00:43, re-plan, and draw. *"Task A from 00:40 to $now line$"* — every millisecond the line swept has
        // to still carry a task panel, or the calendar draws the hole §17's activity carve opened in the band
        // with nothing in it, which is the anomaly.
        val s = account()
        val start = utc(18, 0, 40)
        val first = fill(s, start)
        val firstDrawn = SchedulerDomain.clipPlanForRetractedPeriod(
            first, sleepBands(first), start, DynamicPeriods.MODE_AT_SCREEN, PeriodKindConfig.DEFAULT,
        )
        assertTrue(workAt(firstDrawn, start).isNotEmpty(), "the case needs work at the line to begin with")

        val second = fill(s.copy(panels = first), NOW)
        val drawn = SchedulerDomain.clipPlanForRetractedPeriod(
            second, sleepBands(second), NOW, DynamicPeriods.MODE_AT_SCREEN, PeriodKindConfig.DEFAULT,
        )
        val uncovered = SchedulerDomain.derivedInactivityBands(
            drawn.filterNot { it.screenBreak || it.isRestrictivePeriod }
                .map { TaskTimeRange(it.startEpochMillis, it.endEpochMillis) },
            start,
            NOW,
        )
        assertEquals(emptyList(), uncovered, "no stretch the line swept may be left with no task panel in it")
    }

    @Test
    fun the_wind_down_hour_keeps_its_own_period_when_the_line_is_in_it() {
        // `before bed` is or implies a no-screen period too, so mode 1 lifts THAT — but not the wind-down
        // period itself, whose resilience is editable ([PeriodKinds.isResilienceEditable]). PRD §17's
        // *"a task the user gives a value above 0 works through the wind-down"* is the sanctioned way anything
        // runs there, and § *No idling*'s own clause is satisfied while nobody has been given one: the hour is
        // exactly an hour the user is at a screen for, so retracting it would delete the wind-down outright.
        val windDown = utc(17, 22, 30)
        val panels = fill(account(), now = windDown, horizon = windDown + 6 * HOUR)
        val hour = panels.single { it.restrictiveKind == PeriodKinds.BEFORE_BED && it.startEpochMillis <= windDown }
        assertEquals(utc(17, 22, 15), hour.startEpochMillis)
        assertEquals(utc(17, 23, 15), hour.endEpochMillis, "the wind-down keeps its whole hour")
        assertEquals(emptyList(), workAt(panels, windDown), "and nobody resilient to it means nobody in it")
    }

    @Test
    fun a_task_resilient_to_before_bed_works_through_the_wind_down_at_the_line() {
        // The other side of the same test: once the user HAS let somebody through, the line is no longer
        // covered by anything that prevents every task — so § *No idling* requires that task to be there. It
        // reaches the line only because mode 1 lifted the hour's implied no-screen period; an on-screen task
        // would otherwise still be kept out by that.
        var s = account()
        val alpha = s.tasks.keys.first { s.tasks[it]!!.title == "Alpha" }
        s = SchedulerReducer.reduce(s, SchedulerIntent.SetTaskResilience(alpha, PeriodKinds.BEFORE_BED, 1.0))
        val windDown = utc(17, 22, 30)
        val panels = fill(s, now = windDown, horizon = windDown + 6 * HOUR)
        val atLine = workAt(panels, windDown)
        assertEquals(1, atLine.size, "the task the user let through must be at the line: $atLine")
        assertEquals(alpha, atLine.single().taskId)
    }
}
