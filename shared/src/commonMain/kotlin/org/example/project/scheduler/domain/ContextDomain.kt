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

    /** Every context, in the order the action lists them. */
    val CONTEXTS: List<SearchContext> by lazy {
        listOf(SearchContext(SEARCH_WINDOW, "default of a Search window", SearchDomain.Config())) +
            RIGHT_CLICK_MENUS.map { (name, what) -> SearchContext(MENU_PREFIX + name, "default of the right-click menu of $what", SearchDomain.OPTION_SEARCH) } +
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
