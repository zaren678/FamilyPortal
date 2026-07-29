package com.johnanderson.familyportal.ui

import android.util.Log
import android.view.ViewGroup
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import androidx.compose.material.icons.filled.VideocamOff
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import coil3.compose.AsyncImage
import com.johnanderson.familyportal.camera.CameraRepository
import com.johnanderson.familyportal.core.CameraConfig
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

@Composable
fun CameraScreen(
    cameras: List<CameraConfig>,
    homeAssistantUrl: String,
    repository: CameraRepository,
    previewsActive: Boolean,
    onCameraSelected: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    LaunchedEffect(cameras, homeAssistantUrl, previewsActive) {
        if (!previewsActive || homeAssistantUrl.isBlank()) return@LaunchedEffect
        while (true) {
            coroutineScope {
                cameras.forEach { camera ->
                    val previewCamera = camera.copy(
                        entityId = camera.previewEntityId.ifBlank { camera.entityId },
                    )
                    launch {
                        repository.prewarmHomeAssistantStream(
                            homeAssistantUrl,
                            previewCamera,
                        )
                    }
                }
            }
            delay(30_000L)
        }
    }
    val gridColumns = when {
        cameras.size <= 1 -> 1
        cameras.size <= 4 -> 2
        else -> 3
    }
    if (cameras.isEmpty()) {
        Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("Add cameras in Settings")
        }
    } else {
        LazyVerticalGrid(
            columns = GridCells.Fixed(gridColumns),
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
    val context = LocalContext.current
    val gridCamera = remember(camera) {
        camera.copy(entityId = camera.previewEntityId.ifBlank { camera.entityId })
    }
    var streamUri by remember(gridCamera.entityId, active) { mutableStateOf<String?>(null) }
    var attempt by remember(gridCamera.entityId, active) { mutableStateOf(0) }
    var ready by remember(gridCamera.entityId, active) { mutableStateOf(false) }
    LaunchedEffect(gridCamera.entityId, homeAssistantUrl, active, attempt) {
        if (!active || homeAssistantUrl.isBlank()) return@LaunchedEffect
        delay(if (attempt == 0) startupDelayMillis else (attempt * 1_000L).coerceAtMost(10_000L))
        runCatching {
            withTimeout(30_000L) {
                repository.homeAssistantStreamUri(
                    homeAssistantUrl,
                    gridCamera,
                    forceRefresh = attempt > 0,
                )
            }
        }.onSuccess { streamUri = it }
            .onFailure {
                Log.e("FamilyPortalCamera", "Grid stream failed for ${gridCamera.entityId}", it)
                attempt += 1
            }
    }
    val player = remember(gridCamera.entityId, streamUri, active) {
        if (!active) null else streamUri?.let { uri ->
            ExoPlayer.Builder(context).build().apply {
                volume = 0f
                setMediaItem(MediaItem.fromUri(uri))
                playWhenReady = true
                prepare()
            }
        }
    }
    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_READY) ready = true
            }
            override fun onPlayerError(error: PlaybackException) {
                Log.e("FamilyPortalCamera", "Grid playback failed for ${gridCamera.entityId}", error)
                repository.invalidateHomeAssistantStream(homeAssistantUrl, gridCamera)
                ready = false
                streamUri = null
                attempt += 1
            }
        }
        player?.addListener(listener)
        onDispose {
            player?.removeListener(listener)
            player?.release()
        }
    }
    LaunchedEffect(player) {
        if (player == null) return@LaunchedEffect
        delay(45_000L)
        if (!ready) {
            repository.invalidateHomeAssistantStream(homeAssistantUrl, gridCamera)
            streamUri = null
            attempt += 1
        }
    }
    Box(
        Modifier
            .fillMaxWidth()
            .aspectRatio(CAMERA_TILE_ASPECT_RATIO)
            .background(androidx.compose.ui.graphics.Color.Black)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        if (player != null) {
            AndroidView(
                factory = {
                    PlayerView(it).apply {
                        this.player = player
                        useController = false
                        layoutParams = ViewGroup.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT,
                        )
                    }
                },
                update = { it.player = player },
                modifier = Modifier.fillMaxSize(),
            )
        }
        if (!ready) CircularProgressIndicator()
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
    val context = LocalContext.current
    val previewCamera = remember(camera) {
        camera.copy(entityId = camera.previewEntityId.ifBlank { camera.entityId })
    }
    var streamUri by remember(camera.id, previewCamera.entityId, homeAssistantUrl) { mutableStateOf<String?>(null) }
    var streamAttempt by remember(camera.id, previewCamera.entityId, homeAssistantUrl) { mutableStateOf(0) }
    var playbackReady by remember(camera.id, previewCamera.entityId, homeAssistantUrl) { mutableStateOf(false) }
    var playbackFailed by remember(camera.id, previewCamera.entityId, homeAssistantUrl) { mutableStateOf(false) }
    var fallbackSnapshot by remember(camera.id, camera.entityId, homeAssistantUrl) { mutableStateOf<ByteArray?>(null) }
    fun retryStream() {
        if (streamAttempt >= MAX_STREAM_ATTEMPTS) {
            playbackFailed = true
            return
        }
        repository.invalidateHomeAssistantStream(homeAssistantUrl, previewCamera)
        streamUri = null
        playbackReady = false
        streamAttempt += 1
    }
    LaunchedEffect(camera.id, previewCamera.entityId, homeAssistantUrl, streamAttempt) {
        val result = runCatching {
            withTimeout(30_000L) {
                repository.homeAssistantStreamUri(
                    homeAssistantUrl,
                    previewCamera,
                    forceRefresh = streamAttempt > 0,
                )
            }
        }
        result.onSuccess { streamUri = it }
            .onFailure {
                Log.e("FamilyPortalCamera", "Preview stream request failed for ${previewCamera.entityId}", it)
                delay(1_000L)
                retryStream()
            }
    }
    val player = remember(camera.id, previewCamera.entityId, streamUri) {
        streamUri?.let { uri ->
            ExoPlayer.Builder(context)
                .setAudioAttributes(CAMERA_AUDIO_ATTRIBUTES, false)
                .build()
                .apply {
                    volume = 0f
                    setMediaItem(MediaItem.fromUri(uri))
                    playWhenReady = true
                    prepare()
                }
        }
    }
    var qualityStreamUri by remember(camera.id, camera.entityId, homeAssistantUrl) { mutableStateOf<String?>(null) }
    var qualityAttempt by remember(camera.id, camera.entityId, homeAssistantUrl) { mutableStateOf(0) }
    var qualityReady by remember(camera.id, camera.entityId, homeAssistantUrl) { mutableStateOf(false) }
    var previewIsQuality by remember(camera.id, previewCamera.entityId, camera.entityId, homeAssistantUrl) {
        mutableStateOf(false)
    }
    var startQuality by remember(camera.id, previewCamera.entityId, camera.entityId, homeAssistantUrl) {
        mutableStateOf(false)
    }
    LaunchedEffect(camera.id, previewCamera.entityId, camera.entityId, homeAssistantUrl) {
        // Do not let a broken preview block an otherwise healthy main stream.
        delay(QUALITY_FALLBACK_DELAY_MILLIS)
        startQuality = true
    }
    LaunchedEffect(startQuality, camera.id, camera.entityId, homeAssistantUrl, qualityAttempt) {
        if (!startQuality) return@LaunchedEffect
        if (qualityAttempt > 0) delay(1_000L * qualityAttempt)
        runCatching {
            withTimeout(30_000L) {
                repository.preferredStreamUri(
                    homeAssistantUrl,
                    camera,
                    forceRefresh = qualityAttempt > 0,
                )
            }
        }.onSuccess { uri ->
            if (uri == streamUri) previewIsQuality = true else qualityStreamUri = uri
        }.onFailure {
            Log.e("FamilyPortalCamera", "Quality stream request failed for ${camera.entityId}", it)
            if (qualityAttempt < MAX_QUALITY_STREAM_ATTEMPTS) qualityAttempt += 1
        }
    }
    val qualityPlayer = remember(camera.id, camera.entityId, qualityStreamUri, qualityAttempt) {
        qualityStreamUri?.let { uri ->
            ExoPlayer.Builder(context)
                .setAudioAttributes(CAMERA_AUDIO_ATTRIBUTES, false)
                .build()
                .apply {
                    volume = 0f
                    setMediaItem(MediaItem.fromUri(uri))
                    playWhenReady = true
                    prepare()
                }
        }
    }
    LaunchedEffect(player, qualityPlayer, qualityReady, previewIsQuality, audioEnabled, camera.hasAudio) {
        val previewAudio = audioEnabled && camera.hasAudio && previewIsQuality
        val qualityAudio = audioEnabled && camera.hasAudio && qualityReady
        player?.setAudioAttributes(CAMERA_AUDIO_ATTRIBUTES, previewAudio)
        player?.volume = if (previewAudio) 1f else 0f
        qualityPlayer?.setAudioAttributes(CAMERA_AUDIO_ATTRIBUTES, qualityAudio)
        qualityPlayer?.volume = if (qualityAudio) 1f else 0f
    }
    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onRenderedFirstFrame() {
                playbackReady = true
                playbackFailed = false
                startQuality = true
            }
            override fun onPlayerError(error: PlaybackException) {
                Log.e("FamilyPortalCamera", "Preview playback failed for ${previewCamera.entityId}", error)
                if (!qualityReady) retryStream()
            }
        }
        player?.addListener(listener)
        onDispose {
            player?.removeListener(listener)
            player?.release()
        }
    }
    DisposableEffect(qualityPlayer) {
        val listener = object : Player.Listener {
            override fun onRenderedFirstFrame() {
                qualityReady = true
            }

            override fun onPlayerError(error: PlaybackException) {
                Log.e("FamilyPortalCamera", "Quality playback failed for ${camera.entityId}", error)
                if (qualityReady) {
                    player?.prepare()
                    player?.play()
                }
                qualityReady = false
                qualityStreamUri = null
                if (qualityAttempt < MAX_QUALITY_STREAM_ATTEMPTS) qualityAttempt += 1
            }
        }
        qualityPlayer?.addListener(listener)
        onDispose {
            qualityPlayer?.removeListener(listener)
            qualityPlayer?.release()
        }
    }
    LaunchedEffect(qualityReady) {
        if (qualityReady) player?.stop()
    }
    LaunchedEffect(player, qualityReady) {
        if (player == null || qualityReady) return@LaunchedEffect
        delay(45_000L)
        if (!playbackReady && !qualityReady) retryStream()
    }
    LaunchedEffect(camera.id, playbackFailed, qualityReady, homeAssistantUrl) {
        if (!playbackFailed || qualityReady) return@LaunchedEffect
        while (true) {
            val result = runCatching { repository.snapshot(homeAssistantUrl, camera) }
            result.onSuccess { fallbackSnapshot = it }
            delay(if (result.isSuccess) 2_000L else 30_000L)
        }
    }
    Box(Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Color.Black)) {
        if (qualityPlayer != null) {
            AndroidView(
                factory = {
                    PlayerView(it).apply {
                        this.player = qualityPlayer
                        useController = false
                        layoutParams = ViewGroup.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT,
                        )
                    }
                },
                update = { it.player = qualityPlayer },
                modifier = Modifier.fillMaxSize(),
            )
        }
        if (!qualityReady && !playbackFailed && player != null) {
            AndroidView(
                factory = {
                    PlayerView(it).apply {
                        this.player = player
                        useController = false
                        layoutParams = ViewGroup.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT,
                        )
                    }
                },
                update = { it.player = player },
                modifier = Modifier.fillMaxSize(),
            )
        } else if (!qualityReady && playbackFailed) {
            fallbackSnapshot?.let {
                AsyncImage(
                    model = it,
                    contentDescription = camera.name,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize(),
                )
            }
            Row(
                Modifier.align(Alignment.BottomCenter).padding(24.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (fallbackSnapshot == null) {
                    Icon(Icons.Default.VideocamOff, null, tint = androidx.compose.ui.graphics.Color.White)
                }
                Text(
                    if (fallbackSnapshot == null) " Live video unavailable" else "Snapshot preview",
                    color = androidx.compose.ui.graphics.Color.White,
                )
            }
        }
        if (!playbackFailed && !playbackReady && !qualityReady) {
            CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
        }
        Text(
            if (isDoorbell) "Person at the door" else camera.name,
            modifier = Modifier.align(Alignment.TopStart).padding(24.dp),
            color = androidx.compose.ui.graphics.Color.White,
            style = MaterialTheme.typography.headlineMedium,
        )
        val audioAvailable = (!playbackFailed || qualityReady) &&
            (player != null || qualityPlayer != null) && camera.hasAudio
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

private val CAMERA_AUDIO_ATTRIBUTES = AudioAttributes.Builder()
    .setUsage(C.USAGE_MEDIA)
    .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
    .build()

private const val MAX_STREAM_ATTEMPTS = 3
private const val MAX_QUALITY_STREAM_ATTEMPTS = 2
private const val QUALITY_FALLBACK_DELAY_MILLIS = 3_000L
private const val CAMERA_TILE_ASPECT_RATIO = 1.9f
