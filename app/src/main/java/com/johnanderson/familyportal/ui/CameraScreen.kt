package com.johnanderson.familyportal.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.johnanderson.familyportal.camera.CameraRepository
import com.johnanderson.familyportal.core.CameraConfig

@Composable
fun CameraScreen(
    cameras: List<CameraConfig>,
    homeAssistantUrl: String,
    repository: CameraRepository,
    previewsActive: Boolean,
    onCameraSelected: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    PrewarmCameraStreams(cameras, homeAssistantUrl, repository, active = previewsActive)
    if (cameras.isEmpty()) {
        Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("Add cameras in Settings")
        }
    } else {
        LazyVerticalGrid(
            columns = GridCells.Fixed(cameraGridColumns(cameras.size)),
            modifier = modifier.fillMaxSize().padding(8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            itemsIndexed(cameras, key = { _, camera -> camera.id }) { index, camera ->
                LiveCameraTile(
                    camera = camera,
                    homeAssistantUrl = homeAssistantUrl,
                    repository = repository,
                    active = previewsActive,
                    startupDelayMillis = index * 600L,
                    onClick = { onCameraSelected(camera.id) },
                )
            }
        }
    }
}

@Composable
private fun LiveCameraTile(
    camera: CameraConfig,
    homeAssistantUrl: String,
    repository: CameraRepository,
    active: Boolean,
    startupDelayMillis: Long,
    onClick: () -> Unit,
) {
    Box(
        Modifier
            .fillMaxWidth()
            .aspectRatio(CAMERA_TILE_ASPECT_RATIO)
            .background(androidx.compose.ui.graphics.Color.Black)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        LiveCameraPreview(
            camera = camera,
            homeAssistantUrl = homeAssistantUrl,
            repository = repository,
            active = active,
            startupDelayMillis = startupDelayMillis,
            modifier = Modifier.fillMaxSize(),
        )
        if (camera.isDoorbell) {
            Text(
                "DOORBELL",
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .background(MaterialTheme.colorScheme.tertiary)
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                color = MaterialTheme.colorScheme.onTertiary,
                style = MaterialTheme.typography.labelSmall,
            )
        }
        Text(
            camera.name,
            modifier = Modifier
                .align(Alignment.BottomStart)
                .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.82f))
                .padding(horizontal = 10.dp, vertical = 6.dp),
            color = MaterialTheme.colorScheme.onSurface,
            style = MaterialTheme.typography.titleSmall,
        )
    }
}

@Composable
fun CameraViewerOverlay(
    cameras: List<CameraConfig>,
    initialCameraId: String,
    homeAssistantUrl: String,
    repository: CameraRepository,
    isDoorbell: Boolean,
    onDismiss: () -> Unit,
) {
    if (cameras.isEmpty()) return

    val initialPage = remember(cameras, initialCameraId) {
        cameras.indexOfFirst { it.id == initialCameraId }.coerceAtLeast(0)
    }
    var isMuted by remember(initialCameraId) { mutableStateOf(true) }
    val pagerState = rememberPagerState(
        initialPage = initialPage,
        pageCount = { cameras.size },
    )
    LaunchedEffect(initialPage) {
        if (pagerState.currentPage != initialPage) pagerState.scrollToPage(initialPage)
    }

    HorizontalPager(
        state = pagerState,
        modifier = Modifier.fillMaxSize(),
        key = { page -> cameras[page].id },
        userScrollEnabled = cameras.size > 1,
    ) { page ->
        val camera = cameras[page]
        val isInitialDoorbell = isDoorbell && camera.id == initialCameraId
        FullScreenCameraPage(
            camera = camera,
            homeAssistantUrl = homeAssistantUrl,
            repository = repository,
            isDoorbell = isInitialDoorbell,
            isMuted = isMuted,
            audioEnabled = !isMuted && page == pagerState.settledPage,
            onMuteChanged = { isMuted = it },
            onDismiss = onDismiss,
        )
    }
}

@Composable
private fun FullScreenCameraPage(
    camera: CameraConfig,
    homeAssistantUrl: String,
    repository: CameraRepository,
    isDoorbell: Boolean,
    isMuted: Boolean,
    audioEnabled: Boolean,
    onMuteChanged: (Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    FullScreenCameraVideo(
        camera = camera,
        homeAssistantUrl = homeAssistantUrl,
        repository = repository,
        audioEnabled = audioEnabled,
    ) { audioAvailable ->
        Text(
            if (isDoorbell) "Person at the door" else camera.name,
            modifier = Modifier.align(Alignment.TopStart).padding(24.dp),
            color = androidx.compose.ui.graphics.Color.White,
            style = MaterialTheme.typography.headlineMedium,
        )
        if (audioAvailable) {
            FilledIconButton(
                onClick = { onMuteChanged(!isMuted) },
                modifier = Modifier.align(Alignment.BottomEnd).padding(20.dp),
            ) {
                Icon(
                    if (isMuted) Icons.AutoMirrored.Filled.VolumeOff else Icons.AutoMirrored.Filled.VolumeUp,
                    if (isMuted) "Unmute" else "Mute",
                )
            }
        }
        FilledIconButton(
            onClick = onDismiss,
            modifier = Modifier.align(Alignment.TopEnd).padding(top = 68.dp, end = 20.dp),
        ) {
            Icon(Icons.Default.Close, "Dismiss")
        }
    }
}

private const val CAMERA_TILE_ASPECT_RATIO = 1.9f
