package com.johnanderson.familyportal.tv

import com.johnanderson.familyportal.core.AppSettings
import com.johnanderson.familyportal.core.CameraConfig
import com.johnanderson.familyportal.tv.doorbell.doorbellPopupConfigured
import com.johnanderson.familyportal.tv.ui.tvNeedsSetup
import com.johnanderson.familyportal.tv.ui.viewerHint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TvPortalTest {

    @Test
    fun setupPromptShowsUntilEitherServiceIsConnected() {
        assertTrue(tvNeedsSetup(AppSettings(), googleAuthorized = false))
        assertFalse(tvNeedsSetup(AppSettings(), googleAuthorized = true))
        assertFalse(tvNeedsSetup(AppSettings(homeAssistantUrl = "http://ha:8123"), googleAuthorized = false))
    }

    @Test
    fun viewerHintOnlyOffersAvailableActions() {
        assertEquals("Back Return", viewerHint(canSwitch = false, audioAvailable = false, muted = true))
        assertEquals(
            "◀ ▶ Switch camera   ·   OK Unmute   ·   Back Return",
            viewerHint(canSwitch = true, audioAvailable = true, muted = true),
        )
    }

    @Test
    fun doorbellPopupNeedsHomeAssistantSensorAndDoorbellCamera() {
        val ready = AppSettings(
            homeAssistantUrl = "http://ha:8123",
            doorbellSensorEntityId = "binary_sensor.porch_person",
            cameras = listOf(camera(id = "a", entityId = "camera.porch").copy(isDoorbell = true)),
        )
        assertTrue(doorbellPopupConfigured(ready))
        assertFalse(doorbellPopupConfigured(ready.copy(homeAssistantUrl = "")))
        assertFalse(doorbellPopupConfigured(ready.copy(doorbellSensorEntityId = "")))
        assertFalse(doorbellPopupConfigured(ready.copy(cameras = ready.cameras.map { it.copy(isDoorbell = false) })))
    }

    private fun camera(id: String, entityId: String, previewEntityId: String = "") = CameraConfig(
        id = id,
        name = id,
        entityId = entityId,
        previewEntityId = previewEntityId,
        rtspSecretKey = "key",
    )
}
