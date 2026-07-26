package com.MeshLink.android.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.PriorityHigh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.MeshLink.android.model.MeshLinkMessage
import com.MeshLink.android.model.TrustState

/**
 * Per-message authenticity marker: a tick when the sender's signature verified, an exclamation when
 * it did not.
 *
 * Deliberately small and quiet when things are fine, and coloured only when something is wrong —
 * if every message shouted, the one that matters would be lost in the noise.
 */
@Composable
fun TrustBadge(
    trustState: TrustState,
    modifier: Modifier = Modifier,
    size: Int = 12,
) {
    val scheme = MaterialTheme.colorScheme

    // Our own messages are never verified against a signature, so there's nothing honest to show.
    if (trustState == TrustState.Unknown) return

    if (trustState.isTrusted) {
        Icon(
            imageVector = Icons.Filled.Check,
            contentDescription = "Signature verified",
            tint = scheme.onSurfaceVariant,
            modifier = modifier.size(size.dp),
        )
    } else {
        Icon(
            imageVector = Icons.Filled.PriorityHigh,
            contentDescription = "Unverified sender",
            tint = scheme.primary,
            modifier = modifier.size(size.dp),
        )
    }
}

/** One-line summary of a trust verdict, in plain language rather than crypto jargon. */
fun trustHeadline(trustState: TrustState): String = when (trustState) {
    TrustState.Verified -> "Verified sender"
    TrustState.Failed -> "Signature check failed"
    TrustState.Unsigned -> "Not signed"
    TrustState.NotApproved -> "Unrecognised device"
    TrustState.Unknown -> "Sent from this device"
}

/** What the verdict actually means for the person reading it, and what they should do about it. */
fun trustExplanation(trustState: TrustState): String = when (trustState) {
    TrustState.Verified ->
        "This message was signed with the sender's private key and the signature matched the " +
            "identity key they announced. Nobody altered it in transit, and nobody could have " +
            "forged it without that key."

    TrustState.Failed ->
        "A signature was present but did not match. The message was either modified on its way " +
            "here, or someone is impersonating this sender. Treat the content as untrustworthy."

    TrustState.Unsigned ->
        "This message arrived without a signature, so there is no way to confirm who sent it. " +
            "It may come from an older client, or from software deliberately bypassing the protocol."

    TrustState.NotApproved ->
        "We have no identity key on record for this sender, so the signature could not be checked " +
            "against anything. They have not completed a verified announce on this mesh."

    TrustState.Unknown ->
        "You sent this message. It was signed with this device's private key before going out."
}

/**
 * Message inspector: shows exactly what was verified, which key it was verified against, and which
 * device it came from.
 *
 * The point is that "verified" isn't a claim the user has to take on faith — they can read the key
 * fingerprint and compare it with the sender out of band.
 */
@Composable
fun MessageSecurityDialog(
    message: MeshLinkMessage,
    myPeerID: String,
    onDismiss: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val isSelf = message.senderPeerID == myPeerID
    val trust = if (isSelf) TrustState.Unknown else message.trustState

    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Close", color = scheme.primary, fontFamily = FontFamily.Monospace)
            }
        },
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TrustBadge(trust, size = 16)
                if (trust != TrustState.Unknown) Spacer(Modifier.width(8.dp))
                Text(
                    trustHeadline(trust),
                    color = if (trust.isSuspect) scheme.primary else scheme.onSurface,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    fontSize = 15.sp,
                )
            }
        },
        text = {
            Column(Modifier.fillMaxWidth()) {
                Text(
                    trustExplanation(trust),
                    color = scheme.onSurfaceVariant,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    lineHeight = 16.sp,
                )

                Spacer(Modifier.height(14.dp))

                DetailRow("Sender", message.sender)

                // The peer ID is the device's mesh address — the closest thing to "which handset
                // did this come from" that exists on an anonymous mesh.
                DetailRow("Device ID", message.senderPeerID ?: "unknown")

                DetailRow(
                    "Transport",
                    if (message.isPrivate) {
                        "Encrypted direct (Noise)"
                    } else {
                        "Signed broadcast"
                    },
                )

                val fingerprint = message.senderKeyFingerprint()
                if (fingerprint != null) {
                    Spacer(Modifier.height(10.dp))
                    Text(
                        "SIGNING KEY",
                        color = scheme.onSurfaceVariant,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 9.sp,
                        letterSpacing = 1.sp,
                    )
                    Spacer(Modifier.height(4.dp))
                    KeyBlock(fingerprint)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        // Full key, so it can be compared byte for byte if someone wants to.
                        message.senderPublicKeyHex ?: "",
                        color = scheme.onSurfaceVariant.copy(alpha = 0.7f),
                        fontFamily = FontFamily.Monospace,
                        fontSize = 9.sp,
                        lineHeight = 13.sp,
                    )
                } else if (!isSelf) {
                    Spacer(Modifier.height(10.dp))
                    Text(
                        "No signing key on record for this sender.",
                        color = scheme.primary,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                    )
                }
            }
        },
        containerColor = scheme.surface,
        titleContentColor = scheme.onSurface,
        textContentColor = scheme.onSurfaceVariant,
    )
}

@Composable
private fun DetailRow(label: String, value: String) {
    val scheme = MaterialTheme.colorScheme
    Row(
        Modifier.fillMaxWidth().padding(vertical = 3.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            label,
            color = scheme.onSurfaceVariant,
            fontFamily = FontFamily.Monospace,
            fontSize = 11.sp,
        )
        Spacer(Modifier.width(12.dp))
        Text(
            value,
            color = scheme.onSurface,
            fontFamily = FontFamily.Monospace,
            fontSize = 11.sp,
        )
    }
}

/** The fingerprint, set apart so it reads as something to compare rather than decoration. */
@Composable
private fun KeyBlock(fingerprint: String) {
    val scheme = MaterialTheme.colorScheme
    Text(
        fingerprint,
        color = scheme.onSurface,
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.Bold,
        fontSize = 13.sp,
        letterSpacing = 1.sp,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(scheme.surfaceVariant.copy(alpha = 0.6f))
            .padding(horizontal = 10.dp, vertical = 8.dp),
    )
}
