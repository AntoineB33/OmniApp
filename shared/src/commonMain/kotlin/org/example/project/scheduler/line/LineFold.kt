package org.example.project.scheduler.line

import org.example.project.scheduler.line.Rational.Companion.NEG_INF
import org.example.project.scheduler.line.Region.Companion.EMPTY

/** How far behind the now-line the timeline is folded (ADR 0018, user decision 2026-10-22). */
const val FOLD_DAYS = 30

private const val DAY_MILLIS = 86_400_000L

/**
 * The fold point for [nowMillis]: midnight UTC, [FOLD_DAYS] days before. Quantized to the day so that every device
 * folds at the same point — two devices folding at different instants would replay different edits.
 */
fun foldPointMillis(nowMillis: Long): Long = (nowMillis - FOLD_DAYS * DAY_MILLIS).floorDiv(DAY_MILLIS) * DAY_MILLIS

/**
 * A line whose far past is FOLDED (ADR 0018 phase 2): what lies before [foldAt] is a plain record ([folded]) the
 * replay never sees again, and only the edits that still touch the timeline after it are kept and replayed.
 *
 * - **The fold is a pin with top priority** ([LineSystem.pin]): the folded record, present where it has a kind and
 *   absent everywhere else before [foldAt], is the newest level of every solve. Live edits adapt around it; an edit
 *   whose own claims it would drop — one whose rules reach into the folded past — is refused. Nothing changes the
 *   folded past but a flat edit.
 * - **An edit is cut at [foldAt].** What it does before the fold point is applied FLAT ([flat]): written into the
 *   record as a plain diff, refused if the record would then break the rules by itself, with no adaptation of anything
 *   else. What it does after is a live edit of the line. A move straddling the fold point is cut into what it takes and
 *   what it puts on each side.
 * - **[fold] moves the fold point forward** and keeps the line exactly as it was: the line before the new point
 *   becomes the record, every live edit is cut to what it does after it, and an edit with nothing left there is
 *   forgotten — and with it its undo. The new line is checked against the old one; a fold that would change it is not
 *   made (`false`), and the fold point stays.
 * - **Undo** ([reverse]) takes back what is left of an edit: its live part (refused, like any undo, if a later edit
 *   requires it) and its flat part, until the next fold forgets it.
 */
class FoldedLine(private val rules: List<LineRule> = emptyList()) {

    /** An edit as the caller made it: its flat part, and the id of its live part in the [LineSystem] (null: none). */
    class Record internal constructor(val id: Int, internal var lineId: Int?, val flat: List<LineOp>) {
        var active: Boolean = true
            internal set
    }

    private var line = LineSystem(rules)
    private val records = LinkedHashMap<Int, Record>()
    private var nextId = 0

    /** Positions strictly before it are folded. -inf: nothing is folded yet. */
    var foldAt: Rational = NEG_INF
        private set

    /** The folded record: what is on the line before [foldAt]. */
    var folded: Claims = emptyMap()
        private set

    val ruleset: RuleSet get() = line.ruleset

    /** The edits still remembered (a fold forgets those it leaves nothing of). */
    val liveRecords: List<Record> get() = records.values.filter { it.active }

    /** The number of edits the replay holds, active or not: what memory and every solve grow with. */
    val replayedEdits: Int get() = line.allEdits.size

    fun state(): Claims = line.state()

    private fun past(at: Rational = foldAt): Region = if (at == NEG_INF) EMPTY else Region.below(at)

    private fun future(at: Rational = foldAt): Region = if (at == NEG_INF) Region.FULL else Region.atOrAbove(at)

    /**
     * [op] cut to [side]. With [keepWhole], an op lying wholly on that side is kept as it is (a move stays a move, an
     * extension an extension). Otherwise it is what it takes and what it puts there, as a removal and an add — a move's
     * source and target where they overlap neither take nor put (its contribution there is zero).
     */
    private fun cut(op: LineOp, side: Region, keepWhole: Boolean): List<LineOp> {
        if (keepWhole && (taken(op) or put(op)) - side == EMPTY) return listOf(op)
        val takes = (if (op is LineOp.Move) op.region - op.target else taken(op)) and side
        val puts = (if (op is LineOp.Move) op.target - op.region else put(op)) and side
        return listOfNotNull(
            takes.takeIf { it.isNotEmpty() }?.let { LineOp.Remove(op.kind, it) },
            puts.takeIf { it.isNotEmpty() }?.let { LineOp.Add(op.kind, it) },
        )
    }

    /** [record] with the flat ops applied as a plain diff; [MissingElementError] if one does not fit it. */
    private fun applyFlat(record: Claims, flat: List<LineOp>): Claims {
        val out = LinkedHashMap(record)
        for (op in flat) {
            val have = out.at(op.kind)
            when (op) {
                is LineOp.Add -> {
                    val dup = op.region and have
                    if (dup.isNotEmpty()) throw MissingElementError("'${op.kind}' is already on $dup")
                    out[op.kind] = have or op.region
                }
                is LineOp.Remove -> {
                    val missing = op.region - have
                    if (missing.isNotEmpty()) throw MissingElementError("there is no '${op.kind}' on $missing to remove")
                    out[op.kind] = have - op.region
                }
                else -> error("a flat op is an add or a removal: $op")
            }
        }
        return out.filterValues { it.isNotEmpty() }
    }

    /** Replaces the record by [record], the line adapting around it; refused (nothing changes) if it breaks the rules. */
    private fun setFolded(record: Claims) {
        line.setPin(pinOps(line.ruleset, record, foldAt))
        folded = record
    }

    /**
     * Applies an edit: its part before [foldAt] flat, its part after as a live edit of the line. Returns its id.
     * Throws [MissingElementError] or [ContradictionError], and changes nothing, if either part is refused.
     */
    fun apply(ops: List<LineOp>): Int {
        val past = past()
        val state = line.state()
        for (op in ops) {
            if (op is LineOp.Move && (op.region or op.target) - future() != EMPTY) {
                val missing = op.region - state.at(op.kind)
                if (missing.isNotEmpty()) throw MissingElementError("there is no '${op.kind}' on $missing to move")
            }
        }
        val flat = if (past.isEmpty()) emptyList() else ops.flatMap { cut(it, past, keepWhole = false) }
        val live = if (past.isEmpty()) ops else ops.flatMap { cut(it, future(), keepWhole = true) }
        val before = folded
        if (flat.isNotEmpty()) setFolded(applyFlat(folded, flat))
        val lineId = try {
            if (live.isNotEmpty()) line.apply(live) else null
        } catch (e: IllegalArgumentException) {
            if (flat.isNotEmpty()) setFolded(before)
            throw e
        }
        val id = nextId++
        records[id] = Record(id, lineId, flat)
        return id
    }

    /**
     * Undoes what is left of an edit. Throws [DependencyError], and changes nothing, if a later edit requires its live
     * part; [ContradictionError] or [MissingElementError] if its flat part no longer fits the record.
     */
    fun reverse(id: Int) {
        val rec = records[id] ?: throw NoSuchElementException("edit $id is not remembered (folded)")
        require(rec.active) { "edit $id cannot be reversed" }
        val before = folded
        if (rec.flat.isNotEmpty()) setFolded(applyFlat(folded, inverse(rec.flat)))
        try {
            rec.lineId?.let { line.reverse(it) }
        } catch (e: IllegalArgumentException) {
            if (rec.flat.isNotEmpty()) setFolded(before)
            throw e
        }
        rec.active = false
    }

    /**
     * Moves the fold point forward to [to], keeping the line exactly as it is. Returns false, and changes nothing, if
     * the line rebuilt from the cut edits would differ (the fold is then retried at a later point).
     */
    fun fold(to: Rational): Boolean {
        require(to >= foldAt) { "the fold point only moves forward ($foldAt -> $to)" }
        if (to == foldAt) return true
        val before = line.state()
        val record = before.mapValues { it.value and Region.below(to) }.filterValues { it.isNotEmpty() }
        // The live edits cut to what they do after the new point, restored as they are: each was valid when it was
        // made, and an older one a newer one made adapt is not valid if applied anew — re-applying would refuse it.
        val cutEdits = records.values.filter { it.active && it.lineId != null }
            .map { rec -> rec to line.allEdits[rec.lineId!!].ops.flatMap { cut(it, Region.atOrAbove(to), keepWhole = true) } }
            .filter { it.second.isNotEmpty() }
        val rebuilt = LineSystem(rules)
        val ids = try {
            rebuilt.setPin(pinOps(rebuilt.ruleset, record, to))
            rebuilt.restore(cutEdits.map { it.second })
        } catch (e: IllegalArgumentException) {
            return false
        }
        if (rebuilt.state() != before) return false
        line = rebuilt
        foldAt = to
        folded = record
        val survivors = cutEdits.zip(ids).associate { (cutEdit, lineId) -> cutEdit.first.id to Record(cutEdit.first.id, lineId, emptyList()) }
        records.clear()
        records.putAll(survivors)
        return true
    }

    /** The pin stating [record] on everything before [at]: present where it has a kind, absent everywhere else. */
    private fun pinOps(ruleset: RuleSet, record: Claims, at: Rational): List<LineOp>? {
        if (at == NEG_INF) return null
        val past = Region.below(at)
        return (ruleset.kinds() + record.keys).sorted().flatMap { k ->
            val present = record.at(k) and past
            val absent = past - present
            listOfNotNull(present.takeIf { it.isNotEmpty() }?.let { LineOp.Add(k, it) }, absent.takeIf { it.isNotEmpty() }?.let { LineOp.Remove(k, it) })
        }
    }

    /** Starts dragging a block of the live line (wholly after [foldAt]); the drop is an edit like [apply]'s. */
    fun drag(block: List<Pair<String, Region>>): FoldedDrag {
        val future = future()
        require(block.all { (_, r) -> r - future == EMPTY }) { "a dragged block lies after the fold point" }
        return FoldedDrag(line.drag(block)) { lineId ->
            val id = nextId++
            records[id] = Record(id, lineId, emptyList())
            id
        }
    }

    class FoldedDrag internal constructor(private val drag: Drag, private val register: (Int) -> Int) {
        fun step(d: Rational) = drag.step(d)

        /** Ends the drag; returns the edit's id (null if back at the start). */
        fun drop(): Int? = drag.drop()?.let(register)

        fun cancel() = drag.cancel()
    }
}
