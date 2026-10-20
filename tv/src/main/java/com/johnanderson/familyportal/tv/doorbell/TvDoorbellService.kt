package com.johnanderson.familyportal.tv.doorbell

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.getSystemService
import com.johnanderson.familyportal.FamilyPortalApplication
import com.johnanderson.familyportal.core.AppSettings
import com.johnanderson.familyportal.core.CameraConfig
import com.johnanderson.familyportal.core.PortalOverlay
import com.johnanderson.familyportal.ha.DoorbellTransition
import com.johnanderson.familyportal.tv.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Listens for the doorbell sensor while the TV is in use and shows [DoorbellPopup]
 * over whatever is playing. Stops itself when the doorbell isn't set up.
 */
class TvDoorbellService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val graph get() = (application as FamilyPortalApplication).graph
    private lateinit var popup: DoorbellPopup
    private var watchingConfiguration = false

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
        popup = DoorbellPopup(
            context = this,
            repository = graph.cameraRepository,
            onOpen = ::openFullScreen,
            onDismiss = { graph.coordinator.dismissOverlay() },
        )
        listenForDoorbell()
        scope.launch(Dispatchers.IO) { graph.doorbellMonitor.keepDoorbellStreamWarm() }
        showPopupWhileAlerting()
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
        popup.hide()
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

    private fun showPopupWhileAlerting() {
        scope.launch {
            combine(
                graph.coordinator.state.map { it.overlay as? PortalOverlay.Doorbell },
                graph.settingsRepository.settings,
            ) { alert, settings ->
                alert?.let { doorbell ->
                    PopupTarget(
                        camera = settings.cameras.firstOrNull { it.id == doorbell.cameraId },
                        homeAssistantUrl = settings.homeAssistantUrl,
                    )
                }
            }
                .distinctUntilChanged()
                .collect { target ->
                    if (target == null) popup.hide() else popup.show(target.camera, target.homeAssistantUrl)
                }
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
            graph.coordinator.state.map { it.overlay is PortalOverlay.Doorbell },
        ) { configured, alerting -> configured || alerting }
            .distinctUntilChanged()
            .collect { needed ->
                if (!needed) {
                    Log.i(TAG, "Doorbell popup isn't set up; stopping")
                    stopSelf()
                }
            }
    }

    private fun serviceNotification(): Notification = NotificationCompat.Builder(this, CHANNEL_ID)
        .setSmallIcon(R.drawable.ic_launcher)
        .setContentTitle(getString(R.string.app_name))
        .setContentText(getString(R.string.doorbell_service_notification))
        .setOngoing(true)
        .setCategory(NotificationCompat.CATEGORY_SERVICE)
        .build()

    private fun createNotificationChannel() {
        getSystemService<NotificationManager>()?.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                getString(R.string.doorbell_service_channel_name),
                NotificationManager.IMPORTANCE_LOW,
            ),
        )
    }

    private data class PopupTarget(val camera: CameraConfig?, val homeAssistantUrl: String)

    companion object {
        private const val TAG = "FamilyPortalTvDoorbell"
        private const val CHANNEL_ID = "doorbell_listener"
        private const val NOTIFICATION_ID = 21
        private const val DOORBELL_MAX_DURATION_SECONDS = 4 * 60
        private const val TEST_POPUP_SECONDS = 15
        private const val ACTION_TEST_POPUP = "com.johnanderson.familyportal.tv.action.TEST_DOORBELL_POPUP"

        fun start(context: Context) {
            context.startForegroundService(Intent(context, TvDoorbellService::class.java))
        }

        fun showTestPopup(context: Context) {
            context.startForegroundService(
                Intent(context, TvDoorbellService::class.java).setAction(ACTION_TEST_POPUP),
            )
        }
    }
}

/** The popup needs Home Assistant, a doorbell sensor, and a camera marked as the doorbell. */
internal fun doorbellPopupConfigured(settings: AppSettings): Boolean =
    settings.homeAssistantUrl.isNotBlank() &&
        settings.doorbellSensorEntityId.isNotBlank() &&
        settings.cameras.any { it.isDoorbell }
