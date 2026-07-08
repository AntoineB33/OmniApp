package org.example.project

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.example.project.scheduler.sync.PauseCuePushScheduler
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * PRD §15 / ARCHITECTURE.md §8 (requirement #4): the last-responsible-moment pause-cue push. The server upsert
 * fires at `min(d1, d2) − margin` (2 s when the cue moves later, 1 s otherwise), never in steady state, and a
 * new prediction re-arms the single timer. Uses the test scheduler's virtual clock for both `nowMillis` and
 * `delay`, mirroring production where both are real wall time.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PauseCuePushSchedulerTest {
    private val cancelMargin = 2_000L
    private val publishMargin = 1_000L

    private fun scheduler(dispatcher: TestDispatcher, pushes: MutableList<Long>) =
        PauseCuePushScheduler(
            scope = CoroutineScope(dispatcher),
            nowMillis = { dispatcher.scheduler.currentTime },
            cancelMarginMillis = cancelMargin,
            publishMarginMillis = publishMargin,
            push = { pushes += it },
        )

    @Test
    fun steady_state_never_pushes() {
        val dispatcher = StandardTestDispatcher()
        runTest(dispatcher) {
            val pushes = mutableListOf<Long>()
            val s = scheduler(dispatcher, pushes)
            s.seed(50_000)
            s.onPrediction(50_000) // d1 == d2

            advanceTimeBy(1_000_000)
            runCurrent()
            assertEquals(emptyList(), pushes, "an unchanged prediction is never pushed")
        }
    }

    @Test
    fun postpone_pushes_two_seconds_before_the_old_instant() {
        val dispatcher = StandardTestDispatcher()
        runTest(dispatcher) {
            val pushes = mutableListOf<Long>()
            val s = scheduler(dispatcher, pushes)
            s.seed(10_000) // d1
            s.onPrediction(20_000) // d2 > d1 → target = d1, margin = 2 s → fire at 8_000

            advanceTimeBy(7_999)
            runCurrent()
            assertEquals(emptyList(), pushes, "nothing before d1 − 2 s")

            advanceTimeBy(1) // now 8_000
            runCurrent()
            assertEquals(listOf(20_000L), pushes, "pushes the NEW instant at d1 − 2 s")
        }
    }

    @Test
    fun advance_pushes_one_second_before_the_new_instant() {
        val dispatcher = StandardTestDispatcher()
        runTest(dispatcher) {
            val pushes = mutableListOf<Long>()
            val s = scheduler(dispatcher, pushes)
            s.seed(20_000) // d1
            s.onPrediction(10_000) // d2 < d1 → target = d2, margin = 1 s → fire at 9_000

            advanceTimeBy(8_999)
            runCurrent()
            assertEquals(emptyList(), pushes, "nothing before d2 − 1 s")

            advanceTimeBy(1) // now 9_000
            runCurrent()
            assertEquals(listOf(10_000L), pushes, "pushes at d2 − 1 s")
        }
    }

    @Test
    fun first_publish_uses_the_one_second_margin() {
        val dispatcher = StandardTestDispatcher()
        runTest(dispatcher) {
            val pushes = mutableListOf<Long>()
            val s = scheduler(dispatcher, pushes)
            s.seed(null) // server empty
            s.onPrediction(10_000) // fire at d2 − 1 s = 9_000

            advanceTimeBy(9_000)
            runCurrent()
            assertEquals(listOf(10_000L), pushes)
        }
    }

    @Test
    fun a_change_inside_the_margin_pushes_immediately() {
        val dispatcher = StandardTestDispatcher()
        runTest(dispatcher) {
            val pushes = mutableListOf<Long>()
            val s = scheduler(dispatcher, pushes)
            s.seed(null)

            advanceTimeBy(9_999) // now within 1 s of the target
            runCurrent()
            s.onPrediction(10_000) // target − margin = 9_000 already passed → immediate
            runCurrent()
            assertEquals(listOf(10_000L), pushes)
        }
    }

    @Test
    fun a_pose_pinned_to_the_now_line_never_pushes() {
        // The account1-empty-and-open guarantee. Scenario: the now-line sits inside a 5/15-min rest pose the
        // user is working through (screenActive() true) on a freshly-emptied account, so (a) the server holds
        // nothing → d1 = null, and (b) the pose is pinned to the now-line → its end d2 = now + duration climbs
        // on every engine tick. Each new prediction re-arms the single deferred timer with wait = duration −
        // margin (≈ 299 s for the 5-min pose), which is far longer than the ≤30 s advance-tick cadence, so the
        // timer is cancelled and re-armed long before it could fire. Result: NOT one write per minute (nor per
        // tick) — zero pause_cue_schedule writes for as long as the pose keeps sliding and the user is idle.
        val dispatcher = StandardTestDispatcher()
        runTest(dispatcher) {
            val pushes = mutableListOf<Long>()
            val scope = CoroutineScope(dispatcher)
            val s =
                PauseCuePushScheduler(
                    scope = scope,
                    nowMillis = { dispatcher.scheduler.currentTime },
                    cancelMarginMillis = cancelMargin,
                    publishMarginMillis = publishMargin,
                    push = { pushes += it },
                )
            s.seed(null) // freshly-emptied account: the server holds no cue instant

            val poseDurationMillis = 5L * 60 * 1_000 // the shortest rest pose (5 min)
            val tickMillis = 30_000L // production advance-tick cadence (the worst case — fewest re-arms)
            repeat(120) { // ~1 h of ticks
                s.onPrediction(dispatcher.scheduler.currentTime + poseDurationMillis) // d2 re-pinned to now + duration
                advanceTimeBy(tickMillis)
                runCurrent()
            }

            assertEquals(emptyList(), pushes, "a pose pinned to the now-line (sliding d2, empty server) never pushes")
            scope.cancel() // drop the still-pending deferred timer so runTest's end-of-test drain doesn't fire it
        }
    }

    @Test
    fun a_newer_prediction_re_arms_and_only_it_pushes() {
        val dispatcher = StandardTestDispatcher()
        runTest(dispatcher) {
            val pushes = mutableListOf<Long>()
            val s = scheduler(dispatcher, pushes)
            s.seed(null)
            s.onPrediction(10_000) // would fire at 9_000

            advanceTimeBy(5_000)
            runCurrent()
            s.onPrediction(8_000) // supersedes → target 8_000, margin 1 s → fire at 7_000

            advanceTimeBy(2_000) // now 7_000
            runCurrent()
            assertEquals(listOf(8_000L), pushes, "only the latest prediction is pushed")

            advanceTimeBy(1_000_000)
            runCurrent()
            assertEquals(listOf(8_000L), pushes, "the superseded 10_000 timer never fires")
        }
    }
}
