package org.example.project

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.datetime.TimeZone
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.example.project.scheduler.domain.PeriodDrawing
import org.example.project.scheduler.domain.PeriodKindConfig
import org.example.project.scheduler.domain.PeriodKindStyle
import org.example.project.scheduler.domain.PeriodKinds
import org.example.project.scheduler.domain.RuleProgram
import org.example.project.scheduler.domain.SchedulerDomain
import org.example.project.scheduler.engine.SpokenMessages
import org.example.project.scheduler.model.TaskPanel
import org.example.project.scheduler.persistence.SchedulerStateCodec
import org.example.project.scheduler.state.SchedulerIntent
import org.example.project.scheduler.state.SchedulerReducer
import org.example.project.scheduler.state.SchedulerState

/**
 * User rule 2026-10-15: *"Add configurations in the restrictive periods that allow the user to get a notification at x
 * time before the period. By default, the 'hour before bed' period sends a notification 20min before and 1min
 * before."* — the setting (`PeriodKindStyle.notices`), its write, its storage, and the instants the rule cursor arms.
 */
class PeriodNoticesTest {
    private val MIN = 60_000L
    private val T = 1_000_000_000_000L

    private fun period(id: String, kind: String, start: Long, end: Long, title: String = "") =
        TaskPanel(id, null, title, start, end, periodKind = kind)

    private fun withField(payload: String, name: String, value: String): String =
        JsonObject(Json.parseToJsonElement(payload).jsonObject + (name to Json.parseToJsonElement(value))).toString()

    @Test
    fun the_hour_before_bed_notifies_20_and_1_minute_ahead_by_default_and_no_other_kind_does() {
        val config = SchedulerState.empty().periodKindConfig
        assertEquals(listOf(20 * MIN, MIN), config.notices(PeriodKinds.BEFORE_BED))
        for (kind in PeriodKinds.BUILT_IN - PeriodKinds.BEFORE_BED) assertEquals(emptyList(), config.notices(kind), kind)
        assertEquals(emptyList(), config.notices("commute"), "a kind the account defines starts with none")
    }

    @Test
    fun each_period_is_announced_its_leads_ahead_once_per_stretch_of_its_kind() {
        val config =
            PeriodKindConfig(
                styles = mapOf("commute" to PeriodKindStyle(PeriodDrawing.Crosses, notices = listOf(5 * MIN))),
            )
        val panels =
            listOf(
                period("bb", PeriodKinds.BEFORE_BED, T + 60 * MIN, T + 120 * MIN, title = "No screen before bed"),
                // Two commutes end to end are ONE stretch: only its start is "before the period".
                period("c1", "commute", T + 10 * MIN, T + 20 * MIN),
                period("c2", "commute", T + 20 * MIN, T + 30 * MIN),
                period("c3", "commute", T + 200 * MIN, T + 210 * MIN),
                // A kind with no notice says nothing.
                period("ns", PeriodKinds.NO_SCREEN, T + 5 * MIN, T + 8 * MIN),
            )
        val notices = RuleProgram.noticesOf(panels, config)
        assertEquals(
            listOf(T + 5 * MIN to "commute", T + 40 * MIN to PeriodKinds.BEFORE_BED, T + 59 * MIN to PeriodKinds.BEFORE_BED, T + 195 * MIN to "commute"),
            notices.map { it.atMillis to it.kind },
        )
        val bed = notices.first { it.kind == PeriodKinds.BEFORE_BED }
        assertEquals("No screen before bed", bed.title)
        assertEquals(T + 60 * MIN, bed.periodStartMillis)
        assertEquals(20 * MIN, bed.leadMillis)

        // The cursor arms each, crosses each once, in order, and hands it to the cue sweep as a crossing.
        val cursor = RuleProgram.Cursor(RuleProgram.compile(panels, config), T)
        assertEquals(T + 5 * MIN, cursor.nextTriggerMillis())
        assertEquals(listOf(T + 5 * MIN), cursor.moveTo(T + 6 * MIN).notices.map { it.atMillis })
        val crossed = cursor.moveTo(T + 100 * MIN).notices
        assertEquals(listOf(T + 40 * MIN, T + 59 * MIN), crossed.map { it.atMillis })
        assertTrue(cursor.moveTo(T + 150 * MIN).notices.isEmpty(), "never twice")
        val cues =
            SchedulerDomain.cueCrossings(
                screenBreaks = emptyList(), breakEvents = emptyList(), mode = 1, windDownInstants = emptyList(),
                automaticSchedule = true, alreadyNotifiedPoseDues = emptyMap(), fromMillis = Long.MIN_VALUE, toMillis = T + 100 * MIN,
                periodNotices = crossed,
            )
        assertEquals(listOf(SchedulerDomain.CueKind.PeriodNotice, SchedulerDomain.CueKind.PeriodNotice), cues.map { it.kind })
        assertEquals(T + 60 * MIN, cues.first().endInstant, "the crossing carries the period's start")
        // Without the per-kind settings the program announces nothing (a caller that does not ask).
        assertTrue(RuleProgram.compile(panels).notices.isEmpty())
    }

    @Test
    fun what_it_says() {
        val tz = TimeZone.UTC
        // T is 2001-09-09 01:46:40 UTC: a period at T + 60 min starts at 02:46.
        assertEquals("Starts in 20 min, at 02:46", SchedulerDomain.periodNoticeMessage(20 * MIN, T + 60 * MIN, tz))
        assertEquals("No screen before bed in 20 minutes.", SpokenMessages.periodNotice("No screen before bed", 20 * MIN))
        assertEquals("No screen before bed in 1 minute.", SpokenMessages.periodNotice("No screen before bed", MIN))
        assertEquals("1 hour 30 minutes", SpokenMessages.spokenLength(90 * MIN))
        assertEquals("30 seconds", SpokenMessages.spokenLength(30_000L))
    }

    @Test
    fun the_leads_are_set_through_one_intent_and_undone_as_one_unit() {
        var s = SchedulerState.empty()
        s = SchedulerReducer.reduce(s, SchedulerIntent.SetPeriodNotices(PeriodKinds.BEFORE_BED, listOf(30 * MIN, 30 * MIN, 1_500L, 0L)))
        // Whole seconds, each once, nothing under a second, in the user's order.
        assertEquals(listOf(30 * MIN, 1_000L), s.periodKindConfig.notices(PeriodKinds.BEFORE_BED))
        // None is a value too, kept as an override.
        s = SchedulerReducer.reduce(s, SchedulerIntent.SetPeriodNotices(PeriodKinds.BEFORE_BED, emptyList()))
        assertEquals(emptyList(), s.periodKindConfig.notices(PeriodKinds.BEFORE_BED))
        s = SchedulerReducer.reduce(s, SchedulerIntent.Undo)
        assertEquals(listOf(30 * MIN, 1_000L), s.periodKindConfig.notices(PeriodKinds.BEFORE_BED))
        // Back to the default: no override left.
        s = SchedulerReducer.reduce(s, SchedulerIntent.SetPeriodNotices(PeriodKinds.BEFORE_BED, listOf(20 * MIN, MIN)))
        assertEquals(emptyMap(), s.periodKindStyles)
        // A kind the account does not hold is refused.
        val same = SchedulerReducer.reduce(s, SchedulerIntent.SetPeriodNotices("nowhere", listOf(MIN)))
        assertEquals(s.periodKindStyles, same.periodKindStyles)
    }

    @Test
    fun the_leads_survive_the_store_and_an_older_row_reads_as_the_kinds_default() {
        var s = SchedulerReducer.reduce(SchedulerState.empty(), SchedulerIntent.SetPeriodNotices(PeriodKinds.BEFORE_BED, listOf(45 * MIN)))
        s = SchedulerReducer.reduce(s, SchedulerIntent.SetPeriodNotices(PeriodKinds.SLEEP, emptyList()))
        val back = assertNotNull(SchedulerStateCodec.decode(SchedulerStateCodec.encode(s)))
        assertEquals(listOf(45 * MIN), back.periodKindConfig.notices(PeriodKinds.BEFORE_BED))
        assertEquals(s.periodKindStyles, back.periodKindStyles)

        // A row written before the notices existed (only a drawing chosen): the hour before bed keeps 20 and 1 minute.
        val payload = SchedulerStateCodec.encode(SchedulerState.empty())
        val old = withField(payload, "periodKindStyles", """[{"id":"before bed","drawing":"Crosses","folded":true}]""")
        val decoded = assertNotNull(SchedulerStateCodec.decode(old))
        assertEquals(PeriodDrawing.Crosses, decoded.periodKindConfig.drawing(PeriodKinds.BEFORE_BED))
        assertEquals(listOf(20 * MIN, MIN), decoded.periodKindConfig.notices(PeriodKinds.BEFORE_BED))
        // And a row an older build wrote with junk in it heals.
        val junk = withField(payload, "periodKindStyles", """[{"id":"before bed","drawing":"None","folded":true,"notices":[60000,60000,-5]}]""")
        assertEquals(listOf(MIN), assertNotNull(SchedulerStateCodec.decode(junk)).periodKindConfig.notices(PeriodKinds.BEFORE_BED))
    }
}
