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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
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
) {
    var open by remember { mutableStateOf(false) }
    // The configuration of the search this pop-up IS (user rule 2026-11-11, [SearchDomain.CONFIGURATION_SEARCH]).
    // User rule 2026-11-13: a ▾ window has an id — its owner's and "▾" — and its configuration is kept under it
    // ([ArrowSearches]), where a window with an id owns it; one without (a drop-down's list) keeps it itself.
    val arrowId = LocalArrowOwner.current?.let { it + ArrowSearches.ARROW }
    var own by rememberArrowSearch(arrowId, open, LocalArrowOwner.current)
    // The category revealing its children at each depth, by id.
    var path by remember { mutableStateOf(listOf<String>()) }
    // User rule 2026-11-11: the pop-up IS a Search window, its header and its two right sections hidden as it opens;
    // its background menu shows them ([SearchPartsMenu]). Kept while the arrow stands, as its path is.
    var parts by remember { mutableStateOf(CellEditPopupHost.PICKER_SPLITS) }
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
            hover.focusWindow?.invoke()
            keeper.wanted = null
        }
    }
    // Opened by the hand: a fresh pop-up.
    fun openFresh() {
        keeper.stopWalk()
        path = emptyList()
        open = true
    }
    Box {
        Text(
            "▾",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier
                .clip(RoundedCornerShape(6.dp))
                .reportsHover(hover)
                .onHoverEnter { if (!open) openFresh() }
                .clickable { if (open) close() else openFresh() }
                .padding(horizontal = 8.dp, vertical = 4.dp),
        )
        androidx.compose.runtime.CompositionLocalProvider(
            LocalMenuHover provides hover,
            LocalTreeKeeper provides keeper,
            LocalTreePrefix provides prefix,
            LocalBarPath provides emptyList(),
            // The arrows in this pop-up are of the window it is.
            LocalArrowOwner provides arrowId,
        ) {
        AppDropdownMenu(expanded = open, onDismissRequest = { close() }, properties = PopupProperties(focusable = true)) {
        SearchPopupParts(SearchDomain.windowTitle(own), 320.dp, parts, { parts = it }, onClose = { close() }) {
            if (!parts.barRemoved) TreePopupBar(own, { own = it }, state, nowMillis, windows, claudeLogs, onPickOnCalendar, depth)
            ConfigurationTreeColumns(state, config, openedConfig, onConfigChange, own.query, path, { path = it }, nowMillis, windows, claudeLogs, depth) { which ->
            close()
            onPickOnCalendar(which)
            }
        }
    }
        }
    }
}

/**
 * The bar of a pop-up of the tree: its search field, the search text of [own] — the configuration of the search the
 * pop-up is — and the arrow that opens the tree of [own] itself, one level down (user rule 2026-11-11: *"this is the
 * search configuration of the search configuration of the default search window"*), bounded at [MAX_TREE_DEPTH].
 */
@Composable
private fun TreePopupBar(
    own: SearchDomain.Config,
    onOwnChange: (SearchDomain.Config) -> Unit,
    state: SchedulerState,
    nowMillis: () -> Long,
    windows: List<SearchDomain.WindowEntry>,
    claudeLogs: org.example.project.claudelogs.ClaudeLogCatalog,
    onPickOnCalendar: (SearchDomain.BoxBound) -> Unit,
    depth: Int,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(4.dp),
    ) {
        OutlinedTextField(
            value = own.query,
            onValueChange = { onOwnChange(own.copy(query = it)) },
            singleLine = true,
            label = { Text("Search configuration") },
            modifier = Modifier.weight(1f),
        )
        if (depth < MAX_TREE_DEPTH) {
            ConfigurationTreeArrow(
                state, own, SearchDomain.CONFIGURATION_SEARCH, onOwnChange, nowMillis, windows, claudeLogs,
                onPickOnCalendar, depth + 1,
            )
        }
    }
}

/** How deep the arrows go: each opens the configuration of the pop-up it stands in, which has its own arrow. */
private const val MAX_TREE_DEPTH = 3

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
    query: String,
    /** The category revealing its children at each depth, by id. */
    path: List<String>,
    onPathChange: (List<String>) -> Unit,
    nowMillis: () -> Long,
    windows: List<SearchDomain.WindowEntry>,
    claudeLogs: org.example.project.claudelogs.ClaudeLogCatalog,
    /** The arrow level of this tree ([TreePopupBar]): what its pop-ups' own arrows count from. */
    level: Int,
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
    val tree = remember(shown.settingsChangedAt, query, openedConfig) {
        // User rule 2026-11-13: what the window was opened on comes first ([SearchDomain.contextSettings]).
        SearchDomain.configurationTree(shown, openedConfig).filterConfigurationNodes(query)
    }
    val leaf: @Composable (SearchDomain.Setting) -> Unit = { setting ->
        SettingRow(setting.label) {
            SettingEditor(
                state, setting, shown, openedConfig,
                { next ->
                    val stamped = SearchDomain.withChanged(shown, next, listOf(setting), nowMillis())
                    written.addLast(stamped)
                    shown = stamped
                    onConfigChange(stamped)
                },
                windows, onPickOnCalendar, claudeLogs,
            )
        }
    }
    HighlightsSearch("") {
        TreeColumn(
            tree, 0, path, onPathChange, leaf,
            state, config, openedConfig, onConfigChange, nowMillis, windows, claudeLogs, onPickOnCalendar, level,
        )
    }
}

/**
 * What a search field of the tree leaves of it: a node whose label holds [query] with ALL it holds, and the ancestors of
 * one. Anomaly 2026-11-11 (*"Task Scheduler then timer, but the final Search window is empty"*): the leaves were also
 * cut to the kinds the Search window searches, while their categories stayed — empty — and a category found by its
 * own label kept only the children found too, none as often as not. User rule 2026-11-04: every configuration is in
 * the tree; nothing but the field narrows it, and a category is never shown empty.
 */
internal fun List<SearchDomain.ConfigurationNode>.filterConfigurationNodes(query: String): List<SearchDomain.ConfigurationNode> {
    val needle = query.trim()
    if (needle.isEmpty()) return this
    fun filter(nodes: List<SearchDomain.ConfigurationNode>): List<SearchDomain.ConfigurationNode> =
        nodes.mapNotNull { node ->
            when {
                node.label.contains(needle, ignoreCase = true) -> node
                node is SearchDomain.ConfigurationCategory ->
                    filter(node.children).takeIf { it.isNotEmpty() }?.let { node.copy(children = it) }
                else -> null
            }
        }
    return filter(this)
}

/** One level of the tree: its [nodes], and the pop-up of the one of them [path] says reveals its children. */
@Composable
private fun TreeColumn(
    nodes: List<SearchDomain.ConfigurationNode>,
    depth: Int,
    path: List<String>,
    onPathChange: (List<String>) -> Unit,
    leaf: @Composable (SearchDomain.Setting) -> Unit,
    state: SchedulerState,
    config: SearchDomain.Config,
    openedConfig: SearchDomain.Config?,
    onConfigChange: (SearchDomain.Config) -> Unit,
    nowMillis: () -> Long,
    windows: List<SearchDomain.WindowEntry>,
    claudeLogs: org.example.project.claudelogs.ClaudeLogCatalog,
    onPickOnCalendar: (SearchDomain.BoxBound) -> Unit,
    /** The arrow level of the tree this column is of ([TreePopupBar]). */
    level: Int,
) {
    val leaves = nodes.all { it is SearchDomain.ConfigurationLeaf }
    Column(
        Modifier
            .reportsHover(LocalMenuHover.current)
            .width(if (leaves) TREE_LEAF_COLUMN_WIDTH else TREE_CATEGORY_COLUMN_WIDTH)
            .heightIn(max = TREE_COLUMN_MAX_HEIGHT)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 6.dp),
    ) {
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
                            branch.focusWindow?.invoke()
                            keeper.wanted = null
                        }
                    }
                    val reveal = { if (!revealing) onPathChange(path.take(depth) + node.id) }
                    // A category's pop-up is a ▾ window too: its owner's id and the category.
                    val owner = LocalArrowOwner.current
                    val branchId = owner?.let { it + "/" + node.id }
                    Box {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(4.dp))
                                .background(if (revealing) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent)
                                .reportsHover(branch)
                                .onHoverEnter(reveal)
                                .clickable(onClick = reveal)
                                .padding(horizontal = 8.dp, vertical = 6.dp),
                        ) {
                            Text(node.label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                            Text("◂", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        if (revealing) {
                            androidx.compose.runtime.CompositionLocalProvider(
                                LocalMenuSides provides MenuSide.SUBMENU,
                                LocalMenuHover provides branch,
                                // The arrow in this pop-up's bar opens a tree standing at this category.
                                LocalBarPath provides branchPath,
                                LocalArrowOwner provides branchId,
                            ) {
                                AppDropdownMenu(
                                    expanded = true,
                                    onDismissRequest = { if (path.getOrNull(depth) == node.id) onPathChange(path.take(depth)) },
                                    // The whole pop-up is the branch, its search field and padding included, wherever the
                                    // platform's menu does not report its surface itself.
                                    modifier = Modifier.reportsHover(branch),
                                    properties = PopupProperties(focusable = true),
                                ) {
                                    // Anomaly 2026-11-11: this field wrote into the parent pop-up's too — both
                                    // were the root's one query. Each pop-up is a search of its own over its
                                    // category's children ([SearchDomain.CONFIGURATION_SEARCH]), which goes with it.
                                    var own by rememberArrowSearch(branchId, true, owner)
                                    // Anomaly 2026-11-11 (*"the right-click menu doesn't appear on the background of the
                                    // 2nd headless search window"*): it is a Search window too, as the root's pop-up is.
                                    var parts by remember { mutableStateOf(CellEditPopupHost.PICKER_SPLITS) }
                                    val children = remember(node.children, own.query) {
                                        node.children.filterConfigurationNodes(own.query)
                                    }
                                    SearchPopupParts(
                                        SearchDomain.windowTitle(own), TREE_CATEGORY_COLUMN_WIDTH, parts, { parts = it },
                                        onClose = { if (path.getOrNull(depth) == node.id) onPathChange(path.take(depth)) },
                                    ) {
                                        if (!parts.barRemoved) TreePopupBar(own, { own = it }, state, nowMillis, windows, claudeLogs, onPickOnCalendar, level)
                                        TreeColumn(
                                            children, depth + 1, path, onPathChange, leaf,
                                            state, config, openedConfig, onConfigChange, nowMillis, windows, claudeLogs, onPickOnCalendar, level,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
                is SearchDomain.ConfigurationLeaf ->
                    // An end node closes what a category hovered in this column opened.
                    Box(Modifier.onHoverEnter { if (path.size > depth) onPathChange(path.take(depth)) }) { leaf(node.setting) }
            }
        }
    }
}

private val TREE_CATEGORY_COLUMN_WIDTH = 200.dp
/** Wide enough for the widest control a configuration has (a box bound and its "Pick on the calendar"): none is cut. */
private val TREE_LEAF_COLUMN_WIDTH = 600.dp
private val TREE_COLUMN_MAX_HEIGHT = 480.dp

/**
 * User rule 2026-11-11: **a pop-up of the configurations tree as the Search window it is** — the root's and every
 * category's alike (one drawing, so the two cannot differ again): [parts] says what its background menu
 * ([SearchPartsMenu]) shows — its head ([SearchPopupHead], [onClose] its ✕), its search section ([search]: the bar and
 * the column), and its two right sections, empty since the tree adds nothing. Its own layer: a press in it closes the
 * menus itself ([dismissesMenusOnPress]).
 */
@Composable
private fun SearchPopupParts(
    title: String,
    width: androidx.compose.ui.unit.Dp,
    parts: SearchSplits,
    onPartsChange: (SearchSplits) -> Unit,
    onClose: () -> Unit,
    search: @Composable () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    Column(
        Modifier
            .width(width)
            .dismissesMenusOnPress()
            // A right-click nothing in it answered: the Search window's background menu.
            .then(org.example.project.scheduler.ui.contextMenuModifier(enabled = true, key = "configuration-background") { menu = true }),
    ) {
        SearchPartsMenu(menu, { menu = false }, parts) { part -> onPartsChange(part.toggled(parts)) }
        if (!parts.headerRemoved) SearchPopupHead(title, onClose)
        Column(
            modifier = Modifier.padding(8.dp),
            verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(6.dp),
        ) {
            if (!parts.searchRemoved) search()
            if (!parts.actionsRemoved) {
                EmptySearchSection("Actions on the added elements", parts.actionsHidden) { onPartsChange(parts.copy(actionsHidden = !parts.actionsHidden)) }
            }
            if (!parts.addedRemoved) {
                EmptySearchSection("Added elements", parts.addedHidden) { onPartsChange(parts.copy(addedHidden = !parts.addedHidden)) }
            }
        }
    }
}

/** A right section of a Search window with nothing added ([ConfigurationTreeArrow]'s pop-up): its title, its arrow, and what it waits for. */
@Composable
private fun EmptySearchSection(title: String, collapsed: Boolean, onToggle: () -> Unit) {
    Column(verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            SectionArrow(collapsed, onToggle)
            Text(
                title,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.togglesSection(onToggle),
            )
        }
        if (!collapsed) {
            Text(
                "No element added.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

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
 * has also its own ▾ window or search configuration"*. Kept by `App`, so a ▾ window's configuration outlives its pop-up
 * (for the session: Compose-only, like the configuration a Search window opened with).
 *
 * An id is its owner's and [ARROW] (`Search#2▾`, `Search#2▾▾`), or its owner's and a category of the tree
 * (`Search#2▾/general`). [configs] holds those whose configuration is no longer the one a ▾ window opens with; [open]
 * those on screen; [owners] what each hangs from.
 */
class ArrowSearches {
    val configs = androidx.compose.runtime.mutableStateMapOf<String, SearchDomain.Config>()
    val open = androidx.compose.runtime.mutableStateMapOf<String, Boolean>()
    private val owners = HashMap<String, String>()

    fun of(id: String): SearchDomain.Config = configs[id] ?: SearchDomain.CONFIGURATION_SEARCH

    fun set(id: String, config: SearchDomain.Config) {
        if (config == SearchDomain.CONFIGURATION_SEARCH) configs.remove(id) else configs[id] = config
    }

    fun owned(id: String, owner: String) {
        owners[id] = owner
    }

    /** The ▾ windows that have an id now, in order: the open ones, the saved ones, and those one of them hangs from. */
    fun listed(): List<String> = listedArrowIds(open.keys, configs.keys, owners)

    /** The ▾ windows of the window [id] are forgotten with it. */
    fun forget(id: String) {
        val gone = (configs.keys + open.keys + owners.keys).filter { it.startsWith(id + ARROW) }
        gone.forEach {
            configs.remove(it)
            open.remove(it)
            owners.remove(it)
        }
    }

    companion object {
        const val ARROW: String = "▾"
    }
}

/**
 * The ids [ArrowSearches.listed] gives: the [open] ▾ windows and the [saved] ones, and every ▾ window one of them hangs
 * from ([owners]: it *"has also its own ▾ window"*), sorted so a window stands before what hangs from it.
 */
internal fun listedArrowIds(open: Set<String>, saved: Set<String>, owners: Map<String, String>): List<String> {
    val out = HashSet<String>()
    for (id in open + saved) {
        var at: String? = id
        while (at != null && ArrowSearches.ARROW in at && out.add(at)) at = owners[at]
    }
    return out.sorted()
}

/** The registry of the ▾ windows ([ArrowSearches]); null where no `App` provides one (a test). */
val LocalArrowSearches = androidx.compose.runtime.staticCompositionLocalOf<ArrowSearches?> { null }

/** The id of the Search window the arrows drawn here are of ([ArrowSearches]); null: a window with no id. */
val LocalArrowOwner = androidx.compose.runtime.compositionLocalOf<String?> { null }

/**
 * The configuration of the ▾ window [id], kept by the registry while it has an id and one is provided — and said open
 * while [open] — else by the composition, as before 2026-11-13.
 */
@Composable
private fun rememberArrowSearch(id: String?, open: Boolean, owner: String?): androidx.compose.runtime.MutableState<SearchDomain.Config> {
    val registry = LocalArrowSearches.current
    val local = remember { mutableStateOf(SearchDomain.CONFIGURATION_SEARCH) }
    if (registry == null || id == null) return local
    if (owner != null) registry.owned(id, owner)
    DisposableEffect(registry, id, open) {
        if (open) registry.open[id] = true
        onDispose { registry.open.remove(id) }
    }
    return remember(registry, id) {
        object : androidx.compose.runtime.MutableState<SearchDomain.Config> {
            override var value: SearchDomain.Config
                get() = registry.of(id)
                set(next) = registry.set(id, next)
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
