package org.example.project.scheduler.domain

import kotlin.time.Instant
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.plus
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import org.example.project.scheduler.model.TaskPanel

/**
 * `docs/scheduler_requirements.md` § *Priority, Granularity and Compensation*: *"The timeline is infinite forward and
 * backward, and the pre-placed tasks and restrictive periods can be in infinite patterns."*
 *
 * A panel carrying a [org.example.project.scheduler.model.PanelRepeat] is the first occurrence of a pattern; every
 * later one is DERIVED here, for whatever window is asked about — the fill's, the recurrence bars', the calendar's —
 * and never stored or synced. They are the one funnel for "where does this pattern put its occurrences", so the plan,
 * the breaks and the calendar cannot disagree about it.
 */
object PanelRepeats {
    /** The id prefix of a derived occurrence: `repeat/{base id}/{k}`, `k >= 1` (the base panel is occurrence 0). */
    const val OCCURRENCE_ID_PREFIX: String = "repeat/"

    /** Whether [panel] is a derived occurrence of a repeating panel. */
    fun isOccurrence(panel: TaskPanel): Boolean = panel.id.startsWith(OCCURRENCE_ID_PREFIX)

    /** The id of the panel an occurrence was derived from, or null when [id] is not an occurrence's. */
    fun baseIdOf(id: String): String? =
        if (!id.startsWith(OCCURRENCE_ID_PREFIX)) null else id.removePrefix(OCCURRENCE_ID_PREFIX).substringBeforeLast('/')

    /**
     * The occurrences of [base]'s pattern after the first that overlap `[fromMillis, toMillis)`: each one `k *
     * everyDays` local days after the first, at the same local time of day (so a daily period stays at its hour
     * across a daylight-saving change), as long as the first one, and none starting at or after `untilMillis`.
     */
    fun occurrences(base: TaskPanel, fromMillis: Long, toMillis: Long, timeZone: TimeZone): List<TaskPanel> {
        val rule = base.repeat ?: return emptyList()
        if (rule.everyDays <= 0 || toMillis <= fromMillis || isOccurrence(base)) return emptyList()
        val duration = base.endEpochMillis - base.startEpochMillis
        if (duration <= 0) return emptyList()
        val first = Instant.fromEpochMilliseconds(base.startEpochMillis).toLocalDateTime(timeZone)
        val stepMillis = rule.everyDays * DAY_MILLIS
        // The range of k whose occurrence can overlap the window, widened by one on each side for the hour a
        // daylight-saving change can move an occurrence by.
        val kFrom = maxOf(1L, (fromMillis - duration - base.startEpochMillis).floorDiv(stepMillis) - 1)
        val kTo = (toMillis - base.startEpochMillis).floorDiv(stepMillis) + 1
        val out = ArrayList<TaskPanel>()
        var k = kFrom
        while (k <= kTo) {
            val date = first.date.plus(DatePeriod(days = (k * rule.everyDays).toInt()))
            val start = LocalDateTime(date, first.time).toInstant(timeZone).toEpochMilliseconds()
            if (rule.untilMillis != null && start >= rule.untilMillis) break
            val end = start + duration
            if (end > fromMillis && start < toMillis) {
                out +=
                    base.copy(
                        id = "$OCCURRENCE_ID_PREFIX${base.id}/$k",
                        startEpochMillis = start,
                        endEpochMillis = end,
                        repeat = null,
                        auto = false,
                        alternativeTaskId = null,
                        alternativeSpans = emptyList(),
                    )
            }
            k++
        }
        return out
    }

    /** Occurrence [k] of [base]'s pattern (`k >= 1`), or null when it has none (ended, or no pattern). */
    fun occurrence(base: TaskPanel, k: Long, timeZone: TimeZone): TaskPanel? {
        val rule = base.repeat ?: return null
        if (k < 1 || rule.everyDays <= 0) return null
        val approx = base.startEpochMillis + k * rule.everyDays * DAY_MILLIS
        return occurrences(base, approx - DAY_MILLIS, approx + DAY_MILLIS, timeZone)
            .firstOrNull { it.id == "$OCCURRENCE_ID_PREFIX${base.id}/$k" }
    }

    /** The occurrence number an occurrence's id carries, or null. */
    fun indexOf(id: String): Long? = if (!id.startsWith(OCCURRENCE_ID_PREFIX)) null else id.substringAfterLast('/').toLongOrNull()

    /**
     * [panels] with every repeating panel's occurrences over `[fromMillis, toMillis)` derived beside it — and any
     * occurrence a previous expansion left in the list dropped first, so asking twice never doubles them. The same
     * list when nothing repeats.
     */
    fun expand(panels: List<TaskPanel>, fromMillis: Long, toMillis: Long, timeZone: TimeZone): List<TaskPanel> {
        if (panels.none { it.repeat != null || isOccurrence(it) }) return panels
        val own = panels.filterNot(::isOccurrence)
        return own + own.filter { it.repeat != null }.flatMap { occurrences(it, fromMillis, toMillis, timeZone) }
    }

    private const val DAY_MILLIS: Long = 24L * 60L * 60L * 1000L
}
