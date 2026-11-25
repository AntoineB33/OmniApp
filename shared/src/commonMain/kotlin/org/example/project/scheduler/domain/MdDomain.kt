package org.example.project.scheduler.domain

import org.example.project.scheduler.model.TaskId
import org.example.project.scheduler.state.SchedulerState

/**
 * User rule 2026-11-19: *"Add the md element type. In the action section for an element (like a task element), the user can
 * add md elements. It replaces the current text of a task element. Association between elements doesn't mean one is the
 * child of the other."*
 *
 * **An md element is a markdown text** ([text]), **associated with the elements it is about** ([of], their Search keys,
 * `Kind/id`). An association is no link of [ElementTree]: neither is a parent nor a child of the other, and nothing in the
 * tree of parents and children reads it.
 */
data class MdElement(
    val id: String,
    val text: String = "",
    /** The keys of the elements this one is associated with, in the order it was associated with them. */
    val of: List<String> = emptyList(),
)

object MdDomain {
    /**
     * User rule 2026-11-20: the md element the scheduler's menu shows alone by default, *"one called life context"*. A fixed
     * id, so two devices that make it make the same row; made at the first press of its button ([withLifeContext]).
     */
    const val LIFE_CONTEXT_ID: String = "md-life-context"
    const val LIFE_CONTEXT_TEXT: String = "# Life context"

    /** [elements] with the Life context md element, or null when it is there already (renamed or not: its id says it). */
    fun withLifeContext(elements: List<MdElement>): List<MdElement>? =
        if (elements.any { it.id == LIFE_CONTEXT_ID }) null else elements + MdElement(LIFE_CONTEXT_ID, LIFE_CONTEXT_TEXT)

    /** The first free `md-N`. */
    fun mintId(used: Collection<String>): String {
        val taken = used.toHashSet()
        var n = 1
        while ("md-$n" in taken) n++
        return "md-$n"
    }

    /** What a Search row names it by: its first line that says something, without the markdown marks in front of it. */
    fun title(element: MdElement): String =
        element.text.lineSequence().map { it.trim().trimStart('#', '-', '*', '>', ' ').trim() }.firstOrNull { it.isNotEmpty() }.orEmpty()

    /** The md elements associated with [key], in the account's order. */
    fun associatedWith(state: SchedulerState, key: String): List<MdElement> = state.mdElements.filter { key in it.of }

    /** The md elements associated with any of [keys], each once. */
    fun associatedWithAny(state: SchedulerState, keys: Collection<String>): List<MdElement> {
        val wanted = keys.toHashSet()
        return state.mdElements.filter { md -> md.of.any { it in wanted } }
    }

    /** [elements] with a new md element of [text] associated with [keys], under [id]. */
    fun added(elements: List<MdElement>, id: String, keys: List<String>, text: String = ""): List<MdElement> =
        elements + MdElement(id = id, text = text, of = keys.distinct())

    /** [elements] with [id] no longer associated with any of [keys]: the element itself stays, an md of its own. */
    fun dissociated(elements: List<MdElement>, id: String, keys: Collection<String>): List<MdElement> =
        elements.map { if (it.id == id) it.copy(of = it.of - keys.toSet()) else it }

    /** The id the text a task held is lifted into: a function of the task, so two devices lift it into the same row. */
    fun liftedId(taskId: TaskId): String = "md-task-" + taskId.value

    /**
     * User rule 2026-11-19, *"it replaces the current text of a task element"*: **the text a task holds is moved into an md
     * element associated with it** — on decode (what an older build wrote) and wherever a task is made with a text (a paste,
     * the default sub-tree, a copy). [taskIds] null walks every task. A task whose lifted element is already there (another
     * device lifted it) only loses its text. The ONE place a task's text becomes an md element.
     */
    fun liftTaskTexts(state: SchedulerState, taskIds: Collection<TaskId>? = null): SchedulerState {
        val ids = taskIds ?: state.tasks.keys
        val texted = ids.mapNotNull { id -> state.tasks[id]?.takeIf { it.text.isNotEmpty() } }
        if (texted.isEmpty()) return state
        val present = state.mdElements.mapTo(HashSet()) { it.id }
        // On decode another device may have lifted the same text already; a task made with a text later is given a new one.
        val lifted =
            texted.filter { taskIds != null || liftedId(it.id) !in present }.map { task ->
                val id = liftedId(task.id).takeIf { it !in present } ?: mintId(present)
                present += id
                MdElement(id = id, text = task.text, of = listOf(SearchDomain.taskKey(task.id)))
            }
        val tasks = state.tasks + texted.associate { it.id to it.copy(text = "") }
        return state.copy(tasks = tasks, mdElements = state.mdElements + lifted)
    }

    /** What a copy of the tree carries as a task's text (`- text:`): its md elements' texts, one after the other. */
    fun copiedText(state: SchedulerState, taskId: TaskId): String =
        associatedWith(state, SearchDomain.taskKey(taskId)).map { it.text }.filter { it.isNotBlank() }.joinToString("\n\n")

    /** A copy of a task ([from]) is associated with what it is associated with ([to] beside it). */
    fun withCopyAssociated(state: SchedulerState, from: TaskId, to: TaskId): SchedulerState {
        val fromKey = SearchDomain.taskKey(from)
        val toKey = SearchDomain.taskKey(to)
        if (state.mdElements.none { fromKey in it.of }) return state
        return state.copy(mdElements = state.mdElements.map { if (fromKey in it.of && toKey !in it.of) it.copy(of = it.of + toKey) else it })
    }

    /**
     * User rule 2026-11-21: *"Just like in Obsidian, in an md document, the user can add [[link to another md document]]. The
     * user can define if this link makes the pointed md file a child of this md file (choose how it is indicated)."*
     *
     * **A link is `[[Name]]`, Obsidian's own** (`[[Name|shown]]` and `[[Name#heading]]` read as Obsidian reads them); **a
     * child link is the same with [CHILD_MARK] first inside the brackets, `[[>Name]]`**: the md element it names is a child
     * of the one whose text holds it. [target] is the name, [shown] what the text draws, [range] where it stands in the
     * text, brackets included.
     */
    data class MdLink(val target: String, val child: Boolean, val shown: String, val range: IntRange)

    /** What makes a link a child link: first inside its brackets, `[[>Name]]`. */
    const val CHILD_MARK: Char = '>'

    private val LINK = Regex("""\[\[([^\[\]\n]+?)]]""")

    /** The links [text] holds, in its order. A link with no name (`[[>]]`, `[[ ]]`) is text. */
    fun links(text: String): List<MdLink> =
        if ("[[" !in text) emptyList()
        else LINK.findAll(text).mapNotNull { match ->
            val inner = match.groupValues[1]
            val child = inner.startsWith(CHILD_MARK)
            val body = if (child) inner.drop(1) else inner
            val name = body.substringBefore('|').substringBefore('#').trim()
            if (name.isEmpty()) return@mapNotNull null
            val shown = body.substringAfter('|', "").trim().ifEmpty { body.substringBefore('|').trim() }
            MdLink(name, child, shown, match.range)
        }.toList()

    /**
     * User rule 2026-11-25 (*"In an md file, when writing [[ or [[>, there must be a pop-up window to show all the md files
     * except the current one, with the md creation row at the top."*): **the link being written at the caret** — `[[` (or
     * `[[>`) before [caret] on its line, not closed before it, and what is typed after it ([typed]); [nameStart] is where
     * the name begins. Null where the caret is in no open link.
     */
    data class OpenLink(val start: Int, val nameStart: Int, val child: Boolean, val typed: String)

    fun openLinkAt(text: String, caret: Int): OpenLink? {
        if (caret < 2 || caret > text.length) return null
        val start = text.lastIndexOf("[[", caret - 2)
        if (start < 0) return null
        val between = text.substring(start + 2, caret)
        if ('\n' in between || ']' in between || '[' in between || '|' in between || '#' in between) return null
        val child = between.startsWith(CHILD_MARK)
        val nameStart = start + 2 + if (child) 1 else 0
        return OpenLink(start, nameStart, child, text.substring(nameStart, caret))
    }

    /**
     * [text] with the open [link] at [caret] completed to [name] and closed (its `]]` written unless it follows already),
     * and the caret after it.
     */
    fun completeLink(text: String, caret: Int, link: OpenLink, name: String): Pair<String, Int> {
        val after = text.substring(caret)
        val closing = if (after.startsWith("]]")) "" else "]]"
        val next = text.substring(0, link.nameStart) + name + closing + after
        return next to (link.nameStart + name.length + 2)
    }

    /** The name a link to [element] is written with: its title, else its id ([resolve] reads both). */
    fun linkName(element: MdElement): String = title(element).ifEmpty { element.id }

    /**
     * The md element a link's [name] points at: by its title ([title]), else by its file's name in the folder Claude Code
     * is given ([fileNames]), else by its id — case aside, the account's first. Null: no md element of that name (yet).
     */
    fun resolve(elements: List<MdElement>, name: String): MdElement? {
        val wanted = name.trim().removeSuffix(".md").lowercase()
        elements.firstOrNull { title(it).lowercase() == wanted }?.let { return it }
        val byFile = fileNames(elements).entries.firstOrNull { it.value.removeSuffix(".md").lowercase() == wanted }?.key
        return elements.firstOrNull { it.id == byFile } ?: elements.firstOrNull { it.id.lowercase() == wanted }
    }

    /** The Search key of an md element ([SearchDomain.keyOf]'s form). */
    fun keyOf(id: String): String = SearchDomain.Kind.Md.name + "/" + id

    /**
     * The md elements [md]'s text makes its children (`[[>Name]]`), each once, in the text's order, never itself. Derived
     * from the text, which is what is stored and synced: [ElementTree] reads an md element's children and parents here.
     */
    fun childIdsOf(elements: List<MdElement>, md: MdElement): List<String> =
        links(md.text).filter { it.child }.mapNotNull { resolve(elements, it.target)?.id }.filter { it != md.id }.distinct()

    /** Every md element's children by its text ([childIdsOf]), as `child key → parent keys`: the md part of [ElementTree.parentIndex]. */
    fun parentIndex(elements: List<MdElement>): Map<String, Set<String>> {
        if (elements.none { "[[" in it.text }) return emptyMap()
        val out = HashMap<String, MutableSet<String>>()
        for (md in elements) for (child in childIdsOf(elements, md)) out.getOrPut(keyOf(child), ::LinkedHashSet).add(keyOf(md.id))
        return out
    }

    /** The md elements whose text makes [mdId] their child. */
    fun parentIdsOf(elements: List<MdElement>, mdId: String): List<String> =
        elements.filter { "[[" in it.text && mdId in childIdsOf(elements, it) }.map { it.id }

    /**
     * The file each md element is in the folder Claude Code is opened in ([claudeFolderFiles]): its title made a legal file
     * name on every OS, `.md` after it — so `[[Title]]` names the file as Obsidian reads it; its id where it has no title;
     * a second one of the same name is told apart by its id.
     */
    fun fileNames(elements: List<MdElement>): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        val taken = HashSet<String>()
        taken += CLAUDE_GUIDE_FILE.removeSuffix(".md").lowercase()
        for (md in elements) {
            val base = title(md).replace(ILLEGAL_IN_FILE_NAME, " ").replace(SPACES, " ").trim().trimEnd('.').take(120).trim().ifEmpty { md.id }
            val name = if (taken.add(base.lowercase())) base else "$base (${md.id})".also { taken.add(it.lowercase()) }
            out[md.id] = "$name.md"
        }
        return out
    }

    private val ILLEGAL_IN_FILE_NAME = Regex("""[\\/:*?"<>|\x00-\x1F]""")
    private val SPACES = Regex("""\s+""")

    /** The guide Claude Code reads first in that folder ([claudeFolderFiles]); never an md element's file ([fileNames]). */
    const val CLAUDE_GUIDE_FILE: String = "CLAUDE.md"

    /**
     * User rule 2026-11-21: *"In the actions of an md document, there must be one to open Claude Code to directly ask it
     * questions that Claude Code will easily access the graph of md files, starting in the directory path of the folder
     * containing all the md files the action is associated with."*
     *
     * **The folder Claude Code is opened in, as files: `name → text`.** Every md element of the account, one file each
     * ([fileNames]), its text as it is, so every link of the graph names a file beside it; and [CLAUDE_GUIDE_FILE], which
     * Claude Code reads first: what the folder is, how a link and a child link read, the files the action was opened on
     * ([startIds]), the graph of links and children as the app resolves it, and what each md element is associated with
     * in the app. Pure: the platform writes it ([org.example.project.scheduler.platform.openClaudeCodeOn]).
     */
    fun claudeFolderFiles(state: SchedulerState, startIds: List<String>): Map<String, String> {
        val elements = state.mdElements
        val names = fileNames(elements)
        val files = LinkedHashMap<String, String>()
        for (md in elements) files[names.getValue(md.id)] = md.text
        val guide = StringBuilder()
        guide.appendLine("# The md elements of an OmniApp account")
        guide.appendLine()
        guide.appendLine("Every other `.md` file here is one md element of the OmniApp app, its text as the app holds it. The")
        guide.appendLine("folder is written again each time the app's \"Ask Claude Code\" action is pressed: **an edit made here")
        guide.appendLine("does not reach the app**, and the next press overwrites it. Answer the user's questions from these files.")
        guide.appendLine()
        guide.appendLine("## Links")
        guide.appendLine()
        guide.appendLine("- `[[Name]]` links to the file `Name.md`, as in Obsidian (`[[Name|shown text]]`, `[[Name#heading]]` too).")
        guide.appendLine("- `[[${CHILD_MARK}Name]]` is a **child link**: `Name.md` is a child of the file holding the link. A plain")
        guide.appendLine("  link makes neither a parent nor a child.")
        guide.appendLine()
        val start = startIds.mapNotNull { names[it] }.distinct()
        if (start.isNotEmpty()) {
            guide.appendLine("## Where the user started")
            guide.appendLine()
            guide.appendLine("The action was opened on these files: start from them, and follow their links.")
            guide.appendLine()
            start.forEach { guide.appendLine("- `$it`") }
            guide.appendLine()
        }
        val graph = elements.mapNotNull { md ->
            val links = links(md.text)
            val linked = links.mapNotNull { link -> resolve(elements, link.target)?.takeIf { it.id != md.id }?.let { it.id to link.child } }.distinct()
            val unresolved = links.filter { resolve(elements, it.target) == null }.map { it.target }.distinct()
            if (linked.isEmpty() && unresolved.isEmpty()) null else Triple(md, linked, unresolved)
        }
        if (graph.isNotEmpty()) {
            guide.appendLine("## The graph")
            guide.appendLine()
            for ((md, linked, unresolved) in graph) {
                guide.appendLine("- `${names.getValue(md.id)}`")
                val children = linked.filter { it.second }.mapNotNull { names[it.first] }.distinct()
                val plain = linked.filter { !it.second }.mapNotNull { names[it.first] }.distinct() - children.toSet()
                if (children.isNotEmpty()) guide.appendLine("  - children: " + children.joinToString { "`$it`" })
                if (plain.isNotEmpty()) guide.appendLine("  - links to: " + plain.joinToString { "`$it`" })
                if (unresolved.isNotEmpty()) guide.appendLine("  - links to no file yet: " + unresolved.joinToString { "`$it`" })
            }
            guide.appendLine()
        }
        val associated = elements.filter { it.of.isNotEmpty() }
        if (associated.isNotEmpty()) {
            val rows = SearchDomain.resolve(state, associated.flatMap { it.of }.distinct()).associateBy(SearchDomain::keyOf)
            guide.appendLine("## What each file is about in the app")
            guide.appendLine()
            guide.appendLine("An md element is associated with elements of the app (tasks, alarms…): neither a parent nor a child.")
            guide.appendLine()
            for (md in associated) {
                val about = md.of.joinToString { key -> rows[key]?.let { it.kind.label + " \"" + it.name + "\"" } ?: key }
                guide.appendLine("- `${names.getValue(md.id)}`: $about")
            }
            guide.appendLine()
        }
        files[CLAUDE_GUIDE_FILE] = guide.toString()
        return files
    }
}
