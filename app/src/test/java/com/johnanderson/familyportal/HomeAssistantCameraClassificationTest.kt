package com.johnanderson.familyportal

import com.johnanderson.familyportal.ha.HomeAssistantEntityChoice
import com.johnanderson.familyportal.ha.isLikelyCameraSubstream
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeAssistantCameraClassificationTest {
    @Test
    fun classifiesCommonSubstreamNames() {
        assertTrue(camera("camera.doorbell_sub_4", "Doorbell Sub").isLikelyCameraSubstream())
        assertTrue(camera("camera.driveway_lowres", "Driveway").isLikelyCameraSubstream())
        assertTrue(camera("camera.front_yard_low_resolution", "Front Yard Low Resolution").isLikelyCameraSubstream())
    }

    @Test
    fun keepsPrimaryCamerasAndNamesContainingSub() {
        assertFalse(camera("camera.doorbell_main", "Doorbell Main").isLikelyCameraSubstream())
        assertFalse(camera("camera.suburban_driveway", "Suburban Driveway").isLikelyCameraSubstream())
    }

    private fun camera(entityId: String, name: String) = HomeAssistantEntityChoice(
        entityId = entityId,
        name = name,
        deviceClass = null,
    )
}
