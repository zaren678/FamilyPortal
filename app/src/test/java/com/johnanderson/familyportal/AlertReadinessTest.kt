package com.johnanderson.familyportal

import android.app.NotificationManager
import com.johnanderson.familyportal.service.AlertReadiness
import com.johnanderson.familyportal.service.alertChannelNeedsRecreate
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AlertReadinessTest {
    @Test
    fun popToForegroundRequiresAllThreeSignals() {
        assertTrue(
            AlertReadiness(
                notificationsEnabled = true,
                channelImportance = NotificationManager.IMPORTANCE_HIGH,
                fullScreenIntentAllowed = true,
            ).canPopToForeground,
        )
        assertFalse(
            AlertReadiness(
                notificationsEnabled = false,
                channelImportance = NotificationManager.IMPORTANCE_HIGH,
                fullScreenIntentAllowed = true,
            ).canPopToForeground,
        )
        assertFalse(
            AlertReadiness(
                notificationsEnabled = true,
                channelImportance = NotificationManager.IMPORTANCE_DEFAULT,
                fullScreenIntentAllowed = true,
            ).canPopToForeground,
        )
        assertFalse(
            AlertReadiness(
                notificationsEnabled = true,
                channelImportance = NotificationManager.IMPORTANCE_HIGH,
                fullScreenIntentAllowed = false,
            ).canPopToForeground,
        )
    }

    @Test
    fun channelRecreatedOnlyWhenBelowHigh() {
        assertFalse(alertChannelNeedsRecreate(null))
        assertFalse(alertChannelNeedsRecreate(NotificationManager.IMPORTANCE_HIGH))
        assertFalse(alertChannelNeedsRecreate(NotificationManager.IMPORTANCE_MAX))
        assertTrue(alertChannelNeedsRecreate(NotificationManager.IMPORTANCE_DEFAULT))
        assertTrue(alertChannelNeedsRecreate(NotificationManager.IMPORTANCE_NONE))
    }
}
