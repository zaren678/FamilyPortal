package com.johnanderson.familyportal

import com.johnanderson.familyportal.calendar.CalendarEventEntity
import com.johnanderson.familyportal.ui.eventsForDay
import com.johnanderson.familyportal.ui.nextTimedEvent
import com.johnanderson.familyportal.ui.sunEventLabel
import com.johnanderson.familyportal.ui.weatherConditionIcon
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TodayDashboardTest {
    private val zone = ZoneId.of("America/Los_Angeles")
    private val day = LocalDate.of(2026, 8, 1)

    @Test
    fun mapsRainToHomeAssistantMdiWeatherIcon() {
        assertEquals("weather-rainy", weatherConditionIcon("rainy").name)
        assertEquals("weather-pouring", weatherConditionIcon("pouring").name)
        assertEquals("weather-lightning-rainy", weatherConditionIcon("lightning-rainy").name)
        assertEquals("alert-circle-outline", weatherConditionIcon("unavailable").name)
    }

    @Test
    fun labelsUpcomingSunEventsInLocalTime() {
        val now = day.atTime(15, 0).atZone(zone).toInstant()

        assertEquals("Today 8:34 PM", sunEventLabel("2026-08-02T03:34:00Z", now, zone))
        assertEquals("Tomorrow 5:48 AM", sunEventLabel("2026-08-02T12:48:00Z", now, zone))
        assertNull(sunEventLabel("not-a-time", now, zone))
    }

    @Test
    fun includesEventsThatSpanIntoToday() {
        val overnight = event(
            "overnight",
            day.minusDays(1).atTime(23, 30).atZone(zone).toInstant().toEpochMilli(),
            day.atTime(1, 0).atZone(zone).toInstant().toEpochMilli(),
        )
        val tomorrow = event(
            "tomorrow",
            day.plusDays(1).atTime(8, 0).atZone(zone).toInstant().toEpochMilli(),
            day.plusDays(1).atTime(9, 0).atZone(zone).toInstant().toEpochMilli(),
        )

        assertEquals(
            listOf("overnight"),
            eventsForDay(listOf(tomorrow, overnight), day, zone).map { it.eventId },
        )
    }

    @Test
    fun nextEventIgnoresAllDayAndCompletedEvents() {
        val now = day.atTime(10, 30).atZone(zone).toInstant()
        val allDay = event(
            "all-day",
            day.atStartOfDay(zone).toInstant().toEpochMilli(),
            day.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli(),
            allDay = true,
        )
        val completed = event(
            "completed",
            day.atTime(8, 0).atZone(zone).toInstant().toEpochMilli(),
            day.atTime(9, 0).atZone(zone).toInstant().toEpochMilli(),
        )
        val ongoing = event(
            "ongoing",
            day.atTime(10, 0).atZone(zone).toInstant().toEpochMilli(),
            day.atTime(11, 0).atZone(zone).toInstant().toEpochMilli(),
        )
        val later = event(
            "later",
            day.atTime(12, 0).atZone(zone).toInstant().toEpochMilli(),
            day.atTime(13, 0).atZone(zone).toInstant().toEpochMilli(),
        )

        assertEquals("ongoing", nextTimedEvent(listOf(allDay, completed, ongoing, later), now)?.eventId)
    }

    private fun event(
        id: String,
        start: Long,
        end: Long,
        allDay: Boolean = false,
    ) = CalendarEventEntity(
        calendarId = "family",
        eventId = id,
        title = id,
        location = null,
        startEpochMillis = start,
        endEpochMillis = end,
        allDay = allDay,
        color = "#1565C0",
    )
}
