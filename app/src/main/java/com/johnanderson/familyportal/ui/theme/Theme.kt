package com.johnanderson.familyportal.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val PortalColors = darkColorScheme(
    primary = Color(0xFF9CCBFF),
    onPrimary = Color(0xFF003354),
    primaryContainer = Color(0xFF164B6F),
    onPrimaryContainer = Color(0xFFD2E9FF),
    secondary = Color(0xFFA5D6AF),
    onSecondary = Color(0xFF12371E),
    tertiary = Color(0xFFFFB4AB),
    onTertiary = Color(0xFF690005),
    background = Color(0xFF111315),
    onBackground = Color(0xFFE3E6E9),
    surface = Color(0xFF181A1D),
    onSurface = Color(0xFFE3E6E9),
    surfaceVariant = Color(0xFF2A2E32),
    onSurfaceVariant = Color(0xFFC4C8CC),
    outline = Color(0xFF8E9499),
    outlineVariant = Color(0xFF41464B),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
)

@Composable
fun FamilyPortalTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = PortalColors,
        typography = Typography(),
        content = content,
    )
}
