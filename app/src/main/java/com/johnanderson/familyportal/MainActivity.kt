package com.johnanderson.familyportal
import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.MotionEvent
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.johnanderson.familyportal.core.PortalOverlay
import com.johnanderson.familyportal.core.PortalTab
import com.johnanderson.familyportal.ha.HomeAssistantAuthActivity
import com.johnanderson.familyportal.ha.HomeAssistantAuthManager
import com.johnanderson.familyportal.service.DoorbellService
import com.johnanderson.familyportal.service.WakeScheduler
import com.johnanderson.familyportal.ui.CalendarScreen
import com.johnanderson.familyportal.ui.CameraScreen
import com.johnanderson.familyportal.ui.CameraViewerOverlay
import com.johnanderson.familyportal.ui.PinDialog
import com.johnanderson.familyportal.ui.SettingsScreen
import com.johnanderson.familyportal.ui.theme.FamilyPortalTheme

class MainActivity : ComponentActivity() {
    private val portalViewModel: PortalViewModel by viewModels {
        PortalViewModel.factory(
            (application as FamilyPortalApplication).graph,
            WakeScheduler(this),
        )
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        hideSystemUi()
        requestNotificationPermission()
        DoorbellService.start(this)
        handleAuthorizationCallback(intent)
        setContent {
            FamilyPortalTheme {
                FamilyPortalApp(portalViewModel)
            }
        }
    }
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleAuthorizationCallback(intent)
    }
    private fun handleAuthorizationCallback(intent: Intent) {
        val callback = intent.data ?: return
        when {
            portalViewModel.graph.googleAuthManager.isAuthorizationCallback(callback) ->
                portalViewModel.completeGoogleSignIn(callback)
            HomeAssistantAuthManager.isAuthorizationCallback(callback) ->
                portalViewModel.completeHomeAssistantAuthorization(callback)
            else -> return
        }
        intent.data = null
    }
    override fun onResume() {
        super.onResume()
        hideSystemUi()
        portalViewModel.userActivity()
    }
    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) portalViewModel.userActivity()
        return super.dispatchTouchEvent(event)
    }
    fun applyDisplayState(dimmed: Boolean, sleeping: Boolean, activeBrightness: Float, idleBrightness: Float) {
        if (sleeping) window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.attributes = window.attributes.apply {
            screenBrightness = when {
                sleeping -> 0.01f
                dimmed -> idleBrightness.coerceIn(0.05f, 1f)
                else -> activeBrightness.coerceIn(0.05f, 1f)
            }
        }
    }
    private fun hideSystemUi() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }
    private fun requestNotificationPermission() {
        if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 100)
        }
    }}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FamilyPortalApp(viewModel: PortalViewModel) {
    val activity = LocalContext.current as Activity
    val appState by viewModel.appState.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val sources by viewModel.calendarSources.collectAsStateWithLifecycle()
    val events by viewModel.calendarEvents.collectAsStateWithLifecycle()
    val weekStart by viewModel.weekStart.collectAsStateWithLifecycle()
    val syncState by viewModel.syncState.collectAsStateWithLifecycle()
    val googleAuthorized by viewModel.googleAuthorized.collectAsStateWithLifecycle()
    val googleDeviceAuthorization by viewModel.googleDeviceAuthorization.collectAsStateWithLifecycle()
    val googleAuthBusy by viewModel.googleAuthBusy.collectAsStateWithLifecycle()
    val googleAuthError by viewModel.googleAuthError.collectAsStateWithLifecycle()
    val discoveredHomeAssistants by viewModel.discoveredHomeAssistants.collectAsStateWithLifecycle()
    val homeAssistantCatalog by viewModel.homeAssistantCatalog.collectAsStateWithLifecycle()
    val homeAssistantSetupBusy by viewModel.homeAssistantSetupBusy.collectAsStateWithLifecycle()
    val homeAssistantSetupError by viewModel.homeAssistantSetupError.collectAsStateWithLifecycle()
    val navController = rememberNavController()
    val route by navController.currentBackStackEntryAsState()
    var requestPin by remember { mutableStateOf(false) }
    var pinError by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(appState.isDimmed, appState.isSleeping, settings) {
        (activity as MainActivity).applyDisplayState(
            appState.isDimmed,
            appState.isSleeping,
            settings.activeBrightness,
            settings.idleBrightness,
        )
    }
    val selectedTabIndex = when (route?.destination?.route) {
        ROUTE_CAMERAS -> 1
        ROUTE_SETTINGS -> 2
        else -> 0
    }
    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Column(Modifier.fillMaxSize()) {
            PrimaryTabRow(selectedTabIndex = selectedTabIndex) {
                Tab(
                    selected = selectedTabIndex == 0,
                    onClick = {
                        viewModel.selectTab(PortalTab.CALENDAR)
                        navController.navigate(ROUTE_CALENDAR) { launchSingleTop = true }
                    },
                    text = {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(Icons.Default.CalendarMonth, null)
                            Text(
                                "Calendar",
                                style = MaterialTheme.typography.labelLarge.copy(
                                    fontSize = 16.sp,
                                    lineHeight = 20.sp,
                                ),
                            )
                        }
                    },
                )
                Tab(
                    selected = selectedTabIndex == 1,
                    onClick = {
                        viewModel.selectTab(PortalTab.CAMERAS)
                        navController.navigate(ROUTE_CAMERAS) { launchSingleTop = true }
                    },
                    text = {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(Icons.Default.Videocam, null)
                            Text(
                                "Cameras",
                                style = MaterialTheme.typography.labelLarge.copy(
                                    fontSize = 16.sp,
                                    lineHeight = 20.sp,
                                ),
                            )
                        }
                    },
                )
                Tab(
                    selected = selectedTabIndex == 2,
                    onClick = {
                        if (viewModel.graph.settingsRepository.hasPin()) requestPin = true
                        else navController.navigate(ROUTE_SETTINGS) { launchSingleTop = true }
                    },
                    text = {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(Icons.Default.Settings, null)
                            Text(
                                "Settings",
                                style = MaterialTheme.typography.labelLarge.copy(
                                    fontSize = 16.sp,
                                    lineHeight = 20.sp,
                                ),
                            )
                        }
                    },
                )
            }
            NavHost(navController, startDestination = ROUTE_CALENDAR, modifier = Modifier.weight(1f)) {
                composable(ROUTE_CALENDAR) {
                    CalendarScreen(
                        weekStart = weekStart,
                        events = events,
                        syncState = syncState,
                        onPreviousWeek = viewModel::previousWeek,
                        onNextWeek = viewModel::nextWeek,
                        onToday = viewModel::today,
                        onRefresh = viewModel::refreshCalendars,
                    )
                }
                composable(ROUTE_CAMERAS) {
                    CameraScreen(
                        cameras = settings.cameras,
                        homeAssistantUrl = settings.homeAssistantUrl,
                        repository = viewModel.graph.cameraRepository,
                        previewsActive = appState.overlay == null,
                        onCameraSelected = viewModel::openCamera,
                    )
                }
                composable(ROUTE_SETTINGS) {
                    SettingsScreen(
                        settings = settings,
                        calendarSources = sources,
                        googleConfigured = viewModel.graph.googleAuthManager.isConfigured,
                        googleAuthorized = googleAuthorized,
                        googleDeviceAuthorization = googleDeviceAuthorization,
                        googleAuthBusy = googleAuthBusy,
                        googleAuthError = googleAuthError,
                        homeAssistantState = appState.homeAssistantState,
                        discoveredHomeAssistants = discoveredHomeAssistants,
                        homeAssistantCatalog = homeAssistantCatalog,
                        homeAssistantSetupBusy = homeAssistantSetupBusy,
                        homeAssistantSetupError = homeAssistantSetupError,
                        hasPin = viewModel.graph.settingsRepository.hasPin(),
                        onGoogleSignIn = viewModel::startGoogleDeviceAuthorization,
                        onGoogleSignOut = viewModel::signOutGoogle,
                        onRefreshCalendars = viewModel::refreshCalendarList,
                        onCalendarSelected = viewModel::selectCalendar,
                        onStartHomeAssistantDiscovery = viewModel::startHomeAssistantDiscovery,
                        onAuthorizeHomeAssistant = { baseUrl ->
                            activity.startActivity(
                                HomeAssistantAuthActivity.intent(
                                    activity,
                                    viewModel.authorizeHomeAssistant(baseUrl),
                                ),
                            )
                        },
                        onRefreshHomeAssistantEntities = { viewModel.loadHomeAssistantCatalog() },
                        onSelectDoorbellSensor = viewModel::selectDoorbellSensor,
                        onAddDiscoveredCamera = viewModel::addDiscoveredCamera,
                        onSaveHomeAssistant = viewModel::updateHomeAssistant,
                        onSaveCamera = viewModel::saveCamera,
                        onDeleteCamera = viewModel::deleteCamera,
                        onSaveDisplay = viewModel::updateDisplaySettings,
                        onSetPin = viewModel.graph.settingsRepository::setPin,
                    )
                }
            }
        }
        val overlay = appState.overlay
        if (overlay != null) {
            val cameraId = when (overlay) {
                is PortalOverlay.CameraViewer -> overlay.cameraId
                is PortalOverlay.Doorbell -> overlay.cameraId
            }
            settings.cameras.firstOrNull { it.id == cameraId }?.let { camera ->
                CameraViewerOverlay(
                    camera = camera,
                    homeAssistantUrl = settings.homeAssistantUrl,
                    repository = viewModel.graph.cameraRepository,
                    isDoorbell = overlay is PortalOverlay.Doorbell,
                    onDismiss = viewModel::dismissOverlay,
                )
            }
        } else if (appState.isSleeping) {
            Box(Modifier.fillMaxSize().background(Color.Black))
        }
    }
    if (requestPin) {
        PinDialog(
            title = "Enter settings PIN",
            error = pinError,
            onDismiss = { requestPin = false; pinError = null },
            onSubmit = { pin ->
                if (viewModel.graph.settingsRepository.verifyPin(pin)) {
                    requestPin = false
                    pinError = null
                    navController.navigate(ROUTE_SETTINGS) { launchSingleTop = true }
                } else {
                    pinError = "Incorrect PIN"
                }
            },
        )
    }}

private const val ROUTE_CALENDAR = "calendar"
private const val ROUTE_CAMERAS = "cameras"
private const val ROUTE_SETTINGS = "settings"