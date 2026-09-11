package org.example.project

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.example.project.scheduler.domain.PeriodKinds
import org.example.project.scheduler.domain.SchedulerDomain
import org.example.project.scheduler.model.PanelPins
import org.example.project.scheduler.model.TaskId
import org.example.project.scheduler.model.TaskTimeRange
import org.example.project.scheduler.persistence.SchedulerStateCodec
import org.example.project.scheduler.state.SchedulerIntent
import org.example.project.scheduler.state.SchedulerReducer
import org.example.project.scheduler.state.SchedulerState
import org.example.project.time.AppClock

/**
 * PRD §8 contextual menu **"add…"** → *restrictive period*: the menu's four "add" entries became one, and the
 * two of them that named a kind of period by hand ("add a no-screen period", "add an inactivity period")
 * became a KIND CHOSEN off the account's own list.
 *
 * `side-dev/README.md` § *Restrictive Period* is what makes that a simplification rather than a feature: a
 * period is a start, an end and a kind, and every rule about it is each task's **resilience** to that kind.
 * So this file pins the three places that used to enumerate the two kinds with a flag and now ask the model:
 *
 *  - **one intent lays every period** ([SchedulerIntent.AddRestrictivePeriod]) — the built-ins keep the exact
 *    panels they always produced, and a kind the account defined produces one too, carrying its kind and
 *    NEITHER legacy flag (there is no flag that means it, and setting one would say it was another kind);
 *  - **who overrides whom is the period's refusal** — a period takes the task panels whose resilience to its
 *    kind is `0` and leaves the rest, in both directions;
 *  - **the strip under a period is the same refusal**, so a task the kind lets through keeps its record.
 *
 * Plus the two things that make a kind with no flag survive at all: it is **persisted** (without it a
 * hand-drawn `before bed` or account-defined period decoded as a block of WORK on the next load), and it is
 * in the **scheduling signature** (or re-kinding a period would re-plan nothing).
 *
 * The two built-in kinds' own behaviour is pinned by `CalendarPeriodEditTest` and
 * `NoScreenInactivityPanelTest`, which now say the same things through this one intent.
 */
class RestrictivePeriodKindTest {

    private val HOUR = 3_600_000L
    private val NOW = 1_000_000_000_000L

    /** A kind of the account's own — the case the old two-entry menu could not reach at all. */
    private val DEEP = "deep work"

    private class FixedClock(var now: Long) : AppClock {
        override fun nowMillis(): Long = now
    }

    private fun withClock(body: () -> Unit) {
        val previous = SchedulerReducer.clock
        SchedulerReducer.clock = FixedClock(NOW)
        try {
            body()
        } finally {
            SchedulerReducer.clock = previous
        }
    }

    /**
     * Two leaf tasks and the account's own kind [DEEP]. "Resilient" is given a value above zero for it; "Plain"
     * is never told about it, which by [PeriodKinds.defaultResilience] is a `0` — the whole point of "a kind
     * the user has just defined is added to every task at 0" being that nothing is written to say so.
     */
    private fun accountWithOwnKind(): Triple<SchedulerState, TaskId, TaskId> {
        var s = SchedulerState.empty()
        val c0 = s.lists[s.rootListId]!!.cellIds[0]
        s = SchedulerReducer.reduce(s, SchedulerIntent.SetCellTitle(c0, "Plain"))
        val plain = s.tasks.keys.first { s.tasks[it]!!.title == "Plain" }
        val c1 = s.lists[s.rootListId]!!.cellIds[1]
        s = SchedulerReducer.reduce(s, SchedulerIntent.SetCellTitle(c1, "Resilient"))
        val resilient = s.tasks.keys.first { s.tasks[it]!!.title == "Resilient" }
        s = SchedulerReducer.reduce(s, SchedulerIntent.AddPeriodKind(DEEP))
        s = SchedulerReducer.reduce(s, SchedulerIntent.SetTaskResilience(resilient, DEEP, 0.5))
        return Triple(s, plain, resilient)
    }

    private fun withRecord(s: SchedulerState, taskId: TaskId, start: Long, end: Long): SchedulerState {
        val task = s.tasks.getValue(taskId)
        return s.copy(tasks = s.tasks + (taskId to task.copy(record = task.record + TaskTimeRange(start, end))))
    }

    private fun ownKindPanel(s: SchedulerState) = s.panels.firstOrNull { it.restrictiveKind == DEEP }

    // ----- one intent, every kind -------------------------------------------------------------

    @Test
    fun a_period_of_the_accounts_own_kind_carries_its_kind_and_neither_legacy_flag() {
        val (s0, _, _) = accountWithOwnKind()
        val s = SchedulerReducer.reduce(s0, SchedulerIntent.AddRestrictivePeriod(DEEP, NOW, NOW + HOUR))

        val panel = ownKindPanel(s)
        assertNotNull(panel, "the account's own kind must be layable: ${s.panels}")
        assertEquals(DEEP, panel.periodKind)
        assertTrue(panel.isRestrictivePeriod)
        assertEquals(DEEP, panel.title, "a kind the user named is its own title")
        assertEquals(NOW to NOW + HOUR, panel.startEpochMillis to panel.endEpochMillis)
        // Neither flag: they stand for the two kinds that HAVE one, and this is not either of them.
        assertFalse(panel.noScreen)
        assertFalse(panel.inactivity)
        // A period the user drew: the calendar's pin box reads checked off this, and `pinned` stays false.
        assertTrue(panel.pins.existence)
        assertFalse(panel.pinned)
        assertTrue(SchedulerDomain.isUserPlaced(panel))
    }

    @Test
    fun the_built_in_kinds_still_produce_exactly_the_panels_they_always_did() {
        val noScreen =
            SchedulerReducer
                .reduce(SchedulerState.empty(), SchedulerIntent.AddRestrictivePeriod(PeriodKinds.NO_SCREEN, NOW, NOW + HOUR))
                .panels.single { it.isRestrictivePeriod }
        assertEquals("No screen", noScreen.title)
        assertTrue(noScreen.noScreen)
        assertFalse(noScreen.inactivity)
        assertEquals(PeriodKinds.NO_SCREEN, noScreen.restrictiveKind)

        val grey =
            SchedulerReducer
                .reduce(SchedulerState.empty(), SchedulerIntent.AddRestrictivePeriod(PeriodKinds.NO_TASK, NOW, NOW + HOUR))
                .panels.single { it.isRestrictivePeriod }
        assertEquals("Inactivity", grey.title)
        assertTrue(grey.inactivity)
        assertFalse(grey.noScreen)
        assertEquals(PeriodKinds.NO_TASK, grey.restrictiveKind)
    }

    @Test
    fun a_hand_drawn_before_bed_period_is_an_object_of_its_own_not_one_the_fill_relays() {
        val s = SchedulerReducer.reduce(
            SchedulerState.empty(),
            SchedulerIntent.AddRestrictivePeriod(PeriodKinds.BEFORE_BED, NOW, NOW + HOUR),
        )
        val panel = s.panels.single { it.isRestrictivePeriod }
        assertEquals(PeriodKinds.BEFORE_BED, panel.restrictiveKind)
        assertEquals("Before bed", panel.title)
        // The §17 wind-down hours the fill lays are `before-bed/{wake day}` and are regenerated wholesale;
        // this one is the user's, so it must NOT be one of those or the next fill would wipe it.
        assertFalse(panel.id.startsWith(SchedulerDomain.BEFORE_BED_PANEL_ID_PREFIX))
        assertFalse(SchedulerDomain.isRegeneratedPanel(panel))
        assertTrue(SchedulerDomain.isUserPlaced(panel))
    }

    @Test
    fun a_blank_kind_lays_nothing() {
        val s0 = SchedulerState.empty()
        assertEquals(s0.panels, SchedulerReducer.reduce(s0, SchedulerIntent.AddRestrictivePeriod("   ", NOW, NOW + HOUR)).panels)
    }

    @Test
    fun the_kind_is_normalized_so_one_spelling_is_one_kind() {
        val (s0, _, _) = accountWithOwnKind()
        val s = SchedulerReducer.reduce(s0, SchedulerIntent.AddRestrictivePeriod("  deep   work ", NOW, NOW + HOUR))
        assertEquals(DEEP, s.panels.single { it.isRestrictivePeriod }.restrictiveKind)
    }

    // ----- who overrides whom is the period's refusal ------------------------------------------

    @Test
    fun a_period_of_the_accounts_own_kind_trims_the_task_panel_it_refuses() = withClock {
        val (s0, plain, _) = accountWithOwnKind()
        var s = SchedulerReducer.reduce(
            s0,
            SchedulerIntent.AddTaskPanel(plain, "Plain", NOW + HOUR, NOW + 3 * HOUR, PanelPins(existence = true)),
        )
        s = SchedulerReducer.reduce(s, SchedulerIntent.AddRestrictivePeriod(DEEP, NOW, NOW + 2 * HOUR))

        val panel = s.panels.single { it.taskId == plain }
        assertEquals(
            NOW + 2 * HOUR to NOW + 3 * HOUR,
            panel.startEpochMillis to panel.endEpochMillis,
            "the kind's default 0 refuses this task, so the period takes the hours it covers",
        )
    }

    @Test
    fun a_period_of_the_accounts_own_kind_leaves_a_task_it_is_resilient_to_alone() = withClock {
        val (s0, _, resilient) = accountWithOwnKind()
        var s = SchedulerReducer.reduce(
            s0,
            SchedulerIntent.AddTaskPanel(resilient, "Resilient", NOW + HOUR, NOW + 3 * HOUR, PanelPins(existence = true)),
        )
        s = SchedulerReducer.reduce(s, SchedulerIntent.AddRestrictivePeriod(DEEP, NOW, NOW + 2 * HOUR))

        val panel = s.panels.single { it.taskId == resilient }
        assertEquals(
            NOW + HOUR to NOW + 3 * HOUR,
            panel.startEpochMillis to panel.endEpochMillis,
            "a resilience above 0 means the task may run there — a period scales its share, it does not evict it",
        )
        assertNotNull(ownKindPanel(s), "and the period is still laid whole")
    }

    @Test
    fun a_task_panel_laid_over_a_period_that_refuses_it_trims_the_period() = withClock {
        val (s0, plain, _) = accountWithOwnKind()
        var s = SchedulerReducer.reduce(s0, SchedulerIntent.AddRestrictivePeriod(DEEP, NOW, NOW + 2 * HOUR))
        s = SchedulerReducer.reduce(
            s,
            SchedulerIntent.AddTaskPanel(plain, "Plain", NOW + HOUR, NOW + 3 * HOUR, PanelPins(existence = true)),
        )
        val period = ownKindPanel(s)
        assertNotNull(period)
        assertEquals(NOW to NOW + HOUR, period.startEpochMillis to period.endEpochMillis)
    }

    @Test
    fun a_task_panel_the_period_allows_leaves_it_whole() = withClock {
        val (s0, _, resilient) = accountWithOwnKind()
        var s = SchedulerReducer.reduce(s0, SchedulerIntent.AddRestrictivePeriod(DEEP, NOW, NOW + 2 * HOUR))
        s = SchedulerReducer.reduce(
            s,
            SchedulerIntent.AddTaskPanel(resilient, "Resilient", NOW + HOUR, NOW + 3 * HOUR, PanelPins(existence = true)),
        )
        val period = ownKindPanel(s)
        assertNotNull(period)
        assertEquals(NOW to NOW + 2 * HOUR, period.startEpochMillis to period.endEpochMillis)
    }

    @Test
    fun a_hand_placed_task_panel_never_deletes_a_screen_break_or_a_sleep_band() = withClock {
        val (s0, plain, _) = accountWithOwnKind()
        // Both are `no task allowed` and therefore refuse every task — but they are re-laid wholesale by
        // every fill, so a hand-placed panel is placed THROUGH them (§15/§17) and must not take them away.
        var s = s0.copy(
            panels = s0.panels +
                org.example.project.scheduler.model.TaskPanel(
                    id = "break/1",
                    taskId = null,
                    title = "Screen break",
                    startEpochMillis = NOW + HOUR,
                    endEpochMillis = NOW + HOUR + 20_000L,
                    screenBreak = true,
                ) +
                org.example.project.scheduler.model.TaskPanel(
                    id = "sleep/1",
                    taskId = null,
                    title = "Sleep",
                    startEpochMillis = NOW + 2 * HOUR,
                    endEpochMillis = NOW + 3 * HOUR,
                    sleep = true,
                ),
        )
        s = SchedulerReducer.reduce(
            s,
            SchedulerIntent.AddTaskPanel(plain, "Plain", NOW, NOW + 4 * HOUR, PanelPins(existence = true)),
        )
        assertTrue(s.panels.any { it.id == "break/1" }, "the §15 break must survive: ${s.panels.map { it.id }}")
        assertTrue(s.panels.any { it.id == "sleep/1" }, "the §17 sleep band must survive: ${s.panels.map { it.id }}")
    }

    // ----- the strip under a period is the same refusal ----------------------------------------

    @Test
    fun a_period_of_the_accounts_own_kind_strips_only_the_records_it_refuses() = withClock {
        val (s0, plain, resilient) = accountWithOwnKind()
        var s = withRecord(s0, plain, NOW - 3 * HOUR, NOW - 2 * HOUR)
        s = withRecord(s, resilient, NOW - 3 * HOUR, NOW - 2 * HOUR)
        s = SchedulerReducer.reduce(s, SchedulerIntent.AddRestrictivePeriod(DEEP, NOW - 4 * HOUR, NOW))

        assertTrue(
            s.tasks.getValue(plain).record.isEmpty(),
            "the kind refuses this task, so the app cannot assume the work happened",
        )
        assertEquals(
            listOf(NOW - 3 * HOUR to NOW - 2 * HOUR),
            s.tasks.getValue(resilient).record.map { it.startEpochMillis to it.endEpochMillis },
            "a task the period lets through really could have run there, so its record is true",
        )
    }

    // ----- a kind with no flag has to survive a save and has to re-plan -------------------------

    @Test
    fun the_codec_round_trips_a_period_of_the_accounts_own_kind() {
        val (s0, _, _) = accountWithOwnKind()
        val s = SchedulerReducer.reduce(s0, SchedulerIntent.AddRestrictivePeriod(DEEP, NOW, NOW + HOUR))
        val decoded = SchedulerStateCodec.decode(SchedulerStateCodec.encode(s))
        assertNotNull(decoded)
        val panel = decoded.panels.firstOrNull { it.restrictiveKind == DEEP }
        assertNotNull(
            panel,
            "without the kind on the wire a hand-drawn period decodes as a block of WORK: ${decoded.panels}",
        )
        assertTrue(panel.isRestrictivePeriod)
        assertEquals(DEEP, panel.periodKind)
    }

    @Test
    fun the_codec_round_trips_a_hand_drawn_before_bed_period() {
        val s = SchedulerReducer.reduce(
            SchedulerState.empty(),
            SchedulerIntent.AddRestrictivePeriod(PeriodKinds.BEFORE_BED, NOW, NOW + HOUR),
        )
        val decoded = SchedulerStateCodec.decode(SchedulerStateCodec.encode(s))
        assertNotNull(decoded)
        assertEquals(PeriodKinds.BEFORE_BED, decoded.panels.single { it.isRestrictivePeriod }.restrictiveKind)
    }

    @Test
    fun a_payload_written_before_the_kind_was_persisted_still_decodes_from_its_flags() {
        // Persisted-DB compatibility: the field is new, so a panel that predates it carries none — and the
        // two kinds that have a legacy flag must go on being healed out of it, exactly as before.
        val json =
            """
            {"rootListId":"L","lists":[{"id":"L","parentCellId":null,"cellIds":["c0"]}],
             "cells":[{"id":"c0","parentListId":"L","taskId":null}],
             "tasks":[],
             "panels":[
               {"id":"panel/0","title":"No screen","start":0,"end":3600000,"noScreen":true},
               {"id":"panel/1","title":"Inactivity","start":7200000,"end":10800000,"inactivity":true},
               {"id":"panel/2","title":"Work","start":14400000,"end":18000000}
             ]}
            """.trimIndent()
        val decoded = SchedulerStateCodec.decode(json)
        assertNotNull(decoded)
        assertEquals(PeriodKinds.NO_SCREEN, decoded.panels.single { it.id == "panel/0" }.restrictiveKind)
        assertEquals(PeriodKinds.NO_TASK, decoded.panels.single { it.id == "panel/1" }.restrictiveKind)
        assertEquals("", decoded.panels.single { it.id == "panel/2" }.restrictiveKind)
    }

    @Test
    fun the_scheduling_signature_reads_a_periods_kind_and_not_its_legacy_flags() {
        val (base, _, _) = accountWithOwnKind()
        val deep = SchedulerReducer.reduce(base, SchedulerIntent.AddRestrictivePeriod(DEEP, NOW, NOW + HOUR))
        val panel = deep.panels.single { it.isRestrictivePeriod }
        // The same panel, same id, same bounds, same (absent) flags — only the kind differs. Read off the
        // flags the two were indistinguishable, so re-kinding a period re-planned nothing.
        val other = deep.copy(
            panels = deep.panels.map { if (it.id == panel.id) it.copy(periodKind = PeriodKinds.BEFORE_BED) else it },
        )
        assertNotEquals(
            SchedulerDomain.schedulingSignature(deep),
            SchedulerDomain.schedulingSignature(other),
        )
    }

    // ----- the kind's own naming funnel --------------------------------------------------------

    @Test
    fun a_kinds_title_and_legacy_flags_come_from_one_place() {
        assertEquals("No screen", PeriodKinds.periodTitle(PeriodKinds.NO_SCREEN))
        assertEquals("Inactivity", PeriodKinds.periodTitle(PeriodKinds.NO_TASK))
        assertEquals("Before bed", PeriodKinds.periodTitle(PeriodKinds.BEFORE_BED))
        assertEquals(SchedulerDomain.BEFORE_BED_PANEL_TITLE, PeriodKinds.periodTitle(PeriodKinds.BEFORE_BED))
        assertEquals(DEEP, PeriodKinds.periodTitle(DEEP))

        assertTrue(PeriodKinds.legacyNoScreenFlag(PeriodKinds.NO_SCREEN))
        assertFalse(PeriodKinds.legacyNoScreenFlag(PeriodKinds.NO_TASK))
        assertTrue(PeriodKinds.legacyInactivityFlag(PeriodKinds.NO_TASK))
        assertFalse(PeriodKinds.legacyInactivityFlag(PeriodKinds.BEFORE_BED))
        assertFalse(PeriodKinds.legacyInactivityFlag(DEEP))
        assertFalse(PeriodKinds.legacyNoScreenFlag(DEEP))
    }
}
