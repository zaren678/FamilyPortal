package com.johnanderson.familyportal

import android.content.Context
import android.content.Intent
import android.provider.Settings

/** Android's own settings, or null if this device has no settings screen to open. */
fun deviceSettingsIntent(context: Context): Intent? =
    Intent(Settings.ACTION_SETTINGS)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        .takeIf { it.resolveActivity(context.packageManager) != null }

/**
 * The device's other home screen (Meta's, on the Portal), opened directly so the
 * default doesn't change and Home still comes back to Family Portal. Null if
 * Family Portal is the only home screen.
 */
fun otherHomeScreenIntent(context: Context): Intent? {
    val home = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
    val candidates = context.packageManager.queryIntentActivities(home, 0).map {
        HomeCandidate(it.activityInfo.packageName, it.activityInfo.name, it.priority)
    }
    val other = otherHomeScreen(candidates, context.packageName) ?: return null
    return home.setClassName(other.packageName, other.className).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
}

internal data class HomeCandidate(val packageName: String, val className: String, val priority: Int)

/**
 * Skips Family Portal and negative-priority placeholders like Settings'
 * FallbackHome, which only shows "Android is starting" during boot.
 */
internal fun otherHomeScreen(candidates: List<HomeCandidate>, ownPackage: String): HomeCandidate? =
    candidates
        .filter { it.packageName != ownPackage && it.priority >= 0 }
        .maxByOrNull { it.priority }
