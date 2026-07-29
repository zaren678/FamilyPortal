package com.johnanderson.familyportal.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.content.getSystemService
import com.johnanderson.familyportal.FamilyPortalApplication
import com.johnanderson.familyportal.MainActivity
import com.johnanderson.familyportal.R
import com.johnanderson.familyportal.core.ConnectionState
import com.johnanderson.familyportal.ha.DoorbellTransition
import com.johnanderson.familyportal.ha.doorbellTransition
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.retryWhen
import kotlinx.coroutines.launch
import kotlin.random.Random

class DoorbellService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val graph get() = (application as FamilyPortalApplication).graph

    override fun onCreate() {
        super.onCreate()
        createNotificationChannels()
        startForeground(SERVICE_NOTIFICATION_ID, serviceNotification())
        listenForDoorbell()
        keepDoorbellStreamWarm()
        scope.launch {
            graph.homeAssistantClient.connectionState.collect {
                graph.coordinator.setHomeAssistantState(it)
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        scope.cancel()
        graph.coordinator.setHomeAssistantState(ConnectionState.DISCONNECTED)
        super.onDestroy()
    }

    private fun keepDoorbellStreamWarm() {
        scope.launch {
            graph.settingsRepository.settings.collectLatest { settings ->
                val doorbell = settings.cameras.firstOrNull { it.isDoorbell }
                    ?: return@collectLatest
                if (settings.homeAssistantUrl.isBlank()) return@collectLatest
                val alertCamera = doorbell.copy(
                    entityId = doorbell.previewEntityId.ifBlank { doorbell.entityId },
                )
                while (true) {
                    graph.cameraRepository.prewarmStream(settings.homeAssistantUrl, alertCamera)
                    delay(DOORBELL_PREWARM_INTERVAL_MILLIS)
                }
            }
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private fun listenForDoorbell() {
        scope.launch {
            graph.settingsRepository.settings
                .distinctUntilChanged()
                .flatMapLatest { settings ->
                    val token = graph.homeAssistantAuthManager.accessToken()
                    if (settings.homeAssistantUrl.isBlank() || token.isNullOrBlank()) emptyFlow()
                    else graph.homeAssistantClient.stateChanges(settings.homeAssistantUrl, token)
                        .map { settings to it }
                        .retryWhen { _, attempt ->
                            val backoff = (1_000L shl attempt.coerceAtMost(6).toInt()).coerceAtMost(60_000L)
                            delay(backoff + Random.nextLong(0, 250))
                            true
                        }
                }
                .collect { (settings, change) ->
                    when (change.doorbellTransition(settings.doorbellSensorEntityId)) {
                        DoorbellTransition.START -> {
                            val doorbell = settings.cameras.firstOrNull { it.isDoorbell }
                                ?: return@collect
                            graph.coordinator.startDoorbell(
                                cameraId = doorbell.id,
                                playChime = true,
                                maxDurationSeconds = DOORBELL_MAX_DURATION_SECONDS,
                            )
                            wakeForAlert(playChime = true, durationSeconds = settings.alertDurationSeconds)
                        }
                        DoorbellTransition.STOP -> graph.coordinator.finishDoorbell(
                            postRollSeconds = settings.alertDurationSeconds,
                        )
                        null -> Unit
                    }
                }
        }
    }

    @Suppress("DEPRECATION")
    private fun wakeForAlert(playChime: Boolean, durationSeconds: Int) {
        val powerManager = getSystemService<PowerManager>() ?: return
        powerManager.newWakeLock(
            PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
            "FamilyPortal:doorbell",
        ).apply { acquire(10_000L) }

        if (playChime) {
            ToneGenerator(AudioManager.STREAM_NOTIFICATION, 85).apply {
                startTone(ToneGenerator.TONE_PROP_BEEP2, 700)
                scope.launch {
                    delay(1_000)
                    release()
                }
            }
        }
        getSystemService<NotificationManager>()?.notify(ALERT_NOTIFICATION_ID, alertNotification())
        scope.launch {
            delay(durationSeconds * 1_000L)
            getSystemService<NotificationManager>()?.cancel(ALERT_NOTIFICATION_ID)
        }
    }

    private fun serviceNotification(): Notification = NotificationCompat.Builder(this, SERVICE_CHANNEL)
        .setSmallIcon(R.drawable.ic_launcher)
        .setContentTitle(getString(R.string.app_name))
        .setContentText(getString(R.string.service_notification))
        .setOngoing(true)
        .setCategory(NotificationCompat.CATEGORY_SERVICE)
        .build()

    private fun alertNotification(): Notification {
        val intent = Intent(this, MainActivity::class.java).addFlags(
            Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP,
        )
        val pendingIntent = PendingIntent.getActivity(
            this,
            2,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, ALERT_CHANNEL)
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle("Person at the door")
            .setContentText("Opening the doorbell camera")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .setFullScreenIntent(pendingIntent, true)
            .build()
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService<NotificationManager>() ?: return
        manager.createNotificationChannel(
            NotificationChannel(
                SERVICE_CHANNEL,
                getString(R.string.service_channel_name),
                NotificationManager.IMPORTANCE_LOW,
            ),
        )
        manager.createNotificationChannel(
            NotificationChannel(
                ALERT_CHANNEL,
                "Doorbell alerts",
                NotificationManager.IMPORTANCE_HIGH,
            ).apply { setSound(null, null) },
        )
    }

    companion object {
        private const val SERVICE_CHANNEL = "doorbell_connection"
        private const val ALERT_CHANNEL = "doorbell_alerts"
        private const val SERVICE_NOTIFICATION_ID = 11
        private const val ALERT_NOTIFICATION_ID = 12
        private const val DOORBELL_PREWARM_INTERVAL_MILLIS = 30_000L
        private const val DOORBELL_MAX_DURATION_SECONDS = 4 * 60

        fun start(context: Context) {
            val intent = Intent(context, DoorbellService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(intent)
            else context.startService(intent)
        }
    }
}
