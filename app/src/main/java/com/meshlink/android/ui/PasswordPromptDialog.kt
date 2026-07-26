package com.MeshLink.android.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.MeshLink.android.ui.theme.Space
import com.MeshLink.android.ui.theme.hairline

/**
 * Asks for a channel password. Kept from the original settings file (which was otherwise replaced),
 * restyled to the Akasha monospace/red-on-black language.
 */
@Composable
fun PasswordPromptDialog(
    show: Boolean,
    channelName: String?,
    passwordInput: String,
    onPasswordChange: (String) -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    if (!show) return
    val scheme = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(12.dp)

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = scheme.surface,
        titleContentColor = scheme.onSurface,
        textContentColor = scheme.onSurfaceVariant,
        title = {
            Text(
                "Protected channel",
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
            )
        },
        text = {
            androidx.compose.foundation.layout.Column {
                Text(
                    "${channelName ?: "This channel"} needs a password to join.",
                    fontFamily = FontFamily.Monospace,
                    fontSize = 13.sp,
                )
                Spacer(Modifier.padding(top = Space.m))
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(shape)
                        .background(scheme.surfaceVariant.copy(alpha = 0.5f))
                        .hairline(shape)
                        .padding(horizontal = Space.m, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "*",
                        color = scheme.primary,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp,
                    )
                    Spacer(Modifier.width(Space.s))
                    BasicTextField(
                        value = passwordInput,
                        onValueChange = onPasswordChange,
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = { onConfirm() }),
                        textStyle = TextStyle(
                            color = scheme.onSurface,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 14.sp,
                        ),
                        cursorBrush = SolidColor(scheme.primary),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(
                    "JOIN",
                    color = scheme.primary,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("CANCEL", color = scheme.onSurfaceVariant, fontFamily = FontFamily.Monospace)
            }
        },
    )
}
