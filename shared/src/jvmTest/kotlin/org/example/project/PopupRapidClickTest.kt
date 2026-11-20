package org.example.project

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerButtons
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getAllSemanticsNodes
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.datetime.LocalDate
import org.example.project.ui.BUBBLE_BELOW_POINTER
import org.example.project.ui.OpenedBy
import org.example.project.ui.onHoverEnter
import org.example.project.ui.popupOpener
import org.example.project.ui.reportsHover
import org.example.project.ui.DayField
import org.example.project.ui.InfoHint
import org.example.project.ui.LocalTransientMenuHost
import org.example.project.ui.PopupOpener
import org.example.project.ui.PopupOpeners
import org.example.project.ui.TransientMenuHost
import org.example.project.ui.transientMenuDismissRoot

/**
 * Anomaly 2026-11-20: *"Often when I rapidly click on a down arrow, it appears and disappears in a blink. It also happens
 * for rows that show a window when hovering over it."* and *"The bubble info must appear only at the position of the
 * mouse, even if it is moving."* — drawn off screen, rapid press/release pairs sent to the real controls, and the pointer
 * moved over an info bubble's control.
 *
 * A scene has no window of its own, so the platform's report of the keyboard leaving a pop-up (the desktop's menu window
 * losing it to the window the press landed in) is sent as the desktop's menu sends it, through the one funnel
 * ([PopupOpeners.keyboardLeft]) — before the click and after it, the two orders AWT reports it in.
 */
@OptIn(ExperimentalComposeUiApi::class, InternalComposeUiApi::class)
class PopupRapidClickTest {

    private class Harness(content: @androidx.compose.runtime.Composable () -> Unit) {
        val menus = TransientMenuHost()
        val scene = onEdt {
            ImageComposeScene(900, 900, density = Density(1f)) {
                MaterialTheme {
                    CompositionLocalProvider(LocalTransientMenuHost provides menus) {
                        Box(Modifier.fillMaxSize().transientMenuDismissRoot(menus)) { content() }
                    }
                }
            }
        }
        private var time = 0L

        fun frames(n: Int = 6) = repeat(n) { onEdt { time += 16L; scene.render(time * 1_000_000L) } }

        fun nodes(): List<SemanticsNode> = onEdt { scene.semanticsOwners.flatMap { it.getAllSemanticsNodes(mergingEnabled = false) } }

        fun texts(): List<String> = nodes().mapNotNull { n -> n.config.getOrNull(SemanticsProperties.Text)?.joinToString { it.text } }

        fun boundsOf(text: String) =
            nodes().last { n -> n.config.getOrNull(SemanticsProperties.Text)?.any { it.text == text } == true }.boundsInWindow

        fun move(at: Offset) {
            onEdt {
                time += 16L
                scene.sendPointerEvent(PointerEventType.Move, at, timeMillis = time)
            }
            frames(2)
        }

        fun press(at: Offset) = onEdt {
            time += 16L
            scene.sendPointerEvent(PointerEventType.Press, at, timeMillis = time, buttons = PointerButtons(isPrimaryPressed = true), button = PointerButton.Primary)
        }

        fun release(at: Offset) = onEdt {
            time += 16L
            scene.sendPointerEvent(PointerEventType.Release, at, timeMillis = time, button = PointerButton.Primary)
        }

        /** A press and its release, a frame apart: one quick click. */
        fun click(at: Offset) {
            press(at)
            frames(1)
            release(at)
            frames(2)
        }

        fun close() = onEdt { scene.close() }
    }

    private var harness: Harness? = null

    @AfterTest
    fun closeScene() {
        harness?.close()
    }

    /**
     * A drop-down's field clicked rapidly — a double click, a run of clicks — opens its menu and leaves it open: the
     * root observer does not close it on the second press, and the second click is the first one's gesture. A click
     * once the gesture is over closes it, as ever.
     */
    @Test
    fun a_drop_down_clicked_rapidly_stays_open() {
        val h = Harness {
            Box(Modifier.padding(40.dp)) { DayField(date = null, blank = "pick a day", today = LocalDate(2026, 11, 20), enabled = true) {} }
        }.also { harness = it }
        h.frames()
        val face = h.boundsOf("pick a day  ▾").center
        h.move(face)
        val open = { "Mon" in h.texts() }
        assertFalse(open(), "closed before any click")
        repeat(4) { i ->
            h.click(face)
            h.frames(4)
            assertTrue(open(), "click ${i + 1} of a rapid run: the menu is open")
        }
        // The gesture over: the next click closes it.
        Thread.sleep(PopupOpener.SAME_GESTURE.inWholeMilliseconds + 100)
        h.click(face)
        h.frames(4)
        assertFalse(open(), "a click on its field after the gesture closes it")
        // A double click on it closed opens it, and leaves it open.
        Thread.sleep(PopupOpener.SAME_GESTURE.inWholeMilliseconds + 100)
        h.click(face)
        h.click(face)
        h.frames(4)
        assertTrue(open(), "a double click is one click")
        // A press elsewhere still closes it.
        Thread.sleep(PopupOpener.SAME_GESTURE.inWholeMilliseconds + 100)
        val away = Offset(800f, 800f)
        h.move(away)
        h.click(away)
        h.frames(4)
        assertFalse(open(), "a press outside closes the menu")
    }

    /**
     * A hover pop-up's ▾, wired as `ConfigurationTreeArrow` wires its own — its tracker ([MenuHoverTracker]), its opener
     * ([popupOpener], [OpenedBy]), its click ([MenuHoverTracker.clickedOpener]): hovered, it opens; rapid clicks pin it and
     * never close it, whether the platform reports the keyboard leaving the pop-up before a click or after it — the loss
     * is never applied, the pop-up gets the keyboard back. A click after the gesture closes the pinned pop-up (the
     * toggle), and a press away from the arrow is the loss it always was.
     *
     * Not `ConfigurationTreeArrow` itself: its menu is focusable, and in a scene with no window a focusable menu is
     * Material's popup, which takes the pointer off the layer under it (the arrow is left the moment it opens) — where on
     * the desktop it is a window of its own and the arrow keeps the pointer. So the menu here is not focusable.
     */
    @Test
    fun a_hover_pop_up_s_arrow_clicked_rapidly_keeps_its_pop_up() {
        val h = Harness {
            var open by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
            val hover = org.example.project.ui.rememberMenuHoverTracker { open = false }
            val opener = org.example.project.ui.rememberPopupOpener()
            Box(Modifier.padding(40.dp)) {
                Text(
                    "▾",
                    Modifier
                        .reportsHover(hover, pinOnPress = false)
                        .popupOpener(opener, open)
                        .onHoverEnter { if (!open) { hover.unpin(); open = true } }
                        .clickable {
                            val was = open
                            if (hover.clickedOpener(opener, was, toggles = true)) {
                                if (!was) hover.unpin()
                                open = true
                                hover.pin()
                            } else {
                                open = false
                            }
                        }
                        .padding(8.dp),
                )
                OpenedBy(opener) {
                    CompositionLocalProvider(org.example.project.ui.LocalMenuHover provides hover) {
                        org.example.project.ui.AppDropdownMenu(
                            expanded = open,
                            onDismissRequest = { open = false },
                            modifier = Modifier.reportsHover(hover),
                            properties = androidx.compose.ui.window.PopupProperties(focusable = false),
                        ) { Text("Inside the pop-up", Modifier.padding(8.dp)) }
                    }
                }
            }
        }.also { harness = it }
        h.frames()
        val arrow = h.boundsOf("▾").center
        h.move(arrow)
        h.frames(4)
        val open = { "Inside the pop-up" in h.texts() }
        assertTrue(open(), "hovered, the arrow opens its pop-up")
        val opener = assertNotNull(PopupOpeners.under, "the pointer is on the arrow, the pop-up's opener")
        var lost = 0
        var kept = 0
        fun lossReported() {
            val handle = PopupOpeners.keyboardLeft(opener, lost = { lost++ }, kept = { kept++ })
            if (handle != null) pending += handle
        }
        repeat(4) { i ->
            // The platform reports the press as the keyboard leaving the pop-up: before the click, or after it.
            h.press(arrow)
            if (i % 2 == 0) lossReported()
            h.frames(1)
            h.release(arrow)
            h.frames(2)
            if (i % 2 == 1) lossReported()
            h.frames(3)
            assertTrue(open(), "click ${i + 1} of a rapid run: the pop-up is open")
            assertEquals(0, lost, "click ${i + 1}: the loss its press reported was the click's to answer")
        }
        assertEquals(4, kept, "each report gave the keyboard back to the pop-up the click kept")
        pending.forEach(PopupOpeners::expire)
        assertEquals(0, lost, "no loss left waiting once its click came")
        // The gesture over: a click on the arrow closes the pinned pop-up.
        Thread.sleep(PopupOpener.SAME_GESTURE.inWholeMilliseconds + 100)
        h.press(arrow)
        lossReported()
        h.frames(1)
        h.release(arrow)
        h.frames(4)
        assertFalse(open(), "a click after the gesture closes the pinned pop-up (the toggle)")
        assertEquals(0, lost, "closed by its click, not by the loss")
        // Open again, a press away from the arrow is a loss like any other.
        Thread.sleep(PopupOpener.SAME_GESTURE.inWholeMilliseconds + 100)
        h.click(arrow)
        h.frames(4)
        assertTrue(open())
        h.move(Offset(850f, 850f))
        lossReported()
        assertEquals(1, lost, "a press elsewhere: the loss is applied at once")
    }

    private val pending = ArrayList<Any>()

    /**
     * The info bubble is drawn at the pointer and follows it: [BUBBLE_BELOW_POINTER] under it, at its x, wherever on the
     * control the pointer is and as it moves — never hung from the control's corner.
     */
    @Test
    fun the_info_bubble_follows_the_pointer() {
        val h = Harness {
            Box(Modifier.padding(40.dp)) {
                InfoHint(text = "What this is", modifier = Modifier.width(600.dp)) { Text("A wide row with an info bubble") }
            }
        }.also { harness = it }
        h.frames()
        val row = h.boundsOf("A wide row with an info bubble")
        assertFalse("What this is" in h.texts(), "no bubble before a hover")
        val below = BUBBLE_BELOW_POINTER.value
        for (x in listOf(row.left + 10f, row.center.x, row.right - 20f)) {
            val at = Offset(x, row.center.y)
            h.move(at)
            val bubble = h.boundsOf("What this is")
            // The bubble's text sits in its padding: its box starts at the pointer's x, [BUBBLE_BELOW_POINTER] under it.
            assertTrue(bubble.left in at.x..at.x + 12f, "the bubble at the pointer's x ${at.x}: $bubble")
            assertTrue(bubble.top in at.y + below..at.y + below + 10f, "the bubble just under the pointer ${at.y}: $bubble")
        }
        h.move(Offset(row.center.x, 880f))
        assertFalse("What this is" in h.texts(), "gone once the pointer left the control")
    }
}
