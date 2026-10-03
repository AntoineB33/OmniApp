package org.example.project.ui

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

/**
 * A vertical scrollbar for a [LazyListState], drawn in common code: Compose's own `VerticalScrollbar` has no
 * Android actual. The content height is estimated from the average height of the visible rows (a lazy list
 * never measures the rest), which is exact for uniform rows and close enough for a thumb otherwise. Hidden
 * while everything fits. Drag the thumb to scroll; press the track to page towards the press.
 */
@Composable
internal fun ListScrollbar(state: LazyListState, modifier: Modifier = Modifier) {
    val metrics by remember(state) {
        derivedStateOf {
            val info = state.layoutInfo
            val visible = info.visibleItemsInfo
            val viewport = (info.viewportEndOffset - info.viewportStartOffset).toFloat()
            if (visible.isEmpty() || viewport <= 0f) return@derivedStateOf null
            val averageItem = visible.sumOf { it.size }.toFloat() / visible.size
            val content = averageItem * info.totalItemsCount
            if (content <= viewport + 0.5f) return@derivedStateOf null
            val scrolled = state.firstVisibleItemIndex * averageItem + state.firstVisibleItemScrollOffset
            ScrollMetrics(viewport = viewport, content = content, scrolled = scrolled.coerceIn(0f, content - viewport))
        }
    }
    val m = metrics ?: return
    val scope = rememberCoroutineScope()
    ScrollbarTrack(
        m,
        modifier,
        key = state,
        page = { delta -> scope.launch { state.animateScrollBy(delta) } },
        drag = { delta -> state.dispatchRawDelta(delta) },
    )
}

/**
 * [ListScrollbar] for a plain scrolling column ([ScrollState]) — the same thumb and track, read off the exact
 * content height the column measured (`maxValue` past the viewport). Hidden while everything fits.
 */
@Composable
internal fun ColumnScrollbar(state: ScrollState, modifier: Modifier = Modifier) {
    val metrics by remember(state) {
        derivedStateOf {
            val viewport = state.viewportSize.toFloat()
            if (viewport <= 0f || state.maxValue <= 0) return@derivedStateOf null
            val content = viewport + state.maxValue
            ScrollMetrics(viewport = viewport, content = content, scrolled = state.value.toFloat())
        }
    }
    val m = metrics ?: return
    val scope = rememberCoroutineScope()
    ScrollbarTrack(
        m,
        modifier,
        key = state,
        page = { delta -> scope.launch { state.animateScrollBy(delta) } },
        drag = { delta -> state.dispatchRawDelta(delta) },
    )
}

/** The track and the thumb both scrollbars draw: a press pages towards it ([page]), the thumb drags ([drag]). */
@Composable
private fun ScrollbarTrack(
    m: ScrollMetrics,
    modifier: Modifier,
    key: Any,
    page: (Float) -> Unit,
    drag: (Float) -> Unit,
) {
    val density = LocalDensity.current
    BoxWithConstraints(modifier.fillMaxHeight().width(10.dp)) {
        val trackPx = constraints.maxHeight.toFloat()
        val minThumbPx = with(density) { 24.dp.toPx() }
        val thumbPx = (trackPx * m.viewport / m.content).coerceIn(minOf(minThumbPx, trackPx), trackPx)
        val travelPx = trackPx - thumbPx
        val thumbTopPx = if (travelPx <= 0f) 0f else travelPx * m.scrolled / (m.content - m.viewport)
        // One pixel of thumb travel moves this many pixels of content.
        val contentPerThumbPx = if (travelPx <= 0f) 0f else (m.content - m.viewport) / travelPx

        Box(
            Modifier
                .fillMaxWidth()
                .fillMaxHeight()
                .pointerInput(key, thumbTopPx, thumbPx) {
                    detectTapGestures { press ->
                        val delta = m.viewport * if (press.y < thumbTopPx) -1f else 1f
                        if (press.y < thumbTopPx || press.y > thumbTopPx + thumbPx) page(delta)
                    }
                },
        )
        Box(
            Modifier
                .offset { IntOffset(0, thumbTopPx.toInt()) }
                .fillMaxWidth()
                .height(with(density) { thumbPx.toDp() })
                .background(
                    MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f),
                    RoundedCornerShape(5.dp),
                )
                .pointerInput(key, contentPerThumbPx) {
                    detectVerticalDragGestures { change, dragAmount ->
                        change.consume()
                        drag(dragAmount * contentPerThumbPx)
                    }
                },
        )
    }
}

private class ScrollMetrics(val viewport: Float, val content: Float, val scrolled: Float)
