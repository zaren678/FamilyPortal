package com.johnanderson.familyportal.tv.ui

import android.view.ViewGroup
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import androidx.tv.material3.Border
import androidx.tv.material3.Button
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.SelectableSurfaceDefaults
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.johnanderson.familyportal.EventHistoryState
import com.johnanderson.familyportal.ha.FrigateEvent
import com.johnanderson.familyportal.ui.EventClip
import com.johnanderson.familyportal.ui.FrigateEventImage
import com.johnanderson.familyportal.ui.dayLabel
import com.johnanderson.familyportal.ui.displayName
import com.johnanderson.familyportal.ui.durationLabel
import com.johnanderson.familyportal.ui.eventClockTime
import com.johnanderson.familyportal.ui.eventPlaceAndTime
import com.johnanderson.familyportal.ui.eventTitle
import com.johnanderson.familyportal.ui.eventsByDay
import com.johnanderson.familyportal.ui.noEventsMessage
import com.johnanderson.familyportal.ui.rememberEventClip
import kotlinx.coroutines.delay
import java.time.LocalDate
import java.time.ZoneId

/**
 * What Frigate has detected on the TV's cameras, newest first. Up from the
 * events reaches the camera and label filters; OK plays an event; Back returns.
 */
@Composable
fun TvEventHistoryScreen(
    state: EventHistoryState,
    active: Boolean,
    onRefresh: () -> Unit,
    onLoadOlder: () -> Unit,
    onSelectCamera: (String?) -> Unit,
    onSelectLabel: (String?) -> Unit,
    loadImage: suspend (FrigateEvent) -> ByteArray,
    onEventSelected: (FrigateEvent) -> Unit,
    /** The event to focus first, e.g. the one just played; otherwise the newest. */
    focusEventId: String? = null,
) {
    LaunchedEffect(active) {
        while (active) {
            onRefresh()
            delay(HISTORY_REFRESH_MILLIS)
        }
    }
    val zone = ZoneId.systemDefault()
    val initialFocus = rememberInitialFocus()
    val error = state.error
    Column(
        Modifier
            .fillMaxSize()
            .padding(horizontal = TV_OVERSCAN_HORIZONTAL, vertical = TV_OVERSCAN_VERTICAL),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.History, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(12.dp))
            Text("Camera history", style = MaterialTheme.typography.headlineMedium)
            if (error != null && state.events.isNotEmpty()) {
                Spacer(Modifier.width(24.dp))
                Text(
                    "Couldn't refresh: $error",
                    color = MaterialTheme.colorScheme.error,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Spacer(Modifier.height(12.dp))
        TvHistoryFilters(
            state = state,
            onSelectCamera = onSelectCamera,
            onSelectLabel = onSelectLabel,
            // Only when there's nothing below to focus instead.
            initialFocus = initialFocus.takeIf { state.loaded && state.events.isEmpty() },
        )
        Spacer(Modifier.height(12.dp))
        when {
            state.cameras?.isEmpty() == true -> TvHistoryMessage(
                "None of your cameras come from Frigate. Add cameras from Home Assistant's Frigate integration in Settings.",
            )
            state.events.isEmpty() && error != null -> Column(
                Modifier.fillMaxSize(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Text("Couldn't load camera history: $error", color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(16.dp))
                Button(onClick = onRefresh, modifier = Modifier.initialFocus(initialFocus)) { Text("Try again") }
            }
            !state.loaded -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            state.events.isEmpty() -> TvHistoryMessage(state.noEventsMessage())
            else -> {
                val days = remember(state.events) { eventsByDay(state.events, zone) }
                val focusId = focusEventId?.takeIf { id -> state.events.any { it.id == id } } ?: state.events.first().id
                // Start scrolled to the card that should take focus, so it's composed and can.
                val gridState = rememberLazyGridState(initialFirstVisibleItemIndex = historyGridIndex(days, focusId))
                LazyVerticalGrid(
                    columns = GridCells.Fixed(HISTORY_COLUMNS),
                    horizontalArrangement = Arrangement.spacedBy(20.dp),
                    verticalArrangement = Arrangement.spacedBy(20.dp),
                    // Room for the focused card to grow.
                    contentPadding = PaddingValues(8.dp),
                    state = gridState,
                    modifier = Modifier.fillMaxSize(),
                ) {
                    val today = LocalDate.now(zone)
                    days.forEach { (day, events) ->
                        item(key = "day-$day", span = { GridItemSpan(maxLineSpan) }) {
                            Text(dayLabel(day, today), style = MaterialTheme.typography.titleLarge)
                        }
                        items(events, key = FrigateEvent::id) { event ->
                            TvEventCard(
                                event = event,
                                cameraName = state.cameraFor(event)?.name ?: displayName(event.camera.name),
                                zone = zone,
                                loadImage = loadImage,
                                onClick = { onEventSelected(event) },
                                modifier = Modifier.initialFocus(initialFocus, enabled = event.id == focusId),
                            )
                        }
                    }
                    if (!state.reachedEnd) {
                        item(key = "older", span = { GridItemSpan(maxLineSpan) }) {
                            Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                                if (error != null && !state.loadingOlder) {
                                    Button(onClick = onLoadOlder) { Text("Load older events") }
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
}

@Composable
private fun TvHistoryFilters(
    state: EventHistoryState,
    onSelectCamera: (String?) -> Unit,
    onSelectLabel: (String?) -> Unit,
    initialFocus: InitialFocus?,
) {
    val cameras = state.filterCameras
    val selectedCamera = state.selectedCameraId?.takeIf { id -> cameras.any { it.id == id } }
    // Only worth filtering once there's more than one camera or kind of thing to choose from.
    val offerLabels = state.labels.size > 1 || state.selectedLabel != null
    if (!state.offerCameraFilter && !offerLabels) return
    LazyRow(
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
        // Room for the focused chip to grow.
        contentPadding = PaddingValues(horizontal = 6.dp, vertical = 4.dp),
    ) {
        if (state.offerCameraFilter) {
            item {
                TvHistoryChip(
                    "All cameras",
                    selected = selectedCamera == null,
                    onClick = { onSelectCamera(null) },
                    modifier = initialFocus?.let { Modifier.initialFocus(it) } ?: Modifier,
                )
            }
            items(cameras, key = { it.id }) { camera ->
                TvHistoryChip(camera.name, selected = camera.id == selectedCamera, onClick = { onSelectCamera(camera.id) })
            }
        }
        if (offerLabels) {
            if (state.offerCameraFilter) item { Spacer(Modifier.width(20.dp)) }
            item {
                TvHistoryChip(
                    "Everything",
                    selected = state.selectedLabel == null,
                    onClick = { onSelectLabel(null) },
                    modifier = initialFocus?.takeIf { !state.offerCameraFilter }?.let { Modifier.initialFocus(it) } ?: Modifier,
                )
            }
            items(state.labels, key = { "label-$it" }) { label ->
                TvHistoryChip(displayName(label), selected = label == state.selectedLabel, onClick = { onSelectLabel(label) })
            }
        }
    }
}

@Composable
private fun TvHistoryChip(label: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        selected = selected,
        onClick = onClick,
        modifier = modifier,
        shape = SelectableSurfaceDefaults.shape(RoundedCornerShape(50)),
    ) {
        Row(
            Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (selected) {
                Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
            }
            Text(label, style = MaterialTheme.typography.labelLarge)
        }
    }
}

@Composable
private fun TvHistoryMessage(text: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(text, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.titleMedium)
    }
}

@Composable
private fun TvEventCard(
    event: FrigateEvent,
    cameraName: String,
    zone: ZoneId,
    loadImage: suspend (FrigateEvent) -> ByteArray,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(8.dp)),
        // Stays dark when focused, so the grey second line stays readable; the border shows focus.
        colors = ClickableSurfaceDefaults.colors(
            focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
            focusedContentColor = MaterialTheme.colorScheme.onSurface,
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.05f),
        border = ClickableSurfaceDefaults.border(
            focusedBorder = Border(BorderStroke(3.dp, MaterialTheme.colorScheme.primary)),
        ),
    ) {
        Column {
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
                        style = MaterialTheme.typography.labelMedium,
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
}

/**
 * An event's recording, full screen. OK plays and pauses (or replays at the end),
 * Left/Right skip 10 seconds, Back returns. Shows the snapshot if there's no recording.
 */
@Composable
fun TvEventClipViewer(
    event: FrigateEvent,
    cameraName: String,
    active: Boolean,
    loadClip: suspend (FrigateEvent) -> String,
    loadImage: suspend (FrigateEvent) -> ByteArray,
) {
    val clip = rememberEventClip(event, loadClip, active)
    val player = (clip as? EventClip.Ready)?.player
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { focusRequester.requestFocus() }
    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .focusRequester(focusRequester)
            .focusable()
            .onKeyEvent { keyEvent ->
                if (keyEvent.type != KeyEventType.KeyDown || player == null) return@onKeyEvent false
                when (keyEvent.key) {
                    Key.DirectionCenter, Key.Enter, Key.NumPadEnter, Key.MediaPlayPause -> {
                        if (keyEvent.nativeKeyEvent.repeatCount == 0) togglePlayback(player)
                        true
                    }
                    Key.DirectionLeft -> {
                        player.seekTo((player.currentPosition - CLIP_SKIP_MILLIS).coerceAtLeast(0))
                        true
                    }
                    Key.DirectionRight -> {
                        val end = player.duration.takeIf { it != C.TIME_UNSET } ?: Long.MAX_VALUE
                        player.seekTo((player.currentPosition + CLIP_SKIP_MILLIS).coerceAtMost(end))
                        true
                    }
                    else -> false
                }
            },
    ) {
        when (clip) {
            EventClip.Unavailable -> {
                FrigateEventImage(event, loadImage, ContentScale.Fit, Modifier.fillMaxSize())
                Text(
                    if (event.hasClip) "Couldn't play this recording" else "No recording for this one",
                    color = Color.White,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(36.dp)
                        .background(Color.Black.copy(alpha = 0.65f), RoundedCornerShape(8.dp))
                        .padding(horizontal = 14.dp, vertical = 8.dp),
                )
            }
            is EventClip.Ready -> {
                AndroidView(
                    factory = {
                        PlayerView(it).apply {
                            useController = false
                            layoutParams = ViewGroup.LayoutParams(
                                ViewGroup.LayoutParams.MATCH_PARENT,
                                ViewGroup.LayoutParams.MATCH_PARENT,
                            )
                        }
                    },
                    update = { it.player = clip.player },
                    modifier = Modifier.fillMaxSize(),
                )
                ClipControls(clip.player, Modifier.align(Alignment.BottomStart).padding(36.dp))
            }
            EventClip.Loading -> CircularProgressIndicator(Modifier.align(Alignment.Center))
        }
        Column(
            Modifier
                .align(Alignment.TopStart)
                .padding(36.dp)
                .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(8.dp))
                .padding(horizontal = 16.dp, vertical = 10.dp),
        ) {
            Text(eventTitle(event), color = Color.White, style = MaterialTheme.typography.headlineMedium)
            Text(
                eventPlaceAndTime(event, cameraName, ZoneId.systemDefault()),
                color = Color.White.copy(alpha = 0.85f),
                style = MaterialTheme.typography.titleMedium,
            )
        }
    }
}

/** Progress through the clip and the remote buttons that control it. */
@Composable
private fun ClipControls(player: ExoPlayer, modifier: Modifier = Modifier) {
    var position by remember(player) { mutableLongStateOf(0L) }
    var duration by remember(player) { mutableLongStateOf(0L) }
    var playing by remember(player) { mutableStateOf(false) }
    var ended by remember(player) { mutableStateOf(false) }
    LaunchedEffect(player) {
        while (true) {
            position = player.currentPosition
            duration = player.duration.takeIf { it != C.TIME_UNSET } ?: 0L
            playing = player.isPlaying
            ended = player.playbackState == Player.STATE_ENDED
            delay(CLIP_PROGRESS_MILLIS)
        }
    }
    Column(
        modifier
            .width(560.dp)
            .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(8.dp))
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            LinearProgressIndicator(
                progress = { if (duration > 0) (position.toFloat() / duration).coerceIn(0f, 1f) else 0f },
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(14.dp))
            Text("${clipTime(position)} / ${clipTime(duration)}", color = Color.White, style = MaterialTheme.typography.labelLarge)
        }
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(28.dp), verticalAlignment = Alignment.CenterVertically) {
            RemoteHint(
                key = "OK",
                icon = when {
                    ended -> Icons.Default.Replay
                    playing -> Icons.Default.Pause
                    else -> Icons.Default.PlayArrow
                },
                iconDescription = when {
                    ended -> "Replay"
                    playing -> "Pause"
                    else -> "Play"
                },
            )
            RemoteHint(key = "◀ ▶", label = "10 seconds")
        }
    }
}

private fun togglePlayback(player: ExoPlayer) {
    when {
        player.playbackState == Player.STATE_ENDED -> {
            player.seekTo(0)
            player.play()
        }
        player.isPlaying -> player.pause()
        else -> player.play()
    }
}

/** Where [eventId] sits in the history grid, counting each day's heading. */
internal fun historyGridIndex(days: List<Pair<LocalDate, List<FrigateEvent>>>, eventId: String?): Int {
    var index = 0
    for ((_, events) in days) {
        index += 1
        val position = events.indexOfFirst { it.id == eventId }
        if (position >= 0) return index + position
        index += events.size
    }
    return 0
}

/** "0:07", "1:20" */
internal fun clipTime(millis: Long): String {
    val seconds = millis.coerceAtLeast(0) / 1_000
    return "%d:%02d".format(seconds / 60, seconds % 60)
}

private const val HISTORY_COLUMNS = 4
private const val HISTORY_REFRESH_MILLIS = 30_000L
private const val CLIP_SKIP_MILLIS = 10_000L
private const val CLIP_PROGRESS_MILLIS = 250L
