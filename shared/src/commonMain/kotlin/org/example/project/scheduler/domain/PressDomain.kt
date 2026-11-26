package org.example.project.scheduler.domain

import org.example.project.scheduler.domain.SearchDomain.Kind

/**
 * User rule 2026-11-26: *"Make sure each element has in the action section the behavior of when [it] is clicked, when it
 * is double-clicked etc…"* and *"each element with a state has in the action section the state for all contexts"*.
 * **What each press does on a row of a kind, in each context** ([ContextDomain.CONTEXTS]) — the one place it is said,
 * the code of the rows reading the parts that decide ([doubleClickOpens]; [opening] matched to `SearchRowOpeners.open`
 * by `PressDomainTest`). What a press does depends on the context: a menu's entry runs where a Search window's row is
 * selected, and "Arrange by dragging" ([SearchDomain.Config.menuCustomizing]) on in a context turns its presses into a
 * drag. So the action groups the contexts by what the presses do, as "State in each context" groups them by a state.
 */
object PressDomain {
    /** The presses a row answers, in the order the action lists them. */
    enum class Press(val label: String) {
        Click("click"),
        ArrowClick("click on its arrow"),
        CtrlClick("Ctrl+click"),
        ShiftClick("Shift+click"),
        DoubleClick("double-click"),
        RightClick("right-click"),
        Enter("Enter"),
        HeldPress("held press"),
        Hover("hover"),
    }

    /** One press and what it does. */
    data class Behaviour(val press: Press, val does: String)

    /** One group of [groups]: what the presses do in its [contexts]. */
    data class Group(val behaviours: List<Behaviour>, val contexts: List<ContextDomain.SearchContext>)

    /**
     * User rule 2026-10-01: **the kinds a double-click on a result row opens** — commands, not elements to add. Every
     * other kind is added (an added one, opened).
     */
    fun doubleClickOpens(kind: Kind): Boolean = kind == Kind.Creation || kind == Kind.WorkplaceSetup

    /**
     * **What opening a row of [kind] does** (Enter, a double-click on an added one) — `SearchRowOpeners.open`'s own
     * answer, said; null where it does nothing.
     */
    fun opening(kind: Kind): String? = when (kind) {
        Kind.Task -> "opens the task's own window"
        Kind.Category -> "opens the category's window"
        Kind.RestrictivePeriod -> "opens the period's window"
        Kind.Alarm, Kind.Timer, Kind.Chrono, Kind.Reminder -> "opens its own window, with every setting it has"
        Kind.Quota, Kind.HistoryUnit, Kind.AppSetting -> "opens the Search window holding it alone"
        Kind.WorkplaceSetup -> "applies it"
        Kind.CalendarBlock -> "opens what is on the calendar at its start"
        Kind.TaskRelation -> "opens Task relations"
        Kind.Shortcut -> "opens Keyboard shortcuts"
        Kind.Window -> "opens the window, or brings it back"
        Kind.Calendar -> "opens a Search window showing the calendar"
        Kind.Creation -> "makes a new one of its kind and opens its window"
        Kind.Notification, Kind.ClaudePrompt, Kind.ClaudeAnswer, Kind.ClaudeReasoning, Kind.SearchConfiguration,
        Kind.Option, Kind.Integer, Kind.Action, Kind.Context, Kind.Md, Kind.PresenceEvolution, Kind.MinimumEvolution,
        -> null
    }

    /** Why opening a row of [kind] does nothing — where it is seen instead. */
    private fun notOpened(kind: Kind): String = when (kind) {
        Kind.Notification -> "nothing: a notification is a fact that happened"
        Kind.ClaudePrompt, Kind.ClaudeAnswer, Kind.ClaudeReasoning -> "nothing: its actions show it"
        Kind.SearchConfiguration -> "nothing: it is set in its actions and in the configurations tree"
        Kind.Context -> "nothing: it is seen and changed in the actions of its search configurations"
        Kind.Md -> "nothing: it is read and edited in its actions"
        Kind.PresenceEvolution, Kind.MinimumEvolution -> "nothing: it is edited in the actions of its tasks"
        else -> "nothing"
    }

    /** What the context [contextId] is for its rows: a right-click menu, a list a row is picked from, a Search window. */
    private enum class Place { SearchWindow, Menu, PickList }

    private fun placeOf(contextId: String): Place = when {
        contextId == ContextDomain.SEARCH_WINDOW -> Place.SearchWindow
        contextId.startsWith("menu:") -> Place.Menu
        else -> Place.PickList
    }

    /**
     * **What each press does on a row of [kind] in the context [contextId]**, opened with [config]. A menu's and a
     * drop-down's own rows (an option, an integer: [SearchDomain.isPickedRow]) act at the click's release; any other row
     * is selected at the press. "Arrange by dragging" holds where the rows are no menu's, drop-down's or actions'.
     */
    fun behaviours(kind: Kind, contextId: String, config: SearchDomain.Config): List<Behaviour> {
        val place = placeOf(contextId)
        if (kind == Kind.Action) return actionRow()
        if (place != Place.SearchWindow && SearchDomain.isPickedRow(kind)) return pickedRow(kind, place)
        val arranging = config.menuCustomizing && place == Place.SearchWindow
        return elementRow(kind, arranging)
    }

    /**
     * User rule 2026-11-26: **the contexts grouped by what the presses do on a row of [kind]** — in the catalog's order,
     * a group standing where its first context does. The same everywhere: one group.
     */
    fun groups(defaults: List<ContextDefault>, kind: Kind): List<Group> =
        ContextDomain.CONTEXTS
            .groupBy { behaviours(kind, it.id, ContextDomain.configOf(defaults, it.id)) }
            .map { (behaviours, contexts) -> Group(behaviours, contexts) }

    /** A row of the actions section: a group or an action, opened and closed in place. */
    private fun actionRow(): List<Behaviour> = listOf(
        Behaviour(Press.Click, "selects it"),
        Behaviour(Press.ArrowClick, "opens or closes it"),
        Behaviour(Press.CtrlClick, "adds it to the selection, or takes it out"),
        Behaviour(Press.ShiftClick, "selects every row from the selected one to it"),
        Behaviour(Press.DoubleClick, "opens or closes it"),
        Behaviour(Press.RightClick, "selects it; it has no menu of its own — its section's background has one"),
        Behaviour(Press.Enter, "opens or closes it"),
        Behaviour(Press.HeldPress, "selects it, as a click"),
        Behaviour(Press.Hover, "nothing"),
    )

    /** A right-click menu's entry, or a drop-down's option or integer. */
    private fun pickedRow(kind: Kind, place: Place): List<Behaviour> {
        val act = when {
            place == Place.Menu -> "runs the entry, at the release"
            kind == Kind.Integer -> "picks the number, at the release"
            else -> "picks it, at the release — ticks or unticks it where the list has check boxes (\"Select all\" ticks " +
                "every one listed, a \"create\" row makes one of what is typed)"
        }
        return listOf(
            Behaviour(Press.Click, act),
            Behaviour(Press.CtrlClick, "the same as a click"),
            Behaviour(Press.ShiftClick, "the same as a click"),
            Behaviour(Press.DoubleClick, "nothing more than its click"),
            Behaviour(Press.RightClick, "opens the row menu: add, copy id, copy path, and \"check only this\" where it has a check box"),
            Behaviour(Press.Enter, if (place == Place.Menu) "runs the selected entry" else "picks the selected row"),
            Behaviour(Press.HeldPress, "nothing until the release, which is its click"),
            Behaviour(Press.Hover, if (place == Place.Menu) "shows the entry's own list, where it has one" else "shows the option's pop-up, where it has one"),
        )
    }

    /** A row that stands for an element (or a creation row), as a Search window lists it. */
    private fun elementRow(kind: Kind, arranging: Boolean): List<Behaviour> {
        val opened = opening(kind) ?: notOpened(kind)
        val arrow = when (kind) {
            Kind.Creation -> null
            Kind.Task -> "shows or hides its sub-tree"
            else -> "shows or hides its children"
        }
        val ctrl = Behaviour(Press.CtrlClick, "adds it to the selection, or takes it out")
        val shift = Behaviour(Press.ShiftClick, "selects every row from the selected one to it")
        val menu = Behaviour(Press.RightClick, "selects it and opens the row menu (add, add and remove the others, pin, copy id, copy path…)")
        if (arranging) {
            return listOfNotNull(
                Behaviour(Press.Click, "selects it, nothing more: \"Arrange by dragging\" is on"),
                arrow?.let { Behaviour(Press.ArrowClick, it) },
                ctrl,
                shift,
                Behaviour(Press.DoubleClick, "nothing: \"Arrange by dragging\" is on"),
                menu,
                Behaviour(Press.Enter, "nothing: \"Arrange by dragging\" is on"),
                Behaviour(Press.HeldPress, "drags the selected rows; the order they are dropped in is kept"),
                Behaviour(Press.Hover, "nothing"),
            )
        }
        val double = when {
            kind == Kind.Task -> "on its title, renames it (Edit Mode)"
            doubleClickOpens(kind) -> opened
            else -> "adds it to the window's added elements; in the added elements list, where it is already: $opened"
        }
        return listOfNotNull(
            Behaviour(Press.Click, "selects it"),
            arrow?.let { Behaviour(Press.ArrowClick, it) },
            ctrl,
            shift,
            Behaviour(Press.DoubleClick, double),
            menu,
            Behaviour(Press.Enter, opened),
            Behaviour(Press.HeldPress, "selects it, as a click"),
            Behaviour(Press.Hover, "nothing"),
        )
    }
}
