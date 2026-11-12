package com.johnanderson.familyportal

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.johnanderson.familyportal.core.CameraConfig
import com.johnanderson.familyportal.ha.FrigateCamera
import com.johnanderson.familyportal.ha.FrigateEvent
import com.johnanderson.familyportal.ha.frigateCameras
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.IOException

/** One of the app's cameras that Frigate watches. */
data class HistoryCamera(val id: String, val name: String, val frigate: FrigateCamera)

data class EventHistoryState(
    /** The app's cameras that Frigate watches, to filter by. Null until known. */
    val cameras: List<HistoryCamera>? = null,
    /** Null for all of them. */
    val selectedCameraId: String? = null,
    /** Cameras Frigate has detected something on, as far as events loaded so far show. */
    val camerasSeen: Set<FrigateCamera> = emptySet(),
    /** What Frigate has seen lately, most often first, to filter by. */
    val labels: List<String> = emptyList(),
    /** Null for everything. */
    val selectedLabel: String? = null,
    /** Newest first. */
    val events: List<FrigateEvent> = emptyList(),
    /** True once the newest events for the current filter have arrived. */
    val loaded: Boolean = false,
    val loadingOlder: Boolean = false,
    val reachedEnd: Boolean = false,
    val error: String? = null,
) {
    fun cameraFor(event: FrigateEvent): HistoryCamera? = cameras?.firstOrNull { it.frigate == event.camera }

    /**
     * Cameras worth filtering by: those with detections, plus the chosen one. Cameras
     * with detection turned off in Frigate stay out of the way until it's turned on.
     */
    val filterCameras: List<HistoryCamera>
        get() = cameras.orEmpty().filter { it.frigate in camerasSeen || it.id == selectedCameraId }

    /** Whether filtering by camera would change anything. */
    val offerCameraFilter: Boolean get() = filterCameras.size > 1 || selectedCameraId != null

    internal val filter get() = selectedCameraId to selectedLabel
}

/**
 * Frigate's recent detections on the app's cameras. Only cameras added to the app
 * are shown, so one left off the Portal on purpose (say, indoors) stays off it.
 */
class EventHistoryViewModel(private val graph: AppGraph) : ViewModel() {
    private val _state = MutableStateFlow(EventHistoryState())
    val state: StateFlow<EventHistoryState> = _state.asStateFlow()
    private val loads = Mutex()
    /** The settings [EventHistoryState.cameras] was worked out from. */
    private var camerasSource: Pair<String, List<CameraConfig>>? = null

    /** Fetches the newest events, keeping older ones already loaded. */
    fun refresh() {
        viewModelScope.launch { loads.withLock { loadNewest() } }
    }

    fun loadOlder() {
        val current = state.value
        if (current.reachedEnd || current.loadingOlder || current.events.isEmpty()) return
        _state.update { it.copy(loadingOlder = true) }
        viewModelScope.launch { loads.withLock { loadOlderPage() } }
    }

    fun selectCamera(cameraId: String?) = changeFilter { it.copy(selectedCameraId = cameraId, selectedLabel = null) }

    fun selectLabel(label: String?) = changeFilter { it.copy(selectedLabel = label) }

    suspend fun image(event: FrigateEvent): ByteArray = graph.frigateClient.image(baseUrl(), event)

    suspend fun clipUrl(event: FrigateEvent): String = graph.frigateClient.clipUrl(baseUrl(), event)

    private fun changeFilter(transform: (EventHistoryState) -> EventHistoryState) {
        _state.update { current ->
            val next = transform(current)
            next.copy(
                // Picking a label keeps the others on offer; another camera has seen other things.
                labels = if (next.selectedCameraId == current.selectedCameraId) current.labels else emptyList(),
                events = emptyList(),
                loaded = false,
                loadingOlder = false,
                reachedEnd = false,
                error = null,
            )
        }
        refresh()
    }

    private suspend fun loadNewest() {
        val filter = state.value.filter
        val result = runCatching {
            val (baseUrl, cameras) = historyCameras()
            val selected = cameras.selectedBy(filter.first)
            if (selected.isEmpty()) emptyList()
            else graph.frigateClient.events(baseUrl, selected, listOfNotNull(filter.second), limit = PAGE_SIZE)
        }.onFailure { Log.e(TAG, "Couldn't load Frigate events", it) }
        _state.update { current ->
            // The filter changed while loading; a fresh load is already queued.
            if (current.filter != filter) return@update current
            result.fold(
                onSuccess = { page ->
                    val events = mergeNewestPage(current.events, page, PAGE_SIZE)
                    current.copy(
                        events = events,
                        camerasSeen = current.camerasSeen + page.map(FrigateEvent::camera),
                        labels = if (filter.second == null) labelsByFrequency(events) else current.labels,
                        loaded = true,
                        reachedEnd = if (current.loaded) current.reachedEnd else page.size < PAGE_SIZE,
                        error = null,
                    )
                },
                onFailure = { current.copy(error = it.message ?: "Couldn't load camera history") },
            )
        }
    }

    private suspend fun loadOlderPage() {
        val current = state.value
        val oldest = current.events.lastOrNull()
        if (oldest == null || current.reachedEnd) {
            _state.update { it.copy(loadingOlder = false) }
            return
        }
        val filter = current.filter
        val result = runCatching {
            val (baseUrl, cameras) = historyCameras()
            graph.frigateClient.events(
                baseUrl,
                cameras.selectedBy(filter.first),
                listOfNotNull(filter.second),
                before = oldest.startTime,
                limit = PAGE_SIZE,
            )
        }.onFailure { Log.e(TAG, "Couldn't load older Frigate events", it) }
        _state.update { latest ->
            if (latest.filter != filter) return@update latest
            result.fold(
                onSuccess = { page ->
                    val events = appendOlderPage(latest.events, page)
                    latest.copy(
                        events = events,
                        camerasSeen = latest.camerasSeen + page.map(FrigateEvent::camera),
                        labels = if (filter.second == null) labelsByFrequency(events) else latest.labels,
                        loadingOlder = false,
                        // Also stop if the page only repeated what was already shown.
                        reachedEnd = page.size < PAGE_SIZE || events.size == latest.events.size,
                        error = null,
                    )
                },
                onFailure = { latest.copy(loadingOlder = false, error = it.message ?: "Couldn't load older events") },
            )
        }
    }

    /** The app's cameras that Frigate watches, worked out again whenever the app's cameras change. */
    private suspend fun historyCameras(): Pair<String, List<HistoryCamera>> {
        val settings = graph.settingsRepository.settings.first()
        val baseUrl = settings.homeAssistantUrl
        if (baseUrl.isBlank()) throw IOException("Home Assistant isn't set up yet")
        val source = baseUrl to settings.cameras
        state.value.cameras?.let { known -> if (source == camerasSource) return baseUrl to known }
        val frigate = graph.homeAssistantCatalogClient.load(baseUrl).frigateCameras(settings.cameras)
        val cameras = settings.cameras.sortedByDescending { it.isDoorbell }.mapNotNull { camera ->
            frigate[camera.id]?.let { HistoryCamera(camera.id, camera.name, it) }
        }
        camerasSource = source
        _state.update { it.copy(cameras = cameras) }
        return baseUrl to cameras
    }

    /** The chosen camera, or all of them if none is chosen or it has since been removed. */
    private fun List<HistoryCamera>.selectedBy(cameraId: String?): List<FrigateCamera> =
        (filter { it.id == cameraId }.ifEmpty { this }).map(HistoryCamera::frigate)

    private suspend fun baseUrl(): String = graph.settingsRepository.settings.first().homeAssistantUrl

    companion object {
        private const val TAG = "FamilyPortalHistory"
        private const val PAGE_SIZE = 40

        fun factory(graph: AppGraph): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T = EventHistoryViewModel(graph) as T
            }
    }
}

/**
 * Puts a fresh first page in front of the older events already loaded, replacing
 * the stretch it covers. A short page is everything there is.
 */
internal fun mergeNewestPage(current: List<FrigateEvent>, page: List<FrigateEvent>, pageSize: Int): List<FrigateEvent> {
    if (page.size < pageSize) return page
    val oldestInPage = page.last().startTime
    val pageIds = page.mapTo(HashSet(), FrigateEvent::id)
    return page + current.filter { it.startTime <= oldestInPage && it.id !in pageIds }
}

/** Adds an older page after the events already loaded, skipping any it repeats. */
internal fun appendOlderPage(current: List<FrigateEvent>, page: List<FrigateEvent>): List<FrigateEvent> {
    val loadedIds = current.mapTo(HashSet(), FrigateEvent::id)
    return current + page.filter { it.id !in loadedIds }
}

internal fun labelsByFrequency(events: List<FrigateEvent>): List<String> =
    events.groupingBy(FrigateEvent::label).eachCount().entries
        .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
        .map { it.key }
