package com.johnanderson.familyportal.camera

import android.util.Log
import com.johnanderson.familyportal.core.CameraConfig
import com.johnanderson.familyportal.ha.HomeAssistantAuthManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import org.json.JSONObject
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class CameraRepository(
    private val httpClient: OkHttpClient,
    private val authManager: HomeAssistantAuthManager,
) {
    private val snapshotSlots = Semaphore(2)
    private val streamCache = ConcurrentHashMap<String, CachedStream>()
    private val streamLocks = ConcurrentHashMap<String, Mutex>()

    suspend fun snapshot(baseUrl: String, camera: CameraConfig): ByteArray = withContext(Dispatchers.IO) {
        snapshotSlots.withPermit {
            val token = authManager.accessToken()
                ?: throw IllegalStateException("Home Assistant token is not configured")
            val url = baseUrl.trimEnd('/').toHttpUrl().newBuilder()
                .addPathSegments("api/camera_proxy")
                .addPathSegment(camera.entityId)
                .addQueryParameter("t", System.currentTimeMillis().toString())
                .build()
            val request = Request.Builder()
                .url(url)
                .header("Authorization", "Bearer $token")
                .header("Cache-Control", "no-cache")
                .build()
            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    val detail = response.body?.string()?.take(300).orEmpty()
                    Log.e(TAG, "Snapshot failed for ${camera.entityId}: HTTP ${response.code} $detail")
                    throw IOException("Snapshot failed: HTTP ${response.code}")
                }
                response.body?.bytes() ?: throw IOException("Snapshot response was empty")
            }
        }
    }

    suspend fun streamUri(
        baseUrl: String,
        camera: CameraConfig,
        forceRefresh: Boolean = false,
        useConfiguredRtsp: Boolean = true,
    ): String {
        if (useConfiguredRtsp) {
            authManager.cameraRtsp(camera.id)?.takeIf(String::isNotBlank)?.let { return it }
        }
        val key = streamKey(baseUrl, camera)
        if (!forceRefresh) cachedStream(key)?.let { return it }
        return streamLocks.getOrPut(key, ::Mutex).withLock {
            if (forceRefresh) streamCache.remove(key)
            cachedStream(key)?.let { return@withLock it }
            val token = authManager.accessToken()
                ?: throw IllegalStateException("Home Assistant token is not configured")
            requestHomeAssistantStream(baseUrl, camera.entityId, token).also { uri ->
                streamCache[key] = CachedStream(uri, System.currentTimeMillis() + STREAM_CACHE_MILLIS)
            }
        }
    }

    fun invalidateStream(baseUrl: String, camera: CameraConfig) {
        streamCache.remove(streamKey(baseUrl, camera))
    }

    suspend fun prewarmStream(
        baseUrl: String,
        camera: CameraConfig,
        useConfiguredRtsp: Boolean = true,
    ): Result<Unit> = runCatching {
        if (useConfiguredRtsp && authManager.cameraRtsp(camera.id)?.isNotBlank() == true) {
            return@runCatching
        }
        var uri = streamUri(baseUrl, camera, useConfiguredRtsp = useConfiguredRtsp)
        if (!primeHls(uri)) {
            invalidateStream(baseUrl, camera)
            uri = streamUri(
                baseUrl,
                camera,
                forceRefresh = true,
                useConfiguredRtsp = useConfiguredRtsp,
            )
            check(primeHls(uri)) { "Home Assistant HLS stream was unavailable" }
        }
    }

    private fun streamKey(baseUrl: String, camera: CameraConfig): String =
        "${baseUrl.trimEnd('/')}|${camera.entityId}"

    private fun cachedStream(key: String): String? {
        val cached = streamCache[key] ?: return null
        if (cached.expiresAtMillis > System.currentTimeMillis()) return cached.uri
        streamCache.remove(key, cached)
        return null
    }

    private suspend fun primeHls(masterUri: String): Boolean = withContext(Dispatchers.IO) {
        val master = fetchPlaylist(masterUri) ?: return@withContext false
        val mediaPath = master.lineSequence()
            .map(String::trim)
            .firstOrNull { it.isNotEmpty() && !it.startsWith('#') }
            ?: return@withContext true
        val mediaUri = masterUri.toHttpUrl().resolve(mediaPath)?.toString()
            ?: return@withContext false
        fetchPlaylist(mediaUri) != null
    }

    private fun fetchPlaylist(uri: String): String? {
        val request = Request.Builder().url(uri).header("Cache-Control", "no-cache").build()
        return runCatching {
            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@use null
                response.body?.string()
            }
        }.getOrNull()
    }

    private suspend fun requestHomeAssistantStream(
        baseUrl: String,
        entityId: String,
        token: String,
    ): String = suspendCancellableCoroutine { continuation ->
        val request = Request.Builder().url(webSocketUrl(baseUrl)).build()
        lateinit var socket: WebSocket
        val listener = object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) {
                val message = runCatching { JSONObject(text) }.getOrNull() ?: return
                when (message.optString("type")) {
                    "auth_required" -> webSocket.send(
                        JSONObject()
                            .put("type", "auth")
                            .put("access_token", token)
                            .toString(),
                    )
                    "auth_ok" -> webSocket.send(
                        JSONObject()
                            .put("id", STREAM_REQUEST_ID)
                            .put("type", "camera/stream")
                            .put("entity_id", entityId)
                            .put("format", "hls")
                            .toString(),
                    )
                    "auth_invalid" -> finishWithError(
                        webSocket,
                        IOException("Home Assistant rejected the token"),
                    )
                    "result" -> if (message.optInt("id") == STREAM_REQUEST_ID) {
                        if (!message.optBoolean("success")) {
                            val detail = message.optJSONObject("error")?.optString("message")
                            finishWithError(
                                webSocket,
                                IOException(detail?.takeIf(String::isNotBlank) ?: "Camera does not support HLS"),
                            )
                            return
                        }
                        val path = message.optJSONObject("result")?.optString("url").orEmpty()
                        if (path.isBlank()) {
                            finishWithError(webSocket, IOException("Home Assistant returned no stream URL"))
                            return
                        }
                        val resolved = resolveUrl(baseUrl, path)
                        Log.i(TAG, "HLS stream resolved for $entityId: $resolved")
                        if (continuation.isActive) continuation.resume(resolved)
                        webSocket.close(1000, "Stream URL received")
                    }
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                if (continuation.isActive) continuation.resumeWithException(t)
            }

            private fun finishWithError(webSocket: WebSocket, error: Throwable) {
                Log.e(TAG, "HLS request failed for $entityId", error)
                if (continuation.isActive) continuation.resumeWithException(error)
                webSocket.close(1000, "Stream request failed")
            }
        }
        socket = httpClient.newWebSocket(request, listener)
        continuation.invokeOnCancellation { socket.cancel() }
    }

    private fun resolveUrl(baseUrl: String, path: String): String =
        if (path.startsWith("http://") || path.startsWith("https://")) path
        else "${baseUrl.trimEnd('/')}/${path.trimStart('/')}"

    private fun webSocketUrl(baseUrl: String): String {
        val clean = baseUrl.trim().trimEnd('/')
        return when {
            clean.startsWith("https://") -> "wss://${clean.removePrefix("https://")}/api/websocket"
            clean.startsWith("http://") -> "ws://${clean.removePrefix("http://")}/api/websocket"
            else -> "ws://$clean/api/websocket"
        }
    }

    private data class CachedStream(val uri: String, val expiresAtMillis: Long)

    private companion object {
        const val TAG = "FamilyPortalCamera"
        const val STREAM_REQUEST_ID = 1
        const val STREAM_CACHE_MILLIS = 45_000L
    }
}
