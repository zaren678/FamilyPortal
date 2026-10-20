package com.johnanderson.familyportal

import com.johnanderson.familyportal.core.CameraConfig
import com.johnanderson.familyportal.core.entityIds
import com.johnanderson.familyportal.core.previewStream
import com.johnanderson.familyportal.ha.HomeAssistantCameraChoice
import com.johnanderson.familyportal.ha.HomeAssistantEntityChoice
import com.johnanderson.familyportal.ha.configuredFor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CameraConfigTest {

    @Test
    fun previewStreamFallsBackToMainEntity() {
        assertEquals("camera.porch_sub", camera("a", "camera.porch", "camera.porch_sub").previewStream().entityId)
        assertEquals("camera.porch", camera("a", "camera.porch").previewStream().entityId)
    }

    @Test
    fun entityIdsSkipBlankPreview() {
        assertEquals(setOf("camera.porch", "camera.porch_sub"), camera("a", "camera.porch", "camera.porch_sub").entityIds())
        assertEquals(setOf("camera.porch"), camera("a", "camera.porch").entityIds())
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

    private fun camera(id: String, entityId: String, previewEntityId: String = "") = CameraConfig(
        id = id,
        name = id,
        entityId = entityId,
        previewEntityId = previewEntityId,
        rtspSecretKey = "key",
    )
}
