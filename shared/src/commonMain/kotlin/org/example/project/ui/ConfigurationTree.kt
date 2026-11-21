package org.example.project.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.height
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import kotlinx.coroutines.flow.first
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.PopupProperties
import org.example.project.scheduler.domain.SearchDomain
import org.example.project.scheduler.state.SchedulerState

/** [onEnter] each time the pointer comes over the element: a hover, which a finger never makes. */
internal fun Modifier.onHoverEnter(onEnter: () -> Unit): Modifier =
    composed {
        // Started once: it calls the latest [onEnter], which reads the path as it stands.
        val latest by rememberUpdatedState(onEnter)
        pointerInput(Unit) {
            awaitPointerEventScope {
                while (true) {
                    if (awaitPointerEvent().type == PointerEventType.Enter) latest()
                }
            }
        }
    }

/**
 * User rule 2026-11-04: **the downward arrow at the right of the Search window's bar**. Hovered (or pressed, where there
 * is no hover), it opens the configurations tree ([SearchDomain.configurationTree]) in a pop-up: a column per level, a
 * category revealing its children in the next column when hovered, an end node being the configuration itself with its
 * control — the configurations window's ([SettingEditor]) — so every configuration of the window is set from there.
 * Each change is stamped with its time ([SearchDomain.withChanged]): what the first node, "Recently changed", sorts by.
 */
@Composable
internal fun ConfigurationTreeArrow(
    state: SchedulerState,
    config: SearchDomain.Config,
    openedConfig: SearchDomain.Config?,
    onConfigChange: (SearchDomain.Config) -> Unit,
    nowMillis: () -> Long,
    windows: List<SearchDomain.WindowEntry>,
    claudeLogs: org.example.project.claudelogs.ClaudeLogCatalog,
    onPickOnCalendar: (SearchDomain.BoxBound) -> Unit = {},
    depth: Int = 0,
    /** What the window is about, on or off: first in "Relevant here" ([SearchDomain.configurationTree]). */
    relevant: List<SearchDomain.Setting> = emptyList(),
) {
    var open by remember { mutableStateOf(false) }
    // The configuration of the search this pop-up IS (user rule 2026-11-11, [SearchDomain.CONFIGURATION_SEARCH]).
    // User rule 2026-11-13: a ▾ window has an id — its owner's and "▾" — and its configuration is kept under it
    // ([ArrowSearches]), where a window with an id owns it; one without (a drop-down's list) keeps it itself.
    val arrowId = LocalArrowOwner.current?.let { it + ArrowSearches.ARROW }
    var own by rememberArrowSearch(arrowId, open)
    // The category revealing its children at each depth, by id.
    var path by remember { mutableStateOf(listOf<String>()) }
    // User rule 2026-11-11: the pop-up IS a Search window, its header and its two right sections hidden as it opens;
    // its background menu shows them ([SearchPartsMenu]). Kept while the arrow stands, as its path is.
    var parts by rememberKeptParts(arrowId, CellEditPopupHost.PICKER_SPLITS)
    val positions = LocalPopupPositions.current
    // Anomaly 2026-11-11 (*"three headless search windows deep … ctrl+alt+<- opened the two first, not the third"*,
    // measured in diagnostics.log): a pop-up of the tree — a category's, or the tree an arrow in a pop-up's bar opens —
    // leaves the screen with the pop-up it hangs from, and so did its position. The outermost arrow's [TreeKeeper] keeps
    // every position of its trees, by the address that shows the pop-up, and forgets them with that arrow alone.
    val outer = LocalTreeKeeper.current
    val keeper = outer ?: remember { TreeKeeper() }
    // Where this tree stands: the trees above it, each by the categories of the pop-up whose bar holds the next arrow.
    val prefix = if (outer == null) emptyList() else LocalTreePrefix.current + listOf(LocalBarPath.current)
    val address = prefix + listOf(emptyList<String>())
    val token = keeper.tokenOf(address)
    DisposableEffect(keeper, prefix) {
        keeper.live[prefix] = { if (open) path else null }
        onDispose {
            keeper.live.remove(prefix)
            if (outer == null) keeper.tokens.values.forEach { positions?.forget(it) }
        }
    }
    // The tracker, made below from [close]: what gives the keyboard back to the app's window.
    val tracker = remember { arrayOfNulls<MenuHoverTracker>(1) }
    fun close() {
        if (!open) return
        open = false
        if (outer == null) keeper.stopWalk()
        // The keyboard goes back to the app's window (a pop-up opened from a pop-up gives it back to that one, which
        // says so itself): a position of its own (user rule 2026-11-11).
        if (depth == 0) positions?.appFocused { tracker[0]?.focusAppWindow?.invoke() }
    }
    // User rule 2026-11-04: it vanishes once the pointer has left the arrow, the pop-up and all of its descendants.
    // An arrow inside a pop-up of the tree is one of that pop-up's descendants, it and its own pop-up (anomaly
    // 2026-11-11: its own tracker alone, the pointer on its pop-up was outside the tree, and the whole tree vanished).
    val hover = rememberMenuHoverTracker(parent = LocalMenuHover.current) { close() }
    tracker[0] = hover
    LaunchedEffect(open) { if (!open) hover.unpin() }
    // Anomaly 2026-11-15 (*"I pinned the window …, I restart the app, and all the windows disappeared"*): left pinned, it
    // comes back after a restart showing the categories it showed, pinned along them — brought back as a walk through
    // the positions brings it ([TreeKeeper.wanted]), once no other walk is under way. Kept by its ▾ id ([PopupMemory]).
    val memory = LocalPopupMemory.current
    var restoredTree by remember { mutableStateOf(false) }
    LaunchedEffect(arrowId) {
        val kept = arrowId?.let { memory?.tree(it) }
        if (kept != null) {
            androidx.compose.runtime.snapshotFlow { keeper.wanted }.first { it == null }
            keeper.wanted = prefix + listOf(kept)
        }
        restoredTree = true
    }
    LaunchedEffect(open, path, hover.pinned, restoredTree) {
        if (!restoredTree || arrowId == null) return@LaunchedEffect
        // Waiting to be brought back: not yet open, and not to be forgotten for that.
        if (!open && keeper.wanted?.let { it.take(prefix.size) == prefix && it.size > prefix.size } == true) return@LaunchedEffect
        memory?.setTree(arrowId, if (open && hover.pinned) path else null)
    }
    // User rule 2026-11-11: the keyboard coming to the pop-up is a position of the app; walked back to, the trees are
    // brought back down to it, pinned ([TreeKeeper.wanted]), and it gets the keyboard.
    DisposableEffect(hover, positions, token) {
        hover.onFocused = {
            keeper.focused(address) {
                positions?.focused(token, { keeper.isShowing(address) }, { hover.focusAppWindow?.invoke() }) {
                    keeper.bringBack(address)
                }
            }
        }
        onDispose { hover.onFocused = null }
    }
    // A walk back passing through this tree: open, showing the categories the address goes through, pinned; the tree
    // itself the place walked to, it takes the keyboard.
    val wanted = keeper.wanted
    LaunchedEffect(wanted) {
        val w = wanted ?: return@LaunchedEffect
        if (w.size <= depth || w.take(depth) != prefix) return@LaunchedEffect
        path = w[depth]
        open = true
        hover.pin()
        if (w.size == depth + 1 && w[depth].isEmpty()) {
            repeat(2) { androidx.compose.runtime.withFrameNanos { } }
            hover.pin()
            hover.takeKeyboard()
            keeper.wanted = null
        }
    }
    // User rule 2026-11-16, "Recent › Elsewhere": what the other ▾ trees did, read as the tree opens.
    val owner = LocalArrowOwner.current
    val registry = LocalArrowSearches.current
    val elsewhere = remember(open) {
        if (!open) SearchDomain.Elsewhere()
        else SearchDomain.Elsewhere(
            changed = SearchDomain.Elsewhere.changedIn(registry?.searchWindows?.invoke().orEmpty().filter { it.id != owner }.map { it.config }),
            looked = memory?.lookedIntoElsewhere(arrowId).orEmpty(),
        )
    }
    // ...and what this one looks into: a category it stays on ([LOOKED_INTO_DWELL_MS]), not one the pointer crosses.
    LaunchedEffect(open, path) {
        val node = path.lastOrNull()?.takeIf { open && SearchDomain.isLookedIntoWorthy(it) } ?: return@LaunchedEffect
        val id = arrowId ?: return@LaunchedEffect
        kotlinx.coroutines.delay(LOOKED_INTO_DWELL_MS)
        memory?.lookedInto(id, node.removePrefix(SearchDomain.FLAT_NODE_PREFIX), nowMillis())
    }
    // Opened by the hand: a fresh pop-up, a hover pop-up until a press pins it.
    fun openFresh() {
        keeper.stopWalk()
        hover.unpin()
        path = emptyList()
        open = true
    }
    // Anomaly 2026-11-20 (*"when I rapidly click on a down arrow, it appears and disappears in a blink"*): the arrow is
    // its pop-up's opener ([PopupOpener]) — a press on it is its click's to answer, never the pop-up's window losing the
    // keyboard to the arrow's; a quick run of clicks is one click.
    val opener = rememberPopupOpener()
    fun arrowClicked() {
        val wasOpen = open
        // A hover pop-up is pinned by the click; one pinned already is closed by it (the toggle).
        if (hover.clickedOpener(opener, wasOpen, toggles = true)) {
            if (!wasOpen) openFresh()
            hover.pin()
        } else {
            close()
        }
    }
    // The pop-up this arrow stands in (null: a window of the app): where a hover over it is read from.
    val around = LocalMenuHover.current
    // User rule 2026-11-15: the outermost tree of a Search window of the app becomes that window's Search
    // configurations window when dragged by its head ([PopupDetacher]); a tree in a pop-up's bar is of another search.
    val detacher = LocalPopupDetacher.current
    val detachOwner = LocalArrowOwner.current?.takeIf { outer == null && detacher?.canDetach(it) == true }
    // The window a head's drag made, carried by the rest of the drag: kept across the recompositions the drag causes
    // (its children closing), which make a new head each time.
    val dragging = remember { arrayOfNulls<DetachedDrag>(1) }
    fun windowHead(onDragStart: () -> Unit, closeSelf: () -> Unit, control: PopupWindowControl?): PopupWindowHead? {
        val owner = detachOwner ?: return null
        val detach = detacher ?: return null
        // A pop-up drawn in the app's window (no window of its own to carry) becomes no window.
        control ?: return null
        val bounds = { control.boundsInApp() }
        return PopupWindowHead(
            windowId = "ConfigSearch",
            // The drag makes it its window at once, where it stands; the pop-up's own window steps out of sight and
            // only finishes the drag (anomaly 2026-11-15).
            onDragStart = { name ->
                val at = bounds()
                onDragStart()
                control.startDrag()
                dragging[0] = at?.let { detach.detach(owner, it, DetachedAs.Moved, name) }
            },
            // Read off the screen: the pop-up's own window stepped off it ([PopupWindowControl.pointerMoved]).
            onDrag = { _ -> dragging[0]?.moveBy?.invoke(control.pointerMoved()) },
            onDragEnd = {
                dragging[0]?.done?.invoke()
                dragging[0] = null
                closeSelf()
            },
        ) { how, name ->
            // Where it stands, read before it closes; then the pop-up gone, the window in its place.
            val at = bounds()
            closeSelf()
            if (at != null) detach.detach(owner, at, how, name)
        }
    }
    Box {
        Text(
            "▾",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier
                .clip(RoundedCornerShape(6.dp))
                // Its press pins nothing: the click does (anomaly 2026-11-16, [reportsHover]'s `pinOnPress`).
                .reportsHover(hover, pinOnPress = false)
                .popupOpener(opener, open)
                // User rule 2026-11-14: no hover opens it while a pinned pop-up it is not in holds the keyboard.
                .onHoverEnter { if (!open && MenuHoverTracker.hoverActs(around)) openFresh() }
                // User rule 2026-11-14: a click on the arrow pins its pop-up, opening it first if it is closed; a pinned one
                // it closes. The press pins nothing.
                .clickable { arrowClicked() }
                // ...and so does a right-click (user rule 2026-11-14: *"clicking or right-clicking the button"*).
                .pointerInput(Unit) {
                    awaitPointerEventScope {
                        var secondary = false
                        while (true) {
                            val event = awaitPointerEvent()
                            when (event.type) {
                                PointerEventType.Press -> secondary = event.buttons.isSecondaryPressed
                                PointerEventType.Release -> if (secondary) { secondary = false; arrowClicked() }
                                else -> Unit
                            }
                        }
                    }
                }
                .padding(horizontal = 8.dp, vertical = 4.dp),
        )
        androidx.compose.runtime.CompositionLocalProvider(
            // User rule 2026-11-16 (*"Yes, next to the ▾"*): the tree hangs next to its arrow, even over the list the arrow
            // stands in — opened by a hover, it must be reachable by the pointer crossing straight onto it. Placed off
            // the list ([LocalCallerList]) it stood across the whole list from the arrow, and closed before it was reached.
            LocalCallerList provides null,
            // User rule 2026-11-19: a ▾ at the right of a window that appears opens its tree further right, away from the caller.
            LocalMenuSides provides if (LocalPopupFarSide.current == FarSide.Right) arrowTreeSides(FarSide.Right) else LocalMenuSides.current,
            LocalMenuHover provides hover,
            LocalPopupOpener provides opener,
            LocalTreeKeeper provides keeper,
            LocalTreePrefix provides prefix,
            LocalBarPath provides emptyList(),
            // The arrows in this pop-up are of the window it is.
            LocalArrowOwner provides arrowId,
            LocalTreeWindowHead provides if (detachOwner == null) null else ::windowHead,
        ) {
        // A Search window: shown bare by its menu window, it wears a window's surface ([LocalBareMenu], [LocalPopupChrome]).
        androidx.compose.runtime.CompositionLocalProvider(LocalBareMenu provides true) {
        AppDropdownMenu(expanded = open, onDismissRequest = { close() }, properties = PopupProperties(focusable = true)) {
        val control = LocalPopupWindowControl.current
        TreeSearchWindow(
            own, { own = it }, parts, { parts = it }, onClose = { close() },
            // Its children: the categories it shows.
            window = windowHead(onDragStart = { path = emptyList() }, closeSelf = { close() }, control = control),
            width = TREE_ROOT_POPUP_WIDTH,
            state = state, nowMillis = nowMillis, windows = windows, claudeLogs = claudeLogs, onPickOnCalendar = onPickOnCalendar,
            arrowDepth = depth + 1,
        ) { onFit ->
            ConfigurationTreeColumns(
                state, config, openedConfig, onConfigChange, own, path, { path = it }, nowMillis, windows, claudeLogs, depth,
                relevant = relevant, onFit = onFit, elsewhere = elsewhere,
            ) { which ->
                close()
                onPickOnCalendar(which)
            }
        }
    }
    }
        }
    }
}

/**
 * How long the tree stays on a category before it counts as looked into ("Recent › Elsewhere › Looked into the
 * children"): a hover crossing it on the way elsewhere is no look.
 */
private const val LOOKED_INTO_DWELL_MS = 800L

/** How deep the arrows go: each opens the configuration of the pop-up it stands in, which has its own arrow. */
internal const val MAX_TREE_DEPTH = 3

/**
 * The tree from its root: one pop-up per level. A category hovered shows its children in a pop-up of their own, hung from
 * its row at its left, else its right, else below, else above ([MenuSide.SUBMENU]) — so a pop-up never changes size or
 * moves when a child opens (user rule 2026-11-04), and a child may stand over its grandparents.
 */
@Composable
private fun ConfigurationTreeColumns(
    state: SchedulerState,
    config: SearchDomain.Config,
    openedConfig: SearchDomain.Config?,
    onConfigChange: (SearchDomain.Config) -> Unit,
    /** The pop-up's own search ([SearchDomain.CONFIGURATION_SEARCH] by default): what its list shows of the tree. */
    search: SearchDomain.Config,
    /** The category revealing its children at each depth, by id. */
    path: List<String>,
    onPathChange: (List<String>) -> Unit,
    nowMillis: () -> Long,
    windows: List<SearchDomain.WindowEntry>,
    claudeLogs: org.example.project.claudelogs.ClaudeLogCatalog,
    /** The arrow level of this tree ([TreePopupBar]): what its pop-ups' own arrows count from. */
    level: Int,
    relevant: List<SearchDomain.Setting> = emptyList(),
    /** Its pop-up fitted to it ([TreeColumn]'s). */
    onFit: ((Int) -> Unit)? = null,
    /** What the other ▾ trees did: "Recent › Elsewhere". */
    elsewhere: SearchDomain.Elsewhere = SearchDomain.Elsewhere(),
    onPickOnCalendar: (SearchDomain.BoxBound) -> Unit,
) {
    // The pop-ups are windows of their own: what they write reaches the Search window and comes back a frame or more
    // later. Shown from the Search window's configuration alone, a field lost the letter typed before the echo (seen on
    // screen: "ab" typed, "b" kept). So the tree shows its own copy, written at once, and an echo of one of its own
    // writes is no news; anything else the window's configuration becomes is taken as it is.
    val written = remember { ArrayDeque<SearchDomain.Config>() }
    var shown by remember { mutableStateOf(config) }
    remember(config) {
        val echo = written.indexOf(config)
        if (echo >= 0) repeat(echo + 1) { written.removeFirst() } else { written.clear(); shown = config }
    }
    // The whole configuration: the tree's first node is what is on now ([SearchDomain.activeSettings]).
    // User rule 2026-11-13: what the window was opened on comes first ([SearchDomain.contextSettings]).
    val whole = remember(shown, openedConfig, relevant, elsewhere) { SearchDomain.configurationTree(shown, openedConfig, relevant, elsewhere) }
    val treeRows = remember(whole) { SearchDomain.ConfigurationTreeRows(whole) }
    // User rule 2026-11-16: what the pop-up lists is its own search's answer, the one funnel ([SearchDomain.results]).
    val tree = remember(whole, search, state, windows, claudeLogs) {
        SearchDomain.shownConfigurationNodes(state, whole, root = true, treeRows, search, windows, claudeLogs)
    }
    val leaf: @Composable (SearchDomain.ConfigurationLeaf) -> Unit = { node ->
        ConfigurationLeafRow(
            node, state, shown, openedConfig,
            { next ->
                val stamped = SearchDomain.withChanged(shown, next, listOf(node.setting), nowMillis())
                written.addLast(stamped)
                shown = stamped
                onConfigChange(stamped)
            },
            windows, onPickOnCalendar, claudeLogs,
        )
    }
    HighlightsSearch("") {
        androidx.compose.runtime.CompositionLocalProvider(LocalTreeRows provides treeRows) {
        TreeColumn(
            tree, 0, path, onPathChange, leaf,
            state, config, openedConfig, onConfigChange, nowMillis, windows, claudeLogs, onPickOnCalendar, level, onFit,
        )
        }
    }
}

/** The tree the pop-ups list ([SearchDomain.ConfigurationTreeRows]): what a category's pop-up searches its children in. */
private val LocalTreeRows = androidx.compose.runtime.compositionLocalOf<SearchDomain.ConfigurationTreeRows?> { null }

/**
 * User rule 2026-11-14: **one configuration as the ▾ tree draws it** — *"I want every element has rows with the same
 * height"*: its label on ONE line in a column wide enough for the longest ([TREE_LABEL_WIDTH]; it says its type where
 * it stands among other types', [SearchDomain.ConfigurationLeaf.qualified]), then its control, compact — a task cell
 * tall ([CompactFields]). The one drawing of a leaf, which `SettingRowHeightTest` renders at the tree's own width.
 */
@Composable
internal fun ConfigurationLeafRow(
    node: SearchDomain.ConfigurationLeaf,
    state: SchedulerState,
    config: SearchDomain.Config,
    openedConfig: SearchDomain.Config?,
    onChange: (SearchDomain.Config) -> Unit,
    windows: List<SearchDomain.WindowEntry> = emptyList(),
    onPickOnCalendar: (SearchDomain.BoxBound) -> Unit = {},
    claudeLogs: org.example.project.claudelogs.ClaudeLogCatalog = org.example.project.claudelogs.ClaudeLogCatalog.EMPTY,
) {
    CompactFields {
        SettingRow(
            // User rule 2026-11-16: its description, in the info bubble its name shows on hover.
            node.name, TREE_LABEL_WIDTH, hint = SearchDomain.settingDescription(node.setting),
            path = node.path.takeIf { it.isNotEmpty() }?.joinToString(SearchDomain.TREE_PATH_SEPARATOR),
        ) {
            SettingEditor(state, node.setting, config, openedConfig, onChange, windows, onPickOnCalendar, claudeLogs)
        }
    }
}

/** One level of the tree: its [nodes], and the pop-up of the one of them [path] says reveals its children. */
@Composable
private fun TreeColumn(
    nodes: List<SearchDomain.ConfigurationNode>,
    depth: Int,
    path: List<String>,
    onPathChange: (List<String>) -> Unit,
    leaf: @Composable (SearchDomain.ConfigurationLeaf) -> Unit,
    state: SchedulerState,
    config: SearchDomain.Config,
    openedConfig: SearchDomain.Config?,
    onConfigChange: (SearchDomain.Config) -> Unit,
    nowMillis: () -> Long,
    windows: List<SearchDomain.WindowEntry>,
    claudeLogs: org.example.project.claudelogs.ClaudeLogCatalog,
    onPickOnCalendar: (SearchDomain.BoxBound) -> Unit,
    /** The arrow level of the tree this column is of (its pop-up's ▾ stands one level down). */
    level: Int,
    /**
     * Told how many px the pop-up holding the column ([TreeSearchWindow]) should grow (or, negative, shrink) to hold it
     * whole: the column is that pop-up's list, which takes the height the pop-up is given. Null: a column of its own height.
     */
    onFit: ((Int) -> Unit)? = null,
) {
    val scroll = rememberScrollState()
    // Anomaly 2026-11-15 (*"hovering over 'task' shows a window that blinks continuously"*, the `[popup]` trace: its
    // window resized 360 → 216 → 360 … without end): the pop-up is fitted to the tree's OWN height — its rows measured
    // whole, which no height of the pop-up changes — against the room the pop-up gives it. It was fitted to what the
    // scroll said of the two, which did not hold still as the pop-up changed size.
    var viewportPx by remember { mutableStateOf(-1) }
    var contentPx by remember { mutableStateOf(-1) }
    if (onFit != null) {
        val latestFit by androidx.compose.runtime.rememberUpdatedState(onFit)
        LaunchedEffect(viewportPx, contentPx) {
            if (viewportPx < 0 || contentPx < 0) return@LaunchedEffect
            val delta = contentPx - viewportPx
            // A couple of px is no change: two heights a rounding apart must not take turns.
            if (kotlin.math.abs(delta) > FIT_DEAD_BAND_PX) latestFit(delta)
        }
    }
    // User rule 2026-11-16: a category's pop-up never hides this column, the list its row is in ([LocalCallerList]).
    val columnBounds = remember { arrayOfNulls<androidx.compose.ui.geometry.Rect>(1) }
    Column(
        Modifier
            .reportsHover(LocalMenuHover.current)
            .onGloballyPositioned { columnBounds[0] = it.boundsInWindow() }
            .width(treeColumnWidth(nodes))
            .then(
                if (onFit == null) Modifier.heightIn(max = TREE_COLUMN_MAX_HEIGHT)
                else Modifier.layout { measurable, constraints ->
                    if (constraints.hasBoundedHeight && viewportPx != constraints.maxHeight) viewportPx = constraints.maxHeight
                    val placeable = measurable.measure(constraints)
                    layout(placeable.width, placeable.height) { placeable.place(0, 0) }
                },
            )
            .verticalScrollWithBar(scroll)
            // The scrollbar stands in a room of its own at the right, as beside the Search window's list.
            .padding(start = TREE_COLUMN_PADDING, end = TREE_COLUMN_PADDING + SCROLLBAR_ROOM),
    ) {
        // The rows, measured whole: the tree's own height ([onFit]).
        Column(Modifier.onSizeChanged { if (it.height != contentPx) contentPx = it.height }) {
        for (node in nodes) {
            when (node) {
                is SearchDomain.ConfigurationCategory -> {
                    val revealing = path.getOrNull(depth) == node.id
                    // Anomaly 2026-11-11: the child pop-up stood while the pointer rested on its parent's background
                    // (its padding, its search field: no row of it), since only another row's hover closed it. It is
                    // a hover pop-up of its own: it goes once the pointer has left its row, it and its descendants
                    // ([MenuHoverTracker], the grace being the crossing from the row to it), a branch of the tree's.
                    val branch = rememberMenuHoverTracker(parent = LocalMenuHover.current) {
                        if (path.getOrNull(depth) == node.id) onPathChange(path.take(depth))
                    }
                    // User rule 2026-11-11: the keyboard coming to it is a position of the app. Kept by the outermost
                    // tree's keeper, by the address that shows it: it outlives this pop-up, which leaves the screen with
                    // the tree (anomaly 2026-11-11, [TreeKeeper]); walked back to, the trees come back down to it.
                    val positions = LocalPopupPositions.current
                    val keeper = LocalTreeKeeper.current
                    val prefix = LocalTreePrefix.current
                    val branchPath = path.take(depth) + node.id
                    val address = prefix + listOf(branchPath)
                    val token = keeper?.tokenOf(address) ?: remember { Any() }
                    DisposableEffect(Unit) { onDispose { if (keeper == null) positions?.forget(token) } }
                    DisposableEffect(branch, positions, token) {
                        branch.onFocused = {
                            val record = {
                                positions?.focused(token, { keeper?.isShowing(address) ?: (path.getOrNull(depth) == node.id) }, { branch.focusAppWindow?.invoke() }) {
                                    keeper?.bringBack(address)
                                }
                            }
                            if (keeper != null) keeper.focused(address) { record() } else record()
                        }
                        onDispose { branch.onFocused = null }
                    }
                    // A walk back passing through here: pinned as it shows; the place walked to takes the keyboard.
                    val wanted = keeper?.wanted
                    LaunchedEffect(revealing, wanted) {
                        if (!revealing) {
                            branch.unpin()
                            return@LaunchedEffect
                        }
                        val w = wanted ?: return@LaunchedEffect
                        val level = prefix.size
                        if (w.size <= level || w.take(level) != prefix || w[level].take(depth + 1) != branchPath) return@LaunchedEffect
                        branch.pin()
                        if (w.size == level + 1 && w[level] == branchPath) {
                            repeat(2) { androidx.compose.runtime.withFrameNanos { } }
                            branch.takeKeyboard()
                            keeper.wanted = null
                        }
                    }
                    val reveal = {
                        if (!revealing) {
                            onPathChange(path.take(depth) + node.id)
                        }
                    }
                    // Anomaly 2026-11-20: the row is its pop-up's opener ([PopupOpener]): a press on it is its click's.
                    val rowOpener = rememberPopupOpener()
                    val treeWindowHead = LocalTreeWindowHead.current
                    // The pop-up this row stands in: where a hover over it is read from ([MenuHoverTracker.hoverActs]).
                    val around = LocalMenuHover.current
                    // A category's pop-up is a ▾ window too: its owner's id and the category.
                    val owner = LocalArrowOwner.current
                    val branchId = owner?.let { it + "/" + node.id }
                    // A category of the root's flat list is that category again, and is added as it.
                    AddableTreeRow(SearchDomain.CONFIGURATION_NODE_PREFIX + node.id.removePrefix(SearchDomain.FLAT_NODE_PREFIX)) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(4.dp))
                                .background(if (revealing) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent)
                                .reportsHover(branch)
                                .popupOpener(rowOpener, revealing)
                                .onHoverEnter { if (MenuHoverTracker.hoverActs(around)) reveal() }
                                // User rule 2026-11-14: a click on the row pins the pop-up it opens — opened if it is not.
                                .clickable {
                                    if (branch.clickedOpener(rowOpener, revealing, toggles = false)) {
                                        onPathChange(path.take(depth) + node.id)
                                        branch.pin()
                                    }
                                }
                                .padding(horizontal = 8.dp, vertical = 6.dp),
                        ) {
                            // User rule 2026-11-16: a category is a search configuration too; its description in an info bubble.
                            InfoHint(text = SearchDomain.nodeDescription(node.id.removePrefix(SearchDomain.FLAT_NODE_PREFIX)), modifier = Modifier.weight(1f)) {
                                Text(node.label, style = MaterialTheme.typography.bodyMedium)
                            }
                            HoverPopupArrow()
                        }
                        if (revealing) {
                            androidx.compose.runtime.CompositionLocalProvider(
                                LocalMenuSides provides MenuSide.SUBMENU,
                                LocalCallerList provides { columnBounds[0] },
                                LocalMenuHover provides branch,
                                LocalPopupOpener provides rowOpener,
                                // The arrow in this pop-up's bar opens a tree standing at this category.
                                LocalBarPath provides branchPath,
                                LocalArrowOwner provides branchId,
                            ) {
                                androidx.compose.runtime.CompositionLocalProvider(LocalBareMenu provides true) {
                                AppDropdownMenu(
                                    expanded = true,
                                    onDismissRequest = {
                                        if (path.getOrNull(depth) == node.id) onPathChange(path.take(depth))
                                    },
                                    // The whole pop-up is the branch, its search field and padding included, wherever the
                                    // platform's menu does not report its surface itself.
                                    modifier = Modifier.reportsHover(branch),
                                    properties = PopupProperties(focusable = true),
                                ) {
                                    // Anomaly 2026-11-11: this field wrote into the parent pop-up's too — both
                                    // were the root's one query. Each pop-up is a search of its own over its
                                    // category's children ([SearchDomain.CONFIGURATION_SEARCH]), which goes with it.
                                    var own by rememberArrowSearch(branchId, true)
                                    // Anomaly 2026-11-11 (*"the right-click menu doesn't appear on the background of the
                                    // 2nd headless search window"*): it is a Search window too, as the root's pop-up is.
                                    var parts by rememberKeptParts(branchId, CellEditPopupHost.PICKER_SPLITS)
                                    // User rule 2026-11-16: its own search's answer over the category's children, the one funnel.
                                    val treeRows = LocalTreeRows.current
                                    val children = remember(node.children, own, treeRows, state, windows, claudeLogs) {
                                        if (treeRows == null) node.children
                                        else SearchDomain.shownConfigurationNodes(state, node.children, root = false, treeRows, own, windows, claudeLogs)
                                    }
                                    val closeThis = { if (path.getOrNull(depth) == node.id) onPathChange(path.take(depth)) }
                                    val ownControl = LocalPopupWindowControl.current
                                    TreeSearchWindow(
                                        own, { own = it }, parts, { parts = it },
                                        onClose = closeThis,
                                        // Its children: the categories below it; it stands where its own control says.
                                        window = treeWindowHead?.invoke(
                                            { onPathChange(path.take(depth + 1)) },
                                            closeThis,
                                            ownControl,
                                        ),
                                        width = treePopupWidth(node.children),
                                        state = state, nowMillis = nowMillis, windows = windows, claudeLogs = claudeLogs,
                                        onPickOnCalendar = onPickOnCalendar,
                                        arrowDepth = level + 1,
                                    ) { onFit ->
                                        TreeColumn(
                                            children, depth + 1, path, onPathChange, leaf,
                                            state, config, openedConfig, onConfigChange, nowMillis, windows, claudeLogs, onPickOnCalendar, level,
                                            onFit,
                                        )
                                    }
                                }
                                }
                            }
                        }
                    }
                }
                // A row of another type its own search checks too: drawn as its name, no control of its own.
                is SearchDomain.ConfigurationOther -> {
                    val around = LocalMenuHover.current
                    Box(Modifier.onHoverEnter { if (path.size > depth && MenuHoverTracker.hoverActs(around)) onPathChange(path.take(depth)) }) {
                        Text(node.label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp))
                    }
                }
                is SearchDomain.ConfigurationLeaf -> {
                    // An end node closes what a category hovered in this column opened — a hover like the rows'.
                    val around = LocalMenuHover.current
                    Box(Modifier.onHoverEnter { if (path.size > depth && MenuHoverTracker.hoverActs(around)) onPathChange(path.take(depth)) }) {
                        AddableTreeRow(node.setting.name) { leaf(node) }
                    }
                }
            }
        }
        }
    }
}

private val TREE_CATEGORY_COLUMN_WIDTH = 190.dp

/** A column of categories named by their path: the longest ("Other feature pages › Claude Code logs › reasoning chain") on one line. */
private val TREE_PATH_CATEGORY_COLUMN_WIDTH = 380.dp

/** A configuration's label column in the tree: the longest label on one line ("history: Changed element types"). */
internal val TREE_LABEL_WIDTH = 208.dp

/** The room a configuration's row has in the tree: its label, and the widest control one has, on one line. */
// User rule 2026-11-16 (*"the pop-up windows are a little too wide"*): 520 until then.
internal val TREE_LEAF_CONTENT_WIDTH = 490.dp

/** A column's own side padding. */
private val TREE_COLUMN_PADDING = 6.dp

/** What a pop-up of the tree puts around its column ([SearchPopupParts]). */
private val TREE_POPUP_PADDING = 8.dp

/**
 * Anomaly 2026-11-14 (*"the 'search bars' element has a big height because the text on the right is squished by the
 * width of the window"*): a column of configurations asked for 600 dp inside a pop-up 200 or 320 dp wide, and got that —
 * some 55 dp for each control, whose texts wrapped a word a line. **A column holding a configuration is as wide as a
 * configuration needs, and its pop-up is as wide as its column** ([treePopupWidth]); one of categories alone stays narrow.
 */
private fun treeColumnWidth(nodes: List<SearchDomain.ConfigurationNode>) =
    when {
        nodes.any { it is SearchDomain.ConfigurationLeaf } -> TREE_LEAF_CONTENT_WIDTH + TREE_COLUMN_PADDING * 2
        // Anomaly 2026-11-16 (seen headlessly): categories named by their path ("Recent › Elsewhere › Looked into the
        // children") wrapped in the narrow column, "Search › Where to / search › Children".
        nodes.any { SearchDomain.TREE_PATH_SEPARATOR in it.label } -> TREE_PATH_CATEGORY_COLUMN_WIDTH
        else -> TREE_CATEGORY_COLUMN_WIDTH
    } + SCROLLBAR_ROOM

/** The width of the pop-up holding a column of [nodes]: the column's, and what stands around it. */
private fun treePopupWidth(nodes: List<SearchDomain.ConfigurationNode>) = treeColumnWidth(nodes) + TREE_POPUP_PADDING * 2

/** The root's pop-up: it holds every configuration, whatever its field has narrowed it to — it never changes width as one types. */
private val TREE_ROOT_POPUP_WIDTH = TREE_LEAF_CONTENT_WIDTH + TREE_COLUMN_PADDING * 2 + SCROLLBAR_ROOM + TREE_POPUP_PADDING * 2
private val TREE_COLUMN_MAX_HEIGHT = 480.dp

/**
 * User rule 2026-11-11: **a pop-up of the configurations tree IS a Search window** — since 2026-11-15 (*"Yes, rebuild
 * it"*) the very [SearchWindow], drawn in no frame, its list the tree ([SearchTreeList]): its head, its bar and ▾ (the
 * configurations of its own search [own], one level down: [arrowDepth]), its background menu, its actions and its added
 * elements are a Search window's, the root's pop-up and every category's alike. Until then it drew a look-alike of its
 * own, whose right sections were placeholders. Drawn through `App`'s embedded Search window where there is one
 * ([LocalEmbeddedSearch]: its actions get the app's handlers), else with none. Its height is the tree's ([TreeColumn]'s
 * `onFit`), as far as [TREE_POPUP_MAX_HEIGHT]; its right sections shown, it widens for them. A right-click on a
 * configuration or a category adds it to its added elements ([AddableTreeRow]). Its own layer: a press in it closes the
 * menus itself ([dismissesMenusOnPress]).
 */
@Composable
private fun TreeSearchWindow(
    own: SearchDomain.Config,
    onOwnChange: (SearchDomain.Config) -> Unit,
    parts: SearchSplits,
    onPartsChange: (SearchSplits) -> Unit,
    onClose: () -> Unit,
    /** What its head does as a window's ([SearchPopupHead]); null: a pop-up that becomes no window. */
    window: PopupWindowHead?,
    width: androidx.compose.ui.unit.Dp,
    state: SchedulerState,
    nowMillis: () -> Long,
    windows: List<SearchDomain.WindowEntry>,
    claudeLogs: org.example.project.claudelogs.ClaudeLogCatalog,
    onPickOnCalendar: (SearchDomain.BoxBound) -> Unit,
    arrowDepth: Int,
    tree: @Composable (onFit: (Int) -> Unit) -> Unit,
) {
    val density = androidx.compose.ui.platform.LocalDensity.current
    val rightShown = !(parts.actionsRemoved && parts.addedRemoved)
    var heightPx by remember { mutableStateOf(with(density) { TREE_POPUP_FIRST_HEIGHT.toPx() }) }
    val leastPx = with(density) { (if (rightShown) TREE_POPUP_RIGHT_MIN_HEIGHT else TREE_POPUP_MIN_HEIGHT).toPx() }
    val mostPx = with(density) { TREE_POPUP_MAX_HEIGHT.toPx() }
    // The height it had before its last fit: never fitted straight back to it — two heights taking turns are a blinking
    // window (anomaly 2026-11-15), whatever asks for it.
    var beforeLastFit by remember { mutableStateOf(-1f) }
    val fit = { delta: Int ->
        val next = (heightPx + delta).coerceIn(leastPx, mostPx)
        if (next != heightPx && kotlin.math.abs(next - beforeLastFit) > 0.5f) {
            beforeLastFit = heightPx
            heightPx = next
        }
    }
    val latestFit by androidx.compose.runtime.rememberUpdatedState(fit)
    val treeList = remember(arrowDepth) {
        SearchTreeList(arrowDepth) { add ->
            androidx.compose.runtime.CompositionLocalProvider(LocalTreeAdd provides { key -> add(listOf(key)) }) {
                tree { latestFit(it) }
            }
        }
    }
    val shownHeight = with(density) { heightPx.coerceAtLeast(leastPx).toDp() }
    val modifier = Modifier
        .width(if (rightShown) width + TREE_POPUP_RIGHT_WIDTH else width)
        .height(shownHeight)
        .dismissesMenusOnPress()
    val drawn = LocalEmbeddedSearch.current
    androidx.compose.runtime.CompositionLocalProvider(LocalSearchPopupWindow provides window, LocalPopupChrome provides PopupChrome()) {
        if (drawn != null) {
            drawn(
                EmbeddedSearchRequest(
                    config = own, onConfigChange = onOwnChange, onDrop = null,
                    treeList = treeList, splits = parts, onSplitsChange = onPartsChange,
                    openedConfig = SearchDomain.CONFIGURATION_SEARCH, onDismiss = onClose,
                ),
                modifier,
            )
        } else {
            SearchWindow(
                state = state, openers = OPTION_OPENERS, onStartTaskNow = {}, onGoToTaskTree = { _, _ -> }, onDeepCopyCell = {},
                actionHandlers = OPTION_HANDLERS, onIntent = {}, windows = windows, nowMillis = nowMillis,
                onPickOnCalendar = onPickOnCalendar, claudeLogs = claudeLogs, onDismiss = onClose,
                config = own, onConfigChange = onOwnChange, openedConfig = SearchDomain.CONFIGURATION_SEARCH,
                onOpenConfigurations = {}, splits = parts, onSplitsChange = onPartsChange, embedded = true,
                treeList = treeList, modifier = modifier,
            )
        }
    }
}

/** What adds a configuration of the tree, by its [SearchDomain.Kind.SearchConfiguration] id, to its pop-up's added elements. */
private val LocalTreeAdd = androidx.compose.runtime.compositionLocalOf<((String) -> Unit)?> { null }

/**
 * A row of the tree that a right-click offers to "add" — the configuration (or the category) [id], a
 * [SearchDomain.Kind.SearchConfiguration] row, into its pop-up's added elements, whose actions are about it.
 */
@Composable
private fun AddableTreeRow(id: String, content: @Composable () -> Unit) {
    val add = LocalTreeAdd.current
    if (add == null) {
        Box { content() }
        return
    }
    var menu by remember { mutableStateOf(false) }
    Box(Modifier.then(org.example.project.scheduler.ui.contextMenuModifier(enabled = true, key = "tree-row:$id") { menu = true })) {
        content()
        ActionMenu(
            "TreeRowMenu",
            open = menu,
            onDismiss = { menu = false },
            actions = listOf(
                MenuAction("add") {
                    menu = false
                    add(SearchDomain.keyOf(SearchDomain.ItemResult(SearchDomain.Kind.SearchConfiguration, id, "", "")))
                },
            ),
            atPointer = true,
        )
    }
}

/** How far a tree pop-up's height may be off its tree's before it is fitted again ([TreeColumn]'s `onFit`). */
private const val FIT_DEAD_BAND_PX = 2

/** The height a tree pop-up first opens at, before it is fitted to its tree ([TreeSearchWindow]). */
private val TREE_POPUP_FIRST_HEIGHT = 360.dp
private val TREE_POPUP_MIN_HEIGHT = 120.dp
private val TREE_POPUP_MAX_HEIGHT = TREE_COLUMN_MAX_HEIGHT + 140.dp

/** Its right sections shown: what it widens by, and the least height they need. */
private val TREE_POPUP_RIGHT_WIDTH = 380.dp
private val TREE_POPUP_RIGHT_MIN_HEIGHT = 420.dp

/**
 * Anomaly 2026-11-11: **the positions ([PopupPositions]) of every pop-up of a configurations tree**, kept by the
 * outermost arrow — the one standing in a window, which stays while the pop-ups come and go with what they hang from.
 *
 * A pop-up is named by its **address**: for each tree from the outermost down, the categories its pop-up shows — the
 * pop-up whose bar holds the arrow of the next tree, or, last, the pop-up itself (a tree's own pop-up: no category).
 * [tokenOf] is its one owner while the outermost arrow stands; [isShowing] whether it is on screen now; [bringBack]
 * asks every tree along the address to open, showing those categories, pinned ([wanted]), the last one taking the
 * keyboard — which takes the request back.
 */
internal class TreeKeeper {
    val tokens = HashMap<List<List<String>>, Any>()

    /** Each tree on screen, by where it stands (the address above it): the categories it shows, null while closed. */
    val live = HashMap<List<List<String>>, () -> List<String>?>()

    /** The address a walk back is bringing the trees down to, until the pop-up at its end takes the keyboard. */
    var wanted: List<List<String>>? by mutableStateOf(null)

    /**
     * The pop-up a walk back is bringing the keyboard to, until its window says it has it. The pop-ups opening on the way
     * take the keyboard as they open: those are no position of the app ([focused]), or each would cut off the walk.
     */
    private var walkTarget: List<List<String>>? = null

    /** The keyboard came to the pop-up at [address]: [record] it as a position, unless a walk is passing through it. */
    fun focused(address: List<List<String>>, record: () -> Unit) {
        val target = walkTarget
        if (target != null && target != address) return
        walkTarget = null
        record()
    }

    /** A gesture of the hand (a pop-up opened afresh, the trees closed): no walk goes on. */
    fun stopWalk() {
        wanted = null
        walkTarget = null
    }

    fun tokenOf(address: List<List<String>>): Any = tokens.getOrPut(address) { Any() }

    fun isShowing(address: List<List<String>>): Boolean =
        address.indices.all { level ->
            val shown = live[address.take(level)]?.invoke()
            shown != null && shown.take(address[level].size) == address[level]
        }

    fun bringBack(address: List<List<String>>) {
        walkTarget = address
        wanted = address
    }
}

/**
 * User rule 2026-11-13: **the ▾ windows have ids while they are open or saved** — *"saved in the sense that this window
 * has also its own ▾ window or search configuration"*. An id is its owner's and [ARROW] (`Search#2▾`, `Search#2▾▾`),
 * or its owner's and a category of the tree (`Search#2▾/sort`).
 *
 * **A ▾ window's configuration is kept with the Search window it hangs from** ([SearchDomain.Config.inside], through
 * [read] and [write], `App`'s): it comes back with that window after a restart, and a menu button keeps it with the
 * window's. [open] holds the ▾ windows on screen. [embedded] holds the Search windows drawn inside another — a field's
 * list, a drop-down's, the actions section — while they are open or hold a configuration of their own: they are in no
 * window bar, and have an id all the same.
 */
class ArrowSearches(
    private val read: (root: String) -> Map<String, String>,
    private val write: (root: String, arrows: Map<String, String>) -> Unit,
) {
    val open = androidx.compose.runtime.mutableStateMapOf<String, Boolean>()
    val embedded = androidx.compose.runtime.mutableStateMapOf<String, SearchDomain.SearchWindowState>()

    /**
     * Every Search window open or saved, by id (`App`'s, the "State in each window" action's list): what the other ▾
     * trees changed, for "Recent › Elsewhere" ([SearchDomain.Elsewhere]). None until `App` says.
     */
    var searchWindows: () -> List<SearchDomain.SearchWindowState> = { emptyList() }

    /**
     * The configuration of the window [relative] drawn inside the window [root] — a ▾ window, a field's list, a
     * drop-down's: its own, else what it opens with ([opened]; a ▾ window's by default).
     */
    fun of(root: String, relative: String, opened: SearchDomain.Config = SearchDomain.CONFIGURATION_SEARCH): SearchDomain.Config {
        openedBy[root + relative] = opened
        // Healed (2026-11-16): one stored before the ▾'s search sorted by the tree's order still follows its default sort,
        // which is that order now ([SearchDomain.defaultSortsOf]).
        return SearchDomain.Config.decode(read(root)[relative])?.let(SearchDomain::withDefaultSort) ?: opened
    }

    fun set(root: String, relative: String, config: SearchDomain.Config, opened: SearchDomain.Config = SearchDomain.CONFIGURATION_SEARCH) {
        openedBy[root + relative] = opened
        val all = read(root)
        val next = arrowsWith(all, relative, config, opened)
        if (next != all) write(root, next)
    }

    /** What each window drawn inside opens with, as its last read or write said ([of], [set]): what [setText] needs. */
    private val openedBy = HashMap<String, SearchDomain.Config>()

    /**
     * User rule 2026-11-20: a keystroke of the bar of the window [relative] (in [root]) undone or walked — its text put
     * back from [from] to [to], the rest of its configuration as it is now; nothing while its bar holds another text.
     */
    fun setText(root: String, relative: String, from: String, to: String) {
        val opened = openedBy[root + relative] ?: SearchDomain.CONFIGURATION_SEARCH
        val current = of(root, relative, opened)
        if (current.query == from) set(root, relative, current.copy(query = to), opened)
    }

    /**
     * Every window drawn inside [relative] (in [root]) forgotten — what follows its id with "/" or "▾" — itself kept: a
     * field's window opened afresh is its default window, nothing of what was opened inside it before (anomaly 2026-11-15).
     */
    fun forgetInside(root: String, relative: String) {
        val all = read(root)
        val next = all.filterKeys { !(it.startsWith("$relative/") || it.startsWith(relative + ARROW)) }
        if (next != all) write(root, next)
    }

    /** The ▾ windows of [root] that have an id now, by their id without [root]'s, a window before what hangs from it. */
    fun listed(root: String): List<String> =
        // `Search`'s are not `Search#2`'s: what follows the window's id is a ▾ or a part of the window.
        listedArrowIds(
            open.keys.filter { it.startsWith(root + ARROW) || it.startsWith("$root/") }.mapTo(HashSet()) { it.removePrefix(root) },
            read(root).keys,
        )

    companion object {
        const val ARROW: String = "▾"
    }
}

/** [arrows] with the window [relative] holding [config] — or without it, once it holds what it opens with ([opened]). */
internal fun arrowsWith(
    arrows: Map<String, String>,
    relative: String,
    config: SearchDomain.Config,
    opened: SearchDomain.Config = SearchDomain.CONFIGURATION_SEARCH,
): Map<String, String> =
    if (config == opened) arrows - relative else arrows + (relative to config.encode())

/**
 * The ids [ArrowSearches.listed] gives: the [open] ▾ windows and the [saved] ones, and every ▾ window one of them hangs
 * from (it *"has also its own ▾ window"*: each part of the id that ends with "▾"), sorted so a window stands before
 * what hangs from it.
 */
internal fun listedArrowIds(open: Set<String>, saved: Set<String>): List<String> {
    val out = HashSet<String>()
    for (id in open + saved) {
        if (ArrowSearches.ARROW !in id) continue
        out.add(id)
        id.indices.filter { id[it].toString() == ArrowSearches.ARROW && it < id.length - 1 }.forEach { out.add(id.substring(0, it + 1)) }
    }
    return out.sorted()
}

/** The registry of the ▾ windows ([ArrowSearches]); null where no `App` provides one (a test). */
val LocalArrowSearches = androidx.compose.runtime.staticCompositionLocalOf<ArrowSearches?> { null }

/** The id of the Search window the arrows drawn here are of ([ArrowSearches]); null: outside any. */
val LocalArrowOwner = androidx.compose.runtime.compositionLocalOf<String?> { null }

/** The id of the Search window of the app that keeps the ▾ configurations of what is drawn here ([SearchDomain.Config.inside]). */
val LocalArrowRoot = androidx.compose.runtime.compositionLocalOf<String?> { null }

/** The id of a Search window drawn inside the one the composition stands in ([LocalArrowOwner]): its and [part]. */
@Composable
internal fun embeddedSearchId(part: String): String? = LocalArrowOwner.current?.let { "$it/$part" }

/**
 * User rule 2026-11-13: a Search window drawn inside another **has an id** ([embeddedSearchId]), though no window bar
 * shows it. It is known to the app ([ArrowSearches.embedded]) while it is [open] or [saved] — it holds a configuration
 * of its own — with what writes its configuration ([set]; null: shown, not written).
 */
@Composable
internal fun RegisterEmbeddedSearch(
    id: String?,
    title: String,
    config: SearchDomain.Config,
    open: Boolean,
    saved: Boolean,
    set: ((SearchDomain.Config) -> Unit)?,
) {
    val registry = LocalArrowSearches.current ?: return
    id ?: return
    val latestSet by rememberUpdatedState(set)
    val writes = set != null
    DisposableEffect(registry, id, title, config, open, saved, writes) {
        if (open || saved) {
            registry.embedded[id] = SearchDomain.SearchWindowState(id, title, config, open, if (writes) { next -> latestSet?.invoke(next) } else null)
        }
        onDispose { registry.embedded.remove(id) }
    }
}

/**
 * User rule 2026-11-13: **the own configuration of a Search window drawn inside another** ([id]: a field's list, a
 * drop-down's) — kept with the Search window it stands in ([ArrowSearches], [SearchDomain.Config.inside]) while it is no
 * longer what it opens with ([opened]), so it comes back with that window after a restart; where no window of the app
 * stands above it, by the composition, for as long as [opened] is the same.
 */
@Composable
internal fun rememberInsideConfig(id: String?, opened: SearchDomain.Config): androidx.compose.runtime.MutableState<SearchDomain.Config> {
    val registry = LocalArrowSearches.current
    val root = LocalArrowRoot.current
    val local = remember(id, opened) { mutableStateOf(opened) }
    if (registry == null || id == null || root == null || !id.startsWith(root)) return local
    val relative = id.removePrefix(root)
    return remember(registry, root, relative, opened) {
        object : androidx.compose.runtime.MutableState<SearchDomain.Config> {
            override var value: SearchDomain.Config
                get() = registry.of(root, relative, opened)
                set(next) = registry.set(root, relative, next, opened)
            override fun component1() = value
            override fun component2(): (SearchDomain.Config) -> Unit = { value = it }
        }
    }
}

/**
 * The configuration of the ▾ window [id]: kept with the Search window it hangs from ([ArrowSearches]) where one stands
 * above it ([LocalArrowRoot]) — and said open while [open] — else by the composition, as before 2026-11-13.
 */
@Composable
private fun rememberArrowSearch(id: String?, open: Boolean): androidx.compose.runtime.MutableState<SearchDomain.Config> {
    val registry = LocalArrowSearches.current
    val root = LocalArrowRoot.current
    val local = remember { mutableStateOf(SearchDomain.CONFIGURATION_SEARCH) }
    if (registry == null || id == null || root == null || !id.startsWith(root)) return local
    val relative = id.removePrefix(root)
    DisposableEffect(registry, id, open) {
        if (open) registry.open[id] = true
        onDispose { registry.open.remove(id) }
    }
    return remember(registry, root, relative) {
        object : androidx.compose.runtime.MutableState<SearchDomain.Config> {
            override var value: SearchDomain.Config
                get() = registry.of(root, relative)
                set(next) = registry.set(root, relative, next)
            override fun component1() = value
            override fun component2(): (SearchDomain.Config) -> Unit = { value = it }
        }
    }
}

/** The [TreeKeeper] of the outermost tree a pop-up is in; null outside one. */
internal val LocalTreeKeeper = androidx.compose.runtime.compositionLocalOf<TreeKeeper?> { null }

/** The address of the tree a pop-up is of ([TreeKeeper]): the trees above it. */
internal val LocalTreePrefix = androidx.compose.runtime.compositionLocalOf<List<List<String>>> { emptyList() }

/** The categories the pop-up a bar stands in shows (none: a tree's own pop-up) — where an arrow in that bar stands. */
internal val LocalBarPath = androidx.compose.runtime.compositionLocalOf<List<String>> { emptyList() }

/**
 * The outermost tree's way of making one of its pop-ups a window ([PopupWindowHead], user rule 2026-11-15): given what
 * closes the pop-up's children, what closes the pop-up, and the pop-up's own window ([PopupWindowControl]). Null where
 * the tree becomes no window.
 */
internal val LocalTreeWindowHead = androidx.compose.runtime.compositionLocalOf<
    ((() -> Unit, () -> Unit, PopupWindowControl?) -> PopupWindowHead?)?,
> { null }
