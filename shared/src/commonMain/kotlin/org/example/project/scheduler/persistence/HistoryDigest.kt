package org.example.project.scheduler.persistence

/**
 * The digest of a History Unit's serialized delta: its length and a 64-bit content hash.
 *
 * It exists so a save can answer **"is the unit sitting in this row still the same unit?"** without reading
 * the row's `delta` back. That question is the whole of the incremental history write
 * ([SqlDelightSchedulerStore.save]), and answering it by comparing delta TEXT would reload every byte the
 * rewrite existed to avoid — tens of MB on a mature account, on every keystroke's save debounce.
 *
 * A History Unit is **immutable once committed**, so the digest is computed once, with the unit, and stored
 * beside it. [org.example.project.scheduler.state.HistoryUnit] memoizes it (see
 * [SchedulerStateCodec.encodedDeltaOf]), so a save that adds one unit hashes exactly one delta.
 *
 * The identity a save compares is `(timeMillis, chronoId, debugTainted, length, hash)` — never the hash
 * alone. `chronoId` already distinguishes units sharing a millisecond, so a false match would need two
 * DIFFERENT deltas committed in the same millisecond, at the same tie-break index, with the same taint flag,
 * the same length AND a 64-bit hash collision. A false NON-match costs nothing but a rewrite of that row.
 */
internal object HistoryDigest {
    /** 0 means "not computed / not known" — every caller recomputes rather than trusting it. */
    const val UNKNOWN_HASH: Long = 0

    /** -1 means "not known", which is what a row carried up from a pre-v11 DB holds. */
    const val UNKNOWN_LENGTH: Long = -1

    private const val FNV_OFFSET_BASIS: Long = -3750763034362895579L // 14695981039346656037 unsigned
    private const val FNV_PRIME: Long = 1099511628211L

    /**
     * FNV-1a over the string's UTF-16 units, split into bytes so the result does not depend on the
     * platform's char handling. Chosen over [String.hashCode] because 32 bits is too narrow to carry the
     * identity of a 380 KB delta, and over a cryptographic hash because this guards a local reuse decision,
     * not a trust boundary. It is persisted, so **it must never change**: a different function would make
     * every stored row read as "a different unit" and force one full rewrite.
     */
    fun hash(deltaJson: String): Long {
        var hash = FNV_OFFSET_BASIS
        for (index in deltaJson.indices) {
            val char = deltaJson[index].code
            hash = (hash xor (char and 0xFF).toLong()) * FNV_PRIME
            hash = (hash xor ((char ushr 8) and 0xFF).toLong()) * FNV_PRIME
        }
        // Never hand back the sentinel: a real hash of 0 would read as "unknown" and cost a rewrite.
        return if (hash == UNKNOWN_HASH) FNV_PRIME else hash
    }
}

/**
 * A History Unit's delta, serialized once and kept with its [hash].
 *
 * Held by [org.example.project.scheduler.state.HistoryUnit] as a memo of an immutable value: the same unit
 * object is carried by reference through every state copy the reducer makes, so the JSON and the hash are
 * produced once per unit and reused by every later save, fingerprint and push. Seeded on load from the row
 * just read, so a launch re-serializes nothing either.
 */
internal class EncodedDelta(val json: String, val hash: Long)
