package il.kavmonitor.shared.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import il.kavmonitor.shared.resources.Res
import il.kavmonitor.shared.resources.rubik
import org.jetbrains.compose.resources.Font

/**
 * Tonal-spot scheme from blue #1565C0 (Material color utilities, spec 2021).
 * Primary lands on tone 40. Status colors (time left) stay separate from this brand color.
 */
private val LightColors = lightColorScheme(
    primary = Color(0xFF005DB5),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFD6E3FF),
    onPrimaryContainer = Color(0xFF001B3D),
    secondary = Color(0xFF555F71),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFD9E3F8),
    onSecondaryContainer = Color(0xFF121C2B),
    tertiary = Color(0xFF6E5676),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFF7D8FF),
    onTertiaryContainer = Color(0xFF271430),
    error = Color(0xFFBA1A1A),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF93000A),
    background = Color(0xFFF8F9FF),
    onBackground = Color(0xFF191C20),
    surface = Color(0xFFF8F9FF),
    onSurface = Color(0xFF191C20),
    surfaceVariant = Color(0xFFE0E2EC),
    onSurfaceVariant = Color(0xFF43474E),
    outline = Color(0xFF73777F),
    outlineVariant = Color(0xFFC3C6CF),
    inverseSurface = Color(0xFF2E3036),
    inverseOnSurface = Color(0xFFEFF0F7),
    inversePrimary = Color(0xFFA8C8FF),
    surfaceDim = Color(0xFFD8DAE0),
    surfaceBright = Color(0xFFF8F9FF),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF2F3F9),
    surfaceContainer = Color(0xFFECEEF4),
    surfaceContainerHigh = Color(0xFFE6E8EE),
    surfaceContainerHighest = Color(0xFFE1E2E8),
    surfaceTint = Color(0xFF005DB5),
    scrim = Color(0xFF000000),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFA8C8FF),
    onPrimary = Color(0xFF003063),
    primaryContainer = Color(0xFF00468C),
    onPrimaryContainer = Color(0xFFD6E3FF),
    secondary = Color(0xFFBDC7DC),
    onSecondary = Color(0xFF273141),
    secondaryContainer = Color(0xFF3E4758),
    onSecondaryContainer = Color(0xFFD9E3F8),
    tertiary = Color(0xFFDABDE2),
    onTertiary = Color(0xFF3D2946),
    tertiaryContainer = Color(0xFF553F5D),
    onTertiaryContainer = Color(0xFFF7D8FF),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
    background = Color(0xFF111318),
    onBackground = Color(0xFFE1E2E8),
    surface = Color(0xFF111318),
    onSurface = Color(0xFFE1E2E8),
    surfaceVariant = Color(0xFF43474E),
    onSurfaceVariant = Color(0xFFC3C6CF),
    outline = Color(0xFF8D9199),
    outlineVariant = Color(0xFF43474E),
    inverseSurface = Color(0xFFE1E2E8),
    inverseOnSurface = Color(0xFF2E3036),
    inversePrimary = Color(0xFF005DB5),
    surfaceDim = Color(0xFF111318),
    surfaceBright = Color(0xFF37393E),
    surfaceContainerLowest = Color(0xFF0C0E13),
    surfaceContainerLow = Color(0xFF191C20),
    surfaceContainer = Color(0xFF1D2024),
    surfaceContainerHigh = Color(0xFF272A2F),
    surfaceContainerHighest = Color(0xFF32353A),
    surfaceTint = Color(0xFFA8C8FF),
    scrim = Color(0xFF000000),
)

/** Expressive corners: fields and badges stay soft, cards use the large-increased size. */
private val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(12.dp),
    small = RoundedCornerShape(16.dp),
    medium = RoundedCornerShape(20.dp),
    large = RoundedCornerShape(28.dp),
    extraLarge = RoundedCornerShape(32.dp),
)

/** Hero fills. White text on each is at least 5.4:1. Kept separate from primary so "time left" is not the brand color. */
internal fun statusFill(tone: HeroTone, dark: Boolean): Color = when (tone) {
    HeroTone.PLENTY -> if (dark) Color(0xFF1B5E20) else Color(0xFF146C32)
    HeroTone.GET_READY -> if (dark) Color(0xFF8C2A00) else Color(0xFFB84300)
    HeroTone.NOW -> if (dark) Color(0xFF8E2424) else Color(0xFFB71C1C)
    HeroTone.NONE -> if (dark) Color(0xFF424242) else Color(0xFF424242)
}

@Composable
private fun rubikFamily(): FontFamily {
    fun weight(value: Int) = FontVariation.Settings(FontVariation.weight(value))
    return FontFamily(
        Font(Res.font.rubik, FontWeight.Normal, variationSettings = weight(400)),
        Font(Res.font.rubik, FontWeight.Medium, variationSettings = weight(500)),
        Font(Res.font.rubik, FontWeight.SemiBold, variationSettings = weight(600)),
    )
}

/** Same Material 3 scale, set in Rubik so Hebrew and digits share one face. */
private fun Typography.withFont(font: FontFamily): Typography {
    fun TextStyle.face() = copy(fontFamily = font)
    return copy(
        displayLarge = displayLarge.face(),
        displayMedium = displayMedium.face(),
        displaySmall = displaySmall.face(),
        headlineLarge = headlineLarge.face(),
        headlineMedium = headlineMedium.face(),
        headlineSmall = headlineSmall.face(),
        titleLarge = titleLarge.face(),
        titleMedium = titleMedium.face(),
        titleSmall = titleSmall.face(),
        bodyLarge = bodyLarge.face(),
        bodyMedium = bodyMedium.face(),
        bodySmall = bodySmall.face(),
        labelLarge = labelLarge.face(),
        labelMedium = labelMedium.face(),
        labelSmall = labelSmall.face(),
    )
}

@Composable
fun KavMonitorTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) DarkColors else LightColors,
        typography = Typography().withFont(rubikFamily()),
        shapes = AppShapes,
        content = content,
    )
}
