package com.johnanderson.familyportal.calendar

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.time.Instant

class GoogleCalendarClient(
    private val httpClient: OkHttpClient,
    private val json: Json,
    private val authManager: GoogleAuthManager,
) {
    suspend fun listCalendars(): List<GoogleCalendar> {
        val calendars = mutableListOf<GoogleCalendar>()
        var pageToken: String? = null
        do {
            val url = CALENDAR_LIST.toHttpUrl().newBuilder()
                .addQueryParameter("minAccessRole", "reader")
                .addQueryParameter("showHidden", "false")
                .addQueryParameter("maxResults", "250")
                .addQueryParameter("colorRgbFormat", "true")
                .apply { pageToken?.let { addQueryParameter("pageToken", it) } }
                .build()
            val response = execute<CalendarListResponse>(url.toString())
            calendars += response.items
            pageToken = response.nextPageToken
        } while (pageToken != null)
        return calendars
    }

    suspend fun listEvents(
        calendarId: String,
        start: Instant,
        end: Instant,
    ): List<GoogleEvent> {
        val events = mutableListOf<GoogleEvent>()
        var pageToken: String? = null
        do {
            val url = "$CALENDARS/${java.net.URLEncoder.encode(calendarId, "UTF-8")}/events"
                .toHttpUrl()
                .newBuilder()
                .addQueryParameter("timeMin", start.toString())
                .addQueryParameter("timeMax", end.toString())
                .addQueryParameter("singleEvents", "true")
                .addQueryParameter("showDeleted", "false")
                .addQueryParameter("orderBy", "startTime")
                .addQueryParameter("maxResults", "2500")
                .apply { pageToken?.let { addQueryParameter("pageToken", it) } }
                .build()
            val response = execute<EventListResponse>(url.toString())
            events += response.items.filterNot { it.status == "cancelled" }
            pageToken = response.nextPageToken
        } while (pageToken != null)
        return events
    }

    private suspend inline fun <reified T> execute(url: String): T {
        val request = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer ${authManager.accessToken()}")
            .header("Accept", "application/json")
            .build()
        return httpClient.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) throw IOException("Google API ${response.code}: $body")
            json.decodeFromString(body)
        }
    }

    private companion object {
        const val CALENDAR_LIST = "https://www.googleapis.com/calendar/v3/users/me/calendarList"
        const val CALENDARS = "https://www.googleapis.com/calendar/v3/calendars"
    }
}

@Serializable
data class CalendarListResponse(
    val items: List<GoogleCalendar> = emptyList(),
    val nextPageToken: String? = null,
)

@Serializable
data class GoogleCalendar(
    val id: String,
    val summary: String = "Unnamed calendar",
    val backgroundColor: String? = null,
    val primary: Boolean = false,
)

@Serializable
data class EventListResponse(
    val items: List<GoogleEvent> = emptyList(),
    val nextPageToken: String? = null,
)

@Serializable
data class GoogleEvent(
    val id: String,
    val summary: String? = null,
    val location: String? = null,
    val status: String? = null,
    val start: GoogleEventTime,
    val end: GoogleEventTime,
)

@Serializable
data class GoogleEventTime(
    val date: String? = null,
    @SerialName("dateTime") val dateTime: String? = null,
    val timeZone: String? = null,
)
