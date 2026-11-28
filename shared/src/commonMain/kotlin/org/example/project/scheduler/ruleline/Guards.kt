package org.example.project.scheduler.ruleline

/**
 * What a generated script may never do, checked on the result of each of its edits — a port of
 * `C:\code\line_manager\scripts\guards.py`:
 *
 * N1  change the left side of the chip: the line and the claims on (-inf, chip) are unchanged;
 * N2  remove (or change) an element added by the user: the user's claims (their removals included) are unchanged.
 *
 * The mode rules' edits act at the chip's position x only: M1, the line and the claims on (-inf, x) are unchanged, and
 * the claims outside {x} are unchanged. N2 does not apply to them: the chip's arrival is newer than the user's edits.
 */
fun leftSide(chip: Pos): Region = Region.of(Interval.of(Pos.NEG_INF, chip, false, false))

/** A key of a claim: ("pos", kind, (value, owner)), ("neg"/"off", kind, owner). */
data class ClaimKey(val store: String, val kind: String, val key: Any?)

fun claimsOn(c: Claims, region: Region): Map<ClaimKey, Region> {
    val out = LinkedHashMap<ClaimKey, Region>()
    for ((k, d) in c.pos) for ((key, r) in d) {
        val x = r and region
        if (x.isNotEmpty()) out[ClaimKey("pos", k, key)] = x
    }
    for ((store, d0) in listOf("neg" to c.neg, "off" to c.taken)) for ((k, d) in d0) for ((key, r) in d) {
        val x = r and region
        if (x.isNotEmpty()) out[ClaimKey(store, k, key)] = x
    }
    return out
}

fun userClaims(c: Claims): Map<ClaimKey, Region> {
    val out = LinkedHashMap<ClaimKey, Region>()
    for ((k, d) in c.pos) for ((key, r) in d) if (key.second == Owner.USER && r.isNotEmpty()) out[ClaimKey("pos", k, key.first)] = r
    for ((store, d0) in listOf("neg" to c.neg, "off" to c.taken)) for ((k, d) in d0) {
        val r = d[Owner.USER]
        if (r != null && r.isNotEmpty()) out[ClaimKey(store, k, null)] = r
    }
    return out
}

fun <K> sameDict(a: Map<K, Region>, b: Map<K, Region>): Boolean = a.keys == b.keys && a.all { (k, v) -> v == b.getValue(k) }

/** Whether every operation of an edit is after the chip (read without computing a lazy region whole). */
fun afterChip(ops: List<Op>, chip: Pos): Boolean {
    for (o in ops) {
        val r = o.region
        val lb = if (r is Lazy) r.lowerBound() else if (!r.isEmpty()) r.inf() else null
        if (lb != null && !(lb > chip)) return false
    }
    return true
}

/** Null if the edit respects N1 and N2, else the reason. */
fun checkScriptEdit(before: LineState, after: LineState, chip: Pos, ops: List<Op>? = null, rules: RuleSet? = null): String? {
    val left = leftSide(chip)
    val local = ops != null && rules != null && rules.local && afterChip(ops, chip)
    if (!local && (!before.line.sameOn(after.line, left) || !sameDict(claimsOn(before.claims, left), claimsOn(after.claims, left)))) {
        return "N1: would change the line left of the chip ($chip)"
    }
    if (!sameDict(userClaims(before.claims), userClaims(after.claims))) return "N2: would remove or change an element added by the user"
    return null
}

/** Null if an edit of the mode rules on [at] respects M1, else the reason. */
fun checkModeEdit(before: LineState, after: LineState, at: Region, chip: Pos): String? {
    val left = leftSide(chip)
    if (!before.line.sameOn(after.line, left) || !sameDict(claimsOn(before.claims, left), claimsOn(after.claims, left))) {
        return "M1: would change the line left of the chip ($chip)"
    }
    val away = at.complement()
    if (!sameDict(claimsOn(before.claims, away), claimsOn(after.claims, away))) return "M1: would change elements away from the chip"
    return null
}
