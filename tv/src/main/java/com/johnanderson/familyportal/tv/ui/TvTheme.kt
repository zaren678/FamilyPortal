package com.johnanderson.familyportal.tv.ui

import androidx.compose.runtime.Composable
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.darkColorScheme
import com.johnanderson.familyportal.ui.theme.FamilyPortalTheme

/**
 * Portal palette for both toolkits: shared core-ui composables read the Material 3
 * theme, while TV components (focusable surfaces, list items) read tv-material's.
 */
@Composable
fun FamilyPortalTvTheme(content: @Composable () -> Unit) {
    FamilyPortalTheme {
        val colors = androidx.compose.material3.MaterialTheme.colorScheme
        MaterialTheme(
            colorScheme = darkColorScheme(
                primary = colors.primary,
                onPrimary = colors.onPrimary,
                primaryContainer = colors.primaryContainer,
                onPrimaryContainer = colors.onPrimaryContainer,
                secondary = colors.secondary,
                onSecondary = colors.onSecondary,
                tertiary = colors.tertiary,
                onTertiary = colors.onTertiary,
                background = colors.background,
                onBackground = colors.onBackground,
                surface = colors.surface,
                onSurface = colors.onSurface,
                surfaceVariant = colors.surfaceVariant,
                onSurfaceVariant = colors.onSurfaceVariant,
                inverseSurface = colors.inverseSurface,
                inverseOnSurface = colors.inverseOnSurface,
                error = colors.error,
                onError = colors.onError,
                border = colors.outline,
                borderVariant = colors.outlineVariant,
            ),
            content = content,
        )
    }
}
