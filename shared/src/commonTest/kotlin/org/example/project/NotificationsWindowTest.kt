package org.example.project

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.example.project.scheduler.domain.SearchDomain
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
