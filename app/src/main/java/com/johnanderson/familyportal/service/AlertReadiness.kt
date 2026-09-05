package com.johnanderson.familyportal.service

import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.app.NotificationManagerCompat

/**
 * Whether a doorbell alert posted while the app is backgrounded can actually
 * bring the portal to the foreground.
 *
 * A backgrounded [DoorbellService] cannot start [android.app.Activity]s
 * directly: since Android 10 the system silently drops background activity
 * starts, so the full-screen-intent notification is the only sanctioned
 * bring-to-front path. Every field below must hold for it to fire.
 */
data class AlertReadiness(
    val notificationsEnabled: Boolean,
    val channelImportance: Int,
    val fullScreenIntentAllowed: Boolean,
) {
    val canPopToForeground: Boolean
        get() = notificationsEnabled &&
            channelImportance >= NotificationManager.IMPORTANCE_HIGH &&
            fullScreenIntentAllowed
}

/** Reads the current alert-readiness state from the system. */
fun checkAlertReadiness(context: Context): AlertReadiness {
    val manager = context.getSystemService(NotificationManager::class.java)
    val channel = manager?.getNotificationChannel(DoorbellService.ALERT_CHANNEL)
    return AlertReadiness(
        notificationsEnabled = NotificationManagerCompat.from(context).areNotificationsEnabled(),
        channelImportance = channel?.importance ?: NotificationManager.IMPORTANCE_NONE,
        fullScreenIntentAllowed = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            manager?.canUseFullScreenIntent() == true
        } else {
            true
        },
    )
}

/**
 * An existing alert channel below HIGH importance can never show a
 * full-screen intent, and [NotificationManager.createNotificationChannel] is a
 * no-op for channels that already exist — so it must be deleted and recreated.
 */
internal fun alertChannelNeedsRecreate(existingImportance: Int?): Boolean =
    existingImportance != null && existingImportance < NotificationManager.IMPORTANCE_HIGH

/** System screen where the user can re-enable this app's notifications. */
fun appNotificationSettingsIntent(context: Context): Intent =
    Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
        putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
    }

/**
 * System screen for the Android 14+ full-screen-notification permission.
 * Returns null below Android 14, where no such toggle exists.
 */
fun fullScreenIntentSettingsIntent(context: Context): Intent? =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
        Intent(
            Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT,
            Uri.fromParts("package", context.packageName, null),
        )
    } else {
        null
    }
