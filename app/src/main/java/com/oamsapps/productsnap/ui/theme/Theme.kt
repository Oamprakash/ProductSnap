package com.oamsapps.productsnap.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.oamsapps.productsnap.R

/**
 * "Studio Ink": a dark stage that makes products pop, warm paper for the controls, and one tangerine accent kept
 * for each screen's main action. Fonts are bundled (SIL Open Font License, see /licenses).
 */
object Studio {
    val Paper = Color(0xFFF4F1EA)
    val Card = Color(0xFFFFFFFF)
    val Line = Color(0xFFE2DDD2)
    val Chip = Color(0xFFEDE8DD)
    val Ink = Color(0xFF111214)
    val Muted = Color(0xFF5E5A52)

    /** The photo stage. */
    val Stage = Color(0xFF15161A)
    val StageRaised = Color(0xFF24252B)
    val StageText = Color(0xFFF4F1EA)
    val StageMuted = Color(0xFFA8A49B)

    /** Main action on a screen. Ink text on it (about 6:1). */
    val Accent = Color(0xFFFF5B2E)
    val OnAccent = Ink

    val WarnBg = Color(0xFFFBE3CF)
    val OnWarn = Color(0xFF5A260B)
    val Good = Color(0xFF1F8A4C)
}

val DisplayFont = FontFamily(
    Font(R.font.bricolage_regular, FontWeight.Normal),
    Font(R.font.bricolage_bold, FontWeight.Bold),
)

val BodyFont = FontFamily(
    Font(R.font.instrument_sans_regular, FontWeight.Normal),
    Font(R.font.instrument_sans_bold, FontWeight.Bold),
)

private fun display(size: Int, line: Int) =
    TextStyle(fontFamily = DisplayFont, fontWeight = FontWeight.Bold, fontSize = size.sp, lineHeight = line.sp, letterSpacing = (-0.02 * size).sp)

private fun body(size: Int, line: Int, weight: FontWeight = FontWeight.Normal) =
    TextStyle(fontFamily = BodyFont, fontWeight = weight, fontSize = size.sp, lineHeight = line.sp)

private val StudioType = Typography(
    displayLarge = display(52, 54),
    displayMedium = display(44, 46),
    displaySmall = display(38, 40),
    headlineLarge = display(32, 34),
    headlineMedium = display(28, 30),
    headlineSmall = display(24, 27),
    titleLarge = display(22, 26),
    titleMedium = body(16, 22, FontWeight.Bold),
    titleSmall = body(14, 20, FontWeight.Bold),
    bodyLarge = body(16, 23),
    bodyMedium = body(14, 20),
    bodySmall = body(13, 18),
    labelLarge = body(15, 20, FontWeight.Bold),
    labelMedium = body(13, 16, FontWeight.Bold),
    labelSmall = body(11, 14, FontWeight.Bold),
)

private val StudioColors = lightColorScheme(
    primary = Studio.Ink,
    onPrimary = Studio.Paper,
    primaryContainer = Studio.Accent,
    onPrimaryContainer = Studio.Ink,
    secondary = Studio.Accent,
    onSecondary = Studio.Ink,
    // Selected chips: dark with light text.
    secondaryContainer = Studio.Ink,
    onSecondaryContainer = Studio.Paper,
    tertiary = Studio.Muted,
    onTertiary = Studio.Paper,
    tertiaryContainer = Studio.WarnBg,
    onTertiaryContainer = Studio.OnWarn,
    background = Studio.Paper,
    onBackground = Studio.Ink,
    surface = Studio.Paper,
    onSurface = Studio.Ink,
    surfaceVariant = Studio.Chip,
    onSurfaceVariant = Studio.Muted,
    surfaceContainerLowest = Studio.Card,
    surfaceContainerLow = Color(0xFFFAF8F3),
    surfaceContainer = Color(0xFFF7F4EE),
    surfaceContainerHigh = Studio.Card,
    surfaceContainerHighest = Studio.Chip,
    outline = Color(0xFFCFC8BA),
    outlineVariant = Studio.Line,
    inverseSurface = Studio.Ink,
    inverseOnSurface = Studio.Paper,
    error = Color(0xFFB3261E),
)

private val StudioShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

/** One look in light and dark system modes: the stage is already dark, the controls stay on paper. */
@Composable
fun ProductSnapTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = StudioColors, typography = StudioType, shapes = StudioShapes, content = content)
}
