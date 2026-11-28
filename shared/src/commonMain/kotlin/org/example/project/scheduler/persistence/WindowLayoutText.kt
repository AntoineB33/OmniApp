package org.example.project.scheduler.persistence

import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.nullable
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/**
 * User request 2026-11-28 (*"the sorted history units about window opening or closing"*): **what a change of a window's
 * layout was** — the one statement of it, read by `App` when it names the History Unit it records ([unitLabel]) and by
 * the Search window's "What was done to a window" filter ([label], `SearchDomain.Filters.windowChanges`). In the order a
 * change is named: a window that opens, closes, is minimized or brought back is that, whatever else moved with it.
 */
enum class WindowChange(val label: String, val unitLabel: String) {
    Opened("opened", "Open window"),
    Closed("closed", "Close window"),
    Minimized("minimized", "Reduce window"),
    BroughtBack("brought back", "Bring window back"),
    Filled("filled", "Fill window"),
    Resized("resized", "Resize window"),
    Moved("moved", "Move window"),
    ;

    companion object {
        /** What the layout [before] becoming [after] was. */
        fun of(before: WindowPlacement, after: WindowPlacement): WindowChange =
            when {
                !before.visible && after.visible -> Opened
                before.visible && !after.visible -> Closed
                before.minimized != after.minimized -> if (after.minimized) Minimized else BroughtBack
                before.fillWidth != after.fillWidth || before.fillHeight != after.fillHeight -> Filled
                before.width != after.width || before.height != after.height -> Resized
                else -> Moved
            }

        /** What the layout texts [before] → [after] ([WindowLayoutText.write]) were; null where one is unreadable or they are the same. */
        fun of(before: String?, after: String?): WindowChange? {
            if (before == after) return null
            val from = before?.let(WindowLayoutText::read) ?: return null
            val to = after?.let(WindowLayoutText::read) ?: return null
            return of(from, to)
        }
    }
}

/**
 * **A window's layout as its History Unit holds it** — its row without the configuration, which has a unit of its own —
 * and several windows' layouts as ONE unit holds them (the window bar's Reset, a close that takes other windows with it).
 * Written by `App`, read back by its undo and by [WindowChange.of].
 */
object WindowLayoutText {
    fun write(p: WindowPlacement): String =
        listOf(p.x, p.y, p.width, p.height, p.visible, p.fillWidth, p.fillHeight, p.minimized).joinToString(";")

    fun read(text: String): WindowPlacement? {
        val parts = text.split(';')
        if (parts.size != 8) return null
        return runCatching {
            WindowPlacement(
                x = parts[0].toFloat(), y = parts[1].toFloat(), width = parts[2].toFloat(), height = parts[3].toFloat(),
                visible = parts[4].toBooleanStrict(), fillWidth = parts[5].toBooleanStrict(), fillHeight = parts[6].toBooleanStrict(),
                minimized = parts[7].toBooleanStrict(),
            )
        }.getOrNull()
    }

    private val WINDOWS_SERIALIZER = MapSerializer(String.serializer(), String.serializer().nullable)

    /** Several windows' layouts ([write]) by frame id, as one value. */
    fun writeMany(layouts: Map<String, String?>): String = Json.encodeToString(WINDOWS_SERIALIZER, layouts)

    /** [writeMany]'s reverse; empty for nothing readable. */
    fun readMany(text: String?): Map<String, String?> =
        text?.let { runCatching { Json.decodeFromString(WINDOWS_SERIALIZER, it) }.getOrNull() }.orEmpty()
}
