package org.example.project.scheduler.domain

/**
 * `side-dev/README.md` *Restrictive Period*: **a restrictive period is a start, an end and a KIND**, and
 * *"each task has a resilience value for each kind of restrictive period from 0 to 1. It is a multiplier for
 * the task's priority percentage during that restrictive period."*
 *
 * That one sentence replaces every boolean the app used to carry about where a task may run. A resilience of
 * `0` forbids the task inside the period, `1` leaves it untouched, and anything between scales its share for
 * as long as the period lasts — so "on screen" is not a flag: it is exactly **a resilience of 0 to the kind
 * [NO_SCREEN]**, read through [resilienceFor] like every other kind.
 *
 * Two kinds are built in because the README names them:
 * - [NO_TASK] — *"no task allowed"*, the kind of the three dynamic restrictive periods (§ *3 Dynamic
 *   Restrictive Period*) and of the app's grey regions (an inactivity period, a §17 sleep window). Its
 *   default resilience is `0` — by its own name it accepts nobody — and it is the one kind a task may not be
 *   given a value for at all ([isResilienceEditable]).
 * - [NO_SCREEN] — *"no on-screen task"*, the kind the two `t_p` modes and all three recurrence bars are
 *   written in terms of.
 *
 * **Every other kind is the user's** ([org.example.project.scheduler.state.SchedulerState.periodKinds]),
 * defined by the task edit window's `+`. **A kind the user has just defined is added to every task at the
 * default value `0`** — a restrictive period restricts, so a new one turns everybody away until the period's
 * own edit window hands somebody a value above zero. Nothing is written to a single task to say so: absence
 * *is* the default ([defaultResilience]), exactly as `shortcutBindings` holds overrides only, which is what
 * makes defining a kind free however many tasks the account holds and what makes a task created *later*
 * carry the same answer as the ones that were there.
 *
 * [NO_SCREEN] is the one kind whose default is `1`, and it has to be: "on screen" is a `0` against it
 * ([org.example.project.scheduler.model.Task.DEFAULT_RESILIENCE]), so an off-screen task is exactly one that
 * never overrode it.
 */
object PeriodKinds {
    /** `side-dev/README.md`'s "no task allowed" — the kind of the three dynamic periods and of grey. */
    const val NO_TASK: String = "no task allowed"

    /** `side-dev/README.md`'s "no on-screen task" — what the modes and the recurrence bars are written in. */
    const val NO_SCREEN: String = "no on-screen task"

    /** The two kinds the README itself names; the rest of the list is the account's. */
    val BUILT_IN: List<String> = listOf(NO_TASK, NO_SCREEN)

    /**
     * The resilience a task that was never told about [kind] has to it: **`0` for every kind but
     * [NO_SCREEN]**.
     *
     * That is what a restrictive period *is* — [NO_TASK] accepts nobody by its own name, and a kind the user
     * has just defined accepts nobody either until its edit window says otherwise, which is what "adding a
     * period adds it to every task with the default value 0" means. The one exception is [NO_SCREEN]: an
     * on-screen task is exactly a `0` against it ([org.example.project.scheduler.model.Task.DEFAULT_RESILIENCE]),
     * so its default has to be the *other* answer or an off-screen task would be the one that has to say so.
     */
    fun defaultResilience(kind: String): Double = if (kind == NO_SCREEN) 1.0 else 0.0

    /**
     * Whether a task may be given a resilience to [kind] **at all**.
     *
     * [NO_TASK] is the one kind it may not: *"no task allowed"* says in its own name that it accepts nobody,
     * so its multiplier is always `0` and there is nothing for a task to choose. Every other kind — [NO_SCREEN]
     * and every kind the user defines — is an ordinary editable value.
     *
     * This is a rule about the EDIT WINDOW, not about [resilienceFor]: the map is still read for [NO_TASK]
     * everywhere (that is how a grey period refuses everybody), and an override an older payload wrote is
     * still honoured. What is gone is the row that offered to write one.
     */
    fun isResilienceEditable(kind: String): Boolean = kind != NO_TASK

    /** A resilience is a multiplier in `[0, 1]`; anything outside is healed to the nearest bound. */
    fun clamp(value: Double): Double = if (value.isNaN()) 1.0 else value.coerceIn(0.0, 1.0)

    /**
     * The resilience of a task carrying [overrides] to [kind] — an override if it has one, else
     * [defaultResilience]. The single reading of a resilience map in the whole app: the plan layer, the fill,
     * the calendar and the edit window all ask through here, so none of them can invent a different default.
     */
    fun resilienceFor(overrides: Map<String, Double>, kind: String): Double =
        overrides[kind]?.let { clamp(it) } ?: defaultResilience(kind)

    /**
     * The product of every covering kind's resilience — `side-dev/scheduler.py` `Environment.multiplier`.
     * Overlapping periods MULTIPLY, so the strictest of them still forbids ("Multiple restrictive periods can
     * appear at a given time t").
     */
    fun multiplier(overrides: Map<String, Double>, kinds: Collection<String>): Double {
        var m = 1.0
        for (kind in kinds) {
            m *= resilienceFor(overrides, kind)
            if (m <= 0.0) return 0.0
        }
        return m
    }

    /**
     * Whether a stretch of [kind] is one the two `t_p` modes and the recurrence bars are written about — the
     * README says *"covered by the period 'no on-screen task'"*, and [NO_TASK] covers it a fortiori (a period
     * that turns everybody away turns the on-screen tasks away too). That is exactly why the three dynamic
     * periods, whose kind is [NO_TASK], are the ones the modes govern.
     */
    fun coversNoScreen(kind: String): Boolean = kind == NO_TASK || kind == NO_SCREEN

    /** A user-defined kind is any that is not one of the two the README names. Blank names are refused. */
    fun isUserDefined(kind: String): Boolean = kind.isNotBlank() && kind !in BUILT_IN

    /** Trim + collapse whitespace, so "  deep   work " and "deep work" are one kind and not two. */
    fun normalize(raw: String): String = raw.trim().split(WHITESPACE).filter { it.isNotEmpty() }.joinToString(" ")

    private val WHITESPACE = Regex("""\s+""")
}
