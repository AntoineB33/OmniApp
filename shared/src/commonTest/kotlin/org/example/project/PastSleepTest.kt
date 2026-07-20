package org.example.project

import kotlinx.datetime.TimeZone
import org.example.project.scheduler.domain.SchedulerDomain
import org.example.project.scheduler.model.SleepSchedule
import org.example.project.scheduler.model.TaskPanel
import org.example.project.scheduler.model.TaskTimeRange
import org.example.project.scheduler.state.HistoryCategory
import org.example.project.scheduler.state.SchedulerIntent
import org.example.project.scheduler.state.SchedulerReducer
import org.example.project.scheduler.state.SchedulerState
import org.example.project.scheduler.persistence.SchedulerStateCodec
import org.example.project.time.AppClock
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * PRD §9/§12/§17 (1.6.0 revision): the past is Inactivity + No-screen unless a sleep is a **recorded fact** —
 * a scheduled sleep window the running app found unattended, or a completed Sleep-toggle session — persisted
 * as a "Sleep" panel. Covers the domain helpers ([SchedulerDomain.intersectRegions],
 * [SchedulerDomain.derivedBandsOpenStart]), the [SchedulerIntent.MaterializePastSleep] reducer, the Sleep
 * toggle start/finalize, that a materialized past-sleep panel survives a reschedule, and the codec field.
 */
class PastSleepTest {
    private val minute = 60_000L

    // ---- domain helpers ----------------------------------------------------------------------------------

    @Test
    fun intersect_regions_keeps_only_the_overlap() {
        assertEquals(
            listOf(TaskTimeRange(5, 10)),
            SchedulerDomain.intersectRegions(listOf(TaskTimeRange(0, 10)), listOf(TaskTimeRange(5, 20))),
        )
        assertEquals(
            emptyList(),
            SchedulerDomain.intersectRegions(listOf(TaskTimeRange(0, 5)), listOf(TaskTimeRange(10, 20))),
        )
    }

    @Test
    fun open_start_is_the_earliest_gap_when_nothing_precedes_it() {
        val gaps = listOf(TaskTimeRange(100, 200), TaskTimeRange(300, 400))
        // Empty DB (no evidence) → the earliest band is open-ended into the past.
        assertEquals(100L, SchedulerDomain.derivedBandsOpenStart(gaps, earliestEvidenceMillis = null))
        // Evidence begins no earlier than the gap start → still open (nothing strictly before it).
        assertEquals(100L, SchedulerDomain.derivedBandsOpenStart(gaps, earliestEvidenceMillis = 150L))
        // Evidence precedes the earliest gap → it is bounded, not open.
        assertNull(SchedulerDomain.derivedBandsOpenStart(gaps, earliestEvidenceMillis = 50L))
        // No gaps → nothing to open.
        assertNull(SchedulerDomain.derivedBandsOpenStart(emptyList(), earliestEvidenceMillis = null))
    }

    // ---- MaterializePastSleep reducer --------------------------------------------------------------------

    @Test
    fun materialize_past_sleep_adds_a_persisted_sleep_panel_and_dedups() {
        val state = SchedulerState.empty()
        val span = TaskTimeRange(1_000_000L, 1_000_000L + 2 * minute)
        val out = SchedulerReducer.reduce(state, SchedulerIntent.MaterializePastSleep(listOf(span)))
        val sleep = out.panels.filter { it.sleep }
        assertEquals(1, sleep.size)
        assertEquals("Sleep", sleep[0].title)
        assertEquals(span.startEpochMillis, sleep[0].startEpochMillis)
        assertEquals(span.endEpochMillis, sleep[0].endEpochMillis)
        // A materialized panel gets an allocated id, NOT the derived `sleep/{day}` prefix.
        assertFalse(sleep[0].id.startsWith("sleep/"))
        // Not undoable: no history unit was recorded.
        assertTrue(out.histories.forCategory(HistoryCategory.Calendar).units.isEmpty())
        assertTrue(out.histories.forCategory(HistoryCategory.Main).units.isEmpty())
        // Re-materializing the same span adds nothing (deduped against the existing materialized panel).
        val again = SchedulerReducer.reduce(out, SchedulerIntent.MaterializePastSleep(listOf(span)))
        assertEquals(1, again.panels.count { it.sleep })
    }

    @Test
    fun materialize_past_sleep_drops_sub_minute_slivers() {
        val state = SchedulerState.empty()
        val out = SchedulerReducer.reduce(state, SchedulerIntent.MaterializePastSleep(listOf(TaskTimeRange(0, 30_000L))))
        assertTrue(out.panels.none { it.sleep })
    }

    // ---- Sleep toggle start/finalize ---------------------------------------------------------------------

    @Test
    fun sleep_toggle_stamps_the_start_then_finalizes_a_past_sleep_panel() {
        val clock = object : AppClock {
            var now = 100_000_000L
            override fun nowMillis(): Long = now
        }
        val previous = SchedulerReducer.clock
        SchedulerReducer.clock = clock
        try {
            var s = SchedulerState.empty()
            // Press Sleep → stamps the session start = now.
            s = SchedulerReducer.reduce(s, SchedulerIntent.SetSleepMode(clock.now + 8 * 60 * minute))
            assertEquals(100_000_000L, s.sleepingSinceMillis)
            // An hour later, press Work → finalize [since, now] as a persisted Sleep panel and clear.
            clock.now += 60 * minute
            s = SchedulerReducer.reduce(s, SchedulerIntent.SetSleepMode(null))
            assertNull(s.sleepingUntilMillis)
            assertNull(s.sleepingSinceMillis)
            val sleep = s.panels.filter { it.sleep }
            assertEquals(1, sleep.size)
            assertEquals(100_000_000L, sleep[0].startEpochMillis)
            assertEquals(100_000_000L + 60 * minute, sleep[0].endEpochMillis)

            // The materialized past-sleep panel survives a reschedule (fillSchedule keeps it, unlike the
            // regenerated `sleep/{day}` windows).
            val filled = SchedulerDomain.fillSchedule(s, clock.now, TimeZone.UTC)
            assertTrue(
                filled.any { it.sleep && !it.id.startsWith("sleep/") && it.startEpochMillis == 100_000_000L },
            )
        } finally {
            SchedulerReducer.clock = previous
        }
    }

    @Test
    fun the_schedule_never_projects_sleep_into_the_past() {
        // PRD §17: a scheduled sleep window is only ever drawn from `now` forward — the past is not assumed
        // slept (an emptied DB shows Inactivity + No-screen there). fillSchedule regenerates sleep only
        // over [now, horizon].
        val schedule = SleepSchedule(wakeMinutes = 450, goalWakeMinutes = 450, sleepDurationMinutes = 510)
        val now = 100L * 24 * 60 * minute // day 100, midnight UTC
        val filled = SchedulerDomain.fillSchedule(SchedulerState.empty().copy(sleep = schedule), now, TimeZone.UTC)
        assertTrue(filled.filter { it.sleep }.all { it.endEpochMillis > now })
    }

    // ---- codec compatibility ---------------------------------------------------------------------------

    @Test
    fun sleeping_since_round_trips_and_legacy_payload_decodes_null() {
        val state = SchedulerState.empty().copy(sleepingSinceMillis = 1_700_000_000_000L)
        val decoded = SchedulerStateCodec.decode(SchedulerStateCodec.encode(state))
        assertNotNull(decoded)
        assertEquals(1_700_000_000_000L, decoded.sleepingSinceMillis)

        // A payload written before the field existed decodes to null (no live band).
        val legacy = """{"rootListId":"list/main","lists":[],"cells":[],"tasks":[]}"""
        val legacyDecoded = SchedulerStateCodec.decode(legacy)
        assertNotNull(legacyDecoded)
        assertNull(legacyDecoded.sleepingSinceMillis)
    }

    @Test
    fun a_materialized_sleep_panel_round_trips_through_the_codec() {
        val panel = TaskPanel(
            id = "panel/7",
            taskId = null,
            title = "Sleep",
            startEpochMillis = 1_000_000L,
            endEpochMillis = 1_000_000L + 3 * minute,
            sleep = true,
        )
        val state = SchedulerState.empty().let { it.copy(panels = it.panels + panel) }
        val decoded = SchedulerStateCodec.decode(SchedulerStateCodec.encode(state))
        assertNotNull(decoded)
        val restored = decoded.panels.filter { it.sleep }
        assertEquals(1, restored.size)
        assertEquals("panel/7", restored[0].id)
        assertEquals(1_000_000L, restored[0].startEpochMillis)
    }
}
