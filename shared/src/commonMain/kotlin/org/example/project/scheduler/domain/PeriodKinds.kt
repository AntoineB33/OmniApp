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
 * Five kinds are built in — two because the README names them, one because the app's own §17 sleep
 * schedule lays it, and two because PRD §8's two calendar LAYERS are sentences the user must be able to say
 * as well as read off a lock history:
 * - [NO_TASK] — *"no task allowed"*, the kind of the three dynamic restrictive periods (§ *3 Dynamic
 *   Restrictive Period*) and of the app's grey regions (an inactivity period, a §17 sleep window). Its
 *   default resilience is `0` — by its own name it accepts nobody — and it is the one kind a task may not be
 *   given a value for at all ([isResilienceEditable]).
 * - [NO_SCREEN] — *"no on-screen task"*, the kind the two `t_p` modes and all three recurrence bars are
 *   written in terms of.
 * - [BEFORE_BED] — PRD §17's wind-down: **the hour before bed is covered by the period "before bed"**. It
 *   used to be a hard-coded extension of the sleep obstacle, which is a second mechanism for "where may this
 *   task run" and therefore the mistake this model exists to prevent. As a KIND it is one period like any
 *   other: its default `0` is what keeps the hour empty, and a task the user gives a non-zero resilience to
 *   it works through the wind-down without any rule of its own. It is deliberately NOT [coversNoScreen] —
 *   the user is still at a screen in that hour (PRD §17 lets the screen breaks fall in it), so it is a
 *   stretch nothing is placed in, never a *rest* that bars the breaks after it.
 * - [NO_COMPUTER_UNLOCKED] / [NO_PHONE_UNLOCKED] — PRD §8's two layers, ASSERTED. Each hatches its own
 *   layer and restricts nothing by itself; where the two overlap the stretch is a no-screen period, which is
 *   the layers' own definition ([SchedulerDomain.assertedNoScreenRanges]).
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
 * never overrode it. The two one-sided layer kinds share that default for a different reason — they are not
 * restrictions at all until they overlap each other.
 */
object PeriodKinds {
    /** `side-dev/README.md`'s "no task allowed" — the kind of the three dynamic periods and of grey. */
    const val NO_TASK: String = "no task allowed"

    /** `side-dev/README.md`'s "no on-screen task" — what the modes and the recurrence bars are written in. */
    const val NO_SCREEN: String = "no on-screen task"

    /**
     * PRD §17's wind-down: the kind the hour before each §17 bedtime is covered by
     * ([org.example.project.scheduler.domain.SchedulerDomain.beforeBedPanels]). Built in because the sleep
     * schedule lays periods of it by itself — the user can no more delete it than they can delete "no task
     * allowed" — but an ordinary editable kind in every other respect ([isResilienceEditable]), so "I may
     * still do this in the hour before bed" is a resilience above `0` and nothing else.
     */
    const val BEFORE_BED: String = "before bed"

    /**
     * PRD §8 calendar LAYERS, said by the USER instead of by the OS: **a period the user draws to assert that
     * no computer of theirs was unlocked** — the same sentence
     * [org.example.project.scheduler.domain.SchedulerDomain.ActivityLayer.NoComputerUnlocked]'s hatch makes
     * when it is read off the lock history.
     *
     * It exists because the layer had no way of being *stated*: the hatch was evidence only (the OS record,
     * plus the "I'm away" button for the device the app is running on), so a stretch the user knew nobody was
     * at a computer for could not be put on the calendar unless they were willing to claim the phone was down
     * too. As a KIND it is one period like any other — chosen from the one "add…" chooser, edited in the one
     * period editor, removed with "Remove".
     *
     * **It restricts nothing on its own** ([defaultResilience] is `1`, like [NO_SCREEN]'s): one locked screen
     * is not "no screen", and the app's tasks are on-screen or off-screen, not per-device. What restricts is
     * the OVERLAP — a stretch carrying this kind *and* [NO_PHONE_UNLOCKED] is where both layers fall, which is
     * the definition of a no-screen period, and
     * [org.example.project.scheduler.domain.SchedulerDomain.assertedNoScreenRanges] is the one place that
     * intersection is taken. So the two one-sided kinds never grow a scheduling rule of their own: they feed
     * the rule [NO_SCREEN] already has.
     */
    const val NO_COMPUTER_UNLOCKED: String = "no computer unlocked"

    /** The phone's half of [NO_COMPUTER_UNLOCKED], in every respect. */
    const val NO_PHONE_UNLOCKED: String = "no phone unlocked"

    /**
     * The two kinds the README itself names plus the one PRD §17 lays and the two that state a LAYER; the
     * rest of the list is the account's.
     * A payload that predates [BEFORE_BED] and holds a *user-defined* kind of that very name decodes into
     * this one (`SchedulerStateCodec` keeps only [isUserDefined] names), which is the right healing: the
     * tasks' overrides are keyed by the name, so they go on answering for the period they were written for.
     */
    val BUILT_IN: List<String> =
        listOf(NO_TASK, NO_SCREEN, BEFORE_BED, NO_COMPUTER_UNLOCKED, NO_PHONE_UNLOCKED)

    /**
     * The resilience a task that was never told about [kind] has to it: **`0` for every kind but
     * [NO_SCREEN]**.
     *
     * That is what a restrictive period *is* — [NO_TASK] accepts nobody by its own name, and a kind the user
     * has just defined accepts nobody either until its edit window says otherwise, which is what "adding a
     * period adds it to every task with the default value 0" means. The exceptions are the kinds that say
     * something about a SCREEN rather than about the timeline being empty ([assertedLayers]): [NO_SCREEN],
     * because an on-screen task is exactly a `0` against it
     * ([org.example.project.scheduler.model.Task.DEFAULT_RESILIENCE]) and its default has to be the *other*
     * answer or an off-screen task would be the one that has to say so; and the two one-sided layer kinds,
     * because one locked screen is not "no screen" — what restricts there is their OVERLAP, which is a
     * [NO_SCREEN] stretch and is answered as one ([SchedulerDomain.assertedNoScreenRanges]).
     */
    fun defaultResilience(kind: String): Double = if (assertedLayers(kind).isEmpty()) 0.0 else 1.0

    /**
     * PRD §8: **which calendar LAYERS a period of [kind] asserts** — the one reading of the tie between a
     * restrictive period and the two oblique-line hatches, asked by the calendar (which hatch to draw over
     * the period), by [SchedulerDomain.assertedNoScreenRanges] (where both fall) and by [defaultResilience]
     * (a kind that speaks about a screen restricts nobody by itself).
     *
     * [NO_SCREEN] asserts BOTH — *"a no-screen period is where both layers fall"*, which is the same sentence
     * read from the other end — and each one-sided kind asserts its own. Every other kind, [NO_TASK] and
     * [BEFORE_BED] included, asserts none: they are statements about the TIMELINE being empty, and a user at
     * a locked screen and a user at an unlocked screen with nothing to do are different facts.
     */
    fun assertedLayers(kind: String): Set<SchedulerDomain.ActivityLayer> =
        when (kind) {
            NO_SCREEN -> BOTH_LAYERS
            NO_COMPUTER_UNLOCKED -> COMPUTER_LAYER
            NO_PHONE_UNLOCKED -> PHONE_LAYER
            else -> emptySet()
        }

    // Held rather than built per call: [defaultResilience] asks through here, and that is read once per
    // covering period per task on the plan walk ([multiplier]).
    private val BOTH_LAYERS: Set<SchedulerDomain.ActivityLayer> =
        SchedulerDomain.ActivityLayer.entries.toSet()
    private val COMPUTER_LAYER: Set<SchedulerDomain.ActivityLayer> =
        setOf(SchedulerDomain.ActivityLayer.NoComputerUnlocked)
    private val PHONE_LAYER: Set<SchedulerDomain.ActivityLayer> =
        setOf(SchedulerDomain.ActivityLayer.NoPhoneUnlocked)

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
     *
     * [BEFORE_BED] is deliberately not one of them, though it too refuses everybody by default: the wind-down
     * hour says nothing about screens — the user is expected to be at one, and PRD §17 lets the screen breaks
     * fall inside it — so it absorbs a dynamic period like any other emptiness without becoming a REST that
     * bars the breaks that follow.
     */
    fun coversNoScreen(kind: String): Boolean = kind == NO_TASK || kind == NO_SCREEN

    /**
     * **The title a period of [kind] the user lays carries** — the one place a kind becomes a name on the
     * calendar, read by the reducer that lays the period and by the window that offers the kind.
     *
     * The three built-ins keep the names the app has always drawn them under ("No screen", "Inactivity",
     * "Before bed") because a period's title is what the panel label, the hover bubble and the "(untitled)"
     * tombstone rule all read; **a kind the account defined is its own title**, since the user already named
     * it when they defined it and a second name for one object is the drift this funnel exists to prevent.
     */
    fun periodTitle(kind: String): String =
        when (kind) {
            NO_SCREEN -> "No screen"
            NO_TASK -> "Inactivity"
            BEFORE_BED -> "Before bed"
            NO_COMPUTER_UNLOCKED -> SchedulerDomain.ActivityLayer.NoComputerUnlocked.calendarLabel
            NO_PHONE_UNLOCKED -> SchedulerDomain.ActivityLayer.NoPhoneUnlocked.calendarLabel
            else -> kind
        }

    /**
     * Whether a panel of [kind] carries the legacy [org.example.project.scheduler.model.TaskPanel.noScreen]
     * flag — **true for [NO_SCREEN] and nothing else**.
     *
     * [org.example.project.scheduler.model.TaskPanel.restrictiveKind] is the single reading of a panel's
     * kind and the two flags beside it are legacy; they are still written for the two kinds that HAVE one so
     * that the codec, the three-way merge and
     * [org.example.project.scheduler.domain.SchedulerDomain.unifyNoScreenPeriods] go on answering as they
     * did. A kind with no flag of its own — `before bed`, or one of the account's — writes neither, and
     * everything that matters asks the kind instead. Setting a flag that stands for another kind would be a
     * second, disagreeing statement of what the period is.
     */
    fun legacyNoScreenFlag(kind: String): Boolean = kind == NO_SCREEN

    /** The other half of [legacyNoScreenFlag]: the legacy `inactivity` flag is [NO_TASK]'s and no other's. */
    fun legacyInactivityFlag(kind: String): Boolean = kind == NO_TASK

    /** A user-defined kind is any that is not one of the two the README names. Blank names are refused. */
    fun isUserDefined(kind: String): Boolean = kind.isNotBlank() && kind !in BUILT_IN

    /** Trim + collapse whitespace, so "  deep   work " and "deep work" are one kind and not two. */
    fun normalize(raw: String): String = raw.trim().split(WHITESPACE).filter { it.isNotEmpty() }.joinToString(" ")

    private val WHITESPACE = Regex("""\s+""")
}
