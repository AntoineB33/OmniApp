package org.example.project.ui

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.example.project.scheduler.platform.GlobalShortcut
import org.example.project.scheduler.platform.GlobalShortcutBindings
import org.example.project.scheduler.platform.ShortcutBinding

/**
 * User rule 2026-10-12: **one chord mapped to one window** — an element of the user's own, made with "New" (the
 * Search window's keyboard-shortcut actions, or its "New keyboard shortcut" creation row) and then given its
 * [chord] and its [window], in either order: until it has both it claims nothing.
 */
data class WindowShortcut(
    val id: String,
    /** The window's frame id: a lateral-menu window (`Search`), a copy of one (`Search#2`) or a per-object window. */
    val window: String? = null,
    val chord: ShortcutBinding? = null,
)

/**
 * User rule 2026-10-12: **the chords mapped to a window** — *"Allow the user to map any shortcut to any window. The
 * defined shortcut would then put focus on the app and put focus on the mapped window."*
 *
 * Each is a system-wide chord like the five [GlobalShortcut]s (it is struck from another application, so it is
 * claimed from the OS by the same hook, `installGlobalHotkeys`), under the same two rules — at least
 * [GlobalShortcutBindings.MIN_MODIFIERS] of Ctrl/Shift/Alt, and one chord for one thing ([rejection]).
 *
 * What it is mapped to is a window by its **frame id**, which is a fact about THIS device's windows, as their places
 * and their colours are. So the list is **local-only view state**: kept on a placement row of its own
 * ([PLACEMENT_ID]), never synced, not an Undo/Redo unit. A mapping outlives its window closing: a lateral-menu
 * window is opened again by its chord.
 */
object WindowShortcuts {
    /** The placement row the list is kept on. No window has this frame id. */
    const val PLACEMENT_ID: String = "WindowShortcuts"

    /**
     * What a mapping's id wears as a keyboard-shortcut element of the Search window, where the five shortcuts are
     * named by their own names: `window:ws-3`.
     */
    const val SEARCH_PREFIX: String = org.example.project.scheduler.domain.SearchDomain.WINDOW_SHORTCUT_PREFIX

    private val json = Json { ignoreUnknownKeys = true }

    /** A chord is kept as its spelling ([ShortcutBinding.chord]): `Ctrl+Alt+K`. */
    @Serializable
    private data class StoredMap(val id: String = "", val window: String? = null, val chord: String? = null)

    /** [chords] is the first shape written (2026-10-12, frame id → chord): still read, never written. */
    @Serializable
    private data class Stored(val maps: List<StoredMap> = emptyList(), val chords: Map<String, String> = emptyMap())

    fun encode(shortcuts: List<WindowShortcut>): String =
        json.encodeToString(Stored.serializer(), Stored(shortcuts.map { StoredMap(it.id, it.window, it.chord?.chord) }))

    /**
     * [encode]'s reverse; nothing stored, or nothing readable, is no mapping. HEALED on the way: an entry with no id
     * or an id already read is given one, a chord that spells none or is under the modifiers' floor is left unset,
     * and the second entry on a chord already read loses it.
     */
    fun decode(text: String?): List<WindowShortcut> {
        val stored = text?.let { runCatching { json.decodeFromString(Stored.serializer(), it) }.getOrNull() } ?: return emptyList()
        val raw = stored.maps + stored.chords.map { (window, chord) -> StoredMap("", window, chord) }
        val out = ArrayList<WindowShortcut>()
        for (entry in raw) {
            val chord =
                entry.chord?.let(ShortcutBinding::parse)
                    ?.takeIf { it.modifierCount >= GlobalShortcutBindings.MIN_MODIFIERS && out.none { kept -> kept.chord == it } }
            val id = entry.id.takeIf { it.isNotBlank() && out.none { kept -> kept.id == it } } ?: mintId(out)
            out += WindowShortcut(id, entry.window?.takeIf { it.isNotBlank() }, chord)
        }
        return out
    }

    /** An id no mapping of [existing] has: `ws-0`, `ws-1`… */
    fun mintId(existing: List<WindowShortcut>): String {
        val used = existing.mapTo(HashSet()) { it.id }
        return generateSequence(0) { it + 1 }.map { "ws-$it" }.first { it !in used }
    }

    /** The chords to claim from the OS: those of the mappings that have BOTH a chord and a window. */
    fun chordsOf(shortcuts: List<WindowShortcut>): Set<ShortcutBinding> =
        shortcuts.filter { it.window != null }.mapNotNullTo(LinkedHashSet()) { it.chord }

    /** The window [binding] is mapped to, or null. */
    fun windowOf(shortcuts: List<WindowShortcut>, binding: ShortcutBinding): String? =
        shortcuts.firstOrNull { it.chord == binding && it.window != null }?.window

    /**
     * Why the mapping [id] cannot be given [binding], or null when it can: the system-wide chords' own floor of
     * modifiers, a chord one of the app's shortcuts is on ([globals], the account's overrides), or one another
     * mapping has ([titleOf] names its window). Giving a mapping the chord it already has is accepted.
     */
    fun rejection(
        globals: Map<GlobalShortcut, ShortcutBinding>,
        shortcuts: List<WindowShortcut>,
        id: String?,
        binding: ShortcutBinding,
        titleOf: (String) -> String = { it },
    ): String? {
        if (binding.modifierCount < GlobalShortcutBindings.MIN_MODIFIERS) {
            return "A system-wide chord needs at least ${GlobalShortcutBindings.MIN_MODIFIERS} of Ctrl, Shift and Alt — it is " +
                "swallowed before any other application sees it."
        }
        GlobalShortcutBindings.resolve(globals).entries.firstOrNull { it.value == binding }?.let {
            return "Already bound to \"${it.key.action}\"."
        }
        return heldByWindow(shortcuts, binding, titleOf, except = id)
    }

    /** The sentence for a chord a mapping already has (other than [except]), or null: what a shortcut may not take. */
    fun heldByWindow(
        shortcuts: List<WindowShortcut>,
        binding: ShortcutBinding,
        titleOf: (String) -> String = { it },
        except: String? = null,
    ): String? =
        shortcuts.firstOrNull { it.id != except && it.chord == binding }?.let { held ->
            held.window?.let { "Already the chord of the window \"${titleOf(it)}\"." } ?: "Already the chord of another window shortcut."
        }

    /** [shortcuts] with the mapping [changed] names put in its place — or added, when it is a new one. */
    fun with(shortcuts: List<WindowShortcut>, changed: WindowShortcut): List<WindowShortcut> =
        if (shortcuts.any { it.id == changed.id }) shortcuts.map { if (it.id == changed.id) changed else it } else shortcuts + changed
}
