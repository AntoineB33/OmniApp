package org.example.project.scheduler.domain

import kotlin.concurrent.Volatile
import org.example.project.claudelogs.ClaudeLogCatalog
import org.example.project.claudelogs.ClaudeLogKind
import org.example.project.claudelogs.ClaudeLogSearch
import org.example.project.scheduler.platform.GlobalShortcut
import org.example.project.scheduler.model.TaskRelationKey
import org.example.project.scheduler.state.HistoryUnit
import org.example.project.scheduler.state.HistoryWindow
import org.example.project.scheduler.state.HistoryCategory
import org.example.project.scheduler.state.NotificationSource
import org.example.project.scheduler.state.ChangedElement
import org.example.project.scheduler.state.changedElements
import kotlinx.datetime.number
import kotlinx.datetime.toLocalDateTime
import kotlinx.datetime.TimeZone
import kotlin.time.Instant
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.plus
import org.example.project.scheduler.model.TaskPanel
import org.example.project.scheduler.model.TaskTimeRange
import kotlinx.datetime.isoDayNumber
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.example.project.scheduler.model.CategoryId
import org.example.project.scheduler.model.Cell
import org.example.project.scheduler.model.CellId
import org.example.project.scheduler.model.CellList
import org.example.project.scheduler.model.CellListId
import org.example.project.scheduler.model.ChoreEntry
import org.example.project.scheduler.model.ChronoEntry
import org.example.project.scheduler.model.ChoreRecurrenceUnit
import org.example.project.scheduler.model.Task
import org.example.project.scheduler.model.TaskId
import org.example.project.scheduler.model.WellKnownIds
import org.example.project.scheduler.state.SchedulerIntent
import org.example.project.scheduler.state.SchedulerState
import org.example.project.scheduler.state.defaultSubtreeIsEmpty

/**
 * PRD §7 **Search**: the lateral-menu window that finds a thing of the account by name — a task, a task
 * category, a kind of restrictive period, an alarm, a timer or a reminder — and the one fact about tasks it
 * needed that the app did not keep: **where a task that is in no task tree any more last sat**.
 *
 * ### "In a task tree"
 *
 * The account holds several task trees (the selector's named trees): the live one, whose fields are the
 * state's own, and every other one as a stored snapshot. A task is in a task tree when **any** of them holds
 * it — its title-bearing cell is reachable from that tree's root, the same predicate the live tree's
 * [SchedulerDomain.shortestTaskTreePaths] (and so "go to task") uses. The active
 * tree's stored entry is stale by design and is never read: the live fields stand for it.
 *
 * ### Paths
 *
 * A path is the titles from the tree's root down to the task's **parent** — the task's own title is the row's
 * first section, so repeating it would spend the width the two sections compete for. The root segment is the
 * **tree's name** (the live tree's when it has one, else the root task's own title): with several trees, a
 * path has to say which one it runs through.
 */
/**
 * PRD §8: **the order the calendar's hover bubble names what is under the cursor in**, top to bottom —
 * `reminder > alarm/timer ring > task = break > a period > a layer`. Equal ranks are deliberate ties.
 *
 * ONE declaration, read by the bubble itself (`CalendarBubbleSection.Kind.rank`), by the placement of the
 * calendar's texts (which of two wanting the same point keeps it) and by the Search window's "calendar bubble
 * order" sort ([SearchDomain.SortKey.CalendarBubble]) — three readers that must never disagree.
 */
object CalendarBubbleRank {
    const val REMINDER: Int = 0
    const val RING: Int = 1
    const val TASK: Int = 2
    const val BREAK: Int = 2
    const val PERIOD: Int = 3
    const val LAYER: Int = 4

    /** The rank of a period of [kind]: a screen break's, a layer's, or any other period's. */
    fun ofPeriodKind(kind: String): Int =
        when (kind) {
            in PeriodKinds.BREAK_KINDS -> BREAK
            in PeriodKinds.LAYER_KINDS -> LAYER
            else -> PERIOD
        }

    /** The rank of a Search row of [kind] ([id]: a period row's kind); null = never in the bubble. */
    fun of(kind: SearchDomain.Kind, id: String?): Int? =
        when (kind) {
            SearchDomain.Kind.Reminder -> REMINDER
            SearchDomain.Kind.Alarm, SearchDomain.Kind.Timer -> RING
            SearchDomain.Kind.Task -> TASK
            SearchDomain.Kind.RestrictivePeriod -> ofPeriodKind(id.orEmpty())
            else -> null
        }
}

object SearchDomain {

    /**
     * The drop-down of the configuration section, in the order the user listed it. Each kind carries a check
     * box there: the search looks for every checked kind at once ([results]).
     */
    enum class Kind(val label: String, plural: String? = null) {
        Task("task"),
        Category("task category", "task categories"),
        RestrictivePeriod("restrictive period"),
        Alarm("alarm"),
        Timer("timer"),
        Chrono("chrono"),

        /** User rule 2026-10-03: a quota ([org.example.project.scheduler.model.QuotaEntry]). */
        Quota("quota"),
        Reminder("reminder"),

        /**
         * User rule 2026-10-05: one **blue outlined block** of the calendar ([CalendarBlock]) — what a hand placed there:
         * a task panel, a period, a reminder tag, a ring moved on the calendar. Listed by the element it is a block of
         * ([Filters.blocksOf], [blocksSearchConfig]); opening one opens what is on the calendar there.
         */
        CalendarBlock("calendar block"),

        /**
         * User rule 2026-11-12: **the calendar** — one element ([CALENDAR_ID]) whose actions are the calendar itself
         * ([AddedAction.CalendarGrid]), its day selector and the options of its view. There is no calendar window any
         * more: a Search window holding this element shows the calendar in its actions section, each window its own
         * view of it (the day it was taken to, its display, its locks — [Config.calendarDay], [Config.calendarDayMode]).
         */
        Calendar("calendar"),
        HistoryUnit("history unit"),

        /**
         * A notification the app posted — written, spoken or both ([org.example.project.scheduler.state.NotificationLogEntry],
         * the History window's own log). Listed for the window the app opens when one fires while the app is not in
         * focus (user rule 2026-10-04, [notificationsConfig]); read-only, like a history unit.
         */
        Notification("notification"),
        TaskRelation("task relation"),
        Shortcut("keyboard shortcut"),

        /**
         * A setting of the app itself (user spec 2026-10-02): one row per [AppSettingEntry] — "Sound setting" is the
         * first — whose actions on the added elements are the setting's controls (the global volume slider).
         */
        AppSetting("app setting"),

        /** User rule 2026-11-01: a workplace setup ([org.example.project.scheduler.domain.WorkplaceSetup]). */
        WorkplaceSetup("workplace setup"),

        /**
         * User rule 2026-11-19: an **md element** ([org.example.project.scheduler.domain.MdElement]) — a markdown text,
         * associated with the elements it is about, searched in its whole text.
         */
        Md("md element"),

        /**
         * User rule 2026-11-19: a **presence ratio target evolution** ([org.example.project.scheduler.domain.PresenceEvolution]):
         * the tasks it is given to, what it is relative to, its periods and its formula. Edited from the actions of the tasks
         * it involves ("Presence ratio target").
         */
        PresenceEvolution("presence ratio target evolution"),

        /**
         * User rule 2026-11-20 (*"The minimum time target evolution action should be the same thing"*): a **minimum length
         * target evolution** — the same element ([org.example.project.scheduler.domain.PresenceEvolution], its target
         * [org.example.project.scheduler.domain.EvolutionTarget.Minimum]), edited from the "Minimum length target" action.
         */
        MinimumEvolution("minimum length target evolution"),

        /**
         * A window of the app, open or not ([WindowEntry]): what the rows name is not in the account's state but
         * in `App`'s windows, which hands the list in ([results]'s `windows`).
         */
        Window("window"),

        /**
         * The Claude Code logs page (`OmniPage.ClaudeLogs`, user rule 2026-10-18): a prompt the user typed, an answer
         * and a reasoning chain of Claude Code's session logs. Like a window, what the rows name is not in the
         * account's state: `App` reads the logs and hands them in ([ClaudeLogCatalog], [results]'s `claudeLogs`).
         * Read-only: the actions show each one's content ([AddedAction.PromptText]…).
         */
        ClaudePrompt("prompt"),
        ClaudeAnswer("answer"),
        ClaudeReasoning("reasoning chain"),

        /**
         * User rule 2026-11-11: **a search configuration** ([Setting]) — what the configurations tree lists. The tree a
         * Search window's arrow opens is itself a search over search configurations, so the arrow in ITS bar opens the
         * configuration of that search, whose type is this one alone ([CONFIGURATION_SEARCH]): *"this is the search
         * configuration of the search configuration of the default search window"*.
         */
        SearchConfiguration("search configuration"),

        /**
         * User rule 2026-11-12: *"All the drop-down menus of the app must be Search windows as well."* **An option of a
         * drop-down** — what the field it opens from lists ([optionResults]); no element of the account, so a search of
         * the kind elsewhere lists none. With check boxes, the list starts with "Select all" ([SELECT_ALL_OPTION]).
         */
        Option("option"),

        /**
         * User rule 2026-11-13: **an integer** — what a search configuration holding a number lists to pick it from (the
         * parent distance, [integerResults]): "1", "2", "3"… with no end. Like an option, the field it opens from gives
         * the rows; the account holds none.
         */
        Integer("integer"),

        /**
         * User rule 2026-11-12: *"The action section is a Search window (hiding its header and its own action and added
         * elements list sections)."* **A row of the actions section** — a group of the added elements or one action over
         * them ([actionListRows]); what the section gives its Search window, none in the account.
         */
        Action("action"),

        /**
         * One CREATION row per kind of element the user can make ([CREATABLE]): opening it makes a new one, as that
         * kind's own "+ New …" does, and opens its window (user spec 2026-09-26). History units and task relations
         * are not made by the user, so they have none. A new keyboard shortcut is a chord for a window (user rule
         * 2026-10-12, [WindowEntry.mapping]).
         */
        Creation("creation"),
        ;

        /** What several of the kind are called — a Search window's title says it ([windowTitle]). */
        val plural: String = plural ?: (label + "s")
    }

    /**
     * The kinds a [Kind.Creation] row exists for, in the drop-down's order. A new [Kind.Window] is a Search window
     * listing the window TYPES ([WindowDuplicates.Hidden]).
     */
    val CREATABLE: List<Kind> =
        listOf(
            Kind.Task, Kind.Category, Kind.RestrictivePeriod, Kind.Alarm, Kind.Timer, Kind.Chrono, Kind.Quota, Kind.Reminder,
            Kind.Window, Kind.Shortcut, Kind.WorkplaceSetup, Kind.Md,
        )

    /**
     * The feature page a Search window belongs to ([Config.page]): blank for the Task Scheduler's, this for the Claude
     * Code logs page's — `OmniPage`'s name, which the scheduler's domain does not see.
     */
    const val CLAUDE_LOGS_PAGE: String = "ClaudeLogs"

    /** The feature page of the app's settings (user rule 2026-11-01). */
    const val SETTINGS_PAGE: String = "AppSettings"

    /** The kinds of the Claude Code logs, in the drop-down's order. */
    val CLAUDE_KINDS: List<Kind> = listOf(Kind.ClaudePrompt, Kind.ClaudeAnswer, Kind.ClaudeReasoning)

    /** The id of the "Select all" row of a drop-down whose options have check boxes ([optionResults]). */
    const val SELECT_ALL_OPTION: String = "select-all"

    /** The id of the row that makes what the bar names ([optionResults]'s `creates`). */
    const val CREATE_OPTION: String = "create"

    /**
     * User rule 2026-11-12: **the rows of a drop-down's Search window** — its [options] (id and name, in the drop-down's
     * order) that answer [query], and, where they have check boxes ([checked] not null), first, the element **"Select
     * all"**: *"when clicked selects all the elements of the same list that have a check box"* — those listed. Once they
     * all are, it reads "Deselect all" ([selectAllApplied]).
     */
    fun optionResults(
        options: List<Pair<String, String>>,
        query: String,
        checked: Set<String>?,
        /** Whether a name the bar holds and no option has is offered as a row that makes it ([CREATE_OPTION]). */
        creates: Boolean = false,
    ): List<ItemResult> {
        val listed = options.filter { (_, name) -> query.isBlank() || matchRank(name, query) != null }
        val typed = query.trim()
        val create =
            listOfNotNull(
                ItemResult(Kind.Option, CREATE_OPTION, "create “$typed”", "")
                    .takeIf { creates && typed.isNotEmpty() && options.none { it.second.equals(typed, ignoreCase = true) } },
            )
        val rows = listed.map { (id, name) -> ItemResult(Kind.Option, id, name, "") } + create
        if (checked == null || listed.isEmpty()) return rows
        val all = listed.all { it.first in checked }
        return listOf(ItemResult(Kind.Option, SELECT_ALL_OPTION, if (all) "Deselect all" else "Select all", "")) + rows
    }

    /** What "Select all" leaves checked: every [listed] option, or none of them once they all were. */
    fun selectAllApplied(checked: Set<String>, listed: List<String>): Set<String> =
        if (checked.containsAll(listed)) checked - listed.toSet() else checked + listed

    /** The id of the first row of an integer list: the highest value that means something ([integerResults]). */
    const val HIGHEST_INTEGER: String = "highest"

    /**
     * User rule 2026-11-13: **the rows of a list of integers** ([Kind.Integer]) — first [highestLabel] with the highest
     * value that means something (*"highest parent distance (5)"*, [HIGHEST_INTEGER]), then "1", "2", "3"… The list has
     * no end: [count] is how many integers are given now, more being asked as the last ones come in view. [query] keeps
     * the integers whose digits hold it (and the first row while its text does).
     */
    fun integerResults(highestLabel: String, highest: Int, count: Int, query: String = ""): List<ItemResult> {
        val needle = query.trim()
        val first = ItemResult(Kind.Integer, HIGHEST_INTEGER, "$highestLabel ($highest)", "")
        val head = listOfNotNull(first.takeIf { needle.isEmpty() || it.name.contains(needle, ignoreCase = true) })
        if (needle.isNotEmpty() && needle.any { !it.isDigit() }) return head
        return head + generateSequence(1) { it + 1 }
            .filter { needle.isEmpty() || needle in it.toString() }
            .take(count.coerceAtLeast(0))
            .map { ItemResult(Kind.Integer, it.toString(), it.toString(), "") }
    }

    /** The integer list's Search window as it opens ([integerResults]). */
    val INTEGER_SEARCH: Config by lazy { Config(kinds = setOf(Kind.Integer)) }

    /** Whether [kind]'s rows are given by the field the list opens from, a click picking one: an option, an integer. */
    fun isPickedRow(kind: Kind): Boolean = kind == Kind.Option || kind == Kind.Integer

    /**
     * The drop-down's Search window as it is kept: options, its bar empty, the order its options were given in. Its
     * options are said by its "Listed from" ([ListedFrom]), given each time it opens.
     */
    val OPTION_SEARCH: Config by lazy { Config(kinds = setOf(Kind.Option)) }

    /**
     * User rule 2026-11-21 (*"In the down arrow of the left-side menu, in 'Currently on', there is only 'Types: option'.
     * Make sure every search window of the app always respect only what is in 'Currently on'"*): **where the rows of a
     * window come from when its caller gives them** — [source] said in words, [titles] the rows given, [keys] theirs.
     * Its window's rows are those, narrowed by the rest of its configuration ([keptListed]); nothing is drawn beside what
     * its configuration says. `task-tree.md` § *Listed from*.
     */
    data class ListedFrom(val source: String, val keys: List<String> = emptyList(), val titles: List<String> = emptyList())

    /**
     * **The one funnel** (`SearchWindow`): [config] as its window shows it, its ▾'s "Currently on" and "Default for
     * this" included — saying where its rows come from ([from]), every time it is drawn.
     */
    fun withListedFrom(config: Config, from: ListedFrom?): Config =
        if (config.filters.listedFrom == from) config else config.copy(filters = config.filters.copy(listedFrom = from))

    /** What the window keeps of a configuration its ▾ changed: never where its rows come from ([withListedFrom]). */
    fun withoutListedFrom(config: Config): Config = withListedFrom(config, null)

    /**
     * **Which of the rows given to a window ([ListedFrom]) its configuration keeps**, by key: through [results] — the
     * one funnel of given rows (anomaly 2026-11-14) — its types, filters and excluded elements, as a searched list. The
     * bar is left to the list's own finder (a menu finds by its items' folders, a drop-down by its options), each
     * keeping the order it was given.
     */
    fun keptListed(state: SchedulerState, config: Config, given: List<Result>): Set<String> =
        if (given.isEmpty()) emptySet()
        else results(state, config.searchedKinds, "", { emptyMap() }, config.filters, DEFAULT_SORTS, given = given).mapTo(HashSet(), ::keyOf)

    /**
     * An option as an element of a list ([ListedFrom]): by its name, what the user sees of it — the ids are the
     * caller's own (a menu's place of an entry), and change with the entries listed.
     */
    fun optionKey(name: String): String = Kind.Option.name + "/" + name

    /**
     * User rule 2026-11-21: **the caller of a field's window** ([Config.addedFor]) — the elements of the group of the
     * actions section the field stands in, by their keys, in any order: the field in the group of E1 and E2 is one caller,
     * the same field once E3 joins the group another.
     */
    fun fieldCallerOf(memberKeys: Collection<String>): String = memberKeys.distinct().sorted().joinToString(",")

    /**
     * User rule 2026-11-21: **the callers a window's added elements [added] make** ([fieldCallerOf]) — the element sets of
     * the groups of its actions section ([actionGroups]): every element, the elements of each kind (a creation row
     * counting as its kind), each element alone, and each kind's creation row (its default configuration's group).
     */
    fun callersOf(added: List<String>): Set<String> {
        if (added.isEmpty()) return emptySet()
        fun kindOf(key: String): String = key.substringBefore('/').let { if (it == Kind.Creation.name) key.substringAfter('/') else it }
        val out = HashSet<String>()
        out += fieldCallerOf(added)
        added.groupBy(::kindOf).forEach { (kind, keys) ->
            out += fieldCallerOf(keys)
            out += fieldCallerOf(listOf(Kind.Creation.name + "/" + kind))
        }
        added.forEach { out += fieldCallerOf(listOf(it)) }
        return out
    }

    /**
     * The window a field's window [key] (an id in [Config.inside]) stands in, by its id there ("" for the window holding
     * [Config.inside]) — for a field of an actions section alone (`…/actions/field:…`, a context's `…/actions/actions/field:…`);
     * null for any other.
     */
    private fun fieldOwnerOf(key: String): String? {
        val at = key.lastIndexOf("/field:")
        if (at < 0) return null
        var section = key.substring(0, at)
        if (!section.endsWith("/actions")) return null
        // A context of an action is drawn by the section's own section (`…/actions/actions`), over the same group.
        while (section.endsWith("/actions")) section = section.removeSuffix("/actions")
        return section
    }

    /**
     * User rule 2026-11-21 (anomaly: *"I add a task in the added elements list of the window A, add an element in the
     * added elements list in the window B from the 'presence ratio target evolutions' field, remove the task from A, add
     * it again, re-open B, and find the same element"*): **a field's window is lost with its caller** — every window of a
     * field (and every one opened inside it) whose group ([Config.addedFor]) is no longer one of the window it stands in
     * ([callersOf]) is dropped from [config]'s [Config.inside], as the window changes: the field of the group E1, E2 no
     * longer exists once E3 joins or E1 leaves, and the group made again is another caller. What the window held before
     * is its History Unit's: Ctrl+Z brings it back.
     */
    fun withLostCallersDropped(config: Config): Config {
        if (config.inside.isEmpty()) return config
        val decoded = config.inside.mapValues { (_, text) -> Config.decode(text) }
        val gone = decoded.filter { (key, inner) ->
            // "": a field whose added elements are its caller's own state (`PopupSearchField`'s `caller`), never lost.
            val caller = inner?.addedFor?.takeIf { it.isNotEmpty() } ?: return@filter false
            val owner = fieldOwnerOf(key) ?: return@filter false
            // A window holding what it opens with is not kept: what it lists is not known here, so nothing is judged.
            val ownerAdded = if (owner.isEmpty()) config.added else decoded[owner]?.added ?: return@filter false
            caller !in callersOf(ownerAdded)
        }.keys
        if (gone.isEmpty()) return config
        return config.copy(inside = config.inside.filterKeys { key -> gone.none { key == it || key.startsWith("$it/") || key.startsWith(it + "▾") } })
    }

    /**
     * User rule 2026-11-21: *"The 'added elements' list section of a window must be preserved between closing and
     * re-appearing, except if the caller changes."* **What a field's window opens with** — what it opens with by default
     * ([opened]), what its bar holds ([query]), and the added elements it had ([current]) while they were added for this
     * [caller] (or for no caller said: kept from before it was said); else those it opens with by default.
     */
    fun fieldWindowReopened(current: Config, opened: Config, caller: String, query: String): Config {
        val keeps = current.addedFor == null || current.addedFor == caller
        val added = if (keeps) current.added else opened.added
        return opened.copy(query = query, added = added, addedFor = caller.takeIf { added.isNotEmpty() })
    }

    /** The kinds of the settings page: the app's settings, whose actions are their controls ([AppSettingEntry]). */
    val SETTINGS_KINDS: List<Kind> = listOf(Kind.AppSetting, Kind.WorkplaceSetup)

    /**
     * **The feature pages, the one statement of them**: [id] is what a Search window's configuration and the lateral
     * menu's rows say ([Config.page]), [label] what the page selector and every other list of them read, [kinds] the
     * elements that belong to it. The Task Scheduler's are all but the logs' and the settings page's (user rule
     * 2026-11-04: *"'only this page's types' should not allow the settings element type except in the Settings page"*;
     * a setting was the Task Scheduler's too until then).
     */
    data class FeaturePage(val id: String, val label: String, val kinds: List<Kind>)

    val FEATURE_PAGES: List<FeaturePage> by lazy {
        listOf(
            FeaturePage("", "Task Scheduler", Kind.entries - CLAUDE_KINDS.toSet() - SETTINGS_KINDS.toSet()),
            FeaturePage(CLAUDE_LOGS_PAGE, "Claude Code logs", CLAUDE_KINDS),
            FeaturePage(SETTINGS_PAGE, "Settings", SETTINGS_KINDS),
        )
    }

    /** The kinds of the pages [pages], in the drop-down's order. */
    fun kindsOfPages(pages: Set<String>): List<Kind> {
        val kinds = FEATURE_PAGES.filter { it.id in pages }.flatMapTo(HashSet()) { it.kinds }
        return Kind.entries.filter { it in kinds }
    }

    /**
     * What a Search window opens with on [page] when nothing says otherwise — the global Search button's, user rule
     * 2026-10-18. **The one difference between the pages' Search windows** (user rule 2026-10-31): what this button
     * checks. The window itself is the same on both — every kind, every configuration, every sorting method.
     */
    fun pageDefaultConfig(page: String): Config =
        when (page) {
            CLAUDE_LOGS_PAGE -> claudeLogsConfig()
            SETTINGS_PAGE -> Config(kinds = SETTINGS_KINDS.toSet(), page = SETTINGS_PAGE, pageTypesOnly = true)
            // User rule 2026-11-04: each page's button opens on its own types alone ([Config.pageTypesOnly]).
            // User rule 2026-11-11: the default Search window has every type of its selector checked ([clearedConfig]).
            else -> clearedConfig(page)
        }

    /** The configuration a Search window of the Claude Code logs page opens with: every kind of the logs, newest first. */
    fun claudeLogsConfig(): Config = Config(kinds = CLAUDE_KINDS.toSet(), page = CLAUDE_LOGS_PAGE, sorts = CLAUDE_SORTS, pageTypesOnly = true)

    /**
     * The logs' entries newest first, whatever their kind: a log is read back from its end. ONE method (user rule
     * 2026-10-30): the date is common to a prompt, an answer and a reasoning chain, so it orders them together.
     */
    val CLAUDE_SORTS: List<SortMethod> by lazy { listOf(SortMethod(null, SortKey.Date, descending = true)) }

    /** The log entry kind a Claude kind lists. */
    fun claudeLogKindOf(kind: Kind): ClaudeLogKind? =
        when (kind) {
            Kind.ClaudePrompt -> ClaudeLogKind.Prompt
            Kind.ClaudeAnswer -> ClaudeLogKind.Answer
            Kind.ClaudeReasoning -> ClaudeLogKind.Reasoning
            else -> null
        }

    /**
     * What the id of a chord mapped to a window wears as a [Kind.Shortcut] row, where the app's own shortcuts are
     * named by their [GlobalShortcut] name: `window:ws-3` (user rule 2026-10-12).
     */
    const val WINDOW_SHORTCUT_PREFIX: String = "window:"

    /**
     * What the period edit window's resilience button types into the actions' filter ([Config.actionQuery]): the
     * one action it finds is [AddedAction.TaskResilience].
     */
    const val RESILIENCE_ACTION_QUERY: String = "Resilience"

    /**
     * The Search window the period edit window's resilience button opens (user rule 2026-10-01): the tasks only, and
     * of them only the schedulable ones ([Filters.taskSchedulable] yes — a resilience on a parent is a number nothing
     * reads), its actions filtered down to the resilience one, whose period field is set to [periodKind].
     */
    fun resilienceSearchConfig(periodKind: String): Config =
        Config(
            kinds = setOf(Kind.Task),
            filters = Filters(taskSchedulable = Tri.Yes),
            actionQuery = RESILIENCE_ACTION_QUERY,
            resiliencePeriod = periodKind,
        )

    /**
     * **An element's own window** (user rule 2026-10-01): the Search window of [kind] holding the element [id] alone in
     * its added list, so its top right quarter holds every action on it — what replaced the element's edit window.
     */
    /**
     * User rule 2026-11-19: **the Search window of the global root task cell alone**: the scheduler's left-side menu's
     * default "Root" button. No type checked, the root included, the button following its open rows
     * ([Config.buttonFollowsExpansion]).
     */
    fun rootSearchConfig(): Config =
        Config(
            kinds = emptySet(),
            filters = Filters(includedElements = setOf(taskKey(org.example.project.scheduler.model.WellKnownIds.ROOT_TASK))),
            buttonFollowsExpansion = true,
        )

    /**
     * User rule 2026-11-20: the Search window of ONE md element ([MdDomain.LIFE_CONTEXT_ID] for the menu's default), listed
     * alone and added, so its text is drawn in the actions.
     */
    fun mdSearchConfig(id: String): Config {
        val key = Kind.Md.name + "/" + id
        return Config(kinds = emptySet(), filters = Filters(includedElements = setOf(key)), added = listOf(key))
    }

    /** The md element a Search configuration shows alone ([mdSearchConfig]), or null. */
    fun soleMdOf(config: Config?): String? =
        config?.filters?.includedElements?.singleOrNull()?.takeIf { config.kinds.isEmpty() && it.startsWith(Kind.Md.name + "/") }
            ?.substringAfter('/')

    fun elementSearchConfig(kind: Kind, id: String): Config =
        Config(kinds = setOf(kind), added = listOf(kind.name + "/" + id))

    /**
     * **Every element of a kind** (user rule 2026-10-04): the Search window listing [kind] alone, nothing filtered and
     * nothing added — what a creation row's "Search every element of the kind" opens ([AddedAction.CreationSearchAll]).
     */
    fun kindSearchConfig(kind: Kind): Config = Config(kinds = setOf(kind))

    /**
     * **The notifications window** (user rule 2026-10-04): the Search window the app opens when a notification —
     * written, spoken or both — fires while the app is not in focus. It lists the notifications posted from
     * [sinceMillis] on: the instant the app LOST the focus, so what the user was there to see is not in it. The window
     * stays, and goes on gathering, until the user closes it; the next one starts at the next loss of focus.
     */
    fun notificationsConfig(sinceMillis: Long): Config =
        Config(
            kinds = setOf(Kind.Notification),
            filters = Filters(notificationsSinceMillis = sinceMillis),
            // The newest first: the one that has just fired is the one the window was opened for.
            sorts = listOf(SortMethod(Kind.Notification, SortKey.NotificationDate, descending = true)),
        )

    /**
     * A [Kind.Notification] row's id: the instant it was posted at and its place among the notifications posted at
     * that very instant (two can share one) — see [notificationIds], which is where the place is counted.
     */
    fun notificationId(entry: org.example.project.scheduler.state.NotificationLogEntry, ordinal: Int): String =
        entry.timeMillis.toString() + "#" + ordinal

    /**
     * The ids of [log]'s notifications, in the log's order. The place in an id is counted among the notifications of
     * the SAME INSTANT, not along the log: the log is a rolling tail, and an id that was a place in it changed for
     * every notification each time the oldest one was dropped — an added notification, or one a calendar shows
     * ([CalendarNotifications]), then named another, or nothing (2026-10-13).
     */
    fun notificationIds(log: List<org.example.project.scheduler.state.NotificationLogEntry>): List<String> {
        val seen = HashMap<Long, Int>()
        return log.map { entry ->
            val ordinal = seen[entry.timeMillis] ?: 0
            seen[entry.timeMillis] = ordinal + 1
            notificationId(entry, ordinal)
        }
    }

    /**
     * The keys ([keyOf]) of the notifications posted from [sinceMillis] on, newest first — what the notifications
     * window ([notificationsConfig]) lists, and so what the app ADDS to it (user rule 2026-10-13: *"The unfocused notif
     * window has all the notification elements in the result list in the added elements list as well"*).
     */
    fun notificationKeysSince(log: List<org.example.project.scheduler.state.NotificationLogEntry>, sinceMillis: Long): List<String> {
        val ids = notificationIds(log)
        return log.indices.filter { log[it].timeMillis >= sinceMillis }
            .sortedByDescending { log[it].timeMillis }
            .map { Kind.Notification.name + "/" + ids[it] }
    }

    /** What a scheduler RUN's row id starts with ([schedulerRunId]) — no history category is called that. */
    private const val RUN_ID_PREFIX: String = "run#"

    /**
     * User rule 2026-10-06: **the scheduler engine's entries among the History Unit rows** — each set of rules it found
     * ([org.example.project.scheduler.state.SchedulerRunEntry]: a re-plan, a horizon extension, rules adopted from a
     * peer, a mode switch), what the History window lists under "Scheduler engine". The id is what the run IS — its
     * instant, event, horizon and panel count — never its place in a list that drops its oldest.
     */
    fun schedulerRunId(run: org.example.project.scheduler.state.SchedulerRunEntry): String =
        RUN_ID_PREFIX + run.timeMillis + "#" + run.kind.ordinal + "." + run.horizonMillis + "." + run.panelCount

    /** Whether a [Kind.HistoryUnit] row's id names a run of the scheduler engine ([schedulerRunId]) rather than a unit. */
    fun isSchedulerRunId(id: String): Boolean = id.startsWith(RUN_ID_PREFIX)

    /** The instant of the run a row id names, or null for anything else. */
    fun schedulerRunTimeOf(id: String): Long? =
        if (isSchedulerRunId(id)) id.removePrefix(RUN_ID_PREFIX).substringBefore('#').toLongOrNull() else null

    /** The run a row id names among [runs], or null once it has left the list (it is kept in memory only). */
    fun schedulerRunOf(runs: List<org.example.project.scheduler.state.SchedulerRunEntry>, id: String): org.example.project.scheduler.state.SchedulerRunEntry? =
        runs.lastOrNull { schedulerRunId(it) == id }

    /** How a run of the scheduler engine says where it comes from, in its row's detail and in "Made in". */
    const val SCHEDULER_ENGINE_LABEL: String = "Scheduler engine"

    /**
     * One choice of the history units' **"Made in"** (user rule 2026-10-06): a window of the app — or the scheduler
     * engine, where the sets of rules were made. The engine is not a window (nothing focuses it, and the History
     * window lists it as a source of its own), so it is a choice beside them rather than one more [HistoryWindow].
     */
    sealed interface MadeIn {
        val label: String

        data object Engine : MadeIn {
            override val label: String get() = SCHEDULER_ENGINE_LABEL
        }

        data class Window(val window: HistoryWindow) : MadeIn {
            override val label: String get() = window.label
        }
    }

    /** Every choice of "Made in": the scheduler engine first, then the windows in their own order. */
    val MADE_IN_CHOICES: List<MadeIn> = listOf<MadeIn>(MadeIn.Engine) + HistoryWindow.entries.map { MadeIn.Window(it) }

    /** The instant the notification a [Kind.Notification] row's id names was posted at, or null for a malformed id. */
    fun notificationTimeOf(id: String): Long? = id.substringBefore('#').toLongOrNull()

    /** Whether [config] is a notifications window's ([notificationsConfig]): the notifications, from an instant on. */
    fun isNotificationsWindow(config: Config): Boolean =
        config.kinds == setOf(Kind.Notification) && config.filters.notificationsSinceMillis != null

    /**
     * Whether [config] is a notifications window's as the app keeps it (user rule 2026-10-06): nothing of its search
     * configuration changed, and nothing added but notifications — which the app adds itself, every one the window
     * lists ([notificationKeysSince], user rule 2026-10-13). Only such a window goes on gathering — once the user has
     * changed its search or added something else, the next notification out of focus opens a new one beside it.
     */
    fun isUntouchedNotificationsWindow(config: Config): Boolean {
        val since = config.filters.notificationsSinceMillis ?: return false
        // What the app writes itself (the calendar's view of the notifications) and what the hand opens or picks in the
        // actions section are no change of the window's search; an element added beside the calendar is.
        val search = config.copy(added = emptyList(), calendarView = "", calendarDay = null, calendarDayMode = null, toggledActionRows = emptySet())
        return search == notificationsConfig(since) && config.added.all { it == CALENDAR_KEY }
    }

    /**
     * User rule 2026-11-12: **the "unfocused notif" window** — one Search window: the notifications posted since
     * [sinceMillis] in its search section ([notificationsConfig]), the calendar element added, and the calendar in its
     * actions section showing [notificationIds] as chips. It was a Search window and a calendar window under it.
     */
    fun notificationsWindowConfig(sinceMillis: Long, notificationIds: List<String>): Config =
        calendarWindowConfig(
            notificationsConfig(sinceMillis).copy(
                calendarView = org.example.project.scheduler.domain.CalendarNotifications.WindowConfig(notificationIds).encode(),
            ),
        )

    /** What a Search window is called, on its head and on its tab of the window bar. */
    const val WINDOW_TITLE: String = "Search"

    /** What the notifications window ([notificationsConfig]) is called instead (user rule 2026-10-06). */
    const val NOTIFICATIONS_WINDOW_TITLE: String = "unfocused notif"

    /**
     * **The title of the Search window showing [config]** — read off the configuration, so it follows every change of
     * it and comes back with the window after a restart (user rule 2026-10-15: *"Make the title of a Search window
     * update with the Search configurations … if the configuration is simply only timers, it is 'all timers' …
     * Since there are an infinite number of them, … for the config only timers and can be added to the calendar before
     * 1995, just name it 'all timers' as well"*). So it says the KINDS and the text typed, never a filter:
     *  - the notifications window has a name of its own (user rule 2026-10-06), [NOTIFICATIONS_WINDOW_TITLE];
     *  - one to three kinds: "all timers", "all timers and alarms", "all tasks, timers and alarms";
     *  - more: "all tasks, timers and 4 more kinds"; every kind: "everything", or "everything but timers" with one or
     *    two left out; none: "nothing";
     *  - with text typed, the kinds it is looked for in: `timers matching "tea"`.
     * A name the user gave the window ([WindowFrameHost.tabTitles] — a lateral-menu button's, or "Rename") is shown in
     * its place, by the frame.
     */
    fun windowTitle(config: Config): String {
        if (isNotificationsWindow(config)) return NOTIFICATIONS_WINDOW_TITLE
        // Of the kinds the window offers (user rule 2026-11-04, [Config.pageTypesOnly]): a page's few kinds all checked
        // are named ("all prompts, answers and reasoning chains"), never "everything".
        val offered = config.offeredKinds
        val kinds = offered.filter { it in config.searchedKinds }
        val left = offered.filter { it !in config.searchedKinds }
        fun list(names: List<String>): String =
            if (names.size == 1) names.single() else names.dropLast(1).joinToString(", ") + " and " + names.last()
        val what =
            when {
                kinds.isEmpty() -> return "nothing"
                left.isEmpty() && kinds.size > 3 -> "everything"
                left.size <= 2 && kinds.size > 3 -> "everything but " + list(left.map { it.plural })
                kinds.size <= 3 -> list(kinds.map { it.plural })
                else -> kinds.take(2).joinToString(", ") { it.plural } + " and ${kinds.size - 2} more kinds"
            }
        val query = config.query.trim()
        return when {
            query.isNotEmpty() -> "$what matching \"$query\""
            what.startsWith("everything") -> what
            else -> "all $what"
        }
    }

    /**
     * Whether the notifications window is owed (user rule 2026-10-04): a notification was posted while the app was NOT
     * in focus — at or after [unfocusedSinceMillis], the instant it lost it (null: it has the focus and never lost it
     * since this was last asked) — that the window has not been shown for yet ([answeredAtMillis], the last one it
     * was; null = none).
     */
    fun notificationsWindowOwed(lastNotificationAtMillis: Long?, unfocusedSinceMillis: Long?, answeredAtMillis: Long?): Boolean =
        lastNotificationAtMillis != null && unfocusedSinceMillis != null &&
            lastNotificationAtMillis >= unfocusedSinceMillis &&
            (answeredAtMillis == null || lastNotificationAtMillis > answeredAtMillis)

    /** The kinds the added CREATION rows make, in the drop-down's order ([CREATABLE]), each once. */
    fun creationKinds(added: List<Result>): List<Kind> {
        val made = added.filter { it.kind == Kind.Creation }.filterIsInstance<ItemResult>().mapTo(HashSet()) { it.id }
        return CREATABLE.filter { it.name in made }
    }

    /**
     * The kinds whose action groups [added] brings (the top right section's `onlyKinds`): the kind each element is
     * acted on as ([actionKindOf]) and its own — a "New quota" row is acted on as a quota AND is a creation row, so it
     * brings the quota's default-configuration actions and the creation row's own.
     */
    fun actionKindsOf(added: List<Result>): Set<Kind> =
        added.flatMapTo(HashSet()) { listOf(actionKindOf(it), it.kind) }

    /**
     * **A search that filters nothing** — what Reset "cancels the whole configuration" to (user rule 2026-10-15: *"it
     * means no filter, so every possible element are shown, so the element type selector has everything selected"*):
     * every kind checked, no text, every filter, sort and selection at its default and nothing added. Not [Config]'s
     * default, which checks the tasks alone. A getter: [Config]'s defaults are declared further down this object, and a
     * value built here would read them before they are set.
     */
    val CLEARED_CONFIG: Config
        get() = clearedConfig("")

    /**
     * [CLEARED_CONFIG] of a window on the page [page]: every kind checked, and nothing else — but the page's types alone
     * offered (user rule 2026-11-04, [Config.pageTypesOnly]), as its Search button opens.
     */
    fun clearedConfig(page: String): Config = Config(kinds = Kind.entries.toSet(), page = page, pageTypesOnly = true)

    /**
     * What the Search window's **Reset** puts in the place of [current]: the configuration the window [opened] with
     * while it has changed since — so a window opened on something ("edit…" on the calendar) goes back to it (user rule
     * 2026-10-02) — else the whole configuration cancelled ([CLEARED_CONFIG]), so a second click comes back to the
     * opening one (anomaly 2026-10-15: the History Search button's window, reset as it opened, went to the default
     * configuration — the tasks checked — and nothing brought its history units back). The WHOLE configuration:
     * filters, sorts and added elements included. Equal to [current] when there is nothing to reset. Never its page
     * ([Config.page]): a window reset stays on the page it is on.
     */
    fun resetConfig(current: Config, opened: Config?): Config =
        if (opened != null && current != opened) opened else clearedConfig(current.page)

    /**
     * [config] for a Search window opened from the Search window [sourceId] holding [source] (user rule 2026-10-18):
     * linked to it ([Config.openedFrom]), and holding the calendar right-click it came from
     * ([Config.calendarClickMillis]) unless [config] holds one of its own — so its time fields offer "Right-click time"
     * as the first window's do.
     */
    fun linkedConfig(config: Config, sourceId: String, source: Config): Config =
        config.copy(openedFrom = sourceId, calendarClickMillis = config.calendarClickMillis ?: source.calendarClickMillis)

    /** The Search configuration a [Kind.Window] creation row opens: every window TYPE, once. */
    val WINDOW_TYPES_CONFIG: Config
        get() = Config(kinds = setOf(Kind.Window), filters = Filters(windowDuplicates = WindowDuplicates.Hidden))

    /**
     * Whether the window rows list every window (every copy, every per-object window) or ONE row per window TYPE
     * ([WindowEntry.type]) — the Configuration Search window's "Duplicates" filter.
     */
    enum class WindowDuplicates(val label: String) { Shown("shown"), Hidden("hidden") }

    /**
     * Where a window stands, as its row says it: on screen, reduced to the window bar along the bottom of the
     * app (minimized, `popups.md`), or not open at all. This order is the sort key's.
     */
    enum class WindowStatus(val label: String) {
        Open("open"),
        Minimized("minimized"),
        NotOpen("not open"),
    }

    /**
     * One window of the app, as a [Kind.Window] row lists it: [id] is its frame id (`Search`, `Search#2`,
     * `TaskEdit#3`) — what opening the row brings back — and [title] what its head reads. Every open window is one
     * entry, each copy and each per-object window on its own; a lateral-menu window that is not open is one entry
     * too, so it can be found and opened from here. Built by `App` from its windows, never stored.
     */
    data class WindowEntry(
        val id: String,
        val title: String,
        val status: WindowStatus,
        /**
         * The window's TYPE, which the "Duplicates: hidden" filter lists once: a lateral-menu window's kind, a
         * per-object window's [org.example.project.ui.ObjectWindowKey.Kind] (so the default timer's window is not a
         * timer's window), else its frame id's base.
         */
        val type: String = id,
        /** What a row of that type is called. */
        val typeTitle: String = title,
        /** A TYPE no window is open of — listed only one-per-type, never as a window of its own. */
        val placeholder: Boolean = false,
        /**
         * User rule 2026-10-12: this entry is NOT a window — it is **a chord the user mapped to a window**, handed in
         * with `App`'s windows because, like them, it is the device's and not the account's ([id] is the mapping's).
         * A [Kind.Shortcut] row, never a [Kind.Window] one.
         */
        val mapping: WindowChord? = null,
    ) {
        /** What a mapping says: the window it brings back (null: none chosen yet) and its chord (null: none yet). */
        data class WindowChord(val windowTitle: String?, val chord: String?)

        companion object {
            /** A one-per-type row's id: what opening it asks `App` for. */
            const val TYPE_PREFIX: String = "type:"
        }
    }

    /**
     * User rule 2026-11-16, "Where to search": **a part of a row the bars can be matched against** ([Filters.searchIn],
     * [fieldsMatch]): its id ([keyOf] without its kind), its name, its description ([descriptionOf]), the labels of
     * its buttons ([buttonLabelsOf]).
     */
    enum class SearchField { Id, Name, Description, Buttons }

    /** [Filters.childDistance] for every level below a row: its children, theirs, and so on ("any"). */
    const val CHILD_DISTANCE_ANY: Int = 0

    /** What the bars are matched against by default ([Filters.searchIn]): the name alone. */
    val DEFAULT_SEARCH_IN: Set<SearchField> = setOf(SearchField.Name)

    /** What a search bar's completion list offers ([Config.completion]). */
    enum class Completion(val label: String) {
        TaskTitles("task titles"),
    }

    /**
     * User rule 2026-11-02: the options of the search bar's completion list: each once, never the text in the bar (a
     * blank title completes nothing). The title suggestions' own order ([SchedulerDomain.titleSuggestions]).
     */
    fun completionOptions(state: SchedulerState, completion: Completion?, query: String): List<String> =
        when (completion) {
            null -> emptyList()
            Completion.TaskTitles ->
                SchedulerDomain.titleSuggestions(state, query.trim())
                    .filter { it.isNotBlank() && !it.equals(query.trim(), ignoreCase = true) }
                    .distinct()
        }

    /**
     * The window's configuration: what is typed in the bar, which kinds are checked, and the per-kind
     * [Filters] the Configuration Search window edits. **Local-only view state** — kept on this device with the
     * window's placement, so the window comes back as it was left after a close or a restart, and never
     * synced: how the user is looking for something is not a fact about the account.
     */
    data class Config(
        val query: String = "",
        /**
         * User rule 2026-11-06: **the search bars the user added** to the window's own ([query] is bar 1, these are 2, 3…),
         * and the formula that puts them together ([queryFormula], [SearchBarFormula]) — blank: every bar at once.
         */
        val extraQueries: List<String> = emptyList(),
        val queryFormula: String = "",
        val kinds: Set<Kind> = setOf(Kind.Task),
        val filters: Filters = Filters(),
        /** Dominant first ([SortMethod]). */
        val sorts: List<SortMethod> = DEFAULT_SORTS,
        /**
         * User rule 2026-10-30: whether [sorts] follow the configuration's DEFAULT sorting ([defaultSortsOf]) — true
         * from the window's opening and from each click on the Sort by's "Default" button, until the user changes the
         * sorting. While true, each new search configuration gets its default ([withDefaultSortFollowed]).
         */
        val sortFollowsDefault: Boolean = true,
        /**
         * The **added elements** — the Search window's right half — as result keys ([keyOf]), in the order they
         * were added, each once. Kept with the rest of the configuration: local-only, never synced. A key whose
         * element is gone is kept but lists nothing ([resolve]).
         */
        val added: List<String> = emptyList(),
        /**
         * User rule 2026-11-21: **the caller the [added] elements were added for** — a field's window ([PopupSearchField]):
         * the set of elements of the actions section's group its field stands in, by their keys ([fieldCallerOf]). Its
         * added elements come back when the field opens it again from that caller, and are lost from another (*"the field
         * F for only E1 and E2 doesn't exist anymore"*). Null: added from no such caller, or nothing added.
         */
        val addedFor: String? = null,
        /**
         * The **actions' filter** (user rule 2026-10-01): what finds an action by name — the Added elements
         * configurations window's bar — applied to that window AND to this window's top right quarter. Kept with the
         * rest of the configuration, so it belongs to this Search window and not to the configurations window.
         */
        val actionQuery: String = "",
        /**
         * User rule 2026-11-12: **the rows of the actions section whose open state is not the default one**, by
         * [ActionGroup.id] or [microGroupId] — every row has its expansion arrow, and opens or closes against the default
         * ([isActionRowOpen]: every row closed, but the top ones while [actionsExpanded]). Kept with the rest of the
         * window's configuration. It replaced the retracted groups of 2026-10-10, which were open by default.
         */
        val toggledActionRows: Set<String> = emptySet(),
        /**
         * User rule 2026-11-12, a search configuration ([Setting.GroupByAddedElements]): **the actions grouped by the
         * added elements they act on**, from the largest set of them to the smallest — each group a row whose children
         * are its actions ([actionGroups]). Off: one flat list of action rows, each naming the elements it acts on.
         */
        val actionsGrouped: Boolean = true,
        /**
         * User rule 2026-11-12, a search configuration ([Setting.ExpandResults]): **the top rows open by default**, not
         * their children. Off (the default): every row closed until its arrow opens it.
         */
        val actionsExpanded: Boolean = false,
        /**
         * User rule 2026-11-12, a search configuration ([Setting.SearchSortsOnly]): **the section's search bar sorts,
         * and hides nothing** — the behaviour of 2026-10-11 ([sortedByActionSearch]: the groups never filtered, the
         * actions sorted inside them, the list back to its top). Off: it filters as a search does — an action listed
         * only if it answers, a group only while one of its actions does.
         */
        val actionSearchSortsOnly: Boolean = true,
        /**
         * User rule 2026-11-14, "Arrange by dragging" ([Setting.MenuCustomizing]): **a press selects, nothing activates,
         * and a held press drags the selected rows** — in any Search window. The left-side menu keeps its own order (its
         * items); any other list keeps it in [arranged].
         */
        val menuCustomizing: Boolean = false,
        /**
         * User rule 2026-11-14: **the order the user dragged the rows into** ([arrangedResults]), by result key — read
         * while [menuCustomizing] is on. Rows not in it follow, in the list's own order.
         */
        val arranged: List<String> = emptyList(),
        /**
         * User rule 2026-11-12, a search configuration ([Setting.DropButtons]): **every element of the list with a button to
         * drop it** — the "List of parents" takes the parent off ([ElementTree.parentIntents]); any other Search window takes
         * the row off its list ([Filters.excludedElements]). Off by default.
         */
        val dropButtons: Boolean = false,
        /**
         * User rule 2026-11-19, a search configuration ([Setting.ButtonFollowsExpansion]): *"when on updates the button in the
         * left-side menu automatically only for which rows are expanded"*: the menu button that opened this window keeps its
         * open rows as they are opened and closed, and nothing else of the window. Off by default.
         */
        val buttonFollowsExpansion: Boolean = false,
        /**
         * User rule 2026-11-22, a search configuration ([Setting.TaskRowLayout]): **what a task row shows, in this order**
         * ([TaskRowPart]). Empty — the default — is the row as the tree draws it: the kind, the arrow, the title clipped
         * to its column, the path, the percentage, the minimum time, the categories. Given, the row is exactly these parts,
         * a title among them shown whole, and the rows scroll sideways together where they are wider than the list.
         */
        val taskRowLayout: List<TaskRowPart> = emptyList(),
        /**
         * User rule 2026-10-11: **the actions section's own search bar**. It SORTS — inside each group of elements
         * (a macro group), the actions (the micro groups, each under its title) that answer it come first, the best
         * answer first ([actionSearchRank], [sortedByActionSearch]) — and takes nothing away, nor moves a group of
         * elements. Not [actionQuery], which is the configurations window's FILTER.
         */
        val actionSearch: String = "",
        /**
         * User rule 2026-10-11: the blocks checked in the "Blocks on the calendar" action ([CalendarBlock.id]) — each
         * stands under it with its start and end to edit. A block that is gone from the calendar is simply not shown.
         */
        val pickedBlocks: Set<String> = emptySet(),
        /**
         * User rule 2026-10-12: the timers checked in the "Lowest countdown" action of the group of several timers
         * ([AddedAction.TimerLowestCountdown], by id) — each stands under it with its run; none checked, the one with
         * the lowest countdown does. One no longer added is not shown.
         */
        val pickedTimers: Set<String> = emptySet(),
        /**
         * User rule 2026-10-12: the "Lowest countdown" action draws each timer it shows on ONE row — its countdown,
         * its buttons, Elapsed and when it ends side by side — instead of a block of three lines.
         */
        val compactTimers: Boolean = true,
        /**
         * User rule 2026-10-14, the timers' rule for the chronos: the chronos checked in the "Highest count" action of
         * the group of several chronos ([AddedAction.ChronoHighestCount], by id); none checked, the one with the highest
         * count stands under it. One no longer added is not shown.
         */
        val pickedChronos: Set<String> = emptySet(),
        /** Each chrono a chrono's run shows ("Run", "Highest count") on ONE row: its title, its count, its buttons. */
        val compactChronos: Boolean = false,
        /** The resilience action's period field ([AddedAction.TaskResilience]); null = the first kind it offers. */
        val resiliencePeriod: String? = null,
        /**
         * The calendar right-click this window was opened from ("add…", user rule 2026-10-01) — what the
         * calendar filter's "Set to the right-click" button sets its field to, and the button is offered only while
         * this is set. Null for a window that did not come from the calendar.
         */
        val calendarClickMillis: Long? = null,
        /**
         * User rule 2026-11-12: **this window's view of the calendar element** — the day its day selector took it to
         * (ISO, null = the day the window opened on), its display (null = the account's, [SchedulerState.calendarDayMode])
         * and what its calendar shows of the notifications and the actions (a [CalendarNotifications.WindowConfig], what
         * a calendar window held). A change of the window like any other: one History Unit, its Ctrl+Z.
         */
        val calendarDay: String? = null,
        val calendarDayMode: Boolean? = null,
        val calendarView: String = "",
        /** Where "Add to the calendar" lays the added elements, and until when ([Placement], user rule 2026-10-05). */
        val placement: Placement = Placement(),
        /**
         * The feature page the window belongs to (user rule 2026-10-18): blank for the Task Scheduler, [CLAUDE_LOGS_PAGE]
         * for the Claude Code logs. A window is drawn on its page alone; that is all this says of it.
         */
        val page: String = "",
        /**
         * The Search window this one was opened from, by frame id (user rule 2026-10-18: *"the window that opens must be
         * linked to the previous Search window"*) — a row opened, an action's "New", "Search every element of the kind"…
         * Its tab stands beside that window's and it closes with it; null for a window opened from anywhere else.
         */
        val openedFrom: String? = null,
        /**
         * User rule 2026-11-02: the element this window comes from, by key: the one whose blank field under its children
         * opened it ([ElementTree.childSearchConfig]). "Add a parent" has a button that adds it.
         */
        val elementSource: String? = null,
        /**
         * User rule 2026-11-13: **the configurations of the Search windows drawn inside this one**, kept WITH it — so they
         * come back with the window after a restart, and a menu button's "Update" keeps them. Its ▾ windows at any depth
         * (`▾`, `▾▾`, `▾/sort`, `/actions▾`), a field's list (`/field:…`: "List of parents" and its parent distance)
         * and a drop-down's or a menu's list (`/list:<its name>`), each by its id without this window's and as stored
         * ([encode]); one that holds what it opens with is not kept. Local view state like the rest; not the search
         * ([searchConfigurations]).
         */
        val inside: Map<String, String> = emptyMap(),
        /**
         * User rule 2026-11-02: the search bar's **completion list** — what it offers, or null for none, the default
         * ([Completion], [completionOptions]).
         */
        val completion: Completion? = null,
        /**
         * User rule 2026-11-04: **only this page's types** — while on, the type selector offers the kinds of the window's
         * [page] alone ([FeaturePage.kinds]: the logs page's prompts, answers and reasoning chains) and the window searches
         * the checked ones among them ([searchedKinds]); [kinds] keeps what it holds of the others, back when it is off.
         * On in what each page's Search button opens ([pageDefaultConfig], [clearedConfig]); off in a configuration the
         * app builds for one element or kind, and in one stored before it existed, which then search as they did.
         */
        val pageTypesOnly: Boolean = false,
        /**
         * User rule 2026-11-04: **when each configuration of the window was last changed**, by the user, in epoch ms —
         * what the configurations tree's first node sorts by ([configurationTree], [withChanged]). Local-only like the
         * rest; not the search ([searchConfigurations]), so two windows are the same whatever they changed when.
         */
        val settingsChangedAt: Map<Setting, Long> = emptyMap(),
    ) {
        /** The kinds the type selector offers: the page's while [pageTypesOnly], else every kind. */
        val offeredKinds: List<Kind>
            get() = if (pageTypesOnly) FEATURE_PAGES.firstOrNull { it.id == page }?.kinds?.let { k -> Kind.entries.filter { it in k } } ?: Kind.entries else Kind.entries

        /** The kinds the window searches: the checked ones among those offered ([pageTypesOnly]). */
        val searchedKinds: Set<Kind>
            get() = if (pageTypesOnly) offeredKinds.filterTo(HashSet()) { it in kinds } else kinds

        /** Whether the window's rows need `App`'s windows: the window kind is searched, or a window is added. */
        val readsWindows: Boolean
            get() = Kind.Window in kinds || Kind.Shortcut in kinds ||
                (added + filters.includedElements).any { it.startsWith(Kind.Window.name + "/") || it.startsWith(Kind.Shortcut.name + "/") }

        /** JSON, every field optional, so a later build's extra filter is ignored rather than fatal. */
        fun encode(): String =
            configJson.encodeToString(
                StoredConfig.serializer(),
                StoredConfig(
                    query = query,
                    kinds = Kind.entries.filter { it in kinds }.map { it.name },
                    taskInTree = filters.taskInTree.name,
                    taskTreeRoot = filters.taskTreeRoot.name,
                    taskSchedulable = filters.taskSchedulable.name,
                    taskCategory = filters.taskCategory?.value,
                    categoryHasRules = filters.categoryHasRules.name,
                    periodOrigin = filters.periodOrigin.name,
                    periodDrawing = filters.periodDrawing?.name,
                    periodResilience = filters.periodResilience.name,
                    periodResilienceTask = filters.periodResilienceTask?.value,
                    alarmState = filters.alarmState.name,
                    alarmDays = filters.alarmDays.sortedBy { it.isoDayNumber }.map { it.isoDayNumber },
                    timerState = filters.timerState.name,
                    chronoState = filters.chronoState.name,
                    quotaRepeats = filters.quotaRepeats.name,
                    reminderRepeats = filters.reminderRepeats.name,
                    historyCategory = filters.historyCategory?.name,
                    historyWindow = filters.historyWindow?.name,
                    historyUndone = filters.historyUndone.name,
                    historyEngine = filters.historyEngine.name,
                    historyChangedKinds = Kind.entries.filter { it in filters.historyChangedKinds }.map { it.name },
                    historyChangedElement = filters.historyChangedElement,
                    relationSection = filters.relationSection?.name,
                    shortcutRebound = filters.shortcutRebound.name,
                    windowStatus = filters.windowStatus?.name,
                    windowDuplicates = filters.windowDuplicates.name,
                    sortMethods = sorts.map { StoredSortMethod(it.kind?.name, it.key.name, it.descending) },
                    sortFollowsDefault = sortFollowsDefault,
                    taskOnCalendar = filters.taskOnCalendar.name,
                    taskBoxesFromMillis = filters.taskBoxesFrom,
                    taskBoxesUntilMillis = filters.taskBoxesUntil,
                    periodOnCalendar = filters.periodOnCalendar.name,
                    periodBoxesFromMillis = filters.periodBoxesFrom,
                    periodBoxesUntilMillis = filters.periodBoxesUntil,
                    added = added,
                    addedFor = addedFor,
                    actionQuery = actionQuery,
                    toggledActionRows = toggledActionRows.sorted(),
                    actionsGrouped = actionsGrouped,
                    actionsExpanded = actionsExpanded,
                    actionSearchSortsOnly = actionSearchSortsOnly,
                    menuCustomizing = menuCustomizing,
                    arranged = arranged,
                    dropButtons = dropButtons,
                    buttonFollowsExpansion = buttonFollowsExpansion,
                    taskRowLayout = taskRowLayout.map { it.name },
                    actionSearch = actionSearch,
                    pickedBlocks = pickedBlocks.sorted(),
                    pickedTimers = pickedTimers.sorted(),
                    compactTimers = compactTimers,
                    pickedChronos = pickedChronos.sorted(),
                    compactChronos = compactChronos,
                    resiliencePeriod = resiliencePeriod,
                    calendarAddOn = filters.calendarAddOn,
                    calendarAddAtMillis = filters.calendarAddAtMillis,
                    calendarAddKeeping = filters.calendarAddKeeping,
                    calendarAtOn = filters.calendarAtOn,
                    calendarAtMillis = filters.calendarAtMillis,
                    notificationsSinceMillis = filters.notificationsSinceMillis,
                    notificationSources = NotificationSource.entries.filter { it in filters.notificationSources }.map { it.name },
                    calendarClickMillis = calendarClickMillis,
                    calendarDay = calendarDay,
                    calendarDayMode = calendarDayMode,
                    calendarView = calendarView,
                    blocksOf = filters.blocksOf.sorted(),
                    excludedElements = filters.excludedElements.sorted(),
                    includedElements = filters.includedElements.sorted(),
                    noParentOfKinds = Kind.entries.filter { it in filters.noParentOfKinds }.map { it.name },
                    parentsOf = filters.parentsOf.sorted(),
                    involvementOf = filters.involvementOf.sorted(),
                    parentDistance = filters.parentDistance,
                    searchIn = SearchField.entries.filter { it in filters.searchIn }.map { it.name }.takeIf { filters.searchIn != DEFAULT_SEARCH_IN },
                    searchChildren = filters.searchChildren,
                    childSearchIn = SearchField.entries.filter { it in filters.childSearchIn }.map { it.name }.takeIf { filters.childSearchIn != DEFAULT_SEARCH_IN },
                    childDistance = filters.childDistance,
                    placeStartMillis = placement.startMillis,
                    placeEndByDelta = placement.endByDelta,
                    placeStatedLengthMillis = placement.lengthMillis,
                    placeStatedOverMillis = placement.lengthOverMillis,
                    placeEndMillis = placement.endMillis,
                    page = page,
                    openedFrom = openedFrom,
                    elementSource = elementSource,
                    inside = inside,
                    completion = completion?.name,
                    pageTypesOnly = pageTypesOnly,
                    settingsChangedAt = settingsChangedAt.mapKeys { it.key.name },
                    matchCase = filters.matchCase,
                    wholeWord = filters.wholeWord,
                    regex = filters.regex,
                    extraQueries = extraQueries,
                    queryFormula = queryFormula,
                    claudeProject = filters.claudeProject,
                    claudeFromMillis = filters.claudeFrom,
                    claudeUntilMillis = filters.claudeUntil,
                ),
            )

        companion object {
            /**
             * [encode]'s reverse, or null for nothing stored. Also reads the first stored shape (a line of kind
             * names, then the query), which predates the filters. A kind or a value this build does not know is
             * dropped to its default rather than failing the whole configuration.
             */
            fun decode(text: String?): Config? {
                if (text == null) return null
                if (!text.startsWith("{")) {
                    val lines = text.split('\n', limit = 2)
                    return Config(query = lines.getOrElse(1) { "" }, kinds = kindsNamed(lines[0].split(',')))
                }
                val stored = runCatching { configJson.decodeFromString(StoredConfig.serializer(), text) }.getOrNull()
                    ?: return null
                return Config(
                    query = stored.query,
                    kinds = kindsNamed(stored.kinds),
                    filters = Filters(
                        taskInTree = enumNamed(stored.taskInTree, Tri.Any),
                        taskTreeRoot = enumNamed(stored.taskTreeRoot, Tri.Any),
                        taskSchedulable = enumNamed(stored.taskSchedulable, Tri.Any),
                        taskCategory = stored.taskCategory?.let { CategoryId(it) },
                        categoryHasRules = enumNamed(stored.categoryHasRules, Tri.Any),
                        periodOrigin = enumNamed(stored.periodOrigin, PeriodOrigin.Any),
                        periodDrawing = stored.periodDrawing?.let { name -> PeriodDrawing.entries.firstOrNull { it.name == name } },
                        periodResilience = enumNamed(stored.periodResilience, ResilienceLevel.Any),
                        periodResilienceTask = stored.periodResilienceTask?.let(::TaskId),
                        alarmState = enumNamed(stored.alarmState, AlarmState.Any),
                        alarmDays = stored.alarmDays.mapNotNull { n -> DayOfWeek.entries.firstOrNull { it.isoDayNumber == n } }
                            .toSet(),
                        timerState = enumNamed(stored.timerState, TimerState.Any),
                        chronoState = enumNamed(stored.chronoState, TimerState.Any),
                        quotaRepeats = enumNamed(stored.quotaRepeats, Tri.Any),
                        reminderRepeats = enumNamed(stored.reminderRepeats, ReminderRepeats.Any),
                        historyCategory = HistoryCategory.entries.firstOrNull { it.name == stored.historyCategory },
                        historyWindow = HistoryWindow.entries.firstOrNull { it.name == stored.historyWindow },
                        historyUndone = enumNamed(stored.historyUndone, Tri.Any),
                        historyEngine = enumNamed(stored.historyEngine, Tri.Any),
                        historyChangedKinds = kindsNamed(stored.historyChangedKinds).filterTo(HashSet()) { it in HISTORY_CHANGED_KINDS },
                        historyChangedElement = stored.historyChangedElement?.takeIf { it.isNotBlank() },
                        relationSection =
                            TaskRelationsDomain.Section.entries.firstOrNull { it.name == stored.relationSection },
                        shortcutRebound = enumNamed(stored.shortcutRebound, Tri.Any),
                        windowStatus = WindowStatus.entries.firstOrNull { it.name == stored.windowStatus },
                        windowDuplicates = enumNamed(stored.windowDuplicates, WindowDuplicates.Shown),
                        taskOnCalendar = enumNamed(stored.taskOnCalendar, Tri.Any),
                        // A day an older build stored is that day's first instant ("from") or its last ("until").
                        taskBoxesFrom = stored.taskBoxesFromMillis ?: dayStart(stored.taskBoxesFrom),
                        taskBoxesUntil = stored.taskBoxesUntilMillis ?: dayEnd(stored.taskBoxesUntil),
                        periodOnCalendar = enumNamed(stored.periodOnCalendar, Tri.Any),
                        periodBoxesFrom = stored.periodBoxesFromMillis ?: dayStart(stored.periodBoxesFrom),
                        periodBoxesUntil = stored.periodBoxesUntilMillis ?: dayEnd(stored.periodBoxesUntil),
                        calendarAddOn = stored.calendarAddOn,
                        matchCase = stored.matchCase,
                        wholeWord = stored.wholeWord,
                        regex = stored.regex,
                        calendarAddAtMillis = stored.calendarAddAtMillis,
                        calendarAddKeeping = stored.calendarAddKeeping,
                        calendarAtOn = stored.calendarAtOn,
                        calendarAtMillis = stored.calendarAtMillis,
                        notificationsSinceMillis = stored.notificationsSinceMillis,
                        notificationSources =
                            NotificationSource.entries.filterTo(HashSet()) { it.name in stored.notificationSources },
                        blocksOf = stored.blocksOf.toSet(),
                        excludedElements = stored.excludedElements.toSet(),
                        includedElements = stored.includedElements.toSet(),
                        noParentOfKinds = kindsNamed(stored.noParentOfKinds),
                        parentsOf = stored.parentsOf.toSet(),
                        involvementOf = stored.involvementOf.toSet(),
                        parentDistance = stored.parentDistance.coerceAtLeast(PARENT_DISTANCE_HIGHEST),
                        searchIn = stored.searchIn?.let { names -> SearchField.entries.filterTo(HashSet()) { it.name in names } } ?: DEFAULT_SEARCH_IN,
                        searchChildren = stored.searchChildren,
                        childSearchIn = stored.childSearchIn?.let { names -> SearchField.entries.filterTo(HashSet()) { it.name in names } } ?: DEFAULT_SEARCH_IN,
                        childDistance = stored.childDistance.coerceAtLeast(CHILD_DISTANCE_ANY),
                        claudeProject = stored.claudeProject,
                        claudeFrom = stored.claudeFromMillis,
                        claudeUntil = stored.claudeUntilMillis,
                    ),
                    sorts = sortMethodsNamed(stored.sortMethods ?: legacySortMethods(stored)),
                    sortFollowsDefault = stored.sortFollowsDefault,
                    // Heals the index-shaped history-unit keys of builds before 2026-10-03 ([historyUnitId]): an
                    // index named whichever unit had shifted into it, so the key no longer means what was added.
                    added = stored.added.distinct().filterNot(::isLegacyHistoryUnitKey),
                    addedFor = stored.addedFor,
                    actionQuery = stored.actionQuery,
                    // The retracted groups an older build kept ([Stored.collapsedActionGroups]) were open by default:
                    // read as rows toggled from today's default (closed), they would open what was closed. Dropped.
                    toggledActionRows = stored.toggledActionRows.toSet(),
                    actionsGrouped = stored.actionsGrouped,
                    actionsExpanded = stored.actionsExpanded,
                    actionSearchSortsOnly = stored.actionSearchSortsOnly,
                    menuCustomizing = stored.menuCustomizing,
                    arranged = stored.arranged,
                    dropButtons = stored.dropButtons,
                    buttonFollowsExpansion = stored.buttonFollowsExpansion,
                    taskRowLayout = TaskRowPart.decode(stored.taskRowLayout),
                    actionSearch = stored.actionSearch,
                    pickedBlocks = stored.pickedBlocks.toSet(),
                    pickedTimers = stored.pickedTimers.toSet(),
                    compactTimers = stored.compactTimers,
                    pickedChronos = stored.pickedChronos.toSet(),
                    compactChronos = stored.compactChronos,
                    resiliencePeriod = stored.resiliencePeriod,
                    calendarClickMillis = stored.calendarClickMillis,
                    calendarDay = stored.calendarDay,
                    calendarDayMode = stored.calendarDayMode,
                    calendarView = stored.calendarView,
                    placement = Placement(
                        startMillis = stored.placeStartMillis,
                        endByDelta = stored.placeEndByDelta,
                        // The builds before 2026-10-19 wrote `placeLengthMillis` whether stated or not (1 hour then),
                        // so it is not read: the added elements' own length stands until one is stated anew.
                        lengthMillis = stored.placeStatedLengthMillis?.takeIf { it > 0L },
                        lengthOverMillis = stored.placeStatedOverMillis,
                        endMillis = stored.placeEndMillis,
                    ),
                    page = stored.page,
                    openedFrom = stored.openedFrom,
                    elementSource = stored.elementSource,
                    // The ▾ tree has no "General" node since 2026-11-14: what its pop-up kept names no window any more.
                    inside = stored.inside.filterKeys { RETIRED_GENERAL_POPUP !in it },
                    completion = Completion.entries.firstOrNull { it.name == stored.completion },
                    pageTypesOnly = stored.pageTypesOnly,
                    extraQueries = stored.extraQueries,
                    queryFormula = stored.queryFormula,
                    settingsChangedAt = stored.settingsChangedAt.mapNotNull { (name, at) -> Setting.entries.firstOrNull { it.name == name }?.let { it to at } }.toMap(),
                )
            }

            /**
             * The stored methods this build can read, in order: one naming a kind or a key it does not know, or a
             * key its kind does not offer, is dropped — the rest keep their order — and so is a repeat.
             */
            /**
             * The first sorting shape (2026-09-25, `14e11c8`): one whole-list sort (`sort`, absent = relevance)
             * and one sort per kind (`kindSorts`), each kind's applied beneath the whole list's. As a list: the
             * whole-list method dominant, the kinds' below it. Nothing stored at all is the default list.
             */
            private fun legacySortMethods(stored: StoredConfig): List<StoredSortMethod> =
                listOf(StoredSortMethod(null, stored.sort ?: SortKey.Relevance.name, stored.sortDescending)) +
                    Kind.entries.mapNotNull { kind ->
                        stored.kindSorts[kind.name]?.let {
                            StoredSortMethod(kind.name, it.key ?: SortKey.Relevance.name, it.descending)
                        }
                    }

            private fun sortMethodsNamed(stored: List<StoredSortMethod>): List<SortMethod> =
                stored.mapNotNull { method ->
                    // Before 2026-10-30 the logs' date was one method per kind: each is the one date about every row.
                    if (method.key in LEGACY_LOG_DATE_KEYS) return@mapNotNull SortMethod(null, SortKey.Date, method.descending)
                    val kind =
                        if (method.kind == null) null else Kind.entries.firstOrNull { it.name == method.kind } ?: return@mapNotNull null
                    val key = sortKeysOf(kind).firstOrNull { it.name == method.key } ?: return@mapNotNull null
                    SortMethod(kind, key, method.descending)
                }.distinctBy { it.kind to it.key }

            private val LEGACY_LOG_DATE_KEYS = setOf("PromptDate", "AnswerDate", "ReasoningDate")

            private fun kindsNamed(names: List<String>): Set<Kind> =
                names.mapNotNull { name -> Kind.entries.firstOrNull { it.name == name } }.toSet()

            private inline fun <reified E : Enum<E>> enumNamed(name: String?, default: E): E =
                enumValues<E>().firstOrNull { it.name == name } ?: default

            /** An ISO date (2026-09-27), or null: for nothing stored and for what does not parse alike. */
            private fun dateNamed(text: String?): LocalDate? = text?.let { runCatching { LocalDate.parse(it) }.getOrNull() }

            private fun dayStart(text: String?): Long? =
                dateNamed(text)?.atStartOfDayIn(TimeZone.currentSystemDefault())?.toEpochMilliseconds()

            private fun dayEnd(text: String?): Long? =
                dateNamed(text)?.plus(1, DateTimeUnit.DAY)?.atStartOfDayIn(TimeZone.currentSystemDefault())?.toEpochMilliseconds()
        }
    }

    /**
     * The Configuration Search window's OWN configuration: what its bar finds among the configurations' names,
     * which kinds' sections it shows, and whether it keeps only the kinds the Search window's results hold.
     * Local-only view state, kept like [Config].
     */
    data class ConfigurationSearch(
        val query: String = "",
        val kinds: Set<Kind> = Kind.entries.toSet(),
        val onlyResultKinds: Boolean = false,
        /**
         * The frame id of the Search window whose configurations this window lists and edits — the original's
         * (`Search`) or a copy's (`Search#2`): the one whose button opened it. Each Search window has its own.
         */
        val target: String = "Search",
        /**
         * User rule 2026-10-11, the Search configurations window's switch: **the list no longer follows the result
         * list** — the kinds whose configurations are shown while [onlyResultKinds] is on, in the order they stood in
         * when the switch was set; null = the list follows the results (a kind is hidden as its last row leaves, shown
         * as its first arrives). What keeps a filter that empties its own type out of the results in view, so it can
         * be set off again — the job the "Show the filters that are on" button had until then.
         */
        val frozenKinds: List<Kind>? = null,
        /** The groups retracted to their heading, by [SettingGroup.id] — each has its expansion arrow (2026-10-11). */
        val collapsedGroups: Set<String> = emptySet(),
        /**
         * User rule 2026-11-01: the feature pages whose kinds the window offers ([FeaturePage.id]). Only the logs page's
         * checked, the type selector holds its three kinds and the other kinds' sections go. [kinds] keeps what it holds
         * of a page left unchecked: checked again, it comes back as it was.
         */
        val pages: Set<String> = FEATURE_PAGES.mapTo(HashSet()) { it.id },
    ) {
        /** The kinds the window offers, and of them those it shows. */
        val offeredKinds: List<Kind> get() = kindsOfPages(pages)
        val shownKinds: Set<Kind> get() = offeredKinds.filterTo(HashSet()) { it in kinds }

        fun encode(): String =
            configJson.encodeToString(
                StoredConfigurationSearch.serializer(),
                StoredConfigurationSearch(
                    query, Kind.entries.filter { it in kinds }.map { it.name }, onlyResultKinds, target,
                    frozenKinds = frozenKinds?.map { it.name },
                    collapsedGroups = collapsedGroups.sorted(),
                    logKindsKnown = true,
                    // Every page is nothing to say: a page added later is then offered like the others.
                    pages = pages.sorted().takeIf { it.size < FEATURE_PAGES.size },
                ),
            )

        companion object {
            fun decode(text: String?): ConfigurationSearch? {
                if (text == null) return null
                val stored =
                    runCatching { configJson.decodeFromString(StoredConfigurationSearch.serializer(), text) }.getOrNull()
                        ?: return null
                val kinds = stored.kinds.mapNotNull { name -> Kind.entries.firstOrNull { it.name == name } }.toSet()
                return ConfigurationSearch(
                    stored.query,
                    // Anomaly 2026-10-31 (*"I don't see the project selector"*): "every kind" written before the logs' kinds
                    // existed lacks them, which hid their section for good. A kind cannot have been unchecked before it was.
                    if (stored.logKindsKnown || kinds.isEmpty()) kinds else kinds + CLAUDE_KINDS,
                    stored.onlyResultKinds,
                    stored.target,
                    stored.frozenKinds?.mapNotNull { name -> Kind.entries.firstOrNull { it.name == name } },
                    stored.collapsedGroups.toSet(),
                    stored.pages?.filterTo(HashSet()) { id -> FEATURE_PAGES.any { it.id == id } } ?: FEATURE_PAGES.mapTo(HashSet()) { it.id },
                )
            }
        }
    }

    /** "Any", "yes" or "no" — a filter that can also be left off. */
    enum class Tri(val label: String) { Any("any"), Yes("yes"), No("no") }

    /**
     * The "Default periods" filter: whether a restrictive period is one the app ships (named by its fixed id,
     * `PeriodKinds.isUserDefined` false) or one of the account's own. Stored by the entries' names, which are kept as
     * they were under the filter's first label ("Origin": built-in / yours).
     */
    enum class PeriodOrigin(val label: String) { Any("any"), BuiltIn("yes"), Yours("no") }

    /**
     * How much resilience a task has to a period, as the period filter asks it ([Filters.periodResilience]): none (the
     * period refuses the task), some (it may run there, at whatever share), or all of it (the period leaves it be).
     */
    enum class ResilienceLevel(val label: String) {
        Any("any"), None("none (0 %)"), Some("some (above 0 %)"), Full("full (100 %)");

        fun holds(resilience: Double): Boolean =
            when (this) {
                Any -> true
                None -> resilience <= 0.0
                Some -> resilience > 0.0
                Full -> resilience >= 1.0
            }
    }

    enum class AlarmState(val label: String) { Any("any"), On("on"), Off("off") }

    enum class TimerState(val label: String) { Any("any"), Idle("idle"), Running("running"), Paused("paused") }

    enum class ReminderRepeats(val label: String) { Any("any"), OneOff("one-off"), Repeating("repeating") }

    /**
     * The per-kind filters of the Search window, which the Configuration Search window edits. Every one has an
     * "any" value, and a filter of a kind applies to that kind's rows only — so the defaults filter nothing.
     */
    data class Filters(
        val taskInTree: Tri = Tri.Any,
        /**
         * User rule 2026-11-14 (use case: *"display all the root cells of every tree"*): only the tasks that are the root of
         * a tree — one standing directly under the global root ([treeRootsByTask], [TaskResult.treeRoots]) — or none of them.
         */
        val taskTreeRoot: Tri = Tri.Any,
        /**
         * PRD §9's *schedulable*: a task the scheduler may place — a leaf (no sub-task) that still lives in the tree
         * ([SchedulerDomain.isPlaceableTask], the predicate "start this task now" asks too).
         */
        val taskSchedulable: Tri = Tri.Any,
        /** Null = any category. */
        val taskCategory: CategoryId? = null,
        val categoryHasRules: Tri = Tri.Any,
        val periodOrigin: PeriodOrigin = PeriodOrigin.Any,
        /**
         * User rule 2026-10-11 (*"filter to only periods that have the drawing …"*): only the periods drawn this way
         * ([PeriodKindConfig.drawing], the period's own). Null = any.
         */
        val periodDrawing: PeriodDrawing? = null,
        /**
         * User rule 2026-10-11 (*"filter to only periods that have … resilience to …"*): only the periods
         * [periodResilienceTask] has this much resilience to ([ResilienceLevel], the task's
         * [org.example.project.scheduler.model.Task.resilienceFor]). Says nothing until both are given.
         */
        val periodResilience: ResilienceLevel = ResilienceLevel.Any,
        val periodResilienceTask: TaskId? = null,
        val alarmState: AlarmState = AlarmState.Any,
        /** Empty = any day; else the alarm rings on at least one of them. */
        val alarmDays: Set<DayOfWeek> = emptySet(),
        val timerState: TimerState = TimerState.Any,
        /** A chrono is idle, running or paused like a timer — the same filter, over its own rows. */
        val chronoState: TimerState = TimerState.Any,
        /** User rule 2026-10-03: the quotas that repeat, or the ones that do not. */
        val quotaRepeats: Tri = Tri.Any,
        val reminderRepeats: ReminderRepeats = ReminderRepeats.Any,
        /** Null = any category (PRD §5's stacks: edit, selection, calendar, main, window navigation). */
        val historyCategory: HistoryCategory? = null,
        /** Null = any window the unit was made in. */
        val historyWindow: HistoryWindow? = null,
        /** Yes = undone on its device (still redoable there). */
        val historyUndone: Tri = Tri.Any,
        /**
         * User rule 2026-10-06: Yes = only what the SCHEDULER ENGINE produced — each new set of rules it found
         * ([schedulerRunId]). It is a choice of "Made in" ([madeIn]), beside the windows: the engine is where those
         * rows were made. (No = only the units made in a window; no control sets it.)
         */
        val historyEngine: Tri = Tri.Any,
        /**
         * Empty = any: else the unit changed an element of one of these kinds ([HISTORY_CHANGED_KINDS],
         * [org.example.project.scheduler.state.changedElements]) — several can be checked.
         */
        val historyChangedKinds: Set<Kind> = emptySet(),
        /**
         * Null = any: else the unit changed this element, by its Search key ([keyOf], `Alarm/alarm-3`). A key, so an
         * element deleted since is still one to look for.
         */
        val historyChangedElement: String? = null,
        /** Null = any of the Task relations window's four sections. */
        val relationSection: TaskRelationsDomain.Section? = null,
        /** Yes = bound to another chord than the one it ships with. */
        val shortcutRebound: Tri = Tri.Any,
        /** Null = any: open, minimized or not open. */
        val windowStatus: WindowStatus? = null,
        /** Hidden = one row per window TYPE ([WindowEntry.type]). */
        val windowDuplicates: WindowDuplicates = WindowDuplicates.Shown,
        /** Yes = the task has at least one box on the calendar ([SchedulerDomain.calendarBoxesOfTask]). */
        val taskOnCalendar: Tri = Tri.Any,
        /**
         * User rule 2026-10-11: **the window of the calendar the task's boxes are asked about**, as two instants (a
         * date and a time — the calendar's "edit…" sets both to the right-click). The task is kept when it has a box
         * touching the window: one still running at [taskBoxesFrom], or starting by [taskBoxesUntil] — with both, one
         * box doing both; with both the same instant, a box the instant is in. Null = no bound on that side.
         * (Until then two DAYS, and "every box of the task lies within them".)
         */
        val taskBoxesFrom: Long? = null,
        val taskBoxesUntil: Long? = null,
        /** Yes = at least one period of this kind is on the calendar. */
        val periodOnCalendar: Tri = Tri.Any,
        /** The same window ([taskBoxesFrom]) for the periods of the kind on the calendar. */
        val periodBoxesFrom: Long? = null,
        val periodBoxesUntil: Long? = null,
        /**
         * **The calendar filter** (user rule 2026-10-01): a GLOBAL filter — about every row, whatever its kind — that
         * keeps only what can be put on the calendar at [calendarAddAtMillis] ([calendarAddable]). Its switch is
         * [calendarAddOn]; the position is kept while it is off, so turning it back on finds it.
         */
        /**
         * User rule 2026-11-04, the search bar's right-click menu: **only what the bar holds with its case**
         * ([matchCase]) **and as whole words** ([wholeWord]) — the task tree's find bar's two options, read the same
         * way ([queryOptions], [matchesWith]); filters, so an element that does not match is no result, and only what
         * matches is highlighted ([matchRanges]). Off by default, and in a configuration stored before them.
         */
        val matchCase: Boolean = false,
        val wholeWord: Boolean = false,
        /** User rule 2026-11-06: the bars hold regular expressions ([TaskTreeSearch.Options.regex]). */
        val regex: Boolean = false,
        /**
         * User rule 2026-11-16, "Where to search": **what of a row the bars are matched against** ([SearchField]); a row
         * answers a bar where one of them does ([fieldsMatch]). Its name alone by default, as before that day.
         */
        val searchIn: Set<SearchField> = DEFAULT_SEARCH_IN,
        /** ...and its children's, those [childDistance] levels below it ([ElementTree.childrenOf]), by [childSearchIn]. */
        val searchChildren: Boolean = false,
        val childSearchIn: Set<SearchField> = DEFAULT_SEARCH_IN,
        val childDistance: Int = 1,
        val calendarAddOn: Boolean = false,
        val calendarAddAtMillis: Long? = null,
        /**
         * User rule 2026-10-07: the calendar filter's stricter state — while it is on ([calendarAddOn]), keep only what
         * can be added there **without removing anything** ([calendarAddKeepsEverything]). The three states of the
         * filter are [CalendarAddFilter]: this and the switch read as one choice.
         */
        val calendarAddKeeping: Boolean = false,
        /**
         * **The "is on the calendar at" filter** (user rule 2026-10-01, the calendar's "edit…"): GLOBAL like the one
         * above — keeps only what is on the timeline at [calendarAtMillis] ([calendarElementsAt]). [calendarAtOn] is
         * its switch; the position is kept while it is off.
         */
        val calendarAtOn: Boolean = false,
        val calendarAtMillis: Long? = null,
        /**
         * Null = every notification the log holds: else only those posted from this instant on — when the app lost
         * the focus, for the notifications window the app opens ([notificationsConfig]).
         */
        val notificationsSinceMillis: Long? = null,
        /**
         * Empty = from anything: else only the notifications that came from one of these ([NotificationSource] — the
         * scheduler engine's "task to do now", a screen break, an alarm…); several can be checked.
         */
        val notificationSources: Set<NotificationSource> = emptySet(),
        /**
         * Empty = every block of the calendar: else only the blocks of these elements, by their Search keys ([keyOf],
         * `Task/…`, `RestrictivePeriod/…`) — what "Blocks on the calendar" opens ([blocksSearchConfig]).
         */
        val blocksOf: Set<String> = emptySet(),
        /**
         * User rule 2026-11-02: the elements kept out of the list, by key: what a Search window opened to find a child
         * starts with, its parent ([ElementTree.childSearchConfig]).
         */
        val excludedElements: Set<String> = emptySet(),
        /**
         * User rule 2026-11-21 (*"Make sure every search window of the app always respect only what is in 'Currently
         * on'"*): **where the rows of a window whose rows its caller gives come from** ([ListedFrom]) — a menu's entries,
         * the left-side menu's items, the elements added to a window, the actions on them, the tasks a picker may pick,
         * a ▾'s configurations. Set by the one funnel ([withListedFrom], `SearchWindow`), never by hand; never stored
         * (the caller gives it afresh at every drawing). Null: the window searches the account.
         */
        val listedFrom: ListedFrom? = null,
        /**
         * User rule 2026-11-02: the elements always in the list, by key: **this overrides every other way of narrowing it**,
         * the filters, [excludedElements], the checked kinds and the search text ([results]).
         */
        val includedElements: Set<String> = emptySet(),
        /**
         * User rule 2026-11-12: **only the elements with no parent of these kinds** ([ElementTree.parentsOf]); empty = any.
         * GLOBAL, about every row. With [Kind.Task] in it, the task list also offers the root task ([taskResults]): the one
         * task no task stands above, so *"the root task must be found when the search configurations are only tasks and
         * must not have parents of type [task]"*.
         */
        val noParentOfKinds: Set<Kind> = emptySet(),
        /**
         * User rule 2026-11-12: **only the parents of these elements**, by key ([ElementTree.parentsOf]); empty = any. What
         * the "List of parents" of the actions section searches ([parentsListConfig]).
         */
        val parentsOf: Set<String> = emptySet(),
        /**
         * User rule 2026-11-13, the **parent distance**: how far above the elements of [parentsOf] a parent stands to be
         * listed — 1, the direct parents (the default); 2, their parents alone, not the direct ones (anomaly 2026-11-16)…
         * ([ElementTree.parentsWithin]); [PARENT_DISTANCE_HIGHEST], the highest distance there is. Asks nothing while
         * [parentsOf] is empty.
         */
        val parentDistance: Int = 1,
        /**
         * User rule 2026-11-20 (*"I don't see any sorting search configuration in the down arrow … It means there is a
         * fundamental issue"*): **the tasks the "involvement" order of the presence ratio target evolutions is measured
         * against** ([SortKey.EvolutionInvolvement]), by key. It keeps nothing out: it is what that sort reads, a
         * configuration of the window like any other, shown in its ▾ and changed there.
         */
        val involvementOf: Set<String> = emptySet(),
        /**
         * The Claude Code logs' filters (user rule 2026-10-18), one for the three kinds: only the entries of this project
         * (null = any), and only those written in `[claudeFrom, claudeUntil]` (a null bound asks nothing on its side).
         */
        val claudeProject: String? = null,
        val claudeFrom: Long? = null,
        val claudeUntil: Long? = null,
    ) {
        /** What "Made in" stands on: the scheduler engine, a window, or null for anything. */
        val madeIn: MadeIn?
            get() = when {
                historyEngine == Tri.Yes -> MadeIn.Engine
                else -> historyWindow?.let { MadeIn.Window(it) }
            }

        /** These filters with "Made in" on [choice] — the one field, so the engine and a window are never both asked. */
        fun withMadeIn(choice: MadeIn?): Filters =
            when (choice) {
                null -> copy(historyWindow = null, historyEngine = Tri.Any)
                MadeIn.Engine -> copy(historyWindow = null, historyEngine = Tri.Yes)
                is MadeIn.Window -> copy(historyWindow = choice.window, historyEngine = Tri.Any)
            }

        /** The instant the calendar filter keeps rows for, while it is on and has one; else null. */
        val calendarAddAt: Long?
            get() = calendarAddAtMillis.takeIf { calendarAddOn }

        /** Which of its three states the calendar filter is in ([CalendarAddFilter]). */
        val calendarAddFilter: CalendarAddFilter
            get() = when {
                !calendarAddOn -> CalendarAddFilter.None
                calendarAddKeeping -> CalendarAddFilter.KeepingEverything
                else -> CalendarAddFilter.Addable
            }

        /** These filters with the calendar filter in [state]; its instant is kept whichever it is. */
        fun withCalendarAddFilter(state: CalendarAddFilter): Filters =
            copy(calendarAddOn = state != CalendarAddFilter.None, calendarAddKeeping = state == CalendarAddFilter.KeepingEverything)

        /** The instant the "is on the calendar at" filter keeps rows for, while it is on and has one; else null. */
        val calendarAt: Long?
            get() = calendarAtMillis.takeIf { calendarAtOn }

        /** Whether any filter reads the calendar: the only case its boxes are gathered at all ([results]). */
        val readsCalendar: Boolean
            get() = taskOnCalendar != Tri.Any || taskBoxesFrom != null || taskBoxesUntil != null ||
                periodOnCalendar != Tri.Any || periodBoxesFrom != null || periodBoxesUntil != null

        /** The search bar's filters ([matchCase], [wholeWord], [regex]) as a find bar holds them. */
        val queryOptions: TaskTreeSearch.Options get() = TaskTreeSearch.Options(matchCase, wholeWord, regex)

        /** Whether the bars are matched against the rows' names alone, as before "Where to search" ([searchIn]). */
        val searchesNamesOnly: Boolean get() = searchIn == DEFAULT_SEARCH_IN && !searchChildren

        /** Whether "Where to search" checks [field] of a row ([searchIn]), or of its children ([childSearchIn]). */
        fun searches(setting: Setting): Boolean? =
            when (setting) {
                Setting.SearchInId -> SearchField.Id in searchIn
                Setting.SearchInName -> SearchField.Name in searchIn
                Setting.SearchInDescription -> SearchField.Description in searchIn
                Setting.SearchInButtons -> SearchField.Buttons in searchIn
                Setting.SearchInChildren -> searchChildren
                Setting.ChildSearchInId -> SearchField.Id in childSearchIn
                Setting.ChildSearchInName -> SearchField.Name in childSearchIn
                Setting.ChildSearchInDescription -> SearchField.Description in childSearchIn
                Setting.ChildSearchInButtons -> SearchField.Buttons in childSearchIn
                else -> null
            }

        /** These filters with "Where to search"'s [setting] checked or not. */
        fun withSearches(setting: Setting, on: Boolean): Filters {
            fun Set<SearchField>.with(field: SearchField) = if (on) this + field else this - field
            return when (setting) {
                Setting.SearchInId -> copy(searchIn = searchIn.with(SearchField.Id))
                Setting.SearchInName -> copy(searchIn = searchIn.with(SearchField.Name))
                Setting.SearchInDescription -> copy(searchIn = searchIn.with(SearchField.Description))
                Setting.SearchInButtons -> copy(searchIn = searchIn.with(SearchField.Buttons))
                Setting.SearchInChildren -> copy(searchChildren = on)
                Setting.ChildSearchInId -> copy(childSearchIn = childSearchIn.with(SearchField.Id))
                Setting.ChildSearchInName -> copy(childSearchIn = childSearchIn.with(SearchField.Name))
                Setting.ChildSearchInDescription -> copy(childSearchIn = childSearchIn.with(SearchField.Description))
                Setting.ChildSearchInButtons -> copy(childSearchIn = childSearchIn.with(SearchField.Buttons))
                else -> this
            }
        }

        /**
         * Anomaly 2026-11-15 (*"Root of a tree to yes, and the search result list of the original window shows elements
         * that are not task. Fix those kinds of errors all around the app"*): **a filter of one type, on, keeps the
         * elements of that type alone** — the types of every such filter that is on, or null where none is ([isOn],
         * [Setting.section]). Until that day another type's elements passed it untouched. A "creation" row is no
         * element and stays. Not while the calendar's instant is a filter ([calendarAt], [calendarAddAt]): that one
         * answers for every type, and the calendar's "edit…" sets the task's and the period's box bounds beside it
         * ([calendarAtConfig]) while the alarms, timers and reminders there stay listed.
         */
        val onlyKinds: Set<Kind>?
            get() = if (calendarAt != null || calendarAddAt != null) null else Setting.entries
                .filter { setting -> setting.section != null && roleOf(setting) == SettingRole.Filter && isOn(setting) }
                .mapNotNullTo(HashSet()) { it.section }
                .takeIf { it.isNotEmpty() }

        /** How many filters are set to something other than "any" — the Search window's button shows it. */
        val activeCount: Int
            // A filter of the three Claude kinds is one filter, listed once per kind.
            get() = Setting.entries.count { isOn(it) && it !in CLAUDE_SETTING_ECHOES }

        /**
         * Whether [setting] is a filter set to something other than "any" — the one statement of it, read by the
         * count above and by the Configuration Search window's "filters that are on". A setting that is not a
         * filter (the search text, the types, a Sort by) is never on.
         */
        fun isOn(setting: Setting): Boolean =
            when (setting) {
                Setting.ListedFrom -> listedFrom != null
                Setting.MatchCase -> matchCase
                Setting.WholeWord -> wholeWord
                Setting.Regex -> regex
                Setting.CalendarAdd -> calendarAddAt != null
                Setting.CalendarAt -> calendarAt != null
                Setting.TaskInTree -> taskInTree != Tri.Any
                Setting.TaskTreeRoot -> taskTreeRoot != Tri.Any
                Setting.TaskSchedulable -> taskSchedulable != Tri.Any
                Setting.TaskCategory -> taskCategory != null
                Setting.CategoryHasRules -> categoryHasRules != Tri.Any
                Setting.PeriodOriginSetting -> periodOrigin != PeriodOrigin.Any
                Setting.PeriodDrawingSetting -> periodDrawing != null
                Setting.PeriodResilienceSetting -> periodResilience != ResilienceLevel.Any && periodResilienceTask != null
                Setting.AlarmStateSetting -> alarmState != AlarmState.Any
                Setting.AlarmDays -> alarmDays.isNotEmpty()
                Setting.TimerStateSetting -> timerState != TimerState.Any
                Setting.ChronoStateSetting -> chronoState != TimerState.Any
                Setting.QuotaRepeatsSetting -> quotaRepeats != Tri.Any
                Setting.ReminderRepeatsSetting -> reminderRepeats != ReminderRepeats.Any
                Setting.HistoryCategorySetting -> historyCategory != null
                Setting.HistoryWindowSetting -> madeIn != null
                Setting.HistoryUndoneSetting -> historyUndone != Tri.Any
                Setting.HistoryChangedKindsSetting -> historyChangedKinds.isNotEmpty()
                Setting.HistoryChangedElementSetting -> historyChangedElement != null
                Setting.RelationSectionSetting -> relationSection != null
                Setting.ShortcutReboundSetting -> shortcutRebound != Tri.Any
                Setting.WindowStatusSetting -> windowStatus != null
                Setting.WindowDuplicatesSetting -> windowDuplicates != WindowDuplicates.Shown
                Setting.TaskOnCalendar -> taskOnCalendar != Tri.Any
                Setting.TaskBoxesFrom -> taskBoxesFrom != null
                Setting.TaskBoxesUntil -> taskBoxesUntil != null
                Setting.PeriodOnCalendar -> periodOnCalendar != Tri.Any
                Setting.PeriodBoxesFrom -> periodBoxesFrom != null
                Setting.PeriodBoxesUntil -> periodBoxesUntil != null
                Setting.BlockElements -> blocksOf.isNotEmpty()
                Setting.ExcludedElements -> excludedElements.isNotEmpty()
                Setting.IncludedElements -> includedElements.isNotEmpty()
                Setting.NoParentOfKinds -> noParentOfKinds.isNotEmpty()
                Setting.ParentsOf -> parentsOf.isNotEmpty()
                Setting.InvolvementOf -> involvementOf.isNotEmpty()
                Setting.ParentDistance -> parentsOf.isNotEmpty() && parentDistance != 1
                Setting.NotificationSourceSetting -> notificationSources.isNotEmpty()
                Setting.PromptProject, Setting.AnswerProject, Setting.ReasoningProject -> claudeProject != null
                Setting.PromptFrom, Setting.AnswerFrom, Setting.ReasoningFrom -> claudeFrom != null
                Setting.PromptUntil, Setting.AnswerUntil, Setting.ReasoningUntil -> claudeUntil != null
                else -> false
            }
    }

    /**
     * What a list can be ordered by. [kind] null = a key every list has; else the key belongs to that kind's
     * rows only. Which keys a list offers is [sortKeysOf]'s answer.
     */
    enum class SortKey(val kind: Kind?, val label: String) {
        /** The same name, then names starting with the query, then the rest — the order before sorts existed. */
        Relevance(null, "relevance"),
        Name(null, "name"),
        /** The drop-down's order of the kinds — the whole list only. */
        Type(null, "type"),
        /**
         * The order the calendar's hover bubble names what is under the cursor in ([CalendarBubbleRank]) — the
         * whole list only, and what the calendar's "edit…" opens sorted by ([calendarAtConfig]).
         */
        CalendarBubble(null, "calendar bubble order"),
        /** The creation rows above every other row (user rule 2026-10-30) — a one-kind search's default ([defaultSortsOf]). */
        CreationFirst(null, "creation elements first"),
        TaskPriority(Kind.Task, "priority"),
        TaskPathLength(Kind.Task, "path length"),
        TaskPathCount(Kind.Task, "number of paths"),
        TaskInTree(Kind.Task, "in a task tree"),
        CategoryTasks(Kind.Category, "tasks"),
        CategoryRules(Kind.Category, "rules"),
        PeriodsPlaced(Kind.RestrictivePeriod, "periods placed"),
        AlarmTime(Kind.Alarm, "time"),
        TimerDuration(Kind.Timer, "duration"),
        ReminderTime(Kind.Reminder, "time"),
        ReminderCadence(Kind.Reminder, "cadence"),
        /** When the block starts on the calendar ([CalendarBlock.startMillis]). */
        BlockStart(Kind.CalendarBlock, "start"),
        HistoryDate(Kind.HistoryUnit, "date"),
        NotificationDate(Kind.Notification, "date"),
        RelationSection(Kind.TaskRelation, "section"),
        ShortcutChord(Kind.Shortcut, "chord"),
        /**
         * User rule 2026-11-20: **the evolutions given to the tasks of [Filters.involvementOf] first, then those relative to
         * one of them, then the rest** ([PresenceEvolutions.involvementGroups]) — what the "Presence ratio target
         * evolutions" field's window opens sorted by, a sort like any other.
         */
        EvolutionInvolvement(Kind.PresenceEvolution, "involvement of the tasks of \"Involvement of\""),
        /** The same for the minimum length target evolutions: those given to the tasks of [Filters.involvementOf] first. */
        MinimumInvolvement(Kind.MinimumEvolution, "involvement of the tasks of \"Involvement of\""),
        /** [WindowStatus]'s order: open, minimized, not open. */
        WindowState(Kind.Window, "state"),
        /**
         * When a Claude Code log entry was written ([ClaudeLogCatalog.timeOf], read off its id) — common to a prompt, an
         * answer and a reasoning chain, so one key about every row (user rule 2026-10-30). The rows of the other kinds have
         * no value for it. The builds before had one per kind (`PromptDate`, `AnswerDate`, `ReasoningDate`), read as this.
         */
        Date(null, "date"),
        /**
         * User rule 2026-11-16 (*"I want the same logic everywhere, what changes are the default values in search
         * configurations in some contexts"*): **the order the rows have in the tree they come from** — a given list's own
         * order ([results]' `given`), the configurations tree's for the search configurations ([ConfigurationRows]). What
         * the ▾'s own search sorts by ([CONFIGURATION_SEARCH]); a row from no tree has no value, and goes last.
         */
        TreeOrder(null, "tree order"),
    }

    /**
     * The keys offered for the whole list ([kind] null: relevance, name, type, the calendar bubble's order, the log
     * entries' date) or for one kind's rows (relevance, name, and that kind's own keys).
     */
    fun sortKeysOf(kind: Kind?): List<SortKey> =
        if (kind == null) {
            listOf(SortKey.Relevance, SortKey.Name, SortKey.Type, SortKey.CalendarBubble, SortKey.CreationFirst, SortKey.Date, SortKey.TreeOrder)
        } else {
            listOf(SortKey.Relevance, SortKey.Name) + SortKey.entries.filter { it.kind == kind }
        }

    /**
     * One sorting method of the list at the top of the Configuration Search window: a [key], the rows it is
     * about ([kind] null = every row; else that kind's rows only), and its direction. A method is identified
     * by its kind and key ([sameMethod]) — the list holds each at most once, whatever its direction.
     *
     * The list is ordered **dominant first**, and applied as stable sorts from the least dominant up, so a
     * method only decides between rows every method above it leaves tied. A method about one kind orders that
     * kind's rows **among the places they already hold** ([results]): it never moves a row of another kind,
     * which is what lets "alarm: time" sit anywhere in the list without pulling the alarms to one end.
     */
    data class SortMethod(val kind: Kind?, val key: SortKey, val descending: Boolean = false) {
        fun sameMethod(other: SortMethod): Boolean = kind == other.kind && key == other.key

        val label: String get() = (kind?.label?.let { "$it: " } ?: "") + key.label
    }

    /** Relevance over the whole list — the order the window had before it could be sorted. */
    val DEFAULT_SORTS: List<SortMethod> = listOf(SortMethod(null, SortKey.Relevance))

    /** The tree's order, then relevance — what a search of the search configurations sorts by ([defaultSortsOf]). */
    val TREE_ORDER_SORTS: List<SortMethod> = listOf(SortMethod(null, SortKey.TreeOrder), SortMethod(null, SortKey.Relevance))

    /** The creation rows first, then relevance — what a one-kind search sorts by unless the user says otherwise. */
    val CREATION_FIRST_SORTS: List<SortMethod> = listOf(SortMethod(null, SortKey.CreationFirst), SortMethod(null, SortKey.Relevance))

    /**
     * User rule 2026-10-30: **the default sorting of a search configuration**, read off the configuration alone — never
     * off its sorting — or null where it has none (the Sort by's "Default" button is then grey). The one default: ONE
     * kind checked that the user can make ([CREATABLE]) and nothing else — no text, no filter on — sorts its creation row
     * ([withCreatedKindRows]) to the top. What is added, the actions' bars and the rest of the window's own state are not
     * the search, and say nothing here. The logs' kinds and nothing else — the logs page's button's configuration,
     * [claudeLogsConfig], or some of its kinds — sort by their date, newest first ([CLAUDE_SORTS]), on any page.
     */
    fun defaultSortsOf(config: Config): List<SortMethod>? {
        // User rule 2026-11-16: the search configurations alone are in the tree's order, whatever the bar holds — the ▾'s.
        if (config.searchedKinds == setOf(Kind.SearchConfiguration)) return TREE_ORDER_SORTS
        if (config.query.isNotBlank() || config.filters.activeCount > 0) return null
        if (config.searchedKinds.isNotEmpty() && config.searchedKinds.all { it in CLAUDE_KINDS }) return CLAUDE_SORTS
        val kind = config.searchedKinds.singleOrNull() ?: return null
        return CREATION_FIRST_SORTS.takeIf { kind in CREATABLE }
    }

    /**
     * User rule 2026-10-30: [after] — the edit of a Search window's configuration [before] — with the default sorting
     * followed. A change of the sorting is the user's, and stops the following; while it follows ([Config.sortFollowsDefault],
     * from the window's opening or the last click on "Default"), a new search configuration takes its default
     * ([defaultSortsOf]) — one with none keeps the sorting it has. Every edit of a configuration goes through it
     * (`App.setSearchConfig`), but an undo, which puts a configuration back as it was.
     */
    fun withDefaultSortFollowed(before: Config, after: Config): Config {
        if (after.sorts != before.sorts && after.sortFollowsDefault == before.sortFollowsDefault) {
            return after.copy(sortFollowsDefault = false)
        }
        if (!after.sortFollowsDefault) return after
        val sameSearch = after.query == before.query && after.kinds == before.kinds && after.filters == before.filters
        if (sameSearch && after.sortFollowsDefault == before.sortFollowsDefault) return after
        return withDefaultSort(after)
    }

    /**
     * User rule 2026-11-04: **a Search window's "configurations" are its search configurations** — what a button of the
     * lateral menu (or any opener) compares to bring back the window that has them, else open a new one, and what its
     * "Update" compares. The search as the window holds it once opened ([withDefaultSort]: a window of tasks alone sorts
     * them newest first), and none of the window's own state: the added elements, the actions' bars and picks, where an
     * action places, the window it was opened from or the element it comes from.
     *
     * Anomaly 2026-11-04: compared whole and as written, the Task Scheduler page's Search button never found the window it
     * had opened — that window had taken its default sorting — and opened a new one at every press.
     */
    fun searchConfigurations(config: Config): Config =
        withDefaultSort(config).copy(
            sortFollowsDefault = false,
            added = emptyList(),
            addedFor = null,
            actionQuery = "",
            toggledActionRows = emptySet(),
            actionSearch = "",
            pickedBlocks = emptySet(),
            pickedTimers = emptySet(),
            compactTimers = false,
            pickedChronos = emptySet(),
            compactChronos = false,
            resiliencePeriod = null,
            calendarClickMillis = null,
            placement = Placement(),
            openedFrom = null,
            settingsChangedAt = emptyMap(),
            elementSource = null,
            inside = emptyMap(),
        )

    /**
     * User rule 2026-11-13 (*"Every typing must create a history unit"*): the window drawn inside another whose BAR was
     * typed in between [before] and [after] ([Config.inside]) — the one entry that changed, by its search text alone —
     * by its id; null for any other change. A run of keystrokes there is one History Unit, as in a Search window's own bar.
     */
    fun insideTypedIn(before: Map<String, String>, after: Map<String, String>): String? {
        val key = (before.keys + after.keys).singleOrNull { before[it] != after[it] } ?: return null
        val was = Config.decode(before[key])
        val now = Config.decode(after[key])
        return when {
            was != null && now != null -> key.takeIf { was.copy(query = now.query) == now }
            // It gained or lost its entry by its text alone: what it opens with has none.
            else -> key.takeIf { (was ?: now)?.query?.isNotEmpty() == true && (was ?: now)?.copy(query = "")?.let { it.inside.isEmpty() } == true }
        }
    }

    /** The part of its window's id the actions section's own ends with (`embeddedSearchId`). */
    const val ACTIONS_SECTION_PART: String = "actions"

    /**
     * Anomaly 2026-11-21 (*"I type something in the search bar of the action section, close the window, re-open it …
     * the top arrow doesn't give me the previous text"*): **the actions section's bar is a Search bar like any other**,
     * whose text is its window's [Config.actionSearch] — the window [relative] (`""`: the stored window itself) of the
     * bar whose id below the stored window's is this one; null for any other bar.
     */
    fun actionsBarOf(relative: String): String? =
        relative.takeIf { it.endsWith("/$ACTIONS_SECTION_PART") }?.removeSuffix("/$ACTIONS_SECTION_PART")

    /**
     * The window drawn inside another whose ACTIONS SECTION's bar was typed in between [before] and [after]
     * ([Config.inside], [actionsBarOf]) — the one entry that changed, by its [Config.actionSearch] alone; null for any other
     * change. An entry gained or lost held only that text (what it opens with has none).
     */
    fun insideActionsTypedIn(before: Map<String, String>, after: Map<String, String>): String? {
        val key = (before.keys + after.keys).singleOrNull { before[it] != after[it] } ?: return null
        val was = Config.decode(before[key])
        val now = Config.decode(after[key])
        val from = was ?: now?.copy(actionSearch = "") ?: return null
        val to = now ?: was?.copy(actionSearch = "") ?: return null
        return key.takeIf { from.actionSearch != to.actionSearch && from.copy(actionSearch = to.actionSearch) == to }
    }

    /** Whether two Search windows' configurations, as stored, have the same [searchConfigurations]. */
    fun sameSearchConfigurations(a: String?, b: String?): Boolean =
        searchConfigurations(Config.decode(a) ?: Config()) == searchConfigurations(Config.decode(b) ?: Config())

    /**
     * User rule 2026-11-05: **a Search window's whole state**, as a menu button keeps it — its search as the window holds
     * it once opened (its default sorting taken), its added elements told apart by their ids alone (not their order), its
     * actions' bars, picks and fields. Not the window it was opened from, nor when its settings were last changed.
     */
    fun searchWindowState(config: Config): Config =
        withDefaultSort(config).copy(
            sortFollowsDefault = false,
            added = config.added.distinct().sorted(),
            openedFrom = null,
            settingsChangedAt = emptyMap(),
        )

    /** Whether two Search windows' configurations, as stored, have the same [searchWindowState]. */
    fun sameSearchWindowState(a: String?, b: String?): Boolean =
        searchWindowState(Config.decode(a) ?: Config()) == searchWindowState(Config.decode(b) ?: Config())

    /** [config] with its default sorting ([defaultSortsOf]) while it follows it; as it is otherwise. A window opened is read through it. */
    fun withDefaultSort(config: Config): Config {
        if (!config.sortFollowsDefault) return config
        val sorts = defaultSortsOf(config) ?: return config
        return if (sorts == config.sorts) config else config.copy(sorts = sorts)
    }

    /** What the Sort by's "Default" button sets: the default sorting, followed from now on. Null where there is none. */
    fun defaultSortApplied(config: Config): Config? =
        defaultSortsOf(config)?.let { config.copy(sorts = it, sortFollowsDefault = true) }

    /** [sorts] with [method] at the bottom (checked in a drop-down), or without it (unchecked, or its ✕). */
    fun withSortMethod(sorts: List<SortMethod>, method: SortMethod, on: Boolean): List<SortMethod> {
        val rest = sorts.filterNot { it.sameMethod(method) }
        return if (on) rest + method else rest
    }

    /**
     * User rule 2026-11-22: **one part of a task row** ([Config.taskRowLayout]) — what the row can show, each drawn by the
     * tree cell's own code ([org.example.project.scheduler.ui.TaskRow]), only the order and the choice being the window's.
     */
    enum class TaskRowPart(val label: String) {
        Kind("type"),
        Arrow("expansion arrow"),
        RelativePriority("priority relative to the parent"),
        Title("title, uncut"),
        Path("path"),
        Priority("priority percentage"),
        MinimumTime("minimum time"),
        Categories("categories"),
        NotInTree("not-in-a-tree logo"),
        ;

        companion object {
            /** A stored layout: the parts this build knows, each once, in their order. */
            fun decode(names: List<String>): List<TaskRowPart> =
                names.mapNotNull { name -> entries.firstOrNull { it.name == name } }.distinct()

            /** The user's example (2026-11-22): the arrow, then the share of the parent, then the whole title. */
            val ARROW_RELATIVE_TITLE: List<TaskRowPart> = listOf(Arrow, RelativePriority, Title)
        }
    }

    /** [layout] with [part] added at its end ([on]) or taken out of it. */
    fun withTaskRowPart(layout: List<TaskRowPart>, part: TaskRowPart, on: Boolean): List<TaskRowPart> =
        if (on) (if (part in layout) layout else layout + part) else layout - part

    /** [layout] with the part at [from] moved to [to] — the others keep their order around it. */
    fun movedTaskRowPart(layout: List<TaskRowPart>, from: Int, to: Int): List<TaskRowPart> {
        if (from !in layout.indices) return layout
        val list = layout.toMutableList()
        val part = list.removeAt(from)
        list.add(to.coerceIn(0, list.size), part)
        return list
    }

    /** [sorts] with the method at [from] dragged to [to] — the others keep their order around it. */
    fun movedSortMethod(sorts: List<SortMethod>, from: Int, to: Int): List<SortMethod> {
        if (from !in sorts.indices) return sorts
        val list = sorts.toMutableList()
        val method = list.removeAt(from)
        list.add(to.coerceIn(0, list.size), method)
        return list
    }

    /**
     * Every configuration of the Search window, which the Configuration Search window lists — one section per
     * kind, after the [section]-less ones that are about the search as a whole (the two the Search window
     * itself shows, and the sorting methods about every row). The window finds them by [label]
     * ([configurations]). A [sorts] setting is a drop-down with a check box per sorting method of its section's
     * rows ([sortKeysOf]); checking one adds it at the bottom of the [Config.sorts] list.
     */
    enum class Setting(val section: Kind?, val label: String, val sorts: Boolean = false) {
        SearchText(null, "Search text"),
        /** User rule 2026-11-04: filters on what the bar holds ([Filters.matchCase], [Filters.wholeWord]). */
        MatchCase(null, "Match case"),
        WholeWord(null, "Match whole word"),
        /** User rule 2026-11-06: the bars hold regular expressions ([Filters.regex]). */
        Regex(null, "Use regular expression"),
        /**
         * User rule 2026-11-16, "Search › Where to search": **what of a row the bars are matched against** — its id, its
         * name (the only one, by default), its description, its buttons ([Filters.searchIn], [SearchField]) — and its
         * children's, at a distance ([Filters.searchChildren], [Filters.childSearchIn], [Filters.childDistance]).
         */
        SearchInId(null, "Search in ids"),
        SearchInName(null, "Search in names"),
        SearchInDescription(null, "Search in descriptions"),
        SearchInButtons(null, "Search in buttons"),
        SearchInChildren(null, "Search in children"),
        ChildSearchInId(null, "Search in children's ids"),
        ChildDistance(null, "Children's parent distance"),
        ChildSearchInName(null, "Search in children's names"),
        ChildSearchInDescription(null, "Search in children's descriptions"),
        ChildSearchInButtons(null, "Search in children's buttons"),
        /** User rule 2026-11-06: the search bars added to the window's own, and their formula ([Config.extraQueries]). */
        SearchBars(null, "Search bars"),
        Types(null, "Types"),
        /** User rule 2026-11-04: the type selector offers the page's types alone ([Config.pageTypesOnly]). */
        PageTypes(null, "Only this page's types"),
        /**
         * The Search window's own Reset ([resetConfig]): back to the configuration it opened with, else the whole
         * configuration cancelled. Listed here because every configuration of the Search window is — and it is not the
         * Configuration Search window's own Reset, which clears that window's search.
         */
        ResetSearch(null, "Reset"),
        /** The search bar's completion list ([Config.completion]). */
        CompletionList(null, "Completion list"),
        SortResults(null, "Sort by", sorts = true),
        /** The calendar filter ([Filters.calendarAddOn]): about every row, so in the General section. */
        CalendarAdd(null, "Can go on the calendar at"),
        /** The "is on the calendar at" filter ([Filters.calendarAtOn]): the calendar's "edit…". */
        CalendarAt(null, "Is on the calendar at"),
        /** The elements kept out of the list ([Filters.excludedElements]) and those always in it ([Filters.includedElements]). */
        ExcludedElements(null, "Excluded elements"),
        /** User rule 2026-11-21: where the rows a caller gives come from ([Filters.listedFrom]). */
        ListedFrom(null, "Listed from"),
        IncludedElements(null, "Included elements"),
        /** User rule 2026-11-12: only the elements with no parent of the checked kinds ([Filters.noParentOfKinds]). */
        NoParentOfKinds(null, "Has no parent of the types"),
        /** User rule 2026-11-12: only the parents of the given elements ([Filters.parentsOf]): the "List of parents". */
        ParentsOf(null, "Parents of"),
        /** User rule 2026-11-13: how far above those elements a parent may stand ([Filters.parentDistance]): an integer. */
        ParentDistance(null, "Parent distance"),
        /** User rule 2026-11-20: the tasks the evolutions' "involvement" order is measured against ([Filters.involvementOf]). */
        InvolvementOf(null, "Involvement of"),
        /** User rule 2026-11-12: the results grouped by the added elements they involve ([Config.actionsGrouped]). */
        GroupByAddedElements(null, "Group by added elements"),
        /** User rule 2026-11-12: the top rows of the results open by default, not their children ([Config.actionsExpanded]). */
        ExpandResults(null, "Expand the results"),
        /** User rule 2026-11-12: the search bar sorts and hides nothing ([Config.actionSearchSortsOnly]). */
        SearchSortsOnly(null, "Search sorts, hides nothing"),
        /**
         * The rows are dragged instead of pressed: a press activates nothing, and the order the user drags them into is
         * kept ([Config.menuCustomizing]). User rule 2026-11-14: named for what it does, not for the left-side menu —
         * the one list that honours it so far, whose tree shows it in "Relevant here" ([configurationTree]'s `relevant`).
         */
        MenuCustomizing(null, "Arrange by dragging"),
        /** User rule 2026-11-12: a button on every element to drop it ([Config.dropButtons]). */
        DropButtons(null, "A drop button per element"),
        /** User rule 2026-11-19: the menu button that opened the window keeps its open rows ([Config.buttonFollowsExpansion]). */
        ButtonFollowsExpansion(null, "Button saves open rows"),
        TaskSort(Kind.Task, "Sort by", sorts = true),
        CategorySort(Kind.Category, "Sort by", sorts = true),
        PeriodSort(Kind.RestrictivePeriod, "Sort by", sorts = true),
        AlarmSort(Kind.Alarm, "Sort by", sorts = true),
        TimerSort(Kind.Timer, "Sort by", sorts = true),
        ChronoSort(Kind.Chrono, "Sort by", sorts = true),
        QuotaSort(Kind.Quota, "Sort by", sorts = true),
        ReminderSort(Kind.Reminder, "Sort by", sorts = true),
        BlockSort(Kind.CalendarBlock, "Sort by", sorts = true),
        HistorySort(Kind.HistoryUnit, "Sort by", sorts = true),
        NotificationSort(Kind.Notification, "Sort by", sorts = true),
        RelationSort(Kind.TaskRelation, "Sort by", sorts = true),
        ShortcutSort(Kind.Shortcut, "Sort by", sorts = true),
        WorkplaceSetupSort(Kind.WorkplaceSetup, "Sort by", sorts = true),
        MdSort(Kind.Md, "Sort by", sorts = true),
        PresenceEvolutionSort(Kind.PresenceEvolution, "Sort by", sorts = true),
        MinimumEvolutionSort(Kind.MinimumEvolution, "Sort by", sorts = true),
        AppSettingSort(Kind.AppSetting, "Sort by", sorts = true),
        CalendarSort(Kind.Calendar, "Sort by", sorts = true),
        WindowSort(Kind.Window, "Sort by", sorts = true),
        CreationSort(Kind.Creation, "Sort by", sorts = true),
        PromptSort(Kind.ClaudePrompt, "Sort by", sorts = true),
        AnswerSort(Kind.ClaudeAnswer, "Sort by", sorts = true),
        ReasoningSort(Kind.ClaudeReasoning, "Sort by", sorts = true),
        SearchConfigurationSort(Kind.SearchConfiguration, "Sort by", sorts = true),
        /** User rule 2026-11-12: a drop-down's options, sorted as any kind's rows are. */
        OptionSort(Kind.Option, "Sort by", sorts = true),
        /** User rule 2026-11-13: a list of integers, sorted as any kind's rows are. */
        IntegerSort(Kind.Integer, "Sort by", sorts = true),
        /** User rule 2026-11-12: the actions section's rows, sorted as any kind's rows are. */
        ActionSort(Kind.Action, "Sort by", sorts = true),
        /** User rule 2026-11-22: what a task row shows, in which order ([Config.taskRowLayout]). */
        TaskRowLayout(Kind.Task, "Row layout"),
        TaskInTree(Kind.Task, "In a task tree"),
        /** User rule 2026-11-14: only the roots of the trees ([Filters.taskTreeRoot]). */
        TaskTreeRoot(Kind.Task, "Root of a tree"),
        TaskSchedulable(Kind.Task, "Schedulable"),
        TaskCategory(Kind.Task, "Category"),
        TaskOnCalendar(Kind.Task, "On the calendar"),
        TaskBoxesFrom(Kind.Task, "Every box from"),
        TaskBoxesUntil(Kind.Task, "Every box until"),
        CategoryHasRules(Kind.Category, "Has rules"),
        PeriodOriginSetting(Kind.RestrictivePeriod, "Default periods"),
        /** Only the periods drawn one way ([Filters.periodDrawing]). */
        PeriodDrawingSetting(Kind.RestrictivePeriod, "Drawing"),
        /** Only the periods a task has so much resilience to ([Filters.periodResilience]). */
        PeriodResilienceSetting(Kind.RestrictivePeriod, "Resilience of a task"),
        PeriodOnCalendar(Kind.RestrictivePeriod, "On the calendar"),
        PeriodBoxesFrom(Kind.RestrictivePeriod, "Every box from"),
        PeriodBoxesUntil(Kind.RestrictivePeriod, "Every box until"),
        AlarmStateSetting(Kind.Alarm, "State"),
        AlarmDays(Kind.Alarm, "Rings on"),
        TimerStateSetting(Kind.Timer, "State"),
        ChronoStateSetting(Kind.Chrono, "State"),
        QuotaRepeatsSetting(Kind.Quota, "Repeats"),
        ReminderRepeatsSetting(Kind.Reminder, "Repeats"),
        /** The elements whose blocks are listed ([Filters.blocksOf]). */
        BlockElements(Kind.CalendarBlock, "Blocks of"),
        HistoryCategorySetting(Kind.HistoryUnit, "Category"),
        HistoryWindowSetting(Kind.HistoryUnit, "Made in"),
        HistoryUndoneSetting(Kind.HistoryUnit, "Undone"),
        HistoryChangedKindsSetting(Kind.HistoryUnit, "Changed element types"),
        HistoryChangedElementSetting(Kind.HistoryUnit, "Changed element"),
        /** What the notification came from ([Filters.notificationSources]). */
        NotificationSourceSetting(Kind.Notification, "From"),
        RelationSectionSetting(Kind.TaskRelation, "Section"),
        ShortcutReboundSetting(Kind.Shortcut, "Rebound"),
        WindowStatusSetting(Kind.Window, "State"),
        WindowDuplicatesSetting(Kind.Window, "Duplicates"),
        /** The Claude Code logs' filters ([Filters.claudeProject], [Filters.claudeFrom], [Filters.claudeUntil]). */
        PromptProject(Kind.ClaudePrompt, "Project"),
        PromptFrom(Kind.ClaudePrompt, "Written from"),
        PromptUntil(Kind.ClaudePrompt, "Written until"),
        AnswerProject(Kind.ClaudeAnswer, "Project"),
        AnswerFrom(Kind.ClaudeAnswer, "Written from"),
        AnswerUntil(Kind.ClaudeAnswer, "Written until"),
        ReasoningProject(Kind.ClaudeReasoning, "Project"),
        ReasoningFrom(Kind.ClaudeReasoning, "Written from"),
        ReasoningUntil(Kind.ClaudeReasoning, "Written until"),
    }

    /**
     * User rule 2026-11-13: **the four categories of search configuration, by what one does to the result list** of the
     * window it is a configuration of: it keeps rows out ([Filter]), orders them ([Sort]), changes how every row is
     * drawn ([Display]), or changes the children the rows have in that list ([Children]).
     */
    enum class SettingRole(val label: String) {
        Filter("filter"),
        Sort("sort"),
        Display("display"),
        Children("children"),

        /**
         * User rule 2026-11-13, the fifth: a search configuration that **has search configuration children and changes
         * nothing itself** — a node of the configurations tree ("Recently changed", "Sorting", a page, a type).
         */
        Group("group"),
    }

    /**
     * The category of [setting] ([SettingRole]) — the one statement of it. A "Sort by" sorts, and so does the bar that
     * only sorts; what groups the rows or opens them is about their children; a button on every row, or what the bar
     * offers, is display; every other one narrows the list. Null for the one that is a command and no state: Reset.
     */
    fun roleOf(setting: Setting): SettingRole? =
        when {
            setting.sorts || setting == Setting.SearchSortsOnly -> SettingRole.Sort
            setting == Setting.GroupByAddedElements || setting == Setting.ExpandResults -> SettingRole.Children
            setting == Setting.DropButtons || setting == Setting.CompletionList || setting == Setting.MenuCustomizing ||
                setting == Setting.ButtonFollowsExpansion || setting == Setting.TaskRowLayout -> SettingRole.Display
            setting == Setting.ResetSearch -> null
            else -> SettingRole.Filter
        }

    /**
     * The search configurations currently enabled by [config]. This deliberately includes filters, sorting and
     * presentation/children switches, but not the search text or the selected types: those describe the input and
     * scope of the window rather than an enabled result-list behavior.
     */
    /**
     * The info bubble of a filter of one type or a few (anomaly 2026-11-15: *"add info bubble if necessary to clear
     * ambiguity"*): set, it keeps those types' elements alone ([Filters.onlyKinds]). Null for a configuration of the
     * whole search.
     */
    fun filterHint(settings: List<Setting>): String? {
        if (settings.any { roleOf(it) != SettingRole.Filter }) return null
        val kinds = settings.mapNotNull { it.section }.distinct()
        if (kinds.isEmpty()) return null
        val label = settings.first().label
        val what = when (kinds.size) {
            1 -> "the ${kinds[0].plural}"
            else -> "the " + kinds.dropLast(1).joinToString(", ") { it.plural } + " and the " + kinds.last().plural
        }
        return "Set, \"$label\" lists only $what it holds for: no element of another type."
    }

    fun activeSettings(config: Config?): List<Setting> {
        config ?: return emptyList()
        return (filtersOn(config) + ownSettingsOn(config)).distinct()
    }

    /** The filters [config] sets, "Where to search" included: what [activeSettings] and [contextSettings] both read. */
    private fun filtersOn(config: Config): List<Setting> =
        Setting.entries.filter { config.filters.isOn(it) && it !in CLAUDE_SETTING_ECHOES } + whereSettingsOn(config.filters)

    /**
     * **The window's own settings [config] has on** — no filter: what it lists and how, its display. The ONE list "Currently
     * on" ([activeSettings]) and "Default for this" ([contextSettings]) read (anomaly 2026-11-19: *"'Menu keeps open rows' is
     * in 'Currently on' but not in 'Default for this'"* — the second kept a list of its own, three of these long).
     */
    private fun ownSettingsOn(config: Config): List<Setting> =
        listOfNotNull(
            // User rule 2026-11-16 (*"If nothing is on … what is shown in down arrow is random, right?"*): what orders the
            // list and what it lists are on wherever they are not a plain Search window's ([Config]'s defaults).
            Setting.Types.takeIf { config.kinds != Config().kinds },
            // Anomaly 2026-11-20 (*"in 'Currently on', I don't understand 'sort by [relevance]'"*): "Sort results" draws
            // the methods about every row alone, so it is on for those; a kind's own methods are its own "Sort by", on
            // when one is in the sorting (the evolutions' "involvement" was in none of "Currently on").
            Setting.SortResults.takeIf { config.sorts.filter { it.kind == null }.let { whole -> whole.isNotEmpty() && whole != DEFAULT_SORTS } },
        ) + Setting.entries.filter { s -> s.sorts && s.section != null && config.sorts.any { it.kind == s.section } } + listOfNotNull(
            Setting.CompletionList.takeIf { config.completion != null },
            Setting.ExpandResults.takeIf { config.actionsExpanded },
            Setting.SearchSortsOnly.takeIf { !config.actionSearchSortsOnly },
            Setting.MenuCustomizing.takeIf { config.menuCustomizing },
            Setting.DropButtons.takeIf { config.dropButtons },
            Setting.ButtonFollowsExpansion.takeIf { config.buttonFollowsExpansion },
            Setting.TaskRowLayout.takeIf { config.taskRowLayout.isNotEmpty() },
        )

    /**
     * User rule 2026-11-13: **the search configurations a context is about** — what the window was opened with
     * ([opened]) says it: the filters it set, and what goes with them (the "List of parents" asks the parents of some
     * elements, so how far above them comes first; its drop buttons). They come first in the tree's "Relevant here"
     * ([configurationTree]), before what is on now ([activeSettings]). None for a window opened on nothing.
     */
    fun contextSettings(opened: Config?, relevant: List<Setting> = emptyList()): List<Setting> {
        opened ?: return relevant
        val on = filtersOn(opened)
        val first = listOfNotNull(
            Setting.ParentDistance.takeIf { opened.filters.parentsOf.isNotEmpty() },
            Setting.DropButtons.takeIf { opened.dropButtons },
        )
        // Its own settings, as "Currently on" reads them ([ownSettingsOn]): what it lists and in what order, where not a
        // plain Search window's (user rule 2026-11-16), last.
        val shape = setOf(Setting.Types, Setting.SortResults)
        val own = ownSettingsOn(opened)
        return (relevant + first.take(1) + on + first.drop(1) + own.filter { it !in shape } + own.filter { it in shape }).distinct()
    }

    /**
     * User rule 2026-11-16: **what of "Where to search" is set otherwise than by default** — a part checked that is not
     * by default ([DEFAULT_SEARCH_IN]: the name), and the children's parent distance once it is not 1. A part unchecked
     * is off, so not "on": the name, by default checked, is no news.
     */
    fun whereSettingsOn(filters: Filters): List<Setting> =
        WHERE_SETTINGS.filter { setting ->
            when (setting) {
                Setting.ChildDistance -> filters.searchChildren && filters.childDistance != 1
                else -> filters.searches(setting) == true && Filters().searches(setting) != true
            }
        }

    /** "Where to search"'s configurations, in the tree's order ([TreeSubNode.WhereToSearch]). */
    val WHERE_SETTINGS: List<Setting> = listOf(
        Setting.SearchInId, Setting.SearchInName, Setting.SearchInDescription, Setting.SearchInButtons, Setting.SearchInChildren,
        Setting.ChildSearchInId, Setting.ChildDistance, Setting.ChildSearchInName, Setting.ChildSearchInDescription, Setting.ChildSearchInButtons,
    )

    /**
     * User rule 2026-11-14, "Arrange by dragging": [results] in the order the user dragged them into ([Config.arranged])
     * — those it names first, in its order; the others after, in the list's own.
     */
    /**
     * User rule 2026-11-20: **what the "Presence ratio target evolutions" field of the tasks [taskIds] opens with** — every
     * evolution, sorted by their involvement of those tasks (given to one, then relative to one, then the rest): the sort
     * and the tasks it reads ([Filters.involvementOf]) are configurations of the window, listed in its ▾'s "Currently on"
     * (anomaly 2026-11-20: an order no configuration said was "a fundamental issue").
     */
    fun evolutionsFieldConfig(taskIds: Collection<TaskId>, target: EvolutionTarget = EvolutionTarget.Presence): Config {
        val kind = evolutionKindOf(target)
        return Config(
            kinds = setOf(kind),
            filters = Filters(involvementOf = taskIds.mapTo(LinkedHashSet(), ::taskKey)),
            sorts = listOf(SortMethod(kind, if (target == EvolutionTarget.Minimum) SortKey.MinimumInvolvement else SortKey.EvolutionInvolvement)),
            sortFollowsDefault = false,
        )
    }

    /** The search kind of the evolutions of [target], and back. */
    fun evolutionKindOf(target: EvolutionTarget): Kind = if (target == EvolutionTarget.Minimum) Kind.MinimumEvolution else Kind.PresenceEvolution

    fun evolutionTargetOf(kind: Kind): EvolutionTarget = if (kind == Kind.MinimumEvolution) EvolutionTarget.Minimum else EvolutionTarget.Presence

    fun arrangedResults(results: List<Result>, arranged: List<String>): List<Result> {
        if (arranged.isEmpty()) return results
        val rank = arranged.withIndex().associate { (i, key) -> key to i }
        val (named, rest) = results.partition { keyOf(it) in rank }
        return named.sortedBy { rank.getValue(keyOf(it)) } + rest
    }

    /**
     * The order [arranged] becomes when the rows [moved] (keys, in the list's order) are dropped before [beforeKey] (null:
     * at the end) of [shown], the list's keys as drawn: the shown rows in their new order, then what [arranged] named
     * that is not shown, so a row filtered out keeps its rank among the others when it comes back.
     */
    fun arrangedAfterDrop(shown: List<String>, arranged: List<String>, moved: List<String>, beforeKey: String?): List<String> {
        val movedSet = moved.toSet()
        val rest = shown.filterNot { it in movedSet }
        val at = beforeKey?.let { rest.indexOf(it) }?.takeIf { it >= 0 } ?: rest.size
        val order = rest.subList(0, at) + shown.filter { it in movedSet } + rest.subList(at, rest.size)
        val shownSet = shown.toSet()
        return order + arranged.filterNot { it in shownSet }
    }

    /** The Claude kinds' filters but the prompt's: the same three filters, listed again under another kind. */
    val CLAUDE_SETTING_ECHOES: Set<Setting> by lazy {
        setOf(
            Setting.AnswerProject, Setting.AnswerFrom, Setting.AnswerUntil,
            Setting.ReasoningProject, Setting.ReasoningFrom, Setting.ReasoningUntil,
        )
    }

    /**
     * What the Configuration Search window lists, section by section: the general settings first, then one
     * section per kind in the drop-down's order, each holding the settings whose label contains [query]. A kind
     * outside [kinds] has no section, and neither has one outside [onlyKinds] when that is given (the "only
     * the kinds of the Search window's results" button) — except, when [filtersOn] is given (the "filters that
     * are on" button), for the filters of that kind that are on: those stay listed, alone in their section, so
     * the filter that emptied its own type out of the results can still be turned back off. The general section
     * is about the whole search, so it is never cut by kind. An empty section is dropped.
     */
    /** A node of the configurations tree ([configurationTree]). */
    sealed interface ConfigurationNode {
        val label: String
    }

    /**
     * An end node: one configuration of the window, drawn as its control. [qualified] where it stands among the
     * configurations of other types ("Recently changed", "Sorting"): its label then begins with its type's name, or
     * twenty-eight rows would all read "Sort by".
     */
    data class ConfigurationLeaf(
        val setting: Setting,
        val qualified: Boolean = false,
        /**
         * User rule 2026-11-16 (*"This search configuration will show in its row the path search > where to search"*):
         * where a configuration stands away from its own category — "Recent", "Currently on", "Default for this", the
         * root's flat list — its row says the way to that category ([homePathOf]); none where it stands in it.
         */
        val pathed: Boolean = false,
    ) : ConfigurationNode {
        /** The categories above the configuration's own place, root first: shown above its name where [pathed]. */
        val path: List<String> get() = if (pathed) homePathOf(setting) else emptyList()

        override val label: String
            get() = when {
                pathed -> (path + treeLabelOf(setting)).joinToString(TREE_PATH_SEPARATOR)
                qualified -> setting.section?.let { treeNameOf(it) + ": " + setting.label } ?: treeLabelOf(setting)
                else -> treeLabelOf(setting)
            }

        /** Its name alone, the [path] drawn apart above it. */
        val name: String get() = if (pathed) treeLabelOf(setting) else label
    }

    /** What stands between two categories of a path ([ConfigurationLeaf.path]). */
    const val TREE_PATH_SEPARATOR: String = " › "

    /**
     * User rule 2026-11-14 (*"The width is too big"*): what a type is called before a configuration's name in the tree
     * — its plural, or a shorter word where that one is long ("restrictive periods: Resilience of a task" set the width
     * of the whole label column).
     */
    fun treeNameOf(kind: Kind): String =
        when (kind) {
            Kind.Category -> "categories"
            Kind.RestrictivePeriod -> "periods"
            Kind.CalendarBlock -> "blocks"
            Kind.HistoryUnit -> "history"
            Kind.Shortcut -> "shortcuts"
            Kind.WorkplaceSetup -> "setups"
            Kind.PresenceEvolution -> "evolutions"
            Kind.MinimumEvolution -> "minimum evolutions"
            Kind.ClaudeReasoning -> "reasoning"
            Kind.SearchConfiguration -> "configurations"
            else -> kind.plural
        }

    /**
     * A row of the ▾'s list that is no search configuration — its own search checks other types too (user rule
     * 2026-11-16: the ▾ is a Search window like any): drawn as a row of its kind, with no children in the tree.
     */
    data class ConfigurationOther(val result: Result) : ConfigurationNode {
        override val label: String get() = result.name
    }

    /** The id of the [Kind.SearchConfiguration] row [node] is: a configuration's name, a category's node id. */
    fun configurationRowId(node: ConfigurationNode): String =
        when (node) {
            is ConfigurationLeaf -> node.setting.name
            is ConfigurationCategory -> CONFIGURATION_NODE_PREFIX + node.id
            is ConfigurationOther -> keyOf(node.result)
        }

    /**
     * User rule 2026-11-16: **a configurations tree as a Search window lists it** — every node a [Kind.SearchConfiguration]
     * row named as the tree names it, and what a category holds its rows' children ([ConfigurationRows]).
     */
    class ConfigurationTreeRows(tree: List<ConfigurationNode>) {
        private val byId = HashMap<String, ConfigurationNode>().also { index ->
            fun walk(nodes: List<ConfigurationNode>) {
                for (node in nodes) {
                    val id = configurationRowId(node)
                    if (id in index) continue
                    index[id] = node
                    if (node is ConfigurationCategory) walk(node.children)
                }
            }
            walk(tree)
        }

        fun rowsOf(nodes: List<ConfigurationNode>): List<Result> =
            nodes.filter { it !is ConfigurationOther }.map { ItemResult(Kind.SearchConfiguration, configurationRowId(it), it.label, "") }

        /** The rows of [level] with what each holds: what [results] reads. */
        fun of(level: List<ConfigurationNode>): ConfigurationRows =
            ConfigurationRows(rowsOf(level)) { id -> (byId[id] as? ConfigurationCategory)?.children?.let(::rowsOf).orEmpty() }
    }

    /**
     * User rule 2026-11-16 (*"I want the same logic everywhere, what changes are the default values in search
     * configurations in some contexts"*): **what a pop-up of the ▾ tree lists** — the one [results], under the pop-up's own
     * [search] (its types, bar, where to search, matching, filters, sorting: [CONFIGURATION_SEARCH] by default), over the
     * configurations [level] lays out: the root's ([root]: the search configurations among every type it checks), or a
     * category's children (a given list). Each search configuration row back as its node; another type's row as
     * [ConfigurationOther].
     */
    fun shownConfigurationNodes(
        state: SchedulerState,
        level: List<ConfigurationNode>,
        root: Boolean,
        rows: ConfigurationTreeRows,
        search: Config,
        windows: List<WindowEntry> = emptyList(),
        claudeLogs: ClaudeLogCatalog = ClaudeLogCatalog.EMPTY,
    ): List<ConfigurationNode> {
        val tree = rows.of(level)
        val found = results(
            state, search.searchedKinds, search.query,
            filters = search.filters, sorts = search.sorts, windows = windows, claudeLogs = claudeLogs,
            match = search.filters.queryOptions, extraQueries = search.extraQueries, queryFormula = search.queryFormula,
            given = if (root) null else tree.rows,
            configurationRows = tree,
        )
        val byRow = level.associateBy(::configurationRowId)
        return found.map { row -> (row as? ItemResult)?.takeIf { it.kind == Kind.SearchConfiguration }?.let { byRow[it.id] } ?: ConfigurationOther(row) }
    }

    /** A category of configurations, which reveals [children] on hover. [id] is unique in the tree. */
    data class ConfigurationCategory(val id: String, override val label: String, val children: List<ConfigurationNode>) : ConfigurationNode

    /**
     * User rule 2026-11-14: **the groups of the configurations about every row** — what "General" held in one list of
     * twenty-five, by what the user is after: what the bar holds, which types, the order, who stands above or under
     * whom, the other filters, how a row is drawn. [treeGroupOf] is the one statement of where a configuration stands.
     */
    enum class TreeGroup(val id: String, val label: String) {
        /** User rule 2026-11-16: "Search" — what the bar holds and how it is matched ([MATCHING_NODE]). */
        Bar("bar", "Search"),
        Types("types", "Types"),
        Sorting("sort", "Sorting"),
        Parents("parents", "Parents and children"),
        Filters("filters", "Filters on every type"),
        Display("display", "Display"),
    }

    /**
     * The group of [setting] ([TreeGroup]); null for one that stands elsewhere — a type's own filter, under its
     * type, and Reset, a command: a row of the root alone. One named here is where the user looks for it; any other, and
     * so any added later, goes by what it does ([roleOf]).
     */
    fun treeGroupOf(setting: Setting): TreeGroup? =
        when (setting) {
            Setting.SearchText, Setting.MatchCase, Setting.WholeWord, Setting.Regex, Setting.SearchBars,
            Setting.CompletionList, Setting.SearchSortsOnly,
            -> TreeGroup.Bar
            in WHERE_SETTINGS -> TreeGroup.Bar
            Setting.Types, Setting.PageTypes -> TreeGroup.Types
            Setting.NoParentOfKinds, Setting.ParentsOf, Setting.ParentDistance -> TreeGroup.Parents
            else -> {
                val role = roleOf(setting)
                when {
                    // Every type's order stands with the order of every row.
                    role == SettingRole.Sort -> TreeGroup.Sorting
                    role == null || setting.section != null -> null
                    role == SettingRole.Children -> TreeGroup.Parents
                    role == SettingRole.Filter -> TreeGroup.Filters
                    else -> TreeGroup.Display
                }
            }
        }

    /**
     * User rule 2026-11-04: **the configurations tree** the arrow beside the Search window's bar opens; rethought
     * 2026-11-14 (*"it must be easy with a very intuitive navigation"*) and 2026-11-16 (*"The search of a search
     * configuration from the down arrow must be a very intuitive and easy tree navigation"*, with a sketch). From the root:
     *
     * 1. **"Recent"** — "Here": what was changed in this window, the last first; then every configuration, the last
     *    changed first ([Config.settingsChangedAt]). Each says its way to its own category ([ConfigurationLeaf.pathed]).
     * 2. **"Currently on"** — what is on now ([activeSettings]); none while nothing is.
     * 3. **"Relevant here"** — what the window is about whatever it holds ([relevant], on or off); none where nothing is.
     * 4. **"Default for this"** — what is on by default in this window ([contextSettings] of what it was opened on), and
     *    in each group the part of it in that group (user rule 2026-11-16: *"if somewhere in the app the search bar by
     *    default matches case, then in down arrow > search > matching > default for this, the user can find match case"*).
     * 5. The groups of what is about every row ([TreeGroup]) — "Search" first, its "Matching" under it; "Sorting" holds
     *    every type's order too.
     * 6. **The types of the window's own page** ([Config.page]), each holding its filters and its order — one hover
     *    from the root, no page node above them. A type with an order and no filter has no node: its order is in "Sorting".
     * 7. **"Other feature pages"** — each other page, its types under it.
     * 8. **The flat list**, after all the other rows (user rule 2026-11-14: *"It allowed me to directly get the search
     *    configuration I want by typing its name in the first pop-up"*): every category below the root, named by its
     *    path (user rule 2026-11-16: *"including those that just redirect to children search configurations"*), then every
     *    configuration with its path, what is kept before how it is laid out.
     *
     * [relevant]: user rule 2026-11-14 (*"this search configuration is something relevant for the left-side menu, so it
     * should be accessible intuitively in the down arrow of the left-side menu"*) — the configurations the window is
     * about whatever it holds, said by the code that draws it and never stored (the left-side menu: "Arrange by dragging").
     *
     * Every configuration stands in it ([Setting.entries]), and a category is never empty. Nothing but a search field
     * narrows it — never the types the window searches (anomaly 2026-11-11).
     */
    fun configurationTree(
        config: Config,
        opened: Config? = null,
        relevant: List<Setting> = emptyList(),
        /** What the other ▾ trees changed and looked into ([Elsewhere]): "Recent › Elsewhere". */
        elsewhere: Elsewhere = Elsewhere(),
    ): List<ConfigurationNode> {
        fun qualified(settings: List<Setting>) = settings.map { ConfigurationLeaf(it, qualified = true) }
        fun pathed(settings: List<Setting>) = settings.map { ConfigurationLeaf(it, pathed = true) }
        fun typesOf(page: FeaturePage) =
            Kind.entries.filter { it in page.kinds }.mapNotNull { kind ->
                // What is kept first, then how it is laid out.
                Setting.entries.filter { it.section == kind }.sortedBy { it.sorts }
                    .takeIf { settings -> settings.any { !it.sorts } }
                    ?.let { settings -> ConfigurationCategory("kind/" + kind.name, kind.label, settings.map { ConfigurationLeaf(it) }) }
            }
        val own = FEATURE_PAGES.firstOrNull { it.id == config.page }
        val ownKinds = own?.kinds.orEmpty().toSet()
        // What this window holds by default: what it was opened on, else what its page's Search button opens.
        val defaultList = contextSettings(opened ?: pageDefaultConfig(config.page))
        val defaults = defaultList.toSet()
        // A group's "Default for this": what of [defaults] stands in it, under [id]; none where nothing does.
        fun defaultsOf(id: String, settings: List<Setting>) =
            settings.filter { it in defaults }.takeIf { it.isNotEmpty() }?.let { ConfigurationCategory("$id/default", DEFAULT_NODE_LABEL, qualified(it)) }
        val changedHere = Setting.entries.filter { it in config.settingsChangedAt }.sortedByDescending { config.settingsChangedAt.getValue(it) }
        val on = activeSettings(config).takeIf { it.isNotEmpty() }?.let { ConfigurationCategory(ON_NODE, "Currently on", pathed(it)) }
        val about = relevant.distinct().takeIf { it.isNotEmpty() }?.let { ConfigurationCategory("context", RELEVANT_NODE_LABEL, pathed(it)) }
        val byDefault = defaultList.takeIf { it.isNotEmpty() }?.let { ConfigurationCategory("default", DEFAULT_NODE_LABEL, pathed(it)) }
        val groups = TreeGroup.entries.mapNotNull { group ->
            val settings = Setting.entries.filter { treeGroupOf(it) == group }
                // The order of every row, then of this page's types, then of the others'.
                .sortedBy { if (it.section == null) 0 else if (it.section in ownKinds) 1 else 2 }
            if (settings.isEmpty()) return@mapNotNull null
            // Its sub-categories first ("Where to search", "Matching"), then its configurations, then what of it is on by
            // default; a sub-category its configurations first ("Where to search": Id … then "Children").
            fun under(sub: TreeSubNode): List<Setting> =
                settings.filter { subNodeOf(it) == sub } + TreeSubNode.entries.filter { it.parent == sub }.flatMap(::under)
            fun subNode(sub: TreeSubNode): ConfigurationCategory =
                ConfigurationCategory(
                    sub.id, sub.label,
                    qualified(WHERE_SETTINGS.filter { subNodeOf(it) == sub } + settings.filter { subNodeOf(it) == sub && it !in WHERE_SETTINGS }) +
                        TreeSubNode.entries.filter { it.parent == sub }.map(::subNode) +
                        listOfNotNull(defaultsOf(sub.id, under(sub))),
                )
            val subs = TreeSubNode.entries.filter { it.group == group && it.parent == null }.map(::subNode)
            ConfigurationCategory(
                group.id, group.label,
                subs + qualified(settings.filter { subNodeOf(it) == null }) + listOfNotNull(defaultsOf(group.id, settings)),
            )
        }
        val others = FEATURE_PAGES.filter { it != own }
            .mapNotNull { page -> typesOf(page).takeIf { it.isNotEmpty() }?.let { ConfigurationCategory("page/" + page.id, page.label, it) } }
            .takeIf { it.isNotEmpty() }
            ?.let { ConfigurationCategory(OTHER_PAGES_NODE, "Other feature pages", it) }
        val structure = groups + own?.let(::typesOf).orEmpty() + listOfNotNull(others)
        // Every category, by its path, as it stands away from its place: what only leads to other configurations is
        // found by its name too, in the root's flat list (those below the root) and in "Recent › Elsewhere".
        fun nested(nodes: List<ConfigurationNode>, above: List<String>): List<Pair<Boolean, ConfigurationCategory>> =
            nodes.filterIsInstance<ConfigurationCategory>().filterNot { it.label == DEFAULT_NODE_LABEL }.flatMap { node ->
                val path = above + node.label
                listOf((above.isEmpty()) to node.copy(id = FLAT_NODE_PREFIX + node.id, label = path.joinToString(TREE_PATH_SEPARATOR))) +
                    nested(node.children, path)
            }
        val away = nested(structure, emptyList())
        val awayById = away.associate { (_, node) -> node.id.removePrefix(FLAT_NODE_PREFIX) to node }
        // User rule 2026-11-16: "Recent › Here" (this window), "› Elsewhere" (the other ▾ trees: what they changed, what
        // they looked into, then both, the last first), then every configuration, the last changed anywhere first.
        val changedElsewhere = Setting.entries.filter { it in elsewhere.changed }.sortedByDescending { elsewhere.changed.getValue(it) }
        val looked = elsewhere.looked.filterKeys { it in awayById }.entries.sortedByDescending { it.value }
        val elsewhereNode = if (changedElsewhere.isEmpty() && looked.isEmpty()) null else ConfigurationCategory(
            "$RECENT_NODE/elsewhere", "Elsewhere",
            listOfNotNull(
                changedElsewhere.takeIf { it.isNotEmpty() }?.let { ConfigurationCategory("$RECENT_NODE/elsewhere/changed", "Changed state recently", pathed(it)) },
                looked.takeIf { it.isNotEmpty() }?.let { l -> ConfigurationCategory("$RECENT_NODE/elsewhere/looked", "Looked into the children", l.map { awayById.getValue(it.key) }) },
            ) + (changedElsewhere.map { it to elsewhere.changed.getValue(it) } + looked.map { it.key to it.value })
                .sortedByDescending { it.second }
                .map { (what, _) -> if (what is Setting) ConfigurationLeaf(what, pathed = true) else awayById.getValue(what as String) },
        )
        fun lastChanged(setting: Setting) = maxOf(config.settingsChangedAt[setting] ?: Long.MIN_VALUE, elsewhere.changed[setting] ?: Long.MIN_VALUE)
        val recent = ConfigurationCategory(
            RECENT_NODE, "Recent",
            listOfNotNull(
                changedHere.takeIf { it.isNotEmpty() }?.let { ConfigurationCategory("$RECENT_NODE/here", "Here", pathed(it)) },
                elsewhereNode,
            ) + pathed(Setting.entries.sortedByDescending(::lastChanged)),
        )
        return listOfNotNull(recent, on, about, byDefault) + structure + away.filterNot { it.first }.map { it.second } +
            pathed(Setting.entries.sortedBy { it.sorts })
    }

    /**
     * User rule 2026-11-16 (*"If I did this recently from another down arrow, it will appear in arrow > recent >
     * elsewhere"*): **what the other ▾ trees did** — the configurations their windows changed and when ([changed], the
     * last time any did), and the categories they looked into ([looked], by node id). Local to the device; never stored
     * in a window's configuration.
     */
    data class Elsewhere(val changed: Map<Setting, Long> = emptyMap(), val looked: Map<String, Long> = emptyMap()) {
        companion object {
            /** What the windows [others] changed: each configuration at the last time one of them did. */
            fun changedIn(others: List<Config>): Map<Setting, Long> {
                val out = HashMap<Setting, Long>()
                for (config in others) for ((setting, at) in config.settingsChangedAt) if (at > (out[setting] ?: Long.MIN_VALUE)) out[setting] = at
                return out
            }
        }
    }

    /**
     * Whether looking into the node [id] is worth remembering for "Looked into the children" ([Elsewhere.looked]): a
     * category of the tree's structure, not one that only lists configurations found elsewhere ("Recent", "Currently
     * on", "Relevant here", a "Default for this").
     */
    fun isLookedIntoWorthy(id: String): Boolean {
        val own = id.removePrefix(FLAT_NODE_PREFIX)
        return !(own == RECENT_NODE || own.startsWith("$RECENT_NODE/") || own == ON_NODE || own == "context" || own == "default" || own.endsWith("/default"))
    }

    /** User rule 2026-11-16: a category of a [TreeGroup]'s own, holding part of it ("Search › Matching"). */
    enum class TreeSubNode(val group: TreeGroup, val id: String, val label: String, val parent: TreeSubNode? = null) {
        WhereToSearch(TreeGroup.Bar, "bar/where", "Where to search"),
        WhereChildren(TreeGroup.Bar, "bar/where/children", "Children", WhereToSearch),
        Matching(TreeGroup.Bar, "bar/matching", "Matching"),
    }

    /** The sub-category of its group [setting] stands in ([TreeSubNode]); null: right under the group. */
    fun subNodeOf(setting: Setting): TreeSubNode? =
        when (setting) {
            Setting.MatchCase, Setting.WholeWord, Setting.Regex -> TreeSubNode.Matching
            Setting.SearchInId, Setting.SearchInName, Setting.SearchInDescription, Setting.SearchInButtons -> TreeSubNode.WhereToSearch
            Setting.SearchInChildren, Setting.ChildSearchInId, Setting.ChildDistance, Setting.ChildSearchInName,
            Setting.ChildSearchInDescription, Setting.ChildSearchInButtons,
            -> TreeSubNode.WhereChildren
            else -> null
        }

    /**
     * The categories above [setting]'s own place in the tree, root first ([ConfigurationLeaf.path]): a type's own
     * configuration under its type, any other under its group and sub-category; none for Reset, a row of the root alone.
     */
    fun homePathOf(setting: Setting): List<String> {
        setting.section?.let { return listOf(treeNameOf(it)) }
        val group = treeGroupOf(setting) ?: return emptyList()
        return listOf(group.label) + generateSequence(subNodeOf(setting)) { it.parent }.toList().asReversed().map { it.label }
    }

    /**
     * User rule 2026-11-16 (the sketch: *"where to search: id, name, description, buttons, children"*): what a
     * configuration is called in the tree, where its category says the rest — "Where to search › Id"; elsewhere (the
     * Configuration Search window, a row of the search over configurations) its whole [Setting.label].
     */
    fun treeLabelOf(setting: Setting): String =
        when (setting) {
            Setting.SearchInId, Setting.ChildSearchInId -> "Id"
            Setting.SearchInName, Setting.ChildSearchInName -> "Name"
            Setting.SearchInDescription, Setting.ChildSearchInDescription -> "Description"
            Setting.SearchInButtons, Setting.ChildSearchInButtons -> "Buttons"
            Setting.SearchInChildren -> "Search the children"
            Setting.ChildDistance -> "Parent distance"
            else -> setting.label
        }

    /** The id of the tree's first node ([configurationTree]). */
    const val RECENT_NODE: String = "recent"

    /** The id of "Currently on" ([configurationTree]). */
    const val ON_NODE: String = "on"

    /** What a category of the root's flat list wears before its own id ([configurationTree]): it is that category again. */
    const val FLAT_NODE_PREFIX: String = "all:"

    /** The node of what is on by default here, at the root and in each group ([configurationTree]). */
    const val DEFAULT_NODE_LABEL: String = "Default for this"

    /**
     * The tree's "Relevant here" ([configurationTree]): what the window is about (its `relevant`), on or off. "On for
     * this list" until 2026-11-14; until 2026-11-16 it held what the window was opened on and what is on now too, which
     * "Default for this" and "Currently on" hold since.
     */
    const val RELEVANT_NODE_LABEL: String = "Relevant here"

    /** The id of the node holding the pages the window is not on ([configurationTree]). */
    const val OTHER_PAGES_NODE: String = "other-pages"

    /** What the id of the pop-up of "General" held in a window's [Config.inside]: a node the tree no longer has (2026-11-14). */
    private const val RETIRED_GENERAL_POPUP: String = "▾/general"

    /**
     * User rule 2026-11-16 (*"the description of a search configuration is displayed in an info bubble when hovering over
     * it"*): **the description of the [Kind.SearchConfiguration] row [id]** — a configuration's ([settingDescription]) or a
     * node's of the tree ([nodeDescription]); what "Where to search › Description" reads. Null for an id that is neither.
     */
    fun configurationDescription(id: String): String? =
        if (id.startsWith(CONFIGURATION_NODE_PREFIX)) nodeDescription(id.removePrefix(CONFIGURATION_NODE_PREFIX).removePrefix(FLAT_NODE_PREFIX))
        else Setting.entries.firstOrNull { it.name == id }?.let(::settingDescription)

    /** What [setting] does, in a sentence or two: its info bubble ([configurationDescription]). */
    /** What the methods every list offers mean (anomaly 2026-11-20: "relevance" was read as nothing). */
    private const val SORT_KEY_HINTS: String =
        "Relevance: the rows whose name is the search text first, then those starting with it, then the rest; with nothing " +
            "typed, the list's own order. Name: alphabetical."

    private const val EVOLUTION_INVOLVEMENT_HINT: String =
        "Involvement: the evolutions given to the tasks of \"Involvement of\" first, then those relative to one of them, then the rest."

    fun settingDescription(setting: Setting): String =
        when (setting) {
            Setting.SearchText -> "What the window's bar holds: the rows that answer it are listed, the best answers first."
            Setting.MatchCase -> "The bars match only text written with the same capitals."
            Setting.WholeWord -> "The bars match only whole words, never part of a longer word."
            Setting.Regex -> "The bars hold regular expressions."
            Setting.SearchInId -> "The bars are matched against each row's id."
            Setting.SearchInName -> "The bars are matched against each row's name — the only part searched by default."
            Setting.SearchInDescription -> "The bars are matched against each row's description, the text of its info bubble."
            Setting.SearchInButtons -> "The bars are matched against the labels of what can be pressed on each row."
            Setting.SearchInChildren -> "A row is also found by its children: those at the parent distance below, by the parts checked here."
            Setting.ChildSearchInId -> "A row is found by its children's ids."
            Setting.ChildDistance -> "How far below a row its children are read: any, every level; 1, its direct children; 2, their children alone…"
            Setting.ChildSearchInName -> "A row is found by its children's names."
            Setting.ChildSearchInDescription -> "A row is found by its children's descriptions."
            Setting.ChildSearchInButtons -> "A row is found by the labels of what can be pressed on its children."
            Setting.SearchBars -> "More bars beside the window's own, and the formula that puts them together (AND, OR, NOT)."
            Setting.Types -> "The types of element the window lists."
            Setting.PageTypes -> "The type selector offers only the types of the window's feature page."
            Setting.ResetSearch -> "Puts the window back to the configuration it opened with."
            Setting.CompletionList -> "What the bar offers to complete what is typed."
            Setting.SortResults ->
                "The order of every row: the sorting methods checked, the first one dominant. " + SORT_KEY_HINTS
            Setting.CalendarAdd -> "Only what can be put on the calendar at the instant given."
            Setting.CalendarAt -> "Only what is on the calendar at the instant given."
            Setting.ExcludedElements -> "Elements always kept out of the list."
            Setting.ListedFrom -> "Where the rows come from: what the window is given to list. The rest of its configuration narrows them."
            Setting.IncludedElements -> "Elements always in the list, whatever else narrows it."
            Setting.NoParentOfKinds -> "Only the elements with no parent of the types checked."
            Setting.ParentsOf -> "Only the parents of the elements given."
            Setting.InvolvementOf -> "The tasks the order involvement puts first: the evolutions given to them, then those relative to them, then the rest."
            Setting.ParentDistance -> "How far above those elements a parent stands to be listed: 1, the direct parents; 2, their parents alone…"
            Setting.GroupByAddedElements -> "The actions grouped by the added elements they act on."
            Setting.ExpandResults -> "The top rows open, showing their children, from the start."
            Setting.SearchSortsOnly -> "The actions section's bar sorts the actions and hides none."
            Setting.MenuCustomizing -> "A press selects a row and activates nothing; a held press drags the selected rows into the order kept."
            Setting.DropButtons -> "A button on every element to drop it from the list."
            Setting.TaskRowLayout ->
                "What a task row shows, in this order. Default: the row as the tree draws it. Set, a title in it is shown " +
                    "whole and the list scrolls sideways (Shift + wheel, or the bar under it) where a row is wider than it."
            Setting.ButtonFollowsExpansion ->
                "On: the left-side menu button that opened this window saves which rows are open (sub-tree rows included) each " +
                    "time one is opened or closed, and opens this window with them next time. Off: the button keeps the open " +
                    "rows it last saved; its right-click \"Update\" saves them, with the rest of the window."
            // Anomaly 2026-11-19 (*"Add description bubble info for all the search configuration elements as well"*): each
            // type's filter says what it keeps; it read "Keeps the tasks by 'In a task tree'", its own name again.
            Setting.TaskInTree -> "Yes: only the tasks a task tree holds. No: only the tasks no tree holds any more, kept for what was done."
            Setting.TaskTreeRoot -> "Yes: only the tasks at the root of a task tree, directly under \"Task trees\". No: every other task."
            Setting.TaskSchedulable -> "Yes: only the tasks the scheduler can place — a task with no sub-task, still in a tree. No: the others."
            Setting.TaskCategory -> "Only the tasks carrying the category chosen."
            Setting.TaskOnCalendar -> "Yes: only the tasks with at least one block on the calendar. No: the tasks with none."
            Setting.TaskBoxesFrom -> "Only the tasks with a block on the calendar still running at this instant (with \"until\", one block doing both)."
            Setting.TaskBoxesUntil -> "Only the tasks with a block on the calendar starting by this instant (with \"from\", one block doing both)."
            Setting.CategoryHasRules -> "Yes: only the categories with at least one rule. No: those with none."
            Setting.PeriodOriginSetting -> "Yes: only the periods the app comes with. No: only the periods you made."
            Setting.PeriodDrawingSetting -> "Only the periods drawn on the calendar the way chosen."
            Setting.PeriodResilienceSetting -> "Only the periods the task chosen has this much resilience to: none (it may not run there), some, or all."
            Setting.PeriodOnCalendar -> "Yes: only the periods with at least one block on the calendar. No: those with none."
            Setting.PeriodBoxesFrom -> "Only the periods with a block on the calendar still running at this instant."
            Setting.PeriodBoxesUntil -> "Only the periods with a block on the calendar starting by this instant."
            Setting.AlarmStateSetting -> "Only the alarms that are on, or only those that are off."
            Setting.AlarmDays -> "Only the alarms that ring on at least one of the days checked."
            Setting.TimerStateSetting -> "Only the timers that are idle, running or paused, as chosen."
            Setting.ChronoStateSetting -> "Only the chronos that are idle, running or paused, as chosen."
            Setting.QuotaRepeatsSetting -> "Yes: only the quotas whose loop repeats. No: only those that run once."
            Setting.ReminderRepeatsSetting -> "Only the one-off reminders, or only the repeating ones."
            Setting.BlockElements -> "Only the calendar blocks of the elements chosen."
            Setting.HistoryCategorySetting -> "Only the history units of one stack: edits, selections, the calendar, the main one, window moves."
            Setting.HistoryWindowSetting -> "Only the history units made in the window chosen, or by the scheduler engine."
            Setting.HistoryUndoneSetting -> "Yes: only the history units undone on their device (still redoable there). No: those not undone."
            Setting.HistoryChangedKindsSetting -> "Only the history units that changed an element of one of the types checked."
            Setting.HistoryChangedElementSetting -> "Only the history units that changed the element chosen, even one deleted since."
            Setting.NotificationSourceSetting -> "Only the notifications that came from what is checked: a task to do now, a screen break, an alarm…"
            Setting.RelationSectionSetting -> "Only the task relations of one section of the task relations list."
            Setting.ShortcutReboundSetting -> "Yes: only the keyboard shortcuts bound to another chord than the one they come with. No: the others."
            Setting.WindowStatusSetting -> "Only the windows that are open, minimized to the window bar, or not open."
            Setting.WindowDuplicatesSetting -> "Hidden: one row per type of window rather than one per window."
            Setting.PromptProject, Setting.AnswerProject, Setting.ReasoningProject ->
                "Only the Claude Code log entries of the project chosen."
            Setting.PromptFrom, Setting.AnswerFrom, Setting.ReasoningFrom -> "Only the Claude Code log entries written from this instant on."
            Setting.PromptUntil, Setting.AnswerUntil, Setting.ReasoningUntil -> "Only the Claude Code log entries written until this instant."
            else -> {
                val kind = setting.section
                when {
                    kind == null -> setting.label + "."
                    setting.sorts -> "The order of the ${kind.plural}: the sorting methods checked, the first one dominant. " +
                        SORT_KEY_HINTS + (if (kind == Kind.PresenceEvolution || kind == Kind.MinimumEvolution) " " + EVOLUTION_INVOLVEMENT_HINT else "")
                    else -> "Keeps the ${kind.plural} by \"${setting.label}\". " + (filterHint(listOf(setting)) ?: "")
                }.trim()
            }
        }

    /** What the node [id] of the configurations tree holds: its info bubble ([configurationDescription]). */
    fun nodeDescription(id: String): String? {
        if (id.endsWith("/default") || id == "default") return "The configurations on by default in this window" +
            (if (id == "default") "." else ", among those of this category.")
        TreeGroup.entries.firstOrNull { it.id == id }?.let { group ->
            return when (group) {
                TreeGroup.Bar -> "What the bars hold, what of a row they are matched against, and how."
                TreeGroup.Types -> "Which types of element the window lists."
                TreeGroup.Sorting -> "The order of the rows: of every row, then of each type's."
                TreeGroup.Parents -> "Who stands above or under whom: parents, children, and how the rows open."
                TreeGroup.Filters -> "What keeps rows of any type out of the list."
                TreeGroup.Display -> "How every row is drawn and what pressing it does."
            }
        }
        TreeSubNode.entries.firstOrNull { it.id == id }?.let { sub ->
            return when (sub) {
                TreeSubNode.WhereToSearch -> "What of a row the bars are matched against: its id, name, description, buttons, children."
                TreeSubNode.WhereChildren -> "A row found by its children: which of them, and what of them is read."
                TreeSubNode.Matching -> "How the bars match: case, whole words, regular expressions."
            }
        }
        if (id.startsWith("kind/")) {
            val kind = Kind.entries.firstOrNull { it.name == id.removePrefix("kind/") } ?: return null
            return "The configurations of the ${kind.plural}: their filters, then their order."
        }
        if (id.startsWith("page/")) {
            val page = FEATURE_PAGES.firstOrNull { it.id == id.removePrefix("page/") } ?: return null
            return "The types of the ${page.label} page, each with its configurations."
        }
        return when (id) {
            RECENT_NODE -> "Every configuration, the last changed first; \"Here\" holds those changed in this window."
            "$RECENT_NODE/here" -> "The configurations changed in this window, the last first."
            "$RECENT_NODE/elsewhere" -> "The configurations changed, and the categories looked into, from other ▾ trees."
            "$RECENT_NODE/elsewhere/changed" -> "The configurations changed from other ▾ trees, the last first."
            "$RECENT_NODE/elsewhere/looked" -> "The categories looked into from other ▾ trees, the last first."
            ON_NODE -> "The configurations set otherwise than \"any\" in this window."
            "context" -> "What this window is about, on or off."
            OTHER_PAGES_NODE -> "The feature pages other than this window's, each with its types."
            else -> null
        }
    }

    /** What the id of a node of the configurations tree wears as a [Kind.SearchConfiguration] row; a leaf's is its [Setting]'s name. */
    const val CONFIGURATION_NODE_PREFIX: String = "node:"

    /**
     * Every node of the configurations tree that has children ([SettingRole.Group]), top down: those of a window opened
     * on nothing, on each page in turn — a page's own node stands in the tree of the windows of the other pages.
     */
    fun configurationCategories(): List<ConfigurationCategory> = CONFIGURATION_CATEGORIES

    private val CONFIGURATION_CATEGORIES: List<ConfigurationCategory> by lazy {
        fun walk(nodes: List<ConfigurationNode>): List<ConfigurationCategory> =
            // A category of the root's flat list is one found elsewhere: no row of its own.
            nodes.filterIsInstance<ConfigurationCategory>().filterNot { it.id.startsWith(FLAT_NODE_PREFIX) }.flatMap { listOf(it) + walk(it.children) }
        FEATURE_PAGES.flatMap { walk(configurationTree(Config(page = it.id))) }.distinctBy { it.id }
    }

    /** The search configuration children of the [Kind.SearchConfiguration] row [id], by id: a node's, in the tree's order; a leaf has none. */
    fun configurationChildIds(id: String): List<String> {
        if (!id.startsWith(CONFIGURATION_NODE_PREFIX)) return emptyList()
        // A category of the root's flat list is that category again.
        val own = id.removePrefix(CONFIGURATION_NODE_PREFIX).removePrefix(FLAT_NODE_PREFIX)
        val node = configurationCategories().firstOrNull { it.id == own } ?: return emptyList()
        return node.children.map(::configurationRowId)
    }

    /** The [Setting] an added [Kind.SearchConfiguration] row is, where it has no search configuration children; else null. */
    fun settingOf(row: Result): Setting? =
        (row as? ItemResult)?.takeIf { it.kind == Kind.SearchConfiguration }?.let { item -> Setting.entries.firstOrNull { it.name == item.id } }

    /**
     * User rule 2026-11-13: **one Search window as the "State in each window" action lists it** ([AddedAction.ConfigurationStates]):
     * its [id] — a frame id (`Search#2`), a menu button's, or a ▾ window's (its owner's id and "▾") — its [title], the
     * [config] it holds, [open] or only saved, and [set], what writes its configuration (null: shown, not changed from there).
     */
    class SearchWindowState(
        val id: String,
        val title: String,
        val config: Config,
        val open: Boolean,
        val set: ((Config) -> Unit)? = null,
    )

    /**
     * User rule 2026-11-11: **the search configuration of a pop-up of the configurations tree** as it opens — the tree is
     * a search over search configurations, so its type is [Kind.SearchConfiguration] alone. Its search text is the
     * pop-up's search field; the arrow in that pop-up's bar opens the tree of THIS configuration, one level down.
     */
    /**
     * **The ▾'s own search** (user rule 2026-11-11; 2026-11-16: *"the same logic everywhere, what changes are the default
     * values"*): a Search window like any, whose defaults are the search configurations, in the tree's order
     * ([TREE_ORDER_SORTS]), its bar reading their names and their children's at any depth — so a category is found by what
     * it holds ([CHILD_DISTANCE_ANY]).
     */
    val CONFIGURATION_SEARCH: Config by lazy {
        Config(
            kinds = setOf(Kind.SearchConfiguration),
            sorts = TREE_ORDER_SORTS,
            filters = Filters(searchChildren = true, childDistance = CHILD_DISTANCE_ANY),
        )
    }

    /** [next], what [previous] became by a change of [settings] at [nowMillis]: their change times written ([Config.settingsChangedAt]). */
    fun withChanged(previous: Config, next: Config, settings: Collection<Setting>, nowMillis: Long): Config =
        if (next == previous) next else next.copy(settingsChangedAt = previous.settingsChangedAt + settings.associateWith { nowMillis })

    fun configurations(
        query: String,
        kinds: Set<Kind>,
        onlyKinds: Set<Kind>? = null,
        filtersOn: Filters? = null,
    ): List<Pair<Kind?, List<Setting>>> =
        (listOf<Kind?>(null) + Kind.entries.filter { it in kinds }).mapNotNull { section ->
            val cut = section != null && onlyKinds != null && section !in onlyKinds
            // The kind's settings first, its order last: what is kept, then how it is laid out.
            val settings = Setting.entries
                .filter { it.section == section && matchRank(it.label, query) != null }
                .filter { !cut || filtersOn?.isOn(it) == true }
                .sortedBy { it.sorts }
            if (settings.isEmpty()) null else section to settings
        }

    /**
     * One group of the Search configurations window (user rule 2026-10-11): the configurations that apply to the rows
     * of [kinds] — empty for every row of the result list — and how many rows that is ([reach]). [settings] are one
     * kind's own; [shared] are the configurations SEVERAL kinds have under one name ([SHARED_SETTINGS]), each listed
     * once and written to all of its members at a time.
     */
    data class SettingGroup(
        val kinds: List<Kind>,
        val reach: Int,
        val settings: List<Setting>,
        val shared: List<List<Setting>> = emptyList(),
    ) {
        /** The one kind the group is about, or null (every element, or several kinds). */
        val kind: Kind? get() = kinds.singleOrNull()

        val title: String
            get() =
                (if (kinds.isEmpty()) "Every element" else kinds.joinToString(" + ") { it.label.replaceFirstChar { c -> c.uppercase() } }) +
                    "  ·  " + reach

        /** What the group's expansion arrow is remembered under ([ConfigurationSearch.collapsedGroups]). */
        val id: String get() = if (kinds.isEmpty()) "all" else "kinds/" + kinds.joinToString("+") { it.name }
    }

    /**
     * The configurations several kinds have under ONE name and with one meaning: a task's and a restrictive period's
     * place on the calendar. Where every row of the result list is of those kinds, the configuration applies to every
     * element and is listed with them (anomaly 2026-10-11: *"I don't see 'Every box from' in the 'Every element'
     * group"* — it was listed once per kind and never there). The others that share a label ("State", "Category",
     * "Repeats") are different questions per kind, with different answers, and stay each kind's own.
     */
    val SHARED_SETTINGS: List<List<Setting>> =
        listOf(
            listOf(Setting.TaskOnCalendar, Setting.PeriodOnCalendar),
            listOf(Setting.TaskBoxesFrom, Setting.PeriodBoxesFrom),
            listOf(Setting.TaskBoxesUntil, Setting.PeriodBoxesUntil),
            // The Claude Code logs' filters are one filter each, about the three kinds alike.
            listOf(Setting.PromptProject, Setting.AnswerProject, Setting.ReasoningProject),
            listOf(Setting.PromptFrom, Setting.AnswerFrom, Setting.ReasoningFrom),
            listOf(Setting.PromptUntil, Setting.AnswerUntil, Setting.ReasoningUntil),
        )

    /**
     * User rule 2026-10-11: **the Search configurations window's groups, by how many elements of the result list
     * their configurations apply to** — *"with the same logic as in the action section of the Search window, the
     * first group gather all the configurations that can be applied to every element in a set of n element, where n is
     * as big as possible, followed by other groups with n decreasing"*.
     *  - **Every element**: the configurations about the search as a whole, and those every kind with a row shares
     *    ([SHARED_SETTINGS]) — one field for all of them;
     *  - **several kinds** that share configurations without holding every row ("Task + Restrictive period"): those;
     *  - **each kind**: its own, the shared ones among them (to set one kind's alone).
     * The widest first ([counts], [kindCountsInResults]; groups of as many rows keep the drop-down's order).
     *
     * [onlyApplicable] is the window's button — *"shows only the search configurations that can be applied to at least
     * one element in the result list"*: a kind with no row has no group. [frozen] is its switch — *"to stop hiding or
     * showing new search configurations as the result list changes"*: while given, the kinds shown, and their order,
     * are those and no longer the results' ([ConfigurationSearch.frozenKinds]); the reach written is still the live
     * one. Within [kinds] (the window's own types) and the names [query] finds, as [configurations].
     */
    fun configurationGroups(
        query: String,
        kinds: Set<Kind>,
        counts: Map<Kind, Int>,
        onlyApplicable: Boolean = false,
        frozen: List<Kind>? = null,
    ): List<SettingGroup> {
        val listed = applicableKinds(kinds, counts, onlyApplicable, frozen)
        val sections = configurations(query, listed.toSet()).toMap()
        val total = counts.values.sum()
        fun rows(of: List<Kind>) = of.sumOf { counts[it] ?: 0 }
        // A family is shared where at least two of its kinds are listed (and its name is among what [query] finds).
        val families =
            SHARED_SETTINGS.mapNotNull { family ->
                family.filter { it.section in listed && sections[it.section]?.contains(it) == true }.takeIf { it.size >= 2 }
            }
        val (whole, partial) = families.partition { family -> total > 0 && rows(family.mapNotNull { it.section }) == total }
        val out = ArrayList<SettingGroup>()
        val general = sections[null].orEmpty()
        if (general.isNotEmpty() || whole.isNotEmpty()) out += SettingGroup(emptyList(), total, general, whole)
        val several =
            // Named in the drop-down's order whatever their rows, so the group is one group as the results change.
            partial.groupBy { family -> Kind.entries.filter { kind -> kind in listed && family.any { it.section == kind } } }
                .map { (of, shared) -> SettingGroup(of, rows(of), emptyList(), shared) }
        val single = listed.mapNotNull { kind -> sections[kind]?.let { SettingGroup(listOf(kind), counts[kind] ?: 0, it) } }
        // Frozen, the order is the one the switch kept; else the widest first (stable: several kinds before their own).
        out += if (onlyApplicable && frozen != null) several + single else (several + single).sortedByDescending { it.reach }
        return out
    }

    /** [own] once the arrow of the group [id] is pressed; the ids of groups no longer [listed] are dropped on the way. */
    fun withSettingGroupToggled(own: ConfigurationSearch, id: String, listed: Set<String>): ConfigurationSearch {
        val kept = own.collapsedGroups.filterTo(HashSet()) { it in listed }
        return own.copy(collapsedGroups = if (id in kept) kept - id else kept + id)
    }

    /**
     * The kinds whose configurations the window shows, in its order ([configurationGroups]) — also what the switch
     * keeps when it is set ([ConfigurationSearch.frozenKinds]).
     */
    fun applicableKinds(kinds: Set<Kind>, counts: Map<Kind, Int>, onlyApplicable: Boolean, frozen: List<Kind>? = null): List<Kind> =
        when {
            onlyApplicable && frozen != null -> frozen.filter { it in kinds }
            else ->
                Kind.entries.filter { it in kinds && (!onlyApplicable || (counts[it] ?: 0) > 0) }
                    .sortedByDescending { counts[it] ?: 0 }
        }

    /** How many rows of each kind the Search window's results hold for [config] — a kind with none is absent. */
    /**
     * **Read with everything the Search window's own list is read with** ([results]' [nowMillis], [layerKindsAt],
     * [schedulerRuns], the configuration's placement) — anomaly 2026-10-11: counted without the calendar's layer
     * bands, a period that is on the timeline only as one ("no computer unlocked" off the lock history, the usual
     * period under a right-click) had no row here though the window listed it, so its group was hidden and nothing
     * was "every element"'s. A caller that leaves one out counts another list than the one on screen.
     */
    fun kindCountsInResults(
        state: SchedulerState,
        config: Config,
        windows: List<WindowEntry> = emptyList(),
        nowMillis: Long = 0L,
        layerKindsAt: (Long) -> Set<String> = { emptySet() },
        schedulerRuns: List<org.example.project.scheduler.state.SchedulerRunEntry> = emptyList(),
        claudeLogs: ClaudeLogCatalog = ClaudeLogCatalog.EMPTY,
    ): Map<Kind, Int> =
        // Paths are not needed to know that a row exists, so the walk that lists them is skipped.
        results(
            state, config.searchedKinds, config.query, { emptyMap() }, config.filters, windows = windows, nowMillis = nowMillis,
            layerKindsAt = layerKindsAt, schedulerRuns = schedulerRuns, placement = config.placement,
            // Read only where the end is: the calendar filter's "without removing anything".
            placementOwnLength = if (config.filters.calendarAddKeeping) {
                placementDefaultLength(state, resolve(state, config.added, { emptyMap() }, windows, schedulerRuns, claudeLogs))
            } else {
                null
            },
            claudeLogs = claudeLogs,
        ).groupingBy { checkedKindOf(it, config.searchedKinds) }.eachCount()

    /**
     * The checked kind [row] is listed for: its own — but a creation row listed for the kind it makes, the creation kind
     * unchecked ([withCreatedKindRows]), is one of that kind's rows (user rule 2026-10-30: it has both types).
     */
    private fun checkedKindOf(row: Result, kinds: Set<Kind>): Kind =
        if (row.kind == Kind.Creation && Kind.Creation !in kinds && row is ItemResult) {
            CREATABLE.firstOrNull { it.name == row.id } ?: row.kind
        } else {
            row.kind
        }

    /** The kinds that have at least one row in the Search window's results for [config]. */
    fun kindsInResults(
        state: SchedulerState,
        config: Config,
        windows: List<WindowEntry> = emptyList(),
        claudeLogs: ClaudeLogCatalog = ClaudeLogCatalog.EMPTY,
    ): Set<Kind> =
        // Paths are not needed to know that a row exists, so the walk that lists them is skipped.
        results(state, config.searchedKinds, config.query, { emptyMap() }, config.filters, windows = windows, claudeLogs = claudeLogs)
            .mapTo(HashSet()) { checkedKindOf(it, config.searchedKinds) }

    /** [Filters.parentDistance] for the highest distance there is: the list's first row, "highest parent distance (n)". */
    const val PARENT_DISTANCE_HIGHEST: Int = 0

    /** What the parent distance's field reads: the number, or the highest one there is ([highest]). */
    fun parentDistanceLabel(distance: Int, highest: Int): String =
        if (distance == PARENT_DISTANCE_HIGHEST) "highest ($highest)" else distance.toString()

    /** The parent distance a row of its list of integers ([integerResults]) sets; null for no such row. */
    fun parentDistanceOf(id: String): Int? = if (id == HIGHEST_INTEGER) PARENT_DISTANCE_HIGHEST else id.toIntOrNull()?.takeIf { it >= 1 }

    /** The stored form of a [Config] — strings, so an unknown value decodes to its default. */
    @Serializable
    private data class StoredConfig(
        val query: String = "",
        val kinds: List<String> = listOf(Kind.Task.name),
        val taskInTree: String? = null,
        /** New 2026-11-14: absent = any. */
        val taskTreeRoot: String? = null,
        /** New 2026-10-01: absent from an older build's = any. */
        val taskSchedulable: String? = null,
        val taskCategory: String? = null,
        val categoryHasRules: String? = null,
        val periodOrigin: String? = null,
        /** New 2026-10-11: absent from an older build's = any drawing, no resilience asked. */
        val periodDrawing: String? = null,
        val periodResilience: String? = null,
        val periodResilienceTask: String? = null,
        val alarmState: String? = null,
        val alarmDays: List<Int> = emptyList(),
        val timerState: String? = null,
        /** New 2026-09-26: absent from what an older build stored, which reads as any. */
        val chronoState: String? = null,
        /** New 2026-10-03: absent from an older build's = any. */
        val quotaRepeats: String? = null,
        val reminderRepeats: String? = null,
        val historyCategory: String? = null,
        val historyWindow: String? = null,
        val historyUndone: String? = null,
        /** New 2026-10-06: absent = any. */
        val historyEngine: String? = null,
        /** New 2026-10-03: absent from what an older build stored, which reads as any. */
        val historyChangedKinds: List<String> = emptyList(),
        /** New 2026-10-03: absent = any element. */
        val historyChangedElement: String? = null,
        val relationSection: String? = null,
        val shortcutRebound: String? = null,
        /** New 2026-09-26: absent from what an older build stored, which reads as any. */
        val windowStatus: String? = null,
        /** New 2026-09-26: absent = shown. */
        val windowDuplicates: String? = null,
        /** Null = never stored (a configuration written before sorting): the default list. */
        val sortMethods: List<StoredSortMethod>? = null,
        /** New 2026-10-30: absent from an older build's = following the default, as a window just opened does. */
        val sortFollowsDefault: Boolean = true,
        /** The first sorting shape's fields — read only, when [sortMethods] is absent ([Config.decode]). */
        val sort: String? = null,
        val sortDescending: Boolean = false,
        val kindSorts: Map<String, StoredLegacySort> = emptyMap(),
        /** New 2026-09-27 (the calendar filters, the added elements): absent from an older build's = any / none. */
        val taskOnCalendar: String? = null,
        /** A DAY (`YYYY-MM-DD`), as builds before 2026-10-11 stored the bound: read, never written any more. */
        val taskBoxesFrom: String? = null,
        val taskBoxesUntil: String? = null,
        /** New 2026-10-11: the bound as an instant. Absent from an older build's (which has the day above, or none). */
        val taskBoxesFromMillis: Long? = null,
        val taskBoxesUntilMillis: Long? = null,
        val periodOnCalendar: String? = null,
        val periodBoxesFrom: String? = null,
        val periodBoxesUntil: String? = null,
        val periodBoxesFromMillis: Long? = null,
        val periodBoxesUntilMillis: Long? = null,
        val added: List<String> = emptyList(),
        /** New 2026-11-21: absent = added from no field's caller ([Config.addedFor]). */
        val addedFor: String? = null,
        /** New 2026-10-01: absent from an older build's = no filter, the first period. */
        val actionQuery: String = "",
        /** New 2026-10-10: absent from an older build's = every group of actions open. Read no more since 2026-11-12. */
        val collapsedActionGroups: List<String> = emptyList(),
        /** New 2026-11-12: absent = every row of the actions section at its default ([Config.toggledActionRows]). */
        val toggledActionRows: List<String> = emptyList(),
        /** New 2026-11-12: absent = the actions grouped, closed, the bar sorting ([Config.actionsGrouped]…). */
        val actionsGrouped: Boolean = true,
        val actionsExpanded: Boolean = false,
        val actionSearchSortsOnly: Boolean = true,
        val menuCustomizing: Boolean = false,
        /** New 2026-11-14: absent = no order dragged ([Config.arranged]). */
        val arranged: List<String> = emptyList(),
        /** New 2026-11-12: absent = no drop button ([Config.dropButtons]). */
        val dropButtons: Boolean = false,
        /** New 2026-11-19: absent = the menu button keeps what it kept ([Config.buttonFollowsExpansion]). */
        val buttonFollowsExpansion: Boolean = false,
        /** New 2026-11-22: absent = the default task row ([Config.taskRowLayout]); a part this build does not know is skipped. */
        val taskRowLayout: List<String> = emptyList(),
        /** New 2026-10-11: absent from an older build's = the actions in their own order. */
        val actionSearch: String = "",
        /** New 2026-10-11: absent = no block picked in "Blocks on the calendar". */
        val pickedBlocks: List<String> = emptyList(),
        /** New 2026-10-12: absent = no timer picked in "Lowest countdown" (it then shows the lowest one). */
        val pickedTimers: List<String> = emptyList(),
        /** New 2026-10-12: absent = each timer of "Lowest countdown" as a block, not on one row. */
        val compactTimers: Boolean = true,
        /** New 2026-10-14: absent = no chrono picked in "Highest count" (it then shows the highest one). */
        val pickedChronos: List<String> = emptyList(),
        /** New 2026-10-14: absent = each chrono's run as a block, not on one row. */
        val compactChronos: Boolean = false,
        val resiliencePeriod: String? = null,
        /** New 2026-10-01 (the calendar filter): absent = off, no position, not from the calendar. */
        val calendarAddOn: Boolean = false,
        val calendarAddAtMillis: Long? = null,
        /** New 2026-10-07 (the calendar filter's third state): absent = the filter keeps whatever can be added. */
        val calendarAddKeeping: Boolean = false,
        val calendarClickMillis: Long? = null,
        /** New 2026-11-12 (the calendar element's view): absent = the opening day, the account's display, nothing shown. */
        val calendarDay: String? = null,
        val calendarDayMode: Boolean? = null,
        val calendarView: String = "",
        /** New 2026-10-01 (the calendar's "edit…"): absent = off, no position. */
        val calendarAtOn: Boolean = false,
        val calendarAtMillis: Long? = null,
        /** New 2026-10-04 (the notifications window): absent = every notification. */
        val notificationsSinceMillis: Long? = null,
        /** New 2026-10-05: absent = from anything; a source this build does not know is dropped. */
        val notificationSources: List<String> = emptyList(),
        /** New 2026-10-05 (the calendar blocks, "Add to the calendar"'s start and end): absent = every block, nothing said. */
        val blocksOf: List<String> = emptyList(),
        /** New 2026-11-02: absent = no element kept out, none always in. */
        val excludedElements: List<String> = emptyList(),
        val includedElements: List<String> = emptyList(),
        /** New 2026-11-12: absent = no parent filter; a kind this build does not know is dropped. */
        val noParentOfKinds: List<String> = emptyList(),
        /** New 2026-11-12: absent = the parents of nothing asked. */
        val parentsOf: List<String> = emptyList(),
        /** New 2026-11-13: absent = the direct parents (1); 0 = at any distance. */
        val parentDistance: Int = 1,
        /** New 2026-11-20: absent = no task the evolutions' involvement order is measured against. */
        val involvementOf: List<String> = emptyList(),
        /**
         * New 2026-11-16 ("Where to search"): absent = the names alone, as before; a field this build does not know is
         * dropped. [childDistance] absent = the direct children.
         */
        val searchIn: List<String>? = null,
        val searchChildren: Boolean = false,
        val childSearchIn: List<String>? = null,
        val childDistance: Int = 1,
        val placeStartMillis: Long? = null,
        val placeEndByDelta: Boolean = true,
        val placeEndMillis: Long? = null,
        /**
         * New 2026-10-19, replacing `placeLengthMillis` (always written, so never known to be stated): absent = no
         * length stated, the added elements' own stands.
         */
        val placeStatedLengthMillis: Long? = null,
        val placeStatedOverMillis: Long? = null,
        /** New 2026-10-18 (the Claude Code logs page): absent = the Task Scheduler's window, no filter on the logs. */
        val page: String = "",
        /** New 2026-10-18: absent = opened from no Search window. */
        val openedFrom: String? = null,
        /** New 2026-11-02: absent = the window comes from no element. */
        val elementSource: String? = null,
        /** New 2026-11-13: absent = no window drawn inside this one holds a configuration of its own. */
        val inside: Map<String, String> = emptyMap(),
        /** New 2026-11-02: absent = no completion list. */
        val completion: String? = null,
        /** New 2026-11-04: absent = every type offered, as before the switch ([Config.pageTypesOnly]). */
        val pageTypesOnly: Boolean = false,
        /** New 2026-11-04: absent = nothing known of when a configuration changed ([Config.settingsChangedAt]). */
        val settingsChangedAt: Map<String, Long> = emptyMap(),
        /** New 2026-11-04: absent = the case ignored and any part of a word matched, as before ([Filters.matchCase]). */
        val matchCase: Boolean = false,
        val wholeWord: Boolean = false,
        /** New 2026-11-06: absent = the bars read as text, and the window's own bar alone ([Config.extraQueries]). */
        val regex: Boolean = false,
        val extraQueries: List<String> = emptyList(),
        val queryFormula: String = "",
        val claudeProject: String? = null,
        val claudeFromMillis: Long? = null,
        val claudeUntilMillis: Long? = null,
    )

    @Serializable
    private data class StoredLegacySort(val key: String? = null, val descending: Boolean = false)

    @Serializable
    private data class StoredSortMethod(val kind: String? = null, val key: String = "", val descending: Boolean = false)

    @Serializable
    private data class StoredConfigurationSearch(
        val query: String = "",
        val kinds: List<String> = Kind.entries.map { it.name },
        val onlyResultKinds: Boolean = false,
        val target: String = "Search",
        /** 2026-09-25 → 2026-10-11, the "Show the filters that are on" button: still read past, never used. */
        val showFiltersOn: Boolean = false,
        /** New 2026-10-11: absent from what an older build stored = the list follows the results. */
        val frozenKinds: List<String>? = null,
        /** New 2026-10-11: absent = every group open. */
        val collapsedGroups: List<String> = emptyList(),
        /** New 2026-10-31: absent = written before the logs' kinds could be checked here, so [kinds] says nothing of them. */
        val logKindsKnown: Boolean = false,
        /** New 2026-11-01: absent = every feature page. */
        val pages: List<String>? = null,
    )

    private val configJson = Json { ignoreUnknownKeys = true }

    const val PATH_SEPARATOR: String = " / "

    fun pathLabel(path: List<String>): String = path.joinToString(PATH_SEPARATOR)

    /** Shortest first, then alphabetically — PRD §4's order for paths, so the two menus rank alike. */
    private val pathOrder: Comparator<List<String>> =
        compareBy<List<String>>({ it.size }, { pathLabel(it) })

    // ----- The trees ------------------------------------------------------------------------------

    /** One task tree, walked the same way whether it is the live one or a stored one. */
    private class TreeSource(
        val cells: Map<CellId, Cell>,
        val lists: Map<CellListId, CellList>,
        val tasks: Map<TaskId, Task>,
        val rootListId: CellListId,
        val rootLabel: String,
    )

    /**
     * The live tree's root segment: **the root task's own title**, as its element is named — a path is the titles of the
     * task's parents, and the parent of a task at the top is the global root (anomaly 2026-11-16: *"it says
     * 'tree-2026-10-20 / Very Short Term'. However, when I go to the list of parents of 'Very Short Term', it is not a task
     * element named 'tree-2026-10-20', but one named 'root'"*). It was the active tree's name, then (user rule 2026-11-13)
     * the top task's tree name: neither is an element above the task. Never blank — a path that starts with nothing reads
     * as a path that starts with its second segment.
     */
    private fun liveRootLabel(state: SchedulerState): String =
        state.tasks[WellKnownIds.ROOT_TASK]?.title?.takeIf { it.isNotBlank() } ?: SchedulerDomain.ROOT_TASK_TITLE

    /** The one tree (user rule 2026-11-16: every task tree is a task under the root, [TaskTreesAsTasks]). */
    private fun sources(state: SchedulerState): List<TreeSource> =
        listOf(TreeSource(state.cells, state.lists, state.tasks, state.rootListId, liveRootLabel(state)))

    /**
     * Every task [source] holds, with its shortest path. Breadth-first with each list visited once — the walk
     * [SchedulerDomain.shortestTaskTreePaths] does, with the same membership predicate (a cell whose task has
     * no title is an empty cell, and holds nothing), so "in the tree" means the same thing here as everywhere.
     * Linear in the tree, and EXACT: this is what decides that a task left, so it must never be truncated.
     */
    private fun shortestPaths(source: TreeSource): Map<TaskId, List<String>> {
        val shortest = HashMap<TaskId, List<String>>()
        val visited = hashSetOf(source.rootListId)
        var frontier = listOf(source.rootListId to listOf(source.rootLabel))
        while (frontier.isNotEmpty()) {
            val next = ArrayList<Pair<CellListId, List<String>>>()
            for ((listId, prefix) in frontier) {
                val list = source.lists[listId] ?: continue
                for (cellId in list.cellIds) {
                    val taskId = source.cells[cellId]?.taskId ?: continue
                    val task = source.tasks[taskId] ?: continue
                    if (task.title.isEmpty()) continue
                    val at = prefix
                    if (!SchedulerDomain.isRootTask(taskId) && taskId !in shortest) shortest[taskId] = at
                    val childListId = task.childListId ?: continue
                    if (visited.add(childListId)) next.add(childListId to at + task.title)
                }
            }
            frontier = next
        }
        return shortest
    }

    /**
     * The tasks every task tree holds, each with the shortest path it has across all of them. Memoized on the
     * identity of what it reads, because the stamp below asks it of the state before AND after every edit
     * boundary, and each "after" is the next boundary's "before".
     */
    fun shortestPathsInAnyTree(state: SchedulerState): Map<TaskId, List<String>> {
        val key = ShapeKey(state)
        shortestMemo.lookup(key)?.let { return it }
        val merged = HashMap<TaskId, List<String>>()
        for (source in sources(state)) {
            for ((taskId, path) in shortestPaths(source)) {
                val known = merged[taskId]
                if (known == null || pathOrder.compare(path, known) < 0) merged[taskId] = path
            }
        }
        shortestMemo.store(key, merged)
        return merged
    }

    /** At most this many paths are listed for one task — mirrored parents multiply them. */
    const val MAX_PATHS_PER_TASK: Int = 50

    /** Cell visits the all-paths walk may make, whole account, before it stops listing. */
    private const val ALL_PATHS_VISIT_BUDGET: Int = 200_000

    /**
     * **Every** path of every task, in every task tree, shortest first. Unlike [shortestPaths] a list is
     * walked once per path that reaches it, which is the point (a task under a mirrored parent has one path
     * per occurrence of that parent), so it is bounded twice: [MAX_PATHS_PER_TASK] per task and a visit
     * budget for the whole walk. Level by level, so what a budget cuts off is the longest paths, never the
     * shortest — the one a row shows. Display only: membership is [shortestPathsInAnyTree]'s, which is exact.
     */
    fun allPathsInAnyTree(state: SchedulerState): Map<TaskId, List<List<String>>> {
        val found = HashMap<TaskId, MutableList<List<String>>>()
        var budget = ALL_PATHS_VISIT_BUDGET
        for (source in sources(state)) {
            // (list, path to it, the lists above it — a list may not contain itself, however the data says so)
            var frontier = listOf(Triple(source.rootListId, listOf(source.rootLabel), listOf(source.rootListId)))
            while (frontier.isNotEmpty() && budget > 0) {
                val next = ArrayList<Triple<CellListId, List<String>, List<CellListId>>>()
                for ((listId, prefix, chain) in frontier) {
                    val list = source.lists[listId] ?: continue
                    for (cellId in list.cellIds) {
                        if (--budget < 0) break
                        val taskId = source.cells[cellId]?.taskId ?: continue
                        val task = source.tasks[taskId] ?: continue
                        if (task.title.isEmpty()) continue
                        val at = prefix
                        if (!SchedulerDomain.isRootTask(taskId)) {
                            val paths = found.getOrPut(taskId) { mutableListOf() }
                            if (paths.size < MAX_PATHS_PER_TASK && at !in paths) paths.add(at)
                        }
                        val childListId = task.childListId ?: continue
                        if (childListId !in chain) next.add(Triple(childListId, at + task.title, chain + childListId))
                    }
                }
                frontier = next
            }
        }
        return found.mapValues { (_, paths) -> paths.sortedWith(pathOrder) }
    }

    /**
     * The cell of the LIVE tree standing at [path] for [taskId] — what "go to task tree" reveals when it is
     * asked from one path of a task's row (its path box, or one line of its list of paths) rather than from the
     * task as a whole. Null for a path through a stored tree (its first segment is that tree's name, not the
     * live one's), a stale path, or a task no longer there — the caller then falls back to the task's first
     * occurrence.
     *
     * Walked on demand (a right-click), never per keystroke: depth-first along the path's titles, each (list,
     * depth) once — titles repeat, so several cells may match a segment and each is tried in reading order.
     */
    fun occurrenceAtPath(state: SchedulerState, taskId: TaskId, path: List<String>): SchedulerDomain.TaskOccurrence? {
        val first = path.firstOrNull() ?: return null
        // The first segment names the root the path runs from: a stored tree's name is not the live root's title.
        if (first != liveRootLabel(state)) return null
        val titles = path.drop(1)
        val seen = HashSet<Pair<CellListId, Int>>()
        fun walk(listId: CellListId, depth: Int, ancestors: List<CellId>): SchedulerDomain.TaskOccurrence? {
            if (!seen.add(listId to depth)) return null
            val list = state.lists[listId] ?: return null
            for (cellId in list.cellIds) {
                val cellTaskId = state.cells[cellId]?.taskId ?: continue
                if (depth == titles.size) {
                    if (cellTaskId == taskId && SchedulerDomain.isSelectableCell(state, cellId)) {
                        return SchedulerDomain.TaskOccurrence(cellId, ancestors)
                    }
                    continue
                }
                val task = state.tasks[cellTaskId] ?: continue
                if (task.title != titles[depth]) continue
                val childListId = task.childListId ?: continue
                walk(childListId, depth + 1, ancestors + cellId)?.let { return it }
            }
            return null
        }
        return walk(state.rootListId, 0, emptyList())
    }

    // ----- The last path --------------------------------------------------------------------------

    /**
     * The paths of the tasks **stranded under a stamped task**. A task inside a detached parent's sub-tree
     * has no cell the tree can reach, but its path is the parent's [Task.lastTreePath], the parent's title,
     * and the titles between. Derived, never stored: that is what keeps a detached parent's rename from
     * rewriting (and pushing) every task under it (`ServerQuotaTest`, 2026-09-23).
     *
     * Walked from every seed in [seeds] (a task, and the path it stands at) through the live tree's sub-lists,
     * shortest path first (buckets by length), each list once; [inTree] tasks are skipped as answers but still
     * walked through. A seed reached from another seed gets an answer too, which is how a stamp is found
     * redundant.
     */
    private fun strandedPaths(
        state: SchedulerState,
        inTree: Set<TaskId>,
        seeds: Map<TaskId, List<String>>,
    ): Map<TaskId, List<String>> {
        val best = HashMap<TaskId, List<String>>()
        if (seeds.isEmpty()) return best
        val buckets = HashMap<Int, MutableList<Pair<CellListId, List<String>>>>()
        fun push(listId: CellListId, prefix: List<String>) {
            buckets.getOrPut(prefix.size) { mutableListOf() }.add(listId to prefix)
        }
        for ((taskId, path) in seeds) {
            val task = state.tasks[taskId] ?: continue
            task.childListId?.let { push(it, path + task.title) }
        }
        val visited = HashSet<CellListId>()
        var length = buckets.keys.minOrNull() ?: return best
        while (buckets.isNotEmpty()) {
            val batch = buckets.remove(length)
            length++
            if (batch == null) continue
            for ((listId, prefix) in batch) {
                if (!visited.add(listId)) continue
                val list = state.lists[listId] ?: continue
                for (cellId in list.cellIds) {
                    val taskId = state.cells[cellId]?.taskId ?: continue
                    val task = state.tasks[taskId] ?: continue
                    if (task.title.isBlank() || SchedulerDomain.isRootTask(taskId)) continue
                    if (taskId !in inTree && taskId !in best) best[taskId] = prefix
                    task.childListId?.let { push(it, prefix + task.title) }
                }
            }
        }
        return best
    }

    /** Every out-of-tree task's own stamp, keyed by task: the seeds [strandedPaths] walks from. */
    private fun ownStamps(state: SchedulerState, inTree: Set<TaskId>): Map<TaskId, List<String>> =
        state.tasks.values
            .filter { it.lastTreePath.isNotEmpty() && it.id !in inTree }
            .associate { it.id to it.lastTreePath }

    /**
     * The last path of every live task no task tree holds, as the row shows it: its own stamp, else the path
     * derived from a stamped task above it ([strandedPaths]). A task with neither has none.
     */
    fun lastPaths(state: SchedulerState): Map<TaskId, List<String>> {
        val inTree = shortestPathsInAnyTree(state).keys
        val stamps = ownStamps(state, inTree)
        return strandedPaths(state, inTree, stamps) + stamps
    }

    /**
     * [Task.lastTreePath] kept true across the reduction [before] to [after]: a task whose path could be told
     * before (in some task tree, or stranded under a stamped task) and cannot be told after is stamped with
     * the shortest path it had; a task back in a tree loses its stamp. Run by
     * [org.example.project.scheduler.state.SchedulerReducer.reduce] on the account's own state, never inside
     * a projection, whose re-rooted tree would read as every task leaving.
     *
     * **Only what cannot be derived is stamped.** A task that leaves together with a stamped ancestor whose
     * sub-list still holds it (the whole sub-tree of a detached parent) reads its path off that ancestor
     * ([strandedPaths]), so it gets no stamp of its own unless its own shortest path was shorter.
     *
     * **Only at edit boundaries**, like [SchedulerDomain.pruneDetachedTree]: renaming a parent passes through
     * a blank title between keystrokes, and a blank title holds nothing, so read mid-session its whole
     * sub-tree would leave the tree and come back. So while a session is open (the tree's or a Search
     * sub-tree's) nothing is stamped, and the reduction that closes one is measured from the tree the session
     * **started** on, which is what the gesture changed.
     *
     * Cheap when nothing structural moved (the common case: a tick, a selection): the tree fields are
     * compared by identity, and the tasks by title and sub-list only.
     */
    fun withLastTreePathsStamped(before: SchedulerState, after: SchedulerState): SchedulerState {
        if (before === after) return after
        if (after.editSession != null || after.searchEditSession != null) return after
        val session = before.editSession ?: before.searchEditSession
        val baseline =
            if (session == null) {
                before
            } else {
                // The projection's tree is the live one re-rooted, so its snapshot walked from the live root is
                // the live tree the session started on.
                val tree = session.treeBefore
                before.copy(cells = tree.cells, lists = tree.lists, tasks = tree.tasks)
            }
        if (!treeShapeChanged(baseline, after)) return after

        val was = lastPaths(baseline) + shortestPathsInAnyTree(baseline)
        val now = shortestPathsInAnyTree(after)
        // Tasks whose path could be told before and that have no stamp to tell it now.
        val leaving =
            was.filter { (taskId, _) ->
                val task = after.tasks[taskId]
                // A blank title is a cell being emptied, whose task the edit boundary purges: nothing to name.
                task != null && taskId !in now && task.title.isNotBlank() && task.lastTreePath.isEmpty()
            }
        val derived = strandedPaths(after, now.keys, ownStamps(after, now.keys) + leaving)

        var tasks: MutableMap<TaskId, Task>? = null
        fun put(task: Task) {
            val map = tasks ?: after.tasks.toMutableMap().also { tasks = it }
            map[task.id] = task
        }
        for ((taskId, path) in leaving) {
            if (derived[taskId] == path) continue
            put(after.tasks.getValue(taskId).copy(lastTreePath = path))
        }
        for (task in after.tasks.values) {
            if (task.lastTreePath.isNotEmpty() && task.id in now) put(task.copy(lastTreePath = emptyList()))
        }
        return tasks?.let { after.copy(tasks = it) } ?: after
    }

    /** Whether anything [shortestPathsInAnyTree] reads differs between [a] and [b]. */
    private fun treeShapeChanged(a: SchedulerState, b: SchedulerState): Boolean {
        if (a.cells !== b.cells || a.lists !== b.lists || a.rootListId != b.rootListId) return true
        if (a.tasks === b.tasks) return false
        if (a.tasks.size != b.tasks.size) return true
        for ((id, task) in b.tasks) {
            val old = a.tasks[id] ?: return true
            if (old.title != task.title || old.childListId != task.childListId) return true
        }
        return false
    }

    // ----- The results ----------------------------------------------------------------------------

    /**
     * One task row: its title, **every** path it has (shortest first — the first is the one shown), and
     * whether any task tree holds it. A task no tree holds has its last path ([lastPaths]) as its one path, or
     * none at all when it was cut by a build that did not keep it.
     */
    data class TaskResult(
        val taskId: TaskId,
        val title: String,
        val paths: List<List<String>>,
        val inTaskTree: Boolean,
        /**
         * User rule 2026-11-13: *"the tree action should be in all tasks belonging to a tree"* — the roots of the trees this
         * task belongs to (the tasks standing directly under the global root it stands under, itself if it is one), whose
         * names its "Tree name" edits. Empty: in no tree.
         */
        val treeRoots: Set<TaskId> = emptySet(),
    ) : Result {
        override val kind: Kind get() = Kind.Task
        override val name: String get() = title
        val shownPath: List<String> get() = paths.firstOrNull().orEmpty()
        val hasSeveralPaths: Boolean get() = paths.size > 1
    }

    /**
     * **The rows of [kind] the account holds**, as a Search window lists them — the ONE source of rows, read by the search
     * ([results]) and by what names an element by its key ([resolve]): an element a list can show is an element its added
     * elements can hold (anomaly 2026-11-15). [withRoot]: the global root among the tasks; [windowTypesOnly]: one window
     * row per window type.
     */
    fun kindRows(
        state: SchedulerState,
        kind: Kind,
        query: String,
        allPaths: () -> Map<TaskId, List<List<String>>>,
        windows: List<WindowEntry> = emptyList(),
        schedulerRuns: List<org.example.project.scheduler.state.SchedulerRunEntry> = emptyList(),
        claudeLogs: ClaudeLogCatalog = ClaudeLogCatalog.EMPTY,
        filters: Filters = Filters(),
        timeZone: TimeZone = TimeZone.currentSystemDefault(),
        withRoot: Boolean = false,
        windowTypesOnly: Boolean = false,
    ): List<Result> =
        when {
            kind == Kind.Task -> taskResults(state, query, allPaths(), withRoot = withRoot)
            kind in CLAUDE_KINDS -> claudeResults(kind, query, claudeLogs, filters, timeZone)
            else -> itemResults(state, kind, query, windows = windows, windowTypesOnly = windowTypesOnly, schedulerRuns = schedulerRuns, claudeLogs = claudeLogs)
        }

    /**
     * Every task of the account the search can name: the live tree's tasks (a tombstone and a detached
     * parent among them — alive on their records, not in any tree) and the tasks only a stored tree holds.
     * Never an untitled task, and the root task only [withRoot] (the search for what has no task parent,
     * [Filters.noParentOfKinds]): it is in the tree, with no path above it. [allPaths] is [allPathsInAnyTree], passed in
     * because it is a walk of every tree and the window computes it once per change of the trees, not once per keystroke
     * in the search bar.
     */
    /**
     * **Whether [taskId] is in a task tree**, as every task row says it ([TaskResult.inTaskTree]): the global root, which
     * holds them all and has no path above it, or a task some tree's path reaches ([paths]: a task's paths, by id — what
     * [shortestPathsInAnyTree] or [allPathsInAnyTree] answers). Anomaly 2026-11-19: the id rows of the edit menus said it
     * again by their own rule, and called the root "in no tree".
     */
    fun isInTaskTree(taskId: TaskId, paths: Map<TaskId, *>): Boolean = SchedulerDomain.isRootTask(taskId) || taskId in paths

    fun taskResults(
        state: SchedulerState,
        query: String,
        allPaths: Map<TaskId, List<List<String>>> = allPathsInAnyTree(state),
        withRoot: Boolean = false,
    ): List<TaskResult> {
        val inTree = shortestPathsInAnyTree(state)
        val lastPaths = lastPaths(state)
        // The trees each task belongs to (user rule 2026-11-13).
        val treeRoots = treeRootsByTask(state)
        return state.tasks.values
            .asSequence()
            .filter { (withRoot || !SchedulerDomain.isRootTask(it.id)) && it.title.isNotBlank() }
            .mapNotNull { task -> matchRank(task.title, query)?.let { rank -> rank to task } }
            .map { (rank, task) ->
                if (SchedulerDomain.isRootTask(task.id)) return@map rank to TaskResult(task.id, task.title, emptyList(), inTaskTree = isInTaskTree(task.id, inTree))
                val held = isInTaskTree(task.id, inTree)
                val paths =
                    if (held) {
                        allPaths[task.id]?.takeIf { it.isNotEmpty() } ?: listOf(inTree.getValue(task.id))
                    } else {
                        listOfNotNull(lastPaths[task.id])
                    }
                rank to TaskResult(task.id, task.title, paths, held, treeRoots = treeRoots[task.id].orEmpty())
            }
            .sortedWith(
                compareBy<Pair<Int, TaskResult>>(
                    { it.first },
                    // What a tree holds before what only the timeline remembers — the §4 menu's order too.
                    { if (it.second.inTaskTree) 0 else 1 },
                    { it.second.title.lowercase() },
                    { it.second.shownPath.size },
                    { pathLabel(it.second.shownPath) },
                ),
            )
            .map { it.second }
            .toList()
    }

    /**
     * A row of any other kind: the thing's name and one detail that tells two of the same name apart. [id] is
     * what the window's gestures act on — the category id, the kind itself, the alarm / timer / reminder id.
     */
    data class ItemResult(
        override val kind: Kind,
        val id: String,
        override val name: String,
        val detail: String,
    ) : Result

    /**
     * User rule 2026-11-04: **a row's detail as drawn**: a workplace setup still being applied says "running", as a timer
     * does. Its run lives in memory (`WorkplaceRuns`), never in the state, so it is added where the row is drawn.
     */
    fun shownDetail(item: ItemResult, workplaceRunning: (String) -> Boolean): String =
        if (item.kind == Kind.WorkplaceSetup && workplaceRunning(item.id)) item.detail + " · running" else item.detail

    /** An md element's row detail (user rule 2026-11-19): what it is associated with, by the titles of their rows. */
    private fun mdDetail(state: SchedulerState, md: MdElement): String {
        if (md.of.isEmpty()) return "associated with nothing"
        val names = md.of.take(3).map { key ->
            val id = key.substringAfter('/')
            when (key.substringBefore('/')) {
                Kind.Task.name -> state.tasks[org.example.project.scheduler.model.TaskId(id)]?.title
                Kind.Md.name -> state.mdElements.firstOrNull { it.id == id }?.let(MdDomain::title)
                else -> null
            } ?: id
        }
        return "with " + names.joinToString(", ") + if (md.of.size > 3) " and ${md.of.size - 3} more" else ""
    }

    fun itemResults(
        state: SchedulerState,
        kind: Kind,
        query: String,
        /** For the history units' date and time: the device's own. */
        timeZone: TimeZone = TimeZone.currentSystemDefault(),
        /** The app's windows, for [Kind.Window] — `App`'s to know, not the state's. */
        windows: List<WindowEntry> = emptyList(),
        /** One row per window TYPE ([WindowDuplicates.Hidden]) rather than one per window. */
        windowTypesOnly: Boolean = false,
        /** The scheduler engine's runs, for [Kind.HistoryUnit] — the view model's to know (they are kept in memory only). */
        schedulerRuns: List<org.example.project.scheduler.state.SchedulerRunEntry> = emptyList(),
        /** The Claude Code logs as last read — for the logs' app setting's detail ([AppSettingEntry.ClaudeLogs]). */
        claudeLogs: ClaudeLogCatalog = ClaudeLogCatalog.EMPTY,
    ): List<ItemResult> {
        val items =
            when (kind) {
                // A task is a row of its own ([taskResults]); so is a log entry, searched in its whole text ([claudeResults]).
                Kind.Task, Kind.ClaudePrompt, Kind.ClaudeAnswer, Kind.ClaudeReasoning -> emptyList()
                Kind.Category -> state.categories.map { category ->
                    val carriers = state.tasks.values.count { category.id in it.categoryIds }
                    val rules = category.rules.size
                    ItemResult(
                        kind,
                        category.id.value,
                        category.title,
                        plural(carriers, "task") + " · " + plural(rules, "rule"),
                    )
                }
                Kind.RestrictivePeriod -> state.allPeriodKinds.map { periodKind ->
                    val placed = state.panels.count { it.periodKind == periodKind }
                    val origin = if (PeriodKinds.isUserDefined(periodKind)) "your kind" else "default period"
                    ItemResult(kind, periodKind, periodKind, origin + " · " + plural(placed, "period") + " placed")
                }
                Kind.Alarm -> state.alarms.map { alarm ->
                    val detail = hhmm(alarm.timeOfDayMinutes) + if (alarm.enabled) "" else " · off"
                    ItemResult(kind, alarm.id, alarm.label.ifBlank { "Alarm" }, detail)
                }
                Kind.Timer -> state.timers.map { timer ->
                    val detail =
                        TimerDomain.formatDuration(timer.durationSeconds) +
                            when {
                                timer.running -> " · running"
                                timer.paused -> " · paused"
                                else -> ""
                            }
                    ItemResult(kind, timer.id, timer.label.ifBlank { "Timer" }, detail)
                }
                Kind.WorkplaceSetup -> state.workplaceSetups.map { setup ->
                    ItemResult(kind, setup.id, setup.title.ifBlank { "Workplace setup" }, WorkplaceDomain.detail(setup))
                }
                Kind.Md -> state.mdElements.map { md ->
                    ItemResult(kind, md.id, MdDomain.title(md).ifBlank { "Md element" }, mdDetail(state, md))
                }
                Kind.PresenceEvolution, Kind.MinimumEvolution -> PresenceEvolutions.shown(state, evolutionTargetOf(kind)).map { e ->
                    ItemResult(kind, e.id, PresenceEvolutions.title(state, e), PresenceEvolutions.detail(state, e))
                }
                Kind.Chrono -> state.chronos.map { chrono ->
                    val detail = when {
                        chrono.running -> "running"
                        chrono.paused -> "paused"
                        else -> "idle"
                    }
                    ItemResult(kind, chrono.id, chrono.label.ifBlank { "Chrono" }, detail)
                }
                Kind.Reminder -> state.chores.map { chore ->
                    ItemResult(kind, chore.id.ifEmpty { chore.title }, chore.title, reminderDetail(chore))
                }
                // The detail is what the quota IS (its amount and its loop), never where it stands: a result row is
                // not redrawn as time passes.
                Kind.Quota -> state.quotas.map { quota ->
                    val amount = (formatQuotaNumber(quota.amount) + " " + quota.unit).trim()
                    val loop = dateTime(quota.startMillis, timeZone) + " → " + dateTime(quota.endMillis, timeZone)
                    ItemResult(kind, quota.id, quota.title.ifBlank { "Quota" }, amount + " · " + loop + if (quota.repeats) " · repeats" else "")
                }
                // Every stack's units, as the History window lists them. The id is the unit's IDENTITY
                // ([historyUnitId]), which is what [historyUnitOf] reads back for the filters.
                Kind.HistoryUnit -> HistoryCategory.entries.flatMap { category ->
                    state.histories.forCategory(category).units.map { unit ->
                        val where = unit.window?.label ?: category.name
                        val undone = if (unit.undone) " · undone" else ""
                        ItemResult(kind, historyUnitId(category, unit), unit.delta.label, where + " · " + dateTime(unit.timeMillis, timeZone) + undone)
                    }
                } + schedulerRuns.map { run ->
                    // The scheduler engine's own entries: each set of rules it found, named by the event it was. The
                    // rule state it read and the rules it returned are the "Information" action's to show.
                    ItemResult(
                        kind,
                        schedulerRunId(run),
                        run.kind.label,
                        SCHEDULER_ENGINE_LABEL + " · " + dateTime(run.timeMillis, timeZone) + " · " + plural(run.rules.size, "rule"),
                    )
                }
                // The detail is where the block is: two blocks of one element are told apart by it, and it orders them.
                Kind.CalendarBlock -> calendarBlocks(state, timeZone).map { block ->
                    val span = dateTime(block.startMillis, timeZone) +
                        if (block.endMillis > block.startMillis) " → " + dateTime(block.endMillis, timeZone) else ""
                    ItemResult(kind, block.id, block.name, span)
                }
                Kind.Notification -> notificationIds(state.notificationLog).let { ids -> state.notificationLog.mapIndexed { index, entry ->
                    val said = entry.spoken?.takeIf { it.isNotBlank() && it != entry.message }?.let { " · said: $it" }.orEmpty()
                    ItemResult(
                        kind,
                        ids[index],
                        entry.title.ifBlank { "Notification" },
                        listOf(entry.message, dateTime(entry.timeMillis, timeZone)).filter { it.isNotBlank() }.joinToString(" · ") + said,
                    )
                } }
                // The Task relations window's own rows — the same pairs, the same sections, never a second reading.
                Kind.TaskRelation -> TaskRelationsDomain.rows(state).map { row ->
                    ItemResult(
                        kind,
                        relationId(row.key),
                        row.taskTitle + " in " + row.targetTitle,
                        relationSectionLabel(row.section) + (row.broken?.let { " · " + brokenLabel(it) } ?: ""),
                    )
                }
                Kind.Shortcut -> GlobalShortcut.entries.map { shortcut ->
                    val binding = state.shortcutBindings[shortcut] ?: shortcut.defaultBinding
                    val rebound = binding != shortcut.defaultBinding
                    ItemResult(kind, shortcut.name, shortcut.action, binding.chord + if (rebound) " · rebound" else "")
                } + windows.mapNotNull { entry ->
                    // The chords the user mapped to a window: each an element of its own, made with "New".
                    val mapping = entry.mapping ?: return@mapNotNull null
                    ItemResult(
                        kind, WINDOW_SHORTCUT_PREFIX + entry.id,
                        "Window: " + (mapping.windowTitle ?: "none chosen yet"), mapping.chord ?: "no chord yet",
                    )
                }
                // Each instance its own row — two copies of Search are two rows; the detail is where it stands.
                Kind.Window ->
                    windows.filter { it.mapping == null }.let { real ->
                        if (windowTypesOnly) {
                            windowTypeRows(real)
                        } else {
                            real.filterNot { it.placeholder }.map { window -> ItemResult(kind, window.id, window.title, window.status.label) }
                        }
                    }
                Kind.Creation -> CREATABLE.map { made ->
                    ItemResult(kind, made.name, "New " + made.label, creationDetail(made))
                }
                Kind.AppSetting -> AppSettingEntry.entries.map { setting ->
                    ItemResult(kind, setting.name, setting.title, appSettingDetail(state, setting, claudeLogs))
                }
                Kind.Calendar -> listOf(calendarResult())
                // Every configuration of a Search window, named by its label, the type it is about beside it.
                // Only what a drop-down gives: the account holds none ([optionResults]).
                Kind.Option -> emptyList()
                // Only what the field of a number gives ([integerResults]).
                Kind.Integer -> emptyList()
                // Only what the actions section gives its Search window ([actionListRows]).
                Kind.Action -> emptyList()
                Kind.SearchConfiguration -> configurationCategories().map { node ->
                    // Navigable configuration entries come first: hovering one opens its derived children.
                    ItemResult(kind, CONFIGURATION_NODE_PREFIX + node.id, node.label, SettingRole.Group.label)
                } + Setting.entries.map { setting ->
                    // ...and its category ([SettingRole]).
                    ItemResult(kind, setting.name, setting.label, listOfNotNull(setting.section?.label ?: "general", roleOf(setting)?.label).joinToString(" · "))
                }
            }
        // An md element is found by its whole text, after what its title matches (user rule 2026-11-19).
        val mdTexts = if (kind == Kind.Md && query.isNotBlank()) state.mdElements.associate { it.id to it.text } else emptyMap()
        return items
            .mapNotNull { item ->
                (matchRank(item.name, query) ?: mdTexts[item.id]?.takeIf { it.contains(query.trim(), ignoreCase = true) }?.let { Int.MAX_VALUE })
                    ?.let { it to item }
            }
            .sortedWith(compareBy({ it.second.kind != Kind.SearchConfiguration || !it.second.id.startsWith(CONFIGURATION_NODE_PREFIX) }, { it.first }, { it.second.name.lowercase() }, { it.second.detail }))
            .map { it.second }
    }

    /**
     * The rows of one of the Claude Code logs' kinds ([CLAUDE_KINDS]): the entries of that kind whose WHOLE text holds
     * every term of [query] ([ClaudeLogSearch]), within the logs' filters, newest first. Named by their first line —
     * the rest is the actions' to show ([AddedAction.PromptText]…) — and told apart by when, in which project and which
     * session they were written.
     */
    fun claudeResults(
        kind: Kind,
        query: String,
        claudeLogs: ClaudeLogCatalog,
        filters: Filters = Filters(),
        timeZone: TimeZone = TimeZone.currentSystemDefault(),
    ): List<ItemResult> {
        val logKind = claudeLogKindOf(kind) ?: return emptyList()
        // Every row of the kind, newest first, made once for these logs: a keystroke only filters them.
        val rows =
            claudeLogs.memo(kind to timeZone) {
                claudeLogs.entries
                    .filter { it.second.kind == logKind }
                    .sortedByDescending { it.second.timeMillis ?: Long.MIN_VALUE }
                    .map { (session, entry) ->
                        val firstLine = entry.text.lineSequence().firstOrNull { it.isNotBlank() }.orEmpty().trim()
                        val detail =
                            listOfNotNull(
                                entry.timeMillis?.let { dateTime(it, timeZone) },
                                projectName(session.project),
                                session.title,
                            ).joinToString(" · ")
                        Triple(session, entry, ItemResult(kind, ClaudeLogCatalog.idOf(session, entry), firstLine.take(CLAUDE_NAME_LENGTH), detail))
                    }
            }
        val terms = ClaudeLogSearch.terms(query)
        return rows.mapNotNull { (session, entry, row) ->
            row.takeIf {
                (filters.claudeProject == null || session.project == filters.claudeProject) &&
                    (filters.claudeFrom == null || (entry.timeMillis ?: Long.MIN_VALUE) >= filters.claudeFrom) &&
                    (filters.claudeUntil == null || (entry.timeMillis ?: Long.MAX_VALUE) <= filters.claudeUntil) &&
                    ClaudeLogSearch.matches(entry, terms)
            }
        }
    }

    /** A project by the last folder of its path, as a row names it. */
    fun projectName(project: String): String = project.trimEnd('/', BACKSLASH).substringAfterLast('/').substringAfterLast(BACKSLASH)

    private const val BACKSLASH: Char = '\u005C'

    /** How much of an entry's first line its row is named by. */
    private const val CLAUDE_NAME_LENGTH = 200

    /** One row of the result list, whatever its kind. */
    sealed interface Result {
        val kind: Kind
        val name: String
    }

    /**
     * The result list for every kind in [kinds] (the drop-down's checked boxes): the same name first, then
     * names starting with the query, then the rest — across kinds, so an exact match is never buried under a
     * kind listed before it. Within one tier the kinds keep the drop-down's order, and each kind its own order
     * ([taskResults], [itemResults]). [allPaths] is read only when [Kind.Task] is checked.
     *
     * That is the base [sorts] are applied to, and so the last word on anything they leave tied. They are
     * applied from the least dominant up, each stably ([SortMethod]).
     */
    fun results(
        state: SchedulerState,
        kinds: Set<Kind>,
        query: String,
        allPaths: () -> Map<TaskId, List<List<String>>> = { allPathsInAnyTree(state) },
        filters: Filters = Filters(),
        sorts: List<SortMethod> = DEFAULT_SORTS,
        /** The app's windows, read only when [Kind.Window] is checked ([WindowEntry]). */
        windows: List<WindowEntry> = emptyList(),
        /** For the calendar filters' days: the device's own. */
        timeZone: TimeZone = TimeZone.currentSystemDefault(),
        /** The clock's instant: whether a timer can still end at the calendar filter's position ([calendarAddable]). */
        nowMillis: Long = 0L,
        /**
         * The kinds of the calendar's LAYER bands at an instant — the hatch read off the lock history, which is not in
         * the state — for the "is on the calendar at" filter ([calendarElementsAt]). `App` holds them.
         */
        layerKindsAt: (Long) -> Set<String> = { emptySet() },
        /** The scheduler engine's runs, read only when [Kind.HistoryUnit] is checked ([itemResults]). */
        schedulerRuns: List<org.example.project.scheduler.state.SchedulerRunEntry> = emptyList(),
        /**
         * Where "Add to the calendar" would end what it lays ([Config.placement]) — what the calendar filter's
         * "without removing anything" state lays to see what it removes ([calendarAddKeepsEverything]). Null: an hour.
         */
        placement: Placement? = null,
        /** The added elements' own length ([placementDefaultLength]), which an unstated [placement] length is. */
        placementOwnLength: Long? = null,
        /** The Claude Code logs as last read, for the logs' kinds ([CLAUDE_KINDS]) — `App` reads them. */
        claudeLogs: ClaudeLogCatalog = ClaudeLogCatalog.EMPTY,
        /** The global root among the tasks: a picker that can pick it ("add a parent", user rule 2026-11-13). */
        withRoot: Boolean = false,
        /** The search bar's match case, whole word and regular expression ([Filters.queryOptions]). */
        match: TaskTreeSearch.Options = TaskTreeSearch.Options(),
        /** User rule 2026-11-06: the bars added to [query] and their formula ([Config.extraQueries], [SearchBarFormula]). */
        extraQueries: List<String> = emptyList(),
        queryFormula: String = "",
        /**
         * Anomaly 2026-11-14 (*"Sorting and filters from the ▾ of the added elements list do nothing"*): **the rows of a
         * list that is given, not searched** — the added elements — in the order given. They go through everything a
         * searched list does (the kinds checked, the bar, the filters, the sorting): one funnel, so a list of given rows
         * cannot answer its configuration differently. Null: the account is searched.
         */
        given: List<Result>? = null,
        /**
         * User rule 2026-11-16: **the search configurations as a tree lays them out** for the window it configures — its
         * "Recent", "Currently on"… ([configurationTree]) — in place of the ones every window has ([itemResults]), with
         * what each one holds ([ConfigurationRows.childrenOf]). Null: those every window has.
         */
        configurationRows: ConfigurationRows? = null,
    ): List<Result> {
        val calendar = if (filters.readsCalendar) CalendarBoxes(state, timeZone) else null
        // Several bars, or a regular expression: every row of the kinds is read, and the bars decide ([barsMatch]). One
        // plain bar keeps the kinds' own search, which also ranks what it finds.
        val bars = listOf(query) + extraQueries
        val severalBars = extraQueries.any { it.isNotBlank() }
        // User rule 2026-11-16: bars read against more than the names ([Filters.searchIn]) read every row of the kinds too.
        val whereWide = !filters.searchesNamesOnly
        val kindQuery = if (severalBars || match.regex || whereWide) "" else query
        val base =
            (given?.filter { it.kind in kinds && (kindQuery.isBlank() || matchRank(it.name, kindQuery) != null) }
                ?: Kind.entries
                .filter { it in kinds }
                .flatMap { kind ->
                    if (kind == Kind.SearchConfiguration && configurationRows != null) {
                        return@flatMap configurationRows.rows.filter { kindQuery.isBlank() || matchRank(it.name, kindQuery) != null }
                    }
                    kindRows(
                        state, kind, kindQuery, allPaths, windows, schedulerRuns, claudeLogs, filters, timeZone,
                        // The global root is offered where it can be what is asked: what has no task parent, and the
                        // parents of some elements (anomaly 2026-11-14: a tree's root listed no parent) — the filter
                        // below keeps it only where it is one.
                        withRoot = withRoot || Kind.Task in filters.noParentOfKinds || filters.parentsOf.isNotEmpty(),
                        windowTypesOnly = filters.windowDuplicates == WindowDuplicates.Hidden,
                    )
                }
                // A given list holds what it was given: no row offering to make an element.
                .let { rows -> rows + withCreatedKindRows(state, kinds, query, filters) })
                .let { rows ->
                    when {
                        whereWide -> {
                            // The children a row's are read once each, at the distance asked ([fieldsMatch]).
                            val children = HashMap<String, List<Result>>()
                            val childrenOf = { row: Result ->
                                children.getOrPut(keyOf(row)) {
                                    val tree = configurationRows?.takeIf { row.kind == Kind.SearchConfiguration }
                                    if (tree != null) {
                                        rowsBelow(listOf(row), filters.childDistance, ::keyOf) { tree.childrenOf((it as ItemResult).id) }
                                    } else {
                                        val keys = rowsBelow(listOf(keyOf(row)), filters.childDistance, { it }) { ElementTree.childrenOf(state, it, claudeLogs) }
                                        resolve(state, keys, allPaths, windows, schedulerRuns, claudeLogs)
                                    }
                                }
                            }
                            val answers = { row: Result, bar: String -> fieldsMatch(row, bar, filters, childrenOf, claudeLogs) }
                            if (severalBars) rows.filter { row -> SearchBarFormula.evaluate(queryFormula, bars.size) { i -> answers(row, bars[i]) } }
                            else rows.filter { answers(it, query) }
                        }
                        severalBars -> rows.filter { row -> SearchBarFormula.evaluate(queryFormula, bars.size) { i -> barMatches(row, bars[i], match, claudeLogs) } }
                        match == TaskTreeSearch.Options() || query.isBlank() -> rows
                        else -> rows.filter { matchesWith(it, query, match, claudeLogs) }
                    }
                }
                .let { rows ->
                    // Anomaly 2026-11-15: a filter of a type keeps that type's elements alone.
                    val only = filters.onlyKinds ?: return@let rows
                    rows.filter { it.kind in only || it.kind == Kind.Creation }
                }
                .filter { passes(state, it, filters, calendar) }
                .let { rows ->
                    // User rule 2026-11-12: no parent of the checked kinds — every element's parents read once.
                    // ...and only the parents of the given elements.
                    if (filters.noParentOfKinds.isEmpty() && filters.parentsOf.isEmpty()) return@let rows
                    val parents = ElementTree.parentIndex(state)
                    // ...as far above them as the parent distance says.
                    val wanted =
                        if (filters.parentsOf.isEmpty()) null
                        else ElementTree.parentsWithin(parents, filters.parentsOf, filters.parentDistance.takeIf { it != PARENT_DISTANCE_HIGHEST })
                    rows.filter { row ->
                        val key = keyOf(row)
                        (wanted == null || key in wanted) && parents[key].orEmpty().none { ElementTree.kindOf(it) in filters.noParentOfKinds }
                    }
                }
                .let { rows ->
                    // The blocks of the given elements: each block's element read once, not once per row.
                    if (filters.blocksOf.isEmpty() || rows.none { it.kind == Kind.CalendarBlock }) return@let rows
                    val owners = calendarBlocks(state, timeZone).associate { it.id to it.owner }
                    rows.filter { it.kind != Kind.CalendarBlock || owners[(it as? ItemResult)?.id] in filters.blocksOf }
                }
                .let { rows ->
                    // The calendar filter: about every kind, so asked of every row, once the instant's kinds are read.
                    val at = filters.calendarAddAt ?: return@let rows
                    val addable = rows.filter { calendarAddable(state, it, at, nowMillis) }
                    if (!filters.calendarAddKeeping) return@let addable
                    // The stricter state: a task the periods there refuse could only stand by removing them
                    // ([calendarTaskStandsIn]), and what the add itself would leave in place is asked of the add's
                    // own reducer.
                    val kindsAt = calendarKindsAt(state, at)
                    val end = placementEnd(placement ?: Placement(), at, placementOwnLength)
                    val around = calendarAddSurroundings(state, at, end)
                    addable.filter {
                        calendarTaskStandsIn(state, it, kindsAt) && calendarAddKeepsEverything(around, it, at, end, timeZone)
                    }
                }
                .let { rows ->
                    // The "is on the calendar at" filter: the keys of what is there, read once.
                    val at = filters.calendarAt ?: return@let rows
                    val there = calendarElementsAt(state, at, timeZone, layerKindsAt)
                    rows.filter { keyOf(it) in there }
                }
                .let { rows ->
                    // User rule 2026-11-02: last, since it overrides all of the above. An included element stands in the
                    // list whatever kept it out; one the search does not find goes after those it does ([matchRank]).
                    val excluded = filters.excludedElements
                    val included = filters.includedElements
                    if (excluded.isEmpty() && included.isEmpty()) return@let rows
                    val kept = rows.filterNot { keyOf(it) in excluded && keyOf(it) !in included }
                    val listed = kept.mapTo(HashSet(), ::keyOf)
                    kept + resolve(state, included.filterNot { it in listed }.sorted(), allPaths, windows, schedulerRuns, claudeLogs)
                }
                // Stable: ties keep the kind order and each kind's own order.
                .sortedBy { matchRank(it.name, query) ?: Int.MAX_VALUE }
        if (sorts == DEFAULT_SORTS) return base
        // User rule 2026-11-16, "tree order": the place of each row in the tree it comes from.
        val treeOrder = (given ?: configurationRows?.rows)?.withIndex()?.associate { (i, row) -> keyOf(row) to i }.orEmpty()
        val keys = SortValues(state, query, treeOrder, filters.involvementOf)
        var rows = base
        for (method in sorts.asReversed()) {
            val kind = method.kind
            if (kind == null) {
                rows = sortedBy(rows, method, keys)
                continue
            }
            // Only this kind's rows move, and only into the places this kind's rows held.
            val places = rows.indices.filter { rows[it].kind == kind }
            if (places.size < 2) continue
            val sorted = sortedBy(places.map { rows[it] }, method, keys)
            rows = rows.toMutableList().also { list -> places.forEachIndexed { i, place -> list[place] = sorted[i] } }
        }
        return rows
    }

    /**
     * User rule 2026-10-30: **a creation row has two types** — the creation kind, and the kind of the element it makes.
     * So a search of the kinds [kinds] lists the creation rows of the checked kinds even with the creation kind itself
     * unchecked (with it checked, [itemResults] lists every one already, and none twice). Each is the very row the
     * creation kind lists, matched against [query] like it — and not listed by a kind one of whose filters is on: the
     * element it would make is none of those the filter keeps.
     */
    private fun withCreatedKindRows(state: SchedulerState, kinds: Set<Kind>, query: String, filters: Filters): List<Result> {
        if (Kind.Creation in kinds) return emptyList()
        val made = CREATABLE.filter { kind -> kind in kinds && Setting.entries.none { it.section == kind && filters.isOn(it) } }
        if (made.isEmpty()) return emptyList()
        return itemResults(state, Kind.Creation, query).filter { row -> made.any { it.name == row.id } }
    }

    /**
     * **The Search window's list** (user rule 2026-10-06): [rows] — and, where ONE kind alone is checked and nothing of
     * it is found, that kind's "creation" row, so a search that finds nothing offers to make the thing looked for. The
     * row is the very one the "creation" kind lists ([itemResults]), opened and added like it. A kind the user cannot
     * make (a history unit, a notification, a shortcut…) has none, and its list stays empty; so does a list of several
     * kinds, where there is no one thing to make.
     */
    /** User rule 2026-11-04: a task cell's Edit Mode lists its tasks by title ([pickerRows]). */
    val PICKER_SORTS: List<SortMethod> = listOf(SortMethod(null, SortKey.Name))

    /**
     * User rule 2026-11-04: **the list of the Search window a task cell's Edit Mode shows** (`SearchPicker`): the task
     * elements of [found] the cell may take ([eligible]), in [found]'s order (by their title, [PICKER_SORTS]), and,
     * where it can change task ([creation]), the task kind's creation row first, whatever is typed: its click gives the
     * cell a new task of the same title, a task row's gives it that task.
     */
    fun pickerRows(state: SchedulerState, found: List<Result>, eligible: Set<TaskId>, creation: Boolean): List<Result> {
        val tasks = found.filter { it is TaskResult && it.taskId in eligible }
        if (!creation) return tasks
        return itemResults(state, Kind.Creation, "").filter { it.id == Kind.Task.name } + tasks
    }

    fun withCreationWhenEmpty(state: SchedulerState, rows: List<Result>, kinds: Set<Kind>): List<Result> {
        if (rows.isNotEmpty()) return rows
        val kind = kinds.singleOrNull()?.takeIf { it in CREATABLE } ?: return rows
        return itemResults(state, Kind.Creation, "").filter { it.id == kind.name }
    }

    /**
     * [rows] stably ordered by [sort]. Each row's key is read once (a comparison would read it again per pair),
     * and a row without a value for the key — a task no live cell holds has no priority, a tree no date —
     * goes last in either direction.
     */
    private fun sortedBy(rows: List<Result>, sort: SortMethod, keys: SortValues): List<Result> {
        if (rows.size < 2) return rows
        val keyed = rows.map { it to keys.of(it, sort.key) }
        @Suppress("UNCHECKED_CAST")
        val order = Comparator<Pair<Result, Comparable<*>?>> { (_, a), (_, b) ->
            when {
                a == null && b == null -> 0
                a == null -> 1
                b == null -> -1
                else -> (a as Comparable<Any>).compareTo(b).let { if (sort.descending) -it else it }
            }
        }
        return keyed.sortedWith(order).map { it.first }
    }

    /**
     * A row's value under each [SortKey]. What more than one row reads — the priorities, the per-category and
     * per-kind counts — is computed at most once per [results], and only when a sort asks for it: none of it is
     * read under the default order.
     */
    private class SortValues(
        private val state: SchedulerState,
        private val query: String,
        private val treeOrder: Map<String, Int> = emptyMap(),
        private val involvementOf: Set<String> = emptySet(),
    ) {
        private val involvement by lazy {
            val tasks = involvementOf.mapNotNull { key -> key.takeIf { it.startsWith(Kind.Task.name + "/") }?.substringAfter('/')?.let(::TaskId) }
            val rank = HashMap<String, Int>()
            PresenceEvolutions.involvementGroups(state, tasks).forEachIndexed { i, g -> for (id in g) if (id !in rank) rank[id] = i }
            rank
        }
        private val minimumInvolvement by lazy {
            val tasks = involvementOf.mapNotNull { key -> key.takeIf { it.startsWith(Kind.Task.name + "/") }?.substringAfter('/')?.let(::TaskId) }
            val rank = HashMap<String, Int>()
            PresenceEvolutions.involvementGroups(state, tasks, EvolutionTarget.Minimum).forEachIndexed { i, g -> for (id in g) if (id !in rank) rank[id] = i }
            rank
        }
        private val priorities by lazy { SchedulerDomain.absoluteTaskPriorities(state) }
        private val carriers by lazy {
            state.tasks.values.flatMap { it.categoryIds }.groupingBy { it }.eachCount()
        }
        private val placed by lazy { state.panels.groupingBy { it.periodKind }.eachCount() }
        private val alarms by lazy { state.alarms.associateBy { it.id } }
        private val timers by lazy { state.timers.associateBy { it.id } }
        private val chores by lazy { state.chores.associateBy { it.id.ifEmpty { it.title } } }
        private val blocks by lazy { calendarBlocks(state).associateBy { it.id } }
        private val sectionRank by lazy {
            TaskRelationsDomain.Section.entries.associate { relationSectionLabel(it) to it.ordinal }
        }

        fun of(row: Result, key: SortKey): Comparable<*>? =
            when (key) {
                SortKey.Relevance -> matchRank(row.name, query) ?: Int.MAX_VALUE
                SortKey.Name -> row.name.lowercase()
                SortKey.Type -> row.kind.ordinal
                SortKey.CalendarBubble -> CalendarBubbleRank.of(row.kind, (row as? ItemResult)?.id)
                SortKey.CreationFirst -> if (row.kind == Kind.Creation) 0 else 1
                SortKey.TreeOrder -> treeOrder[keyOf(row)]
                SortKey.TaskPriority -> (row as? TaskResult)?.let { priorities[it.taskId] }
                SortKey.TaskPathLength -> (row as? TaskResult)?.takeIf { it.paths.isNotEmpty() }?.shownPath?.size
                SortKey.TaskPathCount -> (row as? TaskResult)?.paths?.size
                // Ascending: what a tree holds first, as the default order has it.
                SortKey.TaskInTree -> (row as? TaskResult)?.let { if (it.inTaskTree) 0 else 1 }
                else -> (row as? ItemResult)?.let { itemValue(it, key) }
            }

        private fun itemValue(row: ItemResult, key: SortKey): Comparable<*>? =
            when (key) {
                SortKey.CategoryTasks -> carriers[CategoryId(row.id)] ?: 0
                SortKey.CategoryRules -> state.categoryById(CategoryId(row.id))?.rules?.size
                SortKey.PeriodsPlaced -> placed[row.id] ?: 0
                SortKey.AlarmTime -> alarms[row.id]?.timeOfDayMinutes
                SortKey.TimerDuration -> timers[row.id]?.durationSeconds
                SortKey.ReminderTime -> chores[row.id]?.timeOfDayMinutes
                SortKey.ReminderCadence -> chores[row.id]?.spanDays
                SortKey.BlockStart -> blocks[row.id]?.startMillis
                SortKey.HistoryDate -> schedulerRunTimeOf(row.id) ?: historyUnitOf(state, row.id)?.timeMillis
                SortKey.NotificationDate -> notificationTimeOf(row.id)
                SortKey.RelationSection -> sectionRank[row.detail.substringBefore(" · ")]
                SortKey.ShortcutChord -> row.detail.substringBefore(" · ").lowercase()
                SortKey.WindowState -> windowStatusOf(row)?.ordinal
                SortKey.EvolutionInvolvement -> row.takeIf { it.kind == Kind.PresenceEvolution }?.let { involvement[it.id] ?: 2 }
                SortKey.MinimumInvolvement -> row.takeIf { it.kind == Kind.MinimumEvolution }?.let { minimumInvolvement[it.id] ?: 2 }
                SortKey.Date -> (row as? ItemResult)?.takeIf { it.kind in CLAUDE_KINDS }?.id?.let(ClaudeLogCatalog::timeOf)
                else -> null
            }
    }

    /**
     * Whether [result] passes its own kind's [filters]. Another kind's filters do not read it — they keep it out
     * altogether, before this ([Filters.onlyKinds]).
     */
    private fun passes(state: SchedulerState, result: Result, filters: Filters, calendar: CalendarBoxes?): Boolean {
        fun tri(value: Tri, actual: Boolean) = value == Tri.Any || (value == Tri.Yes) == actual
        return when (result) {
            is TaskResult -> {
                val task = state.tasks[result.taskId]
                tri(filters.taskInTree, result.inTaskTree) &&
                    tri(filters.taskTreeRoot, result.taskId in result.treeRoots) &&
                    (filters.taskSchedulable == Tri.Any ||
                        tri(filters.taskSchedulable, SchedulerDomain.isPlaceableTask(state, result.taskId))) &&
                    (filters.taskCategory == null || task?.categoryIds?.contains(filters.taskCategory) == true) &&
                    (calendar == null ||
                        calendar.passes(
                            calendar.ofTask(result.taskId), filters.taskOnCalendar, filters.taskBoxesFrom, filters.taskBoxesUntil,
                            filters.calendarAt,
                        ))
            }
            is ItemResult -> when (result.kind) {
                Kind.Task, Kind.WorkplaceSetup, Kind.Md, Kind.PresenceEvolution, Kind.MinimumEvolution, Kind.SearchConfiguration, Kind.Option, Kind.Integer, Kind.Action -> true
                Kind.Category ->
                    tri(filters.categoryHasRules, state.categoryById(CategoryId(result.id))?.rules?.isNotEmpty() == true)
                Kind.RestrictivePeriod -> when (filters.periodOrigin) {
                    PeriodOrigin.Any -> true
                    PeriodOrigin.BuiltIn -> !PeriodKinds.isUserDefined(result.id)
                    PeriodOrigin.Yours -> PeriodKinds.isUserDefined(result.id)
                } && (filters.periodDrawing == null || state.periodKindConfig.drawing(result.id) == filters.periodDrawing) &&
                    // A task that is gone asks nothing: the filter then reads as off rather than emptying the list.
                    (filters.periodResilienceTask?.let { state.tasks[it] }
                        ?.let { task -> filters.periodResilience.holds(task.resilienceFor(result.id)) } ?: true) &&
                    (calendar == null ||
                    calendar.passes(
                        calendar.ofPeriodKind(result.id), filters.periodOnCalendar, filters.periodBoxesFrom, filters.periodBoxesUntil,
                        filters.calendarAt,
                    ))
                Kind.Alarm -> {
                    val alarm = state.alarms.firstOrNull { it.id == result.id } ?: return true
                    val onOff = when (filters.alarmState) {
                        AlarmState.Any -> true
                        AlarmState.On -> alarm.enabled
                        AlarmState.Off -> !alarm.enabled
                    }
                    onOff && (filters.alarmDays.isEmpty() || alarm.days.any { it in filters.alarmDays })
                }
                Kind.Timer -> {
                    val timer = state.timers.firstOrNull { it.id == result.id } ?: return true
                    when (filters.timerState) {
                        TimerState.Any -> true
                        TimerState.Idle -> timer.idle
                        TimerState.Running -> timer.running
                        TimerState.Paused -> timer.paused
                    }
                }
                Kind.Chrono -> {
                    val chrono = state.chronos.firstOrNull { it.id == result.id } ?: return true
                    when (filters.chronoState) {
                        TimerState.Any -> true
                        TimerState.Idle -> chrono.idle
                        TimerState.Running -> chrono.running
                        TimerState.Paused -> chrono.paused
                    }
                }
                Kind.Quota -> tri(filters.quotaRepeats, state.quotas.firstOrNull { it.id == result.id }?.repeats == true)
                Kind.Reminder -> {
                    val chore = state.chores.firstOrNull { it.id.ifEmpty { it.title } == result.id } ?: return true
                    when (filters.reminderRepeats) {
                        ReminderRepeats.Any -> true
                        ReminderRepeats.OneOff -> chore.spanDays <= 0.0
                        ReminderRepeats.Repeating -> chore.spanDays > 0.0
                    }
                }
                // The row's name IS the title it was posted under, which is what says where it came from.
                Kind.Notification ->
                    (filters.notificationsSinceMillis == null ||
                        (notificationTimeOf(result.id) ?: Long.MAX_VALUE) >= filters.notificationsSinceMillis) &&
                        (filters.notificationSources.isEmpty() || NotificationSource.of(result.name) in filters.notificationSources)
                Kind.HistoryUnit -> {
                    // A set of rules the scheduler engine found: kept by its own filter, and by none of a unit's — it
                    // was made in no window, belongs to no stack, changed no element and is never undone.
                    if (isSchedulerRunId(result.id)) {
                        return tri(filters.historyEngine, true) && filters.historyCategory == null &&
                            filters.historyWindow == null && filters.historyUndone != Tri.Yes &&
                            filters.historyChangedKinds.isEmpty() && filters.historyChangedElement == null
                    }
                    if (!tri(filters.historyEngine, false)) return false
                    val unit = historyUnitOf(state, result.id) ?: return true
                    (filters.historyCategory == null || result.id.substringBefore('#') == filters.historyCategory.name) &&
                        (filters.historyWindow == null || unit.window == filters.historyWindow) &&
                        tri(filters.historyUndone, unit.undone) &&
                        historyChangeMatches(unit.delta.changedElements, filters)
                }
                Kind.TaskRelation ->
                    filters.relationSection == null ||
                        result.detail.substringBefore(" · ") == relationSectionLabel(filters.relationSection)
                Kind.Shortcut -> {
                    val shortcut = GlobalShortcut.entries.firstOrNull { it.name == result.id } ?: return true
                    val binding = state.shortcutBindings[shortcut] ?: shortcut.defaultBinding
                    tri(filters.shortcutRebound, binding != shortcut.defaultBinding)
                }
                Kind.Window -> filters.windowStatus == null || windowStatusOf(result) == filters.windowStatus
                // [Filters.blocksOf] is asked of the whole list at once ([results]).
                // The logs' filters are asked as their rows are made ([claudeResults]).
                Kind.Creation, Kind.AppSetting, Kind.Calendar, Kind.CalendarBlock, Kind.ClaudePrompt, Kind.ClaudeAnswer,
                Kind.ClaudeReasoning -> true
            }
        }
    }

    /**
     * The app's settings the Search window lists ([Kind.AppSetting]) — each one an element whose actions are its
     * controls. Named by [name] in a row's id, so a new one is one more entry here.
     */
    enum class AppSettingEntry(val title: String) {
        /** How loud the app's own sounds are: [SchedulerState.soundVolume], the "Global volume" action's slider. */
        Sound("Sound setting"),
        /**
         * User rule 2026-10-08: the app's voice and its notifications, each a switch — the two the lateral menu holds
         * by default. The menu is the user's to empty, so they are elements too: found here, switched here, and put
         * back in the menu from here ([SchedulerState.notificationVoiceEnabled], [SchedulerState.notificationsEnabled]).
         */
        Voice("Voice"),
        Notifications("Notifications"),
        /** User rule 2026-10-18: where Claude Code's logs are read from ([ClaudeLogCatalog.directory]), this device's. */
        ClaudeLogs("Claude Code logs"),
        /** User rule 2026-10-18: every sound of the app off on this device ([SchedulerState.soundMuted]). */
        Mute("Mute all sound"),
        /** User rule 2026-11-01: how loud Claude Code's own sounds are, this device's. */
        ClaudeSounds("Claude Code sounds"),
        // User rule 2026-11-04: *"Every button that can appear in the left-side menu are links to actions that can be found
        // in the action section of the Search window."* Each control of the menu is a setting here, with ONE action, its
        // control ([APP_SETTING_CONTROL_ACTIONS]); the menu's item and that action are drawn by the same code.
        LookAwayNow("Look away now"),
        SwitchTask("Switch task"),
        SleepWork("Sleep / Work"),
        Away("I'm away"),
        Online("Online"),
        AutoSchedule("Auto schedule"),
        Reminders("Reminders"),
        ScreenBreaks("Screen breaks"),
        CalendarDisplay("Calendar display"),
        PlanTimeLimit("Allocated resources (q)"),
        MinimumTimeWeight("Minimum time weight"),
        SupabaseUsage("Supabase free tier"),
        MemoryLimits("Memory limits"),
        Server("Server"),
    }

    /**
     * The settings whose one action is a control of the lateral menu, drawn by the menu's own code (user rule 2026-11-04):
     * listed in the action section only where their setting is added.
     */
    val APP_SETTING_CONTROL_ACTIONS: Map<AddedAction, AppSettingEntry> by lazy {
        mapOf(
            AddedAction.LookAwayNowControl to AppSettingEntry.LookAwayNow,
            AddedAction.SwitchTaskControl to AppSettingEntry.SwitchTask,
            AddedAction.SleepWorkControl to AppSettingEntry.SleepWork,
            AddedAction.AwayControl to AppSettingEntry.Away,
            AddedAction.OnlineControl to AppSettingEntry.Online,
            AddedAction.AutoScheduleControl to AppSettingEntry.AutoSchedule,
            AddedAction.RemindersControl to AppSettingEntry.Reminders,
            AddedAction.ScreenBreaksControl to AppSettingEntry.ScreenBreaks,
            AddedAction.CalendarDisplayControl to AppSettingEntry.CalendarDisplay,
            AddedAction.PlanTimeLimitControl to AppSettingEntry.PlanTimeLimit,
            AddedAction.MinimumTimeWeightControl to AppSettingEntry.MinimumTimeWeight,
            AddedAction.SupabaseUsageControl to AppSettingEntry.SupabaseUsage,
            AddedAction.MemoryLimitsControl to AppSettingEntry.MemoryLimits,
            AddedAction.ServerControl to AppSettingEntry.Server,
        )
    }

    /** What an [AppSettingEntry]'s row says beside its name: where the setting stands. */
    private fun appSettingDetail(state: SchedulerState, setting: AppSettingEntry, claudeLogs: ClaudeLogCatalog): String =
        when (setting) {
            AppSettingEntry.LookAwayNow -> "starts the look-away break now"
            AppSettingEntry.SwitchTask -> "the task the line is on"
            AppSettingEntry.SleepWork -> "sleeping or working"
            AppSettingEntry.Away -> "this device's I'm away"
            AppSettingEntry.Online -> "online or working offline, and the account"
            AppSettingEntry.AutoSchedule -> if (state.automaticSchedule) "on" else "off"
            AppSettingEntry.Reminders -> if (state.showReminders) "shown" else "hidden"
            AppSettingEntry.ScreenBreaks -> if (state.showScreenBreaks) "shown" else "hidden"
            AppSettingEntry.CalendarDisplay -> "how the calendar is drawn"
            AppSettingEntry.PlanTimeLimit -> "how much the scheduler searches: 0 the fastest run, 1 the best plan the pace allows"
            AppSettingEntry.MinimumTimeWeight -> "the minimum times' weight in the score"
            AppSettingEntry.SupabaseUsage -> "the server's usage, read on Refresh"
            AppSettingEntry.MemoryLimits -> "the project database's and this device's"
            AppSettingEntry.Server -> "the Supabase project this device syncs with"
            AppSettingEntry.ClaudeLogs -> claudeLogs.directory.ifBlank { "no directory" }
            AppSettingEntry.Mute -> if (state.soundMuted) "muted on this device" else "sound on"
            AppSettingEntry.ClaudeSounds -> "how loud Claude Code is on this device"
            AppSettingEntry.Sound -> "volume " + volumePercent(state.soundVolume) + " %"
            AppSettingEntry.Voice -> if (state.notificationVoiceEnabled) "on" else "off"
            AppSettingEntry.Notifications -> if (state.notificationsEnabled) "on" else "off"
        }

    /** A volume in `0..1` as a whole percentage. */
    fun volumePercent(volume: Double): Int = kotlin.math.round(volume.coerceIn(0.0, 1.0) * 100).toInt()

    /** Whether the app setting [setting] is among the [added] elements — what enables its actions. */
    fun appSettingAdded(added: List<Result>, setting: AppSettingEntry): Boolean =
        added.any { it is ItemResult && it.kind == Kind.AppSetting && it.id == setting.name }

    /**
     * The calendar's boxes, gathered once per [results] and only when a calendar filter is on: a task's are
     * [SchedulerDomain.calendarBoxesOfTask]'s, a restrictive period kind's are the panels of that kind
     * ([TaskPanel.restrictiveKind], the one reading of a panel's kind).
     */
    /**
     * Whether [box] touches the window `[from, until]` of the calendar: it is still running at [from] (ends after it)
     * and has started by [until] — a null bound asks nothing on its side. With both bounds the same instant: the box
     * that instant is in.
     */
    fun boxInWindow(box: TaskTimeRange, from: Long?, until: Long?): Boolean =
        (from == null || box.endEpochMillis > from) && (until == null || box.startEpochMillis <= until)

    private class CalendarBoxes(private val state: SchedulerState, private val timeZone: TimeZone) {
        private val byTask by lazy { SchedulerDomain.calendarBoxesByTask(state) }
        private val byPeriodKind by lazy {
            state.panels.filter { it.isRestrictivePeriod }
                .groupBy({ it.restrictiveKind }) { TaskTimeRange(it.startEpochMillis, it.endEpochMillis) }
        }

        fun ofTask(taskId: TaskId): List<TaskTimeRange> = byTask[taskId].orEmpty()

        fun ofPeriodKind(kind: String): List<TaskTimeRange> = byPeriodKind[kind].orEmpty()

        /**
         * Whether [boxes] pass the three calendar filters: on the calendar at all, and — user rule 2026-10-11 —
         * **a box touching the window `[from, until]`** ([boxInWindow]).
         *
         * [vouchedAt] is the instant the "is on the calendar at" filter keeps rows for, when it is on: a row it has
         * kept IS on the timeline there, so where that instant lies in the window the row has a box in it — also
         * where the box is one the calendar derives and the state holds no panel for (a layer band, the idle time, a
         * screen break), which [boxes] cannot show. Without this the calendar's "edit…", which sets both bounds to the
         * right-click, would drop exactly those rows.
         */
        fun passes(boxes: List<TaskTimeRange>, onCalendar: Tri, from: Long?, until: Long?, vouchedAt: Long? = null): Boolean {
            if (onCalendar != Tri.Any && (onCalendar == Tri.Yes) != boxes.isNotEmpty()) return false
            if (from == null && until == null) return true
            if (vouchedAt != null && (from == null || from <= vouchedAt) && (until == null || vouchedAt <= until)) return true
            return boxes.any { boxInWindow(it, from, until) }
        }
    }

    /**
     * One row per window TYPE: its title, and where the type stands — open if any window of it is on screen,
     * minimized if every one is reduced, not open when none is.
     */
    private fun windowTypeRows(windows: List<WindowEntry>): List<ItemResult> =
        windows.groupBy { it.type }.map { (type, ofType) ->
            val real = ofType.filterNot { it.placeholder }.map { it.status }
            val status =
                when {
                    WindowStatus.Open in real -> WindowStatus.Open
                    WindowStatus.Minimized in real -> WindowStatus.Minimized
                    else -> WindowStatus.NotOpen
                }
            ItemResult(Kind.Window, WindowEntry.TYPE_PREFIX + type, ofType.first().typeTitle, status.label)
        }

    private fun creationDetail(kind: Kind): String =
        when (kind) {
            Kind.Window -> "a Search window of the window types"
            Kind.Shortcut -> "a chord for a window"
            else -> "added to this window's list"
        }

    /** A [Kind.Window] row's status, read back off its detail — the one thing the detail says. */
    private fun windowStatusOf(row: ItemResult): WindowStatus? = WindowStatus.entries.firstOrNull { it.label == row.detail }

    /** A quota's amount (or a factor) with no trailing zeros — `40`, `2.5`. */
    fun formatQuotaNumber(value: Double): String {
        val rounded = kotlin.math.round(value * 1000.0) / 1000.0
        return if (rounded == kotlin.math.floor(rounded) && kotlin.math.abs(rounded) < 1e15) rounded.toLong().toString() else rounded.toString()
    }

    /** The id of a task relation's row: its two task ids. */
    fun relationId(key: TaskRelationKey): String = key.taskId.value + "|" + key.relativeTo.value

    /**
     * A [Kind.HistoryUnit] row's id: its stack, its device and its number among that device's units of the stack
     * (`Main#<device>#<seq>`) — the unit's identity across devices, which never moves. It was the unit's INDEX in its
     * stack until 2026-10-03, and a full stack evicts from the front: every new unit (adding an element to a Search
     * window is one) shifted every index by one, so double-clicking the same row added it again under a new key, and
     * the old key named another unit.
     */
    fun historyUnitId(category: HistoryCategory, unit: HistoryUnit): String =
        category.name + "#" + unit.deviceId + "#" + unit.deviceSeq

    /** The history unit a [Kind.HistoryUnit] row's id names, or null — an index-shaped id of an older build names none. */
    fun historyUnitOf(state: SchedulerState, id: String): HistoryUnit? =
        historyUnitPlace(state, id)?.let { (category, index) -> state.histories.forCategory(category).units[index] }

    /** Where the unit [id] names sits now: its stack, and its index in it. */
    fun historyUnitPlace(state: SchedulerState, id: String): Pair<HistoryCategory, Int>? {
        val category = HistoryCategory.entries.firstOrNull { it.name == id.substringBefore('#') } ?: return null
        val rest = id.substringAfter('#')
        if ('#' !in rest) return null // an index-shaped id of an older build: it names no unit any more
        // One map per histories value, not a scan per row: the filters and the sort ask this for every row.
        val cached = unitPlaces
        val places =
            if (cached != null && cached.first === state.histories) cached.second
            else buildMap {
                for (c in HistoryCategory.entries) {
                    state.histories.forCategory(c).units.forEachIndexed { index, unit -> put(historyUnitId(c, unit), c to index) }
                }
            }.also { unitPlaces = state.histories to it }
        return places[id]?.takeIf { it.first == category }
    }

    /** A history unit's added-element key in the index shape of builds before 2026-10-03 (`HistoryUnit/Main#12`). */
    internal fun isLegacyHistoryUnitKey(key: String): Boolean =
        key.startsWith(Kind.HistoryUnit.name + "/") && key.count { it == '#' } == 1

    /** [historyUnitPlace]'s map, for the one histories value it was built from (replaced whole, never mutated). */
    private var unitPlaces: Pair<org.example.project.scheduler.state.SchedulerHistories, Map<String, Pair<HistoryCategory, Int>>>? = null

    fun relationSectionLabel(section: TaskRelationsDomain.Section): String =
        when (section) {
            TaskRelationsDomain.Section.Kept -> "kept"
            TaskRelationsDomain.Section.Edited -> "edited"
            TaskRelationsDomain.Section.Opened -> "opened"
            TaskRelationsDomain.Section.Broken -> "broken"
        }

    private fun brokenLabel(reason: TaskRelationsDomain.Break): String =
        when (reason) {
            TaskRelationsDomain.Break.TaskGone -> "the task is gone"
            TaskRelationsDomain.Break.TargetGone -> "the target is gone"
            TaskRelationsDomain.Break.Moved -> "no longer under it"
        }

    private fun dateTime(millis: Long, timeZone: TimeZone): String {
        val t = Instant.fromEpochMilliseconds(millis).toLocalDateTime(timeZone)
        fun two(n: Int) = n.toString().padStart(2, '0')
        return "${t.year}-${two(t.month.number)}-${two(t.day)} ${two(t.hour)}:${two(t.minute)}"
    }

    /**
     * The kinds of element a History Unit can change ([org.example.project.scheduler.state.changedElements]) — what the
     * "Changed element types" check boxes offer, in the drop-down's order.
     */
    val HISTORY_CHANGED_KINDS: List<Kind> =
        listOf(
            Kind.Task, Kind.Category, Kind.RestrictivePeriod, Kind.Alarm, Kind.Timer, Kind.Chrono, Kind.Quota, Kind.Reminder,
            Kind.Shortcut, Kind.Window, Kind.WorkplaceSetup, Kind.Md, Kind.PresenceEvolution, Kind.MinimumEvolution,
        )

    /** Whether a unit that changed [changed] passes the two "changed" filters: one of the checked kinds, the one element. */
    internal fun historyChangeMatches(changed: Set<ChangedElement>, filters: Filters): Boolean =
        (filters.historyChangedKinds.isEmpty() || changed.any { it.kind in filters.historyChangedKinds }) &&
            (filters.historyChangedElement == null || changed.any { it.key == filters.historyChangedElement })

    /** One identity row of the "Changed element" field: the element's Search key, and what the row reads. */
    data class ElementMenuRow(val key: String, val label: String)

    /** The "Changed element" field's two menus: the elements whose title (or id) IS what is typed, and the title suggestions. */
    data class ElementMenus(val identity: List<ElementMenuRow>, val titles: List<String>)

    /**
     * The "Changed element" field's menus for [text] — the ones a task cell shows, for every kind [kinds] checks (all of
     * [HISTORY_CHANGED_KINDS] when none is). Each kind answers with the readings its own fields use: a task with the
     * cell's id menu and title suggestions ([SchedulerDomain.taskIdentityMenuEntries], [SchedulerDomain.titleSuggestions]),
     * a reminder with the reminder editors' ([SchedulerDomain.reminderMenuEntries],
     * [SchedulerDomain.reminderTitleSuggestions]), and every other kind with the Search window's own rows of it
     * ([itemResults]): the rows whose name or id IS the text, and the names that contain it. The identity rows carry the
     * element's kind and id, since one menu lists several kinds. Titles are ordered as the cell orders them.
     */
    fun changedElementMenus(
        state: SchedulerState,
        kinds: Set<Kind>,
        text: String,
        windows: List<WindowEntry> = emptyList(),
    ): ElementMenus {
        val q = text.trim()
        val identity = ArrayList<ElementMenuRow>()
        val titles = ArrayList<String>()
        for (kind in HISTORY_CHANGED_KINDS.filter { kinds.isEmpty() || it in kinds }) {
            when (kind) {
                Kind.Task -> {
                    SchedulerDomain.taskIdentityMenuEntries(state, q).forEach { entry ->
                        entry.taskId?.let { identity += ElementMenuRow(taskKey(it), kind.label + ": " + entry.label + "  ·  " + it.value) }
                    }
                    titles += SchedulerDomain.titleSuggestions(state, text)
                }
                Kind.Reminder -> {
                    SchedulerDomain.reminderMenuEntries(state, q).forEach { entry ->
                        identity += ElementMenuRow(kind.name + "/" + entry.id, kind.label + ": " + entry.title + "  ·  " + entry.id)
                    }
                    titles += SchedulerDomain.reminderTitleSuggestions(state, text)
                }
                else -> {
                    val rows = itemResults(state, kind, "", windows = windows)
                    rows.filter { q.isNotEmpty() && (it.name.equals(q, ignoreCase = true) || it.id.equals(q, ignoreCase = true)) }
                        .forEach { identity += ElementMenuRow(keyOf(it), kind.label + ": " + it.name + "  ·  " + it.id) }
                    titles += rows.map { it.name }.filter { it.isNotBlank() && it != text && (q.isEmpty() || it.contains(q, ignoreCase = true)) }
                }
            }
        }
        val ordered = titles.distinct().sortedWith(compareByDescending<String> { SchedulerDomain.titleSimilarity(it, q) }.thenBy { it })
        return ElementMenus(identity, ordered)
    }

    /** What the "Changed element" field shows for the element [key] names: its name now, else the key itself. */
    fun changedElementTitle(state: SchedulerState, key: String, windows: List<WindowEntry> = emptyList()): String =
        resolve(state, listOf(key), windows = windows).firstOrNull()?.name ?: key

    /**
     * User rule 2026-11-16, "Where to search": **whether [text] answers the bar's [query]** — the one reading of a bar
     * against one part of a row ([fieldsMatch]): with a filter of the bar on, as [matchesWith] reads a name; else every
     * term of it, case aside, as one bar among several is read ([barMatches]).
     */
    fun textAnswers(text: String, query: String, match: TaskTreeSearch.Options): Boolean =
        if (match != TaskTreeSearch.Options()) TaskTreeSearch.ranges(text, matchPhrase(query, match), match).isNotEmpty()
        else ClaudeLogSearch.rawTerms(query).all { text.contains(it, ignoreCase = true) }

    /**
     * The texts of [row] the bars are matched against, by the parts [fields] checks ([SearchField]): its id (its key
     * without its kind), its name (a log entry's whole text), its description, its buttons.
     */
    fun searchTexts(row: Result, fields: Set<SearchField>, claudeLogs: ClaudeLogCatalog = ClaudeLogCatalog.EMPTY): List<String> =
        buildList {
            if (SearchField.Id in fields) add(keyOf(row).substringAfter('/'))
            if (SearchField.Name in fields) {
                val entry = (row as? ItemResult)?.takeIf { it.kind in CLAUDE_KINDS }?.let { claudeLogs.find(it.id)?.second }
                add(entry?.text ?: row.name)
            }
            if (SearchField.Description in fields) descriptionOf(row)?.let(::add)
            if (SearchField.Buttons in fields) addAll(buttonLabelsOf(row))
        }

    /**
     * User rule 2026-11-16 (*"where to search: id, name, description (the description of a search configuration is
     * displayed in an info bubble when hovering over it), buttons, children"*): **whether [row] answers the bar
     * [query]** by the parts [filters] checks of it ([Filters.searchIn]) or of its children [Filters.childDistance]
     * levels below it ([Filters.searchChildren], [Filters.childSearchIn]). [children] gives the rows at that distance.
     * A blank bar answers everything.
     */
    fun fieldsMatch(
        row: Result,
        query: String,
        filters: Filters,
        children: (Result) -> List<Result>,
        claudeLogs: ClaudeLogCatalog = ClaudeLogCatalog.EMPTY,
    ): Boolean {
        if (query.isBlank()) return true
        val match = filters.queryOptions
        if (searchTexts(row, filters.searchIn, claudeLogs).any { textAnswers(it, query, match) }) return true
        if (!filters.searchChildren) return false
        return children(row).any { child -> searchTexts(child, filters.childSearchIn, claudeLogs).any { textAnswers(it, query, match) } }
    }

    /**
     * What stands [distance] levels below [start] ([below] gives one level), each once, by [key]: exactly that level;
     * [CHILD_DISTANCE_ANY], every level below, down to the bottom.
     */
    fun <T> rowsBelow(start: List<T>, distance: Int, key: (T) -> String, below: (T) -> List<T>): List<T> {
        if (distance != CHILD_DISTANCE_ANY) {
            var level = start
            repeat(distance.coerceAtLeast(1)) { level = level.flatMap(below).distinctBy(key) }
            return level
        }
        val seen = start.mapTo(HashSet(), key)
        val out = ArrayList<T>()
        var level = start
        while (level.isNotEmpty()) {
            level = level.flatMap(below).filter { seen.add(key(it)) }
            out += level
        }
        return out
    }

    /**
     * User rule 2026-11-16: **the search configurations a configurations tree lays out**, as a Search window lists them
     * ([results]' `configurationRows`): its [rows] at one level, and what the row of id [childrenOf] holds.
     */
    class ConfigurationRows(val rows: List<Result>, val childrenOf: (String) -> List<Result>)

    /**
     * The description of [row], as its info bubble says it; null where it has none. A search configuration's is its
     * [configurationDescription].
     */
    fun descriptionOf(row: Result): String? =
        (row as? ItemResult)?.takeIf { it.kind == Kind.SearchConfiguration }?.let { configurationDescription(it.id) }

    /**
     * User rule 2026-11-16 ("Where to search › Buttons"): **the labels of what can be pressed on [row]** — a search
     * configuration's control ([settingButtonLabels]); any other element's actions, those the actions section offers
     * for its type ([AddedAction.section]).
     */
    fun buttonLabelsOf(row: Result): List<String> {
        settingOf(row)?.let { return settingButtonLabels(it) }
        if (row.kind == Kind.SearchConfiguration) return emptyList()
        return AddedAction.entries.filter { it.section == row.kind && it !in RETIRED_ACTIONS }.map { it.label }
    }

    /** The labels on [setting]'s control, as the configurations tree draws it ([buttonLabelsOf]); none for a switch. */
    fun settingButtonLabels(setting: Setting): List<String> {
        fun <T> labels(entries: List<T>, label: (T) -> String) = entries.map(label)
        return when (setting) {
            Setting.TaskInTree, Setting.TaskTreeRoot, Setting.TaskSchedulable, Setting.TaskOnCalendar, Setting.CategoryHasRules,
            Setting.PeriodOnCalendar, Setting.QuotaRepeatsSetting, Setting.HistoryUndoneSetting,
            Setting.ShortcutReboundSetting,
            -> labels(Tri.entries) { it.label }
            Setting.PeriodOriginSetting -> labels(PeriodOrigin.entries) { it.label }
            Setting.PeriodResilienceSetting -> labels(ResilienceLevel.entries) { it.label }
            Setting.AlarmStateSetting -> labels(AlarmState.entries) { it.label }
            Setting.TimerStateSetting, Setting.ChronoStateSetting -> labels(TimerState.entries) { it.label }
            Setting.ReminderRepeatsSetting -> labels(ReminderRepeats.entries) { it.label }
            Setting.WindowDuplicatesSetting -> labels(WindowDuplicates.entries) { it.label }
            Setting.ChildDistance -> listOf("any", "1", "2", "3", "4", "5")
            Setting.AlarmDays -> listOf("Mo", "Tu", "We", "Th", "Fr", "Sa", "Su", "any day")
            Setting.TaskBoxesFrom, Setting.TaskBoxesUntil, Setting.PeriodBoxesFrom, Setting.PeriodBoxesUntil -> listOf("any", "Pick", "Now")
            Setting.CalendarAdd, Setting.CalendarAt -> listOf("Now")
            Setting.SearchBars -> listOf("Add a search bar")
            Setting.ResetSearch -> listOf("Reset")
            Setting.SortResults -> listOf("Default")
            else -> emptyList()
        }
    }

    /**
     * How well [name] answers [query]: 0 = the same name, 1 = starts with it, 2 = contains it, null = not a
     * result. Case-insensitive; a blank query lists everything, alphabetically.
     */
    /**
     * User rule 2026-11-04: whether [row], which the case-blind search found for [query], still answers it with the bar's
     * [match] filters ([Filters.matchCase], [Filters.wholeWord]), read as the task tree's find bar reads them
     * ([TaskTreeSearch.ranges]): **the query is ONE phrase** ([matchPhrase]), in a name as in a log entry's whole text —
     * never its terms one by one (anomaly 2026-11-04: both on, "A at " listed and highlighted a log entry for a lone "A").
     */
    fun matchesWith(row: Result, query: String, match: TaskTreeSearch.Options, claudeLogs: ClaudeLogCatalog = ClaudeLogCatalog.EMPTY): Boolean {
        val entry = (row as? ItemResult)?.takeIf { it.kind in CLAUDE_KINDS }?.let { claudeLogs.find(it.id)?.second }
        return TaskTreeSearch.ranges(entry?.text ?: row.name, matchPhrase(query, match), match).isNotEmpty()
    }

    /**
     * User rule 2026-11-06: **whether one search bar answers [row]** — the bars a formula puts together
     * ([SearchBarFormula]): a blank bar answers everything; with a filter of the bar on, as [matchesWith]; else every
     * term of it — words, or "quoted phrases" — is in the row's name (a log entry's whole text), case aside, as the
     * logs' own search reads a bar ([ClaudeLogSearch]).
     */
    fun barMatches(row: Result, query: String, match: TaskTreeSearch.Options, claudeLogs: ClaudeLogCatalog = ClaudeLogCatalog.EMPTY): Boolean {
        if (query.isBlank()) return true
        if (match != TaskTreeSearch.Options()) return matchesWith(row, query, match, claudeLogs)
        val entry = (row as? ItemResult)?.takeIf { it.kind in CLAUDE_KINDS }?.let { claudeLogs.find(it.id)?.second }
        val text = entry?.text ?: row.name
        return ClaudeLogSearch.rawTerms(query).all { text.contains(it, ignoreCase = true) }
    }

    /**
     * What the bar holds as the one phrase match case and whole word read ([matchesWith], [matchRanges]): trimmed,
     * unquoted — a regular expression as it is written ([TaskTreeSearch.Options.regex]).
     */
    fun matchPhrase(query: String, match: TaskTreeSearch.Options = TaskTreeSearch.Options()): String =
        if (match.regex) query else query.trim().removeSurrounding("\"").trim()

    internal fun matchRank(name: String, query: String): Int? {
        val q = query.trim().lowercase()
        if (q.isEmpty()) return 2
        val n = name.lowercase()
        return when {
            n == q -> 0
            n.startsWith(q) -> 1
            q in n -> 2
            else -> null
        }
    }

    /**
     * **Where a search bar's [query] stands in [text]** (user rule 2026-11-02), the one answer of every highlight: each
     * hit of each of its terms — words, or "quoted phrases" ([ClaudeLogSearch.rawTerms]) — case aside.
     */
    fun matchRanges(text: String, query: String, match: TaskTreeSearch.Options = TaskTreeSearch.Options()): List<IntRange> {
        if (text.isEmpty()) return emptyList()
        // User rule 2026-11-04: with the bar's match case or whole word on, only what matches with them is a hit.
        if (match != TaskTreeSearch.Options()) return TaskTreeSearch.ranges(text, matchPhrase(query, match), match)
        val ranges = ArrayList<IntRange>()
        for (term in ClaudeLogSearch.rawTerms(query)) {
            var at = text.indexOf(term, ignoreCase = true)
            while (at >= 0) {
                ranges += at until at + term.length
                at = text.indexOf(term, at + term.length, ignoreCase = true)
            }
        }
        return ranges
    }

    private fun reminderDetail(chore: ChoreEntry): String {
        val span = chore.daysFormula.ifBlank { formatNumber(chore.spanDays) }
        val cadence =
            when (chore.recurrenceUnit) {
                ChoreRecurrenceUnit.Days, ChoreRecurrenceUnit.Weeks,
                ChoreRecurrenceUnit.Months, ChoreRecurrenceUnit.Years,
                -> "every $span ${chore.recurrenceUnit.label}"
                else -> "$span ${chore.recurrenceUnit.label}"
            }
        return cadence + " · " + hhmm(chore.timeOfDayMinutes)
    }

    private fun formatNumber(value: Double): String =
        if (value == kotlin.math.floor(value)) value.toLong().toString() else value.toString()

    private fun plural(count: Int, noun: String): String = "$count $noun" + if (count == 1) "" else "s"

    private fun hhmm(minutes: Int): String {
        val h = (minutes / 60) % 24
        val m = minutes % 60
        return h.toString().padStart(2, '0') + ":" + m.toString().padStart(2, '0')
    }

    // ----- The added elements ---------------------------------------------------------------------

    /**
     * A row's key: its kind and its id (`Task/…`, `Alarm/…`). What the selection, the check boxes and the added
     * list ([Config.added]) remember a row by — never the row itself, which is rebuilt with the state.
     */
    fun keyOf(result: Result): String =
        when (result) {
            is TaskResult -> taskKey(result.taskId)
            is ItemResult -> result.kind.name + "/" + result.id
        }

    /** The calendar element's id ([Kind.Calendar]): there is one. */
    const val CALENDAR_ID: String = "calendar"

    /** The calendar element's row. */
    fun calendarResult(): ItemResult = ItemResult(Kind.Calendar, CALENDAR_ID, "Calendar", "the plan and what was done")

    /** The calendar element's key, as the added elements hold it. */
    val CALENDAR_KEY: String = Kind.Calendar.name + "/" + CALENDAR_ID

    /** Whether [config]'s window shows the calendar: it holds the calendar element (user rule 2026-11-12). */
    fun showsCalendar(config: Config): Boolean = CALENDAR_KEY in config.added

    /**
     * User rule 2026-11-12: **a Search window that is a calendar** — [base] with the calendar element added, the
     * group of its actions and its "Calendar" row open, so the calendar is what the actions section shows. What the
     * calendar window was, and what every way to "open the calendar" opens now.
     */
    fun calendarWindowConfig(base: Config = Config(kinds = setOf(Kind.Calendar))): Config {
        val added = (base.added - CALENDAR_KEY) + CALENDAR_KEY
        val group = calendarActionGroup(base)
        val open =
            listOfNotNull(group?.id, group?.let { microGroupId(it, AddedAction.CalendarGrid) })
                .filter { !isActionRowOpen(base, it, top = it == group?.id) }
        return base.copy(added = added, toggledActionRows = base.toggledActionRows + open)
    }

    /** The group of actions the calendar element stands in, in a window of [base] ([calendarWindowConfig]). */
    private fun calendarActionGroup(base: Config): ActionGroup? {
        val calendar = calendarResult()
        val groups = actionGroups(actionsFor(addedActions(base.actionQuery, Kind.entries.toSet(), setOf(Kind.Calendar)), listOf(calendar)), listOf(calendar))
        return groups.firstOrNull { g -> g.members.any { keyOf(it) == CALENDAR_KEY } }
    }

    /**
     * User rule 2026-11-21: the "Calendar" row of a calendar window's actions section ([calendarWindowConfig]) — the row
     * whose content is the calendar, which the default Calendar menu button's window opens scrolled to.
     */
    fun calendarActionRowId(base: Config = Config(kinds = setOf(Kind.Calendar))): String? =
        calendarActionGroup(base)?.let { microGroupId(it, AddedAction.CalendarGrid) }

    /** A task's key — its result row's, and a cell of an expanded row's sub-tree that holds it. */
    fun taskKey(taskId: TaskId): String = Kind.Task.name + "/" + taskId.value

    /** [added] with [keys] appended in order — each key once, where it was first added. */
    fun withAdded(added: List<String>, keys: List<String>): List<String> = (added + keys).distinct()

    /**
     * The rows [keys] name, in their order, built exactly as the result list builds them ([taskResults],
     * [itemResults]) — so an added row reads as it did when it was found. A key whose element is gone lists
     * nothing. Only the kinds the keys hold are walked.
     */
    fun resolve(
        state: SchedulerState,
        keys: List<String>,
        allPaths: () -> Map<TaskId, List<List<String>>> = { allPathsInAnyTree(state) },
        windows: List<WindowEntry> = emptyList(),
        schedulerRuns: List<org.example.project.scheduler.state.SchedulerRunEntry> = emptyList(),
        claudeLogs: ClaudeLogCatalog = ClaudeLogCatalog.EMPTY,
    ): List<Result> {
        if (keys.isEmpty()) return emptyList()
        val kinds = keys.mapNotNullTo(LinkedHashSet()) { key -> Kind.entries.firstOrNull { it.name == key.substringBefore('/') } }
        val found = HashMap<String, Result>()
        for (kind in kinds) {
            // An option is no element of the account: it is named by its key ([optionKey]).
            if (kind == Kind.Option) {
                keys.filter { it.startsWith(Kind.Option.name + "/") }.forEach { key ->
                    val name = key.substringAfter('/')
                    found.getOrPut(key) { ItemResult(Kind.Option, name, name, "") }
                }
                continue
            }
            // Anomaly 2026-11-15 (*"the added elements list is empty"*: the global root, added from a "list of parents"
            // that lists it, was dropped here): a key is named by the very rows a search lists ([kindRows]), with
            // everything any list can show — the global root, and a window as one window or one window TYPE, whichever
            // the list it was added from showed.
            val rows =
                kindRows(state, kind, "", allPaths, windows, schedulerRuns, claudeLogs, withRoot = true) +
                    if (kind == Kind.Window) kindRows(state, kind, "", allPaths, windows, schedulerRuns, claudeLogs, windowTypesOnly = true) else emptyList()
            for (row in rows) found.getOrPut(keyOf(row)) { row }
        }
        return keys.mapNotNull { found[it] }
    }

    /**
     * What can be done to **every added element at once** — the Search window's top right quarter lists those of
     * the kinds in the list, and the Added elements configurations window lists them all, in sections, the way
     * the Configuration Search window lists [Setting]s: [section] null is about the list as a whole.
     */
    enum class AddedAction(val section: Kind?, val label: String) {
        /**
         * **The first micro group of every macro group** (user rule 2026-10-13: *"Both 'Open all' and 'Remove from
         * list' buttons must be in the first micro group of each macro group"*): the two buttons about the group's
         * elements as rows of the added elements list — open them, take them off it ([actionGroups]).
         */
        OpenEach(null, "Open or remove from the list"),
        /**
         * User rule 2026-11-02: the added elements' parents and categories ([ElementTree]): a selector of elements, and
         * beside it a button adding the element the window comes from ([Config.elementSource]).
         */
        /**
         * User rule 2026-11-12: *"In any element, there must be a parent action, and among the children there must be the
         * [list of parents] and a field to add new parents."* Its children are the fields of [ParentsChild]: the parents as a
         * Search window of its search section alone, each with its drop button ([parentsListConfig]), and the field of
         * "Add a parent". It replaced the two actions below, retired ([RETIRED_ACTIONS]).
         */
        ElementParents(null, "Parents"),
        /**
         * User rule 2026-11-19: *"In the action section for an element (like a task element), the user can add md elements."*
         * The md elements associated with the added elements, each edited in place and dissociated by its ✕, and the
         * button adding a new one associated with them all ([MdDomain]). No parent and no child: an association.
         */
        ElementMd(null, "Md elements"),
        ElementAddParent(null, "Add a parent"),
        ElementRemoveParent(null, "Remove a parent"),
        ElementAddCategory(null, "Add a category"),
        ElementRemoveCategory(null, "Remove a category"),
        /** The calendar's "add…" (user rule 2026-10-01): every added element that can go there, at the filter's instant. */
        PlaceOnCalendar(null, "Add to the calendar"),
        /**
         * User rule 2026-10-05: a Search window on every blue outlined block the added elements have on the calendar
         * ([blocksSearchConfig]).
         */
        CalendarBlocks(null, "Blocks on the calendar"),
        /**
         * User rule 2026-10-07: the blocks the added elements have on the calendar at an instant, held by a press on
         * the action's button and following the pointer over the calendar until it is released ([calendarDragTargets]).
         */
        DragOnCalendar(null, "Drag on the calendar"),
        ClearList(null, "Remove every element from the list"),
        /**
         * An added CREATION row (user rule 2026-10-04): a Search window on every element of the kind the row makes —
         * "New quota" leads to all the quotas ([kindSearchConfig]). One button per kind among the added creation rows.
         */
        CreationSearchAll(Kind.Creation, "Search every element of the kind"),
        TaskAddCategory(Kind.Task, "Add a category"),
        TaskRemoveCategory(Kind.Task, "Remove a category"),
        TaskMinimumTime(Kind.Task, "Minimum time"),
        // The title of every added one of the kind at once (user rule 2026-10-02): empty unless they all share one.
        AlarmTitle(Kind.Alarm, "Title"),
        /**
         * User rule 2026-10-12, of SEVERAL added timers ([actionsFor]): a timer's "Run" ([TimerRun], each timer's own)
         * for the timer with the lowest countdown — or for the timers checked in its drop-down
         * ([Config.pickedTimers]), which lists them all from the lowest countdown up, each with its title.
         */
        TimerLowestCountdown(Kind.Timer, "Lowest countdown"),
        /**
         * User rule 2026-10-14 (anomaly: *"in the chrono element, I don't see the countdown group … similar to the timer
         * element"*): [TimerLowestCountdown] for SEVERAL added chronos — the run of the chrono with the highest count, or
         * of those checked in its drop-down ([Config.pickedChronos]), which lists them from the highest count down.
         */
        ChronoHighestCount(Kind.Chrono, "Highest count"),
        TimerTitle(Kind.Timer, "Title"),
        ChronoTitle(Kind.Chrono, "Title"),
        QuotaTitle(Kind.Quota, "Title"),
        ReminderTitle(Kind.Reminder, "Title"),
        // A new element of the section's kind, and a copy of each added one (user rule 2026-10-01).
        TaskNew(Kind.Task, "New"),
        TaskDuplicate(Kind.Task, "Duplicate"),
        CategoryNew(Kind.Category, "New"),
        CategoryDuplicate(Kind.Category, "Duplicate"),
        PeriodNew(Kind.RestrictivePeriod, "New"),
        PeriodDuplicate(Kind.RestrictivePeriod, "Duplicate"),
        AlarmNew(Kind.Alarm, "New"),
        AlarmDuplicate(Kind.Alarm, "Duplicate"),
        TimerNew(Kind.Timer, "New"),
        TimerDuplicate(Kind.Timer, "Duplicate"),
        ChronoNew(Kind.Chrono, "New"),
        ChronoDuplicate(Kind.Chrono, "Duplicate"),
        QuotaNew(Kind.Quota, "New"),
        QuotaDuplicate(Kind.Quota, "Duplicate"),
        /**
         * The bin, beside "New" and "Duplicate" — ABOVE the quota's editors, which are one block per added quota: at the
         * end of the section it sat under six of each with six quotas added, and was not found (anomaly 2026-10-04).
         */
        QuotaDelete(Kind.Quota, "Delete"),
        ReminderNew(Kind.Reminder, "New"),
        ReminderDuplicate(Kind.Reminder, "Duplicate"),
        // A task cell's right-click menu (`taskCellMenuActions`), over every added task.
        TaskStartNow(Kind.Task, "Start now"),
        TaskEdit(Kind.Task, "Edit task"),
        TaskGoToTree(Kind.Task, "Go to task tree"),
        TaskGoToCalendar(Kind.Task, "Go to calendar"),
        TaskCopyIds(Kind.Task, "Copy task ids"),
        TaskDeepCopy(Kind.Task, "Deep copy"),
        TaskCollapseSubtrees(Kind.Task, "Collapse sub-trees"),
        TaskAddDefaultSubtree(Kind.Task, "Add default sub-tree"),
        /**
         * User rule 2026-11-11: the default sub-tree's window, which the task tree window's configuration section opened
         * until that window was removed: a task element's actions are where it is reached now.
         */
        TaskDefaultSubtree(Kind.Task, "Default sub-tree"),
        // The task edit window's sections (`TaskEditWindow`), over every added task.
        TaskAddUnder(Kind.Task, "Add under"),
        /**
         * User rule 2026-11-12: each added task's **children, as tree cells** — the drawing an expanded row shows its
         * sub-tree with — so they are edited as the tree's cells are, the switch between renaming and changing the id
         * in their Edit Mode included (a result row has none).
         */
        TaskChildren(Kind.Task, "Children"),
        TaskResilience(Kind.Task, "Resilience"),
        TaskScheduleUnit(Kind.Task, "Schedule unit"),
        /**
         * User rule 2026-11-16: *"In a task element, the user can define the evolution of its presence ratio target and its
         * minimum length target with any mathematical formula."* — the task's weight under each of its parents, a formula
         * of the instant ([TimeFormula]) per place.
         */
        /**
         * User rule 2026-11-20: *"The priority weights table and the relative priority manager must actually be found in
         * the action section."* — the weight table of each sub-list an added task stands in, and the relative priority of
         * each added task, drawn here as their windows draw them: what a default presence ratio target evolution reads.
         */
        TaskPriorityWeights(Kind.Task, "Priority weights"),
        TaskRelativePriority(Kind.Task, "Relative priority"),
        TaskPresenceFormula(Kind.Task, "Presence ratio target"),
        /** User rule 2026-11-16: the task's minimum length, in minutes, a formula of the instant ([TimeFormula]). */
        TaskMinimumFormula(Kind.Task, "Minimum length target"),
        /**
         * User rule 2026-11-13: **the name of the tree a task belongs to** — the title of its root, the task standing directly
         * under the global root (user rule 2026-11-16: a tree IS that task, [TaskTreesAsTasks]). *"the tree action should be
         * in all tasks belonging to a tree"*: listed for every task of a tree ([TaskResult.treeRoots]).
         */
        TaskTreeName(Kind.Task, "Tree name"),
        TaskPaths(Kind.Task, "Paths"),
        /**
         * PRD §9 (user rule 2026-10-03): each added schedulable task's **set of tasks** — what it fulfils while it is on
         * the calendar, each at a percentage ([org.example.project.scheduler.model.Task.fulfilment]).
         */
        TaskFulfilment(Kind.Task, "Set of tasks"),
        /** …and the other way round: the tasks whose set holds each added task, their percentage for it editable here. */
        TaskFulfilledBy(Kind.Task, "Fulfilled by"),
        /**
         * User rule 2026-10-03: each added task's occurrences, by path, each with the task cell categories it carries —
         * given and taken one occurrence at a time ([SchedulerIntent.SetCellCategory], [SchedulerIntent.AddCellCategory]).
         */
        TaskCellCategories(Kind.Task, "Task cell categories"),
        // A category's settings, each ONE control over every added category (user rule 2026-10-02) — no editor per
        // category. A name is unique, so the field is the one category's when one is added.
        CategoryName(Kind.Category, "Name"),
        CategoryRules(Kind.Category, "Rules"),
        CategoryAddRule(Kind.Category, "Add a rule"),
        /**
         * User rule 2026-10-03: whether the added categories are carried by task cells (one occurrence each) or by task
         * ids (every occurrence) — [SchedulerIntent.SetCategoryKind]. It replaced "Share of its sub-list", which a rule
         * at distance 1 now says and holds.
         */
        CategoryKind(Kind.Category, "Task cell category"),
        AlarmOnOff(Kind.Alarm, "State"),
        // An alarm's settings, each ONE field over every added alarm (user rule 2026-10-02: no editor per alarm).
        /**
         * The time of day, and (user rule 2026-10-18) the DATE: blank for an alarm that rings on its days, else the one
         * date it rings on ([org.example.project.scheduler.model.AlarmEntry.onlyOnEpochDay]).
         */
        AlarmTime(Kind.Alarm, "Date and time"),
        AlarmDays(Kind.Alarm, "Days"),
        AlarmRingsFor(Kind.Alarm, "Rings for"),
        AlarmRepeat(Kind.Alarm, "Repeat"),
        AlarmAlert(Kind.Alarm, "Alert"),
        /** The bin: every added one of the kind deleted from the account ([AddedCommand.Delete]). */
        AlarmDelete(Kind.Alarm, "Delete"),
        // The same for the other kinds (user rule 2026-10-02: "don't do that just for alarms"): a timer's, a
        // chrono's and a reminder's settings are ONE field each over every added one, and each kind has its bin.
        TimerDuration(Kind.Timer, "Duration"),
        TimerRingsFor(Kind.Timer, "Rings for"),
        TimerBelowZero(Kind.Timer, "Below zero"),
        TimerAlert(Kind.Timer, "Alert"),
        TimerDelete(Kind.Timer, "Delete"),
        ChronoDelete(Kind.Chrono, "Delete"),
        // User rule 2026-10-03: a quota's settings, one control over every added quota, and where each one stands.
        /** Each added quota's target progression now: the percentage, the amount due, the loop and its renewal. */
        QuotaProgress(Kind.Quota, "Target progression"),
        /** User rule 2026-10-08: the other times each added quota is looked at, and its progression at each. */
        QuotaLookTimes(Kind.Quota, "Progression at other times"),
        QuotaAmount(Kind.Quota, "Amount"),
        /** The first loop's start and end, and whether it repeats. */
        QuotaLoop(Kind.Quota, "Loop"),
        /** How many times the percentage runs from 0 to 100 % in one period ([QuotaEntry.renewals]). */
        QuotaRestarts(Kind.Quota, "Restarts"),
        /** The quota's resilience to each kind of restrictive period, a task's own. */
        QuotaResilience(Kind.Quota, "Resilience"),
        /** The list of the periods with something particular — its restarts, its own end, its amount factor — and "add". */
        QuotaLoops(Kind.Quota, "Particular loops"),
        ReminderEvery(Kind.Reminder, "Every"),
        ReminderTime(Kind.Reminder, "Time"),
        ReminderConstraint(Kind.Reminder, "Constrained in"),
        ReminderAlert(Kind.Reminder, "Alert"),
        ReminderDelete(Kind.Reminder, "Delete"),
        // User rule 2026-10-12: a keyboard shortcut's actions. "New" makes a chord for a window; the chord of every
        // shortcut is set here; a window's shortcut also says which window, and can be deleted.
        ShortcutNew(Kind.Shortcut, "New"),
        ShortcutChord(Kind.Shortcut, "Chord"),
        ShortcutWindow(Kind.Shortcut, "Window"),
        ShortcutDelete(Kind.Shortcut, "Delete"),
        CategoryDelete(Kind.Category, "Delete"),
        // The time of day put on the clock's (user rule 2026-10-02): every added alarm, every added reminder.
        AlarmTimeNow(Kind.Alarm, "Set to the current time"),
        ReminderTimeNow(Kind.Reminder, "Set to the current time"),
        /** The sound setting's slider ([AppSettingEntry.Sound], [SchedulerIntent.SetSoundVolume]). */
        /**
         * User rule 2026-11-12: **the calendar element's actions** — the calendar itself, drawn in a row of its own
         * height ([CalendarGrid]); the day selector; its display and its locks, this window's own view of it; and the
         * account's switches the calendar window held, drawn by the menu's own code.
         */
        CalendarGrid(Kind.Calendar, "Calendar"),
        CalendarDay(Kind.Calendar, "Day"),
        CalendarDisplay(Kind.Calendar, "Display"),
        CalendarLocks(Kind.Calendar, "Lock"),
        CalendarAutoSchedule(Kind.Calendar, "Auto schedule"),
        CalendarReminders(Kind.Calendar, "Reminders"),
        CalendarScreenBreaks(Kind.Calendar, "Screen breaks"),
        CalendarPlanTimeLimit(Kind.Calendar, "Allocated resources (q)"),
        CalendarMinimumTimeWeight(Kind.Calendar, "Minimum time weight"),
        CalendarSleepSchedule(Kind.Calendar, "Sleep schedule"),
        SoundVolume(Kind.AppSetting, "Global volume"),
        /** The voice's and the notifications' switches ([AppSettingEntry.Voice], [AppSettingEntry.Notifications]). */
        VoiceSwitch(Kind.AppSetting, "Voice"),
        NotificationsSwitch(Kind.AppSetting, "Notifications"),
        TimerRun(Kind.Timer, "Run"),
        ChronoRun(Kind.Chrono, "Run"),
        // The period edit window's sections (removed 2026-10-01), over every added period.
        /**
         * User rule 2026-10-13 (*"put the sleep configurations in the sleep period actions instead of the calendar
         * window"*): the Sleep schedule's fields — the night's times, the goal and its date, the two lengths. The
         * SLEEP period's own, and no other period's ([isSleepPeriod]).
         */
        PeriodSleepSchedule(Kind.RestrictivePeriod, "Sleep schedule"),
        PeriodDrawing(Kind.RestrictivePeriod, "Drawing"),
        /**
         * User rule 2026-10-15: *"configurations in the restrictive periods that allow the user to get a notification at
         * x time before the period"* — the leads of each added period's notices ([SchedulerIntent.SetPeriodNotices]).
         */
        PeriodNotices(Kind.RestrictivePeriod, "Notifications before"),
        /**
         * User rule 2026-10-16: *"tell if a notification must tell everything there is in the timeline during this
         * period and right after"* — over every added period ([SchedulerIntent.SetPeriodTellsTimeline]); on by default
         * for the 5 and 15 minute screen breaks.
         */
        PeriodTellsTimeline(Kind.RestrictivePeriod, "Notifications tell the timeline"),
        /**
         * User rule 2026-10-13: *"add the button to open a calendar window that shows all the notifications selected,
         * which are all the notifications of the macro group in the action section"* — a calendar window whose chips
         * are the group's notifications ([CalendarNotifications]).
         */
        NotificationCalendar(Kind.Notification, "Calendar"),
        PeriodCombinations(Kind.RestrictivePeriod, "Combinations"),
        PeriodTaskSearch(Kind.RestrictivePeriod, "Search its tasks"),
        PeriodDelete(Kind.RestrictivePeriod, "Delete"),
        PeriodReset(Kind.RestrictivePeriod, "Reset the default periods"),
        /**
         * Everything known of each added history unit, each fact with its copy button (user rule 2026-10-03): what the
         * History window's row and its information window showed, which a history unit's row opened until then.
         */
        /**
         * Anomaly 2026-10-06: the set of rules of an added scheduler-engine row was the LAST line of "Information",
         * under the whole rule state — one line per task of the account — and was not found. It is an action of its
         * own, listed first: the rules the engine returned, as the text the requirements ask for, with its copy button.
         */
        HistoryRules(Kind.HistoryUnit, "Set of rules"),
        HistoryInformation(Kind.HistoryUnit, "Information"),
        /**
         * The Claude Code logs (user rule 2026-10-18: *"it's in the action section that the user must see the content of
         * the added elements, where the user can see the prompts of prompt elements"*): each added entry's whole text,
         * the prompt an answer or a reasoning chain followed, and the session it was written in — each with its copy
         * button.
         */
        PromptText(Kind.ClaudePrompt, "Prompt"),
        PromptSession(Kind.ClaudePrompt, "Session"),
        AnswerText(Kind.ClaudeAnswer, "Answer"),
        AnswerPrompt(Kind.ClaudeAnswer, "In reply to"),
        AnswerSession(Kind.ClaudeAnswer, "Session"),
        ReasoningText(Kind.ClaudeReasoning, "Reasoning chain"),
        ReasoningPrompt(Kind.ClaudeReasoning, "In reply to"),
        ReasoningSession(Kind.ClaudeReasoning, "Session"),
        /** The logs' app setting's control: the directory they are read from ([AppSettingEntry.ClaudeLogs]). */
        ClaudeLogsDirectory(Kind.AppSetting, "Claude Code logs directory"),
        /** Every sound of the app off on this device ([AppSettingEntry.Mute]). */
        MuteSwitch(Kind.AppSetting, "Mute all sound"),
        /** A workplace setup's own: its name, its OS, its windows and the press that opens them. */
        WorkplaceTitle(Kind.WorkplaceSetup, "Title"),
        WorkplaceOsChoice(Kind.WorkplaceSetup, "Operating system"),
        WorkplaceWindows(Kind.WorkplaceSetup, "Windows"),
        WorkplaceApply(Kind.WorkplaceSetup, "Apply"),
        WorkplaceNew(Kind.WorkplaceSetup, "New"),
        WorkplaceDuplicate(Kind.WorkplaceSetup, "Duplicate"),
        WorkplaceDelete(Kind.WorkplaceSetup, "Delete"),
        // User rule 2026-11-19: an md element's text, the elements it is associated with, and the kind's New / Duplicate / bin.
        MdText(Kind.Md, "Text"),
        MdAssociated(Kind.Md, "Associated with"),
        /**
         * User rule 2026-11-21: Claude Code opened on the md elements' folder, to be asked about the graph of md files
         * ([MdDomain.claudeFolderFiles]).
         */
        MdAskClaude(Kind.Md, "Ask Claude Code"),
        MdNew(Kind.Md, "New"),
        MdDuplicate(Kind.Md, "Duplicate"),
        MdDelete(Kind.Md, "Delete"),
        /** User rule 2026-11-19: the bin of the added presence ratio target evolutions. */
        PresenceEvolutionDelete(Kind.PresenceEvolution, "Delete"),
        MinimumEvolutionDelete(Kind.MinimumEvolution, "Delete"),

        /** The volume of Claude Code's own sounds ([AppSettingEntry.ClaudeSounds]). */
        ClaudeSoundsVolume(Kind.AppSetting, "Claude Code sounds volume"),

        // User rule 2026-11-04: each control of the lateral menu, the one action of its setting
        // ([APP_SETTING_CONTROL_ACTIONS]), drawn by the menu's own code.
        LookAwayNowControl(Kind.AppSetting, "Look away now"),
        SwitchTaskControl(Kind.AppSetting, "Switch task"),
        SleepWorkControl(Kind.AppSetting, "Sleep / Work"),
        AwayControl(Kind.AppSetting, "I'm away"),
        OnlineControl(Kind.AppSetting, "Online"),
        AutoScheduleControl(Kind.AppSetting, "Auto schedule"),
        RemindersControl(Kind.AppSetting, "Reminders"),
        ScreenBreaksControl(Kind.AppSetting, "Screen breaks"),
        CalendarDisplayControl(Kind.AppSetting, "Display"),
        PlanTimeLimitControl(Kind.AppSetting, "Allocated resources (q)"),
        MinimumTimeWeightControl(Kind.AppSetting, "Minimum time weight"),
        SupabaseUsageControl(Kind.AppSetting, "Supabase free tier"),
        MemoryLimitsControl(Kind.AppSetting, "Memory limits"),
        ServerControl(Kind.AppSetting, "Server"),

        /**
         * User rule 2026-11-13: an added search configuration with no search configuration children ([settingOf]) shows
         * **its state for each window id** — every Search window open, and every one saved (a menu button's, the
         * "unfocused notif", a ▾ window that is open or holds a configuration of its own): [SearchWindowState].
         */
        ConfigurationStates(Kind.SearchConfiguration, "State in each window"),

        /**
         * User rule 2026-11-13: an ACTION added to the actions section's own list ([Kind.Action]) shows **its contexts and
         * states** — the elements it acts on, gathered by the state it has for them ([actionContexts]): each context the
         * elements sharing one state, and under it that state, the very action as their own actions section shows it.
         */
        ActionContexts(Kind.Action, "Contexts and states"),
    }

    /**
     * User rule 2026-11-13: **the contexts of [action] over [members]** — *"if the same state … is set the same for the
     * task elements A, B and C, then the context element will have the values A, B and C"*: the elements gathered by the
     * state the action has for them ([actionStateOf]), in the list's order, a context standing where its first element does.
     */
    fun actionContexts(state: SchedulerState, action: AddedAction, members: List<Result>): List<List<Result>> =
        members.groupBy { actionStateOf(state, action, it) }.values.toList()

    /**
     * What [action] holds for [row], as far as telling two elements apart goes — equal for the elements a context gathers
     * ([actionContexts]). "Parents": the element's parents. An action about a set as such ([isSetAction]) has one state
     * for them all. An element's own configuration is read off the element ([ownActionState]); what is not read there is
     * taken to differ from one element to the next — each its own context.
     */
    fun actionStateOf(state: SchedulerState, action: AddedAction, row: Result): Any =
        when {
            action == AddedAction.ElementParents -> ElementTree.parentsOf(state, keyOf(row)).toSet()
            action == AddedAction.ElementMd -> MdDomain.associatedWith(state, keyOf(row)).mapTo(HashSet()) { it.id }
            isSetAction(action) -> Unit
            else -> ownActionState(state, action, row) ?: keyOf(row)
        }

    /**
     * Anomaly 2026-11-14 (*"'Contexts and states' groups elements only for 'Parents'; other actions show one context per
     * element"*): **what [action] holds for [row], read off the element** — the very value its control shows, so two
     * elements holding the same one are one context. Null where it is not read here: an action that SHOWS the element (its
     * run, its progression, its paths, its children, a log entry's text) differs from one element to the next by what it
     * is, and an action whose state is not listed below stays one context per element — gathering two elements that
     * differ would show one's state as the other's.
     */
    private fun ownActionState(state: SchedulerState, action: AddedAction, row: Result): Any? {
        val id = (row as? ItemResult)?.id
        val task = (row as? TaskResult)?.let { state.tasks[it.taskId] }
        fun alarm() = state.alarms.firstOrNull { it.id == id }
        fun timer() = state.timers.firstOrNull { it.id == id }
        fun quota() = state.quotas.firstOrNull { it.id == id }
        fun chore() = state.chores.firstOrNull { it.id.ifEmpty { it.title } == id }
        fun category() = id?.let { state.categoryById(CategoryId(it)) }
        return when (action) {
            // A command with nothing to read: the same for every element it is offered for.
            AddedAction.TaskEdit, AddedAction.TaskGoToTree, AddedAction.TaskGoToCalendar, AddedAction.PeriodTaskSearch,
            AddedAction.WorkplaceApply, AddedAction.MdAskClaude -> Unit
            AddedAction.TaskStartNow -> (row as? TaskResult)?.let { SchedulerDomain.isPlaceableTask(state, it.taskId) }
            AddedAction.TaskMinimumTime -> task?.minimumMinutes
            AddedAction.TaskResilience -> task?.resilience
            AddedAction.TaskScheduleUnit -> task?.scheduleUnit
            AddedAction.MdText -> state.mdElements.firstOrNull { it.id == id && row.kind == Kind.Md }?.text
            AddedAction.TaskPresenceFormula -> task?.let { t -> PresenceEvolutions.involving(state, listOf(t.id)).map { it.id }.toSet() }
            AddedAction.TaskMinimumFormula -> task?.let { t -> PresenceEvolutions.involving(state, listOf(t.id), EvolutionTarget.Minimum).map { it.id }.toSet() }
            AddedAction.TaskTreeName -> treeRootsOf(listOf(row)).map { state.tasks[it]?.title }
            AddedAction.AlarmTitle -> alarm()?.label
            AddedAction.AlarmOnOff -> alarm()?.enabled
            AddedAction.AlarmTime -> alarm()?.let { it.timeOfDayMinutes to it.onlyOnEpochDay }
            AddedAction.AlarmDays -> alarm()?.days
            AddedAction.AlarmRingsFor -> alarm()?.soundSeconds
            AddedAction.AlarmRepeat -> alarm()?.repeats
            AddedAction.AlarmAlert -> alarm()?.alert
            AddedAction.TimerTitle -> timer()?.label
            AddedAction.TimerDuration -> timer()?.durationSeconds
            AddedAction.TimerRingsFor -> timer()?.soundSeconds
            AddedAction.TimerBelowZero -> timer()?.goesNegative
            AddedAction.TimerAlert -> timer()?.alert
            AddedAction.ChronoTitle -> state.chronos.firstOrNull { it.id == id }?.label
            AddedAction.QuotaTitle -> quota()?.title
            AddedAction.QuotaAmount -> quota()?.let { it.amount to it.unit }
            AddedAction.QuotaLoop -> quota()?.let { listOf(it.startMillis, it.endMillis, it.repeats, it.endByDelta, it.repeatCount) }
            AddedAction.QuotaRestarts -> quota()?.renewals
            AddedAction.QuotaResilience -> quota()?.resilience
            AddedAction.QuotaLoops -> quota()?.loops
            AddedAction.ReminderTitle -> chore()?.title
            AddedAction.ReminderEvery -> chore()?.let { listOf(it.spanDays, it.recurrenceUnit, it.daysFormula) }
            AddedAction.ReminderTime -> chore()?.timeOfDayMinutes
            AddedAction.ReminderConstraint -> chore()?.constrainedToReminderId
            AddedAction.ReminderAlert -> chore()?.alert
            AddedAction.CategoryName -> category()?.title
            AddedAction.CategoryRules -> category()?.rules
            AddedAction.CategoryKind -> category()?.kind
            AddedAction.PeriodDrawing -> id?.takeIf { row.kind == Kind.RestrictivePeriod }?.let { state.periodKindConfig.drawing(it) }
            else -> null
        }
    }

    /** The actions that show a Claude Code log entry, each one block per added entry. */
    val CLAUDE_ENTRY_ACTIONS: Set<AddedAction> by lazy {
        setOf(
            AddedAction.PromptText, AddedAction.PromptSession, AddedAction.AnswerText, AddedAction.AnswerPrompt,
            AddedAction.AnswerSession, AddedAction.ReasoningText, AddedAction.ReasoningPrompt, AddedAction.ReasoningSession,
        )
    }

    /**
     * The actions the Added elements configurations window lists, section by section — [configurations]'
     * rule: the general ones first, then one section per kind of [kinds] in the drop-down's order (and of
     * [onlyKinds] when given: the kinds the added list holds), each holding the actions whose label contains
     * [query]. An empty section is dropped.
     */
    fun addedActions(query: String, kinds: Set<Kind>, onlyKinds: Set<Kind>? = null): List<Pair<Kind?, List<AddedAction>>> =
        (listOf<Kind?>(null) + Kind.entries.filter { it in kinds && (onlyKinds == null || it in onlyKinds) })
            .mapNotNull { section ->
                val actions = AddedAction.entries.filter { it.section == section && it !in RETIRED_ACTIONS && matchRank(it.label, query) != null }
                if (actions.isEmpty()) null else section to actions
            }

    /**
     * **The groups in the order the top right section lists them** (user rule 2026-10-02): by the number of added
     * elements a group's actions apply to ([reachOf]), the most first. Stable, so groups reaching as many keep the
     * order [addedActions] gave them — "every element" first, then the drop-down's.
     */
    fun <T> sortedByReach(sections: List<Pair<Kind?, T>>, added: List<Result>): List<Pair<Kind?, T>> =
        sections.sortedByDescending { reachOf(it.first, added) }

    /** How many of [added] the actions of the group [kind] apply to: all of them for the general group (null). */
    /**
     * User rule 2026-10-08, an action kept as an item of the lateral menu: **whether [action] still has something to
     * act on** among the elements it was added with ([addedKeys]) as the account stands — one that is gone (a task
     * deleted, a timer removed) resolves to nothing, and the item is then greyed.
     */
    fun actionCanAct(state: SchedulerState, action: AddedAction, addedKeys: List<String>): Boolean =
        reachOf(action.section, resolve(state, addedKeys)) > 0

    fun reachOf(kind: Kind?, added: List<Result>): Int =
        added.count { kind == null || actionKindOf(it) == kind || it.kind == kind }

    /**
     * The kind whose actions apply to an added element (user rule 2026-10-03): its own — except an added **creation
     * row of a kind with something to offer through that kind's actions** ([ROW_ACTION_KINDS]), which is acted on as that
     * kind: the actions then edit what a new one starts with ([defaultConfigurationAdded]), or only make one.
     */
    fun actionKindOf(result: Result): Kind =
        if (result.kind == Kind.Creation && result is ItemResult) {
            ROW_ACTION_KINDS.firstOrNull { it.name == result.id } ?: result.kind
        } else {
            result.kind
        }

    /**
     * The kinds whose creation row is acted on as them: those with a default configuration, and (user rule 2026-11-02, the
     * audit of the kinds) those with only their "New" to offer. A task tree and a window have no action of the kind.
     */
    private val ROW_ACTION_KINDS: List<Kind> =
        listOf(
            Kind.Quota, Kind.Shortcut, Kind.Alarm, Kind.Reminder, Kind.Timer, Kind.WorkplaceSetup,
            Kind.Task, Kind.Category, Kind.RestrictivePeriod, Kind.Chrono, Kind.Md,
        )

    /** Whether the "New [kind]" creation row is among [added] — its default configuration is then being edited. */
    fun defaultConfigurationAdded(added: List<Result>, kind: Kind): Boolean =
        added.any { it.kind == Kind.Creation && it is ItemResult && it.id == kind.name }

    /**
     * The actions of an added CREATION row: the ones that edit the kind's default configuration — the settings a new
     * element starts with — and **"New"**, which is what the row is for: it creates one from that default (taken out
     * with the others for an evening, 2026-10-04, and missed at once). Everything else of the kind's section
     * (Duplicate, Delete, where an element stands, what is particular to one of its loops) is about an element that
     * exists, and a creation row is not one.
     */
    val DEFAULT_CONFIGURATION_ACTIONS: Set<AddedAction> =
        setOf(
            AddedAction.QuotaNew, AddedAction.QuotaTitle, AddedAction.QuotaAmount, AddedAction.QuotaLoop,
            AddedAction.QuotaRestarts, AddedAction.QuotaResilience,
            // User rule 2026-10-12: a timer's settings — what a new timer starts with ([SchedulerState.newTimerDefaults]),
            // edited in the group inside the timers' ([actionGroups]); it had a window of its own behind a button.
            AddedAction.TimerDuration, AddedAction.TimerRingsFor, AddedAction.TimerBelowZero, AddedAction.TimerAlert,
            // A "New keyboard shortcut" row has no default to edit: "New" is the whole of what it offers.
            AddedAction.ShortcutNew,
            // User rule 2026-10-18 (*"I don't see the default micro group in the action section of an alarm element"*):
            // an alarm's settings and a reminder's, what a new one starts with ([SchedulerState.newAlarmDefaults]…) —
            // and when it is set ([SchedulerState.newAlarmTimes], the default's "Date and time" / "Time").
            AddedAction.AlarmNew, AddedAction.AlarmTime, AddedAction.AlarmDays, AddedAction.AlarmRingsFor,
            AddedAction.AlarmRepeat, AddedAction.AlarmAlert, AddedAction.AlarmOnOff,
            AddedAction.ReminderNew, AddedAction.ReminderEvery, AddedAction.ReminderTime, AddedAction.ReminderAlert,
            // User rule 2026-11-02 (*"I don't see the actions for the default configurations for the workplace setup
            // element"*): what a new one starts with ([SchedulerState.newWorkplaceDefaults]), no name, as an alarm's.
            AddedAction.WorkplaceNew, AddedAction.WorkplaceOsChoice, AddedAction.WorkplaceWindows,
            // …and a "New timer" row, which showed none of the timers' settings above; and the "New" of the kinds with no
            // default configuration to edit, as a "New keyboard shortcut" row.
            AddedAction.TimerNew, AddedAction.TaskNew, AddedAction.CategoryNew, AddedAction.PeriodNew, AddedAction.ChronoNew,
            AddedAction.MdNew,
        )

    /**
     * [sections] as the top right section lists them for [added] (anomaly 2026-10-04: a "New quota" creation row
     * showed Delete and Duplicate): a kind reached ONLY through its creation row — no element of the kind is added —
     * keeps the actions that edit the default configuration ([DEFAULT_CONFIGURATION_ACTIONS]) and nothing else. With
     * an element of the kind added too, the whole section stands: its other actions are that element's.
     */
    fun actionsFor(sections: List<Pair<Kind?, List<AddedAction>>>, added: List<Result>): List<Pair<Kind?, List<AddedAction>>> =
        sections.mapNotNull { (kind, actions) ->
            if (kind == null) {
                // The calendar's actions are those of an element that can be on the calendar (user rule 2026-10-05).
                val onCalendar = added.any { it.kind in CALENDAR_ADD_KINDS }
                // A timer's place on the calendar is the moment it ends, which its run states and moves (user rule
                // 2026-10-12): with nothing but timers added there is no block to list.
                val onlyTimers = added.all { it.kind == Kind.Timer }
                // A creation row is no element, and tasks alone have their own category actions.
                // ...nor is a row of the actions section, added to that section's own list (user rule 2026-11-13).
                val elements = added.any { ElementTree.isElement(it.kind) && it.kind != Kind.Action }
                val onlyTasks = added.all { it.kind == Kind.Task }
                actions.filter {
                    (onCalendar || it !in CALENDAR_ACTIONS) && !(onlyTimers && it == AddedAction.CalendarBlocks) &&
                        (elements || it !in ELEMENT_ACTIONS) && !(onlyTasks && it in ELEMENT_CATEGORY_ACTIONS)
                }.takeIf { it.isNotEmpty() }?.let { kind to it }
            } else if (added.any { it.kind == kind } || !defaultConfigurationAdded(added, kind)) {
                // The lowest countdown is of SEVERAL timers, the highest count of several chronos: one alone has its own run.
                val severalTimers = added.count { it.kind == Kind.Timer } > 1
                val severalChronos = added.count { it.kind == Kind.Chrono } > 1
                // The Sleep schedule is the Sleep period's: listed where that period is added.
                val sleepAdded = added.any(::isSleepPeriod)
                // A tree's actions are those of every task belonging to a tree (user rule 2026-11-13).
                val treeRootAdded = added.any(::belongsToTree)
                (
                    kind to actions.filter {
                        (severalTimers || it != AddedAction.TimerLowestCountdown) &&
                            (severalChronos || it != AddedAction.ChronoHighestCount) &&
                            (sleepAdded || it != AddedAction.PeriodSleepSchedule) &&
                            (treeRootAdded || it != AddedAction.TaskTreeName) &&
                            // A node of the tree changes nothing itself: no state to show.
                            (it != AddedAction.ConfigurationStates || added.any { row -> settingOf(row) != null }) &&
                            // A menu control's action is its setting's alone: listed where that setting is added.
                            APP_SETTING_CONTROL_ACTIONS[it]?.let { setting -> appSettingAdded(added, setting) } != false
                    }
                )
                    .takeIf { it.second.isNotEmpty() }
            } else {
                actions.filter { it in DEFAULT_CONFIGURATION_ACTIONS }.takeIf { it.isNotEmpty() }?.let { kind to it }
            }
        }

    /** The added elements' parents and categories: nothing a list without an element offers ([actionsFor]). */
    val ELEMENT_CATEGORY_ACTIONS: Set<AddedAction> = setOf(AddedAction.ElementAddCategory, AddedAction.ElementRemoveCategory)
    val ELEMENT_ACTIONS: Set<AddedAction> = ELEMENT_CATEGORY_ACTIONS + setOf(AddedAction.ElementParents, AddedAction.ElementMd)

    /**
     * User rule 2026-11-12: **the Search window "Add a parent" opens**, *"with the search configuration of only tasks"*: the
     * tasks, by title, as a cell's Edit Mode lists them ([PICKER_SORTS]); a click picks the parent.
     */
    val ADD_PARENT_SEARCH: Config by lazy { Config(kinds = setOf(Kind.Task), sorts = PICKER_SORTS, sortFollowsDefault = false) }

    /**
     * The children of "Parents" ([AddedAction.ElementParents]), in their order. User rule 2026-11-12: each is a field, the
     * child of "Parents" directly, named by its grey text ([placeholder]) — no row of its own around it.
     */
    enum class ParentsChild(val placeholder: String) { List("list of parents"), Add("add a parent") }

    /**
     * User rule 2026-11-12: **the Search window of the "List of parents"** of [children]: every element kind a parent can
     * be (a category has its own actions: not here), only the parents of [children] ([Filters.parentsOf]), each with its
     * drop button ([Config.dropButtons]). [current] is what the user made of it since (its bar, its other settings): the
     * question it asks is set again, the rest kept.
     */
    /**
     * Anomaly 2026-11-15 (*"when I opened 'list of parents', then selected 'show the added elements', an element was there
     * even though I didn't click on the element in the search result list"*): **the "List of parents" of [children] as it
     * is shown** — [parentsListConfig], its added elements those that are parents of [children] still. Its configuration
     * is kept by the row it is opened from, and a group's row ("every element", a kind's) is the same for every set of
     * elements: what was added to the list of others' parents was in this one's.
     */
    fun parentsListShown(state: SchedulerState, children: List<String>, current: Config?): Config {
        val shown = parentsListConfig(children, current)
        if (shown.added.isEmpty()) return shown
        val distance = shown.filters.parentDistance.takeIf { it != PARENT_DISTANCE_HIGHEST }
        val parents = ElementTree.parentsWithin(ElementTree.parentIndex(state), children, distance)
        return shown.copy(added = shown.added.filter { it in parents })
    }

    fun parentsListConfig(children: List<String>, current: Config? = null): Config {
        val base = current ?: Config(dropButtons = true)
        return base.copy(
            kinds = ElementTree.PARENT_KINDS.toSet(),
            pageTypesOnly = false,
            filters = base.filters.copy(parentsOf = children.toSet()),
        )
    }

    /**
     * **One group of the actions section** (user rule 2026-10-10): the added elements its actions act on ([members])
     * and those actions, in the order they are listed. [kind] is the kind the group is about, null for the group of
     * every element; [element] says the group is ONE element's own.
     */
    data class ActionGroup(
        val kind: Kind?,
        val members: List<Result>,
        val actions: List<AddedAction>,
        val element: Boolean = false,
        /**
         * What the group holds besides its own actions, under a heading of its own inside it: the DEFAULT
         * CONFIGURATION of the kind — what a new element starts with — in the group of several elements of a kind
         * that has one (user rule 2026-10-10). Its members are the kind's creation row, which is what those editors
         * read the default through.
         */
        val inner: List<ActionGroup> = emptyList(),
        /** The heading of an [inner] group, which is not named after its members. */
        val heading: String? = null,
        /** What ONE element contains, which [actionGroups] puts first while the section's bar is found in it. */
        val contents: Boolean = false,
    ) {
        /**
         * What the group is known by across edits of the list — what its expansion arrow is remembered under
         * ([Config.collapsedActionGroups]): the element of an element's group, the kind of a kind's (however many of
         * it are added), one name for the group of every element.
         */
        val id: String
            get() = when {
                contents -> "contents/" + members.joinToString(",") { keyOf(it) }
                heading != null -> "default/" + kind?.name
                element -> "element/" + members.joinToString(",") { keyOf(it) }
                kind != null -> "kind/" + kind.name
                else -> "all"
            }

        /** The heading the section writes over the group. */
        val title: String
            get() {
                val label = kind?.label?.replaceFirstChar { it.uppercase() }
                return when {
                    heading != null -> heading
                    element || contents -> listOfNotNull(label, members.singleOrNull()?.name?.takeIf { it.isNotBlank() }).joinToString(": ")
                    else -> (label ?: "Every element") + "  ·  " + members.size
                }
            }
    }

    /**
     * An element's own actions that stay ITS OWN where several elements are added: what is drawn one block per element
     * (where a quota stands, a timer's run, a history unit's facts, a task's paths) or names one element (a category's
     * name is unique; "Start now" starts one task). Every other own action is ONE field over the elements it is given,
     * so the group of several elements of a kind lists it too — to write to all of them at once.
     */
    val PER_ELEMENT_ACTIONS: Set<AddedAction> =
        setOf(
            AddedAction.QuotaProgress, AddedAction.QuotaLookTimes, AddedAction.QuotaLoops, AddedAction.TimerRun,
            AddedAction.ChronoRun, AddedAction.HistoryRules, AddedAction.HistoryInformation, AddedAction.TaskPaths, AddedAction.TaskChildren,
            AddedAction.TaskCellCategories, AddedAction.TaskFulfilment, AddedAction.TaskFulfilledBy,
            AddedAction.TaskStartNow, AddedAction.TaskEdit, AddedAction.TaskGoToTree, AddedAction.TaskGoToCalendar,
            AddedAction.CategoryName, AddedAction.ShortcutChord, AddedAction.ShortcutWindow,
            AddedAction.PeriodSleepSchedule,
        ) + CLAUDE_ENTRY_ACTIONS

    /** Whether [result] is a task belonging to a tree ([TaskResult.treeRoots]): it has the tree's actions. */
    fun belongsToTree(result: Result): Boolean = (result as? TaskResult)?.treeRoots?.isNotEmpty() == true

    /** The roots of the trees [rows] belong to, each once, in order: what their "Tree name" names. */
    fun treeRootsOf(rows: List<Result>): List<TaskId> = rows.flatMap { (it as? TaskResult)?.treeRoots.orEmpty() }.distinct()

    /**
     * User rule 2026-11-13: **the trees every task of the live tree belongs to** — each task standing directly under the
     * global root is a tree's root, and the tree is what stands under it, at any depth (a mirrored task in several).
     * One walk down from each root, each sub-list once per tree; a task in no tree has none.
     */
    fun treeRootsByTask(state: SchedulerState): Map<TaskId, Set<TaskId>> {
        val out = HashMap<TaskId, MutableSet<TaskId>>()
        fun titled(id: TaskId?) = id?.takeIf { state.tasks[it]?.title?.isNotEmpty() == true }
        val tops = state.lists[state.rootListId]?.cellIds.orEmpty().mapNotNull { titled(state.cells[it]?.taskId) }.distinct()
        for (top in tops) {
            val seenLists = HashSet<CellListId>()
            val pending = ArrayDeque(listOf(top))
            while (pending.isNotEmpty()) {
                val task = pending.removeLast()
                if (!out.getOrPut(task, ::LinkedHashSet).add(top)) continue
                val list = state.tasks[task]?.childListId ?: continue
                if (!seenLists.add(list)) continue
                state.lists[list]?.cellIds?.forEach { cell -> titled(state.cells[cell]?.taskId)?.let(pending::add) }
            }
        }
        return out
    }

    /** Whether [result] is the Sleep period — the one element the Sleep schedule's fields are an action of. */
    fun isSleepPeriod(result: Result): Boolean =
        result is ItemResult && result.kind == Kind.RestrictivePeriod && result.id == PeriodKinds.SLEEP

    /**
     * The actions about a SET of elements as such — a command every member takes alike (make one, copy them, delete
     * them, take them off the list) — as opposed to an element's OWN configuration (its title, where it stands, its
     * settings), which differs from one element to the next. The general actions and an app setting's are all of the
     * first sort ([isSetAction]).
     */
    val SET_ACTIONS: Set<AddedAction> by lazy {
        AddedAction.entries.filterTo(HashSet()) { it.label == "New" || it.label == "Duplicate" || it.label == "Delete" } +
            setOf(
                AddedAction.CreationSearchAll, AddedAction.TaskAddCategory, AddedAction.TaskRemoveCategory,
                AddedAction.TaskCopyIds, AddedAction.TaskDeepCopy, AddedAction.TaskCollapseSubtrees,
                AddedAction.TaskAddDefaultSubtree, AddedAction.AlarmTimeNow, AddedAction.ReminderTimeNow,
                AddedAction.PeriodReset, AddedAction.TimerLowestCountdown, AddedAction.ChronoHighestCount,
                AddedAction.NotificationCalendar,
            )
    }

    fun isSetAction(action: AddedAction): Boolean =
        action.section == null || action.section == Kind.AppSetting || action in SET_ACTIONS

    /** What an element's own group shows FIRST: where it stands now, before what it is called (user rule 2026-10-10). */
    private val LEADING_ACTIONS: List<AddedAction> =
        listOf(AddedAction.QuotaProgress, AddedAction.TimerRun, AddedAction.ChronoRun, AddedAction.AlarmOnOff)

    /**
     * User rule 2026-10-10: **the groups of the actions section, from the widest reach to the narrowest** — *"there
     * should be the action groups that include all the actions that can be applied to all the elements, then a group
     * for less elements and so on… when there are only two quota elements, there must be three groups: the one for both
     * quota (remove them from the list, default configurations for a quota element etc…), the one for the first quota
     * in the list (progression, title etc…) and the one for the second quota"*.
     *
     * [sections] is what [actionsFor] lists for [added]. Out of it:
     *  - **every added element**: the general actions, and what is said just below of a kind every element is;
     *  - **each kind holding several of them** (in a group of its own where they are not all of them): that kind's set actions
     *    ([isSetAction]); then **one field for all of them at once** — *"a title field to rename every added quota
     *    elements at the same time, among other things"*: every own action that is one field over the elements it is
     *    given (all but [PER_ELEMENT_ACTIONS]); then, inside the group, **the default configuration of the kind**
     *    ([ActionGroup.inner], user rule 2026-10-10: *"the first group should have the configurations for the default
     *    configurations of the quota elements"*) — unless its creation row is added, which then has its own group;
     *  - **each element**, in the list's order: its own configuration, what says where it stands first
     *    ([LEADING_ACTIONS]) — and the set actions of its kind when it is the only one of it.
     * **One element alone is one group**: its own configuration, then its kind's set actions, then the general ones.
     * **Every group of elements OPENS with "Open all" and "Remove from list"** ([AddedAction.OpenEach], user rule
     * 2026-10-13), acting on the group's own elements — an element's group and the lone element's included, which had
     * neither until then. It is a micro group like any other: the search bar sorts it with them
     * ([sortedByActionSearch]) — first is where it stands while the bar is empty.
     * A kind no added element is (the window of every configuration lists them all) keeps a group of its own, last.
     */
    /**
     * [config] once the arrow of the group [id] is pressed: retracted if it was open, open if it was retracted. The ids
     * of groups the section no longer lists ([listed] — an element taken off the list) are dropped on the way, so the
     * set never outgrows the section.
     */
    fun withActionGroupToggled(config: Config, id: String, listed: Set<String>): Config {
        val kept = config.toggledActionRows.filterTo(HashSet()) { it in listed }
        return config.copy(toggledActionRows = if (id in kept) kept - id else kept + id)
    }

    /**
     * User rule 2026-11-12: whether the row [id] of the actions section is open — closed by default, but a [top] row
     * while [Config.actionsExpanded] — once its arrow has been pressed ([Config.toggledActionRows]) the other way.
     */
    fun isActionRowOpen(config: Config, id: String, top: Boolean): Boolean =
        (top && config.actionsExpanded) != (id in config.toggledActionRows)

    /**
     * User rule 2026-11-01, **a new element added to a window, and the added elements' "show only its actions"** (the same
     * rule since 2026-11-02): [config] with **only the macro groups holding [element] open**, every other retracted, and the
     * id of the first of them (what the section scrolls to). What is inside the groups stays as the user left it. Null where
     * no group holds it.
     */
    fun withOnlyGroupsOf(config: Config, added: List<Result>, element: Result): Pair<Config, String>? {
        val groups = actionGroups(actionsFor(addedActions(config.actionQuery, Kind.entries.toSet(), actionKindsOf(added)), added), added)
        val key = keyOf(element)
        val its = groups.filter { group -> group.members.any { keyOf(it) == key } }
        val first = its.firstOrNull() ?: return null
        val macro = groups.mapTo(HashSet()) { it.id }
        val inside = listedGroupIds(groups) - macro
        // The groups holding it open, every other closed: each written as a toggle where that is not the default.
        val open = its.mapTo(HashSet()) { it.id }
        val toggled = macro.filter { (it in open) != config.actionsExpanded } + config.toggledActionRows.filter { it in inside }
        return config.copy(toggledActionRows = toggled.toSet()) to first.id
    }

    /**
     * How well an action's title answers the actions section's search bar: 0 = the same words, 1 = starts with it,
     * 2 = contains it, 3 = holds every word of it somewhere ("add to calendar" finds "Add to the calendar"); null =
     * does not answer. A blank search answers nothing, so nothing is moved.
     */
    fun actionSearchRank(title: String, search: String): Int? {
        val q = search.trim().lowercase()
        if (q.isEmpty()) return null
        matchRank(title, q)?.let { return it }
        val t = title.lowercase()
        val words = q.split(' ', '\t').filter { it.isNotEmpty() }
        return if (words.isNotEmpty() && words.all { it in t }) 3 else null
    }

    /**
     * User rule 2026-10-11: *"The search bar sorts the micro groups by the matching of the micro group titles, but
     * doesn't sort the macro groups."* — [actions] (one macro group's micro groups) with those that answer [search]
     * first, the best answer first (a title before any other text of the action, [actionSearchRank]); the rest after
     * them, and every tie, in the order they stood in. EVERY micro group is sorted, the group's "Open all" / "Remove
     * from list" included (user rule 2026-10-13: *"a micro group like any other, so it should be affected by the search
     * bar matching sort"* — it was held first whatever the bar held for a few hours that day).
     */
    fun sortedByActionSearch(actions: List<AddedAction>, search: String): List<AddedAction> {
        if (search.isBlank()) return actions
        return actions.sortedBy { actionSearchRank(it, search) ?: Int.MAX_VALUE }
    }

    /**
     * User rule 2026-10-11: *"The search bar in the action bar sort the micro groups with any text, not just the title
     * of the micro groups. But only the microgroup are sorted, not inside the microgroups."* — how well the action
     * [action] answers [search]: by its title first ([actionSearchRank] of the label, 0–3), else by ANY text it shows
     * ([actionSearchWords]: 4 = it holds the search as typed, 5 = it holds every word of it); null = no text of it
     * answers. A title always beats a text inside, so "ends" still puts an action CALLED that before one with an
     * "ends" field. Nothing inside an action is reordered: the rank is the action's.
     */
    fun actionSearchRank(action: AddedAction, search: String): Int? {
        actionSearchRank(action.label, search)?.let { return it }
        val q = search.trim().lowercase()
        if (q.isEmpty()) return null
        val text = actionSearchWords(action).lowercase()
        if (text.isEmpty()) return null
        if (q in text) return 4
        val words = q.split(' ', '\t').filter { it.isNotEmpty() }
        return if (words.isNotEmpty() && words.all { it in text }) 5 else null
    }

    /**
     * **The texts an action shows under its title** — its buttons, the labels of its fields, its notes — for the
     * actions section's search bar ([actionSearchRank]). Kept here, beside the titles, because the search is the
     * domain's: an editor that gains a button or a label gains its word here (`SearchAddedElementsTest`). NOT in it:
     * what depends on the account — the names of the added elements, of their blocks, the values in the fields.
     */
    fun actionSearchWords(action: AddedAction): String =
        when (action) {
            AddedAction.OpenEach -> "open all remove from list"
            AddedAction.ClearList -> "remove all"
            AddedAction.CreationSearchAll -> "every search"
            AddedAction.PlaceOnCalendar -> "starts ends day at now right-click time after the start ends on add default length"
            AddedAction.DragOnCalendar -> "drag hold the button block follows the pointer"
            AddedAction.CalendarBlocks -> "choose blocks select all remove drag on the calendar starts ends at day"
            AddedAction.TaskMinimumTime -> "min minutes apply"
            AddedAction.TaskStartNow -> "start"
            AddedAction.TaskEdit -> "edit"
            AddedAction.TaskGoToTree, AddedAction.TaskGoToCalendar -> "go"
            AddedAction.TaskCopyIds -> "copy"
            AddedAction.TaskDeepCopy -> "deep copy"
            AddedAction.TaskCollapseSubtrees -> "collapse"
            AddedAction.TaskAddDefaultSubtree -> "add default sub-tree"
            AddedAction.TaskDefaultSubtree -> "open default sub-tree template window"
            AddedAction.TaskChildren -> "children sub-tree cells edit rename change task id switch"
            AddedAction.TaskResilience -> "period apply"
            AddedAction.TaskScheduleUnit -> "apply"
            AddedAction.ElementMd -> "md markdown text notes note add associate dissociate"
            AddedAction.MdText -> "md markdown text notes edit"
            AddedAction.MdAssociated -> "associated with elements dissociate"
            AddedAction.MdAskClaude -> "claude code ask questions ai graph links children folder files terminal open obsidian"
            AddedAction.TaskPriorityWeights -> "priority weights table weight columns chart share sub-list absolute priority"
            AddedAction.TaskRelativePriority -> "relative priority manager percentage relative to ancestor chains pins absolute priority"
            AddedAction.TaskPresenceFormula -> "presence ratio target evolutions tasks relative to parent distance time periods formula categories new"
            AddedAction.TaskMinimumFormula -> "minimum length target time periods formula evolution ramp date"
            AddedAction.TaskTreeName -> "rename tree name apply"
            AddedAction.AlarmOnOff, AddedAction.AlarmRepeat, AddedAction.TimerBelowZero -> "on off"
            AddedAction.AlarmTimeNow, AddedAction.ReminderTimeNow -> "now"
            AddedAction.AlarmTime -> "time date day once one-off right-click"
            AddedAction.SoundVolume -> "sound %"
            AddedAction.ClaudeSoundsVolume -> "sound volume % chime ring done waiting voice"
            AddedAction.TimerRun -> "start pause reset all elapsed ends day"
            AddedAction.ChronoRun -> "start resume pause reset all compact expand"
            AddedAction.TimerLowestCountdown -> "timers select all start pause reset all elapsed ends day run"
            AddedAction.ChronoHighestCount -> "chronos select all start pause reset all run compact expand"
            AddedAction.TaskNew, AddedAction.CategoryNew, AddedAction.PeriodNew, AddedAction.AlarmNew, AddedAction.TimerNew,
            AddedAction.ChronoNew, AddedAction.ReminderNew, AddedAction.QuotaNew, AddedAction.ShortcutNew,
            AddedAction.WorkplaceNew, AddedAction.MdNew -> "new create"
            AddedAction.WorkplaceTitle -> "name rename"
            AddedAction.WorkplaceOsChoice -> "os windows 11 system"
            AddedAction.WorkplaceWindows -> "file explorer vs code visual studio claude code cmd terminal tab path folder add remove default"
            AddedAction.WorkplaceApply -> "open run start"
            AddedAction.ShortcutChord -> "set rebind reset remove press a chord ctrl shift alt"
            AddedAction.ShortcutWindow -> "choose a window"
            AddedAction.TaskDuplicate, AddedAction.CategoryDuplicate, AddedAction.PeriodDuplicate, AddedAction.AlarmDuplicate,
            AddedAction.TimerDuplicate, AddedAction.ChronoDuplicate, AddedAction.ReminderDuplicate, AddedAction.QuotaDuplicate,
            AddedAction.WorkplaceDuplicate, AddedAction.MdDuplicate -> "duplicate copy"
            AddedAction.HistoryRules -> "copy the set of rules"
            AddedAction.HistoryInformation -> "copy all"
            AddedAction.TaskFulfilment -> "tasks add a task fulfils"
            AddedAction.TaskFulfilledBy -> "set holds"
            AddedAction.QuotaProgress -> "due by now loop renewal"
            AddedAction.QuotaLookTimes -> "on the date days from today day at the time it is now due by then loop renewal add a time"
            AddedAction.QuotaAmount -> "amount unit apply"
            AddedAction.QuotaLoop -> "week day starts at ends after the start ends on repeats times without end mon tue wed thu fri sat sun"
            AddedAction.QuotaRestarts -> "runs from 0 to 100 % in a period"
            AddedAction.QuotaResilience -> "resilience values 100 % in every period not at 100 %"
            AddedAction.QuotaLoops -> "loop restarts ends day at regular end add a loop"
            AddedAction.TaskCellCategories -> "task cell categories place"
            AddedAction.TaskPaths -> "place open task tree"
            AddedAction.CategoryKind -> "carried by task cells carried by task ids"
            AddedAction.CategoryName -> "unique rename"
            AddedAction.CategoryRules, AddedAction.CategoryAddRule -> "rule label"
            AddedAction.AlarmDelete, AddedAction.TimerDelete, AddedAction.ChronoDelete, AddedAction.QuotaDelete,
            AddedAction.WorkplaceDelete, AddedAction.MdDelete, AddedAction.PresenceEvolutionDelete, AddedAction.MinimumEvolutionDelete,
            AddedAction.ReminderDelete, AddedAction.CategoryDelete, AddedAction.PeriodDelete, AddedAction.ShortcutDelete -> "delete remove bin"
            AddedAction.TimerAlert, AddedAction.ReminderAlert, AddedAction.AlarmAlert -> "alert sound voice notify vibrate"
            AddedAction.ReminderConstraint -> "choose none"
            AddedAction.PeriodDrawing -> "period"
            AddedAction.PeriodNotices -> "notification notify alert remind warn ahead advance"
            AddedAction.PeriodTellsTimeline -> "notification notify timeline tasks during after next list what comes"
            AddedAction.NotificationCalendar -> "open a calendar with these notifications chips"
            AddedAction.PeriodSleepSchedule ->
                "wake up go to bed stop screens goal time date total sleep time no screen before bed"
            AddedAction.PeriodTaskSearch -> "search the tasks"
            AddedAction.PeriodReset -> "reset"
            AddedAction.PromptText, AddedAction.AnswerText, AddedAction.ReasoningText -> "text content copy read"
            AddedAction.AnswerPrompt, AddedAction.ReasoningPrompt -> "prompt question asked copy"
            AddedAction.PromptSession, AddedAction.AnswerSession, AddedAction.ReasoningSession ->
                "project session title id date file copy"
            AddedAction.ClaudeLogsDirectory -> "folder path read refresh load"
            AddedAction.ConfigurationStates -> "window id open saved value filter sort"
            AddedAction.ActionContexts -> "context state elements same value group"
            AddedAction.MuteSwitch -> "silence quiet sound voice ring alarm notification off on"
            else -> ""
        }

    /** What the actions section's search bar holds when the window is opened by the calendar's "add…". */
    const val CALENDAR_ADD_ACTION_SEARCH: String = "add to calendar"

    /**
     * **One micro group of a group of elements**: one action, or a group of actions inside it (the default configuration
     * of a kind, [ActionGroup.inner]) — what the section draws, in [microGroups]' order.
     */
    sealed interface MicroGroup {
        data class One(val action: AddedAction) : MicroGroup

        data class Inner(val group: ActionGroup) : MicroGroup
    }

    /**
     * The micro groups of [group] in the order the section draws them: its actions, then the groups inside it — and, with
     * text in the section's search bar, those that answer it first, the best first (user rule 2026-10-18: *"I typed
     * 'default' in the search bar of the action section, but it doesn't bring up the default configurations micro
     * group"* — it stood after every action whatever the bar held). A group inside answers by its title like an action
     * ([actionSearchRank], 0–3), else by an action of it, which is a text inside it (4–5: a title always beats a text
     * inside, user rule 2026-10-11). Stable: every tie keeps its place.
     */
    fun microGroups(group: ActionGroup, search: String): List<MicroGroup> {
        val all = group.actions.map { MicroGroup.One(it) } + group.inner.map { MicroGroup.Inner(it) }
        if (search.isBlank()) return all
        fun rank(micro: MicroGroup): Int? =
            when (micro) {
                is MicroGroup.One -> actionSearchRank(micro.action, search)
                is MicroGroup.Inner ->
                    actionSearchRank(micro.group.title, search)
                        ?: micro.group.actions.mapNotNull { actionSearchRank(it, search) }.minOrNull()?.coerceAtLeast(4)
            }
        return all.sortedBy { rank(it) ?: Int.MAX_VALUE }
    }

    /**
     * What the expansion arrow of ONE action's micro group in [group] is remembered under ([Config.collapsedActionGroups])
     * — user rule 2026-10-18: *"every micro group retracts"*, as the groups did. Its group's id and the action's name, so
     * the same action in another group (another element's) is retracted on its own.
     */
    fun microGroupId(group: ActionGroup, action: AddedAction): String = "action/" + group.id + "/" + action.name

    /**
     * User rule 2026-11-12: **a row of the actions section's Search window** — a group's heading ([action] null) or one
     * action of [group] — by its [key] (the arrow's id, [ActionGroup.id] or [microGroupId], made unique), named [title],
     * [detail] saying the elements it acts on where no group row says it, [depth] levels in, [open] by its arrow
     * ([isActionRowOpen]).
     */
    data class ActionListRow(
        val key: String,
        val id: String,
        val title: String,
        val detail: String,
        val depth: Int,
        val open: Boolean,
        val group: ActionGroup,
        val action: AddedAction?,
    )

    /**
     * User rule 2026-11-12: **the rows of the actions section, in order**, as its Search window lists them — what it drew
     * itself until then, the same order and the same open rule. Grouped ([Config.actionsGrouped]): each group a row whose
     * children are its micro groups ([microGroups]) — an action, or a group inside it with its own actions — every row
     * closed by default, but a top row while [Config.actionsExpanded]. Not grouped: every action a top row, naming the
     * elements it acts on, a group inside a group still a row of its own. Only what an open row holds is listed.
     */
    fun actionListRows(groups: List<ActionGroup>, config: Config): List<ActionListRow> {
        val search = if (config.actionSearchSortsOnly) config.actionSearch else ""
        val out = ArrayList<ActionListRow>()
        val seen = HashSet<String>()
        // Two groups sharing an id (an element added twice by an older build) must not share a row.
        fun unique(id: String): String {
            var key = id
            var n = 1
            while (!seen.add(key)) key = id + "#" + n++
            return key
        }
        fun actionRow(group: ActionGroup, action: AddedAction, depth: Int, top: Boolean, about: String) {
            val id = microGroupId(group, action)
            out += ActionListRow(unique(id), id, action.label, about, depth, isActionRowOpen(config, id, top), group, action)
        }
        fun groupRow(group: ActionGroup, depth: Int, top: Boolean) {
            val open = isActionRowOpen(config, group.id, top)
            out += ActionListRow(unique(group.id), group.id, group.title, "", depth, open, group, null)
            if (!open) return
            for (entry in microGroups(group, search)) {
                when (entry) {
                    is MicroGroup.One -> actionRow(group, entry.action, depth + 1, top = false, about = "")
                    is MicroGroup.Inner -> groupRow(entry.group, depth + 1, top = false)
                }
            }
        }
        for (group in groups) {
            if (config.actionsGrouped) {
                groupRow(group, 0, top = true)
            } else {
                for (entry in microGroups(group, search)) {
                    when (entry) {
                        is MicroGroup.One -> actionRow(group, entry.action, 0, top = true, about = group.title)
                        is MicroGroup.Inner -> groupRow(entry.group, 0, top = true)
                    }
                }
            }
        }
        return out
    }

    /**
     * Every arrow the section shows for [groups] — each group, each group inside one, and each action's micro group in
     * both ([microGroupId]): what [withActionGroupToggled] keeps of the retracted ones.
     */
    fun listedGroupIds(groups: List<ActionGroup>): Set<String> {
        val out = HashSet<String>()
        fun walk(group: ActionGroup) {
            out += group.id
            group.actions.forEach { out += microGroupId(group, it) }
            group.inner.forEach(::walk)
        }
        groups.forEach(::walk)
        return out
    }

    /**
     * [actionGroups] with each group's actions sorted by the section's search bar ([sortedByActionSearch]) — and, user
     * rule 2026-11-02, **the contents of the added elements that hold [search] first** ([contentsGroups]): a word the
     * rows do not show is looked for here, so it is what the section opens on. It is the one exception to "the bar moves
     * no group": what moves is an action, out of each element's group into a group before them, and it hides nothing.
     */
    fun actionGroups(
        sections: List<Pair<Kind?, List<AddedAction>>>,
        added: List<Result>,
        search: String,
        claudeLogs: ClaudeLogCatalog = ClaudeLogCatalog.EMPTY,
    ): List<ActionGroup> {
        val groups = actionGroups(sections, added)
        if (search.isBlank()) return groups
        fun sorted(group: ActionGroup): ActionGroup =
            group.copy(actions = sortedByActionSearch(group.actions, search), inner = group.inner.map(::sorted))
        val listed = sections.flatMapTo(HashSet()) { it.second }
        val contents = contentsGroups(added, search, claudeLogs, listed)
        val moved = contents.mapTo(HashSet()) { it.id.removePrefix("contents/") }
        fun without(group: ActionGroup): ActionGroup {
            val element = group.members.singleOrNull()?.takeIf { group.element && keyOf(it) in moved } ?: return group
            return group.copy(actions = group.actions.filterNot { it == contentActionOf(element.kind) })
        }
        return contents + groups.map { sorted(without(it)) }
    }

    /**
     * User rule 2026-11-12: **the groups the actions section lists for [config]** — [actionGroups] sorted by its search
     * bar, and, unless the bar sorts only ([Config.actionSearchSortsOnly]), filtered as a search is: an action listed
     * only if it answers ([actionSearchRank]), a group only while one of its actions or of its inner groups does — or
     * its own title does, and then all of it. A group of contents ([ActionGroup.contents]) is there because it answered.
     */
    fun shownActionGroups(
        sections: List<Pair<Kind?, List<AddedAction>>>,
        added: List<Result>,
        config: Config,
        claudeLogs: ClaudeLogCatalog = ClaudeLogCatalog.EMPTY,
    ): List<ActionGroup> {
        val search = config.actionSearch
        val groups = actionGroups(sections, added, search, claudeLogs)
        if (config.actionSearchSortsOnly || search.isBlank()) return groups
        fun filtered(group: ActionGroup): ActionGroup? {
            if (group.contents || actionSearchRank(group.title, search) != null) return group
            val inner = group.inner.mapNotNull(::filtered)
            val actions = group.actions.filter { actionSearchRank(it, search) != null }
            return if (actions.isEmpty() && inner.isEmpty()) null else group.copy(actions = actions, inner = inner)
        }
        return groups.mapNotNull(::filtered)
    }

    /**
     * The action that shows an element's whole content, where its row shows only a part of it: what a search can find
     * and the row not show. A kind whose search reads more than its row gains its action here.
     */
    private fun contentActionOf(kind: Kind): AddedAction? =
        when (kind) {
            Kind.ClaudePrompt -> AddedAction.PromptText
            Kind.ClaudeAnswer -> AddedAction.AnswerText
            Kind.ClaudeReasoning -> AddedAction.ReasoningText
            else -> null
        }

    /**
     * One group per element of [added] whose content holds every term of [search] (the logs' one match,
     * [ClaudeLogSearch]), in the list's order, holding the action that shows it — a [listed] one only, so the actions'
     * filter still narrows them. One group each, not one for them all: the section is lazy by group, and a keystroke
     * over hundreds of long texts must lay out the ones on screen. No "Open all" in them: they are what it says, no more.
     */
    private fun contentsGroups(added: List<Result>, search: String, claudeLogs: ClaudeLogCatalog, listed: Set<AddedAction>): List<ActionGroup> {
        val terms = ClaudeLogSearch.terms(search)
        if (terms.isEmpty()) return emptyList()
        return added.mapNotNull { row ->
            val action = contentActionOf(row.kind)?.takeIf { it in listed } ?: return@mapNotNull null
            val found = (row as? ItemResult)?.let { claudeLogs.find(it.id) } ?: return@mapNotNull null
            if (ClaudeLogSearch.matches(found.second, terms)) ActionGroup(row.kind, listOf(row), listOf(action), contents = true) else null
        }
    }

    fun actionGroups(sections: List<Pair<Kind?, List<AddedAction>>>, added: List<Result>): List<ActionGroup> {
        if (added.isEmpty()) return sections.map { (kind, actions) -> ActionGroup(kind, emptyList(), actions) }
        fun membersOf(kind: Kind?) = added.filter { kind == null || actionKindOf(it) == kind || it.kind == kind }
        // A creation row acted on as its kind ("New quota") has that kind's default configuration, nothing else of it.
        fun of(element: Result, kind: Kind?, actions: List<AddedAction>) =
            if (element.kind == Kind.Creation && kind != Kind.Creation) actions.filter { it in DEFAULT_CONFIGURATION_ACTIONS }
            // The Sleep schedule is the Sleep period's own, and no other period's.
            else actions.filter { (it != AddedAction.PeriodSleepSchedule || isSleepPeriod(element)) && (it != AddedAction.TaskTreeName || belongsToTree(element)) }
        fun own(actions: List<AddedAction>) =
            actions.filterNot(::isSetAction).let { mine -> LEADING_ACTIONS.filter { it in mine } + mine.filterNot { it in LEADING_ACTIONS } }
        fun set(actions: List<AddedAction>) = actions.filter(::isSetAction)
        // The fields that write to every element of the group at once.
        fun bulk(actions: List<AddedAction>) = actions.filter { !isSetAction(it) && it !in PER_ELEMENT_ACTIONS }
        // The default configuration of [kind], as a group inside the group of several of its elements.
        fun defaults(kind: Kind?, actions: List<AddedAction>): List<ActionGroup> {
            // Not where the kind's creation row is added and acted on as the kind: it then has its own group.
            if (kind == null || added.any { it.kind == Kind.Creation && actionKindOf(it) == kind }) return emptyList()
            val editing = actions.filter { it in DEFAULT_CONFIGURATION_ACTIONS && !isSetAction(it) }
            if (editing.isEmpty()) return emptyList()
            val row = ItemResult(Kind.Creation, kind.name, "New " + kind.label, "")
            return listOf(ActionGroup(kind, listOf(row), editing, heading = "Default configuration of a new " + kind.label))
        }
        val general = sections.firstOrNull { it.first == null }?.second.orEmpty()
        // "Open all" and "Remove from list": the first micro group of every group of elements (user rule 2026-10-13).
        val first = general.filter { it == AddedAction.OpenEach }
        val kinds = sortedByReach(sections.filter { it.first != null }, added)
        val reached = kinds.filter { membersOf(it.first).isNotEmpty() }
        val out = ArrayList<ActionGroup>()
        val lone = added.singleOrNull()
        if (lone != null) {
            val mine = reached.map { (kind, actions) -> of(lone, kind, actions) }
            val actions = first + mine.flatMap(::own) + mine.flatMap(::set) + general
            // The default configuration of its kind is inside its group: it is the only one of it (user rule 2026-10-12).
            if (actions.isNotEmpty()) {
                out += ActionGroup(actionKindOf(lone), added, actions.distinct(), element = true, inner = reached.flatMap { defaults(it.first, it.second) })
            }
        } else {
            val whole = reached.filter { membersOf(it.first).size == added.size }
            val wide = first + general + whole.flatMap { set(it.second) } + whole.flatMap { bulk(it.second) }
            if (wide.isNotEmpty()) {
                out += ActionGroup(whole.singleOrNull()?.first, added, wide.distinct(), inner = whole.flatMap { defaults(it.first, it.second) })
            }
            for ((kind, actions) in reached) {
                val members = membersOf(kind)
                if (members.size < 2 || members.size == added.size) continue
                val about = first + set(actions) + bulk(actions)
                if (about.isNotEmpty()) out += ActionGroup(kind, members, about, inner = defaults(kind, actions))
            }
            for (element in added) {
                val mine = reached.filter { element in membersOf(it.first) }
                val actions =
                    first + mine.flatMap { own(of(element, it.first, it.second)) } +
                        mine.filter { membersOf(it.first).size == 1 }.flatMap { set(of(element, it.first, it.second)) }
                // …and the default configuration of a kind it is the only one of (the others have it in their group).
                val inner = mine.filter { membersOf(it.first).size == 1 }.flatMap { defaults(it.first, it.second) }
                if (actions.isNotEmpty()) out += ActionGroup(actionKindOf(element), listOf(element), actions.distinct(), element = true, inner = inner)
            }
        }
        kinds.filter { membersOf(it.first).isEmpty() }.forEach { (kind, actions) -> out += ActionGroup(kind, emptyList(), actions) }
        return out
    }

    /**
     * The actions no longer LISTED (user rule 2026-10-11: *"remove the 'Drag on the calendar'"* — the drag is a button
     * of "Blocks on the calendar" now, over the blocks checked there). Kept in the enum: an item of the lateral menu
     * made from one before is named by it, and still draws its editor. "Remove every element from the list" went the
     * same way (user rule 2026-10-13): its button stands beside "Open all", in the first micro group of every group.
     */
    val RETIRED_ACTIONS: Set<AddedAction> =
        setOf(
            AddedAction.DragOnCalendar, AddedAction.ClearList, AddedAction.ElementAddParent, AddedAction.ElementRemoveParent,
            // User rule 2026-11-21: the calendar's view is its right-click menu's (`CalendarUi`'s CALENDAR_MENU_VIEW_PARTS).
            AddedAction.CalendarDay, AddedAction.CalendarDisplay, AddedAction.CalendarLocks,
        )

    /** The general actions about the calendar: listed for the elements that can be on it ([CALENDAR_ADD_KINDS]). */
    val CALENDAR_ACTIONS: Set<AddedAction> =
        setOf(AddedAction.PlaceOnCalendar, AddedAction.CalendarBlocks, AddedAction.DragOnCalendar)

    /**
     * Whose blocks the "Drag on the calendar" action holds — the added elements that can be on the calendar, by what
     * a block of each is known by there: a task's id, a period's kind, a reminder's id, an alarm's or a timer's id.
     */
    data class CalendarDragTargets(
        val taskIds: Set<TaskId> = emptySet(),
        val periodKinds: Set<String> = emptySet(),
        val reminderIds: Set<String> = emptySet(),
        val ringIds: Set<String> = emptySet(),
    ) {
        val isEmpty: Boolean get() = taskIds.isEmpty() && periodKinds.isEmpty() && reminderIds.isEmpty() && ringIds.isEmpty()
    }

    /** The [CalendarDragTargets] of the [added] elements: the tasks, periods, reminders, alarms and timers among them. */
    fun calendarDragTargets(state: SchedulerState, added: List<Result>): CalendarDragTargets {
        fun ids(kind: Kind) = added.filterIsInstance<ItemResult>().filter { it.kind == kind }.mapTo(LinkedHashSet()) { it.id }
        return CalendarDragTargets(
            taskIds = addedTaskIds(state, added).toSet(),
            periodKinds = ids(Kind.RestrictivePeriod),
            reminderIds = ids(Kind.Reminder),
            ringIds = ids(Kind.Alarm) + ids(Kind.Timer),
        )
    }

    /**
     * The instant the "Drag on the calendar" action picks the blocks at until the user says otherwise: the right-click
     * the window was opened from (the calendar's "edit…" and "add…"), else the calendar filter's instant.
     */
    fun calendarDragDefaultAt(config: Config): Long? =
        config.calendarClickMillis ?: config.filters.calendarAtMillis ?: config.filters.calendarAddAtMillis

    /** The id a default configuration wears among the added elements of its kind: no element's own. */
    const val DEFAULT_CONFIGURATION_ID: String = "(default configuration)"

    /** The kinds whose added elements share ONE title field ([AddedAction.AlarmTitle]…), by that action. */
    val TITLED_KINDS: Map<AddedAction, Kind> by lazy {
        mapOf(
            AddedAction.AlarmTitle to Kind.Alarm,
            AddedAction.TimerTitle to Kind.Timer,
            AddedAction.ChronoTitle to Kind.Chrono,
            AddedAction.WorkplaceTitle to Kind.WorkplaceSetup,
            AddedAction.QuotaTitle to Kind.Quota,
            AddedAction.ReminderTitle to Kind.Reminder,
        )
    }

    /**
     * The stored title of every added element of [kind] the account still holds, by the row's id — what the group's
     * title field reads ([sharedTitle]) and what Escape puts back ([AddedCommand.Titles]). The STORED one: a nameless
     * alarm's is blank, not the "Alarm" its row shows.
     */
    fun addedTitles(state: SchedulerState, added: List<Result>, kind: Kind): Map<String, String> {
        val ids = addedIds(added, kind).toSet()
        val all: List<Pair<String, String>> =
            when (kind) {
                Kind.Alarm -> state.alarms.map { it.id to it.label }
                Kind.Timer -> state.timers.map { it.id to it.label }
                Kind.Chrono -> state.chronos.map { it.id to it.label }
                Kind.WorkplaceSetup -> state.workplaceSetups.map { it.id to it.title }
                Kind.Quota -> state.quotas.map { it.id to it.title }
                Kind.Reminder -> state.chores.map { it.id.ifEmpty { it.title } to it.title }
                else -> emptyList()
            }
        val own = all.filter { it.first in ids }.toMap()
        // An added "New quota" creation row: the DEFAULT configuration's title is one of the titles the field edits
        // (anomaly 2026-10-03: with only that row added the title field had nothing to edit, and was disabled).
        return if (kind == Kind.Quota && defaultConfigurationAdded(added, Kind.Quota)) {
            mapOf(DEFAULT_CONFIGURATION_ID to state.newQuotaDefaults.title) + own
        } else {
            own
        }
    }

    /**
     * [addedAlarms], and first the account's default configuration of a new alarm (wearing [DEFAULT_CONFIGURATION_ID])
     * where the "New alarm" row is among [added] — the timers' rule ([addedTimersOrDefault]).
     */
    fun addedAlarmsOrDefault(state: SchedulerState, added: List<Result>): List<org.example.project.scheduler.model.AlarmEntry> {
        val own = addedAlarms(state, added)
        return if (defaultConfigurationAdded(added, Kind.Alarm)) listOf(state.newAlarmDefaults.copy(id = DEFAULT_CONFIGURATION_ID)) + own else own
    }

    /** [addedReminders], and first the default configuration of a new reminder where the "New reminder" row is added. */
    fun addedRemindersOrDefault(state: SchedulerState, added: List<Result>): List<org.example.project.scheduler.model.ChoreEntry> {
        val own = addedReminders(state, added)
        return if (defaultConfigurationAdded(added, Kind.Reminder)) listOf(state.newReminderDefaults.copy(id = DEFAULT_CONFIGURATION_ID)) + own else own
    }

    /** The added alarms the account still holds, in the list's order — what the alarm group's shared fields read. */
    fun addedAlarms(state: SchedulerState, added: List<Result>): List<org.example.project.scheduler.model.AlarmEntry> {
        val byId = state.alarms.associateBy { it.id }
        return addedIds(added, Kind.Alarm).mapNotNull { byId[it] }
    }

    /** The reminders list set to [next] ([SchedulerIntent.SetChores], anchored on today), or nothing when unchanged. */
    private fun remindersIntent(
        state: SchedulerState,
        next: List<org.example.project.scheduler.model.ChoreEntry>,
        nowMillis: Long,
        timeZone: TimeZone,
    ): List<SchedulerIntent> {
        if (next == state.chores) return emptyList()
        val todayStart = Instant.fromEpochMilliseconds(nowMillis).toLocalDateTime(timeZone).date
            .atStartOfDayIn(timeZone).toEpochMilliseconds()
        return listOf(SchedulerIntent.SetChores(next, todayStart, nowMillis))
    }

    /**
     * What the timers' SETTINGS actions edit: the added timers — led by the **default configuration** of a new timer
     * (wearing [DEFAULT_CONFIGURATION_ID]) where the "New timer" row is among [added], which is what the group of the
     * default configuration hands them ([actionGroups]). Never the run's actions: a default has none.
     */
    fun addedTimersOrDefault(state: SchedulerState, added: List<Result>): List<org.example.project.scheduler.model.TimerEntry> {
        val own = addedTimers(state, added)
        return if (defaultConfigurationAdded(added, Kind.Timer)) listOf(state.newTimerDefaults.copy(id = DEFAULT_CONFIGURATION_ID)) + own else own
    }

    /** [addedWorkplaceSetups], and first the default configuration of a new one where the "New workplace setup" row is added. */
    fun addedWorkplaceSetupsOrDefault(state: SchedulerState, added: List<Result>): List<WorkplaceSetup> {
        val own = addedWorkplaceSetups(state, added)
        return if (defaultConfigurationAdded(added, Kind.WorkplaceSetup)) listOf(state.newWorkplaceDefaults.copy(id = DEFAULT_CONFIGURATION_ID)) + own else own
    }

    /** [change] on the setups [ids]: the default configuration, among them, being its own setting, not a row of the list. */
    fun workplaceEdits(state: SchedulerState, ids: Set<String>, editKey: String? = null, change: (WorkplaceSetup) -> WorkplaceSetup): List<SchedulerIntent> {
        val next = state.workplaceSetups.map { if (it.id in ids) change(it) else it }
        return listOfNotNull(
            SchedulerIntent.SetNewWorkplaceDefaults(change(state.newWorkplaceDefaults)).takeIf { DEFAULT_CONFIGURATION_ID in ids },
            SchedulerIntent.SetWorkplaceSetups(next, editKey).takeIf { next != state.workplaceSetups },
        )
    }

    /** The added workplace setups the account still holds, in the list's order. */
    fun addedWorkplaceSetups(state: SchedulerState, added: List<Result>): List<WorkplaceSetup> {
        val byId = state.workplaceSetups.associateBy { it.id }
        return addedIds(added, Kind.WorkplaceSetup).mapNotNull { byId[it] }
    }

    /** The added chronos the account still holds, in the list's order. */
    fun addedChronos(state: SchedulerState, added: List<Result>): List<org.example.project.scheduler.model.ChronoEntry> {
        val byId = state.chronos.associateBy { it.id }
        return addedIds(added, Kind.Chrono).mapNotNull { byId[it] }
    }

    /** The added timers the account still holds, in the list's order. */
    fun addedTimers(state: SchedulerState, added: List<Result>): List<org.example.project.scheduler.model.TimerEntry> {
        val byId = state.timers.associateBy { it.id }
        return addedIds(added, Kind.Timer).mapNotNull { byId[it] }
    }

    /** The added reminders the account still holds, in the list's order (a row's id: the reminder's, else its title). */
    fun addedReminders(state: SchedulerState, added: List<Result>): List<org.example.project.scheduler.model.ChoreEntry> {
        val byId = state.chores.associateBy { it.id.ifEmpty { it.title } }
        return addedIds(added, Kind.Reminder).mapNotNull { byId[it] }
    }

    /** What a reminder's "Every" field reads: the formula it was typed as, else its cadence in its own unit. */
    fun reminderEveryText(chore: org.example.project.scheduler.model.ChoreEntry): String =
        chore.daysFormula.ifBlank {
            val n = chore.recurrenceUnit.fromDays(chore.spanDays)
            if (n == n.toLong().toDouble()) n.toLong().toString() else n.toString()
        }

    /** [chore] recurring every [text] (a number or a formula) of [unit] — the reminder editor's own arithmetic. */
    fun withReminderEvery(chore: org.example.project.scheduler.model.ChoreEntry, text: String, unit: org.example.project.scheduler.model.ChoreRecurrenceUnit): org.example.project.scheduler.model.ChoreEntry =
        chore.copy(
            daysFormula = text,
            recurrenceUnit = unit,
            spanDays = unit.toDays(SchedulerDomain.evaluateDayFormula(text) ?: 0.0),
        )

    /** The value every one of [items] has for [read], or null when they differ (or there is none): a shared field's. */
    fun <T, V : Any> sharedValue(items: List<T>, read: (T) -> V): V? = items.map(read).distinct().singleOrNull()

    /**
     * The alert the alarm group's one editor shows for [alerts]: a channel is on only when it is on for EVERY one
     * (so a press says "all of them"), the tone the shared one, else the first's.
     */
    fun sharedAlert(alerts: List<org.example.project.scheduler.model.AlertSettings>): org.example.project.scheduler.model.AlertSettings? {
        val first = alerts.firstOrNull() ?: return null
        return first.copy(
            sound = alerts.all { it.sound },
            voice = alerts.all { it.voice },
            notification = alerts.all { it.notification },
            vibrate = alerts.all { it.vibrate },
        )
    }

    /**
     * [alert] with only what the editor CHANGED between [shown] and [edited] — the channels and the tone nobody
     * touched stay each alarm's own.
     */
    fun withAlertChange(
        alert: org.example.project.scheduler.model.AlertSettings,
        shown: org.example.project.scheduler.model.AlertSettings,
        edited: org.example.project.scheduler.model.AlertSettings,
    ): org.example.project.scheduler.model.AlertSettings =
        alert.copy(
            sound = if (edited.sound != shown.sound) edited.sound else alert.sound,
            tone = if (edited.tone != shown.tone) edited.tone else alert.tone,
            voice = if (edited.voice != shown.voice) edited.voice else alert.voice,
            notification = if (edited.notification != shown.notification) edited.notification else alert.notification,
            vibrate = if (edited.vibrate != shown.vibrate) edited.vibrate else alert.vibrate,
        )

    /**
     * The days chips of the alarm group: a day lit in [shown] (on for every alarm) and no longer in [edited] leaves
     * every alarm, one newly in [edited] joins every alarm — never leaving an alarm with no day to ring on.
     */
    fun withDaysChange(days: Set<kotlinx.datetime.DayOfWeek>, shown: Set<kotlinx.datetime.DayOfWeek>, edited: Set<kotlinx.datetime.DayOfWeek>): Set<kotlinx.datetime.DayOfWeek> =
        (days + (edited - shown) - (shown - edited)).ifEmpty { days }

    /** What the title field shows: the one title every element of [titles] has, else empty. */
    fun sharedTitle(titles: Map<String, String>): String = titles.values.distinct().singleOrNull().orEmpty()

    /** A timer's or a chrono's run-state step, as its own row's buttons take it. */
    enum class RunStep(val label: String) { Start("start"), Pause("pause"), Reset("reset") }

    /** One action on the added elements, with its value: what [addedIntents] turns into the app's own intents. */
    sealed interface AddedCommand {
        data class Category(val categoryId: CategoryId, val carried: Boolean) : AddedCommand

        /** User rule 2026-11-02: the element [parent] made a parent of every added element ([on]), or no longer one. */
        data class ElementParent(val parent: String, val on: Boolean) : AddedCommand

        /**
         * Anomaly 2026-11-19 (*"in 'add a parent', there must be the creation task element in the first place"*): a new task
         * titled [title] (a unique "New task" when blank), made a parent of every added element it may be one of.
         */
        data class NewParentTask(val title: String) : AddedCommand

        data class MinimumTime(val minutes: Int) : AddedCommand

        data class AlarmsOn(val on: Boolean) : AddedCommand

        /**
         * The titles of elements of [kind], by id — every added one given what is typed, or each given back the title
         * it had (Escape). [editKey] names the field's typing session, so its keystrokes are one History Unit.
         */
        data class Titles(val kind: Kind, val titles: Map<String, String>, val editKey: String? = null) : AddedCommand

        /**
         * [change] applied to every added alarm, as ONE list edit ([SchedulerIntent.SetAlarms]) — a shared field's
         * write: the time, a weekday, the ring's length, the repeat, a channel of the alert. [editKey] names a text
         * field's typing session (one History Unit); null for a switch or a chip.
         */
        class AlarmsEdit(
            val editKey: String? = null,
            val change: (org.example.project.scheduler.model.AlarmEntry) -> org.example.project.scheduler.model.AlarmEntry,
        ) : AddedCommand

        /**
         * Every added element of [kind] deleted from the account — its window's bin, over all of them: one list edit
         * for the alarms, timers, chronos and reminders, [SchedulerIntent.DeleteCategory] per category.
         */
        data class Delete(val kind: Kind) : AddedCommand

        /** [AlarmsEdit] for the added timers ([SchedulerIntent.SetTimers]). */
        class TimersEdit(val editKey: String? = null, val change: (org.example.project.scheduler.model.TimerEntry) -> org.example.project.scheduler.model.TimerEntry) : AddedCommand

        /** [AlarmsEdit] for the added reminders ([SchedulerIntent.SetChores]). */
        class RemindersEdit(val change: (org.example.project.scheduler.model.ChoreEntry) -> org.example.project.scheduler.model.ChoreEntry) : AddedCommand

        /** Every added alarm's time of day set to the clock's, to the minute — its days, and an isolated ring's date, kept. */
        data object AlarmsTimeNow : AddedCommand

        /** Every added reminder's time in the day set to the clock's, to the minute. */
        data object RemindersTimeNow : AddedCommand

        data class TimersRun(val step: RunStep) : AddedCommand

        data class ChronosRun(val step: RunStep) : AddedCommand

        /** The task edit window's resilience over the added schedulable leaves ([SchedulerIntent.SetPeriodResilience]). */
        data class Resilience(val kind: String, val value: Double) : AddedCommand

        data class ScheduleUnit(val entries: List<org.example.project.scheduler.model.ScheduleUnitEntry>) : AddedCommand

        /** User rule 2026-11-19: a new md element of [text], associated with every added element ([MdDomain]). */
        data class MdAdd(val text: String = "") : AddedCommand

        /** User rule 2026-11-19: md element [id]'s text, [editKey] coalescing what is typed into one field. */
        data class MdSetText(val id: String, val text: String, val editKey: String? = null) : AddedCommand

        /** User rule 2026-11-19: md element [id] no longer associated with the added elements (or, [keys] given, with those). */
        data class MdDissociate(val id: String, val keys: List<String>? = null) : AddedCommand

        /** User rule 2026-11-16: one place's weight as a formula of the instant (blank: the constant). */
        data class WeightFormula(val cellId: CellId, val formula: String) : AddedCommand

        /** User rule 2026-11-16: every added task's minimum length as a formula of the instant (blank: the constant). */
        data class MinimumFormula(val formula: String) : AddedCommand

        /** User rule 2026-11-13: the name of every tree the added tasks belong to. */
        data class TreeName(val name: String) : AddedCommand

        /** Null = the top level. */
        data class AddUnder(val parentTaskId: TaskId?) : AddedCommand

        data object CollapseSubtrees : AddedCommand

        data object AddDefaultSubtree : AddedCommand

        /** The drawing of every added period ([SchedulerIntent.SetPeriodDrawing]). */
        data class Drawing(val drawing: PeriodDrawing) : AddedCommand

        /** The task edit window's ✕ on one of a task's places ([SchedulerIntent.RemoveTaskPath]). */
        data class RemovePath(val cellId: CellId) : AddedCommand

        /**
         * An intent an element's own editor writes as it is (a category's rules, a period's combinations) — the removed
         * edit window's write, which needs no translation; routed here so the actions keep one write path.
         */
        data class Raw(val intent: SchedulerIntent) : AddedCommand

        /** Every added period of the account's own deleted ([SchedulerIntent.RemovePeriodKind]); a default one stays. */
        data object DeletePeriods : AddedCommand

        /** Every added DEFAULT period back to how the app ships it ([SchedulerIntent.ResetPeriodKinds]). */
        data object ResetDefaultPeriods : AddedCommand
    }

    /** The periods among [added] the account still holds, each once, in the list's order. */
    fun addedPeriodKinds(state: SchedulerState, added: List<Result>): List<String> =
        added.filterIsInstance<ItemResult>()
            .filter { it.kind == Kind.RestrictivePeriod && it.id in state.allPeriodKinds }
            .map { it.id }
            .distinct()

    // ----- The calendar filter (user rule 2026-10-01) ----------------------------------------------------

    /**
     * User rule 2026-10-07: the three states of the calendar filter of the Search window the calendar's "add…" opens —
     * *"what can be added without removing anything where the user right-clicked, what can be added, or no filter"*.
     */
    enum class CalendarAddFilter(val label: String) {
        KeepingEverything("Can be added without removing anything"),
        Addable("Can be added"),
        None("No filter"),
    }

    /**
     * [state] cut down to what laying something over `[atMillis, endMillis)` can touch — the panels within a day of
     * the span, no history, no re-plan — so [calendarAddKeepsEverything] can be asked of every row of a list.
     */
    fun calendarAddSurroundings(state: SchedulerState, atMillis: Long, endMillis: Long): SchedulerState {
        val day = 24L * 60L * 60L * 1000L
        return state.copy(
            panels = state.panels.filter { it.endEpochMillis > atMillis - day && it.startEpochMillis < endMillis + day },
            histories = org.example.project.scheduler.state.SchedulerHistories(),
            automaticSchedule = false,
        )
    }

    /**
     * User rule 2026-10-07: **whether adding [result] to the calendar over `[atMillis, endMillis)` removes nothing** —
     * every panel there (a task's, a period's, whoever laid it) still stands over all it stood over, and no recorded
     * work is gone. Asked of the ADD ITSELF: the drafts "Add to the calendar" lays ([calendarDrafts]) are put through
     * the reducer that lays them, on [around] ([calendarAddSurroundings]), and what was there is looked for in what
     * is left — never a second reading of what a period refuses or what gives way to what. A row that lays no panel
     * (an alarm's ring, a timer, a "creation" row) removes nothing.
     */
    fun calendarAddKeepsEverything(
        around: SchedulerState,
        result: Result,
        atMillis: Long,
        endMillis: Long,
        timeZone: TimeZone = TimeZone.currentSystemDefault(),
    ): Boolean {
        val drafts =
            calendarDrafts(around, listOf(result), atMillis, timeZone, endMillis)
                .filter { it.kind != CalendarElements.Kind.Alarm }
                .map { draft ->
                    // Seeded as the element window seeds a task's panel: the task's own answer to "no screen".
                    if (draft.kind != CalendarElements.Kind.TaskPanel) draft
                    else draft.copy(noScreenResilience = draft.taskId?.let { around.tasks[it]?.resilienceFor(PeriodKinds.NO_SCREEN) } ?: 0.0)
                }
        if (drafts.isEmpty()) return true
        val after =
            org.example.project.scheduler.state.SchedulerReducer.reduce(
                around, org.example.project.scheduler.state.SchedulerIntent.AddCalendarElements(drafts),
            )
        val panelsKept =
            around.panels.all { was ->
                after.panels.any {
                    it.taskId == was.taskId && it.restrictiveKind == was.restrictiveKind && it.chore == was.chore &&
                        it.startEpochMillis <= was.startEpochMillis && it.endEpochMillis >= was.endEpochMillis
                }
            }
        return panelsKept && around.tasks.all { (id, task) -> after.tasks[id]?.record == task.record }
    }

    /**
     * The kinds of what the calendar's "add…" can lay — what its Search window lists. Not "creation": its rows make an
     * element NOW, not at the right-click (the filter still keeps them when the user checks that kind).
     */
    val CALENDAR_ADD_KINDS: Set<Kind> = setOf(Kind.Task, Kind.RestrictivePeriod, Kind.Reminder, Kind.Alarm, Kind.Timer)

    /** The kinds a "creation" row the calendar filter keeps can be: the ones the calendar lays a new one of. */
    private val CALENDAR_CREATABLE: Set<Kind> = setOf(Kind.Task, Kind.RestrictivePeriod, Kind.Alarm, Kind.Reminder)

    /**
     * The Search window the calendar's "add…" opens at [atMillis] (user rule 2026-10-01): what can be added there,
     * the filter on that instant, and the right-click remembered for the filter's button.
     */
    fun calendarAddConfig(atMillis: Long): Config =
        Config(
            kinds = CALENDAR_ADD_KINDS,
            filters = Filters(calendarAddOn = true, calendarAddAtMillis = atMillis),
            calendarClickMillis = atMillis,
            // User rule 2026-10-11: what "add…" is for comes first among the actions.
            actionSearch = CALENDAR_ADD_ACTION_SEARCH,
        )

    /** The kinds of what can be on the calendar at an instant — what the calendar's "edit…" lists. */
    val CALENDAR_AT_KINDS: Set<Kind> = setOf(Kind.Task, Kind.RestrictivePeriod, Kind.Reminder, Kind.Alarm, Kind.Timer)

    /**
     * How far from the right-click a mark with no length (a reminder tag, an alarm's ring, a timer's end) may be and
     * still be "there": the calendar hit-tests those by the height they are DRAWN at, which no instant can say.
     */
    const val CALENDAR_MARK_TOLERANCE_MILLIS: Long = 15 * 60_000L

    /**
     * The Search window the calendar's "edit…" opens at [atMillis] (user rule 2026-10-01): what is on the timeline
     * there, the filter on that instant, and the right-click remembered for the filter's button — listed in the
     * order the hover bubble names them there ([CALENDAR_BUBBLE_SORT], user rule 2026-10-02).
     */
    fun calendarAtConfig(atMillis: Long): Config =
        Config(
            kinds = CALENDAR_AT_KINDS,
            filters = withBoxesAt(Filters(calendarAtOn = true, calendarAtMillis = atMillis), atMillis),
            sorts = withCalendarBubbleSort(DEFAULT_SORTS),
            calendarClickMillis = atMillis,
        )

    /**
     * User rule 2026-10-11: *"the fields 'Every box from' and 'Every box until' should be both set to the date and
     * time of the right-click"* — [filters] with the four of them (a task's, a period's) at [atMillis]. What the
     * calendar's "edit…" opens with, and moves an open window to.
     */
    fun withBoxesAt(filters: Filters, atMillis: Long): Filters =
        filters.copy(taskBoxesFrom = atMillis, taskBoxesUntil = atMillis, periodBoxesFrom = atMillis, periodBoxesUntil = atMillis)

    /** The four "Every box from / until" fields, as what a pick on the calendar writes ([withBoxBound]). */
    enum class BoxBound { TaskFrom, TaskUntil, PeriodFrom, PeriodUntil, BothFrom, BothUntil }

    /** [filters] with the bound [which] at [atMillis] (null: no bound on that side). */
    fun withBoxBound(filters: Filters, which: BoxBound, atMillis: Long?): Filters =
        when (which) {
            BoxBound.TaskFrom -> filters.copy(taskBoxesFrom = atMillis)
            BoxBound.TaskUntil -> filters.copy(taskBoxesUntil = atMillis)
            BoxBound.PeriodFrom -> filters.copy(periodBoxesFrom = atMillis)
            BoxBound.PeriodUntil -> filters.copy(periodBoxesUntil = atMillis)
            // The shared field of the group every element (or both kinds) is in: a task's and a period's at once.
            BoxBound.BothFrom -> filters.copy(taskBoxesFrom = atMillis, periodBoxesFrom = atMillis)
            BoxBound.BothUntil -> filters.copy(taskBoxesUntil = atMillis, periodBoxesUntil = atMillis)
        }

    /**
     * How far past the right-click a screen break may START and still be "there": the calendar's "edit…" names its
     * instant to the MINUTE, and a 20 s break is shorter than that — at the instant itself it was never found.
     */
    const val CALENDAR_BREAK_WINDOW_MILLIS: Long = 60_000L

    /**
     * **The screen breaks drawn on the calendar at [atMillis]** among [breaks] (each a span and the kind the break
     * is, `DynamicPeriods.breakKind`): those covering it, or starting within [CALENDAR_BREAK_WINDOW_MILLIS] after it.
     * The breaks are not in the state's panels (the line banks them locally, the look ahead predicts them), so
     * [calendarElementsAt] is handed their kinds by the calendar, with the layer bands' (anomaly 2026-10-03: "edit…"
     * on a past 20 s break listed no break).
     */
    fun calendarBreaksAt(breaks: List<Pair<TaskTimeRange, String>>, atMillis: Long): List<Pair<TaskTimeRange, String>> =
        breaks.filter { (span, _) ->
            span.endEpochMillis > atMillis && span.startEpochMillis < atMillis + CALENDAR_BREAK_WINDOW_MILLIS
        }

    /**
     * The period kinds a break of [breakKind] is on the calendar: its own, what the account's rules make it carry,
     * and "no screen" — *"always accompanied by the 'no screen' period"* whatever the rules say.
     */
    fun calendarBreakKinds(breakKind: String, config: PeriodKindConfig): Set<String> =
        config.kindsOf(breakKind.ifBlank { PeriodKinds.INACTIVITY }) + PeriodKinds.NO_SCREEN

    /** The whole list in the calendar hover bubble's order ([SortKey.CalendarBubble]). */
    val CALENDAR_BUBBLE_SORT: SortMethod = SortMethod(null, SortKey.CalendarBubble)

    /** [sorts] with [CALENDAR_BUBBLE_SORT] on and DOMINANT — what the calendar's "edit…" turns on. */
    fun withCalendarBubbleSort(sorts: List<SortMethod>): List<SortMethod> =
        listOf(CALENDAR_BUBBLE_SORT) + sorts.filterNot { it.sameMethod(CALENDAR_BUBBLE_SORT) }

    /**
     * **The keys ([keyOf]) of what is on the calendar at [atMillis]**: a task one of whose placed boxes or records
     * covers it; every kind of period covering it ([calendarKindsAt], what each carries included); a reminder with a tag,
     * an armed alarm ringing (that weekday, that time of day) and a running timer ending, within
     * [CALENDAR_MARK_TOLERANCE_MILLIS] of it.
     */
    fun calendarElementsAt(
        state: SchedulerState,
        atMillis: Long,
        timeZone: TimeZone = TimeZone.currentSystemDefault(),
        /** The calendar's layer bands there ("no computer unlocked", "not on a phone"…): see [results]. */
        layerKindsAt: (Long) -> Set<String> = { emptySet() },
    ): Set<String> {
        val out = HashSet<String>()
        fun covers(start: Long, end: Long) = start <= atMillis && atMillis < end
        fun near(instant: Long) = kotlin.math.abs(instant - atMillis) <= CALENDAR_MARK_TOLERANCE_MILLIS
        // Anomaly 2026-10-11 (*"I right-clicked on the 15min break right after now line … but the result list has a
        // task"*): a task is NOT on the calendar where a screen break drawn there refuses it. Its stored run spans
        // the break — the rules hold it at the line across a break the line carries, and a panel placed by hand runs
        // through one — and the calendar draws it cut ([SchedulerDomain.atLine], the column's break holes); so must
        // this. Asked of the breaks the calendar DRAWS over the instant ([drawnPeriodKindsAt]).
        val breaksHere = drawnPeriodKindsAt(atMillis).orEmpty().filter { it in PeriodKinds.BREAK_KINDS }
        fun underBreak(taskId: TaskId) = breaksHere.any { SchedulerDomain.periodRefuses(state.tasks, taskId, it) }
        for (panel in SchedulerDomain.statedPanels(state)) {
            if (panel.chore) {
                if (near(panel.startEpochMillis)) {
                    SchedulerDomain.reminderIdOfChorePanel(panel.id)?.let { out += Kind.Reminder.name + "/" + it }
                }
                continue
            }
            if (covers(panel.startEpochMillis, panel.endEpochMillis)) panel.taskId?.takeIf { !underBreak(it) }?.let { out += taskKey(it) }
        }
        for (task in state.tasks.values) {
            if (!underBreak(task.id) && task.record.any { covers(it.startEpochMillis, it.endEpochMillis) }) out += taskKey(task.id)
        }
        (calendarKindsAt(state, atMillis) + layerKindsAt(atMillis)).forEach { out += Kind.RestrictivePeriod.name + "/" + it }
        val local = Instant.fromEpochMilliseconds(atMillis).toLocalDateTime(timeZone)
        val minuteOfDay = local.hour * 60 + local.minute
        val toleranceMinutes = (CALENDAR_MARK_TOLERANCE_MILLIS / 60_000L).toInt()
        for (alarm in state.alarms) {
            if (!alarm.enabled || ((alarm.isolated || alarm.days.isNotEmpty()) && !alarm.ringsOn(local.date))) continue
            val distance = kotlin.math.abs(alarm.timeOfDayMinutes - minuteOfDay).let { minOf(it, 24 * 60 - it) }
            if (distance <= toleranceMinutes) out += Kind.Alarm.name + "/" + alarm.id
        }
        for (timer in state.timers) {
            if (timer.endsAtMillis?.let(::near) == true) out += Kind.Timer.name + "/" + timer.id
        }
        return out
    }

    /**
     * Every kind of restrictive period covering [atMillis] on the calendar, with what each carries
     * ([PeriodKindConfig.kindsOf]) — the environment a task laid there would stand in. Read off the panels, the
     * dynamic periods included: a task cannot be put inside a 20 s break that accepts nobody.
     */
    fun calendarKindsAt(state: SchedulerState, atMillis: Long): Set<String> {
        val config = state.periodKindConfig
        // As the calendar draws them where it shows the instant: a period that gave way to a line at a screen is not
        // there (anomaly 2026-10-07 — at 03:14, at the screen inside the schedule's Sleep window, the stored window
        // still covered the whole night and no task could be "added": the list held the creation row alone).
        val drawn = drawnPeriodKindsAt(atMillis)
        val kinds =
            drawn ?: SchedulerDomain.statedPanels(state)
                .filter { it.startEpochMillis <= atMillis && atMillis < it.endEpochMillis }
                .mapNotNull { it.restrictiveKind.takeIf(String::isNotEmpty) }
        return kinds.flatMapTo(HashSet()) { config.kindsOf(it) }
    }

    /**
     * **The kinds of the periods the calendar DRAWS at an instant**, or null where it shows nothing of it — injected by
     * `App`, which derives them. The stored panels cannot say it alone: a Sleep window the schedule lays is stored
     * over its whole night, and the rules cut it where the line crossed it at a screen (`scheduler.md` § *A mode-1 line
     * retracts*) from what the devices observed, which is not in the state. None where nothing draws a calendar (a
     * test, a headless engine): the stored panels are then the answer.
     */
    var drawnPeriodKindsAt: (Long) -> Set<String>? = { null }

    /**
     * Whether [result] can be added to the calendar at [atMillis] — what the calendar can show there: a task the
     * scheduler may place (a leaf in the tree), WHATEVER PERIOD STANDS THERE (anomaly 2026-10-07: "add…" on a Sleep
     * period listed no task under "Can be added" — the user: *"all the schedulable tasks … must appear in the result
     * list. If the filter was 'can be added without removing anything', then the result list would only show the task
     * creation element, since the current configurations don't allow any task during a sleep period"*; what the
     * periods there refuse is the stricter state's question, [calendarTaskStandsIn]); any kind of restrictive period; a reminder (a tag of it); an
     * alarm (user rule 2026-10-01: it then rings at that time of day, on that weekday too — [calendarDrafts]); a timer
     * the instant is still ahead of and within its longest run (it then ends there — [calendarTimerIntents]); and the
     * "creation" rows of the kinds the calendar lays a new one of. Nothing else is.
     */
    fun calendarAddable(state: SchedulerState, result: Result, atMillis: Long, nowMillis: Long): Boolean =
        when (result) {
            is TaskResult -> state.tasks[result.taskId] != null && SchedulerDomain.isPlaceableTask(state, result.taskId)
            is ItemResult -> when (result.kind) {
                Kind.RestrictivePeriod -> result.id in state.allPeriodKinds
                Kind.Reminder -> true
                Kind.Alarm -> state.alarms.any { it.id == result.id }
                Kind.Timer -> state.timers.any { it.id == result.id } && timerCanEndAt(atMillis, nowMillis)
                Kind.Creation -> Kind.entries.firstOrNull { it.name == result.id } in CALENDAR_CREATABLE
                else -> false
            }
        }

    /**
     * **"Without removing anything"**, for a task: whether its resilience lets it run in the periods [kindsAt] standing
     * where it would be added (their product above 0, [PeriodKinds.multiplier]). One that cannot could only stand
     * there with the period gone — and the periods the calendar derives (the schedule's Sleep window, a break) are in
     * no panel the add's reducer could be seen to remove. Every other row passes.
     */
    fun calendarTaskStandsIn(state: SchedulerState, result: Result, kindsAt: Set<String>): Boolean =
        result !is TaskResult || state.tasks[result.taskId]?.let { PeriodKinds.multiplier(it.resilience, kindsAt) > 0.0 } != false

    /** Whether a timer started now can end at [atMillis]: ahead of the clock, and no further than its longest run. */
    private fun timerCanEndAt(atMillis: Long, nowMillis: Long): Boolean =
        atMillis > nowMillis && atMillis - nowMillis <= org.example.project.scheduler.model.TimerEntry.MAX_TIMER_SECONDS * 1_000L

    /**
     * "Add to the calendar" for the added TIMERS: each one put on the clock so that it ends at [atMillis] — reset, its
     * time left `atMillis − now`, started ([TimerDomain.withRemaining], [TimerDomain.started]) — as ONE list edit. A
     * timer that cannot end there is left alone; none to move is no intent.
     */
    fun calendarTimerIntents(state: SchedulerState, added: List<Result>, atMillis: Long, nowMillis: Long): List<SchedulerIntent> {
        if (!timerCanEndAt(atMillis, nowMillis)) return emptyList()
        val ids = addedIds(added, Kind.Timer).toSet()
        if (ids.isEmpty()) return emptyList()
        val timers = state.timers.map { timer -> if (timer.id !in ids) timer else timerEndingAt(timer, atMillis, nowMillis) }
        return if (timers == state.timers) emptyList() else listOf(SchedulerIntent.SetTimers(timers))
    }

    /** [timer] put on the clock to end at [atMillis]: reset, its time left `atMillis − now`, started. */
    private fun timerEndingAt(timer: org.example.project.scheduler.model.TimerEntry, atMillis: Long, nowMillis: Long) =
        TimerDomain.started(TimerDomain.withRemaining(TimerDomain.reset(timer), atMillis - nowMillis, nowMillis), nowMillis)

    /**
     * PRD §8: **a ring DRAGGED on the calendar to [atMillis]** — the one list edit it is, or null where it changes
     * nothing.
     *
     * An alarm is a RULE (orange), and the drag is about ONE of its rings — the one at [fromMillis] — so the rule
     * is left where it is, less that date ([AlarmEntry.skippedEpochDays]), and the dragged ring becomes an ISOLATED
     * row of its own on that date at the new time of day ([AlarmEntry.onlyOnEpochDay], to the minute), which is
     * what the calendar outlines blue: the rule's rings on the other days do not move (user rule 2026-10-02). A
     * ring that is already isolated is simply given the new time. A timer is put on the clock to end there, as
     * "Add to the calendar" does ([calendarTimerIntents]); one that cannot end there (the past, or beyond its
     * longest run) is left alone. See [SchedulerDomain.ringOutline].
     */
    fun calendarRingMoveIntent(
        state: SchedulerState,
        id: String,
        timer: Boolean,
        /** The instant the dragged ring was at — which of the alarm's occurrences this is. */
        fromMillis: Long,
        atMillis: Long,
        nowMillis: Long,
        timeZone: TimeZone = TimeZone.currentSystemDefault(),
    ): SchedulerIntent? {
        if (timer) {
            if (!timerCanEndAt(atMillis, nowMillis)) return null
            val timers = state.timers.map {
                if (it.id != id) it else timerEndingAt(it, atMillis, nowMillis).copy(calendarPlaced = true)
            }
            return if (timers == state.timers) null else SchedulerIntent.SetTimers(timers)
        }
        val alarm = state.alarms.firstOrNull { it.id == id } ?: return null
        val local = Instant.fromEpochMilliseconds(atMillis).toLocalDateTime(timeZone)
        val minutes = local.hour * 60 + local.minute
        if (minutes == alarm.timeOfDayMinutes) return null
        if (alarm.isolated) {
            return SchedulerIntent.SetAlarms(state.alarms.map { if (it.id != id) it else it.copy(timeOfDayMinutes = minutes) })
        }
        val date = Instant.fromEpochMilliseconds(fromMillis).toLocalDateTime(timeZone).date
        val day = date.toEpochDays().toLong()
        val today = Instant.fromEpochMilliseconds(nowMillis).toLocalDateTime(timeZone).date.toEpochDays().toLong()
        val isolated = alarm.copy(
            id = AlarmDomain.mintAlarmId(state.alarms.map { it.id }),
            timeOfDayMinutes = minutes,
            days = setOf(date.dayOfWeek),
            onlyOnEpochDay = day,
            skippedEpochDays = emptySet(),
            isolatedFrom = alarm.id,
        )
        // The dates a year behind are dropped as new ones are added, so the row does not grow for ever.
        val rule = alarm.copy(
            skippedEpochDays = alarm.skippedEpochDays.filterTo(mutableSetOf()) { it >= today - SKIPPED_DAYS_KEPT } + day,
        )
        return SchedulerIntent.SetAlarms(state.alarms.flatMap { if (it.id != id) listOf(it) else listOf(rule, isolated) })
    }

    /** How far back an alarm keeps the dates a ring was dragged away from ([AlarmEntry.skippedEpochDays]). */
    private const val SKIPPED_DAYS_KEPT: Long = 366L

    /**
     * The "Add to the calendar" action: the element-window drafts of every added element that can be added at
     * [atMillis] ([calendarAddable]), seeded as that window seeds a fresh one ([CalendarElements.seeded]) — a task's
     * panel, a period of that kind, a tag of that reminder. Saved through the window's own path (`App`), so one Save
     * is what it always was.
     */
    fun calendarDrafts(
        state: SchedulerState,
        added: List<Result>,
        atMillis: Long,
        timeZone: TimeZone = TimeZone.currentSystemDefault(),
        /**
         * Where a task's panel and a period end ([placementEnd]); null, or not after [atMillis] = each its own length.
         * A tag and a ring have no length of the calendar's to give.
         */
        endMillis: Long? = null,
    ): List<CalendarElements.Draft> {
        return added.filter { calendarAddable(state, it, atMillis, nowMillis = atMillis - 1) }.mapNotNull { row ->
            val pick = when {
                row is TaskResult -> CalendarElements.Draft(
                    kind = CalendarElements.Kind.TaskPanel,
                    name = state.tasks[row.taskId]?.title.orEmpty(),
                    taskId = row.taskId,
                )
                row is ItemResult && row.kind == Kind.RestrictivePeriod -> CalendarElements.Draft(
                    kind = CalendarElements.Kind.RestrictivePeriod,
                    name = PeriodKinds.periodTitle(row.id),
                    periodKind = row.id,
                )
                row is ItemResult && row.kind == Kind.Reminder -> CalendarElements.Draft(
                    kind = CalendarElements.Kind.Reminder,
                    name = row.name,
                    reminderId = row.id,
                )
                // An alarm already there: it now rings at that time of day, that weekday among its days, switched on
                // — the element window's own edit of an alarm (`existingId`), so its other settings stay.
                row is ItemResult && row.kind == Kind.Alarm -> {
                    val alarm = state.alarms.firstOrNull { it.id == row.id } ?: return@mapNotNull null
                    val weekday = Instant.fromEpochMilliseconds(atMillis).toLocalDateTime(timeZone).dayOfWeek
                    return@mapNotNull CalendarElements.Draft(
                        kind = CalendarElements.Kind.Alarm,
                        existingId = alarm.id,
                        name = alarm.label,
                        startMillis = atMillis,
                        endMillis = atMillis + alarm.soundSeconds * 1000L,
                        // An alarm on one date only moves to the start's date ([CalendarElements.alarmEdited]): its
                        // weekdays are for when it repeats again, and are not this day's business.
                        alarmDays = if (alarm.onlyOnEpochDay != null) alarm.days else alarm.days + weekday,
                        alert = alarm.alert,
                        alarmArmed = true,
                    )
                }
                // The alarms' "creation" row: a NEW alarm there (anomaly 2026-10-07 — it was a "New alarm here" button
                // shown whatever was added, beside a lone task too). What is laid is what the list holds.
                row is ItemResult && row.kind == Kind.Creation && row.id == Kind.Alarm.name ->
                    return@mapNotNull calendarAlarmDraft(state, atMillis)
                else -> null
            } ?: return@mapNotNull null
            CalendarElements.seeded(
                pick,
                atMillis,
                panelSpanMillis = panelSpanMillis(state, pick.taskId),
                noScreenResilience = pick.taskId?.let { state.tasks[it]?.resilienceFor(PeriodKinds.NO_SCREEN) },
                newAlarm = state.newAlarmDefaults,
                periodSpanMillis = periodOwnLength(state, pick.periodKind),
            ).let { draft ->
                val spans = draft.kind == CalendarElements.Kind.TaskPanel || draft.kind == CalendarElements.Kind.RestrictivePeriod
                if (spans && endMillis != null && endMillis > atMillis) draft.copy(endMillis = endMillis) else draft
            }
        }
    }

    /**
     * **Where "Add to the calendar" lays the added elements** (user rule 2026-10-05), said the way a quota's loop is:
     * a START — null until one is given, and the calendar filter's position stands for it then ([placementStart]) —
     * and an END stated as a LENGTH after the start ([endByDelta]) or as an instant of its own ([endMillis]; until one
     * is given, the length still says it). Local-only view state, kept with the Search window's configuration.
     *
     * The LENGTH is null until the user states one ([placementLength]): the added elements' own length stands for it
     * then ([placementDefaultLength]), else 1 hour (user rule 2026-10-05). Anomaly 2026-10-19 (*"the end field wasn't
     * already by default 15 minutes later. I had to click on the default button"*, and a 3-second ring's end 15 minutes
     * later): the length was a stored 1 hour the elements' own never replaced, and one stated for other elements stayed
     * on. So a stated length remembers the elements' own length it was stated over ([lengthOverMillis]) and gives way
     * once the added elements have another.
     */
    data class Placement(
        val startMillis: Long? = null,
        val endByDelta: Boolean = true,
        val lengthMillis: Long? = null,
        val lengthOverMillis: Long? = null,
        val endMillis: Long? = null,
    )

    /** What "Add to the calendar" ends after when neither the user nor the added elements say otherwise: 1 hour. */
    const val DEFAULT_PLACEMENT_LENGTH_MILLIS: Long = 3_600_000L

    /** The instant "Add to the calendar" lays at: the start given, else the calendar filter's position, else none. */
    fun placementStart(config: Config): Long? = config.placement.startMillis ?: config.filters.calendarAddAtMillis

    /**
     * The length "Add to the calendar"'s end is after the start ([Placement]): the one the user stated, unless the added
     * elements now have an own length ([ownLength], [placementDefaultLength]) other than the one it was stated over;
     * else their own; else 1 hour.
     */
    fun placementLength(placement: Placement, ownLength: Long?): Long =
        placement.lengthMillis?.takeIf { ownLength == null || ownLength == placement.lengthOverMillis }
            ?: ownLength
            ?: DEFAULT_PLACEMENT_LENGTH_MILLIS

    /** [placement] with [lengthMillis] stated by the user over the added elements' own length [ownLength]. */
    fun withPlacementLength(placement: Placement, lengthMillis: Long, ownLength: Long?): Placement =
        placement.copy(lengthMillis = lengthMillis, lengthOverMillis = ownLength)

    /** Where a block laid at [startMillis] ends as [placement] states it, the added elements' own length [ownLength]. */
    fun placementEnd(placement: Placement, startMillis: Long, ownLength: Long? = null): Long =
        placement.endMillis?.takeIf { !placement.endByDelta } ?: (startMillis + placementLength(placement, ownLength))

    /** Whether [placement] states an end that is not after [startMillis] — nothing is laid then. */
    fun placementRefused(placement: Placement, startMillis: Long, ownLength: Long? = null): Boolean =
        placementEnd(placement, startMillis, ownLength) <= startMillis

    /** An added element's own time of day ([elementTimesOfDay]): what the button is named and the second it sets. */
    data class ElementTime(val label: String, val secondOfDay: Int)

    /**
     * User rule 2026-10-18 (*"in 'Add to the calendar', there should be a button to set the time field to the one defined
     * in the 'Date and time'"*): **the times of day the added elements have of their own** — an alarm's time, a
     * reminder's time (one that has one), a timer's end, a calendar block's start — each a button beside the start's time
     * that sets it there and keeps the day chosen: an alarm put on another day at its own time rings there too
     * ([calendarDrafts]), a task laid at an alarm's time starts when it rings. Each named after its element; in the
     * order of the added list, one per element.
     */
    fun elementTimesOfDay(
        state: SchedulerState,
        added: List<Result>,
        nowMillis: Long,
        timeZone: TimeZone = TimeZone.currentSystemDefault(),
    ): List<ElementTime> {
        fun secondOf(millis: Long) = Instant.fromEpochMilliseconds(millis).toLocalDateTime(timeZone).let { it.hour * 3600 + it.minute * 60 + it.second }
        return added.filterIsInstance<ItemResult>().mapNotNull { row ->
            val name = row.name.ifBlank { row.kind.label }
            when (row.kind) {
                Kind.Alarm -> state.alarms.firstOrNull { it.id == row.id }?.let { ElementTime(name, it.timeOfDayMinutes * 60) }
                Kind.Reminder ->
                    state.chores.firstOrNull { it.id.ifEmpty { it.title } == row.id }
                        ?.takeIf { it.timeOfDayMinutes >= 0 }
                        ?.let { ElementTime(name, it.timeOfDayMinutes * 60) }
                Kind.Timer ->
                    state.timers.firstOrNull { it.id == row.id }
                        ?.let { ElementTime("$name (end)", secondOf(TimerDomain.endMomentMillis(it, nowMillis))) }
                Kind.CalendarBlock -> calendarBlockOf(state, row.id, timeZone)?.let { ElementTime("$name (start)", secondOf(it.startMillis)) }
                else -> null
            }
        }
    }

    /**
     * User rule 2026-10-11: *"If the added elements list is only a 15min screen break, then add a default button next
     * to the 'ends' field to set it to 15 minutes."* — widened 2026-10-14 (*"Under 'Add in the calendar' for a timer
     * element, the end field, there should be a button named 'default' to set the end of the block at 3 seconds after
     * the start if the sound is set to 3 seconds. […] Find as many elements as possible where it is relevant"*):
     * **the length the added elements have of their own** — the one every one of them has ([elementOwnLength]), or
     * null where one has none or two disagree. The button that sets it is called "Default" whatever gives it.
     */
    fun placementDefaultLength(state: SchedulerState, added: List<Result>): Long? {
        if (added.isEmpty()) return null
        val lengths = added.map { elementOwnLength(state, it) ?: return null }.distinct()
        return lengths.singleOrNull()
    }

    /**
     * The length ONE added element has of its own, or null where it has none:
     *  - a screen break: as long as its name implies (`docs/scheduler_requirements.md`: *"A screen break period lasts
     *    as long as its name implies"*) — the 5- and 15-minute ones; a 20-second one is shorter than the shortest
     *    block the calendar lays, so it has none;
     *  - a task: its minimum execution time, which is the length a panel of it is meant to reach;
     *  - the "sleep" period: the Sleep schedule's sleep time; "before bed": the length of its period before bed;
     *  - an alarm and a timer: how long its ring sounds ([org.example.project.scheduler.model.AlarmEntry.soundSeconds]).
     */
    fun elementOwnLength(state: SchedulerState, row: Result): Long? {
        if (row is TaskResult) return taskOwnLength(state, row.taskId)
        if (row !is ItemResult) return null
        val seconds = when (row.kind) {
            Kind.Alarm -> state.alarms.firstOrNull { it.id == row.id }?.soundSeconds
            Kind.Timer -> state.timers.firstOrNull { it.id == row.id }?.soundSeconds
            else -> null
        }
        if (seconds != null) return if (seconds > 0) seconds * 1_000L else null
        if (row.kind != Kind.RestrictivePeriod) return null
        return periodOwnLength(state, row.id)
    }

    /** A task's own length ([elementOwnLength]): its minimum execution time, or null where it has none. */
    fun taskOwnLength(state: SchedulerState, taskId: TaskId): Long? {
        val minutes = state.tasks[taskId]?.minimumMinutes ?: return null
        return if (minutes > 0) minutes * 60_000L else null
    }

    /**
     * What a NEW panel of [taskId] spans where nothing else says (PRD §8 Manual add): the task's own length, else 45
     * minutes — the calendar's element window and "Add to the calendar" alike.
     */
    fun panelSpanMillis(state: SchedulerState, taskId: TaskId?): Long =
        taskId?.let { taskOwnLength(state, it) } ?: DEFAULT_PANEL_SPAN_MILLIS

    /** A new panel of a task with no minimum time of its own spans this long ([panelSpanMillis]). */
    const val DEFAULT_PANEL_SPAN_MILLIS: Long = 45 * 60_000L

    /** A period kind's own length ([elementOwnLength]), or null where it has none. */
    fun periodOwnLength(state: SchedulerState, kind: String): Long? {
        breakLengthMillis(state, kind)?.let { length ->
            return if (length >= SchedulerDomain.MIN_MANUAL_ENTRY_MILLIS) length else null
        }
        val minutes =
            when (kind) {
                // (No Sleep schedule on the account: no length to offer.)
                PeriodKinds.SLEEP -> state.sleep?.sleepDurationMinutes ?: return null
                PeriodKinds.BEFORE_BED -> state.sleep?.beforeBedMinutes ?: return null
                else -> return null
            }
        return if (minutes > 0) minutes * 60_000L else null
    }

    /** How long a screen break of [kind] lasts on this account, or null where [kind] is no screen break. */
    fun breakLengthMillis(state: SchedulerState, kind: String): Long? {
        val label = SchedulerDomain.breakLabelOfKind(kind) ?: return null
        return SchedulerDomain.dynamicPeriodSpecs(state.screenBreaks.ifEmpty { SchedulerDomain.DEFAULT_SCREEN_BREAKS })
            .firstOrNull { it.label == label }?.durationMillis
    }

    /**
     * User rule 2026-10-11: *"If the user enters a value in the 'ends' field that implies that the added 15min break
     * violates the rules, meaning that it spans over more than 15 minutes and has a part in the future part of the
     * timeline, the user can't add it"* — the sentence saying why `[startMillis, endMillis)` cannot be laid for the
     * [added] elements, or null where it can: a screen break among them would last longer than its name implies while
     * reaching past [nowMillis]. Wholly behind the line it is the user rewriting history, which the requirements allow.
     */
    fun placementBreakViolation(state: SchedulerState, added: List<Result>, startMillis: Long, endMillis: Long, nowMillis: Long): String? {
        if (endMillis <= nowMillis) return null
        for (row in added) {
            if (row !is ItemResult || row.kind != Kind.RestrictivePeriod) continue
            val length = breakLengthMillis(state, row.id) ?: continue
            if (endMillis - startMillis > length) return "a " + row.name + " lasts " + lengthText(length) + ", and this one would reach past now."
        }
        return null
    }

    /** A length as a few words: "15 min", "8 h 30", "20 s". */
    fun lengthText(millis: Long): String {
        val seconds = millis / 1000
        return when {
            seconds < 60 -> "$seconds s"
            seconds % 3600 == 0L -> "${seconds / 3600} h"
            seconds < 3600 -> "${seconds / 60} min"
            else -> "${seconds / 3600} h ${(seconds % 3600 / 60).toString().padStart(2, '0')}"
        }
    }

    // ----- The calendar's blocks (user rule 2026-10-05) ----------------------------------------------------

    /**
     * **One blue outlined block of the calendar** — what a hand placed there, by the calendar's own reading of it:
     * a panel outlined [SchedulerDomain.PanelOutline.User] (a task's panel added, dragged or resized; a period drawn),
     * a reminder's tag ([SchedulerDomain.reminderTagOutline]) and a ring moved on the calendar
     * ([SchedulerDomain.ringOutline]: an isolated alarm, a timer put there). [owner] is the Search key ([keyOf]) of the
     * element it is a block of; a tag and a ring are an instant, their end their start.
     */
    data class CalendarBlock(
        val id: String,
        val owner: String,
        val name: String,
        val startMillis: Long,
        val endMillis: Long,
        /**
         * The kind of a period a RULE lays here — a night of the Sleep schedule, its period before bed — or null for
         * a block the state holds. One of a pattern that repeats for ever ([patternBlocks]).
         */
        val derivedKind: String? = null,
    )

    private const val BLOCK_ALARM_PREFIX: String = "alarm:"

    /** The id of the block ([CalendarBlock.id]) an isolated ring of the alarm [alarmId] is on the calendar. */
    fun alarmBlockId(alarmId: String): String = BLOCK_ALARM_PREFIX + alarmId
    private const val BLOCK_TIMER_PREFIX: String = "timer:"

    /** Every [CalendarBlock] of the account, in the timeline's order. */
    fun calendarBlocks(state: SchedulerState, timeZone: TimeZone = TimeZone.currentSystemDefault()): List<CalendarBlock> {
        val out = ArrayList<CalendarBlock>()
        // As they stand on the calendar: a period a mode-1 line has crossed is listed over what is left of it.
        for (panel in SchedulerDomain.statedPanels(state)) {
            if (panel.chore) {
                val reminderId = SchedulerDomain.reminderIdOfChorePanel(panel.id) ?: continue
                out += CalendarBlock(panel.id, Kind.Reminder.name + "/" + reminderId, panel.title, panel.startEpochMillis, panel.startEpochMillis)
                continue
            }
            if (SchedulerDomain.panelOutline(panel) != SchedulerDomain.PanelOutline.User) continue
            if (panel.isRestrictivePeriod) {
                val kind = panel.restrictiveKind
                out += CalendarBlock(
                    panel.id, Kind.RestrictivePeriod.name + "/" + kind, PeriodKinds.periodTitle(kind),
                    panel.startEpochMillis, panel.endEpochMillis,
                )
            } else {
                val taskId = panel.taskId ?: continue
                out += CalendarBlock(
                    panel.id, taskKey(taskId), state.tasks[taskId]?.title?.ifBlank { null } ?: panel.title,
                    panel.startEpochMillis, panel.endEpochMillis,
                )
            }
        }
        for (alarm in state.alarms) {
            val day = alarm.onlyOnEpochDay ?: continue
            val at = AlarmDomain.occurrenceMillis(alarm, LocalDate.fromEpochDays(day.toInt()), timeZone)
            out += CalendarBlock(BLOCK_ALARM_PREFIX + alarm.id, Kind.Alarm.name + "/" + alarm.id, alarm.label.ifBlank { "Alarm" }, at, at)
        }
        for (timer in state.timers) {
            val at = timer.endsAtMillis?.takeIf { timer.calendarPlaced } ?: continue
            out += CalendarBlock(BLOCK_TIMER_PREFIX + timer.id, Kind.Timer.name + "/" + timer.id, timer.label.ifBlank { "Timer" }, at, at)
        }
        return out.sortedBy { it.startMillis }
    }

    /** The block a [Kind.CalendarBlock] row's id names, or null once it is gone from the calendar. */
    fun calendarBlockOf(state: SchedulerState, id: String, timeZone: TimeZone = TimeZone.currentSystemDefault()): CalendarBlock? =
        calendarBlocks(state, timeZone).firstOrNull { it.id == id }

    /**
     * User rule 2026-10-11: **every block the [added] elements have on the calendar**, in the timeline's order — what
     * the "Blocks on the calendar" action's drop-down lists ([blockOwners]).
     */
    fun blocksOfAdded(
        state: SchedulerState,
        added: List<Result>,
        timeZone: TimeZone = TimeZone.currentSystemDefault(),
        /** The stretch whose PATTERN occurrences are listed too ([patternBlocks]); null: none. */
        patternsFrom: Long? = null,
        patternsUntil: Long? = null,
    ): List<CalendarBlock> {
        val owners = blockOwners(added).toSet()
        if (owners.isEmpty()) return emptyList()
        val placed = calendarBlocks(state, timeZone).filter { it.owner in owners }
        if (patternsFrom == null || patternsUntil == null) return placed
        return (placed + patternBlocks(state, owners, patternsFrom, patternsUntil, timeZone)).distinctBy { it.id }.sortedBy { it.startMillis }
    }

    /**
     * Anomaly 2026-10-11 (*"In the Sleep period actions … I see only one block. Since it is a pattern that repeats
     * infinitely, they must not all showing in the drop-down list, but more must show as the user scrolls down"*):
     * **the occurrences a pattern lays over `[fromMillis, untilMillis)`** for the elements [owners] name — the nights
     * of the Sleep schedule and its periods before bed ([CalendarBlock.derivedKind]), and the later occurrences of a
     * panel the user made repeat ([PanelRepeats]). A pattern has no end, so the caller asks for a stretch and for more
     * as its list is scrolled. An occurrence taken out of its pattern (dragged, edited, removed) is not one of these.
     */
    fun patternBlocks(state: SchedulerState, owners: Set<String>, fromMillis: Long, untilMillis: Long, timeZone: TimeZone): List<CalendarBlock> {
        val out = ArrayList<CalendarBlock>()
        fun periodKey(kind: String) = Kind.RestrictivePeriod.name + "/" + kind
        if (periodKey(PeriodKinds.SLEEP) in owners) {
            SchedulerDomain.sleepPanels(state.sleep, fromMillis, untilMillis, timeZone).forEach {
                out += CalendarBlock(it.id, periodKey(PeriodKinds.SLEEP), PeriodKinds.periodTitle(PeriodKinds.SLEEP), it.startEpochMillis, it.endEpochMillis, PeriodKinds.SLEEP)
            }
        }
        if (periodKey(PeriodKinds.BEFORE_BED) in owners) {
            SchedulerDomain.beforeBedPanels(state, fromMillis, untilMillis, timeZone).forEach {
                out += CalendarBlock(it.id, periodKey(PeriodKinds.BEFORE_BED), PeriodKinds.periodTitle(PeriodKinds.BEFORE_BED), it.startEpochMillis, it.endEpochMillis, PeriodKinds.BEFORE_BED)
            }
        }
        for (panel in state.panels) {
            if (panel.repeat == null || panel.chore || SchedulerDomain.panelOutline(panel) != SchedulerDomain.PanelOutline.User) continue
            val owner = if (panel.isRestrictivePeriod) periodKey(panel.restrictiveKind) else panel.taskId?.let(::taskKey) ?: continue
            if (owner !in owners) continue
            val name = if (panel.isRestrictivePeriod) PeriodKinds.periodTitle(panel.restrictiveKind) else state.tasks[panel.taskId]?.title?.ifBlank { null } ?: panel.title
            PanelRepeats.occurrences(panel, fromMillis, untilMillis, timeZone).forEach {
                out += CalendarBlock(it.id, owner, name, it.startEpochMillis, it.endEpochMillis)
            }
        }
        return out
    }

    /**
     * Anomaly 2026-10-11 (*"I still have the Sleep block starting today at 5:15, even though in the calendar it starts
     * at now line"*): **the span the calendar draws a rule-laid [block] over**, out of the [pieces] it draws of that
     * kind — the schedule gives a night (an hour before bed) its whole span, and the calendar cuts it where a line at
     * a screen crossed it or the user placed a task (`SchedulerDomain.retractOverAtScreenPast`). From the first piece
     * inside the rule's span to the last; a piece that reaches outside it is another period and is not read. Null for
     * a block a hand placed (its stored span is the drawn one) and where the calendar draws nothing of it — off the
     * days it shows, or wholly given up.
     */
    fun blockDrawnSpan(block: CalendarBlock, pieces: List<TaskTimeRange>): TaskTimeRange? {
        if (block.derivedKind == null) return null
        val own =
            pieces.filter { it.endEpochMillis > it.startEpochMillis && it.startEpochMillis >= block.startMillis && it.endEpochMillis <= block.endMillis }
        if (own.isEmpty()) return null
        return TaskTimeRange(own.minOf { it.startEpochMillis }, own.maxOf { it.endEpochMillis })
    }

    /**
     * Anomaly 2026-10-11 (*"The date/time of the start/end of a block doesn't update as it gets changed in the
     * calendar"*): **[picked] once the calendar has changed from [before] to [after]** — a checked block that is gone
     * while a block of the SAME element has appeared is that block under a new id (a night dragged out of the Sleep
     * schedule, an occurrence taken out of its pattern, several panels committed as one), so the new id is checked in
     * its place. Paired per element, in the timeline's order; a block that simply left (removed) leaves the picked
     * ones, and a block that simply arrived is not checked. The same set where nothing of the kind happened.
     */
    fun pickedBlocksFollowing(picked: Set<String>, before: List<CalendarBlock>, after: List<CalendarBlock>): Set<String> {
        if (picked.isEmpty() || before.isEmpty()) return picked
        val now = after.mapTo(HashSet()) { it.id }
        val was = before.associateBy { it.id }
        val gone = picked.mapNotNull { id -> was[id]?.takeIf { id !in now } }
        if (gone.isEmpty()) return picked
        // What appeared: a block the state holds (never one more occurrence of a pattern scrolled into the list).
        val fresh = after.filter { it.id !in was && !blockIsOfPattern(it) }.groupBy { it.owner }.mapValues { it.value.toMutableList() }
        var out = picked
        for (block in gone.sortedBy { it.startMillis }) {
            val next = fresh[block.owner]?.removeFirstOrNull() ?: continue
            out = out - block.id + next.id
        }
        return out
    }

    /** Whether [block] is one occurrence of a pattern: edited or dragged, it leaves the pattern and is a block of its own. */
    fun blockIsOfPattern(block: CalendarBlock): Boolean = block.derivedKind != null || PanelRepeats.baseIdOf(block.id) != null

    /**
     * The id [block] is listed under once [blockEditIntent] is applied: its own — except an occurrence of a pattern,
     * which becomes the panel the state mints next (both reducers allocate it first). What lets the list keep showing
     * the block the user is in the middle of editing.
     */
    fun blockIdAfterEdit(state: SchedulerState, block: CalendarBlock): String =
        if (blockIsOfPattern(block)) "panel/" + state.nextPanelCounter else block.id

    /** The alarm's or timer's id where [block] is a ring; null for a panel, a tag or a period. */
    fun blockRingId(block: CalendarBlock): String? =
        when {
            block.id.startsWith(BLOCK_ALARM_PREFIX) -> block.id.removePrefix(BLOCK_ALARM_PREFIX)
            block.id.startsWith(BLOCK_TIMER_PREFIX) -> block.id.removePrefix(BLOCK_TIMER_PREFIX)
            else -> null
        }

    /** A tag and a ring are an instant: one day-and-time, no end of their own. */
    fun blockIsInstant(block: CalendarBlock): Boolean = block.endMillis <= block.startMillis

    /** An isolated alarm's ring keeps its day: an alarm rings at a TIME OF DAY, and its date is the one it was moved on. */
    fun blockKeepsItsDay(block: CalendarBlock): Boolean = block.id.startsWith(BLOCK_ALARM_PREFIX)

    /**
     * **The edit that puts [block] over `[startMillis, endMillis)`** — the one a drag of it on the calendar commits:
     * a task's panel or a period through [SchedulerIntent.UpdateTaskPanel] (placed by hand, so the existence pin), a
     * reminder's tag through the calendar's own element save, a ring through [calendarRingMoveIntent]. Null where
     * there is nothing to write: the block is gone, the span is not one (an end not after the start), or it is where
     * it already stands.
     */
    fun blockEditIntent(
        state: SchedulerState,
        block: CalendarBlock,
        startMillis: Long,
        endMillis: Long,
        nowMillis: Long,
        timeZone: TimeZone = TimeZone.currentSystemDefault(),
    ): SchedulerIntent? {
        if (block.id.startsWith(BLOCK_ALARM_PREFIX)) {
            return calendarRingMoveIntent(state, block.id.removePrefix(BLOCK_ALARM_PREFIX), false, block.startMillis, startMillis, nowMillis, timeZone)
        }
        if (block.id.startsWith(BLOCK_TIMER_PREFIX)) {
            return calendarRingMoveIntent(state, block.id.removePrefix(BLOCK_TIMER_PREFIX), true, block.startMillis, startMillis, nowMillis, timeZone)
        }
        if (block.derivedKind != null) {
            // A night of the schedule (or its period before bed): it leaves the schedule and is the user's period.
            if (endMillis <= startMillis || (startMillis == block.startMillis && endMillis == block.endMillis)) return null
            return SchedulerIntent.PlaceDerivedPeriod(block.derivedKind, block.startMillis, block.endMillis, startMillis, endMillis)
        }
        // An occurrence of a repeating panel is written through its own id: it leaves the pattern as an exception.
        val panel =
            state.panels.firstOrNull { it.id == block.id }
                ?: PanelRepeats.baseIdOf(block.id)?.let { baseId -> state.panels.firstOrNull { it.id == baseId }?.copy(id = block.id) }
                ?: return null
        if (panel.chore) {
            if (startMillis == panel.startEpochMillis) return null
            return SchedulerIntent.AddCalendarElements(
                listOf(
                    CalendarElements.Draft(
                        kind = CalendarElements.Kind.Reminder,
                        existingId = panel.id,
                        name = panel.title,
                        startMillis = startMillis,
                        endMillis = startMillis,
                        reminderId = SchedulerDomain.reminderIdOfChorePanel(panel.id).orEmpty(),
                        reminderChecked = panel.checked,
                        // Put there by hand, as a drag of it pins it.
                        reminderPinned = true,
                    ),
                ),
            )
        }
        if (endMillis <= startMillis) return null
        if (startMillis == block.startMillis && endMillis == block.endMillis) return null
        return SchedulerIntent.UpdateTaskPanel(
            panel.id, panel.taskId, panel.title, startMillis, endMillis, SchedulerDomain.pinsAfterHandPlacement(panel.pins),
        )
    }

    /**
     * **What takes [blocks] off the calendar**: the panels and the tags are removed ([SchedulerIntent.RemoveTaskPanels],
     * one unit); an isolated ring's row is deleted (the rule it left already skips that day, so nothing rings then);
     * a timer put on the calendar is no longer a block of it (it goes on running — stopping it is the timer's own
     * action).
     */
    fun blockRemoveIntents(
        state: SchedulerState,
        blocks: List<CalendarBlock>,
        nowMillis: Long = 0L,
        timeZone: TimeZone = TimeZone.currentSystemDefault(),
    ): List<SchedulerIntent> {
        val ids = blocks.mapTo(HashSet()) { it.id }
        val out = ArrayList<SchedulerIntent>()
        // A panel the state holds, and an occurrence of a repeating one (removed as an exception of its pattern).
        val panels = state.panels.filter { it.id in ids }.map { it.id } + blocks.filter { it.derivedKind == null && PanelRepeats.baseIdOf(it.id) != null }.map { it.id }
        if (panels.isNotEmpty()) out += SchedulerIntent.RemoveTaskPanels(panels.distinct())
        // A night of the Sleep schedule, or its period before bed: the schedule skips it (as a dragged one leaves it).
        val sleep = state.sleep
        if (sleep != null) {
            fun days(kind: String, prefix: String) =
                blocks.filter { it.derivedKind == kind }.mapNotNull { it.id.removePrefix(prefix).toLongOrNull() }
            val nights = days(PeriodKinds.SLEEP, "sleep/")
            val evenings = days(PeriodKinds.BEFORE_BED, SchedulerDomain.BEFORE_BED_PANEL_ID_PREFIX)
            if (nights.isNotEmpty() || evenings.isNotEmpty()) {
                val today = Instant.fromEpochMilliseconds(nowMillis).toLocalDateTime(timeZone).date.toEpochDays().toLong()
                out += SchedulerIntent.SetSleepSchedule(
                    sleep.copy(
                        skippedWakeEpochDays = sleep.skippedWakeEpochDays + nights,
                        skippedBeforeBedEpochDays = sleep.skippedBeforeBedEpochDays + evenings,
                    ),
                    today,
                )
            }
        }
        val alarms = state.alarms.filterNot { (BLOCK_ALARM_PREFIX + it.id) in ids && it.isolated }
        if (alarms.size != state.alarms.size) out += SchedulerIntent.SetAlarms(alarms)
        val timers = state.timers.map { if ((BLOCK_TIMER_PREFIX + it.id) in ids && it.calendarPlaced) it.copy(calendarPlaced = false) else it }
        if (timers != state.timers) out += SchedulerIntent.SetTimers(timers)
        return out
    }

    /** The keys of the [added] elements that can have a block on the calendar ([CALENDAR_ADD_KINDS]), each once. */
    fun blockOwners(added: List<Result>): List<String> =
        added.filter { it.kind in CALENDAR_ADD_KINDS }.map(::keyOf).distinct()

    /**
     * **The blocks of the given elements** (user rule 2026-10-05): the Search window "Blocks on the calendar" opens —
     * the calendar blocks alone, those of [owners] ([Filters.blocksOf]), in the timeline's order.
     */
    fun blocksSearchConfig(owners: List<String>): Config =
        Config(
            kinds = setOf(Kind.CalendarBlock),
            filters = Filters(blocksOf = owners.toSet()),
            sorts = listOf(SortMethod(Kind.CalendarBlock, SortKey.BlockStart)),
        )

    /** A new alarm on the calendar: the element window's fresh alarm at [atMillis], the account's default configuration. */
    fun calendarAlarmDraft(state: SchedulerState, atMillis: Long): CalendarElements.Draft =
        CalendarElements.seeded(
            CalendarElements.Draft(kind = CalendarElements.Kind.Alarm),
            atMillis,
            panelSpanMillis = 0L,
            noScreenResilience = null,
            newAlarm = state.newAlarmDefaults,
        )

    /** The ids of every element of [kind] the account holds — what tells which ones a New or a Duplicate made. */
    fun elementIds(state: SchedulerState, kind: Kind): Set<String> =
        when (kind) {
            Kind.Task -> state.tasks.keys.mapTo(HashSet()) { it.value }
            Kind.Category -> state.categories.mapTo(HashSet()) { it.id.value }
            Kind.RestrictivePeriod -> state.allPeriodKinds.toSet()
            Kind.Alarm -> state.alarms.mapTo(HashSet()) { it.id }
            Kind.Timer -> state.timers.mapTo(HashSet()) { it.id }
            Kind.Chrono -> state.chronos.mapTo(HashSet()) { it.id }
            Kind.WorkplaceSetup -> state.workplaceSetups.mapTo(HashSet()) { it.id }
            Kind.Md -> state.mdElements.mapTo(HashSet()) { it.id }
            Kind.PresenceEvolution, Kind.MinimumEvolution -> PresenceEvolutions.shown(state, evolutionTargetOf(kind)).mapTo(HashSet()) { it.id }
            Kind.Quota -> state.quotas.mapTo(HashSet()) { it.id }
            Kind.Reminder -> state.chores.mapTo(HashSet()) { it.id }
            else -> emptySet()
        }

    /** The keys ([keyOf]) of the elements of [kind] [after] holds and [before] did not, in a stable order. */
    fun newElementKeys(before: SchedulerState, after: SchedulerState, kind: Kind): List<String> {
        val old = elementIds(before, kind)
        return (elementIds(after, kind) - old).sorted().map { kind.name + "/" + it }
    }

    /**
     * The added elements' **Duplicate** (user rule 2026-10-01): a copy of every added element of [kind], its title
     * with `" copy"` at the end. A task, a category and a period are copied by intents of their own
     * ([SchedulerIntent.DuplicateTasks] — one Undo/Redo unit for them all —, [SchedulerIntent.DuplicateCategory],
     * [SchedulerIntent.DuplicatePeriodKind]); an alarm, a timer, a chrono and a reminder are one more row of their
     * list, written through the list's own intent (a timer and a chrono idle — a copy is not a second run). Nothing
     * added of [kind] is no intent.
     */
    fun duplicateIntents(
        state: SchedulerState,
        added: List<Result>,
        kind: Kind,
        nowMillis: Long,
        timeZone: TimeZone = TimeZone.currentSystemDefault(),
    ): List<SchedulerIntent> {
        val rows = added.filterIsInstance<ItemResult>().filter { it.kind == kind }.distinctBy { it.id }
        fun named(id: String) = rows.first { it.id == id }.name + " copy"
        return when (kind) {
            Kind.Task -> addedTaskIds(state, added).takeIf { it.isNotEmpty() }?.let { listOf(SchedulerIntent.DuplicateTasks(it)) }.orEmpty()
            Kind.Category ->
                rows.map { CategoryId(it.id) }.filter { state.categoryById(it) != null }.map { SchedulerIntent.DuplicateCategory(it) }
            Kind.RestrictivePeriod -> addedPeriodKinds(state, added).map { SchedulerIntent.DuplicatePeriodKind(it) }
            Kind.Alarm -> {
                val ids = state.alarms.mapTo(HashSet()) { it.id }
                val copies = state.alarms.filter { a -> rows.any { it.id == a.id } }.map { alarm ->
                    alarm.copy(id = AlarmDomain.mintAlarmId(ids).also { ids += it }, label = named(alarm.id))
                }
                if (copies.isEmpty()) emptyList() else listOf(SchedulerIntent.SetAlarms(state.alarms + copies))
            }
            Kind.Timer -> {
                val ids = state.timers.mapTo(HashSet()) { it.id }
                val copies = state.timers.filter { t -> rows.any { it.id == t.id } }.map { timer ->
                    timer.copy(
                        id = TimerDomain.mintTimerId(ids).also { ids += it },
                        label = named(timer.id),
                        endsAtMillis = null, remainingMillis = null, runMillis = null, endedAtMillis = null,
                    )
                }
                if (copies.isEmpty()) emptyList() else listOf(SchedulerIntent.SetTimers(state.timers + copies))
            }
            Kind.Md -> {
                val ids = state.mdElements.mapTo(HashSet()) { it.id }
                val copies = state.mdElements.filter { m -> rows.any { it.id == m.id } }.map { it.copy(id = MdDomain.mintId(ids).also { id -> ids += id }) }
                if (copies.isEmpty()) emptyList() else listOf(SchedulerIntent.SetMdElements(state.mdElements + copies))
            }
            Kind.WorkplaceSetup -> {
                val ids = state.workplaceSetups.mapTo(HashSet()) { it.id }
                val copies = state.workplaceSetups.filter { s -> rows.any { it.id == s.id } }.map { setup ->
                    setup.copy(id = WorkplaceDomain.mintId(ids).also { ids += it }, title = named(setup.id))
                }
                if (copies.isEmpty()) emptyList() else listOf(SchedulerIntent.SetWorkplaceSetups(state.workplaceSetups + copies))
            }
            Kind.Chrono -> {
                val ids = state.chronos.mapTo(HashSet()) { it.id }
                val copies = state.chronos.filter { c -> rows.any { it.id == c.id } }.map { chrono ->
                    ChronoEntry(id = ChronoDomain.mintChronoId(ids).also { ids += it }, label = named(chrono.id))
                }
                if (copies.isEmpty()) emptyList() else listOf(SchedulerIntent.SetChronos(state.chronos + copies))
            }
            Kind.Quota -> {
                val ids = state.quotas.mapTo(HashSet()) { it.id }
                val copies = state.quotas.filter { q -> rows.any { it.id == q.id } }.map { quota ->
                    quota.copy(id = QuotaDomain.mintQuotaId(ids).also { ids += it }, title = named(quota.id))
                }
                if (copies.isEmpty()) emptyList() else listOf(SchedulerIntent.SetQuotas(state.quotas + copies))
            }
            Kind.Reminder -> {
                // A blank id is minted by the reducer, as for a new reminder.
                val copies = state.chores.filter { c -> rows.any { it.id == c.id } }.map { it.copy(id = "", title = named(it.id)) }
                if (copies.isEmpty()) {
                    emptyList()
                } else {
                    val todayStart = Instant.fromEpochMilliseconds(nowMillis).toLocalDateTime(timeZone).date
                        .atStartOfDayIn(timeZone).toEpochMilliseconds()
                    listOf(SchedulerIntent.SetChores(state.chores + copies, todayStart, nowMillis))
                }
            }
            else -> emptyList()
        }
    }

    /**
     * The added elements' "remove the others" (user rule 2026-10-02): [added] left holding the selected [keys]
     * alone, in their order — unchanged when it holds none of them, so a stale press removes nothing.
     */
    fun keepingOnly(added: List<String>, keys: Collection<String>): List<String> =
        added.filter { it in keys }.ifEmpty { added }

    /** The added elements' "remove": the selected [keys] off the list, the others kept in their order. */
    fun removing(added: List<String>, keys: Collection<String>): List<String> = added.filterNot { it in keys }

    /** The ids of [kind]'s elements among [added], each once, in the list's order. */
    fun addedIds(added: List<Result>, kind: Kind): List<String> =
        added.filterIsInstance<ItemResult>().filter { it.kind == kind }.map { it.id }.distinct()

    /** The live tasks among [added], each once, in the list's order. */
    fun addedTaskIds(state: SchedulerState, added: List<Result>): List<TaskId> =
        added.filterIsInstance<TaskResult>().map { it.taskId }.filter { it in state.tasks }.distinct()

    /**
     * The added tasks the task edit window's leaf-only sections act on (resilience, schedule unit): the schedulable
     * leaves — a parent task is a grouping the scheduler never places, so a value on one is a number nothing reads.
     */
    fun addedLeafIds(state: SchedulerState, added: List<Result>): List<TaskId> =
        addedTaskIds(state, added).filter { SchedulerDomain.isLeafTask(state, it) }

    /** The cell each added task is acted on through, for the cell menu's entries: its first live occurrence. */
    fun addedTaskCells(state: SchedulerState, added: List<Result>): List<CellId> =
        addedTaskIds(state, added).mapNotNull { SchedulerDomain.firstTaskOccurrence(state, it)?.cellId }

    /** The kinds the resilience action's period field offers: those a task may be given a value for. */
    fun resilienceKinds(state: SchedulerState): List<String> =
        state.allPeriodKinds.filter(PeriodKinds::isResilienceEditable)

    /** The resilience action's period, as [Config.resiliencePeriod] names it, else the first kind it offers. */
    fun resiliencePeriodOf(state: SchedulerState, config: Config): String? {
        val kinds = resilienceKinds(state)
        return config.resiliencePeriod?.takeIf { it in kinds } ?: kinds.firstOrNull()
    }

    /**
     * The added DEFAULT periods a Reset would change — those whose drawing or combination rules are not as the app
     * ships them, by their id (`ItemResult.id`, the kind's fixed name). Empty greys the button; a period of the
     * account's own has no default and is never one of them.
     */
    fun modifiedDefaultPeriods(state: SchedulerState, added: List<Result>): List<String> =
        added.filterIsInstance<ItemResult>()
            .filter { it.kind == Kind.RestrictivePeriod && it.id in state.allPeriodKinds && !PeriodKinds.isUserDefined(it.id) }
            .map { it.id }
            .distinct()
            .filter { kind ->
                kind in state.periodKindStyles ||
                    PeriodKinds.combinationsReset(state.periodCombinations, setOf(kind)) !== state.periodCombinations
            }

    /**
     * [command] applied to the [added] rows of its kind, as the intents the app already has for it — never a
     * second write path. A tree edit over several tasks is ONE intent and so one Undo/Redo unit
     * ([SchedulerIntent.SetTasksCategory], [SchedulerIntent.SetTasksMinimumTime]); the alarms' switch rides
     * [SchedulerIntent.SetAlarms] like the row's own switch does. A timer's or a chrono's run is its own row's
     * button per element — a unit each since 2026-10-01 ([SchedulerIntent.StartTimer]). Nothing to change is no intent
     * at all.
     */
    fun addedIntents(
        state: SchedulerState,
        added: List<Result>,
        command: AddedCommand,
        nowMillis: Long,
        timeZone: TimeZone = TimeZone.currentSystemDefault(),
    ): List<SchedulerIntent> {
        fun idsOf(kind: Kind) = added.filterIsInstance<ItemResult>().filter { it.kind == kind }.mapTo(HashSet()) { it.id }
        val taskIds = added.filterIsInstance<TaskResult>().map { it.taskId }.filter { it in state.tasks }
        return when (command) {
            is AddedCommand.Delete -> {
                val ids = idsOf(command.kind)
                when (command.kind) {
                    Kind.Alarm -> {
                        val next = state.alarms.filterNot { it.id in ids }
                        if (next.size == state.alarms.size) emptyList() else listOf(SchedulerIntent.SetAlarms(next))
                    }
                    Kind.Timer -> {
                        val next = state.timers.filterNot { it.id in ids }
                        if (next.size == state.timers.size) emptyList() else listOf(SchedulerIntent.SetTimers(next))
                    }
                    Kind.Md -> {
                        val next = state.mdElements.filterNot { it.id in ids }
                        if (next.size == state.mdElements.size) emptyList() else listOf(SchedulerIntent.SetMdElements(next))
                    }
                    // User rule 2026-11-20: a default evolution deleted is marked gone ([PresenceEvolutions.deleted]).
                    Kind.PresenceEvolution, Kind.MinimumEvolution -> {
                        val next = PresenceEvolutions.deleted(state, ids)
                        if (next == state.presenceEvolutions) emptyList() else listOf(SchedulerIntent.SetPresenceEvolutions(next))
                    }
                    Kind.WorkplaceSetup -> {
                        val next = state.workplaceSetups.filterNot { it.id in ids }
                        if (next.size == state.workplaceSetups.size) emptyList() else listOf(SchedulerIntent.SetWorkplaceSetups(next))
                    }
                    Kind.Chrono -> {
                        val next = state.chronos.filterNot { it.id in ids }
                        if (next.size == state.chronos.size) emptyList() else listOf(SchedulerIntent.SetChronos(next))
                    }
                    Kind.Quota -> {
                        val next = state.quotas.filterNot { it.id in ids }
                        if (next.size == state.quotas.size) emptyList() else listOf(SchedulerIntent.SetQuotas(next))
                    }
                    Kind.Reminder -> remindersIntent(state, state.chores.filterNot { it.id.ifEmpty { it.title } in ids }, nowMillis, timeZone)
                    Kind.Category ->
                        state.categories.filter { it.id.value in ids }.map { SchedulerIntent.DeleteCategory(it.id) }
                    else -> emptyList()
                }
            }
            is AddedCommand.TimersEdit -> {
                val ids = idsOf(Kind.Timer)
                val next = state.timers.map { if (it.id in ids) command.change(it) else it }
                // The default configuration is its own setting, not a row of the list ([addedTimersOrDefault]).
                val default =
                    if (!defaultConfigurationAdded(added, Kind.Timer)) null
                    else command.change(state.newTimerDefaults.copy(id = DEFAULT_CONFIGURATION_ID)).let(NewElementDefaults::timerDefaults)
                        .takeIf { it != state.newTimerDefaults }
                listOfNotNull(
                    default?.let { SchedulerIntent.SetNewTimerDefaults(it) },
                    SchedulerIntent.SetTimers(next, command.editKey).takeIf { next != state.timers },
                )
            }
            is AddedCommand.RemindersEdit -> {
                val ids = idsOf(Kind.Reminder)
                // The default configuration is its own setting, not a row of the list ([addedRemindersOrDefault]).
                val default =
                    if (!defaultConfigurationAdded(added, Kind.Reminder)) null
                    else command.change(state.newReminderDefaults.copy(id = DEFAULT_CONFIGURATION_ID)).let(NewElementDefaults::reminderDefaults)
                        .takeIf { it != state.newReminderDefaults }
                listOfNotNull(default?.let { SchedulerIntent.SetNewReminderDefaults(it) }) +
                    remindersIntent(state, state.chores.map { if (it.id.ifEmpty { it.title } in ids) command.change(it) else it }, nowMillis, timeZone)
            }
            is AddedCommand.AlarmsEdit -> {
                val ids = idsOf(Kind.Alarm)
                val next = state.alarms.map { if (it.id in ids) command.change(it) else it }
                // The default configuration is its own setting, not a row of the list ([addedAlarmsOrDefault]).
                val default =
                    if (!defaultConfigurationAdded(added, Kind.Alarm)) null
                    else command.change(state.newAlarmDefaults.copy(id = DEFAULT_CONFIGURATION_ID)).let(NewElementDefaults::alarmDefaults)
                        .takeIf { it != state.newAlarmDefaults }
                listOfNotNull(
                    default?.let { SchedulerIntent.SetNewAlarmDefaults(it) },
                    SchedulerIntent.SetAlarms(next, command.editKey).takeIf { next != state.alarms },
                )
            }
            is AddedCommand.Titles -> {
                val titles = command.titles
                when (command.kind) {
                    Kind.Alarm -> {
                        val next = state.alarms.map { a -> titles[a.id]?.let { a.copy(label = it) } ?: a }
                        if (next == state.alarms) emptyList() else listOf(SchedulerIntent.SetAlarms(next, command.editKey))
                    }
                    Kind.Timer -> {
                        val next = state.timers.map { t -> titles[t.id]?.let { t.copy(label = it) } ?: t }
                        if (next == state.timers) emptyList() else listOf(SchedulerIntent.SetTimers(next, command.editKey))
                    }
                    Kind.WorkplaceSetup -> {
                        val next = state.workplaceSetups.map { s -> titles[s.id]?.let { s.copy(title = it) } ?: s }
                        if (next == state.workplaceSetups) emptyList() else listOf(SchedulerIntent.SetWorkplaceSetups(next, command.editKey))
                    }
                    Kind.Chrono -> {
                        val next = state.chronos.map { c -> titles[c.id]?.let { c.copy(label = it) } ?: c }
                        if (next == state.chronos) emptyList() else listOf(SchedulerIntent.SetChronos(next, command.editKey))
                    }
                    Kind.Quota -> {
                        val next = state.quotas.map { q -> titles[q.id]?.let { q.copy(title = it) } ?: q }
                        // The default configuration's title is its own setting, not a row of the list.
                        val default = titles[DEFAULT_CONFIGURATION_ID]?.takeIf { it != state.newQuotaDefaults.title }
                        listOfNotNull(
                            default?.let { SchedulerIntent.SetNewQuotaDefaults(state.newQuotaDefaults.copy(title = it)) },
                            SchedulerIntent.SetQuotas(next, command.editKey).takeIf { next != state.quotas },
                        )
                    }
                    Kind.Reminder -> {
                        val next = state.chores.map { c -> titles[c.id.ifEmpty { c.title }]?.let { c.copy(title = it) } ?: c }
                        if (next == state.chores) {
                            emptyList()
                        } else {
                            val todayStart = Instant.fromEpochMilliseconds(nowMillis).toLocalDateTime(timeZone).date
                                .atStartOfDayIn(timeZone).toEpochMilliseconds()
                            listOf(SchedulerIntent.SetChores(next, todayStart, nowMillis))
                        }
                    }
                    else -> emptyList()
                }
            }
            AddedCommand.AlarmsTimeNow -> {
                val ids = idsOf(Kind.Alarm)
                val local = Instant.fromEpochMilliseconds(nowMillis).toLocalDateTime(timeZone)
                val minutes = local.hour * 60 + local.minute
                val alarms = state.alarms.map { if (it.id in ids) it.copy(timeOfDayMinutes = minutes) else it }
                if (alarms == state.alarms) emptyList() else listOf(SchedulerIntent.SetAlarms(alarms))
            }
            AddedCommand.RemindersTimeNow -> {
                val ids = idsOf(Kind.Reminder)
                val local = Instant.fromEpochMilliseconds(nowMillis).toLocalDateTime(timeZone)
                val minutes = local.hour * 60 + local.minute
                val chores = state.chores.map { if (it.id in ids) it.copy(timeOfDayMinutes = minutes) else it }
                if (chores == state.chores) {
                    emptyList()
                } else {
                    val todayStart = local.date.atStartOfDayIn(timeZone).toEpochMilliseconds()
                    listOf(SchedulerIntent.SetChores(chores, todayStart, nowMillis))
                }
            }
            is AddedCommand.Category ->
                if (taskIds.isEmpty()) emptyList()
                else listOf(SchedulerIntent.SetTasksCategory(taskIds, command.categoryId, command.carried))
            is AddedCommand.ElementParent -> ElementTree.parentIntents(state, added.map(::keyOf), command.parent, command.on)
            is AddedCommand.NewParentTask -> {
                // The id the reducer will mint ([SchedulerState.allocateTaskId]): the links are asked of the state holding it.
                val title =
                    command.title.trim().ifEmpty {
                        generateSequence(1) { it + 1 }.map { if (it == 1) "New task" else "New task $it" }
                            .first { name -> state.tasks.values.none { it.title.equals(name, ignoreCase = true) } }
                    }
                val (id, allocated) = state.allocateTaskId()
                val made = allocated.copy(tasks = allocated.tasks + (id to org.example.project.scheduler.model.Task(id = id, title = title)))
                listOf(SchedulerIntent.CreatePathlessTask(title)) + ElementTree.parentIntents(made, added.map(::keyOf), taskKey(id), on = true)
            }
            is AddedCommand.MinimumTime ->
                if (taskIds.isEmpty()) emptyList() else listOf(SchedulerIntent.SetTasksMinimumTime(taskIds, command.minutes))
            is AddedCommand.AlarmsOn -> {
                val ids = idsOf(Kind.Alarm)
                if (state.alarms.none { it.id in ids && it.enabled != command.on }) {
                    emptyList()
                } else {
                    listOf(SchedulerIntent.SetAlarms(state.alarms.map { if (it.id in ids) it.copy(enabled = command.on) else it }))
                }
            }
            is AddedCommand.TimersRun -> {
                val ids = idsOf(Kind.Timer)
                state.timers.filter { it.id in ids }.mapNotNull { timer ->
                    when (command.step) {
                        RunStep.Start -> SchedulerIntent.StartTimer(timer.id, nowMillis).takeIf { !timer.running }
                        RunStep.Pause -> SchedulerIntent.PauseTimer(timer.id, nowMillis).takeIf { timer.running }
                        RunStep.Reset -> SchedulerIntent.ResetTimer(timer.id).takeIf { !timer.idle }
                    }
                }
            }
            is AddedCommand.Resilience -> {
                val ids = addedLeafIds(state, added)
                if (ids.isEmpty()) emptyList() else listOf(SchedulerIntent.SetPeriodResilience(ids, command.kind, command.value))
            }
            is AddedCommand.ScheduleUnit -> {
                val ids = addedLeafIds(state, added)
                if (ids.isEmpty()) emptyList() else listOf(SchedulerIntent.SetTasksScheduleUnit(ids, command.entries))
            }
            is AddedCommand.MdAdd -> {
                val keys = added.filter { ElementTree.isElement(it.kind) }.map(::keyOf)
                if (keys.isEmpty()) emptyList()
                else listOf(SchedulerIntent.SetMdElements(MdDomain.added(state.mdElements, MdDomain.mintId(state.mdElements.map { it.id }), keys, command.text)))
            }
            is AddedCommand.MdSetText -> {
                val next = state.mdElements.map { if (it.id == command.id) it.copy(text = command.text) else it }
                if (next == state.mdElements) emptyList() else listOf(SchedulerIntent.SetMdElements(next, command.editKey))
            }
            is AddedCommand.MdDissociate -> {
                val next = MdDomain.dissociated(state.mdElements, command.id, command.keys ?: added.map(::keyOf))
                if (next == state.mdElements) emptyList() else listOf(SchedulerIntent.SetMdElements(next))
            }
            is AddedCommand.WeightFormula -> listOf(SchedulerIntent.SetCellWeightFormula(command.cellId, command.formula))
            is AddedCommand.MinimumFormula ->
                if (taskIds.isEmpty()) emptyList() else listOf(SchedulerIntent.SetTasksMinimumFormula(taskIds.distinct(), command.formula))
            is AddedCommand.TreeName -> {
                val roots = treeRootsOf(added)
                if (roots.isEmpty()) emptyList() else listOf(SchedulerIntent.SetTreeName(roots, command.name))
            }
            is AddedCommand.AddUnder ->
                if (taskIds.isEmpty()) emptyList() else listOf(SchedulerIntent.AddTasksPath(taskIds.distinct(), command.parentTaskId))
            // The cell menu's own intents, per cell: collapsing is view state, and adding the template takes a list.
            AddedCommand.CollapseSubtrees -> addedTaskCells(state, added).map { SchedulerIntent.CollapseSubtrees(it) }
            AddedCommand.AddDefaultSubtree ->
                addedTaskCells(state, added).takeIf { it.isNotEmpty() && !state.defaultSubtreeIsEmpty }
                    ?.let { listOf(SchedulerIntent.AddDefaultSubtree(it)) }
                    .orEmpty()
            is AddedCommand.RemovePath -> listOf(SchedulerIntent.RemoveTaskPath(command.cellId))
            is AddedCommand.Raw -> listOf(command.intent)
            is AddedCommand.Drawing ->
                addedPeriodKinds(state, added)
                    .filter { state.periodKindConfig.drawing(it) != command.drawing }
                    .map { SchedulerIntent.SetPeriodDrawing(it, command.drawing) }
            AddedCommand.DeletePeriods ->
                addedPeriodKinds(state, added).filter(PeriodKinds::isUserDefined).map { SchedulerIntent.RemovePeriodKind(it) }
            AddedCommand.ResetDefaultPeriods ->
                modifiedDefaultPeriods(state, added).takeIf { it.isNotEmpty() }
                    ?.let { listOf(SchedulerIntent.ResetPeriodKinds(it)) }
                    .orEmpty()
            is AddedCommand.ChronosRun -> {
                val ids = idsOf(Kind.Chrono)
                state.chronos.filter { it.id in ids }.mapNotNull { chrono ->
                    when (command.step) {
                        RunStep.Start -> SchedulerIntent.StartChrono(chrono.id, nowMillis).takeIf { !chrono.running }
                        RunStep.Pause -> SchedulerIntent.PauseChrono(chrono.id, nowMillis).takeIf { chrono.running }
                        RunStep.Reset -> SchedulerIntent.ResetChrono(chrono.id).takeIf { !chrono.idle }
                    }
                }
            }
        }
    }

    // ----- Memo -----------------------------------------------------------------------------------

    /** What [shortestPathsInAnyTree] reads: the maps by IDENTITY (comparing them would cost the walk), the ids by value. */
    private class ShapeKey(state: SchedulerState) {
        private val cells = state.cells
        private val lists = state.lists
        private val tasks = state.tasks
        private val rootListId = state.rootListId

        fun sameAs(other: ShapeKey): Boolean =
            cells === other.cells && lists === other.lists && tasks === other.tasks && rootListId == other.rootListId
    }

    /**
     * A handful of results keyed by [ShapeKey]. The state is reduced on more than one thread, so the table is
     * an immutable value swapped whole: a race only costs a recomputation.
     */
    private class ShapeMemo<V>(private val capacity: Int) {
        @Volatile
        private var entries: List<Pair<ShapeKey, V>> = emptyList()

        fun lookup(key: ShapeKey): V? = entries.firstOrNull { (k, _) -> k.sameAs(key) }?.second

        fun store(key: ShapeKey, value: V) {
            entries = (listOf(key to value) + entries).take(capacity)
        }
    }

    private val shortestMemo = ShapeMemo<Map<TaskId, List<String>>>(capacity = 4)
}
