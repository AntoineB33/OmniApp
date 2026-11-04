package org.example.project.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import org.example.project.scheduler.domain.SchedulerDomain
import org.example.project.scheduler.domain.SearchDomain
import org.example.project.scheduler.model.CellId
import org.example.project.scheduler.state.CellEditMode
import org.example.project.scheduler.state.EditExitNavigation
import org.example.project.scheduler.state.SchedulerIntent
import org.example.project.scheduler.state.SchedulerState
import org.example.project.scheduler.ui.cellEditModeOptions

/** The rectangle's size, or the room there is. Wide enough for a Search section ([COMPACT_SECTION_MIN_WIDTH]). */
private val CELL_EDIT_POPUP_WIDTH = 560.dp
private val CELL_EDIT_POPUP_HEIGHT = 440.dp

/**
 * User rule 2026-11-02: where the rectangle of a cell in Edit Mode stands, all in the same coordinates: **below** the
 * cell, else **above** it, else on its **left**, else on its **right**, each only where it fits whole in [room]. Fitting
 * nowhere, it stands below as far as the room lets it.
 */
internal fun cellEditPopupOffset(anchor: Rect, popup: IntSize, room: IntSize): IntOffset {
    val width = popup.width
    val height = popup.height
    val alignedX = anchor.left.toInt().coerceIn(0, (room.width - width).coerceAtLeast(0))
    val alignedY = anchor.top.toInt().coerceIn(0, (room.height - height).coerceAtLeast(0))
    return when {
        anchor.bottom + height <= room.height -> IntOffset(alignedX, anchor.bottom.toInt())
        anchor.top - height >= 0f -> IntOffset(alignedX, anchor.top.toInt() - height)
        anchor.left - width >= 0f -> IntOffset(anchor.left.toInt() - width, alignedY)
        anchor.right + width <= room.width -> IntOffset(anchor.right.toInt(), alignedY)
        else -> IntOffset(alignedX, (room.height - height).coerceAtLeast(0))
    }
}

/**
 * One cell in Edit Mode, as its row told it ([PublishCellEditPopup]). [state] is the DRAWN state, the one holding the
 * edit session (a projection in the §4 template and a Search sub-tree) and the one [onIntent] speaks to; [namingSource]
 * is the account's, which the Search section looks through.
 */
internal data class CellEditPopupRequest(
    val state: SchedulerState,
    val namingSource: SchedulerState,
    val cellId: CellId,
    val onIntent: (SchedulerIntent) -> Unit,
    val refocusField: () -> Unit,
    /** The cell's field in the window, read at layout: the rectangle follows a scrolled tree, nothing recomposes. */
    val anchor: () -> Rect?,
    /** The cell's edit: a press in the rectangle is a press in it (`rememberOutsidePressEditor`). */
    val region: OutsidePressEditor,
)

/** What the rows publish and the top layer draws: at most one edit session is on screen. */
internal class CellEditPopupHost {
    private var owner: Any? = null
    var request: CellEditPopupRequest? by mutableStateOf(null)
        private set

    fun publish(from: Any, next: CellEditPopupRequest) {
        owner = from
        if (request != next) request = next
    }

    fun retract(from: Any) {
        if (owner !== from) return
        owner = null
        request = null
    }
}

internal val LocalCellEditPopupHost = staticCompositionLocalOf<CellEditPopupHost?> { null }

/** Tells the top layer about the cell this row is editing, for as long as it is composed. */
@Composable
internal fun PublishCellEditPopup(request: CellEditPopupRequest) {
    val host = LocalCellEditPopupHost.current ?: return
    val owner = remember { Any() }
    SideEffect { host.publish(owner, request) }
    DisposableEffect(host, owner) { onDispose { host.retract(owner) } }
}

/**
 * User rule 2026-11-02: **a task cell in Edit Mode shows a rectangle** ([cellEditPopupOffset]): one switch, "switch task"
 * on (the default) or "rename" off, over a Search window reduced to its search section ([SearchPicker]). The bar IS the
 * cell's draft, both ways, and starts with its completion list on the task titles.
 *
 * A layer of the content area and not a `Popup`: a non-focusable `Popup` cannot be typed in, and a focusable one eats the
 * outside press that §4's Forced Exit needs to go on and select the next cell. Being in the window, a press in it reaches
 * the root observer, so the rectangle is a part of the cell's edit ([outsidePressPart]). It also passes the content
 * area's own observer, which would drop the frame focus: it gives it back to the window the cell is in.
 */
@Composable
internal fun CellEditPopupLayer(
    host: CellEditPopupHost,
    modifier: Modifier = Modifier,
    /** The Search window, as `App` alone can build it ([SearchWindow] with its [SearchPicker]). */
    search: @Composable (config: SearchDomain.Config, onConfigChange: (SearchDomain.Config) -> Unit, picker: SearchPicker, modifier: Modifier) -> Unit,
) {
    val request = host.request ?: return
    val session = request.state.editSession ?: return
    val state = request.state
    val cellId = request.cellId
    val frames = LocalWindowFrameHost.current
    val cellWindow = remember(cellId) { frames?.focusedId }
    var origin by remember { mutableStateOf(Offset.Zero) }

    // Read once a session: the list's question does not change as the draft does, and it walks a sub-tree per task.
    val eligible = remember(cellId) { SchedulerDomain.assignableTaskIds(state, cellId) }
    val changing = session.mode == CellEditMode.ChangeTask
    val intent = request.onIntent
    val picker =
        SearchPicker(
            eligible = eligible,
            canPick = changing,
            onPick = { taskId ->
                intent(SchedulerIntent.PickTaskFromMenu(taskId))
                intent(SchedulerIntent.ExitEdit(EditExitNavigation.Stay))
            },
            // User rule 2026-11-04: the creation row stands first whenever the cell can change task.
            onNewTask = if (changing) {
                {
                    intent(SchedulerIntent.SelectCreateAssignTask)
                    intent(SchedulerIntent.ExitEdit(EditExitNavigation.Stay))
                }
            } else {
                null
            },
        )
    val config =
        SearchDomain.Config(
            query = session.draftText,
            kinds = setOf(SearchDomain.Kind.Task),
            completion = SearchDomain.Completion.TaskTitles,
            // User rule 2026-11-04: the tasks sorted by their title, the creation row before them ([SearchDomain.pickerRows]).
            sorts = SearchDomain.PICKER_SORTS,
            sortFollowsDefault = false,
        )

    Layout(
        content = {
            Surface(
                modifier = Modifier
                    .outsidePressPart(request.region)
                    .raiseOnPress { cellWindow?.let { frames?.focus(it) } }
                    // The tree's Escape, which the caret being in the rectangle no longer reaches.
                    .onPreviewKeyEvent { event ->
                        if (event.type == KeyEventType.KeyDown && event.key == Key.Escape) {
                            intent(SchedulerIntent.CancelEdit)
                            true
                        } else {
                            false
                        }
                    },
                shape = RoundedCornerShape(8.dp),
                // User rule 2026-11-04: it IS the Search window, so it is drawn on the windows' own background and
                // border ([WindowFrame]'s): a tonal elevation tinted it, and it read as another thing.
                color = MaterialTheme.colorScheme.surface,
                border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                shadowElevation = 8.dp,
            ) {
                Column(Modifier.fillMaxSize().padding(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    // A cell being created has one mode, and so no switch ([cellEditModeOptions]).
                    val modes = cellEditModeOptions(state, cellId, intent, request.refocusField)
                    if (modes.isNotEmpty()) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Switch(
                                checked = changing,
                                onCheckedChange = { modes.first { option -> !option.selected }.onSelect() },
                                modifier = Modifier.scale(0.7f),
                            )
                            Text(
                                text = if (changing) "switch task" else "rename",
                                style = MaterialTheme.typography.labelLarge,
                            )
                        }
                    }
                    search(
                        config,
                        { next -> if (next.query != session.draftText) intent(SchedulerIntent.UpdateEditText(next.query)) },
                        picker,
                        Modifier.fillMaxWidth().weight(1f),
                    )
                }
            }
        },
        modifier = modifier.fillMaxSize().onGloballyPositioned { origin = it.positionInWindow() },
    ) { measurables, constraints ->
        val width = CELL_EDIT_POPUP_WIDTH.roundToPx().coerceAtMost(constraints.maxWidth)
        val height = CELL_EDIT_POPUP_HEIGHT.roundToPx().coerceAtMost(constraints.maxHeight)
        val placeable = measurables.single().measure(Constraints.fixed(width, height))
        layout(constraints.maxWidth, constraints.maxHeight) {
            val anchor = request.anchor() ?: return@layout
            placeable.place(
                cellEditPopupOffset(
                    anchor.translate(-origin),
                    IntSize(width, height),
                    IntSize(constraints.maxWidth, constraints.maxHeight),
                ),
            )
        }
    }
}
