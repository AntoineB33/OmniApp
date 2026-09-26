package org.example.project

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.example.project.scheduler.domain.ChronoDomain
import org.example.project.scheduler.domain.SearchDomain
import org.example.project.scheduler.model.ChronoEntry
import org.example.project.scheduler.persistence.SchedulerStateCodec
import org.example.project.scheduler.state.HistoryCategory
import org.example.project.scheduler.state.SchedulerIntent
import org.example.project.scheduler.state.SchedulerReducer
import org.example.project.scheduler.state.SchedulerState

/** PRD §18 Chronos: a chronometer counts up from its start instant, pauses, resumes, resets — and syncs its run. */
class ChronoTest {
    private val second = 1_000L
    private val now = 1_800_000_000_000L

    @Test
    fun a_chrono_counts_up_pauses_resumes_and_resets() {
        val idle = ChronoEntry(id = "chrono-0")
        assertTrue(idle.idle)
        val running = ChronoDomain.started(idle, now)
        assertEquals(75 * second, running.elapsedAtMillis(now + 75 * second))
        assertEquals("1:15", ChronoDomain.format(running.elapsedAtMillis(now + 75_900L)))
        assertEquals(running, ChronoDomain.started(running, now + second), "pressing start twice moves nothing")
        val paused = ChronoDomain.paused(running, now + 75 * second)
        assertTrue(paused.paused)
        assertEquals(75 * second, paused.elapsedAtMillis(now + 10_000 * second), "a paused chrono stands still")
        val resumed = ChronoDomain.started(paused, now + 100 * second)
        assertEquals(85 * second, resumed.elapsedAtMillis(now + 110 * second))
        assertTrue(ChronoDomain.reset(resumed).idle)
    }

    @Test
    fun the_list_is_undoable_and_the_run_is_not() {
        var s = SchedulerReducer.reduce(SchedulerState.empty(), SchedulerIntent.SetChronos(listOf(ChronoEntry(id = ""))))
        val id = s.chronos.single().id
        assertEquals("chrono-0", id)
        assertEquals(1, s.histories.forCategory(HistoryCategory.Main).units.size)
        s = SchedulerReducer.reduce(s, SchedulerIntent.StartChrono(id, now))
        s = SchedulerReducer.reduce(s, SchedulerIntent.PauseChrono(id, now + 5 * second))
        assertEquals(1, s.histories.forCategory(HistoryCategory.Main).units.size, "start and pause are not units")
        assertEquals(5 * second, s.chronos.single().bankedMillis)
        s = SchedulerReducer.reduce(s, SchedulerIntent.SetChronos(listOf(s.chronos.single().copy(label = "Run"))))
        assertEquals("Run", s.chronos.single().label)
        assertEquals(5 * second, s.chronos.single().bankedMillis, "a label edit leaves the run alone")
        val undone = SchedulerReducer.reduce(s, SchedulerIntent.Undo)
        assertEquals("", undone.chronos.single().label)
        s = SchedulerReducer.reduce(s, SchedulerIntent.ResetChrono(id))
        assertTrue(s.chronos.single().idle)
    }

    @Test
    fun chronos_round_trip_the_codec_and_an_older_payload_has_none() {
        val json =
            """
            {"rootListId":"L","lists":[{"id":"L","parentCellId":null,"cellIds":["c0"]}],
             "cells":[{"id":"c0","parentListId":"L","taskId":null}],
             "tasks":[{"id":"t0","title":"X"}]}
            """.trimIndent()
        val decoded = SchedulerStateCodec.decode(json)
        assertNotNull(decoded)
        assertTrue(decoded.chronos.isEmpty())
        val added = SchedulerReducer.reduce(decoded, SchedulerIntent.SetChronos(listOf(ChronoEntry(id = "chrono-0", label = "Run"))))
        // The unit that added it reloads too, and still undoes.
        val reloaded = SchedulerStateCodec.decode(SchedulerStateCodec.encode(added))!!
        assertEquals(added.chronos, reloaded.chronos)
        assertTrue(SchedulerReducer.reduce(reloaded, SchedulerIntent.Undo).chronos.isEmpty())
        // The run state round-trips.
        val s = SchedulerReducer.reduce(added, SchedulerIntent.StartChrono("chrono-0", now))
        assertEquals(s.chronos, SchedulerStateCodec.decode(SchedulerStateCodec.encode(s))!!.chronos)
        // A running chrono is on the wire: it reads the same on every device.
        val stopped = SchedulerReducer.reduce(s, SchedulerIntent.PauseChrono("chrono-0", now + second))
        assertTrue(SchedulerStateCodec.syncFingerprint(s) != SchedulerStateCodec.syncFingerprint(stopped))
    }

    @Test
    fun the_search_finds_chronos_and_filters_them_by_state() {
        val s = SchedulerState.empty().copy(
            chronos = listOf(
                ChronoEntry(id = "chrono-0", label = "Run", startedAtMillis = now),
                ChronoEntry(id = "chrono-1", label = "Rest", bankedMillis = 5 * second),
                ChronoEntry(id = "chrono-2"),
            ),
        )
        val kinds = setOf(SearchDomain.Kind.Chrono)
        val rows = SearchDomain.results(s, kinds, "").map { it as SearchDomain.ItemResult }
        assertEquals(setOf("Run" to "running", "Rest" to "paused", "Chrono" to "idle"), rows.map { it.name to it.detail }.toSet())
        val running = SearchDomain.results(s, kinds, "", filters = SearchDomain.Filters(chronoState = SearchDomain.TimerState.Running))
        assertEquals(listOf("Run"), running.map { it.name })
        val config = SearchDomain.Config(kinds = kinds, filters = SearchDomain.Filters(chronoState = SearchDomain.TimerState.Paused))
        assertEquals(config, SearchDomain.Config.decode(config.encode()))
    }
}
