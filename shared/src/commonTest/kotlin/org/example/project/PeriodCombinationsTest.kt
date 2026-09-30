package org.example.project

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.example.project.scheduler.domain.PeriodCombination
import org.example.project.scheduler.domain.PeriodKindConfig
import org.example.project.scheduler.domain.PeriodKindStyle
import org.example.project.scheduler.domain.PeriodKinds
import org.example.project.scheduler.domain.RestrictivePeriod
import org.example.project.scheduler.domain.SchedulerDomain
import org.example.project.scheduler.model.TaskTimeRange
import org.example.project.scheduler.persistence.SchedulerStateCodec
import org.example.project.scheduler.state.SchedulerIntent
import org.example.project.scheduler.state.SchedulerReducer
import org.example.project.scheduler.state.SchedulerState

/**
 * `docs/scheduler_requirements.md` § *$now line$ 3 modes* (2026-09-30) and the period edit window's **combinations**:
 *
 * - *"Mode 3: $now line$ is in either 'fake no computer unlocked' or 'fake no phone unlocked' periods. 'fake no computer
 *   unlocked' can't be with 'no computer unlocked', same with the phone."*
 * - *"When 'fake no computer unlocked' and 'no phone unlocked' are present for example, it is always accompanied by 'no
 *   screen' (except if the user changes the period configurations). In the restrictive period edit window, add a section
 *   where the user can select a combination of periods, and select which periods appear when this combination is
 *   present."*
 */
class PeriodCombinationsTest {
    private val HOUR = 3_600_000L
    private val T = 1_000_000_000_000L
    private val DEFAULT = PeriodKindConfig.DEFAULT

    private fun span(a: Long, b: Long) = TaskTimeRange(T + a * HOUR, T + b * HOUR)

    // ----- the defaults: each computer layer with each phone layer is "no screen" ----------------------------------

    @Test
    fun every_computer_layer_with_every_phone_layer_is_no_screen_by_default() {
        for (computer in listOf(PeriodKinds.NO_COMPUTER_UNLOCKED, PeriodKinds.FAKE_NO_COMPUTER_UNLOCKED)) {
            for (phone in listOf(PeriodKinds.NO_PHONE_UNLOCKED, PeriodKinds.FAKE_NO_PHONE_UNLOCKED)) {
                val closed = DEFAULT.closeRegions(mapOf(computer to listOf(span(0, 3)), phone to listOf(span(2, 5))))
                assertEquals(listOf(span(2, 3)), closed[PeriodKinds.NO_SCREEN], "$computer + $phone")
            }
        }
    }

    @Test
    fun one_layer_alone_is_no_screen_nowhere() {
        val closed = DEFAULT.closeRegions(mapOf(PeriodKinds.FAKE_NO_COMPUTER_UNLOCKED to listOf(span(0, 3))))
        assertEquals(null, closed[PeriodKinds.NO_SCREEN])
    }

    @Test
    fun the_user_can_take_a_default_combination_away() {
        val config = PeriodKindConfig(combinations = PeriodKinds.DEFAULT_COMBINATIONS.filterNot { it.id == "layers-fake-computer" })
        val present = mapOf(PeriodKinds.FAKE_NO_COMPUTER_UNLOCKED to listOf(span(0, 3)), PeriodKinds.NO_PHONE_UNLOCKED to listOf(span(0, 3)))
        assertEquals(null, config.closeRegions(present)[PeriodKinds.NO_SCREEN])
        assertEquals(listOf(span(0, 3)), DEFAULT.closeRegions(present)[PeriodKinds.NO_SCREEN])
    }

    // ----- the user's own combinations -----------------------------------------------------------------------------

    @Test
    fun a_combination_brings_its_kinds_with_their_companions_and_may_feed_another() {
        val config =
            PeriodKindConfig(
                styles = mapOf("commute" to PeriodKindStyle(setOf("errand"), DEFAULT.drawing("commute"))),
                combinations =
                    listOf(
                        // Inactivity and "no computer unlocked" together bring a commute (and the commute's companion)…
                        PeriodCombination("a", setOf(PeriodKinds.INACTIVITY, PeriodKinds.NO_COMPUTER_UNLOCKED), setOf("commute")),
                        // …and a commute with "no phone unlocked" brings "no screen": the first rule feeds the second.
                        PeriodCombination("b", setOf("commute", PeriodKinds.NO_PHONE_UNLOCKED), setOf(PeriodKinds.NO_SCREEN)),
                    ),
            )
        val closed =
            config.closeRegions(
                mapOf(
                    PeriodKinds.INACTIVITY to listOf(span(0, 4)),
                    PeriodKinds.NO_COMPUTER_UNLOCKED to listOf(span(1, 6)),
                    PeriodKinds.NO_PHONE_UNLOCKED to listOf(span(3, 8)),
                ),
            )
        assertEquals(listOf(span(1, 4)), closed["commute"])
        assertEquals(listOf(span(1, 4)), closed["errand"], "the implied kind's companion comes too")
        assertEquals(listOf(span(3, 4)), closed[PeriodKinds.NO_SCREEN])
    }

    @Test
    fun the_scheduler_sees_a_combination_as_a_period_and_never_twice() {
        val config =
            PeriodKindConfig(combinations = listOf(PeriodCombination("a", setOf(PeriodKinds.SLEEP, "commute"), setOf(PeriodKinds.NO_SCREEN))))
        val periods =
            listOf(
                RestrictivePeriod(T, T + 4 * HOUR, "commute", "commute"),
                // SLEEP carries "no screen" itself, so the rule adds nothing where sleep already is.
                RestrictivePeriod(T + 2 * HOUR, T + 6 * HOUR, PeriodKinds.SLEEP, "Sleep"),
            )
        val noScreen = SchedulerDomain.companionPeriods(periods, config).filter { it.kind == PeriodKinds.NO_SCREEN }
        assertEquals(listOf(span(2, 6)), SchedulerDomain.mergeOccupied(noScreen.map { TaskTimeRange(it.startMillis, it.endMillis) }))
    }

    // ----- the fake layer: the "I'm away" button, never with the real one ------------------------------------------

    @Test
    fun an_away_spell_is_the_fake_layer_and_no_screen_where_the_phone_is_locked_as_before() {
        // Computer locked 0–10, away 20–30 (unlocked), no phone that can be asked (assumed locked).
        val observed =
            SchedulerDomain.observedNoScreenRegions(
                computerLocked = listOf(span(0, 10)),
                phoneLocked = null,
                sinceMillis = T,
                untilMillis = T + 100 * HOUR,
                computerAway = listOf(span(20, 30)),
            )
        assertEquals(listOf(span(0, 10), span(20, 30)), observed)
    }

    @Test
    fun without_its_combination_an_away_spell_is_no_screen_nowhere() {
        val config = PeriodKindConfig(combinations = PeriodKinds.DEFAULT_COMBINATIONS.filterNot { PeriodKinds.FAKE_NO_COMPUTER_UNLOCKED in it.kinds })
        val observed =
            SchedulerDomain.observedNoScreenRegions(
                computerLocked = listOf(span(0, 10)),
                phoneLocked = null,
                sinceMillis = T,
                untilMillis = T + 100 * HOUR,
                computerAway = listOf(span(20, 30)),
                config = config,
            )
        assertEquals(listOf(span(0, 10)), observed)
    }

    @Test
    fun the_fake_layer_cannot_be_where_the_real_one_is() {
        assertEquals(
            listOf(span(0, 2), span(5, 6)),
            SchedulerDomain.fakeLayerRegions(listOf(span(0, 6)), listOf(span(2, 5))),
        )
    }

    @Test
    fun the_fake_kinds_are_built_in_layer_kinds_restricting_nobody_alone() {
        for (kind in listOf(PeriodKinds.FAKE_NO_COMPUTER_UNLOCKED, PeriodKinds.FAKE_NO_PHONE_UNLOCKED)) {
            assertTrue(kind in PeriodKinds.BUILT_IN)
            assertTrue(PeriodKinds.isLayerKind(kind))
            assertEquals(1.0, PeriodKinds.defaultResilience(kind))
            assertTrue(kind in SchedulerState.empty().allPeriodKinds)
        }
    }

    // ----- the setting: reducer and store --------------------------------------------------------------------------

    @Test
    fun the_window_writes_the_list_and_a_removed_kind_leaves_every_rule() {
        var s = SchedulerReducer.reduce(SchedulerState.empty(), SchedulerIntent.AddPeriodKind("commute"))
        val rule = PeriodCombination("combination-1", setOf("commute", PeriodKinds.NO_PHONE_UNLOCKED, "unknown"), setOf(PeriodKinds.NO_SCREEN))
        s = SchedulerReducer.reduce(s, SchedulerIntent.SetPeriodCombinations(PeriodKinds.DEFAULT_COMBINATIONS + rule))
        val kept = s.periodCombinations.single { it.id == "combination-1" }
        assertEquals(setOf("commute", PeriodKinds.NO_PHONE_UNLOCKED), kept.kinds, "a kind the account does not hold is dropped")
        assertTrue(SchedulerDomain.schedulingSignature(s) != SchedulerDomain.schedulingSignature(SchedulerState.empty().copy(periodKinds = s.periodKinds, periodKindStyles = s.periodKindStyles)))
        s = SchedulerReducer.reduce(s, SchedulerIntent.RemovePeriodKind("commute"))
        assertEquals(setOf(PeriodKinds.NO_PHONE_UNLOCKED), s.periodCombinations.single { it.id == "combination-1" }.kinds)
    }

    @Test
    fun the_defaults_write_nothing_and_a_payload_without_the_field_loads_them() {
        val s = SchedulerState.empty()
        val payload = SchedulerStateCodec.encode(s)
        val root = Json.parseToJsonElement(payload).jsonObject
        assertTrue("periodCombinations" !in root || root["periodCombinations"].toString() == "null", "defaults are not stored")
        val stripped = JsonObject(root - "periodCombinations").toString()
        assertEquals(PeriodKinds.DEFAULT_COMBINATIONS, assertNotNull(SchedulerStateCodec.decode(stripped)).periodCombinations)
    }

    @Test
    fun an_edited_list_round_trips_and_an_emptied_one_stays_empty() {
        val edited = listOf(PeriodCombination("x", setOf(PeriodKinds.SLEEP, PeriodKinds.NO_PHONE_UNLOCKED), setOf(PeriodKinds.BEFORE_BED)))
        for (rules in listOf(edited, emptyList())) {
            val s = SchedulerReducer.reduce(SchedulerState.empty(), SchedulerIntent.SetPeriodCombinations(rules))
            val decoded = assertNotNull(SchedulerStateCodec.decode(SchedulerStateCodec.encode(s)))
            assertEquals(rules, decoded.periodCombinations)
        }
    }
}
