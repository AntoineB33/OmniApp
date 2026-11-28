package org.example.project.scheduler.ruleline

import org.example.project.scheduler.line.Rational

/**
 * The state of the line: the user's edits, the claims they give, and the line — a port of
 * `C:\code\line_manager\scripts\line.py`.
 *
 * Claims are the elements placed by an edit: "K at p%" on a region, or "no K" on a region, each with its owner (the
 * user, the generated script, or a mode rule), and the user's removals ("off K": K absent there, until a newer edit
 * decides otherwise). The line is computed from the claims by the element rules ([solve]); a state is always
 * consistent. The user's edits are all kept, in order, and the claims are recomputed from them at each edit of the
 * user, so an edit that gave way applies again when what it contradicted goes.
 */
object Owner {
    const val USER = "user"
    const val GEN = "generated"
    const val MODE = "mode"

    /** What the line had where the memory ran short: no edit behind it. */
    const val FLAT = "flat"
}

private val HUNDRED = Rational.of(100)

private fun percent(p: Rational): Rational {
    if (!(p > Rational.ZERO && p <= HUNDRED)) throw IllegalArgumentException("percentage $p not in (0, 100]")
    return p
}

/**
 * One part of an edit: put K on a region at a value (an element), put_no (a "no K" element), take (K off: it must
 * then be absent there), take_no (the "no K" elements off).
 */
data class Op(val action: String, val kind: String, val region: Region, val value: Rational = HUNDRED) {
    override fun toString(): String = when (action) {
        "put" -> "put $kind $value% on $region"
        "put_no" -> "put no $kind on $region"
        "take" -> "take $kind off $region"
        else -> "take no $kind off $region"
    }

    companion object {
        fun put(kind: String, where: Region, value: Rational = HUNDRED) = Op("put", kind, where, percent(value))
        fun putNo(kind: String, where: Region) = Op("put_no", kind, where)
        fun take(kind: String, where: Region) = Op("take", kind, where)
        fun takeNo(kind: String, where: Region) = Op("take_no", kind, where)
    }
}

/** Measured, with the rationals of its two ends. */
const val BYTES_PER_INTERVAL = 320

/** With its claims. */
const val BYTES_PER_EDIT = 1500

/** An edit of the user as the history keeps it: what it touches of the line. */
interface Footprinted {
    val footprint: Region
}

/**
 * The user's edits, oldest first, that know which ones touch a stretch of the line. The index only grows and is shared
 * by the histories that come from one another: an entry counts if its edit is at its rank in this one.
 */
class Edits<R : Footprinted> private constructor(
    val list: List<R>,
    private val buckets: HashMap<Long, MutableList<Pair<Int, R>>>,
    private val wide: MutableList<Pair<Int, R>>,
) : List<R> by list {

    constructor(edits: List<R> = emptyList()) : this(edits.toList(), HashMap(), ArrayList()) {
        note(0)
    }

    operator fun plus(more: List<R>): Edits<R> {
        val out = Edits(list + more, buckets, wide)
        out.note(list.size)
        return out
    }

    private fun note(start: Int) {
        for (i in start until list.size) {
            val found = bucketsOf(list[i].footprint)
            if (found == null) wide.add(i to list[i]) else for (b in found) buckets.getOrPut(b) { ArrayList() }.add(i to list[i])
        }
    }

    private fun bucketsOf(where: Region): Set<Long>? {
        if (where.left.isNotEmpty() || where.right.isNotEmpty()) return null
        val out = HashSet<Long>()
        for (iv in where.core.intervals) {
            val first = (iv.lo.x / Rational.of(BUCKET)).floor()
            val last = (iv.hi.x / Rational.of(BUCKET)).floor()
            if (out.size + last - first >= MAX_BUCKETS) return null
            for (b in first..last) out.add(b)
        }
        return out
    }

    /** The edits that touch [where], oldest first. */
    fun touching(where: Region): List<R> {
        val found = bucketsOf(where) ?: return list.filter { (it.footprint and where).isNotEmpty() }
        val near = HashSet<Int>()
        for (b in found) for ((i, r) in buckets[b].orEmpty()) if (i < list.size && list[i] === r) near.add(i)
        for ((i, r) in wide) if (i < list.size && list[i] === r) near.add(i)
        return near.sorted().map { list[it] }.filter { (it.footprint and where).isNotEmpty() }
    }

    override fun equals(other: Any?): Boolean = other is Edits<*> && list == other.list

    override fun hashCode(): Int = list.hashCode()

    companion object {
        /** Units of the line. */
        const val BUCKET = 64L

        /** An edit over more goes through every search ([wide]). */
        const val MAX_BUCKETS = 64L
    }
}

class Claims {
    /** kind -> (value, owner) -> region. */
    var pos: MutableMap<String, MutableMap<Pair<Rational, String>, Region>> = LinkedHashMap()

    /** kind -> owner -> region of "no K". */
    var neg: MutableMap<String, MutableMap<String, Region>> = LinkedHashMap()

    /** "off K": removed by the user. */
    var taken: MutableMap<String, MutableMap<String, Region>> = LinkedHashMap()

    /** All the user's edits apply: none gave way through the rules. */
    var clean = false

    /** These claims on [where] only. */
    fun on(where: Region): Claims {
        val out = Claims()
        fun <K> keep(theirs: Map<String, Map<K, Region>>, mine: MutableMap<String, MutableMap<K, Region>>) {
            for ((k, d) in theirs) {
                val kept = LinkedHashMap<K, Region>()
                for ((key, r) in d) {
                    val x = r and where
                    if (x.isNotEmpty()) kept[key] = x
                }
                if (kept.isNotEmpty()) mine[k] = kept
            }
        }
        keep(pos, out.pos)
        keep(neg, out.neg)
        keep(taken, out.taken)
        return out
    }

    fun copy(): Claims {
        val c = Claims()
        c.clean = clean
        c.pos = pos.mapValuesTo(LinkedHashMap()) { LinkedHashMap(it.value) }
        c.neg = neg.mapValuesTo(LinkedHashMap()) { LinkedHashMap(it.value) }
        c.taken = taken.mapValuesTo(LinkedHashMap()) { LinkedHashMap(it.value) }
        return c
    }

    fun kinds(): List<String> = (pos.keys + neg.keys + taken.keys).toSortedList()

    fun isEmpty(): Boolean = listOf(pos.values, neg.values, taken.values).all { s -> s.all { d -> d.values.all { it.isEmpty() } } }

    fun positive(kind: String): Region = union(pos[kind].orEmpty().values)

    fun negative(kind: String): Region = union(neg[kind].orEmpty().values)

    fun off(kind: String): Region = union(taken[kind].orEmpty().values)

    fun owned(kind: String, owner: String): Region = union(pos[kind].orEmpty().filter { it.key.second == owner }.values)

    fun byValue(kind: String): Map<Rational, Region> {
        val out = LinkedHashMap<Rational, Region>()
        for ((key, r) in pos[kind].orEmpty()) out[key.first] = (out[key.first] ?: Region.EMPTY) or r
        return out
    }

    fun problem(extraNeg: Map<String, Region>? = null): Problem {
        val n = LinkedHashMap<String, Region>()
        for (k in neg.keys + taken.keys) n[k] = negative(k) or off(k)
        for ((k, r) in extraNeg.orEmpty()) n[k] = (n[k] ?: Region.EMPTY) or r
        return Problem(pos.keys.associateWith { byValue(it) }, n)
    }

    // writing (no rule is applied here: see the resolve layer)

    private fun <K> removeFrom(d: MutableMap<K, Region>?, region: Region) {
        if (d == null) return
        for (key in d.keys.toList()) {
            val r = d.getValue(key) - region
            if (r.isEmpty()) d.remove(key) else d[key] = r
        }
    }

    fun removePos(kind: String, region: Region) = removeFrom(pos[kind], region)

    fun removeNeg(kind: String, region: Region) = removeFrom(neg[kind], region)

    fun removeTaken(kind: String, region: Region) = removeFrom(taken[kind], region)

    fun addPos(kind: String, region: Region, value: Rational, owner: String) {
        removePos(kind, region)
        removeNeg(kind, region)
        removeTaken(kind, region)
        val d = pos.getOrPut(kind) { LinkedHashMap() }
        val key = value to owner
        d[key] = (d[key] ?: Region.EMPTY) or region
    }

    fun addNeg(kind: String, region: Region, owner: String) {
        removePos(kind, region)
        removeNeg(kind, region)
        val d = neg.getOrPut(kind) { LinkedHashMap() }
        d[owner] = (d[owner] ?: Region.EMPTY) or region
    }

    fun addTaken(kind: String, region: Region, owner: String) {
        val d = taken.getOrPut(kind) { LinkedHashMap() }
        d[owner] = (d[owner] ?: Region.EMPTY) or region
    }

    /** These claims and the other's. */
    fun merged(other: Claims): Claims {
        val c = copy()
        fun <K> add(mine: MutableMap<String, MutableMap<K, Region>>, theirs: Map<String, Map<K, Region>>) {
            for ((k, d) in theirs) for ((key, r) in d) {
                val m = mine.getOrPut(k) { LinkedHashMap() }
                m[key] = (m[key] ?: Region.EMPTY) or r
            }
        }
        add(c.pos, other.pos)
        add(c.neg, other.neg)
        add(c.taken, other.taken)
        return c
    }

    fun same(other: Claims): Boolean {
        fun <K> eq(a: Map<String, Map<K, Region>>, b: Map<String, Map<K, Region>>): Boolean {
            for (k in a.keys + b.keys) {
                val x = a[k].orEmpty()
                val y = b[k].orEmpty()
                for (key in x.keys + y.keys) if ((x[key] ?: Region.EMPTY) != (y[key] ?: Region.EMPTY)) return false
            }
            return true
        }
        return eq(pos, other.pos) && eq(neg, other.neg) && eq(taken, other.taken)
    }

    override fun toString(): String {
        val out = ArrayList<String>()
        for (k in pos.keys.sorted()) {
            for ((key, r) in pos.getValue(k).entries.sortedWith(compareBy({ it.key.first }, { it.key.second }))) {
                out.add("$k ${key.first}% on $r (${key.second})")
            }
        }
        for (k in neg.keys.sorted()) for ((o, r) in neg.getValue(k).entries.sortedBy { it.key }) out.add("no $k on $r ($o)")
        for (k in taken.keys.sorted()) for ((o, r) in taken.getValue(k).entries.sortedBy { it.key }) out.add("off $k on $r ($o)")
        return if (out.isEmpty()) "(no claims)" else out.joinToString("\n")
    }
}

private fun Set<String>.toSortedList(): List<String> = sorted()

class LineState(var claims: Claims, var line: Line, var history: List<Footprinted> = emptyList()) {

    fun copy(): LineState = LineState(claims.copy(), line, history)

    /** About what it takes, in bytes: each interval is kept in the line, the claims and the edits it comes from. */
    fun memory(): Long = BYTES_PER_INTERVAL.toLong() * regions().sumOf { pieces(it) } + BYTES_PER_EDIT.toLong() * history.size

    private fun regions(): List<Region> {
        val out = ArrayList<Region>()
        for (d in line.vals.values) out.addAll(d.values)
        for (d in claims.pos.values) out.addAll(d.values)
        for (d in claims.neg.values) out.addAll(d.values)
        for (d in claims.taken.values) out.addAll(d.values)
        return out
    }

    /** Where the intervals that end before [c] start, of every kind and claim, in order, each position once. */
    fun startsBefore(c: Pos): List<Pos> {
        val out = ArrayList<Pos>()
        for (x in regions().flatMap { r -> r.core.intervals.filter { it.hi < c }.map { it.lo } }.sorted()) {
            if (out.isEmpty() || out.last() != x) out.add(x)
        }
        return out
    }

    companion object {
        fun empty(): LineState = LineState(Claims(), Line(emptyMap()))
    }
}

/** The intervals a region keeps: a lazy one those of what it is made of, each operand counted as the reference does. */
private fun pieces(r: Region): Long {
    if (r is Lazy) {
        r.computed?.let { return it.core.intervals.size.toLong() }
        // (the reference reads `core` of each operand, which computes a lazy operand whole)
        return listOfNotNull(r.a, r.b).sumOf { x -> if (x is Lazy) x.full().core.intervals.size.toLong() else pieces(x) }
    }
    return r.core.intervals.size.toLong()
}
