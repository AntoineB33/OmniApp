package org.example.project

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.example.project.scheduler.domain.PeriodKinds
import org.example.project.scheduler.domain.QuotaDomain
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
    fun a_loop_with_two_times_more_quota_doubles_the_amount_not_the_pace() {
        val quota = weekly(loops = listOf(QuotaLoop(index = 1, amountFactor = 2.0)))
        val second = QuotaDomain.progressAt(quota, emptyList(), T0 + WEEK + WEEK / 2)
        assertNear(0.5, second.fraction, "the same pace")
        assertNear(80.0, second.amount, "two times more quota in that loop")
        assertNear(40.0, second.targetAmount, "half of it due")
        assertNear(40.0, QuotaDomain.progressAt(quota, emptyList(), T0 + WEEK / 2).amount, "the other loops are untouched")
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
                    QuotaLoop(index = 2, renewals = 0, amountFactor = -3.0),
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
        val fine = weekly(loops = listOf(QuotaLoop(1, amountFactor = 2.0)))
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
        val quota = weekly(resilience = mapOf(PeriodKinds.SLEEP to 0.0), loops = listOf(QuotaLoop(1, T0 + WEEK + DAY, T0 + 2 * WEEK, 2.0, 2)))
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
            SearchDomain.AddedAction.QuotaProgress, SearchDomain.AddedAction.QuotaAmount, SearchDomain.AddedAction.QuotaLoop,
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
            SchedulerIntent.SetNewQuotaDefaults(weekly(resilience = mapOf(PeriodKinds.SLEEP to 0.0), loops = listOf(QuotaLoop(1, amountFactor = 2.0)))),
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
                SearchDomain.AddedAction.QuotaLoop, SearchDomain.AddedAction.QuotaResilience,
            ),
            quotaActions(listOf(creation)).toSet(),
            "the default's settings and the button that creates one from them — no Duplicate / Delete / progression / particular loops",
        )
        // A real quota added — alone or beside the row — has its whole section.
        assertTrue(SearchDomain.AddedAction.QuotaDelete in quotaActions(listOf(quotaRow)))
        assertTrue(SearchDomain.AddedAction.QuotaDelete in quotaActions(listOf(creation, quotaRow)))
    }
}
