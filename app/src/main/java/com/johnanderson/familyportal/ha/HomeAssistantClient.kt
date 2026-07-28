package com.johnanderson.familyportal.ha

import com.johnanderson.familyportal.core.ConnectionState
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.io.IOException

data class HomeAssistantStateChange(
    val entityId: String,
    val oldState: String?,
    val newState: String?,
)

enum class DoorbellTransition { START, STOP }

internal fun HomeAssistantStateChange.doorbellTransition(
    sensorEntityId: String,
): DoorbellTransition? {
    if (entityId != sensorEntityId) return null
    return when {
        oldState != "on" && newState == "on" -> DoorbellTransition.START
        oldState != "off" && newState == "off" -> DoorbellTransition.STOP
        else -> null
    }
}

class HomeAssistantClient(
    private val httpClient: OkHttpClient,
    private val json: Json,
) {
    private val _connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    fun stateChanges(baseUrl: String, token: String): Flow<HomeAssistantStateChange> = callbackFlow {
        _connectionState.value = ConnectionState.CONNECTING
        val request = Request.Builder().url(webSocketUrl(baseUrl)).build()
        val listener = object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) {
                val root = runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull() ?: return
                when (root.string("type")) {
                    "auth_required" -> webSocket.send(
                        buildJsonObject {
                            put("type", "auth")
                            put("access_token", token)
                        }.toString(),
                    )
                    "auth_ok" -> {
                        _connectionState.value = ConnectionState.CONNECTED
                        webSocket.send(
                            buildJsonObject {
                                put("id", 1)
                                put("type", "subscribe_events")
                                put("event_type", "state_changed")
                            }.toString(),
                        )
                    }
                    "auth_invalid" -> close(IOException("Home Assistant rejected the token"))
                    "event" -> root.toStateChange()?.let { trySend(it) }
                }
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

    private fun JsonObject.toStateChange(): HomeAssistantStateChange? {
        val data = this["event"]?.jsonObject?.get("data")?.jsonObject ?: return null
        val entityId = data.string("entity_id") ?: return null
        val oldState = data["old_state"]?.let { element ->
            runCatching { element.jsonObject.string("state") }.getOrNull()
        }
        val newState = data["new_state"]?.let { element ->
            runCatching { element.jsonObject.string("state") }.getOrNull()
        }
        return HomeAssistantStateChange(entityId, oldState, newState)
    }

    private fun JsonObject.string(key: String): String? =
        get(key)?.jsonPrimitive?.contentOrNull

    private fun webSocketUrl(baseUrl: String): String {
        val clean = baseUrl.trim().trimEnd('/')
        val wsBase = when {
            clean.startsWith("https://") -> "wss://${clean.removePrefix("https://")}"
            clean.startsWith("http://") -> "ws://${clean.removePrefix("http://")}"
            clean.startsWith("wss://") || clean.startsWith("ws://") -> clean
            else -> "ws://$clean"
        }
        return "$wsBase/api/websocket"
    }
}
