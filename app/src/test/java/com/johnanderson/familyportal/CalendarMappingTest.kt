package com.johnanderson.familyportal

import com.johnanderson.familyportal.calendar.CalendarSourceEntity
import com.johnanderson.familyportal.calendar.GoogleEvent
import com.johnanderson.familyportal.calendar.GoogleEventTime
import com.johnanderson.familyportal.calendar.toEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

class CalendarMappingTest {
    private val source = CalendarSourceEntity(
        id = "family@example.com",
        name = "Family",
        color = "#1565C0",
        selected = true,
        primaryCalendar = false,
    )

    @Test
    fun mapsTimedEventToInstant() {
        val entity = GoogleEvent(
            id = "event-1",
            summary = "Dentist",
            start = GoogleEventTime(dateTime = "2026-07-27T10:00:00-07:00"),
            end = GoogleEventTime(dateTime = "2026-07-27T11:00:00-07:00"),
        ).toEntity(source, ZoneId.of("America/Los_Angeles"))!!

        assertEquals(Instant.parse("2026-07-27T17:00:00Z").toEpochMilli(), entity.startEpochMillis)
        assertEquals(false, entity.allDay)
        assertEquals("Dentist", entity.title)
    }

    @Test
    fun keepsAllDayEndExclusive() {
        val entity = GoogleEvent(
            id = "event-2",
            summary = null,
            start = GoogleEventTime(date = "2026-07-27"),
            end = GoogleEventTime(date = "2026-07-29"),
        ).toEntity(source, ZoneId.of("America/Los_Angeles"))!!

        assertTrue(entity.allDay)
        assertEquals("Untitled event", entity.title)
        assertEquals(48 * 60 * 60 * 1_000L, entity.endEpochMillis - entity.startEpochMillis)
    }
}
