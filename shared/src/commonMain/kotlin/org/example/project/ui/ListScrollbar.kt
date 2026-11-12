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
 * Android actual. Hidden while everything fits. Drag the thumb to scroll; press the track to page towards the press.
 *
 * User rule 2026-11-12: **the content's height is the sum of its rows' heights** ([RowHeights]) — it changes when a row
 * expands, collapses or is resized, or the list changes, never because the list scrolled. A lazy list lays out only the
 * rows on screen, so each row's height is remembered as it is shown and a row never shown yet is counted at the average
 * of those known; the sum is exact once every row has been on screen. (It was the visible rows' average times the count:
 * a calendar of 500 dp among titles of 24 dp made the thumb grow and shrink as the calendar came and went.)
 */
@Composable
internal fun ListScrollbar(state: LazyListState, modifier: Modifier = Modifier) {
    val heights = remember(state) { RowHeights() }
    val metrics by remember(state) {
        derivedStateOf {
            val info = state.layoutInfo
            val visible = info.visibleItemsInfo
            val viewport = (info.viewportEndOffset - info.viewportStartOffset).toFloat()
            if (visible.isEmpty() || viewport <= 0f) return@derivedStateOf null
            heights.learn(info.totalItemsCount, visible.map { Triple(it.index, it.key, it.size) })
            val spacing = info.mainAxisItemSpacing.toFloat()
            val content = heights.total(info.totalItemsCount) + spacing * (info.totalItemsCount - 1).coerceAtLeast(0) +
                info.beforeContentPadding + info.afterContentPadding
            if (content <= viewport + 0.5f) return@derivedStateOf null
            val first = state.firstVisibleItemIndex
            val scrolled = heights.total(first) + spacing * first + state.firstVisibleItemScrollOffset
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

/**
 * **The rows' heights a lazy list has shown** ([ListScrollbar]): each row's by its key, and which key stands at each
 * index. A row seen keeps its height until it is seen again; one never seen counts at the average of those known.
 *
 * When the count changes, what changed is taken to be among the rows last on screen — a row expanded, collapsed, added
 * or taken off where the user is looking — so the rows before them keep their index and the rows after them move by the
 * difference. A list replaced (another search) is learnt again as it is shown.
 */
internal class RowHeights {
    private var keys: MutableList<Any?> = ArrayList()
    private val sizes = HashMap<Any, Int>()
    private var count = -1
    private var firstShown = 0
    private var lastShown = -1

    /** What the list lays out now: its [total] count, and each row on screen as (index, key, height). */
    fun learn(total: Int, shown: List<Triple<Int, Any, Int>>) {
        if (count >= 0 && total != count) {
            val delta = total - count
            val moved = MutableList<Any?>(total) { null }
            for (i in 0 until minOf(firstShown, total, keys.size)) moved[i] = keys[i]
            for (i in (lastShown + 1) until minOf(count, keys.size)) {
                val j = i + delta
                if (j in 0 until total) moved[j] = keys[i]
            }
            keys = moved
        }
        while (keys.size < total) keys.add(null)
        while (keys.size > total) keys.removeAt(keys.size - 1)
        for ((index, key, size) in shown) {
            if (index in 0 until total) keys[index] = key
            sizes[key] = size
        }
        count = total
        if (shown.isNotEmpty()) {
            firstShown = shown.minOf { it.first }
            lastShown = shown.maxOf { it.first }
        }
    }

    /** The heights of the rows before [end], summed: those known as shown, the others at their average. */
    fun total(end: Int): Float {
        var known = 0L
        var knownCount = 0
        for (key in keys) {
            val size = key?.let { sizes[it] } ?: continue
            known += size
            knownCount++
        }
        val average = if (knownCount == 0) 0f else known.toFloat() / knownCount
        var sum = 0f
        for (i in 0 until minOf(end, keys.size)) sum += keys[i]?.let { sizes[it] }?.toFloat() ?: average
        return sum
    }
}
