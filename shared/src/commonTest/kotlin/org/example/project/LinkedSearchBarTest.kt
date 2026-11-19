package org.example.project

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import org.example.project.ui.SearchRowOpeners

/**
 * User rule 2026-11-19: *"Some rows can get the down arrow as well when one of their field is double clicked, and the field
 * gets the search bar function for the window that opens."* The field learns which window it is the bar of: the opener's
 * answer goes through the window that asked ([SearchRowOpeners.askedThrough]).
 */
class LinkedSearchBarTest {
    private fun opens(onOpenChildSearch: (String) -> String? = { null }) =
        SearchRowOpeners({}, {}, {}, {}, {}, onOpenChildSearch = onOpenChildSearch)

    @Test
    fun the_window_a_double_clicked_field_opens_is_named_back_to_it_through_the_window_that_asked() {
        var asked = 0
        val openers = opens(onOpenChildSearch = { parent -> "Search#$parent" })
        val through = openers.askedThrough { run -> asked++; run() }
        assertEquals("Search#Chrono/a", through.onOpenChildSearch("Chrono/a"))
        assertEquals(1, asked, "asked from the window, so the window it opens is linked to it")
        assertNull(opens().linkedWindow("Search#2"), "no window: the field is blank")
    }
}
