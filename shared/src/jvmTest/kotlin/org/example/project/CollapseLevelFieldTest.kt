package org.example.project

import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getAllSemanticsNodes
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.Density
import kotlin.test.Test
import kotlin.test.assertTrue
import org.example.project.scheduler.ui.COLLAPSE_LABEL
import org.example.project.scheduler.ui.TaskCellMenuActions
import org.example.project.scheduler.ui.taskCellMenuActions
import org.example.project.ui.ActionMenu

/**
 * Anomaly 2026-11-26 (*"I don't see the input field at the right of 'collapse'"*): the row menu's "collapse" entry carries
 * its level field, drawn wide enough to type in — in the real menu, at its real width.
 */
class CollapseLevelFieldTest {
    @OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class, androidx.compose.ui.InternalComposeUiApi::class)
    @Test
    fun the_collapse_entry_shows_its_level_field() {
        val actions = taskCellMenuActions(TaskCellMenuActions({}, {}, {}, {}, {}, {}))
        val scene = onEdt {
            ImageComposeScene(900, 900, density = Density(1f)) {
                ActionMenu("test", open = true, onDismiss = {}, actions = actions)
            }
        }
        try {
            var time = 0L
            repeat(8) { onEdt { time += 16L; scene.render(time * 1_000_000L) } }
            val nodes = onEdt { scene.semanticsOwners.flatMap { it.getAllSemanticsNodes(mergingEnabled = false) } }
            val texts = nodes.mapNotNull { n -> n.config.getOrNull(SemanticsProperties.Text)?.joinToString { it.text } }
            assertTrue(COLLAPSE_LABEL in texts, "the entry is listed: $texts")
            val fields = nodes.filter { it.config.getOrNull(SemanticsProperties.EditableText) != null }
            val label = nodes.firstOrNull { n -> n.config.getOrNull(SemanticsProperties.Text)?.any { it.text == COLLAPSE_LABEL } == true }
            // The menu's own search bar is a field too: the level field is the one on the entry's row.
            val onRow = fields.filter { f -> label != null && f.boundsInRoot.center.y in label.boundsInRoot.top - 20f..label.boundsInRoot.bottom + 20f }
            assertTrue(onRow.isNotEmpty(), "a field on the \"collapse\" row: ${fields.map { it.boundsInRoot }} label ${label?.boundsInRoot}")
            assertTrue(onRow.all { it.boundsInRoot.width >= 40f }, "wide enough to type in: ${onRow.map { it.boundsInRoot }}")
        } finally {
            onEdt { scene.close() }
        }
    }
}
