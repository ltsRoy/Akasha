package com.MeshLink.android.ui.theme

import android.app.Activity
import android.os.Build
import android.view.View
import android.view.WindowInsetsController
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView

// Red-on-black: near-black layered surfaces with a single signal red as the accent.
// Red carries the emergency/SOS meaning, so it stays the only saturated colour in the app —
// everything else is neutral ink so the red never has to compete.
private val DarkColorScheme = darkColorScheme(
    primary = Color(0xFFD93A34),        // Signal red — SOS, live state, active accents
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFF3A1210),
    onPrimaryContainer = Color(0xFFFFC9C4),
    secondary = Color(0xFF8A8A8F),      // Neutral gray accent
    onSecondary = Color(0xFF0B0B0C),
    secondaryContainer = Color(0xFF1F1F22),
    onSecondaryContainer = Color(0xFFD6D6D2),
    background = Color(0xFF0B0B0C),     // Near-black
    onBackground = Color(0xFFEDEDEA),
    surface = Color(0xFF131315),        // Cards / bottom sheet
    onSurface = Color(0xFFEDEDEA),
    surfaceVariant = Color(0xFF1F1F22), // Borders / dividers / message cards
    onSurfaceVariant = Color(0xFF8E8E93),
    error = Color(0xFFFF6B62),
    onError = Color(0xFF0B0B0C)
)

// Warm near-white light scheme, tuned to sit flush with the desaturated map style.
private val LightColorScheme = lightColorScheme(
    primary = Color(0xFF4A6F67),        // Deep muted teal
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD9E4E0),
    onPrimaryContainer = Color(0xFF22352F),
    secondary = Color(0xFF6E6A66),      // Warm neutral gray
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFE8E7E3),
    onSecondaryContainer = Color(0xFF33312E),
    background = Color(0xFFF5F5F3),     // Matches map_style_light base
    onBackground = Color(0xFF23221F),
    surface = Color(0xFFFBFBF9),        // Cards / sheet
    onSurface = Color(0xFF23221F),
    surfaceVariant = Color(0xFFEBEAE6), // Borders / dividers / message cards
    onSurfaceVariant = Color(0xFF6E6A66),
    error = Color(0xFFB4524E),          // Dusty red, matches the armed orb
    onError = Color.White
)

@Composable
fun MeshLinkTheme(
    darkTheme: Boolean? = null,
    content: @Composable () -> Unit
) {
    // App-level override from ThemePreferenceManager
    val themePref by ThemePreferenceManager.themeFlow.collectAsState(initial = ThemePreference.System)
    val shouldUseDark = when (darkTheme) {
        true -> true
        false -> false
        null -> when (themePref) {
            ThemePreference.Dark -> true
            ThemePreference.Light -> false
            ThemePreference.System -> isSystemInDarkTheme()
        }
    }

    val colorScheme = if (shouldUseDark) DarkColorScheme else LightColorScheme

    val view = LocalView.current
    SideEffect {
        (view.context as? Activity)?.window?.let { window ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                window.insetsController?.setSystemBarsAppearance(
                    if (!shouldUseDark) WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS else 0,
                    WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
                )
            } else {
                @Suppress("DEPRECATION")
                window.decorView.systemUiVisibility = if (!shouldUseDark) {
                    View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
                } else 0
            }
            window.navigationBarColor = colorScheme.background.toArgb()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                window.isNavigationBarContrastEnforced = false
            }
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}
