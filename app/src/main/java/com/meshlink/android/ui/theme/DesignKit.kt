package com.MeshLink.android.ui.theme

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Shared UI primitives: fully-rounded pill buttons, a segmented toggle, gray section headers,
 * a sheet grabber, and a rich empty state. Colors come from MaterialTheme.colorScheme so the
 * whole kit stays monochrome/discrete instead of tied to a single hardcoded accent.
 */

private val Pill = RoundedCornerShape(percent = 50)

/** Fully-rounded action pill. [primary] = accent fill / dark label; ghost = outlined / light label. */
@Composable
fun PillButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    primary: Boolean = true,
    icon: ImageVector? = null,
) {
    val scheme = MaterialTheme.colorScheme
    val content = if (primary) scheme.onPrimary else scheme.onSurface
    Row(
        modifier
            .clip(Pill)
            .then(if (primary) Modifier.background(scheme.primary) else Modifier.hairline(Pill))
            .bounceClick(onClick)
            .padding(horizontal = Space.xl, vertical = 13.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(icon, null, tint = content, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(Space.s))
        }
        Text(text, color = content, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 15.sp)
    }
}

/** Segmented control: dark track, accent-filled pill behind the selected label. */
@Composable
fun SegmentedToggle(
    options: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    Row(
        modifier
            .clip(Pill)
            .background(scheme.surfaceVariant.copy(alpha = 0.6f))
            .hairline(Pill)
            .padding(3.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        options.forEachIndexed { i, label ->
            val on = i == selectedIndex
            Box(
                Modifier
                    .weight(1f)
                    .clip(Pill)
                    .background(if (on) scheme.primary else Color.Transparent)
                    .bounceClick { onSelect(i) }
                    .padding(vertical = 8.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    label,
                    color = if (on) scheme.onPrimary else scheme.onSurfaceVariant,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 13.sp,
                    fontWeight = if (on) FontWeight.Bold else FontWeight.Medium,
                    maxLines = 1,
                )
            }
        }
    }
}

/** Gray, uppercase, letter-spaced label that opens a group of content. */
@Composable
fun SectionHeader(text: String, modifier: Modifier = Modifier) {
    Text(
        text.uppercase(),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        fontFamily = FontFamily.Monospace,
        fontSize = 12.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = 1.sp,
        modifier = modifier,
    )
}

/** The small handle at the top of a bottom-sheet-style surface. Neutral, not accent-colored. */
@Composable
fun GrabberHandle(modifier: Modifier = Modifier) {
    Box(
        modifier
            .size(width = 36.dp, height = 4.dp)
            .clip(Pill)
            .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f))
    )
}

/** Centered empty state: accent glyph, bold headline, gray direction, optional ghost-pill action. */
@Composable
fun RichEmptyState(
    icon: ImageVector,
    title: String,
    subtitle: String,
    modifier: Modifier = Modifier,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    val scheme = MaterialTheme.colorScheme
    Column(
        modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(
            Modifier.size(64.dp).clip(CircleShape).background(scheme.primary.copy(alpha = 0.12f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, null, tint = scheme.primary, modifier = Modifier.size(30.dp))
        }
        Spacer(Modifier.height(Space.l))
        Text(
            title,
            color = scheme.onSurface,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            fontSize = 22.sp,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(Space.s))
        Text(
            subtitle,
            color = scheme.onSurfaceVariant,
            fontFamily = FontFamily.Monospace,
            fontSize = 15.sp,
            textAlign = TextAlign.Center,
            lineHeight = 21.sp,
        )
        if (actionLabel != null && onAction != null) {
            Spacer(Modifier.height(Space.xl))
            PillButton(actionLabel, onAction, primary = false)
        }
    }
}
