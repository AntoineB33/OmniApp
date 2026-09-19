package org.example.project.scheduler.domain

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.ln
import org.example.project.scheduler.model.TaskId

/**
 * `docs/scheduler_score.md` — **the score, as code.**
 *
 * The requirements ask for "the best possible score for the two optimization criteria"; that section defines the
 * score, and this class is its one evaluator. The optimizer ([ScheduleOptimizer]) never scores a continuation any
 * other way, so what it minimizes and what the definition says cannot drift apart.
 *
 * ### What it is built from
 * - [tasks]: the rule state `R(x)` applied at the now-line, in the tie-break order (higher priority, then title).
 * - [blocks]: the pre-placed tasks (a `null` task is a block owned by nobody).
 * - [windows]: the restrictive periods, each carrying every task's multiplier. They may overlap: overlapping
 *   periods multiply.
 * - `[fromMillis, toMillis)`: the stretch of wall time the model describes. The environment of the last stretch
 *   continues forever past [toMillis] as far as the compensation is concerned.
 *
 * ### The schedulable clock
 * Every duration in the score is measured on the schedulable clock `u` — time at which at least one task may run.
 * The wall timeline is cut at every edge into segments; the schedulable ones are merged, across the stretches
 * nobody may run in, into **pieces** of constant environment on the `u` axis. A 20-second period between two
 * identical stretches is therefore invisible to the score, which is the definition's own reading of "a stretch
 * where no task may run does not interrupt a panel".
 *
 * ### Units
 * Millis throughout. The lag is a duration (millis), criterion 1 integrates its square over `u` (millis³), and a
 * shortfall of `s` on task `i` costs `τ_i·s·(2M_i + s)` (millis³) — the common unit the definition states.
 */
class ScoreModel(
    val tasks: List<PlanTask>,
    blocks: List<PlanBlock>,
    windows: List<PlanWindow>,
    val fromMillis: Long,
    val toMillis: Long,
) {
    val n: Int = tasks.size
    val indexOf: Map<TaskId, Int> = tasks.withIndex().associate { (i, t) -> t.id to i }

    /** `P_i`. */
    val priority: DoubleArray = DoubleArray(n) { tasks[it].priority.coerceAtLeast(0.0) }

    /** `π_i = P_i / Σ P_j`, uniform when every priority is 0. */
    val share: DoubleArray

    /** `M_i`, millis. */
    val minimum: DoubleArray = DoubleArray(n) { tasks[it].minimumMillis.coerceAtLeast(0L).toDouble() }

    /** `τ_i = max(M_i, 1 min) / π_i` — task `i`'s smallest window; `Θ` for a task with no share. */
    val tau: DoubleArray

    /** `Θ = max τ_i` over the tasks with a share — the discount horizon. */
    val theta: Double

    // --- wall segments -------------------------------------------------------------------------------
    private val wallStart: LongArray
    private val wallEnd: LongArray
    private val wallU0: DoubleArray
    private val wallSchedulable: BooleanArray
    private val wallFixed: IntArray

    // --- pieces on the schedulable clock ------------------------------------------------------------
    /** Number of pieces. */
    val pieceCount: Int
    val pieceUStart: DoubleArray
    val pieceULen: DoubleArray
    /** `μ_i` per piece. */
    val pieceMult: Array<DoubleArray>
    /** The pre-placed task a piece belongs to, or -1 when the scheduler decides it. */
    val pieceFixed: IntArray
    /** `q_i` per piece. */
    private val pieceShare: Array<DoubleArray>
    /** Compensation coefficients: `c_i(v) = A·e^(−v/λ) + B·e^(−(len−v)/λ)` inside the piece. */
    private val compA: Array<DoubleArray>
    private val compB: Array<DoubleArray>
    private val pieceHasComp: BooleanArray

    /** Total schedulable time the model describes. */
    val uEnd: Double

    init {
        val total = priority.sum()
        share = if (n == 0) DoubleArray(0)
        else if (total > 0.0) DoubleArray(n) { priority[it] / total }
        else DoubleArray(n) { 1.0 / n }
        val windowOf = DoubleArray(n) { i ->
            if (share[i] > 0.0) maxOf(minimum[i], MIN_WINDOW_MILLIS) / share[i] else Double.NaN
        }
        theta = windowOf.filter { !it.isNaN() }.maxOrNull() ?: MIN_WINDOW_MILLIS
        tau = DoubleArray(n) { i -> if (windowOf[i].isNaN()) theta else windowOf[i] }

        // --- cut the wall timeline at every edge
        val edgeSet = HashSet<Long>()
        edgeSet += fromMillis
        edgeSet += toMillis
        for (b in blocks) {
            if (b.startMillis in fromMillis..toMillis) edgeSet += b.startMillis
            if (b.endMillis in fromMillis..toMillis) edgeSet += b.endMillis
        }
        for (w in windows) {
            if (w.startMillis in fromMillis..toMillis) edgeSet += w.startMillis
            w.endMillis?.let { if (it in fromMillis..toMillis) edgeSet += it }
        }
        val edges = edgeSet.sorted()
        val segCount = (edges.size - 1).coerceAtLeast(0)
        val sortedWindows = windows.sortedBy { it.startMillis }
        val sortedBlocks = blocks.filter { it.endMillis > it.startMillis }.sortedBy { it.startMillis }
        val segMult = ArrayList<DoubleArray>(segCount)
        val segFixed = IntArray(segCount)
        wallFixed = segFixed
        wallStart = LongArray(segCount)
        wallEnd = LongArray(segCount)
        wallU0 = DoubleArray(segCount)
        wallSchedulable = BooleanArray(segCount)
        var wi = 0
        val active = ArrayList<PlanWindow>()
        var bi = 0
        for (s in 0 until segCount) {
            val a = edges[s]
            val b = edges[s + 1]
            wallStart[s] = a
            wallEnd[s] = b
            while (wi < sortedWindows.size && sortedWindows[wi].startMillis <= a) active += sortedWindows[wi++]
            active.removeAll { it.endMillis != null && it.endMillis <= a }
            while (bi < sortedBlocks.size && sortedBlocks[bi].endMillis <= a) bi++
            val block = sortedBlocks.getOrNull(bi)?.takeIf { it.startMillis <= a && a < it.endMillis }
            val mult = DoubleArray(n)
            var fixed = -1
            if (block != null) {
                val owner = block.taskId?.let { indexOf[it] }
                if (owner != null) {
                    mult[owner] = 1.0
                    fixed = owner
                } else {
                    fixed = NOBODY
                }
            } else {
                for (i in 0 until n) {
                    var m = 1.0
                    val id = tasks[i].id
                    for (w in active) {
                        m *= w.multiplierFor(id)
                        if (m <= 0.0) break
                    }
                    mult[i] = m.coerceIn(0.0, 1.0)
                }
            }
            segMult += mult
            segFixed[s] = fixed
            wallSchedulable[s] = fixed != NOBODY && mult.any { it > 0.0 }
        }

        // --- merge the schedulable segments into pieces on the schedulable clock
        val pStart = ArrayList<Double>()
        val pLen = ArrayList<Double>()
        val pMult = ArrayList<DoubleArray>()
        val pFixed = ArrayList<Int>()
        var u = 0.0
        for (s in 0 until segCount) {
            wallU0[s] = u
            if (!wallSchedulable[s]) continue
            val len = (wallEnd[s] - wallStart[s]).toDouble()
            val last = pMult.size - 1
            if (last >= 0 && pFixed[last] == segFixed[s] && pMult[last].contentEquals(segMult[s])) {
                pLen[last] = pLen[last] + len
            } else {
                pStart += u
                pLen += len
                pMult += segMult[s]
                pFixed += segFixed[s]
            }
            u += len
        }
        uEnd = u
        pieceCount = pStart.size
        pieceUStart = pStart.toDoubleArray()
        pieceULen = pLen.toDoubleArray()
        pieceMult = pMult.toTypedArray()
        pieceFixed = pFixed.toIntArray()
        pieceShare = Array(pieceCount) { p -> localShares(pieceMult[p], pieceFixed[p]) }

        // --- compensation coefficients
        compA = Array(pieceCount) { DoubleArray(n) }
        compB = Array(pieceCount) { DoubleArray(n) }
        pieceHasComp = BooleanArray(pieceCount)
        for (i in 0 until n) {
            if (share[i] <= 0.0) continue
            val t = COMPENSATION_LENGTH_MILLIS
            val reach = COMP_REACH_LENGTHS * t
            // Only a piece where the multiplier is below 1 can deprive anyone.
            val depr = (0 until pieceCount).filter { pieceMult[it][i] < 1.0 }
            if (depr.isEmpty()) continue
            for (p in 0 until pieceCount) {
                val mu = pieceMult[p][i]
                if (mu <= 0.0) continue
                var a = 0.0
                var b = 0.0
                for (q in depr) {
                    val mq = pieceMult[q][i]
                    if (mq >= mu) continue
                    val lenQ = pieceLenForComp(q)
                    val weight = (mu - mq) * t * (1.0 - expNeg(lenQ / t))
                    if (q < p) {
                        val d = pieceUStart[p] - (pieceUStart[q] + pieceULen[q])
                        if (d > reach) continue
                        a += weight * exp(-d / t)
                    } else if (q > p) {
                        val d = pieceUStart[q] - (pieceUStart[p] + pieceULen[p])
                        if (d > reach) continue
                        b += weight * exp(-d / t)
                    }
                }
                val k = share[i] / t
                compA[p][i] = a * k
                compB[p][i] = b * k
                if (a > 0.0 || b > 0.0) pieceHasComp[p] = true
            }
        }
    }

    private fun pieceLenForComp(p: Int): Double =
        if (p == pieceCount - 1 && wallEnd.isNotEmpty() && wallEnd.last() >= toMillis) Double.POSITIVE_INFINITY
        else pieceULen[p]

    private fun localShares(mult: DoubleArray, fixed: Int): DoubleArray {
        val q = DoubleArray(n)
        if (fixed >= 0) {
            q[fixed] = 1.0
            return q
        }
        var sum = 0.0
        for (i in 0 until n) sum += priority[i] * mult[i]
        if (sum > 0.0) {
            for (i in 0 until n) q[i] = priority[i] * mult[i] / sum
        } else {
            val permitted = (0 until n).count { mult[it] > 0.0 }
            if (permitted > 0) for (i in 0 until n) if (mult[i] > 0.0) q[i] = 1.0 / permitted
        }
        return q
    }

    // ----- the target share ------------------------------------------------------------------------

    /** `f_i` at offset [v] inside piece [p], written into [out]. */
    fun targetAt(p: Int, v: Double, out: DoubleArray) {
        val q = pieceShare[p]
        if (!pieceHasComp[p]) {
            q.copyInto(out)
            return
        }
        val len = pieceULen[p]
        var c = 0.0
        val t = COMPENSATION_LENGTH_MILLIS
        for (i in 0 until n) {
            var ci = 0.0
            val a = compA[p][i]
            if (a != 0.0) ci += a * exp(-v / t)
            val b = compB[p][i]
            if (b != 0.0) ci += b * exp(-(len - v) / t)
            out[i] = ci
            c += ci
        }
        if (c <= 1.0) {
            for (i in 0 until n) out[i] = q[i] * (1.0 - c) + out[i]
        } else {
            for (i in 0 until n) out[i] = out[i] / c
        }
    }

    /** `f_i` at the schedulable instant [u] (tests and diagnostics). */
    fun targetAt(u: Double): DoubleArray {
        val out = DoubleArray(n)
        val p = pieceAt(u)
        if (p >= 0) targetAt(p, u - pieceUStart[p], out)
        return out
    }

    // ----- the clock ---------------------------------------------------------------------------------

    /** The piece holding [u] (`uStart ≤ u < uStart + len`), the last one past the end, -1 when empty. */
    fun pieceAt(u: Double): Int {
        if (pieceCount == 0) return -1
        var lo = 0
        var hi = pieceCount - 1
        while (lo < hi) {
            val mid = (lo + hi + 1) ushr 1
            if (pieceUStart[mid] <= u) lo = mid else hi = mid - 1
        }
        return lo
    }

    /** The schedulable clock at wall instant [millis]. */
    fun uAt(millis: Long): Double {
        if (wallStart.isEmpty() || millis <= fromMillis) return 0.0
        if (millis >= toMillis) return uEnd
        var lo = 0
        var hi = wallStart.size - 1
        while (lo < hi) {
            val mid = (lo + hi + 1) ushr 1
            if (wallStart[mid] <= millis) lo = mid else hi = mid - 1
        }
        return if (wallSchedulable[lo]) wallU0[lo] + (millis - wallStart[lo]) else wallU0[lo]
    }

    /** The wall instant a run STARTING at [u] begins: after any stretch nobody may run in. */
    fun wallStartAt(u: Double): Long {
        for (s in firstSegmentFrom(u) until wallStart.size) {
            if (!wallSchedulable[s]) continue
            val len = (wallEnd[s] - wallStart[s]).toDouble()
            if (u < wallU0[s] + len - HALF_MILLI) return wallStart[s] + roundOffset(u - wallU0[s])
        }
        return toMillis
    }

    /** The wall instant a run ENDING at [u] ends: before any stretch nobody may run in. */
    fun wallEndAt(u: Double): Long {
        if (u <= HALF_MILLI) return wallStartAt(0.0)
        for (s in firstSegmentFrom(u - HALF_MILLI) until wallStart.size) {
            if (!wallSchedulable[s]) continue
            val len = (wallEnd[s] - wallStart[s]).toDouble()
            if (u <= wallU0[s] + len + HALF_MILLI) return wallStart[s] + roundOffset(u - wallU0[s]).coerceAtMost(len.toLong())
        }
        return toMillis
    }

    /** One wall-time stretch of a run, and whether it lies inside a pre-placed task. */
    data class WallInterval(val startMillis: Long, val endMillis: Long, val fixed: Boolean)

    /**
     * The wall-time stretches `[fromU, toU)` of the schedulable clock covers: split wherever a stretch nobody may
     * run in intervenes, and where it enters or leaves a pre-placed task.
     */
    fun wallIntervals(fromU: Double, toU: Double): List<WallInterval> {
        val out = ArrayList<WallInterval>()
        if (toU <= fromU) return out
        for (s in firstSegmentFrom(fromU) until wallStart.size) {
            if (!wallSchedulable[s]) continue
            val u0 = wallU0[s]
            val u1 = u0 + (wallEnd[s] - wallStart[s]).toDouble()
            if (u1 <= fromU + HALF_MILLI) continue
            if (u0 >= toU - HALF_MILLI) break
            val a = wallStart[s] + roundOffset(maxOf(fromU, u0) - u0)
            val b = wallStart[s] + roundOffset(minOf(toU, u1) - u0)
            if (b <= a) continue
            val fixed = wallFixed[s] >= 0
            val last = out.lastOrNull()
            if (last != null && last.endMillis == a && last.fixed == fixed) out[out.size - 1] = last.copy(endMillis = b)
            else out += WallInterval(a, b, fixed)
        }
        return out
    }

    private fun roundOffset(x: Double): Long = kotlin.math.round(x.coerceAtLeast(0.0)).toLong()

    private fun firstSegmentFrom(u: Double): Int {
        var lo = 0
        var hi = wallStart.size - 1
        if (hi < 0) return 0
        while (lo < hi) {
            val mid = (lo + hi + 1) ushr 1
            if (wallU0[mid] <= u) lo = mid else hi = mid - 1
        }
        // step back over zero-length schedulable prefixes sharing the same u
        while (lo > 0 && wallU0[lo - 1] >= u) lo--
        return lo
    }

    /** May task [i] run at the schedulable instant [u]? */
    fun permitted(i: Int, u: Double): Boolean {
        val p = pieceAt(u)
        if (p < 0) return false
        val fixed = pieceFixed[p]
        return if (fixed >= 0) fixed == i else pieceMult[p][i] > 0.0
    }

    /**
     * How far a run of [i] may go from [u]: up to the first piece where it may not run, or where a pre-placed task
     * of somebody else begins. Running into its OWN pre-placed task is not an end (the two are one panel).
     */
    fun runLimit(i: Int, u: Double): Double {
        var p = pieceAt(u)
        if (p < 0) return u
        while (p < pieceCount) {
            val fixed = pieceFixed[p]
            val ok = if (fixed >= 0) fixed == i else pieceMult[p][i] > 0.0
            if (!ok) return maxOf(u, pieceUStart[p])
            p++
        }
        return uEnd
    }

    /** Where the pre-placed task [u] is inside ends (the consecutive pieces fixed to the same task), or [u]. */
    fun fixedEnd(u: Double): Double {
        var p = pieceAt(u)
        if (p < 0) return u
        val owner = pieceFixed[p]
        if (owner < 0) return u
        while (p < pieceCount && pieceFixed[p] == owner) p++
        return if (p < pieceCount) pieceUStart[p] else uEnd
    }

    /** Where the first pre-placed piece strictly after the piece holding [u] starts, or [uEnd]. */
    fun nextFixedStart(u: Double): Double {
        var p = pieceAt(u)
        if (p < 0) return uEnd
        p++
        while (p < pieceCount) {
            if (pieceFixed[p] >= 0) return pieceUStart[p]
            p++
        }
        return uEnd
    }

    /** The next instant after [u] at which the environment changes, or [uEnd]. */
    fun nextEdge(u: Double): Double {
        val p = pieceAt(u)
        if (p < 0) return uEnd
        return minOf(uEnd, pieceUStart[p] + pieceULen[p])
    }

    /** The tasks the scheduler may choose from at [u], in tie-break order; the pre-placed task alone inside one. */
    fun candidatesAt(u: Double): List<Int> {
        val p = pieceAt(u)
        if (p < 0) return emptyList()
        val fixed = pieceFixed[p]
        if (fixed >= 0) return listOf(fixed)
        return (0 until n).filter { pieceMult[p][it] > 0.0 }
    }

    /** Is [u] inside a pre-placed task? Its owner, or -1. */
    fun fixedAt(u: Double): Int {
        val p = pieceAt(u)
        return if (p < 0) -1 else pieceFixed[p]
    }

    // ----- the lag, and the two criteria ------------------------------------------------------------

    /**
     * Inside a piece with compensation the target moves on the scale of `λ`, so the piece is cut into cells of at
     * most `λ/16` and the target is read at each cell's midpoint; the lag is exact inside a cell for that
     * target (an exponential, and so is the discounted square of it). The cells are FIXED per piece, so how an
     * interval happens to be split by the calls that integrate it never changes the result.
     */
    private val cellLen: DoubleArray =
        DoubleArray(pieceCount) { p -> if (pieceHasComp[p]) minOf(pieceULen[p], COMPENSATION_LENGTH_MILLIS / COMP_STEPS_PER_LENGTH).coerceAtLeast(1.0) else Double.POSITIVE_INFINITY }
    private val cellTotal = arrayOfNulls<DoubleArray>(pieceCount)

    /** `∫ q_i` from the model's start to each piece's start — what the base policy's quick lag estimate reads. */
    private val shareIntegral: Array<DoubleArray> = Array(pieceCount + 1) { DoubleArray(n) }.also { acc ->
        for (p in 0 until pieceCount) for (i in 0 until n) acc[p + 1][i] = acc[p][i] + pieceShare[p][i] * pieceULen[p]
    }

    private fun compOf(i: Int, p: Int, v: Double): Double {
        val t = COMPENSATION_LENGTH_MILLIS
        var c = 0.0
        val a = compA[p][i]
        if (a != 0.0) c += a * exp(-v / t)
        val b = compB[p][i]
        if (b != 0.0) c += b * exp(-(pieceULen[p] - v) / t)
        return c
    }

    private fun cellCount(p: Int): Int = maxOf(1, ceil(pieceULen[p] / cellLen[p]).toInt())

    /** `C` at the midpoint of cell [k] of piece [p], computed once. */
    private fun totalComp(p: Int, k: Int, v: Double): Double {
        val cache = cellTotal[p] ?: DoubleArray(cellCount(p)) { Double.NaN }.also { cellTotal[p] = it }
        val kk = k.coerceIn(0, cache.size - 1)
        val known = cache[kk]
        if (!known.isNaN()) return known
        var c = 0.0
        for (i in 0 until n) c += compOf(i, p, v)
        cache[kk] = c
        return c
    }

    /** `f_i` at the midpoint [v] of cell [k] of piece [p]. */
    private fun targetOf(i: Int, p: Int, k: Int, v: Double): Double {
        val q = pieceShare[p][i]
        if (!pieceHasComp[p]) return q
        val total = totalComp(p, k, v)
        val c = compOf(i, p, v)
        return if (total <= 1.0) q * (1.0 - total) + c else c / total
    }

    /** A fresh cursor at [u], every lag at zero, discounting from [originU]. */
    fun cursor(u: Double = 0.0, originU: Double = u): ScoreCursor =
        ScoreCursor(DoubleArray(n), DoubleArray(n) { u }, u, -1, 0.0, 0.0, originU)

    /**
     * Serve [task] (an index, or -1 for schedulable time the frozen past gave to nobody) from `cursor.u` to
     * [untilU], adding both criteria to `cursor.cost`.
     *
     * Every other task's lag is advanced LAZILY: it is a function of its own service and of its own target only,
     * so it is integrated when the task is next served or when the cursor is [settle]d. That is the same integral
     * as advancing all of them at every step, cut at different instants — and cutting it never changes it.
     */
    fun serve(cursor: ScoreCursor, task: Int, untilU: Double) {
        if (untilU <= cursor.u) return
        if (task != cursor.run) {
            chargeShortfall(cursor)
            cursor.run = task
            cursor.runLen = 0.0
        }
        if (task >= 0) {
            advance(cursor, task, cursor.u, served = false)
            advance(cursor, task, untilU, served = true)
        }
        cursor.runLen += untilU - cursor.u
        cursor.u = untilU
    }

    /** Integrate every lag up to [u] (the cursor's own instant by default). */
    fun settle(cursor: ScoreCursor, u: Double = cursor.u) {
        for (i in 0 until n) advance(cursor, i, u, served = false)
    }

    /** A settled copy of [cursor] that discounts from its own instant and carries no score yet. */
    fun rebase(cursor: ScoreCursor): ScoreCursor {
        val c = cursor.copy()
        settle(c)
        return ScoreCursor(c.lag, DoubleArray(n) { c.u }, c.u, c.run, c.runLen, 0.0, c.u)
    }

    /** Task [i]'s lag at [u] without integrating it — a quick estimate for the base policy (forgetting ignored). */
    fun estimateLag(cursor: ScoreCursor, i: Int, u: Double): Double =
        cursor.lag[i] - (shareIntegralAt(i, u) - shareIntegralAt(i, cursor.lagU[i]))

    private fun shareIntegralAt(i: Int, u: Double): Double {
        val p = pieceAt(u)
        if (p < 0) return 0.0
        return shareIntegral[p][i] + pieceShare[p][i] * (u - pieceUStart[p])
    }

    /** Advance task [i]'s lag from where it stands to [toU], served or not throughout. */
    fun advance(cursor: ScoreCursor, i: Int, toU: Double, served: Boolean) {
        var u = cursor.lagU[i]
        if (toU <= u + EPS) return
        val x = if (served) 1.0 else 0.0
        var p = pieceAt(u)
        while (u < toU - EPS && p >= 0 && p < pieceCount) {
            val pStart = pieceUStart[p]
            val stop = if (p == pieceCount - 1) toU else minOf(toU, pStart + pieceULen[p])
            if (stop <= u + EPS) {
                p++
                continue
            }
            if (!pieceHasComp[p]) {
                integrateOne(cursor, i, x, pieceShare[p][i], u, stop - u)
                u = stop
            } else {
                val h = cellLen[p]
                // The cell index is derived from `u` ONCE and then stepped: re-deriving it from a `u` that landed on
                // a cell end rounds back into that same cell about a quarter of the time at real millis offsets, so
                // `s == u` and the loop never ends (the 2026-09-17 release app that never drew a window).
                var k = floor((u - pStart) / h).toInt().coerceAtLeast(0)
                while (u < stop - EPS) {
                    val cellEnd = pStart + (k + 1) * h
                    if (cellEnd <= u + EPS) {
                        k++
                        continue
                    }
                    val s = minOf(stop, cellEnd)
                    val mid = minOf(pieceULen[p], (k + 0.5) * h)
                    integrateOne(cursor, i, x, targetOf(i, p, k, mid), u, s - u)
                    u = s
                    k++
                }
            }
            p++
        }
        if (u < toU - EPS) {
            // past the model's last piece nothing is schedulable to reason about; hold the target of the last one
            val last = pieceCount - 1
            if (last >= 0) integrateOne(cursor, i, x, pieceShare[last][i], u, toU - u)
        }
        cursor.lagU[i] = toU
    }

    private fun integrateOne(cursor: ScoreCursor, i: Int, x: Double, f: Double, u: Double, h: Double) {
        if (h <= 0.0) return
        val t = tau[i]
        val kd = 1.0 / theta
        val lInf = (x - f) * t
        val d = cursor.lag[i] - lInf
        val acc = lInf * lInf * integral(kd, h) + 2.0 * lInf * d * integral(kd + 1.0 / t, h) +
            d * d * integral(kd + 2.0 / t, h)
        cursor.cost += exp(-(u - cursor.originU) / theta) * acc
        cursor.lag[i] = lInf + d * exp(-h / t)
    }

    /** `∫₀ʰ e^(−k·w) dw`. */
    private fun integral(k: Double, h: Double): Double {
        val kh = k * h
        return if (kh < 1e-8) h * (1.0 - kh / 2.0) else (1.0 - exp(-kh)) / k
    }

    /** Criterion 2 for the run the cursor is in, charged at `cursor.u` as if it ended there. */
    fun chargeShortfall(cursor: ScoreCursor) {
        val run = cursor.run
        if (run < 0) return
        val s = minimum[run] - cursor.runLen
        if (s <= EPS) return
        cursor.cost += exp(-(cursor.u - cursor.originU) / theta) * shortfallCost(run, s)
    }

    /**
     * Criterion 2's charge for a panel of task [i] short by [s]: `τ_i·s·(2M_i + s)` — what raising a lag of `M_i`
     * to `M_i + s` costs over the task's window. Its slope at `s = 0` is `2M_i·τ_i`, more than any lag a panel of
     * minimum length leaves behind can repay, so a panel is cut short only where a larger debt calls for it.
     */
    fun shortfallCost(i: Int, s: Double): Double = tau[i] * s * (2.0 * minimum[i] + s)

    /**
     * A LOWER bound on task [i]'s criterion 1 from [fromU] to [untilU], from its lag [lag] at [fromU], whatever is
     * scheduled: the lag is driven towards zero as fast as any schedule could (a rate of 1 plus its own forgetting)
     * and held there. Used to prune and to close a window; never used as a score.
     */
    fun lowerBound(i: Int, lag: Double, fromU: Double, untilU: Double, originU: Double): Double {
        val span = untilU - fromU
        val l0 = abs(lag)
        if (span <= 0.0 || l0 <= 0.0) return 0.0
        val kd = 1.0 / theta
        val t = tau[i]
        // |L|(w) ≥ (l0 + τ)e^(−w/τ) − τ, which reaches 0 at w0 = τ·ln(1 + l0/τ).
        val w0 = minOf(span, t * ln(1.0 + l0 / t))
        val a = l0 + t
        val acc = a * a * integral(kd + 2.0 / t, w0) - 2.0 * a * t * integral(kd + 1.0 / t, w0) + t * t * integral(kd, w0)
        return exp(-(fromU - originU) / theta) * acc.coerceAtLeast(0.0)
    }

    /** The lower bound of every task's criterion 1 from a settled [cursor] to [untilU]. */
    fun lowerBound(cursor: ScoreCursor, untilU: Double): Double {
        val c = cursor.copy()
        settle(c)
        var acc = 0.0
        for (i in 0 until n) acc += lowerBound(i, c.lag[i], c.u, untilU, c.originU)
        return acc
    }

    /** The environment's own description of task [i], for diagnostics. */
    fun taskId(i: Int): TaskId = tasks[i].id

    companion object {
        const val NOBODY: Int = -2
        const val MIN_WINDOW_MILLIS: Double = 60_000.0
        /**
         * `λ`, the schedulable distance over which a deprivation's compensation fades, on both sides. It also bounds
         * what a long deprivation buys (`2π_iλ`), so it is what makes a 48-hour blockage buy barely more than a
         * 24-hour one. A constant, not `τ_i`: how much a task is repaid must not depend on its minimum time.
         */
        const val COMPENSATION_LENGTH_MILLIS: Double = 4 * 3_600_000.0
        /** `e^(−40)`: past it a deprivation's influence is below double precision's relevance to the score. */
        const val COMP_REACH_LENGTHS: Double = 40.0
        const val COMP_STEPS_PER_LENGTH: Double = 16.0
        const val EPS: Double = 1e-6
        const val HALF_MILLI: Double = 0.5

        private fun expNeg(x: Double): Double = if (x == Double.POSITIVE_INFINITY) 0.0 else exp(-x)
    }
}

/**
 * Where the score stands after a prefix of a continuation: every task's lag and the instant it was integrated up
 * to ([lagU] — see [ScoreModel.serve]), the run in progress (task and length on the schedulable clock) and the
 * score accumulated since [originU].
 */
class ScoreCursor(
    val lag: DoubleArray,
    val lagU: DoubleArray,
    var u: Double,
    var run: Int,
    var runLen: Double,
    var cost: Double,
    val originU: Double,
) {
    fun copy(): ScoreCursor = ScoreCursor(lag.copyOf(), lagU.copyOf(), u, run, runLen, cost, originU)
}
