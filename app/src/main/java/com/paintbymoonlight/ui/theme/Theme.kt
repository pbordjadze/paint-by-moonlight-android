package com.paintbymoonlight.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight

/**
 * Paint by Moonlight's colors, from the iOS design system's tokens: Nightshade (`signature`)
 * leads, gold is for stars, sparkles and the crescent only, and the paper is the screen.
 */
object Moonlight {
    val nightshade = Color(0xFF7B3F7E)
    val gold = Color(0xFFC9A24A)
    val paperLight = Color(0xFFF2F1F4)
    val paperDark = Color(0xFF141117)
    /** The canvas sheet: its own paper and ink in both appearances. */
    val sheet = Color(0xFFFFFFFF)
    val sheetInk = Color(0xFF8E8A93)
}

private val light = lightColorScheme(
    primary = Moonlight.nightshade,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFFFD6F9),
    onPrimaryContainer = Color(0xFF330036),
    secondary = Color(0xFF6B5869),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFF4DAEE),
    onSecondaryContainer = Color(0xFF251624),
    tertiary = Color(0xFF775A0B),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFFFDF9C),
    onTertiaryContainer = Color(0xFF251A00),
    background = Moonlight.paperLight,
    onBackground = Color(0xFF1E1A1E),
    surface = Moonlight.paperLight,
    onSurface = Color(0xFF1E1A1E),
    surfaceVariant = Color(0xFFEBDFE8),
    onSurfaceVariant = Color(0xFF4D444C),
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color(0xFFF8F6F9),
    surfaceContainer = Color(0xFFEFEBF0),
    surfaceContainerHigh = Color(0xFFE9E5EA),
    surfaceContainerHighest = Color(0xFFE3DFE4),
    outline = Color(0xFF7F747D),
    outlineVariant = Color(0xFFD0C3CD),
)

private val dark = darkColorScheme(
    primary = Color(0xFFF0B0EE),
    onPrimary = Color(0xFF4C0E50),
    primaryContainer = Color(0xFF662868),
    onPrimaryContainer = Color(0xFFFFD6F9),
    secondary = Color(0xFFD8BED3),
    onSecondary = Color(0xFF3B2B3A),
    secondaryContainer = Color(0xFF534151),
    onSecondaryContainer = Color(0xFFF4DAEE),
    tertiary = Color(0xFFE8C26B),
    onTertiary = Color(0xFF3F2E00),
    tertiaryContainer = Color(0xFF5A4300),
    onTertiaryContainer = Color(0xFFFFDF9C),
    background = Moonlight.paperDark,
    onBackground = Color(0xFFE9E0E6),
    surface = Moonlight.paperDark,
    onSurface = Color(0xFFE9E0E6),
    surfaceVariant = Color(0xFF4D444C),
    onSurfaceVariant = Color(0xFFD0C3CD),
    surfaceContainerLowest = Color(0xFF0F0C11),
    surfaceContainerLow = Color(0xFF1C181E),
    surfaceContainer = Color(0xFF211C22),
    surfaceContainerHigh = Color(0xFF2B262C),
    surfaceContainerHighest = Color(0xFF363137),
    outline = Color(0xFF998D97),
    outlineVariant = Color(0xFF4D444C),
)

/** Titles and numerals in a serif, as iOS sets them in New York; the rest in the system sans. */
private val base = Typography()
private val typography = base.copy(
    displayLarge = base.displayLarge.serif(),
    displayMedium = base.displayMedium.serif(),
    displaySmall = base.displaySmall.serif(),
    headlineLarge = base.headlineLarge.serif(),
    headlineMedium = base.headlineMedium.serif(),
    headlineSmall = base.headlineSmall.serif(),
    titleLarge = base.titleLarge.serif(),
)

private fun TextStyle.serif() = copy(fontFamily = FontFamily.Serif, fontWeight = FontWeight.Medium)

/** The numerals on swatches and the canvas. */
val NumeralFamily = FontFamily.Serif

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun MoonlightTheme(darkTheme: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    MaterialExpressiveTheme(
        colorScheme = if (darkTheme) dark else light,
        motionScheme = MotionScheme.expressive(),
        typography = typography,
        content = content,
    )
}

