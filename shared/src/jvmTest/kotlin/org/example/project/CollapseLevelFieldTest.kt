package org.example.project

import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerButtons
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getAllSemanticsNodes
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.Density
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.example.project.scheduler.ui.COLLAPSE_LABEL
import org.example.project.scheduler.ui.TaskCellMenuActions
import org.example.project.scheduler.ui.taskCellMenuActions
import org.example.project.ui.ActionMenu

/**
 * Anomaly 2026-11-26 (*"I don't see the input field at the right of 'collapse'"*, then *"Clicking on the input field
 * shouldn't activate collapse. The field must be 0 by default"*): the row menu's "collapse" entry carries its level field,
 * drawn wide enough to type in, holding 0; a click in it is the field's, a click on the entry's name collapses — in the
 * real menu, at its real width.
 */
class CollapseLevelFieldTest {
    @OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class, androidx.compose.ui.InternalComposeUiApi::class)
    @Test
    fun the_collapse_entry_shows_its_level_field_and_a_click_in_it_does_not_collapse() {
        val collapsed = ArrayList<Int>()
        val actions = taskCellMenuActions(TaskCellMenuActions({}, {}, {}, {}, {}, { level -> collapsed += level }))
        val scene = onEdt {
            ImageComposeScene(900, 900, density = Density(1f)) {
                ActionMenu("test", open = true, onDismiss = {}, actions = actions)
            }
        }
        var time = 0L
        fun frames(n: Int = 4) = repeat(n) { onEdt { time += 16L; scene.render(time * 1_000_000L) } }
        fun nodes(): List<SemanticsNode> = onEdt { scene.semanticsOwners.flatMap { it.getAllSemanticsNodes(mergingEnabled = false) } }
        fun click(at: Offset) {
            onEdt { scene.sendPointerEvent(PointerEventType.Move, at) }
            onEdt { scene.sendPointerEvent(PointerEventType.Press, at, buttons = PointerButtons(isPrimaryPressed = true)) }
            frames(1)
            onEdt { scene.sendPointerEvent(PointerEventType.Release, at, buttons = PointerButtons()) }
            frames(2)
        }
        try {
            frames(8)
            val all = nodes()
            val label = all.firstOrNull { n -> n.config.getOrNull(SemanticsProperties.Text)?.any { it.text == COLLAPSE_LABEL } == true }
            assertTrue(label != null, "the entry is listed")
            // The menu's own search bar is a field too: the level field is the one on the entry's row.
            val field = all.filter { it.config.getOrNull(SemanticsProperties.EditableText) != null }
                .firstOrNull { f -> f.boundsInRoot.center.y in label.boundsInRoot.top - 20f..label.boundsInRoot.bottom + 20f }
            assertTrue(field != null, "a field on the \"collapse\" row")
            assertTrue(field.boundsInRoot.width >= 40f, "wide enough to type in: ${field.boundsInRoot}")
            assertEquals("0", field.config.getOrNull(SemanticsProperties.EditableText)?.text, "0 by default")
            click(field.boundsInRoot.center)
            assertEquals(emptyList(), collapsed, "a click in the field does not collapse")
            click(label.boundsInRoot.center)
            assertEquals(listOf(0), collapsed, "a click on the entry collapses, at the field's level")
        } finally {
            onEdt { scene.close() }
        }
    }
}
