package com.johnanderson.familyportal

import com.johnanderson.familyportal.ha.HomeAssistantCatalog
import com.johnanderson.familyportal.ha.HomeAssistantEntityChoice
import com.johnanderson.familyportal.ha.findLogicalCamera
import com.johnanderson.familyportal.ha.isLikelyCameraSubstream
import com.johnanderson.familyportal.ha.logicalCameras
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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
    fun groupsFrigateMainAndSubEntitiesAsOneLogicalCamera() {
        val catalog = HomeAssistantCatalog(
            personSensors = emptyList(),
            cameras = listOf(
                camera("camera.whole_house_frigate_doorbell", "Frigate Doorbell"),
                camera("camera.doorbell_sub_2", "Frigate Doorbell Sub 2"),
                camera("camera.whole_house_frigate_driveway", "Frigate Driveway"),
            ),
        )

        val cameras = catalog.logicalCameras()

        assertEquals(2, cameras.size)
        assertEquals(
            "camera.doorbell_sub_2",
            cameras.first { it.main.name == "Frigate Doorbell" }.preview?.entityId,
        )
        assertNull(cameras.first { it.main.name == "Frigate Driveway" }.preview)
    }

    @Test
    fun doesNotGuessWhenFriendlyNamePairingIsAmbiguous() {
        val catalog = HomeAssistantCatalog(
            personSensors = emptyList(),
            cameras = listOf(
                camera("camera.frigate_doorbell", "Frigate Doorbell"),
                camera("camera.doorbell_sub", "Frigate Doorbell Sub"),
                camera("camera.doorbell_sub_2", "Frigate Doorbell Sub 2"),
            ),
        )

        assertNull(catalog.logicalCameras().single().preview)
    }

    @Test
    fun resolvesExactEntityBeforeFriendlyName() {
        val catalog = HomeAssistantCatalog(
            personSensors = emptyList(),
            cameras = listOf(
                camera("camera.lorex_doorbell", "Doorbell"),
                camera("camera.frigate_doorbell", "Doorbell"),
            ),
        )

        assertEquals(
            "camera.frigate_doorbell",
            catalog.findLogicalCamera("camera.frigate_doorbell", "", "Doorbell")?.main?.entityId,
        )
        assertNull(catalog.findLogicalCamera("camera.removed_doorbell", "", "Doorbell"))
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
