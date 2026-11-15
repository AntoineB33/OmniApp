package org.example.project

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import org.example.project.ui.EchoedText
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Anomaly 2026-11-15: in the actions section of a "List of parents", typing "paren" wrote "preat" — the bar's owner
 * gives its text back a frame or more late, and the field took the stale text as new. [EchoedText] keeps the bar's own.
 */
class EchoedTextTest {
    /** Types [word] one key at a time at the caret, the owner answering [lag] keys behind. */
    private fun typeWithLag(word: String, lag: Int): EchoedText {
        val field = EchoedText("")
        val owner = ArrayDeque<String>()
        var ownerText = ""
        for (c in word) {
            field.ownerHolds(ownerText)
            val v = field.value
            val text = v.text.substring(0, v.selection.start) + c + v.text.substring(v.selection.end)
            if (field.typed(TextFieldValue(text, TextRange(v.selection.start + 1)))) owner.addLast(text)
            if (owner.size > lag) ownerText = owner.removeFirst()
        }
        while (owner.isNotEmpty()) {
            ownerText = owner.removeFirst()
            field.ownerHolds(ownerText)
        }
        return field
    }

    @Test
    fun aLateOwnerDoesNotMoveTheCaret() {
        for (lag in 0..3) {
            val field = typeWithLag("paren", lag)
            assertEquals("paren", field.value.text, "lag $lag")
            assertEquals(TextRange(5), field.value.selection, "lag $lag")
        }
    }

    @Test
    fun anOwnersOwnTextReplacesTheField() {
        val field = EchoedText("abc")
        field.typed(TextFieldValue("abcd", TextRange(4)))
        // The cross, a revert, a configuration put back: none of what was typed.
        field.ownerHolds("")
        assertEquals("", field.value.text)
        field.ownerHolds("xyz")
        assertEquals(TextFieldValue("xyz", TextRange(3)), field.value)
    }

    @Test
    fun anOlderEchoAfterANewerOneIsAReplacement() {
        val field = EchoedText("")
        field.typed(TextFieldValue("a", TextRange(1)))
        field.typed(TextFieldValue("ab", TextRange(2)))
        field.ownerHolds("ab")
        // Caught up: "a" is no echo any more, the owner went back to it on its own.
        field.ownerHolds("a")
        assertEquals("a", field.value.text)
    }
}
