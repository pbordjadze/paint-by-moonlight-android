package com.paintbymoonlight.ui.theme

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.core.view.WindowCompat

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

internal val LightColors = lightColorScheme(
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

// Nightshade lifted just enough to read on the dark paper (5.8:1), so the FAB, the main buttons
// and the text buttons keep the signature color instead of M3's pale pink; ink dark on it (6:1).
internal val DarkColors = darkColorScheme(
    primary = Color(0xFFB57BB8),
    onPrimary = Color(0xFF2A0A2D),
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

/**
 * A card raised off the page: white on the light paper, a lighter tone on the dark (where the
 * lowest container would sink below the page instead).
 */
val ColorScheme.raised: Color
    get() = if (surface.luminance() > 0.5f) surfaceContainerLowest else surfaceContainerHigh

/** The numerals on swatches and the canvas. */
val NumeralFamily = FontFamily.Serif

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun MoonlightTheme(darkTheme: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    SystemBarIcons(darkTheme)
    MaterialExpressiveTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        motionScheme = MotionScheme.expressive(),
        typography = typography,
        content = content,
    )
}


/**
 * The status and navigation bars' icons dark on the light paper and light on the dark, whatever
 * window the theme is drawn in (an activity whose own theme says otherwise, or one recreated
 * for a change of appearance), so they never vanish into the screen behind them.
 */
@Composable
private fun SystemBarIcons(darkTheme: Boolean) {
    val view = LocalView.current
    if (view.isInEditMode) return
    SideEffect {
        val window = view.context.activity()?.window ?: return@SideEffect
        WindowCompat.getInsetsController(window, view).apply {
            isAppearanceLightStatusBars = !darkTheme
            isAppearanceLightNavigationBars = !darkTheme
        }
    }
}

private tailrec fun Context.activity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.activity()
    else -> null
}
