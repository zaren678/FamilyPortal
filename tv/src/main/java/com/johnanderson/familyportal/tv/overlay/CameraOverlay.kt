package com.johnanderson.familyportal.tv.overlay

import android.content.Context
import android.graphics.PixelFormat
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.WindowManager
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Doorbell
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.johnanderson.familyportal.camera.CameraRepository
import com.johnanderson.familyportal.core.CameraConfig
import com.johnanderson.familyportal.tv.ui.FamilyPortalTvTheme
import com.johnanderson.familyportal.tv.ui.RemoteHint
import com.johnanderson.familyportal.ui.LiveCameraPreview

/** What the top-right corner window shows. A doorbell alert always wins over a pin. */
sealed interface OverlayContent {
    val homeAssistantUrl: String

    /** Holds the remote: OK opens the camera full screen and Back dismisses the alert. */
    data class Doorbell(val camera: CameraConfig?, override val homeAssistantUrl: String) : OverlayContent

    /** Never takes the remote, so the app behind stays fully controllable. */
    data class Pinned(val camera: CameraConfig, override val homeAssistantUrl: String) : OverlayContent
}

/**
 * A small camera window in the top-right corner, drawn over whatever is playing.
 * Requires the "Display over other apps" permission. Call from the main thread.
 */
class CameraOverlay(
    private val context: Context,
    private val repository: CameraRepository,
    private val onOpenDoorbell: (CameraConfig) -> Unit,
    private val onDismissDoorbell: () -> Unit,
) {
    private val windowManager = context.getSystemService(WindowManager::class.java)
    private var view: ComposeView? = null
    private var owner: OverlayLifecycleOwner? = null

    fun show(content: OverlayContent) {
        hide()
        if (!Settings.canDrawOverlays(context)) {
            Log.w(TAG, "Skipping camera overlay: \"Display over other apps\" isn't allowed")
            return
        }
        val owner = OverlayLifecycleOwner().apply { resume() }
        val view = ComposeView(context).apply {
            setViewTreeLifecycleOwner(owner)
            setViewTreeSavedStateRegistryOwner(owner)
            setContent {
                FamilyPortalTvTheme {
                    when (content) {
                        is OverlayContent.Doorbell -> DoorbellContent(content, repository, onOpenDoorbell, onDismissDoorbell)
                        is OverlayContent.Pinned -> PinnedContent(content, repository)
                    }
                }
            }
        }
        runCatching { windowManager.addView(view, layoutParams(focusable = content is OverlayContent.Doorbell)) }
            .onSuccess {
                this.view = view
                this.owner = owner
                Log.i(TAG, "Showing ${content::class.simpleName} overlay")
            }
            .onFailure {
                Log.e(TAG, "Unable to show the camera overlay", it)
                owner.destroy()
            }
    }

    fun hide() {
        view?.let { runCatching { windowManager.removeView(it) } }
        owner?.destroy()
        view = null
        owner = null
    }

    private fun layoutParams(focusable: Boolean): WindowManager.LayoutParams {
        val metrics = context.resources.displayMetrics
        val width = (metrics.widthPixels * WIDTH_FRACTION).toInt()
        // Never FLAG_NOT_TOUCHABLE: Android 12+ caps touch-through overlays at 80% opacity.
        var flags = WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
        if (!focusable) flags = flags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        return WindowManager.LayoutParams(
            width,
            width * 9 / 16,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            flags,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.END
            // Same ~5% overscan margins the dashboard uses.
            x = (metrics.widthPixels * 0.05f).toInt()
            y = (metrics.heightPixels * 0.05f).toInt()
            title = "Family Portal camera"
        }
    }

    private companion object {
        const val TAG = "FamilyPortalTvOverlay"
        const val WIDTH_FRACTION = 0.3f
    }
}

@Composable
private fun DoorbellContent(
    content: OverlayContent.Doorbell,
    repository: CameraRepository,
    onOpen: (CameraConfig) -> Unit,
    onDismiss: () -> Unit,
) {
    val camera = content.camera
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { focusRequester.requestFocus() }
    OverlayFrame(
        camera = camera,
        homeAssistantUrl = content.homeAssistantUrl,
        repository = repository,
        borderColor = MaterialTheme.colorScheme.primary,
        modifier = Modifier
            .focusRequester(focusRequester)
            .focusable()
            .onKeyEvent { event ->
                // Consume both halves of the press but act on release, so the app
                // behind never receives an unmatched key-up after the window closes.
                val released = event.type == KeyEventType.KeyUp
                when (event.key) {
                    Key.Back, Key.Escape -> {
                        if (released) onDismiss()
                        true
                    }
                    Key.DirectionCenter, Key.Enter, Key.NumPadEnter -> {
                        if (released && camera != null) onOpen(camera)
                        true
                    }
                    else -> false
                }
            },
    ) {
        OverlayLabel(Icons.Default.Doorbell, "At the door", highlighted = true)
        if (camera != null) {
            Box(
                Modifier
                    .align(Alignment.BottomStart)
                    .padding(10.dp)
                    .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(6.dp))
                    .padding(horizontal = 10.dp, vertical = 4.dp),
            ) {
                RemoteHint(key = "OK", icon = Icons.Default.Fullscreen, iconDescription = "Full screen")
            }
        }
    }
}

@Composable
private fun PinnedContent(content: OverlayContent.Pinned, repository: CameraRepository) {
    OverlayFrame(
        camera = content.camera,
        homeAssistantUrl = content.homeAssistantUrl,
        repository = repository,
        borderColor = MaterialTheme.colorScheme.border,
    ) {
        OverlayLabel(Icons.Default.PushPin, content.camera.name, highlighted = false)
    }
}

@Composable
private fun OverlayFrame(
    camera: CameraConfig?,
    homeAssistantUrl: String,
    repository: CameraRepository,
    borderColor: Color,
    modifier: Modifier = Modifier,
    labels: @Composable BoxScope.() -> Unit,
) {
    val shape = RoundedCornerShape(12.dp)
    Box(
        modifier
            .fillMaxSize()
            .background(Color.Black, shape)
            .border(3.dp, borderColor, shape),
    ) {
        if (camera != null && homeAssistantUrl.isNotBlank()) {
            LiveCameraPreview(
                camera = camera,
                homeAssistantUrl = homeAssistantUrl,
                repository = repository,
                active = true,
                startupDelayMillis = 0L,
                modifier = Modifier.fillMaxSize().padding(3.dp),
            )
        } else {
            Text("No doorbell camera is set up", modifier = Modifier.align(Alignment.Center), color = Color.White)
        }
        labels()
    }
}

@Composable
private fun BoxScope.OverlayLabel(icon: ImageVector, text: String, highlighted: Boolean) {
    val background = if (highlighted) MaterialTheme.colorScheme.primary else Color.Black.copy(alpha = 0.6f)
    val foreground = if (highlighted) MaterialTheme.colorScheme.onPrimary else Color.White
    Row(
        modifier = Modifier
            .align(Alignment.TopStart)
            .padding(10.dp)
            .background(background, RoundedCornerShape(6.dp))
            .padding(horizontal = 10.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = foreground, modifier = Modifier.size(20.dp))
        Text(text, color = foreground, style = MaterialTheme.typography.titleSmall)
    }
}

/** Lifecycle for a window that lives outside any activity, so Compose can run in it. */
private class OverlayLifecycleOwner : SavedStateRegistryOwner {
    private val lifecycleRegistry = LifecycleRegistry(this)
    private val savedStateController = SavedStateRegistryController.create(this)

    override val lifecycle: Lifecycle get() = lifecycleRegistry
    override val savedStateRegistry: SavedStateRegistry get() = savedStateController.savedStateRegistry

    fun resume() {
        savedStateController.performRestore(null)
        lifecycleRegistry.currentState = Lifecycle.State.RESUMED
    }

    fun destroy() {
        lifecycleRegistry.currentState = Lifecycle.State.DESTROYED
    }
}
