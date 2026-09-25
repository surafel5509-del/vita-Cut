package com.vitacut.core.designsystem.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * Branded alert dialog shell. All copy is passed in (already resolved from string resources);
 * the confirm slot can be danger-styled for destructive flows.
 */
@Composable
fun VitaDialog(
    title: String,
    onDismiss: () -> Unit,
    confirmText: String,
    onConfirm: () -> Unit,
    modifier: Modifier = Modifier,
    dismissText: String? = null,
    onDismissAction: (() -> Unit)? = null,
    danger: Boolean = false,
    content: (@Composable ColumnScope.() -> Unit)? = null,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = modifier,
        shape = MaterialTheme.shapes.extraLarge,
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
        title = { Text(title, style = MaterialTheme.typography.headlineSmall) },
        text = content?.let {
            {
                Column {
                    it()
                }
            }
        },
        confirmButton = {
            if (danger) {
                VitaDangerButton(text = confirmText, onClick = onConfirm)
            } else {
                VitaButton(text = confirmText, onClick = onConfirm)
            }
        },
        dismissButton = dismissText?.let { text ->
            {
                VitaTextButton(
                    text = text,
                    onClick = onDismissAction ?: onDismiss,
                )
            }
        },
    )
}

/** Two-button confirmation with an optional explanatory body. */
@Composable
fun VitaConfirmDialog(
    title: String,
    message: String,
    confirmText: String,
    dismissText: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    danger: Boolean = false,
) {
    VitaDialog(
        title = title,
        onDismiss = onDismiss,
        confirmText = confirmText,
        onConfirm = onConfirm,
        modifier = modifier,
        dismissText = dismissText,
        danger = danger,
        content = {
            Row(
                modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                horizontalArrangement = Arrangement.Start,
            ) {
                Text(message, style = MaterialTheme.typography.bodyMedium)
            }
        },
    )
}
