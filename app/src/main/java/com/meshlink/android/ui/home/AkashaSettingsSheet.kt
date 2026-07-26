package com.MeshLink.android.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.MeshLink.android.R
import com.MeshLink.android.service.MeshServicePreferences
import com.MeshLink.android.ui.ChatViewModel
import com.MeshLink.android.ui.theme.GrabberHandle
import com.MeshLink.android.ui.theme.SectionHeader
import com.MeshLink.android.ui.theme.Space
import com.MeshLink.android.ui.theme.ThoughtOrb
import com.MeshLink.android.ui.theme.OrbState
import com.MeshLink.android.ui.theme.bounceClick
import com.MeshLink.android.ui.theme.hairline

/**
 * Akasha settings — deliberately small.
 *
 * Replaces the inherited settings sheet, which was a product brochure plus knobs that don't help
 * anyone in an emergency (theme switcher, Nostr proof-of-work difficulty slider, Tor transport
 * config). What's left is what a person under stress might actually need to change: who they appear
 * as, whether the mesh keeps running in the background, and a way to wipe everything.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AkashaSettingsSheet(
    isPresented: Boolean,
    onDismiss: () -> Unit,
    viewModel: ChatViewModel,
    onShowDebug: () -> Unit,
) {
    if (!isPresented) return

    val scheme = MaterialTheme.colorScheme
    val context = LocalContext.current
    val nickname by viewModel.nickname.collectAsStateWithLifecycle()
    val peers by viewModel.connectedPeers.collectAsStateWithLifecycle()

    var confirmWipe by remember { mutableStateOf(false) }
    var background by remember {
        mutableStateOf(runCatching { MeshServicePreferences.isBackgroundEnabled(true) }.getOrDefault(true))
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = scheme.surface,
        contentColor = scheme.onSurface,
        dragHandle = {
            Box(Modifier.fillMaxWidth().padding(vertical = Space.m), contentAlignment = Alignment.Center) {
                GrabberHandle()
            }
        },
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Space.l)
                .navigationBarsPadding(),
        ) {
            // --- Identity: the orb doubles as the app mark here ---
            Row(verticalAlignment = Alignment.CenterVertically) {
                ThoughtOrb(
                    state = if (peers.isEmpty()) OrbState.Searching else OrbState.Working,
                    ink = scheme.onSurface,
                    modifier = Modifier.size(52.dp),
                )
                Spacer(Modifier.width(Space.l))
                Column {
                    Row(verticalAlignment = Alignment.Bottom) {
                        Text(
                            stringResource(R.string.app_name).uppercase(),
                            color = scheme.onSurface,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            fontSize = 20.sp,
                            letterSpacing = 2.sp,
                        )
                        Text(
                            "/",
                            color = scheme.primary,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            fontSize = 20.sp,
                        )
                    }
                    Text(
                        if (peers.isEmpty()) "no peers in range" else "${peers.size} connected",
                        color = scheme.onSurfaceVariant,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                    )
                }
            }

            Spacer(Modifier.height(Space.xl))

            // --- Your handle ---
            SectionHeader("your name")
            Spacer(Modifier.height(Space.s))
            NicknameField(
                value = nickname,
                onValueChange = { viewModel.setNickname(it) },
            )
            Text(
                "How you appear to everyone nearby.",
                color = scheme.onSurfaceVariant,
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
                modifier = Modifier.padding(top = Space.xs),
            )

            Spacer(Modifier.height(Space.xl))

            // --- Network behaviour ---
            SectionHeader("network")
            Spacer(Modifier.height(Space.s))
            SettingRow(
                icon = Icons.Filled.Bolt,
                title = "Stay connected in background",
                subtitle = "Keeps relaying messages when the app is closed. Uses more battery.",
                trailing = {
                    Switch(
                        checked = background,
                        onCheckedChange = {
                            background = it
                            runCatching { MeshServicePreferences.setBackgroundEnabled(it) }
                        },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = scheme.onPrimary,
                            checkedTrackColor = scheme.primary,
                            uncheckedThumbColor = scheme.onSurfaceVariant,
                            uncheckedTrackColor = scheme.surfaceVariant,
                        ),
                    )
                },
            )

            Spacer(Modifier.height(Space.xl))

            // --- Destructive + diagnostics ---
            SectionHeader("data")
            Spacer(Modifier.height(Space.s))
            SettingRow(
                icon = Icons.Filled.DeleteForever,
                title = "Erase everything",
                subtitle = "Wipes messages, contacts and your identity, then starts fresh.",
                tint = scheme.primary,
                onClick = { confirmWipe = true },
            )
            Spacer(Modifier.height(Space.s))
            SettingRow(
                icon = Icons.Filled.BugReport,
                title = "Diagnostics",
                subtitle = "Connection logs and mesh topology.",
                onClick = onShowDebug,
            )

            Spacer(Modifier.height(Space.xl))
            Text(
                "Works with no internet, no accounts, no servers. Messages hop device to device.",
                color = scheme.onSurfaceVariant,
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
                lineHeight = 16.sp,
            )
            Spacer(Modifier.height(Space.xl))
        }
    }

    if (confirmWipe) {
        AlertDialog(
            onDismissRequest = { confirmWipe = false },
            containerColor = scheme.surface,
            titleContentColor = scheme.onSurface,
            textContentColor = scheme.onSurfaceVariant,
            title = { Text("Erase everything?", fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold) },
            text = {
                Text(
                    "All messages, contacts and your identity are deleted from this device. " +
                        "This can't be undone.",
                    fontFamily = FontFamily.Monospace,
                    fontSize = 13.sp,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmWipe = false
                    viewModel.panicClearAllData()
                    onDismiss()
                }) {
                    Text("ERASE", color = scheme.primary, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmWipe = false }) {
                    Text("CANCEL", color = scheme.onSurfaceVariant, fontFamily = FontFamily.Monospace)
                }
            },
        )
    }
}

@Composable
private fun NicknameField(value: String, onValueChange: (String) -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(12.dp)
    Row(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(scheme.surfaceVariant.copy(alpha = 0.5f))
            .hairline(shape)
            .padding(horizontal = Space.m, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "@",
            color = scheme.primary,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            fontSize = 14.sp,
        )
        Spacer(Modifier.width(Space.xs))
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = true,
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

/** One settings line: icon, title, explanation, and either a control or a tap target. */
@Composable
private fun SettingRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String,
    modifier: Modifier = Modifier,
    tint: androidx.compose.ui.graphics.Color? = null,
    onClick: (() -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    val scheme = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(12.dp)
    Row(
        modifier
            .fillMaxWidth()
            .clip(shape)
            .background(scheme.surfaceVariant.copy(alpha = 0.4f))
            .hairline(shape)
            .then(if (onClick != null) Modifier.bounceClick(onClick) else Modifier)
            .padding(horizontal = Space.m, vertical = Space.m),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            icon,
            null,
            tint = tint ?: scheme.onSurfaceVariant,
            modifier = Modifier.size(20.dp),
        )
        Spacer(Modifier.width(Space.m))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                title,
                color = tint ?: scheme.onSurface,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                fontSize = 13.sp,
            )
            Text(
                subtitle,
                color = scheme.onSurfaceVariant,
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
                lineHeight = 15.sp,
            )
        }
        if (trailing != null) {
            Spacer(Modifier.width(Space.s))
            trailing()
        }
    }
}
