package org.example.project

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.example.project.scheduler.domain.SchedulerDomain
import org.example.project.scheduler.engine.SchedulerEngine
import org.example.project.scheduler.model.TaskTimeRange
import org.example.project.scheduler.persistence.DeclaredAwaySpanRecord
import org.example.project.scheduler.persistence.DeclaredAwayStore
import org.example.project.scheduler.ui.TaskSchedulerViewModel
import org.example.project.time.AppClock

/**
 * PRD §8/§15, the "I'm away" button vs. a RESTART: **the episodes survive it, the flag does not.**
 *
 * The button is the one no-screen fact the OS can never re-supply — the machine stays UNLOCKED while it is
 * on, so no session log will ever show the stretch — and the engine used to hold the episodes in memory
 * only. A restart therefore erased them: the layer
 * ([SchedulerDomain.declaredAwayRegions]), the hatch drawn out of it and the §9 record-bank evidence all
 * fell silent over a stretch the `t_p` mode had just been 3 for. Observed on account 3 (2026-09-12), where
 * a 17-minute declared-away spell vanished the moment a redeploy restarted the app.
 *
 * The FLAG is the other half of the rule and goes the other way: it is a live declaration, and an app that
 * re-asserted it at startup would be claiming an absence it cannot see the end of — only a lock→unlock edge
 * clears it, and a machine left unlocked never produces one.
 */
class DeclaredAwayPersistenceTest {

    /** An in-memory [DeclaredAwayStore], keyed by start like the real table. */
    private class FakeAwayStore(seed: List<DeclaredAwaySpanRecord> = emptyList()) : DeclaredAwayStore {
        val rows = seed.associateBy { it.startMillis }.toMutableMap()

        override fun loadDeclaredAwaySpans(): List<DeclaredAwaySpanRecord> = rows.values.sortedBy { it.startMillis }

        override fun saveDeclaredAwaySpan(record: DeclaredAwaySpanRecord) {
            rows[record.startMillis] = record
        }

        override fun pruneDeclaredAwaySpans(floorMillis: Long) {
            rows.values.removeAll { it.endMillis <= floorMillis }
        }
    }

    private class MovableClock(var now: Long) : AppClock {
        override fun nowMillis(): Long = now
    }

    private fun engineOver(store: DeclaredAwayStore, clock: AppClock): SchedulerEngine =
        SchedulerEngine(
            vm = TaskSchedulerViewModel(store = null, saveDispatcher = Dispatchers.Default),
            clock = clock,
            scope = CoroutineScope(Dispatchers.Unconfined),
            screenActive = { true },
            speak = {},
            declaredAwayStore = store,
        )

    @Test
    fun a_closed_away_episode_is_still_there_after_a_restart() {
        val store = FakeAwayStore()
        val clock = MovableClock(10_000_000)

        val first = engineOver(store, clock)
        first.setUserAway(true)
        clock.now = 11_020_000 // ~17 minutes away, the account-3 spell
        first.setUserAway(false)
        assertEquals(
            listOf(TaskTimeRange(10_000_000, 11_020_000)),
            first.declaredAwaySpans.value,
            "the live engine did not record the episode",
        )

        // The redeploy: a brand-new engine over the same store.
        val second = engineOver(store, clock)
        assertEquals(
            listOf(TaskTimeRange(10_000_000, 11_020_000)),
            second.declaredAwaySpans.value,
            "the restart forgot the episode the user declared",
        )
        assertEquals(
            listOf(TaskTimeRange(10_000_000, 11_020_000)),
            SchedulerDomain.declaredAwayRegions(second.declaredAwaySpans.value, second.declaredAwaySince.value, clock.now),
            "…so the calendar layer has nothing to hatch",
        )
        assertFalse(second.userAway.value, "the BUTTON must not come back on — it is a live declaration")
    }

    /**
     * The row is keyed by its START, so the press that opens it, every beat that extends it and the "I'm
     * back" that closes it are one row — an episode can never come back as a pile of fragments.
     */
    @Test
    fun one_episode_is_one_row_from_press_to_return() {
        val store = FakeAwayStore()
        val clock = MovableClock(5_000)

        val engine = engineOver(store, clock)
        engine.setUserAway(true)
        assertEquals(1, store.rows.size, "the press did not open a row")
        clock.now = 65_000
        engine.setUserAway(false)

        assertEquals(listOf(DeclaredAwaySpanRecord(5_000, 65_000)), store.loadDeclaredAwaySpans())
    }

    /**
     * Killed mid-away (the deploy's own kill, a crash): the row the store still holds comes back as a
     * CLOSED span ending where the last beat left it. The stretch the user declared is a fact whatever
     * became of the process; how much longer it ran past the last thing the app saw is not knowable.
     */
    @Test
    fun an_episode_the_app_died_inside_comes_back_closed_at_its_last_beat() {
        val clock = MovableClock(400_000)
        val store = FakeAwayStore(listOf(DeclaredAwaySpanRecord(100_000, 130_000)))

        val engine = engineOver(store, clock)

        assertEquals(listOf(TaskTimeRange(100_000, 130_000)), engine.declaredAwaySpans.value)
        assertFalse(engine.userAway.value)
    }

    /** Load prunes to the same 24 h window the no-screen evidence answers over, like the in-memory record. */
    @Test
    fun episodes_older_than_the_evidence_window_are_not_loaded() {
        val dayMillis = 24L * 60 * 60 * 1000
        val clock = MovableClock(10 * dayMillis)
        val store =
            FakeAwayStore(
                listOf(
                    DeclaredAwaySpanRecord(3 * dayMillis, 3 * dayMillis + 60_000), // long gone
                    DeclaredAwaySpanRecord(10 * dayMillis - 60_000, 10 * dayMillis - 30_000), // inside the window
                    DeclaredAwaySpanRecord(5 * dayMillis, 5 * dayMillis), // degenerate: never a span
                ),
            )

        val engine = engineOver(store, clock)

        assertEquals(listOf(TaskTimeRange(10 * dayMillis - 60_000, 10 * dayMillis - 30_000)), engine.declaredAwaySpans.value)
    }

    /** A store with no capability at all keeps exactly the behaviour the engine had before the table. */
    @Test
    fun no_store_still_works_in_memory() {
        val clock = MovableClock(1_000)
        val engine =
            SchedulerEngine(
                vm = TaskSchedulerViewModel(store = null, saveDispatcher = Dispatchers.Default),
                clock = clock,
                scope = CoroutineScope(Dispatchers.Unconfined),
                screenActive = { true },
                speak = {},
            )

        engine.setUserAway(true)
        clock.now = 61_000
        engine.setUserAway(false)

        assertTrue(engine.declaredAwaySpans.value.isNotEmpty())
    }
}
