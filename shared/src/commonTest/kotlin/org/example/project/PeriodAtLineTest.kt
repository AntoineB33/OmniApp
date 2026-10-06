package org.example.project

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import org.example.project.scheduler.domain.DynamicPeriods
import org.example.project.scheduler.domain.PeriodKindConfig
import org.example.project.scheduler.domain.PeriodKinds
import org.example.project.scheduler.domain.SchedulerDomain
import org.example.project.scheduler.model.TaskTimeRange

/**
 * User rule 2026-10-05: **a `sleep` period dragged onto the now-line in mode 1 is retracted by it** — it shortens as
 * it is carried across, is gone as its end reaches the line, and stands whole again on the other side. A consequence
 * of two requirements, not a rule of the drag: a mode-1 line is not in "no screen", and the "no screen" a rule lays
 * with a `sleep` period is where that period is (it moves with it; it never stays behind or stretches to reach it).
 */
class PeriodAtLineTest {
    private val now = 1_800_000_000_000L
    private val hour = 3_600_000L
    private val minute = 60_000L
    private val config = PeriodKindConfig.DEFAULT

    private fun at(start: Long, end: Long, kind: String = PeriodKinds.SLEEP, mode: Int = DynamicPeriods.MODE_AT_SCREEN) =
        SchedulerDomain.periodAtLine(TaskTimeRange(start, end), kind, now, mode, config)

    @Test
    fun a_sleep_period_carried_across_a_mode_1_line_shortens_is_gone_then_stands_whole_on_the_other_side() {
        // Ahead of the line: whole.
        assertEquals(TaskTimeRange(now + hour, now + 3 * hour), at(now + hour, now + 3 * hour))
        // Its start has just reached the line: still whole — the line is not IN it, it is at its edge.
        assertEquals(TaskTimeRange(now, now + 2 * hour), at(now, now + 2 * hour))
        // Carried further: ]line; its end], shorter at each step.
        assertEquals(TaskTimeRange(now, now + hour), at(now - hour, now + hour))
        assertEquals(TaskTimeRange(now, now + 10 * minute), at(now - 110 * minute, now + 10 * minute))
        // Its end reaches the line: nothing of it is left (less than a minute is none).
        assertNull(at(now - 2 * hour + 30_000L, now + 30_000L))
        // And the hand goes on: wholly behind the line, it is whole again.
        assertEquals(TaskTimeRange(now - 2 * hour, now), at(now - 2 * hour, now))
        assertEquals(TaskTimeRange(now - 3 * hour, now - hour), at(now - 3 * hour, now - hour))
    }

    @Test
    fun it_is_the_no_screen_that_gives_way_and_only_to_a_line_at_a_screen() {
        val across = TaskTimeRange(now - hour, now + hour)
        // A "no screen" period itself, and what carries one.
        assertEquals(TaskTimeRange(now, now + hour), at(now - hour, now + hour, PeriodKinds.NO_SCREEN))
        assertEquals(TaskTimeRange(now, now + hour), at(now - hour, now + hour, PeriodKinds.SLEEP))
        // A period that says nothing about a screen is where the hand puts it.
        assertEquals(false, config.isOrImpliesNoScreen("deep work"))
        assertEquals(across, at(now - hour, now + hour, "deep work"))
        // Modes 2 and 3: the line must BE covered there, so nothing gives way.
        for (mode in listOf(DynamicPeriods.MODE_AWAY, DynamicPeriods.MODE_ON_BREAK)) {
            assertEquals(across, at(now - hour, now + hour, PeriodKinds.SLEEP, mode), "mode $mode")
        }
    }
}
