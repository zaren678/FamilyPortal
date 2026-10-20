package com.johnanderson.familyportal.core

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.Instant

class AppCoordinator(private val scope: CoroutineScope) {
    private val _state = MutableStateFlow(AppUiState())
    val state: StateFlow<AppUiState> = _state.asStateFlow()
    private var alertTimeout: Job? = null

    fun selectTab(tab: PortalTab) {
        cancelAlertTimeout()
        _state.update { it.copy(selectedTab = tab, overlay = null) }
    }

    fun openCamera(cameraId: String) {
        cancelAlertTimeout()
        _state.update { it.copy(overlay = PortalOverlay.CameraViewer(cameraId)) }
    }

    fun startDoorbell(cameraId: String, playChime: Boolean, maxDurationSeconds: Int) {
        cancelAlertTimeout()
        _state.update {
            it.copy(
                overlay = PortalOverlay.Doorbell(cameraId, Instant.now(), playChime),
            )
        }
        alertTimeout = scope.launch {
            delay(maxDurationSeconds * 1_000L)
            dismissOverlay()
        }
    }

    fun finishDoorbell(postRollSeconds: Int) {
        if (_state.value.overlay !is PortalOverlay.Doorbell) return
        cancelAlertTimeout()
        alertTimeout = scope.launch {
            delay(postRollSeconds * 1_000L)
            dismissOverlay()
        }
    }

    fun dismissOverlay() {
        cancelAlertTimeout()
        _state.update { it.copy(overlay = null) }
    }

    private fun cancelAlertTimeout() {
        alertTimeout?.cancel()
        alertTimeout = null
    }

    fun setHomeAssistantState(connectionState: ConnectionState) {
        _state.update { it.copy(homeAssistantState = connectionState) }
    }

}
