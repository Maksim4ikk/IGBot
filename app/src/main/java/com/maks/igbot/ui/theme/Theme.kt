package com.maks.igbot.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val LightColorScheme = lightColorScheme(
    primary = BrandPrimary,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE5E9D8),
    onPrimaryContainer = Color(0xFF27311D),
    secondary = BrandAccent,
    onSecondary = Color.White,
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

@Composable
fun IGBotTheme(
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = LightColorScheme,
        typography = Typography,
        content = content
    )
}
