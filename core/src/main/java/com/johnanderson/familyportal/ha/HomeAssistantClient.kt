package com.johnanderson.familyportal.ha

import com.johnanderson.familyportal.core.ConnectionState
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.io.IOException
import java.time.Instant

/** An entity's state, and when Home Assistant last saw it change. Null state means the entity doesn't exist. */
data class HomeAssistantEntityState(
    val state: String?,
    val lastChanged: Instant? = null,
)

enum class DoorbellTransition { START, STOP }

class HomeAssistantAuthenticationException(message: String) : IOException(message)

fun doorbellTransition(oldState: String?, newState: String?): DoorbellTransition? = when {
    oldState != "on" && newState == "on" -> DoorbellTransition.START
    oldState != "off" && newState == "off" -> DoorbellTransition.STOP
    else -> null
}

class HomeAssistantClient(
    private val httpClient: OkHttpClient,
    private val json: Json,
) {
    private val _connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    /**
     * [entityId]'s state as soon as the subscription starts, then each change to it.
     * Home Assistant sends only this entity's updates rather than every state change
     * in the house. With a blank [entityId], just holds the connection open so
     * [connectionState] still reflects Home Assistant. Never completes normally.
     */
    fun entityStates(baseUrl: String, token: String, entityId: String): Flow<HomeAssistantEntityState> = callbackFlow {
        _connectionState.value = ConnectionState.CONNECTING
        val request = Request.Builder().url(homeAssistantWebSocketUrl(baseUrl)).build()
        val listener = object : WebSocketListener() {
            // Only touched on OkHttp's reader thread.
            private var current: HomeAssistantEntityState? = null

            override fun onMessage(webSocket: WebSocket, text: String) {
                val root = runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull() ?: return
                when (root.string("type")) {
                    "auth_required" -> webSocket.send(
                        buildJsonObject {
                            put("type", "auth")
                            put("access_token", token)
                        }.toString(),
                    )
                    "auth_ok" -> if (entityId.isBlank()) {
                        _connectionState.value = ConnectionState.CONNECTED
                    } else {
                        webSocket.send(
                            buildJsonObject {
                                put("id", SUBSCRIPTION_ID)
                                put("type", "subscribe_entities")
                                putJsonArray("entity_ids") { add(entityId) }
                            }.toString(),
                        )
                    }
                    "auth_invalid" -> close(
                        HomeAssistantAuthenticationException("Home Assistant rejected the token"),
                    )
                    "result" -> if (root["success"]?.jsonPrimitive?.booleanOrNull == true) {
                        _connectionState.value = ConnectionState.CONNECTED
                    } else {
                        close(IOException("Home Assistant refused the subscription: ${root["error"]}"))
                    }
                    "event" -> {
                        val update = root["event"]?.jsonObject ?: return
                        // The first event is the snapshot, so report it even if the entity is missing.
                        val next = update.entityStateUpdate(entityId, current)
                            ?: if (current == null) HomeAssistantEntityState(null) else return
                        current = next
                        trySend(next)
                    }
                }
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                // Finish the close handshake now (e.g. Home Assistant restarting) rather
                // than waiting for a missed ping to notice.
                webSocket.close(1000, null)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                _connectionState.value = ConnectionState.ERROR
                close(t)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                _connectionState.value = ConnectionState.DISCONNECTED
                close(IOException("Home Assistant WebSocket closed: $code $reason"))
            }
        }
        val webSocket = httpClient.newWebSocket(request, listener)
        awaitClose {
            webSocket.close(1000, "Configuration changed")
            _connectionState.value = ConnectionState.DISCONNECTED
        }
    }

    private companion object {
        const val SUBSCRIPTION_ID = 1
    }
}

internal fun homeAssistantWebSocketUrl(baseUrl: String): String {
    val clean = baseUrl.trim().trimEnd('/')
    val wsBase = when {
        clean.startsWith("https://") -> "wss://${clean.removePrefix("https://")}"
        clean.startsWith("http://") -> "ws://${clean.removePrefix("http://")}"
        clean.startsWith("wss://") || clean.startsWith("ws://") -> clean
        else -> "ws://$clean"
    }
    return "$wsBase/api/websocket"
}

/**
 * Applies a `subscribe_entities` event to [entityId]: "a" carries its full state,
 * "c" its changed fields under "+", and "r" lists removed entities. Returns null
 * when the event doesn't change the entity's state, e.g. an attribute update.
 */
internal fun JsonObject.entityStateUpdate(
    entityId: String,
    previous: HomeAssistantEntityState?,
): HomeAssistantEntityState? {
    (get("a") as? JsonObject)?.get(entityId)?.jsonObject?.let { added ->
        return HomeAssistantEntityState(added.string("s"), added.epochSeconds("lc"))
    }
    (get("c") as? JsonObject)?.get(entityId)?.jsonObject?.get("+")?.jsonObject?.let { changed ->
        val state = changed.string("s") ?: return null
        return HomeAssistantEntityState(state, changed.epochSeconds("lc") ?: previous?.lastChanged)
    }
    if ((get("r") as? JsonArray)?.any { it.jsonPrimitive.contentOrNull == entityId } == true) {
        return HomeAssistantEntityState(null)
    }
    return null
}

internal fun JsonObject.string(key: String): String? = get(key)?.jsonPrimitive?.contentOrNull

internal fun JsonObject.epochSeconds(key: String): Instant? =
    get(key)?.jsonPrimitive?.doubleOrNull?.let { Instant.ofEpochMilli((it * 1_000).toLong()) }
