package org.example.project

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.unit.Density
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.example.project.ui.CheckBoxDropDown

/**
 * User rules 2026-10-11: the check-box drop-down *"when it is long enough to be scrollable"* has a vertical scroll
 * bar, and a list with no end asks for more as it is scrolled to its bottom. Both are layout: a menu measures its
 * entries' intrinsic width, and the list scrolls in a column of its own inside it — so the menu is rendered here,
 * open, short and long, to see that it lays out at all and that the request for more is made.
 */
@OptIn(InternalComposeUiApi::class)
class CheckBoxDropDownRenderTest {

    private class Probe {
        var options by mutableStateOf(emptyList<Int>())
        var deployed by mutableStateOf(false)
        var asked = 0
    }

    private fun render(probe: Probe, more: Boolean) {
        val scene =
            ImageComposeScene(width = 500, height = 700, density = Density(1f)) {
                MaterialTheme {
                    CheckBoxDropDown(
                        options = probe.options,
                        checked = emptySet(),
                        face = "blocks",
                        label = { "Block number $it" },
                        onChange = {},
                        deploy = !probe.deployed,
                        onDeployed = { probe.deployed = true },
                        onNearEnd =
                            if (!more) null
                            else ({
                                probe.asked++
                                // A list with no end: thirty more each time, as the blocks editor does.
                                if (probe.options.size < 300) probe.options = probe.options + (probe.options.size until probe.options.size + 30)
                            }),
                    )
                }
            }
        try {
            // Frames enough for the list to open (one frame after it is laid out) and settle.
            repeat(40) { frame -> scene.render(frame * 16_000_000L) }
        } finally {
            scene.close()
        }
    }

    @Test
    fun a_short_list_and_a_long_one_both_lay_out_open() {
        // Short: nothing to scroll, no bar. Long: taller than the list may be, so it scrolls beside its bar.
        render(Probe().apply { options = (0 until 3).toList() }, more = false)
        render(Probe().apply { options = (0 until 200).toList() }, more = false)
    }

    @Test
    fun a_list_with_no_end_asks_for_more_until_it_is_long_enough_to_scroll() {
        val probe = Probe().apply { options = (0 until 2).toList() }
        render(probe, more = true)
        assertTrue(probe.deployed, "the list opened")
        assertTrue(probe.asked > 0, "too short to scroll, it asked for more")
        assertTrue(probe.options.size > 2 && probe.options.size < 300, "…and stopped asking once it scrolled: ${probe.options.size}")
        // Without the hook, a list is whole and asks for nothing.
        val whole = Probe().apply { options = (0 until 2).toList() }
        render(whole, more = false)
        assertEquals(0, whole.asked)
    }
}
