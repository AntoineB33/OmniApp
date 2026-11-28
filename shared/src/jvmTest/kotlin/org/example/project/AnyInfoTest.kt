package org.example.project

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.datetime.DayOfWeek
import org.example.project.claudelogs.ClaudeLogCatalog
import org.example.project.scheduler.domain.ContextDomain
import org.example.project.scheduler.domain.MdElement
import org.example.project.scheduler.domain.SearchDomain
import org.example.project.scheduler.domain.SearchDomain.AddedAction
import org.example.project.scheduler.domain.SearchDomain.Kind
import org.example.project.scheduler.domain.SearchDomain.Setting
import org.example.project.scheduler.model.AlarmEntry
import org.example.project.scheduler.model.ChoreEntry
import org.example.project.scheduler.model.QuotaEntry
import org.example.project.scheduler.model.Task
import org.example.project.scheduler.model.TaskId
import org.example.project.scheduler.model.TimerEntry
import org.example.project.scheduler.state.SchedulerState
import org.example.project.ui.actionRowTree
import org.example.project.ui.subRowsCopyText

/**
 * User rule 2026-11-27: *"I want the app to easily get the user any info they want"* — the example *"for any row, the user
 * wants to see its state everywhere in the app"*, handed to Claude Code by a row's deep copy. Each test is a question a user
 * could have, answered by ONE deep copy of the row that holds it: the text must say the value the app shows, not only the
 * row's title. What says a value is [SearchDomain.actionValueText], the one funnel.
 */
class AnyInfoTest {
    private val tab = 9.toChar()

    private val account: SchedulerState = SchedulerState.empty().let { empty ->
        empty.copy(
            alarms = listOf(
                AlarmEntry(id = "w", label = "Wake", timeOfDayMinutes = 7 * 60 + 30, days = setOf(DayOfWeek.MONDAY, DayOfWeek.FRIDAY), enabled = false),
                AlarmEntry(id = "n", label = "Nap", timeOfDayMinutes = 14 * 60),
            ),
            timers = listOf(TimerEntry(id = "t", label = "Tea", durationSeconds = 180)),
            quotas = listOf(QuotaEntry(id = "q", title = "Gemini", amount = 50.0, unit = "requests")),
            chores = listOf(ChoreEntry(title = "Water", spanDays = 2.0, id = "r", timeOfDayMinutes = 10 * 60)),
            mdElements = listOf(MdElement("m", "Why I wake early\nsecond line", of = listOf("Alarm/w"))),
            tasks = empty.tasks + listOf("a" to 30, "b" to 30, "c" to 45).associate { (id, m) ->
                TaskId(id) to Task(TaskId(id), id.uppercase(), minimumMinutes = m)
            },
        )
    }

    private fun copyOf(key: String): String = SearchDomain.elementsCopyText(account, listOf(key), 1, includeIds = false, excludeTitle = "")
    private fun row(key: String) = SearchDomain.resolve(account, listOf(key), { emptyMap() }).single()

    @Test
    fun when_does_my_alarm_ring_and_is_it_on() {
        val text = copyOf("Alarm/w")
        assertTrue(text.startsWith("Wake\n"), text)
        assertTrue("07:30" in text, text)
        assertTrue("MONDAY" in text && "FRIDAY" in text, text)
        assertTrue("${tab}- State: off\n" in text, text)
    }

    @Test
    fun how_long_is_my_timer_and_how_much_is_left() {
        val text = copyOf("Timer/t")
        assertTrue("3min 0s" in text, text)
        assertTrue("idle, 3min 0s left" in text, text)
    }

    @Test
    fun how_much_is_my_quota_and_how_often_my_reminder() {
        assertTrue("50 requests" in copyOf("Quota/q"), copyOf("Quota/q"))
        val reminder = copyOf("Reminder/r")
        assertTrue("every 2" in reminder && "10:00" in reminder, reminder)
    }

    @Test
    fun which_notes_are_attached_to_this_alarm() {
        val text = copyOf("Alarm/w")
        assertTrue("- Notes (md elements): Why I wake early" in text, text)
        assertTrue("md elements" !in copyOf("Alarm/n"), "nothing said where none is attached")
    }

    /** What only the UI says of an element (a history unit's facts) joins its lines, multi-line values indented under it. */
    @Test
    fun what_the_ui_says_of_an_element_is_copied_too() {
        val text = SearchDomain.elementsCopyText(
            account, listOf("Quota/q"), 1, includeIds = false, excludeTitle = "",
            moreLines = { listOf("Set of rules" to "rule 1\nrule 2") },
        )
        assertTrue("$tab- Set of rules: rule 1\n$tab${tab}rule 2\n" in text, text)
    }

    /** "What is the minimum time of every task?": "Contexts and states" groups them, each pair saying its value. */
    @Test
    fun what_is_the_minimum_time_of_every_task() {
        val tasks = SearchDomain.resolve(account, listOf("a", "b", "c").map { SearchDomain.taskKey(TaskId(it)) }, { emptyMap() })
        val contexts = SearchDomain.actionContexts(account, AddedAction.TaskMinimumTime, tasks)
        assertEquals(listOf(listOf("A", "B"), listOf("C")), contexts.map { c -> c.map { it.name } })
        assertEquals(listOf("30 min", "45 min"), contexts.map { SearchDomain.actionValueText(account, AddedAction.TaskMinimumTime, it.first()) })
        // An action over several elements: one value where they share it, else each element's.
        assertEquals(listOf("" to "30 min"), SearchDomain.actionRowValues(account, AddedAction.TaskMinimumTime, tasks.take(2)))
        assertEquals(listOf("A" to "30 min", "B" to "30 min", "C" to "45 min"), SearchDomain.actionRowValues(account, AddedAction.TaskMinimumTime, tasks))
    }

    /** "What time is this alarm set to?", asked of the action row itself: its deep copy says the time beside "Time". */
    @Test
    fun an_action_row_deep_copy_says_its_value() {
        val added = listOf(row("Alarm/w"))
        val window = SearchDomain.Config(kinds = setOf(Kind.Alarm), added = listOf("Alarm/w"), actionsGrouped = true)
        val sections = SearchDomain.actionsFor(SearchDomain.addedActions("", Kind.entries.toSet(), SearchDomain.actionKindsOf(added)), added)
        val groups = SearchDomain.shownActionGroups(sections, added, window, ClaudeLogCatalog.EMPTY)
        val all = SearchDomain.actionListRows(groups, window, expandAll = true)
        val time = all.first { it.action == AddedAction.AlarmTime }
        val text = subRowsCopyText(actionRowTree(all, time, account), Int.MAX_VALUE, includeIds = false, excludeTitle = "")
        assertTrue(text.startsWith(time.title + " — 07:30"), text)
        // The whole group copied at once says every value of the alarm.
        val group = subRowsCopyText(actionRowTree(all, all.first(), account), Int.MAX_VALUE, includeIds = false, excludeTitle = "")
        assertTrue("07:30" in group && "MONDAY" in group, group)
    }

    /**
     * "Where is 'parent distance' set, and to what?": two contexts holding different distances are two groups, each saying
     * its value — never one "on" group showing the first one's distance for both.
     */
    @Test
    fun a_setting_on_with_different_values_is_one_group_per_value() {
        val (one, two) = ContextDomain.CONTEXTS.take(2).map { it.id }
        fun withDistance(id: String, d: Int): (List<org.example.project.scheduler.domain.ContextDefault>) -> List<org.example.project.scheduler.domain.ContextDefault> = { defaults ->
            val own = ContextDomain.configOf(defaults, id)
            ContextDomain.written(defaults, id, own.copy(filters = own.filters.copy(parentsOf = setOf("Alarm/w"), parentDistance = d)))
        }
        val defaults = withDistance(two, 3)(withDistance(one, 2)(emptyList()))
        val groups = ContextDomain.groups(defaults, Setting.ParentDistance)
        val on = groups.filter { it.on }
        assertEquals(2, on.size, groups.toString())
        assertEquals(setOf("2", "3"), on.mapNotNull { it.value }.toSet())
        assertEquals(listOf(one), on.first { it.value == "2" }.contexts.map { it.id })
    }

    /**
     * The guard of the generalization: **every state "Contexts and states" groups elements by is one a deep copy can say**
     * — an action reading a value off an element ([SearchDomain.actionStateOf] other than the element itself) has a text
     * for it. A new action given a state but no text fails here.
     */
    @Test
    fun every_state_read_off_an_element_can_be_said() {
        val keys = listOf("Alarm/w", "Timer/t", "Quota/q", "Reminder/r") + SearchDomain.taskKey(TaskId("a"))
        val missing = ArrayList<String>()
        for (key in keys) {
            val r = row(key)
            for (action in AddedAction.entries.filter { it.section == r.kind }) {
                if (SearchDomain.isSetAction(action)) continue
                val read = SearchDomain.actionStateOf(account, action, r)
                if (read == Unit || read == SearchDomain.keyOf(r)) continue
                if (SearchDomain.actionValueText(account, action, r) == null && action.label != "Title") missing += "$key ${action.name}"
            }
        }
        assertTrue(missing.isEmpty(), "states read but never said: $missing")
        assertNotNull(SearchDomain.actionValueText(account, AddedAction.ElementParents, row("Alarm/w")))
    }
}
