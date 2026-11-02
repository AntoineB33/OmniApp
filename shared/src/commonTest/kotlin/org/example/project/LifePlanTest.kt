package org.example.project

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import androidx.compose.ui.text.input.TextFieldValue
import org.example.project.scheduler.domain.LifePlanDomain
import org.example.project.scheduler.domain.LifePlanEntry
import org.example.project.scheduler.domain.LifePlanKind
import org.example.project.scheduler.domain.LifePlanSection
import org.example.project.scheduler.domain.LifePlanView
import org.example.project.scheduler.persistence.SchedulerStateCodec
import org.example.project.scheduler.state.HistoryCategory
import org.example.project.scheduler.state.LifePlanDelta
import org.example.project.scheduler.state.SchedulerIntent
import org.example.project.scheduler.state.SchedulerReducer
import org.example.project.scheduler.state.SchedulerState
import org.example.project.scheduler.sync.EntityRows
import org.example.project.scheduler.sync.SnapshotMerge
import org.example.project.ui.CustomMenuButtons
import org.example.project.ui.LiveFieldText

/**
 * User rule 2026-11-02: *"Add a default button on the left-side menu in the Schedule feature page that opens the life plan
 * window"*: a drop-down of sections, "steps" a list whose elements hold sub-elements under an arrow, "problems" a place to
 * write in tabs. The plan is rows of the account, a line of a tab being one (`server-quota.md`).
 */
class LifePlanTest {
    private val random = Random(7)

    private fun r(state: SchedulerState, intent: SchedulerIntent) = SchedulerReducer.reduce(state, intent)

    private fun main(state: SchedulerState) = state.histories.forCategory(HistoryCategory.Main).units

    private fun merge(base: SchedulerState, local: SchedulerState, remote: SchedulerState): SchedulerState =
        SchedulerStateCodec.decodeSnapshot(
            SnapshotMerge.merge(
                SchedulerStateCodec.encodeSnapshot(base), SchedulerStateCodec.encodeSnapshot(local), SchedulerStateCodec.encodeSnapshot(remote),
            )!!,
        )!!

    /** One tab holding [text], and its id. */
    private fun tabWith(text: String): Pair<List<LifePlanEntry>, String> {
        val (entries, tab) = LifePlanDomain.addedTab(emptyList(), random)
        return LifePlanDomain.withText(entries, tab, text, random) to tab
    }

    /** The entries [after] holds that [before] does not hold the same. */
    private fun touched(before: List<LifePlanEntry>, after: List<LifePlanEntry>) = after.filter { it !in before }

    @Test
    fun the_button_is_a_default_of_the_schedule_page_alone() {
        assertTrue(CustomMenuButtons.LIFE_PLAN in CustomMenuButtons.defaultsOf(""))
        assertEquals("LifePlan", CustomMenuButtons.LIFE_PLAN.windowId)
        assertFalse(CustomMenuButtons.LIFE_PLAN.global)
        for (page in CustomMenuButtons.PAGES.filter { it.isNotEmpty() }) {
            assertFalse(CustomMenuButtons.LIFE_PLAN in CustomMenuButtons.defaultsOf(page))
        }
    }

    @Test
    fun a_menu_stored_before_is_given_the_button_once_under_the_calendar() {
        val before =
            """{"buttons":[{"id":"d-calendar","window":"Calendar","title":"Calendar"},{"id":"b1","window":"Search","title":"Mine"}],""" +
                """"seeded":true,"globalSeeded":true,"muteSeeded":true,"soundsTogether":true}"""
        val list = CustomMenuButtons.decode(before)
        assertEquals(listOf("d-calendar", "d-life-plan", "b1"), list.map { it.id })
        // Removed from there, it stays removed.
        val emptied = CustomMenuButtons.removed(list, "d-life-plan")
        assertEquals(listOf("d-calendar", "b1"), CustomMenuButtons.decode(CustomMenuButtons.encode(emptied)).map { it.id })
        // Another page's stored menu is not given it.
        val logs = CustomMenuButtons.decode(before, CustomMenuButtons.CLAUDE_LOGS_DEFAULTS)
        assertFalse(logs.any { it.id == "d-life-plan" })
    }

    @Test
    fun a_step_holds_sub_elements_shown_under_its_arrow_only() {
        val (one, a) = LifePlanDomain.addedStep(emptyList(), null, random)
        val (two, b) = LifePlanDomain.addedStep(one, null, random)
        val (three, sub) = LifePlanDomain.addedStep(two, a, random)
        val (entries, subSub) = LifePlanDomain.addedStep(three, sub, random)

        val closed = LifePlanDomain.stepRows(entries, emptySet())
        assertEquals(listOf(a, b), closed.map { it.step.id })
        assertEquals(listOf(true, false), closed.map { it.hasChildren })
        assertEquals(listOf(false, false), closed.map { it.expanded })

        val open = LifePlanDomain.stepRows(entries, setOf(a, sub))
        assertEquals(listOf(a, sub, subSub, b), open.map { it.step.id })
        assertEquals(listOf(0, 1, 2, 0), open.map { it.depth })

        // A sub-element is deleted with its step.
        assertEquals(listOf(b), LifePlanDomain.removed(entries, a).map { it.id })
    }

    @Test
    fun the_order_comes_from_the_ranks_as_rows_come_back_in_no_order() {
        val (one, a) = LifePlanDomain.addedStep(emptyList(), null, random)
        val (two, b) = LifePlanDomain.addedStep(one, null, random)
        val (three, c) = LifePlanDomain.addedStep(two, null, random)
        assertEquals(listOf(a, b, c), LifePlanDomain.stepRows(three.reversed(), emptySet()).map { it.step.id })
        val (entries, tab) = tabWith("x\ny\nz")
        assertEquals("x\ny\nz", LifePlanDomain.textOf(entries.reversed(), tab))
    }

    @Test
    fun typing_in_a_line_rewrites_that_row_and_enter_adds_one() {
        val text = (1..200).joinToString("\n") { "line $it" }
        val (entries, tab) = tabWith(text)
        assertEquals(201, entries.size)

        val typed = LifePlanDomain.withText(entries, tab, text.replace("line 100\n", "line 100!\n"), random)
        assertEquals(listOf("line 100!"), touched(entries, typed).map { it.text })
        assertEquals(entries.size, typed.size)

        val split = LifePlanDomain.withText(entries, tab, text.replace("line 100\n", "line\n100\n"), random)
        assertEquals(setOf("line", "100"), touched(entries, split).map { it.text }.toSet())
        assertEquals(text.replace("line 100\n", "line\n100\n"), LifePlanDomain.textOf(split, tab))

        val joined = LifePlanDomain.withText(entries, tab, text.replace("line 100\nline 101\n", ""), random)
        assertEquals(entries.size - 2, joined.size)
        assertEquals(text.replace("line 100\nline 101\n", ""), LifePlanDomain.textOf(joined, tab))
    }

    @Test
    fun any_edit_of_the_text_reads_back_exactly() {
        val samples = listOf("", "a", "a\n", "\n", "\n\n", "a\n\nb", "b\na", "a\nb\nc\na\nb", "a\nb", "c")
        for (from in samples) for (to in samples) {
            val (entries, tab) = tabWith(from)
            assertEquals(to, LifePlanDomain.textOf(LifePlanDomain.withText(entries, tab, to, random), tab), "$from -> $to")
        }
        val (entries, tab) = tabWith("a")
        assertEquals("b\nc", LifePlanDomain.textOf(LifePlanDomain.withText(entries, tab, "b\r\nc", random), tab))
    }

    @Test
    fun lines_put_again_and_again_at_one_place_keep_their_order() {
        var (entries, tab) = tabWith("first\nlast")
        var text = "first\nlast"
        // Always just after the first line: each new rank halves one gap.
        for (i in 1..120) {
            text = text.replaceFirst("\n", "\nnew $i\n")
            entries = LifePlanDomain.withText(entries, tab, text, random)
            assertEquals(text, LifePlanDomain.textOf(entries, tab), "after $i")
        }
    }

    @Test
    fun a_tab_is_added_named_and_deleted_with_what_is_written_in_it() {
        val (entries, first) = tabWith("kept\nwords")
        val (two, second) = LifePlanDomain.addedTab(entries, random)
        assertEquals(listOf("Tab 1", "Tab 2"), LifePlanDomain.tabs(two).map { it.text })
        val named = LifePlanDomain.titled(two, second, "Money")
        assertEquals("Money", LifePlanDomain.tabOf(named, second)?.text)

        val without = LifePlanDomain.removed(named, first)
        assertEquals(listOf(second), LifePlanDomain.tabs(without).map { it.id })
        assertEquals(1, without.size)
        // A window still naming the deleted tab opens the first left, and nothing once there is none.
        assertEquals(second, LifePlanDomain.tabOf(without, first)?.id)
        assertEquals(null, LifePlanDomain.tabOf(emptyList(), first))
    }

    @Test
    fun a_change_is_one_history_unit_undone_and_a_run_of_keys_is_one() {
        val (entries, tab) = tabWith("a\nb")
        var s = r(SchedulerState.empty(), SchedulerIntent.SetLifePlan(entries))
        assertEquals(1, main(s).size)

        s = r(s, SchedulerIntent.SetLifePlan(LifePlanDomain.withText(s.lifePlan, tab, "ax\nb", random), "life-plan/$tab/1"))
        s = r(s, SchedulerIntent.SetLifePlan(LifePlanDomain.withText(s.lifePlan, tab, "axy\nb", random), "life-plan/$tab/1"))
        assertEquals(2, main(s).size)
        // Nothing changed is no unit.
        assertEquals(s, r(s, SchedulerIntent.SetLifePlan(s.lifePlan)))

        val unit = main(s).last().delta as LifePlanDelta
        assertEquals(1, unit.changes.touched.size, "a unit of typing holds the line typed in, never the tab")

        val undone = r(s, SchedulerIntent.Undo)
        assertEquals("a\nb", LifePlanDomain.textOf(undone.lifePlan, tab))
        assertEquals("axy\nb", LifePlanDomain.textOf(r(undone, SchedulerIntent.Redo).lifePlan, tab))
    }

    @Test
    fun a_tab_deleted_comes_back_whole_with_an_undo() {
        val (entries, tab) = tabWith("a\nb\nc")
        var s = r(SchedulerState.empty(), SchedulerIntent.SetLifePlan(entries))
        s = r(s, SchedulerIntent.SetLifePlan(LifePlanDomain.removed(s.lifePlan, tab)))
        assertTrue(s.lifePlan.isEmpty())
        assertEquals("a\nb\nc", LifePlanDomain.textOf(r(s, SchedulerIntent.Undo).lifePlan, tab))
    }

    @Test
    fun the_plan_survives_the_codec_and_an_older_payload_has_none() {
        val (entries, tab) = tabWith("one\ntwo")
        val (steps, _) = LifePlanDomain.addedStep(entries, null, random)
        val written = SchedulerStateCodec.encode(SchedulerState.empty().copy(lifePlan = steps))
        assertEquals(steps, assertNotNull(SchedulerStateCodec.decode(written)).lifePlan)
        assertEquals("one\ntwo", LifePlanDomain.textOf(SchedulerStateCodec.decode(written)!!.lifePlan, tab))

        val older = SchedulerStateCodec.encode(SchedulerState.empty())
        assertFalse(older.contains("\"lifePlan\":[{"))
        assertEquals(emptyList(), SchedulerStateCodec.decode(older)!!.lifePlan)
        // A history written before this reads too, and a unit of the plan comes back as written.
        var s = r(SchedulerState.empty(), SchedulerIntent.SetLifePlan(entries))
        s = r(s, SchedulerIntent.SetLifePlan(LifePlanDomain.withText(s.lifePlan, tab, "one!\ntwo", random)))
        val back = SchedulerStateCodec.decode(SchedulerStateCodec.encode(s))!!
        assertEquals("one\ntwo", LifePlanDomain.textOf(r(back, SchedulerIntent.Undo).lifePlan, tab))
    }

    @Test
    fun decoding_heals_what_cannot_be_reached() {
        val orphan = LifePlanEntry("x", LifePlanKind.Line, "gone", 1.0, "lost")
        val (entries, _) = tabWith("kept")
        val payload = SchedulerStateCodec.encode(SchedulerState.empty().copy(lifePlan = entries + orphan))
        assertEquals(entries, SchedulerStateCodec.decode(payload)!!.lifePlan)
    }

    @Test
    fun a_line_is_one_server_row() {
        val (entries, _) = tabWith((1..50).joinToString("\n") { "line $it" })
        val rows = EntityRows.split(SchedulerStateCodec.encodeSnapshot(SchedulerState.empty().copy(lifePlan = entries)).statePayload)
        val own = rows.filterKeys { it.kind == "lifePlan" }
        assertEquals(entries.size, own.size)
        assertTrue(own.values.all { it.length < 200 }, "each row is small, however long the tab")
    }

    @Test
    fun two_devices_writing_in_two_lines_of_a_tab_keep_both() {
        val (entries, tab) = tabWith("a\nb\nc")
        val base = SchedulerState.empty().copy(lifePlan = entries)
        val here = base.copy(lifePlan = LifePlanDomain.withText(entries, tab, "a!\nb\nc", random))
        val there = base.copy(lifePlan = LifePlanDomain.withText(entries, tab, "a\nb\nc?", random))
        val merged = merge(base, here, there)
        assertEquals("a!\nb\nc?", LifePlanDomain.textOf(merged.lifePlan, tab))
    }

    @Test
    fun a_line_added_to_a_tab_deleted_elsewhere_goes_with_the_tab() {
        val (entries, tab) = tabWith("a")
        val base = SchedulerState.empty().copy(lifePlan = entries)
        val here = base.copy(lifePlan = LifePlanDomain.withText(entries, tab, "a\nb", random))
        val there = base.copy(lifePlan = LifePlanDomain.removed(entries, tab))
        assertEquals(emptyList(), merge(base, here, there).lifePlan)
    }

    @Test
    fun a_loop_of_parents_is_healed_away() {
        val a = LifePlanEntry("a", LifePlanKind.Step, "b")
        val b = LifePlanEntry("b", LifePlanKind.Step, "a")
        val root = LifePlanEntry("r", LifePlanKind.Step)
        assertEquals(listOf(root), LifePlanDomain.healed(listOf(a, b, root)))
    }

    @Test
    fun how_a_window_is_looked_at_is_kept_by_frame_id() {
        val views = mapOf("LifePlan" to LifePlanView(LifePlanSection.Problems.name, "t", listOf("a")), "LifePlan#2" to LifePlanView())
        assertEquals(views, LifePlanDomain.decodeViews(LifePlanDomain.encodeViews(views)))
        assertEquals(emptyMap(), LifePlanDomain.decodeViews(null))
        assertEquals(emptyMap(), LifePlanDomain.decodeViews("{not json"))
        assertEquals(LifePlanSection.Steps, LifePlanSection.of(null))
        assertEquals(LifePlanSection.Steps, LifePlanSection.of("Retired"))
    }

    @Test
    fun a_field_ignores_its_own_late_echo_and_takes_any_other_text() {
        val live = LiveFieldText("a")
        val told = ArrayList<String>()
        live.typed(TextFieldValue("ab")) { told += it }
        live.typed(TextFieldValue("abc")) { told += it }
        assertEquals(listOf("ab", "abc"), told)
        // The state answers a frame late: the older text is not put back under the hand.
        live.stored("ab")
        assertEquals("abc", live.value.text)
        live.stored("abc")
        assertEquals("abc", live.value.text)
        // An undo is another's text.
        live.stored("a")
        assertEquals("a", live.value.text)
        // A move of the caret tells nothing.
        live.typed(TextFieldValue("a", androidx.compose.ui.text.TextRange(0))) { told += it }
        assertEquals(2, told.size)
    }
}
