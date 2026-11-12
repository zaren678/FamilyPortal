package com.johnanderson.familyportal.ha

import com.johnanderson.familyportal.core.CameraConfig
import com.johnanderson.familyportal.core.entityIds
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException

data class HomeAssistantEntityChoice(
    val entityId: String,
    val name: String,
    val deviceClass: String?,
    /** Set on cameras from the Frigate integration. */
    val frigateCamera: FrigateCamera? = null,
)

data class HomeAssistantCameraChoice(
    val main: HomeAssistantEntityChoice,
    val preview: HomeAssistantEntityChoice?,
)

/** The configured camera that already uses this Home Assistant camera's main or preview stream. */
fun List<CameraConfig>.configuredFor(choice: HomeAssistantCameraChoice): CameraConfig? {
    val choiceEntityIds = setOfNotNull(choice.main.entityId, choice.preview?.entityId)
    return firstOrNull { camera -> camera.entityIds().any(choiceEntityIds::contains) }
}

/**
 * The Frigate camera behind each of [cameras] that has one, by camera ID. Frigate's
 * detections come from the main stream's camera, so that's preferred over the preview's.
 */
fun HomeAssistantCatalog.frigateCameras(cameras: List<CameraConfig>): Map<String, FrigateCamera> {
    val byEntityId = this.cameras.associateBy(HomeAssistantEntityChoice::entityId)
    return cameras.mapNotNull { camera ->
        val frigate = byEntityId[camera.entityId]?.frigateCamera
            ?: byEntityId[camera.previewEntityId]?.frigateCamera
            ?: return@mapNotNull null
        camera.id to frigate
    }.toMap()
}

fun HomeAssistantEntityChoice.isLikelyCameraSubstream(): Boolean {
    val searchable = "$name ${entityId.substringAfter('.')}".lowercase()
    return SUBSTREAM_TERM.containsMatchIn(searchable) || LOW_RESOLUTION_TERM.containsMatchIn(searchable)
}

fun HomeAssistantCatalog.logicalCameras(): List<HomeAssistantCameraChoice> {
    val previews = cameras.filter(HomeAssistantEntityChoice::isLikelyCameraSubstream)
    return cameras
        .filterNot(HomeAssistantEntityChoice::isLikelyCameraSubstream)
        .map { main ->
            val entityMatch = previews.firstOrNull { preview ->
                preview.cameraPairingKey() == main.cameraPairingKey()
            }
            val nameMatches = previews.filter { preview ->
                preview.cameraDisplayNameKey() == main.cameraDisplayNameKey()
            }
            HomeAssistantCameraChoice(
                main = main,
                preview = entityMatch ?: nameMatches.singleOrNull(),
            )
        }
}

fun HomeAssistantCatalog.findLogicalCamera(
    entityId: String,
    previewEntityId: String,
    name: String,
): HomeAssistantCameraChoice? {
    val logicalCameras = logicalCameras()
    logicalCameras.firstOrNull { camera ->
        camera.main.entityId == entityId ||
            camera.preview?.entityId == entityId ||
            camera.main.entityId == previewEntityId ||
            camera.preview?.entityId == previewEntityId
    }?.let { return it }

    if (cameras.any { it.entityId == entityId }) return null
    return logicalCameras.filter { it.main.cameraDisplayNameKey() == name.cameraDisplayNameKey() }
        .singleOrNull()
}

private fun HomeAssistantEntityChoice.cameraDisplayNameKey(): String = name.cameraDisplayNameKey()

private fun String.cameraDisplayNameKey(): String = lowercase()
    .replace(CAMERA_VARIANT_TERM, " ")
    .replace(Regex("[^a-z0-9]+"), " ")
    .trim()

private fun HomeAssistantEntityChoice.cameraPairingKey(): String = entityId
    .substringAfter('.')
    .lowercase()
    .replace(CAMERA_VARIANT_TERM, " ")
    .replace(Regex("[^a-z0-9]+"), " ")
    .trim()

private val CAMERA_VARIANT_TERM =
    Regex("""(?:^|[\s_.-])(main|sub|substream|lowres|low[\s_.-]+resolution)(?:[\s_.-]+\d+)?(?=$|[\s_.-])""")

private val SUBSTREAM_TERM = Regex("""(?:^|[\s_.-])(sub|substream|lowres)(?:$|[\s_.-])""")
private val LOW_RESOLUTION_TERM = Regex("""(?:^|[\s_.-])low[\s_.-]+resolution(?:$|[\s_.-])""")

data class HomeAssistantCatalog(
    val personSensors: List<HomeAssistantEntityChoice>,
    val cameras: List<HomeAssistantEntityChoice>,
    val weather: List<HomeAssistantEntityChoice> = emptyList(),
)

class HomeAssistantCatalogClient(
    private val httpClient: OkHttpClient,
    private val json: Json,
    private val authManager: HomeAssistantAuthManager,
) {
    suspend fun load(baseUrl: String): HomeAssistantCatalog = withContext(Dispatchers.IO) {
        val token = authManager.accessToken() ?: throw IOException("Home Assistant authorization is required")
        val request = Request.Builder()
            .url("${baseUrl.trimEnd('/')}/api/states")
            .header("Authorization", "Bearer $token")
            .header("Accept", "application/json")
            .build()
        val states = httpClient.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) throw IOException("Unable to load Home Assistant entities (${response.code})")
            json.decodeFromString<List<StateResponse>>(body)
        }
        val choices = states.map { state ->
            HomeAssistantEntityChoice(
                entityId = state.entityId,
                name = state.attributes.friendlyName ?: state.entityId,
                deviceClass = state.attributes.deviceClass,
                frigateCamera = state.frigateCamera(),
            )
        }
        HomeAssistantCatalog(
            personSensors = choices.filter { choice ->
                choice.entityId.startsWith("binary_sensor.") &&
                    (choice.deviceClass in PERSON_DEVICE_CLASSES ||
                        PERSON_TERMS.any { choice.name.contains(it, ignoreCase = true) })
            }.sortedBy(HomeAssistantEntityChoice::name),
            cameras = choices.filter { it.entityId.startsWith("camera.") }
                .sortedBy(HomeAssistantEntityChoice::name),
            weather = choices.filter { it.entityId.startsWith("weather.") }
                .sortedBy(HomeAssistantEntityChoice::name),
        )
    }

    @Serializable
    private data class StateResponse(
        @kotlinx.serialization.SerialName("entity_id") val entityId: String,
        val attributes: Attributes = Attributes(),
    )

    @Serializable
    private data class Attributes(
        @kotlinx.serialization.SerialName("friendly_name") val friendlyName: String? = null,
        @kotlinx.serialization.SerialName("device_class") val deviceClass: String? = null,
        // The Frigate integration's camera entities say which Frigate camera they show.
        // Other integrations may use these names for anything, so they're read leniently.
        @kotlinx.serialization.SerialName("camera_name") val frigateCameraName: JsonElement? = null,
        @kotlinx.serialization.SerialName("client_id") val frigateClientId: JsonElement? = null,
    )

    private fun StateResponse.frigateCamera(): FrigateCamera? {
        if (!entityId.startsWith("camera.")) return null
        val name = (attributes.frigateCameraName as? JsonPrimitive)?.contentOrNull?.takeIf(String::isNotBlank) ?: return null
        val instanceId = (attributes.frigateClientId as? JsonPrimitive)?.contentOrNull?.takeIf(String::isNotBlank)
        return FrigateCamera(instanceId ?: DEFAULT_FRIGATE_INSTANCE, name)
    }

    private companion object {
        val PERSON_DEVICE_CLASSES = setOf("motion", "occupancy", "presence")
        val PERSON_TERMS = listOf("person", "doorbell", "front door", "visitor")
        // Frigate's default MQTT client ID, which the integration uses to name the server.
        const val DEFAULT_FRIGATE_INSTANCE = "frigate"
    }
}
