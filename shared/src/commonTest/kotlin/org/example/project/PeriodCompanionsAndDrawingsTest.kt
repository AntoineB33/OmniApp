package org.example.project

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlinx.datetime.TimeZone
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.example.project.scheduler.domain.DynamicPeriods
import org.example.project.scheduler.domain.PeriodDrawing
import org.example.project.scheduler.domain.PeriodKindConfig
import org.example.project.scheduler.domain.PeriodKindStyle
import org.example.project.scheduler.domain.PeriodKinds
import org.example.project.scheduler.domain.RestrictivePeriod
import org.example.project.scheduler.domain.SchedulerDomain
import org.example.project.scheduler.model.TaskId
import org.example.project.scheduler.model.TaskPanel
import org.example.project.scheduler.model.TaskTimeRange
import org.example.project.scheduler.persistence.SchedulerStateCodec
import org.example.project.scheduler.state.SchedulerIntent
import org.example.project.scheduler.state.SchedulerReducer
import org.example.project.scheduler.state.SchedulerState

/**
 * The **period edit window**'s two settings (user rule, 2026-09-18):
 *
 *  - *"the user can define a set of periods that are always present when this period is present"* — a kind's
 *    COMPANIONS ([PeriodKindStyle.companions], resolved transitively by [PeriodKindConfig.kindsOf]). By default
 *    inactivity is not accompanied by "no screen", and "no screen" is not accompanied by "no computer unlocked"
 *    or "no phone unlocked";
 *  - *"the drawing of each period among a predefined set of drawings that are very distinguishable from one
 *    another, even when they overlap"* — [PeriodDrawing].
 */
class PeriodCompanionsAndDrawingsTest {

    private val HOUR = 3_600_000L
    private val NOW = 1_000_000_000_000L
    private val DEFAULT = PeriodKindConfig.DEFAULT

    private fun period(kind: String, start: Long, end: Long) = RestrictivePeriod(start, end, kind, kind)

    private fun panel(kind: String, start: Long, end: Long) =
        TaskPanel(
            id = "$kind@$start",
            taskId = null,
            title = PeriodKinds.periodTitle(kind),
            startEpochMillis = start,
            endEpochMillis = end,
            periodKind = kind,
        )

    private fun styled(vararg entries: Pair<String, Set<String>>) =
        PeriodKindConfig(entries.associate { (kind, companions) -> kind to PeriodKindStyle(companions, DEFAULT.drawing(kind)) })

    // ----- the defaults -------------------------------------------------------------------------

    @Test
    fun the_default_companions_are_the_users() {
        assertEquals(emptySet(), DEFAULT.impliedKinds(PeriodKinds.INACTIVITY), "inactivity carries no 'no screen'")
        assertEquals(emptySet(), DEFAULT.impliedKinds(PeriodKinds.NO_SCREEN), "'no screen' carries neither layer")
        assertEquals(emptySet(), DEFAULT.assertedLayers(PeriodKinds.NO_SCREEN))
        // PRD §17 is kept: a night and the hour of wind-down into it still carry a no-screen period.
        assertEquals(setOf(PeriodKinds.NO_SCREEN), DEFAULT.impliedKinds(PeriodKinds.SLEEP))
        assertEquals(setOf(PeriodKinds.NO_SCREEN), DEFAULT.impliedKinds(PeriodKinds.BEFORE_BED))
        assertEquals(emptySet(), DEFAULT.impliedKinds("deep work"))
    }

    @Test
    fun the_built_in_kinds_wear_pairwise_distinct_drawings() {
        val drawings = PeriodKinds.BUILT_IN.map(DEFAULT::drawing)
        assertEquals(drawings.size, drawings.toSet().size, "every built-in kind can be told apart: $drawings")
        assertEquals(PeriodDrawing.VerticalLines, DEFAULT.drawing(PeriodKinds.INACTIVITY), "inactivity keeps |")
        assertEquals(PeriodDrawing.RisingObliques, DEFAULT.drawing(PeriodKinds.NO_COMPUTER_UNLOCKED), "keeps /")
        assertEquals(PeriodDrawing.FallingObliques, DEFAULT.drawing(PeriodKinds.NO_PHONE_UNLOCKED), "keeps \\")
    }

    @Test
    fun a_box_draws_its_kind_and_its_companions_but_leaves_the_layers_to_the_hatch() {
        assertEquals(
            listOf(DEFAULT.drawing(PeriodKinds.BEFORE_BED), DEFAULT.drawing(PeriodKinds.NO_SCREEN)),
            DEFAULT.boxDrawings(PeriodKinds.BEFORE_BED),
        )
        assertEquals(emptyList(), DEFAULT.boxDrawings(PeriodKinds.NO_COMPUTER_UNLOCKED))
        val layered = styled(PeriodKinds.NO_SCREEN to setOf(PeriodKinds.NO_PHONE_UNLOCKED))
        assertEquals(listOf(DEFAULT.drawing(PeriodKinds.NO_SCREEN)), layered.boxDrawings(PeriodKinds.NO_SCREEN))
    }

    // ----- companions are transitive -------------------------------------------------------------

    @Test
    fun companions_are_transitive_and_a_cycle_is_harmless() {
        val config = styled(
            "commute" to setOf("outdoors"),
            "outdoors" to setOf(PeriodKinds.NO_SCREEN, "commute"),
        )
        assertEquals(setOf("commute", "outdoors", PeriodKinds.NO_SCREEN), config.kindsOf("commute"))
        assertEquals(setOf("commute", "outdoors", PeriodKinds.NO_SCREEN), config.kindsOf("outdoors"))
        assertTrue(config.isOrImpliesNoScreen("commute"))
    }

    // ----- what reaches the scheduler ------------------------------------------------------------

    @Test
    fun a_companion_is_a_period_of_its_own_over_the_same_span_and_is_never_counted_twice() {
        val config = styled("commute" to setOf(PeriodKinds.NO_SCREEN))
        val periods = listOf(
            period("commute", NOW, NOW + 2 * HOUR),
            // A drawn "no screen" over the second hour: the companion must not duplicate it there, or the
            // plan (which MULTIPLIES covering resiliences) would square a fractional one.
            period(PeriodKinds.NO_SCREEN, NOW + HOUR, NOW + 2 * HOUR),
        )
        val companions = SchedulerDomain.companionPeriods(periods, config)
        assertEquals(1, companions.size, "$companions")
        assertEquals(PeriodKinds.NO_SCREEN, companions.single().kind)
        assertEquals(NOW, companions.single().startMillis)
        assertEquals(NOW + HOUR, companions.single().endMillis)
    }

    @Test
    fun both_layers_still_make_a_no_screen_period_whatever_the_companions_say() {
        val periods = listOf(
            period(PeriodKinds.NO_COMPUTER_UNLOCKED, NOW, NOW + 3 * HOUR),
            period(PeriodKinds.NO_PHONE_UNLOCKED, NOW + 2 * HOUR, NOW + 5 * HOUR),
        )
        val companions = SchedulerDomain.companionPeriods(periods, DEFAULT)
        assertEquals(listOf(TaskTimeRange(NOW + 2 * HOUR, NOW + 3 * HOUR)), companions.map { TaskTimeRange(it.startMillis, it.endMillis) })
        assertTrue(companions.all { it.kind == PeriodKinds.NO_SCREEN })
    }

    /** One on-screen task with enough work to fill the horizon, and its id. */
    private fun oneOnScreenTask(): Pair<SchedulerState, TaskId> {
        var s = SchedulerState.empty()
        val c0 = s.lists[s.rootListId]!!.cellIds[0]
        s = SchedulerReducer.reduce(s, SchedulerIntent.SetCellTitle(c0, "Solo"))
        val solo = s.tasks.keys.first { s.tasks[it]!!.title == "Solo" }
        return SchedulerReducer.reduce(s, SchedulerIntent.SetTaskMinimumTime(solo, 30)) to solo
    }

    private fun placedMillisIn(panels: List<TaskPanel>, taskId: TaskId, from: Long, to: Long): Long =
        panels.filter { it.taskId == taskId }
            .sumOf { (minOf(it.endEpochMillis, to) - maxOf(it.startEpochMillis, from)).coerceAtLeast(0L) }

    @Test
    fun a_kind_accompanied_by_no_screen_keeps_an_on_screen_task_out_of_its_periods() {
        val (s0, solo) = oneOnScreenTask()
        var s = SchedulerReducer.reduce(s0, SchedulerIntent.AddPeriodKind("commute"))
        // The task may work through a commute by the commute's own resilience…
        s = SchedulerReducer.reduce(s, SchedulerIntent.SetTaskResilience(solo, "commute", 1.0))
        s = SchedulerReducer.reduce(s, SchedulerIntent.AddRestrictivePeriod("commute", NOW + HOUR, NOW + 2 * HOUR))
        val free = SchedulerDomain.fillSchedule(s, NOW, TimeZone.UTC, horizonMillis = NOW + 6 * HOUR)
        assertTrue(placedMillisIn(free, solo, NOW + HOUR, NOW + 2 * HOUR) > 0L, "resilient to the commute")

        // …until the account says a commute always comes with "no screen", which an on-screen task is 0 to.
        val before = SchedulerDomain.schedulingSignature(s)
        s = SchedulerReducer.reduce(s, SchedulerIntent.SetPeriodCompanions("commute", setOf(PeriodKinds.NO_SCREEN)))
        assertNotEquals(before, SchedulerDomain.schedulingSignature(s), "a companion change must re-plan")
        val kept = SchedulerDomain.fillSchedule(s, NOW, TimeZone.UTC, horizonMillis = NOW + 6 * HOUR)
        assertEquals(0L, placedMillisIn(kept, solo, NOW + HOUR, NOW + 2 * HOUR))
        assertEquals(
            listOf(TaskTimeRange(NOW + HOUR, NOW + 2 * HOUR)),
            SchedulerDomain.assertedNoScreenRanges(s.panels, s.periodKindConfig),
            "and the record bank reads the stretch as no-screen time",
        )
    }

    @Test
    fun a_drawing_change_is_paint_and_re_plans_nothing() {
        val s0 = SchedulerState.empty()
        val s = SchedulerReducer.reduce(s0, SchedulerIntent.SetPeriodDrawing(PeriodKinds.INACTIVITY, PeriodDrawing.Crosses))
        assertEquals(PeriodDrawing.Crosses, s.periodKindConfig.drawing(PeriodKinds.INACTIVITY))
        assertEquals(SchedulerDomain.schedulingSignature(s0), SchedulerDomain.schedulingSignature(s))
    }

    @Test
    fun inactivity_given_a_no_screen_companion_retracts_at_a_mode_1_line() {
        // The default keeps a grey period the user drew in place under the line; the setting is the user's.
        val periods = listOf(period(PeriodKinds.INACTIVITY, NOW - HOUR, NOW + HOUR))
        assertTrue(SchedulerDomain.retractedAtLineSpans(periods, NOW, DynamicPeriods.MODE_AT_SCREEN, DEFAULT).isEmpty())
        val config = styled(PeriodKinds.INACTIVITY to setOf(PeriodKinds.NO_SCREEN))
        assertEquals(
            listOf(TaskTimeRange(NOW, NOW + HOUR)),
            SchedulerDomain.retractedAtLineSpans(periods, NOW, DynamicPeriods.MODE_AT_SCREEN, config),
        )
    }

    // ----- the reducer ---------------------------------------------------------------------------

    @Test
    fun companions_keep_only_kinds_the_account_holds_and_never_the_kind_itself() {
        val s0 = SchedulerReducer.reduce(SchedulerState.empty(), SchedulerIntent.AddPeriodKind("commute"))
        val s = SchedulerReducer.reduce(
            s0,
            SchedulerIntent.SetPeriodCompanions("commute", setOf("commute", "nonexistent", PeriodKinds.NO_SCREEN)),
        )
        assertEquals(setOf(PeriodKinds.NO_SCREEN), s.periodKindConfig.style("commute").companions)
        assertSame(
            s,
            SchedulerReducer.reduce(s, SchedulerIntent.SetPeriodCompanions("commute", setOf(PeriodKinds.NO_SCREEN))),
            "the same set again is a no-op",
        )
        assertSame(s, SchedulerReducer.reduce(s, SchedulerIntent.SetPeriodCompanions("nonexistent", emptySet())))
    }

    @Test
    fun a_built_in_put_back_to_its_default_holds_no_override() {
        val s0 = SchedulerState.empty()
        val on = SchedulerReducer.reduce(s0, SchedulerIntent.SetPeriodCompanions(PeriodKinds.INACTIVITY, setOf(PeriodKinds.NO_SCREEN)))
        assertTrue(PeriodKinds.INACTIVITY in on.periodKindStyles)
        val off = SchedulerReducer.reduce(on, SchedulerIntent.SetPeriodCompanions(PeriodKinds.INACTIVITY, emptySet()))
        assertEquals(emptyMap(), off.periodKindStyles)
    }

    @Test
    fun a_new_kind_gets_the_least_worn_drawing() {
        val s1 = SchedulerReducer.reduce(SchedulerState.empty(), SchedulerIntent.AddPeriodKind("commute"))
        val d1 = s1.periodKindConfig.drawing("commute")
        assertTrue(d1 !in PeriodKinds.BUILT_IN.map(DEFAULT::drawing), "not one a built-in kind wears: $d1")
        val s2 = SchedulerReducer.reduce(s1, SchedulerIntent.AddPeriodKind("outdoors"))
        assertNotEquals(d1, s2.periodKindConfig.drawing("outdoors"))
    }

    @Test
    fun removing_a_kind_takes_its_style_and_every_reference_to_it() {
        var s = SchedulerReducer.reduce(SchedulerState.empty(), SchedulerIntent.AddPeriodKind("commute"))
        s = SchedulerReducer.reduce(s, SchedulerIntent.SetPeriodCompanions(PeriodKinds.SLEEP, setOf(PeriodKinds.NO_SCREEN, "commute")))
        s = SchedulerReducer.reduce(s, SchedulerIntent.RemovePeriodKind("commute"))
        assertTrue("commute" !in s.periodKindStyles)
        assertEquals(setOf(PeriodKinds.NO_SCREEN), s.periodKindConfig.impliedKinds(PeriodKinds.SLEEP))
    }

    // ----- persistence ---------------------------------------------------------------------------

    @Test
    fun the_settings_round_trip() {
        var s = SchedulerReducer.reduce(SchedulerState.empty(), SchedulerIntent.AddPeriodKind("commute"))
        s = SchedulerReducer.reduce(s, SchedulerIntent.SetPeriodCompanions("commute", setOf(PeriodKinds.NO_SCREEN)))
        s = SchedulerReducer.reduce(s, SchedulerIntent.SetPeriodDrawing(PeriodKinds.NO_SCREEN, PeriodDrawing.Crosses))
        val decoded = assertNotNull(SchedulerStateCodec.decode(SchedulerStateCodec.encode(s)))
        assertEquals(s.periodKindStyles, decoded.periodKindStyles)
    }

    private fun withStyles(payload: String, styles: JsonArray?): String {
        val root = Json.parseToJsonElement(payload).jsonObject
        val next = if (styles == null) root - "periodKindStyles" else root + ("periodKindStyles" to styles)
        return JsonObject(next).toString()
    }

    @Test
    fun a_payload_written_before_the_setting_existed_loads_every_kind_at_its_default() {
        val s = SchedulerReducer.reduce(SchedulerState.empty(), SchedulerIntent.AddPeriodKind("commute"))
        val old = withStyles(SchedulerStateCodec.encode(s), null)
        val decoded = assertNotNull(SchedulerStateCodec.decode(old))
        assertEquals(emptyMap(), decoded.periodKindStyles)
        assertEquals(DEFAULT.impliedKinds(PeriodKinds.SLEEP), decoded.periodKindConfig.impliedKinds(PeriodKinds.SLEEP))
        assertEquals(listOf("commute"), decoded.periodKinds)
    }

    @Test
    fun decode_heals_what_the_rules_forbid() {
        val s = SchedulerReducer.reduce(SchedulerState.empty(), SchedulerIntent.AddPeriodKind("commute"))
        fun row(id: String, companions: List<String>, drawing: String) =
            JsonObject(
                mapOf(
                    "id" to JsonPrimitive(id),
                    "companions" to JsonArray(companions.map(::JsonPrimitive)),
                    "drawing" to JsonPrimitive(drawing),
                ),
            )
        val payload = withStyles(
            SchedulerStateCodec.encode(s),
            JsonArray(
                listOf(
                    // itself, an unknown kind and a legacy spelling of "no screen" as companions
                    row("commute", listOf("commute", "gone", "no on-screen task"), "Spirals"),
                    // a style for a kind the account does not hold
                    row("gone", listOf(PeriodKinds.NO_SCREEN), "Crosses"),
                ),
            ),
        )
        val decoded = assertNotNull(SchedulerStateCodec.decode(payload))
        assertEquals(setOf("commute"), decoded.periodKindStyles.keys)
        val commute = decoded.periodKindStyles.getValue("commute")
        assertEquals(setOf(PeriodKinds.NO_SCREEN), commute.companions)
        assertEquals(PeriodKinds.defaultStyle("commute").drawing, commute.drawing, "an unknown drawing heals to the default")
    }
}
