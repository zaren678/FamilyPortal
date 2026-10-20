package com.johnanderson.familyportal.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import kotlin.math.max

fun parseColor(value: String): Color = runCatching {
    Color(android.graphics.Color.parseColor(value))
}.getOrDefault(Color(0xFF607D8B))

fun eventBackgroundColor(value: String): Color {
    val original = parseColor(value)
    if (contrastRatio(original, EVENT_CONTENT_COLOR) >= MIN_TEXT_CONTRAST) {
        return original
    }

    var readableFactor = 0f
    var unreadableFactor = 1f
    repeat(12) {
        val factor = (readableFactor + unreadableFactor) / 2f
        val candidate = original.scaledRgb(factor)
        if (contrastRatio(candidate, EVENT_CONTENT_COLOR) >= MIN_TEXT_CONTRAST) {
            readableFactor = factor
        } else {
            unreadableFactor = factor
        }
    }
    return original.scaledRgb(readableFactor)
}

fun contrastRatio(first: Color, second: Color): Float {
    val lighter = max(first.luminance(), second.luminance())
    val darker = minOf(first.luminance(), second.luminance())
    return (lighter + 0.05f) / (darker + 0.05f)
}

private fun Color.scaledRgb(factor: Float): Color = Color(
    red = red * factor,
    green = green * factor,
    blue = blue * factor,
    alpha = alpha,
)

private const val MIN_TEXT_CONTRAST = 4.5f
val EVENT_CONTENT_COLOR = Color.White
