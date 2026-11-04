package org.example.project.ui

import androidx.compose.runtime.Composable

/** One item added to a text field's own right-click menu ([WithTextMenuItems]). */
data class TextMenuItem(val label: String, val onClick: () -> Unit)

/**
 * User rule 2026-11-04: **[items] added to the right-click menu of the text fields in [content]**, after their own (cut,
 * copy, paste, select all). The desktop's menu takes them; elsewhere a field's menu is the platform's, which takes none,
 * and [content] is drawn as it is (`docs/PLATFORMS.md`).
 */
@Composable
expect fun WithTextMenuItems(items: () -> List<TextMenuItem>, content: @Composable () -> Unit)
