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
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
    pointerInput(Unit) {
        awaitPointerEventScope {
            while (true) {
                if (awaitPointerEvent().type == PointerEventType.Enter) onEnter()
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
) {
    var open by remember { mutableStateOf(false) }
    Box {
        Text(
            "▾",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier
                .clip(RoundedCornerShape(6.dp))
                .onHoverEnter { open = true }
                .clickable { open = !open }
                .padding(horizontal = 8.dp, vertical = 4.dp),
        )
        AppDropdownMenu(expanded = open, onDismissRequest = { open = false }, properties = PopupProperties(focusable = true)) {
            ConfigurationTreeColumns(state, config, openedConfig, onConfigChange, nowMillis, windows, claudeLogs) { which ->
                open = false
                onPickOnCalendar(which)
            }
        }
    }
}

/** The tree's columns: the root, then the children of the category hovered in each column before. */
@Composable
private fun ConfigurationTreeColumns(
    state: SchedulerState,
    config: SearchDomain.Config,
    openedConfig: SearchDomain.Config?,
    onConfigChange: (SearchDomain.Config) -> Unit,
    nowMillis: () -> Long,
    windows: List<SearchDomain.WindowEntry>,
    claudeLogs: org.example.project.claudelogs.ClaudeLogCatalog,
    onPickOnCalendar: (SearchDomain.BoxBound) -> Unit,
) {
    val tree = remember(config.settingsChangedAt) { SearchDomain.configurationTree(config) }
    // The category hovered at each depth, by id.
    var path by remember { mutableStateOf(listOf<String>()) }
    val latestConfig by rememberUpdatedState(config)
    val columns = buildList {
        add(tree)
        var nodes = tree
        for (id in path) {
            val category = nodes.filterIsInstance<SearchDomain.ConfigurationCategory>().firstOrNull { it.id == id } ?: break
            add(category.children)
            nodes = category.children
        }
    }
    HighlightsSearch("") {
    Row {
        columns.forEachIndexed { depth, nodes ->
            if (depth > 0) VerticalDivider(Modifier.heightIn(max = TREE_COLUMN_MAX_HEIGHT))
            val leaves = nodes.all { it is SearchDomain.ConfigurationLeaf }
            Column(
                Modifier
                    .width(if (leaves) TREE_LEAF_COLUMN_WIDTH else TREE_CATEGORY_COLUMN_WIDTH)
                    .heightIn(max = TREE_COLUMN_MAX_HEIGHT)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 6.dp),
            ) {
                for (node in nodes) {
                    when (node) {
                        is SearchDomain.ConfigurationCategory -> {
                            val reveal = { path = path.take(depth) + node.id }
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(4.dp))
                                    .background(if (path.getOrNull(depth) == node.id) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent)
                                    .onHoverEnter(reveal)
                                    .clickable(onClick = reveal)
                                    .padding(horizontal = 8.dp, vertical = 6.dp),
                            ) {
                                Text(node.label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                                Text("▸", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        is SearchDomain.ConfigurationLeaf -> {
                            // An end node closes what a category hovered in this column opened.
                            Box(Modifier.onHoverEnter { if (path.size > depth) path = path.take(depth) }) {
                                SettingRow(node.label) {
                                    SettingEditor(
                                        state, node.setting, config, openedConfig,
                                        { next -> onConfigChange(SearchDomain.withChanged(latestConfig, next, listOf(node.setting), nowMillis())) },
                                        windows, onPickOnCalendar, claudeLogs,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
    }
}

private val TREE_CATEGORY_COLUMN_WIDTH = 200.dp
/** Wide enough for the widest control a configuration has (a box bound and its "Pick on the calendar"): none is cut. */
private val TREE_LEAF_COLUMN_WIDTH = 600.dp
private val TREE_COLUMN_MAX_HEIGHT = 480.dp
