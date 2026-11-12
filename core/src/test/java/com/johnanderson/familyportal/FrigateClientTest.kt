package com.johnanderson.familyportal

import com.johnanderson.familyportal.ha.FrigateCamera
import com.johnanderson.familyportal.ha.FrigateClient
import com.johnanderson.familyportal.ha.FrigateEvent
import com.johnanderson.familyportal.ha.HomeAssistantCommands
import com.johnanderson.familyportal.ha.parseFrigateEvents
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException
import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList

/** Talks to a fake Home Assistant answering like its Frigate integration did on a real install. */
class FrigateClientTest {
    private val server = MockWebServer()
    private val json = Json { ignoreUnknownKeys = true }
    private val commands = CopyOnWriteArrayList<JsonObject>()
    private val tokens = CopyOnWriteArrayList<String>()

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun readsEventsSentAsAJsonString() {
        val events = parseFrigateEvents(json, "frigate", JsonPrimitive(EVENTS))

        assertEquals(
            FrigateEvent(
                id = "1794455827.107226-z59ke9",
                camera = FrigateCamera("frigate", "doorbell"),
                label = "person",
                subLabel = null,
                startTime = Instant.ofEpochMilli(1_794_455_827_107),
                endTime = Instant.ofEpochMilli(1_794_455_833_614),
                hasClip = true,
                hasSnapshot = true,
            ),
            events[0],
        )
        assertNull("still being tracked", events[1].endTime)
        assertEquals("Alex", events[1].subLabel)
        // Older Frigate versions send the sub label with its score.
        assertEquals("UPS", events[2].subLabel)
        assertEquals(false, events[2].hasSnapshot)
    }

    @Test
    fun asksEachFrigateServerForItsCamerasAndMergesTheNewest() = runBlocking {
        serve(connections = 2) { command ->
            when (command["instance_id"]?.jsonPrimitive?.content) {
                "frigate" -> result(json.encodeToString(JsonPrimitive.serializer(), JsonPrimitive(EVENTS)))
                else -> result(
                    """"[{\"id\":\"garage-1\",\"camera\":\"garage\",\"label\":\"car\",""" +
                        """\"start_time\":1794455900.0,\"end_time\":1794455910.0,\"has_clip\":true,\"has_snapshot\":true}]"""",
                )
            }
        }

        val events = withTimeout(TIMEOUT_MILLIS) {
            client().events(
                server.url("/").toString(),
                listOf(
                    FrigateCamera("frigate", "doorbell"),
                    FrigateCamera("frigate", "driveway"),
                    FrigateCamera("garage", "garage"),
                ),
                labels = listOf("person", "car"),
                before = Instant.ofEpochMilli(1_794_456_000_250),
                limit = 3,
            )
        }

        assertEquals(listOf("garage-1", "1794455827.107226-z59ke9", "1794455800.0-live"), events.map { it.id })
        val frigateCommand = commands.single { it["instance_id"]?.jsonPrimitive?.content == "frigate" }
        assertEquals("frigate/events/get", frigateCommand["type"]?.jsonPrimitive?.content)
        assertEquals("""["doorbell","driveway"]""", frigateCommand["cameras"].toString())
        assertEquals("""["person","car"]""", frigateCommand["labels"].toString())
        // Whole seconds, rounded up so nothing between the two is skipped.
        assertEquals("1794456001", frigateCommand["before"].toString())
        assertEquals("3", frigateCommand["limit"].toString())
    }

    @Test
    fun clipLinkComesFromHomeAssistantsMediaBrowser() = runBlocking {
        serve { result("""{"url":"/api/frigate/frigate/vod/doorbell/start/1/end/2/index.m3u8?authSig=abc","mime_type":"application/x-mpegURL"}""") }

        val url = withTimeout(TIMEOUT_MILLIS) {
            client().clipUrl(server.url("/ha/").toString(), parseFrigateEvents(json, "frigate", JsonPrimitive(EVENTS))[0])
        }

        assertEquals(server.url("/ha/api/frigate/frigate/vod/doorbell/start/1/end/2/index.m3u8?authSig=abc").toString(), url)
        val command = commands.single()
        assertEquals("media_source/resolve_media", command["type"]?.jsonPrimitive?.content)
        assertEquals(
            "media-source://frigate/frigate/event/clips/doorbell/1794455827.107226-z59ke9",
            command["media_content_id"]?.jsonPrimitive?.content,
        )
        assertEquals("3600", command["expires"].toString())
    }

    @Test
    fun rejectedTokenIsRefreshedOnce() = runBlocking {
        serve(connections = 2, rejectToken = "stale") { result("[]") }
        val forced = mutableListOf<Boolean>()
        val commands = HomeAssistantCommands(OkHttpClient(), json) { force ->
            forced += force
            if (force) "fresh" else "stale"
        }

        withTimeout(TIMEOUT_MILLIS) {
            commands.send(server.url("/").toString(), JsonObject(mapOf("type" to JsonPrimitive("frigate/events/get"))))
        }

        assertEquals(listOf(false, true), forced)
        assertEquals(listOf("stale", "fresh"), tokens)
    }

    @Test
    fun refusedCommandSaysWhy() = runBlocking {
        serve { """{"type":"result","success":false,"error":{"code":"not_found","message":"Unable to find Frigate instance with ID: nope"}}""" }

        try {
            withTimeout(TIMEOUT_MILLIS) {
                client().events(server.url("/").toString(), listOf(FrigateCamera("nope", "doorbell")), limit = 10)
            }
            fail("Expected the command to fail")
        } catch (e: IOException) {
            assertTrue(e.message.orEmpty().contains("Unable to find Frigate instance"))
        }
    }

    private fun client() = HomeAssistantCommands(OkHttpClient(), json) { "token" }
        .let { FrigateClient(it, OkHttpClient(), json) { "token" } }

    private fun result(result: String) = """{"type":"result","success":true,"result":$result}"""

    /**
     * Authenticates like Home Assistant over [connections] connections, then answers
     * each command with [reply]'s message (its "id" is filled in).
     */
    private fun serve(connections: Int = 1, rejectToken: String? = null, reply: (JsonObject) -> String) {
        repeat(connections) {
            server.enqueue(
                MockResponse().withWebSocketUpgrade(
                    object : WebSocketListener() {
                        override fun onOpen(webSocket: WebSocket, response: Response) {
                            webSocket.send("""{"type":"auth_required","ha_version":"2026.11.1"}""")
                        }

                        override fun onMessage(webSocket: WebSocket, text: String) {
                            val message = json.parseToJsonElement(text).jsonObject
                            if (message["type"]?.jsonPrimitive?.content == "auth") {
                                val token = message["access_token"]!!.jsonPrimitive.content
                                tokens += token
                                webSocket.send(
                                    if (token == rejectToken) """{"type":"auth_invalid","message":"Invalid access"}"""
                                    else """{"type":"auth_ok","ha_version":"2026.11.1"}""",
                                )
                                return
                            }
                            commands += message
                            webSocket.send(reply(message).replaceFirst("{", """{"id":${message["id"]},"""))
                        }

                        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                            webSocket.close(1000, null)
                        }
                    },
                ),
            )
        }
        server.start()
    }

    private companion object {
        const val TIMEOUT_MILLIS = 5_000L

        /** Trimmed from a real Frigate 0.16 install, plus an in-progress event and an older-style sub label. */
        val EVENTS = """
            [{"id":"1794455827.107226-z59ke9","camera":"doorbell","label":"person","zones":[],
              "start_time":1794455827.107226,"end_time":1794455833.614354,"has_clip":true,"has_snapshot":true,
              "plus_id":null,"retain_indefinitely":false,"sub_label":null,"top_score":null,"false_positive":null,
              "box":null,"data":{"box":[0.67,0.35,0.24,0.63],"score":0.937,"top_score":0.953,"type":"object",
              "max_severity":"alert"}},
             {"id":"1794455800.0-live","camera":"driveway","label":"person","start_time":1794455800.0,
              "end_time":null,"has_clip":true,"has_snapshot":true,"sub_label":"Alex"},
             {"id":"1794455700.5-ups","camera":"doorbell","label":"car","start_time":1794455700.5,
              "end_time":1794455760.5,"has_clip":true,"sub_label":["UPS",0.91]}]
        """.trimIndent()
    }
}
