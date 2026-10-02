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
import org.example.project.scheduler.domain.PeriodFormula
import org.example.project.scheduler.domain.PeriodFormulaToken
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
 * - *"Mode 3: $now line$ is in either 'not on a computer' or 'not on a phone' periods. 'not on a
 *   computer' can't be with 'no computer unlocked', same with the phone."*
 * - *"When 'not on a computer' and 'no phone unlocked' are present for example, it is always accompanied by 'no
 *   screen' (except if the user changes the period configurations). In the restrictive period edit window, add a section
 *   where the user can select a combination of periods, and select which periods appear when this combination is
 *   present."*
 */
class PeriodCombinationsTest {
    private val HOUR = 3_600_000L
    private val T = 1_000_000_000_000L
    private val DEFAULT = PeriodKindConfig.DEFAULT

    private fun span(a: Long, b: Long) = TaskTimeRange(T + a * HOUR, T + b * HOUR)

    /** A rule of one field on each side — the shape every rule had before formulas. */
    private fun rule(id: String, kinds: Set<String>, implies: Set<String>) =
        PeriodCombination(id, PeriodFormula.of(kinds), PeriodFormula.of(implies))

    // ----- the defaults: each computer layer with each phone layer is "no screen" ----------------------------------

    @Test
    fun every_computer_layer_with_every_phone_layer_is_no_screen_by_default() {
        for (computer in listOf(PeriodKinds.NO_COMPUTER_UNLOCKED, PeriodKinds.NOT_ON_A_COMPUTER)) {
            for (phone in listOf(PeriodKinds.NO_PHONE_UNLOCKED, PeriodKinds.NOT_ON_A_PHONE)) {
                val closed = DEFAULT.closeRegions(mapOf(computer to listOf(span(0, 3)), phone to listOf(span(2, 5))))
                assertEquals(listOf(span(2, 3)), closed[PeriodKinds.NO_SCREEN], "$computer + $phone")
            }
        }
    }

    @Test
    fun one_layer_alone_is_no_screen_nowhere() {
        val closed = DEFAULT.closeRegions(mapOf(PeriodKinds.NOT_ON_A_COMPUTER to listOf(span(0, 3))))
        assertEquals(null, closed[PeriodKinds.NO_SCREEN])
    }

    @Test
    fun the_default_is_one_formula_of_the_four_layers() {
        assertEquals(
            "( no computer unlocked or not on a computer ) and ( no phone unlocked or not on a phone )",
            PeriodFormula.describe(PeriodKinds.DEFAULT_COMBINATIONS.first().condition),
        )
        assertEquals(setOf(PeriodKinds.NO_SCREEN), PeriodKinds.DEFAULT_COMBINATIONS.first().implies)
        // Both computer layers and a phone layer: the overlap with either computer layer counts.
        val closed =
            DEFAULT.closeRegions(
                mapOf(
                    PeriodKinds.NO_COMPUTER_UNLOCKED to listOf(span(0, 2)),
                    PeriodKinds.NOT_ON_A_COMPUTER to listOf(span(3, 5)),
                    PeriodKinds.NOT_ON_A_PHONE to listOf(span(1, 4)),
                ),
            )
        assertEquals(listOf(span(1, 2), span(3, 4)), closed[PeriodKinds.NO_SCREEN])
    }

    /** The default layer rule with "not on a computer" taken out of it. */
    private val realComputerOnly
        get() = PeriodKinds.DEFAULT_COMBINATIONS.map { rule ->
            if (rule.id == PeriodKinds.LAYERS_RULE.id) {
                rule.copy(condition = listOf(B, AND, OPEN, C, OR, PeriodFormulaToken.Kinds(setOf(PeriodKinds.NOT_ON_A_PHONE)), CLOSE))
            } else {
                rule
            }
        }

    @Test
    fun the_user_can_take_a_default_combination_away() {
        val config = PeriodKindConfig(combinations = realComputerOnly)
        val present = mapOf(PeriodKinds.NOT_ON_A_COMPUTER to listOf(span(0, 3)), PeriodKinds.NO_PHONE_UNLOCKED to listOf(span(0, 3)))
        assertEquals(null, config.closeRegions(present)[PeriodKinds.NO_SCREEN])
        assertEquals(listOf(span(0, 3)), DEFAULT.closeRegions(present)[PeriodKinds.NO_SCREEN])
    }

    // ----- the user's own combinations -----------------------------------------------------------------------------

    @Test
    fun a_combination_brings_its_kinds_with_their_companions_and_may_feed_another() {
        val config =
            PeriodKindConfig(
                combinations =
                    listOf(
                        PeriodKinds.companionRule("commute", setOf("errand")),
                        // Inactivity and "no computer unlocked" together bring a commute (and the commute's companion)…
                        rule("a", setOf(PeriodKinds.INACTIVITY, PeriodKinds.NO_COMPUTER_UNLOCKED), setOf("commute")),
                        // …and a commute with "no phone unlocked" brings "no screen": the first rule feeds the second.
                        rule("b", setOf("commute", PeriodKinds.NO_PHONE_UNLOCKED), setOf(PeriodKinds.NO_SCREEN)),
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
            PeriodKindConfig(combinations = PeriodKinds.DEFAULT_COMBINATIONS + rule("a", setOf(PeriodKinds.SLEEP, "commute"), setOf(PeriodKinds.NO_SCREEN)))
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
        val config = PeriodKindConfig(combinations = realComputerOnly)
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
        for (kind in listOf(PeriodKinds.NOT_ON_A_COMPUTER, PeriodKinds.NOT_ON_A_PHONE)) {
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
        val rule = rule("combination-1", setOf("commute", PeriodKinds.NO_PHONE_UNLOCKED, "unknown"), setOf(PeriodKinds.NO_SCREEN))
        s = SchedulerReducer.reduce(s, SchedulerIntent.SetPeriodCombinations(PeriodKinds.DEFAULT_COMBINATIONS + rule))
        val kept = s.periodCombinations.single { it.id == "combination-1" }
        assertEquals(PeriodFormula.of(setOf("commute", PeriodKinds.NO_PHONE_UNLOCKED)), kept.condition, "a kind the account does not hold is dropped")
        assertTrue(SchedulerDomain.schedulingSignature(s) != SchedulerDomain.schedulingSignature(SchedulerState.empty().copy(periodKinds = s.periodKinds, periodKindStyles = s.periodKindStyles)))
        s = SchedulerReducer.reduce(s, SchedulerIntent.RemovePeriodKind("commute"))
        assertEquals(PeriodFormula.of(setOf(PeriodKinds.NO_PHONE_UNLOCKED)), s.periodCombinations.single { it.id == "combination-1" }.condition)
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
        val edited = listOf(rule("x", setOf(PeriodKinds.SLEEP, PeriodKinds.NO_PHONE_UNLOCKED), setOf(PeriodKinds.BEFORE_BED)))
        for (rules in listOf(edited, emptyList())) {
            val s = SchedulerReducer.reduce(SchedulerState.empty(), SchedulerIntent.SetPeriodCombinations(rules))
            val decoded = assertNotNull(SchedulerStateCodec.decode(SchedulerStateCodec.encode(s)))
            assertEquals(rules, decoded.periodCombinations)
        }
    }

    // ----- the "When" formula (2026-10-01): "or", "and", "(" and ")" ---------------------------------------------

    private val A = PeriodFormulaToken.Kinds(setOf(PeriodKinds.SLEEP))
    private val B = PeriodFormulaToken.Kinds(setOf(PeriodKinds.NO_COMPUTER_UNLOCKED))
    private val C = PeriodFormulaToken.Kinds(setOf(PeriodKinds.NO_PHONE_UNLOCKED))
    private val AND = PeriodFormulaToken.And
    private val OR = PeriodFormulaToken.Or
    private val OPEN = PeriodFormulaToken.Open
    private val CLOSE = PeriodFormulaToken.Close

    private val present =
        mapOf(
            PeriodKinds.SLEEP to listOf(span(0, 2)),
            PeriodKinds.NO_COMPUTER_UNLOCKED to listOf(span(1, 5)),
            PeriodKinds.NO_PHONE_UNLOCKED to listOf(span(4, 8)),
        )

    private fun beforeBedWhere(vararg condition: PeriodFormulaToken): List<TaskTimeRange>? =
        PeriodKindConfig(
            combinations = listOf(PeriodCombination("f", condition.toList(), PeriodFormula.of(setOf(PeriodKinds.BEFORE_BED)))),
        ).closeRegions(present)[PeriodKinds.BEFORE_BED]

    @Test
    fun or_is_where_either_holds_and_binds_looser_than_and() {
        assertEquals(listOf(span(0, 5)), beforeBedWhere(A, OR, B))
        assertEquals(listOf(span(1, 2)), beforeBedWhere(A, AND, B))
        // A or (B and C) = 0–2 ∪ 4–5; A or B and C reads the same way.
        assertEquals(listOf(span(0, 2), span(4, 5)), beforeBedWhere(A, OR, OPEN, B, AND, C, CLOSE))
        assertEquals(listOf(span(0, 2), span(4, 5)), beforeBedWhere(A, OR, B, AND, C))
        // (A or B) and C = 4–5.
        assertEquals(listOf(span(4, 5)), beforeBedWhere(OPEN, A, OR, B, CLOSE, AND, C))
        // A `(` left open closes at the end.
        assertEquals(listOf(span(4, 5)), beforeBedWhere(C, AND, OPEN, A, OR, B))
    }

    @Test
    fun a_formula_that_does_not_parse_brings_nothing() {
        assertEquals(null, beforeBedWhere(A, OR))
        assertEquals(null, beforeBedWhere(A, CLOSE))
        assertEquals(null, beforeBedWhere())
        // An empty field holds nowhere, so it is dropped from an "or" and empties an "and".
        val empty = PeriodFormulaToken.Kinds(emptySet())
        assertEquals(listOf(span(0, 2)), beforeBedWhere(A, OR, empty))
        assertEquals(null, beforeBedWhere(A, AND, empty))
    }

    @Test
    fun a_rule_a_kind_satisfies_alone_is_what_it_carries() {
        // "when sleep or commute then before bed": every sleep period carries a before-bed one (and its no screen).
        val config =
            PeriodKindConfig(
                combinations =
                    PeriodKinds.DEFAULT_COMBINATIONS +
                        PeriodCombination(
                            "f",
                            listOf(A, OR, PeriodFormulaToken.Kinds(setOf("commute"))),
                            PeriodFormula.of(setOf(PeriodKinds.BEFORE_BED)),
                        ),
            )
        assertEquals(setOf(PeriodKinds.SLEEP, PeriodKinds.NO_SCREEN, PeriodKinds.BEFORE_BED), config.kindsOf(PeriodKinds.SLEEP))
        assertEquals(setOf("commute", PeriodKinds.BEFORE_BED, PeriodKinds.NO_SCREEN), config.kindsOf("commute"))
        // Both layers together are no screen, but neither alone carries it.
        assertEquals(setOf(PeriodKinds.NO_COMPUTER_UNLOCKED), config.kindsOf(PeriodKinds.NO_COMPUTER_UNLOCKED))
    }

    @Test
    fun the_then_side_is_every_field_it_holds() {
        val config =
            PeriodKindConfig(
                combinations = listOf(PeriodCombination("f", listOf(A), PeriodFormula.allOf(listOf(setOf(PeriodKinds.BEFORE_BED), setOf("commute"))))),
            )
        val closed = config.closeRegions(present)
        assertEquals(listOf(span(0, 2)), closed[PeriodKinds.BEFORE_BED])
        assertEquals(listOf(span(0, 2)), closed["commute"])
    }

    @Test
    fun the_buttons_append_only_steps_that_keep_the_formula_readable() {
        var f = PeriodFormula.of(setOf(PeriodKinds.SLEEP))
        assertTrue(PeriodFormula.canAppendOperator(f))
        assertTrue(!PeriodFormula.canOpen(f), "a ( cannot follow a filled field")
        assertTrue(!PeriodFormula.canClose(f), "nothing is open")
        f = PeriodFormula.appendOperator(f, AND) // sleep and ?
        assertTrue(PeriodFormula.canOpen(f))
        f = PeriodFormula.open(f) // sleep and ( ?
        f = f.dropLast(1) + B
        f = PeriodFormula.appendOperator(f, OR) // sleep and ( B or ?
        f = f.dropLast(1) + C
        assertTrue(PeriodFormula.canClose(f))
        f = PeriodFormula.close(f)
        assertEquals(listOf(A, AND, OPEN, B, OR, C, CLOSE), f)
        assertTrue(!PeriodFormula.canClose(f))
        assertEquals(listOf(span(1, 2)), beforeBedWhere(*f.toTypedArray()))
        // ⌫ undoes one step at a time, back to the starting field.
        f = PeriodFormula.removeLast(f)
        assertEquals(listOf(A, AND, OPEN, B, OR, C), f)
        f = PeriodFormula.removeLast(f)
        assertEquals(listOf(A, AND, OPEN, B), f)
        f = PeriodFormula.removeLast(f)
        assertEquals(listOf(A, AND, PeriodFormulaToken.Kinds(emptySet())), f)
        f = PeriodFormula.removeLast(f)
        assertEquals(listOf(A), f)
        assertTrue(!PeriodFormula.canRemoveLast(f))
    }

    @Test
    fun a_formula_round_trips_and_changes_the_scheduling_signature() {
        val rules =
            listOf(PeriodCombination("f", listOf(OPEN, A, OR, B, CLOSE, AND, C), PeriodFormula.allOf(listOf(setOf(PeriodKinds.BEFORE_BED), setOf(PeriodKinds.NO_SCREEN)))))
        val s = SchedulerReducer.reduce(SchedulerState.empty(), SchedulerIntent.SetPeriodCombinations(rules))
        assertEquals(rules, assertNotNull(SchedulerStateCodec.decode(SchedulerStateCodec.encode(s))).periodCombinations)
        val other = SchedulerReducer.reduce(
            SchedulerState.empty(),
            SchedulerIntent.SetPeriodCombinations(listOf(rules.single().copy(condition = listOf(A, OR, B, AND, C)))),
        )
        assertTrue(SchedulerDomain.schedulingSignature(s) != SchedulerDomain.schedulingSignature(other))
    }

    // ----- a payload written before 2026-10-01: the "always present with it" sets fold into rules --------------------

    private fun withField(payload: String, name: String, value: String): String =
        JsonObject(Json.parseToJsonElement(payload).jsonObject + (name to Json.parseToJsonElement(value))).toString()

    @Test
    fun an_older_payload_at_the_defaults_loads_the_default_rules() {
        val payload = SchedulerStateCodec.encode(SchedulerState.empty())
        // What the default sleep style looked like when a drawing was changed: the whole style, companions included.
        val old = withField(payload, "periodKindStyles", """[{"id":"sleep","companions":["no screen"],"drawing":"HorizontalLines"}]""")
        val decoded = assertNotNull(SchedulerStateCodec.decode(old))
        assertEquals(PeriodKinds.DEFAULT_COMBINATIONS, decoded.periodCombinations)
        assertEquals(emptyMap(), decoded.periodKindStyles, "a style left at its default is no override")
    }

    @Test
    fun an_older_payload_s_own_companion_sets_become_rules_beside_its_combinations() {
        val payload = SchedulerStateCodec.encode(SchedulerState.empty())
        val old =
            withField(
                withField(
                    payload,
                    "periodKindStyles",
                    // Sleep's "no screen" struck off; before bed also brings "no phone unlocked"; sleep redrawn.
                    """[{"id":"sleep","drawing":"Crosses"},""" +
                        """{"id":"before bed","companions":["no phone unlocked","no screen"],"drawing":"Zigzags"}]""",
                ),
                "periodCombinations",
                """{"rules":[{"id":"combination-1","kinds":["sleep","no phone unlocked"],"implies":["no screen"]}]}""",
            )
        val decoded = assertNotNull(SchedulerStateCodec.decode(old))
        assertEquals(
            listOf(
                rule("combination-1", setOf(PeriodKinds.SLEEP, PeriodKinds.NO_PHONE_UNLOCKED), setOf(PeriodKinds.NO_SCREEN)),
                PeriodKinds.companionRule(PeriodKinds.BEFORE_BED, setOf(PeriodKinds.NO_PHONE_UNLOCKED, PeriodKinds.NO_SCREEN)),
                PeriodKinds.companionRule(PeriodKinds.BREAK_5MIN, setOf(PeriodKinds.NO_SCREEN)),
                PeriodKinds.companionRule(PeriodKinds.BREAK_15MIN, setOf(PeriodKinds.NO_SCREEN)),
            ),
            decoded.periodCombinations,
        )
        assertEquals(org.example.project.scheduler.domain.PeriodDrawing.Crosses, decoded.periodKindConfig.drawing(PeriodKinds.SLEEP))
        // Folded once: encoding and decoding again changes nothing.
        val again = assertNotNull(SchedulerStateCodec.decode(SchedulerStateCodec.encode(decoded)))
        assertEquals(decoded.periodCombinations, again.periodCombinations)
        assertEquals(decoded.periodKindStyles, again.periodKindStyles)
    }

    @Test
    fun an_older_edited_list_s_four_untouched_layer_rules_become_the_one_formula() {
        val payload = SchedulerStateCodec.encode(SchedulerState.empty())
        fun old(rules: String) = assertNotNull(SchedulerStateCodec.decode(withField(payload, "periodCombinations", """{"rules":[$rules]}""")))
        val four =
            """{"id":"layers","kinds":["no computer unlocked","no phone unlocked"],"implies":["no screen"]},""" +
                """{"id":"layers-fake-computer","kinds":["not on a computer","no phone unlocked"],"implies":["no screen"]},""" +
                """{"id":"layers-fake-phone","kinds":["no computer unlocked","not on a phone"],"implies":["no screen"]},""" +
                """{"id":"layers-fake-both","kinds":["not on a computer","not on a phone"],"implies":["no screen"]}"""
        val mine = """{"id":"combination-1","kinds":["sleep"],"implies":["before bed"]}"""
        assertEquals(
            // An edited list keeps what the account made of it: the later default (no screen → layers) is not added.
            listOf(rule("combination-1", setOf(PeriodKinds.SLEEP), setOf(PeriodKinds.BEFORE_BED))) +
                PeriodKinds.DEFAULT_COMBINATIONS.filterNot { it == PeriodKinds.NO_SCREEN_LAYERS_RULE },
            old("$mine,$four").periodCombinations,
        )
        // One of them struck off: the account's edit stands, nothing is collapsed.
        val three = four.substringBeforeLast(",{")
        assertEquals(3, old(three).periodCombinations.count { it.id.startsWith("layers") })
    }

    @Test
    fun a_redrawn_kind_at_the_default_rules_writes_no_rule_and_keeps_them() {
        val s = SchedulerReducer.reduce(
            SchedulerState.empty(),
            SchedulerIntent.SetPeriodDrawing(PeriodKinds.SLEEP, org.example.project.scheduler.domain.PeriodDrawing.Crosses),
        )
        val decoded = assertNotNull(SchedulerStateCodec.decode(SchedulerStateCodec.encode(s)))
        assertEquals(PeriodKinds.DEFAULT_COMBINATIONS, decoded.periodCombinations)
        assertEquals(s.periodKindStyles, decoded.periodKindStyles)
    }

    // ----- "not" in "When", "or" in "then" (2026-10-01) ------------------------------------------------------------

    private val NOT = PeriodFormulaToken.Not

    @Test
    fun not_holds_wherever_there_is_none_on_the_whole_timeline() {
        val closed = beforeBedWhere(NOT, A)
        assertEquals(
            listOf(TaskTimeRange(PeriodFormula.TIMELINE.startEpochMillis, T), TaskTimeRange(T + 2 * HOUR, PeriodFormula.TIMELINE.endEpochMillis)),
            closed,
        )
        // "no computer unlocked and not sleep" = 2–5.
        assertEquals(listOf(span(2, 5)), beforeBedWhere(B, AND, NOT, A))
        // not binds tighter than and: "not sleep and no computer unlocked" reads the same.
        assertEquals(listOf(span(2, 5)), beforeBedWhere(NOT, A, AND, B))
        // A rule with a "not" is carried by no kind: adding a period can make it false.
        val config = PeriodKindConfig(combinations = listOf(PeriodCombination("f", listOf(NOT, A), PeriodFormula.of(setOf("commute")))))
        assertEquals(setOf(PeriodKinds.INACTIVITY), config.kindsOf(PeriodKinds.INACTIVITY))
    }

    @Test
    fun the_not_button_starts_an_operand_and_undoes_like_a_bracket() {
        var f = PeriodFormula.appendOperator(listOf(A), AND)
        assertTrue(PeriodFormula.canOpen(f))
        f = PeriodFormula.negate(f)
        f = PeriodFormula.open(f)
        assertEquals(listOf(A, AND, NOT, OPEN, PeriodFormulaToken.Kinds(emptySet())), f)
        f = PeriodFormula.removeLast(f)
        assertEquals(listOf(A, AND, NOT, PeriodFormulaToken.Kinds(emptySet())), f)
        f = PeriodFormula.removeLast(f)
        assertEquals(listOf(A, AND, PeriodFormulaToken.Kinds(emptySet())), f)
    }

    private val noScreenDrawn = mapOf(PeriodKinds.NO_SCREEN to listOf(span(0, 4)))

    @Test
    fun a_no_screen_period_the_user_draws_brings_both_layers_by_default() {
        val closed = DEFAULT.closeRegions(noScreenDrawn, manual = noScreenDrawn)
        assertEquals(listOf(span(0, 4)), closed[PeriodKinds.NO_COMPUTER_UNLOCKED])
        assertEquals(listOf(span(0, 4)), closed[PeriodKinds.NO_PHONE_UNLOCKED])
        assertEquals(null, closed[PeriodKinds.NOT_ON_A_COMPUTER], "only the left of each or is laid")
    }

    @Test
    fun the_left_of_or_is_not_laid_where_the_or_already_holds() {
        val present = noScreenDrawn + (PeriodKinds.NOT_ON_A_COMPUTER to listOf(span(1, 2))) + (PeriodKinds.NO_PHONE_UNLOCKED to listOf(span(3, 6)))
        val closed = DEFAULT.closeRegions(present, manual = noScreenDrawn)
        assertEquals(listOf(span(0, 1), span(2, 4)), closed[PeriodKinds.NO_COMPUTER_UNLOCKED])
        assertEquals(listOf(span(0, 6)), closed[PeriodKinds.NO_PHONE_UNLOCKED])
    }

    @Test
    fun a_no_screen_stretch_the_user_did_not_draw_brings_no_layer() {
        // A sleep window carries no screen, and the layers make no screen: neither is drawn by the user.
        val closed =
            DEFAULT.closeRegions(
                mapOf(PeriodKinds.SLEEP to listOf(span(0, 8)), PeriodKinds.NO_SCREEN to listOf(span(10, 12))),
            )
        assertEquals(listOf(span(0, 8), span(10, 12)), closed[PeriodKinds.NO_SCREEN])
        assertEquals(null, closed[PeriodKinds.NO_COMPUTER_UNLOCKED])
        assertEquals(null, closed[PeriodKinds.NO_PHONE_UNLOCKED])
        assertEquals(setOf(PeriodKinds.NO_SCREEN), DEFAULT.kindsOf(PeriodKinds.NO_SCREEN), "no kind carries a placement")
        assertEquals(emptySet(), DEFAULT.assertedLayers(PeriodKinds.SLEEP))
    }

    @Test
    fun the_calendar_hatches_the_layers_of_a_drawn_no_screen_but_not_over_an_away_spell() {
        val drawn =
            org.example.project.scheduler.model.TaskPanel(
                id = "p",
                taskId = null,
                title = PeriodKinds.periodTitle(PeriodKinds.NO_SCREEN),
                startEpochMillis = T,
                endEpochMillis = T + 4 * HOUR,
                periodKind = PeriodKinds.NO_SCREEN,
            )
        assertTrue(SchedulerDomain.isUserPlaced(drawn))
        val computer = SchedulerDomain.ActivityLayer.entries.first { PeriodKinds.layerKind(it) == PeriodKinds.NO_COMPUTER_UNLOCKED }
        assertEquals(listOf(span(0, 4)), SchedulerDomain.assertedLayerRanges(listOf(drawn), computer, DEFAULT))
        val away = mapOf(PeriodKinds.NOT_ON_A_COMPUTER to listOf(span(1, 2)))
        assertEquals(listOf(span(0, 1), span(2, 4)), SchedulerDomain.statedKindRegions(listOf(drawn), DEFAULT, away)[PeriodKinds.NO_COMPUTER_UNLOCKED])
    }

    @Test
    fun formulas_with_or_and_not_round_trip_and_yesterdays_then_fields_still_load() {
        val rules =
            listOf(
                PeriodCombination("f", listOf(NOT, A, AND, B), listOf(OPEN, C, OR, PeriodFormulaToken.Kinds(setOf(PeriodKinds.NOT_ON_A_PHONE)), CLOSE)),
            )
        val s = SchedulerReducer.reduce(SchedulerState.empty(), SchedulerIntent.SetPeriodCombinations(rules))
        assertEquals(rules, assertNotNull(SchedulerStateCodec.decode(SchedulerStateCodec.encode(s))).periodCombinations)
        val old =
            withField(
                SchedulerStateCodec.encode(SchedulerState.empty()),
                "periodCombinations",
                """{"folded":true,"rules":[{"id":"x","condition":[{"kinds":["sleep"]}],"then":[["before bed"],["no screen"]]}]}""",
            )
        assertEquals(
            listOf(PeriodCombination("x", listOf(A), PeriodFormula.allOf(listOf(setOf(PeriodKinds.BEFORE_BED), setOf(PeriodKinds.NO_SCREEN))))),
            assertNotNull(SchedulerStateCodec.decode(old)).periodCombinations,
        )
    }

    // ----- the OS knows better than the left of "or" (2026-10-01) ------------------------------------------------

    @Test
    fun where_the_os_saw_a_computer_unlocked_the_or_lays_not_on_a_computer() {
        // Drawn no screen 0–4; this computer was seen unlocked 1–3 (the phone's history cannot be asked).
        val unlocked = mapOf(PeriodKinds.NO_COMPUTER_UNLOCKED to listOf(span(1, 3)))
        val closed = DEFAULT.closeRegions(noScreenDrawn, manual = noScreenDrawn, knownAbsent = unlocked)
        assertEquals(listOf(span(0, 1), span(3, 4)), closed[PeriodKinds.NO_COMPUTER_UNLOCKED])
        assertEquals(listOf(span(1, 3)), closed[PeriodKinds.NOT_ON_A_COMPUTER])
        assertEquals(listOf(span(0, 4)), closed[PeriodKinds.NO_PHONE_UNLOCKED], "nothing is known of the phone")
        assertEquals(null, closed[PeriodKinds.NOT_ON_A_PHONE])
        // Still nothing where the "or" already holds: an away spell over 2–3 is left as it is.
        val away = noScreenDrawn + (PeriodKinds.NOT_ON_A_COMPUTER to listOf(span(2, 3)))
        val withAway = DEFAULT.closeRegions(away, manual = noScreenDrawn, knownAbsent = unlocked)
        assertEquals(listOf(span(1, 3)), withAway[PeriodKinds.NOT_ON_A_COMPUTER])
    }

    @Test
    fun known_unlocked_is_the_asked_past_minus_the_locks_and_nothing_when_it_cannot_be_asked() {
        assertEquals(
            listOf(span(0, 1), span(3, 5)),
            SchedulerDomain.knownUnlockedRegions(listOf(span(1, 3)), T, T + 5 * HOUR),
        )
        assertEquals(emptyList(), SchedulerDomain.knownUnlockedRegions(null, T, T + 5 * HOUR))
    }

    @Test
    fun a_sleep_window_lays_no_layer_where_the_line_crossed_it_at_a_screen() {
        // User report (2026-10-01): "I didn't hit the I'm away button, so the now line must be in mode 1 and the sleep
        // period must retract to the now line" — a sleep window (orange, the user's own, carrying a no-screen period)
        // states nothing over the past this computer was unlocked for with the button off.
        val sleep =
            org.example.project.scheduler.model.TaskPanel(
                id = "sleep/x",
                taskId = null,
                title = SchedulerDomain.SLEEP_PANEL_TITLE,
                startEpochMillis = T,
                endEpochMillis = T + 8 * HOUR,
                sleep = true,
                periodKind = PeriodKinds.SLEEP,
            )
        assertTrue(SchedulerDomain.isUserStated(sleep))
        val unlocked = mapOf(PeriodKinds.NO_COMPUTER_UNLOCKED to listOf(span(1, 3)))
        val stated =
            SchedulerDomain.statedKindRegions(listOf(sleep), DEFAULT, knownAbsent = unlocked, atScreenPast = listOf(span(1, 3)))
        assertEquals(null, stated[PeriodKinds.NOT_ON_A_COMPUTER])
        assertEquals(listOf(span(0, 1), span(3, 8)), stated[PeriodKinds.SLEEP])
        assertEquals(listOf(span(0, 1), span(3, 8)), stated[PeriodKinds.NO_COMPUTER_UNLOCKED])
        assertEquals(listOf(span(0, 1), span(3, 8)), stated[PeriodKinds.NO_PHONE_UNLOCKED])
        // The Sleep band itself is what retracted: "Sleep is still just behind now line" (same day). The band the
        // calendar projects carries no stored kind, only the flag.
        val band = sleep.copy(periodKind = "")
        assertEquals(
            listOf(span(0, 1), span(3, 8)),
            SchedulerDomain.retractOverAtScreenPast(listOf(band), listOf(span(1, 3)), DEFAULT)
                .map { TaskTimeRange(it.startEpochMillis, it.endEpochMillis) },
        )
        assertEquals(
            emptyList(),
            SchedulerDomain.retractOverAtScreenPast(listOf(band), listOf(span(0, 8)), DEFAULT),
            "a window worked through to the line leaves nothing behind it",
        )
        // "I'm away" pressed over 2–3: mode 3 there, so the window stands and the spell is its fake layer.
        val away = mapOf(PeriodKinds.NOT_ON_A_COMPUTER to listOf(span(2, 3)))
        val withAway =
            SchedulerDomain.statedKindRegions(listOf(sleep), DEFAULT, away, unlocked, atScreenPast = listOf(span(1, 2)))
        assertEquals(listOf(span(2, 3)), withAway[PeriodKinds.NOT_ON_A_COMPUTER])
        assertEquals(listOf(span(0, 1), span(2, 8)), withAway[PeriodKinds.SLEEP])
        // A "No screen" period the user DREW over those hours is their word about them: it keeps its dotted band.
        val drawn = sleep.copy(id = "drawn", sleep = false, periodKind = PeriodKinds.NO_SCREEN)
        assertEquals(
            listOf(span(1, 3)),
            SchedulerDomain.statedKindRegions(listOf(drawn), DEFAULT, knownAbsent = unlocked, atScreenPast = listOf(span(1, 3)))[
                PeriodKinds.NOT_ON_A_COMPUTER,
            ],
        )
        // A break is the app's own (grey): it carries no screen, but brings no layer.
        val brk = sleep.copy(id = "break", sleep = false, screenBreak = true, periodKind = PeriodKinds.BREAK_15MIN)
        assertTrue(!SchedulerDomain.isUserStated(brk))
        assertEquals(null, SchedulerDomain.statedKindRegions(listOf(brk), DEFAULT)[PeriodKinds.NO_COMPUTER_UNLOCKED])
    }
}