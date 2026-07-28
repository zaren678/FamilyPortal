package com.johnanderson.familyportal.core

import kotlinx.serialization.Serializable
import java.time.Instant

enum class PortalTab { CALENDAR, CAMERAS }

sealed interface PortalOverlay {
    data class CameraViewer(val cameraId: String) : PortalOverlay
    data class Doorbell(
        val cameraId: String,
        val detectedAt: Instant,
        val playChime: Boolean,
    ) : PortalOverlay
}

enum class ConnectionState { DISCONNECTED, CONNECTING, CONNECTED, ERROR }

data class AppUiState(
    val selectedTab: PortalTab = PortalTab.CALENDAR,
    val overlay: PortalOverlay? = null,
    val homeAssistantState: ConnectionState = ConnectionState.DISCONNECTED,
    val isDimmed: Boolean = false,
    val isSleeping: Boolean = false,
)

@Serializable
data class CameraConfig(
    val id: String,
    val name: String,
    val entityId: String,
    val previewEntityId: String = "",
    val rtspSecretKey: String,
    val isDoorbell: Boolean = false,
)

@Serializable
data class AppSettings(
    val homeAssistantUrl: String = "",
    val homeAssistantRevision: Int = 0,
    val doorbellSensorEntityId: String = "",
    val cameras: List<CameraConfig> = emptyList(),
    val activeStartMinutes: Int = 7 * 60,
    val activeEndMinutes: Int = 22 * 60,
    val idleDelayMinutes: Int = 5,
    val activeBrightness: Float = 0.85f,
    val idleBrightness: Float = 0.25f,
    val alertDurationSeconds: Int = 30,
)
