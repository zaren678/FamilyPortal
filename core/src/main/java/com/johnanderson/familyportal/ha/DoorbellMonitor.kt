package com.johnanderson.familyportal.ha

import android.util.Log
import com.johnanderson.familyportal.camera.CameraRepository
import com.johnanderson.familyportal.core.AppSettings
import com.johnanderson.familyportal.core.SettingsRepository
import com.johnanderson.familyportal.core.previewStream
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.retryWhen
import java.io.IOException
import java.time.Duration
import java.time.Instant
import kotlin.random.Random

/**
 * Watches Home Assistant for the configured doorbell sensor. Shared by the Portal's
 * alert service and the TV's popup service.
 */
class DoorbellMonitor(
    private val settingsRepository: SettingsRepository,
    private val authManager: HomeAssistantAuthManager,
    private val client: HomeAssistantClient,
    private val cameraRepository: CameraRepository,
) {
    /** Doorbell sensor transitions with the settings they apply to. Follows settings changes. */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun transitions(): Flow<Pair<AppSettings, DoorbellTransition>> =
        settingsRepository.settings
            .distinctUntilChanged()
            .flatMapLatest { settings ->
                if (settings.homeAssistantUrl.isBlank()) {
                    emptyFlow()
                } else {
                    watchDoorbellSensor(
                        sensorEntityId = settings.doorbellSensorEntityId,
                        accessToken = authManager::accessToken,
                        entityStates = { token ->
                            client.entityStates(settings.homeAssistantUrl, token, settings.doorbellSensorEntityId)
                        },
                    ).map { settings to it }
                }
            }

    /** Keeps the doorbell camera's preview stream warm so alerts show video quickly. Never returns. */
    suspend fun keepDoorbellStreamWarm() {
        settingsRepository.settings.collectLatest { settings ->
            val doorbell = settings.cameras.firstOrNull { it.isDoorbell }
                ?: return@collectLatest
            if (settings.homeAssistantUrl.isBlank()) return@collectLatest
            val alertCamera = doorbell.previewStream()
            while (true) {
                cameraRepository.prewarmHomeAssistantStream(
                    settings.homeAssistantUrl,
                    alertCamera,
                )
                delay(DOORBELL_PREWARM_INTERVAL_MILLIS)
            }
        }
    }

    private companion object {
        const val DOORBELL_PREWARM_INTERVAL_MILLIS = 30_000L
    }
}

private const val TAG = "FamilyPortalDoorbell"

/** Someone who arrived this recently while disconnected still gets an alert on reconnecting. */
private val CATCH_UP_WINDOW: Duration = Duration.ofMinutes(2)

/**
 * Transitions of [sensorEntityId] for as long as this is collected. Reconnects after
 * any failure (quickly once a connection has worked) and refreshes the token after
 * auth failures. Each connection starts with the sensor's current state, so a visit
 * that began during a dropped connection or a restart isn't missed.
 */
internal fun watchDoorbellSensor(
    sensorEntityId: String,
    accessToken: suspend (forceRefresh: Boolean) -> String?,
    entityStates: (token: String) -> Flow<HomeAssistantEntityState>,
    now: () -> Instant = Instant::now,
): Flow<DoorbellTransition> {
    var known: HomeAssistantEntityState? = null
    var failures = 0
    var forceRefresh = false
    return flow {
        val token = accessToken(forceRefresh)
        forceRefresh = false
        if (token.isNullOrBlank()) throw IOException("Not signed in to Home Assistant")
        var connected = false
        emitAll(
            entityStates(token).mapNotNull { current ->
                val transition = if (connected) {
                    doorbellTransition(known?.state, current.state).also {
                        Log.i(TAG, "Doorbell sensor ${known?.state} -> ${current.state}; transition=$it")
                    }
                } else {
                    connected = true
                    failures = 0
                    catchUpTransition(known, current, now()).also {
                        Log.i(
                            TAG,
                            "Watching $sensorEntityId: ${current.state} since ${current.lastChanged}; transition=$it",
                        )
                    }
                }
                known = current
                transition
            },
        )
        throw IOException("Home Assistant event stream ended")
    }.retryWhen { cause, _ ->
        if (cause is HomeAssistantAuthenticationException) forceRefresh = true
        Log.w(TAG, "Home Assistant event stream failed; retrying", cause)
        delay(reconnectDelayMillis(failures++))
        true
    }
}

/**
 * What the sensor's state on connecting means, given what it was last time.
 * Someone who arrived within [CATCH_UP_WINDOW] gets an alert; a visit that ended
 * while disconnected lets the alert wind down.
 */
internal fun catchUpTransition(
    known: HomeAssistantEntityState?,
    current: HomeAssistantEntityState,
    now: Instant,
): DoorbellTransition? {
    val wasOn = known?.state == "on"
    val isOn = current.state == "on"
    return when {
        isOn && !wasOn -> DoorbellTransition.START.takeIf {
            current.lastChanged?.let { Duration.between(it, now) <= CATCH_UP_WINDOW } == true
        }
        wasOn && !isOn -> DoorbellTransition.STOP
        else -> null
    }
}

/** 1, 2, 4, 8, then every 15 seconds, with jitter. Home Assistant is usually on the LAN, so retrying is cheap. */
internal fun reconnectDelayMillis(failures: Int): Long =
    (1_000L shl failures.coerceIn(0, 4)).coerceAtMost(15_000L) + Random.nextLong(0, 250)
