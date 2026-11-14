package org.example.project

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * User rule 2026-11-14: *"There must be only one code for the search bar."* Anomaly of that day: the bar of the ▾
 * pop-up looked unlike the Search window's — its file imported Material's `OutlinedTextField` by name, so it never
 * went through the app's own field (`EnterLeavesField.kt`), and it drew a bar of its own beside `SearchBar`.
 *
 * Three things none may bring back: Material's field named anywhere but in the app's own; a search bar's cross
 * (`SearchBarCross`) given by hand instead of by `SearchBar` — the task cell that is its rectangle's bar excepted; and a
 * ✕ drawn by hand (*"unify them"*, the same day: nine framed ones and seven round ones) instead of `CrossButton`, the
 * one cross that takes something away, or `WindowHeadButton`, the one that closes a window.
 */
class OneTextFieldTest {
    private val root = generateSequence(File("").absoluteFile) { it.parentFile }.first { File(it, "settings.gradle.kts").isFile }

    private fun lines(): List<Triple<File, Int, String>> =
        listOf("shared/src/commonMain", "shared/src/jvmMain", "shared/src/androidMain", "shared/src/jsMain", "shared/src/wasmJsMain", "desktopApp/src", "androidApp/src", "webApp/src")
            .map { File(root, it) }.filter { it.isDirectory }
            .flatMap { dir -> dir.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList() }
            .flatMap { file -> file.readLines().mapIndexed { index, line -> Triple(file, index + 1, line) } }
            .filterNot { it.third.trimStart().startsWith("//") || it.third.trimStart().startsWith("*") }

    private fun found(except: Set<String>, test: (String) -> Boolean): List<String> =
        lines().filter { (file, _, line) -> file.name !in except && test(line) }.map { (file, at, _) -> "${file.relativeTo(root)}:$at" }

    @Test
    fun no_source_names_the_material_text_field_but_the_apps_own() {
        val material = Regex("""material3\.(Outlined)?TextField\b""")
        assertEquals(emptyList(), found(setOf("EnterLeavesField.kt")) { material.containsMatchIn(it) }, "Material's field, named past the app's own")
    }

    @Test
    fun every_search_bar_is_the_one_search_bar() {
        assertEquals(
            emptyList(),
            found(setOf("CompactSection.kt", "TaskSchedulerScreen.kt")) { "SearchBarCross(" in it },
            "a search bar drawn by hand: SearchBar gives the cross",
        )
    }

    @Test
    fun every_cross_is_the_one_cross() {
        // The cross itself; a tab's close on its fill colour and the head's; the find bar's close; a relation's label.
        val own = setOf("CompactSection.kt", "WindowFrame.kt", "TaskTreeFindBar.kt", "TaskRelationsWindow.kt")
        assertEquals(emptyList(), found(own) { "\"✕\"" in it && "WindowHeadButton(" !in it }, "a ✕ drawn by hand")
    }
}
