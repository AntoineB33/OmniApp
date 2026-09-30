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
 * Six kinds are built in — three because the README names them (its one grey kind being two here), one
 * because the app's own §17 sleep schedule lays it, and two because PRD §8's two calendar LAYERS are sentences the user must be able to say
 * as well as read off a lock history:
 * - [INACTIVITY] / [SLEEP] — the README's *"no task allowed"*, split in two because the calendar always
 *   drew and named them apart: the kind of the three dynamic restrictive periods (§ *3 Dynamic Restrictive
 *   Period*) and of every grey stretch, and the kind of a §17 sleep window. Their default resilience is `0` —
 *   by their own names they accept nobody — and they are the two kinds a task may not be given a value for at
 *   all ([isResilienceEditable]).
 * - [NO_SCREEN] — *"no on-screen task"*, the kind the two `t_p` modes and all three recurrence bars are
 *   written in terms of.
 * - [BEFORE_BED] — PRD §17's wind-down: **the hour before bed is covered by the period "before bed"**. It
 *   used to be a hard-coded extension of the sleep obstacle, which is a second mechanism for "where may this
 *   task run" and therefore the mistake this model exists to prevent. As a KIND it is one period like any
 *   other: its default `0` is what keeps the hour empty, and a task the user gives a non-zero resilience to
 *   it works through the wind-down without any rule of its own. By default it is **accompanied by a "no
 *   screen" period** over the same span ([defaultStyle], [PeriodKindConfig.kindsOf]) — a companion, never a
 *   second panel.
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
    /**
     * **A stretch nothing at all is scheduled in** — the app's grey: a past hour no panel covers, a period the
     * user drew to say nothing happened, and the three dynamic periods (§ *3 Dynamic Restrictive Period*).
     *
     * With [SLEEP] it replaces the README's single `no task allowed`, which was ONE kind doing two jobs: the
     * calendar has always drawn and named the two separately ("Inactivity", "Sleep"), the user says them
     * separately, and a chooser offering `no task allowed` could not offer either of the words the rest of the
     * app uses. Splitting them is what makes **the kind the user's own word** — so a kind needs no second name
     * for the menus, which is the drift three spellings of this one had already started.
     *
     * "Always allows no tasks": its resilience is `0` and [isResilienceEditable] refuses to write one, exactly
     * as `no task allowed`'s was — the split renames and divides, it does not change what a grey period does.
     */
    const val INACTIVITY: String = "inactivity"

    /**
     * **A sleep window** — PRD §17's own periods, and one the user may draw by hand like any other kind.
     * [INACTIVITY]'s twin: the other half of the README's `no task allowed`, and the same rules (nothing is
     * placed, no resilience to write).
     *
     * Being a KIND is all it is. What makes a §17 window a §17 window is still the schedule that laid it and
     * the `sleep` flag it carries — the carving, the block bridging and the rest-pose reading all key on that,
     * not on the kind — so a hand-drawn `sleep` period is a grey period that admits nobody, in exactly the way
     * a hand-drawn `before bed` period is: the same statement, made by a hand instead of by the rule.
     */
    const val SLEEP: String = "sleep"

    /**
     * `side-dev/README.md`'s "no on-screen task" — what the modes and the recurrence bars are written in —
     * **under the name the calendar says it in**. The README's spelling was the stored one until 2026-09-12,
     * which made it the third of three names for one kind (the stored one, the chooser's "no screen", the
     * panel's "No screen"); the kinds are the user's words now and the spellings a payload may hold are
     * healed by [migrateStoredKind].
     */
    const val NO_SCREEN: String = "no screen"

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
     * [org.example.project.scheduler.domain.SchedulerDomain.companionPeriods] is the one place that
     * intersection is taken. So the two one-sided kinds never grow a scheduling rule of their own: they feed
     * the rule [NO_SCREEN] already has.
     */
    const val NO_COMPUTER_UNLOCKED: String = "no computer unlocked"

    /** The phone's half of [NO_COMPUTER_UNLOCKED], in every respect. */
    const val NO_PHONE_UNLOCKED: String = "no phone unlocked"

    /**
     * `docs/scheduler_requirements.md` § *$now line$ 3 modes*: **a computer of the account is unlocked, and the user
     * said nobody is at it** — the "I'm away" button pressed on a computer. *"Mode 3: $now line$ is in either 'fake no
     * computer unlocked' or 'fake no phone unlocked' periods. 'fake no computer unlocked' can't be with 'no computer
     * unlocked'"*: where the computer really is locked the stretch is [NO_COMPUTER_UNLOCKED], never both
     * ([SchedulerDomain.observedNoScreenRegions] takes the real one out of it).
     *
     * A layer kind like the real one ([isLayerKind]): it restricts nothing by itself, and what it makes with the phone's
     * layer is a combination rule ([DEFAULT_COMBINATIONS]: with either phone layer, a "no screen" period).
     */
    const val FAKE_NO_COMPUTER_UNLOCKED: String = "fake no computer unlocked"

    /** The phone's half of [FAKE_NO_COMPUTER_UNLOCKED]: the "I'm away" button pressed on a phone. */
    const val FAKE_NO_PHONE_UNLOCKED: String = "fake no phone unlocked"

    /**
     * `docs/scheduler_requirements.md` § *screen breaks*: **the kind of the 5-minute screen break** — *"The 5min break is
     * accompanied by two periods: the first minute that allow no tasks and the 4 next minutes"*. The break is a
     * period of this kind end to end, accompanied by "no screen", and its first minute also by [INACTIVITY]
     * (`SchedulerDomain.screenBreakPeriods`): nobody runs in that minute whatever their resilience, while in the four
     * after it a task runs exactly when it has been given a resilience above `0` to this kind. Like every kind, it
     * starts at `0` for every task — so an account nobody has touched keeps the whole break empty.
     */
    const val BREAK_5MIN: String = "5min screen break"

    /**
     * `docs/scheduler_requirements.md` § *screen breaks*: **the kind of the 15-minute screen break**, accompanied by "no
     * screen". The requirements say nothing more of it, so it is an ordinary editable kind: `0` for every task until
     * one is given more. (The 20-second break *"allows no task"* — it is [INACTIVITY], which nobody may be resilient
     * to.)
     */
    const val BREAK_15MIN: String = "15min screen break"

    /**
     * The two kinds of the screen breaks a task may be resilient to ([BREAK_5MIN], [BREAK_15MIN]). Offered by every
     * list of kinds a task can be resilient to (`SchedulerState.allPeriodKinds`), but not [BUILT_IN]: a break is laid by
     * the rules, drawn as the break it is, and has no drawing of its own to keep apart from the others.
     */
    val BREAK_KINDS: List<String> = listOf(BREAK_5MIN, BREAK_15MIN)

    /**
     * The kinds the README itself names (its grey one as two) plus the one PRD §17 lays and the two that
     * state a LAYER; the rest of the list is the account's.
     * A payload that predates [BEFORE_BED] and holds a *user-defined* kind of that very name decodes into
     * this one (`SchedulerStateCodec` keeps only [isUserDefined] names), which is the right healing: the
     * tasks' overrides are keyed by the name, so they go on answering for the period they were written for.
     */
    val BUILT_IN: List<String> =
        listOf(
            INACTIVITY, SLEEP, NO_SCREEN, BEFORE_BED, NO_COMPUTER_UNLOCKED, NO_PHONE_UNLOCKED,
            FAKE_NO_COMPUTER_UNLOCKED, FAKE_NO_PHONE_UNLOCKED,
        )

    /**
     * **The combination rules an account starts with** (`docs/scheduler_requirements.md` § *$now line$ 3 modes*; user
     * rule 2026-09-30: *"when 'fake no computer unlocked' and 'no phone unlocked' are present, it is always accompanied
     * by 'no screen' (except if the user changes the period configurations)"*): **each computer layer with each phone
     * layer — real or fake — is a "no screen" period.** Where a computer's and a phone's "nobody here" overlap, nobody is
     * at any screen. Edited in the period edit window ([PeriodCombination], [PeriodKindConfig.combinations]); until the
     * account edits them these are in force. Until 2026-09-30 the first of them was hard-coded.
     */
    val DEFAULT_COMBINATIONS: List<PeriodCombination> =
        listOf(
            PeriodCombination("layers", setOf(NO_COMPUTER_UNLOCKED, NO_PHONE_UNLOCKED), setOf(NO_SCREEN)),
            PeriodCombination("layers-fake-computer", setOf(FAKE_NO_COMPUTER_UNLOCKED, NO_PHONE_UNLOCKED), setOf(NO_SCREEN)),
            PeriodCombination("layers-fake-phone", setOf(NO_COMPUTER_UNLOCKED, FAKE_NO_PHONE_UNLOCKED), setOf(NO_SCREEN)),
            PeriodCombination("layers-fake-both", setOf(FAKE_NO_COMPUTER_UNLOCKED, FAKE_NO_PHONE_UNLOCKED), setOf(NO_SCREEN)),
        )

    /**
     * **The kind a stored name means** — the one reading of every spelling a payload written before
     * 2026-09-12 may hold, asked by `SchedulerStateCodec` (panels, the account's kind list and every task's
     * resilience map) and by [org.example.project.scheduler.model.TaskPanel.restrictiveKind], which is the
     * single reading of a panel's kind.
     *
     * Two renames to undo, and the second is why this cannot be a plain map: `no task allowed` became TWO
     * kinds, so which one a panel meant is read off the panel — [isSleep] (its `sleep` flag, the thing §17
     * has always marked its own windows with). Everything that is not a panel has no sleep to speak of and
     * lands on [INACTIVITY], which is right: a resilience override or an account kind list naming
     * `no task allowed` was about grey in general.
     *
     * **The resilience map is the one that must not be missed.** A task is on-screen exactly when it holds a
     * `0` against [NO_SCREEN] ([org.example.project.scheduler.model.Task.onScreen]); left un-migrated, every
     * task in an existing account would have carried its `0` under a name nothing asks about any more and
     * every one of them would have read as off-screen — free to be scheduled inside the no-screen periods
     * that were keeping them out.
     */
    fun migrateStoredKind(stored: String, isSleep: Boolean = false): String =
        when (normalize(stored)) {
            LEGACY_NO_TASK -> if (isSleep) SLEEP else INACTIVITY
            LEGACY_NO_SCREEN -> NO_SCREEN
            else -> normalize(stored)
        }

    /** The README's one grey kind, split into [INACTIVITY] and [SLEEP] on 2026-09-12. */
    private const val LEGACY_NO_TASK: String = "no task allowed"

    /** [NO_SCREEN]'s stored name until 2026-09-12. */
    private const val LEGACY_NO_SCREEN: String = "no on-screen task"

    /**
     * The resilience a task that was never told about [kind] has to it: **`0` for every kind but
     * [NO_SCREEN]**.
     *
     * That is what a restrictive period *is* — [INACTIVITY] and [SLEEP] accept nobody by their own names, and a kind the user
     * has just defined accepts nobody either until its edit window says otherwise, which is what "adding a
     * period adds it to every task with the default value 0" means. The exceptions are the kinds that say
     * something about a SCREEN rather than about the timeline being empty ([isLayerKind]): [NO_SCREEN],
     * because an on-screen task is exactly a `0` against it
     * ([org.example.project.scheduler.model.Task.DEFAULT_RESILIENCE]) and its default has to be the *other*
     * answer or an off-screen task would be the one that has to say so; and the two one-sided layer kinds,
     * because one locked screen is not "no screen" — what restricts there is their OVERLAP, which is a
     * [NO_SCREEN] stretch and is answered as one ([SchedulerDomain.assertedNoScreenRanges]).
     */
    fun defaultResilience(kind: String): Double = if (isLayerKind(kind)) 1.0 else 0.0

    /**
     * **The style a kind has until the account says otherwise** — its companions and its drawing
     * ([PeriodKindStyle]). The account's overrides live in
     * [org.example.project.scheduler.state.SchedulerState.periodKindStyles] and are read through
     * [PeriodKindConfig], never beside it.
     *
     * The companions (user rule, 2026-09-18: *"the user can define a set of periods that are always present when
     * this period is present"*):
     * - [SLEEP] and [BEFORE_BED] carry a [NO_SCREEN] period (PRD §17: a night and the hour of wind-down leading
     *   into it are the plainest stretches there are of nobody being at a screen);
     * - [INACTIVITY] carries **no** "no screen" period — it says the timeline is empty, not that nobody is at a
     *   screen, and a grey stretch the user drew must not be retracted by a mode-1 line;
     * - [NO_SCREEN] carries **neither** "no computer unlocked" nor "no phone unlocked": it is its own statement,
     *   drawn with its own pattern. Since 2026-09-19 that is what "no screen" MEANS: a period that refuses the
     *   tasks with a resilience of 0 to it, and nothing about computers or phones. The reverse — a stretch where both layers fall IS a no-screen period — is the
     *   layers' definition and stays true whatever the account sets ([SchedulerDomain.companionPeriods]);
     * - every other kind carries nothing.
     *
     * The drawings are pairwise distinct across the built-ins, so the six can overlap in any combination and
     * still be told apart. A kind the account defines gets the least-used drawing when it is added
     * (`SchedulerReducer`'s `reduceAddPeriodKind`); [PeriodDrawing.Crosses] is only the fallback for a payload
     * that never stored one.
     */
    fun defaultStyle(kind: String): PeriodKindStyle =
        when (kind) {
            INACTIVITY -> PeriodKindStyle(emptySet(), PeriodDrawing.VerticalLines)
            SLEEP -> PeriodKindStyle(setOf(NO_SCREEN), PeriodDrawing.HorizontalLines)
            NO_SCREEN -> PeriodKindStyle(emptySet(), PeriodDrawing.HalfCirclesLeft)
            BEFORE_BED -> PeriodKindStyle(setOf(NO_SCREEN), PeriodDrawing.Zigzags)
            NO_COMPUTER_UNLOCKED -> PeriodKindStyle(emptySet(), PeriodDrawing.RisingObliques)
            NO_PHONE_UNLOCKED -> PeriodKindStyle(emptySet(), PeriodDrawing.FallingObliques)
            // The real layer's slope, DOTTED: the look an "I'm away" stretch has always had on the calendar (the
            // user's word against an unlocked machine).
            FAKE_NO_COMPUTER_UNLOCKED -> PeriodKindStyle(emptySet(), PeriodDrawing.DottedRisingObliques)
            FAKE_NO_PHONE_UNLOCKED -> PeriodKindStyle(emptySet(), PeriodDrawing.DottedFallingObliques)
            // *"always accompanied by the 'no screen' period"*; drawn as the grey family the breaks belong to.
            BREAK_5MIN, BREAK_15MIN -> PeriodKindStyle(setOf(NO_SCREEN), PeriodDrawing.VerticalLines)
            else -> PeriodKindStyle(emptySet(), PeriodDrawing.Crosses)
        }

    /**
     * Whether [kind] is, **by its own name**, a sentence about SCREENS — [NO_SCREEN] and the two one-sided
     * layer kinds — as opposed to one that merely carries such a period as a companion.
     *
     * The difference matters wherever the question is what the period IS rather than what comes with it: its
     * default resilience ([defaultResilience] — `before bed` still turns everybody away, the no-screen period it
     * carries being the part that is about screens), the calendar paint of a drawn period (it does not cover the
     * past), and which periods unify ([SchedulerDomain.unifyNoScreenPeriods]).
     */
    fun isLayerKind(kind: String): Boolean = kind == NO_SCREEN || kind in LAYER_KINDS

    /**
     * The kinds that state a calendar LAYER: per [SchedulerDomain.ActivityLayer], the real one ([layerKind]) and the
     * fake one ([fakeLayerKind]).
     */
    val LAYER_KINDS: Set<String> =
        setOf(NO_COMPUTER_UNLOCKED, NO_PHONE_UNLOCKED, FAKE_NO_COMPUTER_UNLOCKED, FAKE_NO_PHONE_UNLOCKED)

    /** The kind that states [layer] was FAKED — its device unlocked, the user saying nobody is at it. */
    fun fakeLayerKind(layer: SchedulerDomain.ActivityLayer): String =
        when (layer) {
            SchedulerDomain.ActivityLayer.NoComputerUnlocked -> FAKE_NO_COMPUTER_UNLOCKED
            SchedulerDomain.ActivityLayer.NoPhoneUnlocked -> FAKE_NO_PHONE_UNLOCKED
        }

    /** The kind that states [layer] — the one tie between a layer hatch and a restrictive period. */
    fun layerKind(layer: SchedulerDomain.ActivityLayer): String =
        when (layer) {
            SchedulerDomain.ActivityLayer.NoComputerUnlocked -> NO_COMPUTER_UNLOCKED
            SchedulerDomain.ActivityLayer.NoPhoneUnlocked -> NO_PHONE_UNLOCKED
        }

    /**
     * Whether a task may be given a resilience to [kind] **at all**.
     *
     * [INACTIVITY] is the one it may not: it is the kind of what `docs/scheduler_requirements.md` says *"allows no
     * task"* — the 20 s screen break and the 5 min break's first minute — so its multiplier is `0` for everybody,
     * whatever a resilience map holds ([resilienceFor]). Every other kind is an ordinary editable value — [SLEEP]
     * included: the requirements say a sleep period *"allows no task (a task has a 0 resilience to it by default)"*,
     * a default and not a rule (until 2026-09-29 it could not be edited).
     */
    fun isResilienceEditable(kind: String): Boolean = kind != INACTIVITY

    /** A resilience is a multiplier in `[0, 1]`; anything outside is healed to the nearest bound. */
    fun clamp(value: Double): Double = if (value.isNaN()) 1.0 else value.coerceIn(0.0, 1.0)

    /**
     * The resilience of a task carrying [overrides] to [kind] — an override if it has one, else
     * [defaultResilience]. The single reading of a resilience map in the whole app: the plan layer, the fill,
     * the calendar and the edit window all ask through here, so none of them can invent a different default.
     */
    fun resilienceFor(overrides: Map<String, Double>, kind: String): Double =
        // "Allows no task": an override a payload holds for it (the editor never wrote one) is not honoured.
        if (kind == INACTIVITY) 0.0 else overrides[kind]?.let { clamp(it) } ?: defaultResilience(kind)

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
     * README says *"covered by the period 'no on-screen task'"*, and the two grey kinds cover it a fortiori (a period
     * that turns everybody away turns the on-screen tasks away too). That is exactly why the three dynamic
     * periods, whose kind is [INACTIVITY], are the ones the modes govern.
     *
     * [BEFORE_BED] is not one of them BY NAME, and does not need to be: the no-screen period it carries
     * ([PeriodKindConfig.impliedKinds]) reaches the bars as a [NO_SCREEN] period of its own
     * ([SchedulerDomain.companionPeriods]), so the wind-down hour is a no-screen stretch exactly where
     * that period is — and a second answer here would count it twice.
     */
    fun coversNoScreen(kind: String): Boolean =
        kind == INACTIVITY || kind == SLEEP || kind == NO_SCREEN || kind in BREAK_KINDS

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
            INACTIVITY -> "Inactivity"
            SLEEP -> "Sleep"
            BEFORE_BED -> "Before bed"
            NO_COMPUTER_UNLOCKED -> SchedulerDomain.ActivityLayer.NoComputerUnlocked.calendarLabel
            NO_PHONE_UNLOCKED -> SchedulerDomain.ActivityLayer.NoPhoneUnlocked.calendarLabel
            FAKE_NO_COMPUTER_UNLOCKED -> "Fake no computer unlocked"
            FAKE_NO_PHONE_UNLOCKED -> "Fake no phone unlocked"
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

    /**
     * The other half of [legacyNoScreenFlag]: the legacy `inactivity` flag is [INACTIVITY]'s and no other's —
     * [SLEEP] has a legacy flag of its own (`sleep`), which is what tells the two halves of the README's old
     * `no task allowed` apart in a payload ([migrateStoredKind]).
     */
    fun legacyInactivityFlag(kind: String): Boolean = kind == INACTIVITY

    /** A user-defined kind is any that is not one of the two the README names. Blank names are refused. */
    fun isUserDefined(kind: String): Boolean = kind.isNotBlank() && kind !in BUILT_IN && kind !in BREAK_KINDS

    /** Trim + collapse whitespace, so "  deep   work " and "deep work" are one kind and not two. */
    fun normalize(raw: String): String = raw.trim().split(WHITESPACE).filter { it.isNotEmpty() }.joinToString(" ")

    private val WHITESPACE = Regex("""\s+""")
}
