package com.johnanderson.familyportal.tv

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
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
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.SurfaceDefaults
import com.johnanderson.familyportal.FamilyPortalApplication
import com.johnanderson.familyportal.PortalViewModel
import com.johnanderson.familyportal.core.AppSettings
import com.johnanderson.familyportal.ha.HomeAssistantAuthActivity
import com.johnanderson.familyportal.tv.doorbell.TvDoorbellService
import com.johnanderson.familyportal.tv.doorbell.doorbellPopupConfigured
import com.johnanderson.familyportal.tv.ui.FamilyPortalTvTheme
import com.johnanderson.familyportal.tv.ui.TvPortalScreen
import com.johnanderson.familyportal.tv.ui.TvSetupScreen

/**
 * Android TV entry point. The TV keeps its own settings and signs in on its own
 * (Google device code, Home Assistant login page); nothing is copied from a Portal.
 * There is no HOME role or PIN; doorbell alerts are an optional popup over other apps.
 */
class TvMainActivity : ComponentActivity() {

    private val portalViewModel: PortalViewModel by viewModels {
        PortalViewModel.factory((application as FamilyPortalApplication).graph)
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
                    FamilyPortalTvApp(portalViewModel)
                }
            }
        }
    }
}

@Composable
private fun FamilyPortalTvApp(viewModel: PortalViewModel) {
    // Read settings straight from the repository so "not loaded yet" (null) isn't
    // mistaken for "nothing configured" and flashes the setup prompt.
    val settingsFlow = remember(viewModel) { viewModel.graph.settingsRepository.settings }
    val settings: AppSettings = settingsFlow.collectAsStateWithLifecycle(initialValue = null).value
        ?: return
    val events by viewModel.todayEvents.collectAsStateWithLifecycle()
    val googleAuthorized by viewModel.googleAuthorized.collectAsStateWithLifecycle()
    val weather by viewModel.weather.collectAsStateWithLifecycle()
    var showSetup by rememberSaveable { mutableStateOf(false) }
    val context = LocalContext.current
    val popupConfigured = doorbellPopupConfigured(settings)
    LaunchedEffect(popupConfigured) {
        if (popupConfigured) TvDoorbellService.start(context)
    }

    if (showSetup) {
        val calendarSources by viewModel.calendarSources.collectAsStateWithLifecycle()
        val googleDeviceAuthorization by viewModel.googleDeviceAuthorization.collectAsStateWithLifecycle()
        val googleAuthBusy by viewModel.googleAuthBusy.collectAsStateWithLifecycle()
        val googleAuthError by viewModel.googleAuthError.collectAsStateWithLifecycle()
        val discoveredHomeAssistants by viewModel.discoveredHomeAssistants.collectAsStateWithLifecycle()
        val homeAssistantCatalog by viewModel.homeAssistantCatalog.collectAsStateWithLifecycle()
        val homeAssistantBusy by viewModel.homeAssistantSetupBusy.collectAsStateWithLifecycle()
        val homeAssistantError by viewModel.homeAssistantSetupError.collectAsStateWithLifecycle()
        val homeAssistantAuthLauncher = rememberLauncherForActivityResult(
            HomeAssistantAuthActivity.Authorize(),
        ) { callback -> callback?.let(viewModel::completeHomeAssistantAuthorization) }
        // Re-checked on every lifecycle change so it updates after returning from system settings.
        val lifecycleState by LocalLifecycleOwner.current.lifecycle.currentStateFlow.collectAsState()
        val overlayPermissionGranted = remember(lifecycleState) { Settings.canDrawOverlays(context) }
        BackHandler { showSetup = false }
        TvSetupScreen(
            settings = settings,
            calendarSources = calendarSources,
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
            onConnectHomeAssistant = { baseUrl ->
                homeAssistantAuthLauncher.launch(viewModel.authorizeHomeAssistant(baseUrl))
            },
            onAddCamera = { camera -> viewModel.addDiscoveredCamera(camera, doorbell = false) },
            onRemoveCamera = viewModel::deleteCamera,
            onSelectWeather = viewModel::selectWeatherEntity,
            overlayPermissionGranted = overlayPermissionGranted,
            onSelectDoorbellSensor = viewModel::selectDoorbellSensor,
            onSelectDoorbellCamera = viewModel::setDoorbellCamera,
            onOpenOverlaySettings = { openOverlaySettings(context) },
            onTestDoorbellPopup = { TvDoorbellService.showTestPopup(context) },
            onDone = { showSetup = false },
        )
    } else {
        TvPortalScreen(
            settings = settings,
            events = events,
            googleAuthorized = googleAuthorized,
            weather = weather,
            cameraRepository = viewModel.graph.cameraRepository,
            onOpenSetup = { showSetup = true },
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
