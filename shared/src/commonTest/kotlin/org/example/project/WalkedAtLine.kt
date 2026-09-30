package org.example.project

import org.example.project.scheduler.domain.DynamicPeriods
import org.example.project.scheduler.domain.FrozenScreenBreaks
import org.example.project.scheduler.domain.PlanBlock
import org.example.project.scheduler.domain.PlanTask
import org.example.project.scheduler.domain.RestrictivePeriod
import org.example.project.scheduler.domain.SchedulerDomain
import org.example.project.scheduler.model.ScreenBreak
import org.example.project.scheduler.model.TaskPanel

/**
 * The three dynamic periods **the walk at the line** places over `[fromMillis, toMillis]` — what the rules say, for
 * tests about the placement itself. It is NOT what the calendar draws behind the line: that is the banked record
 * alone ([SchedulerDomain.takenScreenBreakPanels], `docs/scheduler_requirements.md` § *frozen past*).
 */
internal fun walkedAtLine(
    screenBreaks: List<ScreenBreak>,
    fromMillis: Long,
    toMillis: Long,
    basePeriods: List<RestrictivePeriod> = emptyList(),
    blocks: List<PlanBlock> = emptyList(),
    tasks: List<PlanTask> = emptyList(),
    anchorMillis: Long = toMillis,
    tpMillis: Long = toMillis,
    mode: Int = DynamicPeriods.MODE_AT_SCREEN,
    frozen: FrozenScreenBreaks? = null,
): List<TaskPanel> =
    SchedulerDomain.dynamicPeriodPanels(
        screenBreaks = screenBreaks,
        fromMillis = fromMillis,
        toMillis = toMillis,
        tpMillis = tpMillis,
        basePeriods = basePeriods,
        blocks = blocks,
        tasks = tasks,
        mode = mode,
        anchorMillis = anchorMillis,
        atLine = true,
        frozen = frozen,
    )
