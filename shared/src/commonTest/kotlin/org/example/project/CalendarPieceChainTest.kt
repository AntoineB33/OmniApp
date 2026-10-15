package org.example.project

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import org.example.project.scheduler.model.TaskTimeRange
import org.example.project.ui.CalendarPiece
import org.example.project.ui.CalendarRecord
import org.example.project.ui.calendarChainWantsPiece
import org.example.project.ui.extendCalendarChain
import org.example.project.ui.pieceIndexAt

/**
 * User rule 2026-10-15: *"the dynamic change of the calendar over time must be as performant as the graph by the
 * Python program, with the scheduler engine returning a set of rules that covers all the situations over a bigger
 * time interval"*. The calendar is handed a CHAIN of pieces read ahead of the line ([extendCalendarChain]) and
 * switches between them itself; nothing is derived as time passes.
 *
 * The contract: **the chain, evaluated at any instant it covers, is exactly the rules read at that instant** — across
 * every boundary, including the one the user saw lag: a 15-min break the line in mode 1 reaches, which is drawn where
 * it falls until then and dragged `]line; line + 15 min]` from that instant on.
 */
class CalendarPieceChainTest {
    private val tz = TimeZone.UTC
    private val base = LocalDateTime(2026, 10, 15, 10, 0).toInstant(tz).toEpochMilliseconds()
    private val minute = 60_000L
    private val due = base + 30 * minute
    private val pose = 15 * minute
    private val end = base + 90 * minute

    /**
     * The rules of a mode-1 line, as `test.py`'s branches: task A done behind the line and planned ahead of it up
     * to the break; the break where it falls until the line reaches it, then dragged; task B after the break.
     */
    private fun rules(line: Long): List<CalendarRecord> {
        val out = ArrayList<CalendarRecord>()
        if (line > base) out += CalendarRecord("A done", TaskTimeRange(base, minOf(line, due).coerceAtLeast(base)))
        if (line < due) {
            out += CalendarRecord("A", TaskTimeRange(maxOf(line, base), due), scheduled = true)
            out += CalendarRecord("15 min", TaskTimeRange(due, due + pose), screenBreak = true)
            out += CalendarRecord("B", TaskTimeRange(due + pose, end), scheduled = true)
        } else {
            out += CalendarRecord("A done", TaskTimeRange(due, line))
            out += CalendarRecord("15 min", TaskTimeRange(line + 1, line + 1 + pose), screenBreak = true)
            out += CalendarRecord("B", TaskTimeRange(minOf(line + 1 + pose, end), end), scheduled = true)
        }
        return out
    }

    private fun chainOver(from: Long, until: Long): List<CalendarPiece> {
        var chain = emptyList<CalendarPiece>()
        while (chain.isEmpty() || chain.last().endMillis <= until) {
            // The line held at [from], so nothing is dropped: the whole interval stays inspectable.
            chain = extendCalendarChain(chain, from, tz, ::rules)
        }
        return chain
    }

    private fun stripped(records: List<CalendarRecord>) =
        records.map { it.copy(startFollowsLine = false, endFollowsLine = false) }

    @Test
    fun the_chain_is_the_rules_at_every_instant_it_covers() {
        val from = base - 10 * minute
        val until = end + 10 * minute
        val chain = chainOver(from, until)
        val instants = buildList {
            var t = from
            while (t < until) {
                add(t)
                t += 7_013L
            }
            for (b in listOf(base, due, end - pose - 1, end)) for (d in -3L..3L) add(b + d)
        }
        for (t in instants) {
            val piece = chain[pieceIndexAt(chain, t)]
            assertTrue(t >= piece.startMillis || piece === chain.first(), "instant $t before the chain")
            assertEquals(stripped(rules(t)), stripped(piece.recordsAt(t)), "at ${t - base} ms from the base")
        }
    }

    @Test
    fun the_break_is_dragged_from_the_very_instant_the_line_reaches_it() {
        val chain = chainOver(base, end)
        // A piece starts AT the break's due — not a resample period later.
        val atDue = chain.first { it.startMillis >= due }
        assertEquals(due, atDue.startMillis)
        val dragged = atDue.records.single { it.title == "15 min" }
        assertTrue(dragged.startFollowsLine && dragged.endFollowsLine)
    }

    @Test
    fun a_line_past_the_end_of_the_chain_starts_it_again_where_it_is() {
        val chain = chainOver(base, base + minute)
        val line = chain.last().endMillis + 5 * minute
        val again = extendCalendarChain(chain, line, tz, ::rules)
        assertEquals(1, again.size)
        assertEquals(line, again.single().startMillis)
    }

    @Test
    fun the_chain_is_extended_only_while_it_ends_within_the_lookahead() {
        val chain = chainOver(base, base)
        assertTrue(calendarChainWantsPiece(emptyList(), base))
        assertTrue(calendarChainWantsPiece(chain, chain.last().endMillis - 1, lookaheadMillis = 1))
        assertEquals(false, calendarChainWantsPiece(chain, chain.last().endMillis - 2, lookaheadMillis = 0L))
    }
}
