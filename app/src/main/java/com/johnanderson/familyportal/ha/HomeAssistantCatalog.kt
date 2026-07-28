package com.johnanderson.familyportal.ha

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException

data class HomeAssistantEntityChoice(
    val entityId: String,
    val name: String,
    val deviceClass: String?,
)

fun HomeAssistantEntityChoice.isLikelyCameraSubstream(): Boolean {
    val searchable = "$name ${entityId.substringAfter('.')}".lowercase()
    return SUBSTREAM_TERM.containsMatchIn(searchable) || LOW_RESOLUTION_TERM.containsMatchIn(searchable)
}

private val SUBSTREAM_TERM = Regex("""(?:^|[\s_.-])(sub|substream|lowres)(?:$|[\s_.-])""")
private val LOW_RESOLUTION_TERM = Regex("""(?:^|[\s_.-])low[\s_.-]+resolution(?:$|[\s_.-])""")

data class HomeAssistantCatalog(
    val personSensors: List<HomeAssistantEntityChoice>,
    val cameras: List<HomeAssistantEntityChoice>,
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
    )

    private companion object {
        val PERSON_DEVICE_CLASSES = setOf("motion", "occupancy", "presence")
        val PERSON_TERMS = listOf("person", "doorbell", "front door", "visitor")
    }
}
