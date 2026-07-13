package org.example.project

import org.example.project.scheduler.domain.SchedulerDomain
import org.example.project.scheduler.model.SleepSchedule
import org.example.project.scheduler.persistence.SchedulerStateCodec
import org.example.project.scheduler.state.SchedulerIntent
import org.example.project.scheduler.state.SchedulerReducer
import org.example.project.scheduler.state.SchedulerState
import kotlinx.datetime.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * The Sleep/Work toggle state ([SchedulerState.sleepingUntilMillis]): the reducer sets/clears it, [isSleeping]
 * reflects the wake instant, the field round-trips through the codec, an old payload written before it existed
 * still decodes as "working", and the next-wake-instant helper picks the first wake strictly after `now`.
 */
class SleepModeTest {
    @Test
    fun set_sleep_mode_sets_and_clears_the_wake_instant() {
        val working = SchedulerState.empty()
        assertNull(working.sleepingUntilMillis)

        val sleeping = SchedulerReducer.reduce(working, SchedulerIntent.SetSleepMode(5_000L))
        assertEquals(5_000L, sleeping.sleepingUntilMillis)

        val backToWork = SchedulerReducer.reduce(sleeping, SchedulerIntent.SetSleepMode(null))
        assertNull(backToWork.sleepingUntilMillis)
    }

    @Test
    fun setting_the_same_mode_is_a_no_op() {
        val sleeping = SchedulerState.empty().copy(sleepingUntilMillis = 5_000L)
        assertSame(sleeping, SchedulerReducer.reduce(sleeping, SchedulerIntent.SetSleepMode(5_000L)))
    }

    @Test
    fun is_sleeping_is_true_only_before_the_wake_instant() {
        val state = SchedulerState.empty().copy(sleepingUntilMillis = 10_000L)
        assertTrue(state.isSleeping(nowMillis = 9_999L))
        assertFalse(state.isSleeping(nowMillis = 10_000L)) // wake reached → working
        assertFalse(SchedulerState.empty().isSleeping(nowMillis = 0L)) // null = working
    }

    @Test
    fun mode_round_trips_through_the_codec() {
        val state = SchedulerState.empty().copy(sleepingUntilMillis = 1_800_000_000_000L)
        val decoded = SchedulerStateCodec.decode(SchedulerStateCodec.encode(state))
        assertNotNull(decoded)
        assertEquals(1_800_000_000_000L, decoded.sleepingUntilMillis)
    }

    /**
     * Persisted-DB compatibility (CLAUDE.md): a payload written before the Sleep/Work toggle existed (no
     * `sleepingUntilMillis` field) must still decode, defaulting to null (working).
     */
    @Test
    fun legacy_payload_without_the_field_decodes_as_working() {
        val legacy = """{"rootListId":"list/main","lists":[],"cells":[],"tasks":[]}"""
        val decoded = SchedulerStateCodec.decode(legacy)
        assertNotNull(decoded)
        assertNull(decoded.sleepingUntilMillis)
    }

    @Test
    fun next_wake_instant_is_the_first_wake_strictly_after_now() {
        // Wake at 07:30 (450 min). At 06:00 UTC on 1970-01-02 the next wake is that same day's 07:30; at 08:00
        // it is the following day's 07:30.
        val sleep = SleepSchedule(wakeMinutes = 450, goalWakeMinutes = 450, sleepDurationMinutes = 510)
        val tz = TimeZone.UTC
        val dayMillis = 24L * 60 * 60 * 1_000
        val wakeMillisOfDay = 450L * 60 * 1_000

        val at6am = dayMillis + 6 * 60 * 60 * 1_000 // 1970-01-02 06:00 UTC
        assertEquals(dayMillis + wakeMillisOfDay, SchedulerDomain.nextWakeInstantMillis(sleep, at6am, tz))

        val at8am = dayMillis + 8 * 60 * 60 * 1_000 // 1970-01-02 08:00 UTC (past today's wake)
        assertEquals(2 * dayMillis + wakeMillisOfDay, SchedulerDomain.nextWakeInstantMillis(sleep, at8am, tz))
    }
}
