package org.example.project

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * User rule 2026-11-04: **no text of the app is cut by "…"** — a text too long for its room wraps. 43 places cut one
 * (`TextOverflow.Ellipsis`: window titles, result rows, the type selector's face, the configurations' labels…) and the
 * notification's path dropped its start behind one; none may come back. A label that says more is to come ("add…",
 * "Syncing…") is no cut text and is not what this reads.
 */
class NoTextCutByAnEllipsisTest {
    @Test
    fun no_source_of_the_app_cuts_a_text_by_an_ellipsis() {
        val root = generateSequence(File("").absoluteFile) { it.parentFile }.first { File(it, "settings.gradle.kts").isFile }
        val sources =
            listOf("shared/src/commonMain", "shared/src/jvmMain", "shared/src/androidMain", "shared/src/jsMain", "shared/src/wasmJsMain", "desktopApp/src", "androidApp/src", "webApp/src")
                .map { File(root, it) }.filter { it.isDirectory }
                .flatMap { dir -> dir.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList() }
        val cutting =
            sources.flatMap { file ->
                file.readLines().mapIndexedNotNull { index, line ->
                    "${file.relativeTo(root)}:${index + 1}".takeIf { "TextOverflow.Ellipsis" in line && !line.trimStart().startsWith("//") }
                }
            }
        assertEquals(emptyList(), cutting, "texts cut by an ellipsis")
    }
}
