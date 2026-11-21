package org.example.project

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.example.project.scheduler.domain.ElementTree
import org.example.project.scheduler.domain.MdDomain
import org.example.project.scheduler.domain.MdElement
import org.example.project.scheduler.domain.SearchDomain
import org.example.project.scheduler.state.SchedulerState
import org.example.project.ui.renderMarkdown

/**
 * User rule 2026-11-21: *"Just like in Obsidian, in an md document, the user can add [[link to another md document]]. The
 * user can define if this link makes the pointed md file a child of this md file (choose how it is indicated). In the
 * actions of an md document, there must be one to open Claude Code to directly ask it questions that Claude Code will easily
 * access the graph of md files, starting in the directory path of the folder containing all the md files the action is
 * associated with."* — `[[Name]]` links, `[[>Name]]` a child; [MdDomain.claudeFolderFiles] the folder.
 */
class MdLinkTest {
    private val home = MdElement("md-1", "# Home\nSee [[Projects]] and [[>Health|my health]] and [[>projects#Now]].")
    private val projects = MdElement("md-2", "# Projects\n[[>Kitchen]] back to [[Home]] and [[Nowhere]]")
    private val health = MdElement("md-3", "# Health")
    private val kitchen = MdElement("md-4", "Kitchen")
    private val state = SchedulerState.empty().copy(mdElements = listOf(home, projects, health, kitchen))
    private fun key(md: MdElement) = MdDomain.keyOf(md.id)

    @Test
    fun a_link_is_obsidians_and_a_child_link_has_its_mark_first() {
        val links = MdDomain.links(home.text)
        assertEquals(listOf("Projects", "Health", "projects"), links.map { it.target })
        assertEquals(listOf(false, true, true), links.map { it.child })
        assertEquals("my health", links[1].shown)
        assertTrue(MdDomain.links("[[]] [[>]] [[ ]] [not]").isEmpty(), "a link with no name is text")
        // Resolved by title, case aside; a name of no md element is no link to anything.
        assertEquals("md-2", MdDomain.resolve(state.mdElements, "projects")?.id)
        assertNull(MdDomain.resolve(state.mdElements, "Nowhere"))
        // Drawn as what it shows, a child with its mark.
        assertEquals("See Projects and ▸my health and ▸projects#Now.", renderMarkdown(home.text).text.lines()[1])
    }

    @Test
    fun a_child_link_makes_a_child_in_the_one_tree_and_a_plain_link_does_not() {
        assertEquals(listOf(key(health), key(projects)), ElementTree.childrenOf(state, key(home)))
        assertEquals(listOf(key(kitchen)), ElementTree.childrenOf(state, key(projects)))
        assertEquals(listOf(key(home)), ElementTree.parentsOf(state, key(projects)))
        assertTrue(ElementTree.parentsOf(state, key(home)).isEmpty(), "Projects' [[Home]] is a plain link")
        assertEquals(setOf(key(projects)), ElementTree.parentIndex(state)[key(kitchen)])
        assertEquals(2, ElementTree.highestParentDistance(state, listOf(key(kitchen))))
        // Said by the text: no ✕ of the tree takes it out.
        assertFalse(ElementTree.canUnlink(state, key(home), key(health)))
        // Derived: the text changed, the child is gone.
        val edited = state.copy(mdElements = state.mdElements.map { if (it.id == home.id) it.copy(text = "# Home") else it })
        assertTrue(ElementTree.childrenOf(edited, key(home)).isEmpty())
    }

    @Test
    fun the_folder_claude_code_opens_in_holds_every_md_file_and_a_guide_to_the_graph() {
        val clash = MdElement("md-5", "# Home: v2?")
        val dup = MdElement("md-6", "# Home")
        val untitled = MdElement("md-7", "")
        val s = state.copy(mdElements = state.mdElements + listOf(clash, dup, untitled))
        val names = MdDomain.fileNames(s.mdElements)
        assertEquals("Home.md", names["md-1"])
        assertEquals("Home  v2.md".replace("  ", " "), names["md-5"])
        assertEquals("Home (md-6).md", names["md-6"])
        assertEquals("md-7.md", names["md-7"])
        assertEquals(names.values.size, names.values.map { it.lowercase() }.toSet().size, "one file per md element")
        val files = MdDomain.claudeFolderFiles(s, listOf(projects.id))
        assertEquals(home.text, files["Home.md"])
        val guide = files.getValue(MdDomain.CLAUDE_GUIDE_FILE)
        assertTrue("[[>Name]]" in guide, guide)
        assertTrue("- `Projects.md`\n  - children: `Kitchen.md`\n  - links to: `Home.md`\n  - links to no file yet: `Nowhere`" in guide, guide)
        assertTrue("start from them" in guide && "- `Projects.md`" in guide.substringAfter("Where the user started"), guide)
        assertEquals(s.mdElements.size + 1, files.size)
        // A file named like the guide never takes its place.
        val claude = MdElement("md-8", "# CLAUDE")
        assertEquals("CLAUDE (md-8).md", MdDomain.fileNames(listOf(claude))["md-8"])
    }
}
