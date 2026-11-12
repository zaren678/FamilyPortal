package com.johnanderson.familyportal.tv

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.platform.LocalContext
import androidx.core.net.toUri
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.SurfaceDefaults
import com.johnanderson.familyportal.FamilyPortalApplication
import com.johnanderson.familyportal.EventHistoryViewModel
import com.johnanderson.familyportal.PortalViewModel
import com.johnanderson.familyportal.ha.FrigateEvent
import com.johnanderson.familyportal.ha.HomeAssistantAuthActivity
import com.johnanderson.familyportal.tv.overlay.TvOverlayService
import com.johnanderson.familyportal.tv.overlay.doorbellPopupConfigured
import com.johnanderson.familyportal.tv.ui.FamilyPortalTvTheme
import com.johnanderson.familyportal.tv.ui.TvEventClipViewer
import com.johnanderson.familyportal.tv.ui.TvEventHistoryScreen
import com.johnanderson.familyportal.tv.ui.TvPortalScreen
import com.johnanderson.familyportal.tv.ui.rememberIsDreaming
import com.johnanderson.familyportal.tv.ui.rememberTvCameraSelection
import com.johnanderson.familyportal.ui.displayName
import com.johnanderson.familyportal.tv.ui.StatusLevel
import com.johnanderson.familyportal.tv.ui.TvSetupScreen
import com.johnanderson.familyportal.tv.ui.TvStatusInput
import com.johnanderson.familyportal.tv.ui.TvStatusScreen
import com.johnanderson.familyportal.tv.ui.tvStatusItems
import java.time.Instant
import java.time.ZoneId

/**
 * Android TV entry point. The TV keeps its own settings and signs in on its own
 * (Google device code, Home Assistant login page); nothing is copied from a Portal.
 * There is no HOME role or PIN; doorbell alerts are an optional popup over other apps.
 */
class TvMainActivity : ComponentActivity() {

    private val portalViewModel: PortalViewModel by viewModels {
        PortalViewModel.factory((application as FamilyPortalApplication).graph)
    }
    private val eventHistoryViewModel: EventHistoryViewModel by viewModels {
        EventHistoryViewModel.factory((application as FamilyPortalApplication).graph)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            FamilyPortalTvTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    shape = RectangleShape,
                    colors = SurfaceDefaults.colors(containerColor = MaterialTheme.colorScheme.background),
                ) {
                    FamilyPortalTvApp(portalViewModel, eventHistoryViewModel)
                }
            }
        }
    }
}

@Composable
private fun FamilyPortalTvApp(viewModel: PortalViewModel, historyViewModel: EventHistoryViewModel) {
    // Null until loaded, so "not loaded yet" isn't mistaken for "nothing set up".
    val settings = viewModel.settings.collectAsStateWithLifecycle().value ?: return
    val events by viewModel.todayEvents.collectAsStateWithLifecycle()
    val tomorrowEvents by viewModel.tomorrowEvents.collectAsStateWithLifecycle()
    val googleAuthorized by viewModel.googleAuthorized.collectAsStateWithLifecycle()
    val weather by viewModel.weather.collectAsStateWithLifecycle()
    val weatherError by viewModel.weatherError.collectAsStateWithLifecycle()
    val syncState by viewModel.syncState.collectAsStateWithLifecycle()
    val homeAssistantCatalog by viewModel.homeAssistantCatalog.collectAsStateWithLifecycle()
    val homeAssistantError by viewModel.homeAssistantSetupError.collectAsStateWithLifecycle()
    val doorbellConnection by viewModel.graph.homeAssistantClient.connectionState.collectAsStateWithLifecycle()
    val pinnedCameraId = viewModel.appState.collectAsStateWithLifecycle().value.pinnedCameraId
    var showSetup by rememberSaveable { mutableStateOf(false) }
    var showStatus by rememberSaveable { mutableStateOf(false) }
    var showHistory by rememberSaveable { mutableStateOf(false) }
    var playingEvent by remember { mutableStateOf<FrigateEvent?>(null) }
    var lastPlayedEventId by rememberSaveable { mutableStateOf<String?>(null) }
    val cameraSelection = rememberTvCameraSelection()
    val context = LocalContext.current
    val activity = LocalActivity.current
    val popupConfigured = doorbellPopupConfigured(settings)
    LaunchedEffect(popupConfigured) {
        if (popupConfigured) TvOverlayService.start(context)
    }
    // Re-checked on every lifecycle change so it updates after returning from system settings.
    val lifecycleState by LocalLifecycleOwner.current.lifecycle.currentStateFlow.collectAsState()
    val overlayPermissionGranted = remember(lifecycleState) { Settings.canDrawOverlays(context) }
    val statusItems = tvStatusItems(
        TvStatusInput(
            homeAssistantUrl = settings.homeAssistantUrl,
            homeAssistantCatalogLoaded = homeAssistantCatalog != null,
            homeAssistantError = homeAssistantError,
            googleConfigured = viewModel.graph.googleAuthManager.isConfigured,
            googleAuthorized = googleAuthorized,
            calendarSync = syncState,
            weatherEntityId = settings.weatherEntityId,
            weatherName = weather?.name,
            weatherError = weatherError,
            doorbellConfigured = popupConfigured,
            overlayAllowed = overlayPermissionGranted,
            doorbellConnection = doorbellConnection,
        ),
        now = Instant.now(),
        zone = ZoneId.systemDefault(),
    )

    if (showStatus) {
        BackHandler { showStatus = false }
        TvStatusScreen(
            items = statusItems,
            onCheckAgain = {
                viewModel.loadHomeAssistantCatalog()
                viewModel.refreshCalendars()
            },
            onOpenOverlaySettings = { openOverlaySettings(context) },
            onDone = { showStatus = false },
        )
    } else if (showSetup) {
        val calendarSources by viewModel.calendarSources.collectAsStateWithLifecycle()
        val calendarListError by viewModel.calendarListError.collectAsStateWithLifecycle()
        val googleDeviceAuthorization by viewModel.googleDeviceAuthorization.collectAsStateWithLifecycle()
        val googleAuthBusy by viewModel.googleAuthBusy.collectAsStateWithLifecycle()
        val googleAuthError by viewModel.googleAuthError.collectAsStateWithLifecycle()
        val discoveredHomeAssistants by viewModel.discoveredHomeAssistants.collectAsStateWithLifecycle()
        val homeAssistantBusy by viewModel.homeAssistantSetupBusy.collectAsStateWithLifecycle()
        val homeAssistantAuthLauncher = rememberLauncherForActivityResult(
            HomeAssistantAuthActivity.Authorize(),
        ) { callback -> callback?.let(viewModel::completeHomeAssistantAuthorization) }
        BackHandler { showSetup = false }
        TvSetupScreen(
            settings = settings,
            calendarSources = calendarSources,
            calendarListError = calendarListError,
            googleConfigured = viewModel.graph.googleAuthManager.isConfigured,
            googleAuthorized = googleAuthorized,
            googleDeviceAuthorization = googleDeviceAuthorization,
            googleAuthBusy = googleAuthBusy,
            googleAuthError = googleAuthError,
            discoveredHomeAssistants = discoveredHomeAssistants,
            homeAssistantCatalog = homeAssistantCatalog,
            homeAssistantBusy = homeAssistantBusy,
            homeAssistantError = homeAssistantError,
            onGoogleSignIn = viewModel::startGoogleDeviceAuthorization,
            onGoogleSignOut = viewModel::signOutGoogle,
            onRefreshCalendars = viewModel::refreshCalendarList,
            onCalendarSelected = viewModel::selectCalendar,
            onStartDiscovery = viewModel::startHomeAssistantDiscovery,
            onStopDiscovery = viewModel::stopHomeAssistantDiscovery,
            onConnectHomeAssistant = { baseUrl ->
                homeAssistantAuthLauncher.launch(viewModel.authorizeHomeAssistant(baseUrl))
            },
            onAddCamera = { camera -> viewModel.addDiscoveredCamera(camera, doorbell = false) },
            onRemoveCamera = viewModel::deleteCamera,
            onSetCameraAudio = viewModel::setCameraAudio,
            onSelectWeather = viewModel::selectWeatherEntity,
            overlayPermissionGranted = overlayPermissionGranted,
            onSelectDoorbellSensor = viewModel::selectDoorbellSensor,
            onSelectDoorbellCamera = viewModel::setDoorbellCamera,
            onOpenOverlaySettings = { openOverlaySettings(context) },
            onTestDoorbellPopup = { TvOverlayService.showTestPopup(context) },
            onOpenStatus = { showStatus = true },
            onDone = { showSetup = false },
        )
    } else if (showHistory) {
        val history by historyViewModel.state.collectAsStateWithLifecycle()
        val active = lifecycleState.isAtLeast(Lifecycle.State.RESUMED) && !rememberIsDreaming()
        val event = playingEvent
        if (event != null) {
            BackHandler { playingEvent = null }
            TvEventClipViewer(
                event = event,
                cameraName = history.cameraFor(event)?.name ?: displayName(event.camera.name),
                active = active,
                loadClip = historyViewModel::clipUrl,
                loadImage = historyViewModel::image,
            )
        } else {
            BackHandler { showHistory = false }
            TvEventHistoryScreen(
                state = history,
                active = active,
                onRefresh = historyViewModel::refresh,
                onLoadOlder = historyViewModel::loadOlder,
                onSelectCamera = historyViewModel::selectCamera,
                onSelectLabel = historyViewModel::selectLabel,
                loadImage = historyViewModel::image,
                onEventSelected = { selected ->
                    val camera = history.cameraFor(selected)
                    if (selected.endTime == null && camera != null) {
                        // Still happening: watch it live instead.
                        cameraSelection.fullscreenCameraId = camera.id
                        showHistory = false
                    } else {
                        lastPlayedEventId = selected.id
                        playingEvent = selected
                    }
                },
                focusEventId = lastPlayedEventId,
            )
        }
    } else {
        TvPortalScreen(
            settings = settings,
            events = events,
            tomorrowEvents = tomorrowEvents,
            googleAuthorized = googleAuthorized,
            weather = weather,
            cameraRepository = viewModel.graph.cameraRepository,
            statusProblem = statusItems.any { it.level == StatusLevel.PROBLEM },
            pinnedCameraId = pinnedCameraId,
            cameraSelection = cameraSelection,
            onOpenSetup = { showSetup = true },
            onOpenStatus = { showStatus = true },
            onOpenHistory = { cameraId ->
                if (historyViewModel.state.value.selectedCameraId != cameraId) historyViewModel.selectCamera(cameraId)
                lastPlayedEventId = null
                showHistory = true
            },
            onTogglePin = { cameraId ->
                if (cameraId == pinnedCameraId) viewModel.graph.coordinator.unpinCamera()
                else TvOverlayService.pinCamera(context, cameraId)
            },
            onPinAndLeave = { cameraId ->
                TvOverlayService.pinCamera(context, cameraId)
                // Back to whatever was playing; the pin shows once Family Portal is hidden.
                activity?.moveTaskToBack(true)
            },
        )
    }
}

/** Android TV may not accept the per-app form of this screen, so fall back to the full list. */
private fun openOverlaySettings(context: Context) {
    val perApp = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, "package:${context.packageName}".toUri())
    runCatching { context.startActivity(perApp) }
        .recoverCatching { context.startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION)) }
        .onFailure { Log.w("FamilyPortalTv", "No screen for \"Display over other apps\"", it) }
}
