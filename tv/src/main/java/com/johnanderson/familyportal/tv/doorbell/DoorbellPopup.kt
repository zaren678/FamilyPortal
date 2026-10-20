package com.johnanderson.familyportal.tv.doorbell

import android.content.Context
import android.graphics.PixelFormat
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.WindowManager
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
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
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.johnanderson.familyportal.camera.CameraRepository
import com.johnanderson.familyportal.core.CameraConfig
import com.johnanderson.familyportal.tv.ui.FamilyPortalTvTheme
import com.johnanderson.familyportal.ui.LiveCameraPreview

/**
 * A small doorbell camera window in the top-right corner, drawn over whatever is
 * playing. While shown it holds the remote: OK calls [onOpen], Back calls [onDismiss],
 * and other keys go unhandled (media keys fall through to the playing app's session).
 * Requires the "Display over other apps" permission. Call from the main thread.
 */
class DoorbellPopup(
    private val context: Context,
    private val repository: CameraRepository,
    private val onOpen: (CameraConfig) -> Unit,
    private val onDismiss: () -> Unit,
) {
    private val windowManager = context.getSystemService(WindowManager::class.java)
    private var view: ComposeView? = null
    private var owner: PopupLifecycleOwner? = null

    fun show(camera: CameraConfig?, homeAssistantUrl: String) {
        hide()
        if (!Settings.canDrawOverlays(context)) {
            Log.w(TAG, "Skipping doorbell popup: \"Display over other apps\" isn't allowed")
            return
        }
        val owner = PopupLifecycleOwner().apply { resume() }
        val view = ComposeView(context).apply {
            setViewTreeLifecycleOwner(owner)
            setViewTreeSavedStateRegistryOwner(owner)
            setContent {
                FamilyPortalTvTheme {
                    DoorbellPopupContent(camera, homeAssistantUrl, repository, onOpen, onDismiss)
                }
            }
        }
        runCatching { windowManager.addView(view, layoutParams()) }
            .onSuccess {
                this.view = view
                this.owner = owner
                Log.i(TAG, "Showing doorbell popup for ${camera?.entityId}")
            }
            .onFailure {
                Log.e(TAG, "Unable to show doorbell popup", it)
                owner.destroy()
            }
    }

    fun hide() {
        view?.let { runCatching { windowManager.removeView(it) } }
        owner?.destroy()
        view = null
        owner = null
    }

    private fun layoutParams(): WindowManager.LayoutParams {
        val metrics = context.resources.displayMetrics
        val width = (metrics.widthPixels * POPUP_WIDTH_FRACTION).toInt()
        return WindowManager.LayoutParams(
            width,
            width * 9 / 16,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            // Focusable so OK and Back reach the popup instead of the app behind it.
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.END
            // Same ~5% overscan margins the dashboard uses.
            x = (metrics.widthPixels * 0.05f).toInt()
            y = (metrics.heightPixels * 0.05f).toInt()
            title = "Family Portal doorbell"
        }
    }

    private companion object {
        const val TAG = "FamilyPortalTvDoorbell"
        const val POPUP_WIDTH_FRACTION = 0.3f
    }
}

@Composable
private fun DoorbellPopupContent(
    camera: CameraConfig?,
    homeAssistantUrl: String,
    repository: CameraRepository,
    onOpen: (CameraConfig) -> Unit,
    onDismiss: () -> Unit,
) {
    val shape = RoundedCornerShape(12.dp)
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { focusRequester.requestFocus() }
    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black, shape)
            .border(3.dp, MaterialTheme.colorScheme.primary, shape)
            .focusRequester(focusRequester)
            .focusable()
            .onKeyEvent { event ->
                // Consume both halves of the press but act on release, so the app
                // behind never receives an unmatched key-up after the popup closes.
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
            Text(
                "No doorbell camera is set up",
                modifier = Modifier.align(Alignment.Center),
                color = Color.White,
            )
        }
        Text(
            "Someone's at the door",
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(10.dp)
                .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(6.dp))
                .padding(horizontal = 10.dp, vertical = 4.dp),
            color = MaterialTheme.colorScheme.onPrimary,
            style = MaterialTheme.typography.titleSmall,
        )
        Text(
            if (camera != null) "OK  Full screen   ·   Back  Dismiss" else "Back  Dismiss",
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(10.dp)
                .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(6.dp))
                .padding(horizontal = 10.dp, vertical = 4.dp),
            color = Color.White,
            style = MaterialTheme.typography.labelLarge,
        )
    }
}

/** Lifecycle for a window that lives outside any activity, so Compose can run in it. */
private class PopupLifecycleOwner : SavedStateRegistryOwner {
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
