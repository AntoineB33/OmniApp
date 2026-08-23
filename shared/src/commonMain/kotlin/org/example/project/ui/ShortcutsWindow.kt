package org.example.project.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt
import org.example.project.scheduler.platform.GlobalHotkeyClaim

/**
 * PRD §7: the floating window behind the lateral menu's **Keyboard shortcuts** button — every chord the app
 * answers to, grouped by the surface that owns it ([KeyboardShortcutCatalog]).
 *
 * The system-wide block leads, and carries one extra line the rest do not need: **what claim the OS actually
 * granted** ([claim]). Those two chords are the only ones that can be taken by another application, and when
 * that happens the symptom the user sees — nothing happens, or something else happens as well — says nothing
 * about the cause. This line does.
 *
 * Mirrors the other floating windows' drag-title / dismiss / raise-on-press pattern.
 */
@Composable
fun ShortcutsWindow(
    /** How the OS is delivering the system-wide chords right now (`GlobalHotkeys.claim`). */
    claim: GlobalHotkeyClaim,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    initialOffset: Offset = Offset.Zero,
    /** Persists the window's new drag position when a drag gesture ends (local-only geometry). */
    onOffsetChange: (Offset) -> Unit = {},
    onRaise: () -> Unit = {},
) {
    var offset by remember { mutableStateOf(initialOffset) }

    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surface,
        shadowElevation = 10.dp,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = modifier
            .offset { IntOffset(offset.x.roundToInt(), offset.y.roundToInt()) }
            // requiredWidth (not width) so the window keeps its fixed width whatever the content area is.
            .requiredWidth(430.dp)
            .raiseOnPress(onRaise),
    ) {
        Column(Modifier.fillMaxWidth()) {
            // Title bar doubles as the drag handle for moving the window.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .windowDragHandle(onDragEnd = { onOffsetChange(offset) }) { dragAmount ->
                        offset += dragAmount
                    }
                    .padding(start = 14.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "Keyboard shortcuts",
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f),
                )
                Box(
                    modifier = Modifier.size(28.dp).clip(CircleShape).clickable(onClick = onDismiss),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        "✕",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Box(Modifier.fillMaxWidth().height(1.dp).background(MaterialTheme.colorScheme.outlineVariant))

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    // The list is longer than most screens leave room for, so it scrolls inside the window
                    // rather than growing past the bottom of the app.
                    .heightIn(max = 560.dp)
                    .verticalScroll(rememberScrollState())
                    .padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                KeyboardShortcutCatalog.groups.forEach { group ->
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Text(
                            text = group.title,
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                        )
                        group.note?.let {
                            Text(
                                text = it,
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        if (group === KeyboardShortcutCatalog.globalGroup) {
                            Text(
                                text = claim.explain(),
                                style = MaterialTheme.typography.labelMedium,
                                color = if (claim == GlobalHotkeyClaim.Exclusive) {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                } else {
                                    MaterialTheme.colorScheme.error
                                },
                            )
                        }
                        Box(Modifier.fillMaxWidth().height(1.dp).background(MaterialTheme.colorScheme.outlineVariant))
                        group.shortcuts.forEach { shortcut ->
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(top = 3.dp),
                                verticalAlignment = Alignment.Top,
                            ) {
                                Text(
                                    text = shortcut.keys,
                                    style = MaterialTheme.typography.bodySmall,
                                    fontFamily = FontFamily.Monospace,
                                    modifier = Modifier.width(150.dp),
                                )
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    text = shortcut.description,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.weight(1f),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/** The one sentence the window shows under the system-wide block for each possible OS answer. */
private fun GlobalHotkeyClaim.explain(): String = when (this) {
    GlobalHotkeyClaim.Exclusive ->
        "Claimed exclusively: no other application sees these presses."
    GlobalHotkeyClaim.Shared ->
        "Registered as system hot-keys only — an application with its own keyboard hook may act on the " +
            "same press as well."
    GlobalHotkeyClaim.Unavailable ->
        "Unavailable: another application already owns these chords. The menu buttons still work."
    GlobalHotkeyClaim.Unsupported ->
        "Not available on this platform — use the menu buttons."
    GlobalHotkeyClaim.NotInstalled ->
        "Not claimed yet."
}
