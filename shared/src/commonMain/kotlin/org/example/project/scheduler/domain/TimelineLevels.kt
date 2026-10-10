package org.example.project.scheduler.domain

import org.example.project.scheduler.model.HiddenPanel
import org.example.project.scheduler.model.Task
import org.example.project.scheduler.model.TaskId
import org.example.project.scheduler.model.TaskPanel
import org.example.project.scheduler.model.TaskTimeRange

/**
 * `docs/scheduler_input_requirements.md`: *"The timeline has … hidden levels called tm_levels. When a blue/orange
 * outlined block is positioned at t_r, then what was there before is added at t_r to the lowest tm_level where nothing
 * is at t_r. What is in the timeline is the result of the overlap of those tm_levels, applying them from top to
 * bottom, ignoring those that are incompatible with the higher tm_levels."*
 *
 * The blocks a hand placed each stand on a level ([TaskPanel.tmLevel]); a block positioned over others is raised above
 * all of them ([raised]). [settle] is the overlap: the blocks are applied from the highest level down, and the part of
 * one that cannot share its stretch with what is already applied is HIDDEN ([HiddenPanel]) rather than cut away — so
 * it is back the moment what stood over it has gone. Nothing is ever destroyed by placing a block.
 *
 * **Incompatible** is the one question the override rule has always asked ([SchedulerDomain.periodRefuses]) — a
 * period and a task panel cannot share a stretch where the period, or a period the account's combination rules bring
 * with it there ([PeriodKindConfig.closeRegions]), refuses the task — plus one more: under a period of its own kind a
 * period says nothing new, so that part of it is hidden too (*"the included block loses its blue/orange outlines"*).
 * And a task panel positioned over another task's panel hides it (*"Dragging task A into task B hides task B, unless
 * shift is pressed, which makes the two task panels share the width"*, [TaskPanel.tmShare]); two on ONE level — every
 * panel placed before the levels existed — go on sharing the width.
 *
 * The bottom level is the scheduler's: what it lays is derived around the timeline this leaves, by every fill, so it
 * needs no piece kept here. Pure — the reducer's calendar commit is the one caller.
 */
object TimelineLevels {
    /** The hidden pieces wholly behind this much of the past are dropped ([purged]): the frozen-history limit. */
    const val HIDDEN_KEEP_MILLIS: Long = 90L * 24L * 60L * 60L * 1000L

    /** No account keeps more hidden pieces than this ([purged]); the ones ending earliest go first. */
    const val MAX_HIDDEN_PANELS: Int = 500

    class Settled(val panels: List<TaskPanel>, val hidden: List<HiddenPanel>)

    /**
     * Whether [panel] is a block that stands on a level: one a hand placed, with a length. A repeating panel is a
     * pattern, not a block — its occurrences are derived wherever they are asked for — and a break the app conducted
     * is the line's history, so neither is cut by a level nor cuts one.
     */
    fun participates(panel: TaskPanel): Boolean =
        SchedulerDomain.isUserPlaced(panel) && panel.repeat == null && !panel.conductedBreak && !panel.screenBreak &&
            panel.endEpochMillis > panel.startEpochMillis

    /**
     * [panels] with the block [id] raised above every other block — shown or hidden — its span overlaps: *"what was
     * there before is added … to the lowest tm_level where nothing is"*, which is the same order said from the other
     * side. A block already above them all keeps its level, so asking twice changes nothing.
     */
    fun raised(panels: List<TaskPanel>, hidden: List<HiddenPanel>, id: String): List<TaskPanel> {
        val block = panels.firstOrNull { it.id == id }?.takeIf(::participates) ?: return panels
        var top = Int.MIN_VALUE
        for (other in panels) {
            if (other.id == id || !participates(other) || !overlaps(other, block)) continue
            top = maxOf(top, other.tmLevel)
        }
        for (piece in hidden) if (!piece.record && overlaps(piece.panel, block)) top = maxOf(top, piece.panel.tmLevel)
        if (top == Int.MIN_VALUE || block.tmLevel > top) return panels
        return panels.map { if (it.id == id) it.copy(tmLevel = top + 1) else it }
    }

    /**
     * The overlap of the levels over [windows] — the stretches an edit touched — and over every block reaching into
     * them: [panels] and [hidden] with each such block shown where it is compatible with the levels above it and
     * hidden where it is not. Idempotent, and the very lists it was given where nothing moves.
     *
     * [allocate] hands out the id of a piece that needs a new one (a block cut in two); a piece keeps the id it has
     * wherever it can, so settling again writes nothing.
     */
    fun settle(
        panels: List<TaskPanel>,
        hidden: List<HiddenPanel>,
        tasks: Map<TaskId, Task>,
        config: PeriodKindConfig,
        windows: List<TaskTimeRange>,
        allocate: () -> String,
        minLength: Long = SchedulerDomain.MIN_MANUAL_ENTRY_MILLIS,
    ): Settled {
        val same = Settled(panels, hidden)
        if (windows.isEmpty()) return same
        val shown = panels.filter(::participates)
        // A hidden stretch of WORK is no block: the reducer's record pass moves those, and they pass through here.
        @Suppress("NAME_SHADOWING")
        val hidden = hidden.filterNot { it.record }.let { blocks -> if (blocks.size == hidden.size) hidden else blocks }
        val works = same.hidden.filter { it.record }
        if (shown.isEmpty() && hidden.isEmpty()) return same

        // The blocks the edit can reach: everything touching the windows, and everything touching those.
        var reach = SchedulerDomain.mergeOccupied(windows.filter { it.endEpochMillis > it.startEpochMillis })
        if (reach.isEmpty()) return same
        val inShown = LinkedHashSet<String>()
        val inHidden = LinkedHashSet<String>()
        while (true) {
            var grew = false
            for (p in shown) if (p.id !in inShown && touches(p, reach)) { inShown += p.id; grew = true }
            for (h in hidden) if (h.id !in inHidden && touches(h.panel, reach)) { inHidden += h.id; grew = true }
            if (!grew) break
            reach = SchedulerDomain.mergeOccupied(
                reach + shown.filter { it.id in inShown }.map(::spanOf) + hidden.filter { it.id in inHidden }.map { spanOf(it.panel) },
            )
        }
        if (inShown.isEmpty() && inHidden.isEmpty()) return same

        // Each block as the user stated it: its pieces, shown and hidden, joined back where they touch.
        class Member(val panel: TaskPanel, val isShown: Boolean)
        class Stated(val template: TaskPanel, val range: TaskTimeRange, val members: List<Member>)
        val members = shown.filter { it.id in inShown }.map { Member(it, true) } + hidden.filter { it.id in inHidden }.map { Member(it.panel, false) }
        val stated = ArrayList<Stated>()
        for ((_, group) in members.groupBy { listOf(it.panel.tmBlockId, it.panel.tmLevel, it.panel.taskId?.value, it.panel.restrictiveKind) }) {
            var run = ArrayList<Member>()
            var end = Long.MIN_VALUE
            fun close() {
                if (run.isEmpty()) return
                val template = (run.firstOrNull { it.isShown } ?: run.first()).panel
                stated += Stated(template, TaskTimeRange(run.minOf { it.panel.startEpochMillis }, run.maxOf { it.panel.endEpochMillis }), run)
                run = ArrayList()
            }
            for (m in group.sortedBy { it.panel.startEpochMillis }) {
                if (run.isNotEmpty() && m.panel.startEpochMillis > end) close()
                run += m
                end = maxOf(end, m.panel.endEpochMillis)
            }
            close()
        }
        // From the top level down; on one level, what is shown today first, then by start.
        stated.sortWith(
            compareByDescending<Stated> { it.template.tmLevel }
                .thenByDescending { s -> s.members.any { it.isShown } }
                .thenBy { it.range.startEpochMillis }
                .thenBy { it.template.tmBlockId },
        )

        val periods = HashMap<String, List<TaskTimeRange>>()
        class TaskRun(val panel: TaskPanel, val span: TaskTimeRange)
        val taskRuns = ArrayList<TaskRun>()
        val usedIds = HashSet<String>()
        panels.forEach { if (it.id !in inShown) usedIds += it.id }
        hidden.forEach { if (it.id !in inHidden) usedIds += it.id }
        val newShown = ArrayList<TaskPanel>()
        val newHidden = ArrayList<TaskPanel>()

        for (s in stated) {
            val whole = listOf(s.range)
            val kind = s.template.restrictiveKind
            var visible: List<TaskTimeRange> =
                if (kind.isBlank()) {
                    // A task panel: hidden wherever a period in force above it refuses its task, and under another
                    // task's panel of a higher level that was not positioned to share the width.
                    val closed = if (periods.isEmpty()) emptyMap() else config.closeRegions(periods)
                    val refusing = closed.filterKeys { SchedulerDomain.periodRefuses(tasks, s.template.taskId, it) }.values.flatten()
                    val over =
                        taskRuns.filter {
                            it.panel.tmLevel > s.template.tmLevel && !it.panel.tmShare &&
                                it.panel.taskId != s.template.taskId && it.panel.tmBlockId != s.template.tmBlockId
                        }.map { it.span }
                    SchedulerDomain.subtractRegions(whole, refusing + over)
                } else {
                    // A period: hidden under a period of its own kind, and wherever it would refuse a task above it.
                    val own = SchedulerDomain.subtractRegions(whole, periods[kind].orEmpty())
                    val over = taskRuns.filter { run -> own.any { it.startEpochMillis < run.span.endEpochMillis && run.span.startEpochMillis < it.endEpochMillis } }
                    if (own.isEmpty() || over.isEmpty()) own
                    else {
                        val closed = config.closeRegions(periods + (kind to SchedulerDomain.mergeOccupied(periods[kind].orEmpty() + own)))
                        val bad = ArrayList<TaskTimeRange>()
                        for (run in over) {
                            for ((k, where) in closed) {
                                if (SchedulerDomain.periodRefuses(tasks, run.panel.taskId, k)) bad += SchedulerDomain.intersectRegions(listOf(run.span), where)
                            }
                        }
                        SchedulerDomain.subtractRegions(own, bad)
                    }
                }
            // A sliver left standing by a cut is no block: it waits with the rest of what is hidden.
            if (visible != whole) visible = visible.filter { it.endEpochMillis - it.startEpochMillis >= minLength }
            val covered = SchedulerDomain.subtractRegions(whole, visible)
            if (kind.isBlank()) visible.forEach { taskRuns += TaskRun(s.template, it) }
            else if (visible.isNotEmpty()) periods[kind] = SchedulerDomain.mergeOccupied(periods[kind].orEmpty() + visible)

            val block = s.template.tmBlockId
            fun idFor(range: TaskTimeRange, preferShown: Boolean): String {
                fun pick(shownSide: Boolean): String? =
                    s.members
                        .filter {
                            it.isShown == shownSide && it.panel.id !in usedIds &&
                                it.panel.startEpochMillis < range.endEpochMillis && range.startEpochMillis < it.panel.endEpochMillis
                        }
                        // The block's own id first, so a block made whole again is the object it was.
                        .maxWithOrNull(
                            compareBy<Member> { it.panel.id == block }
                                .thenBy { minOf(it.panel.endEpochMillis, range.endEpochMillis) - maxOf(it.panel.startEpochMillis, range.startEpochMillis) },
                        )
                        ?.panel?.id
                val id = pick(preferShown) ?: block.takeIf { it !in usedIds } ?: pick(!preferShown) ?: generateSequence(allocate).first { it !in usedIds }
                usedIds += id
                return id
            }
            fun piece(range: TaskTimeRange, id: String): TaskPanel =
                s.template.copy(
                    id = id,
                    startEpochMillis = range.startEpochMillis,
                    endEpochMillis = range.endEpochMillis,
                    tmOrigin = if (id == block) "" else block,
                )
            for (range in visible) newShown += piece(range, idFor(range, preferShown = true))
            for (range in covered) newHidden += piece(range, idFor(range, preferShown = false))
        }

        // In place where a piece keeps its id, appended where it is new — the panel list's order is part of it.
        val shownById = newShown.associateBy { it.id }
        val outPanels = ArrayList<TaskPanel>(panels.size + newShown.size)
        val placed = HashSet<String>()
        for (p in panels) {
            if (p.id !in inShown) outPanels += p
            else shownById[p.id]?.let { outPanels += it; placed += it.id }
        }
        newShown.forEach { if (it.id !in placed) outPanels += it }
        val hiddenById = newHidden.associateBy { it.id }
        val outHidden = ArrayList<HiddenPanel>(hidden.size + newHidden.size)
        placed.clear()
        for (h in hidden) {
            if (h.id !in inHidden) outHidden += h
            else hiddenById[h.id]?.let { outHidden += if (it == h.panel) h else HiddenPanel(it); placed += it.id }
        }
        newHidden.forEach { if (it.id !in placed) outHidden += HiddenPanel(it) }
        return if (outPanels == panels && outHidden == hidden) same else Settled(outPanels, outHidden + works)
    }

    /**
     * [hidden] within the limits a hidden level is kept to (`docs/scheduler_requirements.md` § *Strict Requirements*,
     * exception 2 — *"a limit may be imposed on the memory for the frozen timeline history"* — and
     * `docs/invariants/server-quota.md`: no rows that grow with editing without a purge): the pieces wholly more than
     * [HIDDEN_KEEP_MILLIS] behind [nowMillis] are dropped, then the earliest-ending past [MAX_HIDDEN_PANELS].
     *
     * A hidden stretch of WORK ([HiddenPanel.record]) is never dropped: it is the frozen past itself, moved out of a
     * task's record and no larger than it was there, so it grows with what was worked, not with editing.
     */
    fun purged(hidden: List<HiddenPanel>, nowMillis: Long): List<HiddenPanel> {
        if (hidden.isEmpty()) return hidden
        val recent = hidden.filter { it.record || it.panel.endEpochMillis > nowMillis - HIDDEN_KEEP_MILLIS }
        val blocks = recent.count { !it.record }
        val kept =
            if (blocks <= MAX_HIDDEN_PANELS) recent
            else {
                val drop =
                    recent.filterNot { it.record }.sortedBy { it.panel.endEpochMillis }.take(blocks - MAX_HIDDEN_PANELS).mapTo(HashSet()) { it.id }
                recent.filterNot { it.id in drop }
            }
        return if (kept.size == hidden.size) hidden else kept
    }

    private fun spanOf(panel: TaskPanel): TaskTimeRange = TaskTimeRange(panel.startEpochMillis, panel.endEpochMillis)

    private fun overlaps(a: TaskPanel, b: TaskPanel): Boolean =
        a.startEpochMillis < b.endEpochMillis && b.startEpochMillis < a.endEpochMillis

    /** Overlapping or abutting: a piece that only touches the stretch may be the other half of a block in it. */
    private fun touches(panel: TaskPanel, ranges: List<TaskTimeRange>): Boolean =
        ranges.any { panel.startEpochMillis <= it.endEpochMillis && it.startEpochMillis <= panel.endEpochMillis }
}
