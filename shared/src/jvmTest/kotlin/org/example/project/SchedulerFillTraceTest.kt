package org.example.project

import java.io.File
import kotlin.test.Test
import org.example.project.scheduler.domain.SchedulerDomain
import org.example.project.scheduler.model.TaskId
import org.example.project.scheduler.persistence.SchedulerStateCodec
import org.example.project.scheduler.state.SchedulerState

/**
 * DEBUG-ONLY tracer for "why did the auto schedule give task X two panels in a row?".
 *
 * Loads your real persisted desktop state (~/.omniapp/scheduler-state.json) and replays the
 * §9 fill loop, printing — for every panel it lays down — each leaf candidate's recent share f
 * and its absolute priority p, the chosen task, and the span. A task is chosen when f <= p
 * (highest priority first). Two consecutive lines naming the same task is the anomaly: read its
 * f vs p on the second line to see why it still qualified (or whether every rival was over-served
 * so the fallback re-picked it).
 *
 * Run just this:  ./gradlew :shared:jvmTest --tests "org.example.project.SchedulerFillTraceTest"
 * Delete this file when done — it is not a real test (it always passes; it only prints).
 */
class SchedulerFillTraceTest {

    private val MIN = 60_000L

    @Test
    fun trace_fill_schedule_decisions() {
        val file = File(System.getProperty("user.home"), ".omniapp/scheduler-state.json")
        if (!file.exists()) {
            println("[trace] no state file at $file — open the desktop app once, or paste your JSON.")
            return
        }
        val state = SchedulerStateCodec.decode(file.readText())
        if (state == null) {
            println("[trace] could not decode $file")
            return
        }
        // Use real 'now'. To reproduce a specific moment, hard-code an epoch-millis here instead.
        val now = System.currentTimeMillis()
        traceFill(state, now)
    }

    /** Mirrors SchedulerDomain.fillSchedule's loop, but logs every decision instead of just panels. */
    private fun traceFill(state: SchedulerState, now: Long) {
        val absolute = SchedulerDomain.absoluteTaskPriorities(state)
        fun pct(t: TaskId) = ((absolute[t] ?: 0.0) * 100).let { "%.1f%%".format(it) }
        fun title(t: TaskId) = state.tasks[t]?.title ?: t.value

        val horizon = now + SchedulerDomain.SCHEDULE_HORIZON_MILLIS
        val current = SchedulerDomain.panelAt(state.panels, now)
        val kept = state.panels.filter {
            SchedulerDomain.isSchedulerFixed(it) || it === current ||
                it.endEpochMillis <= now || it.startEpochMillis > horizon
        }
        var working = state.copy(panels = kept)
        var cursor = SchedulerDomain.firstFreeMoment(kept, now)

        println("=== fill trace @ now=$now (kept ${kept.size} panels) ===")
        var prevTask: TaskId? = null
        var i = 0
        while (cursor < horizon && i < 200) {
            val covering = kept.firstOrNull {
                SchedulerDomain.isSchedulerFixed(it) && it.startEpochMillis <= cursor && cursor < it.endEpochMillis
            }
            if (covering != null) { cursor = covering.endEpochMillis; continue }

            val workingPeriods = SchedulerDomain.workingPeriods(working, cursor)
            val candidates = working.tasks.keys
                .filter {
                    !SchedulerDomain.isRootTask(it) && !SchedulerDomain.isMainTask(it) &&
                        SchedulerDomain.taskHasCells(working, it) && SchedulerDomain.isLeafTask(working, it) &&
                        working.tasks[it]?.title?.isNotBlank() == true
                }
                .sortedWith(
                    compareByDescending<TaskId> { absolute[it] ?: 0.0 }
                        .thenBy { working.tasks[it]?.title.orEmpty() },
                )
            val chosen = SchedulerDomain.nextTask(working, cursor) ?: break
            val span = SchedulerDomain.scheduledSpanMinutes(working, chosen, cursor)
            val tMin = (cursor - now) / MIN

            val repeat = if (chosen == prevTask) "  <-- SAME AS PREVIOUS" else ""
            println("+${"%5d".format(tMin)}min  pick=${title(chosen)} (p=${pct(chosen)}, span=${span}m)$repeat")
            for (c in candidates) {
                val f = SchedulerDomain.taskRecentShare(working, c, cursor, workingPeriods)
                val p = absolute[c] ?: 0.0
                val mark = when {
                    c == chosen -> " *CHOSEN"
                    f <= p -> " (also qualifies)"
                    else -> " over-served"
                }
                println("           ${title(c).padEnd(20)} f=${"%.3f".format(f)}  p=${"%.3f".format(p)}$mark")
            }

            var end = cursor + span * MIN
            SchedulerDomain.nextPinnedStartAfter(kept, cursor)?.let { end = minOf(end, it) }
            if (end <= cursor) break
            working = working.copy(
                panels = working.panels + org.example.project.scheduler.model.TaskPanel(
                    id = "trace/$i", taskId = chosen, title = title(chosen),
                    startEpochMillis = cursor, endEpochMillis = end, pinned = false, auto = true,
                ),
            )
            prevTask = chosen
            cursor = end
            i++
        }
        println("=== end trace ($i panels) ===")
    }
}
