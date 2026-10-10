package org.example.project.scheduler.domain

import kotlin.concurrent.Volatile
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
    enum class Kind(val label: String) {
        Task("task"),
        Category("task category"),
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
        HistoryUnit("history unit"),

        /**
         * A notification the app posted — written, spoken or both ([org.example.project.scheduler.state.NotificationLogEntry],
         * the History window's own log). Listed for the window the app opens when one fires while the app is not in
         * focus (user rule 2026-10-04, [notificationsConfig]); read-only, like a history unit.
         */
        Notification("notification"),
        TaskTree("task tree"),
        TaskRelation("task relation"),
        Shortcut("keyboard shortcut"),

        /**
         * A setting of the app itself (user spec 2026-10-02): one row per [AppSettingEntry] — "Sound setting" is the
         * first — whose actions on the added elements are the setting's controls (the global volume slider).
         */
        AppSetting("app setting"),

        /**
         * A window of the app, open or not ([WindowEntry]): what the rows name is not in the account's state but
         * in `App`'s windows, which hands the list in ([results]'s `windows`).
         */
        Window("window"),

        /**
         * One CREATION row per kind of element the user can make ([CREATABLE]): opening it makes a new one, as that
         * kind's own "+ New …" does, and opens its window (user spec 2026-09-26). History units, task relations and
         * keyboard shortcuts are not made by the user, so they have none.
         */
        Creation("creation"),
    }

    /**
     * The kinds a [Kind.Creation] row exists for, in the drop-down's order. A new [Kind.Window] is a Search window
     * listing the window TYPES ([WindowDuplicates.Hidden]).
     */
    val CREATABLE: List<Kind> =
        listOf(
            Kind.Task, Kind.Category, Kind.RestrictivePeriod, Kind.Alarm, Kind.Timer, Kind.Chrono, Kind.Quota, Kind.Reminder,
            Kind.TaskTree, Kind.Window,
        )

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

    /** A [Kind.Notification] row's id: the instant it was posted at and its place in the log (two can share an instant). */
    fun notificationId(entry: org.example.project.scheduler.state.NotificationLogEntry, index: Int): String =
        entry.timeMillis.toString() + "#" + index

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
     * Whether [config] is a notifications window's exactly as the app opened it (user rule 2026-10-06): nothing of its
     * search configuration changed and nothing added. Only such a window goes on gathering — once the user has changed
     * it, the next notification out of focus opens a new one beside it.
     */
    fun isUntouchedNotificationsWindow(config: Config): Boolean =
        config.filters.notificationsSinceMillis?.let { config == notificationsConfig(it) } == true

    /** What a Search window is called, on its head and on its tab of the window bar. */
    const val WINDOW_TITLE: String = "Search"

    /** What the notifications window ([notificationsConfig]) is called instead (user rule 2026-10-06). */
    const val NOTIFICATIONS_WINDOW_TITLE: String = "unfocused notif"

    /**
     * The title of the Search window showing [config] (user rule 2026-10-06): the notifications window has a name of
     * its own. Read off the configuration, so it comes back with the window after a restart — and a window the user
     * turns into another search is a Search window again.
     */
    fun windowTitle(config: Config): String =
        if (isNotificationsWindow(config)) NOTIFICATIONS_WINDOW_TITLE else WINDOW_TITLE

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
     * What the Search window's **Reset** puts in the place of [current] (user rule 2026-10-02): the configuration the
     * window [opened] with while it has changed since — so a window opened on something ("edit…" on the calendar)
     * goes back to it — else the default configuration, to start on something unrelated. The WHOLE configuration:
     * filters, sorts and added elements included. Equal to [current] when there is nothing to reset.
     */
    fun resetConfig(current: Config, opened: Config?): Config =
        if (opened != null && current != opened) opened else Config()

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
    ) {
        companion object {
            /** A one-per-type row's id: what opening it asks `App` for. */
            const val TYPE_PREFIX: String = "type:"
        }
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
        /**
         * The **added elements** — the Search window's right half — as result keys ([keyOf]), in the order they
         * were added, each once. Kept with the rest of the configuration: local-only, never synced. A key whose
         * element is gone is kept but lists nothing ([resolve]).
         */
        val added: List<String> = emptyList(),
        /**
         * The **actions' filter** (user rule 2026-10-01): what finds an action by name — the Added elements
         * configurations window's bar — applied to that window AND to this window's top right quarter. Kept with the
         * rest of the configuration, so it belongs to this Search window and not to the configurations window.
         */
        val actionQuery: String = "",
        /**
         * User rule 2026-10-10: the groups of the actions section retracted to their heading, by [ActionGroup.id] —
         * each group has its expansion arrow. Kept with the rest of the window's configuration.
         */
        val collapsedActionGroups: Set<String> = emptySet(),
        /** The resilience action's period field ([AddedAction.TaskResilience]); null = the first kind it offers. */
        val resiliencePeriod: String? = null,
        /**
         * The calendar right-click this window was opened from ("add…", user rule 2026-10-01) — what the
         * calendar filter's "Set to the right-click" button sets its field to, and the button is offered only while
         * this is set. Null for a window that did not come from the calendar.
         */
        val calendarClickMillis: Long? = null,
        /** Where "Add to the calendar" lays the added elements, and until when ([Placement], user rule 2026-10-05). */
        val placement: Placement = Placement(),
    ) {
        /** Whether the window's rows need `App`'s windows: the window kind is searched, or a window is added. */
        val readsWindows: Boolean
            get() = Kind.Window in kinds || added.any { it.startsWith(Kind.Window.name + "/") }

        /** JSON, every field optional, so a later build's extra filter is ignored rather than fatal. */
        fun encode(): String =
            configJson.encodeToString(
                StoredConfig.serializer(),
                StoredConfig(
                    query = query,
                    kinds = Kind.entries.filter { it in kinds }.map { it.name },
                    taskInTree = filters.taskInTree.name,
                    taskSchedulable = filters.taskSchedulable.name,
                    taskCategory = filters.taskCategory?.value,
                    categoryHasRules = filters.categoryHasRules.name,
                    periodOrigin = filters.periodOrigin.name,
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
                    taskTreeOpen = filters.taskTreeOpen.name,
                    taskTreeDated = filters.taskTreeDated.name,
                    relationSection = filters.relationSection?.name,
                    shortcutRebound = filters.shortcutRebound.name,
                    windowStatus = filters.windowStatus?.name,
                    windowDuplicates = filters.windowDuplicates.name,
                    sortMethods = sorts.map { StoredSortMethod(it.kind?.name, it.key.name, it.descending) },
                    taskOnCalendar = filters.taskOnCalendar.name,
                    taskBoxesFrom = filters.taskBoxesFrom?.toString(),
                    taskBoxesUntil = filters.taskBoxesUntil?.toString(),
                    periodOnCalendar = filters.periodOnCalendar.name,
                    periodBoxesFrom = filters.periodBoxesFrom?.toString(),
                    periodBoxesUntil = filters.periodBoxesUntil?.toString(),
                    added = added,
                    actionQuery = actionQuery,
                    collapsedActionGroups = collapsedActionGroups.sorted(),
                    resiliencePeriod = resiliencePeriod,
                    calendarAddOn = filters.calendarAddOn,
                    calendarAddAtMillis = filters.calendarAddAtMillis,
                    calendarAddKeeping = filters.calendarAddKeeping,
                    calendarAtOn = filters.calendarAtOn,
                    calendarAtMillis = filters.calendarAtMillis,
                    notificationsSinceMillis = filters.notificationsSinceMillis,
                    notificationSources = NotificationSource.entries.filter { it in filters.notificationSources }.map { it.name },
                    calendarClickMillis = calendarClickMillis,
                    blocksOf = filters.blocksOf.sorted(),
                    placeStartMillis = placement.startMillis,
                    placeEndByDelta = placement.endByDelta,
                    placeLengthMillis = placement.lengthMillis,
                    placeEndMillis = placement.endMillis,
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
                        taskSchedulable = enumNamed(stored.taskSchedulable, Tri.Any),
                        taskCategory = stored.taskCategory?.let { CategoryId(it) },
                        categoryHasRules = enumNamed(stored.categoryHasRules, Tri.Any),
                        periodOrigin = enumNamed(stored.periodOrigin, PeriodOrigin.Any),
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
                        taskTreeOpen = enumNamed(stored.taskTreeOpen, Tri.Any),
                        taskTreeDated = enumNamed(stored.taskTreeDated, Tri.Any),
                        relationSection =
                            TaskRelationsDomain.Section.entries.firstOrNull { it.name == stored.relationSection },
                        shortcutRebound = enumNamed(stored.shortcutRebound, Tri.Any),
                        windowStatus = WindowStatus.entries.firstOrNull { it.name == stored.windowStatus },
                        windowDuplicates = enumNamed(stored.windowDuplicates, WindowDuplicates.Shown),
                        taskOnCalendar = enumNamed(stored.taskOnCalendar, Tri.Any),
                        taskBoxesFrom = dateNamed(stored.taskBoxesFrom),
                        taskBoxesUntil = dateNamed(stored.taskBoxesUntil),
                        periodOnCalendar = enumNamed(stored.periodOnCalendar, Tri.Any),
                        periodBoxesFrom = dateNamed(stored.periodBoxesFrom),
                        periodBoxesUntil = dateNamed(stored.periodBoxesUntil),
                        calendarAddOn = stored.calendarAddOn,
                        calendarAddAtMillis = stored.calendarAddAtMillis,
                        calendarAddKeeping = stored.calendarAddKeeping,
                        calendarAtOn = stored.calendarAtOn,
                        calendarAtMillis = stored.calendarAtMillis,
                        notificationsSinceMillis = stored.notificationsSinceMillis,
                        notificationSources =
                            NotificationSource.entries.filterTo(HashSet()) { it.name in stored.notificationSources },
                        blocksOf = stored.blocksOf.toSet(),
                    ),
                    sorts = sortMethodsNamed(stored.sortMethods ?: legacySortMethods(stored)),
                    // Heals the index-shaped history-unit keys of builds before 2026-10-03 ([historyUnitId]): an
                    // index named whichever unit had shifted into it, so the key no longer means what was added.
                    added = stored.added.distinct().filterNot(::isLegacyHistoryUnitKey),
                    actionQuery = stored.actionQuery,
                    collapsedActionGroups = stored.collapsedActionGroups.toSet(),
                    resiliencePeriod = stored.resiliencePeriod,
                    calendarClickMillis = stored.calendarClickMillis,
                    placement = Placement(
                        startMillis = stored.placeStartMillis,
                        endByDelta = stored.placeEndByDelta,
                        lengthMillis = stored.placeLengthMillis?.takeIf { it > 0L } ?: DEFAULT_PLACEMENT_LENGTH_MILLIS,
                        endMillis = stored.placeEndMillis,
                    ),
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

            /** An ISO date (2026-09-27), or null: for nothing stored and for what does not parse alike. */
            private fun dateNamed(text: String?): LocalDate? = text?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
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

    /**
     * The "Default periods" filter: whether a restrictive period is one the app ships (named by its fixed id,
     * `PeriodKinds.isUserDefined` false) or one of the account's own. Stored by the entries' names, which are kept as
     * they were under the filter's first label ("Origin": built-in / yours).
     */
    enum class PeriodOrigin(val label: String) { Any("any"), BuiltIn("yes"), Yours("no") }

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
         * PRD §9's *schedulable*: a task the scheduler may place — a leaf (no sub-task) that still lives in the tree
         * ([SchedulerDomain.isPlaceableTask], the predicate "start this task now" asks too).
         */
        val taskSchedulable: Tri = Tri.Any,
        /** Null = any category. */
        val taskCategory: CategoryId? = null,
        val categoryHasRules: Tri = Tri.Any,
        val periodOrigin: PeriodOrigin = PeriodOrigin.Any,
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
        /** Yes = the task tree that is open (the live one). */
        val taskTreeOpen: Tri = Tri.Any,
        /** Yes = put on the timeline at a date. */
        val taskTreeDated: Tri = Tri.Any,
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
        /** Every box of the task starts on this day or later (and it has one). Null = any. */
        val taskBoxesFrom: LocalDate? = null,
        /** Every box of the task ends by the end of this day (and it has one). Null = any. */
        val taskBoxesUntil: LocalDate? = null,
        /** Yes = at least one period of this kind is on the calendar. */
        val periodOnCalendar: Tri = Tri.Any,
        /** Every period of this kind on the calendar starts on this day or later (and there is one). Null = any. */
        val periodBoxesFrom: LocalDate? = null,
        /** Every period of this kind on the calendar ends by the end of this day (and there is one). Null = any. */
        val periodBoxesUntil: LocalDate? = null,
        /**
         * **The calendar filter** (user rule 2026-10-01): a GLOBAL filter — about every row, whatever its kind — that
         * keeps only what can be put on the calendar at [calendarAddAtMillis] ([calendarAddable]). Its switch is
         * [calendarAddOn]; the position is kept while it is off, so turning it back on finds it.
         */
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
                Setting.CalendarAdd -> calendarAddAt != null
                Setting.CalendarAt -> calendarAt != null
                Setting.TaskInTree -> taskInTree != Tri.Any
                Setting.TaskSchedulable -> taskSchedulable != Tri.Any
                Setting.TaskCategory -> taskCategory != null
                Setting.CategoryHasRules -> categoryHasRules != Tri.Any
                Setting.PeriodOriginSetting -> periodOrigin != PeriodOrigin.Any
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
                Setting.TaskTreeOpenSetting -> taskTreeOpen != Tri.Any
                Setting.TaskTreeDatedSetting -> taskTreeDated != Tri.Any
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
                Setting.NotificationSourceSetting -> notificationSources.isNotEmpty()
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
        TaskTreeDate(Kind.TaskTree, "date"),
        TaskTreeSize(Kind.TaskTree, "tasks"),
        RelationSection(Kind.TaskRelation, "section"),
        ShortcutChord(Kind.Shortcut, "chord"),
        /** [WindowStatus]'s order: open, minimized, not open. */
        WindowState(Kind.Window, "state"),
    }

    /**
     * The keys offered for the whole list ([kind] null: relevance, name, type, the calendar bubble's order) or
     * for one kind's rows (relevance, name, and that kind's own keys).
     */
    fun sortKeysOf(kind: Kind?): List<SortKey> =
        if (kind == null) {
            listOf(SortKey.Relevance, SortKey.Name, SortKey.Type, SortKey.CalendarBubble)
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
        /**
         * The Search window's own Reset ([resetConfig]): back to the configuration it opened with, else to the
         * default one. Listed here because every configuration of the Search window is — and it is not the
         * Configuration Search window's own Reset, which clears that window's search.
         */
        ResetSearch(null, "Reset"),
        SortResults(null, "Sort by", sorts = true),
        /** The calendar filter ([Filters.calendarAddOn]): about every row, so in the General section. */
        CalendarAdd(null, "Can be added to the calendar at"),
        /** The "is on the calendar at" filter ([Filters.calendarAtOn]): the calendar's "edit…". */
        CalendarAt(null, "Is on the calendar at"),
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
        TaskTreeSort(Kind.TaskTree, "Sort by", sorts = true),
        RelationSort(Kind.TaskRelation, "Sort by", sorts = true),
        ShortcutSort(Kind.Shortcut, "Sort by", sorts = true),
        AppSettingSort(Kind.AppSetting, "Sort by", sorts = true),
        WindowSort(Kind.Window, "Sort by", sorts = true),
        CreationSort(Kind.Creation, "Sort by", sorts = true),
        TaskInTree(Kind.Task, "In a task tree"),
        TaskSchedulable(Kind.Task, "Schedulable"),
        TaskCategory(Kind.Task, "Category"),
        TaskOnCalendar(Kind.Task, "On the calendar"),
        TaskBoxesFrom(Kind.Task, "Every box from"),
        TaskBoxesUntil(Kind.Task, "Every box until"),
        CategoryHasRules(Kind.Category, "Has rules"),
        PeriodOriginSetting(Kind.RestrictivePeriod, "Default periods"),
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
        TaskTreeOpenSetting(Kind.TaskTree, "Open"),
        TaskTreeDatedSetting(Kind.TaskTree, "On the timeline"),
        RelationSectionSetting(Kind.TaskRelation, "Section"),
        ShortcutReboundSetting(Kind.Shortcut, "Rebound"),
        WindowStatusSetting(Kind.Window, "State"),
        WindowDuplicatesSetting(Kind.Window, "Duplicates"),
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
    fun kindsInResults(state: SchedulerState, config: Config, windows: List<WindowEntry> = emptyList()): Set<Kind> =
        // Paths are not needed to know that a row exists, so the walk that lists them is skipped.
        results(state, config.kinds, config.query, { emptyMap() }, config.filters, windows = windows)
            .mapTo(HashSet()) { it.kind }

    /** The stored form of a [Config] — strings, so an unknown value decodes to its default. */
    @Serializable
    private data class StoredConfig(
        val query: String = "",
        val kinds: List<String> = listOf(Kind.Task.name),
        val taskInTree: String? = null,
        /** New 2026-10-01: absent from an older build's = any. */
        val taskSchedulable: String? = null,
        val taskCategory: String? = null,
        val categoryHasRules: String? = null,
        val periodOrigin: String? = null,
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
        val taskTreeOpen: String? = null,
        val taskTreeDated: String? = null,
        val relationSection: String? = null,
        val shortcutRebound: String? = null,
        /** New 2026-09-26: absent from what an older build stored, which reads as any. */
        val windowStatus: String? = null,
        /** New 2026-09-26: absent = shown. */
        val windowDuplicates: String? = null,
        /** Null = never stored (a configuration written before sorting): the default list. */
        val sortMethods: List<StoredSortMethod>? = null,
        /** The first sorting shape's fields — read only, when [sortMethods] is absent ([Config.decode]). */
        val sort: String? = null,
        val sortDescending: Boolean = false,
        val kindSorts: Map<String, StoredLegacySort> = emptyMap(),
        /** New 2026-09-27 (the calendar filters, the added elements): absent from an older build's = any / none. */
        val taskOnCalendar: String? = null,
        val taskBoxesFrom: String? = null,
        val taskBoxesUntil: String? = null,
        val periodOnCalendar: String? = null,
        val periodBoxesFrom: String? = null,
        val periodBoxesUntil: String? = null,
        val added: List<String> = emptyList(),
        /** New 2026-10-01: absent from an older build's = no filter, the first period. */
        val actionQuery: String = "",
        /** New 2026-10-10: absent from an older build's = every group of actions open. */
        val collapsedActionGroups: List<String> = emptyList(),
        val resiliencePeriod: String? = null,
        /** New 2026-10-01 (the calendar filter): absent = off, no position, not from the calendar. */
        val calendarAddOn: Boolean = false,
        val calendarAddAtMillis: Long? = null,
        /** New 2026-10-07 (the calendar filter's third state): absent = the filter keeps whatever can be added. */
        val calendarAddKeeping: Boolean = false,
        val calendarClickMillis: Long? = null,
        /** New 2026-10-01 (the calendar's "edit…"): absent = off, no position. */
        val calendarAtOn: Boolean = false,
        val calendarAtMillis: Long? = null,
        /** New 2026-10-04 (the notifications window): absent = every notification. */
        val notificationsSinceMillis: Long? = null,
        /** New 2026-10-05: absent = from anything; a source this build does not know is dropped. */
        val notificationSources: List<String> = emptyList(),
        /** New 2026-10-05 (the calendar blocks, "Add to the calendar"'s start and end): absent = every block, nothing said. */
        val blocksOf: List<String> = emptyList(),
        val placeStartMillis: Long? = null,
        val placeEndByDelta: Boolean = true,
        val placeLengthMillis: Long? = null,
        val placeEndMillis: Long? = null,
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

    /**
     * [path] as a label of at most [maxChars]: whole, when it fits; else with its START dropped, whole segments
     * first, behind an ellipsis (`… / Work / Report`) — the end, nearest the task, is what identifies it. A last
     * segment too long on its own keeps its end.
     */
    fun shortenedPathLabel(path: List<String>, maxChars: Int): String {
        val whole = pathLabel(path)
        if (whole.length <= maxChars) return whole
        for (drop in 1 until path.size) {
            val candidate = "…" + PATH_SEPARATOR + pathLabel(path.drop(drop))
            if (candidate.length <= maxChars) return candidate
        }
        return "…" + path.last().takeLast((maxChars - 1).coerceAtLeast(1))
    }

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
        /** The app's windows, for [Kind.Window] — `App`'s to know, not the state's. */
        windows: List<WindowEntry> = emptyList(),
        /** One row per window TYPE ([WindowDuplicates.Hidden]) rather than one per window. */
        windowTypesOnly: Boolean = false,
        /** The scheduler engine's runs, for [Kind.HistoryUnit] — the view model's to know (they are kept in memory only). */
        schedulerRuns: List<org.example.project.scheduler.state.SchedulerRunEntry> = emptyList(),
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
                Kind.Notification -> state.notificationLog.mapIndexed { index, entry ->
                    val said = entry.spoken?.takeIf { it.isNotBlank() && it != entry.message }?.let { " · said: $it" }.orEmpty()
                    ItemResult(
                        kind,
                        notificationId(entry, index),
                        entry.title.ifBlank { "Notification" },
                        listOf(entry.message, dateTime(entry.timeMillis, timeZone)).filter { it.isNotBlank() }.joinToString(" · ") + said,
                    )
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
                // Each instance its own row — two copies of Search are two rows; the detail is where it stands.
                Kind.Window ->
                    if (windowTypesOnly) {
                        windowTypeRows(windows)
                    } else {
                        windows.filterNot { it.placeholder }.map { window -> ItemResult(kind, window.id, window.title, window.status.label) }
                    }
                Kind.Creation -> CREATABLE.map { made ->
                    ItemResult(kind, made.name, "New " + made.label, creationDetail(made))
                }
                Kind.AppSetting -> AppSettingEntry.entries.map { setting ->
                    ItemResult(kind, setting.name, setting.title, appSettingDetail(state, setting))
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
    ): List<Result> {
        val calendar = if (filters.readsCalendar) CalendarBoxes(state, timeZone) else null
        val base =
            Kind.entries
                .filter { it in kinds }
                .flatMap { kind ->
                    if (kind == Kind.Task) {
                        taskResults(state, query, allPaths())
                    } else {
                        itemResults(
                            state, kind, query, windows = windows,
                            windowTypesOnly = filters.windowDuplicates == WindowDuplicates.Hidden,
                            schedulerRuns = schedulerRuns,
                        )
                    }
                }
                .filter { passes(state, it, filters, calendar) }
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
                    val end = placementEnd(placement ?: Placement(), at)
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
     * **The Search window's list** (user rule 2026-10-06): [rows] — and, where ONE kind alone is checked and nothing of
     * it is found, that kind's "creation" row, so a search that finds nothing offers to make the thing looked for. The
     * row is the very one the "creation" kind lists ([itemResults]), opened and added like it. A kind the user cannot
     * make (a history unit, a notification, a shortcut…) has none, and its list stays empty; so does a list of several
     * kinds, where there is no one thing to make.
     */
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
                SortKey.TaskTreeDate -> trees[row.id]?.dateMillis
                SortKey.TaskTreeSize -> trees[row.id]?.let { entry ->
                    if (entry.id == state.activeTaskTreeId) state.tasks.size else entry.tree.tasks.size
                }
                SortKey.RelationSection -> sectionRank[row.detail.substringBefore(" · ")]
                SortKey.ShortcutChord -> row.detail.substringBefore(" · ").lowercase()
                SortKey.WindowState -> windowStatusOf(row)?.ordinal
                else -> null
            }
    }

    /** Whether [result] passes its own kind's [filters]; another kind's filters never touch it. */
    private fun passes(state: SchedulerState, result: Result, filters: Filters, calendar: CalendarBoxes?): Boolean {
        fun tri(value: Tri, actual: Boolean) = value == Tri.Any || (value == Tri.Yes) == actual
        return when (result) {
            is TaskResult -> {
                val task = state.tasks[result.taskId]
                    ?: state.taskTrees.firstNotNullOfOrNull { it.tree.tasks[result.taskId] }
                tri(filters.taskInTree, result.inTaskTree) &&
                    (filters.taskSchedulable == Tri.Any ||
                        tri(filters.taskSchedulable, SchedulerDomain.isPlaceableTask(state, result.taskId))) &&
                    (filters.taskCategory == null || task?.categoryIds?.contains(filters.taskCategory) == true) &&
                    (calendar == null ||
                        calendar.passes(
                            calendar.ofTask(result.taskId), filters.taskOnCalendar, filters.taskBoxesFrom, filters.taskBoxesUntil,
                        ))
            }
            is ItemResult -> when (result.kind) {
                Kind.Task -> true
                Kind.Category ->
                    tri(filters.categoryHasRules, state.categoryById(CategoryId(result.id))?.rules?.isNotEmpty() == true)
                Kind.RestrictivePeriod -> when (filters.periodOrigin) {
                    PeriodOrigin.Any -> true
                    PeriodOrigin.BuiltIn -> !PeriodKinds.isUserDefined(result.id)
                    PeriodOrigin.Yours -> PeriodKinds.isUserDefined(result.id)
                } && (calendar == null ||
                    calendar.passes(
                        calendar.ofPeriodKind(result.id), filters.periodOnCalendar, filters.periodBoxesFrom, filters.periodBoxesUntil,
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
                Kind.Window -> filters.windowStatus == null || windowStatusOf(result) == filters.windowStatus
                // [Filters.blocksOf] is asked of the whole list at once ([results]).
                Kind.Creation, Kind.AppSetting, Kind.CalendarBlock -> true
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
    }

    /** What an [AppSettingEntry]'s row says beside its name: where the setting stands. */
    private fun appSettingDetail(state: SchedulerState, setting: AppSettingEntry): String =
        when (setting) {
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
    private class CalendarBoxes(private val state: SchedulerState, private val timeZone: TimeZone) {
        private val byTask by lazy { SchedulerDomain.calendarBoxesByTask(state) }
        private val byPeriodKind by lazy {
            state.panels.filter { it.isRestrictivePeriod }
                .groupBy({ it.restrictiveKind }) { TaskTimeRange(it.startEpochMillis, it.endEpochMillis) }
        }

        fun ofTask(taskId: TaskId): List<TaskTimeRange> = byTask[taskId].orEmpty()

        fun ofPeriodKind(kind: String): List<TaskTimeRange> = byPeriodKind[kind].orEmpty()

        /**
         * Whether [boxes] pass the three calendar filters: on the calendar at all, every box starting on [from]
         * or later, every box ending by the end of [until]. A day bound needs a box to hold: with none, there is
         * nothing "every box after that day" is about, and the user asking for one is asking about boxes.
         */
        fun passes(boxes: List<TaskTimeRange>, onCalendar: Tri, from: LocalDate?, until: LocalDate?): Boolean {
            if (onCalendar != Tri.Any && (onCalendar == Tri.Yes) != boxes.isNotEmpty()) return false
            if (from != null) {
                val start = from.atStartOfDayIn(timeZone).toEpochMilliseconds()
                if (boxes.isEmpty() || boxes.any { it.startEpochMillis < start }) return false
            }
            if (until != null) {
                val end = until.plus(1, DateTimeUnit.DAY).atStartOfDayIn(timeZone).toEpochMilliseconds()
                if (boxes.isEmpty() || boxes.any { it.endEpochMillis > end }) return false
            }
            return true
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
            Kind.Task -> "at the top of the tree, in its edit window"
            Kind.Window -> "a Search window of the window types"
            Kind.TaskTree -> "a copy of the open tree"
            else -> "in its own window"
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
            Kind.TaskTree, Kind.Shortcut, Kind.Window,
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
    ): List<Result> {
        if (keys.isEmpty()) return emptyList()
        val kinds = keys.mapNotNullTo(LinkedHashSet()) { key -> Kind.entries.firstOrNull { it.name == key.substringBefore('/') } }
        val found = HashMap<String, Result>()
        for (kind in kinds) {
            val rows =
                when (kind) {
                    Kind.Task -> taskResults(state, "", allPaths())
                    // A window row is one window or one window TYPE, whichever the list it was added from showed.
                    Kind.Window ->
                        itemResults(state, kind, "", windows = windows) +
                            itemResults(state, kind, "", windows = windows, windowTypesOnly = true)
                    else -> itemResults(state, kind, "", windows = windows, schedulerRuns = schedulerRuns)
                }
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
        OpenEach(null, "Open each"),
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
        // A task cell's right-click menu (`TaskCellMenuItems`), over every added task.
        TaskStartNow(Kind.Task, "Start now"),
        TaskEdit(Kind.Task, "Edit task"),
        TaskGoToTree(Kind.Task, "Go to task tree"),
        TaskGoToCalendar(Kind.Task, "Go to calendar"),
        TaskCopyIds(Kind.Task, "Copy task ids"),
        TaskDeepCopy(Kind.Task, "Deep copy"),
        TaskCollapseSubtrees(Kind.Task, "Collapse sub-trees"),
        TaskAddDefaultSubtree(Kind.Task, "Add default sub-tree"),
        // The task edit window's sections (`TaskEditWindow`), over every added task.
        TaskAddUnder(Kind.Task, "Add under"),
        TaskResilience(Kind.Task, "Resilience"),
        TaskScheduleUnit(Kind.Task, "Schedule unit"),
        TaskText(Kind.Task, "Text"),
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
        AlarmTime(Kind.Alarm, "Time"),
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
        CategoryDelete(Kind.Category, "Delete"),
        // The time of day put on the clock's (user rule 2026-10-02): every added alarm, every added reminder.
        AlarmTimeNow(Kind.Alarm, "Set to the current time"),
        ReminderTimeNow(Kind.Reminder, "Set to the current time"),
        /** The sound setting's slider ([AppSettingEntry.Sound], [SchedulerIntent.SetSoundVolume]). */
        SoundVolume(Kind.AppSetting, "Global volume"),
        /** The voice's and the notifications' switches ([AppSettingEntry.Voice], [AppSettingEntry.Notifications]). */
        VoiceSwitch(Kind.AppSetting, "Voice"),
        NotificationsSwitch(Kind.AppSetting, "Notifications"),
        TimerRun(Kind.Timer, "Run"),
        ChronoRun(Kind.Chrono, "Run"),
        // The period edit window's sections (removed 2026-10-01), over every added period.
        PeriodDrawing(Kind.RestrictivePeriod, "Drawing"),
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
                val actions = AddedAction.entries.filter { it.section == section && matchRank(it.label, query) != null }
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
     * row of a kind that has a default configuration** edited through that kind's actions ("New quota"), which is
     * acted on as that kind: the actions then edit what a new one starts with ([defaultConfigurationAdded]).
     */
    fun actionKindOf(result: Result): Kind =
        if (result.kind == Kind.Creation && result is ItemResult && result.id == Kind.Quota.name) Kind.Quota else result.kind

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
                // "Open each" is about SEVERAL elements (user rule 2026-10-07): a lone one is opened by its own row.
                val lone = added.size == 1
                actions.filter { (onCalendar || it !in CALENDAR_ACTIONS) && !(lone && it == AddedAction.OpenEach) }
                    .takeIf { it.isNotEmpty() }?.let { kind to it }
            } else if (added.any { it.kind == kind } || !defaultConfigurationAdded(added, kind)) {
                kind to actions
            } else {
                actions.filter { it in DEFAULT_CONFIGURATION_ACTIONS }.takeIf { it.isNotEmpty() }?.let { kind to it }
            }
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
    ) {
        /**
         * What the group is known by across edits of the list — what its expansion arrow is remembered under
         * ([Config.collapsedActionGroups]): the element of an element's group, the kind of a kind's (however many of
         * it are added), one name for the group of every element.
         */
        val id: String
            get() = when {
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
                    element -> listOfNotNull(label, members.singleOrNull()?.name?.takeIf { it.isNotBlank() }).joinToString(": ")
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
            AddedAction.ChronoRun, AddedAction.HistoryRules, AddedAction.HistoryInformation, AddedAction.TaskPaths,
            AddedAction.TaskCellCategories, AddedAction.TaskFulfilment, AddedAction.TaskFulfilledBy,
            AddedAction.TaskStartNow, AddedAction.TaskEdit, AddedAction.TaskGoToTree, AddedAction.TaskGoToCalendar,
            AddedAction.CategoryName,
        )

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
                AddedAction.PeriodReset,
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
     *  - **each kind holding several of them** (in a group of its own where they are not all of them, with "Open
     *    each" and "Remove every element from the list" acting on the kind's elements): that kind's set actions
     *    ([isSetAction]); then **one field for all of them at once** — *"a title field to rename every added quota
     *    elements at the same time, among other things"*: every own action that is one field over the elements it is
     *    given (all but [PER_ELEMENT_ACTIONS]); then, inside the group, **the default configuration of the kind**
     *    ([ActionGroup.inner], user rule 2026-10-10: *"the first group should have the configurations for the default
     *    configurations of the quota elements"*) — unless its creation row is added, which then has its own group;
     *  - **each element**, in the list's order: its own configuration, what says where it stands first
     *    ([LEADING_ACTIONS]) — and the set actions of its kind when it is the only one of it.
     * **One element alone is one group**: its own configuration, then its kind's set actions, then the general ones —
     * without "Remove every element from the list" (*"it can be done by clicking on the cross"*) nor "Open each".
     * A kind no added element is (the window of every configuration lists them all) keeps a group of its own, last.
     */
    /**
     * [config] once the arrow of the group [id] is pressed: retracted if it was open, open if it was retracted. The ids
     * of groups the section no longer lists ([listed] — an element taken off the list) are dropped on the way, so the
     * set never outgrows the section.
     */
    fun withActionGroupToggled(config: Config, id: String, listed: Set<String>): Config {
        val kept = config.collapsedActionGroups.filterTo(HashSet()) { it in listed }
        return config.copy(collapsedActionGroups = if (id in kept) kept - id else kept + id)
    }

    fun actionGroups(sections: List<Pair<Kind?, List<AddedAction>>>, added: List<Result>): List<ActionGroup> {
        if (added.isEmpty()) return sections.map { (kind, actions) -> ActionGroup(kind, emptyList(), actions) }
        fun membersOf(kind: Kind?) = added.filter { kind == null || actionKindOf(it) == kind || it.kind == kind }
        // A creation row acted on as its kind ("New quota") has that kind's default configuration, nothing else of it.
        fun of(element: Result, kind: Kind?, actions: List<AddedAction>) =
            if (element.kind == Kind.Creation && kind != Kind.Creation) actions.filter { it in DEFAULT_CONFIGURATION_ACTIONS } else actions
        fun own(actions: List<AddedAction>) =
            actions.filterNot(::isSetAction).let { mine -> LEADING_ACTIONS.filter { it in mine } + mine.filterNot { it in LEADING_ACTIONS } }
        fun set(actions: List<AddedAction>) = actions.filter(::isSetAction)
        // The fields that write to every element of the group at once.
        fun bulk(actions: List<AddedAction>) = actions.filter { !isSetAction(it) && it !in PER_ELEMENT_ACTIONS }
        // The default configuration of [kind], as a group inside the group of several of its elements.
        fun defaults(kind: Kind?, actions: List<AddedAction>): List<ActionGroup> {
            if (kind == null || defaultConfigurationAdded(added, kind)) return emptyList()
            val editing = actions.filter { it in DEFAULT_CONFIGURATION_ACTIONS && !isSetAction(it) }
            if (editing.isEmpty()) return emptyList()
            val row = ItemResult(Kind.Creation, kind.name, "New " + kind.label, "")
            return listOf(ActionGroup(kind, listOf(row), editing, heading = "Default configuration of a new " + kind.label))
        }
        val general = sections.firstOrNull { it.first == null }?.second.orEmpty()
        val kinds = sortedByReach(sections.filter { it.first != null }, added)
        val reached = kinds.filter { membersOf(it.first).isNotEmpty() }
        val out = ArrayList<ActionGroup>()
        val lone = added.singleOrNull()
        if (lone != null) {
            val mine = reached.map { (kind, actions) -> of(lone, kind, actions) }
            val actions =
                mine.flatMap(::own) + mine.flatMap(::set) + general.filter { it != AddedAction.ClearList && it != AddedAction.OpenEach }
            if (actions.isNotEmpty()) out += ActionGroup(actionKindOf(lone), added, actions.distinct(), element = true)
        } else {
            val whole = reached.filter { membersOf(it.first).size == added.size }
            val wide = general + whole.flatMap { set(it.second) } + whole.flatMap { bulk(it.second) }
            if (wide.isNotEmpty()) {
                out += ActionGroup(whole.singleOrNull()?.first, added, wide.distinct(), inner = whole.flatMap { defaults(it.first, it.second) })
            }
            val listWide = general.filter { it == AddedAction.OpenEach || it == AddedAction.ClearList }
            for ((kind, actions) in reached) {
                val members = membersOf(kind)
                if (members.size < 2 || members.size == added.size) continue
                val about = listWide + set(actions) + bulk(actions)
                if (about.isNotEmpty()) out += ActionGroup(kind, members, about, inner = defaults(kind, actions))
            }
            for (element in added) {
                val mine = reached.filter { element in membersOf(it.first) }
                val actions =
                    mine.flatMap { own(of(element, it.first, it.second)) } +
                        mine.filter { membersOf(it.first).size == 1 }.flatMap { set(of(element, it.first, it.second)) }
                if (actions.isNotEmpty()) out += ActionGroup(actionKindOf(element), listOf(element), actions.distinct(), element = true)
            }
        }
        kinds.filter { membersOf(it.first).isEmpty() }.forEach { (kind, actions) -> out += ActionGroup(kind, emptyList(), actions) }
        return out
    }

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

        data class Text(val text: String) : AddedCommand

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
            filters = Filters(calendarAtOn = true, calendarAtMillis = atMillis),
            sorts = withCalendarBubbleSort(DEFAULT_SORTS),
            calendarClickMillis = atMillis,
        )

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
        for (panel in SchedulerDomain.statedPanels(state)) {
            if (panel.chore) {
                if (near(panel.startEpochMillis)) {
                    SchedulerDomain.reminderIdOfChorePanel(panel.id)?.let { out += Kind.Reminder.name + "/" + it }
                }
                continue
            }
            if (covers(panel.startEpochMillis, panel.endEpochMillis)) panel.taskId?.let { out += taskKey(it) }
        }
        for (task in state.tasks.values) {
            if (task.record.any { covers(it.startEpochMillis, it.endEpochMillis) }) out += taskKey(task.id)
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
                        alarmDays = alarm.days + weekday,
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
                panelSpanMillis = (pick.taskId?.let { state.tasks[it]?.minimumMinutes } ?: 45) * 60_000L,
                noScreenResilience = pick.taskId?.let { state.tasks[it]?.resilienceFor(PeriodKinds.NO_SCREEN) },
                newAlarm = state.newAlarmDefaults,
            ).let { draft ->
                val spans = draft.kind == CalendarElements.Kind.TaskPanel || draft.kind == CalendarElements.Kind.RestrictivePeriod
                if (spans && endMillis != null && endMillis > atMillis) draft.copy(endMillis = endMillis) else draft
            }
        }
    }

    /**
     * **Where "Add to the calendar" lays the added elements** (user rule 2026-10-05), said the way a quota's loop is:
     * a START — null until one is given, and the calendar filter's position stands for it then ([placementStart]) —
     * and an END stated as a LENGTH after the start ([endByDelta], the default: 1 hour, user rule 2026-10-05) or as an
     * instant of its own ([endMillis]; until one is given, the length still says it). Local-only view state, kept with
     * the Search window's configuration.
     */
    data class Placement(
        val startMillis: Long? = null,
        val endByDelta: Boolean = true,
        val lengthMillis: Long = DEFAULT_PLACEMENT_LENGTH_MILLIS,
        val endMillis: Long? = null,
    )

    /** What "Add to the calendar" ends after until the user says otherwise: 1 hour after the start. */
    const val DEFAULT_PLACEMENT_LENGTH_MILLIS: Long = 3_600_000L

    /** The instant "Add to the calendar" lays at: the start given, else the calendar filter's position, else none. */
    fun placementStart(config: Config): Long? = config.placement.startMillis ?: config.filters.calendarAddAtMillis

    /** Where a block laid at [startMillis] ends as [placement] states it. */
    fun placementEnd(placement: Placement, startMillis: Long): Long =
        placement.endMillis?.takeIf { !placement.endByDelta } ?: (startMillis + placement.lengthMillis)

    /** Whether [placement] states an end that is not after [startMillis] — nothing is laid then. */
    fun placementRefused(placement: Placement, startMillis: Long): Boolean =
        placementEnd(placement, startMillis) <= startMillis

    // ----- The calendar's blocks (user rule 2026-10-05) ----------------------------------------------------

    /**
     * **One blue outlined block of the calendar** — what a hand placed there, by the calendar's own reading of it:
     * a panel outlined [SchedulerDomain.PanelOutline.User] (a task's panel added, dragged or resized; a period drawn),
     * a reminder's tag ([SchedulerDomain.reminderTagOutline]) and a ring moved on the calendar
     * ([SchedulerDomain.ringOutline]: an isolated alarm, a timer put there). [owner] is the Search key ([keyOf]) of the
     * element it is a block of; a tag and a ring are an instant, their end their start.
     */
    data class CalendarBlock(val id: String, val owner: String, val name: String, val startMillis: Long, val endMillis: Long)

    private const val BLOCK_ALARM_PREFIX: String = "alarm:"
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
                if (next == state.timers) emptyList() else listOf(SchedulerIntent.SetTimers(next, command.editKey))
            }
            is AddedCommand.RemindersEdit -> {
                val ids = idsOf(Kind.Reminder)
                remindersIntent(state, state.chores.map { if (it.id.ifEmpty { it.title } in ids) command.change(it) else it }, nowMillis, timeZone)
            }
            is AddedCommand.AlarmsEdit -> {
                val ids = idsOf(Kind.Alarm)
                val next = state.alarms.map { if (it.id in ids) command.change(it) else it }
                if (next == state.alarms) emptyList() else listOf(SchedulerIntent.SetAlarms(next, command.editKey))
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
            is AddedCommand.Text ->
                if (taskIds.isEmpty()) emptyList() else listOf(SchedulerIntent.SetTasksText(taskIds.distinct(), command.text))
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
