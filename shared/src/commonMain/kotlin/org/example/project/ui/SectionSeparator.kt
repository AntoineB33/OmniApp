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
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

/** How thick a section separator's grab strip is; the drawn line is 1 dp at its centre. */
val SECTION_SEPARATOR_THICKNESS: Dp = 9.dp

/**
 * The line between two sections of a window that the user drags to share the room between them: a 1 dp line in
 * a [SECTION_SEPARATOR_THICKNESS] grab strip, under the plain OS resize double arrow.
 *
 * [vertical] is a line running top to bottom (two sections side by side), dragged sideways; otherwise the line
 * runs left to right and is dragged up and down. [onDrag] receives the movement along the drag axis in pixels;
 * the caller turns it into its own split. The drag is consumed, so it never also scrolls what is under it.
 * [onDragEnd] is called once the button is released (or the drag cancelled): the moment to tell the split to the
 * rest of the app. Anomaly 2026-10-21 (*"the lines move slower than the mouse"*): telling it at every step
 * recomposed the whole window per pixel (~100 ms a step on a real account) and the line trailed the pointer —
 * a split is applied with [sectionWidth] / [sectionShare], at layout, and told at the end.
 */
@Composable
fun SectionSeparator(vertical: Boolean, onDrag: (Float) -> Unit, modifier: Modifier = Modifier, onDragEnd: () -> Unit = {}) {
    val latestDrag by rememberUpdatedState(onDrag)
    val latestEnd by rememberUpdatedState(onDragEnd)
    Box(
        modifier = modifier
            .then(
                if (vertical) Modifier.fillMaxHeight().width(SECTION_SEPARATOR_THICKNESS)
                else Modifier.fillMaxWidth().height(SECTION_SEPARATOR_THICKNESS),
            )
            .pointerHoverIcon(if (vertical) horizontalResizePointerIcon() else verticalResizePointerIcon())
            .pointerInput(vertical) {
                detectDragGestures(onDragEnd = { latestEnd() }, onDragCancel = { latestEnd() }) { change, delta ->
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
fun SectionJoint(onDrag: (Offset) -> Unit, modifier: Modifier = Modifier, onDragEnd: () -> Unit = {}) {
    val latestDrag by rememberUpdatedState(onDrag)
    val latestEnd by rememberUpdatedState(onDragEnd)
    Box(
        modifier = modifier
            .size(SECTION_JOINT_SIZE)
            .pointerHoverIcon(jointResizePointerIcon())
            .pointerInput(Unit) {
                detectDragGestures(onDragEnd = { latestEnd() }, onDragCancel = { latestEnd() }) { change, delta ->
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

/**
 * A section [width] wide, the width READ AT LAYOUT: a dragged line changes it without recomposing the window
 * (see [SectionSeparator]). Kept within what the parent offers.
 */
fun Modifier.sectionWidth(width: () -> Dp): Modifier = layout { measurable, constraints ->
    val w = width().roundToPx().coerceIn(constraints.minWidth, constraints.maxWidth)
    val placeable = measurable.measure(constraints.copy(minWidth = w, maxWidth = w))
    layout(w, placeable.height) { placeable.place(0, 0) }
}

/**
 * A section taking [share] of the room its parent offers on the drag axis — that room less [reserved], the
 * separator's own thickness — the share READ AT LAYOUT, as [sectionWidth]. [horizontal] sizes the width (sections
 * side by side), otherwise the height. The room is the parent's offer to this section, so it must come FIRST in its
 * row or column, the other section weighted to take the rest.
 */
fun Modifier.sectionShare(horizontal: Boolean, reserved: Dp, share: () -> Float): Modifier = layout { measurable, constraints ->
    val max = if (horizontal) constraints.maxWidth else constraints.maxHeight
    val room = (max - reserved.roundToPx()).coerceAtLeast(0)
    val size = (share() * room).roundToInt().coerceIn(0, room)
    val placeable = measurable.measure(
        if (horizontal) constraints.copy(minWidth = size, maxWidth = size) else constraints.copy(minHeight = size, maxHeight = size),
    )
    layout(placeable.width, placeable.height) { placeable.place(0, 0) }
}
