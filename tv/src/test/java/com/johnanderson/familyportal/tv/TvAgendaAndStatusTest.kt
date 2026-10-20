package com.johnanderson.familyportal.tv

import com.johnanderson.familyportal.calendar.CalendarEventEntity
import com.johnanderson.familyportal.calendar.CalendarSyncState
import com.johnanderson.familyportal.core.ConnectionState
import com.johnanderson.familyportal.tv.ui.AgendaDay
import com.johnanderson.familyportal.tv.ui.StatusKind
import com.johnanderson.familyportal.tv.ui.StatusLevel
import com.johnanderson.familyportal.tv.ui.TvStatusInput
import com.johnanderson.familyportal.tv.ui.isPastAt
import com.johnanderson.familyportal.tv.ui.tvAgendaSections
import com.johnanderson.familyportal.tv.ui.tvStatusItems
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TvAgendaAndStatusTest {
    private val zone = ZoneId.of("America/Los_Angeles")

    @Test
    fun tomorrowAppearsOnceNothingTimedIsLeftToday() {
        val evening = at(21, 14)
        val spa = event("spa", startHour = 18, endHour = 20)
        val lockup = event("lockup", startHour = 21, endHour = 22)
        val tomorrow = listOf(event("school", startHour = 8, endHour = 9, dayOffset = 1))

        assertEquals(listOf(AgendaDay.TODAY), tvAgendaSections(listOf(spa, lockup), tomorrow, evening).map { it.day })
        assertEquals(
            listOf(AgendaDay.TODAY, AgendaDay.TOMORROW),
            tvAgendaSections(listOf(spa), tomorrow, evening).map { it.day },
        )
        // Nothing to add when tomorrow is empty.
        assertEquals(listOf(AgendaDay.TODAY), tvAgendaSections(listOf(spa), emptyList(), evening).map { it.day })
    }

    @Test
    fun onlyFinishedTimedEventsArePast() {
        val evening = at(21, 14)
        assertTrue(event("spa", startHour = 18, endHour = 20).isPastAt(evening))
        assertFalse(event("lockup", startHour = 21, endHour = 22).isPastAt(evening))
        assertFalse(event("bday", startHour = 0, endHour = 0, allDay = true).isPastAt(evening))
    }

    @Test
    fun statusReportsOffProblemsAndHealthyConnections() {
        val healthy = TvStatusInput(
            homeAssistantUrl = "http://192.168.1.233:8123",
            homeAssistantCatalogLoaded = true,
            homeAssistantError = null,
            googleConfigured = true,
            googleAuthorized = true,
            calendarSync = CalendarSyncState(lastSuccess = at(21, 12)),
            weatherEntityId = "weather.home",
            weatherName = "Home",
            weatherError = null,
            doorbellConfigured = true,
            overlayAllowed = true,
            doorbellConnection = ConnectionState.CONNECTED,
        )
        val items = tvStatusItems(healthy, at(21, 14), zone).associateBy { it.kind }
        assertTrue(items.values.all { it.level == StatusLevel.OK })
        assertEquals("192.168.1.233:8123", items.getValue(StatusKind.HOME_ASSISTANT).detail)
        assertEquals("Synced 9:12 PM", items.getValue(StatusKind.GOOGLE_CALENDAR).detail)

        val broken = tvStatusItems(
            healthy.copy(overlayAllowed = false, calendarSync = CalendarSyncState(error = "Timeout")),
            at(21, 14),
            zone,
        ).associateBy { it.kind }
        assertEquals(StatusLevel.PROBLEM, broken.getValue(StatusKind.DOORBELL_POPUP).level)
        assertEquals("Sync failed: Timeout", broken.getValue(StatusKind.GOOGLE_CALENDAR).detail)

        val off = tvStatusItems(
            healthy.copy(homeAssistantUrl = "", weatherEntityId = "", doorbellConfigured = false, googleAuthorized = false),
            at(21, 14),
            zone,
        )
        assertTrue(off.all { it.level == StatusLevel.OFF })
    }

    private fun at(hour: Int, minute: Int): Instant =
        ZonedDateTime.of(2026, 10, 19, hour, minute, 0, 0, zone).toInstant()

    private fun event(
        id: String,
        startHour: Int,
        endHour: Int,
        dayOffset: Long = 0,
        allDay: Boolean = false,
    ): CalendarEventEntity {
        val day = ZonedDateTime.of(2026, 10, 19, 0, 0, 0, 0, zone).plusDays(dayOffset)
        val start = if (allDay) day else day.withHour(startHour)
        val end = if (allDay) day.plusDays(1) else day.withHour(endHour)
        return CalendarEventEntity(
            calendarId = "family",
            eventId = id,
            title = id,
            location = null,
            startEpochMillis = start.toInstant().toEpochMilli(),
            endEpochMillis = end.toInstant().toEpochMilli(),
            allDay = allDay,
            color = "#607D8B",
        )
    }
}
