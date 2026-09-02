package org.example.project.scheduler.domain

import org.example.project.scheduler.model.TaskId
import kotlin.math.roundToInt

/**
 * How alike one task's title is to the titles of the **other** tasks the "All tasks" window lists — the
 * figure [SchedulerDomain.TaskListSort.Similarity] orders that window by (PRD §7).
 *
 * The question the sort answers is "which pieces of work has the user written down twice?", so what matters
 * about a task is its **single closest** neighbour, not its average distance from the tree: a task with one
 * near-duplicate is a duplicate whatever else the account holds. Hence [best] first and [matches] only as the
 * tie-break — among tasks that are all equally close to *something*, the one that is that close to the most
 * things is the one worth looking at first.
 *
 * **The score is a whole percent, and that is load-bearing, not cosmetic.** The order is defined by "the same
 * maximum", and a Dice ratio is a `Double`: two pairs that are alike in exactly the same way would compare
 * unequal at the seventeenth digit, so the tie-break would never fire and [matches] would always be 1.
 * Quantizing makes "the same maximum" a real answer.
 */
data class TitleSimilarity(
    /**
     * The best score against any **other** listed task, in whole percent — `0` when nothing is alike at all
     * (which is the answer for a lone task, and for a title with no letters or digits in it).
     */
    val best: Int,
    /** How many other tasks reach exactly [best]. `0` exactly when [best] is `0`. */
    val matches: Int,
) {
    companion object {
        /** What two titles that normalize to the same text score. */
        const val PERFECT: Int = 100

        /**
         * The similarity of two titles, in whole percent: the **Sørensen–Dice coefficient over character
         * bigrams** of the normalized titles.
         *
         * Bigrams rather than words because the near-duplicates this sort is for are near-*spellings* —
         * `Write report` / `Write reports`, `Maths` / `Math homework` — which a word-set measure calls
         * strangers; and rather than an edit distance because Dice is symmetric, needs no matrix, and is
         * insensitive to the word order two people writing the same task down twice rarely agree on.
         *
         * Normalization case-folds, replaces every non-alphanumeric character with a space and collapses the
         * runs, so punctuation and spacing say nothing. Two titles that normalize to the same non-empty text
         * score [PERFECT]; a title with fewer than two characters left (or none at all) has no bigram to
         * match with, so it scores `0` against everything but its own twin.
         */
        fun score(a: String, b: String): Int {
            val na = normalized(a)
            val nb = normalized(b)
            return score(na, bigrams(na), nb, bigrams(nb))
        }

        /**
         * [TitleSimilarity] for every task of [titles], each measured against all the others.
         *
         * One pass over the pairs, filling both sides of each — the relation is symmetric, so asking per task
         * would do the same work twice. It is `O(n²)` in the number of tasks, which is why
         * [SchedulerDomain.taskListEntries] computes it **only** when the similarity sort asks for it (ADR
         * 0009: nothing this size belongs on a path something else walks for free).
         */
        fun of(titles: Map<TaskId, String>): Map<TaskId, TitleSimilarity> {
            val ids = titles.keys.toList()
            val texts = ids.map { normalized(titles[it].orEmpty()) }
            val grams = texts.map { bigrams(it) }
            val best = IntArray(ids.size)
            val matches = IntArray(ids.size)
            for (i in ids.indices) {
                for (j in i + 1 until ids.size) {
                    val s = score(texts[i], grams[i], texts[j], grams[j])
                    // A zero is "not alike", not a tie at zero: it must never make a task count as somebody's
                    // match, or every task in the account would report matches against every other.
                    if (s == 0) continue
                    if (s > best[i]) {
                        best[i] = s
                        matches[i] = 1
                    } else if (s == best[i]) {
                        matches[i]++
                    }
                    if (s > best[j]) {
                        best[j] = s
                        matches[j] = 1
                    } else if (s == best[j]) {
                        matches[j]++
                    }
                }
            }
            return ids.indices.associate { ids[it] to TitleSimilarity(best[it], matches[it]) }
        }

        /** The scoring itself, over titles already normalized and their (sorted) bigrams. */
        private fun score(a: String, aGrams: IntArray, b: String, bGrams: IntArray): Int {
            if (a.isEmpty() || b.isEmpty()) return 0
            if (aGrams.isEmpty() || bGrams.isEmpty()) return if (a == b) PERFECT else 0
            val shared = overlap(aGrams, bGrams)
            return (200.0 * shared / (aGrams.size + bGrams.size)).roundToInt()
        }

        /** Case-folded, alphanumerics only, single-spaced, untrimmed edges removed. */
        private fun normalized(title: String): String {
            val out = StringBuilder(title.length)
            for (ch in title) {
                if (ch.isLetterOrDigit()) {
                    out.append(ch.lowercaseChar())
                } else if (out.isNotEmpty() && out[out.length - 1] != ' ') {
                    out.append(' ')
                }
            }
            while (out.isNotEmpty() && out[out.length - 1] == ' ') out.deleteAt(out.length - 1)
            return out.toString()
        }

        /**
         * The title's character bigrams, each packed into one `Int` and the whole sorted — so the overlap
         * below is a merge rather than a set build, and no `String` is allocated per pair.
         */
        private fun bigrams(text: String): IntArray {
            if (text.length < 2) return IntArray(0)
            val out = IntArray(text.length - 1)
            for (i in 0 until text.length - 1) {
                out[i] = (text[i].code shl 16) or text[i + 1].code
            }
            out.sort()
            return out
        }

        /** Multiset intersection size of two sorted bigram arrays. */
        private fun overlap(a: IntArray, b: IntArray): Int {
            var i = 0
            var j = 0
            var shared = 0
            while (i < a.size && j < b.size) {
                val x = a[i]
                val y = b[j]
                when {
                    x < y -> i++
                    x > y -> j++
                    else -> {
                        shared++
                        i++
                        j++
                    }
                }
            }
            return shared
        }
    }
}
