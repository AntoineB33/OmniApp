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
    var own by remember { mutableStateOf(SearchDomain.CONFIGURATION_SEARCH) }
    // The category revealing its children at each depth, by id — held here, so a pop-up brought back
    // ([PopupPositions]) comes back with the categories it showed.
    var path by remember { mutableStateOf(listOf<String>()) }
    // What it showed as it closed: a walk through the positions brings it back so (user rule 2026-11-11).
    var pathAtClose by remember { mutableStateOf(listOf<String>()) }
    // User rule 2026-11-11: brought back by Ctrl+Alt+←, it is as if its background had been clicked: pinned.
    var pinOnOpen by remember { mutableStateOf(false) }
    val positions = LocalPopupPositions.current
    val token = remember { Any() }
    DisposableEffect(Unit) { onDispose { positions?.forget(token) } }
    fun close() {
        pathAtClose = path
        open = false
    }
    // User rule 2026-11-04: it vanishes once the pointer has left the arrow, the pop-up and all of its descendants.
    // An arrow inside a pop-up of the tree is one of that pop-up's descendants, it and its own pop-up (anomaly
    // 2026-11-11: its own tracker alone, the pointer on its pop-up was outside the tree, and the whole tree vanished).
    val hover = rememberMenuHoverTracker(parent = LocalMenuHover.current) { close() }
    LaunchedEffect(open) {
        if (!open) hover.unpin() else if (pinOnOpen) { hover.pin(); pinOnOpen = false }
    }
    // User rule 2026-11-11: the keyboard coming to the pop-up is a position of the app; walked back to, it gets the
    // keyboard again, or comes back as it was, pinned.
    DisposableEffect(hover, positions) {
        hover.onFocused = {
            positions?.focused(token, { open }) {
                if (open) {
                    hover.pin()
                    hover.focusWindow?.invoke()
                } else {
                    path = pathAtClose
                    pinOnOpen = true
                    open = true
                }
            }
        }
        onDispose { hover.onFocused = null }
    }
    // Opened by the hand: a fresh pop-up.
    fun openFresh() {
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
        androidx.compose.runtime.CompositionLocalProvider(LocalMenuHover provides hover) {
        AppDropdownMenu(expanded = open, onDismissRequest = { close() }, properties = PopupProperties(focusable = true)) {
        Column(
            modifier = Modifier.width(320.dp).padding(8.dp),
            verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(6.dp),
        ) {
            TreePopupBar(own, { own = it }, state, nowMillis, windows, claudeLogs, onPickOnCalendar, depth)
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
    val tree = remember(shown.settingsChangedAt, query) {
        SearchDomain.configurationTree(shown).filterConfigurationNodes(query)
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
                    // User rule 2026-11-11: the keyboard coming to it is a position of the app; walked back to, it gets
                    // the keyboard again, or comes back with the categories shown then, pinned.
                    val positions = LocalPopupPositions.current
                    val token = remember { Any() }
                    var pinOnReveal by remember { mutableStateOf(false) }
                    val latestPath by rememberUpdatedState(path)
                    val latestPathChange by rememberUpdatedState(onPathChange)
                    DisposableEffect(Unit) { onDispose { positions?.forget(token) } }
                    DisposableEffect(branch, positions) {
                        branch.onFocused = {
                            val shown = latestPath
                            positions?.focused(token, { latestPath.getOrNull(depth) == node.id }) {
                                if (latestPath.getOrNull(depth) == node.id) {
                                    branch.pin()
                                    branch.focusWindow?.invoke()
                                } else {
                                    pinOnReveal = true
                                    latestPathChange(shown)
                                }
                            }
                        }
                        onDispose { branch.onFocused = null }
                    }
                    LaunchedEffect(revealing) {
                        if (!revealing) branch.unpin() else if (pinOnReveal) { branch.pin(); pinOnReveal = false }
                    }
                    val reveal = { if (!revealing) onPathChange(path.take(depth) + node.id) }
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
                            ) {
                                AppDropdownMenu(
                                    expanded = true,
                                    onDismissRequest = { if (path.getOrNull(depth) == node.id) onPathChange(path.take(depth)) },
                                    // The whole pop-up is the branch, its search field and padding included, wherever the
                                    // platform's menu does not report its surface itself.
                                    modifier = Modifier.reportsHover(branch),
                                    properties = PopupProperties(focusable = true),
                                ) {
                                    Column(
                                        modifier = Modifier.width(TREE_CATEGORY_COLUMN_WIDTH).padding(8.dp),
                                        verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(6.dp),
                                    ) {
                                        // Anomaly 2026-11-11: this field wrote into the parent pop-up's too — both
                                        // were the root's one query. Each pop-up is a search of its own over its
                                        // category's children ([SearchDomain.CONFIGURATION_SEARCH]), which goes with it.
                                        var own by remember { mutableStateOf(SearchDomain.CONFIGURATION_SEARCH) }
                                        val children = remember(node.children, own.query) {
                                            node.children.filterConfigurationNodes(own.query)
                                        }
                                        TreePopupBar(own, { own = it }, state, nowMillis, windows, claudeLogs, onPickOnCalendar, level)
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
