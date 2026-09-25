package org.example.project.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.PopupProperties
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.example.project.scheduler.ui.contextMenuModifier

/**
 * PRD §7 *Lateral menu*: a button the user made at the bottom of the lateral menu for ONE window — the window
 * itself or one of its copies ([windowId] is its frame id: `Search`, `Search#2`). Made by the ☆ in that window's
 * head; pressing it does what the window's own menu button does.
 *
 * **Local-only view state**, kept by `App` in the window-placement store under [CustomMenuButtons.PLACEMENT_ID]:
 * a button names a window of THIS device (a copy exists only here), so it is not a fact about the account.
 */
@Serializable
data class CustomMenuButton(
    val id: String,
    @SerialName("window") val windowId: String,
    val title: String,
)

/** The list of [CustomMenuButton]s, in menu order, and the few things done to it. Pure. */
object CustomMenuButtons {
    /** The placement row the list is kept on — no window has this frame id. */
    const val PLACEMENT_ID: String = "LateralMenu"

    @Serializable
    private data class Stored(val buttons: List<CustomMenuButton> = emptyList())

    private val json = Json { ignoreUnknownKeys = true }

    fun encode(buttons: List<CustomMenuButton>): String = json.encodeToString(Stored.serializer(), Stored(buttons))

    /** [encode]'s reverse; nothing stored, or nothing readable, is no button. */
    fun decode(text: String?): List<CustomMenuButton> =
        text?.let { runCatching { json.decodeFromString(Stored.serializer(), it).buttons }.getOrNull() }.orEmpty()

    /** [buttons] with a new one for [windowId] at the bottom, and its id — the first `b<n>` no button holds. */
    fun added(buttons: List<CustomMenuButton>, windowId: String, title: String): Pair<List<CustomMenuButton>, String> {
        val used = buttons.map { it.id }.toSet()
        val id = generateSequence(1) { it + 1 }.map { "b$it" }.first { it !in used }
        return buttons + CustomMenuButton(id, windowId, title) to id
    }

    /** [buttons] with [id] renamed — a blank title keeps the one it had. */
    fun renamed(buttons: List<CustomMenuButton>, id: String, title: String): List<CustomMenuButton> =
        if (title.isBlank()) buttons else buttons.map { if (it.id == id) it.copy(title = title.trim()) else it }

    fun removed(buttons: List<CustomMenuButton>, id: String): List<CustomMenuButton> = buttons.filterNot { it.id == id }
}

/**
 * The lateral menu's bottom section: the buttons the user made ([CustomMenuButton]), drawn like the menu's own.
 * [editingId]'s title is an edit field instead, opened with the whole title selected — so typing replaces it and
 * Enter keeps it. Enter or leaving the field commits ([onRename]; a blank title keeps the old one), Escape keeps
 * the title it had. A right-click offers Rename (the same field) and Remove.
 */
@Composable
internal fun CustomMenuSection(
    buttons: List<CustomMenuButton>,
    isOpen: (windowId: String) -> Boolean,
    onClick: (CustomMenuButton) -> Unit,
    editingId: String?,
    onStartRename: (id: String) -> Unit,
    onRename: (id: String, title: String) -> Unit,
    onEditDone: () -> Unit,
    onRemove: (id: String) -> Unit,
) {
    if (buttons.isEmpty()) return
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        HorizontalDivider(color = CalColors.grid)
        for (button in buttons) {
            if (button.id == editingId) {
                CustomMenuTitleField(button, onRename, onEditDone)
            } else {
                var menuOpen by remember(button.id) { mutableStateOf(false) }
                Box {
                    MenuButton(
                        label = button.title,
                        active = isOpen(button.windowId),
                        onClick = { onClick(button) },
                        modifier = contextMenuModifier(enabled = true, key = button.id) { menuOpen = true },
                    )
                    transientMenuDismissal(menuOpen) { menuOpen = false }
                    DropdownMenu(
                        expanded = menuOpen,
                        onDismissRequest = { menuOpen = false },
                        properties = PopupProperties(focusable = false),
                    ) {
                        DropdownMenuItem(text = { Text("Rename") }, onClick = { menuOpen = false; onStartRename(button.id) })
                        DropdownMenuItem(text = { Text("Remove") }, onClick = { menuOpen = false; onRemove(button.id) })
                    }
                }
            }
        }
    }
}

@Composable
private fun CustomMenuTitleField(
    button: CustomMenuButton,
    onRename: (id: String, title: String) -> Unit,
    onEditDone: () -> Unit,
) {
    // The whole title selected: the first key typed replaces it, Enter keeps it.
    var value by remember(button.id) { mutableStateOf(TextFieldValue(button.title, TextRange(0, button.title.length))) }
    val focus = remember { FocusRequester() }
    var focused by remember { mutableStateOf(false) }
    var done by remember(button.id) { mutableStateOf(false) }
    fun finish(commit: Boolean) {
        if (done) return
        done = true
        if (commit) onRename(button.id, value.text)
        onEditDone()
    }
    LaunchedEffect(button.id) { runCatching { focus.requestFocus() } }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .border(1.dp, CalColors.accent, RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surface)
            .clickable { runCatching { focus.requestFocus() } }
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        BasicTextField(
            value = value,
            onValueChange = { value = it },
            singleLine = true,
            textStyle = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.onSurface),
            cursorBrush = SolidColor(CalColors.accent),
            modifier = Modifier
                .fillMaxWidth()
                .focusRequester(focus)
                .onFocusChanged {
                    // Leaving the field commits, as Enter does — but only once it has held the focus.
                    if (focused && !it.isFocused) finish(commit = true)
                    focused = it.isFocused
                }
                .onPreviewKeyEvent { event ->
                    if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                    when (event.key) {
                        Key.Enter, Key.NumPadEnter -> { finish(commit = true); true }
                        Key.Escape -> { finish(commit = false); true }
                        else -> false
                    }
                },
        )
    }
}
