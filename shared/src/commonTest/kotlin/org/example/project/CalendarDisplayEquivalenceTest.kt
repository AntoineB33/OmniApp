package org.example.project

import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.plus
import kotlinx.datetime.toLocalDateTime
import org.example.project.scheduler.model.TaskId
import org.example.project.scheduler.model.TaskTimeRange
import org.example.project.scheduler.persistence.ActiveSessionRecord
import org.example.project.ui.CalendarRecord
import org.example.project.ui.DeviceActivityIndex
import org.example.project.ui.DeviceActivitySegment
import org.example.project.ui.PanelSlice
import org.example.project.ui.PlacedRecord
import org.example.project.ui.deviceActivitySegments
import org.example.project.ui.overlapLayout
import org.example.project.ui.recordsByDay
import org.example.project.ui.recordsForDay
import org.example.project.ui.weightHandles
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * ADR 0009 display hot path: the calendar's per-frame derivations were rewritten for COST only — a
 * per-span index instead of a per-column scan, an incremental sweep instead of a per-slice rescan, a
 * session index instead of a per-record rebuild. Nothing about the output may move, so each new form is
 * pinned here against the previous definition (kept below as the oracle) over randomized inputs.
 */
class CalendarDisplayEquivalenceTest {

    private val tz = TimeZone.UTC

    // ---- the previous definitions, verbatim, as oracles -------------------------------------------

    private fun key(r: PlacedRecord): String =
        r.entryId
            ?: "auto/${if (r.scheduled) "s" else "r"}/${r.taskId?.value}/${r.fullStartMillis}/${r.fullEndMillis}"

    private fun eq(a: Float, b: Float) = kotlin.math.abs(a - b) < 1e-4f

    private fun oldOverlapLayout(blocks: List<PlacedRecord>): Map<String, List<PanelSlice>> {
        if (blocks.isEmpty()) return emptyMap()
        val boundaries = mutableSetOf<Float>()
        for (b in blocks) {
            boundaries.add(b.startHour)
            boundaries.add(b.endHour)
        }
        val bounds = boundaries.sorted()
        val raw = HashMap<String, MutableList<PanelSlice>>()
        for (i in 0 until bounds.size - 1) {
            val a = bounds[i]
            val b = bounds[i + 1]
            if (b <= a) continue
            val active = blocks
                .filter { it.startHour <= a && it.endHour >= b }
                .sortedWith(compareBy({ it.startHour }, { key(it) }))
            if (active.isEmpty()) continue
            val total = active.sumOf { it.layoutWeight }.let { if (it <= 0.0) active.size.toDouble() else it }
            var x = 0f
            for (block in active) {
                val w = (block.layoutWeight / total).toFloat()
                raw.getOrPut(key(block)) { mutableListOf() }
                    .add(PanelSlice(topHour = a, bottomHour = b, xFraction = x, widthFraction = w))
                x += w
            }
        }
        // The coalescing step is shared and unchanged; mirror it so the two sides are comparable.
        return raw.mapValues { (_, slices) ->
            val merged = mutableListOf<PanelSlice>()
            for (s in slices) {
                val last = merged.lastOrNull()
                if (last != null && eq(last.bottomHour, s.topHour) &&
                    eq(last.xFraction, s.xFraction) && eq(last.widthFraction, s.widthFraction)
                ) {
                    merged[merged.lastIndex] = last.copy(bottomHour = s.bottomHour)
                } else {
                    merged.add(s)
                }
            }
            merged
        }
    }

    /** The old [weightHandles], flattened to the tuple that fully describes each handle it produced. */
    private fun oldWeightHandles(blocks: List<PlacedRecord>): List<String> {
        if (blocks.size < 2) return emptyList()
        val boundaries = mutableSetOf<Float>()
        for (b in blocks) {
            boundaries.add(b.startHour)
            boundaries.add(b.endHour)
        }
        val bounds = boundaries.sorted()
        val out = mutableListOf<String>()
        for (i in 0 until bounds.size - 1) {
            val a = bounds[i]
            val b = bounds[i + 1]
            if (b <= a) continue
            val active = blocks
                .filter { it.startHour <= a && it.endHour >= b }
                .sortedWith(compareBy({ it.startHour }, { key(it) }))
            if (active.size < 2) continue
            val total = active.sumOf { it.layoutWeight }.let { if (it <= 0.0) active.size.toDouble() else it }
            var leftSum = 0.0
            for (j in active.indices) {
                val w = active[j].layoutWeight
                val right = active.getOrNull(j + 1)
                if (right != null && active[j].entryIds.isNotEmpty() && right.entryIds.isNotEmpty()) {
                    out.add(
                        "$a/$b/${((leftSum + w) / total).toFloat()}/${active[j].entryIds}/${right.entryIds}/" +
                            "$leftSum/$total/${w + right.layoutWeight}",
                    )
                }
                leftSum += w
            }
        }
        return out
    }

    private fun newWeightHandles(blocks: List<PlacedRecord>): List<String> =
        weightHandles(blocks).map {
            "${it.topHour}/${it.bottomHour}/${it.boundaryFraction}/${it.leftIds}/${it.rightIds}/" +
                "${it.leftSumWeight}/${it.totalWeight}/${it.pairWeight}"
        }

    // ---- generators -------------------------------------------------------------------------------

    /** Quarter-hour quantized so blocks genuinely SHARE boundaries — the case the sweep has to get right. */
    private fun randomBlocks(rnd: Random, n: Int): List<PlacedRecord> =
        (0 until n).map { i ->
            val start = rnd.nextInt(0, 96) / 4f
            val len = rnd.nextInt(0, 12) / 4f
            PlacedRecord(
                title = "b$i",
                startHour = start,
                endHour = (start + len).coerceAtMost(24f),
                scheduled = rnd.nextBoolean(),
                entryId = if (rnd.nextBoolean()) "e$i" else null,
                entryIds = if (rnd.nextBoolean()) listOf("e$i") else emptyList(),
                taskId = TaskId("t$i"),
                layoutWeight = if (rnd.nextBoolean()) 1.0 else rnd.nextInt(1, 5).toDouble(),
                fullStartMillis = i.toLong(),
                fullEndMillis = i.toLong() + 1,
            )
        }

    private fun randomRecords(rnd: Random, n: Int, baseMillis: Long): List<CalendarRecord> =
        (0 until n).map { i ->
            val start = baseMillis + rnd.nextLong(-5 * 86_400_000L, 5 * 86_400_000L)
            // A mix of zero-length markers, short blocks and multi-day bands.
            val len = when (rnd.nextInt(4)) {
                0 -> 0L
                1 -> rnd.nextLong(1_000L, 3_600_000L)
                2 -> rnd.nextLong(3_600_000L, 86_400_000L)
                else -> rnd.nextLong(86_400_000L, 4 * 86_400_000L)
            }
            CalendarRecord(
                title = "r$i",
                range = TaskTimeRange(start, start + len),
                scheduled = rnd.nextBoolean(),
                entryId = if (rnd.nextBoolean()) "e$i" else null,
                taskId = TaskId("t$i"),
                reminder = rnd.nextInt(8) == 0,
                screenBreak = rnd.nextInt(8) == 0,
                alarm = rnd.nextInt(12) == 0,
                screenBreakOpenFromMillis = if (rnd.nextInt(4) == 0) start + len / 2 else null,
                deviceSegments =
                    if (rnd.nextInt(3) == 0) {
                        listOf(DeviceActivitySegment(start, start + len / 2, listOf("Desktop")))
                    } else {
                        emptyList()
                    },
            )
        }

    // ---- the equivalences -------------------------------------------------------------------------

    @Test
    fun overlapLayoutSweepMatchesThePerSliceRescan() {
        val rnd = Random(20260821)
        repeat(400) { iter ->
            val blocks = randomBlocks(rnd, rnd.nextInt(0, 14))
            assertEquals(oldOverlapLayout(blocks), overlapLayout(blocks), "overlapLayout differs at iter $iter")
        }
    }

    @Test
    fun weightHandlesSweepMatchesThePerSliceRescan() {
        val rnd = Random(4242)
        repeat(400) { iter ->
            val blocks = randomBlocks(rnd, rnd.nextInt(0, 14))
            assertEquals(oldWeightHandles(blocks), newWeightHandles(blocks), "weightHandles differ at iter $iter")
        }
    }

    @Test
    fun recordsByDayMatchesRecordsForDayOnEveryVisibleDay() {
        val rnd = Random(99)
        val base = 1_700_000_000_000L
        val firstDay = Instant.fromEpochMilliseconds(base).toLocalDateTime(tz).date
        repeat(60) { iter ->
            val records = randomRecords(rnd, rnd.nextInt(0, 30), base)
            val dayCount = rnd.nextInt(1, 14)
            val index = recordsByDay(records, firstDay, dayCount, tz)
            var day: LocalDate = firstDay
            repeat(dayCount) {
                assertEquals(recordsForDay(records, day, tz), index[day].orEmpty(), "day $day at iter $iter")
                day = day.plus(1, DateTimeUnit.DAY)
            }
            // The index is bounded by the SCREEN: nothing outside the requested span is ever built.
            assertEquals(
                emptyList(),
                index.keys.filter { it < firstDay || it >= day },
                "out-of-span days at iter $iter",
            )
        }
    }

    @Test
    fun deviceActivityIndexMatchesThePerCallForm() {
        val rnd = Random(7)
        val base = 1_700_000_000_000L
        repeat(80) { iter ->
            val sessions = (0 until rnd.nextInt(0, 12)).map { _ ->
                val s = base + rnd.nextLong(-86_400_000L, 86_400_000L)
                ActiveSessionRecord(
                    deviceId = "d${rnd.nextInt(0, 4)}",
                    startMillis = s,
                    endMillis = s + rnd.nextLong(0L, 7_200_000L),
                    updatedAtMillis = s,
                    kind = listOf("", "desktop", "phone")[rnd.nextInt(3)],
                )
            }
            val index = DeviceActivityIndex(sessions)
            repeat(20) {
                val s = base + rnd.nextLong(-86_400_000L, 86_400_000L)
                val range = TaskTimeRange(s, s + rnd.nextLong(0L, 10_800_000L))
                val until = base + rnd.nextLong(0L, 86_400_000L)
                assertEquals(
                    deviceActivitySegments(range, sessions, until),
                    index.segmentsFor(range, until),
                    "segments differ at iter $iter",
                )
            }
        }
    }
}
