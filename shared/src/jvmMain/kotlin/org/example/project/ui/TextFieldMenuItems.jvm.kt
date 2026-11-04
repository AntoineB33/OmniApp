package org.example.project.ui

import androidx.compose.foundation.ContextMenuDataProvider
import androidx.compose.foundation.ContextMenuItem
import androidx.compose.runtime.Composable

@Composable
actual fun WithTextMenuItems(items: () -> List<TextMenuItem>, content: @Composable () -> Unit) =
    ContextMenuDataProvider(items = { items().map { ContextMenuItem(it.label, it.onClick) } }, content = content)
