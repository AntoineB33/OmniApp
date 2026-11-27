package org.example.project

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.example.project.scheduler.domain.ContextDefault
import org.example.project.scheduler.domain.ContextDomain
import org.example.project.scheduler.domain.SearchDomain
import org.example.project.scheduler.domain.SearchDomain.Setting
import org.example.project.scheduler.persistence.SchedulerStateCodec
import org.example.project.scheduler.state.SchedulerIntent
import org.example.project.scheduler.state.SchedulerReducer
import org.example.project.scheduler.state.SchedulerState

/**
 * User rule 2026-11-25: *"In the action section of a search configuration element, there is a section to show its state
 * for each context."* A search configuration off in every context shows ONE group (its context row, its state "off"); on
 * only for one context, TWO — that context, on; every other, off. The contexts' defaults are the code's until the user
 * changes them, and what they change is synced ([ContextDefault]).
 */
class ContextDefaultsTest {
    private val calendar = "menu:calendar grid menu"

    @Test
    fun off_everywhere_is_one_group_on_for_one_context_is_two() {
        // A list of parents and an added elements section have a drop button by default: "Arrange by dragging" is off everywhere.
        val off = ContextDomain.groups(emptyList(), Setting.MenuCustomizing)
        assertEquals(1, off.size)
        assertFalse(off.single().on)
        assertEquals(ContextDomain.CONTEXTS, off.single().contexts)

        val on = ContextDomain.written(emptyList(), calendar, ContextDomain.configOf(emptyList(), calendar).copy(menuCustomizing = true))
        val groups = ContextDomain.groups(on, Setting.MenuCustomizing)
        assertEquals(listOf(true, false), groups.map { it.on })
        assertEquals(listOf(calendar), groups[0].contexts.map { it.id })
        assertEquals(ContextDomain.CONTEXTS.size - 1, groups[1].contexts.size)
        // A context row's window: those contexts, added.
        assertEquals(listOf(ContextDomain.keyOf(calendar)), SearchDomain.contextsSearchConfig(listOf(calendar)).added)
        assertEquals(setOf(SearchDomain.Kind.Context), SearchDomain.contextsSearchConfig(listOf(calendar)).kinds)
    }

    @Test
    fun a_change_made_on_one_context_is_made_on_another_its_other_settings_kept() {
        val before = SearchDomain.OPTION_SEARCH
        val after = before.copy(dropButtons = true)
        val other = SearchDomain.Config(query = "kept", separators = false, menuCustomizing = true)
        val changed = ContextDomain.withChange(other, before, after)
        assertTrue(changed.dropButtons, "the change")
        assertEquals("kept", changed.query)
        assertTrue(changed.menuCustomizing, "its own settings kept")
        assertFalse(changed.separators, "what did not change is not copied")
    }

    @Test
    fun nothing_is_stored_that_the_code_says_already() {
        val default = ContextDomain.configOf(emptyList(), calendar)
        val on = ContextDomain.written(emptyList(), calendar, default.copy(dropButtons = true))
        assertEquals(1, on.size)
        assertTrue(ContextDomain.configOf(on, calendar).dropButtons)
        assertEquals(emptyList(), ContextDomain.written(on, calendar, default), "back to the code's default: the row goes")
        // A menu not in the catalog opens as a drop-down.
        assertEquals(ContextDomain.DROP_DOWN, ContextDomain.menuContextId("no such menu", rightClick = true))
        assertEquals(ContextDomain.DROP_DOWN, ContextDomain.menuContextId("calendar grid menu", rightClick = false))
        assertEquals(calendar, ContextDomain.menuContextId("calendar grid menu", rightClick = true))
    }

    @Test
    fun the_defaults_are_one_undoable_unit_persisted_and_absent_from_an_older_payload() {
        val state = SchedulerState.empty()
        val rows = ContextDomain.written(emptyList(), calendar, ContextDomain.configOf(emptyList(), calendar).copy(dropButtons = true))
        val set = SchedulerReducer.reduce(state, SchedulerIntent.SetContextDefaults(rows))
        assertEquals(rows, set.contextDefaults)
        assertEquals(emptyList(), SchedulerReducer.reduce(set, SchedulerIntent.Undo).contextDefaults, "one History Unit")
        // Persisted and read back.
        val decoded = SchedulerStateCodec.decode(SchedulerStateCodec.encode(set))!!
        assertEquals(rows, decoded.contextDefaults)
        // A payload written before 2026-11-25 has no such field: it decodes, every context on the code's default.
        val older = SchedulerStateCodec.encode(state).replace(Regex(",?\"contextDefaults\":\\[\\]"), "")
        assertFalse(older.contains("contextDefaults"))
        assertEquals(emptyList(), SchedulerStateCodec.decode(older)!!.contextDefaults)
        // A row that names nothing is healed away.
        val broken = SchedulerStateCodec.encode(set).replace(Regex("\"id\":\"" + Regex.escape(calendar) + "\""), "\"id\":\"\"")
        assertEquals(emptyList(), SchedulerStateCodec.decode(broken)!!.contextDefaults)
    }

    @Test
    fun the_contexts_are_elements_of_the_context_kind() {
        val rows = SearchDomain.resolve(SchedulerState.empty(), listOf(ContextDomain.keyOf(calendar)))
        assertEquals(1, rows.size)
        assertEquals("default of the right-click menu of the calendar", rows.single().name)
    }

    /**
     * User rule 2026-11-25: *"for the right-click menu of a task element, there would be a search configuration that is on
     * in this context and that says put 'add', then 'add and remove', then…"* — "Entries, in order", on in every row menu's
     * context, names every entry the row menu can give, by the id the code gives it.
     */
    @Test
    fun the_row_menus_list_their_entries_by_one_search_configuration() {
        val task = ContextDomain.configOf(emptyList(), "menu:task cell menu")
        assertEquals(ContextDomain.ROW_MENU_ENTRIES, task.entries)
        assertTrue(Setting.Entries in SearchDomain.activeSettings(task), "on in that context")
        val full = org.example.project.scheduler.ui.TaskCellMenuActions({}, {}, {}, {}, {}, {}, onAdd = {}, onAddReplacing = {})
        val ids = org.example.project.scheduler.ui.taskCellMenuActions(full).map { it.id }
        assertEquals(emptyList(), ids - ContextDomain.ROW_MENU_ENTRIES.toSet(), "every entry is named")
        assertEquals(ids, ContextDomain.ROW_MENU_ENTRIES.filter { it in ids }, "in the builder's order")
        // User rule 2026-11-25 ("the same everywhere"): every right-click menu sets its own.
        assertTrue(ContextDomain.configOf(emptyList(), "menu:SearchPartsMenu").entries.isNotEmpty())
        // Kept and read back; an older payload has none.
        val on = SearchDomain.Config(entries = listOf("add", "copy path"))
        assertEquals(on, SearchDomain.Config.decode(on.encode()))
        assertEquals(emptyList(), SearchDomain.Config.decode(SearchDomain.Config().encode())!!.entries)
    }

    @Test
    fun an_entrys_default_presences_and_putting_it_in_or_out() {
        val presences = ContextDomain.presences(emptyList(), "add")
        assertEquals(
            ContextDomain.MENU_ENTRIES.filterValues { "add" in it }.keys.map { "menu:$it" }.toSet(),
            presences.listed.map { it.id }.toSet(),
        )
        assertTrue(presences.unlisted.any { it.id == "menu:SearchPartsMenu" }, "a menu whose entries leave it out")
        assertTrue(presences.unset.none { it.id.startsWith("menu:") }, "every menu sets its entries")
        val out = ContextDomain.withEntry(emptyList(), listOf("menu:task cell menu"), "add", on = false)
        assertFalse("add" in ContextDomain.configOf(out, "menu:task cell menu").entries)
        assertTrue("menu:task cell menu" in ContextDomain.presences(out, "add").unlisted.map { it.id })
        val back = ContextDomain.withEntry(out, listOf("menu:task cell menu"), "add", on = true)
        assertEquals("add", ContextDomain.configOf(back, "menu:task cell menu").entries.last(), "put back at the end")
        // The action is offered for an added entry.
        val added = listOf(SearchDomain.ItemResult(SearchDomain.Kind.Option, "add", "add", ""))
        val actions = SearchDomain.actionsFor(SearchDomain.addedActions("", SearchDomain.Kind.entries.toSet(), SearchDomain.actionKindsOf(added)), added)
        assertTrue(actions.any { (_, list) -> SearchDomain.AddedAction.OptionPresences in list }, "$actions")
    }
}
