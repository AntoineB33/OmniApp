package org.example.project.ui

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isCtrlPressed
import androidx.compose.ui.input.pointer.isMetaPressed
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.isShiftPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex

/**
 * A row of a Search window's list that its owner gives rather than the search ([MenuSearchRows]): its [key] (the
 * owner's id for it), what the bar finds it by ([title]), how many folders it stands in ([depth], in [parent]), and
 * whether a drag may move it ([movable]: the left-side menu's page selector is always first).
 */
data class GivenRow(
    val key: String,
    val title: String,
    val depth: Int = 0,
    val parent: String? = null,
    val movable: Boolean = true,
)

/**
 * User rule 2026-11-13: **what the left-side menu gives the Search window that is it** ([LateralMenu]). Since 2026-11-14
 * (*"I do shift+click to select several rows, but it doesn't work"* — the menu drew its own list, with no selection) the
 * Search window's own list draws them: its selection (Ctrl/Shift), its keyboard, its scrollbar, and "Arrange by dragging"
 * ([SearchDomain.Config.menuCustomizing]), which drags the selected rows. The owner gives the [rows] the bar keeps, how
 * each is drawn inside its row ([row], told whether the list is being arranged: then it activates nothing), what a press
 * or Enter does to one ([onActivate]), and where a drag puts some ([onArrange]: before a row, in a folder; [onGroup]:
 * dropped ON a row, null where a list has no folders). What its background menu holds of its own comes first
 * ([backgroundActions]: "Reset").
 */
class MenuSearchRows(
    val rows: List<GivenRow>,
    val row: @Composable (key: String, arranging: Boolean) -> Unit,
    val onActivate: (key: String) -> Unit = {},
    val onArrange: (keys: List<String>, beforeKey: String?, parent: String?) -> Unit = { _, _, _ -> },
    val onGroup: ((keys: List<String>, ontoKey: String) -> Unit)? = null,
    val backgroundActions: List<MenuAction> = emptyList(),
    /** How far a level of folders stands in: where a drop line between two rows starts ([CustomMenuButtons.dropPlace]). */
    val levelStep: Dp = 0.dp,
    /** The gap between two rows, and the room left under the last (the window bar is drawn over the menu's bottom). */
    val rowGap: Dp = 0.dp,
    val bottomPadding: Dp = 0.dp,
    /** Bumped by the owner to bring the list's end into view (a new item added there). */
    val scrollToEnd: Int = 0,
    /** Shown when the bar keeps none of them. */
    val noneListed: String = "No item matches.",
) {
    val byKey: Map<String, GivenRow> = rows.associateBy { it.key }
}

/** The rows a held press is dragging ([ArrangeState]): their list keys, the one pressed, and how far the hand has gone. */
internal class ArrangeDrag(val keys: List<String>, val pressed: String, val offset: Float)

/**
 * User rule 2026-11-14, "Arrange by dragging": **the drag of a Search window's list**, the one rule for the left-side
 * menu and any other list. The rows on screen are read off the list itself ([LazyListState]'s layout); what a release
 * means is [CustomMenuButtons.dropOnto] and [CustomMenuButtons.dropPlace], the menu's rule since 2026-11-01.
 *
 * [depthOf] / [parentOf] / [movable]: a row's place in its folders (none outside the menu); [canGroup]: a release ON a
 * row means something (the menu's folders).
 */
internal class ArrangeState(private val list: LazyListState) {
    /** What the list says of its rows now: set at every composition, read by a gesture that outlives several. */
    var depthOf: (String) -> Int = { 0 }
    var parentOf: (String) -> String? = { null }
    var movable: (String) -> Boolean = { true }
    var canGroup: Boolean = false

    var drag by mutableStateOf<ArrangeDrag?>(null)
        private set

    fun start(keys: List<String>, pressed: String) {
        drag = ArrangeDrag(keys, pressed, 0f)
    }

    fun by(dy: Float) {
        drag = drag?.let { ArrangeDrag(it.keys, it.pressed, it.offset + dy) }
    }

    fun cancel() {
        drag = null
    }

    /** Every row inside a dragged one, at any depth: it goes along, and nothing lands in it. */
    private fun block(keys: List<String>): Set<String> {
        val out = keys.toMutableSet()
        val shown = list.layoutInfo.visibleItemsInfo.map { it.key as String }
        var grew = true
        while (grew) {
            grew = false
            for (key in shown) if (key !in out && parentOf(key)?.let { it in out } == true) grew = out.add(key)
        }
        return out
    }

    private fun others(d: ArrangeDrag): List<CustomMenuButtons.DropRow> {
        val blocked = block(d.keys)
        return list.layoutInfo.visibleItemsInfo
            .filter { val k = it.key as String; k !in blocked && movable(k) }
            .map { val k = it.key as String; CustomMenuButtons.DropRow(k, parentOf(k), depthOf(k), it.offset.toFloat(), it.size.toFloat()) }
    }

    private fun middle(d: ArrangeDrag): Float? =
        list.layoutInfo.visibleItemsInfo.firstOrNull { it.key == d.pressed }?.let { it.offset + it.size / 2f + d.offset }

    fun onto(d: ArrangeDrag): String? = if (!canGroup) null else middle(d)?.let { CustomMenuButtons.dropOnto(others(d), it) }

    fun place(d: ArrangeDrag): CustomMenuButtons.DropPlace? = middle(d)?.let { CustomMenuButtons.dropPlace(others(d), it) }

    /**
     * The release: [onGroup] the rows ON the row under them, else [onArrange] them before the row the line stood above, in
     * its folder; nothing for a press that hardly moved. The rows go in the list's order ([inOrder]), those inside another
     * dragged one carried by it.
     */
    fun end(
        inOrder: (List<String>) -> List<String>,
        onGroup: (keys: List<String>, onto: String) -> Unit,
        onArrange: (keys: List<String>, before: String?, parent: String?) -> Unit,
    ) {
        val d = drag ?: return
        val onto = onto(d)
        val place = place(d)
        drag = null
        if (kotlin.math.abs(d.offset) <= DRAG_SLOP_PX) return
        val keys = inOrder(d.keys).filter { key -> generateSequence(parentOf(key), parentOf).none { it in d.keys } }
        if (onto != null) onGroup(keys, onto) else if (place != null) onArrange(keys, place.beforeId, place.parent)
    }

    /** Where a release would put the rows, drawn over the list: an outline round the row they would go ON, else a line. */
    fun Modifier.dropIndicator(color: Color, levelStep: Dp): Modifier = drawWithContent {
        drawContent()
        val d = drag ?: return@drawWithContent
        if (kotlin.math.abs(d.offset) <= DRAG_SLOP_PX) return@drawWithContent
        val rows = list.layoutInfo.visibleItemsInfo.associateBy { it.key as String }
        val onto = onto(d)?.let(rows::get)
        if (onto != null) {
            drawRoundRect(
                color = color,
                topLeft = Offset(0f, onto.offset.toFloat()),
                size = Size(size.width, onto.size.toFloat()),
                cornerRadius = CornerRadius(8.dp.toPx()),
                style = Stroke(width = 2.dp.toPx()),
            )
            return@drawWithContent
        }
        val place = place(d) ?: return@drawWithContent
        val gap = 3.dp.toPx()
        val y =
            place.lineAbove?.let(rows::get)?.let { it.offset - gap }
                ?: place.lineBelow?.let(rows::get)?.let { it.offset + it.size + gap }
                ?: return@drawWithContent
        // It starts where a row of that depth would (user rule 2026-11-01).
        val x = levelStep.toPx() * minOf(place.depth, MAX_MENU_LEVELS)
        drawLine(color, Offset(x, y), Offset(size.width, y), strokeWidth = 2.dp.toPx())
    }

    /** A dragged row follows the hand, over the others. */
    fun Modifier.followsDrag(key: String): Modifier {
        val d = drag
        val dragged = d != null && key in d.keys
        return zIndex(if (dragged) 1f else 0f).graphicsLayer {
            translationY = if (dragged) d!!.offset else 0f
            alpha = if (dragged) 0.85f else 1f
        }
    }
}

/** How far a press must move before it is a drag rather than a click. */
private const val DRAG_SLOP_PX = 4f

/**
 * The press of a row while the list is being arranged, taken BEFORE what the row draws (the Initial pass, consumed): a
 * button opens nothing, a switch flips nothing (anomaly 2026-10-08). [onPress] says Ctrl and Shift as held; a press
 * that moves is a drag ([onDrag] by how far, [onEnd] at the release); [onClick] is a release that hardly moved. A
 * right-click passes: the row's own menu answers it.
 */
internal fun Modifier.arrangePress(
    key: Any?,
    onPress: (ctrl: Boolean, shift: Boolean) -> Unit,
    onDrag: (dy: Float) -> Unit,
    onEnd: () -> Unit,
    onClick: (ctrl: Boolean, shift: Boolean) -> Unit,
): Modifier = composed {
    // The gesture outlives the composition that started it: it calls what the row is NOW (its index moves as rows do).
    val press by rememberUpdatedState(onPress)
    val dragBy by rememberUpdatedState(onDrag)
    val end by rememberUpdatedState(onEnd)
    val click by rememberUpdatedState(onClick)
    pointerInput(key) {
        awaitPointerEventScope {
            var held = false
            var moved = 0f
            var ctrl = false
            var shift = false
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                if (event.buttons.isSecondaryPressed) continue
                when (event.type) {
                    PointerEventType.Press -> {
                        event.changes.forEach { it.consume() }
                        held = true
                        moved = 0f
                        ctrl = event.keyboardModifiers.isCtrlPressed || event.keyboardModifiers.isMetaPressed
                        shift = event.keyboardModifiers.isShiftPressed
                        press(ctrl, shift)
                    }
                    PointerEventType.Move ->
                        if (held) {
                            val dy = event.changes.firstOrNull()?.let { it.position.y - it.previousPosition.y } ?: 0f
                            event.changes.forEach { it.consume() }
                            if (dy != 0f) {
                                moved += dy
                                dragBy(dy)
                            }
                        }
                    PointerEventType.Release -> {
                        event.changes.forEach { it.consume() }
                        if (held) {
                            held = false
                            if (kotlin.math.abs(moved) <= DRAG_SLOP_PX) click(ctrl, shift)
                            end()
                        }
                    }
                }
            }
        }
    }
}

/**
 * A Ctrl- or Shift-press on a row that is not being arranged: it selects ([onSelect]) and reaches nothing under it — a
 * menu's button is not pressed by a press that selects it. A plain press passes, to the row's own answer.
 */
internal fun Modifier.modifiedPressSelects(key: Any?, onSelect: (ctrl: Boolean, shift: Boolean) -> Unit): Modifier = composed {
    val select by rememberUpdatedState(onSelect)
    pointerInput(key) {
        awaitPointerEventScope {
            var swallowing = false
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                when (event.type) {
                    PointerEventType.Press -> {
                        val ctrl = event.keyboardModifiers.isCtrlPressed || event.keyboardModifiers.isMetaPressed
                        val shift = event.keyboardModifiers.isShiftPressed
                        swallowing = (ctrl || shift) && !event.buttons.isSecondaryPressed
                        if (swallowing) {
                            event.changes.forEach { it.consume() }
                            select(ctrl, shift)
                        }
                    }
                    PointerEventType.Move, PointerEventType.Release -> if (swallowing) {
                        event.changes.forEach { it.consume() }
                        if (event.type == PointerEventType.Release) swallowing = false
                    }
                }
            }
        }
    }
}
