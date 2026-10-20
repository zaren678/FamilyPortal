package com.johnanderson.familyportal.tv.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.tv.material3.Border
import androidx.tv.material3.Button
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.OutlinedButton
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.johnanderson.familyportal.calendar.CalendarEventEntity
import com.johnanderson.familyportal.camera.CameraRepository
import com.johnanderson.familyportal.core.AppSettings
import com.johnanderson.familyportal.core.CameraConfig
import com.johnanderson.familyportal.ha.WeatherSnapshot
import com.johnanderson.familyportal.ui.EVENT_CONTENT_COLOR
import com.johnanderson.familyportal.ui.EventDetailDialog
import com.johnanderson.familyportal.ui.LiveCameraPreview
import com.johnanderson.familyportal.ui.PrewarmCameraStreams
import com.johnanderson.familyportal.ui.cameraGridColumns
import com.johnanderson.familyportal.ui.eventBackgroundColor
import com.johnanderson.familyportal.ui.eventsForDay
import com.johnanderson.familyportal.ui.weatherConditionIcon
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt
import kotlinx.coroutines.delay

/**
 * TV dashboard: live camera grid plus today's agenda, navigated with the D-pad.
 * OK on a camera opens it full screen; Back returns focus to that camera.
 */
@Composable
fun TvPortalScreen(
    settings: AppSettings,
    events: List<CalendarEventEntity>,
    googleAuthorized: Boolean,
    weather: WeatherSnapshot?,
    cameraRepository: CameraRepository,
    onOpenSetup: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val lifecycleOwner = LocalLifecycleOwner.current
    val lifecycleState by lifecycleOwner.lifecycle.currentStateFlow.collectAsState()
    // Stop all video when the TV leaves the app or the screensaver starts.
    val active = lifecycleState.isAtLeast(Lifecycle.State.RESUMED)
    val now by produceState(Instant.now()) {
        while (true) {
            value = Instant.now()
            delay(CLOCK_REFRESH_MILLIS)
        }
    }
    val zone = ZoneId.systemDefault()
    val today = now.atZone(zone).toLocalDate()
    val agenda = remember(events, today) { eventsForDay(events, today, zone) }
    val cameras = remember(settings.cameras) { settings.cameras.sortedByDescending { it.isDoorbell } }
    var fullscreenCameraId by rememberSaveable { mutableStateOf<String?>(null) }
    var lastViewedCameraId by rememberSaveable { mutableStateOf<String?>(null) }
    var selectedEvent by remember { mutableStateOf<CalendarEventEntity?>(null) }

    fullscreenCameraId?.takeIf { id -> cameras.any { it.id == id } }?.let { cameraId ->
        TvCameraViewer(
            cameras = cameras,
            initialCameraId = cameraId,
            homeAssistantUrl = settings.homeAssistantUrl,
            repository = cameraRepository,
            active = active,
            onExit = { lastCameraId ->
                lastViewedCameraId = lastCameraId
                fullscreenCameraId = null
            },
        )
        return
    }

    selectedEvent?.let { event ->
        EventDetailDialog(event = event, onDismiss = { selectedEvent = null })
    }

    val needsSetup = tvNeedsSetup(settings, googleAuthorized)
    Column(
        modifier
            .fillMaxSize()
            .padding(horizontal = TV_OVERSCAN_HORIZONTAL, vertical = TV_OVERSCAN_VERTICAL),
    ) {
        TvHeader(
            now = now,
            zone = zone,
            weather = weather,
            onOpenSetup = onOpenSetup,
            requestFocus = !needsSetup && cameras.isEmpty() && agenda.isEmpty(),
        )
        Spacer(Modifier.height(20.dp))
        if (needsSetup) {
            TvSetupPrompt(onOpenSetup = onOpenSetup)
        } else {
            Row(
                Modifier.fillMaxSize(),
                horizontalArrangement = Arrangement.spacedBy(28.dp),
            ) {
                TvCameraGrid(
                    cameras = cameras,
                    homeAssistantUrl = settings.homeAssistantUrl,
                    repository = cameraRepository,
                    active = active,
                    focusCameraId = lastViewedCameraId ?: cameras.firstOrNull()?.id,
                    onCameraSelected = { fullscreenCameraId = it },
                    modifier = Modifier.weight(1.6f).fillMaxHeight(),
                )
                TvAgenda(
                    events = agenda,
                    googleAuthorized = googleAuthorized,
                    zone = zone,
                    requestFocus = cameras.isEmpty(),
                    onEventSelected = { selectedEvent = it },
                    modifier = Modifier.weight(1f).fillMaxHeight(),
                )
            }
        }
    }
}

/** Show the setup prompt only until at least one of Home Assistant or Google is connected. */
internal fun tvNeedsSetup(settings: AppSettings, googleAuthorized: Boolean): Boolean =
    settings.homeAssistantUrl.isBlank() && !googleAuthorized

@Composable
private fun TvHeader(
    now: Instant,
    zone: ZoneId,
    weather: WeatherSnapshot?,
    onOpenSetup: () -> Unit,
    requestFocus: Boolean,
) {
    val local = now.atZone(zone)
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(local.format(TV_DAY_DATE), style = MaterialTheme.typography.headlineLarge)
            Text(
                local.format(TV_CLOCK_TIME),
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        weather?.let { TvWeatherSummary(it) }
        Spacer(Modifier.width(32.dp))
        OutlinedButton(onClick = onOpenSetup, modifier = Modifier.initialFocus(requestFocus)) {
            Icon(Icons.Default.Settings, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text("Settings")
        }
    }
}

@Composable
private fun TvWeatherSummary(weather: WeatherSnapshot) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            weatherConditionIcon(weather.condition),
            contentDescription = weather.condition,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(44.dp),
        )
        Spacer(Modifier.width(12.dp))
        Column {
            Text(
                weather.temperature?.let { "${it.roundToInt()}${weather.temperatureUnit}" } ?: "--",
                style = MaterialTheme.typography.headlineSmall,
            )
            val high = weather.highTemperature?.roundToInt()
            val low = weather.lowTemperature?.roundToInt()
            if (high != null && low != null) {
                Text(
                    "H $high°  L $low°",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun TvSetupPrompt(onOpenSetup: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(8.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(32.dp)) {
            Text("Set up Family Portal on this TV", style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.height(8.dp))
            Text(
                "Connect Google Calendar and Home Assistant. You'll approve the sign-ins " +
                    "from your phone; this TV keeps its own settings.",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(24.dp))
            Button(onClick = onOpenSetup, modifier = Modifier.initialFocus(true)) {
                Text("Set up")
            }
        }
    }
}

@Composable
private fun TvCameraGrid(
    cameras: List<CameraConfig>,
    homeAssistantUrl: String,
    repository: CameraRepository,
    active: Boolean,
    focusCameraId: String?,
    onCameraSelected: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (cameras.isEmpty() || homeAssistantUrl.isBlank()) {
        Box(modifier, contentAlignment = Alignment.Center) {
            Text(
                if (homeAssistantUrl.isBlank()) "Connect Home Assistant in Settings to see cameras"
                else "Add cameras in Settings",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        return
    }
    PrewarmCameraStreams(cameras, homeAssistantUrl, repository, active)
    LazyVerticalGrid(
        columns = GridCells.Fixed(cameraGridColumns(cameras.size)),
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        itemsIndexed(cameras, key = { _, camera -> camera.id }) { index, camera ->
            TvCameraTile(
                camera = camera,
                homeAssistantUrl = homeAssistantUrl,
                repository = repository,
                active = active,
                startupDelayMillis = index * 600L,
                requestFocus = camera.id == focusCameraId,
                onClick = { onCameraSelected(camera.id) },
            )
        }
    }
}

@Composable
private fun TvCameraTile(
    camera: CameraConfig,
    homeAssistantUrl: String,
    repository: CameraRepository,
    active: Boolean,
    startupDelayMillis: Long,
    requestFocus: Boolean,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(16f / 9f)
            .initialFocus(requestFocus),
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(8.dp)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = Color.Black,
            focusedContainerColor = Color.Black,
        ),
        // Video renders on its own surface, which doesn't follow Compose scaling.
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
        border = ClickableSurfaceDefaults.border(
            focusedBorder = Border(BorderStroke(4.dp, MaterialTheme.colorScheme.primary)),
        ),
    ) {
        LiveCameraPreview(
            camera = camera,
            homeAssistantUrl = homeAssistantUrl,
            repository = repository,
            active = active,
            startupDelayMillis = startupDelayMillis,
            modifier = Modifier.fillMaxSize(),
        )
        Text(
            camera.name,
            modifier = Modifier
                .align(Alignment.BottomStart)
                .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.85f))
                .padding(horizontal = 12.dp, vertical = 8.dp),
            color = MaterialTheme.colorScheme.onSurface,
            style = MaterialTheme.typography.titleMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun TvAgenda(
    events: List<CalendarEventEntity>,
    googleAuthorized: Boolean,
    zone: ZoneId,
    requestFocus: Boolean,
    onEventSelected: (CalendarEventEntity) -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(modifier = modifier, shape = RoundedCornerShape(8.dp)) {
        Column(Modifier.fillMaxSize().padding(20.dp)) {
            Text("Today", style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.height(12.dp))
            if (events.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        if (googleAuthorized) "No events today" else "Connect Google Calendar in Settings",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    itemsIndexed(events, key = { _, event -> "${event.calendarId}/${event.eventId}" }) { index, event ->
                        TvAgendaRow(
                            event = event,
                            zone = zone,
                            requestFocus = requestFocus && index == 0,
                            onClick = { onEventSelected(event) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun TvAgendaRow(
    event: CalendarEventEntity,
    zone: ZoneId,
    requestFocus: Boolean,
    onClick: () -> Unit,
) {
    val eventColor = eventBackgroundColor(event.color)
    Surface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().initialFocus(requestFocus),
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(6.dp)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = eventColor,
            contentColor = EVENT_CONTENT_COLOR,
            focusedContainerColor = eventColor,
            focusedContentColor = EVENT_CONTENT_COLOR,
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.03f),
        border = ClickableSurfaceDefaults.border(
            focusedBorder = Border(BorderStroke(3.dp, EVENT_CONTENT_COLOR)),
        ),
    ) {
        Row(
            Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    event.title,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (!event.allDay) {
                    Text(event.tvTimeLabel(zone), style = MaterialTheme.typography.bodyMedium)
                }
            }
            if (event.allDay) {
                Text("All day", style = MaterialTheme.typography.labelLarge)
            }
        }
    }
}

/** Requests focus once when first composed, e.g. to restore the last-viewed camera. */
@Composable
internal fun Modifier.initialFocus(enabled: Boolean): Modifier {
    if (!enabled) return this
    val requester = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { requester.requestFocus() } }
    return focusRequester(requester)
}

private fun CalendarEventEntity.tvTimeLabel(zone: ZoneId): String {
    val start = Instant.ofEpochMilli(startEpochMillis).atZone(zone)
    val end = Instant.ofEpochMilli(endEpochMillis).atZone(zone)
    return "${start.format(TV_EVENT_TIME)} – ${end.format(TV_EVENT_TIME)}"
}

// Google's TV guidelines: keep content within ~5% of each screen edge.
private val TV_OVERSCAN_HORIZONTAL = 48.dp
private val TV_OVERSCAN_VERTICAL = 27.dp
private const val CLOCK_REFRESH_MILLIS = 30_000L
private val TV_DAY_DATE = DateTimeFormatter.ofPattern("EEEE, MMMM d")
private val TV_CLOCK_TIME = DateTimeFormatter.ofPattern("h:mm a")
private val TV_EVENT_TIME = DateTimeFormatter.ofPattern("h:mm a")
