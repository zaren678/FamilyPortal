package com.johnanderson.familyportal

import com.johnanderson.familyportal.calendar.CalendarEventEntity
import com.johnanderson.familyportal.ui.layoutTimelineEvents
import com.johnanderson.familyportal.ui.pagerPageForWeek
import com.johnanderson.familyportal.ui.weekForPagerPage
import com.johnanderson.familyportal.ui.weekRangeLabel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime

class CalendarTimelineTest {
    private val zone = ZoneId.of("America/Los_Angeles")
    private val day = LocalDate.of(2026, 7, 28)

    @Test
    fun weekRangeAlwaysIncludesYear() {
        assertEquals("Jul 26 - Aug 1, 2026", weekRangeLabel(LocalDate.of(2026, 7, 26)))
        assertEquals(
            "Dec 27, 2026 - Jan 2, 2027",
            weekRangeLabel(LocalDate.of(2026, 12, 27)),
        )
    }

    @Test
    fun pagerPagesMapToAdjacentWeeks() {
        val base = LocalDate.of(2026, 7, 26)

        assertEquals(base.minusWeeks(1), weekForPagerPage(base, 4_999))
        assertEquals(base, weekForPagerPage(base, 5_000))
        assertEquals(base.plusWeeks(1), weekForPagerPage(base, 5_001))
        assertEquals(5_001, pagerPageForWeek(base, base.plusWeeks(1)))
    }

    @Test
    fun positionsEventsByMinuteOfDay() {
        val placements = layoutTimelineEvents(
            listOf(
                event("morning", 8, 0, 9, 0),
                event("evening", 17, 30, 18, 30),
            ),
            day,
            zone,
        ).associateBy { it.event.eventId }

        assertEquals(480f, placements.getValue("morning").startMinute, 0.01f)
        assertEquals(1050f, placements.getValue("evening").startMinute, 0.01f)
        assertEquals(0, placements.getValue("morning").lane)
        assertEquals(0, placements.getValue("evening").lane)
    }

    @Test
    fun assignsOverlappingEventsToSeparateLanes() {
        val placements = layoutTimelineEvents(
            listOf(
                event("first", 8, 0, 9, 0),
                event("second", 8, 30, 9, 30),
            ),
            day,
            zone,
        )

        assertEquals(setOf(0, 1), placements.map { it.lane }.toSet())
        assertTrue(placements.all { it.laneCount == 2 })
    }

    @Test
    fun shortCardsUseSeparateLanesWhenTheirVisualBoundsOverlap() {
        val placements = layoutTimelineEvents(
            listOf(
                event("first", 8, 0, 8, 15),
                event("second", 8, 30, 8, 45),
            ),
            day,
            zone,
        )

        assertEquals(setOf(0, 1), placements.map { it.lane }.toSet())
    }

    @Test
    fun clipsOvernightEventToTheSelectedDay() {
        val start = day.minusDays(1).atTime(23, 0).atZone(zone).toInstant().toEpochMilli()
        val end = day.atTime(1, 30).atZone(zone).toInstant().toEpochMilli()
        val placement = layoutTimelineEvents(
            listOf(event("overnight", start, end)),
            day,
            zone,
        ).single()

        assertEquals(0f, placement.startMinute, 0.01f)
        assertEquals(90f, placement.endMinute, 0.01f)
    }

    private fun event(
        id: String,
        startHour: Int,
        startMinute: Int,
        endHour: Int,
        endMinute: Int,
    ): CalendarEventEntity {
        val start = day.atTime(startHour, startMinute).atZone(zone).toInstant().toEpochMilli()
        val end = day.atTime(endHour, endMinute).atZone(zone).toInstant().toEpochMilli()
        return event(id, start, end)
    }

    private fun event(id: String, start: Long, end: Long) = CalendarEventEntity(
        calendarId = "family",
        eventId = id,
        title = id,
        location = null,
        startEpochMillis = start,
        endEpochMillis = end,
        allDay = false,
        color = "#1565C0",
    )
}
