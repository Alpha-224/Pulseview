package com.Twinhealth.pulseview.ui.theme

import android.content.Context
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.Twinhealth.pulseview.R

// ── Font Families ────────────────────────────────────────────────────────────

val DmSerifDisplay = FontFamily(
    Font(R.font.dm_serif_display_regular, FontWeight.Normal)
)

val IbmPlexSans = FontFamily(
    Font(R.font.ibm_plex_sans_regular, FontWeight.Normal)
)

val IbmPlexMono = FontFamily(
    Font(R.font.ibm_plex_mono_regular, FontWeight.Normal)
)

// Reusable custom monospaced text style for numeric values/readouts
val MonoNumericStyle = TextStyle(
    fontFamily = IbmPlexMono,
    fontWeight = FontWeight.Normal
)

// ── Typography ───────────────────────────────────────────────────────────────

val PulseviewTypography = Typography(
    headlineLarge = TextStyle(
        fontFamily = DmSerifDisplay,
        fontWeight = FontWeight.Normal,
        fontSize = 32.sp,
        lineHeight = 40.sp,
        letterSpacing = 0.sp
    ),
    headlineMedium = TextStyle(
        fontFamily = DmSerifDisplay,
        fontWeight = FontWeight.Normal,
        fontSize = 28.sp,
        lineHeight = 36.sp,
        letterSpacing = 0.sp
    ),
    titleLarge = TextStyle(
        fontFamily = DmSerifDisplay,
        fontWeight = FontWeight.Normal,
        fontSize = 22.sp,
        lineHeight = 28.sp,
        letterSpacing = 0.sp
    ),
    bodyLarge = TextStyle(
        fontFamily = IbmPlexSans,
        fontWeight = FontWeight.Normal,
        fontSize = 16.sp,
        lineHeight = 24.sp,
        letterSpacing = 0.5.sp
    ),
    bodyMedium = TextStyle(
        fontFamily = IbmPlexSans,
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.25.sp
    ),
    labelLarge = TextStyle(
        fontFamily = IbmPlexSans,
        fontWeight = FontWeight.Medium,
        fontSize = 14.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.1.sp
    )
)

// ── Color Palette ────────────────────────────────────────────────────────────
// Web design system tokens applied exactly.

private val DarkColorScheme = darkColorScheme(
    primary          = Color(0xFF14FFEC),   // Accent: cyan-teal
    onPrimary        = Color(0xFF0A0F1E),   // Contrast text on primary
    primaryContainer = Color(0x2E14FFEC),   // Accent dim/translucent (0.18 alpha)
    background       = Color(0xFF0A0F1E),   // Dark background
    onBackground     = Color(0xFFE8EDF5),   // Text primary
    surface          = Color(0xFF111827),   // Slightly elevated surface
    onSurface        = Color(0xFFE8EDF5),   // Text primary
    surfaceVariant   = Color(0xFF151E2D),   // Card
    onSurfaceVariant = Color(0xFF8892A4),   // Text muted
    outline          = Color(0x12FFFFFF),   // Border (rgba 255,255,255,0.07 -> ~7% -> 0x12)
    outlineVariant   = Color(0x26FFFFFF),   // Border strong (rgba 255,255,255,0.15 -> ~15% -> 0x26)
    error            = Color(0xFFE05252),   // Error/unsafe
    onError          = Color.White,
    secondary        = Color(0xFFE8A838),   // Warning (used as secondary)
    tertiary         = Color(0xFF3ECFA0),   // Safe/success (used as tertiary)
)

private val LightColorScheme = lightColorScheme(
    primary          = Color(0xFF0D7377),   // Accent: deep teal (intentional light mode accent)
    onPrimary        = Color.White,
    primaryContainer = Color(0x2E0D7377),   // Accent dim/translucent (0.18 alpha)
    background       = Color(0xFFFFFFFF),   // Light background
    onBackground     = Color(0xFF1A1A2E),   // Text primary
    surface          = Color(0xFFF4F6F9),   // Surface
    onSurface        = Color(0xFF1A1A2E),   // Text primary
    surfaceVariant   = Color(0xFFFFFFFF),   // Card
    onSurfaceVariant = Color(0xFF6B7280),   // Text muted
    outline          = Color(0x12000000),   // Border (rgba 0,0,0,0.07 -> ~7% -> 0x12)
    outlineVariant   = Color(0x21000000),   // Border strong (rgba 0,0,0,0.13 -> ~13% -> 0x21)
    error            = Color(0xFFC0392B),   // Error
    onError          = Color.White,
    secondary        = Color(0xFFE8A838),   // Warning (same)
    tertiary         = Color(0xFF2D9B6F),   // Safe
)

// ── ColorScheme Extensions for Semantic Names ────────────────────────────────

val ColorScheme.card: Color get() = surfaceVariant
val ColorScheme.warning: Color get() = secondary
val ColorScheme.safe: Color get() = tertiary
val ColorScheme.border: Color get() = outline
val ColorScheme.borderStrong: Color get() = outlineVariant
val ColorScheme.textPrimary: Color get() = onBackground
val ColorScheme.textMuted: Color get() = onSurfaceVariant

// ── Theme State Persistence ──────────────────────────────────────────────────

object ThemeSettings {
    var isDarkTheme by mutableStateOf(true)
        private set

    fun init(context: Context) {
        val prefs = context.getSharedPreferences("pulseview_prefs", Context.MODE_PRIVATE)
        isDarkTheme = prefs.getBoolean("dark_theme", true)
    }

    fun toggleTheme(context: Context) {
        isDarkTheme = !isDarkTheme
        val prefs = context.getSharedPreferences("pulseview_prefs", Context.MODE_PRIVATE)
        prefs.edit().putBoolean("dark_theme", isDarkTheme).apply()
    }
}

/**
 * PulseView Compose theme.
 * Defaults to dark theme but respects user choices persisted via ThemeSettings.
 */
@Composable
fun PulseviewTheme(
    darkTheme: Boolean = ThemeSettings.isDarkTheme,
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme
    MaterialTheme(
        colorScheme = colorScheme,
        typography = PulseviewTypography,
        content = content
    )
}
