package com.johnanderson.familyportal.ha

import androidx.collection.LruCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.time.Instant
import kotlin.math.ceil

/** A camera as Frigate knows it: which Frigate server ([instanceId]) and its name there. */
data class FrigateCamera(val instanceId: String, val name: String)

/** Something Frigate detected, e.g. a person at the doorbell. */
data class FrigateEvent(
    val id: String,
    val camera: FrigateCamera,
    /** What Frigate saw: "person", "car", "dog"… */
    val label: String,
    /** A recognized face, plate, or delivery company, if any. */
    val subLabel: String?,
    val startTime: Instant,
    /** Null while Frigate is still tracking it. */
    val endTime: Instant?,
    val hasClip: Boolean,
    val hasSnapshot: Boolean,
)

/**
 * Frigate's detection history, through Home Assistant's Frigate integration, so the
 * app's Home Assistant sign-in covers it and Frigate needn't be reachable directly.
 */
class FrigateClient(
    private val commands: HomeAssistantCommands,
    private val httpClient: OkHttpClient,
    private val json: Json,
    private val accessToken: suspend () -> String?,
) {
    private val images = object : LruCache<String, ByteArray>(IMAGE_CACHE_BYTES) {
        override fun sizeOf(key: String, value: ByteArray) = value.size
    }

    /** The newest [limit] events on [cameras] that started before [before] (or ever), newest first. */
    suspend fun events(
        baseUrl: String,
        cameras: Collection<FrigateCamera>,
        labels: Collection<String> = emptyList(),
        before: Instant? = null,
        limit: Int,
    ): List<FrigateEvent> = coroutineScope {
        cameras.groupBy(FrigateCamera::instanceId).map { (instanceId, instanceCameras) ->
            async {
                val result = commands.send(
                    baseUrl,
                    buildJsonObject {
                        put("type", "frigate/events/get")
                        put("instance_id", instanceId)
                        putJsonArray("cameras") { instanceCameras.forEach { add(it.name) } }
                        if (labels.isNotEmpty()) putJsonArray("labels") { labels.forEach { add(it) } }
                        // Whole seconds only, so round up and let callers drop the repeats.
                        before?.let { put("before", ceil(it.toEpochMilli() / 1_000.0).toLong()) }
                        put("limit", limit)
                    },
                )
                parseFrigateEvents(json, instanceId, result)
            }
        }.awaitAll().flatten().sortedByDescending(FrigateEvent::startTime).take(limit)
    }

    /** The event's snapshot, or its smaller thumbnail when Frigate didn't keep a snapshot. */
    suspend fun image(baseUrl: String, event: FrigateEvent): ByteArray = withContext(Dispatchers.IO) {
        images[event.id]?.let { return@withContext it }
        val kind = if (event.hasSnapshot) "snapshot" else "thumbnail"
        val token = accessToken() ?: throw IOException("Not signed in to Home Assistant")
        val url = baseUrl.trimEnd('/').toHttpUrl().newBuilder()
            .addPathSegments("api/frigate")
            .addPathSegment(event.camera.instanceId)
            .addPathSegment(kind)
            .addPathSegment(event.id)
            .build()
        val request = Request.Builder().url(url).header("Authorization", "Bearer $token").build()
        val bytes = httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("Frigate $kind failed: HTTP ${response.code}")
            response.body?.bytes() ?: throw IOException("Frigate $kind was empty")
        }
        // Frigate keeps picking a better snapshot until the event ends.
        if (event.endTime != null) images.put(event.id, bytes)
        bytes
    }

    /**
     * A link that plays the event's recording, from a few seconds before it to a few
     * after. It's signed, so the player needs no sign-in, and works for an hour.
     */
    suspend fun clipUrl(baseUrl: String, event: FrigateEvent): String {
        val result = commands.send(
            baseUrl,
            buildJsonObject {
                put("type", "media_source/resolve_media")
                put("media_content_id", event.mediaSourceId())
                put("expires", CLIP_LINK_SECONDS)
            },
        )
        val url = (result as? JsonObject)?.string("url") ?: throw IOException("Home Assistant returned no clip link")
        return if (url.startsWith("http://") || url.startsWith("https://")) url
        else "${baseUrl.trimEnd('/')}/${url.trimStart('/')}"
    }

    private companion object {
        const val IMAGE_CACHE_BYTES = 8 * 1024 * 1024
        const val CLIP_LINK_SECONDS = 3_600
    }
}

/** How the Frigate integration's media browser names an event's clip. */
internal fun FrigateEvent.mediaSourceId(): String =
    "media-source://frigate/${camera.instanceId}/event/clips/${camera.name}/$id"

/** Reads `frigate/events/get`'s result, which the integration sends as a JSON string. */
internal fun parseFrigateEvents(json: Json, instanceId: String, result: JsonElement): List<FrigateEvent> {
    val events = when (result) {
        is JsonArray -> result
        is JsonPrimitive -> result.contentOrNull?.let { runCatching { json.parseToJsonElement(it) }.getOrNull() }
        else -> null
    } as? JsonArray ?: throw IOException("Frigate returned unexpected events: ${result.toString().take(200)}")
    return events.mapNotNull { element ->
        val event = element as? JsonObject ?: return@mapNotNull null
        FrigateEvent(
            id = event.string("id") ?: return@mapNotNull null,
            camera = FrigateCamera(instanceId, event.string("camera") ?: return@mapNotNull null),
            label = event.string("label") ?: return@mapNotNull null,
            subLabel = when (val subLabel = event["sub_label"]) {
                // Older Frigate versions send [name, score].
                is JsonArray -> (subLabel.firstOrNull() as? JsonPrimitive)?.contentOrNull
                is JsonPrimitive -> subLabel.contentOrNull
                else -> null
            }?.takeIf(String::isNotBlank),
            startTime = event.epochSeconds("start_time") ?: return@mapNotNull null,
            endTime = event.epochSeconds("end_time"),
            hasClip = (event["has_clip"] as? JsonPrimitive)?.booleanOrNull == true,
            hasSnapshot = (event["has_snapshot"] as? JsonPrimitive)?.booleanOrNull == true,
        )
    }
}
