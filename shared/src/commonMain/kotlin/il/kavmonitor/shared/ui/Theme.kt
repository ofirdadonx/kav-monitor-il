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
 * Tonal-spot scheme from the brand green #1B7F3B (Material color utilities, spec 2021).
 * Primary lands on tone 40 (#35693E), so containers, outline, and surfaces stay in the same green family.
 */
private val LightColors = lightColorScheme(
    primary = Color(0xFF35693E),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFB7F1BA),
    onPrimaryContainer = Color(0xFF1C5128),
    secondary = Color(0xFF516351),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFD4E8D1),
    onSecondaryContainer = Color(0xFF3A4B3A),
    tertiary = Color(0xFF39656D),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFBDEAF3),
    onTertiaryContainer = Color(0xFF1F4D54),
    error = Color(0xFFBA1A1A),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF93000A),
    background = Color(0xFFF7FBF2),
    onBackground = Color(0xFF181D18),
    surface = Color(0xFFF7FBF2),
    onSurface = Color(0xFF181D18),
    surfaceVariant = Color(0xFFDDE5D9),
    onSurfaceVariant = Color(0xFF414941),
    outline = Color(0xFF727970),
    outlineVariant = Color(0xFFC1C9BE),
    inverseSurface = Color(0xFF2D322C),
    inverseOnSurface = Color(0xFFEEF2EA),
    inversePrimary = Color(0xFF9CD4A0),
    surfaceDim = Color(0xFFD7DBD3),
    surfaceBright = Color(0xFFF7FBF2),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF1F5EC),
    surfaceContainer = Color(0xFFEBEFE7),
    surfaceContainerHigh = Color(0xFFE5E9E1),
    surfaceContainerHighest = Color(0xFFE0E4DB),
    surfaceTint = Color(0xFF35693E),
    scrim = Color(0xFF000000),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF9CD4A0),
    onPrimary = Color(0xFF003914),
    primaryContainer = Color(0xFF1C5128),
    onPrimaryContainer = Color(0xFFB7F1BA),
    secondary = Color(0xFFB8CCB6),
    onSecondary = Color(0xFF243425),
    secondaryContainer = Color(0xFF3A4B3A),
    onSecondaryContainer = Color(0xFFD4E8D1),
    tertiary = Color(0xFFA1CED7),
    onTertiary = Color(0xFF00363D),
    tertiaryContainer = Color(0xFF1F4D54),
    onTertiaryContainer = Color(0xFFBDEAF3),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
    background = Color(0xFF101510),
    onBackground = Color(0xFFE0E4DB),
    surface = Color(0xFF101510),
    onSurface = Color(0xFFE0E4DB),
    surfaceVariant = Color(0xFF414941),
    onSurfaceVariant = Color(0xFFC1C9BE),
    outline = Color(0xFF8B9389),
    outlineVariant = Color(0xFF414941),
    inverseSurface = Color(0xFFE0E4DB),
    inverseOnSurface = Color(0xFF2D322C),
    inversePrimary = Color(0xFF35693E),
    surfaceDim = Color(0xFF101510),
    surfaceBright = Color(0xFF363A35),
    surfaceContainerLowest = Color(0xFF0B0F0B),
    surfaceContainerLow = Color(0xFF181D18),
    surfaceContainer = Color(0xFF1C211C),
    surfaceContainerHigh = Color(0xFF262B26),
    surfaceContainerHighest = Color(0xFF313630),
    surfaceTint = Color(0xFF9CD4A0),
    scrim = Color(0xFF000000),
)

private val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(4.dp),
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(12.dp),
    large = RoundedCornerShape(16.dp),
    extraLarge = RoundedCornerShape(28.dp),
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
