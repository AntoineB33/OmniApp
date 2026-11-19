package org.example.project.scheduler.domain

/**
 * **The window colours** (user rule 2026-10-06). Every window has a colour of its own — the background of its head and
 * of its tab in the window bar — and KEEPS it for as long as it is open: unlike a task's colour, it is never worked out
 * again because something else changed.
 *
 * The colours are a partition of the sRGB cube (`256³`):
 *
 * 1. **Each DEFAULT window owns an equal part of the cube** ([DEFAULT_WINDOWS], [partOf]): the calendar, the task tree,
 *    a Search window, the "unfocused notif" window… — one part per kind of window, all of the same volume
 *    ([parts]: the cube halved along its longest side until there is a part for each).
 * 2. **A default window's colour is the centre of its part** — the point furthest from the part's edge.
 * 3. **Another window of the same kind** (a duplicate, a second "unfocused notif" window) takes the colour of that part
 *    **furthest from every colour in use and from the part's edge** ([pick]). A colour of another part is never nearer
 *    to a point than the edge between them is, so the colours in use that count are those of the part itself.
 *
 * Pure arithmetic on `0xRRGGBB` integers. Which window holds which colour, and for how long, is
 * `org.example.project.ui.WindowFrameHost`'s business.
 */
object WindowColorSpace {
    /** The part of the Search window the app opens on what fired out of focus ([SearchDomain.notificationsConfig]). */
    const val UNFOCUSED_NOTIF: String = "UnfocusedNotif"

    /** The part of a window whose kind is not listed — a notice. */
    const val OTHER: String = "Other"

    /**
     * The default windows, by the name their frame id starts with: the lateral-menu windows first, then the windows
     * about one object. Their ORDER is where each one's part lies in the cube, so a new kind goes at the end of its
     * group rather than in the middle — the windows already open keep their colour either way.
     */
    val DEFAULT_WINDOWS: List<String> =
        listOf(
            "Calendar", "TaskTree", "Search", UNFOCUSED_NOTIF, "History", "Sleep", "Alarms", "TaskTrees",
            "TaskRelations", "Categories", "DefaultSubtree", "Shortcuts", "Online", "ConfigSearch", "AddedConfig",
            "TaskEdit", "CategoryEdit", "PeriodKindEdit", "PriorityWeights", "RelativePriority", "DeepCopy",
            "AlarmOrTimerEdit", "AlarmOrTimerDefaults", "ReminderEdit", "ReminderDefaults", "HistoryEntryInfo",
            "TaskTreeDetail", "CalendarElements", "CalendarEntryEdit", "CalendarPeriodEdit", "CalendarReminderEdit",
            "ReminderConstraintEdit", OTHER,
        )

    /** A box of the cube: on each axis the colours from `lo` (included) to `hi` (excluded). */
    data class Part(val rLo: Int, val rHi: Int, val gLo: Int, val gHi: Int, val bLo: Int, val bHi: Int) {
        val volume: Long get() = (rHi - rLo).toLong() * (gHi - gLo) * (bHi - bLo)

        /** The part's own colour: its centre. */
        val centre: Int get() = rgb((rLo + rHi) / 2, (gLo + gHi) / 2, (bLo + bHi) / 2)

        operator fun contains(color: Int): Boolean =
            red(color) in rLo until rHi && green(color) in gLo until gHi && blue(color) in bLo until bHi
    }

    /** The cube cut into [count] parts of the same volume (to a colour's rounding), in order. */
    fun parts(count: Int): List<Part> {
        val out = ArrayList<Part>(count)
        fun cut(part: Part, n: Int) {
            if (n <= 1) {
                out.add(part)
                return
            }
            val first = n / 2
            val r = part.rHi - part.rLo
            val g = part.gHi - part.gLo
            val b = part.bHi - part.bLo
            // Along the longest side, in the proportion of the parts each half still has to hold.
            when {
                r >= g && r >= b -> (part.rLo + (r.toLong() * first / n).toInt()).let {
                    cut(part.copy(rHi = it), first)
                    cut(part.copy(rLo = it), n - first)
                }
                g >= b -> (part.gLo + (g.toLong() * first / n).toInt()).let {
                    cut(part.copy(gHi = it), first)
                    cut(part.copy(gLo = it), n - first)
                }
                else -> (part.bLo + (b.toLong() * first / n).toInt()).let {
                    cut(part.copy(bHi = it), first)
                    cut(part.copy(bLo = it), n - first)
                }
            }
        }
        cut(Part(0, SIDE, 0, SIDE, 0, SIDE), count.coerceAtLeast(1))
        return out
    }

    private val PARTS: Map<String, Part> by lazy { DEFAULT_WINDOWS.zip(parts(DEFAULT_WINDOWS.size)).toMap() }

    /** The default window a frame id (`Search#2`, `TaskTreeDetail/…`) or a part's own name stands for. */
    fun kindOf(frameId: String): String =
        frameId.substringBefore('#').substringBefore('/').takeIf { it in PARTS } ?: OTHER

    /** The part of the cube the windows of [frameId]'s kind take their colours in. */
    fun partOf(frameId: String): Part = PARTS.getValue(kindOf(frameId))

    /**
     * The colour of a NEW window whose kind owns [part], [used] being the colours of the windows open now: the part's
     * centre while no window has it, else the colour of the part furthest from every colour in use and from the part's
     * edge — the nearest to the centre among those that tie, so the answer is one colour.
     */
    fun pick(part: Part, used: Collection<Int>): Int {
        val taken = used.filter { it in part }
        val centre = part.centre
        if (centre !in taken) return centre
        val cr = red(centre)
        val cg = green(centre)
        val cb = blue(centre)
        val ur = IntArray(taken.size) { red(taken[it]) }
        val ug = IntArray(taken.size) { green(taken[it]) }
        val ub = IntArray(taken.size) { blue(taken[it]) }
        var best = centre
        var bestScore = -1
        var bestToCentre = Int.MAX_VALUE
        for (r in part.rLo until part.rHi) {
            val er = minOf(r - part.rLo, part.rHi - 1 - r)
            if (er * er < bestScore) continue
            for (g in part.gLo until part.gHi) {
                val eg = minOf(er, g - part.gLo, part.gHi - 1 - g)
                if (eg * eg < bestScore) continue
                for (b in part.bLo until part.bHi) {
                    val edge = minOf(eg, b - part.bLo, part.bHi - 1 - b)
                    // Squared distances throughout: the nearest of the edge and the colours in use.
                    var score = edge * edge
                    if (score < bestScore) continue
                    for (i in ur.indices) {
                        val dr = r - ur[i]
                        val dg = g - ug[i]
                        val db = b - ub[i]
                        val d = dr * dr + dg * dg + db * db
                        if (d < score) {
                            score = d
                            if (score < bestScore) break
                        }
                    }
                    if (score < bestScore) continue
                    val toCentre = (r - cr) * (r - cr) + (g - cg) * (g - cg) + (b - cb) * (b - cb)
                    if (score > bestScore || toCentre < bestToCentre) {
                        best = rgb(r, g, b)
                        bestScore = score
                        bestToCentre = toCentre
                    }
                }
            }
        }
        return best
    }

    private const val SIDE: Int = 256

    private fun rgb(r: Int, g: Int, b: Int): Int = (r shl 16) or (g shl 8) or b

    private fun red(color: Int): Int = (color shr 16) and 0xFF

    private fun green(color: Int): Int = (color shr 8) and 0xFF

    private fun blue(color: Int): Int = color and 0xFF
}
