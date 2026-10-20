package com.johnanderson.familyportal.ui

import com.johnanderson.familyportal.calendar.CalendarEventEntity
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

fun eventsForDay(
    events: List<CalendarEventEntity>,
    day: LocalDate,
    zone: ZoneId = ZoneId.systemDefault(),
): List<CalendarEventEntity> {
    val dayStart = day.atStartOfDay(zone).toInstant().toEpochMilli()
    val dayEnd = day.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
    return events.filter { it.startEpochMillis < dayEnd && it.endEpochMillis > dayStart }
        .sortedWith(compareByDescending<CalendarEventEntity> { it.allDay }
            .thenBy { it.startEpochMillis })
}

fun nextTimedEvent(
    events: List<CalendarEventEntity>,
    now: Instant,
): CalendarEventEntity? = events.firstOrNull {
    !it.allDay && it.endEpochMillis > now.toEpochMilli()
}
