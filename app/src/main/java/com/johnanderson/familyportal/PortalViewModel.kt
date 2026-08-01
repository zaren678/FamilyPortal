package com.johnanderson.familyportal

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.johnanderson.familyportal.calendar.CalendarEventEntity
import com.johnanderson.familyportal.calendar.CalendarSourceEntity
import com.johnanderson.familyportal.calendar.CalendarSyncState
import com.johnanderson.familyportal.calendar.GoogleDeviceAuthorization
import com.johnanderson.familyportal.core.AppSettings
import com.johnanderson.familyportal.core.CameraConfig
import com.johnanderson.familyportal.core.PortalTab
import com.johnanderson.familyportal.ha.DiscoveredHomeAssistant
import com.johnanderson.familyportal.ha.HomeAssistantCameraChoice
import com.johnanderson.familyportal.ha.HomeAssistantCatalog
import com.johnanderson.familyportal.ha.WeatherSnapshot
import com.johnanderson.familyportal.ha.findLogicalCamera
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters
import java.util.UUID

@OptIn(ExperimentalCoroutinesApi::class)
class PortalViewModel(
    val graph: AppGraph,
) : ViewModel() {
    val appState = graph.coordinator.state
    val settings: StateFlow<AppSettings> = graph.settingsRepository.settings.stateIn(
        viewModelScope,
        SharingStarted.Eagerly,
        AppSettings(),
    )
    val calendarSources: StateFlow<List<CalendarSourceEntity>> =
        graph.calendarRepository.sources.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val syncState: StateFlow<CalendarSyncState> = graph.calendarRepository.syncState
    val googleAuthorized: StateFlow<Boolean> = graph.googleAuthManager.authorized
    private val _googleDeviceAuthorization = MutableStateFlow<GoogleDeviceAuthorization?>(null)
    val googleDeviceAuthorization: StateFlow<GoogleDeviceAuthorization?> =
        _googleDeviceAuthorization.asStateFlow()
    private val _googleAuthBusy = MutableStateFlow(false)
    val googleAuthBusy: StateFlow<Boolean> = _googleAuthBusy.asStateFlow()
    private val _googleAuthError = MutableStateFlow<String?>(null)
    val googleAuthError: StateFlow<String?> = _googleAuthError.asStateFlow()
    val discoveredHomeAssistants: StateFlow<List<DiscoveredHomeAssistant>> = graph.homeAssistantDiscovery.discovered
    private val _homeAssistantCatalog = MutableStateFlow<HomeAssistantCatalog?>(null)
    val homeAssistantCatalog: StateFlow<HomeAssistantCatalog?> = _homeAssistantCatalog.asStateFlow()
    private val _homeAssistantSetupBusy = MutableStateFlow(false)
    val homeAssistantSetupBusy: StateFlow<Boolean> = _homeAssistantSetupBusy.asStateFlow()
    private val _homeAssistantSetupError = MutableStateFlow<String?>(null)
    val homeAssistantSetupError: StateFlow<String?> = _homeAssistantSetupError.asStateFlow()
    private val _weather = MutableStateFlow<WeatherSnapshot?>(null)
    val weather: StateFlow<WeatherSnapshot?> = _weather.asStateFlow()
    private val _weatherError = MutableStateFlow<String?>(null)
    val weatherError: StateFlow<String?> = _weatherError.asStateFlow()

    private val _weekStart = MutableStateFlow(currentSunday())
    val weekStart: StateFlow<LocalDate> = _weekStart.asStateFlow()
    val calendarEvents: StateFlow<List<CalendarEventEntity>> = _weekStart
        .flatMapLatest { date ->
            val zone = ZoneId.systemDefault()
            val start = date.minusDays(7).atStartOfDay(zone).toInstant()
            val end = date.plusDays(14).atStartOfDay(zone).toInstant()
            graph.calendarRepository.events(start, end)
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    private val _today = MutableStateFlow(LocalDate.now())
    val todayEvents: StateFlow<List<CalendarEventEntity>> = _today
        .flatMapLatest { date ->
            val zone = ZoneId.systemDefault()
            graph.calendarRepository.events(
                date.atStartOfDay(zone).toInstant(),
                date.plusDays(1).atStartOfDay(zone).toInstant(),
            )
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    init {
        viewModelScope.launch {
            while (true) {
                _today.value = LocalDate.now()
                delay(60 * 1_000L)
            }
        }
        viewModelScope.launch {
            settings.collect { value ->
                if (value.homeAssistantUrl.isNotBlank() && _homeAssistantCatalog.value == null) {
                    loadHomeAssistantCatalog(value.homeAssistantUrl)
                }
            }
        }
        viewModelScope.launch {
            graph.networkMonitor.available.collect { available ->
                if (available && graph.googleAuthManager.isAuthorized) {
                    graph.calendarRepository.syncNow()
                }
            }
        }
        viewModelScope.launch {
            while (true) {
                if (graph.googleAuthManager.isAuthorized) {
                    graph.calendarRepository.syncNow()
                }
                delay(10 * 60 * 1_000L)
            }
        }
        viewModelScope.launch {
            settings
                .map { Triple(it.homeAssistantUrl, it.homeAssistantRevision, it.weatherEntityId) }
                .distinctUntilChanged()
                .collectLatest { (baseUrl, _, entityId) ->
                    _weather.value = null
                    _weatherError.value = null
                    if (baseUrl.isBlank() || entityId.isBlank()) {
                        return@collectLatest
                    }
                    while (true) {
                        runCatching { graph.homeAssistantWeatherClient.load(baseUrl, entityId) }
                            .onSuccess {
                                _weather.value = it
                                _weatherError.value = null
                            }
                            .onFailure { error ->
                                _weatherError.value = error.message ?: "Weather unavailable"
                            }
                        delay(15 * 60 * 1_000L)
                    }
                }
        }
    }

    fun selectWeek(start: LocalDate) { _weekStart.value = start }
    fun selectTab(tab: PortalTab) = graph.coordinator.selectTab(tab)
    fun openCamera(id: String) = graph.coordinator.openCamera(id)
    fun dismissOverlay() = graph.coordinator.dismissOverlay()

    fun refreshCalendars() = viewModelScope.launch { graph.calendarRepository.syncNow() }
    fun refreshCalendarList() = viewModelScope.launch {
        runCatching { graph.calendarRepository.refreshCalendarList() }
    }
    fun selectCalendar(id: String, selected: Boolean) = viewModelScope.launch {
        graph.calendarRepository.setCalendarSelected(id, selected)
        graph.calendarRepository.syncNow()
    }

    fun startGoogleDeviceAuthorization() = viewModelScope.launch {
        if (_googleAuthBusy.value) return@launch
        _googleAuthBusy.value = true
        _googleAuthError.value = null
        _googleDeviceAuthorization.value = null
        graph.googleAuthManager.authorizeDevice { authorization ->
            _googleDeviceAuthorization.value = authorization
        }.onSuccess {
            _googleDeviceAuthorization.value = null
            graph.calendarRepository.syncNow()
        }.onFailure { error ->
            _googleDeviceAuthorization.value = null
            _googleAuthError.value = error.message ?: "Google authorization failed"
        }
        _googleAuthBusy.value = false
    }

    fun completeGoogleSignIn(callback: android.net.Uri) = viewModelScope.launch {
        val result = graph.googleAuthManager.handleAuthorizationRedirect(callback)
        if (result.isSuccess) graph.calendarRepository.syncNow()
    }

    fun signOutGoogle() {
        graph.googleAuthManager.signOut()
    }

    fun startHomeAssistantDiscovery() = graph.homeAssistantDiscovery.start()

    fun authorizeHomeAssistant(baseUrl: String): android.net.Uri =
        graph.homeAssistantAuthManager.authorizationUri(normalizeHomeAssistantUrl(baseUrl))

    fun completeHomeAssistantAuthorization(callback: android.net.Uri) = viewModelScope.launch {
        _homeAssistantSetupBusy.value = true
        _homeAssistantSetupError.value = null
        graph.homeAssistantAuthManager.completeAuthorization(callback)
            .onSuccess { baseUrl ->
                graph.settingsRepository.update {
                    it.copy(homeAssistantUrl = baseUrl, homeAssistantRevision = it.homeAssistantRevision + 1)
                }
                loadHomeAssistantCatalog(baseUrl)
            }
            .onFailure { _homeAssistantSetupError.value = it.message ?: "Authorization failed" }
        _homeAssistantSetupBusy.value = false
    }

    fun loadHomeAssistantCatalog(baseUrl: String = settings.value.homeAssistantUrl) = viewModelScope.launch {
        if (baseUrl.isBlank()) return@launch
        _homeAssistantSetupBusy.value = true
        _homeAssistantSetupError.value = null
        runCatching { graph.homeAssistantCatalogClient.load(baseUrl) }
            .onSuccess { catalog ->
                _homeAssistantCatalog.value = catalog
                pairDiscoveredCameraStreams(catalog)
            }
            .onFailure { _homeAssistantSetupError.value = it.message ?: "Unable to load entities" }
        _homeAssistantSetupBusy.value = false
    }

    fun selectDoorbellSensor(entityId: String) = viewModelScope.launch {
        graph.settingsRepository.update { it.copy(doorbellSensorEntityId = entityId) }
    }

    fun selectWeatherEntity(entityId: String) = viewModelScope.launch {
        graph.settingsRepository.update { it.copy(weatherEntityId = entityId) }
    }

    fun addDiscoveredCamera(camera: HomeAssistantCameraChoice, doorbell: Boolean) =
        saveCamera(
            existingId = null,
            name = camera.main.name,
            entityId = camera.main.entityId,
            previewEntityId = camera.preview?.entityId.orEmpty(),
            rtspUri = "",
            hasAudio = false,
            doorbell = doorbell,
        )

    private suspend fun pairDiscoveredCameraStreams(catalog: HomeAssistantCatalog) {
        graph.settingsRepository.update { current ->
            val paired = current.cameras.map { camera ->
                val discovered = catalog.findLogicalCamera(
                    entityId = camera.entityId,
                    previewEntityId = camera.previewEntityId,
                    name = camera.name,
                ) ?: return@map camera
                camera.copy(
                    entityId = discovered.main.entityId,
                    previewEntityId = discovered.preview?.entityId ?: camera.previewEntityId,
                )
            }
            current.copy(cameras = paired)
        }
    }

    fun updateHomeAssistant(url: String, token: String, sensor: String) = viewModelScope.launch {
        if (token.isNotBlank()) {
            graph.homeAssistantAuthManager.storeManualToken(normalizeHomeAssistantUrl(url), token.trim())
        }
        graph.settingsRepository.update {
            it.copy(
                homeAssistantUrl = normalizeHomeAssistantUrl(url),
                doorbellSensorEntityId = sensor.trim(),
                homeAssistantRevision = it.homeAssistantRevision + 1,
            )
        }
        loadHomeAssistantCatalog(normalizeHomeAssistantUrl(url))
    }

    fun saveCamera(
        existingId: String?,
        name: String,
        entityId: String,
        previewEntityId: String,
        rtspUri: String,
        hasAudio: Boolean,
        doorbell: Boolean,
    ) =
        viewModelScope.launch {
            val id = existingId ?: UUID.randomUUID().toString()
            if (rtspUri.isNotBlank()) graph.settingsRepository.setCameraRtsp(id, rtspUri.trim())
            graph.settingsRepository.update { current ->
                val updated = CameraConfig(
                    id = id,
                    name = name.trim(),
                    entityId = entityId.trim(),
                    previewEntityId = previewEntityId.trim(),
                    rtspSecretKey = com.johnanderson.familyportal.core.SecureStore.rtspKey(id),
                    hasAudio = hasAudio,
                    isDoorbell = doorbell,
                )
                val updatedEntityIds = setOf(updated.entityId, updated.previewEntityId)
                    .filter(String::isNotBlank)
                    .toSet()
                if (existingId == null && current.cameras.any { camera ->
                        setOf(camera.entityId, camera.previewEntityId)
                            .filter(String::isNotBlank)
                            .any(updatedEntityIds::contains)
                    }
                ) {
                    return@update current
                }
                val normalized = current.cameras.map { camera ->
                    if (doorbell) camera.copy(isDoorbell = false) else camera
                }
                val cameras = if (existingId == null) {
                    normalized + updated
                } else {
                    normalized.map { camera -> if (camera.id == id) updated else camera }
                }
                current.copy(cameras = cameras)
            }
        }

    fun deleteCamera(cameraId: String) = viewModelScope.launch {
        graph.secureStore.remove(com.johnanderson.familyportal.core.SecureStore.rtspKey(cameraId))
        graph.settingsRepository.update { current ->
            current.copy(cameras = current.cameras.filterNot { it.id == cameraId })
        }
    }

    fun updateDoorbellSettings(alertSeconds: Int) =
        viewModelScope.launch {
            graph.settingsRepository.update {
                it.copy(alertDurationSeconds = alertSeconds.coerceIn(10, 120))
            }
        }

    private fun normalizeHomeAssistantUrl(value: String): String {
        val trimmed = value.trim().trimEnd('/')
        return when {
            trimmed.isBlank() -> ""
            trimmed.startsWith("http://") || trimmed.startsWith("https://") -> trimmed
            else -> "http://$trimmed"
        }
    }

    override fun onCleared() {
        graph.homeAssistantDiscovery.stop()
        super.onCleared()
    }

    companion object {
        private fun currentSunday(): LocalDate = LocalDate.now()
            .with(TemporalAdjusters.previousOrSame(DayOfWeek.SUNDAY))

        fun factory(graph: AppGraph): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    PortalViewModel(graph) as T
            }

    }
}
