package org.example.project

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.datetime.TimeZone
import org.example.project.scheduler.domain.SchedulerDomain
import org.example.project.scheduler.model.TaskTimeRange
import org.example.project.scheduler.persistence.SchedulerStateCodec
import org.example.project.scheduler.state.SchedulerIntent
import org.example.project.scheduler.state.SchedulerReducer
import org.example.project.scheduler.state.SchedulerState
import org.example.project.scheduler.ui.TaskSchedulerViewModel
import org.example.project.time.AppClock

/**
 * The **task tree selector** above the tree: the account's named alternative task trees
 * ([org.example.project.scheduler.state.TaskTreeEntry]), which are *live* — the selected one IS the tree the
 * app shows and edits, and switching away keeps everything done in it.
 *
 * Covers the two menus the field is built from (the identity menu — today's tree, "New task tree", then the
 * similarly-titled trees — and the contains-match title suggestions), the `tree-YYYY-MM-DD` name a first
 * startup gives the tree, the flush-on-switch that makes the trees live rather than frozen, that a task
 * existing only in an inactive tree keeps its completed-work records, Undo/Redo of a switch, and — per the
 * persisted-DB compatibility rule — that a payload written before the selector existed decodes to "no named
 * trees, unnamed live tree" and that the trees round-trip.
 */
class TaskTreeSelectorTest {

    /** 2026-08-09T00:00Z — a fixed day, so the dated default name is assertable. */
    private val DAY_2026_08_09 = 1_786_233_600_000L

    /** A `tree-<today>` title the menu tests can pass in without depending on the machine's date. */
    private val TODAY = "tree-2026-08-09"

    /** A state whose root list's first cell holds a task named [title]. */
    private fun stateWithTask(title: String): SchedulerState {
        val s = SchedulerState.empty()
        val c0 = s.lists[s.rootListId]!!.cellIds[0]
        return SchedulerReducer.reduce(s, SchedulerIntent.SetCellTitle(c0, title))
    }

    private fun titlesOf(state: SchedulerState): List<String> =
        state.lists[state.rootListId]!!.cellIds
            .mapNotNull { state.cells[it]?.taskId }
            .mapNotNull { state.tasks[it]?.title }
            .filter { it.isNotBlank() }

    private fun treeIdOf(state: SchedulerState, title: String) =
        state.taskTrees.first { it.title == title }.id

    // ---- creation ------------------------------------------------------------------------------

    @Test
    fun creating_a_task_tree_captures_the_current_tree_and_selects_it() {
        val s0 = stateWithTask("Alpha")
        val s = SchedulerReducer.reduce(s0, SchedulerIntent.CreateTaskTree("Work"))

        assertEquals(listOf("Work"), s.taskTrees.map { it.title })
        assertEquals(treeIdOf(s, "Work"), s.activeTaskTreeId)
        // The new tree IS what is on screen — creating it changes no cell.
        assertEquals(listOf("Alpha"), titlesOf(s))
        assertEquals(listOf("Alpha"), s.taskTrees[0].tree.tasks.values.map { it.title }.filter { it == "Alpha" })
    }

    @Test
    fun creating_a_tree_with_a_blank_name_does_nothing() {
        val s0 = stateWithTask("Alpha")
        val s = SchedulerReducer.reduce(s0, SchedulerIntent.CreateTaskTree("   "))
        assertEquals(s0.taskTrees, s.taskTrees)
        assertEquals(s0.histories, s.histories)
    }

    // ---- switching -----------------------------------------------------------------------------

    /** "Work" holds Alpha; "Studies" (created from it) is edited to hold Beta instead. */
    private fun twoDivergedTrees(): SchedulerState {
        var s = stateWithTask("Alpha")
        s = SchedulerReducer.reduce(s, SchedulerIntent.CreateTaskTree("Work"))
        s = SchedulerReducer.reduce(s, SchedulerIntent.CreateTaskTree("Studies"))
        val c0 = s.lists[s.rootListId]!!.cellIds[0]
        return SchedulerReducer.reduce(s, SchedulerIntent.SetCellTitle(c0, "Beta"))
    }

    @Test
    fun switching_back_restores_the_tree_that_was_left() {
        val s = twoDivergedTrees()
        assertEquals(listOf("Beta"), titlesOf(s))

        val work = SchedulerReducer.reduce(s, SchedulerIntent.SelectTaskTree(treeIdOf(s, "Work")))
        assertEquals(treeIdOf(s, "Work"), work.activeTaskTreeId)
        assertEquals(listOf("Alpha"), titlesOf(work))

        // …and the tree just left kept its own edit (the trees are live, not frozen backups).
        val studies = SchedulerReducer.reduce(work, SchedulerIntent.SelectTaskTree(treeIdOf(s, "Studies")))
        assertEquals(listOf("Beta"), titlesOf(studies))
    }

    @Test
    fun switching_flushes_the_edits_made_since_the_tree_was_selected() {
        var s = twoDivergedTrees()
        // One more edit in "Studies" that no create/switch has captured yet.
        val c0 = s.lists[s.rootListId]!!.cellIds[0]
        s = SchedulerReducer.reduce(s, SchedulerIntent.SetCellTitle(c0, "Gamma"))

        s = SchedulerReducer.reduce(s, SchedulerIntent.SelectTaskTree(treeIdOf(s, "Work")))
        s = SchedulerReducer.reduce(s, SchedulerIntent.SelectTaskTree(treeIdOf(s, "Studies")))
        assertEquals(listOf("Gamma"), titlesOf(s))
    }

    @Test
    fun selecting_the_active_tree_or_an_unknown_one_is_a_no_op() {
        val s = twoDivergedTrees()
        val same = SchedulerReducer.reduce(s, SchedulerIntent.SelectTaskTree(s.activeTaskTreeId!!))
        assertEquals(s, same)
        val unknown = SchedulerReducer.reduce(
            s,
            SchedulerIntent.SelectTaskTree(org.example.project.scheduler.model.TaskTreeId("tree/nope")),
        )
        assertEquals(s, unknown)
    }

    @Test
    fun a_switch_never_re_mints_an_id_the_other_tree_already_uses() {
        var s = twoDivergedTrees()
        // "Studies" allocated ids past the counter "Work" was captured with.
        val counterBefore = s.nextTaskCounter
        s = SchedulerReducer.reduce(s, SchedulerIntent.SelectTaskTree(treeIdOf(s, "Work")))
        assertTrue(s.nextTaskCounter >= counterBefore, "counters must never walk backwards across a switch")
    }

    @Test
    fun a_task_that_lives_only_in_an_inactive_tree_keeps_its_records() {
        // Undo/Redo strips records from its snapshots (PRD §8); a stored task tree must NOT — it is the
        // only home a record has while its tree is not the active one.
        var s = stateWithTask("Alpha")
        val alpha = s.tasks.keys.first { s.tasks[it]!!.title == "Alpha" }
        s = s.copy(tasks = s.tasks + (alpha to s.tasks[alpha]!!.copy(record = listOf(TaskTimeRange(10L, 20L)))))
        s = SchedulerReducer.reduce(s, SchedulerIntent.CreateTaskTree("Work"))
        s = SchedulerReducer.reduce(s, SchedulerIntent.CreateTaskTree("Studies"))
        // Empty Alpha out of "Studies", so it exists only in "Work".
        val c0 = s.lists[s.rootListId]!!.cellIds[0]
        s = SchedulerReducer.reduce(s, SchedulerIntent.SetCellTitle(c0, "Beta"))

        s = SchedulerReducer.reduce(s, SchedulerIntent.SelectTaskTree(treeIdOf(s, "Work")))
        assertEquals(listOf(TaskTimeRange(10L, 20L)), s.tasks[alpha]!!.record)
    }

    // ---- rename --------------------------------------------------------------------------------

    @Test
    fun renaming_keeps_the_same_tree_and_content() {
        var s = twoDivergedTrees()
        val id = s.activeTaskTreeId!!
        s = SchedulerReducer.reduce(s, SchedulerIntent.RenameTaskTree(id, "School"))
        assertEquals(id, s.activeTaskTreeId)
        assertEquals(listOf("Work", "School"), s.taskTrees.map { it.title })
        assertEquals(listOf("Beta"), titlesOf(s))
    }

    @Test
    fun creating_or_renaming_a_tree_leaves_the_cell_selection_alone() {
        // Neither touches a cell, so nothing the selection names has gone anywhere. (A *switch* does clear
        // it — every id it holds belongs to the tree being left.)
        var s = stateWithTask("Alpha")
        val c0 = s.lists[s.rootListId]!!.cellIds[0]
        s = SchedulerReducer.reduce(
            s,
            SchedulerIntent.ClickCell(c0, ctrl = false, shift = false, visibleOrder = listOf(c0)),
        )
        assertEquals(c0, s.selection.main)

        s = SchedulerReducer.reduce(s, SchedulerIntent.CreateTaskTree("Work"))
        assertEquals(c0, s.selection.main)
        s = SchedulerReducer.reduce(s, SchedulerIntent.RenameTaskTree(s.activeTaskTreeId!!, "Job"))
        assertEquals(c0, s.selection.main)
    }

    @Test
    fun renaming_to_the_same_or_a_blank_name_does_nothing() {
        val s = twoDivergedTrees()
        val id = s.activeTaskTreeId!!
        assertEquals(s, SchedulerReducer.reduce(s, SchedulerIntent.RenameTaskTree(id, "Studies")))
        assertEquals(s, SchedulerReducer.reduce(s, SchedulerIntent.RenameTaskTree(id, "  ")))
    }

    // ---- Undo / Redo ---------------------------------------------------------------------------

    @Test
    fun undo_walks_a_switch_back_and_redo_replays_it() {
        val s = twoDivergedTrees()
        val switched = SchedulerReducer.reduce(s, SchedulerIntent.SelectTaskTree(treeIdOf(s, "Work")))
        assertEquals(listOf("Alpha"), titlesOf(switched))

        val undone = SchedulerReducer.reduce(switched, SchedulerIntent.Undo)
        assertEquals(treeIdOf(s, "Studies"), undone.activeTaskTreeId)
        assertEquals(listOf("Beta"), titlesOf(undone))

        val redone = SchedulerReducer.reduce(undone, SchedulerIntent.Redo)
        assertEquals(treeIdOf(s, "Work"), redone.activeTaskTreeId)
        assertEquals(listOf("Alpha"), titlesOf(redone))
    }

    @Test
    fun undoing_past_a_switch_reaches_the_previous_trees_own_edits_only_after_it_is_restored() {
        // The ordering guarantee that lets one Main history serve every tree: a tree mutation recorded under
        // "Studies" can only be undone once the switch unit has put "Studies" back.
        val s = twoDivergedTrees()
        val switched = SchedulerReducer.reduce(s, SchedulerIntent.SelectTaskTree(treeIdOf(s, "Work")))
        val undoneSwitch = SchedulerReducer.reduce(switched, SchedulerIntent.Undo)
        assertEquals(listOf("Beta"), titlesOf(undoneSwitch))
        // The next undo is the "Beta" rename, applied to the tree it was made in.
        val undoneRename = SchedulerReducer.reduce(undoneSwitch, SchedulerIntent.Undo)
        assertEquals(listOf("Alpha"), titlesOf(undoneRename))
    }

    @Test
    fun undo_of_the_first_creation_leaves_an_unnamed_tree() {
        val s0 = stateWithTask("Alpha")
        val created = SchedulerReducer.reduce(s0, SchedulerIntent.CreateTaskTree("Work"))
        val undone = SchedulerReducer.reduce(created, SchedulerIntent.Undo)
        assertTrue(undone.taskTrees.isEmpty())
        assertNull(undone.activeTaskTreeId)
        assertEquals(listOf("Alpha"), titlesOf(undone))
    }

    // ---- the two menus -------------------------------------------------------------------------

    @Test
    fun the_tree_menu_leads_with_todays_tree_then_new_tree_then_similar_titles() {
        var s = stateWithTask("Alpha")
        s = SchedulerReducer.reduce(s, SchedulerIntent.CreateTaskTree("Work"))
        s = SchedulerReducer.reduce(s, SchedulerIntent.CreateTaskTree("Workshop"))
        s = SchedulerReducer.reduce(s, SchedulerIntent.CreateTaskTree("Studies"))

        // Empty field: today's tree and the creation row still lead, and every tree is listed under them —
        // this menu is how a tree gets opened, so it must be browsable with nothing typed.
        assertEquals(
            listOf(TODAY, "New task tree", "Studies", "Work", "Workshop"),
            SchedulerDomain.taskTreeMenuEntries(s, "", TODAY).map { it.label },
        )
        // A partial draft narrows the tail to the similar titles — unlike the cell's Tasks menu, which needs
        // the exact one — while the two leading rows stay put.
        assertEquals(
            listOf(TODAY, "New task tree", "Work", "Workshop"),
            SchedulerDomain.taskTreeMenuEntries(s, "Wor", TODAY).map { it.label },
        )
        // Most similar first: the exact match outranks the containment one.
        assertEquals(
            listOf(TODAY, "New task tree", "Work", "Workshop"),
            SchedulerDomain.taskTreeMenuEntries(s, "work", TODAY).map { it.label },
        )

        val rows = SchedulerDomain.taskTreeMenuEntries(s, "work", TODAY)
        assertEquals(SchedulerDomain.TaskTreeMenuEntry.Kind.Today, rows[0].kind)
        assertEquals(SchedulerDomain.TaskTreeMenuEntry.Kind.New, rows[1].kind)
        assertEquals(SchedulerDomain.TaskTreeMenuEntry.Kind.Existing, rows[2].kind)
        // No tree is named after today here, so that row creates rather than opens — which is why the rows
        // are told apart by `kind` and never by a null id (the "New task tree" row has one too).
        assertNull(rows[0].id)
        assertNull(rows[1].id)
        assertEquals(treeIdOf(s, "Work"), rows[2].id)
    }

    @Test
    fun todays_tree_is_offered_once_and_as_itself_when_it_already_exists() {
        // "Always show tree-<today>" must not become a second row for a tree that is already there, nor an
        // offer to create a duplicate: the row carries that tree's own id and drops out of the tail.
        var s = stateWithTask("Alpha")
        s = SchedulerReducer.reduce(s, SchedulerIntent.CreateTaskTree(TODAY))
        s = SchedulerReducer.reduce(s, SchedulerIntent.CreateTaskTree("Work"))

        val rows = SchedulerDomain.taskTreeMenuEntries(s, "", TODAY)
        assertEquals(listOf(TODAY, "New task tree", "Work"), rows.map { it.label })
        assertEquals(treeIdOf(s, TODAY), rows[0].id)
        assertEquals(SchedulerDomain.TaskTreeMenuEntry.Kind.Today, rows[0].kind)
        // …and it still leads even when the typed text could not match it.
        assertEquals(TODAY, SchedulerDomain.taskTreeMenuEntries(s, "zzz", TODAY).first().label)
    }

    @Test
    fun the_default_tree_name_is_the_local_day() {
        val utc = TimeZone.of("UTC")
        assertEquals("tree-1970-01-01", SchedulerDomain.defaultTaskTreeTitle(0L, utc))
        assertEquals("tree-2026-08-09", SchedulerDomain.defaultTaskTreeTitle(DAY_2026_08_09, utc))
        // Local, not UTC: an instant just before midnight UTC is already the next day further east.
        assertEquals(
            "tree-2026-08-10",
            SchedulerDomain.defaultTaskTreeTitle(DAY_2026_08_09 + 23 * 3_600_000L, TimeZone.of("Europe/Paris")),
        )
    }

    // ---- first startup -------------------------------------------------------------------------

    @Test
    fun first_startup_names_the_tree_after_today() {
        val controllable = object : AppClock {
            override fun nowMillis(): Long = DAY_2026_08_09
        }
        val previous = SchedulerReducer.clock
        SchedulerReducer.clock = controllable
        try {
            val s0 = stateWithTask("Alpha")
            val loaded = TaskSchedulerViewModel.loadInitialState(store = null, initial = s0)
            val expected = SchedulerDomain.defaultTaskTreeTitle(DAY_2026_08_09)
            assertEquals(listOf(expected), loaded.taskTrees.map { it.title })
            assertEquals(loaded.taskTrees[0].id, loaded.activeTaskTreeId)
            // The entry holds the tree as loaded, so switching away and back is not a way to lose it…
            assertEquals(listOf("Alpha"), titlesOf(loaded))
            assertTrue(loaded.taskTrees[0].tree.tasks.values.any { it.title == "Alpha" })
            // …and it is a default, not a user action: it records no History Unit, so Ctrl+Z cannot land on
            // it. The menu's own first row is then this very tree rather than an offer to create it.
            assertEquals(s0.histories.main.units.size, loaded.histories.main.units.size)
            assertEquals(
                loaded.activeTaskTreeId,
                SchedulerDomain.taskTreeMenuEntries(loaded, "", expected).first().id,
            )
        } finally {
            SchedulerReducer.clock = previous
        }
    }

    @Test
    fun an_account_that_already_names_its_tree_is_left_alone_at_startup() {
        // The seed is for a first startup only — a reload must never mint a second, differently-dated tree
        // (nor re-point the account at one) just because the day moved on.
        val s = twoDivergedTrees()
        val loaded = TaskSchedulerViewModel.loadInitialState(store = null, initial = s)
        assertEquals(listOf("Work", "Studies"), loaded.taskTrees.map { it.title })
        assertEquals(s.activeTaskTreeId, loaded.activeTaskTreeId)
    }

    @Test
    fun title_suggestions_match_on_containment_and_list_everything_when_empty() {
        var s = stateWithTask("Alpha")
        s = SchedulerReducer.reduce(s, SchedulerIntent.CreateTaskTree("Work"))
        s = SchedulerReducer.reduce(s, SchedulerIntent.CreateTaskTree("Workshop"))
        s = SchedulerReducer.reduce(s, SchedulerIntent.CreateTaskTree("Studies"))

        assertEquals(
            listOf("Studies", "Work", "Workshop"),
            SchedulerDomain.taskTreeTitleSuggestions(s, "").sorted(),
        )
        assertEquals(listOf("Work", "Workshop"), SchedulerDomain.taskTreeTitleSuggestions(s, "wor"))
        // The exact match sorts first (similarity), which is what the field's Enter would commit to.
        assertEquals("Work", SchedulerDomain.taskTreeTitleSuggestions(s, "Work").first())
        assertEquals(treeIdOf(s, "Work"), SchedulerDomain.taskTreeIdForTitle(s, " work "))
        assertNull(SchedulerDomain.taskTreeIdForTitle(s, "wor"))
    }

    // ---- persistence ---------------------------------------------------------------------------

    @Test
    fun codec_round_trips_the_trees_and_the_selection() {
        val s = twoDivergedTrees()
        val decoded = SchedulerStateCodec.decode(SchedulerStateCodec.encode(s))
        assertNotNull(decoded)
        assertEquals(listOf("Work", "Studies"), decoded.taskTrees.map { it.title })
        assertEquals(s.activeTaskTreeId, decoded.activeTaskTreeId)
        assertEquals(s.nextTaskTreeCounter, decoded.nextTaskTreeCounter)
        // The stored tree survives whole, so switching after a reload still restores it.
        val work = SchedulerReducer.reduce(decoded, SchedulerIntent.SelectTaskTree(treeIdOf(decoded, "Work")))
        assertEquals(listOf("Alpha"), titlesOf(work))
    }

    @Test
    fun a_switch_survives_the_snapshot_round_trip_used_by_the_store_and_sync() {
        val s = twoDivergedTrees()
        val decoded = SchedulerStateCodec.decodeSnapshot(SchedulerStateCodec.encodeSnapshot(s))
        assertNotNull(decoded)
        assertEquals(listOf("Work", "Studies"), decoded.taskTrees.map { it.title })
        // The task-tree History Unit round-trips too, so Undo still walks the switch back after a reload.
        val undone = SchedulerReducer.reduce(decoded, SchedulerIntent.Undo)
        assertEquals(listOf("Alpha"), titlesOf(undone))
    }

    @Test
    fun codec_decodes_a_payload_written_before_the_selector_existed() {
        // Persisted-DB compatibility: no taskTrees / activeTaskTreeId / nextTaskTreeCounter keys. It must
        // load as "no named trees, unnamed live tree" — the state a fresh account is already in.
        val json =
            """
            {"rootListId":"L","lists":[{"id":"L","parentCellId":null,"cellIds":["c0"]}],
             "cells":[{"id":"c0","parentListId":"L","taskId":"t0"}],
             "tasks":[{"id":"t0","title":"Alpha","occurrences":["c0"]}]}
            """.trimIndent()
        val decoded = SchedulerStateCodec.decode(json)
        assertNotNull(decoded)
        assertTrue(decoded.taskTrees.isEmpty())
        assertNull(decoded.activeTaskTreeId)
        assertEquals(0, decoded.nextTaskTreeCounter)
        // …and naming it from there works, capturing what was loaded.
        val named = SchedulerReducer.reduce(decoded, SchedulerIntent.CreateTaskTree("Work"))
        assertEquals(listOf("Work"), named.taskTrees.map { it.title })
        assertEquals(listOf("Alpha"), titlesOf(named))
    }

    @Test
    fun the_trees_are_authoritative_so_they_move_the_sync_fingerprint() {
        val s0 = stateWithTask("Alpha")
        val s = SchedulerReducer.reduce(s0, SchedulerIntent.CreateTaskTree("Work"))
        assertTrue(
            SchedulerStateCodec.syncFingerprint(s0) != SchedulerStateCodec.syncFingerprint(s),
            "creating a task tree is authoritative user data and must push",
        )
    }
}
