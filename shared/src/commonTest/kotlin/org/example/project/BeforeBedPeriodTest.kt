package org.example.project

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import org.example.project.scheduler.domain.PeriodKindConfig
import org.example.project.scheduler.domain.PeriodKinds
import org.example.project.scheduler.domain.RestrictivePeriod
import org.example.project.scheduler.domain.SchedulerDomain
import org.example.project.scheduler.model.TaskId
import org.example.project.scheduler.model.TaskTimeRange
import org.example.project.scheduler.persistence.SchedulerStateCodec
import org.example.project.scheduler.state.SchedulerIntent
import org.example.project.scheduler.state.SchedulerReducer
import org.example.project.scheduler.state.SchedulerState

/**
 * PRD §17 **the hour before bed is covered by the period "before bed"**.
 *
 * The wind-down used to be a hard-coded extension of the sleep obstacle — a second mechanism for "where may
 * this task run", which is exactly what the resilience model exists to prevent (CLAUDE.md). It is now one
 * restrictive period of one built-in KIND ([PeriodKinds.BEFORE_BED]), and the hour stays empty for the one
 * reason any period empties a stretch: every task's default resilience to that kind is `0`.
 */
class BeforeBedPeriodTest {
    private val tz = TimeZone.UTC
    private val HOUR_MS = 3_600_000L

    private fun utc(year: Int, month: Int, day: Int, hour: Int, minute: Int): Long =
        LocalDateTime(year, month, day, hour, minute).toInstant(tz).toEpochMilliseconds()

    /** A single "Solo" task (45-min minimum), the tree the fill runs over. */
    private fun soloTask(): Pair<SchedulerState, TaskId> {
        var s = SchedulerState.empty()
        val c0 = s.lists[s.rootListId]!!.cellIds[0]
        s = SchedulerReducer.reduce(s, SchedulerIntent.SetCellTitle(c0, "Solo"))
        val solo = s.tasks.keys.first { s.tasks[it]!!.title == "Solo" }
        return SchedulerReducer.reduce(s, SchedulerIntent.SetTaskMinimumTime(solo, 45)) to solo
    }

    private fun sleeping(): SchedulerState = soloTask().first.copy(sleep = SchedulerDomain.DEFAULT_SLEEP)

    // ----- the kind ------------------------------------------------------------------------------

    @Test
    fun before_bed_is_a_built_in_kind_that_turns_every_task_away_by_default() {
        assertTrue(PeriodKinds.BEFORE_BED in PeriodKinds.BUILT_IN)
        assertEquals(0.0, PeriodKinds.defaultResilience(PeriodKinds.BEFORE_BED))
        // Editable, unlike "no task allowed": "I may still do this before bed" is a value a task may hold.
        assertTrue(PeriodKinds.isResilienceEditable(PeriodKinds.BEFORE_BED))
        // Not the user's, so it can be neither defined again nor deleted — like the two the README names.
        assertFalse(PeriodKinds.isUserDefined(PeriodKinds.BEFORE_BED))
        val s0 = SchedulerState.empty()
        assertEquals(
            s0.periodKinds,
            SchedulerReducer.reduce(s0, SchedulerIntent.AddPeriodKind("before bed")).periodKinds,
        )
        assertTrue(PeriodKinds.BEFORE_BED in s0.allPeriodKinds)
    }

    @Test
    fun every_before_bed_period_is_also_a_no_screen_period() {
        // The user's rule: "Everytime there is 'before bed' restrictive period, there is also 'no screen'
        // period" — a COMPANION of the kind by default, not a companion panel. And (2026-09-18) "no screen" is
        // by default NOT accompanied by the two layer periods, so the hour asserts no layer.
        val config = PeriodKindConfig.DEFAULT
        assertEquals(setOf(PeriodKinds.NO_SCREEN), config.impliedKinds(PeriodKinds.BEFORE_BED))
        assertEquals(emptySet(), config.assertedLayers(PeriodKinds.BEFORE_BED))
        // ...but it is not a no-screen period BY NAME: it still turns everybody away by default, and the
        // bars read the implied period rather than a second answer off the kind.
        assertFalse(PeriodKinds.isLayerKind(PeriodKinds.BEFORE_BED))
        assertFalse(PeriodKinds.coversNoScreen(PeriodKinds.BEFORE_BED))
        // `sleep` implies it too (2026-09-18): a night is the plainest stretch there is of nobody being at a
        // screen, and the hour of wind-down leading into it already said so. `inactivity` and an account's own
        // kind do NOT — they say the timeline is empty there, which is a different fact.
        assertEquals(setOf(PeriodKinds.NO_SCREEN), config.impliedKinds(PeriodKinds.SLEEP))
        listOf(PeriodKinds.NO_SCREEN, PeriodKinds.INACTIVITY, "deep focus").forEach {
            assertEquals(emptySet(), config.impliedKinds(it), it)
        }
    }

    @Test
    fun the_wind_down_hour_carries_its_no_screen_period_and_no_layer() {
        // The no-screen period the scheduler reads over the next before-bed hour — and, since "no screen" is not
        // accompanied by the layer periods by default, no layer hatch over it.
        val now = utc(2024, 1, 1, 10, 0)
        val panels = SchedulerDomain.fillSchedule(sleeping(), now, tz)
        val windDowns = panels.filter { it.id.startsWith(SchedulerDomain.BEFORE_BED_PANEL_ID_PREFIX) }
        assertTrue(windDowns.isNotEmpty())
        val spans = windDowns.map { TaskTimeRange(it.startEpochMillis, it.endEpochMillis) }.sortedBy { it.startEpochMillis }
        // The hour runs straight into the §17 window it is measured back from, and a sleep window implies a
        // no-screen period of its own (2026-09-18), so the hatch over the pair is ONE stretch per night —
        // wind-down start to wake — not the hour alone. Two abutting statements of "nobody is at a screen"
        // are one statement, which is the same merge every other layer reading takes.
        val sleeps = panels.filter { it.restrictiveKind == PeriodKinds.SLEEP }
            .map { TaskTimeRange(it.startEpochMillis, it.endEpochMillis) }
        assertTrue(sleeps.isNotEmpty(), "the fixture sleeps")
        val hatched = SchedulerDomain.mergeOccupied(spans + sleeps)
        val config = PeriodKindConfig.DEFAULT
        SchedulerDomain.ActivityLayer.entries.forEach { layer ->
            assertEquals(emptyList(), SchedulerDomain.assertedLayerRanges(panels, layer, config), "$layer hatch")
        }
        assertEquals(hatched, SchedulerDomain.assertedNoScreenRanges(panels, config))
        // …and each night's hatch really does start at the wind-down hour rather than at bedtime.
        spans.forEach { hour ->
            assertTrue(
                hatched.any { it.startEpochMillis == hour.startEpochMillis && it.endEpochMillis > hour.endEpochMillis },
                "the hour at ${hour.startEpochMillis} must open a stretch that outlasts it: $hatched",
            )
        }
        val implied = SchedulerDomain.companionPeriods(
            panels.filter { it.isRestrictivePeriod && !it.screenBreak }.map {
                RestrictivePeriod(it.startEpochMillis, it.endEpochMillis, it.restrictiveKind, it.title)
            },
            config,
        )
        assertEquals(hatched, implied.map { TaskTimeRange(it.startMillis, it.endMillis) }.sortedBy { it.startEpochMillis })
        assertTrue(implied.all { it.kind == PeriodKinds.NO_SCREEN })
        // restrictivePeriodsOf (the display's and the cue's environment) carries both kinds over the hour.
        val periods = SchedulerDomain.restrictivePeriodsOf(windDowns, config)
        spans.forEach { span ->
            val here = periods.filter { it.startMillis == span.startEpochMillis && it.endMillis == span.endEpochMillis }
            assertEquals(setOf(PeriodKinds.BEFORE_BED, PeriodKinds.NO_SCREEN), here.map { it.kind }.toSet())
        }
    }

    @Test
    fun an_explicit_no_screen_period_over_the_hour_is_not_counted_twice() {
        // The plan MULTIPLIES covering resiliences, so a hand-drawn "No screen" period over a wind-down hour
        // must not be joined by a second, implied one — that would square a fractional resilience.
        val now = utc(2024, 1, 1, 10, 0)
        val windDown = SchedulerDomain.beforeBedPanels(SchedulerDomain.DEFAULT_SLEEP, now, now + 24 * HOUR_MS, tz).first()
        val drawn = SchedulerReducer.reduce(
            SchedulerState.empty(),
            SchedulerIntent.AddRestrictivePeriod(
                PeriodKinds.NO_SCREEN, windDown.startEpochMillis - HOUR_MS, windDown.endEpochMillis,
            ),
        ).panels.filter { it.isRestrictivePeriod }
        val periods = SchedulerDomain.restrictivePeriodsOf(drawn + windDown, PeriodKindConfig.DEFAULT)
        assertEquals(1, periods.count { it.kind == PeriodKinds.NO_SCREEN })
    }

    @Test
    fun a_legacy_user_defined_kind_of_that_name_decodes_into_the_built_in_one() {
        // CLAUDE.md persisted-DB compatibility: a payload written before the kind was built in could hold it
        // in the account's own list. `periodKinds` keeps only user-defined names, so it collapses into the
        // built-in — and the tasks' overrides, which are keyed by the NAME, go on answering for it.
        val (s0, solo) = soloTask()
        val hand = s0.copy(periodKinds = listOf(PeriodKinds.BEFORE_BED))
        val withValue =
            SchedulerReducer.reduce(hand, SchedulerIntent.SetTaskResilience(solo, PeriodKinds.BEFORE_BED, 0.5))
        val decoded = SchedulerStateCodec.decode(SchedulerStateCodec.encode(withValue))
        assertNotNull(decoded)
        assertEquals(emptyList(), decoded.periodKinds)
        assertEquals(1, decoded.allPeriodKinds.count { it == PeriodKinds.BEFORE_BED })
        assertEquals(0.5, decoded.tasks[solo]!!.resilienceFor(PeriodKinds.BEFORE_BED))
    }

    // ----- where the period falls ----------------------------------------------------------------

    @Test
    fun the_period_is_the_hour_ending_at_each_bedtime() {
        // Default schedule: wake 07:30, 8h30 in bed -> bedtime 23:00, so the wind-down is [22:00, 23:00).
        val now = utc(2024, 1, 1, 10, 0)
        val to = now + 3L * 24 * HOUR_MS
        val windDowns = SchedulerDomain.beforeBedPanels(SchedulerDomain.DEFAULT_SLEEP, now, to, tz)
            .sortedBy { it.startEpochMillis }
        val nights = SchedulerDomain.sleepPanels(SchedulerDomain.DEFAULT_SLEEP, now, to, tz)
            .sortedBy { it.startEpochMillis }
        assertTrue(windDowns.isNotEmpty())
        assertEquals(utc(2024, 1, 1, 22, 0), windDowns.first().startEpochMillis)
        assertEquals(utc(2024, 1, 1, 23, 0), windDowns.first().endEpochMillis)
        // One per night, each ending exactly where that night begins.
        assertEquals(nights.map { it.startEpochMillis }, windDowns.map { it.endEpochMillis })
        windDowns.forEach {
            assertEquals(SchedulerDomain.BEFORE_BED_MILLIS, it.endEpochMillis - it.startEpochMillis)
            assertEquals(PeriodKinds.BEFORE_BED, it.restrictiveKind)
            assertTrue(it.isRestrictivePeriod && it.taskId == null)
        }
        assertTrue(SchedulerDomain.beforeBedPanels(null, now, to, tz).isEmpty())
    }

    @Test
    fun a_night_starting_just_past_the_window_still_contributes_its_hour() {
        // The hour lies BEFORE its window, so the query has to look one hour past its own right edge or the
        // wind-down of the very next night falls out of the answer.
        val now = utc(2024, 1, 1, 10, 0)
        val panels =
            SchedulerDomain.beforeBedPanels(SchedulerDomain.DEFAULT_SLEEP, now, utc(2024, 1, 1, 22, 30), tz)
        assertEquals(1, panels.size)
        assertEquals(utc(2024, 1, 1, 22, 0), panels.first().startEpochMillis)
    }

    @Test
    fun the_wind_down_drifts_with_the_wake_time_it_is_measured_back_from() {
        // Derived from [sleepPanels], never from a second reading of the schedule: the wake time drifts
        // toward its goal and the bedtime — and therefore the hour before it — drifts with it.
        val sleep = SchedulerDomain.DEFAULT_SLEEP.copy(goalWakeMinutes = 390, anchorEpochDay = 19723)
        val now = utc(2024, 1, 1, 10, 0)
        val to = now + 5L * 24 * HOUR_MS
        val nights = SchedulerDomain.sleepPanels(sleep, now, to, tz).sortedBy { it.startEpochMillis }
        val windDowns = SchedulerDomain.beforeBedPanels(sleep, now, to, tz).sortedBy { it.startEpochMillis }
        assertEquals(nights.map { it.startEpochMillis }, windDowns.map { it.endEpochMillis })
        assertTrue(nights.map { it.startEpochMillis }.distinct().size > 1, "the schedule did not drift")
    }

    // ----- what the fill does with it ------------------------------------------------------------

    @Test
    fun the_fill_lays_the_period_and_places_no_task_in_it() {
        val now = utc(2024, 1, 1, 10, 0)
        val panels = SchedulerDomain.fillSchedule(sleeping(), now, tz)
        val windDowns = panels.filter { it.restrictiveKind == PeriodKinds.BEFORE_BED }
        assertTrue(windDowns.isNotEmpty(), "the fill laid no wind-down period")
        val autos = panels.filter { it.auto }
        assertTrue(autos.isNotEmpty())
        windDowns.forEach { w ->
            assertEquals(SchedulerDomain.BEFORE_BED_PANEL_TITLE, w.title)
            assertTrue(
                autos.none { it.startEpochMillis < w.endEpochMillis && it.endEpochMillis > w.startEpochMillis },
                "a task was scheduled in the hour before bed",
            )
        }
    }

    @Test
    fun an_off_screen_task_resilient_to_the_kind_works_through_the_wind_down() {
        // The escape is the model's own, and the only one: a value above 0 against "before bed" — and, since
        // the hour is also a no-screen period, against "no screen" too (an OFF-screen task). There is no
        // second switch anywhere that says so.
        val (base, solo) = soloTask()
        val resilient = SchedulerReducer.reduce(base, SchedulerIntent.SetTaskResilience(solo, PeriodKinds.BEFORE_BED, 1.0))
        val state = SchedulerReducer
            .reduce(resilient, SchedulerIntent.SetTaskResilience(solo, PeriodKinds.NO_SCREEN, 1.0))
            .copy(sleep = SchedulerDomain.DEFAULT_SLEEP)
        val now = utc(2024, 1, 1, 10, 0)
        val panels = SchedulerDomain.fillSchedule(state, now, tz)
        val firstWindDown = panels.filter { it.restrictiveKind == PeriodKinds.BEFORE_BED }
            .minByOrNull { it.startEpochMillis }
        assertNotNull(firstWindDown)
        assertTrue(
            panels.any {
                it.auto && it.startEpochMillis < firstWindDown.endEpochMillis &&
                    it.endEpochMillis > firstWindDown.startEpochMillis
            },
            "a task resilient to \"before bed\" was still kept out of the wind-down hour",
        )
    }

    @Test
    fun an_on_screen_task_resilient_to_before_bed_is_still_kept_out_by_its_no_screen_period() {
        val (base, solo) = soloTask()
        val state = SchedulerReducer
            .reduce(base, SchedulerIntent.SetTaskResilience(solo, PeriodKinds.BEFORE_BED, 1.0))
            .copy(sleep = SchedulerDomain.DEFAULT_SLEEP)
        assertTrue(state.tasks[solo]!!.onScreen)
        val now = utc(2024, 1, 1, 10, 0)
        val panels = SchedulerDomain.fillSchedule(state, now, tz)
        val windDowns = panels.filter { it.restrictiveKind == PeriodKinds.BEFORE_BED }
        assertTrue(windDowns.isNotEmpty())
        windDowns.forEach { w ->
            assertTrue(
                panels.none {
                    it.auto && it.startEpochMillis < w.endEpochMillis && it.endEpochMillis > w.startEpochMillis
                },
                "an on-screen task was placed in the no-screen period the wind-down hour carries",
            )
        }
    }

    @Test
    fun the_period_is_derived_so_it_never_accumulates_and_never_reaches_the_wire() {
        val now = utc(2024, 1, 1, 10, 0)
        val once = SchedulerDomain.fillSchedule(sleeping(), now, tz)
        val windDowns = once.filter { it.restrictiveKind == PeriodKinds.BEFORE_BED }
        assertTrue(windDowns.isNotEmpty())
        // Regenerated, not kept: a second fill over the first's output holds the same ones, not twice as many.
        val twice = SchedulerDomain.fillSchedule(sleeping().copy(panels = once), now, tz)
        assertEquals(
            windDowns.map { it.id }.sorted(),
            twice.filter { it.restrictiveKind == PeriodKinds.BEFORE_BED }.map { it.id }.sorted(),
        )
        // ...and derived means it is stripped from the sync payload and out of the scheduling signature.
        windDowns.forEach { assertTrue(SchedulerDomain.isRegeneratedPanel(it)) }
        val state = sleeping()
        assertEquals(
            SchedulerDomain.schedulingSignature(state),
            SchedulerDomain.schedulingSignature(state.copy(panels = once)),
        )
    }

    @Test
    fun the_wind_down_cue_fires_where_the_period_starts() {
        // The engine reads the wind-down instants off the periods the fill laid, so the notification and the
        // band on the calendar can never say two different things.
        val now = utc(2024, 1, 1, 10, 0)
        val panels = SchedulerDomain.fillSchedule(sleeping(), now, tz)
        val instants =
            panels.filter { it.restrictiveKind == PeriodKinds.BEFORE_BED }.map { it.startEpochMillis }
        val nights = panels.filter { it.sleep }.map { it.startEpochMillis }
        assertTrue(instants.isNotEmpty())
        assertEquals(nights.map { it - SchedulerDomain.BEFORE_BED_MILLIS }.sorted(), instants.sorted())
    }
}
