package dev.infinityf4p.tiebapure.core.designsystem

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val LightColors = lightColorScheme(
    primary = Color(0xFF007AFF),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD8E9FF),
    onPrimaryContainer = Color(0xFF001C38),
    inversePrimary = Color(0xFF0A84FF),
    secondary = Color(0xFF536577),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFD7E4F7),
    onSecondaryContainer = Color(0xFF101C29),
    tertiary = Color(0xFF248A3D),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFD9F7E0),
    onTertiaryContainer = Color(0xFF0A3215),
    background = Color(0xFFF2F2F7),
    onBackground = Color(0xFF1C1C1E),
    surface = Color.White,
    onSurface = Color(0xFF1C1C1E),
    surfaceTint = Color(0xFF007AFF),
    surfaceVariant = Color(0xFFF2F2F7),
    onSurfaceVariant = Color(0xFF636366),
    inverseSurface = Color(0xFF2C2C2E),
    inverseOnSurface = Color(0xFFF2F2F7),
    surfaceDim = Color(0xFFD9D9DE),
    surfaceBright = Color.White,
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color(0xFFF8F8FA),
    surfaceContainer = Color(0xFFF2F2F7),
    surfaceContainerHigh = Color(0xFFECECF1),
    surfaceContainerHighest = Color(0xFFE5E5EA),
    outline = Color(0xFFC6C6C8),
    outlineVariant = Color(0xFFE5E5EA),
    error = Color(0xFFD70015),
    onError = Color.White,
    errorContainer = Color(0xFFFFE5E5),
    onErrorContainer = Color(0xFF680008),
    scrim = Color.Black,
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF0A84FF),
    onPrimary = Color.Black,
    primaryContainer = Color(0xFF003B70),
    onPrimaryContainer = Color(0xFFD8E9FF),
    inversePrimary = Color(0xFF007AFF),
    secondary = Color(0xFFB8C8DA),
    onSecondary = Color(0xFF233240),
    secondaryContainer = Color(0xFF394857),
    onSecondaryContainer = Color(0xFFD7E4F7),
    tertiary = Color(0xFF30D158),
    onTertiary = Color.Black,
    tertiaryContainer = Color(0xFF164823),
    onTertiaryContainer = Color(0xFFD9F7E0),
    background = Color.Black,
    onBackground = Color(0xFFF2F2F7),
    surface = Color(0xFF1C1C1E),
    onSurface = Color(0xFFF2F2F7),
    surfaceTint = Color(0xFF0A84FF),
    surfaceVariant = Color(0xFF2C2C2E),
    onSurfaceVariant = Color(0xFFAEAEB2),
    inverseSurface = Color(0xFFE5E5EA),
    inverseOnSurface = Color(0xFF1C1C1E),
    surfaceDim = Color.Black,
    surfaceBright = Color(0xFF2C2C2E),
    surfaceContainerLowest = Color.Black,
    surfaceContainerLow = Color(0xFF161618),
    surfaceContainer = Color(0xFF1C1C1E),
    surfaceContainerHigh = Color(0xFF242426),
    surfaceContainerHighest = Color(0xFF2C2C2E),
    outline = Color(0xFF48484A),
    outlineVariant = Color(0xFF38383A),
    error = Color(0xFFFF453A),
    onError = Color.Black,
    errorContainer = Color(0xFF680008),
    onErrorContainer = Color(0xFFFFDAD6),
    scrim = Color.Black,
)

private val TiebaPureTypography = Typography(
    displaySmall = TextStyle(
        fontWeight = FontWeight.Bold,
        fontSize = 34.sp,
        lineHeight = 41.sp,
        letterSpacing = 0.sp,
    ),
    headlineSmall = TextStyle(
        fontWeight = FontWeight.Bold,
        fontSize = 28.sp,
        lineHeight = 34.sp,
        letterSpacing = 0.sp,
    ),
    titleLarge = TextStyle(
        fontWeight = FontWeight.SemiBold,
        fontSize = 22.sp,
        lineHeight = 28.sp,
        letterSpacing = 0.sp,
    ),
    titleMedium = TextStyle(
        fontWeight = FontWeight.SemiBold,
        fontSize = 17.sp,
        lineHeight = 22.sp,
        letterSpacing = 0.sp,
    ),
    titleSmall = TextStyle(
        fontWeight = FontWeight.SemiBold,
        fontSize = 15.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.sp,
    ),
    bodyLarge = TextStyle(
        fontWeight = FontWeight.Normal,
        fontSize = 17.sp,
        lineHeight = 24.sp,
        letterSpacing = 0.sp,
    ),
    bodyMedium = TextStyle(
        fontWeight = FontWeight.Normal,
        fontSize = 15.sp,
        lineHeight = 21.sp,
        letterSpacing = 0.sp,
    ),
    bodySmall = TextStyle(
        fontWeight = FontWeight.Normal,
        fontSize = 13.sp,
        lineHeight = 18.sp,
        letterSpacing = 0.sp,
    ),
    labelLarge = TextStyle(
        fontWeight = FontWeight.Medium,
        fontSize = 15.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.sp,
    ),
    labelMedium = TextStyle(
        fontWeight = FontWeight.Medium,
        fontSize = 13.sp,
        lineHeight = 18.sp,
        letterSpacing = 0.sp,
    ),
    labelSmall = TextStyle(
        fontWeight = FontWeight.Medium,
        fontSize = 12.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.sp,
    ),
)

private val TiebaPureShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(8.dp),
    large = RoundedCornerShape(8.dp),
    extraLarge = RoundedCornerShape(8.dp),
)

@Immutable
data class TiebaPureDimensions(
    val spacingXxs: Dp = 4.dp,
    val spacingXs: Dp = 8.dp,
    val spacingSm: Dp = 12.dp,
    val spacingMd: Dp = 16.dp,
    val spacingLg: Dp = 24.dp,
    val chipRadius: Dp = 6.dp,
    val mediaRadius: Dp = 8.dp,
    val cardRadius: Dp = 8.dp,
    val avatarSmall: Dp = 32.dp,
    val avatarMedium: Dp = 40.dp,
    val avatarLarge: Dp = 48.dp,
    val avatarProfile: Dp = 56.dp,
    val iconInline: Dp = 17.dp,
    val iconToolbar: Dp = 22.dp,
    val iconPlay: Dp = 48.dp,
    val minimumTouchTarget: Dp = 48.dp,
    val readableContentMaxWidth: Dp = 680.dp,
)

val LocalTiebaPureDimensions = staticCompositionLocalOf { TiebaPureDimensions() }

object TiebaPureColors {
    val VideoAccent = Color(0xFFF59E0B)
}

@Composable
fun TiebaPureTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val colors = if (darkTheme) DarkColors else LightColors
    MaterialTheme(
        colorScheme = colors,
        typography = TiebaPureTypography,
        shapes = TiebaPureShapes,
        content = content,
    )
}
