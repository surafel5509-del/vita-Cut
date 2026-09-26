package com.vitacut.core.designsystem.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/** Standard content card used across home/templates/settings. */
@Composable
fun VitaCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    containerColor: Color = MaterialTheme.colorScheme.surfaceContainer,
    border: BorderStroke? = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.35f)),
    content: @Composable ColumnScope.() -> Unit,
) {
    if (onClick != null) {
        Surface(
            onClick = onClick,
            modifier = modifier,
            shape = MaterialTheme.shapes.large,
            color = containerColor,
            border = border,
        ) {
            Column(content = content)
        }
    } else {
        Surface(
            modifier = modifier,
            shape = MaterialTheme.shapes.large,
            color = containerColor,
            border = border,
        ) {
            Column(content = content)
        }
    }
}

/**
 * Editor panel background (tool sheets, side panels). Slightly raised above the canvas, no
 * border — the elevation reads through the tone step alone in the dark-first scheme.
 */
@Composable
fun VitaPanel(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(
        modifier = modifier,
        shape = MaterialTheme.shapes.extraLarge,
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 2.dp,
    ) {
        Column(content = content)
    }
}
