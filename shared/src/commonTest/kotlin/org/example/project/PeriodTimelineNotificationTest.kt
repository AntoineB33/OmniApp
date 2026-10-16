package org.example.project

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.example.project.scheduler.domain.PeriodDrawing
import org.example.project.scheduler.domain.PeriodKindConfig
import org.example.project.scheduler.domain.PeriodKindStyle
import org.example.project.scheduler.domain.PeriodKinds
import org.example.project.scheduler.domain.RuleProgram
import org.example.project.scheduler.domain.SchedulerDomain
import org.example.project.scheduler.model.TaskId
import org.example.project.scheduler.model.TaskPanel
import org.example.project.scheduler.persistence.SchedulerStateCodec
import org.example.project.scheduler.state.SchedulerIntent
import org.example.project.scheduler.state.SchedulerReducer
import org.example.project.scheduler.state.SchedulerState

/**
 * User rule 2026-10-16: *"In the period configurations, add the action to tell if a notification must tell everything
 * there is in the timeline during this period and right after. By default, it is on for the notification that is
 * triggered by default at the start of a 5 and 15 minute screen break."* — `PeriodKindStyle.tellsTimeline`, its write,
 * its storage, and what the notification says.
 */
class PeriodTimelineNotificationTest {
    private val MIN = 60_000L
    private val tz = TimeZone.UTC
    private val at1610 = LocalDateTime(2026, 10, 16, 16, 10).toInstant(tz).toEpochMilliseconds()

    private fun task(id: String, title: String, start: Long, end: Long) =
        TaskPanel(id, TaskId("t-$id"), title, start, end, auto = true)

    private fun period(id: String, kind: String, start: Long, end: Long, title: String = "") =
        TaskPanel(id, null, title, start, end, periodKind = kind)

    private fun withField(payload: String, name: String, value: String): String =
        JsonObject(Json.parseToJsonElement(payload).jsonObject + (name to Json.parseToJsonElement(value))).toString()

    @Test
    fun on_by_default_for_the_5_and_15_minute_breaks_only() {
        val config = SchedulerState.empty().periodKindConfig
        assertTrue(config.tellsTimeline(PeriodKinds.BREAK_5MIN))
        assertTrue(config.tellsTimeline(PeriodKinds.BREAK_15MIN))
        for (kind in PeriodKinds.BUILT_IN + PeriodKinds.BREAK_20S + "commute") assertFalse(config.tellsTimeline(kind), kind)
        // The breaks' titles name their kinds, so the start cue finds the setting.
        val breaks = SchedulerDomain.DEFAULT_SCREEN_BREAKS
        assertEquals(PeriodKinds.BREAK_5MIN, SchedulerDomain.screenBreakKindOf(breaks, "take a 5min pose and blink hard"))
        assertEquals(PeriodKinds.BREAK_15MIN, SchedulerDomain.screenBreakKindOf(breaks, "take a 15min pose"))
        assertEquals(PeriodKinds.BREAK_20S, SchedulerDomain.screenBreakKindOf(breaks, "look 20 feet away"))
        assertEquals(null, SchedulerDomain.screenBreakKindOf(breaks, "nothing"))
    }

    @Test
    fun the_users_example() {
        val end = at1610 + 15 * MIN
        val panels =
            listOf(
                task("a", "task A", at1610 + MIN, at1610 + 6 * MIN),
                task("b", "task B", at1610 + 6 * MIN, end),
                period("bb", PeriodKinds.BEFORE_BED, end, end + 60 * MIN, title = "1 hour before bed"),
                // Never listed: a break, a grey stretch, and what follows the first thing after the period.
                TaskPanel("sb", null, "look 20 feet away", at1610 + 3 * MIN, at1610 + 3 * MIN + 20_000L, screenBreak = true),
                period("in", PeriodKinds.INACTIVITY, at1610 + 8 * MIN, at1610 + 9 * MIN),
                task("c", "task C", end + 60 * MIN, end + 90 * MIN),
            )
        val lines = SchedulerDomain.periodTimelineLines(panels, PeriodKinds.BREAK_15MIN, at1610, end, tz)
        assertEquals(listOf("at 16h11, task A", "at 16h16, task B", "at 16h25, 1 hour before bed"), lines)
        assertEquals(
            "15 minute screen break\nat 16h11, task A\nat 16h16, task B\nat 16h25, 1 hour before bed",
            SchedulerDomain.withTimelineLines("15 minute screen break", lines),
        )
        assertEquals("alone", SchedulerDomain.withTimelineLines("alone", emptyList()))
    }

    @Test
    fun a_block_running_at_the_start_is_said_at_the_start_and_one_task_cut_in_pieces_is_one_line() {
        val end = at1610 + 5 * MIN
        val panels =
            listOf(
                task("a1", "task A", at1610 - 10 * MIN, at1610 + 2 * MIN),
                task("a2", "task A", at1610 + 2 * MIN, at1610 + 4 * MIN),
                // Right after, after a gap: the first thing beginning at or after the end, both blocks at that instant.
                task("x", "task X", end + 7 * MIN, end + 20 * MIN),
                period("p", "commute", end + 7 * MIN, end + 30 * MIN),
                // The period the notification is about is not listed.
                period("own", "walk", at1610, end),
            )
        assertEquals(
            listOf("at 16h10, task A", "at 16h22, task X", "at 16h22, commute"),
            SchedulerDomain.periodTimelineLines(panels, "walk", at1610, end, tz),
        )
    }

    @Test
    fun the_setting_decides_and_a_notice_carries_its_stretchs_end() {
        val panels =
            listOf(
                period("c1", "commute", at1610, at1610 + 10 * MIN),
                period("c2", "commute", at1610 + 10 * MIN, at1610 + 20 * MIN),
                task("a", "task A", at1610 + 5 * MIN, at1610 + 15 * MIN),
            )
        var s = SchedulerReducer.reduce(SchedulerState.empty(), SchedulerIntent.AddPeriodKind("commute"))
        s = s.copy(panels = panels)
        assertEquals(emptyList(), SchedulerDomain.notificationTimelineLines(s, "commute", at1610, at1610 + 20 * MIN, tz))
        s = SchedulerReducer.reduce(s, SchedulerIntent.SetPeriodTellsTimeline("commute", true))
        assertEquals(
            listOf("at 16h15, task A"),
            SchedulerDomain.notificationTimelineLines(s, "commute", at1610, at1610 + 20 * MIN, tz),
        )
        // The notice's period end is the end of the whole stretch of the kind.
        val config = PeriodKindConfig(styles = mapOf("commute" to PeriodKindStyle(PeriodDrawing.Crosses, notices = listOf(5 * MIN))))
        val notice = RuleProgram.noticesOf(panels, config).single()
        assertEquals(at1610 + 20 * MIN, notice.periodEndMillis)
        val cue =
            SchedulerDomain.cueCrossings(
                screenBreaks = emptyList(), breakEvents = emptyList(), mode = 1, windDownInstants = emptyList(),
                automaticSchedule = true, alreadyNotifiedPoseDues = emptyMap(), fromMillis = Long.MIN_VALUE,
                toMillis = at1610, periodNotices = listOf(notice),
            ).single()
        assertEquals("commute", cue.periodKind)
        assertEquals(at1610 + 20 * MIN, cue.periodEndMillis)
    }

    @Test
    fun set_through_one_intent_undone_as_one_unit_and_kept_by_the_store() {
        var s = SchedulerReducer.reduce(SchedulerState.empty(), SchedulerIntent.SetPeriodTellsTimeline(PeriodKinds.BREAK_5MIN, false))
        assertFalse(s.periodKindConfig.tellsTimeline(PeriodKinds.BREAK_5MIN))
        s = SchedulerReducer.reduce(s, SchedulerIntent.SetPeriodTellsTimeline(PeriodKinds.BEFORE_BED, true))
        assertTrue(s.periodKindConfig.tellsTimeline(PeriodKinds.BEFORE_BED))
        // A break kind's setting survives the store (its kind used to be dropped on decode).
        val back = assertNotNull(SchedulerStateCodec.decode(SchedulerStateCodec.encode(s)))
        assertEquals(s.periodKindStyles, back.periodKindStyles)
        assertFalse(back.periodKindConfig.tellsTimeline(PeriodKinds.BREAK_5MIN))
        assertTrue(back.periodKindConfig.tellsTimeline(PeriodKinds.BEFORE_BED))
        assertEquals(listOf(20 * MIN, MIN), back.periodKindConfig.notices(PeriodKinds.BEFORE_BED))

        s = SchedulerReducer.reduce(s, SchedulerIntent.Undo)
        assertFalse(s.periodKindConfig.tellsTimeline(PeriodKinds.BEFORE_BED))
        // Back to the default: no override left.
        s = SchedulerReducer.reduce(s, SchedulerIntent.SetPeriodTellsTimeline(PeriodKinds.BREAK_5MIN, true))
        assertEquals(emptyMap(), s.periodKindStyles)
        // A kind the account does not hold is refused.
        assertEquals(s.periodKindStyles, SchedulerReducer.reduce(s, SchedulerIntent.SetPeriodTellsTimeline("nowhere", true)).periodKindStyles)
    }

    @Test
    fun a_row_written_before_the_switch_reads_as_the_kinds_default() {
        val payload = SchedulerStateCodec.encode(SchedulerState.empty())
        val old =
            withField(
                payload,
                "periodKindStyles",
                """[{"id":"before bed","drawing":"Crosses","folded":true,"notices":[60000]},""" +
                    """{"id":"15min screen break","drawing":"Crosses","folded":true,"notices":[]}]""",
            )
        val decoded = assertNotNull(SchedulerStateCodec.decode(old))
        assertFalse(decoded.periodKindConfig.tellsTimeline(PeriodKinds.BEFORE_BED))
        assertTrue(decoded.periodKindConfig.tellsTimeline(PeriodKinds.BREAK_15MIN))
        assertEquals(PeriodDrawing.Crosses, decoded.periodKindConfig.drawing(PeriodKinds.BREAK_15MIN))
        // Written only where it is not the default, so such a row keeps its bytes.
        assertFalse("tellsTimeline" in SchedulerStateCodec.encode(decoded))
    }
}
