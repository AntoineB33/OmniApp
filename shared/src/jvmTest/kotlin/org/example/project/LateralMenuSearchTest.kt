package org.example.project

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.unit.Density
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.example.project.scheduler.domain.SearchDomain
import org.example.project.ui.CustomMenuButton
import org.example.project.ui.CustomMenuButtons
import org.example.project.ui.CustomMenuSection
import org.example.project.ui.LATERAL_MENU_SEARCH
import org.example.project.ui.LateralMenu

/**
 * User rule 2026-11-13: *"Make the left-side menu a Search window as well, with a specific background color."* Under
 * the page button stands the Search window, its list the menu's items; its bar finds among them.
 */
class LateralMenuSearchTest {
    private val buttons =
        listOf("Search", "Alarms", "Life plan", "Search timers").mapIndexed { i, title -> CustomMenuButton(id = "b$i", windowId = "w$i", title = title) }

    private fun <T> onEdt(block: () -> T): T {
        var result: Result<T>? = null
        javax.swing.SwingUtilities.invokeAndWait { result = runCatching(block) }
        return result!!.getOrThrow()
    }

    private fun draw(query: String): ByteArray {
        val scene = onEdt {
            ImageComposeScene(width = 188, height = 520, density = Density(1f)) {
                MaterialTheme {
                    var config by remember { mutableStateOf(LATERAL_MENU_SEARCH.copy(query = query)) }
                    LateralMenu(
                        page = OmniPage.TaskScheduler,
                        onPageSelected = {},
                        config = config,
                        onConfigChange = { config = it },
                        items = { typed ->
                            CustomMenuSection(
                                buttons = buttons, onClick = {}, editingId = null, onStartRename = {}, onRename = { _, _ -> },
                                onEditDone = {}, onRemove = {}, query = typed,
                            )
                        },
                    )
                }
            }
        }
        try {
            var bytes = ByteArray(0)
            repeat(5) { onEdt { bytes = scene.render().encodeToData()!!.bytes } }
            return bytes
        } finally {
            onEdt { scene.close() }
        }
    }

    @Test
    fun the_menu_is_drawn_as_a_search_window_and_its_bar_finds_among_its_items() {
        val all = draw("")
        val found = draw("search")
        val none = draw("zzz")
        assertTrue(!all.contentEquals(found), "the bar narrows the items")
        assertTrue(!found.contentEquals(none), "what it finds is drawn")
    }

    @Test
    fun the_bar_finds_the_items_whose_name_holds_what_it_holds() {
        val rows = CustomMenuButtons.visibleRows(buttons)
        assertEquals(rows, CustomMenuButtons.answering(rows, ""))
        assertEquals(rows, CustomMenuButtons.answering(rows, "   "))
        assertEquals(listOf("Search", "Search timers"), CustomMenuButtons.answering(rows, "SEARCH").map { it.button.title })
        assertEquals(emptyList(), CustomMenuButtons.answering(rows, "zzz"))
    }

    @Test
    fun the_menu_opens_with_nothing_typed_and_its_items_given() {
        assertEquals("", LATERAL_MENU_SEARCH.query)
        assertEquals(setOf(SearchDomain.Kind.Option), LATERAL_MENU_SEARCH.kinds)
    }
}
