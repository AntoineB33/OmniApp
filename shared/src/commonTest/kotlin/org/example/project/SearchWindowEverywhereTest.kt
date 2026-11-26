package org.example.project

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.example.project.scheduler.domain.ElementTree
import org.example.project.scheduler.domain.MdDomain
import org.example.project.scheduler.domain.MdElement
import org.example.project.scheduler.domain.SearchDomain
import org.example.project.scheduler.domain.SearchDomain.AddedAction
import org.example.project.scheduler.domain.SearchDomain.Kind
import org.example.project.scheduler.model.TaskId

/**
 * User rule 2026-11-25: *"Transform the deep copy window into a search window where every action is a row. Generalize this
 * fix so that the search window is the only window of the app."* — the deep copy's controls are rows of a "Deep copy"
 * group; a row of an actions section can be pinned, and is opened and scrolled to whenever the added elements change; the
 * `[[` of an md text offers the other md elements, its creation row first; a pop-up's creation row is at the top.
 */
class SearchWindowEverywhereTest {
    private fun task(id: String) = SearchDomain.TaskResult(TaskId(id), id, emptyList(), inTaskTree = true)
    private fun groupsOf(added: List<SearchDomain.Result>, config: SearchDomain.Config = SearchDomain.Config()) =
        SearchDomain.shownActionGroups(
            SearchDomain.actionsFor(SearchDomain.addedActions("", Kind.entries.toSet(), SearchDomain.actionKindsOf(added)), added),
            added,
            config,
        )

    private fun allIds(groups: List<SearchDomain.ActionGroup>): List<String> {
        val out = ArrayList<String>()
        fun walk(group: SearchDomain.ActionGroup) {
            out += group.id
            group.actions.forEach { out += SearchDomain.microGroupId(group, it) }
            group.inner.forEach(::walk)
        }
        groups.forEach(::walk)
        return out
    }

    @Test
    fun the_deep_copy_window_is_a_group_of_rows() {
        val added = listOf(task("a"))
        val groups = groupsOf(added)
        val deep = groups.flatMap { it.inner }.single { it.tag == SearchDomain.DEEP_COPY_GROUP }
        assertEquals(SearchDomain.DEEP_COPY_TITLE, deep.title)
        // Every control of the window is a row of its own, the copy among them, and none stands outside the group.
        assertEquals(SearchDomain.DEEP_COPY_ACTIONS, deep.actions)
        assertTrue(groups.none { g -> g.actions.any { it in SearchDomain.DEEP_COPY_ACTIONS } })
        assertEquals(deep.id, SearchDomain.deepCopyGroupId(added))
        // Several tasks: one group, in the widest group, acting on them all.
        val two = listOf(task("a"), task("b"))
        val wide = groupsOf(two).flatMap { it.inner }.filter { it.tag == SearchDomain.DEEP_COPY_GROUP }
        assertEquals(1, wide.size)
        assertEquals(two, wide.single().members)
    }

    @Test
    fun a_pinned_row_and_its_ancestors_are_opened() {
        val added = listOf(task("a"))
        val groups = groupsOf(added)
        val copy = assertNotNull(SearchDomain.actionRowId(added, AddedAction.TaskDeepCopy))
        val config = SearchDomain.withActionRowPinned(SearchDomain.Config(), copy)
        val path = assertNotNull(SearchDomain.actionRowPath(groups, config, copy))
        // The element's group, the Deep copy group, the row.
        assertEquals(3, path.size)
        assertEquals(copy, path.last().first)
        val opened = assertNotNull(SearchDomain.withPinnedActionRowOpen(config, groups))
        val rows = SearchDomain.actionListRows(groups, opened)
        val row = rows.single { it.id == copy }
        assertTrue(row.open)
        assertTrue(rows.filter { it.id in path.map { p -> p.first } }.all { it.open })
        // Opening again changes nothing; a row no longer listed is left alone.
        assertEquals(opened, SearchDomain.withPinnedActionRowOpen(opened, groups))
        assertNull(SearchDomain.withPinnedActionRowOpen(config.copy(pinnedActionRow = "action/nowhere/X"), groups))
        // Pinning another unpins the first: one pinned row per window.
        val other = allIds(groups).first { it != copy && it.startsWith("action/") }
        assertEquals(other, SearchDomain.withActionRowPinned(opened, other).pinnedActionRow)
        assertNull(SearchDomain.withActionRowPinned(opened, null).pinnedActionRow)
    }

    @Test
    fun a_pinned_row_is_reached_when_the_actions_are_not_grouped() {
        val added = listOf(task("a"))
        val config = SearchDomain.Config(actionsGrouped = false)
        val groups = groupsOf(added, config)
        val copy = assertNotNull(SearchDomain.actionRowId(added, AddedAction.TaskDeepCopy))
        val path = assertNotNull(SearchDomain.actionRowPath(groups, config, copy))
        assertEquals(2, path.size)
        assertTrue(path.first().second)
    }

    @Test
    fun the_pinned_row_is_kept_with_the_window_and_an_older_one_has_none() {
        val pinned = SearchDomain.Config(pinnedActionRow = "deepcopy/Task/a")
        assertEquals(pinned, SearchDomain.Config.decode(pinned.encode()))
        assertNull(SearchDomain.Config.decode(SearchDomain.Config().encode())?.pinnedActionRow)
    }

    @Test
    fun an_md_link_being_written_is_found_and_completed() {
        assertNull(MdDomain.openLinkAt("no link", 7))
        val text = "see [[Sp"
        val link = assertNotNull(MdDomain.openLinkAt(text, text.length))
        assertEquals("Sp", link.typed)
        assertEquals(false, link.child)
        assertEquals("see [[Sport]]" to 13, MdDomain.completeLink(text, text.length, link, "Sport"))
        val child = assertNotNull(MdDomain.openLinkAt("[[>", 3))
        assertTrue(child.child)
        assertEquals("", child.typed)
        assertEquals("[[>Sport]]", MdDomain.completeLink("[[>", 3, child, "Sport").first)
        // A closed link, or one across a line, is not being written; a `]]` already after the caret is kept.
        assertNull(MdDomain.openLinkAt("[[Sport]] x", 11))
        assertNull(MdDomain.openLinkAt("[[Sp\nort", 9))
        val open = assertNotNull(MdDomain.openLinkAt("[[Sp]]", 4))
        assertEquals("[[Sport]]", MdDomain.completeLink("[[Sp]]", 4, open, "Sport").first)
        // An md element with no title is linked by its id.
        assertEquals("md-3", MdDomain.linkName(MdElement(id = "md-3")))
    }

    @Test
    fun a_pop_up_s_creation_row_is_at_the_top() {
        val options = listOf("1" to "Alpha", "2" to "Beta")
        val rows = SearchDomain.optionResults(options, "Gamma", checked = null, creates = true)
        assertEquals(SearchDomain.CREATE_OPTION, rows.first().id)
        val checked = SearchDomain.optionResults(options, "a", checked = emptySet(), creates = true)
        assertEquals(SearchDomain.CREATE_OPTION, checked.first().id)
        // A blank child row's pop-up: its parent excluded, the creation row first all the same.
        val child = SearchDomain.withDefaultSort(ElementTree.childSearchConfig(Kind.Task.name + "/a", ""))
        assertEquals(SearchDomain.CREATION_FIRST_SORTS, child.sorts)
        val excluded = SearchDomain.Config(kinds = setOf(Kind.Task)).let {
            it.copy(filters = it.filters.copy(excludedElements = setOf("Task/a")))
        }
        assertEquals(SearchDomain.CREATION_FIRST_SORTS, SearchDomain.defaultSortsOf(excluded))
    }

    /** User rule 2026-11-25: the Task relations window's two buttons are actions over the added pairs. */
    @Test
    fun the_task_relations_window_s_buttons_are_actions() {
        val pair = SearchDomain.ItemResult(Kind.TaskRelation, "a|b", "a in b", "")
        val actions = groupsOf(listOf(pair)).flatMap { it.actions }
        assertTrue(AddedAction.TaskRelationKeep in actions && AddedAction.TaskRelationDrop in actions, actions.toString())
        val key = assertNotNull(SearchDomain.relationKeyOf("a|b"))
        assertEquals("a|b", SearchDomain.relationId(key))
        assertNull(SearchDomain.relationKeyOf("nopair"))
    }

    /** User rule 2026-11-25: the default alarm's window is the Search window of the alarm's creation row, its group pinned. */
    @Test
    fun a_default_configuration_is_its_creation_row_s_group() {
        val creation = SearchDomain.ItemResult(Kind.Creation, Kind.Alarm.name, "New alarm", "")
        val id = assertNotNull(SearchDomain.groupIdOf(listOf(creation), SearchDomain.keyOf(creation)))
        val group = groupsOf(listOf(creation)).single { it.id == id }
        assertTrue(group.actions.isNotEmpty() && group.actions.any { it in SearchDomain.DEFAULT_CONFIGURATION_ACTIONS }, group.actions.toString())
    }

    /**
     * Anomaly 2026-11-26 (checked on the desktop): the row pinned in one task's window was not opened once a second task
     * was added — its group became the group of both. The pin follows the row there, under its new id.
     */
    @Test
    fun a_pinned_row_follows_its_action_into_the_group_it_moves_to() {
        val one = listOf(task("a"))
        val ids = assertNotNull(SearchDomain.actionRowId(one, AddedAction.DeepCopyIds))
        val two = listOf(task("a"), task("b"))
        val groups = groupsOf(two)
        val opened = assertNotNull(SearchDomain.withPinnedActionRowOpen(SearchDomain.Config(pinnedActionRow = ids), groups))
        val moved = assertNotNull(opened.pinnedActionRow)
        assertTrue(moved != ids && moved.endsWith("/" + AddedAction.DeepCopyIds.name), moved)
        assertTrue(SearchDomain.actionListRows(groups, opened).single { it.id == moved }.open)
        // A group of elements pinned is about them: it does not move to another.
        assertNull(SearchDomain.followedActionRow(groups, "element/Task/c"))
    }
}
