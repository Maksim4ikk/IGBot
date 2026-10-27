package com.maks.igbot.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

private val LightColorScheme = lightColorScheme(
    primary = BrandPrimary,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE5E9D8),
    onPrimaryContainer = Color(0xFF27311D),
    secondary = BrandAccent,
    onSecondary = BrandPrimaryDark,
    secondaryContainer = Color(0xFFE8EED5),
    onSecondaryContainer = Color(0xFF33421A),
    background = AppBackground,
    onBackground = AppText,
    surface = AppSurface,
    onSurface = AppText,
    surfaceVariant = AppSurfaceSoft,
    onSurfaceVariant = AppTextMuted,
    outline = AppOutline,
    error = AppError,
    onError = Color.White
)

// Єдина система заокруглень: поля й кнопки — 14, картки — 24
private val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(14.dp),
    medium = RoundedCornerShape(18.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(28.dp)
)

@Composable
fun IGBotTheme(
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = LightColorScheme,
        typography = Typography,
        shapes = AppShapes,
        content = content
    )
}
