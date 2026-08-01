package com.johnanderson.familyportal.ui

import com.johnanderson.familyportal.calendar.CalendarEventEntity
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import kotlin.math.max

internal data class TimelineEventPlacement(
    val event: CalendarEventEntity,
    val startMinute: Float,
    val endMinute: Float,
    val lane: Int,
    val laneCount: Int,
)

private data class RawTimelineEvent(
    val event: CalendarEventEntity,
    val startMinute: Float,
    val endMinute: Float,
    val visualEndMinute: Float,
)

internal fun layoutTimelineEvents(
    events: List<CalendarEventEntity>,
    day: LocalDate,
    zone: ZoneId = ZoneId.systemDefault(),
): List<TimelineEventPlacement> {
    val rawEvents = events
        .asSequence()
        .filterNot { it.allDay }
        .filter { it.occursOn(day, zone) }
        .map { event ->
            val start = Instant.ofEpochMilli(event.startEpochMillis).atZone(zone)
            val end = Instant.ofEpochMilli(event.endEpochMillis).atZone(zone)
            val startMinute = if (start.toLocalDate().isBefore(day)) {
                0f
            } else {
                start.toLocalTime().toSecondOfDay() / 60f
            }
            val endMinute = if (end.toLocalDate().isAfter(day)) {
                MINUTES_PER_DAY
            } else {
                end.toLocalTime().toSecondOfDay() / 60f
            }.coerceAtLeast(startMinute + 1f)
            RawTimelineEvent(
                event = event,
                startMinute = startMinute,
                endMinute = endMinute.coerceAtMost(MINUTES_PER_DAY),
                visualEndMinute = max(endMinute, startMinute + MIN_VISUAL_EVENT_MINUTES)
                    .coerceAtMost(MINUTES_PER_DAY),
            )
        }
        .sortedWith(compareBy<RawTimelineEvent> { it.startMinute }.thenBy { it.endMinute })
        .toList()

    val placements = mutableListOf<TimelineEventPlacement>()
    var groupStart = 0
    while (groupStart < rawEvents.size) {
        var groupEnd = groupStart + 1
        var latestVisualEnd = rawEvents[groupStart].visualEndMinute
        while (groupEnd < rawEvents.size && rawEvents[groupEnd].startMinute < latestVisualEnd) {
            latestVisualEnd = max(latestVisualEnd, rawEvents[groupEnd].visualEndMinute)
            groupEnd += 1
        }

        val group = rawEvents.subList(groupStart, groupEnd)
        val laneEnds = mutableListOf<Float>()
        val assignedLanes = group.map { event ->
            val availableLane = laneEnds.indexOfFirst { it <= event.startMinute }
            val lane = if (availableLane >= 0) availableLane else laneEnds.size
            if (availableLane >= 0) {
                laneEnds[lane] = event.visualEndMinute
            } else {
                laneEnds.add(event.visualEndMinute)
            }
            event to lane
        }
        val laneCount = laneEnds.size
        placements += assignedLanes.map { (event, lane) ->
            TimelineEventPlacement(
                event = event.event,
                startMinute = event.startMinute,
                endMinute = event.endMinute,
                lane = lane,
                laneCount = laneCount,
            )
        }
        groupStart = groupEnd
    }
    return placements
}

internal fun CalendarEventEntity.occursOn(
    day: LocalDate,
    zone: ZoneId = ZoneId.systemDefault(),
): Boolean {
    val startDate = Instant.ofEpochMilli(startEpochMillis).atZone(zone).toLocalDate()
    val endDate = Instant.ofEpochMilli(
        (endEpochMillis - 1).coerceAtLeast(startEpochMillis),
    ).atZone(zone).toLocalDate()
    return !day.isBefore(startDate) && !day.isAfter(endDate)
}

internal fun weekRangeLabel(weekStart: LocalDate): String {
    val weekEnd = weekStart.plusDays((DAYS_PER_WEEK - 1).toLong())
    return if (weekStart.year == weekEnd.year) {
        "${weekStart.format(MONTH_DAY)} - ${weekEnd.format(MONTH_DAY_YEAR)}"
    } else {
        "${weekStart.format(MONTH_DAY_YEAR)} - ${weekEnd.format(MONTH_DAY_YEAR)}"
    }
}

internal fun weekForPagerPage(
    baseWeek: LocalDate,
    page: Int,
    initialPage: Int = WEEK_PAGER_INITIAL_PAGE,
): LocalDate = baseWeek.plusWeeks((page - initialPage).toLong())

internal fun pagerPageForWeek(
    baseWeek: LocalDate,
    week: LocalDate,
    initialPage: Int = WEEK_PAGER_INITIAL_PAGE,
): Int = initialPage + ChronoUnit.WEEKS.between(baseWeek, week).toInt()

internal const val WEEK_PAGER_PAGE_COUNT = 10_000
internal const val WEEK_PAGER_INITIAL_PAGE = WEEK_PAGER_PAGE_COUNT / 2

private const val DAYS_PER_WEEK = 7
private const val MINUTES_PER_DAY = 24f * 60f
private const val MIN_VISUAL_EVENT_MINUTES = 60f
private val MONTH_DAY = DateTimeFormatter.ofPattern("MMM d")
private val MONTH_DAY_YEAR = DateTimeFormatter.ofPattern("MMM d, yyyy")
