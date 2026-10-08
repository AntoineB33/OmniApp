package org.example.project

import kotlin.math.abs
import kotlin.test.Test
import kotlinx.datetime.toInstant
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.example.project.scheduler.domain.PeriodKinds
import org.example.project.scheduler.domain.QuotaDomain
import org.example.project.scheduler.domain.SchedulerDomain
import org.example.project.scheduler.domain.SearchDomain
import org.example.project.scheduler.model.QuotaEntry
import org.example.project.scheduler.model.QuotaLoop
import org.example.project.scheduler.model.TaskPanel
import org.example.project.scheduler.persistence.SchedulerStateCodec
import org.example.project.scheduler.state.SchedulerIntent
import org.example.project.scheduler.state.SchedulerReducer
import org.example.project.scheduler.state.SchedulerState

/**
 * User rule 2026-10-03: **the quota element** — an amount to reach over a loop of time, whose **target progression**
 * is the share of the loop's time elapsed, weighted by the quota's resilience to the restrictive periods covering it.
 * A loop can have two times more quota, be renewed (the progression twice as fast, back to 0 % at 100 %), start and
 * end elsewhere, and the quota can not repeat.
 */
class QuotaTest {

    private val HOUR = 3_600_000L
    private val DAY = 24 * HOUR
    private val WEEK = 7 * DAY
    private val T0 = 1_700_000_000_000L

    private fun weekly(resilience: Map<String, Double> = emptyMap(), loops: List<QuotaLoop> = emptyList(), repeats: Boolean = true) =
        QuotaEntry(
            id = "quota-0", title = "Read", amount = 40.0, unit = "pages", startMillis = T0, endMillis = T0 + WEEK,
            repeats = repeats, resilience = resilience, loops = loops,
        )

    private fun period(kind: String, from: Long, to: Long) =
        TaskPanel(id = "period/$from", taskId = null, title = kind, startEpochMillis = from, endEpochMillis = to, periodKind = kind)

    private fun assertNear(expected: Double, actual: Double, what: String) =
        assertTrue(abs(expected - actual) < 1e-9, "$what: expected $expected but was $actual")

    @Test
    fun the_target_progression_is_the_share_of_the_loop_elapsed() {
        val quota = weekly()
        assertNear(0.0, QuotaDomain.progressAt(quota, emptyList(), T0).fraction, "at the start")
        val middle = QuotaDomain.progressAt(quota, emptyList(), T0 + WEEK / 2)
        assertNear(0.5, middle.fraction, "half the week")
        assertNear(20.0, middle.targetAmount, "20 of the 40 pages are due")
        assertNear(40.0, middle.amount, "of the whole amount")
        assertNear(0.25, QuotaDomain.progressAt(quota, emptyList(), T0 + WEEK / 4).fraction, "a quarter")
    }

    @Test
    fun the_loop_repeats_by_its_length_and_a_quota_that_does_not_repeat_stays_at_100_percent() {
        val third = QuotaDomain.progressAt(weekly(), emptyList(), T0 + 2 * WEEK + DAY)
        assertEquals(2, third.loop.index, "the third loop")
        assertEquals(T0 + 2 * WEEK, third.loop.startMillis)
        assertNear(1.0 / 7.0, third.fraction, "a day into it")

        val once = weekly(repeats = false)
        val after = QuotaDomain.progressAt(once, emptyList(), T0 + 3 * WEEK)
        assertEquals(0, after.loop.index)
        assertNear(1.0, after.fraction, "reached, and it stays there")
        assertNear(0.0, QuotaDomain.progressAt(once, emptyList(), T0 - DAY).fraction, "not started yet")
    }

    @Test
    fun the_progression_stands_still_in_a_period_the_quota_has_no_resilience_to() {
        // The first half of the week is a "sleep" period the quota is at 0 % to: nothing is due until it ends, and
        // the whole amount is spread over the second half.
        val quota = weekly(resilience = mapOf(PeriodKinds.SLEEP to 0.0))
        val periods = listOf(period(PeriodKinds.SLEEP, T0, T0 + WEEK / 2))
        assertNear(0.0, QuotaDomain.progressAt(quota, periods, T0 + WEEK / 4).fraction, "inside the period")
        assertNear(0.0, QuotaDomain.progressAt(quota, periods, T0 + WEEK / 2).fraction, "as it ends")
        assertNear(0.5, QuotaDomain.progressAt(quota, periods, T0 + 3 * WEEK / 4).fraction, "half of what is left")

        // At 50 % resilience the period counts for half its length: (½·¼) / (½·½ + ½) = 1/6 a quarter in.
        val half = weekly(resilience = mapOf(PeriodKinds.SLEEP to 0.5))
        assertNear(1.0 / 6.0, QuotaDomain.progressAt(half, periods, T0 + WEEK / 4).fraction, "half pace inside the period")

        // A period the quota is fully resilient to changes nothing.
        val full = weekly(resilience = mapOf(PeriodKinds.SLEEP to 1.0))
        assertNear(0.25, QuotaDomain.progressAt(full, periods, T0 + WEEK / 4).fraction, "full resilience")
    }

    @Test
    fun a_renewed_loop_moves_twice_as_fast_and_comes_back_to_zero_in_its_middle() {
        val quota = weekly(loops = listOf(QuotaLoop(index = 0, renewals = 2)))
        val quarter = QuotaDomain.progressAt(quota, emptyList(), T0 + WEEK / 4)
        assertNear(0.5, quarter.fraction, "twice as fast")
        assertEquals(1, quarter.renewal)
        val middle = QuotaDomain.progressAt(quota, emptyList(), T0 + WEEK / 2)
        assertNear(0.0, middle.fraction, "back to 0 % on reaching 100 % in the middle")
        assertEquals(2, middle.renewal)
        assertNear(0.5, QuotaDomain.progressAt(quota, emptyList(), T0 + 3 * WEEK / 4).fraction, "the second renewal")
        val end = QuotaDomain.progressAt(quota.copy(repeats = false), emptyList(), T0 + WEEK)
        assertNear(1.0, end.fraction, "the end of the loop is 100 % of its last renewal")
        assertEquals(2, end.renewal)
    }

    @Test
    fun a_particular_loop_can_start_and_end_elsewhere() {
        // The second loop runs two days late and lasts three days.
        val own = QuotaLoop(index = 1, startMillis = T0 + WEEK + 2 * DAY, endMillis = T0 + WEEK + 5 * DAY)
        val quota = weekly(loops = listOf(own))
        val inside = QuotaDomain.progressAt(quota, emptyList(), T0 + WEEK + 3 * DAY)
        assertEquals(1, inside.loop.index)
        assertNear(1.0 / 3.0, inside.fraction, "a day into its three")
        // Before its own start the second loop has not begun: 0 %.
        assertNear(0.0, QuotaDomain.progressAt(quota, emptyList(), T0 + WEEK + DAY).fraction, "before its own start")
        // The third loop is the regular one.
        assertEquals(T0 + 2 * WEEK, QuotaDomain.loopAt(quota, T0 + 2 * WEEK + HOUR).startMillis)
    }

    @Test
    fun a_quota_is_healed_into_the_one_shape_it_may_have() {
        val bad =
            QuotaEntry(
                id = "quota-0", amount = Double.NaN, startMillis = T0, endMillis = T0 - 1,
                resilience = mapOf("x" to 7.0),
                loops = listOf(
                    QuotaLoop(index = -1),
                    QuotaLoop(index = 2, renewals = 0),
                    QuotaLoop(index = 2, renewals = 5),
                    QuotaLoop(index = 3, startMillis = T0 + 10, endMillis = T0),
                    QuotaLoop(index = 4),
                ),
            )
        val healed = QuotaDomain.healed(bad)
        assertEquals(0.0, healed.amount)
        assertEquals(T0 + QuotaDomain.DEFAULT_LOOP_MILLIS, healed.endMillis, "a loop that does not end after it starts is a week")
        assertEquals(1.0, healed.resilience["x"])
        // One loop per index; a factor and renewals healed to 1 and bounds dropped leave nothing particular.
        assertEquals(emptyList(), healed.loops)
        val fine = weekly(loops = listOf(QuotaLoop(1, renewals = 2)))
        assertTrue(QuotaDomain.healed(fine) === fine, "a quota already in shape is the same instance")
    }

    // ----- the state: a list like the chronos' ---------------------------------------------------------

    @Test
    fun setting_the_quotas_is_one_unit_and_undo_takes_it_back() {
        var s = SchedulerState.empty()
        s = SchedulerReducer.reduce(s, SchedulerIntent.SetQuotas(listOf(weekly().copy(id = ""))))
        val quota = s.quotas.single()
        assertEquals("quota-0", quota.id, "a blank id is minted")
        assertTrue(SchedulerReducer.reduce(s, SchedulerIntent.SetQuotas(s.quotas)) === s, "nothing changed, no unit")
        assertEquals(emptyList(), SchedulerReducer.reduce(s, SchedulerIntent.Undo).quotas)
    }

    @Test
    fun quotas_survive_a_round_trip_and_a_payload_written_before_them_still_loads() {
        var s = SchedulerState.empty()
        val quota = weekly(resilience = mapOf(PeriodKinds.SLEEP to 0.0), loops = listOf(QuotaLoop(1, T0 + WEEK + DAY, T0 + 2 * WEEK, 2)))
        s = SchedulerReducer.reduce(s, SchedulerIntent.SetQuotas(listOf(quota)))
        val decoded = assertNotNull(SchedulerStateCodec.decodeSnapshot(SchedulerStateCodec.encodeSnapshot(s)))
        assertEquals(listOf(quota), decoded.quotas)
        // The history unit that made it round-trips too: undo after a reload takes the quota away.
        assertEquals(emptyList(), SchedulerReducer.reduce(decoded, SchedulerIntent.Undo).quotas)

        // The previous shape: a payload with no quota list at all — which is exactly what a state holding none writes
        // (a field equal to its default is not encoded) — loads with none.
        val previous = SchedulerStateCodec.encode(SchedulerState.empty())
        assertTrue("\"quotas\"" !in previous, "the older shape is what is being loaded")
        assertEquals(emptyList(), assertNotNull(SchedulerStateCodec.decode(previous)).quotas)
    }

    // ----- the Search window --------------------------------------------------------------------------

    @Test
    fun a_quota_is_a_search_result_with_its_own_actions() {
        val s = SchedulerReducer.reduce(SchedulerState.empty(), SchedulerIntent.SetQuotas(listOf(weekly())))
        val row = SearchDomain.results(s, setOf(SearchDomain.Kind.Quota), "").filterIsInstance<SearchDomain.ItemResult>().single()
        assertEquals("Read", row.name)
        assertTrue("40 pages" in row.detail && "repeats" in row.detail, row.detail)
        val actions = SearchDomain.addedActions("", setOf(SearchDomain.Kind.Quota)).single { it.first == SearchDomain.Kind.Quota }.second
        for (action in listOf(
            SearchDomain.AddedAction.QuotaProgress, SearchDomain.AddedAction.QuotaLookTimes,
            SearchDomain.AddedAction.QuotaAmount, SearchDomain.AddedAction.QuotaLoop,
            SearchDomain.AddedAction.QuotaResilience, SearchDomain.AddedAction.QuotaLoops, SearchDomain.AddedAction.QuotaTitle,
            SearchDomain.AddedAction.QuotaDelete,
        )) assertTrue(action in actions, "the actions lack $action")
        assertTrue(SearchDomain.Kind.Quota in SearchDomain.CREATABLE, "a quota is made from its creation row")
        // The bin stands with "New" and "Duplicate", ABOVE the editors that are one block per added quota (anomaly
        // 2026-10-04: at the end of the section it was under all of them, and was not found).
        assertTrue(
            actions.indexOf(SearchDomain.AddedAction.QuotaDelete) < actions.indexOf(SearchDomain.AddedAction.QuotaProgress),
            "Delete comes before the per-quota editors: $actions",
        )
    }

    // ----- the default configuration: the quota actions of an added "New quota" creation row ----------------

    @Test
    fun an_added_new_quota_row_is_acted_on_as_a_quota_and_edits_the_default_configuration() {
        val creation = SearchDomain.ItemResult(SearchDomain.Kind.Creation, SearchDomain.Kind.Quota.name, "New quota", "")
        assertEquals(SearchDomain.Kind.Quota, SearchDomain.actionKindOf(creation))
        assertTrue(SearchDomain.defaultConfigurationAdded(listOf(creation), SearchDomain.Kind.Quota))
        assertEquals(1, SearchDomain.reachOf(SearchDomain.Kind.Quota, listOf(creation)), "the quota actions reach it")
        // Another kind's creation row is not: it has no default edited through its actions.
        val chrono = SearchDomain.ItemResult(SearchDomain.Kind.Creation, SearchDomain.Kind.Chrono.name, "New chrono", "")
        assertEquals(SearchDomain.Kind.Creation, SearchDomain.actionKindOf(chrono))

        // The setting itself: settings only, no identity, no particular loop; not an Undo/Redo unit.
        var s = SchedulerState.empty()
        val before = s.histories
        s = SchedulerReducer.reduce(
            s,
            SchedulerIntent.SetNewQuotaDefaults(weekly(resilience = mapOf(PeriodKinds.SLEEP to 0.0), loops = listOf(QuotaLoop(1, renewals = 2)))),
        )
        assertEquals("", s.newQuotaDefaults.id)
        assertEquals(emptyList(), s.newQuotaDefaults.loops)
        assertEquals(40.0, s.newQuotaDefaults.amount)
        assertEquals(before, s.histories, "a default configuration is a setting, not a history unit")
    }

    @Test
    fun a_new_quota_starts_from_the_default_over_the_loop_being_lived() {
        val monday = T0 + 3 * DAY
        // Untouched default: the week the quota is made in.
        val plain = org.example.project.scheduler.domain.NewElementDefaults.newQuota(
            org.example.project.scheduler.domain.NewElementDefaults.QUOTA, "quota-0", T0 + 10 * WEEK, monday, monday + WEEK,
        )
        assertEquals(monday to monday + WEEK, plain.startMillis to plain.endMillis)
        assertEquals("Quota", plain.title)

        // A default "every 3 days from T0, 12 pages, standing still at night": the new quota's first loop is the
        // default's loop that now falls in, and it carries the amount, the unit and the resilience.
        val defaults = QuotaEntry(id = "", title = "Read", amount = 12.0, unit = "pages", startMillis = T0, endMillis = T0 + 3 * DAY, resilience = mapOf(PeriodKinds.SLEEP to 0.0))
        val made = org.example.project.scheduler.domain.NewElementDefaults.newQuota(defaults, "quota-1", T0 + 7 * DAY + HOUR, monday, monday + WEEK)
        assertEquals("quota-1", made.id)
        assertEquals(T0 + 6 * DAY to T0 + 9 * DAY, made.startMillis to made.endMillis, "the third loop of the default")
        assertEquals(12.0, made.amount)
        assertEquals(mapOf(PeriodKinds.SLEEP to 0.0), made.resilience)

        // A default that does not repeat keeps its own dates.
        val once = org.example.project.scheduler.domain.NewElementDefaults.newQuota(defaults.copy(repeats = false), "quota-2", T0 + 30 * DAY, monday, monday + WEEK)
        assertEquals(T0 to T0 + 3 * DAY, once.startMillis to once.endMillis)
    }

    @Test
    fun the_default_configuration_survives_a_round_trip_and_an_older_payload_has_the_built_in_one() {
        var s = SchedulerState.empty()
        val untouched = SchedulerStateCodec.encode(s)
        assertTrue("newQuotaDefaults" !in untouched, "an untouched default writes no field — the older shape")
        assertEquals(org.example.project.scheduler.domain.NewElementDefaults.QUOTA, assertNotNull(SchedulerStateCodec.decode(untouched)).newQuotaDefaults)

        s = SchedulerReducer.reduce(s, SchedulerIntent.SetNewQuotaDefaults(weekly(resilience = mapOf(PeriodKinds.SLEEP to 0.5))))
        val decoded = assertNotNull(SchedulerStateCodec.decodeSnapshot(SchedulerStateCodec.encodeSnapshot(s)))
        assertEquals(s.newQuotaDefaults, decoded.newQuotaDefaults)
    }

    /** Anomaly 2026-10-03: with only the "New quota" row added, the Title action had nothing to edit. */
    @Test
    fun the_title_action_edits_the_default_configurations_title() {
        val creation = SearchDomain.ItemResult(SearchDomain.Kind.Creation, SearchDomain.Kind.Quota.name, "New quota", "")
        var s = SchedulerReducer.reduce(SchedulerState.empty(), SchedulerIntent.SetQuotas(listOf(weekly())))
        val quotaRow = SearchDomain.results(s, setOf(SearchDomain.Kind.Quota), "").single()

        val titles = SearchDomain.addedTitles(s, listOf(creation), SearchDomain.Kind.Quota)
        assertEquals(mapOf(SearchDomain.DEFAULT_CONFIGURATION_ID to "Quota"), titles, "the default's title is there to edit")

        val command = SearchDomain.AddedCommand.Titles(SearchDomain.Kind.Quota, titles.keys.associateWith { "Reading" }, "k")
        s = SearchDomain.addedIntents(s, listOf(creation), command, T0).fold(s) { acc, intent -> SchedulerReducer.reduce(acc, intent) }
        assertEquals("Reading", s.newQuotaDefaults.title)
        assertEquals("Read", s.quotas.single().title, "the account's quotas are untouched")
        // …and a new quota is then named by it.
        assertEquals("Reading", org.example.project.scheduler.domain.NewElementDefaults.newQuota(s.newQuotaDefaults, "quota-9", T0, T0, T0 + WEEK).title)

        // With a real quota added beside the row, one field names both.
        val both = SearchDomain.addedTitles(s, listOf(creation, quotaRow), SearchDomain.Kind.Quota)
        assertEquals(setOf(SearchDomain.DEFAULT_CONFIGURATION_ID, "quota-0"), both.keys)
    }

    // ---- User rule 2026-10-04: the period's start, its end as a length or a date, and how many times it repeats ----

    @Test
    fun a_moved_start_carries_a_length_along_and_leaves_a_dated_end_where_it_is() {
        val byLength = weekly()
        assertTrue(byLength.endByDelta, "the default: the end is a length after the start")
        val moved = QuotaDomain.withStart(byLength, T0 + DAY)
        assertEquals(T0 + DAY to T0 + DAY + WEEK, moved.startMillis to moved.endMillis, "7 days after the start, still")

        val dated = weekly().copy(endByDelta = false)
        val later = QuotaDomain.withStart(dated, T0 + DAY)
        assertEquals(T0 + DAY to T0 + WEEK, later.startMillis to later.endMillis, "the end has a date of its own")
        assertEquals(dated, QuotaDomain.withStart(dated, T0 + WEEK), "a start at its end is refused")

        // A default configuration that says no period yet: its start dates it, a week long.
        val unset = QuotaEntry(id = "", startMillis = 0L, endMillis = 0L)
        assertEquals(WEEK, QuotaDomain.periodLengthMillis(unset), "7 days after the start by default")
        assertEquals(T0 + WEEK, QuotaDomain.withStart(unset, T0).endMillis)

        assertEquals(T0 + 3 * DAY, QuotaDomain.withLength(byLength, 3 * DAY).endMillis)
        assertEquals(byLength, QuotaDomain.withLength(byLength, 0L), "a length of nothing is refused")
        assertEquals(T0 + 2 * DAY, QuotaDomain.withEnd(dated, T0 + 2 * DAY).endMillis)
        assertEquals(dated, QuotaDomain.withEnd(dated, T0), "an end not after the start is refused")
    }

    @Test
    fun it_repeats_without_end_by_default_and_a_count_stops_it_on_its_last_period() {
        assertEquals(null, weekly().repeatCount, "without end by default")
        assertEquals(Int.MAX_VALUE, QuotaDomain.lastLoopIndex(weekly()))

        val twice = QuotaDomain.withRepeatCount(weekly(), 2) // three periods in all
        assertEquals(2, QuotaDomain.lastLoopIndex(twice))
        assertEquals(2, QuotaDomain.loopAt(twice, T0 + 2 * WEEK + DAY).index)
        val past = QuotaDomain.progressAt(twice, emptyList(), T0 + 9 * WEEK)
        assertEquals(2, past.loop.index, "past its last period it stays the last one")
        assertNear(1.0, past.fraction, "at 100 %")

        val none = QuotaDomain.withRepeatCount(weekly(), 0)
        assertFalse(none.repeats, "0 times is a quota that does not repeat")
        assertEquals(null, none.repeatCount)
        val again = QuotaDomain.withRepeatCount(none, null)
        assertTrue(again.repeats && again.repeatCount == null, "blank: without end")

        // What is particular to a period past the last one is dropped with it.
        val trimmed = QuotaDomain.healed(weekly(loops = listOf(QuotaLoop(1, renewals = 2), QuotaLoop(5, renewals = 3))).copy(repeatCount = 2))
        assertEquals(listOf(1), trimmed.loops.map { it.index })
        // A stored count of nothing heals into "does not repeat".
        assertFalse(QuotaDomain.healed(weekly().copy(repeatCount = 0)).repeats)
    }

    @Test
    fun a_quota_can_be_given_a_value_for_the_20s_break_which_a_task_cannot() {
        // Anomaly 2026-10-04: the 20 s break was not among the periods a quota could be given a resilience for. It
        // "allows no task" — a rule about tasks; a quota's progression through it is the user's to say.
        val kind = PeriodKinds.BREAK_20S
        val untouched = weekly()
        assertNear(0.0, QuotaDomain.resilienceFor(untouched, kind), "untouched, the progression stands still there, as before")
        val through = weekly(resilience = mapOf(kind to 1.0, PeriodKinds.INACTIVITY to 0.5))
        assertNear(1.0, QuotaDomain.resilienceFor(through, kind), "the value the quota was given")
        assertNear(0.5, QuotaDomain.resilienceFor(through, PeriodKinds.INACTIVITY), "inactivity too")
        assertNear(0.0, PeriodKinds.resilienceFor(through.resilience, kind), "a task still has none, whatever a map holds")

        // And the progression reads it: a day of the week under a 20 s-break period no longer stops the quota.
        val covered = listOf(period(kind, T0, T0 + DAY))
        assertNear(0.0, QuotaDomain.progressAt(untouched, covered, T0 + DAY).fraction, "standing still through it")
        assertNear(1.0 / 7.0, QuotaDomain.progressAt(through, covered, T0 + DAY).fraction, "moving through it at full rate")
        // The value survives a save.
        val s = SchedulerReducer.reduce(SchedulerState.empty(), SchedulerIntent.SetQuotas(listOf(through)))
        assertEquals(1.0, assertNotNull(SchedulerStateCodec.decode(SchedulerStateCodec.encode(s))).quotas.single().resilience[kind])
    }

    // ---- Anomaly 2026-10-04: "claude limit" read 64.5 % where 43 % of its waking time had gone -----------------

    @Test
    fun the_nights_already_slept_weigh_on_the_pace_as_much_as_the_nights_to_come() {
        // The panels hold the sleep windows only as the last fill laid them — from the now-line on. Read off them,
        // the loop's past had no night in it and ran at full rate while the future had its nights taken out.
        val tz = kotlinx.datetime.TimeZone.UTC
        val base = SchedulerState.empty().copy(sleep = SchedulerDomain.DEFAULT_SLEEP) // 8 h 30 a night, every night
        val quota = weekly() // default resilience: 0 to sleep and to the hour before bed
        val now = T0 + 5 * DAY
        // The state as the app holds it five days in: the plan — and its nights — laid from the line on.
        val planned = base.copy(panels = SchedulerDomain.fillSchedule(base, now, tz, horizonMillis = T0 + WEEK))
        assertTrue(planned.panels.any { it.sleep && it.startEpochMillis > now }, "the fixture holds the nights to come")
        assertTrue(planned.panels.none { it.sleep && it.endEpochMillis < now - DAY }, "and none of the nights gone")

        val loop = QuotaDomain.loopAt(quota, now)
        fun elapsed(periods: List<TaskPanel>) = QuotaDomain.progress(quota, QuotaDomain.profile(quota, loop, periods), now).elapsed
        val oneSided = elapsed(SchedulerDomain.restrictivePeriods(planned))
        val whole = elapsed(SchedulerDomain.quotaPeriods(planned, loop.startMillis, loop.endMillis, tz))
        // Every day holds the same night, so five days of seven are five sevenths of the waking time.
        assertTrue(abs(whole - 5.0 / 7.0) < 0.01, "five sevenths of the waking time have gone: $whole")
        assertTrue(oneSided > whole + 0.05, "the one-sided reading ran ahead: $oneSided against $whole")
        // What the calendar happens to hold does not move it: the same with no plan laid at all.
        assertNear(whole, elapsed(SchedulerDomain.quotaPeriods(base, loop.startMillis, loop.endMillis, tz)), "whatever the fill laid")
    }

    @Test
    fun the_breaks_the_scheduler_expects_ahead_do_not_weigh_on_the_pace_and_a_drawn_period_does() {
        val tz = kotlinx.datetime.TimeZone.UTC
        val base = SchedulerState.empty().copy(screenBreaks = SchedulerDomain.DEFAULT_SCREEN_BREAKS)
        val now = T0 + 3 * DAY
        val planned = base.copy(panels = SchedulerDomain.fillSchedule(base, now, tz, horizonMillis = T0 + WEEK))
        assertTrue(planned.panels.any { it.screenBreak }, "the fixture's plan holds breaks ahead of the line")
        val periods = SchedulerDomain.quotaPeriods(planned, T0, T0 + WEEK, tz)
        assertTrue(periods.none { it.screenBreak }, "a break the machine placed is on one side of the line only")
        // A period the user placed counts, wherever it is — behind the line too.
        val drawn = SchedulerReducer.reduce(planned, SchedulerIntent.AddRestrictivePeriod(PeriodKinds.NO_SCREEN, T0 + DAY, T0 + 2 * DAY))
        val withDrawn = SchedulerDomain.quotaPeriods(drawn, T0, T0 + WEEK, tz)
        assertEquals(listOf(T0 + DAY to T0 + 2 * DAY), withDrawn.filter { it.restrictiveKind == PeriodKinds.NO_SCREEN }.map { it.startEpochMillis to it.endEpochMillis })
    }

    // ---- User rule 2026-10-04: the quota's restarts, and what is particular to one loop ---------------------------

    @Test
    fun the_quotas_restarts_make_it_reach_100_percent_where_it_would_have_reached_50() {
        val twice = weekly().copy(renewals = 2)
        assertNear(0.5, QuotaDomain.progressAt(twice, emptyList(), T0 + WEEK / 4).fraction, "twice as fast")
        val middle = QuotaDomain.progressAt(twice, emptyList(), T0 + WEEK / 2)
        assertNear(0.0, middle.fraction, "100 % reached at the middle, and restarted at 0 %")
        assertEquals(2, middle.renewal)
        // Every period of it, not only the first.
        assertNear(0.5, QuotaDomain.progressAt(twice, emptyList(), T0 + WEEK + WEEK / 4).fraction, "the next period too")
        // A particular loop says its own number, and one that says none takes the quota's.
        val mixed = twice.copy(loops = listOf(QuotaLoop(1, renewals = 1), QuotaLoop(2, endMillis = T0 + 2 * WEEK + DAY)))
        assertEquals(1, QuotaDomain.loop(mixed, 1).renewals, "its own, even when that is 1")
        assertEquals(2, QuotaDomain.loop(mixed, 2).renewals, "none of its own: the quota's")
        assertEquals(mixed, QuotaDomain.healed(mixed), "an explicit 1 is particular while the quota says 2")
        assertEquals(1, QuotaDomain.healed(weekly().copy(renewals = 0)).renewals, "a number that is none heals to 1")
    }

    @Test
    fun a_loop_ending_inside_its_period_holds_100_percent_until_the_next_loop_starts() {
        val end = T0 + WEEK + 4 * DAY // the second period ends three days early
        assertTrue(QuotaDomain.endInsidePeriod(weekly(), 1, end))
        val quota = QuotaDomain.withParticular(weekly(), 1) { it.copy(endMillis = end) }
        assertEquals(listOf(QuotaLoop(1, endMillis = end)), quota.loops)
        assertNear(0.5, QuotaDomain.progressAt(quota, emptyList(), T0 + WEEK + 2 * DAY).fraction, "half of its four days")
        val after = QuotaDomain.progressAt(quota, emptyList(), T0 + WEEK + 5 * DAY)
        assertEquals(1, after.loop.index, "still that loop")
        assertNear(1.0, after.fraction, "at 100 % from its end…")
        assertNear(1.0, QuotaDomain.progressAt(quota, emptyList(), T0 + 2 * WEEK - 1).fraction, "…until the next loop starts")
        assertNear(0.0, QuotaDomain.progressAt(quota, emptyList(), T0 + 2 * WEEK).fraction, "which starts where it always did")

        // The ending time has to be inside the loop: before its start, at it, or past its regular end is refused.
        for (bad in listOf(T0 + WEEK, T0 + WEEK - HOUR, T0 + 2 * WEEK + HOUR)) {
            assertFalse(QuotaDomain.endInsidePeriod(weekly(), 1, bad))
            assertEquals(emptyList(), QuotaDomain.withParticular(weekly(), 1) { it.copy(endMillis = bad) }.loops, "refused: nothing particular is kept")
        }
        // Its regular end is no end of its own.
        assertTrue(QuotaDomain.endInsidePeriod(weekly(), 1, T0 + 2 * WEEK))
    }

    @Test
    fun the_restarts_are_stored_and_a_loop_an_older_build_wrote_reads_as_it_meant() {
        val quota = weekly(loops = listOf(QuotaLoop(1, renewals = 1), QuotaLoop(2, renewals = 3), QuotaLoop(3, endMillis = T0 + 3 * WEEK + DAY))).copy(renewals = 2)
        val s = SchedulerReducer.reduce(SchedulerState.empty(), SchedulerIntent.SetQuotas(listOf(quota)))
        val encoded = SchedulerStateCodec.encode(s)
        val decoded = assertNotNull(SchedulerStateCodec.decode(encoded)).quotas.single()
        assertEquals(2, decoded.renewals)
        assertEquals(listOf(1, 3, null), decoded.loops.map { it.renewals })

        // An older payload: no number on the quota, and on a loop only `renewals` — 1 there was "nothing particular".
        val older = encoded.replace(Regex(""","ownRenewals":\d+"""), "").replace(""","renewals":2}""", "}")
        assertTrue("ownRenewals" !in older && older != encoded)
        val read = assertNotNull(SchedulerStateCodec.decode(older)).quotas.single()
        assertEquals(1, read.renewals, "the percentage ran once over a period")
        assertEquals(listOf(3, null), read.loops.map { it.renewals }, "3 was particular; 1 was not, so that loop is no longer listed")

        // An amount factor an older build stored ("two times more quota", dropped 2026-10-04) is ignored: a loop that
        // held nothing else is no longer particular, and the amount is the quota's own in every loop.
        val factored = encoded.replace("\"loops\":[", "\"loops\":[{\"index\":7,\"amountFactor\":2.0},")
        assertTrue(factored != encoded)
        val plain = assertNotNull(SchedulerStateCodec.decode(factored)).quotas.single()
        assertTrue(plain.loops.none { it.index == 7 })
        assertNear(40.0, QuotaDomain.progressAt(plain, emptyList(), T0 + 7 * WEEK + DAY).amount, "the quota's own amount")
    }

    @Test
    fun a_weekday_is_the_latest_such_day_and_a_length_reads_as_it_is_typed() {
        val friday = kotlinx.datetime.LocalDate(2026, 10, 2)
        assertEquals(kotlinx.datetime.DayOfWeek.FRIDAY, friday.dayOfWeek)
        assertEquals(kotlinx.datetime.LocalDate(2026, 9, 30), QuotaDomain.latestWeekday(kotlinx.datetime.DayOfWeek.WEDNESDAY, friday))
        assertEquals(friday, QuotaDomain.latestWeekday(kotlinx.datetime.DayOfWeek.FRIDAY, friday), "today itself")
        assertEquals(kotlinx.datetime.LocalDate(2026, 9, 26), QuotaDomain.latestWeekday(kotlinx.datetime.DayOfWeek.SATURDAY, friday))

        assertEquals("7d", QuotaDomain.formatLength(WEEK))
        assertEquals("1d 12h", QuotaDomain.formatLength(DAY + 12 * HOUR))
        assertEquals("2h 30min", QuotaDomain.formatLength(2 * HOUR + 30 * 60_000L))
        assertEquals(WEEK, QuotaDomain.parseLength("7d"))
        assertEquals(WEEK, QuotaDomain.parseLength("7"), "a bare number is days")
        assertEquals(DAY + 12 * HOUR, QuotaDomain.parseLength("1d 12h"))
        assertEquals(90 * 60_000L, QuotaDomain.parseLength("90 min"))
        for (bad in listOf("", "0d", "soon", "7d 3d", "7x")) assertEquals(null, QuotaDomain.parseLength(bad), "\"$bad\"")
        for (length in listOf(WEEK, DAY + 12 * HOUR, 45 * 60_000L)) assertEquals(length, QuotaDomain.parseLength(QuotaDomain.formatLength(length)))
        // User rule 2026-10-05: the field is a number and a unit's drop-down. A stored length reads in the largest unit
        // it is a whole number of; a number is written in the unit picked, to the minute.
        val units = QuotaDomain.LengthUnit.entries.associateBy { it.label }
        assertEquals(listOf("minutes", "hours", "days", "weeks"), QuotaDomain.LengthUnit.entries.map { it.label })
        assertEquals(units["weeks"], QuotaDomain.lengthUnitOf(WEEK))
        assertEquals(units["days"], QuotaDomain.lengthUnitOf(3 * DAY))
        assertEquals(units["hours"], QuotaDomain.lengthUnitOf(DAY + 12 * HOUR))
        assertEquals(units["minutes"], QuotaDomain.lengthUnitOf(90 * 60_000L))
        assertEquals("36", QuotaDomain.lengthIn(DAY + 12 * HOUR, units.getValue("hours")))
        assertEquals("1.5", QuotaDomain.lengthIn(DAY + 12 * HOUR, units.getValue("days")))
        assertEquals("1", QuotaDomain.lengthIn(WEEK, units.getValue("weeks")))
        assertEquals(90 * 60_000L, QuotaDomain.parseLengthIn("1.5", units.getValue("hours")))
        assertEquals(90 * 60_000L, QuotaDomain.parseLengthIn(" 1,5 ", units.getValue("hours")), "a comma is the point")
        assertEquals(2 * WEEK, QuotaDomain.parseLengthIn("2", units.getValue("weeks")))
        for (bad in listOf("", "0", "-1", "soon", "1d", "0.001")) assertEquals(null, QuotaDomain.parseLengthIn(bad, units.getValue("minutes")), "\"$bad\"")
        for (length in listOf(WEEK, DAY + 12 * HOUR, 45 * 60_000L)) {
            val unit = QuotaDomain.lengthUnitOf(length)
            assertEquals(length, QuotaDomain.parseLengthIn(QuotaDomain.lengthIn(length, unit), unit))
        }
    }

    @Test
    fun the_ends_statement_and_the_count_are_stored_and_an_older_quota_reads_as_before() {
        val quota = QuotaDomain.withRepeatCount(weekly().copy(endByDelta = false), 3)
        val s = SchedulerReducer.reduce(SchedulerState.empty(), SchedulerIntent.SetQuotas(listOf(quota)))
        val encoded = SchedulerStateCodec.encode(s)
        val decoded = assertNotNull(SchedulerStateCodec.decode(encoded)).quotas.single()
        assertEquals(false to 3, decoded.endByDelta to decoded.repeatCount)
        // A quota written before the two fields existed: a length after the start, repeating without end.
        val older = encoded.replace(",\"endByDelta\":false", "").replace(",\"repeatCount\":3", "")
        assertTrue(older != encoded && "endByDelta" !in older && "repeatCount" !in older)
        val read = assertNotNull(SchedulerStateCodec.decode(older)).quotas.single()
        assertEquals(true to null, read.endByDelta to read.repeatCount)
        assertTrue(read.repeats)
    }

    @Test
    fun a_new_quota_takes_the_defaults_way_of_ending_and_its_count() {
        // The default configuration of the default configuration: 7 days after the start, without end.
        val base = org.example.project.scheduler.domain.NewElementDefaults.QUOTA
        assertTrue(base.endByDelta && base.repeatCount == null && base.repeats)
        assertEquals(WEEK, QuotaDomain.periodLengthMillis(base))
        val custom = QuotaDomain.withRepeatCount(base.copy(endByDelta = false), 4)
        val made = org.example.project.scheduler.domain.NewElementDefaults.newQuota(custom, "quota-9", T0, T0, T0 + WEEK)
        assertEquals(false to 4, made.endByDelta to made.repeatCount)
    }

    /** User rule 2026-10-04: a creation row's actions lead to every element of the kind it makes. */
    @Test
    fun a_creation_row_offers_the_search_on_every_element_of_its_kind() {
        fun creation(kind: SearchDomain.Kind) = SearchDomain.ItemResult(SearchDomain.Kind.Creation, kind.name, "New " + kind.label, "")
        fun sections(added: List<SearchDomain.Result>) =
            SearchDomain.actionsFor(
                SearchDomain.addedActions("", SearchDomain.Kind.entries.toSet(), SearchDomain.actionKindsOf(added)),
                added,
            ).toMap()

        // A plain creation row ("New alarm") has the action, in the creation rows' own group.
        val alarm = sections(listOf(creation(SearchDomain.Kind.Alarm)))
        assertEquals(listOf(SearchDomain.AddedAction.CreationSearchAll), alarm[SearchDomain.Kind.Creation])
        // "New quota" is acted on as a quota (its default configuration) AND keeps the creation row's own action.
        val quota = sections(listOf(creation(SearchDomain.Kind.Quota)))
        assertEquals(listOf(SearchDomain.AddedAction.CreationSearchAll), quota[SearchDomain.Kind.Creation])
        assertTrue(SearchDomain.AddedAction.QuotaAmount in quota.getValue(SearchDomain.Kind.Quota))
        // An element that exists is no creation row: no such group for it.
        val s = SchedulerReducer.reduce(SchedulerState.empty(), SchedulerIntent.SetQuotas(listOf(weekly())))
        val quotaRow = SearchDomain.results(s, setOf(SearchDomain.Kind.Quota), "").single()
        assertTrue(SearchDomain.Kind.Creation !in sections(listOf(quotaRow)))

        // One button per kind among the added rows, in the drop-down's order, each once.
        assertEquals(
            listOf(SearchDomain.Kind.Alarm, SearchDomain.Kind.Quota),
            SearchDomain.creationKinds(listOf(creation(SearchDomain.Kind.Quota), quotaRow, creation(SearchDomain.Kind.Alarm), creation(SearchDomain.Kind.Quota))),
        )
        // What it opens: that kind alone, nothing filtered, nothing added — so every quota is listed.
        val config = SearchDomain.kindSearchConfig(SearchDomain.Kind.Quota)
        assertEquals(SearchDomain.Config(kinds = setOf(SearchDomain.Kind.Quota)), config)
        assertEquals(listOf("quota-0"), SearchDomain.results(s, config.kinds, config.query).map { (it as SearchDomain.ItemResult).id })
    }

    /** Anomaly 2026-10-04: an added "New quota" creation row showed Delete and Duplicate — actions of a quota that exists. */
    @Test
    fun a_creation_row_alone_shows_only_the_actions_that_edit_the_default_configuration() {
        val creation = SearchDomain.ItemResult(SearchDomain.Kind.Creation, SearchDomain.Kind.Quota.name, "New quota", "")
        val s = SchedulerReducer.reduce(SchedulerState.empty(), SchedulerIntent.SetQuotas(listOf(weekly())))
        val quotaRow = SearchDomain.results(s, setOf(SearchDomain.Kind.Quota), "").single()
        fun quotaActions(added: List<SearchDomain.Result>) =
            SearchDomain.actionsFor(
                SearchDomain.addedActions("", SearchDomain.Kind.entries.toSet(), SearchDomain.actionKindsOf(added)),
                added,
            ).single { it.first == SearchDomain.Kind.Quota }.second

        assertEquals(
            setOf(
                SearchDomain.AddedAction.QuotaNew, SearchDomain.AddedAction.QuotaTitle, SearchDomain.AddedAction.QuotaAmount,
                SearchDomain.AddedAction.QuotaLoop, SearchDomain.AddedAction.QuotaRestarts, SearchDomain.AddedAction.QuotaResilience,
            ),
            quotaActions(listOf(creation)).toSet(),
            "the default's settings and the button that creates one from them — no Duplicate / Delete / progression / particular loops",
        )
        // A real quota added — alone or beside the row — has its whole section.
        assertTrue(SearchDomain.AddedAction.QuotaDelete in quotaActions(listOf(quotaRow)))
        assertTrue(SearchDomain.AddedAction.QuotaDelete in quotaActions(listOf(creation, quotaRow)))
    }

    // ----- the other times a quota is looked at (user rule 2026-10-08) --------------------------------------------

    private fun look(dateEpochDay: Long? = null, daysFromToday: Int = 0, minuteOfDay: Int? = null) =
        org.example.project.scheduler.model.QuotaLookTime(dateEpochDay, daysFromToday, minuteOfDay)

    /**
     * "They can be defined in absolute date/time, or relative to today, or both (e.g. tomorrow at 10AM)": a day (a date,
     * or days from today) and a time (a time of day, or the time it is now), each on its own.
     */
    @Test
    fun a_look_time_is_a_day_and_a_time_each_absolute_or_relative_to_the_present() {
        val tz = kotlinx.datetime.TimeZone.UTC
        fun at(year: Int, month: Int, day: Int, hour: Int, minute: Int) =
            kotlinx.datetime.LocalDateTime(year, month, day, hour, minute).toInstant(tz).toEpochMilliseconds()
        val now = at(2026, 10, 8, 14, 30) + 42_000L
        val date = kotlinx.datetime.LocalDate(2026, 12, 24).toEpochDays()
        // Absolute: a date and a time.
        assertEquals(at(2026, 12, 24, 9, 0), QuotaDomain.lookInstant(look(dateEpochDay = date, minuteOfDay = 9 * 60), now, tz))
        // Relative to today: so many days from today, at the time it is now (to the minute).
        assertEquals(at(2026, 10, 10, 14, 30), QuotaDomain.lookInstant(look(daysFromToday = 2), now, tz))
        assertEquals(at(2026, 10, 7, 14, 30), QuotaDomain.lookInstant(look(daysFromToday = -1), now, tz))
        // Both: tomorrow at 10AM — and it is the day after once a day has passed.
        val tomorrowAtTen = look(daysFromToday = 1, minuteOfDay = 10 * 60)
        assertEquals(at(2026, 10, 9, 10, 0), QuotaDomain.lookInstant(tomorrowAtTen, now, tz))
        assertEquals(at(2026, 10, 10, 10, 0), QuotaDomain.lookInstant(tomorrowAtTen, now + 24 * HOUR, tz))
        // A date, at the time it is now.
        assertEquals(at(2026, 12, 24, 14, 30), QuotaDomain.lookInstant(look(dateEpochDay = date), now, tz))
        // Said the other way, the instant is the same.
        for (one in listOf(tomorrowAtTen, look(dateEpochDay = date, minuteOfDay = 540), look(daysFromToday = 2))) {
            val dayFlipped = QuotaDomain.withDayRelative(one, relative = one.dateEpochDay != null, now, tz)
            assertEquals(QuotaDomain.lookInstant(one, now, tz), QuotaDomain.lookInstant(dayFlipped, now, tz), "$one")
            assertTrue((dayFlipped.dateEpochDay == null) != (one.dateEpochDay == null))
        }
        assertEquals(look(daysFromToday = 1, minuteOfDay = 14 * 60 + 30), QuotaDomain.withTimeRelative(look(daysFromToday = 1), relative = false, now, tz))
        assertEquals(look(daysFromToday = 1), QuotaDomain.withTimeRelative(tomorrowAtTen, relative = true, now, tz))
        assertEquals(listOf("today", "tomorrow", "yesterday", "in 3 days", "2 days ago", "2026-12-24"),
            listOf(look(), look(daysFromToday = 1), look(daysFromToday = -1), look(daysFromToday = 3), look(daysFromToday = -2), look(dateEpochDay = date)).map { QuotaDomain.lookDayText(it) })
    }

    @Test
    fun the_progression_at_a_look_time_is_the_pace_line_read_at_that_instant() {
        val quota = weekly()
        val tz = kotlinx.datetime.TimeZone.UTC
        val now = quota.startMillis + (quota.endMillis - quota.startMillis) / 4
        val inTwoDays = QuotaDomain.lookInstant(org.example.project.scheduler.model.QuotaLookTime(daysFromToday = 2), now, tz)
        val then = QuotaDomain.progressAt(quota, emptyList(), inTwoDays)
        // The instant is to the minute: two days on, less the seconds of the minute it is now.
        assertTrue(now + 2 * DAY - inTwoDays in 0 until 60_000L, "two days from now, to the minute")
        assertNear((inTwoDays - quota.startMillis).toDouble() / WEEK, then.fraction, "a quarter of the week, and two days more")
        assertTrue(kotlin.math.abs(then.fraction - (0.25 + 2.0 / 7.0)) < 0.001, "" + then.fraction)
        // Past the end of the loop it is the next loop's line.
        val nextWeek = QuotaDomain.lookInstant(org.example.project.scheduler.model.QuotaLookTime(daysFromToday = 7), now, tz)
        assertEquals(1, QuotaDomain.loopAt(quota, nextWeek).index)
        assertTrue(kotlin.math.abs(QuotaDomain.progressAt(quota, emptyList(), nextWeek).fraction - 0.25) < 0.001, "the same place in the next loop")
    }

    @Test
    fun the_look_times_are_stored_healed_and_absent_from_a_payload_written_before_them() {
        val times = listOf(look(daysFromToday = 1, minuteOfDay = 600), look(dateEpochDay = 20_800L, minuteOfDay = 0), look(daysFromToday = -3))
        val s = SchedulerReducer.reduce(SchedulerState.empty(), SchedulerIntent.SetQuotas(listOf(weekly().copy(lookTimes = times))))
        assertEquals(times, assertNotNull(SchedulerStateCodec.decode(SchedulerStateCodec.encode(s))).quotas.single().lookTimes)
        assertEquals(times, assertNotNull(SchedulerStateCodec.decodeSnapshot(SchedulerStateCodec.encodeSnapshot(s))).quotas.single().lookTimes)
        // The shape before 2026-10-08: a quota with no such field (one equal to its default is not encoded) has none.
        val before = SchedulerReducer.reduce(SchedulerState.empty(), SchedulerIntent.SetQuotas(listOf(weekly())))
        val payload = SchedulerStateCodec.encode(before)
        assertTrue("lookTimes" !in payload, "an untouched quota writes nothing new")
        assertEquals(emptyList(), assertNotNull(SchedulerStateCodec.decode(payload)).quotas.single().lookTimes)
        // Healed: a time that is no time of day reads the time it is now, a day out of reach is no date, the list is capped.
        val bad = weekly().copy(lookTimes = listOf(look(minuteOfDay = 5000), look(dateEpochDay = 9_000_000L)) + List(40) { look(daysFromToday = it) })
        val healed = QuotaDomain.healed(bad)
        assertEquals(QuotaDomain.MAX_LOOK_TIMES, healed.lookTimes.size)
        assertEquals(listOf(look(), look()), healed.lookTimes.take(2))
        val fine = weekly().copy(lookTimes = times)
        assertTrue(QuotaDomain.healed(fine) === fine, "a quota already in shape is the same instance")
    }
}
