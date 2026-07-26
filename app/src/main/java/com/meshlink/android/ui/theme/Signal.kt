package com.MeshLink.android.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.graphics.Color

/**
 * Semantic colour tokens for meanings the Material [androidx.compose.material3.ColorScheme] has no
 * slot for — signal strength, link health, transport state.
 *
 * Rule for the whole app: red is reserved for urgency (SOS, alerts, live broadcast). Everything
 * else is a neutral ink ramp, so when red appears it always means something. Signal strength is
 * therefore expressed as brightness, not as a green-to-red gradient.
 */
object Signal {
    /** Urgency. Matches colorScheme.primary; named here for call sites that mean "alert". */
    val Alert = Color(0xFFD93A34)
    val AlertDeep = Color(0xFFB02C27)

    /** Neutral ink ramp, brightest first. Used for signal strength and emphasis tiers. */
    val Ink = Color(0xFFEDEDEA)
    val Ink2 = Color(0xFFC9C9C4)
    val Ink3 = Color(0xFF9A9A96)
    val Ink4 = Color(0xFF6D6D70)
    val Dim = Color(0xFF8E8E93)

    /** Sparingly, for "in progress / degraded" states such as Tor bootstrapping. */
    val Pending = Color(0xFFE0A03A)

    /** Hard failure. */
    val Danger = Color(0xFFFF6B62)

    /**
     * Signal strength as brightness. [rssi] is dBm; BLE realistically spans about -30 (touching)
     * to -100 (edge of range).
     */
    fun forRssi(rssi: Int): Color = when {
        rssi >= -55 -> Ink
        rssi >= -70 -> Ink2
        rssi >= -85 -> Ink3
        else -> Ink4
    }

    /** Signal strength bucketed to 0..4 bars. */
    fun barsForRssi(rssi: Int?): Int = when {
        rssi == null -> 0
        rssi >= -55 -> 4
        rssi >= -70 -> 3
        rssi >= -85 -> 2
        else -> 1
    }
}

/** True when the active scheme is the dark (default) one. */
@Composable
@ReadOnlyComposable
fun isDarkScheme(): Boolean {
    val bg = MaterialTheme.colorScheme.background
    return bg.red + bg.green + bg.blue < 1.5f
}
