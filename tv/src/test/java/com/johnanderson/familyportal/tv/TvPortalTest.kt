package com.johnanderson.familyportal.tv

import com.johnanderson.familyportal.core.AppSettings
import com.johnanderson.familyportal.core.CameraConfig
import com.johnanderson.familyportal.tv.overlay.doorbellPopupConfigured
import com.johnanderson.familyportal.tv.ui.tvNeedsSetup
import com.johnanderson.familyportal.tv.ui.ViewerHint
import com.johnanderson.familyportal.tv.ui.viewerHints
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
    fun viewerHintsOnlyOfferAvailableActions() {
        assertEquals(
            listOf(ViewerHint.PIN),
            viewerHints(canSwitch = false, audioAvailable = false, historyAvailable = false),
        )
        assertEquals(
            listOf(ViewerHint.SWITCH_CAMERA, ViewerHint.HISTORY, ViewerHint.PIN, ViewerHint.TOGGLE_AUDIO),
            viewerHints(canSwitch = true, audioAvailable = true, historyAvailable = true),
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
