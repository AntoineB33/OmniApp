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

    /** What a named drop-down's context id starts with, before its name ([DROP_DOWNS]). */
    const val DROP_DOWN_PREFIX: String = "drop-down:"

    /**
     * User rule 2026-11-27 (after *"Types › Types"*: one context for every drop-down could not turn a configuration on for
     * one of them): **the drop-downs, by the name each is drawn under** (`OptionSearchMenu`'s first argument, a search
     * configuration's own being its [SearchDomain.Setting] name) and what the user calls it. Each is a context of its own
     * whose default is [DROP_DOWN]'s as the account has it, what it changes laid over that ([configOf]). One missing here
     * opens with [DROP_DOWN]'s (`ContextCatalogTest` lists those named in the code).
     */
    val DROP_DOWNS: List<Pair<String, String>> by lazy {
        listOf(
            "PeriodSelectorField" to "a period selector field",
            "AddedElementsConfigurationWindow" to "the added elements configurations window's types",
            "workplace operating system" to "a workplace setup's operating system",
            "period drawing" to "a period's drawing",
            "picked timers" to "the timers picked",
            "picked chronos" to "the chronos picked",
            "ShortcutWindowEditor" to "a shortcut's window",
            "ResilienceEditor" to "a task's resilience",
            "NewElementTimesEditor" to "a new element's time",
            "NewElementTimesEditor#2" to "a new element's second time",
            "AlertSoundDropdown" to "an alert's sound",
            "calendar blocks" to "the calendar blocks",
            "dragged blocks" to "the dragged blocks",
            "CalendarElementsWindow" to "the calendar elements window",
            "HistoryDropdownField" to "a history field",
            "RecurrenceUnitDropdown" to "a recurrence's unit",
            "PageNavButton" to "the page button",
            "CalendarElementDisplay" to "the calendar's displayed elements",
            "EditModeSelector" to "the edit mode",
            "LengthField" to "a length",
            "ClaudeFilterEditor" to "a Claude Code logs filter",
            "sort by" to "the sorting methods",
            "task row parts" to "a task row's parts",
            "menu entries" to "a menu's entries",
            "SleepTimeChoice" to "a sleep time",
            "TaskCategoryCell" to "a task's categories",
            "TimeSimPanel" to "the time simulation",
            "constrained in" to "where a task is constrained",
            "task of the blocks" to "the task of the blocks",
            "md links" to "an md element's links",
        ) + DROP_DOWN_SETTINGS.map { it.name to "the \"${it.label}\" field (${it.section?.label ?: "every type"})" }
    }

    /** The search configurations whose control is a drop-down drawn under their own name (`SettingEditor`). */
    val DROP_DOWN_SETTINGS: List<SearchDomain.Setting> = listOf(
        SearchDomain.Setting.Types, SearchDomain.Setting.CompletionList, SearchDomain.Setting.CalendarAdd,
        SearchDomain.Setting.ExcludedElements, SearchDomain.Setting.IncludedElements, SearchDomain.Setting.ParentsOf,
        SearchDomain.Setting.InvolvementOf, SearchDomain.Setting.NoParentOfKinds, SearchDomain.Setting.PeriodDrawingSetting,
        SearchDomain.Setting.HistoryCategorySetting, SearchDomain.Setting.HistoryWindowSetting,
        SearchDomain.Setting.HistoryChangedKindsSetting, SearchDomain.Setting.NotificationSourceSetting,
        SearchDomain.Setting.RelationSectionSetting, SearchDomain.Setting.WindowListedKindsSetting,
        SearchDomain.Setting.WindowStatusSetting,
    )

    /** The head menu's entry writing the window's size as its context's ([withSize]). */
    const val USE_SIZE_ENTRY: String = "Use this size as the default here"

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
                "Fill the width", "Fill the height", "Reduce", "Maximize", USE_SIZE_ENTRY, "Close",
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

    /** What a window's context id starts with, before its name ([FRAMES]). */
    const val FRAME_PREFIX: String = "window:"

    /**
     * User rule 2026-11-27 (*"For a context element, there should be a width and height values"*): **the windows that are
     * no Search window, by the name each opens its size under** (`AppWindowFrame`'s `sizeContext`, `contextSize`), what
     * the user calls it, and the size the code gives it ([SearchDomain.WindowSize]). Each is a context of its own, so its
     * size is set like any context's value ([sizeOf]). A Search window's is its own context's ([SEARCH_WINDOW]'s where
     * that sets none). `ContextCatalogTest` lists every name the code opens a window under.
     */
    val FRAMES: List<Triple<String, String, SearchDomain.WindowSize>> = listOf(
        Triple("Online", "the Online window", SearchDomain.WindowSize(420, 460)),
        Triple("Priority weights", "the priority weights window", SearchDomain.WindowSize(760, 600)),
        Triple("Relative priority", "a relative priority window", SearchDomain.WindowSize(520, 420)),
        Triple("Task", "a task's window", SearchDomain.WindowSize(360, 620)),
        Triple("Deep copy", "the deep copy window", SearchDomain.WindowSize(420, 380)),
        Triple("Added elements configurations", "the added elements configurations window", SearchDomain.WindowSize(520, 560)),
        Triple("Alarms", "the alarms window", SearchDomain.WindowSize(440, 560)),
        Triple("Calendar elements", "the calendar elements window", SearchDomain.WindowSize(400, 640)),
        Triple("Reminder", "a reminder's window", SearchDomain.WindowSize(560, 480)),
        Triple("History", "the History window", SearchDomain.WindowSize(980, 520)),
        Triple("History unit", "a history unit's window", SearchDomain.WindowSize(480, 420)),
        Triple("Edit task", "the calendar's edit task window", SearchDomain.WindowSize(320, 560)),
        Triple("Period", "a period's window", SearchDomain.WindowSize(340, 520)),
        Triple("Add reminder", "the add reminder window", SearchDomain.WindowSize(320, 480)),
        Triple("Constrain to reminder", "the constrain to reminder window", SearchDomain.WindowSize(320, 420)),
        Triple("Categories", "the categories window", SearchDomain.WindowSize(460, 520)),
        Triple("Category", "a category's window", SearchDomain.WindowSize(420, 620)),
        // The tree's column as `TREE_ROOT_POPUP_WIDTH` lays it (`ContextCatalogTest` holds the two together).
        Triple("Configurations tree", "the configurations tree window", SearchDomain.WindowSize(604, 600)),
        Triple("Default sub-tree", "the default sub-tree window", SearchDomain.WindowSize(560, 560)),
        Triple("Notice", "a notice", SearchDomain.WindowSize(420, 200)),
        Triple("Keyboard shortcuts", "the keyboard shortcuts window", SearchDomain.WindowSize(470, 620)),
        Triple("Sleep", "the sleep window", SearchDomain.WindowSize(320, 300)),
        Triple("Task relations", "the task relations window", SearchDomain.WindowSize(560, 520)),
        Triple("Cell edit", "a task cell's edit pop-up", SearchDomain.WindowSize(480, 440)),
        Triple("Field", "a field's pop-up window", SearchDomain.WindowSize(480, 440)),
        Triple("Children search", "the pop-up of an element's children", SearchDomain.WindowSize(760, 480)),
    )

    /** A Search window's size where its own context sets none: the code's ([SEARCH_WINDOW]'s default). */
    val SEARCH_WINDOW_SIZE: SearchDomain.WindowSize = SearchDomain.WindowSize(1040, 600)

    /** A window opened under a name no context has: what it opens at (`ContextCatalogTest` keeps it unused). */
    private val UNKNOWN_FRAME_SIZE: SearchDomain.WindowSize = SearchDomain.WindowSize(480, 480)

    /** The context of the window opened under [name] ([FRAMES]). */
    fun frameContextId(name: String): String = FRAME_PREFIX + name

    /** Every context, in the order the action lists them. */
    val CONTEXTS: List<SearchContext> by lazy {
        listOf(SearchContext(SEARCH_WINDOW, "default of a Search window", SearchDomain.Config())) +
            RIGHT_CLICK_MENUS.map { (name, what) ->
                SearchContext(MENU_PREFIX + name, "default of the right-click menu of $what", SearchDomain.OPTION_SEARCH.copy(entries = MENU_ENTRIES[name].orEmpty()))
            } +
            listOf(
                SearchContext(DROP_DOWN, "default of a drop-down", SearchDomain.OPTION_SEARCH),
                SearchContext("integers", "default of a list of integers", SearchDomain.INTEGER_SEARCH),
            ) +
            DROP_DOWNS.map { (name, what) -> SearchContext(DROP_DOWN_PREFIX + name, "default of the drop-down of $what", SearchDomain.OPTION_SEARCH) } +
            SearchDomain.WINDOW_CONTEXTS.map { (id, title, config) -> SearchContext(id, title, config) } +
            FRAMES.map { (name, what, size) -> SearchContext(frameContextId(name), "default of $what", SearchDomain.Config(size = size)) }
    }

    private val byId: Map<String, SearchContext> by lazy { CONTEXTS.associateBy { it.id } }

    fun context(id: String): SearchContext? = byId[id]

    /** The context a menu drawn under [name] opens in: its own, a right-click menu's or a drop-down's, else a drop-down's. */
    fun menuContextId(name: String, rightClick: Boolean): String =
        if (rightClick) (MENU_PREFIX + name).takeIf { it in byId } ?: DROP_DOWN
        else (DROP_DOWN_PREFIX + name).takeIf { it in byId } ?: DROP_DOWN

    /** The context whose default [id]'s is, as the account has it: a named drop-down's is [DROP_DOWN]'s. */
    fun parentOf(id: String): String? = DROP_DOWN.takeIf { id.startsWith(DROP_DOWN_PREFIX) }

    /** The key of a context's element ([SearchDomain.Kind.Context]). */
    fun keyOf(id: String): String = SearchDomain.Kind.Context.name + "/" + id

    /** **What a context opens with** — the user's default where they changed it ([ContextDefault]), else the code's. */
    fun configOf(defaults: List<ContextDefault>, id: String): SearchDomain.Config {
        val code = context(id)?.default ?: SearchDomain.Config()
        val base = inheritedOf(defaults, id)
        val stored = defaults.firstOrNull { it.id == id }?.let { SearchDomain.Config.decode(it.config) } ?: return base
        return if (base === code) stored else withChange(base, code, stored)
    }

    /** What [id] opens with before its own change: its parent's as the account has it ([parentOf]), else the code's. */
    private fun inheritedOf(defaults: List<ContextDefault>, id: String): SearchDomain.Config {
        val code = context(id)?.default ?: SearchDomain.Config()
        val parent = parentOf(id) ?: return code
        val inherited = configOf(defaults, parent)
        // Nothing changed above it: the code's own, the same object.
        return if (inherited.encode() == (context(parent)?.default ?: SearchDomain.Config()).encode()) code else inherited
    }

    /**
     * User rule 2026-11-27 (*"When a window opens, every search configuration element for this window gets the
     * corresponding value by default"*): **what a window [built] in a context opens with** — the one funnel every window
     * and field goes through as it opens. [built] is its context's code default ([SearchDomain.WINDOW_CONTEXTS]) with
     * what the opening says laid over it (the instant, the elements); here, what the account made of that context's
     * default is put under it, field by field ([withChange]). A configuration in no context, or in one the account never
     * changed, is opened as it is.
     */
    fun opened(defaults: List<ContextDefault>, built: SearchDomain.Config): SearchDomain.Config {
        val id = built.context ?: return built
        val code = context(id)?.default ?: return built
        val account = configOf(defaults, id)
        if (account === code) return built
        return withChange(account, code, built)
    }

    fun configOf(state: SchedulerState, id: String): SearchDomain.Config = configOf(state.contextDefaults, id)

    /**
     * User rule 2026-11-27: **the size a window of the context [id] opens at** - the one funnel every window and sized
     * pop-up reads (`AppWindowFrame`, `contextSize`): the account's value of the context where it set one, else the
     * code's; a Search window's context setting none takes a Search window's ([SEARCH_WINDOW]). The size the user gave
     * a window on a device (`WindowPlacement`) stands over this one; this is where a window starts and what Reset gives.
     */
    fun sizeOf(defaults: List<ContextDefault>, id: String): SearchDomain.WindowSize =
        configOf(defaults, id).size
            // A row stored with no size (an older build's, or one cleared): the code's size of the context.
            ?: context(id)?.default?.size
            ?: UNKNOWN_FRAME_SIZE.takeIf { id.startsWith(FRAME_PREFIX) }
            ?: configOf(defaults, SEARCH_WINDOW).size
            ?: SEARCH_WINDOW_SIZE

    /**
     * **[size] in the room the app gives the window**, in dp: each side its own, cut to its share of the room (an unknown
     * room - not laid out yet, or unbounded - cuts nothing), never under [minDp]. What a window opens at.
     */
    fun fitted(size: SearchDomain.WindowSize, roomWidthDp: Float?, roomHeightDp: Float?, minDp: Float = 0f): Pair<Float, Float> {
        fun side(dp: Int, room: Float?, percent: Int): Float {
            val cap = room?.takeIf { it.isFinite() && it > 0f }?.let { it * percent / 100f } ?: Float.MAX_VALUE
            return minOf(dp.toFloat(), cap).coerceAtLeast(minDp)
        }
        return side(size.widthDp, roomWidthDp, size.maxWidthPercent) to side(size.heightDp, roomHeightDp, size.maxHeightPercent)
    }

    /**
     * User rule 2026-11-27, the head's "Use this size as the default here": **the defaults once the context [id] opens at
     * [widthDp] x [heightDp]**, its shares of the room kept - one History Unit like any change of a context's default.
     */
    fun withSize(defaults: List<ContextDefault>, id: String, widthDp: Int, heightDp: Int): List<ContextDefault> {
        val before = sizeOf(defaults, id)
        val size = SearchDomain.WindowSize.of(widthDp, heightDp, before.maxWidthPercent, before.maxHeightPercent) ?: return defaults
        return written(defaults, id, configOf(defaults, id).copy(size = size))
    }

    /** One group of [groups]: whether [setting] is on in its [contexts]. */
    /** [value]: what the setting holds in these contexts where it is on ([SearchDomain.settingValue]) as text; null where none. */
    data class StateGroup(val on: Boolean, val contexts: List<SearchContext>, val value: String? = null)

    /**
     * User rule 2026-11-25: **a search configuration's state in each context, grouped by state** — the contexts where it is
     * on, then those where it is off; a group with no context is not listed. Off everywhere: one group. User rule
     * 2026-11-27: on, the contexts are also told apart **by the value it holds there** ([SearchDomain.settingValue]) — a
     * parent distance of 1 in one and 3 in another are two groups, never one showing the first one's value for both.
     */
    fun groups(defaults: List<ContextDefault>, setting: SearchDomain.Setting): List<StateGroup> {
        val configs = CONTEXTS.associate { it.id to configOf(defaults, it.id) }
        val (on, off) = CONTEXTS.partition { setting in SearchDomain.activeSettings(configs.getValue(it.id)) }
        val onGroups = on.groupBy { SearchDomain.settingValue(configs.getValue(it.id), setting) }
            .map { (value, contexts) -> StateGroup(true, contexts, value?.let(SearchDomain::valueText)) }
        return onGroups + listOfNotNull(StateGroup(false, off).takeIf { off.isNotEmpty() })
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
        val rest = defaults.filterNot { it.id == id }
        // A named drop-down holding what its parent holds says nothing of its own.
        if (config.encode() == inheritedOf(rest, id).encode()) return rest
        val row = ContextDefault(id, config.encode())
        val at = defaults.indexOfFirst { it.id == id }
        return if (at < 0) rest + row else rest.toMutableList().apply { add(at, row) }
    }

    private val json = Json { ignoreUnknownKeys = true }
}
