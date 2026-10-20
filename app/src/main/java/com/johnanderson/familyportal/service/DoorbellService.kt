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
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.getSystemService
import com.johnanderson.familyportal.FamilyPortalApplication
import com.johnanderson.familyportal.MainActivity
import com.johnanderson.familyportal.R
import com.johnanderson.familyportal.core.ConnectionState
import com.johnanderson.familyportal.ha.DoorbellTransition
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class DoorbellService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var alertNotificationTimeout: Job? = null
    private val graph get() = (application as FamilyPortalApplication).graph

    override fun onCreate() {
        super.onCreate()
        createNotificationChannels()
        startForeground(SERVICE_NOTIFICATION_ID, serviceNotification())
        listenForDoorbell()
        keepDoorbellStreamWarm()
        scope.launch {
            graph.homeAssistantClient.connectionState.collect {
                Log.i(TAG, "Home Assistant event stream state=$it")
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
        scope.launch { graph.doorbellMonitor.keepDoorbellStreamWarm() }
    }

    private fun listenForDoorbell() {
        scope.launch {
            graph.doorbellMonitor.transitions().collect { (settings, transition) ->
                when (transition) {
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
        val notificationManager = getSystemService<NotificationManager>()
        val channelImportance = notificationManager
            ?.getNotificationChannel(ALERT_CHANNEL)
            ?.importance
        val fullScreenIntentAllowed = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            notificationManager?.canUseFullScreenIntent()
        } else {
            true
        }
        Log.i(
            TAG,
            "Posting doorbell alert; notificationsEnabled=${notificationManager?.areNotificationsEnabled()} " +
                "channelImportance=$channelImportance fullScreenIntentAllowed=$fullScreenIntentAllowed",
        )
        alertNotificationTimeout?.cancel()
        notificationManager?.notify(ALERT_NOTIFICATION_ID, alertNotification())
        launchDoorbellActivity()
        alertNotificationTimeout = scope.launch {
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

    private fun doorbellActivityIntent(): Intent = Intent(this, MainActivity::class.java)
        .addFlags(
            Intent.FLAG_ACTIVITY_NEW_TASK or
                Intent.FLAG_ACTIVITY_CLEAR_TOP or
                Intent.FLAG_ACTIVITY_SINGLE_TOP,
        )

    private fun launchDoorbellActivity() {
        // Best effort only: since Android 10 the system silently drops activity
        // starts from a background service, so the full-screen-intent
        // notification posted in wakeForAlert is the real bring-to-front path.
        runCatching { startActivity(doorbellActivityIntent()) }
            .onSuccess {
                Log.i(
                    TAG,
                    "Requested foreground activity for doorbell alert " +
                        "(may be dropped while backgrounded; full-screen intent is the fallback)",
                )
            }
            .onFailure { Log.w(TAG, "Unable to request foreground activity for doorbell alert", it) }
    }

    private fun alertNotification(): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this,
            2,
            doorbellActivityIntent(),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, ALERT_CHANNEL)
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle("Person at the door")
            .setContentText("Opening the doorbell camera")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .setFullScreenIntent(pendingIntent, true)
            .build()
    }

    private fun createNotificationChannels() {
        val manager = getSystemService<NotificationManager>() ?: return
        manager.createNotificationChannel(
            NotificationChannel(
                SERVICE_CHANNEL,
                getString(R.string.service_channel_name),
                NotificationManager.IMPORTANCE_LOW,
            ),
        )
        // createNotificationChannel is a no-op for existing channels, so a
        // channel left at lower importance by an earlier install would
        // permanently break full-screen doorbell alerts unless recreated.
        val existing = manager.getNotificationChannel(ALERT_CHANNEL)
        if (alertChannelNeedsRecreate(existing?.importance)) {
            Log.w(
                TAG,
                "Recreating doorbell alert channel; importance was ${existing?.importance}",
            )
            manager.deleteNotificationChannel(ALERT_CHANNEL)
        }
        manager.createNotificationChannel(
            NotificationChannel(
                ALERT_CHANNEL,
                "Doorbell alerts",
                NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                setSound(null, null)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            },
        )
    }

    companion object {
        private const val TAG = "FamilyPortalDoorbell"
        private const val SERVICE_CHANNEL = "doorbell_connection"
        internal const val ALERT_CHANNEL = "doorbell_alerts"
        private const val SERVICE_NOTIFICATION_ID = 11
        private const val ALERT_NOTIFICATION_ID = 12
        private const val DOORBELL_MAX_DURATION_SECONDS = 4 * 60

        fun start(context: Context) {
            val intent = Intent(context, DoorbellService::class.java)
            context.startForegroundService(intent)
        }
    }
}
