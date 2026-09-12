package org.example.project

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.example.project.scheduler.domain.DynamicPeriods
import org.example.project.scheduler.domain.DynamicPeriods.MODE_AT_SCREEN
import org.example.project.scheduler.domain.DynamicPeriods.MODE_AWAY
import org.example.project.scheduler.domain.PeriodKinds
import org.example.project.scheduler.domain.PlanTask
import org.example.project.scheduler.domain.RestrictivePeriod
import org.example.project.scheduler.domain.SchedulerDomain
import org.example.project.scheduler.model.TaskId

/**
 * `docs/scheduler_requirements.md` § *3 Dynamic Restrictive Period*, last bullet — **a dynamic period is
 * pulled back onto the start of the PeriodKinds.NO_SCREEN chain that touches it**:
 *
 * > When a PeriodKinds.NO_SCREEN period touches the start of a dynamic restrictive period, and that this chain
 * > of PeriodKinds.NO_SCREEN periods ends somewhere in $[now line;+infinity)$, then the dynamic restrictive
 * > period now starts at the start of this chain. If it means starting in the past, this is the only
 * > exception to the **frozen past** rule.
 *
 * What it is FOR is the line still moving in an away mode. The user walks away; a break falls due while they
 * are gone; any emptiness absorbs a period, so the break is pushed to the end of the stretch — which, while
 * the pause is still running, IS the now-line, and goes on being the now-line for as long as the user stays
 * away. Read without this rule the break rides the line and never happens, which is what the calendar showed:
 * the period vanished from the past instead of staying in it. With it, the minutes already spent away COUNT
 * towards the break, the break is placed where they began, and the stretch from its end to the line is
 * covered by the ordinary PeriodKinds.NO_SCREEN cover — an Inactivity band, or Sleep inside a §17 window.
 *
 * Where the pull-back is REFUSED is mode 1's own rule and not an exception to this one; that pair is the third
 * test here. The modes themselves are [TpModeTest] and [DynamicPeriodsTest].
 */
class ScreenBreakChainPullBackTest {

    private val SEC = 1_000L
    private val MIN = 60_000L
    private val HOUR = 3_600_000L

    /** One on-screen task, so a PeriodKinds.NO_SCREEN period is a stretch nobody can run in. */
    private val onScreenOnly =
        listOf(PlanTask(TaskId("task/user/0"), 1.0, 0L, mapOf(PeriodKinds.NO_SCREEN to 0.0)))

    /** The ongoing pause as `SchedulerDomain.liveRestPeriod` hands it over: closed at the line. */
    private fun awayChain(startMillis: Long, endMillis: Long, closedEnd: Boolean) =
        DynamicPeriods.Base(
            listOf(RestrictivePeriod(startMillis, endMillis, PeriodKinds.NO_SCREEN, "no screen", closedEnd)),
            emptyList(),
            onScreenOnly,
        )

    @Test
    fun a_break_due_at_the_end_of_an_away_chain_starts_where_the_chain_did() {
        val chainStart = 50 * MIN
        val tp = 52 * MIN
        val base = awayChain(chainStart, tp, closedEnd = true)
        val spec = DynamicPeriods.Spec(DynamicPeriods.LABEL_20S, 20 * SEC, 25 * MIN)
        val placed =
            DynamicPeriods.instances(
                base, listOf(spec), 0L, 2 * HOUR, tpMillis = tp,
                mode = DynamicPeriods.MODE_AWAY, sweepFromMillis = 0L,
            )
        val crossed = placed.single { it.startMillis in chainStart..tp }
        assertEquals(chainStart, crossed.startMillis, "the break starts where the away chain did: $placed")
        assertEquals(20 * SEC, crossed.durationMillis, "and is NOT stretched to reach the line")
        assertTrue(crossed.endMillis < tp, "so it is over, and frozen in the past behind the line")

        // The rule is stated of the PERIODS, not of the line's mode, so it is not a mode rule. The 20 s
        // look-away is never dragged in any mode either, so mode 1 answers this one identically.
        assertEquals(
            placed,
            DynamicPeriods.instances(
                base, listOf(spec), 0L, 2 * HOUR, tpMillis = tp,
                mode = DynamicPeriods.MODE_AT_SCREEN, sweepFromMillis = 0L,
            ),
        )
    }

    @Test
    fun a_chain_the_line_has_left_still_holds_the_break_it_took() {
        // The bullet's own clause — the chain has to END somewhere in [now line, +infinity) — is the PRESENT
        // TENSE of "the chain took this break": a chain reaching the line is one the user is still inside, so
        // the break is still being taken. Once the line is past it the question is no longer about the line at
        // all, and answering it with the line's CURRENT position is what broke the frozen past: the break was
        // placed at the chain's start for the whole time the line was inside the chain, and the instant the
        // user came back it moved to the chain's end and was then dragged onto the line by mode 1. A chain
        // that outlasted the break took it, and that answer never changes as the line advances.
        val chainStart = 50 * MIN
        val chainEnd = 52 * MIN
        val base = awayChain(chainStart, chainEnd, closedEnd = false)
        val spec = DynamicPeriods.Spec(DynamicPeriods.LABEL_20S, 20 * SEC, 25 * MIN)
        fun placedAt(tp: Long) =
            DynamicPeriods.instances(
                base, listOf(spec), 0L, 2 * HOUR, tpMillis = tp,
                mode = DynamicPeriods.MODE_AWAY, sweepFromMillis = 0L,
            ).map { it.startMillis }.filter { it in chainStart..chainEnd }

        assertEquals(listOf(chainStart), placedAt(51 * MIN), "while the line is inside the chain")
        assertEquals(listOf(chainStart), placedAt(90 * MIN), "and still, once the line has left it")
        assertEquals(chainStart, DynamicPeriods.chainTaking(base, spec, chainEnd, 90 * MIN, MODE_AWAY)?.startMillis)
    }

    @Test
    fun a_chain_shorter_than_the_break_took_nothing() {
        // The other half of the same sentence, and what keeps the exception to the frozen past confined to the
        // case it is written for: the user came back too soon, so the break was never completed. It is owed
        // again — mode 1 goes back to dragging it — and nothing is written into a past it did not happen in.
        val chainStart = 50 * MIN
        val chainEnd = chainStart + 2 * MIN
        val base = awayChain(chainStart, chainEnd, closedEnd = false)
        val specs =
            listOf(
                DynamicPeriods.Spec(DynamicPeriods.LABEL_20S, 20 * SEC, 10 * HOUR),
                DynamicPeriods.Spec(DynamicPeriods.LABEL_5MIN, 5 * MIN, 50 * MIN),
            )
        val pose = specs.last()
        assertEquals(null, DynamicPeriods.chainTaking(base, pose, chainEnd, 90 * MIN, MODE_AT_SCREEN))
        val placed =
            DynamicPeriods.instances(
                base, specs, 0L, 2 * HOUR, tpMillis = 90 * MIN,
                mode = DynamicPeriods.MODE_AT_SCREEN, sweepFromMillis = 0L,
            )
        assertTrue(
            placed.none { it.startMillis in chainStart until chainEnd },
            "a two-minute pause is no five-minute pose: $placed",
        )
    }

    @Test
    fun mode_one_refuses_a_pull_back_that_would_cover_the_line_with_a_pose() {
        // Mode 1's rule: the now-line must NOT be covered by the period PeriodKinds.NO_SCREEN. A pose pulled
        // back far enough to reach the line would cover it, so mode 1 keeps the drag — the half-open
        // (t_p, t_p + d] — while the away modes, where the line is covered by definition, take the pull-back.
        val chainStart = 53 * MIN
        val tp = 55 * MIN
        val base = awayChain(chainStart, tp, closedEnd = true)
        // Two specs, because the labels are POSITIONAL: the shortest of them is the look-away, which is never
        // dragged, so a pose needs something shorter beside it to BE the pose. The look-away's cadence is put
        // past the horizon so only the pose is placed.
        val specs =
            listOf(
                DynamicPeriods.Spec(DynamicPeriods.LABEL_20S, 20 * SEC, 10 * HOUR),
                DynamicPeriods.Spec(DynamicPeriods.LABEL_5MIN, 4 * MIN, 25 * MIN),
            )
        fun placeAt(mode: Int) =
            DynamicPeriods.instances(base, specs, 0L, 2 * HOUR, tpMillis = tp, mode = mode, sweepFromMillis = 0L)

        val atScreen = placeAt(DynamicPeriods.MODE_AT_SCREEN).first { it.startMillis >= chainStart }
        assertEquals(tp, atScreen.startMillis, "mode 1: the pose is still owed AT the line")
        assertTrue(atScreen.openStart, "as the half-open (t_p, t_p + d], so t_p itself stays uncovered")

        for (mode in listOf(DynamicPeriods.MODE_AWAY, DynamicPeriods.MODE_ON_BREAK)) {
            val away = placeAt(mode).first { it.startMillis >= chainStart }
            assertEquals(chainStart, away.startMillis, "mode $mode: the minutes already spent away count")
            assertTrue(!away.openStart, "and nothing is being dragged, so it is an ordinary closed period")
            assertTrue(
                away.coveredFromMillis <= tp && tp < away.coveredUntilMillis,
                "which is what covers the line: $away",
            )
        }
    }

    @Test
    fun the_break_is_never_stretched_the_gap_behind_the_line_is_covered_instead() {
        // The user's rule for a line still moving in mode 2 or 3: the 20 s / 5 min / 15 min period is NOT
        // stretched to keep covering the line. It stays the length it is and is frozen where it happened, and
        // what reaches from its end to the line is the ordinary PeriodKinds.NO_SCREEN cover.
        val chainStart = 50 * MIN
        val tp = 52 * MIN
        val base = awayChain(chainStart, tp, closedEnd = true)
        val spec = DynamicPeriods.Spec(DynamicPeriods.LABEL_20S, 20 * SEC, 25 * MIN)
        val out =
            DynamicPeriods.periods(
                base, listOf(spec), 0L, 2 * HOUR, tpMillis = tp,
                mode = DynamicPeriods.MODE_AWAY, sweepFromMillis = 0L,
            )
        val crossed = out.single { it.kind == PeriodKinds.INACTIVITY && it.startMillis in chainStart..tp }
        assertEquals(20 * SEC, crossed.durationMillis, "the break keeps its own length")
        assertTrue(!crossed.covers(tp), "so it is not what covers the line")
        // Here the live pause itself covers the line, which is why `awayCover` finds nothing left to do.
        assertTrue(
            (out + base.periods).any { it.covers(tp) && PeriodKinds.coversNoScreen(it.kind) },
            "modes 2 and 3: t_p is covered by 'no on-screen task', by the stretch and not by the break: $out",
        )
    }

    @Test
    fun a_break_the_line_crossed_while_away_is_still_drawn_in_the_past() {
        // The report, at the level it was seen: *"when the now line reaches the end of a 20s/5min/15min screen
        // break in mode 2 or 3, then it stays in the past (it currently disappears)"*. The calendar's past-side
        // markers are the same placement asked about a window that has gone by
        // (`SchedulerDomain.takenScreenBreakPanels`), so a POSE shows there exactly when it really happened —
        // never in mode 1, where the line pushed it ahead of itself and it never did.
        val now = 1_700_000_000_000L
        val breaks = SchedulerDomain.DEFAULT_SCREEN_BREAKS
        val poseTitles = breaks.filter { it.restBreak }.map { it.title }.toSet()
        fun past(mode: Int) =
            SchedulerDomain.takenScreenBreakPanels(
                breaks, now - 6 * HOUR, now - 1, tpMillis = now, mode = mode,
            )
        assertTrue(
            past(DynamicPeriods.MODE_AT_SCREEN).none { it.title in poseTitles },
            "mode 1: a pose the line reached was dragged and never happened, so the past holds none",
        )
        for (mode in listOf(DynamicPeriods.MODE_AWAY, DynamicPeriods.MODE_ON_BREAK)) {
            val elapsed = past(mode).filter { it.title in poseTitles }
            assertTrue(elapsed.isNotEmpty(), "mode $mode: a pose the line crossed stays drawn where it happened")
            assertTrue(
                elapsed.all { it.endEpochMillis <= now },
                "and it is frozen there, not stretched forward to the line: $elapsed",
            )
        }
        assertEquals(
            past(DynamicPeriods.MODE_ON_BREAK),
            past(DynamicPeriods.MODE_AWAY),
            "the two away modes draw one past",
        )
    }
}
