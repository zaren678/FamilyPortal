package com.johnanderson.familyportal.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val PortalColors = lightColorScheme(
    primary = Color(0xFF1565C0),
    onPrimary = Color.White,
    secondary = Color(0xFF3E6B48),
    tertiary = Color(0xFFD32F2F),
    background = Color(0xFFF4F4F0),
    surface = Color(0xFFFFFFFF),
    surfaceVariant = Color(0xFFE5E7E2),
    outline = Color(0xFF737772),
    onBackground = Color(0xFF20231F),
    onSurface = Color(0xFF20231F),
)

@Composable
fun FamilyPortalTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = PortalColors,
        typography = Typography(),
        content = content,
    )
}
