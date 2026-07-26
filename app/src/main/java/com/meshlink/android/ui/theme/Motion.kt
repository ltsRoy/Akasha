package com.MeshLink.android.ui.theme

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp

/**
 * Shared motion + layout primitives for a clean, discrete UI.
 * Adapted pattern: fully-rounded pills, hairline borders, tactile press feedback.
 * No brand colors — kept neutral/monochrome so it reads as "signal", not a logo.
 */

/** Hairline border for card definition on dark surfaces. Pass the card's shape so it aligns. */
fun Modifier.hairline(shape: Shape): Modifier = this.border(1.dp, Color.White.copy(alpha = 0.06f), shape)

/** 4-pt spacing scale — use instead of scattering magic dp values. */
object Space {
    val xs = 4.dp
    val s = 8.dp
    val m = 12.dp
    val l = 16.dp
    val xl = 24.dp
}

/** Tactile press: the element dips to 96% while held, springs back on release. No ripple. */
@Composable
fun Modifier.bounceClick(onClick: () -> Unit): Modifier {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.96f else 1f, label = "bounce")
    return this
        .graphicsLayer { scaleX = scale; scaleY = scale }
        .clickable(interactionSource = interaction, indication = null, onClick = onClick)
}
