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
    /**
     * Doorbell sensor transitions with the settings they apply to. Follows settings
     * changes, reconnects with backoff, and refreshes the token after auth failures.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun transitions(): Flow<Pair<AppSettings, DoorbellTransition>> =
        settingsRepository.settings
            .distinctUntilChanged()
            .flatMapLatest { settings ->
                if (settings.homeAssistantUrl.isBlank()) emptyFlow()
                else {
                    var forceRefresh = false
                    flow {
                        val token = authManager.accessToken(forceRefresh)
                        forceRefresh = false
                        if (!token.isNullOrBlank()) {
                            emitAll(
                                client.stateChanges(
                                    settings.homeAssistantUrl,
                                    token,
                                ),
                            )
                        }
                    }
                        .map { settings to it }
                        .retryWhen { cause, attempt ->
                            if (cause is HomeAssistantAuthenticationException) forceRefresh = true
                            Log.w(TAG, "Home Assistant event stream failed; retrying", cause)
                            val backoff = (1_000L shl attempt.coerceAtMost(6).toInt())
                                .coerceAtMost(60_000L)
                            delay(backoff + Random.nextLong(0, 250))
                            true
                        }
                }
            }
            .mapNotNull { (settings, change) ->
                val transition = change.doorbellTransition(settings.doorbellSensorEntityId)
                if (change.entityId == settings.doorbellSensorEntityId) {
                    Log.i(
                        TAG,
                        "Doorbell sensor ${change.oldState} -> ${change.newState}; transition=$transition",
                    )
                }
                transition?.let { settings to it }
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
        const val TAG = "FamilyPortalDoorbell"
        const val DOORBELL_PREWARM_INTERVAL_MILLIS = 30_000L
    }
}
