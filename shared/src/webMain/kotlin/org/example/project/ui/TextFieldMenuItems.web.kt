package org.example.project.ui

import androidx.compose.runtime.Composable

/** The platform's own text menu takes no item of the app's: the field is drawn as it is. */
@Composable
actual fun WithTextMenuItems(items: () -> List<TextMenuItem>, content: @Composable () -> Unit) = content()
