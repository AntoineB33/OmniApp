package org.example.project.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** How thick a section separator's grab strip is; the drawn line is 1 dp at its centre. */
val SECTION_SEPARATOR_THICKNESS: Dp = 9.dp

/**
 * The line between two sections of a window that the user drags to share the room between them: a 1 dp line in
 * a [SECTION_SEPARATOR_THICKNESS] grab strip, under the plain OS resize double arrow.
 *
 * [vertical] is a line running top to bottom (two sections side by side), dragged sideways; otherwise the line
 * runs left to right and is dragged up and down. [onDrag] receives the movement along the drag axis in pixels;
 * the caller turns it into its own split. The drag is consumed, so it never also scrolls what is under it.
 */
@Composable
fun SectionSeparator(vertical: Boolean, onDrag: (Float) -> Unit, modifier: Modifier = Modifier) {
    val latestDrag by rememberUpdatedState(onDrag)
    Box(
        modifier = modifier
            .then(
                if (vertical) Modifier.fillMaxHeight().width(SECTION_SEPARATOR_THICKNESS)
                else Modifier.fillMaxWidth().height(SECTION_SEPARATOR_THICKNESS),
            )
            .pointerHoverIcon(if (vertical) horizontalResizePointerIcon() else verticalResizePointerIcon())
            .pointerInput(vertical) {
                detectDragGestures { change, delta ->
                    change.consume()
                    latestDrag(if (vertical) delta.x else delta.y)
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .then(if (vertical) Modifier.fillMaxHeight().width(1.dp) else Modifier.fillMaxWidth().height(1.dp))
                .background(MaterialTheme.colorScheme.outlineVariant),
        )
    }
}

/** How wide the square of a [SectionJoint] is: the crossing of the two strips, a little larger to be found. */
val SECTION_JOINT_SIZE: Dp = 17.dp

/**
 * The joint where a vertical [SectionSeparator] meets a horizontal one: a [SECTION_JOINT_SIZE] square, centred on
 * the crossing and laid over both strips, under the four-way arrow. Dragging it moves both lines at once —
 * [onDrag] receives the movement on both axes in pixels, x for the vertical line and y for the horizontal one.
 */
@Composable
fun SectionJoint(onDrag: (Offset) -> Unit, modifier: Modifier = Modifier) {
    val latestDrag by rememberUpdatedState(onDrag)
    Box(
        modifier = modifier
            .size(SECTION_JOINT_SIZE)
            .pointerHoverIcon(jointResizePointerIcon())
            .pointerInput(Unit) {
                detectDragGestures { change, delta ->
                    change.consume()
                    latestDrag(delta)
                }
            },
    )
}

/**
 * The first section's share of a split after a drag of [deltaPx] along a [totalPx]-long axis (the separator's own
 * thickness taken off), kept so neither section shrinks below [minPx]. A split too small for two minimums is
 * left where it was.
 */
fun draggedSplit(fraction: Float, deltaPx: Float, totalPx: Float, minPx: Float): Float {
    if (totalPx <= 2 * minPx) return fraction
    val lo = minPx / totalPx
    return (fraction + deltaPx / totalPx).coerceIn(lo, 1f - lo)
}
