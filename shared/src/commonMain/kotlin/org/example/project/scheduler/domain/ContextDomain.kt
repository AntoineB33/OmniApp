package org.example.project.scheduler.domain

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import org.example.project.scheduler.state.SchedulerState

/**
 * User rule 2026-11-25: **a context's default, as the user changed it** — the configuration a place of the app opens its
 * rows with ([ContextDomain.SearchContext]), stored whole ([SearchDomain.Config.encode]). Authoritative, persisted and
 * synced, one row per context (`persistence.md`); a context the user never changed has none, its default being the code's.
 */
data class ContextDefault(val id: String, val config: String)

/**
 * User rule 2026-11-25: *"It is only search configuration elements that define what row is shown in a window. What is
 * built in code is the default values of context-values of search configuration elements."* **The contexts** — the places
 * of the app that show rows, each with its default configuration written here, the one place defaults are built — and
 * each search configuration's **state in each context**, grouped by state ([groups]).
 */
object ContextDomain {
    /** One context: its [id] (its element's), what it is called, and the configuration the code gives it. */
    data class SearchContext(val id: String, val title: String, val default: SearchDomain.Config)

    /** A Search window opened on nothing. */
    const val SEARCH_WINDOW: String = "search-window"

    /** A drop-down's list no context of its own names ([menuContextId]). */
    const val DROP_DOWN: String = "drop-down"

    /** What a right-click menu's context id starts with, before its name. */
    private const val MENU_PREFIX: String = "menu:"

    /**
     * The right-click menus, by the name each is drawn under (`ActionMenu`'s first argument) and what the user calls it.
     * A menu missing here opens with [DROP_DOWN]'s configuration (`ContextCatalogTest` lists them all).
     */
    val RIGHT_CLICK_MENUS: List<Pair<String, String>> = listOf(
        "task cell menu" to "a task cell",
        "ItemResultRow" to "an element row",
        "ElementChildren" to "a child row",
        "ElementChildren blank" to "the blank row ending children",
        "TaskPathBox" to "a task row's path",
        "TaskPathBox#2" to "a line of a task row's paths",
        "TaskIdentityRow" to "a task row of an Edit Mode menu",
        "EditMenuRow" to "a row of an Edit Mode menu",
        "SearchPartsMenu" to "the background of a Search window",
        "calendar grid menu" to "the calendar",
        "calendar held drag menu" to "a block held on the calendar",
        "LateralMenu" to "the background of the left-side menu",
        "lateral menu button menu" to "a button of the left-side menu",
        "MenuAddableBox" to "a box the left-side menu can add",
        "HeadMenu" to "a window's head",
        "window bar menu" to "the window bar",
        "WindowBarMoreButton" to "the window bar's more button",
        "priority column menu" to "the priority percentage",
        "priority weight column menu" to "a weight table's percentage",
        "RelativePriorityWindow" to "a relative priority row",
        "NudgeMenu" to "a time nudge button",
        "time nudge menu" to "an alarm's time nudge",
        "TreeRowMenu" to "a row of the configurations tree",
    )

    /**
     * User rule 2026-11-25 (*"for the right-click menu of a task element, there would be a search configuration that is on
     * in this context and that says put 'add', then 'add and remove', then…"*): **the entries of a row's menu, by id, in
     * their order** — the default "Entries, in order" of every row menu ([ROW_MENUS]). The code gives each entry its id
     * (`MenuAction.id`); which are listed, and where, is this.
     */
    val ROW_MENU_ENTRIES: List<String> = listOf(
        "start this task now", "add", "add and remove the others", "remove", "remove the others", "show only its actions", "pin",
        "check only this", "open in Task relations", "open in Keyboard shortcuts", "show window", "create", "apply",
        "edit task", "copy id (ctrl c)", "copy path", "deep copy", "collapse",
    )

    /** The right-click menus that are a row's: they list [ROW_MENU_ENTRIES] by default. */
    val ROW_MENUS: Set<String> = setOf(
        "task cell menu", "ItemResultRow", "ElementChildren", "ElementChildren blank", "TaskPathBox",
        "TaskIdentityRow", "EditMenuRow",
    )

    /** How many sections deep a background menu names its sections' groups ([MENU_ENTRIES]' "SearchPartsMenu"). */
    private const val NESTED_SECTION_GROUPS: Int = 3

    /** A Search window's background entries ("Show / Hide the header"…), by id, the window's or a section's. */
    private val SEARCH_PARTS: List<String> = listOf("the header", "the search bar", "the search", "the actions", "the added elements")

    /**
     * User rule 2026-11-25 (*"Make sure the logic is the same everywhere."*): **the default "Entries, in order" of every
     * right-click menu** — the row menus' [ROW_MENU_ENTRIES], and each other menu's own. A row that stands for data (a
     * task's paths, the time steps, the calendar's choices) has its group's id: the group stands where its id does.
     */
    val MENU_ENTRIES: Map<String, List<String>> by lazy {
        ROW_MENUS.associateWith { ROW_MENU_ENTRIES } + mapOf(
            "TaskPathBox#2" to listOf("paths"),
            // Anomaly 2026-11-26: a section inside a section is a group of its own, after its separator — "section 2 …", a
            // third level "section 3 …" (`sectionGroups`); unnamed here, its entries were dropped from the menu.
            "SearchPartsMenu" to listOf("Reset", "Copy id") + SEARCH_PARTS + "separator" + SEARCH_PARTS.map { "section $it" } +
                (2..NESTED_SECTION_GROUPS).flatMap { depth -> listOf("separator") + SEARCH_PARTS.map { "section $depth $it" } },
            "calendar grid menu" to listOf("element here", "choices", "edit…", "move", "add…", "day…", "display…", "lock…"),
            "calendar held drag menu" to listOf("cancel", "resume drag"),
            "LateralMenu" to listOf("Reset"),
            "lateral menu button menu" to listOf("Update", "Rename", "all pages", "match the whole state", "Remove", "Reset"),
            "MenuAddableBox" to listOf("add in the left-side menu"),
            "HeadMenu" to listOf(
                "Rename", "Copy id", "Remove from menu", "Copy all", "Add a button for this window to the left menu", "Duplicate",
                "Fill the width", "Fill the height", "Reduce", "Maximize", "Close",
            ),
            "window bar menu" to listOf("close selection", "minimize selection", "open selection", "global"),
            "WindowBarMoreButton" to listOf("undo", "redo"),
            "priority column menu" to listOf("relative priority", "priority weights"),
            "priority weight column menu" to listOf("Add column to the right", "Reset to default", "Delete column"),
            "RelativePriorityWindow" to listOf("relative to"),
            "NudgeMenu" to listOf("time steps"),
            "time nudge menu" to listOf("time steps"),
            "TreeRowMenu" to listOf("add"),
        )
    }

    /** Every context, in the order the action lists them. */
    val CONTEXTS: List<SearchContext> by lazy {
        listOf(SearchContext(SEARCH_WINDOW, "default of a Search window", SearchDomain.Config())) +
            RIGHT_CLICK_MENUS.map { (name, what) ->
                SearchContext(MENU_PREFIX + name, "default of the right-click menu of $what", SearchDomain.OPTION_SEARCH.copy(entries = MENU_ENTRIES[name].orEmpty()))
            } +
            listOf(
                SearchContext(DROP_DOWN, "default of a drop-down", SearchDomain.OPTION_SEARCH),
                SearchContext("integers", "default of a list of integers", SearchDomain.INTEGER_SEARCH),
            )
    }

    private val byId: Map<String, SearchContext> by lazy { CONTEXTS.associateBy { it.id } }

    fun context(id: String): SearchContext? = byId[id]

    /** The context a menu drawn under [name] opens in: its own where it is a right-click menu, else a drop-down's. */
    fun menuContextId(name: String, rightClick: Boolean): String =
        (MENU_PREFIX + name).takeIf { rightClick && it in byId } ?: DROP_DOWN

    /** The key of a context's element ([SearchDomain.Kind.Context]). */
    fun keyOf(id: String): String = SearchDomain.Kind.Context.name + "/" + id

    /** **What a context opens with** — the user's default where they changed it ([ContextDefault]), else the code's. */
    fun configOf(defaults: List<ContextDefault>, id: String): SearchDomain.Config {
        val base = context(id)?.default ?: SearchDomain.Config()
        val stored = defaults.firstOrNull { it.id == id } ?: return base
        return SearchDomain.Config.decode(stored.config) ?: base
    }

    fun configOf(state: SchedulerState, id: String): SearchDomain.Config = configOf(state.contextDefaults, id)

    /** One group of [groups]: whether [setting] is on in its [contexts]. */
    data class StateGroup(val on: Boolean, val contexts: List<SearchContext>)

    /**
     * User rule 2026-11-25: **a search configuration's state in each context, grouped by state** — the contexts where it is
     * on, then those where it is off; a group with no context is not listed. Off everywhere: one group.
     */
    fun groups(defaults: List<ContextDefault>, setting: SearchDomain.Setting): List<StateGroup> {
        val (on, off) = CONTEXTS.partition { setting in SearchDomain.activeSettings(configOf(defaults, it.id)) }
        return listOfNotNull(StateGroup(true, on).takeIf { on.isNotEmpty() }, StateGroup(false, off).takeIf { off.isNotEmpty() })
    }

    /**
     * User rule 2026-11-25: **an entry's default presences** — the contexts whose "Entries, in order" names [entryId]
     * ([listed]), those whose entries are set and do not ([unlisted], where it can be put), and those that set none and
     * list whatever their menu gives ([unset]).
     */
    data class Presences(val listed: List<SearchContext>, val unlisted: List<SearchContext>, val unset: List<SearchContext>)

    fun presences(defaults: List<ContextDefault>, entryId: String): Presences {
        val withEntries = CONTEXTS.map { it to configOf(defaults, it.id).entries }
        return Presences(
            listed = withEntries.filter { (_, entries) -> entryId in entries }.map { it.first },
            unlisted = withEntries.filter { (_, entries) -> entries.isNotEmpty() && entryId !in entries }.map { it.first },
            unset = withEntries.filter { (_, entries) -> entries.isEmpty() }.map { it.first },
        )
    }

    /** The defaults once [entryId] is listed in [contextIds] (at their end) or taken out of them ([on] false). */
    fun withEntry(defaults: List<ContextDefault>, contextIds: List<String>, entryId: String, on: Boolean): List<ContextDefault> =
        contextIds.fold(defaults) { acc, id ->
            val config = configOf(acc, id)
            val entries = if (on) (config.entries - entryId) + entryId else config.entries - entryId
            written(acc, id, config.copy(entries = entries))
        }

    /**
     * **[target] with what changed from [before] to [after]** — field by field, a filter's own fields one by one: the
     * change made on one context's configuration, made on another's, the rest of that one kept.
     */
    fun withChange(target: SearchDomain.Config, before: SearchDomain.Config, after: SearchDomain.Config): SearchDomain.Config {
        val t = json.parseToJsonElement(target.encode()) as JsonObject
        val b = json.parseToJsonElement(before.encode()) as JsonObject
        val a = json.parseToJsonElement(after.encode()) as JsonObject
        return SearchDomain.Config.decode(merged(t, b, a).toString()) ?: target
    }

    private fun merged(target: JsonObject, before: JsonObject, after: JsonObject): JsonObject {
        val keys = before.keys + after.keys
        val out = LinkedHashMap<String, JsonElement>(target)
        for (key in keys) {
            val b = before[key]
            val a = after[key]
            if (a == b) continue
            val t = target[key]
            if (a is JsonObject && b is JsonObject && t is JsonObject) out[key] = merged(t, b, a)
            else if (a == null) out.remove(key) else out[key] = a
        }
        return JsonObject(out)
    }

    /**
     * The account's [ContextDefault]s once the context [id] opens with [config]: its row written, or dropped where
     * [config] is the code's own default — nothing is stored that the code says already.
     */
    fun written(defaults: List<ContextDefault>, id: String, config: SearchDomain.Config): List<ContextDefault> {
        val default = context(id)?.default ?: SearchDomain.Config()
        val rest = defaults.filterNot { it.id == id }
        if (config.encode() == default.encode()) return rest
        val row = ContextDefault(id, config.encode())
        val at = defaults.indexOfFirst { it.id == id }
        return if (at < 0) rest + row else rest.toMutableList().apply { add(at, row) }
    }

    private val json = Json { ignoreUnknownKeys = true }
}
