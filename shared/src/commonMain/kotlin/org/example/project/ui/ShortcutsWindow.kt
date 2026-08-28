package org.example.project.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt
import org.example.project.scheduler.platform.GlobalHotkeyClaim
import org.example.project.scheduler.platform.GlobalShortcut
import org.example.project.scheduler.platform.GlobalShortcutBindings
import org.example.project.scheduler.platform.ShortcutBinding
import org.example.project.scheduler.platform.ShortcutKey
import org.example.project.scheduler.platform.setGlobalHotkeyCapture

/**
 * PRD §7: the floating window behind the lateral menu's **Keyboard shortcuts** button — every chord the app
 * answers to, grouped by the surface that owns it ([KeyboardShortcutCatalog]).
 *
 * The system-wide block leads, and is the only one that is more than a reference list.
 *
 *  * It carries one extra line the rest do not need: **what claim the OS actually granted** ([claim]). Those
 *    are the only chords another application can take, and when that happens the symptom the user sees —
 *    nothing happens, or something else happens as well — says nothing about the cause. This line does.
 *  * Its chords are **rebindable**, because they are the ones that collide: a system-wide claim is
 *    first come, first served, so a chord some other application already owns is unusable here until the
 *    user can move it. Everything below is a Compose key handler scoped to a surface — no collisions to
 *    resolve, and nothing to read the handlers back off (see [KeyboardShortcutCatalog]).
 *
 * **Rebinding is a capture, not a text field**: the user presses the chord they want. While a row is
 * listening, [setGlobalHotkeyCapture] stands the OS claim down — otherwise the chords the app already owns
 * would be the one set of chords it could never hear, since the hook swallows them before Compose is handed
 * the key.
 *
 * Mirrors the other floating windows' drag-title / dismiss / raise-on-press pattern.
 */
@Composable
fun ShortcutsWindow(
    /** How the OS is delivering the system-wide chords right now (`GlobalHotkeys.claim`). */
    claim: GlobalHotkeyClaim,
    /** The account's chord **overrides** (`SchedulerState.shortcutBindings`); absent ⇒ the shipped chord. */
    bindings: Map<GlobalShortcut, ShortcutBinding>,
    /** Bind [GlobalShortcut] to the captured chord, or — with a null binding — back to its default. */
    onRebind: (GlobalShortcut, ShortcutBinding?) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    initialOffset: Offset = Offset.Zero,
    /** Persists the window's new drag position when a drag gesture ends (local-only geometry). */
    onOffsetChange: (Offset) -> Unit = {},
    onRaise: () -> Unit = {},
) {
    var offset by remember { mutableStateOf(initialOffset) }
    // The row currently listening for a chord, or null. Compose-only state: a capture in flight is not a
    // fact about the account, and closing the window must simply abandon it.
    var capturing by remember { mutableStateOf<GlobalShortcut?>(null) }
    // Why the last captured chord was refused, shown under the block until the next capture succeeds.
    var rejection by remember { mutableStateOf<String?>(null) }
    val captureFocus = remember { FocusRequester() }
    // Whether the listening row has actually been handed the keyboard yet, so the first (still unfocused)
    // report right after [FocusRequester.requestFocus] does not read as "the user clicked away".
    var captureHasFocus by remember { mutableStateOf(false) }

    // Read once per composition: `onDispose` would otherwise see the NEW value and never stand the claim
    // back up when a capture ends.
    val capturingNow = capturing
    DisposableEffect(capturingNow) {
        if (capturingNow != null) setGlobalHotkeyCapture(true)
        // Balanced on every exit — a chord taken, Escape, a click elsewhere, or the window closing.
        onDispose { if (capturingNow != null) setGlobalHotkeyCapture(false) }
    }
    LaunchedEffect(capturingNow) { if (capturingNow != null) captureFocus.requestFocus() }

    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surface,
        shadowElevation = 10.dp,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = modifier
            .offset { IntOffset(offset.x.roundToInt(), offset.y.roundToInt()) }
            // requiredWidth (not width) so the window keeps its fixed width whatever the content area is.
            .requiredWidth(470.dp)
            .raiseOnPress(onRaise)
            // Focusable ONLY while a row is listening: the window is an overlay over the tree, and a node
            // that could hold the keyboard the rest of the time would swallow the tree's own typing.
            .then(
                if (capturingNow == null) {
                    Modifier
                } else {
                    Modifier
                        // A press anywhere else abandons the capture. Without this the row would sit
                        // listening with the OS claim standing down — every chord dead until the user
                        // happened to come back and press Cancel.
                        .onFocusChanged { focus ->
                            if (focus.isFocused) {
                                captureHasFocus = true
                            } else if (captureHasFocus) {
                                captureHasFocus = false
                                capturing = null
                                rejection = null
                            }
                        }
                        .focusRequester(captureFocus)
                        .focusable()
                        .onPreviewKeyEvent { event ->
                            // Everything is consumed while listening, key-ups included: a chord half-read
                            // must not also reach whatever is underneath.
                            if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent true
                            if (event.key == Key.Escape) {
                                capturing = null
                                rejection = null
                                return@onPreviewKeyEvent true
                            }
                            // A modifier on its own, or a key outside the bindable set, is the user still
                            // reaching for the chord — keep listening.
                            val key = capturableKeys[event.key] ?: return@onPreviewKeyEvent true
                            val binding =
                                ShortcutBinding(
                                    key = key,
                                    ctrl = event.isCtrlPressed,
                                    shift = event.isShiftPressed,
                                    alt = event.isAltPressed,
                                )
                            val why = GlobalShortcutBindings.rejection(bindings, capturingNow, binding)
                            rejection = why
                            if (why == null) {
                                capturing = null
                                onRebind(capturingNow, binding)
                            }
                            true
                        }
                },
            ),
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
                val globalGroup = KeyboardShortcutCatalog.globalGroup(bindings)
                GroupBlock(globalGroup) {
                    Text(
                        text = claim.explain(),
                        style = MaterialTheme.typography.labelMedium,
                        color = if (claim == GlobalHotkeyClaim.Exclusive) {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        } else {
                            MaterialTheme.colorScheme.error
                        },
                    )
                    Box(
                        Modifier.fillMaxWidth().height(1.dp)
                            .background(MaterialTheme.colorScheme.outlineVariant),
                    )
                    GlobalShortcut.entries.forEach { shortcut ->
                        GlobalShortcutRow(
                            shortcut = shortcut,
                            chord = GlobalShortcutBindings.chordOf(bindings, shortcut),
                            // Only an override can be reset — a shortcut already on its shipped chord has
                            // nothing to put back.
                            overridden = shortcut in bindings,
                            listening = capturingNow == shortcut,
                            // The chords do nothing on a platform that cannot claim them, so there is
                            // nothing to rebind there either.
                            rebindable = claim != GlobalHotkeyClaim.Unsupported,
                            onStartCapture = {
                                rejection = null
                                captureHasFocus = false
                                capturing = shortcut
                            },
                            onCancelCapture = {
                                rejection = null
                                capturing = null
                            },
                            onReset = {
                                rejection = null
                                capturing = null
                                onRebind(shortcut, null)
                            },
                        )
                    }
                    rejection?.let {
                        Text(
                            text = it,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }
                    if (capturingNow != null) {
                        Text(
                            text = "Press the chord you want — at least " +
                                "${GlobalShortcutBindings.MIN_MODIFIERS} of Ctrl, Shift and Alt, then a " +
                                "letter, a digit or a function key. Escape cancels.",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }
                }

                KeyboardShortcutCatalog.fixedGroups.forEach { group ->
                    GroupBlock(group) {
                        Box(
                            Modifier.fillMaxWidth().height(1.dp)
                                .background(MaterialTheme.colorScheme.outlineVariant),
                        )
                        group.shortcuts.forEach { shortcut ->
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(top = 3.dp),
                                verticalAlignment = Alignment.Top,
                            ) {
                                ChordText(shortcut.keys)
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

/** The title + note heading every block shares; [body] is the block's own rows. */
@Composable
private fun GroupBlock(group: KeyboardShortcutGroup, body: @Composable () -> Unit) {
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
        body()
    }
}

/**
 * One rebindable system-wide chord: what it is bound to, what it does, and the controls to move it.
 *
 * While [listening] the chord column reads as a prompt instead of a chord — the row is the only place the
 * user can see that the app is waiting on their keyboard, and the OS claim is standing down for as long as
 * it says so.
 */
@Composable
private fun GlobalShortcutRow(
    shortcut: GlobalShortcut,
    chord: String,
    overridden: Boolean,
    listening: Boolean,
    rebindable: Boolean,
    onStartCapture: () -> Unit,
    onCancelCapture: () -> Unit,
    onReset: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (listening) {
            Text(
                text = "Press a chord…",
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.width(150.dp),
            )
        } else {
            ChordText(chord)
        }
        Spacer(Modifier.width(8.dp))
        Text(
            text = shortcut.action,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        if (rebindable) {
            Spacer(Modifier.width(8.dp))
            if (listening) {
                RowAction("Cancel", onCancelCapture)
            } else {
                RowAction("Rebind", onStartCapture)
                if (overridden) {
                    Spacer(Modifier.width(4.dp))
                    RowAction("Reset", onReset)
                }
            }
        }
    }
}

/** A chord, in the one style the whole window prints chords in. */
@Composable
private fun ChordText(chord: String) {
    Text(
        text = chord,
        style = MaterialTheme.typography.bodySmall,
        fontFamily = FontFamily.Monospace,
        modifier = Modifier.width(150.dp),
    )
}

/** The row's small outlined action — deliberately quiet, so the list still reads as a reference list. */
@Composable
private fun RowAction(label: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(4.dp))
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(4.dp))
            .background(Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 3.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
        )
    }
}

/**
 * Which Compose [Key] means which bindable [ShortcutKey] — the capture's whole vocabulary.
 *
 * Built through [ShortcutKey.byLabel] rather than by zipping two lists in the same order, so reordering
 * either enum can never silently map a press onto the wrong key.
 */
private val capturableKeys: Map<Key, ShortcutKey> =
    buildMap {
        val letters = listOf(
            Key.A, Key.B, Key.C, Key.D, Key.E, Key.F, Key.G, Key.H, Key.I, Key.J, Key.K, Key.L, Key.M,
            Key.N, Key.O, Key.P, Key.Q, Key.R, Key.S, Key.T, Key.U, Key.V, Key.W, Key.X, Key.Y, Key.Z,
        )
        ('A'..'Z').forEachIndexed { index, letter ->
            ShortcutKey.byLabel(letter.toString())?.let { put(letters[index], it) }
        }
        val digits = listOf(
            Key.Zero, Key.One, Key.Two, Key.Three, Key.Four,
            Key.Five, Key.Six, Key.Seven, Key.Eight, Key.Nine,
        )
        digits.forEachIndexed { digit, key -> ShortcutKey.byLabel(digit.toString())?.let { put(key, it) } }
        val functions = listOf(
            Key.F1, Key.F2, Key.F3, Key.F4, Key.F5, Key.F6,
            Key.F7, Key.F8, Key.F9, Key.F10, Key.F11, Key.F12,
        )
        functions.forEachIndexed { index, key -> ShortcutKey.byLabel("F${index + 1}")?.let { put(key, it) } }
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
