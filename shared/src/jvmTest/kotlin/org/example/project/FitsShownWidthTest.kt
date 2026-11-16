package org.example.project

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals
import org.example.project.ui.fitsShownWidth
import org.example.project.ui.keepsWidthAbove

/**
 * Anomaly 2026-11-16 (*"When I click on 'add a parent' in a window with little width, I have to horizontally scroll to get
 * to the down arrow, even though there is enough space on the right"*): a section narrower than its least width lays its
 * rows out wider and cuts them ([keepsWidthAbove]); a row fitted to what is shown ([fitsShownWidth]) ends at the cut, so the
 * control at its right end stays in view.
 */
class FitsShownWidthTest {
    private fun rightOfLastControl(sectionWidth: Int, fitted: Boolean): Pair<Float, Float> {
        var shownRight = 0f
        var controlRight = 0f
        val scene = onEdt {
            ImageComposeScene(800, 300, density = Density(1f)) {
                Box(
                    Modifier.width(sectionWidth.dp).padding(start = 10.dp)
                        .onGloballyPositioned { shownRight = it.boundsInWindow().right }
                        .keepsWidthAbove(440.dp),
                ) {
                    Column(Modifier.fillMaxWidth()) {
                        Row(Modifier.fillMaxWidth().padding(start = 16.dp).then(if (fitted) Modifier.fitsShownWidth() else Modifier)) {
                            // The field narrows; the arrow beside it is the control that must stay in view.
                            Spacer(Modifier.weight(1f).height(20.dp))
                            Spacer(Modifier.width(24.dp).height(20.dp).onGloballyPositioned { controlRight = it.positionInWindowRight() })
                        }
                    }
                }
            }
        }
        try {
            repeat(3) { onEdt { scene.render() } }
        } finally {
            onEdt { scene.close() }
        }
        return shownRight to controlRight
    }

    private fun androidx.compose.ui.layout.LayoutCoordinates.positionInWindowRight(): Float =
        localToWindow(androidx.compose.ui.geometry.Offset(size.width.toFloat(), 0f)).x

    @Test
    fun a_fitted_row_ends_at_the_shown_edge_of_a_cut_section() {
        val (shown, control) = rightOfLastControl(sectionWidth = 300, fitted = true)
        assertEquals(shown, control, 1f, "the arrow ends at the section's shown edge")
    }

    @Test
    fun an_unfitted_row_runs_past_the_cut_as_before() {
        val (shown, control) = rightOfLastControl(sectionWidth = 300, fitted = false)
        assertEquals(10f + 440f, control, 1f, "laid out at the section's least width")
        assert(control > shown)
    }

    @Test
    fun a_section_wide_enough_changes_nothing() {
        val (shown, control) = rightOfLastControl(sectionWidth = 600, fitted = true)
        assertEquals(shown, control, 1f)
    }
}
