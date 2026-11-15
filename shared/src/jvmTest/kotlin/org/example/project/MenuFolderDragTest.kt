package org.example.project

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerButtons
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerKeyboardModifiers
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import org.example.project.ui.GivenRow
import org.example.project.ui.LATERAL_MENU_SEARCH
import org.example.project.ui.LateralMenu
import org.example.project.ui.MenuItemRows

/**
 * The left-side menu's items are rows of the Search window it is (anomaly 2026-11-14: *"I do shift+click to select
 * several rows, but it doesn't work. ctrl+click doesn't work either"* — the menu drew a list of its own, with no
 * selection). On real pointer events: with "Arrange by dragging" on, a press activates nothing, Ctrl/Shift select, and
 * a held press drags the selected rows — over the middle of another row it groups (user rule 2026-11-01), between two it
 * moves them. Off, a press activates the item and a Ctrl-press selects it without activating it.
 */
@OptIn(InternalComposeUiApi::class, ExperimentalComposeUiApi::class)
class MenuFolderDragTest {
    private class Probe {
        val middles = HashMap<String, Offset>()
        var grouped: Pair<List<String>, String>? = null
        var moved: Pair<List<String>, String?>? = null
        val clicked = ArrayList<String>()
    }

    private val keys = listOf("a", "b", "c")

    private fun scene(probe: Probe, arranging: Boolean) =
        ImageComposeScene(width = 300, height = 600, density = Density(1f)) {
            MaterialTheme {
                LateralMenu(
                    page = OmniPage.TaskScheduler,
                    onPageSelected = {},
                    config = LATERAL_MENU_SEARCH.copy(menuCustomizing = arranging),
                    items = { _ ->
                        MenuItemRows(
                            rows = keys.map { GivenRow(it, "Button $it") },
                            row = { key, _ ->
                                Box(
                                    Modifier.fillMaxWidth().height(40.dp)
                                        .onGloballyPositioned { probe.middles[key] = it.boundsInRoot().center }
                                        .clickable { probe.clicked += key },
                                ) { Text("Button $key") }
                            },
                            onActivate = { probe.clicked += it },
                            onArrange = { ids, before, _ -> probe.moved = ids to before },
                            onGroup = { ids, onto -> probe.grouped = ids to onto },
                        )
                    },
                )
            }
        }

    private var time = 0L

    /** On the Swing thread, as the app's scenes are (the Search window's menus and windows ask AWT). */

    private fun frames(scene: ImageComposeScene) = onEdt { repeat(4) { time += 16_000_000L; scene.render(time) } }

    private fun send(scene: ImageComposeScene, type: PointerEventType, at: Offset, buttons: PointerButtons? = null, mods: PointerKeyboardModifiers? = null) =
        onEdt {
            scene.sendPointerEvent(type, at, buttons = buttons, keyboardModifiers = mods, button = if (type == PointerEventType.Move) null else PointerButton.Primary)
        }

    private fun click(scene: ImageComposeScene, at: Offset, ctrl: Boolean = false, shift: Boolean = false) {
        val mods = PointerKeyboardModifiers(isCtrlPressed = ctrl, isShiftPressed = shift)
        send(scene, PointerEventType.Move, at, mods = mods)
        send(scene, PointerEventType.Press, at, PointerButtons(isPrimaryPressed = true), mods)
        send(scene, PointerEventType.Release, at, PointerButtons(), mods)
        frames(scene)
    }

    private fun drag(scene: ImageComposeScene, from: Offset, by: Float) {
        val primary = PointerButtons(isPrimaryPressed = true)
        send(scene, PointerEventType.Move, from)
        send(scene, PointerEventType.Press, from, primary)
        frames(scene)
        val steps = 6
        for (i in 1..steps) {
            send(scene, PointerEventType.Move, from + Offset(0f, by * i / steps), primary)
            frames(scene)
        }
        send(scene, PointerEventType.Release, from + Offset(0f, by), PointerButtons())
        frames(scene)
    }

    private fun <T> run(arranging: Boolean, block: (ImageComposeScene, Probe) -> T): T {
        val probe = Probe()
        val scene = onEdt { scene(probe, arranging) }
        try {
            frames(scene)
            return block(scene, probe)
        } finally {
            onEdt { scene.close() }
        }
    }

    private fun Probe.pitch() = middles.getValue("b").y - middles.getValue("a").y

    @Test
    fun dragged_over_the_middle_of_another_it_groups() = run(arranging = true) { scene, probe ->
        drag(scene, probe.middles.getValue("a"), probe.pitch())
        assertEquals(listOf("a") to "b", probe.grouped)
        assertNull(probe.moved)
    }

    @Test
    fun dragged_between_two_it_moves() = run(arranging = true) { scene, probe ->
        drag(scene, probe.middles.getValue("a"), probe.pitch() * 1.5f)
        assertEquals(listOf("a") to "c", probe.moved)
        assertNull(probe.grouped)
    }

    @Test
    fun ctrl_and_shift_select_several_and_a_drag_carries_them_all() = run(arranging = true) { scene, probe ->
        click(scene, probe.middles.getValue("a"))
        click(scene, probe.middles.getValue("b"), shift = true)
        // Past the last row: at the end, in the list's order.
        drag(scene, probe.middles.getValue("a"), probe.pitch() * 3.4f)
        assertEquals(listOf("a", "b") to null, probe.moved, "grouped: ${probe.grouped}, clicked: ${probe.clicked}")
        assertEquals(emptyList(), probe.clicked, "arranged, a press activates nothing")
    }

    @Test
    fun ctrl_picks_rows_apart() = run(arranging = true) { scene, probe ->
        click(scene, probe.middles.getValue("a"))
        click(scene, probe.middles.getValue("c"), ctrl = true)
        // Above the middle of b, short of its middle half: before it.
        drag(scene, probe.middles.getValue("c"), -probe.pitch() * 1.3f)
        assertEquals(listOf("a", "c") to "b", probe.moved)
    }

    @Test
    fun not_arranged_a_press_activates_and_a_ctrl_press_selects_without_activating() = run(arranging = false) { scene, probe ->
        click(scene, probe.middles.getValue("b"))
        assertEquals(listOf("b"), probe.clicked)
        click(scene, probe.middles.getValue("c"), ctrl = true)
        assertEquals(listOf("b"), probe.clicked, "a Ctrl-press selects")
        drag(scene, probe.middles.getValue("a"), probe.pitch())
        assertNull(probe.moved, "nothing is dragged while not arranged")
        assertNull(probe.grouped)
    }
}
