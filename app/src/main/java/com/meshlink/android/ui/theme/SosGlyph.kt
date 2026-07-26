package com.MeshLink.android.ui.theme

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap

/**
 * SOS rendered as its Morse code — ··· ––– ··· — drawn on canvas.
 *
 * This replaces the 🆘 emoji everywhere it appeared. Morse is the universal distress language, so
 * the glyph carries real meaning while staying in the same monochrome, canvas-drawn visual family
 * as the [ThoughtOrb]. When [animated], the nine symbols light up left-to-right in a running sweep,
 * echoing a signal being keyed out.
 */
@Composable
fun SosGlyph(
    modifier: Modifier = Modifier,
    color: Color = Color.White,
    animated: Boolean = false,
) {
    val symbols = morseSos

    val anim by rememberInfiniteTransition(label = "sos").animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1500, easing = LinearEasing), RepeatMode.Restart),
        label = "sweep",
    )
    val sweep = if (animated) anim else 1f

    Canvas(modifier) {
        val n = symbols.size
        // Total weighted width: dot=1 unit, dash=3 units, gaps=1 unit between symbols.
        val dotU = 1f
        val dashU = 3f
        val gapU = 1f
        val totalUnits = symbols.sumOf { (if (it) dashU else dotU).toDouble() }.toFloat() + gapU * (n - 1)
        val unit = size.width / totalUnits
        val thickness = (size.height * 0.42f).coerceAtMost(unit * 0.9f)
        val cy = size.height / 2f

        var x = 0f
        symbols.forEachIndexed { i, isDash ->
            val w = (if (isDash) dashU else dotU) * unit
            // Sweep highlight: symbols before the sweep head are lit, the head is brightest.
            val pos = i.toFloat() / (n - 1).coerceAtLeast(1)
            val lit = if (!animated) 1f else {
                val d = kotlin.math.abs(pos - sweep)
                (1f - d * 2.2f).coerceIn(0.35f, 1f)
            }
            drawLine(
                color = color.copy(alpha = lit),
                start = Offset(x + thickness / 2f, cy),
                end = Offset(x + w - thickness / 2f, cy),
                strokeWidth = thickness,
                cap = StrokeCap.Round,
            )
            x += w + gapU * unit
        }
    }
}

// dot dot dot / dash dash dash / dot dot dot
private val morseSos: List<Boolean> = listOf(
    false, false, false,
    true, true, true,
    false, false, false,
)
