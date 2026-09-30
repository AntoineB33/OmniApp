package org.example.project

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.example.project.scheduler.domain.BreakMachine
import org.example.project.scheduler.domain.DynamicPeriods
import org.example.project.scheduler.domain.FrozenScreenBreaks
import org.example.project.scheduler.domain.PeriodKindConfig
import org.example.project.scheduler.domain.PeriodKinds
import org.example.project.scheduler.domain.RestrictivePeriod
import org.example.project.scheduler.domain.SchedulerDomain
import org.example.project.scheduler.model.ScreenBreak
import org.example.project.scheduler.model.TaskPanel
import org.example.project.scheduler.model.TaskTimeRange
import org.example.project.scheduler.state.SchedulerIntent
import org.example.project.scheduler.state.SchedulerReducer
import org.example.project.scheduler.state.SchedulerState

/**
 * `docs/scheduler_requirements.md` § *Default restrictive periods*: **where the 20 s, the 5 min and the 15 min breaks
 * go**, as the app reads it — the break machine's placement ([SchedulerDomain.screenBreakPanels] for what is drawn,
 * [SchedulerDomain.breaksTheLineWillMeet] for what the plan is built around), the banked record behind the line and
 * the cues. The machine's own transitions, one requirement each, are `BreakMachineTest`.
 */
class DynamicPeriodsTest {

    private val SEC = 1_000L
    private val MIN = 60_000L
    private val HOUR = 3_600_000L
    private val NOW = 1_000_000_000_000L

    private val breaks = SchedulerDomain.DEFAULT_SCREEN_BREAKS
    private val lookAway = breaks.first { it.durationMillis == 20 * SEC }.title
    private val pose5 = breaks.first { it.durationMillis == 5 * MIN }.title
    private val pose15 = breaks.first { it.durationMillis == 15 * MIN }.title

    /** The breaks as drawn from a line at [NOW] that has nothing to continue from (it starts rested). */
    private fun place(
        toMillis: Long = NOW + 6 * HOUR,
        periods: List<RestrictivePeriod> = emptyList(),
        sides: List<ScreenBreak> = breaks,
        mode: Int = DynamicPeriods.MODE_AT_SCREEN,
    ): List<TaskPanel> = SchedulerDomain.screenBreakPanels(sides, NOW, toMillis, periods, mode)

    private fun starts(panels: List<TaskPanel>, title: String) =
        panels.filter { it.title == title }.map { it.startEpochMillis - NOW }

    /** The line moved from [fromMillis] to [toMillis] in [mode], one step every [stepMillis]: the record it banks and every transition. */
    private fun walkLine(
        fromMillis: Long,
        toMillis: Long,
        mode: Int = DynamicPeriods.MODE_AT_SCREEN,
        sides: List<ScreenBreak> = breaks,
        stepMillis: Long = 10 * SEC,
    ): Pair<FrozenScreenBreaks?, List<BreakMachine.Event>> {
        var record: FrozenScreenBreaks? = null
        val events = ArrayList<BreakMachine.Event>()
        var t = fromMillis
        while (t <= toMillis) {
            val step = SchedulerDomain.stepScreenBreaks(sides, record, t, emptyList(), mode)
            record = step.record
            events += step.events
            t += stepMillis
        }
        return record to events
    }

    // ----- the three bars -----------------------------------------------------------------------

    @Test
    fun after_any_dynamic_period_there_is_no_20s_period_for_twenty_minutes() {
        val panels = place()
        assertTrue(panels.isNotEmpty(), "the placement must produce something to be about")
        for (i in panels.indices) {
            for (j in i + 1 until panels.size) {
                if (panels[j].title != lookAway) continue
                val gap = panels[j].startEpochMillis - panels[i].endEpochMillis
                assertTrue(
                    gap >= DynamicPeriods.BAR_20S_AFTER_ANY_MILLIS || gap < 0,
                    "a 20 s period fell ${gap / 1000}s after a dynamic period ending at " +
                        "${(panels[i].endEpochMillis - NOW) / 1000}s",
                )
            }
        }
    }

    @Test
    fun after_a_five_minute_rest_stretch_there_is_no_5min_period_for_an_hour() {
        val panels = place()
        val fiveMinStarts = starts(panels, pose5)
        assertTrue(fiveMinStarts.isNotEmpty(), "the 5 min period must appear at all")
        for (panel in panels) {
            val length = panel.endEpochMillis - panel.startEpochMillis
            if (length < DynamicPeriods.STRETCH_SHORT_MILLIS) continue
            for (start in fiveMinStarts.map { it + NOW }) {
                if (start <= panel.endEpochMillis) continue
                assertTrue(
                    start - panel.endEpochMillis >= DynamicPeriods.BAR_5MIN_AFTER_STRETCH_MILLIS,
                    "a 5 min period fell ${(start - panel.endEpochMillis) / 60000}min after a rest stretch",
                )
            }
        }
    }

    @Test
    fun after_a_fifteen_minute_rest_stretch_there_is_no_15min_period_for_two_hours() {
        val panels = place(toMillis = NOW + 12 * HOUR)
        val long = panels.filter { it.title == pose15 }.map { it.startEpochMillis }
        assertTrue(long.size >= 2, "the case needs two 15 min periods to be about")
        for (i in 0 until long.size - 1) {
            val end = long[i] + 15 * MIN
            assertTrue(
                long[i + 1] - end >= DynamicPeriods.BAR_15MIN_AFTER_LONG_MILLIS,
                "two 15 min periods only ${(long[i + 1] - end) / 60000}min apart",
            )
        }
    }

    @Test
    fun a_hand_drawn_rest_stretch_takes_one_break_at_its_start_and_bars_the_periods_that_follow_it() {
        // A no-screen period ahead: every break falling due in it starts at its start (the requirements' last screen
        // break rule), and a >= 15-minute stretch bars what comes AFTER it.
        val quiet = RestrictivePeriod(NOW + 10 * MIN, NOW + 40 * MIN, PeriodKinds.INACTIVITY, "Inactivity")
        val panels = place(periods = listOf(quiet))
        assertEquals(10 * MIN, starts(panels, lookAway).minOrNull(), "the 20 s period the stretch takes starts where the stretch does")
        val firstLookAwayAfter = starts(panels, lookAway).filter { it >= 40 * MIN }.minOrNull()
        assertTrue(firstLookAwayAfter != null)
        assertTrue(
            firstLookAwayAfter - 40 * MIN >= DynamicPeriods.BAR_20S_AFTER_LONG_MILLIS,
            "a 30-minute rest must bar the 20 s period for 20 minutes after it; got ${(firstLookAwayAfter - 40 * MIN) / 60000}min",
        )
        val first15 = starts(panels, pose15).minOrNull()
        assertTrue(first15 != null)
        assertTrue(first15 >= 40 * MIN + DynamicPeriods.BAR_15MIN_AFTER_LONG_MILLIS, "…and the 15 min period for two hours")
    }

    // ----- the chain rule -----------------------------------------------------------------------

    @Test
    fun no_two_breaks_ever_overlap() {
        // "Where the rules allow a continuous chain of breaks, the interval of the whole chain only contains one
        // screen break": whatever is placed, nothing overlaps.
        val panels = place(toMillis = NOW + 24 * HOUR)
        for (i in 0 until panels.size - 1) {
            assertTrue(panels[i + 1].startEpochMillis >= panels[i].endEpochMillis, "overlap at ${panels[i]} / ${panels[i + 1]}")
        }
    }

    // ----- the kind -----------------------------------------------------------------------------

    @Test
    fun each_of_the_three_is_a_period_of_the_kind_of_its_role() {
        val panels = place(toMillis = NOW + 6 * HOUR)
        assertEquals(
            setOf(PeriodKinds.INACTIVITY, PeriodKinds.BREAK_5MIN, PeriodKinds.BREAK_15MIN),
            panels.map { it.restrictiveKind }.toSet(),
        )
    }

    // ----- the t_p modes, as the app reads them --------------------------------------------------

    @Test
    fun mode_one_drags_a_pose_the_line_has_reached_ahead_of_it() {
        // "If the $now line$ reaches a 5min break in mode 1, this 5min break becomes ]$now line$; $now line$ + 5min]".
        val (record, _) = walkLine(NOW, NOW + 70 * MIN)
        val drawn = SchedulerDomain.screenBreakPanels(breaks, NOW + 70 * MIN, NOW + 2 * HOUR, emptyList(), frozen = record)
        val dragged = drawn.first { it.title == pose5 }
        assertTrue(SchedulerDomain.isDraggedScreenBreak(dragged), "the reached pose is dragged")
        assertEquals(NOW + 70 * MIN + 1, dragged.startEpochMillis, "the instant t_p itself is not covered")
        assertEquals(NOW + 75 * MIN + 1, dragged.endEpochMillis, "every instant after it is, up to t_p + 5 min")
        assertTrue(
            record!!.breaks.none { it.label == DynamicPeriods.LABEL_5MIN },
            "a dragged pose never happens, so nothing of it is banked",
        )
    }

    @Test
    fun mode_one_enters_the_look_away_and_it_stays_where_it_happened() {
        val (record, _) = walkLine(NOW, NOW + 30 * MIN)
        val drawn = SchedulerDomain.takenScreenBreakPanels(breaks, NOW, NOW + 30 * MIN, record)
        assertEquals(listOf(NOW + 20 * MIN), drawn.filter { it.title == lookAway }.map { it.startEpochMillis })
        assertTrue(drawn.all { it.endEpochMillis - it.startEpochMillis == 20 * SEC }, "it lasts exactly its length")
    }

    @Test
    fun the_plan_is_built_around_the_look_aways_before_the_first_pose_only() {
        val met = SchedulerDomain.breaksTheLineWillMeet(breaks, NOW, NOW + 6 * HOUR, emptyList(), DynamicPeriods.MODE_AT_SCREEN, null)
        assertEquals(listOf(lookAway, lookAway), met.map { it.title })
        assertTrue(met.all { it.startEpochMillis < NOW + HOUR }, "a pose the line reaches is dragged; nothing after it happens")
    }

    @Test
    fun the_cue_is_the_whole_of_what_tells_mode_two_from_mode_three() {
        assertTrue(DynamicPeriods.breaksAreNotifiedAt(DynamicPeriods.MODE_AT_SCREEN))
        assertTrue(!DynamicPeriods.breaksAreNotifiedAt(DynamicPeriods.MODE_AWAY))
        assertTrue(DynamicPeriods.breaksAreNotifiedAt(DynamicPeriods.MODE_ON_BREAK))
        val events =
            listOf(
                BreakMachine.Event.Started(DynamicPeriods.LABEL_20S, NOW - 30 * MIN, NOW - 30 * MIN + 20 * SEC),
                BreakMachine.Event.Owed(NOW - 5 * MIN, DynamicPeriods.LABEL_5MIN),
            )
        fun crossings(mode: Int) =
            SchedulerDomain.cueCrossings(
                screenBreaks = breaks,
                breakEvents = events,
                mode = mode,
                windDownInstants = listOf(NOW - MIN),
                automaticSchedule = true,
                alreadyNotifiedPoseDues = emptyMap(),
                fromMillis = NOW - 6 * HOUR,
                toMillis = NOW,
            )
        assertTrue(crossings(DynamicPeriods.MODE_AT_SCREEN).any { it.kind != SchedulerDomain.CueKind.WindDown })
        assertTrue(crossings(DynamicPeriods.MODE_ON_BREAK).any { it.kind != SchedulerDomain.CueKind.WindDown })
        assertEquals(
            listOf(SchedulerDomain.CueKind.WindDown),
            crossings(DynamicPeriods.MODE_AWAY).map { it.kind },
            "mode 2 announces no screen break at all; the wind-down is not one",
        )
    }

    @Test
    fun a_pose_is_announced_where_it_falls_due_and_a_look_away_where_the_line_enters_it() {
        // The cues are the machine's own transitions: nothing is re-derived to find them.
        val (record, events) = walkLine(NOW, NOW + 90 * MIN)
        val crossings =
            SchedulerDomain.cueCrossings(
                screenBreaks = breaks,
                breakEvents = events,
                mode = DynamicPeriods.MODE_AT_SCREEN,
                windDownInstants = emptyList(),
                automaticSchedule = true,
                alreadyNotifiedPoseDues = emptyMap(),
                fromMillis = NOW,
                toMillis = NOW + 90 * MIN,
            )
        assertEquals(
            listOf(NOW + HOUR),
            crossings.filter { it.kind == SchedulerDomain.CueKind.RestPoseDue }.map { it.instant },
            "the 5 min pose falls due at the hour",
        )
        assertEquals(
            record!!.breaks.filter { it.label == DynamicPeriods.LABEL_20S }.map { it.startMillis },
            crossings.filter { it.kind == SchedulerDomain.CueKind.LookAwayStart }.map { it.instant },
            "every look-away the line entered — and banked — is announced, and nothing else is",
        )
    }

    // ----- what follows from the bars -----------------------------------------------------------

    @Test
    fun a_sub_minute_cadence_cannot_flood_the_timeline() {
        val dense = ScreenBreak("dense", intervalMillis = 1_000L, durationMillis = 1_000L)
        val panels = place(toMillis = NOW + 6 * HOUR, sides = listOf(dense))
        assertTrue(panels.isNotEmpty())
        val gaps = panels.zipWithNext { a, b -> b.startEpochMillis - a.endEpochMillis }
        assertTrue(gaps.all { it >= DynamicPeriods.BAR_20S_AFTER_ANY_MILLIS }, "gaps ${gaps.map { it / 1000 }}")
        assertTrue(panels.size <= 6 * 3 + 1)
    }

    @Test
    fun the_mode_follows_whether_any_device_is_unlocked() {
        val unlocked = SchedulerDomain.anyDeviceUnlockedAt(emptyList(), null, null, NOW)
        assertTrue(unlocked, "no pause anywhere means somebody is at a screen")
        assertEquals(DynamicPeriods.MODE_AT_SCREEN, SchedulerDomain.tpMode(unlocked))
        assertEquals(DynamicPeriods.MODE_AT_SCREEN, SchedulerDomain.tpMode(unlocked, awayDeclared = true))
        val locked = SchedulerDomain.anyDeviceUnlockedAt(emptyList(), NOW - 10 * MIN, null, NOW)
        assertTrue(!locked, "an ongoing pause covers the line, so no device is unlocked")
        assertEquals(DynamicPeriods.MODE_AWAY, SchedulerDomain.tpMode(locked))
        assertEquals(DynamicPeriods.MODE_ON_BREAK, SchedulerDomain.tpMode(locked, awayDeclared = true))
        assertTrue(SchedulerDomain.anyDeviceUnlockedAt(emptyList(), NOW - 10 * MIN, NOW - MIN, NOW))
        val banked = listOf(TaskTimeRange(NOW - HOUR, NOW - 30 * MIN))
        assertTrue(SchedulerDomain.anyDeviceUnlockedAt(banked, null, null, NOW))
    }

    @Test
    fun the_line_in_a_20s_break_it_entered_at_a_screen_is_in_mode_3() {
        // § *Mode switching*: "can't go in mode 1 during a '20s screen break'".
        val (record, _) = walkLine(NOW, NOW + 20 * MIN + 10 * SEC)
        assertEquals(
            DynamicPeriods.MODE_ON_BREAK,
            SchedulerDomain.effectiveTpMode(record, DynamicPeriods.MODE_AT_SCREEN, NOW + 20 * MIN + 10 * SEC),
        )
        assertEquals(
            DynamicPeriods.MODE_AT_SCREEN,
            SchedulerDomain.effectiveTpMode(record, DynamicPeriods.MODE_AT_SCREEN, NOW + 21 * MIN),
            "and back in mode 1 once it has left it",
        )
    }

    @Test
    fun an_ongoing_pause_covers_the_line() {
        val ongoing =
            SchedulerDomain.liveRestPeriod(SchedulerDomain.LiveRest(TaskTimeRange(NOW - 10 * MIN, NOW), ongoing = true))
        assertTrue(ongoing != null && ongoing.covers(NOW), "an ongoing pause covers the now-line")
        val held =
            SchedulerDomain.liveRestPeriod(SchedulerDomain.LiveRest(TaskTimeRange(NOW - 10 * MIN, NOW), ongoing = false))
        assertTrue(held != null && !held.covers(NOW), "one the user has come back from does not")
    }

    // ----- what the DEVICES observed is a rest stretch too ---------------------------------------

    @Test
    fun a_pause_the_devices_observed_bars_the_5min_period_for_an_hour() {
        // The reported anomaly (2026-08-29): both layers said "nobody unlocked" for 13 minutes and a 5-minute pose was
        // owed twelve minutes later, inside the hour the requirements bar it in.
        val restEnd = NOW - 10 * MIN
        val observed = SchedulerDomain.observedNoScreenPeriods(listOf(TaskTimeRange(restEnd - 13 * MIN, restEnd)))
        assertEquals(PeriodKinds.NO_SCREEN, observed.single().kind)
        val barred = place(periods = observed)
        val offending = starts(barred, pose5).filter { NOW + it < restEnd + HOUR }
        assertTrue(offending.isEmpty(), "a 13-minute observed pause bars the 5 min period for an hour after it: $offending")
        assertEquals(restEnd + HOUR - NOW, starts(barred, pose5).minOrNull())
    }

    @Test
    fun the_fill_is_handed_what_the_devices_observed() {
        var state = SchedulerState.empty()
        state = SchedulerReducer.reduce(state, SchedulerIntent.SetCellTitle(state.lists[state.rootListId]!!.cellIds[0], "Screen work"))
        state = state.copy(screenBreaks = SchedulerDomain.DEFAULT_SCREEN_BREAKS)
        fun poses(evidence: List<TaskTimeRange>) =
            SchedulerDomain.fillSchedule(state, NOW, horizonMillis = NOW + 6 * HOUR, noScreenEvidence = evidence)
                .filter { it.title == pose5 }.map { it.startEpochMillis }
        assertEquals(NOW + HOUR, poses(emptyList()).minOrNull(), "rested at the line: the 5 min pose an hour on")
        val restEnd = NOW - 5 * MIN
        val observed = listOf(TaskTimeRange(restEnd - 13 * MIN, restEnd))
        assertTrue(
            poses(observed).none { it < restEnd + HOUR },
            "the fill must bar the 5 min pose for an hour after a pause the devices observed",
        )
    }

    @Test
    fun the_placement_environment_is_assembled_in_one_place() {
        val drawn =
            TaskPanel(
                id = "p1", taskId = null, title = "Inactivity",
                startEpochMillis = NOW - 4 * HOUR, endEpochMillis = NOW - 3 * HOUR,
                inactivity = true, periodKind = PeriodKinds.INACTIVITY,
            )
        val base =
            SchedulerDomain.dynamicPeriodBase(
                panels = listOf(drawn),
                config = PeriodKindConfig.DEFAULT,
                liveRest = SchedulerDomain.LiveRest(TaskTimeRange(NOW - 10 * MIN, NOW), ongoing = true),
                noScreenEvidence = listOf(TaskTimeRange(NOW - 2 * HOUR, NOW - 90 * MIN)),
            )
        assertEquals(3, base.size, "one period per source, and none of the three dropped")
        assertTrue(base.any { it.startMillis == NOW - 4 * HOUR }, "the panel the user drew")
        assertTrue(base.any { it.covers(NOW) }, "the live pause, covering the line")
        assertTrue(base.any { it.startMillis == NOW - 2 * HOUR && it.kind == PeriodKinds.NO_SCREEN }, "what the devices observed")
    }

    // ----- a break the app CONDUCTED is a dynamic period, and bars like one -----------------------

    @Test
    fun a_break_the_app_conducted_bars_the_20s_period_for_twenty_minutes() {
        // The reported anomaly (2026-09-03): the user pressed "Look away now" and the instant it finished another 20 s
        // period was owed at the line. A conducted break is one of the three: "after the end of a screen break, no 20s
        // break in the next 20 minutes".
        val earlierRest = RestrictivePeriod(NOW - 50 * MIN, NOW - 30 * MIN, PeriodKinds.INACTIVITY, "Inactivity")
        assertTrue(
            starts(place(periods = listOf(earlierRest)), lookAway).any { it in 0 until DynamicPeriods.BAR_20S_AFTER_ANY_MILLIS },
            "the scenario must be one where a 20 s period falls due inside the next twenty minutes",
        )
        val conducted =
            SchedulerReducer.reduce(SchedulerState.empty(), SchedulerIntent.RecordConductedBreak(lookAway, NOW - 20 * SEC, NOW)).panels
        assertTrue(conducted.single().conductedBreak, "the recorded break must say it was one of the three")
        val periods = SchedulerDomain.restrictivePeriodsOf(conducted, PeriodKindConfig.DEFAULT)
        assertTrue(periods.any { it.dynamic }, "…and reach the machine as a DYNAMIC period")
        val offending = starts(place(periods = listOf(earlierRest) + periods), lookAway).filter { it < DynamicPeriods.BAR_20S_AFTER_ANY_MILLIS }
        assertTrue(offending.isEmpty(), "a 20 s period fell ${offending.firstOrNull()?.div(1000)}s after a conducted look-away")
    }

    @Test
    fun a_hand_drawn_twenty_second_inactivity_is_not_a_dynamic_period() {
        val earlierRest = RestrictivePeriod(NOW - 50 * MIN, NOW - 30 * MIN, PeriodKinds.INACTIVITY, "Inactivity")
        val drawn = RestrictivePeriod(NOW - 20 * SEC, NOW, PeriodKinds.INACTIVITY, "Inactivity")
        assertEquals(
            starts(place(periods = listOf(earlierRest)), lookAway),
            starts(place(periods = listOf(earlierRest, drawn)), lookAway),
            "a hand-drawn 20 s inactivity bars nothing",
        )
    }
}
