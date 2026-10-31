package com.johnanderson.familyportal

import com.johnanderson.familyportal.core.ConnectionState
import com.johnanderson.familyportal.ha.HomeAssistantClient
import com.johnanderson.familyportal.ha.HomeAssistantEntityState
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException
import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList

/** Talks to a fake Home Assistant speaking the WebSocket API's subscribe_entities messages. */
class HomeAssistantClientTest {
    private val server = MockWebServer()
    private val json = Json { ignoreUnknownKeys = true }
    private val client = HomeAssistantClient(OkHttpClient(), json)
    private val received = CopyOnWriteArrayList<String>()

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun reportsTheSensorsStateThenOnlyItsStateChanges() = runBlocking {
        serve { webSocket ->
            webSocket.send("""{"id":1,"type":"result","success":true,"result":null}""")
            webSocket.send(
                """{"id":1,"type":"event","event":{"a":{"$SENSOR":{"s":"off","a":{"device_class":"occupancy"},""" +
                    """"c":"01JB0","lc":1761854400.25}}}}""",
            )
            // Attribute-only update: no state change to report.
            webSocket.send("""{"id":1,"type":"event","event":{"c":{"$SENSOR":{"+":{"a":{"x":1},"c":"01JB1","lu":1761854405.0}}}}}""")
            webSocket.send("""{"id":1,"type":"event","event":{"c":{"$SENSOR":{"+":{"s":"on","c":"01JB2","lc":1761854410.5}}}}}""")
            webSocket.send("""{"id":1,"type":"event","event":{"c":{"$SENSOR":{"+":{"s":"off","c":"01JB3","lc":1761854420.0}}}}}""")
        }

        val states = withTimeout(TIMEOUT_MILLIS) {
            client.entityStates(server.url("/").toString(), "secret", SENSOR).take(3).toList()
        }

        assertEquals(
            listOf(
                HomeAssistantEntityState("off", Instant.ofEpochMilli(1_761_854_400_250)),
                HomeAssistantEntityState("on", Instant.ofEpochMilli(1_761_854_410_500)),
                HomeAssistantEntityState("off", Instant.ofEpochMilli(1_761_854_420_000)),
            ),
            states,
        )
        val auth = json.parseToJsonElement(received[0]).jsonObject
        assertEquals("secret", auth["access_token"]?.jsonPrimitive?.content)
        val subscribe = json.parseToJsonElement(received[1]).jsonObject
        assertEquals("subscribe_entities", subscribe["type"]?.jsonPrimitive?.content)
        assertEquals("[\"$SENSOR\"]", subscribe["entity_ids"].toString())
    }

    @Test
    fun missingSensorIsReportedAsNoState() = runBlocking {
        serve { webSocket ->
            webSocket.send("""{"id":1,"type":"result","success":true,"result":null}""")
            webSocket.send("""{"id":1,"type":"event","event":{"a":{}}}""")
        }

        val state = withTimeout(TIMEOUT_MILLIS) {
            client.entityStates(server.url("/").toString(), "secret", SENSOR).first()
        }

        assertEquals(HomeAssistantEntityState(null), state)
    }

    @Test
    fun refusedSubscriptionFailsSoTheCallerRetries() = runBlocking {
        serve { webSocket ->
            webSocket.send(
                """{"id":1,"type":"result","success":false,"error":{"code":"unknown_command","message":"Unknown command."}}""",
            )
        }

        try {
            withTimeout(TIMEOUT_MILLIS) { client.entityStates(server.url("/").toString(), "secret", SENSOR).first() }
            fail("Expected the subscription to fail")
        } catch (e: IOException) {
            assertTrue(e.message.orEmpty().contains("unknown_command"))
        }
    }

    @Test
    fun serverClosingTheConnectionFailsSoTheCallerRetries() = runBlocking {
        serve { webSocket ->
            webSocket.send("""{"id":1,"type":"result","success":true,"result":null}""")
            webSocket.close(1001, "Home Assistant is restarting")
        }

        try {
            withTimeout(TIMEOUT_MILLIS) { client.entityStates(server.url("/").toString(), "secret", SENSOR).first() }
            fail("Expected the stream to fail")
        } catch (e: IOException) {
            assertTrue(e.message.orEmpty().contains("1001"))
        }
    }

    @Test
    fun withoutASensorJustStaysConnected() = runBlocking {
        serve {}

        val connection = launch { client.entityStates(server.url("/").toString(), "secret", "").collect {} }
        withTimeout(TIMEOUT_MILLIS) { client.connectionState.first { it == ConnectionState.CONNECTED } }
        connection.cancel()

        assertEquals("only authenticates", 1, received.size)
    }

    /** Authenticates like Home Assistant, then hands subscriptions to [onSubscribe]. */
    private fun serve(onSubscribe: (WebSocket) -> Unit) {
        server.enqueue(
            MockResponse().withWebSocketUpgrade(
                object : WebSocketListener() {
                    override fun onOpen(webSocket: WebSocket, response: Response) {
                        webSocket.send("""{"type":"auth_required","ha_version":"2026.10.0"}""")
                    }

                    override fun onMessage(webSocket: WebSocket, text: String) {
                        received += text
                        when (json.parseToJsonElement(text).jsonObject["type"]?.jsonPrimitive?.content) {
                            "auth" -> webSocket.send("""{"type":"auth_ok","ha_version":"2026.10.0"}""")
                            "subscribe_entities" -> onSubscribe(webSocket)
                        }
                    }

                    override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                        webSocket.close(1000, null)
                    }
                },
            ),
        )
        server.start()
    }

    private companion object {
        const val SENSOR = "binary_sensor.front_door_person"
        const val TIMEOUT_MILLIS = 5_000L
    }
}
