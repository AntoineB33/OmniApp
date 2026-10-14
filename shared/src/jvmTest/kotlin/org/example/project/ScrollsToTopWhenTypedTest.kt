package org.example.project

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.example.project.ui.ScrollsToTopWhenTyped

/**
 * User rule 2026-10-14: *"When typing in a search bar, the list below must be scrolled to the top (in the action
 * section and the search section of the Search window for example)."* — every list under a search bar calls
 * [ScrollsToTopWhenTyped]; rendered here headlessly, both kinds of list.
 */
class ScrollsToTopWhenTypedTest {

    private fun frames(scene: ImageComposeScene, from: Long): Long {
        var time = from
        repeat(4) { time += 16_000_000L; scene.render(time) }
        return time
    }

    @Test
    fun a_scrolled_list_goes_back_to_its_top_when_the_bar_is_typed_in_and_keeps_its_place_otherwise() {
        val text = mutableStateOf("ab")
        lateinit var scroll: ScrollState
        val scene = ImageComposeScene(width = 200, height = 200, density = Density(1f)) {
            scroll = rememberScrollState()
            ScrollsToTopWhenTyped(text.value, scroll)
            Column(Modifier.fillMaxSize().verticalScroll(scroll)) {
                repeat(50) { Box(Modifier.height(40.dp)) }
            }
        }
        try {
            var time = frames(scene, 0L)
            // Composed with text already in the bar: nothing is typed, nothing moves.
            runBlocking { scroll.scrollTo(600) }
            time = frames(scene, time)
            assertEquals(600, scroll.value, "a list opened with text in its bar keeps its place")
            // A letter typed: back to the top.
            text.value = "abc"
            time = frames(scene, time)
            assertEquals(0, scroll.value)
            // …and the cross emptying the bar is typing too.
            runBlocking { scroll.scrollTo(400) }
            text.value = ""
            frames(scene, time)
            assertEquals(0, scroll.value)
        } finally {
            scene.close()
        }
    }

    /**
     * User rule 2026-10-14: *"If the user types something on the search bar, then delete, if the user didn't scroll by
     * themselves then the scroll comes back to what it was before entering edit mode in the search bar."*
     */
    @Test
    fun deleting_what_was_typed_puts_the_list_back_where_it_was_unless_the_hand_scrolled_it() {
        val text = mutableStateOf("")
        lateinit var scroll: ScrollState
        // The results the bar gives: fifty rows with nothing typed, fewer with something — three for "a…", forty for
        // "x…" (enough to be scrolled by hand) — as a search narrows them.
        val scene = ImageComposeScene(width = 200, height = 200, density = Density(1f)) {
            scroll = rememberScrollState()
            ScrollsToTopWhenTyped(text.value, scroll)
            Column(Modifier.fillMaxSize().verticalScroll(scroll)) {
                repeat(if (text.value.isEmpty()) 50 else if (text.value.startsWith("x")) 40 else 3) { Box(Modifier.height(40.dp)) }
            }
        }
        try {
            var time = frames(scene, 0L)
            runBlocking { scroll.scrollTo(600) }
            time = frames(scene, time)
            // Typed, then deleted letter by letter: at the top while anything is typed, back at 600 once it is all gone.
            for (typed in listOf("a", "ab", "a")) {
                text.value = typed
                time = frames(scene, time)
                assertEquals(0, scroll.value, "while \"$typed\" is typed")
            }
            text.value = ""
            time = frames(scene, time)
            assertEquals(600, scroll.value, "the list is back where it was")

            // The hand scrolls while something is typed: deleting it is typing like any other — to the top.
            text.value = "x"
            time = frames(scene, time)
            text.value = "xy"
            time = frames(scene, time)
            runBlocking { scroll.scrollTo(200) }
            time = frames(scene, time)
            assertEquals(200, scroll.value)
            text.value = ""
            frames(scene, time)
            assertEquals(0, scroll.value, "the hand scrolled: not put back")
        } finally {
            scene.close()
        }
    }

    @Test
    fun a_lazy_list_is_put_back_on_the_row_it_was_on() {
        val text = mutableStateOf("")
        val list = LazyListState()
        val scene = ImageComposeScene(width = 200, height = 200, density = Density(1f)) {
            ScrollsToTopWhenTyped(text.value, list)
            LazyColumn(Modifier.fillMaxSize(), state = list) {
                items(if (text.value.isEmpty()) 100 else 4) { Box(Modifier.height(40.dp)) }
            }
        }
        try {
            var time = frames(scene, 0L)
            runBlocking { list.scrollToItem(30, 12) }
            time = frames(scene, time)
            text.value = "q"
            time = frames(scene, time)
            assertEquals(0, list.firstVisibleItemIndex)
            text.value = ""
            frames(scene, time)
            assertEquals(30, list.firstVisibleItemIndex)
            assertEquals(12, list.firstVisibleItemScrollOffset)
        } finally {
            scene.close()
        }
    }

    @Test
    fun a_lazy_list_goes_back_to_its_first_item_when_the_bar_is_typed_in() {
        val text = mutableStateOf("")
        val list = LazyListState()
        val scene = ImageComposeScene(width = 200, height = 200, density = Density(1f)) {
            ScrollsToTopWhenTyped(text.value, list)
            LazyColumn(Modifier.fillMaxSize(), state = list) {
                items(100) { Box(Modifier.height(40.dp)) }
            }
        }
        try {
            var time = frames(scene, 0L)
            runBlocking { list.scrollToItem(30) }
            time = frames(scene, time)
            assertTrue(list.firstVisibleItemIndex >= 30)
            text.value = "t"
            frames(scene, time)
            assertEquals(0, list.firstVisibleItemIndex)
            assertEquals(0, list.firstVisibleItemScrollOffset)
        } finally {
            scene.close()
        }
    }
}
