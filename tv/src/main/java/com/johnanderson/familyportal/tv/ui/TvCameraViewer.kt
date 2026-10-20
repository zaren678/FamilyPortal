package com.johnanderson.familyportal.tv.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.johnanderson.familyportal.camera.CameraRepository
import com.johnanderson.familyportal.core.CameraConfig
import com.johnanderson.familyportal.ui.FullScreenCameraVideo

/**
 * Full-screen camera on TV. Left/Right switches camera, Down pins it to the corner,
 * OK toggles audio when the camera has it, Back returns. Always starts muted.
 */
@Composable
fun TvCameraViewer(
    cameras: List<CameraConfig>,
    initialCameraId: String,
    homeAssistantUrl: String,
    repository: CameraRepository,
    active: Boolean,
    onPin: (CameraConfig) -> Unit,
    onExit: (lastCameraId: String) -> Unit,
) {
    var index by rememberSaveable(initialCameraId) {
        mutableIntStateOf(cameras.indexOfFirst { it.id == initialCameraId }.coerceAtLeast(0))
    }
    val camera = cameras[index.coerceIn(cameras.indices)]
    var muted by remember(camera.id) { mutableStateOf(true) }
    var audioAvailable by remember(camera.id) { mutableStateOf(false) }
    val focusRequester = remember { FocusRequester() }

    BackHandler { onExit(camera.id) }
    KeepScreenOn()
    LaunchedEffect(Unit) { focusRequester.requestFocus() }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .focusRequester(focusRequester)
            .focusable()
            .onKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onKeyEvent false
                when (event.key) {
                    Key.DirectionLeft, Key.DirectionRight -> {
                        if (cameras.size < 2) return@onKeyEvent false
                        val step = if (event.key == Key.DirectionRight) 1 else -1
                        index = (index + step + cameras.size) % cameras.size
                        true
                    }
                    Key.DirectionDown -> {
                        if (event.nativeKeyEvent.repeatCount == 0) onPin(camera)
                        true
                    }
                    Key.DirectionCenter, Key.Enter, Key.NumPadEnter -> {
                        if (!audioAvailable) return@onKeyEvent false
                        // Holding OK repeats key-downs; only the first press toggles.
                        if (event.nativeKeyEvent.repeatCount == 0) muted = !muted
                        true
                    }
                    else -> false
                }
            },
    ) {
        if (active) {
            key(camera.id) {
                FullScreenCameraVideo(
                    camera = camera,
                    homeAssistantUrl = homeAssistantUrl,
                    repository = repository,
                    audioEnabled = !muted,
                ) { hasAudio ->
                    SideEffect { audioAvailable = hasAudio }
                    Text(
                        camera.name,
                        modifier = Modifier.align(Alignment.TopStart).padding(36.dp),
                        color = Color.White,
                        style = MaterialTheme.typography.headlineMedium,
                    )
                    val hints = viewerHints(canSwitch = cameras.size > 1, audioAvailable = hasAudio)
                    if (hints.isNotEmpty()) {
                        Row(
                            modifier = Modifier
                                .align(Alignment.BottomStart)
                                .padding(36.dp)
                                .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(8.dp))
                                .padding(horizontal = 14.dp, vertical = 8.dp),
                            horizontalArrangement = Arrangement.spacedBy(28.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            hints.forEach { hint ->
                                when (hint) {
                                    ViewerHint.SWITCH_CAMERA -> RemoteHint(key = "◀ ▶", label = "Cameras")
                                    ViewerHint.PIN -> RemoteHint(key = "▼", icon = Icons.Default.PushPin, iconDescription = "Pin")
                                    ViewerHint.TOGGLE_AUDIO -> RemoteHint(
                                        key = "OK",
                                        icon = if (muted) Icons.AutoMirrored.Filled.VolumeOff else Icons.AutoMirrored.Filled.VolumeUp,
                                        iconDescription = if (muted) "Muted" else "Sound on",
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

internal enum class ViewerHint { SWITCH_CAMERA, PIN, TOGGLE_AUDIO }

/** Remote hints worth showing; Back is left out since it always returns. */
internal fun viewerHints(canSwitch: Boolean, audioAvailable: Boolean): List<ViewerHint> =
    listOfNotNull(
        ViewerHint.SWITCH_CAMERA.takeIf { canSwitch },
        ViewerHint.PIN,
        ViewerHint.TOGGLE_AUDIO.takeIf { audioAvailable },
    )

/** Live video is watched deliberately, so only the full-screen view blocks the TV's screensaver. */
@Composable
private fun KeepScreenOn() {
    val view = LocalView.current
    DisposableEffect(view) {
        view.keepScreenOn = true
        onDispose { view.keepScreenOn = false }
    }
}
