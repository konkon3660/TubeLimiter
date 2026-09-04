package com.tubelimiter.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/**
 * Same indigo brand as the Chrome extension's `common.css` (`--primary-color` etc.) so the
 * two clients read as one product. Fixed palette on purpose — Material You dynamic color
 * would drift the app's color off the extension's per device.
 */
val BrandPrimary = Color(0xFF4F46E5)
val BrandPrimaryHover = Color(0xFF4338CA)
val BrandDanger = Color(0xFFEF4444)
val BrandSuccess = Color(0xFF10B981)
val BrandWarning = Color(0xFFF59E0B)

private val LightColors = lightColorScheme(
    primary = BrandPrimary,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE0E7FF),
    onPrimaryContainer = Color(0xFF3730A3),
    secondary = BrandSuccess,
    onSecondary = Color.White,
    error = BrandDanger,
    onError = Color.White,
    background = Color(0xFFFFFFFF),
    onBackground = Color(0xFF334155),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF334155),
    surfaceVariant = Color(0xFFF8FAFC),
    onSurfaceVariant = Color(0xFF64748B),
    // Was 0xFFE2E8F0 — only ~1.23:1 against the white Card/surface behind it, far under the
    // ~3:1 WCAG minimum for non-text UI boundaries. Material3's OutlinedTextField draws its
    // border in this color, so every outlined field (FocusCard's 지연/지속 inputs, AuthScreen,
    // etc.) was effectively borderless. 0x64748B matches onSurfaceVariant's proven-readable
    // tone and lands at ~4.76:1.
    outline = Color(0xFF64748B),
)

private val DarkColors = darkColorScheme(
    primary = BrandPrimary,
    onPrimary = Color.White,
    primaryContainer = Color(0xFF312E81),
    onPrimaryContainer = Color(0xFFE0E7FF),
    secondary = BrandSuccess,
    onSecondary = Color.White,
    error = BrandDanger,
    onError = Color.White,
    background = Color(0xFF0F172A),
    onBackground = Color(0xFFF1F5F9),
    surface = Color(0xFF0F172A),
    onSurface = Color(0xFFF1F5F9),
    surfaceVariant = Color(0xFF1E293B),
    onSurfaceVariant = Color(0xFFCBD5E1),
    // Was 0xFF334155 — only ~1.72:1 against the 0xFF0F172A surface, same under-contrast bug
    // as the light scheme's outline. 0x748399 lands at ~4.63:1.
    outline = Color(0xFF748399),
)

@Composable
fun TubeLimiterTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(colorScheme = if (darkTheme) DarkColors else LightColors, content = content)
}
