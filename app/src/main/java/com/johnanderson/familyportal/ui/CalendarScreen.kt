package com.johnanderson.familyportal.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Today
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.johnanderson.familyportal.calendar.CalendarEventEntity
import com.johnanderson.familyportal.calendar.CalendarSyncState
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable
fun CalendarScreen(
    weekStart: LocalDate,
    events: List<CalendarEventEntity>,
    syncState: CalendarSyncState,
    onPreviousWeek: () -> Unit,
    onNextWeek: () -> Unit,
    onToday: () -> Unit,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxSize().padding(16.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "${weekStart.format(MONTH_DAY)} – ${weekStart.plusDays(6).format(MONTH_DAY)}",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.weight(1f))
            if (syncState.syncing) CircularProgressIndicator(Modifier.width(24.dp), strokeWidth = 2.dp)
            syncState.error?.let {
                Text("Offline", color = MaterialTheme.colorScheme.tertiary, modifier = Modifier.padding(horizontal = 8.dp))
            }
            IconButton(onClick = onToday) { Icon(Icons.Default.Today, "Today") }
            IconButton(onClick = onPreviousWeek) { Icon(Icons.Default.ChevronLeft, "Previous week") }
            IconButton(onClick = onNextWeek) { Icon(Icons.Default.ChevronRight, "Next week") }
            IconButton(onClick = onRefresh, enabled = !syncState.syncing) {
                Icon(Icons.Default.Refresh, "Refresh calendars")
            }
        }
        Spacer(Modifier.height(12.dp))
        Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(Modifier.weight(0.72f).fillMaxHeight()) {
                (0L..6L).forEach { offset ->
                    val day = weekStart.plusDays(offset)
                    DayColumn(
                        day = day,
                        events = events.filter { it.occursOn(day) },
                        modifier = Modifier.weight(1f).fillMaxHeight(),
                    )
                }
            }
            AgendaPanel(
                events = events.filter { it.endEpochMillis > System.currentTimeMillis() }.take(20),
                modifier = Modifier.weight(0.28f).fillMaxHeight(),
            )
        }
    }
}

@Composable
private fun DayColumn(day: LocalDate, events: List<CalendarEventEntity>, modifier: Modifier = Modifier) {
    val today = day == LocalDate.now()
    Column(
        modifier
            .background(if (today) Color(0xFFE7F0FA) else MaterialTheme.colorScheme.surface)
            .padding(horizontal = 5.dp, vertical = 8.dp),
    ) {
        Text(
            day.format(DAY_NAME),
            style = MaterialTheme.typography.labelLarge.copy(
                fontSize = 16.sp,
                lineHeight = 20.sp,
            ),
        )
        Text(
            day.dayOfMonth.toString(),
            style = MaterialTheme.typography.titleLarge,
            color = if (today) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
        )
        HorizontalDivider(Modifier.padding(vertical = 6.dp))
        LazyColumn(verticalArrangement = Arrangement.spacedBy(5.dp)) {
            items(events, key = { "${it.calendarId}:${it.eventId}" }) { event ->
                EventChip(event)
            }
        }
    }
}

@Composable
private fun EventChip(event: CalendarEventEntity) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(end = 5.dp),
    ) {
        Box(Modifier.width(5.dp).height(48.dp).background(parseColor(event.color)))
        Column(Modifier.padding(horizontal = 6.dp, vertical = 4.dp)) {
            Text(
                event.title,
                style = MaterialTheme.typography.labelLarge.copy(
                    fontSize = 17.sp,
                    lineHeight = 20.sp,
                ),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                event.timeLabel(),
                style = MaterialTheme.typography.labelSmall.copy(
                    fontSize = 14.sp,
                    lineHeight = 18.sp,
                ),
            )
        }
    }
}

@Composable
private fun AgendaPanel(events: List<CalendarEventEntity>, modifier: Modifier = Modifier) {
    Column(modifier.background(MaterialTheme.colorScheme.surface).padding(12.dp)) {
        Text("Upcoming", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(8.dp))
        LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            items(events, key = { "agenda:${it.calendarId}:${it.eventId}" }) { event ->
                Row {
                    Box(Modifier.width(4.dp).height(40.dp).background(parseColor(event.color)))
                    Column(Modifier.padding(start = 8.dp)) {
                        Text(event.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            event.agendaLabel(),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.outline,
                        )
                    }
                }
            }
        }
    }
}

private fun CalendarEventEntity.occursOn(day: LocalDate): Boolean {
    val zone = ZoneId.systemDefault()
    val startDate = Instant.ofEpochMilli(startEpochMillis).atZone(zone).toLocalDate()
    val endDate = Instant.ofEpochMilli((endEpochMillis - 1).coerceAtLeast(startEpochMillis)).atZone(zone).toLocalDate()
    return !day.isBefore(startDate) && !day.isAfter(endDate)
}

private fun CalendarEventEntity.timeLabel(): String {
    if (allDay) return "All day"
    return Instant.ofEpochMilli(startEpochMillis).atZone(ZoneId.systemDefault()).format(TIME)
}

private fun CalendarEventEntity.agendaLabel(): String {
    val start = Instant.ofEpochMilli(startEpochMillis).atZone(ZoneId.systemDefault())
    return if (allDay) start.format(AGENDA_DATE) else "${start.format(AGENDA_DATE)} · ${start.format(TIME)}"
}

internal fun parseColor(value: String): Color = runCatching {
    Color(android.graphics.Color.parseColor(value))
}.getOrDefault(Color(0xFF607D8B))

private val DAY_NAME = DateTimeFormatter.ofPattern("EEE")
private val MONTH_DAY = DateTimeFormatter.ofPattern("MMM d")
private val AGENDA_DATE = DateTimeFormatter.ofPattern("EEE, MMM d")
private val TIME = DateTimeFormatter.ofPattern("h:mm a")
