package org.example.project

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.example.project.scheduler.domain.SearchDomain
import org.example.project.scheduler.domain.SearchDomain.Kind
import org.example.project.ui.CustomMenuButtons
import org.example.project.ui.MenuControl
import org.example.project.ui.WindowFrameHost
import org.example.project.ui.WindowFrameState

/**
 * User rules 2026-11-01: a new element joins the Search window that asked, showing only its own groups of actions; a window
 * keeps its name through a change of page (anomaly: "Claude quota" became "all quotas"); the three sound switches stand
 * together in the scheduler's menu.
 */
class NewElementJoinsWindowTest {
    private fun timer(id: String) = SearchDomain.ItemResult(Kind.Timer, id, id, "")

    private fun alarm(id: String) = SearchDomain.ItemResult(Kind.Alarm, id, id, "")

    private fun groups(config: SearchDomain.Config, added: List<SearchDomain.Result>) =
        SearchDomain.actionGroups(
            SearchDomain.actionsFor(SearchDomain.addedActions(config.actionQuery, Kind.entries.toSet(), SearchDomain.actionKindsOf(added)), added),
            added,
        )

    @Test
    fun only_the_groups_holding_the_new_element_stay_open() {
        val added = listOf(alarm("a"), timer("t1"), timer("new"))
        val config = SearchDomain.Config(added = added.map(SearchDomain::keyOf))
        val (next, first) = assertNotNull(SearchDomain.withOnlyGroupsOf(config, added, added.last()))
        val all = groups(next, added)
        val open = all.filter { it.id !in next.collapsedActionGroups }
        assertTrue(open.isNotEmpty())
        assertTrue(open.all { group -> group.members.any { SearchDomain.keyOf(it) == "Timer/new" } }, "open: ${open.map { it.id }}")
        assertEquals(all.filter { group -> group.members.any { SearchDomain.keyOf(it) == "Timer/new" } }.map { it.id }, open.map { it.id })
        assertTrue(all.any { it.id in next.collapsedActionGroups }, "the others are retracted")
        // The section starts at the first of them, in the order the groups are listed.
        assertEquals(open.first().id, first)
    }

    @Test
    fun a_group_retracted_by_hand_is_reopened_if_it_holds_the_new_element() {
        val added = listOf(timer("t1"), timer("new"))
        val before = SearchDomain.Config(added = added.map(SearchDomain::keyOf))
        val own = groups(before, added).first { group -> group.members.any { SearchDomain.keyOf(it) == "Timer/new" } }.id
        val (next, _) = assertNotNull(SearchDomain.withOnlyGroupsOf(before.copy(collapsedActionGroups = setOf(own)), added, added.last()))
        assertFalse(own in next.collapsedActionGroups)
    }

    @Test
    fun the_only_element_is_shown() {
        val added = listOf(timer("new"))
        val (next, first) = assertNotNull(SearchDomain.withOnlyGroupsOf(SearchDomain.Config(added = listOf("Timer/new")), added, added.single()))
        assertFalse(first in next.collapsedActionGroups)
    }

    private fun host(vararg ids: String): WindowFrameHost {
        val host = WindowFrameHost()
        for (id in ids) host.register(WindowFrameHost.Registration(id, id, WindowFrameState(id), false, null) {})
        return host
    }

    @Test
    fun a_window_leaving_the_screen_keeps_its_name_and_its_colour() {
        val host = host("Search#2")
        host.tabTitles["Search#2"] = "Claude quota"
        val colour = host.colors["Search#2"]
        // A change of page takes it out of the frames.
        host.unregister("Search#2")
        assertEquals("Claude quota", host.tabTitles["Search#2"])
        host.register(WindowFrameHost.Registration("Search#2", "Search#2", WindowFrameState("Search#2"), false, null) {})
        assertEquals(colour, host.colors["Search#2"], "the one it had, not a new one")
    }

    @Test
    fun a_window_closed_leaves_nothing_for_the_next_to_wear_it() {
        val host = host("Search#2")
        host.tabTitles["Search#2"] = "Claude quota"
        host.tabOrder = listOf("Search#2")
        host.windowClosed("Search#2")
        assertNull(host.tabTitles["Search#2"])
        assertNull(host.colors["Search#2"])
        assertEquals(emptyList(), host.tabOrder)
    }

    private fun positions(list: List<org.example.project.ui.CustomMenuButton>) = list.map { it.id }

    @Test
    fun the_sound_switches_stand_together_in_a_new_menu() {
        assertEquals(
            listOf(MenuControl.MuteAll.name, MenuControl.Voice.name, MenuControl.Notifications.name),
            CustomMenuButtons.DEFAULTS.subList(1, 4).map { it.control },
        )
    }

    @Test
    fun a_menu_stored_before_has_them_brought_together_once() {
        val older = """{"buttons":[{"id":"g-search","window":"Search","title":"Search","global":true},{"id":"g-mute","window":"","title":"","control":"MuteAll","global":true},{"id":"d-task-tree","window":"TaskTree","title":"Task tree"},{"id":"d-Voice","window":"","title":"","control":"Voice"},{"id":"b1","window":"Search","title":"Mine"},{"id":"d-Notifications","window":"","title":"","control":"Notifications"}],"seeded":true,"globalSeeded":true,"muteSeeded":true,"claudeSoundsSeeded":true,"lifePlanSeeded":true}"""
        val read = CustomMenuButtons.decode(older)
        assertEquals(listOf("g-search", "g-mute", "d-Voice", "d-Notifications", "d-task-tree", "b1"), positions(read))
        // Once: moved apart after that, they stay.
        val apart = CustomMenuButtons.moved(read, "d-Voice", null)
        assertEquals(positions(apart), positions(CustomMenuButtons.decode(CustomMenuButtons.encode(apart))))
    }

    @Test
    fun switches_removed_or_in_a_folder_are_the_users() {
        val removed = """{"buttons":[{"id":"g-mute","window":"","title":"","control":"MuteAll","global":true},{"id":"b1","window":"Search","title":"Mine"},{"id":"d-Voice","window":"","title":"","control":"Voice"}],"seeded":true,"globalSeeded":true,"muteSeeded":true,"lifePlanSeeded":true}"""
        assertEquals(listOf("g-mute", "b1", "d-Voice"), positions(CustomMenuButtons.decode(removed)))
        val folder = """{"buttons":[{"id":"g-mute","window":"","title":"","control":"MuteAll","global":true},{"id":"f1","window":"","title":"F","folder":true},{"id":"d-Voice","window":"","title":"","control":"Voice","parent":"f1"},{"id":"d-Notifications","window":"","title":"","control":"Notifications","parent":"f1"}],"seeded":true,"globalSeeded":true,"muteSeeded":true,"lifePlanSeeded":true}"""
        assertEquals(listOf("g-mute", "f1", "d-Voice", "d-Notifications"), positions(CustomMenuButtons.decode(folder)))
    }
}
