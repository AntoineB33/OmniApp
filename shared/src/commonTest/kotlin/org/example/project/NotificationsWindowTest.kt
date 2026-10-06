package org.example.project

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.example.project.scheduler.domain.SearchDomain
import org.example.project.scheduler.state.NotificationSource
import org.example.project.scheduler.state.SchedulerIntent
import org.example.project.scheduler.state.SchedulerReducer
import org.example.project.scheduler.state.SchedulerState

/**
 * User rule 2026-10-04: **the notifications window** — when a notification (written, spoken or both) fires while the
 * app is not in focus, a Search window comes up, in front, on the notifications posted since the app lost the focus; it
 * goes on gathering until the user closes it.
 */
class NotificationsWindowTest {
    private val t0 = 1_800_000_000_000L
    private val minute = 60_000L

    private fun logged(vararg posts: Pair<Long, String>): SchedulerState =
        posts.fold(SchedulerState.empty()) { s, (at, title) ->
            SchedulerReducer.reduce(s, SchedulerIntent.RecordNotification(title, "message of $title", at))
        }

    private fun titles(state: SchedulerState, config: SearchDomain.Config): List<String> =
        SearchDomain.results(state, config.kinds, config.query, filters = config.filters, sorts = config.sorts).map { (it as SearchDomain.ItemResult).name }

    @Test
    fun the_window_lists_what_fired_since_the_app_lost_the_focus_newest_first() {
        // Anomaly 2026-10-04: a timer ended out of focus and the window listed the whole log, days of it, instead of
        // the one notification that fired while the user was away.
        val s = logged(t0 to "seen in focus", t0 + 2 * minute to "timer", t0 + 3 * minute to "break")
        val lostFocusAt = t0 + minute
        assertEquals(listOf("break", "timer"), titles(s, SearchDomain.notificationsConfig(lostFocusAt)))
        // One fired on the very instant the focus was lost counts as fired out of focus.
        assertEquals(listOf("break", "timer"), titles(s, SearchDomain.notificationsConfig(t0 + 2 * minute)))
        // A Search window on the notifications opened by hand lists the whole log.
        assertEquals(3, titles(s, SearchDomain.kindSearchConfig(SearchDomain.Kind.Notification)).size)
        // Two posted on one instant are two rows.
        val twins = logged(t0 to "a", t0 to "b")
        assertEquals(2, SearchDomain.results(twins, setOf(SearchDomain.Kind.Notification), "").map { (it as SearchDomain.ItemResult).id }.toSet().size)
    }

    /** Anomaly 2026-10-05: the Search window could not keep only the scheduler engine's notifications. */
    @Test
    fun the_notifications_are_filtered_by_what_they_came_from() {
        val titles = org.example.project.scheduler.state.NotificationTitles
        val s = logged(
            t0 to titles.TASK_TO_DO_NOW, t0 + minute to titles.SCREEN_BREAK, t0 + 2 * minute to titles.SCREEN_BREAK_OVER,
            t0 + 3 * minute to titles.TIMER, t0 + 4 * minute to titles.TASK_TO_DO_NOW, t0 + 5 * minute to "posted by an older build",
        )
        fun from(vararg sources: NotificationSource): List<String> {
            val config = SearchDomain.kindSearchConfig(SearchDomain.Kind.Notification)
                .let { it.copy(filters = it.filters.copy(notificationSources = sources.toSet())) }
            assertEquals(config, SearchDomain.Config.decode(config.encode()), "kept with the configuration")
            assertEquals(sources.isNotEmpty(), config.filters.isOn(SearchDomain.Setting.NotificationSourceSetting))
            return titles(s, config).sorted()
        }
        assertEquals(6, from().size, "nothing checked: from anything")
        assertEquals(listOf(titles.TASK_TO_DO_NOW, titles.TASK_TO_DO_NOW), from(NotificationSource.TaskToDoNow))
        assertEquals(listOf(titles.SCREEN_BREAK, titles.SCREEN_BREAK_OVER), from(NotificationSource.ScreenBreak))
        assertEquals(listOf(titles.TASK_TO_DO_NOW, titles.TASK_TO_DO_NOW, titles.TIMER), from(NotificationSource.TaskToDoNow, NotificationSource.Timer))
        assertEquals(listOf("posted by an older build"), from(NotificationSource.Other))
        assertEquals(emptyList(), from(NotificationSource.Alarm))
        // Every title the app posts under has a source of its own, and a ring's title is its kind's label.
        assertEquals(NotificationSource.Alarm, NotificationSource.of(org.example.project.scheduler.engine.RingKind.Alarm.label))
        assertEquals(NotificationSource.Timer, NotificationSource.of(org.example.project.scheduler.engine.RingKind.Timer.label))
        assertEquals(NotificationSource.Reminder, NotificationSource.of(org.example.project.scheduler.engine.RingKind.Reminder.label))
        assertEquals(NotificationSource.SleepSchedule, NotificationSource.of(titles.STOP_WORK))
        assertEquals(NotificationSource.Shortcut, NotificationSource.of(titles.SHORTCUT_RECEIVED))
        assertEquals(NotificationSource.Shortcut, NotificationSource.of(titles.NOTIFICATIONS_ON))
        // The app's own notifications window keeps its "since" beside the filter.
        val since = SearchDomain.notificationsConfig(t0 + 2 * minute)
            .let { it.copy(filters = it.filters.copy(notificationSources = setOf(NotificationSource.TaskToDoNow))) }
        assertEquals(listOf(titles.TASK_TO_DO_NOW), titles(s, since))
        // A configuration stored before the filter existed, or naming a source this build does not know: from anything.
        assertEquals(emptySet(), SearchDomain.Config.decode("""{"kinds":["Notification"]}""")!!.filters.notificationSources)
        assertEquals(
            setOf(NotificationSource.Timer),
            SearchDomain.Config.decode("""{"kinds":["Notification"],"notificationSources":["Timer","FromALaterBuild"]}""")!!.filters.notificationSources,
        )
    }

    /**
     * User rule 2026-10-06: what the scheduler engine produces — each set of rules it found — is listed with the
     * HISTORY UNITS, kept alone by "From the scheduler engine"; a notification never leads to a set of rules.
     */
    @Test
    fun the_scheduler_engines_sets_of_rules_are_listed_with_the_history_units() {
        val run = org.example.project.scheduler.state.SchedulerRunEntry(
            timeMillis = t0 + minute, kind = org.example.project.scheduler.state.SchedulerRunEntry.Kind.Replan,
            horizonMillis = t0 + 60 * minute, panelCount = 4,
            ruleState = listOf("Read 50% min 10"), rules = listOf("+0:00:00  run Read", "+0:10:00  run Walk"),
        )
        val later = run.copy(timeMillis = t0 + 5 * minute, kind = org.example.project.scheduler.state.SchedulerRunEntry.Kind.Extension)
        val runs = listOf(run, later)
        // An account with one History Unit of the user's (a task named) and one posted notification.
        var s = SchedulerState.empty()
        s = SchedulerReducer.reduce(s, SchedulerIntent.SetCellTitle(s.lists[s.rootListId]!!.cellIds[0], "Read"))
        s = SchedulerReducer.reduce(s, SchedulerIntent.RecordNotification(org.example.project.scheduler.state.NotificationTitles.TASK_TO_DO_NOW, "Read", t0))
        fun units(engine: SearchDomain.Tri, extra: SearchDomain.Filters.() -> SearchDomain.Filters = { this }) =
            SearchDomain.results(
                s, setOf(SearchDomain.Kind.HistoryUnit), "",
                filters = SearchDomain.Filters(historyEngine = engine).extra(), schedulerRuns = runs,
            ).map { it as SearchDomain.ItemResult }
        val all = units(SearchDomain.Tri.Any)
        val engine = units(SearchDomain.Tri.Yes)
        val users = units(SearchDomain.Tri.No)
        // From the scheduler engine: the sets of rules it found, and nothing else.
        assertEquals(setOf("Re-plan", "Horizon extension"), engine.mapTo(HashSet()) { it.name })
        assertTrue(engine.all { SearchDomain.isSchedulerRunId(it.id) && "2 rules" in it.detail })
        assertTrue(users.isNotEmpty() && users.none { SearchDomain.isSchedulerRunId(it.id) }, "the user's own units")
        assertEquals(all.size, engine.size + users.size)
        // It is a choice of "Made in", beside the windows — and the first of them.
        assertEquals(SearchDomain.MadeIn.Engine, SearchDomain.MADE_IN_CHOICES.first())
        assertEquals(1 + org.example.project.scheduler.state.HistoryWindow.entries.size, SearchDomain.MADE_IN_CHOICES.size)
        val onEngine = SearchDomain.Filters().withMadeIn(SearchDomain.MadeIn.Engine)
        assertEquals(SearchDomain.Filters(historyEngine = SearchDomain.Tri.Yes), onEngine)
        assertEquals(SearchDomain.MadeIn.Engine, onEngine.madeIn)
        assertTrue(onEngine.isOn(SearchDomain.Setting.HistoryWindowSetting))
        val calendar = SearchDomain.MadeIn.Window(org.example.project.scheduler.state.HistoryWindow.Calendar)
        // Picking a window leaves the engine, and the other way round: one field, never both asked.
        assertEquals(calendar, onEngine.withMadeIn(calendar).madeIn)
        assertEquals(SearchDomain.Tri.Any, onEngine.withMadeIn(calendar).historyEngine)
        assertEquals(null, SearchDomain.Filters().withMadeIn(calendar).withMadeIn(SearchDomain.MadeIn.Engine).historyWindow)
        assertEquals(SearchDomain.Filters(), onEngine.withMadeIn(null))
        assertEquals("Scheduler engine", SearchDomain.MadeIn.Engine.label)
        // A set of rules was made in no window, is in no stack and is never undone: a unit's own filters leave it out.
        assertEquals(emptyList(), units(SearchDomain.Tri.Any) { copy(historyWindow = org.example.project.scheduler.state.HistoryWindow.Calendar) }.filter { SearchDomain.isSchedulerRunId(it.id) })
        assertEquals(emptyList(), units(SearchDomain.Tri.Yes) { copy(historyUndone = SearchDomain.Tri.Yes) })
        // A row leads back to its run — by what the run is, never its place in a list that drops its oldest.
        val row = engine.first { it.name == "Re-plan" }
        assertEquals(run, SearchDomain.schedulerRunOf(runs, row.id))
        assertEquals(run, SearchDomain.schedulerRunOf(listOf(later, run), row.id))
        assertEquals(null, SearchDomain.schedulerRunOf(listOf(later), row.id), "kept in memory only: gone with the list")
        assertEquals(t0 + minute, SearchDomain.schedulerRunTimeOf(row.id))
        // An added run is found again by its key, kept by the stored configuration, and listed newest first by date.
        val key = SearchDomain.keyOf(row)
        assertEquals(listOf<SearchDomain.Result>(row), SearchDomain.resolve(s, listOf(key), schedulerRuns = runs))
        val config = SearchDomain.Config(kinds = setOf(SearchDomain.Kind.HistoryUnit), added = listOf(key), filters = SearchDomain.Filters(historyEngine = SearchDomain.Tri.Yes))
        assertEquals(config, SearchDomain.Config.decode(config.encode()))
        assertEquals(SearchDomain.Tri.Any, SearchDomain.Config.decode("""{"kinds":["HistoryUnit"]}""")!!.filters.historyEngine)
        val byDate = SearchDomain.results(
            s, setOf(SearchDomain.Kind.HistoryUnit), "", filters = SearchDomain.Filters(historyEngine = SearchDomain.Tri.Yes),
            sorts = listOf(SearchDomain.SortMethod(SearchDomain.Kind.HistoryUnit, SearchDomain.SortKey.HistoryDate, descending = true)),
            schedulerRuns = runs,
        )
        assertEquals(listOf("Horizon extension", "Re-plan"), byDate.map { it.name })
        // The notifications are the posted ones alone, whatever the engine ran.
        assertEquals(1, SearchDomain.results(s, setOf(SearchDomain.Kind.Notification), "", schedulerRuns = runs).size)
        assertEquals(SearchDomain.Filters(notificationsSinceMillis = t0), SearchDomain.notificationsConfig(t0).filters)
    }

    /**
     * Anomaly 2026-10-06: *"I filtered for history units from scheduler engine and got nothing"* — the app had just
     * started and kept its plan, so nothing had run in this session, and the runs are kept in memory only.
     */
    @Test
    fun a_launch_that_keeps_its_plan_lists_the_set_of_rules_in_force() {
        var s = SchedulerState.empty()
        s = SchedulerReducer.reduce(s, SchedulerIntent.SetCellTitle(s.lists[s.rootListId]!!.cellIds[0], "Read"))
        s = SchedulerReducer.reduce(s, SchedulerIntent.SetCellTitle(s.lists[s.rootListId]!!.cellIds[1], "Walk"))
        assertEquals(null, org.example.project.scheduler.domain.SchedulerDomain.planInForceRun(s, t0, 1), "no plan, no rules")
        val planned = SchedulerReducer.reduce(s, SchedulerIntent.RefreshSchedule(t0))
        val madeAt = planned.planBasis!!.madeAtMillis
        // The next launch, ten minutes on: the plan is kept, nothing runs — the rules in force are a row all the same.
        val run = org.example.project.scheduler.domain.SchedulerDomain.planInForceRun(planned, t0 + 10 * minute, 1)!!
        assertEquals(org.example.project.scheduler.state.SchedulerRunEntry.Kind.KeptAtLaunch, run.kind)
        assertEquals(madeAt, run.timeMillis, "dated when the rules were found, not when the app started")
        assertTrue(run.rules.size > 1 && run.rules.any { "run " in it }, "the set of rules, read at the launch: ${run.rules}")
        // The rule state input it answers is the state's own, read as a fill reads it.
        assertEquals(setOf("Read", "Walk"), run.ruleState.mapTo(HashSet()) { it.substringBefore(" — ") })
        assertTrue(run.ruleState.all { "priority" in it && "minimum" in it && "resilience" in it }, run.ruleState.toString())
        val rows = SearchDomain.results(
            planned, setOf(SearchDomain.Kind.HistoryUnit), "",
            filters = SearchDomain.Filters().withMadeIn(SearchDomain.MadeIn.Engine), schedulerRuns = listOf(run),
        )
        assertEquals(listOf("Plan in force at launch"), rows.map { it.name })
        // Added, its actions lead with "Set of rules" — an action of its own, before "Information".
        val added = SearchDomain.resolve(planned, listOf(SearchDomain.keyOf(rows.single())), schedulerRuns = listOf(run))
        val actions = SearchDomain.actionsFor(
            SearchDomain.addedActions("", SearchDomain.Kind.entries.toSet(), SearchDomain.actionKindsOf(added)), added,
        ).first { it.first == SearchDomain.Kind.HistoryUnit }.second
        assertEquals(listOf(SearchDomain.AddedAction.HistoryRules, SearchDomain.AddedAction.HistoryInformation), actions)
        assertEquals("Set of rules", SearchDomain.AddedAction.HistoryRules.label)
    }

    @Test
    fun the_window_keeps_its_since_across_a_restart_and_is_told_from_a_search_opened_by_hand() {
        val config = SearchDomain.notificationsConfig(t0)
        assertEquals(config, SearchDomain.Config.decode(config.encode()))
        assertTrue(SearchDomain.isNotificationsWindow(SearchDomain.Config.decode(config.encode())!!))
        // A configuration stored before the filter existed, or a search on the notifications opened by hand: every
        // notification, and not the window the app opens.
        val byHand = SearchDomain.Config(kinds = setOf(SearchDomain.Kind.Notification))
        assertEquals(null, SearchDomain.Config.decode(byHand.encode())!!.filters.notificationsSinceMillis)
        assertFalse(SearchDomain.isNotificationsWindow(byHand))
        // User rule 2026-10-06: the window the app opens has a name of its own; one opened by hand is a Search window.
        assertEquals("unfocused notif", SearchDomain.windowTitle(SearchDomain.Config.decode(config.encode())!!))
        assertEquals("Search", SearchDomain.windowTitle(byHand))
        assertEquals("Search", SearchDomain.windowTitle(SearchDomain.Config()))
    }

    @Test
    fun the_window_is_owed_for_each_notification_posted_out_of_focus_once() {
        val lost = t0
        // In focus (it never lost it): nothing opens.
        assertFalse(SearchDomain.notificationsWindowOwed(t0 + minute, unfocusedSinceMillis = null, answeredAtMillis = null))
        // Out of focus, and a notification fires: owed.
        assertTrue(SearchDomain.notificationsWindowOwed(lost + minute, lost, null))
        // The last notification predates the loss of focus: it was posted with the user looking.
        assertFalse(SearchDomain.notificationsWindowOwed(lost - minute, lost, null))
        // No notification at all.
        assertFalse(SearchDomain.notificationsWindowOwed(null, lost, null))
        // Shown for that one already: asked again (the focus coming back), nothing more is owed…
        assertFalse(SearchDomain.notificationsWindowOwed(lost + minute, lost, answeredAtMillis = lost + minute))
        // …and a second one out of focus brings the window back in front, even though it is already open.
        assertTrue(SearchDomain.notificationsWindowOwed(lost + 2 * minute, lost, answeredAtMillis = lost + minute))
    }
}
