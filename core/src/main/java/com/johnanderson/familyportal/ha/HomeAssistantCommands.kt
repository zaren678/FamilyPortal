package com.johnanderson.familyportal.ha

import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.io.IOException

/**
 * One-off Home Assistant WebSocket API commands, for things the REST API doesn't
 * offer, like Frigate's event history. Each command gets a connection of its own
 * that closes once Home Assistant answers.
 */
class HomeAssistantCommands(
    private val httpClient: OkHttpClient,
    private val json: Json,
    private val accessToken: suspend (forceRefresh: Boolean) -> String?,
) {
    /** Sends [command] (without an "id") and returns its result, retrying once with a fresh token if it's rejected. */
    suspend fun send(baseUrl: String, command: JsonObject): JsonElement {
        val token = accessToken(false) ?: throw IOException("Not signed in to Home Assistant")
        return try {
            send(baseUrl, token, command)
        } catch (e: HomeAssistantAuthenticationException) {
            send(baseUrl, accessToken(true) ?: throw e, command)
        }
    }

    private suspend fun send(baseUrl: String, token: String, command: JsonObject): JsonElement =
        suspendCancellableCoroutine { continuation ->
            val request = Request.Builder().url(homeAssistantWebSocketUrl(baseUrl)).build()
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
                        "auth_ok" -> webSocket.send(JsonObject(command + ("id" to JsonPrimitive(COMMAND_ID))).toString())
                        "auth_invalid" -> finish(
                            webSocket,
                            Result.failure(HomeAssistantAuthenticationException("Home Assistant rejected the token")),
                        )
                        "result" -> finish(
                            webSocket,
                            if (root["success"]?.jsonPrimitive?.booleanOrNull == true) {
                                Result.success(root["result"] ?: JsonNull)
                            } else {
                                val error = root["error"] as? JsonObject
                                Result.failure(
                                    IOException(
                                        "Home Assistant couldn't run ${command.string("type")}: " +
                                            "${error?.string("code")} ${error?.string("message")}",
                                    ),
                                )
                            },
                        )
                    }
                }

                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                    webSocket.close(1000, null)
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    if (continuation.isActive) {
                        continuation.resumeWith(Result.failure(IOException("Home Assistant closed the connection: $code $reason")))
                    }
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    if (continuation.isActive) continuation.resumeWith(Result.failure(t))
                }

                private fun finish(webSocket: WebSocket, result: Result<JsonElement>) {
                    if (continuation.isActive) continuation.resumeWith(result)
                    webSocket.close(1000, null)
                }
            }
            val webSocket = httpClient.newWebSocket(request, listener)
            continuation.invokeOnCancellation { webSocket.cancel() }
        }

    private companion object {
        const val COMMAND_ID = 1
    }
}
