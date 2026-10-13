package com.oneshotchat.android.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/** Oshi palette: near-black ink, cool greys, one pale ice-blue accent. */
object Palette {
    val Ink = Color(0xFF0B0B0D)
    val Surface = Color(0xFF121316)
    val Raised = Color(0xFF1A1C20)
    val Hairline = Color(0xFF23262B)
    val Text = Color(0xFFE4E6EA)
    val Dim = Color(0xFF858B95)
    val Ice = Color(0xFFA9D6F5)
    val IceDeep = Color(0xFF1B2A35)
    val Alert = Color(0xFFD98A8A)
}

private val OshiColors = darkColorScheme(
    primary = Palette.Ice,
    onPrimary = Palette.Ink,
    primaryContainer = Palette.IceDeep,
    onPrimaryContainer = Palette.Ice,
    secondary = Palette.Dim,
    onSecondary = Palette.Ink,
    secondaryContainer = Palette.Raised,
    onSecondaryContainer = Palette.Text,
    tertiary = Palette.Ice,
    tertiaryContainer = Palette.Raised,
    onTertiaryContainer = Palette.Text,
    background = Palette.Ink,
    onBackground = Palette.Text,
    surface = Palette.Ink,
    onSurface = Palette.Text,
    surfaceVariant = Palette.Raised,
    onSurfaceVariant = Palette.Dim,
    surfaceContainer = Palette.Surface,
    surfaceContainerHigh = Palette.Raised,
    surfaceContainerHighest = Palette.Raised,
    outline = Palette.Dim,
    outlineVariant = Palette.Hairline,
    error = Palette.Alert,
    onError = Palette.Ink,
)

private val base = Typography()
private val OshiTypography = base.copy(
    displayMedium = base.displayMedium.copy(fontWeight = FontWeight.Light, letterSpacing = 1.sp),
    headlineSmall = base.headlineSmall.copy(fontWeight = FontWeight.Light, letterSpacing = 1.sp),
    titleMedium = base.titleMedium.copy(fontWeight = FontWeight.Normal, letterSpacing = 0.3.sp),
    bodyLarge = base.bodyLarge.copy(lineHeight = 24.sp),
    labelLarge = base.labelLarge.copy(fontWeight = FontWeight.Normal),
)

/** Dark only, no dynamic colour: the look is the same on every device. */
@Composable
fun OneShotTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = OshiColors, typography = OshiTypography, content = content)
}
