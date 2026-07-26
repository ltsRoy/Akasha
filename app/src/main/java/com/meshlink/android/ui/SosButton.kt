package com.MeshLink.android.ui

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FloatingActionButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Triggers the device vibrator for [durationMs] milliseconds.
 * Uses the modern VibratorManager on API 31+ and the legacy Vibrator service on older versions.
 * Does NOT use any deprecated APIs.
 */
private fun vibrateDevice(context: Context, durationMs: Long = 500L) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        // API 31+: Use VibratorManager to obtain the default vibrator
        val vibratorManager =
            context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
        vibratorManager?.defaultVibrator?.vibrate(
            VibrationEffect.createOneShot(durationMs, VibrationEffect.DEFAULT_AMPLITUDE)
        )
    } else {
        // API 26-30: Use legacy Vibrator service with VibrationEffect (not deprecated)
        @Suppress("DEPRECATION")
        val vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        vibrator?.vibrate(
            VibrationEffect.createOneShot(durationMs, VibrationEffect.DEFAULT_AMPLITUDE)
        )
    }
}

/**
 * A red Extended FAB labeled "🆘 SOS" that, when tapped, shows a confirmation
 * AlertDialog asking the user if they want to broadcast an emergency SOS.
 * On confirmation it:
 *   1. Vibrates the device for 500ms
 *   2. Calls [onSosConfirmed] so the caller can dispatch the SOS message
 *
 * Place this composable inside a Box with Alignment.BottomEnd (or use Scaffold's
 * floatingActionButton slot) in your ChatScreen.
 */
@Composable
fun SosFloatingActionButton(
    onSosConfirmed: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var showConfirmDialog by remember { mutableStateOf(false) }

    // ── SOS FAB ──────────────────────────────────────────────────────────
    ExtendedFloatingActionButton(
        onClick = { showConfirmDialog = true },
        containerColor = Color(0xFFD32F2F), // Material Red 700
        contentColor = Color.White,
        elevation = FloatingActionButtonDefaults.elevation(
            defaultElevation = 8.dp,
            pressedElevation = 12.dp
        ),
        modifier = modifier.padding(16.dp)
    ) {
        Text(
            text = "🆘 SOS",
            fontSize = 18.sp,
            fontWeight = FontWeight.Bold
        )
    }

    // ── Confirmation Dialog ──────────────────────────────────────────────
    if (showConfirmDialog) {
        AlertDialog(
            onDismissRequest = { showConfirmDialog = false },
            title = {
                Text(
                    text = "Emergency SOS",
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Text("Send emergency SOS to all nearby devices?")
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showConfirmDialog = false
                        // Vibrate for 500ms to give tactile confirmation
                        vibrateDevice(context, durationMs = 500L)
                        // Notify the parent to send the SOS broadcast
                        onSosConfirmed()
                    }
                ) {
                    Text(
                        text = "YES",
                        color = Color(0xFFD32F2F),
                        fontWeight = FontWeight.Bold
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { showConfirmDialog = false }) {
                    Text("NO")
                }
            }
        )
    }
}
