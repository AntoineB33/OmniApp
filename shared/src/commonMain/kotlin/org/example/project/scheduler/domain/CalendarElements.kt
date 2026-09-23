package org.example.project.scheduler.domain

import kotlinx.datetime.DayOfWeek
import org.example.project.scheduler.model.AlarmEntry
import org.example.project.scheduler.model.AlertSettings
import org.example.project.scheduler.model.PanelPins
import org.example.project.scheduler.model.TaskId

/**
 * PRD §8 contextual menu **"add…" / "edit…"**: the model behind the ONE window both entries open — a
 * **set** of things to put on the calendar (or a set already on it), and the configuration each of them owns.
 *
 * The window that came before this one was a ROUTER: it asked *what do you want to add?*, then handed off to
 * the editor that owns that object, and every editor asked for its own bounds again. That is fine for one
 * element and wrong for several — laying a task panel and the period that must cover it meant opening two
 * windows and typing the same two instants twice, and there was no way at all to say "these three things all
 * start here". So the window now holds a LIST, and the configuration is grouped by **who shares it**:
 *  - what every element in the list answers the same way is asked ONCE, at the top ([ConfigSection]
 *    with [ConfigSection.sharedByAll] true);
 *  - everything else is asked per group of elements that share it, titled by those elements' own names.
 *
 * That grouping is [calendarConfigSections], and it is the whole of the window's layout — there is no table
 * anywhere saying "a period also gets a bound chooser". A field belongs to a kind ([fieldsOf]) and the
 * sections fall out of the elements in the list.
 *
 * **Nothing here writes.** The drafts are values; `App.kt` turns the list into
 * [org.example.project.scheduler.state.SchedulerIntent.AddCalendarElements] (and, for alarms,
 * `SetAlarms`) on Save, which is what keeps "nothing is placed until Save" one rule for every kind.
 */
object CalendarElements {

    /**
     * **The kinds of element the window's drop-down offers** — what the user picks *before* naming which
     * one. Four, and they are the four things a hand can put at a point on the calendar.
     *
     * `task` means **a task panel**: a block of work on the timeline. The §13 task window and PRD §17's
     * sleep schedule are not here and must not be — neither is laid by the calendar, both are reached from
     * the menu row that names them, and copying their fields into this window would be a second editor for
     * an object that already has one (CLAUDE.md § *One rule, one funnel*). A **timer** is not here either:
     * a timer marker is drawn from a countdown whose remaining time is DERIVED state, so there is no start
     * on it to give.
     */
    enum class Kind(
        /** What the drop-down row reads, and the word a section title names an element of this kind by. */
        val label: String,
    ) {
        /** PRD §8: a block of work — `AddTaskPanel` / `UpdateTaskPanel`. */
        TaskPanel("task"),

        /** `side-dev/README.md` § *Restrictive Period*: a start, an end and a KIND. */
        RestrictivePeriod("restrictive period"),

        /** PRD §18: a ring at a time of day, on a set of weekdays — `SetAlarms`. */
        Alarm("alarm"),

        /** PRD §14: a zero-duration tag with an id of its own — `AddReminder`. */
        Reminder("reminder"),
    }

    /**
     * PRD §8: **how one bound is given.** Lifted out of the period editor, where it was a private enum, the
     * moment a period could share its start field with a panel: the mode belongs to the BOUND, and the
     * window offers only the modes every element in the section can express ([boundsOf]).
     *
     * "Now" is a mode rather than a shortcut that fills the field, so "from ∞ to now" means the instant the
     * user saves, not the instant they opened the window.
     */
    enum class Bound { At, Now, Infinite }

    /**
     * **One configuration a section can be asking about.** The identity of a field, not its value — the
     * values live on the drafts, and [sharedValue] is what says whether the elements of a section agree.
     *
     * The order of the entries is the order a section renders them in, so a section holding `Start` and
     * `End` always reads top-to-bottom in that order however its elements were added.
     */
    enum class Field(val label: String) {
        /**
         * When the element begins. **Every kind has one**, which is why it is nearly always the one thing
         * the top section holds: a panel's and a period's start, a reminder's instant, and — the user's
         * rule — **the instant an alarm starts ringing** (or speaking, or showing its notification).
         */
        Start("Begins"),

        /**
         * When it ends. A reminder has none (PRD §14: zero duration); an **alarm's is the end of the ring**,
         * so `end - start` is its [AlarmEntry.soundSeconds] and the field is a real answer rather than a
         * borrowed one.
         */
        End("Ends"),

        /** PRD §8: the four independent pin dimensions of a task panel. */
        Pins("Pins"),

        /** `side-dev/README.md`: the panel's task's resilience to `no screen`, shown as the on-screen switch. */
        Screen("Screen"),

        /** PRD §18: the weekdays the alarm rings on. */
        AlarmDays("Rings on"),

        /** PRD §11/§18: the channels the alarm announces itself through. */
        AlarmAlert("Alert"),

        /** PRD §18: whether the alarm is armed. */
        AlarmArmed("Armed"),

        /** PRD §14: the tag is placed already struck off (which anchors the recurrence). */
        ReminderChecked("Already done"),

        /** PRD §14: the tag survives regeneration. */
        ReminderPinned("Stays put"),
    }

    /**
     * **Which configurations an element of [kind] owns.** The one table in this file, and the only place a
     * kind is spelt out: [calendarConfigSections] reads it and nothing else, so giving a kind a new field is
     * a line here and no change at all to the grouping or to the window.
     */
    fun fieldsOf(kind: Kind): List<Field> =
        when (kind) {
            Kind.TaskPanel -> listOf(Field.Start, Field.End, Field.Pins, Field.Screen)
            Kind.RestrictivePeriod -> listOf(Field.Start, Field.End)
            Kind.Alarm -> listOf(Field.Start, Field.End, Field.AlarmDays, Field.AlarmAlert, Field.AlarmArmed)
            Kind.Reminder -> listOf(Field.Start, Field.ReminderChecked, Field.ReminderPinned)
        }

    /**
     * **Which bound modes a [kind] can express.** Only a restrictive period can begin at ∞ or follow the
     * now-line — a panel, an alarm and a reminder are at an instant and nothing else — so a section mixing a
     * period with any other kind offers `At` alone (the intersection, [offeredBounds]).
     *
     * This is not a restriction the window invents: `∞ → now` is the period editor's whole reason for
     * existing (it empties the recorded past), and there is no such sentence about a ring or a tag.
     */
    fun boundsOf(kind: Kind): Set<Bound> =
        when (kind) {
            Kind.RestrictivePeriod -> setOf(Bound.At, Bound.Now, Bound.Infinite)
            Kind.TaskPanel, Kind.Alarm, Kind.Reminder -> setOf(Bound.At)
        }

    /** The bound modes every element of [drafts] can express — what a shared bound field may offer. */
    fun offeredBounds(drafts: List<Draft>): Set<Bound> =
        if (drafts.isEmpty()) emptySet()
        else drafts.map { boundsOf(it.kind) }.reduce { a, b -> a intersect b }

    /**
     * **One element the window is adding or editing**, with every configuration any kind can carry. One flat
     * type rather than a sealed hierarchy per kind, and deliberately: the window's whole job is to write ONE
     * value across elements of DIFFERENT kinds ("these three all begin here"), which a hierarchy turns into a
     * `when` at every write. What a draft of a given kind actually owns is [fieldsOf]; the rest is inert.
     */
    data class Draft(
        val kind: Kind,
        /**
         * The object this draft edits, `null` while it is being ADDED. A panel id (`panel/{n}`), a manual
         * reminder tag's panel id, or an alarm id (`alarm-{n}`) — whatever names the thing to write back to.
         */
        val existingId: String? = null,
        /** What the element is called in a section title and in the window's list. Blank ⇒ its kind's word. */
        val name: String = "",
        val startMillis: Long = 0L,
        val endMillis: Long = 0L,
        val startBound: Bound = Bound.At,
        val endBound: Bound = Bound.At,
        /** [Kind.TaskPanel]: the tree task the panel stands for; null for a calendar-only "New task". */
        val taskId: TaskId? = null,
        /** [Kind.TaskPanel]. */
        val pins: PanelPins = PanelPins(existence = true),
        /** [Kind.TaskPanel]: the task's resilience to `no screen` — 0 is "on screen". */
        val noScreenResilience: Double = 0.0,
        /** [Kind.RestrictivePeriod]: the kind the period is OF, off `state.allPeriodKinds`. */
        val periodKind: String = "",
        /** [Kind.Alarm]. */
        val alarmDays: Set<DayOfWeek> = AlarmEntry.EVERY_DAY,
        /** [Kind.Alarm]. */
        val alert: AlertSettings = AlertSettings.RING,
        /** [Kind.Alarm]. */
        val alarmArmed: Boolean = true,
        /** [Kind.Reminder]: the reminder this tag is an occurrence of; blank mints a fresh one on Save. */
        val reminderId: String = "",
        /** [Kind.Reminder]. */
        val reminderChecked: Boolean = false,
        /** [Kind.Reminder]. */
        val reminderPinned: Boolean = false,
    ) {
        /** How a section title and the window's list name this element: its own name, else its kind's word. */
        val displayName: String get() = name.ifBlank { kind.label }
    }

    /**
     * **One section of the window's configuration** — a set of [fields] and the [drafts] that all own every
     * one of them.
     *
     * [sharedByAll] marks the one section the spec puts SECOND in the window, above the rest: the
     * configurations every element in the list answers. It is a property of the section rather than a
     * separate return value so that the one-element case needs no branch at all — with a single element
     * every field is shared by all, so the window is one untitled section and reads exactly like the
     * single-object editor it replaces.
     */
    data class ConfigSection(
        val fields: List<Field>,
        /**
         * **Where** in the window's list this section's elements are. Positions rather than values, because
         * a window can hold two drafts that are equal — two fresh panels of one task, say — and a write
         * aimed at one of them by value would land on both.
         */
        val indices: List<Int>,
        val drafts: List<Draft>,
        val sharedByAll: Boolean,
    ) {
        /**
         * A stable name for this section across recompositions: its owner set. It is what the window keys
         * a half-typed field's text by, so typing into "Begins" does not lose a character when an unrelated
         * element is added below.
         */
        val key: String get() = indices.joinToString(",")

        /**
         * "the names and kinds of the elements that share the specific configuration" — blank on the shared
         * section, which is titled by being first rather than by naming everything twice.
         */
        val title: String
            get() =
                if (sharedByAll) ""
                else drafts.joinToString(" · ") { draft ->
                    if (draft.name.isBlank()) draft.kind.label else "${draft.name} (${draft.kind.label})"
                }
    }

    /**
     * **The window's configuration layout: the fields grouped so that as few questions are asked as
     * possible.**
     *
     * The grouping is a partition of the FIELDS by their **owner set** — the set of elements that own them.
     * Two fields land in one section exactly when the same elements own both, which is the strongest form
     * "grouped by sharing as much as possible" can take: any coarser grouping would put a field in front of
     * an element that has not got it, and any finer one would ask two identical questions of one set of
     * elements. It is deliberately **not** a partition of the elements — a task panel shares `Start` with an
     * alarm and `End` with a period, so an element belongs to as many sections as it has distinct owner sets,
     * and trying to give each element one home is what makes the layout impossible rather than merely
     * awkward.
     *
     * Order: the shared section first (when there is one), then the rest by **descending owner count** — the
     * most widely shared question first — and ties by the position of the first owner, so a section never
     * moves because an unrelated element was added after it. Inside a section the fields keep [Field]'s own
     * order.
     *
     * A field owned by NO element (impossible today, but it falls out of [fieldsOf]) yields no section
     * rather than an empty one.
     */
    fun calendarConfigSections(drafts: List<Draft>): List<ConfigSection> {
        if (drafts.isEmpty()) return emptyList()
        // Owner set as an ordered list of indices, which is both the grouping key and the section's order.
        val ownersOf = LinkedHashMap<Field, List<Int>>()
        for (field in Field.entries) {
            val owners = drafts.indices.filter { field in fieldsOf(drafts[it].kind) }
            if (owners.isNotEmpty()) ownersOf[field] = owners
        }
        val all = drafts.indices.toList()
        return ownersOf.entries
            .groupBy({ it.value }, { it.key })
            .map { (owners, fields) ->
                ConfigSection(
                    fields = fields,
                    indices = owners,
                    drafts = owners.map { drafts[it] },
                    sharedByAll = owners == all,
                )
            }
            .sortedWith(
                compareByDescending<ConfigSection> { it.sharedByAll }
                    .thenByDescending { it.indices.size }
                    .thenBy { section -> section.indices.first() },
            )
    }

    /**
     * **What a shared field SHOWS when its elements disagree.** `null` is "mixed", and it is the reason this
     * type exists at all: a window opened on three things at one point holds three different starts, and a
     * field seeded with the first of them would quietly move the other two to it the moment the user pressed
     * Save without touching anything. Mixed renders blank.
     *
     * The other half of the rule is that a mixed field **writes nothing until it is touched**, and the
     * window gets that for free rather than by tracking touches: each draft keeps its own value, and an
     * edit to a shared field is what copies one value across the section's [ConfigSection.indices]. A Save
     * on a window nobody typed into therefore writes back exactly what it read.
     */
    fun <T> sharedValue(values: List<T>): T? {
        val first = values.firstOrNull() ?: return null
        return if (values.all { it == first }) first else null
    }

    /** [sharedValue] read off a section's own drafts through [read]. */
    fun <T> ConfigSection.sharedValue(read: (Draft) -> T): T? =
        CalendarElements.sharedValue(drafts.map(read))

    /**
     * **One shared field written across a section**: [drafts] with every element the section owns put
     * through [edit], and every other element left alone.
     *
     * The one place a section's write happens, so "a field asked once is written to all of its owners" is a
     * rule rather than a habit of each editor — and it goes through the section's INDICES, because a window
     * may hold two equal drafts (two fresh panels of one task) and a write matched by value would land on
     * both.
     */
    fun applyToSection(
        drafts: List<Draft>,
        section: ConfigSection,
        edit: (Draft) -> Draft,
    ): List<Draft> =
        drafts.mapIndexed { index, draft -> if (index in section.indices) edit(draft) else draft }

    /**
     * PRD §18: **the time of day a start of [startMillis] states**, measured from the local midnight
     * [dayStartMillis] — how the window's shared `Start` reaches an alarm.
     *
     * The date is NOT part of an alarm: the rule is a time of day on a set of weekdays, so the window shows
     * the occurrence the user right-clicked near and writing a new start moves the TIME, never the date. A
     * start dragged onto another day therefore changes when the alarm rings on every day it rings on —
     * which is what an alarm is, and why the weekday set is a field of its own beside it rather than
     * something the start quietly edits.
     */
    fun alarmTimeOfDayMinutes(startMillis: Long, dayStartMillis: Long): Int {
        val offset = startMillis - dayStartMillis
        val minutes = (offset / 60_000L).toInt()
        return ((minutes % AlarmEntry.MINUTES_PER_DAY) + AlarmEntry.MINUTES_PER_DAY) % AlarmEntry.MINUTES_PER_DAY
    }

    /**
     * PRD §18: the ring length a start/end pair states, clamped to what an alarm may carry
     * ([AlarmEntry.MAX_ALARM_SOUND_SECONDS]) and never zero — a zero-length ring is a silenced alarm
     * ([AlarmEntry.schedulable]), which is what the *Armed* switch is for.
     */
    fun alarmSoundSeconds(startMillis: Long, endMillis: Long): Int =
        ((endMillis - startMillis) / 1000L)
            .coerceIn(1L, AlarmEntry.MAX_ALARM_SOUND_SECONDS.toLong())
            .toInt()
}
