package com.MeshLink.android.ui.theme

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * Dotted thought-orb, in the spirit of the `thinking-orbs` reference: a dotted globe drawn with
 * plain 2D canvas arcs — no gradients, no blur, no shaders — kept strictly monochrome so it reads
 * as ink on paper rather than a glowing toy.
 *
 * Reference: https://github.com/Jakubantalik/thinking-orbs (MIT). The visual language (dotted
 * globe + per-state animation, monochrome, canvas-arcs-only) is reimplemented here in Compose;
 * no code is copied.
 */
enum class OrbState {
    /** A scan meridian sweeps the dotted globe — idle / looking for peers. */
    Searching,

    /** Dots ride tilted orbits — mesh is live and relaying. */
    Working,

    /** A waveform rolls through the rings — alert broadcasting. */
    Listening,
}

private const val BANDS = 11

@Composable
fun ThoughtOrb(
    state: OrbState,
    modifier: Modifier = Modifier,
    ink: Color = Color(0xFF23221F),
    speed: Float = 1f,
) {
    val transition = rememberInfiniteTransition(label = "orb")

    // One shared clock; each state reads it at its own tempo.
    val cycleMs = when (state) {
        OrbState.Searching -> 4200
        OrbState.Working -> 5200
        OrbState.Listening -> 1900
    }
    val t by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            tween((cycleMs / speed).toInt(), easing = LinearEasing),
            RepeatMode.Restart,
        ),
        label = "t",
    )

    // Smoothly fade dot emphasis when the state changes, so switches don't pop.
    val emphasis by animateFloatAsState(
        targetValue = if (state == OrbState.Listening) 1f else 0.72f,
        animationSpec = tween(400),
        label = "emphasis",
    )

    Canvas(modifier) {
        val cx = size.width / 2f
        val cy = size.height / 2f
        val r = size.minDimension / 2f * 0.86f
        val baseDot = (size.minDimension / 74f).coerceAtLeast(1.1f)

        // Globe rotation. Searching/Working spin slowly; Listening holds still and pulses instead.
        val spin = when (state) {
            OrbState.Listening -> 0f
            else -> t * 2f * PI.toFloat()
        }

        // The sweeping scan meridian, expressed as a longitude in radians.
        val scanLon = t * 2f * PI.toFloat()

        for (b in 0 until BANDS) {
            // Latitude from -pi/2..pi/2, skipping the exact poles.
            val latFrac = (b + 0.5f) / BANDS
            val lat = (latFrac - 0.5f) * PI.toFloat()
            val cosLat = cos(lat)
            val ringR = r * cosLat
            val y = cy + r * sin(lat)

            // Fewer dots near the poles keeps spacing even instead of bunching.
            val count = (6 + (cosLat * 22f)).toInt().coerceAtLeast(6)

            for (i in 0 until count) {
                var lon = (i.toFloat() / count) * 2f * PI.toFloat() + spin

                // Working: tilt each band's phase so dots trace offset orbits rather than
                // a rigid lattice.
                if (state == OrbState.Working) {
                    lon += sin(t * 2f * PI.toFloat() + b * 0.55f) * 0.32f
                }

                val x = cx + ringR * cos(lon)

                // Depth: +1 toward the viewer, -1 away. Back-hemisphere dots stay faint so the
                // form reads as a sphere without any shading.
                val depth = sin(lon) * cosLat
                val front = (depth + 1f) / 2f

                var alpha = 0.14f + front * 0.46f * emphasis
                var dot = baseDot * (0.72f + front * 0.52f)

                when (state) {
                    OrbState.Searching -> {
                        // Distance from the scan meridian, wrapped to [-pi, pi].
                        var d = ((lon - scanLon + PI.toFloat()) % (2f * PI.toFloat())) - PI.toFloat()
                        if (d < -PI.toFloat()) d += 2f * PI.toFloat()
                        val near = (1f - (abs(d) / 0.55f)).coerceIn(0f, 1f)
                        alpha += near * 0.62f * front
                        dot *= 1f + near * 0.85f
                    }

                    OrbState.Working -> {
                        // A slow brightness drift around the equator.
                        val pulse = (sin(lon * 2f - t * 4f * PI.toFloat()) + 1f) / 2f
                        alpha += pulse * 0.22f * front
                    }

                    OrbState.Listening -> {
                        // A vertical waveform travelling through the bands.
                        val wave = sin(latFrac * 5f * PI.toFloat() - t * 2f * PI.toFloat())
                        val amp = (wave + 1f) / 2f
                        alpha += amp * 0.5f * front
                        dot *= 0.85f + amp * 0.7f
                    }
                }

                drawCircle(
                    color = ink.copy(alpha = alpha.coerceIn(0f, 1f)),
                    radius = dot,
                    center = Offset(x, y),
                )
            }
        }
    }
}
