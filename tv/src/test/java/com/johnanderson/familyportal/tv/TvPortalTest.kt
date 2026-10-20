package com.johnanderson.familyportal.tv

import com.johnanderson.familyportal.core.AppSettings
import com.johnanderson.familyportal.core.CameraConfig
import com.johnanderson.familyportal.ha.HomeAssistantCameraChoice
import com.johnanderson.familyportal.ha.HomeAssistantEntityChoice
import com.johnanderson.familyportal.tv.ui.configuredFor
import com.johnanderson.familyportal.tv.ui.tvNeedsSetup
import com.johnanderson.familyportal.tv.ui.viewerHint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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
    fun cameraChoiceMatchesConfiguredCameraByMainOrPreviewEntity() {
        val choice = HomeAssistantCameraChoice(
            main = HomeAssistantEntityChoice("camera.porch", "Porch", null),
            preview = HomeAssistantEntityChoice("camera.porch_sub", "Porch sub", null),
        )
        val byMain = camera(id = "a", entityId = "camera.porch")
        val byPreview = camera(id = "b", entityId = "camera.other", previewEntityId = "camera.porch_sub")
        val unrelated = camera(id = "c", entityId = "camera.garage")

        assertEquals("a", listOf(unrelated, byMain).configuredFor(choice)?.id)
        assertEquals("b", listOf(unrelated, byPreview).configuredFor(choice)?.id)
        assertNull(listOf(unrelated).configuredFor(choice))
    }

    @Test
    fun viewerHintOnlyOffersAvailableActions() {
        assertEquals("Back Return", viewerHint(canSwitch = false, audioAvailable = false, muted = true))
        assertEquals(
            "◀ ▶ Switch camera   ·   OK Unmute   ·   Back Return",
            viewerHint(canSwitch = true, audioAvailable = true, muted = true),
        )
    }

    private fun camera(id: String, entityId: String, previewEntityId: String = "") = CameraConfig(
        id = id,
        name = id,
        entityId = entityId,
        previewEntityId = previewEntityId,
        rtspSecretKey = "key",
    )
}
