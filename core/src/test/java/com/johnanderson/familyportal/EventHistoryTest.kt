package com.johnanderson.familyportal

import com.johnanderson.familyportal.core.CameraConfig
import com.johnanderson.familyportal.ha.FrigateCamera
import com.johnanderson.familyportal.ha.FrigateEvent
import com.johnanderson.familyportal.ha.HomeAssistantCatalog
import com.johnanderson.familyportal.ha.HomeAssistantEntityChoice
import com.johnanderson.familyportal.ha.frigateCameras
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant

class EventHistoryTest {
    @Test
    fun findsEachCamerasFrigateCameraFromItsMainStream() {
        val catalog = HomeAssistantCatalog(
            personSensors = emptyList(),
            cameras = listOf(
                choice("camera.whole_house_frigate_doorbell", FrigateCamera("frigate", "doorbell")),
                choice("camera.doorbell_sub_2", FrigateCamera("frigate", "doorbell_sub")),
                choice("camera.garage_sub", FrigateCamera("frigate", "garage")),
                choice("camera.porch", frigate = null),
            ),
        )

        val frigate = catalog.frigateCameras(
            listOf(
                camera("doorbell", "camera.whole_house_frigate_doorbell", "camera.doorbell_sub_2"),
                // Only the preview comes from Frigate.
                camera("garage", "camera.garage_rtsp", "camera.garage_sub"),
                camera("porch", "camera.porch"),
            ),
        )

        assertEquals(
            mapOf("doorbell" to FrigateCamera("frigate", "doorbell"), "garage" to FrigateCamera("frigate", "garage")),
            frigate,
        )
    }

    @Test
    fun newestPageReplacesWhatItCoversAndKeepsOlderEvents() {
        // "x" was deleted in Frigate since it was loaded; "c" has ended since.
        val loaded = listOf(event("x", 35), event("c", 30, ongoing = true), event("b", 20), event("a", 10))
        val page = listOf(event("d", 40), event("c", 30))

        val merged = mergeNewestPage(loaded, page, pageSize = 2)
        assertEquals(listOf("d", "c", "b", "a"), merged.map { it.id })
        assertEquals(Instant.ofEpochSecond(35), merged[1].endTime)
        // A short page is everything there is, e.g. after Frigate deleted old events.
        assertEquals(listOf("d"), mergeNewestPage(loaded, listOf(event("d", 40)), pageSize = 2).map { it.id })
    }

    @Test
    fun olderPageSkipsEventsAlreadyShown() {
        val loaded = listOf(event("c", 30), event("b", 20))
        assertEquals(
            listOf("c", "b", "a"),
            appendOlderPage(loaded, listOf(event("b", 20), event("a", 10))).map { it.id },
        )
    }

    @Test
    fun labelsAreOfferedMostCommonFirst() {
        val events = listOf(event("1", 1, "car"), event("2", 2, "person"), event("3", 3, "car"), event("4", 4, "dog"))
        assertEquals(listOf("car", "dog", "person"), labelsByFrequency(events))
    }

    @Test
    fun onlyCamerasWithDetectionsAreOfferedAsFilters() {
        val doorbell = HistoryCamera("doorbell", "Doorbell", FrigateCamera("frigate", "doorbell"))
        val driveway = HistoryCamera("driveway", "Driveway", FrigateCamera("frigate", "driveway"))
        val yard = HistoryCamera("yard", "Yard", FrigateCamera("frigate", "yard"))
        val onlyDoorbell = EventHistoryState(cameras = listOf(doorbell, driveway, yard), camerasSeen = setOf(doorbell.frigate))

        // One camera detecting: nothing to choose between.
        assertEquals(listOf(doorbell), onlyDoorbell.filterCameras)
        assertEquals(false, onlyDoorbell.offerCameraFilter)
        // Opened for a camera that detects nothing: it's still shown, with a way back to all.
        val chosen = onlyDoorbell.copy(selectedCameraId = "driveway")
        assertEquals(listOf(doorbell, driveway), chosen.filterCameras)
        assertEquals(true, chosen.offerCameraFilter)
        // Detection turned on for another camera.
        assertEquals(true, onlyDoorbell.copy(camerasSeen = setOf(doorbell.frigate, yard.frigate)).offerCameraFilter)
    }

    private fun choice(entityId: String, frigate: FrigateCamera?) =
        HomeAssistantEntityChoice(entityId, entityId, "camera", frigate)

    private fun camera(id: String, entityId: String, previewEntityId: String = "") =
        CameraConfig(id = id, name = id, entityId = entityId, previewEntityId = previewEntityId, rtspSecretKey = "")

    private fun event(id: String, startSeconds: Long, label: String = "person", ongoing: Boolean = false) = FrigateEvent(
        id = id,
        camera = FrigateCamera("frigate", "doorbell"),
        label = label,
        subLabel = null,
        startTime = Instant.ofEpochSecond(startSeconds),
        endTime = if (ongoing) null else Instant.ofEpochSecond(startSeconds + 5),
        hasClip = true,
        hasSnapshot = true,
    )
}
