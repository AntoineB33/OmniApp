package org.example.project

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.example.project.scheduler.domain.BreakMachine
import org.example.project.scheduler.domain.BreakMachine.Event
import org.example.project.scheduler.domain.DynamicPeriods.LABEL_20S
import org.example.project.scheduler.domain.DynamicPeriods.MODE_AT_SCREEN
import org.example.project.scheduler.domain.DynamicPeriods.MODE_AWAY
import org.example.project.scheduler.domain.DynamicPeriods.MODE_ON_BREAK
import org.example.project.scheduler.domain.SchedulerDomain

/**
 * `docs/scheduler_requirements.md` § *screen breaks*, **lived through at random**: the now line moved by random
 * amounts — seconds, minutes, hours — and its mode switched at random between the three, for days of timeline per
 * seed, with what the machine banks ([BreakMachine]) checked against the requirements' own sentences. [BreakMachineTest]
 * pins each rule on the case that shows it; this asks them of whatever sequence the seeds reach.
 *
 * Asked of every break the line has left behind (one the requirements' own exception removed — a mode-1 line inside
 * a pose — is not one):
 *  - *"A screen break period lasts as long as its name implies."*
 *  - *"Where the five rules above allow a continuous chain of breaks, then the interval of the whole chain only
 *    contains one screen break"*: no two breaks overlap.
 *  - *"After the end of a 20s break, no 20s break in the next 20 minutes."*
 *  - *"After a ≥ 5-minute of 'no screen', no 5min break in the next 1 hour"*, *"after a ≥ 15-minute … no 15min break
 *    in the next 2 hours"* — for the breaks themselves: after a 5min break, none for an hour; after a 15min, none
 *    for two. (Not asked: the same of the line's own stretches away from a screen, whose reading — "after" the
 *    stretch has ENDED — is the machine's, pinned case by case in [BreakMachineTest].)
 * And of the line at every step:
 *  - *"The current now line mode … can't go in mode 1 during a '20s screen break'"*;
 *  - *"The now line must be in mode 1 or 3 before entering the 20s break"*: none starts under a mode-2 line.
 *
 * No known "no screen" period is on this timeline: the rule that pulls a break to such a period's start is the one
 * the requirements let contradict the others (*"even if it contradicts with the five first screen break rules"*), so
 * it has its own cases in [BreakMachineTest] and none here.
 */
class BreakMachineFuzzTest {
    private val SEC = 1_000L
    private val MIN = 60_000L
    private val HOUR = 3_600_000L
    private val T0 = 1_000_000_000_000L

    private val specs = SchedulerDomain.dynamicPeriodSpecs(SchedulerDomain.DEFAULT_SCREEN_BREAKS)
    private fun spec(label: String) = specs.first { it.label == label }

    private data class Banked(val label: String, val start: Long, val end: Long)

    private fun live(seed: Int, steps: Int) {
        val random = Random(seed)
        var s = BreakMachine.initial(T0, specs)
        val events = ArrayList<Event>()
        var open: Banked? = null
        val done = ArrayList<Banked>()
        val log = ArrayList<String>()
        fun at(t: Long) = "+" + (t - T0) / 1000 + "s"
        fun told() = "seed $seed:\n  " + log.takeLast(40).joinToString("\n  ") + "\n  banked: " + done.takeLast(8).joinToString { "${it.label}[${at(it.start)},${at(it.end)}]" }
        repeat(steps) {
            val before = events.size
            if (random.nextInt(4) == 0) {
                val mode = listOf(MODE_AT_SCREEN, MODE_AWAY, MODE_ON_BREAK).random(random)
                log += "${at(s.atMillis)} mode ${s.baseMode} -> $mode"
                s = BreakMachine.switchMode(s, mode, emptyList(), specs, events)
            } else {
                val dt =
                    when (random.nextInt(5)) {
                        0 -> (1 + random.nextInt(30)) * SEC
                        1 -> (1 + random.nextInt(25)) * MIN
                        2 -> (1 + random.nextInt(5)) * HOUR
                        3 -> (1 + random.nextInt(600)) * SEC
                        else -> (1 + random.nextInt(90)) * MIN
                    }
                log += "${at(s.atMillis)} +${dt / 1000}s in mode ${s.baseMode}"
                s = BreakMachine.advance(s, s.atMillis + dt, emptyList(), specs, events)
            }
            for (event in events.subList(before, events.size)) {
                log += "    $event".replace(T0.toString().take(4), "…")
                when (event) {
                    is Event.Started -> {
                        assertEquals(null, open, "one break at a time\n${told()}")
                        if (event.label == LABEL_20S) {
                            assertTrue(s.baseMode != MODE_AWAY, "the line must be in mode 1 or 3 before entering the 20s break\n${told()}")
                        }
                        open = Banked(event.label, event.startMillis, event.endMillis)
                    }
                    is Event.Grew -> {
                        assertEquals(open?.start, event.startMillis, "the longest of the chain, brought to its start\n${told()}")
                        open = Banked(event.label, event.startMillis, event.endMillis)
                    }
                    is Event.Ended -> {
                        assertEquals(open, Banked(event.label, event.startMillis, event.endMillis), "the break that ends is the one that was in progress\n${told()}")
                        assertEquals(spec(event.label).durationMillis, event.endMillis - event.startMillis, "a break lasts as long as its name implies\n${told()}")
                        done += Banked(event.label, event.startMillis, event.endMillis)
                        open = null
                    }
                    is Event.Removed -> {
                        assertEquals(open?.start, event.startMillis, "what is removed is the break the line was in\n${told()}")
                        open = null
                    }
                    is Event.Owed -> Unit
                }
            }
            val active = s.active
            assertEquals(active?.let { Triple(it.label, it.startMillis, it.endMillis) }, open?.let { Triple(it.label, it.start, it.end) }, "the events say what the state holds\n${told()}")
            if (active != null && active.label == LABEL_20S) {
                assertTrue(BreakMachine.effectiveMode(s) != MODE_AT_SCREEN, "the line can't be in mode 1 during a 20s screen break\n${told()}")
            }
        }
        val sorted = done.sortedBy { it.start }
        for ((a, b) in sorted.zipWithNext()) {
            // Never one over another. (Two may TOUCH in one case, of no length in continuous time: the mode switched to
            // 1 at the very instant a pose ends, with a 20 s break owed since before it — found by seed 9. A second
            // later it is what the requirements say: the end of a pose bars no 20 s break.)
            assertTrue(b.start >= a.end, "a chain of breaks is one break: $a then $b\n${told()}")
        }
        for ((i, b) in sorted.withIndex()) {
            val mine = spec(b.label)
            for (a in sorted.subList(0, i)) {
                // Each break's own recurrence: after one of a label has ended, none of that label inside its bar. (A
                // pose is NOT asked here to keep away from the end of a longer one inside one stretch of "no screen":
                // the machine reads the requirements' "after a ≥ 5-minute of 'no screen'" as after the STRETCH, so a
                // 5min break falling due 45 minutes after a 15min one, the line still away, is taken there —
                // `BreakMachine`'s header, and seed 28.)
                val bars = a.label == b.label
                if (bars) assertTrue(b.start >= a.end + mine.cadenceMillis, "no ${b.label} break within its bar after $a: $b\n${told()}")
            }
        }
    }

    @Test
    fun the_breaks_the_line_leaves_behind_keep_the_rules_whatever_the_line_does() {
        for (seed in 1..300) live(seed, steps = 400)
    }
}
