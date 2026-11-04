package org.example.project.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import org.example.project.scheduler.domain.SearchDomain
import org.example.project.scheduler.domain.TaskTreeSearch

/**
 * **What a search bar holds is highlighted in what it searches** (user rule 2026-11-02): the text of the bar a section
 * stands under, read by the section's texts ([searchHighlighted]) — so that an editor embedded in a section highlights
 * there and draws as it always does elsewhere. Empty outside such a section.
 */
internal val LocalSearchHighlight = compositionLocalOf { "" }

/**
 * User rule 2026-11-04: the Search window's match case and whole word ([SearchDomain.Filters.queryOptions]): with one on,
 * only what matches with it is highlighted. Off outside the Search window's own bar, whose window provides it.
 */
internal val LocalSearchMatch = compositionLocalOf { TaskTreeSearch.Options() }

/** [content] with what the bar holds, [query], highlighted in its texts ([LocalSearchHighlight]). */
@Composable
internal fun HighlightsSearch(query: String, content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalSearchHighlight provides query, LocalSearchMatch provides TaskTreeSearch.Options(), content = content)
}

/** [text] with every hit of [query] shaded as the tree's Ctrl+F shades its own ([SearchDomain.matchRanges]). */
internal fun highlightedText(text: String, query: String, match: TaskTreeSearch.Options = TaskTreeSearch.Options()): AnnotatedString {
    val ranges = SearchDomain.matchRanges(text, query, match)
    if (ranges.isEmpty()) return AnnotatedString(text)
    return buildAnnotatedString {
        append(text)
        for (range in ranges) addStyle(SpanStyle(background = SheetColors.searchMatchFill), range.first, range.last + 1)
    }
}

/** [highlightedText], walked only when the text or the bar changes. */
@Composable
internal fun searchHighlighted(text: String, query: String = LocalSearchHighlight.current): AnnotatedString {
    val match = LocalSearchMatch.current
    return remember(text, query, match) { highlightedText(text, query, match) }
}
