package com.example.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

private val TacticianDarkColorScheme = darkColorScheme(
    primary = ElectricCobalt,
    onPrimary = OnElectricCobalt,
    primaryContainer = ElectricCobaltContainer,
    onPrimaryContainer = InkWhite,
    secondary = AmberPop,
    onSecondary = OnAmberPop,
    secondaryContainer = AmberPopContainer,
    onSecondaryContainer = InkWhite,
    tertiary = MintSurge,
    onTertiary = OnMintSurge,
    tertiaryContainer = MintSurgeContainer,
    onTertiaryContainer = MintSurge,
    error = CoralPulse,
    errorContainer = CoralPulseContainer,
    background = SlateCanvasDark,
    onBackground = InkWhite,
    surface = SlateSurfaceDark,
    onSurface = InkWhite,
    surfaceVariant = SlateSurfaceContainerDark,
    onSurfaceVariant = InkMutedDark,
    surfaceContainerLow = SlateSurfaceDark,
    surfaceContainer = SlateSurfaceContainerDark,
    surfaceContainerHigh = SlateSurfaceHighDark,
    outline = OutlineDark
)

private val TacticianLightColorScheme = lightColorScheme(
    primary = CobaltLight,
    onPrimary = CloudSurfaceLight,
    primaryContainer = CobaltContainerLight,
    onPrimaryContainer = InkDark,
    secondary = AmberLight,
    onSecondary = CloudSurfaceLight,
    secondaryContainer = AmberContainerLight,
    onSecondaryContainer = InkDark,
    tertiary = MintLight,
    onTertiary = CloudSurfaceLight,
    tertiaryContainer = MintContainerLight,
    onTertiaryContainer = InkDark,
    error = CoralLight,
    errorContainer = CoralContainerLight,
    background = CloudCanvasLight,
    onBackground = InkDark,
    surface = CloudSurfaceLight,
    onSurface = InkDark,
    surfaceVariant = CloudSurfaceContainerLight,
    onSurfaceVariant = InkMutedLight,
    surfaceContainerLow = CloudSurfaceLight,
    surfaceContainer = CloudSurfaceContainerLight,
    surfaceContainerHigh = CloudSurfaceHighLight,
    outline = OutlineLight
)

val TacticianShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(14.dp),
    medium = RoundedCornerShape(22.dp),
    large = RoundedCornerShape(28.dp),
    extraLarge = RoundedCornerShape(36.dp)
)

@Composable
fun TacticianTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> TacticianDarkColorScheme
        else -> TacticianLightColorScheme
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = TacticianTypography,
        shapes = TacticianShapes,
        content = content
    )
}
