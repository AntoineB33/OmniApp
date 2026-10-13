package org.example.project.ui

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.material3.DropdownMenu
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.window.PopupProperties

/** Android: material's menu, as it is — there is no other application's window for it to be under ([AppDropdownMenu]). */
@Composable
actual fun AppDropdownMenu(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    modifier: Modifier,
    offset: DpOffset,
    properties: PopupProperties,
    atPointer: Boolean,
    content: @Composable ColumnScope.() -> Unit,
) = DropdownMenu(expanded, onDismissRequest, modifier, offset, properties = properties, content = content)
