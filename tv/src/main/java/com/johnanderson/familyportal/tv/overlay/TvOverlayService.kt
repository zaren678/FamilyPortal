package com.johnanderson.familyportal.tv.overlay

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.core.content.getSystemService
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner
import com.johnanderson.familyportal.FamilyPortalApplication
import com.johnanderson.familyportal.core.AppSettings
import com.johnanderson.familyportal.core.AppUiState
import com.johnanderson.familyportal.core.CameraConfig
import com.johnanderson.familyportal.core.PortalOverlay
import com.johnanderson.familyportal.ha.DoorbellTransition
import com.johnanderson.familyportal.tv.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Runs the top-right camera window: doorbell alerts from the Home Assistant sensor,
 * and a pinned camera that comes back once an alert ends. Stops itself when the
 * doorbell isn't set up and nothing is pinned.
 */
class TvOverlayService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val graph get() = (application as FamilyPortalApplication).graph
    private lateinit var overlay: CameraOverlay
    private val dreaming = MutableStateFlow(false)
    private var watchingConfiguration = false

    private val systemEvents = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                // A pin is for this viewing session; don't bring it back after sleep.
                Intent.ACTION_SCREEN_OFF -> graph.coordinator.unpinCamera()
                Intent.ACTION_DREAMING_STARTED -> dreaming.value = true
                Intent.ACTION_DREAMING_STOPPED -> dreaming.value = false
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            serviceNotification(),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
            } else {
                0
            },
        )
        overlay = CameraOverlay(
            context = this,
            repository = graph.cameraRepository,
            onOpenDoorbell = ::openFullScreen,
            onDismissDoorbell = { graph.coordinator.dismissOverlay() },
        )
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_DREAMING_STARTED)
            addAction(Intent.ACTION_DREAMING_STOPPED)
        }
        ContextCompat.registerReceiver(this, systemEvents, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        listenForDoorbell()
        scope.launch(Dispatchers.IO) { graph.doorbellMonitor.keepDoorbellStreamWarm() }
        showOverlay()
        unpinRemovedCameras()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val testPopup = intent?.action == ACTION_TEST_POPUP
        scope.launch {
            if (testPopup) startTestPopup()
            // Started after any test popup so the check below doesn't stop the
            // service before the popup it was started for appears.
            if (!watchingConfiguration) {
                watchingConfiguration = true
                stopWhenNotNeeded()
            }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        unregisterReceiver(systemEvents)
        overlay.hide()
        scope.cancel()
        super.onDestroy()
    }

    private fun listenForDoorbell() {
        scope.launch(Dispatchers.IO) {
            graph.doorbellMonitor.transitions().collect { (settings, transition) ->
                withContext(Dispatchers.Main) {
                    when (transition) {
                        DoorbellTransition.START -> {
                            val doorbell = settings.cameras.firstOrNull { it.isDoorbell }
                                ?: return@withContext
                            if (getSystemService<PowerManager>()?.isInteractive == false) {
                                Log.i(TAG, "Person detected while the TV is off; not showing the popup")
                                return@withContext
                            }
                            graph.coordinator.startDoorbell(
                                cameraId = doorbell.id,
                                playChime = false,
                                maxDurationSeconds = DOORBELL_MAX_DURATION_SECONDS,
                            )
                        }
                        DoorbellTransition.STOP -> graph.coordinator.finishDoorbell(
                            postRollSeconds = settings.alertDurationSeconds,
                        )
                    }
                }
            }
        }
    }

    private fun showOverlay() {
        // Family Portal's own screens already show every camera, so the pin waits
        // until the app is in the background.
        val appVisible = ProcessLifecycleOwner.get().lifecycle.currentStateFlow
            .map { it.isAtLeast(Lifecycle.State.STARTED) }
        scope.launch {
            combine(
                graph.coordinator.state,
                graph.settingsRepository.settings,
                appVisible,
                dreaming,
            ) { state, settings, visible, isDreaming -> overlayContent(state, settings, visible, isDreaming) }
                .distinctUntilChanged()
                .collect { content -> if (content == null) overlay.hide() else overlay.show(content) }
        }
    }

    private fun unpinRemovedCameras() {
        scope.launch {
            combine(graph.coordinator.state, graph.settingsRepository.settings) { state, settings ->
                state.pinnedCameraId?.takeIf { id -> settings.cameras.none { it.id == id } }
            }.collect { removed -> if (removed != null) graph.coordinator.unpinCamera() }
        }
    }

    private fun openFullScreen(camera: CameraConfig) {
        graph.coordinator.dismissOverlay()
        // Allowed from the background because the app can draw over other apps.
        runCatching { startActivity(DoorbellViewerActivity.intent(this, camera.id)) }
            .onFailure { Log.e(TAG, "Unable to open the doorbell camera full screen", it) }
    }

    private suspend fun startTestPopup() {
        val settings = graph.settingsRepository.settings.first()
        val camera = settings.cameras.firstOrNull { it.isDoorbell } ?: settings.cameras.firstOrNull()
        Log.i(TAG, "Showing a test doorbell popup for ${camera?.entityId}")
        graph.coordinator.startDoorbell(
            cameraId = camera?.id.orEmpty(),
            playChime = false,
            maxDurationSeconds = TEST_POPUP_SECONDS,
        )
    }

    private suspend fun stopWhenNotNeeded() {
        combine(
            graph.settingsRepository.settings.map(::doorbellPopupConfigured),
            graph.coordinator.state,
        ) { configured, state -> configured || state.overlay is PortalOverlay.Doorbell || state.pinnedCameraId != null }
            .distinctUntilChanged()
            .collect { needed ->
                if (!needed) {
                    Log.i(TAG, "No doorbell popup or pinned camera; stopping")
                    stopSelf()
                }
            }
    }

    private fun serviceNotification(): Notification = NotificationCompat.Builder(this, CHANNEL_ID)
        .setSmallIcon(R.drawable.ic_launcher)
        .setContentTitle(getString(R.string.app_name))
        .setContentText(getString(R.string.overlay_service_notification))
        .setOngoing(true)
        .setCategory(NotificationCompat.CATEGORY_SERVICE)
        .build()

    private fun createNotificationChannel() {
        getSystemService<NotificationManager>()?.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                getString(R.string.overlay_service_channel_name),
                NotificationManager.IMPORTANCE_LOW,
            ),
        )
    }

    companion object {
        private const val TAG = "FamilyPortalTvOverlay"
        private const val CHANNEL_ID = "doorbell_listener"
        private const val NOTIFICATION_ID = 21
        private const val DOORBELL_MAX_DURATION_SECONDS = 4 * 60
        private const val TEST_POPUP_SECONDS = 15
        private const val ACTION_TEST_POPUP = "com.johnanderson.familyportal.tv.action.TEST_DOORBELL_POPUP"

        fun start(context: Context) {
            context.startForegroundService(Intent(context, TvOverlayService::class.java))
        }

        fun showTestPopup(context: Context) {
            context.startForegroundService(
                Intent(context, TvOverlayService::class.java).setAction(ACTION_TEST_POPUP),
            )
        }

        /** Keeps [cameraId] in the corner window whenever Family Portal isn't on screen. */
        fun pinCamera(context: Context, cameraId: String) {
            // Pin before starting so the service sees it and doesn't stop itself.
            (context.applicationContext as FamilyPortalApplication).graph.coordinator.pinCamera(cameraId)
            start(context)
        }
    }
}

/** The popup needs Home Assistant, a doorbell sensor, and a camera marked as the doorbell. */
internal fun doorbellPopupConfigured(settings: AppSettings): Boolean =
    settings.homeAssistantUrl.isNotBlank() &&
        settings.doorbellSensorEntityId.isNotBlank() &&
        settings.cameras.any { it.isDoorbell }

/** A doorbell alert takes the corner; otherwise the pin, unless Family Portal or the screensaver is showing. */
internal fun overlayContent(
    state: AppUiState,
    settings: AppSettings,
    appVisible: Boolean,
    dreaming: Boolean,
): OverlayContent? {
    (state.overlay as? PortalOverlay.Doorbell)?.let { alert ->
        return OverlayContent.Doorbell(
            camera = settings.cameras.firstOrNull { it.id == alert.cameraId },
            homeAssistantUrl = settings.homeAssistantUrl,
        )
    }
    if (appVisible || dreaming) return null
    val pinned = settings.cameras.firstOrNull { it.id == state.pinnedCameraId } ?: return null
    return OverlayContent.Pinned(pinned, settings.homeAssistantUrl)
}
