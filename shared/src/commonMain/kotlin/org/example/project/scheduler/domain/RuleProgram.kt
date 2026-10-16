package org.example.project.scheduler.domain

import org.example.project.scheduler.model.TaskId
import org.example.project.scheduler.model.TaskPanel

/**
 * `docs/scheduler_requirements.md` § *Rule Structure*: **the task side of the set of rules, compiled for a forward
 * cursor.** The scheduler returns its rules as panels; this turns them — once, whenever a new set is returned — into
 * sequential local branches and the instants they change at:
 *
 * - [segments]: the timeline cut at every boundary of a work panel, each piece naming the panel that holds there
 *   (the first of the rules in their own order, as [SchedulerDomain.panelAt] reads them) and the alternative the
 *   rules name for it (§ *Alternative Schedules*, [SchedulerDomain.alternativeTaskAt]);
 * - [elapseInstants]: where a plan panel ends, the trigger for banking what ran;
 * - [windDowns] and [reminders]: the instants the wind-down hour and the reminder tags fall at;
 * - [notices]: the instants a period's kind asks to be told of it ahead (user rule 2026-10-15, [PeriodNotice]).
 *
 * The runtime holds a [Cursor]: as the line moves it only compares the line with the next armed boundary and steps
 * forward — no filtering, sorting or search over the timeline (§ *No Global Lookups*). Compiling is the scheduler's
 * side of the contract and runs when the rules change, never on a tick.
 */
class RuleProgram private constructor(
    /** The panels this program was compiled from: a different list is a different set of rules. */
    val source: List<TaskPanel>,
    val segments: List<Segment>,
    val elapseInstants: LongArray,
    val windDowns: LongArray,
    val reminders: List<TaskPanel>,
    val notices: List<PeriodNotice>,
    /** The per-kind settings the [notices] were read from: other settings are another program. */
    val noticeSource: PeriodKindConfig?,
) {
    /** `[startMillis, endMillis)`: [panel] holds there (null for none), [alternative] runs if it is refused. */
    data class Segment(val startMillis: Long, val endMillis: Long, val panel: TaskPanel?, val alternative: TaskId?)

    /**
     * User rule 2026-10-15: *"a notification at x time before the period"* — due at [atMillis], [leadMillis] before a
     * period of [kind] titled [title] begins at [periodStartMillis]. [id] names it for the once-only de-dupe: the kind,
     * the start and the lead, which a re-plan that keeps the period keeps. [periodEndMillis] is where the stretch of
     * the kind ends (what a notice telling the timeline lists up to, [PeriodKindStyle.tellsTimeline]).
     */
    data class PeriodNotice(
        val atMillis: Long,
        val kind: String,
        val title: String,
        val periodStartMillis: Long,
        val leadMillis: Long,
        val periodEndMillis: Long = periodStartMillis,
    ) {
        val id: String get() = "$kind@$periodStartMillis-$leadMillis"
    }

    companion object {
        fun compile(panels: List<TaskPanel>, config: PeriodKindConfig? = null): RuleProgram {
            val work =
                panels.withIndex().filter { (_, p) ->
                    p.taskId != null && !p.chore && !p.isRestrictivePeriod && p.endEpochMillis > p.startEpochMillis
                }
            val rules =
                panels.filter { it.auto && it.taskId != null && (it.alternativeTaskId != null || it.alternativeSpans.isNotEmpty()) }
                    .sortedBy { it.startEpochMillis }
            // Every instant the answer can change at: the work panels' edges, and where an alternative changes inside
            // a run.
            val cuts =
                buildSet {
                    for ((_, p) in work) {
                        add(p.startEpochMillis)
                        add(p.endEpochMillis)
                    }
                    for (r in rules) {
                        add(r.startEpochMillis)
                        add(r.endEpochMillis)
                        for (span in r.alternativeSpans) add(span.fromMillis)
                    }
                }.sorted()
            val segments = ArrayList<Segment>()
            var w = 0
            var ruleIndex = 0
            val byStart = work.sortedBy { it.value.startEpochMillis }
            val open = ArrayList<IndexedValue<TaskPanel>>()
            for (k in 0 until cuts.size - 1) {
                val from = cuts[k]
                val to = cuts[k + 1]
                while (w < byStart.size && byStart[w].value.startEpochMillis <= from) open += byStart[w++]
                open.removeAll { it.value.endEpochMillis <= from }
                val holding = open.minByOrNull { it.index }?.value
                while (ruleIndex < rules.size && rules[ruleIndex].endEpochMillis <= from) ruleIndex++
                val alternative =
                    rules.getOrNull(ruleIndex)?.let { r ->
                        if (r.startEpochMillis <= from) r.alternativeAt(from) else r.alternativeTaskId
                    }
                segments += Segment(from, to, holding, alternative)
            }
            val elapse =
                panels.asSequence().filter { it.auto && !it.pinned }.map { it.endEpochMillis }.distinct().sorted().toList().toLongArray()
            val windDowns =
                panels.asSequence().filter { it.restrictiveKind == PeriodKinds.BEFORE_BED }.map { it.startEpochMillis }
                    .distinct().sorted().toList().toLongArray()
            val reminders = SchedulerDomain.reminderTagsOf(panels).sortedBy { it.startEpochMillis }
            return RuleProgram(panels, segments, elapse, windDowns, reminders, config?.let { noticesOf(panels, it) }.orEmpty(), config)
        }

        /**
         * The notices [config]'s kinds ask for ([PeriodKindConfig.notices]) before the periods among [panels]. A period
         * is a stretch of ONE kind: where periods of a kind touch or overlap they are one stretch, and only its start is
         * "before the period" — a period laid end to end with the previous one of its kind would otherwise be announced
         * while the user is already in it. Sorted by the instant they are due.
         */
        fun noticesOf(panels: List<TaskPanel>, config: PeriodKindConfig): List<PeriodNotice> {
            val byKind =
                panels.filter { it.isRestrictivePeriod && it.endEpochMillis > it.startEpochMillis }
                    .groupBy { it.restrictiveKind }
                    .filterKeys { config.notices(it).isNotEmpty() }
            val out = ArrayList<PeriodNotice>()
            for ((kind, periods) in byKind) {
                val leads = config.notices(kind)
                // The stretches: (start, end, title of the period that opens it).
                val runs = ArrayList<Triple<Long, Long, String>>()
                for (period in periods.sortedBy { it.startEpochMillis }) {
                    val last = runs.lastOrNull()
                    if (last == null || period.startEpochMillis > last.second) {
                        runs += Triple(period.startEpochMillis, period.endEpochMillis, period.title.ifBlank { PeriodKinds.periodTitle(kind) })
                    } else if (period.endEpochMillis > last.second) {
                        runs[runs.size - 1] = last.copy(second = period.endEpochMillis)
                    }
                }
                for ((start, end, title) in runs) {
                    for (lead in leads) out += PeriodNotice(start - lead, kind, title, start, lead, end)
                }
            }
            return out.sortedWith(compareBy({ it.atMillis }, { -it.leadMillis }))
        }
    }

    /**
     * The runtime's position in a [RuleProgram]: a forward cursor. [moveTo] only ever steps forward from where it
     * stands — a line that moves continuously never needs to look back — and a cursor handed a program compiled later
     * is placed once, by the compile, not by the line.
     */
    class Cursor(val program: RuleProgram, atMillis: Long) {
        private var segment = firstIndex(program.segments.size) { program.segments[it].endMillis > atMillis }
        private var elapse = firstIndex(program.elapseInstants.size) { program.elapseInstants[it] > atMillis }
        private var windDown = firstIndex(program.windDowns.size) { program.windDowns[it] > atMillis }
        private var reminder = firstIndex(program.reminders.size) { program.reminders[it].startEpochMillis > atMillis }
        private var notice = firstIndex(program.notices.size) { program.notices[it].atMillis > atMillis }
        var atMillis: Long = atMillis
            private set

        /** The work panel the rules hold at the line, or null. */
        val panel: TaskPanel?
            get() = program.segments.getOrNull(segment)?.takeIf { it.startMillis <= atMillis }?.panel

        /** § *Alternative Schedules*: who runs if the task the rules hold at the line is refused. */
        val alternative: TaskId?
            get() = program.segments.getOrNull(segment)?.alternative

        /** What the line crossed on this move: wind-down starts, reminder tags and notices in `(previous, toMillis]`. */
        data class Crossed(
            val windDowns: List<Long>,
            val reminders: List<TaskPanel>,
            val elapsed: Boolean,
            val notices: List<PeriodNotice> = emptyList(),
        )

        /** Move the line forward to [toMillis] (a position behind the cursor changes nothing). */
        fun moveTo(toMillis: Long): Crossed {
            if (toMillis <= atMillis) return Crossed(emptyList(), emptyList(), false)
            while (segment < program.segments.size && program.segments[segment].endMillis <= toMillis) segment++
            var elapsed = false
            while (elapse < program.elapseInstants.size && program.elapseInstants[elapse] <= toMillis) {
                elapse++
                elapsed = true
            }
            val winds = ArrayList<Long>()
            while (windDown < program.windDowns.size && program.windDowns[windDown] <= toMillis) winds += program.windDowns[windDown++]
            val tags = ArrayList<TaskPanel>()
            while (reminder < program.reminders.size && program.reminders[reminder].startEpochMillis <= toMillis) {
                tags += program.reminders[reminder++]
            }
            val notices = ArrayList<PeriodNotice>()
            while (notice < program.notices.size && program.notices[notice].atMillis <= toMillis) notices += program.notices[notice++]
            atMillis = toMillis
            return Crossed(winds, tags, elapsed, notices)
        }

        /**
         * The next instant this cursor is armed for: a segment boundary, a panel's end, a wind-down, a reminder or a
         * period's notice.
         */
        fun nextTriggerMillis(): Long? =
            listOfNotNull(
                program.segments.getOrNull(segment)?.let { if (it.startMillis > atMillis) it.startMillis else it.endMillis },
                program.elapseInstants.getOrNull(elapse),
                program.windDowns.getOrNull(windDown),
                program.reminders.getOrNull(reminder)?.startEpochMillis,
                program.notices.getOrNull(notice)?.atMillis,
            ).minOrNull()

        /** The next instant a plan panel ends at — the only instants what ran has to be banked at. */
        fun nextElapseMillis(): Long? = program.elapseInstants.getOrNull(elapse)

        private fun firstIndex(size: Int, pred: (Int) -> Boolean): Int {
            var lo = 0
            var hi = size
            while (lo < hi) {
                val mid = (lo + hi) ushr 1
                if (pred(mid)) hi = mid else lo = mid + 1
            }
            return lo
        }
    }
}
