package org.example.project

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import org.example.project.scheduler.domain.BankedBreak
import org.example.project.scheduler.domain.DynamicPeriods
import org.example.project.scheduler.domain.FrozenScreenBreaks
import org.example.project.scheduler.domain.SchedulerDomain

/**
 * `docs/scheduler_requirements.md` § *Mode switching*: *"The current $now line$ mode can be decided anytime by a
 * program, but can't go in mode 1 during a '20s screen break' restrictive period. When $now line$ enters a '20s screen
 * break' restrictive period in mode 1, it gets in mode 3, and when it leaves it, it gets in mode 1 unless the user
 * wanted it to stay in mode 3, or unless it is in mode 2."* ([SchedulerDomain.lookAwayHoldUntil], [SchedulerDomain.tpMode]).
 */
class ModeSwitchingTest {
    private val SEC = 1_000L
    private val T = 1_700_000_000_000L
    private val lookAway = BankedBreak(DynamicPeriods.LABEL_20S, T, T + 20 * SEC)
    private val record = FrozenScreenBreaks(listOf(lookAway), T + 5 * SEC, T + 5 * SEC)

    private fun mode(unlocked: Boolean, away: Boolean, hold: Long?, now: Long) =
        SchedulerDomain.tpMode(unlocked, away, lookAwayHold = hold != null && now < hold)

    @Test
    fun a_line_at_a_screen_that_enters_a_look_away_is_in_mode_3_until_it_leaves_it() {
        val now = T + 5 * SEC
        val hold = SchedulerDomain.lookAwayHoldUntil(null, record, now, DynamicPeriods.MODE_AT_SCREEN)
        assertEquals(lookAway.endMillis, hold)
        assertEquals(DynamicPeriods.MODE_ON_BREAK, mode(unlocked = true, away = false, hold = hold, now = now))
        // It leaves it: mode 1 again.
        val after = lookAway.endMillis + 1
        val released = SchedulerDomain.lookAwayHoldUntil(hold, record, after, DynamicPeriods.MODE_AT_SCREEN)
        assertNull(released)
        assertEquals(DynamicPeriods.MODE_AT_SCREEN, mode(unlocked = true, away = false, hold = released, now = after))
    }

    @Test
    fun it_cannot_go_to_mode_1_during_a_look_away_it_is_in_through_the_away_button() {
        // In mode 3 by the button, inside a look-away; the button clears at the unlock — the line stays in mode 3.
        val now = T + 10 * SEC
        val hold = SchedulerDomain.lookAwayHoldUntil(null, record, now, DynamicPeriods.MODE_ON_BREAK)
        assertEquals(lookAway.endMillis, hold)
        assertEquals(DynamicPeriods.MODE_ON_BREAK, mode(unlocked = true, away = false, hold = hold, now = now + SEC))
    }

    @Test
    fun mode_2_is_never_held() {
        // "…unless it is in mode 2."
        assertNull(SchedulerDomain.lookAwayHoldUntil(null, record, T + 5 * SEC, DynamicPeriods.MODE_AWAY))
        assertNull(SchedulerDomain.lookAwayHoldUntil(T + 20 * SEC, record, T + 6 * SEC, DynamicPeriods.MODE_AWAY))
    }

    @Test
    fun the_hold_follows_the_break_the_look_away_grew_into() {
        // The chain merge can make the look-away the start of a longer break: "it" is the break the line entered.
        val merged = BankedBreak(DynamicPeriods.LABEL_15MIN, T, T + 15 * 60 * SEC)
        val grown = FrozenScreenBreaks(listOf(merged), T + 5 * SEC, T + 5 * SEC)
        val hold = SchedulerDomain.lookAwayHoldUntil(T + 20 * SEC, grown, T + 6 * SEC, DynamicPeriods.MODE_AT_SCREEN)
        assertEquals(merged.endMillis, hold)
    }

    @Test
    fun a_pose_banked_while_away_does_not_hold_a_line_that_comes_back_to_the_screen() {
        // Only a LOOK-AWAY entered holds the line; a pose the line is inside at the unlock is removed instead (the
        // requirements' exception, `bankScreenBreaks`).
        val pose = BankedBreak(DynamicPeriods.LABEL_5MIN, T, T + 5 * 60 * SEC)
        val away = FrozenScreenBreaks(listOf(pose), T + 60 * SEC, T + 60 * SEC)
        assertNull(SchedulerDomain.lookAwayHoldUntil(null, away, T + 61 * SEC, DynamicPeriods.MODE_AT_SCREEN))
    }
}
