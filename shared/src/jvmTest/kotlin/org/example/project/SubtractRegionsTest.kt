package org.example.project

import kotlin.test.Test
import kotlin.test.assertEquals
import org.example.project.scheduler.domain.SchedulerDomain
import org.example.project.scheduler.model.TaskTimeRange

/**
 * PRD §15/§17: [SchedulerDomain.subtractRegions] carves the sleep windows out of the "Inactivity" bands so a
 * night renders as "Sleep", not "Inactivity". Pins the split / trim / drop / passthrough cases.
 */
class SubtractRegionsTest {
    private fun r(start: Long, end: Long) = TaskTimeRange(start, end)

    @Test
    fun a_region_in_the_middle_splits_the_range() {
        assertEquals(listOf(r(0, 30), r(50, 100)), SchedulerDomain.subtractRegions(listOf(r(0, 100)), listOf(r(30, 50))))
    }

    @Test
    fun a_region_covering_the_range_drops_it() {
        assertEquals(emptyList(), SchedulerDomain.subtractRegions(listOf(r(10, 90)), listOf(r(0, 100))))
    }

    @Test
    fun regions_trim_the_leading_and_trailing_edges() {
        assertEquals(listOf(r(30, 70)), SchedulerDomain.subtractRegions(listOf(r(0, 100)), listOf(r(0, 30), r(70, 100))))
    }

    @Test
    fun no_regions_passes_ranges_through() {
        assertEquals(listOf(r(0, 100)), SchedulerDomain.subtractRegions(listOf(r(0, 100)), emptyList()))
    }

    @Test
    fun overlapping_regions_are_merged_before_subtracting() {
        // Two overlapping sleep windows [20,40] and [30,60] carve one hole [20,60].
        assertEquals(listOf(r(0, 20), r(60, 100)), SchedulerDomain.subtractRegions(listOf(r(0, 100)), listOf(r(20, 40), r(30, 60))))
    }

    @Test
    fun carves_each_of_several_nightly_windows_out_of_one_long_pause() {
        // A single 3-"day" inactivity band with two nightly sleep windows → three daytime inactivity chunks.
        val pauses = SchedulerDomain.subtractRegions(listOf(r(0, 300)), listOf(r(100, 150), r(200, 250)))
        assertEquals(listOf(r(0, 100), r(150, 200), r(250, 300)), pauses)
    }
}
