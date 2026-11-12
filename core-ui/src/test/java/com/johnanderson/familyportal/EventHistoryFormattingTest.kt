package com.johnanderson.familyportal

import com.johnanderson.familyportal.ha.FrigateCamera
import com.johnanderson.familyportal.ha.FrigateEvent
import com.johnanderson.familyportal.ui.dayLabel
import com.johnanderson.familyportal.ui.displayName
import com.johnanderson.familyportal.ui.durationLabel
import com.johnanderson.familyportal.ui.eventTitle
import com.johnanderson.familyportal.ui.eventsByDay
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

class EventHistoryFormattingTest {
    private val start = Instant.parse("2026-11-12T03:57:07Z")

    @Test
    fun titlesUseWordsAndWhoFrigateRecognized() {
        assertEquals("Person", eventTitle(event()))
        assertEquals("Person · Alex", eventTitle(event(subLabel = "Alex")))
        assertEquals("License plate", displayName("license_plate"))
        assertEquals("Front yard light", displayName("front_yard_light"))
    }

    @Test
    fun durationsAreShort() {
        assertEquals("6s", durationLabel(event(seconds = 6)))
        assertEquals("1s", durationLabel(event(seconds = 0)))
        assertEquals("1m 20s", durationLabel(event(seconds = 80)))
        assertEquals("1h 5m", durationLabel(event(seconds = 3_900)))
        assertEquals("Now", durationLabel(event(seconds = null)))
    }

    @Test
    fun daysAreNamedRelativeToToday() {
        val today = LocalDate.of(2026, 11, 11)
        assertEquals("Today", dayLabel(today, today))
        assertEquals("Yesterday", dayLabel(today.minusDays(1), today))
        assertEquals("Monday, November 9", dayLabel(today.minusDays(2), today))
        assertEquals("Wednesday, December 31, 2025", dayLabel(LocalDate.of(2025, 12, 31), today))
    }

    @Test
    fun eventsAreGroupedByLocalDay() {
        val zone = ZoneId.of("America/Los_Angeles")
        // 7:57 PM and 1:00 AM local are on different days, though the same UTC day.
        val late = event(id = "late", startTime = Instant.parse("2026-11-12T03:57:07Z"))
        val early = event(id = "early", startTime = Instant.parse("2026-11-11T09:00:00Z"))
        val previous = event(id = "previous", startTime = Instant.parse("2026-11-11T07:59:00Z"))

        assertEquals(
            listOf(
                LocalDate.of(2026, 11, 11) to listOf("late", "early"),
                LocalDate.of(2026, 11, 10) to listOf("previous"),
            ),
            eventsByDay(listOf(late, early, previous), zone).map { (day, events) -> day to events.map { it.id } },
        )
    }

    private fun event(
        id: String = "1",
        subLabel: String? = null,
        startTime: Instant = start,
        seconds: Long? = 6,
    ) = FrigateEvent(
        id = id,
        camera = FrigateCamera("frigate", "doorbell"),
        label = "person",
        subLabel = subLabel,
        startTime = startTime,
        endTime = seconds?.let { startTime.plusSeconds(it) },
        hasClip = true,
        hasSnapshot = true,
    )
}
