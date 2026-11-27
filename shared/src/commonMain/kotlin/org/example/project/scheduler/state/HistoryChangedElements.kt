package org.example.project.scheduler.state

import org.example.project.scheduler.domain.PeriodKinds
import org.example.project.scheduler.domain.SchedulerDomain
import org.example.project.scheduler.domain.SearchDomain
import org.example.project.scheduler.model.TaskPanel
import org.example.project.scheduler.platform.GlobalShortcut
import org.example.project.scheduler.platform.GlobalShortcutBindings

/**
 * The keys of the History Units of what `App` keeps outside the state ([ExternalDelta.key]): the one statement of
 * them, read by `App` when it records such a unit and by [changedElements] when the Search window asks what one changed.
 */
object ExternalKeys {
    /** The lateral menu's buttons the user made. */
    const val MENU: String = "menu"

    /** A Search window's configuration, followed by its frame id (`search/Search`, `search/Search#2`). */
    const val SEARCH: String = "search/"

    /**
     * User rule 2026-11-20: ONE keystroke in a Search window's bar ([searchText]) — the bar's text alone, before and after,
     * so a walk of the bar's pin moves the text and nothing else of the window ([ExternalDelta.runStart]). Never starts
     * with [SEARCH].
     */
    const val SEARCH_TEXT: String = "search-text/"

    /**
     * User rule 2026-11-22 (*"fold them in"*): **the keyboard moving to or from a pop-up of a ▾ configurations tree** —
     * a position like a move of the focus between windows, in [HistoryCategory.WindowNav]. Before and after are where the
     * keyboard was and went: a pop-up's address (`org.example.project.ui.PopupAddress`), or null for its window. Local
     * to the device, like a bar's keystroke ([Delta.localOnly]).
     */
    const val POPUP: String = "popup"

    /** What separates a stored Search window's id from the id, under it, of a window drawn inside it. */
    private const val INSIDE: Char = '\t'

    /**
     * The key of the bar of the Search window [owner], whose configuration the Search window [root] stores — the window
     * itself (`owner == root`: the Search window, a copy, the left-side menu), or one drawn inside it (a ▾ window, a
     * field's list: `SearchDomain.Config.inside`, under `owner` less `root`).
     */
    fun searchText(root: String, owner: String, caller: String = ""): String =
        (if (owner == root) SEARCH_TEXT + root else SEARCH_TEXT + root + INSIDE + owner.removePrefix(root)) +
            (if (caller.isEmpty()) "" else CALLER + caller)

    /**
     * What separates a bar's key from the caller of the field's window it is drawn in (`SearchDomain.fieldCallerOf`):
     * user rule 2026-11-21, *"don't drop the pin if the caller changes but it is still the same caller"* — the bar has a
     * pin by caller, the same caller finding its own again, another caller one of its own.
     */
    private const val CALLER: Char = '\u001F'

    /** [searchText] read back: the storing window's id, and the inside id ("" for the window itself). Null: another key. */
    fun searchTextOf(key: String): Pair<String, String>? {
        if (!key.startsWith(SEARCH_TEXT)) return null
        val rest = key.removePrefix(SEARCH_TEXT).substringBefore(CALLER)
        return rest.substringBefore(INSIDE) to rest.substringAfter(INSIDE, "")
    }

    /** A window's layout, followed by its frame id. */
    const val WINDOW: String = "window/"

    /** A calendar window's own configuration, followed by its frame id. */
    const val CALENDAR: String = "calendar/"

    /**
     * The day a calendar window was taken to by its day selector, followed by its frame id (user rule 2026-11-12: a
     * change of that window, walked by its Ctrl+Z — not a selection of rows, which Alt+arrows walk).
     */
    const val CALENDAR_DAY: String = "calendar-day/"

    /** A Search configurations window's own configuration, followed by its frame id. */
    const val CONFIG_SEARCH: String = "config-search/"

    /** The name a window was given from its head, followed by its frame id. */
    const val WINDOW_TITLE: String = "window-title/"

    /** Which windows stand on every page. */
    const val GLOBAL_WINDOWS: String = "global-windows"

    /** How loud Claude Code's own sounds are. */
    const val CLAUDE_SOUNDS: String = "claude-sounds"

    /** The order of the window bar's tabs. */
    const val TAB_ORDER: String = "tab-order"

    /**
     * Several windows' layouts changed by ONE action (user rule 2026-11-04: the window bar's Reset, a close that takes the
     * windows opened from the window closed, the bar menu's close and minimize of a selection): one History Unit.
     */
    const val WINDOWS: String = "windows"

    /** The keys of what the window bar changes: no window's, so undone from whichever window has the focus. */
    val WINDOW_BAR: Set<String> = setOf(WINDOWS, TAB_ORDER, GLOBAL_WINDOWS)
}

/**
 * One element of the app a History Unit changed, named as the Search window names its own rows ([SearchDomain.keyOf]):
 * its [kind] and its [id] there — a task's id, an alarm's, a period's kind, a window's frame id.
 */
data class ChangedElement(val kind: SearchDomain.Kind, val id: String) {
    /** The Search window's key of the element: what the "Changed element" filter holds. */
    val key: String get() = kind.name + "/" + id
}

/**
 * PRD §6 (user rule 2026-10-03): **the elements of the app this unit CHANGED** — the Search window's "Changed element
 * types" and "Changed element" filters for history units. Read off the delta, never stored. A change TO an element: a
 * task's fields, a cell re-pointed at it, a block or record of it; an alarm, timer, chrono, category, task tree or
 * shortcut edited, added or removed; a period kind (its drawing, the kind itself, a period of it on the calendar, the
 * sleep schedule for `sleep`); a reminder tag; a window's layout or — for a Search window — its configuration, whichever
 * window the change was made from. A selection, an expansion or a move of the focus changes how something is LOOKED at,
 * not the thing, so it names none.
 */
val Delta.changedElements: Set<ChangedElement>
    get() = buildSet {
        when (val delta = this@changedElements) {
            is TreeMutationDelta -> addTasksOf(delta.diff)
            is EmptyCellsDelta -> addTasksOf(delta.diff)
            is DefaultSubtreeDelta -> addTasksOf(delta.live)
            // A unit an older build wrote about the stored trees: the tasks it moved.
            is TaskTreeDelta -> addTasksOf(delta.diff.live)
            is PanelDelta -> {
                addPanels(delta.changes.before.values + delta.changes.after.values)
                delta.records.tasks.forEach { add(ChangedElement(SearchDomain.Kind.Task, it.value)) }
            }
            is RecordDelta -> delta.changes.tasks.forEach { add(ChangedElement(SearchDomain.Kind.Task, it.value)) }
            is SleepDelta -> add(ChangedElement(SearchDomain.Kind.RestrictivePeriod, PeriodKinds.SLEEP))
            is AlarmsDelta -> delta.changes.touched.forEach { add(ChangedElement(SearchDomain.Kind.Alarm, it)) }
            is TimersDelta -> delta.changes.touched.forEach { add(ChangedElement(SearchDomain.Kind.Timer, it)) }
            is ChronosDelta -> delta.changes.touched.forEach { add(ChangedElement(SearchDomain.Kind.Chrono, it)) }
            is PresenceEvolutionsDelta -> delta.changes.touched.forEach { add(ChangedElement(SearchDomain.evolutionKindOf(delta.changes.after[it]?.target ?: delta.changes.before[it]?.target ?: org.example.project.scheduler.domain.EvolutionTarget.Presence), it)) }
            is WorkplaceSetupsDelta -> delta.changes.touched.forEach { add(ChangedElement(SearchDomain.Kind.WorkplaceSetup, it)) }
            is ContextDefaultsDelta -> delta.changes.touched.forEach { add(ChangedElement(SearchDomain.Kind.Context, it)) }
            is MdElementsDelta -> delta.changes.touched.forEach { add(ChangedElement(SearchDomain.Kind.Md, it)) }
            is TreeAndMdDelta -> addAll(delta.tree.changedElements + delta.md.changedElements)
            is ElementLinksDelta ->
                (delta.changes.before.values + delta.changes.after.values).flatMap { listOf(it.parent, it.child) }.forEach { key ->
                    org.example.project.scheduler.domain.ElementTree.kindOf(key)?.let { add(ChangedElement(it, key.substringAfter('/'))) }
                }
            is QuotasDelta -> delta.changes.touched.forEach { add(ChangedElement(SearchDomain.Kind.Quota, it)) }
            is ShortcutBindingDelta ->
                GlobalShortcut.entries
                    .filter { GlobalShortcutBindings.chordOf(delta.before, it) != GlobalShortcutBindings.chordOf(delta.after, it) }
                    .forEach { add(ChangedElement(SearchDomain.Kind.Shortcut, it.name)) }
            is SettingsDelta -> {
                delta.kinds?.let { (b, a) -> ((a - b.toSet()) + (b - a.toSet())).forEach { add(ChangedElement(SearchDomain.Kind.RestrictivePeriod, it)) } }
                delta.styles.touched.forEach { add(ChangedElement(SearchDomain.Kind.RestrictivePeriod, it)) }
                delta.categories.touched.forEach { add(ChangedElement(SearchDomain.Kind.Category, it.value)) }
                delta.tree?.let { addTasksOf(it) }
                addPanels(delta.panels.before.values + delta.panels.after.values)
            }
            is ExternalDelta ->
                when {
                    delta.key.startsWith(ExternalKeys.SEARCH) ->
                        add(ChangedElement(SearchDomain.Kind.Window, delta.key.removePrefix(ExternalKeys.SEARCH)))
                    delta.key.startsWith(ExternalKeys.SEARCH_TEXT) ->
                        ExternalKeys.searchTextOf(delta.key)?.let { add(ChangedElement(SearchDomain.Kind.Window, it.first)) }
                    delta.key.startsWith(ExternalKeys.WINDOW_TITLE) ->
                        add(ChangedElement(SearchDomain.Kind.Window, delta.key.removePrefix(ExternalKeys.WINDOW_TITLE)))
                    delta.key.startsWith(ExternalKeys.WINDOW) ->
                        add(ChangedElement(SearchDomain.Kind.Window, delta.key.removePrefix(ExternalKeys.WINDOW)))
                    delta.key.startsWith(ExternalKeys.CALENDAR_DAY) ->
                        add(ChangedElement(SearchDomain.Kind.Window, delta.key.removePrefix(ExternalKeys.CALENDAR_DAY)))
                    delta.key.startsWith(ExternalKeys.CALENDAR) ->
                        add(ChangedElement(SearchDomain.Kind.Window, delta.key.removePrefix(ExternalKeys.CALENDAR)))
                    delta.key.startsWith(ExternalKeys.CONFIG_SEARCH) ->
                        add(ChangedElement(SearchDomain.Kind.Window, delta.key.removePrefix(ExternalKeys.CONFIG_SEARCH)))
                }
            else -> Unit
        }
    }

val Delta.changedConfigurations: List<String>
    get() = configurationChanges.map { it.path }

/**
 * One search configuration a unit changed ([configurationChanges]): [where] (a context's title, a Search window's frame id),
 * the [setting], and the configuration before and after.
 */
data class ConfigurationChange(val where: String, val setting: SearchDomain.Setting, val before: SearchDomain.Config, val after: SearchDomain.Config) {
    /** "<where> › <its path> › <its label>": what [changedConfigurations] says. */
    val path: String get() = "$where › ${SearchDomain.homePathOf(setting).plus(setting.label).joinToString(" › ")}"

    /**
     * User request 2026-11-27 (*"all the rows with a check box the user checked on a Sunday"*): **what this change checked
     * (true) and unchecked (false)** — the items of a check-box configuration ([SearchDomain.CHECK_BOX_OPTIONS]) that its
     * value gained or lost. Empty for any other configuration.
     */
    val checks: List<Pair<Any, Boolean>>
        get() {
            if (setting !in SearchDomain.CHECK_BOX_OPTIONS) return emptyList()
            val b = SearchDomain.settingValue(before, setting) as? Set<*> ?: return emptyList()
            val a = SearchDomain.settingValue(after, setting) as? Set<*> ?: return emptyList()
            return (a - b).filterNotNull().map { it to true } + (b - a).filterNotNull().map { it to false }
        }
}

/**
 * User rule 2026-11-27 (*"history units … that changed 'default search window > down arrow > Sorting > Sort by'"*): **the
 * search configurations this unit changed, and where** — a context's default ([ContextDefaultsDelta], where = the
 * context's title, a context absent on one side being the code's default) or a Search window's own configuration
 * ([ExternalKeys.SEARCH], where = its frame id). A configuration changed is one whose being on or its value
 * ([SearchDomain.settingValue]) differs. Read off the delta, never stored.
 */
val Delta.configurationChanges: List<ConfigurationChange>
    get() {
        fun diff(where: String, before: SearchDomain.Config?, after: SearchDomain.Config?): List<ConfigurationChange> {
            if (before == null || after == null) return emptyList()
            val on = SearchDomain.activeSettings(before).toSet() to SearchDomain.activeSettings(after).toSet()
            return SearchDomain.Setting.entries.filter { s ->
                (s in on.first) != (s in on.second) || SearchDomain.settingValue(before, s) != SearchDomain.settingValue(after, s)
            }.map { s -> ConfigurationChange(where, s, before, after) }
        }
        return when (val delta = this) {
            is ContextDefaultsDelta -> delta.changes.touched.flatMap { id ->
                val context = org.example.project.scheduler.domain.ContextDomain.context(id)
                val default = context?.default ?: SearchDomain.Config()
                fun side(map: Map<String, org.example.project.scheduler.domain.ContextDefault>) =
                    map[id]?.let { SearchDomain.Config.decode(it.config) } ?: default
                diff(context?.title ?: id, side(delta.changes.before), side(delta.changes.after))
            }
            is ExternalDelta ->
                if (delta.key.startsWith(ExternalKeys.SEARCH)) {
                    diff(
                        delta.key.removePrefix(ExternalKeys.SEARCH),
                        delta.before?.let { SearchDomain.Config.decode(it) } ?: SearchDomain.Config(),
                        delta.after?.let { SearchDomain.Config.decode(it) } ?: SearchDomain.Config(),
                    )
                } else emptyList()
            else -> emptyList()
        }
    }

/** A tree diff keeps only what differs: the tasks in it, and the tasks the cells that changed point at. */
private fun MutableSet<ChangedElement>.addTasksOf(diff: TreeDiff) {
    diff.tasks.touched.forEach { add(ChangedElement(SearchDomain.Kind.Task, it.value)) }
    (diff.cells.before.values + diff.cells.after.values).forEach { cell ->
        cell.taskId?.let { add(ChangedElement(SearchDomain.Kind.Task, it.value)) }
    }
}

/** A block names its task, a reminder tag its reminder, a period its kind. */
private fun MutableSet<ChangedElement>.addPanels(panels: Collection<TaskPanel>) {
    for (panel in panels) {
        panel.taskId?.let { add(ChangedElement(SearchDomain.Kind.Task, it.value)) }
        when {
            panel.chore -> SchedulerDomain.reminderIdOfChorePanel(panel.id)?.let { add(ChangedElement(SearchDomain.Kind.Reminder, it)) }
            panel.isRestrictivePeriod -> add(ChangedElement(SearchDomain.Kind.RestrictivePeriod, panel.restrictiveKind))
        }
    }
}
