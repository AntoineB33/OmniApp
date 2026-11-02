package org.example.project.scheduler.domain

import kotlin.random.Random
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** What an entry of the life plan is. Stored by name: an entry is never renamed. */
enum class LifePlanKind {
    /** A row of the "Steps" list; its [LifePlanEntry.parent] is the step it is a sub-element of, or null. */
    Step,

    /** A tab of the "Problems" section; its [LifePlanEntry.text] is its title. */
    Tab,

    /** One line of what is written in a tab, its [LifePlanEntry.parent]. */
    Line,
}

/**
 * User rule 2026-11-02: **one row of the life plan**. Authoritative, persisted and synced, and the plan is one flat list of
 * these: a step, a tab, or a LINE of a tab. A tab is never one row holding its text: the server stores a row per entry and a
 * History Unit is a diff of the rows touched, so a tab written over months would otherwise rewrite and copy its whole text at
 * every edit (`server-quota.md`). Typing in a line changes that one row.
 *
 * The order is [rank] among the entries of one [parent], never the place in the list: the rows of a sync come back in no
 * order of theirs. Two entries of one rank stand by id.
 */
data class LifePlanEntry(
    val id: String,
    val kind: LifePlanKind,
    val parent: String? = null,
    val rank: Double = 0.0,
    val text: String = "",
)

/** The choices of the window's drop-down. Stored by name on this device ([LifePlanView.section]). */
enum class LifePlanSection(val label: String) {
    Steps("Steps"),
    Problems("Problems"),
    CareerEvolution("Career evolution"),
    CrisisResilience("Crisis resilience"),
    ;

    companion object {
        fun of(name: String?): LifePlanSection = entries.firstOrNull { it.name == name } ?: Steps
    }
}

/**
 * How one Life plan window is looked at: its [section], its open [tab] and the steps [expanded]. Local-only view state, kept
 * per frame id on the [LifePlanDomain.PLACEMENT_ID] row and never synced.
 */
@Serializable
data class LifePlanView(
    val section: String? = null,
    val tab: String? = null,
    val expanded: List<String> = emptyList(),
)

/** One drawn row of the "Steps" list. */
data class LifePlanStepRow(val step: LifePlanEntry, val depth: Int, val hasChildren: Boolean, val expanded: Boolean)

object LifePlanDomain {
    /** The placement row the windows' [LifePlanView]s are kept on. No window has this frame id. */
    const val PLACEMENT_ID: String = "LifePlanViews"

    /** Below this gap between two ranks they are numbered again, a Double having no more room between them. */
    private const val SMALLEST_RANK_GAP: Double = 1e-9

    private val ORDER = compareBy<LifePlanEntry>({ it.rank }, { it.id })

    private val json = Json { ignoreUnknownKeys = true }

    @Serializable
    private data class StoredViews(val views: Map<String, LifePlanView> = emptyMap())

    fun encodeViews(views: Map<String, LifePlanView>): String = json.encodeToString(StoredViews.serializer(), StoredViews(views))

    /** [encodeViews]' reverse; nothing stored, or nothing readable, is every window as it opens. */
    fun decodeViews(text: String?): Map<String, LifePlanView> =
        text?.let { runCatching { json.decodeFromString(StoredViews.serializer(), it).views }.getOrNull() }.orEmpty()

    /** The tabs of the "Problems" section, in their order. */
    fun tabs(entries: List<LifePlanEntry>): List<LifePlanEntry> = entries.filter { it.kind == LifePlanKind.Tab }.sortedWith(ORDER)

    /** The tab [id] where it still exists, else the first: a window can name a tab deleted since. */
    fun tabOf(entries: List<LifePlanEntry>, id: String?): LifePlanEntry? = tabs(entries).let { tabs -> tabs.firstOrNull { it.id == id } ?: tabs.firstOrNull() }

    private fun linesOf(entries: List<LifePlanEntry>, tab: String): List<LifePlanEntry> =
        entries.filter { it.kind == LifePlanKind.Line && it.parent == tab }.sortedWith(ORDER)

    /** What is written in the tab [tab]. */
    fun textOf(entries: List<LifePlanEntry>, tab: String): String = linesOf(entries, tab).joinToString("\n") { it.text }

    /** The "Steps" list as drawn: tree order, the sub-elements of a step not [expanded] left out. */
    fun stepRows(entries: List<LifePlanEntry>, expanded: Set<String>): List<LifePlanStepRow> {
        val byParent = entries.filter { it.kind == LifePlanKind.Step }.groupBy { it.parent }
        val rows = ArrayList<LifePlanStepRow>()
        fun walk(parent: String?, depth: Int) {
            for (step in byParent[parent].orEmpty().sortedWith(ORDER)) {
                val children = step.id in byParent
                val open = children && step.id in expanded
                rows += LifePlanStepRow(step, depth, children, open)
                if (open) walk(step.id, depth + 1)
            }
        }
        walk(null, 0)
        return rows
    }

    /** A new id, none of [entries]'. Random, not a count: two devices adding offline must not make one row of two entries. */
    fun mintId(entries: List<LifePlanEntry>, random: Random = Random.Default): String =
        freshId(entries.mapTo(HashSet()) { it.id }, random)

    private fun freshId(used: MutableSet<String>, random: Random): String =
        generateSequence { "lp-" + random.nextLong(Long.MAX_VALUE).toString(36) }.first { used.add(it) }

    private fun nextRank(entries: List<LifePlanEntry>, kind: LifePlanKind, parent: String?): Double =
        (entries.filter { it.kind == kind && it.parent == parent }.maxOfOrNull { it.rank } ?: 0.0) + 1.0

    /** [entries] with an empty step at the end of [parent]'s (null: of the list), and its id. */
    fun addedStep(entries: List<LifePlanEntry>, parent: String?, random: Random = Random.Default): Pair<List<LifePlanEntry>, String> {
        val id = mintId(entries, random)
        return entries + LifePlanEntry(id, LifePlanKind.Step, parent, nextRank(entries, LifePlanKind.Step, parent)) to id
    }

    /** [entries] with a new tab at the end, named by its place, and its id. */
    fun addedTab(entries: List<LifePlanEntry>, random: Random = Random.Default): Pair<List<LifePlanEntry>, String> {
        val id = mintId(entries, random)
        val title = "Tab " + (tabs(entries).size + 1)
        return entries + LifePlanEntry(id, LifePlanKind.Tab, rank = nextRank(entries, LifePlanKind.Tab, null), text = title) to id
    }

    /** [entries] with the step or tab [id] named [text]. */
    fun titled(entries: List<LifePlanEntry>, id: String, text: String): List<LifePlanEntry> =
        entries.map { if (it.id == id) it.copy(text = text) else it }

    /** [entries] without [id] and whatever stands under it: a step's sub-elements, a tab's lines. */
    fun removed(entries: List<LifePlanEntry>, id: String): List<LifePlanEntry> {
        val gone = hashSetOf(id)
        val byParent = entries.groupBy { it.parent }
        val pending = ArrayDeque(listOf(id))
        while (pending.isNotEmpty()) {
            for (child in byParent[pending.removeFirst()].orEmpty()) if (gone.add(child.id)) pending.addLast(child.id)
        }
        return entries.filterNot { it.id in gone }
    }

    /**
     * [entries] with [text] written in the tab [tab], **line by line, touching only the rows that changed**: the lines
     * equal at the start and at the end are left as they are, those between take the new text under the ids they had, and
     * what is left over is added or removed. So typing in a line changes one row, and Enter adds one.
     */
    fun withText(entries: List<LifePlanEntry>, tab: String, text: String, random: Random = Random.Default): List<LifePlanEntry> {
        val old = linesOf(entries, tab)
        val lines = text.replace("\r\n", "\n").split('\n')
        if (old.map { it.text } == lines || (old.isEmpty() && lines == listOf(""))) return entries
        val shortest = minOf(old.size, lines.size)
        var start = 0
        while (start < shortest && old[start].text == lines[start]) start++
        var end = 0
        while (end < shortest - start && old[old.size - 1 - end].text == lines[lines.size - 1 - end]) end++
        val oldMiddle = old.subList(start, old.size - end)
        val newMiddle = lines.subList(start, lines.size - end)
        val reused = minOf(oldMiddle.size, newMiddle.size)
        val kept = oldMiddle.subList(0, reused).mapIndexed { i, line -> line.copy(text = newMiddle[i]) }
        val added = newMiddle.subList(reused, newMiddle.size)
        val below = (kept.lastOrNull() ?: old.getOrNull(start - 1))?.rank
        val above = old.getOrNull(old.size - end)?.rank
        val low = below ?: above?.minus(added.size + 1) ?: 0.0
        val high = above ?: (low + added.size + 1)
        val gap = (high - low) / (added.size + 1)
        val used = entries.mapTo(HashSet()) { it.id }
        val fresh = added.mapIndexed { i, line -> LifePlanEntry(freshId(used, random), LifePlanKind.Line, tab, low + gap * (i + 1), line) }
        val freshIds = fresh.mapTo(HashSet()) { it.id }
        val ordered = old.subList(0, start) + kept + fresh + old.subList(old.size - end, old.size)
        // A new line keeps being put between the same two: at the end of what a Double can tell apart, every line is numbered again.
        val written = if (fresh.isNotEmpty() && gap < SMALLEST_RANK_GAP) ordered.mapIndexed { i, line -> line.copy(rank = i + 1.0) } else ordered
        val byId = written.associateBy { it.id }
        val gone = oldMiddle.subList(reused, oldMiddle.size).mapTo(HashSet()) { it.id }
        return entries.filterNot { it.id in gone }.map { byId[it.id] ?: it } + written.filter { it.id in freshIds }
    }

    /**
     * [entries] without what can no longer be reached: a line whose tab is gone, a step under a step that is gone. Two
     * devices can make one: a line added on one while the other deleted its tab, each row merged on its own. The deletion wins.
     */
    fun healed(entries: List<LifePlanEntry>): List<LifePlanEntry> {
        val byId = entries.associateBy { it.id }
        val reached = HashMap<String, Boolean>()
        fun holds(entry: LifePlanEntry, seen: Set<String>): Boolean =
            reached.getOrPut(entry.id) {
                val parent = entry.parent?.let(byId::get)
                when (entry.kind) {
                    LifePlanKind.Tab -> entry.parent == null
                    LifePlanKind.Line -> parent?.kind == LifePlanKind.Tab
                    LifePlanKind.Step ->
                        entry.parent == null ||
                            (parent?.kind == LifePlanKind.Step && parent.id !in seen && holds(parent, seen + entry.id))
                }
            }
        return entries.filter { holds(it, emptySet()) }.takeIf { it.size != entries.size } ?: entries
    }
}
