package org.example.project

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import java.io.File
import javax.swing.SwingUtilities
import org.example.project.ui.verticalScrollWithBar
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/**
 * Anomaly 2026-11-14: *"in the down arrow of the default search window, there is no vertical scrollbar, even though it
 * is supposed to be using the same code as the default Search window that has a vertical scrollbar"*. The ▾ tree's
 * columns scrolled by a bare `verticalScroll`, as did every window's body and every menu; only the lazy lists drew a
 * bar, and two of those did not either (the completion list, the task picker).
 *
 * **Everything that scrolls vertically shows the one scrollbar**: a plain column through `verticalScrollWithBar`, a lazy
 * list beside a `ListScrollbar` (`ListScrollbar.kt`, both drawing the same thumb). The sources are read for either.
 */
class OneScrollbarTest {
    private val root = generateSequence(File("").absoluteFile) { it.parentFile }.first { File(it, "settings.gradle.kts").isFile }

    private fun sources(): List<File> =
        listOf("shared/src/commonMain", "shared/src/jvmMain", "shared/src/androidMain", "shared/src/jsMain", "shared/src/wasmJsMain", "desktopApp/src", "androidApp/src", "webApp/src")
            .map { File(root, it) }.filter { it.isDirectory }
            .flatMap { dir -> dir.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList() }

    private fun code(file: File): List<Pair<Int, String>> =
        file.readLines().mapIndexed { index, line -> index + 1 to line }
            .filterNot { (_, line) -> line.trimStart().startsWith("//") || line.trimStart().startsWith("*") }

    @Test
    fun no_column_scrolls_without_the_bar() {
        val bare = Regex("""\.verticalScroll\(""")
        val found = sources().filter { it.name != "ListScrollbar.kt" }.flatMap { file ->
            code(file).filter { (_, line) -> bare.containsMatchIn(line) }.map { (at, _) -> "${file.relativeTo(root)}:$at" }
        }
        assertEquals(emptyList(), found, "a column scrolled by a bare verticalScroll: use verticalScrollWithBar")
    }

    @Test
    fun every_lazy_list_has_its_bar() {
        // As many ListScrollbar( as LazyColumn( in a file: one bar per list.
        val found = sources().mapNotNull { file ->
            val lines = code(file).map { it.second }
            val lists = lines.count { "LazyColumn(" in it }
            val bars = lines.count { "ListScrollbar(" in it && "fun ListScrollbar(" !in it }
            if (lists > bars) "${file.relativeTo(root)}: $lists LazyColumn, $bars ListScrollbar" else null
        }
        assertEquals(emptyList(), found, "a lazy list with no ListScrollbar")
    }

    /** The pixel at the bar's place, top right of a 100 × 100 column holding [content] px of white rows. */
    private fun barPixel(content: Int): Int {
        var out: Result<Int>? = null
        SwingUtilities.invokeAndWait {
            out = runCatching {
                val scene = ImageComposeScene(100, 100, density = Density(1f)) {
                    Box(Modifier.size(100.dp).background(Color.White)) {
                        Column(Modifier.size(100.dp).verticalScrollWithBar(rememberScrollState())) {
                            Box(Modifier.width(100.dp).height(content.dp).background(Color.White))
                        }
                    }
                }
                try {
                    scene.render()
                    scene.render().toComposeImageBitmapPixel(95, 5)
                } finally {
                    scene.close()
                }
            }
        }
        return out!!.getOrThrow()
    }

    private fun org.jetbrains.skia.Image.toComposeImageBitmapPixel(x: Int, y: Int): Int =
        org.jetbrains.skia.Bitmap.makeFromImage(this).getColor(x, y)

    @Test
    fun a_column_that_overflows_draws_the_bar_and_one_that_fits_does_not() {
        assertEquals(0xFFFFFFFF.toInt(), barPixel(content = 50), "a column that fits draws no bar")
        assertNotEquals(0xFFFFFFFF.toInt(), barPixel(content = 1000), "a column that overflows draws its bar")
    }
}
