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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.zIndex

/**
 * PRD §7 *Lateral menu*: a button the user made at the bottom of the lateral menu from ONE window's ☆. Like
 * every button of the menu it asks for **one exact window**: of the kind [windowId] names (the frame id it was
 * made from, `Search` / `Search#2`, or a per-object window's [ObjectWindowKey]), with the configuration that
 * window had when the ☆ was pressed ([config]) — so a Search window's query, types, filters and sorting can be
 * kept as a button. That window is brought back when it is open, and opened anew otherwise.
 *
 * **Local-only view state**, kept by `App` in the window-placement store under [CustomMenuButtons.PLACEMENT_ID]:
 * a button names a window of THIS device, so it is not a fact about the account.
 */
@Serializable
data class CustomMenuButton(
    val id: String,
    @SerialName("window") val windowId: String,
    val title: String,
    /**
     * The window's configuration as its ☆ saved it, in the window's own stored form (a Search window's
     * `SearchDomain.Config.encode()`), or null for a window that has none. Absent from a button made before
     * 2026-09-26, which is given its window's configuration once, at load ([CustomMenuButtons.withConfigsFrozen]).
     */
    val config: String? = null,
    /**
     * User rule 2026-10-07: where the window stood and how its sections were shared when the button was made or last
     * UPDATED ([WindowLayout], [CustomMenuButtons.encodeLayout]) — the window the button opens is put back so. Null on
     * a button made before, which opens its window wherever a window of its kind opens.
     */
    val layout: String? = null,
    /**
     * User rule 2026-10-08: the menu holds **any kind of control**, not only windows' buttons. Set, this item IS that
     * control of the app ([MenuControl], by name) — a switch, a field, a button that acts — drawn in the menu by the
     * one code that draws it where it lives; [windowId] is then empty and [title] is the name the user gave it, blank
     * for the control's own. Null: a window's button, as every item was before.
     */
    val control: String? = null,
    /**
     * User rule 2026-10-08: set, this item is **an action of the Search window on the elements it was added with** —
     * a `SearchDomain.AddedAction` by name (Duplicate, Start, a field of theirs…), [config] being that Search
     * window's configuration then, its added elements included. Drawn by the action's own editor; greyed while none
     * of those elements is left for it to act on. [windowId] is then empty.
     */
    val action: String? = null,
)

/**
 * User rule 2026-10-08: **a control of the app that can stand in the lateral menu** — *"There can be any kind of button
 * in it, like switch buttons or fields."* Each is one thing the app already draws somewhere (the menu itself, the
 * calendar's configuration, an app setting's actions); in the menu it is drawn by that same code
 * (`MenuControlItem`). Named by [name] in a stored item, so an entry is never renamed; a new control is one more
 * entry here, a branch of `MenuControlItem`, and a [MenuAddable] round it where it lives.
 */
enum class MenuControl(val title: String) {
    Voice("Voice"),
    Notifications("Notifications"),
    LookAwayNow("Look away now"),
    SwitchTask("Switch task"),
    SleepWork("Sleep / Work"),
    Away("I'm away"),
    Online("Online"),
    AutoSchedule("Auto schedule"),
    Reminders("Reminders"),
    ScreenBreaks("Screen breaks"),
    CalendarDisplay("Display"),
    PlanTimeLimit("Time limit after a change"),
    MinimumTimeWeight("Minimum time weight"),
    SleepSchedule("Sleep"),
    SoundVolume("Global volume"),
    ;

    companion object {
        fun of(name: String?): MenuControl? = entries.firstOrNull { it.name == name }
    }
}

/**
 * User rule 2026-10-08, the menu's **"Customize"**: while [active], a right-click on a control of the app that can
 * stand in the menu ([MenuAddable]) offers ONE thing — "add in the left-side menu" ([add]) — *"just like the star
 * button in the header of a window"*. Compose-only: a mode of the session, off at every start.
 */
class MenuCustomizer {
    var active: Boolean by mutableStateOf(false)
    var add: (MenuControl) -> Unit = {}

    /** "add in the left-side menu", for an action of a Search window: its name, a title, that window's configuration. */
    var addAction: (action: String, title: String, config: String) -> Unit = { _, _, _ -> }
}

val LocalMenuCustomizer = androidx.compose.runtime.staticCompositionLocalOf<MenuCustomizer?> { null }

/**
 * [content] is the control [control] where it LIVES in the app. Nothing changes of it until the menu is being
 * customized ([MenuCustomizer.active]): it is then outlined, and a right-click anywhere on it — taken before the
 * control itself, a text field's own menu included — opens the one-entry menu that adds it to the lateral menu.
 */
@Composable
fun MenuAddable(control: MenuControl, modifier: Modifier = Modifier, content: @Composable () -> Unit) =
    MenuAddableBox(control, { it.add(control) }, modifier, content)

/**
 * [MenuAddable] for anything the menu can hold: [onAdd] says what "add in the left-side menu" adds (a control, or an
 * action of a Search window with the elements it acts on).
 */
@Composable
fun MenuAddableBox(key: Any?, onAdd: (MenuCustomizer) -> Unit, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val customizer = LocalMenuCustomizer.current
    val active = customizer?.active == true
    var menuOpen by remember(key) { mutableStateOf(false) }
    Box(
        modifier
            .then(
                if (!active) Modifier
                else Modifier
                    .border(1.dp, CalColors.accent, RoundedCornerShape(6.dp))
                    .secondaryPressFirst(key) { menuOpen = true },
            ),
    ) {
        content()
        if (active) {
            transientMenuDismissal(menuOpen) { menuOpen = false }
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }, properties = PopupProperties(focusable = false)) {
                DropdownMenuItem(
                    text = { Text("add in the left-side menu") },
                    onClick = {
                        menuOpen = false
                        customizer?.let(onAdd)
                    },
                )
            }
        }
    }
}

/**
 * A menu item that can do nothing (user rule 2026-10-08: "the button is grayed"): drawn faint, and every press and
 * release of the left button swallowed before what is under it. The right button is left alone — its menu still opens.
 */
internal fun Modifier.greyedAndDeaf(disabled: Boolean): Modifier =
    if (!disabled) this
    else graphicsLayer { alpha = 0.38f }.pointerInput(Unit) {
        awaitPointerEventScope {
            while (true) {
                val event = awaitPointerEvent(androidx.compose.ui.input.pointer.PointerEventPass.Initial)
                val pressOrRelease =
                    event.type == androidx.compose.ui.input.pointer.PointerEventType.Press ||
                        event.type == androidx.compose.ui.input.pointer.PointerEventType.Release
                if (pressOrRelease && !event.buttons.isSecondaryPressed) event.changes.forEach { it.consume() }
            }
        }
    }

/** Cut at its own bounds — an editor made for a wide section, shown in the narrow menu. */
internal fun Modifier.cutAtBounds(): Modifier = clipToBounds()

/**
 * A press of the left button HOLDS what is under it instead of pressing it: the press, its moves and its release are
 * taken before whatever is there (the Initial pass, consumed — so a switch is not flipped and a button opens nothing,
 * anomaly 2026-10-08), and told as a drag: [onStart], the vertical movement since the last event ([onDrag]), [onEnd].
 * The right button is left alone. On a node that does not move with the drag, so the movements are true.
 */
internal fun Modifier.heldDrag(key: Any?, onStart: () -> Unit, onDrag: (dy: Float) -> Unit, onEnd: () -> Unit): Modifier =
    pointerInput(key) {
        awaitPointerEventScope {
            var held = false
            while (true) {
                val event = awaitPointerEvent(androidx.compose.ui.input.pointer.PointerEventPass.Initial)
                if (event.buttons.isSecondaryPressed) continue
                when (event.type) {
                    androidx.compose.ui.input.pointer.PointerEventType.Press -> {
                        event.changes.forEach { it.consume() }
                        held = true
                        onStart()
                    }
                    androidx.compose.ui.input.pointer.PointerEventType.Move ->
                        if (held) {
                            val dy = event.changes.firstOrNull()?.let { it.position.y - it.previousPosition.y } ?: 0f
                            event.changes.forEach { it.consume() }
                            if (dy != 0f) onDrag(dy)
                        }
                    androidx.compose.ui.input.pointer.PointerEventType.Release -> {
                        event.changes.forEach { it.consume() }
                        if (held) {
                            held = false
                            onEnd()
                        }
                    }
                }
            }
        }
    }

/** A right-click taken BEFORE whatever is under it (the Initial pass, consumed): a field's or a switch's own answer never runs. */
internal fun Modifier.secondaryPressFirst(key: Any?, onPress: () -> Unit): Modifier =
    pointerInput(key) {
        awaitPointerEventScope {
            while (true) {
                val event = awaitPointerEvent(androidx.compose.ui.input.pointer.PointerEventPass.Initial)
                if (event.type == androidx.compose.ui.input.pointer.PointerEventType.Press && event.buttons.isSecondaryPressed) {
                    event.changes.forEach { it.consume() }
                    onPress()
                }
            }
        }
    }

/**
 * **A window's place and the lines between its sections**, as a button keeps them: its offset and size in the app
 * ([WindowFrameState]), [splits] — the Search window's two shares (left, top right), the calendar's
 * configuration width in dp, nothing for a window with one section — and [hidden], which of its sections are
 * retracted to their arrow (anomaly 2026-10-08: a section hidden was no change, and "Update" was not offered), in
 * the window's own order: the Search window's search, actions, added elements; the calendar's grid, configuration.
 */
@Serializable
data class WindowLayout(
    val x: Float,
    val y: Float,
    val width: Float = 0f,
    val height: Float = 0f,
    val splits: List<Float> = emptyList(),
    val hidden: List<Boolean> = emptyList(),
) {
    /** The same layout to the eye: a pixel, and a thousandth of a share, are no change. */
    fun sameAs(other: WindowLayout?): Boolean =
        other != null &&
            kotlin.math.abs(x - other.x) < 1f && kotlin.math.abs(y - other.y) < 1f &&
            kotlin.math.abs(width - other.width) < 1f && kotlin.math.abs(height - other.height) < 1f &&
            // A layout kept before the hidden sections were (none listed) hid none.
            hidden.any { it } == other.hidden.any { it } && (hidden.none { it } || hidden == other.hidden) &&
            splits.size == other.splits.size &&
            splits.indices.all { kotlin.math.abs(splits[it] - other.splits[it]) < if (splits[it] <= 1f && other.splits[it] <= 1f) 0.002f else 1f }
}

/**
 * One window's sections as it is looked at, in [WindowLayout]'s own terms: [splits] (the Search window's two shares,
 * the calendar's configuration width) and [hidden] (which sections are retracted).
 */
@Serializable
data class WindowSections(val splits: List<Float> = emptyList(), val hidden: List<Boolean> = emptyList())

/** The list of [CustomMenuButton]s, in menu order, and the few things done to it. Pure. */
object CustomMenuButtons {
    /** The placement row the list is kept on — no window has this frame id. */
    const val PLACEMENT_ID: String = "LateralMenu"

    /**
     * [seeded]: whether the list already holds the menu's own items ([DEFAULTS]). A list written before the whole menu
     * was the user's (2026-10-08) held only the buttons they had made, under a fixed part: read, it is given that fixed
     * part ONCE, on top ([decode]) — and from then on the user's removals are final.
     */
    @Serializable
    private data class Stored(val buttons: List<CustomMenuButton> = emptyList(), val seeded: Boolean = false)

    /**
     * User rule 2026-10-08: *"Make the entire left-side menu of the app customizable, except for the top button."*
     * **What the menu holds until the user changes it** — what its fixed part was, in its order: the two windows'
     * buttons, then the controls. Each an ordinary item: renamed, removed, added back like any other.
     */
    val DEFAULTS: List<CustomMenuButton> =
        listOf(
            CustomMenuButton("d-task-tree", "TaskTree", "Task tree"),
            CustomMenuButton("d-calendar", "Calendar", "Calendar"),
        ) + listOf(
            MenuControl.Voice, MenuControl.Notifications, MenuControl.LookAwayNow, MenuControl.SwitchTask,
            MenuControl.SleepWork, MenuControl.Away, MenuControl.Online,
        ).map { CustomMenuButton("d-" + it.name, "", "", control = it.name) }

    private val json = Json { ignoreUnknownKeys = true }

    fun encode(buttons: List<CustomMenuButton>): String = json.encodeToString(Stored.serializer(), Stored(buttons, seeded = true))

    /**
     * The placement row the window bar's tab names are kept on ([WindowFrameHost.tabTitles]) — frame id → the name
     * of the button that created that window. No window has this frame id.
     */
    const val TAB_TITLES_PLACEMENT_ID: String = "TabTitles"

    @Serializable
    private data class StoredTabTitles(val titles: Map<String, String> = emptyMap())

    fun encodeTabTitles(titles: Map<String, String>): String =
        json.encodeToString(StoredTabTitles.serializer(), StoredTabTitles(titles))

    /** [encodeTabTitles]' reverse; nothing stored, or nothing readable, is no name. */
    fun decodeTabTitles(text: String?): Map<String, String> =
        text?.let { runCatching { json.decodeFromString(StoredTabTitles.serializer(), it).titles }.getOrNull() }.orEmpty()

    /**
     * The placement row the open windows' colours are kept on ([WindowFrameHost.colors]) — frame id → `0xRRGGBB` — so
     * a window that comes back after a restart comes back in its colour. No window has this frame id.
     */
    const val WINDOW_COLORS_PLACEMENT_ID: String = "WindowColors"

    @Serializable
    private data class StoredWindowColors(val colors: Map<String, Int> = emptyMap())

    fun encodeWindowColors(colors: Map<String, Int>): String =
        json.encodeToString(StoredWindowColors.serializer(), StoredWindowColors(colors))

    /** [encodeWindowColors]' reverse; nothing stored, or nothing readable, is no colour. */
    fun decodeWindowColors(text: String?): Map<String, Int> =
        text?.let { runCatching { json.decodeFromString(StoredWindowColors.serializer(), it).colors }.getOrNull() }.orEmpty()

    /**
     * The placement row the open windows' SECTIONS are kept on ([WindowSections]) — frame id → the lines between its
     * sections and which are retracted — so a window that comes back after a restart comes back as it was looked at
     * (anomaly 2026-10-08: they lived in memory only, a restart put every line back in the middle, and a button's
     * "Update" then kept the middle). No window has this frame id.
     */
    const val WINDOW_SECTIONS_PLACEMENT_ID: String = "WindowSections"

    @Serializable
    private data class StoredWindowSections(val sections: Map<String, WindowSections> = emptyMap())

    fun encodeWindowSections(sections: Map<String, WindowSections>): String =
        json.encodeToString(StoredWindowSections.serializer(), StoredWindowSections(sections))

    /** [encodeWindowSections]' reverse; nothing stored, or nothing readable, is every window as it opens. */
    fun decodeWindowSections(text: String?): Map<String, WindowSections> =
        text?.let { runCatching { json.decodeFromString(StoredWindowSections.serializer(), it).sections }.getOrNull() }.orEmpty()

    /**
     * [encode]'s reverse. Nothing stored, or nothing readable, is the menu's own items ([DEFAULTS]); a list written
     * before those were items is given them on top, once ([Stored.seeded]).
     */
    fun decode(text: String?): List<CustomMenuButton> {
        val stored = text?.let { runCatching { json.decodeFromString(Stored.serializer(), it) }.getOrNull() } ?: return DEFAULTS
        return if (stored.seeded) stored.buttons else DEFAULTS + stored.buttons.filterNot { button -> DEFAULTS.any { it.id == button.id } }
    }

    /**
     * [buttons] with the control [control] at the bottom, and its item's id — the one it already has when the menu
     * holds that control (a control stands in the menu once: two of one switch would be one switch twice).
     */
    fun addedControl(buttons: List<CustomMenuButton>, control: MenuControl): Pair<List<CustomMenuButton>, String> {
        buttons.firstOrNull { it.control == control.name }?.let { return buttons to it.id }
        val used = buttons.map { it.id }.toSet()
        val id = generateSequence(1) { it + 1 }.map { "c$it" }.first { it !in used }
        return buttons + CustomMenuButton(id, "", "", control = control.name) to id
    }

    /** [buttons] with a new one for [windowId] at the bottom, and its id — the first `b<n>` no button holds. */
    fun added(
        buttons: List<CustomMenuButton>,
        windowId: String,
        title: String,
        config: String? = null,
    ): Pair<List<CustomMenuButton>, String> {
        val used = buttons.map { it.id }.toSet()
        val id = generateSequence(1) { it + 1 }.map { "b$it" }.first { it !in used }
        return buttons + CustomMenuButton(id, windowId, title, config) to id
    }

    /** [buttons] with an item for the Search action [action] on the elements [config] holds, at the bottom, and its id. */
    fun addedAction(buttons: List<CustomMenuButton>, action: String, title: String, config: String): Pair<List<CustomMenuButton>, String> {
        val used = buttons.map { it.id }.toSet()
        val id = generateSequence(1) { it + 1 }.map { "a$it" }.first { it !in used }
        return buttons + CustomMenuButton(id, "", title, config, action = action) to id
    }

    /**
     * User rule 2026-10-08, the customization mode's drag: [buttons] with [id] moved to stand just before [beforeId] —
     * at the very end when that is null or names no item. Said by the item it lands before, never by an index: the
     * menu draws only the items it can ([shown]), and an index among those is not one in the list.
     */
    fun moved(buttons: List<CustomMenuButton>, id: String, beforeId: String?): List<CustomMenuButton> {
        val item = buttons.firstOrNull { it.id == id } ?: return buttons
        if (beforeId == id) return buttons
        val rest = buttons.filterNot { it.id == id }
        val at = rest.indexOfFirst { it.id == beforeId }.takeIf { it >= 0 } ?: rest.size
        return rest.subList(0, at) + item + rest.subList(at, rest.size)
    }

    /**
     * Where an item dragged among the menu's drawn items lands: before the first of the [others] (each an id and the
     * middle of its row, top to bottom, the dragged one left out) whose middle lies below [draggedMiddle]; null = after
     * them all.
     */
    fun dropBefore(others: List<Pair<String, Float>>, draggedMiddle: Float): String? =
        others.firstOrNull { it.second > draggedMiddle }?.first

    /** [buttons] with [id] renamed — a blank title keeps the one it had. */
    fun renamed(buttons: List<CustomMenuButton>, id: String, title: String): List<CustomMenuButton> =
        if (title.isBlank()) buttons else buttons.map { if (it.id == id) it.copy(title = title.trim()) else it }

    fun removed(buttons: List<CustomMenuButton>, id: String): List<CustomMenuButton> = buttons.filterNot { it.id == id }

    fun encodeLayout(layout: WindowLayout): String = json.encodeToString(WindowLayout.serializer(), layout)

    /** [encodeLayout]'s reverse; nothing stored, or nothing readable, is no layout. */
    fun decodeLayout(text: String?): WindowLayout? =
        text?.let { runCatching { json.decodeFromString(WindowLayout.serializer(), it) }.getOrNull() }

    /**
     * User rule 2026-10-07, the button menu's **"Update"**: whether the window the button opened is no longer what
     * the button holds — its configuration ([config], as the window holds it now, against [savedConfig], the
     * button's read the same way) or its layout ([layout] against the button's own, or — for a button that never
     * kept one — against [opened], the layout its window had when the button opened it). No window: nothing to say.
     */
    fun needsUpdate(button: CustomMenuButton, savedConfig: String?, config: String?, layout: WindowLayout?, opened: WindowLayout?): Boolean {
        if (layout == null) return false
        if (config != savedConfig) return true
        // A button that never kept a layout, about a window it is not known to have opened so (one that came back
        // at a restart): there is a state to save, and saving it is what ends the question.
        val reference = decodeLayout(button.layout) ?: opened ?: return true
        return !layout.sameAs(reference)
    }

    /** [buttons] with [id] holding its window's state as it stands: [config] and [layout]. */
    fun updated(buttons: List<CustomMenuButton>, id: String, config: String?, layout: WindowLayout?): List<CustomMenuButton> =
        buttons.map { if (it.id != id) it else it.copy(config = config ?: it.config, layout = layout?.let(::encodeLayout) ?: it.layout) }

    /**
     * [buttons] with every button that has no [CustomMenuButton.config] given [configOf] its window — once, when the
     * list is loaded: a button made before the snapshot existed then keeps ONE configuration, instead of following
     * whatever its window becomes. A window with no configuration answers null and leaves the button as it is.
     */
    fun withConfigsFrozen(buttons: List<CustomMenuButton>, configOf: (windowId: String) -> String?): List<CustomMenuButton> =
        buttons.map { button -> if (button.config != null) button else configOf(button.windowId)?.let { button.copy(config = it) } ?: button }
}

/**
 * User rule 2026-10-08: **the lateral menu, all of it but the page button** — every item the user's
 * ([CustomMenuButton]): a window's button, or a control of the app ([MenuControl], drawn by [controlContent]). They
 * all have the SAME right-click menu: Update (a window's button whose window changed), Rename, Remove, and Customize
 * ([customizing], [onCustomize]) — which is also what a right-click beside them offers, so a menu emptied of
 * everything can still be filled again. While customizing, a line at the top says so and ends it.
 *
 * (Formerly) the lateral menu's bottom section: the buttons the user made ([CustomMenuButton]), drawn like the menu's own.
 * [editingId]'s title is an edit field instead, opened with the whole title selected — so typing replaces it and
 * Enter keeps it. Enter or leaving the field commits ([onRename]; a blank title keeps the old one), Escape keeps
 * the title it had. A right-click offers Rename (the same field) and Remove — and **Update** (user rule 2026-10-07)
 * while the window the button opened is open and is no longer what the button holds ([canUpdate], asked when the menu
 * opens): the button then keeps that window's state as it stands ([onUpdate]).
 */
@Composable
internal fun CustomMenuSection(
    buttons: List<CustomMenuButton>,
    onClick: (CustomMenuButton) -> Unit,
    editingId: String?,
    onStartRename: (id: String) -> Unit,
    onRename: (id: String, title: String) -> Unit,
    onEditDone: () -> Unit,
    onRemove: (id: String) -> Unit,
    canUpdate: (CustomMenuButton) -> Boolean = { false },
    onUpdate: (CustomMenuButton) -> Unit = {},
    /** Whether the menu is being customized ([MenuCustomizer.active]), and the menu entry that starts or ends it. */
    customizing: Boolean = false,
    onCustomize: (Boolean) -> Unit = {},
    /** Draws the control an item is, under the name the user gave it (null: the control's own). */
    controlContent: @Composable (control: MenuControl, title: String?) -> Unit = { _, _ -> },
    /** The customization mode's drag: the item [id] dropped just before [beforeId] (null: at the end). */
    onMove: (id: String, beforeId: String?) -> Unit = { _, _ -> },
    /** Draws an item that is a Search action on its elements ([CustomMenuButton.action]). */
    actionContent: @Composable (CustomMenuButton) -> Unit = {},
) {
    // User rule 2026-10-08: "in customization mode, the user can drag the buttons or fields in the left-side menu".
    // Each drawn item's row (its top and height in this column), the one held and how far the hand has it. The held
    // item follows the hand; a line says where a release would put it ([CustomMenuButtons.dropBefore]).
    val rows = remember { androidx.compose.runtime.mutableStateMapOf<String, Pair<Float, Float>>() }
    var draggedId by remember { mutableStateOf<String?>(null) }
    var dragOffset by remember { mutableStateOf(0f) }
    val shownIds = buttons.map { it.id }
    fun dropTarget(): String? {
        val id = draggedId ?: return null
        val (top, height) = rows[id] ?: return null
        val others = shownIds.filter { it != id }.mapNotNull { other -> rows[other]?.let { other to it.first + it.second / 2f } }
        return CustomMenuButtons.dropBefore(others, top + height / 2f + dragOffset)
    }
    val lineColor = CalColors.accent
    Column(
        verticalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier.drawWithContent {
            drawContent()
            val id = draggedId ?: return@drawWithContent
            val before = dropTarget()
            val others = shownIds.filter { it != id }
            val y =
                if (before != null) rows[before]?.let { it.first - 5.dp.toPx() }
                else others.lastOrNull()?.let { rows[it] }?.let { it.first + it.second + 5.dp.toPx() }
            if (y != null) drawLine(lineColor, Offset(0f, y), Offset(size.width, y), strokeWidth = 2.dp.toPx())
        },
    ) {
        if (customizing) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .border(1.dp, CalColors.accent, RoundedCornerShape(8.dp))
                    .padding(horizontal = 10.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    "Customizing: right-click an outlined setting or button to add it here.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                MenuButton(label = "Done", active = true, onClick = { onCustomize(false) })
            }
        }
        for (button in buttons) {
            if (button.id == editingId) {
                CustomMenuTitleField(button, onRename, onEditDone)
            } else {
                val control = MenuControl.of(button.control)
                var menuOpen by remember(button.id) { mutableStateOf(false) }
                Box(
                    // While the menu is being customized its items do NOTHING at a press (anomaly 2026-10-08: a switch
                    // was still flipped, a button still opened its window): they are there to be renamed and removed.
                    // Only their right-click menu answers.
                    // …and a press held and moved DRAGS the item to another place of the menu ([heldDrag], which is
                    // also what swallows the press). The row is measured here, on the box that does not move; what
                    // follows the hand is the box inside it.
                    Modifier
                        .onGloballyPositioned { rows[button.id] = it.positionInParent().y to it.size.height.toFloat() }
                        .then(
                            if (!customizing) Modifier
                            else Modifier.heldDrag(
                                button.id,
                                onStart = { draggedId = button.id; dragOffset = 0f },
                                onDrag = { dy -> if (draggedId == button.id) dragOffset += dy },
                                onEnd = {
                                    if (draggedId == button.id) {
                                        val before = dropTarget()
                                        val moved = kotlin.math.abs(dragOffset) > 4f
                                        draggedId = null
                                        dragOffset = 0f
                                        if (moved) onMove(button.id, before)
                                    }
                                },
                            ),
                        )
                        // A control answers a right-click itself (a field's own menu, a switch): the item's menu is
                        // asked first. A window's button keeps the app's one right-click gesture.
                        .then(
                            if (control != null || button.action != null) Modifier.secondaryPressFirst(button.id) { menuOpen = true }
                            else Modifier,
                        ),
                ) {
                    Box(
                        Modifier
                            .zIndex(if (draggedId == button.id) 1f else 0f)
                            .graphicsLayer {
                                translationY = if (draggedId == button.id) dragOffset else 0f
                                alpha = if (draggedId == button.id) 0.85f else 1f
                            },
                    ) {
                    if (button.action != null) {
                        actionContent(button)
                    } else if (control != null) {
                        controlContent(control, button.title.takeIf { it.isNotBlank() })
                    } else {
                        MenuButton(
                            label = button.title,
                            active = false,
                            onClick = { onClick(button) },
                            modifier = contextMenuModifier(enabled = true, key = button.id) { menuOpen = true },
                        )
                    }
                    }
                    transientMenuDismissal(menuOpen) { menuOpen = false }
                    DropdownMenu(
                        expanded = menuOpen,
                        onDismissRequest = { menuOpen = false },
                        properties = PopupProperties(focusable = false),
                    ) {
                        if (canUpdate(button)) {
                            DropdownMenuItem(text = { Text("Update") }, onClick = { menuOpen = false; onUpdate(button) })
                        }
                        DropdownMenuItem(text = { Text("Rename") }, onClick = { menuOpen = false; onStartRename(button.id) })
                        DropdownMenuItem(text = { Text("Remove") }, onClick = { menuOpen = false; onRemove(button.id) })
                        DropdownMenuItem(
                            text = { Text(if (customizing) "Stop customizing" else "Customize") },
                            onClick = { menuOpen = false; onCustomize(!customizing) },
                        )
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
    // The whole title selected: the first key typed replaces it, Enter keeps it. A control not renamed yet starts
    // from its own name.
    val shown = button.title.ifBlank { MenuControl.of(button.control)?.title.orEmpty() }
    var value by remember(button.id) { mutableStateOf(TextFieldValue(shown, TextRange(0, shown.length))) }
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
