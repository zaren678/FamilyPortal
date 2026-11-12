package com.johnanderson.familyportal.ui

import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import coil3.compose.AsyncImage
import com.johnanderson.familyportal.EventHistoryState
import com.johnanderson.familyportal.ha.FrigateEvent
import kotlinx.coroutines.CancellationException
import java.time.Duration
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** "Person", or "Person · Alex" when Frigate recognized who or what it was. */
fun eventTitle(event: FrigateEvent): String =
    listOfNotNull(displayName(event.label), event.subLabel).joinToString(" · ")

/** Frigate's names for things, like "license_plate", as words: "License plate". */
fun displayName(name: String): String =
    name.replace('_', ' ').trim().replaceFirstChar(Char::uppercaseChar)

/** How long Frigate tracked it: "6s", "1m 20s", "1h 5m". */
fun durationLabel(event: FrigateEvent): String {
    val end = event.endTime ?: return "Now"
    val seconds = Duration.between(event.startTime, end).seconds.coerceAtLeast(1)
    return when {
        seconds < 60 -> "${seconds}s"
        seconds < 3_600 -> "${seconds / 60}m ${seconds % 60}s"
        else -> "${seconds / 3_600}h ${seconds % 3_600 / 60}m"
    }
}

fun dayLabel(day: LocalDate, today: LocalDate): String = when (day) {
    today -> "Today"
    today.minusDays(1) -> "Yesterday"
    else -> day.format(if (day.year == today.year) DAY_HEADER else DAY_HEADER_WITH_YEAR)
}

/** "7:57 PM" */
fun eventClockTime(event: FrigateEvent, zone: ZoneId): String = event.startTime.atZone(zone).format(EVENT_CLOCK)

/** "Doorbell · Today 7:57 PM", for an event shown on its own. */
fun eventPlaceAndTime(event: FrigateEvent, cameraName: String, zone: ZoneId): String =
    "$cameraName · ${dayLabel(event.startTime.atZone(zone).toLocalDate(), LocalDate.now(zone))} " +
        eventClockTime(event, zone)

/** What to say when there are no events: "Nothing detected on Driveway". */
fun EventHistoryState.noEventsMessage(): String =
    cameras?.firstOrNull { it.id == selectedCameraId }?.let { "Nothing detected on ${it.name}" }
        ?: "Nothing detected yet"

/** [events] (newest first) split into days in [zone], newest day first. */
fun eventsByDay(events: List<FrigateEvent>, zone: ZoneId): List<Pair<LocalDate, List<FrigateEvent>>> =
    events.groupBy { it.startTime.atZone(zone).toLocalDate() }.toList()

/** The event's snapshot, once [loadImage] has fetched it. */
@Composable
fun FrigateEventImage(
    event: FrigateEvent,
    loadImage: suspend (FrigateEvent) -> ByteArray,
    contentScale: ContentScale,
    modifier: Modifier = Modifier,
) {
    // Reloaded when the event ends, since Frigate keeps picking a better snapshot until then.
    val image by produceState<ByteArray?>(null, event.id, event.endTime) {
        value = try {
            loadImage(event)
        } catch (e: CancellationException) {
            // Scrolled away before it arrived.
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Couldn't load the picture for ${event.id}", e)
            null
        }
    }
    image?.let { AsyncImage(model = it, contentDescription = null, contentScale = contentScale, modifier = modifier) }
}

sealed interface EventClip {
    data object Loading : EventClip
    data class Ready(val player: ExoPlayer) : EventClip
    /** No recording, or it couldn't be played; show the snapshot instead. */
    data object Unavailable : EventClip
}

/** A player for [event]'s recording, playing while [active] and released when it leaves. */
@Composable
fun rememberEventClip(
    event: FrigateEvent,
    loadClip: suspend (FrigateEvent) -> String,
    active: Boolean,
): EventClip {
    val context = LocalContext.current
    var clipUrl by remember(event.id) { mutableStateOf<String?>(null) }
    var failed by remember(event.id) { mutableStateOf(!event.hasClip) }
    LaunchedEffect(event.id) {
        if (!event.hasClip) return@LaunchedEffect
        runCatching { loadClip(event) }
            .onSuccess { clipUrl = it }
            .onFailure {
                Log.e(TAG, "Couldn't get a link to the clip for ${event.id}", it)
                failed = true
            }
    }
    val player = remember(clipUrl) {
        clipUrl?.let { url ->
            ExoPlayer.Builder(context).build().apply {
                setMediaItem(MediaItem.fromUri(url))
                prepare()
            }
        }
    }
    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                Log.e(TAG, "Couldn't play the clip for ${event.id}", error)
                failed = true
            }
        }
        player?.addListener(listener)
        onDispose {
            player?.removeListener(listener)
            player?.release()
        }
    }
    LaunchedEffect(player, active) {
        player?.playWhenReady = active
    }
    return when {
        failed -> EventClip.Unavailable
        player != null -> EventClip.Ready(player)
        else -> EventClip.Loading
    }
}

private const val TAG = "FamilyPortalHistory"
private val EVENT_CLOCK = DateTimeFormatter.ofPattern("h:mm a")
private val DAY_HEADER = DateTimeFormatter.ofPattern("EEEE, MMMM d")
private val DAY_HEADER_WITH_YEAR = DateTimeFormatter.ofPattern("EEEE, MMMM d, yyyy")
