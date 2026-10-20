package com.johnanderson.familyportal.tv

import com.johnanderson.familyportal.core.AppSettings
import com.johnanderson.familyportal.core.AppUiState
import com.johnanderson.familyportal.core.CameraConfig
import com.johnanderson.familyportal.core.PortalOverlay
import com.johnanderson.familyportal.tv.overlay.OverlayContent
import com.johnanderson.familyportal.tv.overlay.overlayContent
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TvOverlayTest {
    private val doorbell = camera("door")
    private val backyard = camera("backyard")
    private val settings = AppSettings(homeAssistantUrl = "http://ha:8123", cameras = listOf(doorbell, backyard))
    private val pinned = AppUiState(pinnedCameraId = "backyard")
    private val alerting = pinned.copy(overlay = PortalOverlay.Doorbell("door", Instant.EPOCH, playChime = false))

    @Test
    fun doorbellAlertTakesTheCornerEvenOverFamilyPortal() {
        assertEquals(
            OverlayContent.Doorbell(doorbell, "http://ha:8123"),
            overlayContent(alerting, settings, appVisible = true, dreaming = false),
        )
    }

    @Test
    fun pinReturnsOnceTheAlertEnds() {
        assertEquals(
            OverlayContent.Pinned(backyard, "http://ha:8123"),
            overlayContent(pinned, settings, appVisible = false, dreaming = false),
        )
    }

    @Test
    fun pinWaitsWhileFamilyPortalOrTheScreensaverIsShowing() {
        assertNull(overlayContent(pinned, settings, appVisible = true, dreaming = false))
        assertNull(overlayContent(pinned, settings, appVisible = false, dreaming = true))
    }

    @Test
    fun nothingShowsForARemovedPinnedCamera() {
        assertNull(overlayContent(pinned, settings.copy(cameras = listOf(doorbell)), appVisible = false, dreaming = false))
        assertNull(overlayContent(AppUiState(), settings, appVisible = false, dreaming = false))
    }

    private fun camera(id: String) = CameraConfig(id = id, name = id, entityId = "camera.$id", rtspSecretKey = "key")
}
