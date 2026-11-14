package org.example.project

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import javax.swing.SwingUtilities
import kotlin.test.Test
import kotlin.test.assertEquals
import org.example.project.scheduler.domain.SearchDomain
import org.example.project.scheduler.domain.SearchDomain.Setting
import org.example.project.scheduler.state.SchedulerState
import org.example.project.scheduler.ui.TASK_ROW_MIN_HEIGHT
import org.example.project.ui.ConfigurationLeafRow
import org.example.project.ui.TREE_LEAF_CONTENT_WIDTH

/**
 * User rule 2026-11-14: *"the 'search bars' element has a big height because the text on the right is squished by the
 * width of the window … I want every element has rows with the same height."* Every configuration of the ▾ tree, drawn
 * as the tree draws it ([ConfigurationLeafRow]) at the width the tree gives it, is rows of ONE height — a task cell's:
 * one row as it opens, and one more per thing the user adds to it (a search bar, a sorting method).
 */
class SettingRowHeightTest {
    private fun <T> onEdt(block: () -> T): T {
        var out: Result<T>? = null
        SwingUtilities.invokeAndWait { out = runCatching(block) }
        return out!!.getOrThrow()
    }

    /** The height of every configuration's row under [config], each labelled as "Recently changed" labels it. */
    private fun heights(config: SearchDomain.Config, width: Dp = TREE_LEAF_CONTENT_WIDTH): Map<Setting, Float> {
        val out = LinkedHashMap<Setting, Float>()
        val content: @Composable () -> Unit = {
            MaterialTheme {
                Column(Modifier.width(width)) {
                    for (setting in Setting.entries) {
                        Box(Modifier.onGloballyPositioned { out[setting] = it.size.height.toFloat() }) {
                            ConfigurationLeafRow(
                                SearchDomain.ConfigurationLeaf(setting, qualified = true), SchedulerState.empty(), config, null, {},
                            )
                        }
                    }
                }
            }
        }
        val scene = onEdt { ImageComposeScene(1200, 8000, density = Density(1f), content = content) }
        try {
            repeat(3) { onEdt { scene.render() } }
        } finally {
            onEdt { scene.close() }
        }
        return out
    }

    private val row = TASK_ROW_MIN_HEIGHT.value

    @Test
    fun every_configuration_is_one_row_tall_as_it_opens() {
        val tall = heights(SearchDomain.Config()).filterValues { it != row }
        assertEquals(emptyMap(), tall, "rows of another height than a task cell's ($row)")
    }

    @Test
    fun what_the_user_adds_to_a_configuration_adds_rows_of_the_same_height() {
        val config = SearchDomain.Config(
            extraQueries = listOf("a", "b"),
            sorts = listOf(SearchDomain.SortMethod(null, SearchDomain.SortKey.Name)),
            sortFollowsDefault = false,
        )
        val all = heights(config)
        // Each is a whole number of rows (the gap between two rows of one element aside).
        val gap = org.example.project.ui.COMPACT_ROW_GAP.value
        for ((setting, height) in all) {
            val rows = ((height + gap) / (row + gap))
            assertEquals(rows.toInt().toFloat(), rows, "$setting is $height tall: no whole number of rows")
        }
        // Two bars added: the button's row, a row per bar, the formula's.
        assertEquals(4 * row + 3 * gap, all.getValue(Setting.SearchBars))
        // Nothing added: the button's row alone.
        assertEquals(row, heights(SearchDomain.Config()).getValue(Setting.SearchBars))
    }
}
