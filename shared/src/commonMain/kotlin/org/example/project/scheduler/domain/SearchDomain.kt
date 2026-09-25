package org.example.project.scheduler.domain

import kotlin.concurrent.Volatile
import org.example.project.scheduler.platform.GlobalShortcut
import org.example.project.scheduler.model.TaskRelationKey
import org.example.project.scheduler.state.HistoryUnit
import org.example.project.scheduler.state.HistoryWindow
import org.example.project.scheduler.state.HistoryCategory
import kotlinx.datetime.number
import kotlinx.datetime.toLocalDateTime
import kotlinx.datetime.TimeZone
import kotlin.time.Instant
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.isoDayNumber
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.example.project.scheduler.model.CategoryId
import org.example.project.scheduler.model.Cell
import org.example.project.scheduler.model.CellId
import org.example.project.scheduler.model.CellList
import org.example.project.scheduler.model.CellListId
import org.example.project.scheduler.model.ChoreEntry
import org.example.project.scheduler.model.ChoreRecurrenceUnit
import org.example.project.scheduler.model.Task
import org.example.project.scheduler.model.TaskId
import org.example.project.scheduler.model.WellKnownIds
import org.example.project.scheduler.state.SchedulerState

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
object SearchDomain {

    /**
     * The drop-down of the configuration section, in the order the user listed it. Each kind carries a check
     * box there: the search looks for every checked kind at once ([results]).
     */
    enum class Kind(val label: String) {
        Task("task"),
        Category("task category"),
        RestrictivePeriod("restrictive period"),
        Alarm("alarm"),
        Timer("timer"),
        Reminder("reminder"),
        HistoryUnit("history unit"),
        TaskTree("task tree"),
        TaskRelation("task relation"),
        Shortcut("keyboard shortcut"),
    }

    /**
     * The window's configuration: what is typed in the bar, which kinds are checked, and the per-kind
     * [Filters] the Configuration Search window edits. **Local-only view state** — kept on this device with the
     * window's placement, so the window comes back as it was left after a close or a restart, and never
     * synced: how the user is looking for something is not a fact about the account.
     */
    data class Config(
        val query: String = "",
        val kinds: Set<Kind> = setOf(Kind.Task),
        val filters: Filters = Filters(),
        /** Dominant first ([SortMethod]). */
        val sorts: List<SortMethod> = DEFAULT_SORTS,
    ) {
        /** JSON, every field optional, so a later build's extra filter is ignored rather than fatal. */
        fun encode(): String =
            configJson.encodeToString(
                StoredConfig.serializer(),
                StoredConfig(
                    query = query,
                    kinds = Kind.entries.filter { it in kinds }.map { it.name },
                    taskInTree = filters.taskInTree.name,
                    taskCategory = filters.taskCategory?.value,
                    categoryHasRules = filters.categoryHasRules.name,
                    periodOrigin = filters.periodOrigin.name,
                    alarmState = filters.alarmState.name,
                    alarmDays = filters.alarmDays.sortedBy { it.isoDayNumber }.map { it.isoDayNumber },
                    timerState = filters.timerState.name,
                    reminderRepeats = filters.reminderRepeats.name,
                    historyCategory = filters.historyCategory?.name,
                    historyWindow = filters.historyWindow?.name,
                    historyUndone = filters.historyUndone.name,
                    taskTreeOpen = filters.taskTreeOpen.name,
                    taskTreeDated = filters.taskTreeDated.name,
                    relationSection = filters.relationSection?.name,
                    shortcutRebound = filters.shortcutRebound.name,
                    sortMethods = sorts.map { StoredSortMethod(it.kind?.name, it.key.name, it.descending) },
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
                        taskCategory = stored.taskCategory?.let { CategoryId(it) },
                        categoryHasRules = enumNamed(stored.categoryHasRules, Tri.Any),
                        periodOrigin = enumNamed(stored.periodOrigin, PeriodOrigin.Any),
                        alarmState = enumNamed(stored.alarmState, AlarmState.Any),
                        alarmDays = stored.alarmDays.mapNotNull { n -> DayOfWeek.entries.firstOrNull { it.isoDayNumber == n } }
                            .toSet(),
                        timerState = enumNamed(stored.timerState, TimerState.Any),
                        reminderRepeats = enumNamed(stored.reminderRepeats, ReminderRepeats.Any),
                        historyCategory = HistoryCategory.entries.firstOrNull { it.name == stored.historyCategory },
                        historyWindow = HistoryWindow.entries.firstOrNull { it.name == stored.historyWindow },
                        historyUndone = enumNamed(stored.historyUndone, Tri.Any),
                        taskTreeOpen = enumNamed(stored.taskTreeOpen, Tri.Any),
                        taskTreeDated = enumNamed(stored.taskTreeDated, Tri.Any),
                        relationSection =
                            TaskRelationsDomain.Section.entries.firstOrNull { it.name == stored.relationSection },
                        shortcutRebound = enumNamed(stored.shortcutRebound, Tri.Any),
                    ),
                    sorts = sortMethodsNamed(stored.sortMethods ?: legacySortMethods(stored)),
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
                    val kind =
                        if (method.kind == null) null else Kind.entries.firstOrNull { it.name == method.kind } ?: return@mapNotNull null
                    val key = sortKeysOf(kind).firstOrNull { it.name == method.key } ?: return@mapNotNull null
                    SortMethod(kind, key, method.descending)
                }.distinctBy { it.kind to it.key }

            private fun kindsNamed(names: List<String>): Set<Kind> =
                names.mapNotNull { name -> Kind.entries.firstOrNull { it.name == name } }.toSet()

            private inline fun <reified E : Enum<E>> enumNamed(name: String?, default: E): E =
                enumValues<E>().firstOrNull { it.name == name } ?: default
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
         * (`Search`) or a copy's (`Search#2`): the one whose button opened it last.
         */
        val target: String = "Search",
        /**
         * Keep the filters that are ON listed even where [onlyResultKinds] would drop their type: a filter that
         * empties its own type out of the results would otherwise vanish the moment it is set, taking with it
         * the one control that turns it back off.
         */
        val showFiltersOn: Boolean = false,
    ) {
        fun encode(): String =
            configJson.encodeToString(
                StoredConfigurationSearch.serializer(),
                StoredConfigurationSearch(
                    query, Kind.entries.filter { it in kinds }.map { it.name }, onlyResultKinds, target, showFiltersOn,
                ),
            )

        companion object {
            fun decode(text: String?): ConfigurationSearch? {
                if (text == null) return null
                val stored =
                    runCatching { configJson.decodeFromString(StoredConfigurationSearch.serializer(), text) }.getOrNull()
                        ?: return null
                return ConfigurationSearch(
                    stored.query,
                    stored.kinds.mapNotNull { name -> Kind.entries.firstOrNull { it.name == name } }.toSet(),
                    stored.onlyResultKinds,
                    stored.target,
                    stored.showFiltersOn,
                )
            }
        }
    }

    /** "Any", "yes" or "no" — a filter that can also be left off. */
    enum class Tri(val label: String) { Any("any"), Yes("yes"), No("no") }

    enum class PeriodOrigin(val label: String) { Any("any"), BuiltIn("built-in"), Yours("yours") }

    enum class AlarmState(val label: String) { Any("any"), On("on"), Off("off") }

    enum class TimerState(val label: String) { Any("any"), Idle("idle"), Running("running"), Paused("paused") }

    enum class ReminderRepeats(val label: String) { Any("any"), OneOff("one-off"), Repeating("repeating") }

    /**
     * The per-kind filters of the Search window, which the Configuration Search window edits. Every one has an
     * "any" value, and a filter of a kind applies to that kind's rows only — so the defaults filter nothing.
     */
    data class Filters(
        val taskInTree: Tri = Tri.Any,
        /** Null = any category. */
        val taskCategory: CategoryId? = null,
        val categoryHasRules: Tri = Tri.Any,
        val periodOrigin: PeriodOrigin = PeriodOrigin.Any,
        val alarmState: AlarmState = AlarmState.Any,
        /** Empty = any day; else the alarm rings on at least one of them. */
        val alarmDays: Set<DayOfWeek> = emptySet(),
        val timerState: TimerState = TimerState.Any,
        val reminderRepeats: ReminderRepeats = ReminderRepeats.Any,
        /** Null = any category (PRD §5's stacks: edit, selection, calendar, main, window navigation). */
        val historyCategory: HistoryCategory? = null,
        /** Null = any window the unit was made in. */
        val historyWindow: HistoryWindow? = null,
        /** Yes = undone on its device (still redoable there). */
        val historyUndone: Tri = Tri.Any,
        /** Yes = the task tree that is open (the live one). */
        val taskTreeOpen: Tri = Tri.Any,
        /** Yes = put on the timeline at a date. */
        val taskTreeDated: Tri = Tri.Any,
        /** Null = any of the Task relations window's four sections. */
        val relationSection: TaskRelationsDomain.Section? = null,
        /** Yes = bound to another chord than the one it ships with. */
        val shortcutRebound: Tri = Tri.Any,
    ) {
        /** How many filters are set to something other than "any" — the Search window's button shows it. */
        val activeCount: Int
            get() = Setting.entries.count { isOn(it) }

        /**
         * Whether [setting] is a filter set to something other than "any" — the one statement of it, read by the
         * count above and by the Configuration Search window's "filters that are on". A setting that is not a
         * filter (the search text, the types, a Sort by) is never on.
         */
        fun isOn(setting: Setting): Boolean =
            when (setting) {
                Setting.TaskInTree -> taskInTree != Tri.Any
                Setting.TaskCategory -> taskCategory != null
                Setting.CategoryHasRules -> categoryHasRules != Tri.Any
                Setting.PeriodOriginSetting -> periodOrigin != PeriodOrigin.Any
                Setting.AlarmStateSetting -> alarmState != AlarmState.Any
                Setting.AlarmDays -> alarmDays.isNotEmpty()
                Setting.TimerStateSetting -> timerState != TimerState.Any
                Setting.ReminderRepeatsSetting -> reminderRepeats != ReminderRepeats.Any
                Setting.HistoryCategorySetting -> historyCategory != null
                Setting.HistoryWindowSetting -> historyWindow != null
                Setting.HistoryUndoneSetting -> historyUndone != Tri.Any
                Setting.TaskTreeOpenSetting -> taskTreeOpen != Tri.Any
                Setting.TaskTreeDatedSetting -> taskTreeDated != Tri.Any
                Setting.RelationSectionSetting -> relationSection != null
                Setting.ShortcutReboundSetting -> shortcutRebound != Tri.Any
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
        HistoryDate(Kind.HistoryUnit, "date"),
        TaskTreeDate(Kind.TaskTree, "date"),
        TaskTreeSize(Kind.TaskTree, "tasks"),
        RelationSection(Kind.TaskRelation, "section"),
        ShortcutChord(Kind.Shortcut, "chord"),
    }

    /**
     * The keys offered for the whole list ([kind] null: relevance, name, type) or for one kind's rows
     * (relevance, name, and that kind's own keys).
     */
    fun sortKeysOf(kind: Kind?): List<SortKey> =
        if (kind == null) {
            listOf(SortKey.Relevance, SortKey.Name, SortKey.Type)
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

    /** [sorts] with [method] at the bottom (checked in a drop-down), or without it (unchecked, or its ✕). */
    fun withSortMethod(sorts: List<SortMethod>, method: SortMethod, on: Boolean): List<SortMethod> {
        val rest = sorts.filterNot { it.sameMethod(method) }
        return if (on) rest + method else rest
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
        Types(null, "Types"),
        SortResults(null, "Sort by", sorts = true),
        TaskSort(Kind.Task, "Sort by", sorts = true),
        CategorySort(Kind.Category, "Sort by", sorts = true),
        PeriodSort(Kind.RestrictivePeriod, "Sort by", sorts = true),
        AlarmSort(Kind.Alarm, "Sort by", sorts = true),
        TimerSort(Kind.Timer, "Sort by", sorts = true),
        ReminderSort(Kind.Reminder, "Sort by", sorts = true),
        HistorySort(Kind.HistoryUnit, "Sort by", sorts = true),
        TaskTreeSort(Kind.TaskTree, "Sort by", sorts = true),
        RelationSort(Kind.TaskRelation, "Sort by", sorts = true),
        ShortcutSort(Kind.Shortcut, "Sort by", sorts = true),
        TaskInTree(Kind.Task, "In a task tree"),
        TaskCategory(Kind.Task, "Category"),
        CategoryHasRules(Kind.Category, "Has rules"),
        PeriodOriginSetting(Kind.RestrictivePeriod, "Origin"),
        AlarmStateSetting(Kind.Alarm, "State"),
        AlarmDays(Kind.Alarm, "Rings on"),
        TimerStateSetting(Kind.Timer, "State"),
        ReminderRepeatsSetting(Kind.Reminder, "Repeats"),
        HistoryCategorySetting(Kind.HistoryUnit, "Category"),
        HistoryWindowSetting(Kind.HistoryUnit, "Made in"),
        HistoryUndoneSetting(Kind.HistoryUnit, "Undone"),
        TaskTreeOpenSetting(Kind.TaskTree, "Open"),
        TaskTreeDatedSetting(Kind.TaskTree, "On the timeline"),
        RelationSectionSetting(Kind.TaskRelation, "Section"),
        ShortcutReboundSetting(Kind.Shortcut, "Rebound"),
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

    /** The kinds that have at least one row in the Search window's results for [config]. */
    fun kindsInResults(state: SchedulerState, config: Config): Set<Kind> =
        // Paths are not needed to know that a row exists, so the walk that lists them is skipped.
        results(state, config.kinds, config.query, { emptyMap() }, config.filters).mapTo(HashSet()) { it.kind }

    /** The stored form of a [Config] — strings, so an unknown value decodes to its default. */
    @Serializable
    private data class StoredConfig(
        val query: String = "",
        val kinds: List<String> = listOf(Kind.Task.name),
        val taskInTree: String? = null,
        val taskCategory: String? = null,
        val categoryHasRules: String? = null,
        val periodOrigin: String? = null,
        val alarmState: String? = null,
        val alarmDays: List<Int> = emptyList(),
        val timerState: String? = null,
        val reminderRepeats: String? = null,
        val historyCategory: String? = null,
        val historyWindow: String? = null,
        val historyUndone: String? = null,
        val taskTreeOpen: String? = null,
        val taskTreeDated: String? = null,
        val relationSection: String? = null,
        val shortcutRebound: String? = null,
        /** Null = never stored (a configuration written before sorting): the default list. */
        val sortMethods: List<StoredSortMethod>? = null,
        /** The first sorting shape's fields — read only, when [sortMethods] is absent ([Config.decode]). */
        val sort: String? = null,
        val sortDescending: Boolean = false,
        val kindSorts: Map<String, StoredLegacySort> = emptyMap(),
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
        /** New 2026-09-25: absent from what an older build stored, which reads as off. */
        val showFiltersOn: Boolean = false,
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
     * The live tree's root segment: the active tree's name, else the root task's own title. Never blank —
     * a path that starts with nothing reads as a path that starts with its second segment.
     */
    private fun liveRootLabel(state: SchedulerState): String =
        state.activeTaskTree?.title?.takeIf { it.isNotBlank() }
            ?: state.tasks[WellKnownIds.ROOT_TASK]?.title?.takeIf { it.isNotBlank() }
            ?: "root"

    private fun sources(state: SchedulerState): List<TreeSource> = buildList {
        add(TreeSource(state.cells, state.lists, state.tasks, state.rootListId, liveRootLabel(state)))
        for (entry in state.taskTrees) {
            // The active entry's snapshot is stale by design — the live fields above ARE that tree.
            if (entry.id == state.activeTaskTreeId) continue
            val tree = entry.tree
            add(TreeSource(tree.cells, tree.lists, tree.tasks, WellKnownIds.ROOT_LIST, entry.title.ifBlank { "tree" }))
        }
    }

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
                    if (!SchedulerDomain.isRootTask(taskId) && taskId !in shortest) shortest[taskId] = prefix
                    val childListId = task.childListId ?: continue
                    if (visited.add(childListId)) next.add(childListId to prefix + task.title)
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
                        if (!SchedulerDomain.isRootTask(taskId)) {
                            val paths = found.getOrPut(taskId) { mutableListOf() }
                            if (paths.size < MAX_PATHS_PER_TASK && prefix !in paths) paths.add(prefix)
                        }
                        val childListId = task.childListId ?: continue
                        if (childListId !in chain) next.add(Triple(childListId, prefix + task.title, chain + childListId))
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
        if (path.firstOrNull() != liveRootLabel(state)) return null
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
        if (a.taskTrees !== b.taskTrees || a.activeTaskTreeId != b.activeTaskTreeId) return true
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
    ) : Result {
        override val kind: Kind get() = Kind.Task
        override val name: String get() = title
        val shownPath: List<String> get() = paths.firstOrNull().orEmpty()
        val hasSeveralPaths: Boolean get() = paths.size > 1
    }

    /**
     * Every task of the account the search can name: the live tree's tasks (a tombstone and a detached
     * parent among them — alive on their records, not in any tree) and the tasks only a stored tree holds.
     * Never the root task, never an untitled task. [allPaths] is [allPathsInAnyTree], passed in because it is
     * a walk of every tree and the window computes it once per change of the trees, not once per keystroke
     * in the search bar.
     */
    fun taskResults(
        state: SchedulerState,
        query: String,
        allPaths: Map<TaskId, List<List<String>>> = allPathsInAnyTree(state),
    ): List<TaskResult> {
        val inTree = shortestPathsInAnyTree(state)
        val lastPaths = lastPaths(state)
        val candidates = LinkedHashMap<TaskId, Task>()
        for (entry in state.taskTrees) {
            if (entry.id == state.activeTaskTreeId) continue
            for ((id, task) in entry.tree.tasks) if (id !in candidates) candidates[id] = task
        }
        // The live task wins: it is the one every edit reaches.
        candidates.putAll(state.tasks)
        return candidates.values
            .asSequence()
            .filter { !SchedulerDomain.isRootTask(it.id) && it.title.isNotBlank() }
            .mapNotNull { task -> matchRank(task.title, query)?.let { rank -> rank to task } }
            .map { (rank, task) ->
                val held = task.id in inTree
                val paths =
                    if (held) {
                        allPaths[task.id]?.takeIf { it.isNotEmpty() } ?: listOf(inTree.getValue(task.id))
                    } else {
                        listOfNotNull(lastPaths[task.id])
                    }
                rank to TaskResult(task.id, task.title, paths, held)
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

    fun itemResults(
        state: SchedulerState,
        kind: Kind,
        query: String,
        /** For the history units' date and time: the device's own. */
        timeZone: TimeZone = TimeZone.currentSystemDefault(),
    ): List<ItemResult> {
        val items =
            when (kind) {
                Kind.Task -> emptyList()
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
                    val origin = if (PeriodKinds.isUserDefined(periodKind)) "your kind" else "built-in"
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
                Kind.Reminder -> state.chores.map { chore ->
                    ItemResult(kind, chore.id.ifEmpty { chore.title }, chore.title, reminderDetail(chore))
                }
                // Every stack's units, as the History window lists them. The id is the unit's place in its stack,
                // which is what [historyUnitOf] reads back for the filters.
                Kind.HistoryUnit -> HistoryCategory.entries.flatMap { category ->
                    state.histories.forCategory(category).units.mapIndexed { index, unit ->
                        val where = unit.window?.label ?: category.name
                        val undone = if (unit.undone) " · undone" else ""
                        ItemResult(kind, category.name + "#" + index, unit.delta.label, where + " · " + dateTime(unit.timeMillis, timeZone) + undone)
                    }
                }
                Kind.TaskTree -> state.taskTrees.map { entry ->
                    val open = entry.id == state.activeTaskTreeId
                    val size = if (open) state.tasks.size else entry.tree.tasks.size
                    val date = entry.dateMillis?.let { dateTime(it, timeZone).substringBefore(' ') } ?: "no date"
                    ItemResult(kind, entry.id.value, entry.title.ifBlank { "tree" }, (if (open) "open · " else "") + date + " · " + plural(size, "task"))
                }
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
                }
            }
        return items
            .mapNotNull { item -> matchRank(item.name, query)?.let { it to item } }
            .sortedWith(compareBy({ it.first }, { it.second.name.lowercase() }, { it.second.detail }))
            .map { it.second }
    }

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
    ): List<Result> {
        val base =
            Kind.entries
                .filter { it in kinds }
                .flatMap { kind ->
                    if (kind == Kind.Task) taskResults(state, query, allPaths()) else itemResults(state, kind, query)
                }
                .filter { passes(state, it, filters) }
                // Stable: ties keep the kind order and each kind's own order.
                .sortedBy { matchRank(it.name, query) ?: Int.MAX_VALUE }
        if (sorts == DEFAULT_SORTS) return base
        val keys = SortValues(state, query)
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
    private class SortValues(private val state: SchedulerState, private val query: String) {
        private val priorities by lazy { SchedulerDomain.absoluteTaskPriorities(state) }
        private val carriers by lazy {
            state.tasks.values.flatMap { it.categoryIds }.groupingBy { it }.eachCount()
        }
        private val placed by lazy { state.panels.groupingBy { it.periodKind }.eachCount() }
        private val alarms by lazy { state.alarms.associateBy { it.id } }
        private val timers by lazy { state.timers.associateBy { it.id } }
        private val chores by lazy { state.chores.associateBy { it.id.ifEmpty { it.title } } }
        private val trees by lazy { state.taskTrees.associateBy { it.id.value } }
        private val sectionRank by lazy {
            TaskRelationsDomain.Section.entries.associate { relationSectionLabel(it) to it.ordinal }
        }

        fun of(row: Result, key: SortKey): Comparable<*>? =
            when (key) {
                SortKey.Relevance -> matchRank(row.name, query) ?: Int.MAX_VALUE
                SortKey.Name -> row.name.lowercase()
                SortKey.Type -> row.kind.ordinal
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
                SortKey.HistoryDate -> historyUnitOf(state, row.id)?.timeMillis
                SortKey.TaskTreeDate -> trees[row.id]?.dateMillis
                SortKey.TaskTreeSize -> trees[row.id]?.let { entry ->
                    if (entry.id == state.activeTaskTreeId) state.tasks.size else entry.tree.tasks.size
                }
                SortKey.RelationSection -> sectionRank[row.detail.substringBefore(" · ")]
                SortKey.ShortcutChord -> row.detail.substringBefore(" · ").lowercase()
                else -> null
            }
    }

    /** Whether [result] passes its own kind's [filters]; another kind's filters never touch it. */
    private fun passes(state: SchedulerState, result: Result, filters: Filters): Boolean {
        fun tri(value: Tri, actual: Boolean) = value == Tri.Any || (value == Tri.Yes) == actual
        return when (result) {
            is TaskResult -> {
                val task = state.tasks[result.taskId]
                    ?: state.taskTrees.firstNotNullOfOrNull { it.tree.tasks[result.taskId] }
                tri(filters.taskInTree, result.inTaskTree) &&
                    (filters.taskCategory == null || task?.categoryIds?.contains(filters.taskCategory) == true)
            }
            is ItemResult -> when (result.kind) {
                Kind.Task -> true
                Kind.Category ->
                    tri(filters.categoryHasRules, state.categoryById(CategoryId(result.id))?.rules?.isNotEmpty() == true)
                Kind.RestrictivePeriod -> when (filters.periodOrigin) {
                    PeriodOrigin.Any -> true
                    PeriodOrigin.BuiltIn -> !PeriodKinds.isUserDefined(result.id)
                    PeriodOrigin.Yours -> PeriodKinds.isUserDefined(result.id)
                }
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
                Kind.Reminder -> {
                    val chore = state.chores.firstOrNull { it.id.ifEmpty { it.title } == result.id } ?: return true
                    when (filters.reminderRepeats) {
                        ReminderRepeats.Any -> true
                        ReminderRepeats.OneOff -> chore.spanDays <= 0.0
                        ReminderRepeats.Repeating -> chore.spanDays > 0.0
                    }
                }
                Kind.HistoryUnit -> {
                    val unit = historyUnitOf(state, result.id) ?: return true
                    (filters.historyCategory == null || result.id.substringBefore('#') == filters.historyCategory.name) &&
                        (filters.historyWindow == null || unit.window == filters.historyWindow) &&
                        tri(filters.historyUndone, unit.undone)
                }
                Kind.TaskTree -> {
                    val entry = state.taskTrees.firstOrNull { it.id.value == result.id } ?: return true
                    tri(filters.taskTreeOpen, entry.id == state.activeTaskTreeId) && tri(filters.taskTreeDated, entry.dateMillis != null)
                }
                Kind.TaskRelation ->
                    filters.relationSection == null ||
                        result.detail.substringBefore(" · ") == relationSectionLabel(filters.relationSection)
                Kind.Shortcut -> {
                    val shortcut = GlobalShortcut.entries.firstOrNull { it.name == result.id } ?: return true
                    val binding = state.shortcutBindings[shortcut] ?: shortcut.defaultBinding
                    tri(filters.shortcutRebound, binding != shortcut.defaultBinding)
                }
            }
        }
    }

    /** The id of a task relation's row: its two task ids. */
    fun relationId(key: TaskRelationKey): String = key.taskId.value + "|" + key.relativeTo.value

    /** The history unit a [Kind.HistoryUnit] row's id names (its stack, and its place in it), or null. */
    fun historyUnitOf(state: SchedulerState, id: String): HistoryUnit? {
        val category = HistoryCategory.entries.firstOrNull { it.name == id.substringBefore('#') } ?: return null
        val index = id.substringAfter('#').toIntOrNull() ?: return null
        return state.histories.forCategory(category).units.getOrNull(index)
    }

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
     * How well [name] answers [query]: 0 = the same name, 1 = starts with it, 2 = contains it, null = not a
     * result. Case-insensitive; a blank query lists everything, alphabetically.
     */
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

    // ----- Memo -----------------------------------------------------------------------------------

    /** What [shortestPathsInAnyTree] reads: the maps by IDENTITY (comparing them would cost the walk), the ids by value. */
    private class ShapeKey(state: SchedulerState) {
        private val cells = state.cells
        private val lists = state.lists
        private val tasks = state.tasks
        private val taskTrees = state.taskTrees
        private val rootListId = state.rootListId
        private val activeTaskTreeId = state.activeTaskTreeId

        fun sameAs(other: ShapeKey): Boolean =
            cells === other.cells && lists === other.lists && tasks === other.tasks &&
                taskTrees === other.taskTrees && rootListId == other.rootListId &&
                activeTaskTreeId == other.activeTaskTreeId
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
