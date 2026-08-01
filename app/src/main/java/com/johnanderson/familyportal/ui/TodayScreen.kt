package com.johnanderson.familyportal.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.johnanderson.familyportal.calendar.CalendarEventEntity
import com.johnanderson.familyportal.core.CameraConfig
import com.johnanderson.familyportal.core.ConnectionState
import com.johnanderson.familyportal.ha.WeatherSnapshot
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.delay

@Composable
fun TodayScreen(
    events: List<CalendarEventEntity>,
    cameras: List<CameraConfig>,
    homeAssistantState: ConnectionState,
    weather: WeatherSnapshot?,
    weatherError: String?,
    onOpenCalendar: () -> Unit,
    onCameraSelected: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val now by produceState(Instant.now()) {
        while (true) {
            value = Instant.now()
            delay(CLOCK_REFRESH_MILLIS)
        }
    }
    val zone = ZoneId.systemDefault()
    val today = now.atZone(zone).toLocalDate()
    val todayEvents = remember(events, today) { eventsForDay(events, today, zone) }
    val nextEvent = remember(todayEvents, now) { nextTimedEvent(todayEvents, now) }
    var selectedEvent by remember { mutableStateOf<CalendarEventEntity?>(null) }

    Column(modifier.fillMaxSize().padding(horizontal = 18.dp, vertical = 14.dp)) {
        TodayHeader(today = today, now = now, nextEvent = nextEvent, zone = zone)
        Spacer(Modifier.height(12.dp))
        HorizontalDivider()
        Spacer(Modifier.height(12.dp))
        Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(40.dp)) {
            Surface(
                modifier = Modifier.weight(1.5f).fillMaxHeight(),
                shape = RoundedCornerShape(6.dp),
                color = MaterialTheme.colorScheme.surface,
            ) {
                TodayAgenda(
                    events = todayEvents,
                    now = now,
                    zone = zone,
                    onEventClick = { selectedEvent = it },
                    onOpenCalendar = onOpenCalendar,
                    modifier = Modifier.fillMaxSize().padding(16.dp),
                )
            }
            Surface(
                modifier = Modifier.weight(1f).fillMaxHeight(),
                shape = RoundedCornerShape(6.dp),
                color = MaterialTheme.colorScheme.surface,
            ) {
                Column(
                    Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(16.dp),
                ) {
                    WeatherDashboardPanel(weather = weather, error = weatherError, now = now, zone = zone)
                    DashboardSectionDivider()
                    HomeStatus(connectionState = homeAssistantState)
                    DashboardSectionDivider()
                    CameraShortcuts(cameras = cameras, onCameraSelected = onCameraSelected)
                }
            }
        }
    }

    selectedEvent?.let { event ->
        EventDetailDialog(event = event, onDismiss = { selectedEvent = null })
    }
}

@Composable
private fun TodayHeader(
    today: LocalDate,
    now: Instant,
    nextEvent: CalendarEventEntity?,
    zone: ZoneId,
) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(
                today.format(DAY_DATE),
                style = MaterialTheme.typography.headlineMedium.copy(fontSize = 28.sp),
            )
            Text(
                if (nextEvent == null) "No more timed events today" else nextEvent.nextEventLabel(now, zone),
                style = MaterialTheme.typography.bodyLarge.copy(fontSize = 18.sp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Text(
            now.atZone(zone).format(CLOCK_TIME),
            style = MaterialTheme.typography.displaySmall.copy(fontSize = 42.sp),
        )
    }
}

@Composable
private fun TodayAgenda(
    events: List<CalendarEventEntity>,
    now: Instant,
    zone: ZoneId,
    onEventClick: (CalendarEventEntity) -> Unit,
    onOpenCalendar: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("Today's agenda", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
            Button(onClick = onOpenCalendar) {
                Icon(Icons.Default.CalendarMonth, null)
                Text(" Week")
            }
        }
        Spacer(Modifier.height(8.dp))
        if (events.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    "Nothing scheduled today",
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(events, key = { "${it.calendarId}:${it.eventId}" }) { event ->
                    TodayEventRow(
                        event = event,
                        isPast = !event.allDay && event.endEpochMillis <= now.toEpochMilli(),
                        zone = zone,
                        onClick = { onEventClick(event) },
                    )
                }
            }
        }
    }
}

@Composable
private fun TodayEventRow(
    event: CalendarEventEntity,
    isPast: Boolean,
    zone: ZoneId,
    onClick: () -> Unit,
) {
    val eventColor = eventBackgroundColor(event.color)
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(4.dp))
            .background(eventColor.copy(alpha = if (isPast) 0.58f else 1f))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            event.todayTimeLabel(zone),
            modifier = Modifier.width(148.dp),
            style = MaterialTheme.typography.labelLarge.copy(fontSize = 16.sp),
            color = Color.White,
        )
        Text(
            event.title,
            style = MaterialTheme.typography.titleMedium.copy(fontSize = 19.sp),
            fontWeight = FontWeight.SemiBold,
            color = Color.White,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun HomeStatus(connectionState: ConnectionState) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Home status", style = MaterialTheme.typography.headlineSmall)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Home, null, modifier = Modifier.padding(end = 8.dp))
            Column {
                Text("Home Assistant", style = MaterialTheme.typography.titleMedium)
                Text(
                    connectionState.name.lowercase().replaceFirstChar(Char::uppercase),
                    color = if (connectionState == ConnectionState.CONNECTED) {
                        MaterialTheme.colorScheme.secondary
                    } else {
                        MaterialTheme.colorScheme.error
                    },
                )
            }
        }
    }
}

@Composable
private fun DashboardSectionDivider() {
    HorizontalDivider(
        modifier = Modifier.padding(vertical = 18.dp),
        color = MaterialTheme.colorScheme.outlineVariant,
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CameraShortcuts(cameras: List<CameraConfig>, onCameraSelected: (String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Cameras", style = MaterialTheme.typography.headlineSmall)
        if (cameras.isEmpty()) {
            Text("No cameras configured")
        } else {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                cameras.forEach { camera ->
                    OutlinedButton(onClick = { onCameraSelected(camera.id) }) {
                        Icon(Icons.Default.Videocam, null)
                        Text(" ${camera.name}", maxLines = 1)
                    }
                }
            }
        }
    }
}

private fun CalendarEventEntity.todayTimeLabel(zone: ZoneId): String {
    if (allDay) return "All day"
    val start = Instant.ofEpochMilli(startEpochMillis).atZone(zone)
    val end = Instant.ofEpochMilli(endEpochMillis).atZone(zone)
    return if (start.format(MERIDIEM) == end.format(MERIDIEM)) {
        "${start.format(EVENT_TIME_NO_MERIDIEM)}-${end.format(EVENT_TIME)}"
    } else {
        "${start.format(EVENT_TIME)}-${end.format(EVENT_TIME)}"
    }
}

private fun CalendarEventEntity.nextEventLabel(now: Instant, zone: ZoneId): String {
    val start = Instant.ofEpochMilli(startEpochMillis).atZone(zone)
    return if (start.toInstant() <= now) {
        "Now: $title"
    } else {
        "Up next at ${start.format(EVENT_TIME)}: $title"
    }
}

internal fun eventsForDay(
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

internal fun nextTimedEvent(
    events: List<CalendarEventEntity>,
    now: Instant,
): CalendarEventEntity? = events.firstOrNull {
    !it.allDay && it.endEpochMillis > now.toEpochMilli()
}

private const val CLOCK_REFRESH_MILLIS = 30_000L
private val DAY_DATE = DateTimeFormatter.ofPattern("EEEE, MMMM d")
private val CLOCK_TIME = DateTimeFormatter.ofPattern("h:mm a")
private val EVENT_TIME = DateTimeFormatter.ofPattern("h:mm a")
private val EVENT_TIME_NO_MERIDIEM = DateTimeFormatter.ofPattern("h:mm")
private val MERIDIEM = DateTimeFormatter.ofPattern("a")
