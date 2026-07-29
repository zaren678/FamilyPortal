package com.johnanderson.familyportal.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.produceState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalDensity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.johnanderson.familyportal.calendar.CalendarEventEntity
import com.johnanderson.familyportal.calendar.CalendarSyncState
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.max

@Composable
fun CalendarScreen(
    weekStart: LocalDate,
    events: List<CalendarEventEntity>,
    syncState: CalendarSyncState,
    onWeekSelected: (LocalDate) -> Unit,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var selectedEvent by remember { mutableStateOf<CalendarEventEntity?>(null) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) selectedEvent = null
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(lifecycleOwner) { selectedEvent = null }
    val pagerBaseWeek = remember { weekStart }
    val pagerState = rememberPagerState(
        initialPage = WEEK_PAGER_INITIAL_PAGE,
        pageCount = { WEEK_PAGER_PAGE_COUNT },
    )
    val coroutineScope = rememberCoroutineScope()
    val displayedWeek = weekForPagerPage(pagerBaseWeek, pagerState.currentPage)
    val currentMinuteOfDay by produceState(LocalTime.now().toSecondOfDay() / 60f) {
        while (true) {
            val now = LocalTime.now()
            value = now.toSecondOfDay() / 60f
            delay(60_000L - (System.currentTimeMillis() % 60_000L))
        }
    }

    LaunchedEffect(pagerState.settledPage) {
        onWeekSelected(weekForPagerPage(pagerBaseWeek, pagerState.settledPage))
    }

    Column(modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 10.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                weekRangeLabel(displayedWeek),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.weight(1f))
            if (syncState.syncing) {
                CircularProgressIndicator(Modifier.width(24.dp), strokeWidth = 2.dp)
            }
            syncState.error?.let {
                Text(
                    "Offline",
                    color = MaterialTheme.colorScheme.tertiary,
                    modifier = Modifier.padding(horizontal = 8.dp),
                )
            }
            IconButton(
                onClick = {
                    coroutineScope.launch {
                        pagerState.animateScrollToPage(
                            pagerPageForWeek(pagerBaseWeek, currentWeekStart()),
                        )
                    }
                },
            ) { Icon(Icons.Default.Today, "Today") }
            IconButton(
                onClick = {
                    coroutineScope.launch {
                        pagerState.animateScrollToPage(pagerState.currentPage - 1)
                    }
                },
            ) {
                Icon(Icons.Default.ChevronLeft, "Previous week")
            }
            IconButton(
                onClick = {
                    coroutineScope.launch {
                        pagerState.animateScrollToPage(pagerState.currentPage + 1)
                    }
                },
            ) { Icon(Icons.Default.ChevronRight, "Next week") }
            IconButton(onClick = onRefresh, enabled = !syncState.syncing) {
                Icon(Icons.Default.Refresh, "Refresh calendars")
            }
        }
        HorizontalPager(
            state = pagerState,
            modifier = Modifier.weight(1f).fillMaxWidth(),
            beyondViewportPageCount = 1,
            key = { page -> weekForPagerPage(pagerBaseWeek, page).toEpochDay() },
        ) { page ->
            WeekTimeline(
                weekStart = weekForPagerPage(pagerBaseWeek, page),
                events = events,
                currentMinuteOfDay = currentMinuteOfDay,
                onEventClick = { selectedEvent = it },
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
    selectedEvent?.let { event ->
        EventDetailDialog(
            event = event,
            onDismiss = { selectedEvent = null },
        )
    }
}

@Composable
private fun WeekTimeline(
    weekStart: LocalDate,
    events: List<CalendarEventEntity>,
    currentMinuteOfDay: Float,
    onEventClick: (CalendarEventEntity) -> Unit,
    modifier: Modifier = Modifier,
) {
    val days = remember(weekStart) { List(DAYS_PER_WEEK) { weekStart.plusDays(it.toLong()) } }
    val eventsByDay = remember(days, events) {
        days.associateWith { day -> events.filter { it.occursOn(day) } }
    }
    val allDayByDay = remember(eventsByDay) {
        eventsByDay.mapValues { (_, dayEvents) -> dayEvents.filter { it.allDay } }
    }
    val allDayRows = allDayByDay.values.maxOfOrNull { it.size }
        ?.coerceAtMost(MAX_ALL_DAY_ROWS)
        ?: 0

    Column(modifier) {
        Row(Modifier.fillMaxWidth().height(DAY_HEADER_HEIGHT)) {
            Spacer(Modifier.width(TIME_GUTTER_WIDTH))
            days.forEach { day ->
                DayHeader(day, Modifier.weight(1f).fillMaxHeight())
            }
        }
        if (allDayRows > 0) {
            AllDayBand(
                days = days,
                eventsByDay = allDayByDay,
                rows = allDayRows,
                onEventClick = onEventClick,
            )
        }
        HorizontalDivider()
        TimelineBody(
            weekStart = weekStart,
            days = days,
            eventsByDay = eventsByDay,
            currentMinuteOfDay = currentMinuteOfDay,
            onEventClick = onEventClick,
            modifier = Modifier.weight(1f).fillMaxWidth(),
        )
    }
}

@Composable
private fun DayHeader(day: LocalDate, modifier: Modifier = Modifier) {
    val today = day == LocalDate.now()
    val backgroundColor = if (today) {
        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f)
    } else {
        MaterialTheme.colorScheme.surface
    }
    Column(
        modifier.background(backgroundColor),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            day.format(DAY_NAME),
            style = MaterialTheme.typography.labelLarge.copy(
                fontSize = 16.sp,
                lineHeight = 19.sp,
            ),
        )
        Text(
            day.dayOfMonth.toString(),
            style = MaterialTheme.typography.titleLarge,
            color = if (today) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
        )
    }
}

@Composable
private fun AllDayBand(
    days: List<LocalDate>,
    eventsByDay: Map<LocalDate, List<CalendarEventEntity>>,
    rows: Int,
    onEventClick: (CalendarEventEntity) -> Unit,
) {
    val todayBackground = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f)
    val bandHeight = ALL_DAY_ROW_HEIGHT * rows
    Row(Modifier.fillMaxWidth().height(bandHeight)) {
        Box(
            Modifier.width(TIME_GUTTER_WIDTH).fillMaxHeight().padding(end = 6.dp),
            contentAlignment = Alignment.TopEnd,
        ) {
            Text(
                "All day",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline,
            )
        }
        days.forEach { day ->
            val events = eventsByDay[day].orEmpty()
            Column(
                Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .background(if (day == LocalDate.now()) todayBackground else Color.Transparent)
                    .padding(horizontal = 2.dp),
            ) {
                val visibleEvents = if (events.size > rows) events.take((rows - 1).coerceAtLeast(0)) else events
                visibleEvents.forEach { event ->
                    AllDayEventChip(event, onClick = { onEventClick(event) })
                }
                if (events.size > rows) {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(ALL_DAY_ROW_HEIGHT)
                            .padding(vertical = 2.dp),
                        contentAlignment = Alignment.CenterStart,
                    ) {
                        Text(
                            "+${events.size - visibleEvents.size} more",
                            modifier = Modifier.padding(horizontal = 6.dp),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.outline,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun AllDayEventChip(event: CalendarEventEntity, onClick: () -> Unit) {
    val eventColor = parseColor(event.color)
    val contentColor = contrastingContentColor(eventColor)
    Row(
        Modifier
            .fillMaxWidth()
            .height(ALL_DAY_ROW_HEIGHT)
            .padding(vertical = 2.dp)
            .clip(RoundedCornerShape(4.dp))
            .background(eventColor)
            .clickable(onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            event.title,
            modifier = Modifier.padding(horizontal = 5.dp),
            style = MaterialTheme.typography.labelLarge.copy(fontSize = 14.sp, lineHeight = 17.sp),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            color = contentColor,
        )
    }
}

@Composable
private fun TimelineBody(
    weekStart: LocalDate,
    days: List<LocalDate>,
    eventsByDay: Map<LocalDate, List<CalendarEventEntity>>,
    currentMinuteOfDay: Float,
    onEventClick: (CalendarEventEntity) -> Unit,
    modifier: Modifier = Modifier,
) {
    val scrollState = rememberScrollState()
    val density = LocalDensity.current
    val today = LocalDate.now()

    LaunchedEffect(weekStart) {
        val initialHour = if (today in days) {
            (LocalTime.now().hour - HOURS_BEFORE_NOW).coerceIn(0, HOURS_PER_DAY - 1)
        } else {
            DEFAULT_START_HOUR
        }
        scrollState.scrollTo(with(density) { (HOUR_HEIGHT * initialHour).roundToPx() })
    }

    Box(modifier) {
        Row(
            Modifier
                .fillMaxWidth()
                .verticalScroll(scrollState),
        ) {
            TimeGutter()
            days.forEach { day ->
                DayTimeline(
                    day = day,
                    events = eventsByDay[day].orEmpty().filterNot { it.allDay },
                    currentMinuteOfDay = currentMinuteOfDay,
                    onEventClick = onEventClick,
                    modifier = Modifier.weight(1f).height(TIMELINE_HEIGHT),
                )
            }
        }
    }
}

@Composable
private fun TimeGutter() {
    Box(Modifier.width(TIME_GUTTER_WIDTH).height(TIMELINE_HEIGHT)) {
        repeat(HOURS_PER_DAY) { hour ->
            Text(
                LocalTime.of(hour, 0).format(HOUR_LABEL),
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .offset(y = HOUR_HEIGHT * hour)
                    .padding(top = 2.dp, end = 6.dp),
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 12.sp),
                color = MaterialTheme.colorScheme.outline,
            )
        }
    }
}

@Composable
private fun DayTimeline(
    day: LocalDate,
    events: List<CalendarEventEntity>,
    currentMinuteOfDay: Float,
    onEventClick: (CalendarEventEntity) -> Unit,
    modifier: Modifier = Modifier,
) {
    val lineColor = MaterialTheme.colorScheme.outlineVariant
    val currentTimeColor = MaterialTheme.colorScheme.error
    val today = day == LocalDate.now()
    val placements = remember(day, events) { layoutTimelineEvents(events, day) }
    val backgroundColor = if (today) {
        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f)
    } else {
        MaterialTheme.colorScheme.surface
    }

    BoxWithConstraints(
        modifier
            .background(backgroundColor)
            .drawBehind {
                val hourHeight = HOUR_HEIGHT.toPx()
                for (hour in 0..HOURS_PER_DAY) {
                    val y = hour * hourHeight
                    drawLine(
                        color = lineColor,
                        start = Offset(0f, y),
                        end = Offset(size.width, y),
                        strokeWidth = 1f,
                    )
                }
                drawLine(
                    color = lineColor,
                    start = Offset(0f, 0f),
                    end = Offset(0f, size.height),
                    strokeWidth = 1f,
                )
                if (today) {
                    val y = currentMinuteOfDay / MINUTES_PER_HOUR * hourHeight
                    drawLine(
                        color = currentTimeColor,
                        start = Offset(0f, y),
                        end = Offset(size.width, y),
                        strokeWidth = 2.dp.toPx(),
                    )
                }
            },
    ) {
        placements.forEach { placement ->
            val laneGap = 2.dp
            val totalGap = laneGap * (placement.laneCount - 1)
            val eventWidth = ((maxWidth - totalGap).value / placement.laneCount).dp
            val x = (eventWidth + laneGap) * placement.lane
            val y = HOUR_HEIGHT * (placement.startMinute / MINUTES_PER_HOUR)
            val durationHeight = HOUR_HEIGHT * (
                (placement.endMinute - placement.startMinute) / MINUTES_PER_HOUR
            )
            TimelineEventCard(
                event = placement.event,
                onClick = { onEventClick(placement.event) },
                modifier = Modifier
                    .offset(x = x, y = y)
                    .width(eventWidth)
                    .height(max(durationHeight.value, MIN_EVENT_HEIGHT.value).dp)
                    .padding(horizontal = 1.dp, vertical = 1.dp),
            )
        }
    }
}

@Composable
private fun TimelineEventCard(
    event: CalendarEventEntity,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val eventColor = parseColor(event.color)
    val contentColor = contrastingContentColor(eventColor)
    Row(
        modifier
            .clip(RoundedCornerShape(4.dp))
            .background(eventColor)
            .clickable(onClick = onClick),
    ) {
        Column(Modifier.padding(horizontal = 5.dp, vertical = 3.dp)) {
            Text(
                event.title,
                style = MaterialTheme.typography.labelLarge.copy(
                    fontSize = 16.sp,
                    lineHeight = 18.sp,
                ),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                color = contentColor,
            )
            Text(
                event.timeRangeLabel(),
                style = MaterialTheme.typography.labelSmall.copy(
                    fontSize = 13.sp,
                    lineHeight = 15.sp,
                ),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = contentColor,
            )
        }
    }
}

private fun CalendarEventEntity.timeRangeLabel(): String {
    val zone = ZoneId.systemDefault()
    val start = Instant.ofEpochMilli(startEpochMillis).atZone(zone)
    val end = Instant.ofEpochMilli(endEpochMillis).atZone(zone)
    return "${start.format(TIME)}-${end.format(TIME)}"
}

internal fun parseColor(value: String): Color = runCatching {
    Color(android.graphics.Color.parseColor(value))
}.getOrDefault(Color(0xFF607D8B))

internal fun contrastingContentColor(background: Color): Color =
    if (background.luminance() > 0.5f) Color.Black else Color.White

private fun currentWeekStart(): LocalDate {
    val today = LocalDate.now()
    return today.minusDays((today.dayOfWeek.value % DAYS_PER_WEEK).toLong())
}

private const val DAYS_PER_WEEK = 7
private const val HOURS_PER_DAY = 24
private const val MINUTES_PER_HOUR = 60f
private const val DEFAULT_START_HOUR = 7
private const val HOURS_BEFORE_NOW = 3
private const val MAX_ALL_DAY_ROWS = 3

private val HOUR_HEIGHT = 52.dp
private val TIMELINE_HEIGHT = HOUR_HEIGHT * HOURS_PER_DAY
private val TIME_GUTTER_WIDTH = 56.dp
private val DAY_HEADER_HEIGHT = 52.dp
private val ALL_DAY_ROW_HEIGHT = 32.dp
private val MIN_EVENT_HEIGHT = 44.dp

private val DAY_NAME = DateTimeFormatter.ofPattern("EEE")
private val HOUR_LABEL = DateTimeFormatter.ofPattern("h a")
private val TIME = DateTimeFormatter.ofPattern("h:mm a")
