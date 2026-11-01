package org.example.project

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerButtons
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.unit.Density
import kotlin.test.Test
import kotlin.test.assertEquals
import org.example.project.ui.WindowBar
import org.example.project.ui.WindowFrameHost
import org.example.project.ui.WindowFrameState
import org.example.project.ui.WindowTabGroups

/**
 * User rule 2026-11-01: *"In the system tray, the user can drag any tab to reorganize. If it is linked to a tab, or tabs are
 * linked to it, they all get dragged as a group."* The rules, then the bar on real pointer events.
 */
@OptIn(InternalComposeUiApi::class, ExperimentalComposeUiApi::class)
class WindowBarDragTest {
    private val links = mapOf("Conf" to "Search", "Deep" to "Conf")

    private fun layout(opened: List<String>, place: List<String> = emptyList()) =
        WindowTabGroups.layout(opened, place) { links[it] }

    private val opened = listOf("A", "Search", "Conf", "Deep", "B")

    @Test
    fun a_tab_goes_with_all_it_is_linked_to_up_and_down() {
        val bar = layout(opened)
        assertEquals(listOf(listOf("A"), listOf("Search", "Conf", "Deep"), listOf("B")), bar.groups)
        for (id in listOf("Search", "Conf", "Deep")) {
            assertEquals(listOf("Search", "Conf", "Deep", "A", "B"), WindowTabGroups.moved(bar, id, "A"), "dragged by $id")
            assertEquals(listOf("A", "B", "Search", "Conf", "Deep"), WindowTabGroups.moved(bar, id, null), "dragged by $id")
        }
    }

    @Test
    fun a_lone_tab_never_lands_inside_a_group() {
        val bar = layout(opened)
        assertEquals(listOf("Search", "Conf", "Deep", "A", "B"), WindowTabGroups.moved(bar, "A", "B"))
        // Asked to stand before the middle of a group, it stands before the group.
        assertEquals(listOf("A", "Search", "Conf", "Deep", "B"), WindowTabGroups.moved(bar, "A", "Conf"))
        assertEquals(bar.order, WindowTabGroups.moved(bar, "Conf", "Deep"), "within its own group: nothing")
    }

    @Test
    fun the_order_dragged_is_read_first_and_a_new_window_stands_at_the_end() {
        val place = listOf("B", "A", "Search", "Conf", "Deep")
        assertEquals(place, layout(opened, place).order)
        assertEquals(place + "New", layout(listOf("New") + opened, place).order)
        // A window of the order that is not open takes no place.
        assertEquals(listOf("A", "B"), WindowTabGroups.layout(listOf("A", "B"), listOf("Gone", "A", "B")) { null }.order)
        // A linked tab stands by its parent whatever the order says of it: the parent's place decides.
        assertEquals(listOf("A", "B", "Search", "Conf", "Deep"), layout(opened, listOf("Deep", "A", "B", "Search", "Conf")).order)
    }

    @Test
    fun a_group_lands_before_the_first_group_whose_middle_is_past_its_own() {
        val others = listOf("a" to 100f, "b" to 300f)
        assertEquals("a", WindowTabGroups.dropBefore(others, 50f))
        assertEquals("b", WindowTabGroups.dropBefore(others, 200f))
        assertEquals(null, WindowTabGroups.dropBefore(others, 400f))
    }

    private fun host(vararg ids: String): WindowFrameHost {
        val host = WindowFrameHost()
        for (id in ids) host.register(WindowFrameHost.Registration(id, id, WindowFrameState(id), false, null) {})
        host.tabParentOf = { links[it] }
        return host
    }

    private fun drag(host: WindowFrameHost, from: Offset, by: Float, steps: Int = 8) {
        val scene =
            ImageComposeScene(width = 900, height = 100, density = Density(1f)) {
                MaterialTheme { WindowBar(host) }
            }
        try {
            var time = 0L
            fun frames() = repeat(4) { time += 16_000_000L; scene.render(time) }
            frames()
            val primary = PointerButtons(isPrimaryPressed = true)
            scene.sendPointerEvent(PointerEventType.Move, from, type = PointerType.Mouse)
            scene.sendPointerEvent(PointerEventType.Press, from, buttons = primary, button = PointerButton.Primary, type = PointerType.Mouse)
            frames()
            for (i in 1..steps) {
                scene.sendPointerEvent(PointerEventType.Move, from + Offset(by * i / steps, 0f), buttons = primary, type = PointerType.Mouse)
                frames()
            }
            scene.sendPointerEvent(PointerEventType.Release, from + Offset(by, 0f), buttons = PointerButtons(), button = PointerButton.Primary, type = PointerType.Mouse)
            frames()
        } finally {
            scene.close()
        }
    }

    @Test
    fun a_mouse_drag_moves_the_group_and_is_no_click() {
        val host = host("A", "Search", "Conf", "B")
        var told: Pair<List<String>, List<String>>? = null
        host.onTabOrderChanged = { before, after -> told = before to after }
        // The first tab, on its name (its right end is its ✕), to the far right: past every other group.
        drag(host, Offset(12f, 20f), 800f)
        assertEquals(listOf("Search", "Conf", "B", "A"), host.tabLayout.order)
        assertEquals(emptyList<String>() to listOf("Search", "Conf", "B", "A"), told)
        assertEquals(null, host.focusedId, "a drag is no click: the tab took no focus")
        assertEquals(emptySet(), host.selectedTabs)
    }

    @Test
    fun a_press_that_does_not_move_is_still_a_click() {
        val host = host("A", "B")
        drag(host, Offset(12f, 20f), 0f, steps = 1)
        assertEquals(setOf("A"), host.selectedTabs)
        assertEquals(listOf("A", "B"), host.tabLayout.order)
    }

    @Test
    fun ctrl_tab_walks_the_order_drawn_and_a_closed_window_leaves_the_order() {
        val host = host("A", "B", "C")
        host.tabOrder = listOf("C", "A", "B")
        host.focus("C")
        host.walkTabsInBarOrder(+1)
        assertEquals("A", host.focusedId)
        host.windowClosed("A")
        assertEquals(listOf("C", "B"), host.tabOrder)
    }
}
