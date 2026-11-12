package com.johnanderson.familyportal.ui

import android.view.ViewGroup
import androidx.activity.compose.BackHandler
import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.PlayerView
import com.johnanderson.familyportal.EventHistoryState
import com.johnanderson.familyportal.ha.FrigateEvent
import kotlinx.coroutines.delay
import java.time.LocalDate
import java.time.ZoneId

/** What Frigate has detected on the app's cameras, newest first, grouped by day. */
@Composable
fun EventHistoryScreen(
    state: EventHistoryState,
    active: Boolean,
    onRefresh: () -> Unit,
    onLoadOlder: () -> Unit,
    onSelectCamera: (String?) -> Unit,
    onSelectLabel: (String?) -> Unit,
    loadImage: suspend (FrigateEvent) -> ByteArray,
    onEventSelected: (FrigateEvent) -> Unit,
    modifier: Modifier = Modifier,
    /** Drawn before the filters, on the same row. */
    header: @Composable () -> Unit = {},
) {
    LaunchedEffect(active) {
        while (active) {
            onRefresh()
            delay(HISTORY_REFRESH_MILLIS)
        }
    }
    val zone = ZoneId.systemDefault()
    val error = state.error
    // Lined up with the live camera grid it replaces.
    Column(modifier.fillMaxSize().padding(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            header()
            HistoryFilters(state, onSelectCamera, onSelectLabel, Modifier.weight(1f).padding(start = 16.dp))
        }
        if (error != null && state.events.isNotEmpty()) {
            Text(
                "Couldn't refresh: $error",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 6.dp),
            )
        }
        Spacer(Modifier.height(10.dp))
        when {
            state.cameras?.isEmpty() == true -> HistoryMessage(
                "None of your cameras come from Frigate",
                "Add cameras from Home Assistant's Frigate integration in Settings to see what they've detected.",
            )
            state.events.isEmpty() && error != null -> HistoryMessage(
                "Couldn't load camera history",
                error,
                action = { Button(onClick = onRefresh) { Text("Try again") } },
            )
            !state.loaded -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            state.events.isEmpty() -> HistoryMessage(
                state.noEventsMessage(),
                "People, cars, and animals Frigate spots will show up here.",
            )
            else -> LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 230.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                val today = LocalDate.now(zone)
                eventsByDay(state.events, zone).forEach { (day, events) ->
                    item(key = "day-$day", span = { GridItemSpan(maxLineSpan) }) {
                        Text(
                            dayLabel(day, today),
                            style = MaterialTheme.typography.titleLarge,
                            modifier = Modifier.padding(top = 6.dp),
                        )
                    }
                    items(events, key = FrigateEvent::id) { event ->
                        EventCard(
                            event = event,
                            cameraName = state.cameraFor(event)?.name ?: displayName(event.camera.name),
                            zone = zone,
                            loadImage = loadImage,
                            onClick = { onEventSelected(event) },
                        )
                    }
                }
                if (!state.reachedEnd) {
                    item(key = "older", span = { GridItemSpan(maxLineSpan) }) {
                        Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                            if (state.error != null && !state.loadingOlder) {
                                TextButton(onClick = onLoadOlder) { Text("Load older events") }
                            } else {
                                CircularProgressIndicator()
                                // Scrolled to the bottom, or the screen isn't full yet.
                                LaunchedEffect(state.events.size) { onLoadOlder() }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun HistoryFilters(
    state: EventHistoryState,
    onSelectCamera: (String?) -> Unit,
    onSelectLabel: (String?) -> Unit,
    modifier: Modifier = Modifier,
) {
    val cameras = state.filterCameras
    val selectedCamera = state.selectedCameraId?.takeIf { id -> cameras.any { it.id == id } }
    // Only worth filtering once there's more than one camera or kind of thing to choose from.
    val offerLabels = state.labels.size > 1 || state.selectedLabel != null
    LazyRow(
        modifier,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (state.offerCameraFilter) {
            item {
                HistoryChip("All cameras", selected = selectedCamera == null) { onSelectCamera(null) }
            }
            items(cameras, key = { it.id }) { camera ->
                HistoryChip(camera.name, selected = camera.id == selectedCamera) { onSelectCamera(camera.id) }
            }
        }
        if (offerLabels) {
            if (state.offerCameraFilter) {
                item { VerticalDivider(Modifier.height(32.dp).padding(horizontal = 4.dp)) }
            }
            item {
                HistoryChip("Everything", selected = state.selectedLabel == null) { onSelectLabel(null) }
            }
            items(state.labels, key = { "label-$it" }) { label ->
                HistoryChip(displayName(label), selected = label == state.selectedLabel) { onSelectLabel(label) }
            }
        }
    }
}

@Composable
private fun HistoryChip(label: String, selected: Boolean, onClick: () -> Unit) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label, style = MaterialTheme.typography.labelLarge.copy(fontSize = 16.sp)) },
    )
}

@Composable
private fun HistoryMessage(title: String, detail: String, action: (@Composable () -> Unit)? = null) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(title, style = MaterialTheme.typography.headlineSmall)
            Text(
                detail,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.width(520.dp).padding(top = 8.dp),
            )
            if (action != null) {
                Spacer(Modifier.height(16.dp))
                action()
            }
        }
    }
}

@Composable
private fun EventCard(
    event: FrigateEvent,
    cameraName: String,
    zone: ZoneId,
    loadImage: suspend (FrigateEvent) -> ByteArray,
    onClick: () -> Unit,
) {
    Column(
        Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(MaterialTheme.colorScheme.surface)
            .clickable(onClick = onClick),
    ) {
        Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).background(Color.Black)) {
            FrigateEventImage(event, loadImage, ContentScale.Crop, Modifier.fillMaxSize())
            if (event.endTime == null) {
                Text(
                    "NOW",
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .background(MaterialTheme.colorScheme.tertiary)
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                    color = MaterialTheme.colorScheme.onTertiary,
                    style = MaterialTheme.typography.labelSmall,
                )
            } else {
                Text(
                    durationLabel(event),
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(6.dp)
                        .background(Color.Black.copy(alpha = 0.65f), RoundedCornerShape(4.dp))
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                    color = Color.White,
                    style = MaterialTheme.typography.labelMedium,
                )
            }
        }
        Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            Text(
                eventTitle(event),
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                "$cameraName · ${eventClockTime(event, zone)}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * Plays an event's recording full screen, or shows its picture when there's no
 * recording to play. Pauses while not [active], e.g. under a doorbell alert.
 */
@OptIn(UnstableApi::class) // PlayerView button and buffering settings
@Composable
fun EventClipOverlay(
    event: FrigateEvent,
    cameraName: String,
    active: Boolean,
    loadClip: suspend (FrigateEvent) -> String,
    loadImage: suspend (FrigateEvent) -> ByteArray,
    onDismiss: () -> Unit,
) {
    BackHandler(onBack = onDismiss)
    val clip = rememberEventClip(event, loadClip, active)
    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            // Keep taps from reaching the screen underneath.
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {},
    ) {
        when (clip) {
            EventClip.Unavailable -> {
                FrigateEventImage(event, loadImage, ContentScale.Fit, Modifier.fillMaxSize())
                Text(
                    if (event.hasClip) "Couldn't play this recording" else "No recording for this one",
                    color = Color.White,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(24.dp)
                        .background(Color.Black.copy(alpha = 0.65f), RoundedCornerShape(4.dp))
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                )
            }
            is EventClip.Ready -> AndroidView(
                factory = {
                    PlayerView(it).apply {
                        player = clip.player
                        setShowNextButton(false)
                        setShowPreviousButton(false)
                        setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING)
                        layoutParams = ViewGroup.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT,
                        )
                    }
                },
                update = { it.player = clip.player },
                modifier = Modifier.fillMaxSize(),
            )
            EventClip.Loading -> CircularProgressIndicator(Modifier.align(Alignment.Center))
        }
        Column(
            Modifier
                .align(Alignment.TopStart)
                .padding(20.dp)
                .background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(6.dp))
                .padding(horizontal = 14.dp, vertical = 8.dp),
        ) {
            Text(eventTitle(event), color = Color.White, style = MaterialTheme.typography.headlineSmall)
            Text(
                eventPlaceAndTime(event, cameraName, ZoneId.systemDefault()),
                color = Color.White.copy(alpha = 0.85f),
                style = MaterialTheme.typography.bodyLarge,
            )
        }
        FilledIconButton(
            onClick = onDismiss,
            modifier = Modifier.align(Alignment.TopEnd).padding(top = 68.dp, end = 20.dp),
        ) {
            Icon(Icons.Default.Close, "Close")
        }
    }
}

private const val HISTORY_REFRESH_MILLIS = 30_000L
