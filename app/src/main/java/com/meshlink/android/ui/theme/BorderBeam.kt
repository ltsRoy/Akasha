package com.MeshLink.android.ui.theme

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.abs

/**
 * Animated border beam — a glow that rides the edge of an element.
 *
 * Reference: https://github.com/Jakubantalik/border-beam (MIT). Two of its families are ported
 * here: [BeamMode.Travel] (a highlight orbiting the border) and [BeamMode.Pulse] (the whole edge
 * breathing). Implemented from scratch on Compose's canvas — the traveling highlight comes from a
 * sweep gradient whose stops are recomputed per frame, which avoids needing brush rotation.
 *
 * Deliberately single-hue rather than the reference's rainbow default, to stay discrete.
 */
enum class BeamMode { Travel, Pulse }

@Composable
fun Modifier.borderBeam(
    cornerRadius: Dp = 12.dp,
    color: Color = Color(0xFF4A6F67),
    strokeWidth: Dp = 1.5.dp,
    mode: BeamMode = BeamMode.Travel,
    active: Boolean = true,
    durationMillis: Int = 2600,
    /** Width of the travelling highlight, as a fraction of the perimeter. */
    tail: Float = 0.16f,
    /** Overall intensity, 0..1. */
    strength: Float = 1f,
): Modifier = composed {
    if (!active) return@composed this

    val transition = rememberInfiniteTransition(label = "beam")
    val progress by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(durationMillis, easing = LinearEasing), RepeatMode.Restart),
        label = "beamProgress",
    )
    val breath by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            tween(durationMillis, easing = LinearEasing),
            RepeatMode.Reverse,
        ),
        label = "beamBreath",
    )

    drawWithContent {
        drawContent()

        val stroke = Stroke(width = strokeWidth.toPx())
        val radius = CornerRadius(cornerRadius.toPx())
        val inset = strokeWidth.toPx() / 2f
        val topLeft = Offset(inset, inset)
        val rectSize = Size(size.width - inset * 2f, size.height - inset * 2f)

        when (mode) {
            BeamMode.Pulse -> {
                // The whole edge breathes between a faint hairline and a lit stroke.
                val a = (0.18f + breath * 0.62f) * strength
                drawRoundRect(
                    color = color.copy(alpha = a.coerceIn(0f, 1f)),
                    topLeft = topLeft,
                    size = rectSize,
                    cornerRadius = radius,
                    style = stroke,
                )
            }

            BeamMode.Travel -> {
                // Idle hairline so the edge stays defined once the highlight passes.
                drawRoundRect(
                    color = color.copy(alpha = 0.14f * strength),
                    topLeft = topLeft,
                    size = rectSize,
                    cornerRadius = radius,
                    style = stroke,
                )

                // Sweep gradient with per-frame stops: alpha peaks at `progress` and falls off
                // within `tail`, wrapping around 0/1 so the highlight never visibly jumps.
                val steps = 48
                val stops = Array(steps + 1) { i ->
                    val pos = i.toFloat() / steps
                    var d = abs(pos - progress)
                    if (d > 0.5f) d = 1f - d
                    val near = (1f - (d / tail)).coerceIn(0f, 1f)
                    // Ease the falloff so the head is bright and the tail fades smoothly.
                    val a = near * near * strength
                    pos to color.copy(alpha = a)
                }

                drawRoundRect(
                    brush = Brush.sweepGradient(
                        colorStops = stops,
                        center = Offset(size.width / 2f, size.height / 2f),
                    ),
                    topLeft = topLeft,
                    size = rectSize,
                    cornerRadius = radius,
                    style = stroke,
                )
            }
        }
    }
}
