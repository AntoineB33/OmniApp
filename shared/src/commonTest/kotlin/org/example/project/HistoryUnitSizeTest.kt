package org.example.project

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.example.project.scheduler.model.PanelPins
import org.example.project.scheduler.model.TaskTimeRange
import org.example.project.scheduler.persistence.HistoryRow
import org.example.project.scheduler.persistence.PersistedSnapshot
import org.example.project.scheduler.persistence.SchedulerStateCodec
import org.example.project.scheduler.state.HistoryWindow
import org.example.project.scheduler.state.HistoryCategory
import org.example.project.scheduler.state.SchedulerIntent
import org.example.project.scheduler.state.SchedulerReducer
import org.example.project.scheduler.state.SchedulerState

/**
 * `docs/invariants/persistence.md` § *A History Unit records what it changed*: **a unit costs what the change
 * touched, not what the account holds.**
 *
 * A unit used to carry whole copies — the release account's units averaged 396 KB for a tree change and 339 KB for a
 * calendar edit (2026-09-17) — and every unit is written to the device's database AND the account's server. On an
 * account the size of the release one, every everyday gesture must now write under [MAX_UNIT_BYTES].
 */
class HistoryUnitSizeTest {
    private val MAX_UNIT_BYTES = 2_000
    private val NOW = 1_788_343_200_000L

    /** 20 areas of 10 tasks: the release account's size (224 tasks). */
    private fun account(): SchedulerState {
        var s = SchedulerState.empty()
        repeat(20) { i -> s = SchedulerReducer.reduce(s, SchedulerIntent.SetCellTitle(s.lists[s.rootListId]!!.cellIds[i], "Area $i")) }
        for (cell in s.lists[s.rootListId]!!.cellIds.take(20)) {
            s = SchedulerReducer.reduce(s, SchedulerIntent.ToggleExpand(cell))
            val task = s.cells[cell]!!.taskId!!
            repeat(10) { c ->
                val list = s.lists[s.tasks[task]!!.childListId!!]!!
                s = SchedulerReducer.reduce(s, SchedulerIntent.SetCellTitle(list.cellIds[c], "Task $c of ${s.tasks[task]!!.title}"))
            }
        }
        return s
    }

    /** The serialized size of the newest unit of [category] in [state], as the store and the server receive it. */
    private fun newestUnitBytes(state: SchedulerState, category: HistoryCategory): Int {
        val rows = SchedulerStateCodec.encodeSnapshot(state).history.filter { it.category == category.name }
        return assertNotNull(rows.maxByOrNull { it.ordinal }).deltaJson.length
    }

    private fun check(what: String, before: SchedulerState, after: SchedulerState, category: HistoryCategory = HistoryCategory.Main) {
        val units = after.histories.forCategory(category).units.size - before.histories.forCategory(category).units.size
        assertTrue(units >= 1 || after.histories.forCategory(category).pointer >= 0, "$what made no $category unit")
        val bytes = newestUnitBytes(after, category)
        assertTrue(bytes <= MAX_UNIT_BYTES, "$what wrote a $bytes-byte unit (limit $MAX_UNIT_BYTES)")
    }

    @Test
    fun every_everyday_unit_is_small_on_an_account_the_size_of_the_release_one() {
        val s0 = account()
        val cells = s0.cells.values.filter { it.taskId != null }.map { it.id }
        val task = s0.cells[cells[57]]!!.taskId!!

        val renamed = SchedulerReducer.reduce(s0, SchedulerIntent.SetCellTitle(cells[57], "Renamed task"))
        check("renaming a task", s0, renamed)

        val emptyCell = s0.lists[s0.tasks[task]!!.childListId ?: s0.cells[cells[57]]!!.parentListId]!!.cellIds.last()
        val created = SchedulerReducer.reduce(s0, SchedulerIntent.SetCellTitle(emptyCell, "Brand new task"))
        check("creating a task", s0, created)

        val minimum = SchedulerReducer.reduce(s0, SchedulerIntent.SetTaskMinimumTime(task, 25))
        check("setting a minimum time", s0, minimum)

        val expanded = SchedulerReducer.reduce(s0, SchedulerIntent.ToggleExpand(cells[3]))
        check("expanding a row", s0, expanded)

        var calendar = s0
        repeat(300) { i ->
            calendar = SchedulerReducer.reduce(
                calendar,
                SchedulerIntent.AddTaskPanel(task, "block $i", NOW + i * 3_600_000L, NOW + i * 3_600_000L + 1_800_000L, PanelPins(existence = true)),
            )
        }
        val added = SchedulerReducer.reduce(
            calendar,
            SchedulerIntent.AddTaskPanel(task, "one more", NOW - 3_600_000L, NOW - 1_800_000L, PanelPins(existence = true)),
        )
        check("adding a calendar block beside 300 others", calendar, added, HistoryCategory.Calendar)

        val clicked = SchedulerReducer.reduce(s0, SchedulerIntent.ClickCell(cells[9], ctrl = false, shift = false, visibleOrder = cells))
        check("selecting a cell", s0, clicked, HistoryCategory.Selection)

        val focused = SchedulerReducer.reduce(s0, SchedulerIntent.FocusWindow(HistoryWindow.Calendar))
        check("focusing a window", s0, focused, HistoryCategory.WindowNav)
    }

    @Test
    fun a_record_unit_carries_the_periods_it_changed_not_the_whole_record() {
        // A task with a long record — months of banked work — gains one period.
        var s = account()
        val task = s.tasks.keys.first { s.tasks[it]!!.title.startsWith("Task 3") }
        val long = (0 until 500).map { TaskTimeRange(NOW - (it + 1) * 7_200_000L, NOW - (it + 1) * 7_200_000L + 3_600_000L) }
        s = s.copy(tasks = s.tasks + (task to s.tasks[task]!!.copy(record = long)))
        val delta = org.example.project.scheduler.state.RecordDelta(
            mapOf(task to long),
            mapOf(task to long + TaskTimeRange(NOW, NOW + 1_800_000L)),
        )
        assertEquals(mapOf(task to listOf(TaskTimeRange(NOW, NOW + 1_800_000L))), delta.changes.added)
        assertTrue(delta.changes.removed.isEmpty())
        val redone = delta.redo(s)
        assertEquals(501, redone.tasks[task]!!.record.size)
        assertEquals(long.sortedBy { it.startEpochMillis }, delta.undo(redone).tasks[task]!!.record)
    }

    /**
     * CLAUDE.md *Persisted-DB compatibility*: a unit an older build wrote holds the WHOLE tree on both sides. It must
     * still load — converted into the change it describes — and undo exactly as before.
     */
    @Test
    fun a_unit_written_with_whole_trees_still_loads_and_undoes() {
        val before = account()
        val cells = before.cells.values.filter { it.taskId != null }.map { it.id }
        val after = SchedulerReducer.reduce(before, SchedulerIntent.SetCellTitle(cells[42], "Renamed by an older build"))

        // The old shape: `treeMutation` with every list, cell and task of the tree on each side.
        val json = Json { ignoreUnknownKeys = true }
        fun wholeTree(state: SchedulerState): JsonObject {
            val payload = json.parseToJsonElement(SchedulerStateCodec.encodeSnapshot(state).statePayload).jsonObject
            return buildJsonObject {
                put("lists", payload.getValue("lists"))
                put("cells", payload.getValue("cells"))
                put("tasks", payload.getValue("tasks"))
                put("nextTaskCounter", state.nextTaskCounter)
                put("nextCellCounter", state.nextCellCounter)
            }
        }
        val legacyUnit = buildJsonObject {
            put("type", "treeMutation")
            put("before", wholeTree(before))
            put("after", wholeTree(after))
            put("label", "Set title")
        }.toString()

        val encoded = SchedulerStateCodec.encodeSnapshot(after)
        val mainRows = encoded.history.filter { it.category == HistoryCategory.Main.name }
        val newest = mainRows.maxBy { it.ordinal }
        val legacy =
            PersistedSnapshot(
                encoded.statePayload,
                encoded.history.filterNot { it === newest } + HistoryRow(newest.category, newest.ordinal, newest.timeMillis, newest.chronoId, newest.debugTainted, legacyUnit, newest.window),
                encoded.pointers,
            )
        val loaded = assertNotNull(SchedulerStateCodec.decodeSnapshot(legacy))
        val undone = SchedulerReducer.reduce(loaded, SchedulerIntent.Undo)
        assertEquals(before.tasks.mapValues { it.value.title }, undone.tasks.mapValues { it.value.title })
        assertEquals(before.cells, undone.cells)
        assertEquals(before.lists, undone.lists)
        // Written back, the converted unit is the small one.
        assertTrue(
            SchedulerStateCodec.encodeSnapshot(loaded).history.filter { it.category == HistoryCategory.Main.name }.maxBy { it.ordinal }.deltaJson.length <= MAX_UNIT_BYTES,
        )
    }
}
