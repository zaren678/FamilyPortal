package com.johnanderson.familyportal.tv.doorbell

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.SurfaceDefaults
import com.johnanderson.familyportal.FamilyPortalApplication
import com.johnanderson.familyportal.tv.ui.FamilyPortalTvTheme
import com.johnanderson.familyportal.tv.ui.TvCameraViewer

/**
 * Full-screen camera opened from the doorbell popup. It runs in its own task, so
 * Back finishes it and returns to whatever was playing before the popup appeared.
 */
class DoorbellViewerActivity : ComponentActivity() {
    private val cameraId = mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        cameraId.value = intent.getStringExtra(EXTRA_CAMERA_ID)
        val graph = (application as FamilyPortalApplication).graph
        setContent {
            FamilyPortalTvTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    shape = RectangleShape,
                    colors = SurfaceDefaults.colors(containerColor = MaterialTheme.colorScheme.background),
                ) {
                    val settingsFlow = remember { graph.settingsRepository.settings }
                    val settings = settingsFlow.collectAsStateWithLifecycle(initialValue = null).value
                        ?: return@Surface
                    val lifecycleState by LocalLifecycleOwner.current.lifecycle.currentStateFlow.collectAsState()
                    val cameras = remember(settings.cameras) { settings.cameras.sortedByDescending { it.isDoorbell } }
                    val initialCameraId = cameraId.value?.takeIf { id -> cameras.any { it.id == id } }
                        ?: cameras.firstOrNull()?.id
                    if (initialCameraId == null) {
                        LaunchedEffect(Unit) { finish() }
                        return@Surface
                    }
                    TvCameraViewer(
                        cameras = cameras,
                        initialCameraId = initialCameraId,
                        homeAssistantUrl = settings.homeAssistantUrl,
                        repository = graph.cameraRepository,
                        active = lifecycleState.isAtLeast(Lifecycle.State.RESUMED),
                        onExit = { finish() },
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        cameraId.value = intent.getStringExtra(EXTRA_CAMERA_ID)
    }

    companion object {
        private const val EXTRA_CAMERA_ID = "camera_id"

        fun intent(context: Context, cameraId: String): Intent =
            Intent(context, DoorbellViewerActivity::class.java)
                .putExtra(EXTRA_CAMERA_ID, cameraId)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
}
