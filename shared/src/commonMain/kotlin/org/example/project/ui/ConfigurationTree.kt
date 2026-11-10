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
    var query by remember { mutableStateOf("") }
    // User rule 2026-11-04: it vanishes once the pointer has left the arrow, the pop-up and all of its descendants.
    val hover = rememberMenuHoverTracker { open = false }
    Box {
        Text(
            "▾",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier
                .clip(RoundedCornerShape(6.dp))
                .reportsHover(hover)
                .onHoverEnter { open = true }
                .clickable { open = !open }
                .padding(horizontal = 8.dp, vertical = 4.dp),
        )
        androidx.compose.runtime.CompositionLocalProvider(LocalMenuHover provides hover) {
        AppDropdownMenu(expanded = open, onDismissRequest = { open = false }, properties = PopupProperties(focusable = true)) {
        Column(
            modifier = Modifier.width(320.dp).padding(8.dp),
            verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(6.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(4.dp),
            ) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    singleLine = true,
                    label = { Text("Search configuration") },
                    modifier = Modifier.weight(1f),
                )
                // Keep the nested configuration chain bounded, while retaining the arrow in the third popup.
                if (depth < 3) {
                    ConfigurationTreeArrow(
                        state, config, openedConfig, onConfigChange, nowMillis, windows, claudeLogs,
                        onPickOnCalendar, depth + 1,
                    )
                }
            }
            ConfigurationTreeColumns(state, config, openedConfig, onConfigChange, query, { query = it }, nowMillis, windows, claudeLogs) { which ->
            open = false
            onPickOnCalendar(which)
            }
        }
    }
        }
    }
}

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
    onQueryChange: (String) -> Unit,
    nowMillis: () -> Long,
    windows: List<SearchDomain.WindowEntry>,
    claudeLogs: org.example.project.claudelogs.ClaudeLogCatalog,
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
    val tree = remember(shown.settingsChangedAt, shown.searchedKinds, query) {
        SearchDomain.configurationTree(shown).filterConfigurationNodes(query, shown.searchedKinds)
    }
    // The category revealing its children at each depth, by id.
    var path by remember { mutableStateOf(listOf<String>()) }
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
        TreeColumn(tree, 0, path, { path = it }, leaf, query, onQueryChange)
    }
}

private fun List<SearchDomain.ConfigurationNode>.filterConfigurationNodes(
    query: String,
    associatedKinds: Set<SearchDomain.Kind>,
): List<SearchDomain.ConfigurationNode> {
    val needle = query.trim()
    fun filter(nodes: List<SearchDomain.ConfigurationNode>): List<SearchDomain.ConfigurationNode> =
        nodes.mapNotNull { node ->
            when (node) {
                is SearchDomain.ConfigurationLeaf ->
                    node.takeIf {
                        (it.setting.section == null || it.setting.section in associatedKinds) &&
                            (needle.isEmpty() || it.label.contains(needle, ignoreCase = true))
                    }
                is SearchDomain.ConfigurationCategory -> {
                    val children = filter(node.children)
                    node.takeIf { node.label.contains(needle, ignoreCase = true) || children.isNotEmpty() }
                        ?.copy(children = children)
                }
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
    query: String,
    onQueryChange: (String) -> Unit,
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
                    val reveal = { if (!revealing) onPathChange(path.take(depth) + node.id) }
                    Box {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(4.dp))
                                .background(if (revealing) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent)
                                .onHoverEnter(reveal)
                                .clickable(onClick = reveal)
                                .padding(horizontal = 8.dp, vertical = 6.dp),
                        ) {
                            Text(node.label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                            Text("◂", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        if (revealing) {
                            androidx.compose.runtime.CompositionLocalProvider(LocalMenuSides provides MenuSide.SUBMENU) {
                                AppDropdownMenu(
                                    expanded = true,
                                    onDismissRequest = { if (path.getOrNull(depth) == node.id) onPathChange(path.take(depth)) },
                                    properties = PopupProperties(focusable = true),
                                ) {
                                    Column(
                                        modifier = Modifier.width(TREE_CATEGORY_COLUMN_WIDTH).padding(8.dp),
                                        verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(6.dp),
                                    ) {
                                        OutlinedTextField(
                                            value = query,
                                            onValueChange = onQueryChange,
                                            singleLine = true,
                                            label = { Text("Search configuration") },
                                            modifier = Modifier.fillMaxWidth(),
                                        )
                                        TreeColumn(node.children, depth + 1, path, onPathChange, leaf, query, onQueryChange)
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
