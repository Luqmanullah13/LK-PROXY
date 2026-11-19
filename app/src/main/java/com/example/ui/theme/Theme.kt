package com.example.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

private val LkDarkColorScheme = darkColorScheme(
    primary = LkRed,
    onPrimary = LkWhite,
    primaryContainer = LkRedDim,
    onPrimaryContainer = LkWhite,
    secondary = LkRed,
    onSecondary = LkWhite,
    background = LkBlack,
    onBackground = LkWhite,
    surface = LkSurface,
    onSurface = LkWhite,
    surfaceVariant = LkSurfaceElevated,
    onSurfaceVariant = LkGrey,
    outline = LkRed,
    outlineVariant = LkBorderMuted,
    error = LkRed,
    onError = LkWhite
)

@Composable
fun LkProxyTheme(
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = LkDarkColorScheme,
        typography = LkTypography,
        content = content
    )
}
