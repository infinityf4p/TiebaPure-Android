package dev.infinityf4p.tiebapure.core.media

import android.content.Context
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.annotation.OptIn
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import dev.infinityf4p.tiebapure.core.model.VideoContent
import java.io.File
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient

private sealed interface VideoPlayerState {
    data class Loading(val progress: Float?) : VideoPlayerState

    sealed interface LocalPlayback : VideoPlayerState {
        val lease: VideoFileLease
    }

    data class Preparing(override val lease: VideoFileLease) : LocalPlayback
    data class Ready(override val lease: VideoFileLease) : LocalPlayback
    data class Failed(val message: String, val retryable: Boolean) : VideoPlayerState
}

internal data class VideoPlaybackRequest(
    val sourceUrl: String,
    val generation: Int,
)

internal fun acceptsVideoPlaybackUpdate(
    activeRequest: VideoPlaybackRequest?,
    updateRequest: VideoPlaybackRequest,
): Boolean = activeRequest == updateRequest

internal fun mergeVideoDownloadProgress(current: Float?, candidate: Float): Float? {
    if (!candidate.isFinite() || candidate <= 0f) return current
    val normalized = candidate.coerceIn(0f, 1f)
    return maxOf(current ?: 0f, normalized)
}

internal fun shouldPauseVideoForLifecycleEvent(event: Lifecycle.Event): Boolean =
    event == Lifecycle.Event.ON_STOP

class VideoFileLease internal constructor(
    val file: File,
    private val lease: TemporaryMediaFileLease,
) : AutoCloseable {
    fun release() = lease.release()

    override fun close() = release()
}

class SecureVideoDownloadClient internal constructor(
    context: Context,
    client: OkHttpClient,
    maximumBytes: Long,
) {
    constructor(context: Context) : this(context, OkHttpClient(), MAXIMUM_VIDEO_BYTES)

    private val directory = File(context.cacheDir, "video-playback")
    private val downloader = SecureMediaDownloader(
        directory = directory,
        suppliedClient = client,
        maximumBytes = maximumBytes,
        kind = RemoteMediaKind.Video,
    )

    init {
        removeStalePlaybackFiles(directory)
    }

    suspend fun download(url: String, onProgress: (Float) -> Unit = {}): VideoFileLease {
        val downloaded = downloader.download(url, onProgress)
        return VideoFileLease(downloaded.lease.file, downloaded.lease)
    }

    internal companion object {
        const val MAXIMUM_VIDEO_BYTES = 200L * 1_024 * 1_024
    }
}

@Composable
fun VideoPlayer(
    video: VideoContent,
    onDismiss: () -> Unit,
) {
    val videoUrl = video.videoUrl?.takeIf(MediaUrlPolicy::isAllowedDownloadableVideo)
    val coverUrl = video.coverUrl?.takeIf(MediaUrlPolicy::isAllowed)
    val webUrl = video.webUrl?.takeIf(MediaUrlPolicy::isAllowed)
    val context = LocalContext.current.applicationContext
    val downloader = remember { SecureVideoDownloadClient(context) }
    val scope = rememberCoroutineScope()
    val uriHandler = LocalUriHandler.current
    val sessionOwner = remember {
        OrderedVideoSessionOwner<ExoPlayer, VideoFileLease>(
            disposePlayer = ExoPlayer::release,
            disposeLease = VideoFileLease::release,
        )
    }
    var retryGeneration by remember(videoUrl) { mutableIntStateOf(0) }
    val request = remember(videoUrl, retryGeneration) {
        videoUrl?.let { VideoPlaybackRequest(it, retryGeneration) }
    }
    val latestRequest by rememberUpdatedState(request)
    var state by remember(videoUrl) {
        mutableStateOf<VideoPlayerState>(
            when {
                videoUrl != null -> VideoPlayerState.Loading(progress = null)
                webUrl != null -> VideoPlayerState.Failed(
                    message = "此视频需在浏览器中播放",
                    retryable = false,
                )
                else -> VideoPlayerState.Failed(
                    message = "视频地址不可用",
                    retryable = false,
                )
            },
        )
    }

    LaunchedEffect(request) {
        sessionOwner.release()
        VoicePlaybackCoordinator.shared(context).stop()
        val activeRequest = request ?: return@LaunchedEffect
        state = VideoPlayerState.Loading(progress = null)
        try {
            val lease = downloader.download(activeRequest.sourceUrl) { progress ->
                scope.launch {
                    if (!acceptsVideoPlaybackUpdate(latestRequest, activeRequest)) return@launch
                    val loading = state as? VideoPlayerState.Loading ?: return@launch
                    state = loading.copy(
                        progress = mergeVideoDownloadProgress(loading.progress, progress),
                    )
                }
            }
            if (!acceptsVideoPlaybackUpdate(latestRequest, activeRequest)) {
                lease.release()
                return@LaunchedEffect
            }
            sessionOwner.replaceLease(lease)
            state = VideoPlayerState.Preparing(lease)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            if (acceptsVideoPlaybackUpdate(latestRequest, activeRequest)) {
                state = VideoPlayerState.Failed(
                    message = "视频加载失败\n请检查网络后重试",
                    retryable = true,
                )
            }
        }
    }
    DisposableEffect(sessionOwner) {
        onDispose(sessionOwner::release)
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            dismissOnBackPress = true,
            dismissOnClickOutside = false,
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false,
        ),
    ) {
        BackHandler(onBack = onDismiss)
        when (val current = state) {
            is VideoPlayerState.Loading -> VideoLoading(
                coverUrl = coverUrl,
                progress = current.progress,
                onDismiss = onDismiss,
            )
            is VideoPlayerState.Failed -> VideoFailure(
                coverUrl = coverUrl,
                message = current.message,
                retryable = current.retryable,
                webUrl = webUrl,
                onRetry = { retryGeneration += 1 },
                onOpenWeb = { url ->
                    runCatching { uriHandler.openUri(url) }
                        .onSuccess { onDismiss() }
                },
                onDismiss = onDismiss,
            )
            is VideoPlayerState.LocalPlayback -> {
                val activeRequest = request
                if (activeRequest != null) {
                    LocalVideoPlayer(
                        lease = current.lease,
                        coverUrl = coverUrl,
                        isReady = current is VideoPlayerState.Ready,
                        request = activeRequest,
                        sessionOwner = sessionOwner,
                        isCurrent = {
                            acceptsVideoPlaybackUpdate(latestRequest, activeRequest) &&
                                (state as? VideoPlayerState.LocalPlayback)?.lease === current.lease
                        },
                        onFirstFrame = {
                            state = VideoPlayerState.Ready(current.lease)
                        },
                        onFailure = {
                            state = VideoPlayerState.Failed(
                                message = "视频播放失败\n请重试或在浏览器中打开",
                                retryable = true,
                            )
                        },
                        onDismiss = onDismiss,
                    )
                }
            }
        }
    }
}

@Composable
private fun VideoFailure(
    coverUrl: String?,
    message: String,
    retryable: Boolean,
    webUrl: String?,
    onRetry: () -> Unit,
    onOpenWeb: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    VideoPosterSurface(
        coverUrl = coverUrl,
        onDismiss = onDismiss,
        testTag = "video-failure",
    ) {
        Column(
            modifier = Modifier
                .align(Alignment.Center)
                .padding(24.dp)
                .widthIn(max = 360.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(Color.Black.copy(alpha = 0.76f))
                .padding(horizontal = 20.dp, vertical = 18.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(
                text = message,
                color = Color.White,
                textAlign = TextAlign.Center,
            )
            if (retryable) {
                Button(
                    onClick = onRetry,
                    modifier = Modifier
                        .heightIn(min = 48.dp)
                        .widthIn(min = 112.dp)
                        .testTag("video-retry"),
                ) {
                    Text("重试")
                }
            }
            if (webUrl != null) {
                val modifier = Modifier
                    .heightIn(min = 48.dp)
                    .widthIn(min = 112.dp)
                    .testTag("video-open-web")
                if (retryable) {
                    OutlinedButton(
                        onClick = { onOpenWeb(webUrl) },
                        modifier = modifier,
                    ) {
                        Text("在浏览器中打开")
                    }
                } else {
                    Button(
                        onClick = { onOpenWeb(webUrl) },
                        modifier = modifier,
                    ) {
                        Text("在浏览器中打开")
                    }
                }
            }
        }
    }
}

@Composable
private fun VideoLoading(
    coverUrl: String?,
    progress: Float?,
    onDismiss: () -> Unit,
) {
    VideoPosterSurface(
        coverUrl = coverUrl,
        onDismiss = onDismiss,
        testTag = "video-loading",
    ) {
        Column(
            modifier = Modifier.align(Alignment.Center),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            if (progress == null) {
                CircularProgressIndicator(color = Color.White)
            } else {
                CircularProgressIndicator(progress = { progress }, color = Color.White)
            }
            Spacer(Modifier.height(12.dp))
            Text(
                text = progress?.let {
                    "正在下载视频 ${(it * 100).toInt().coerceIn(0, 100)}%"
                } ?: "正在下载视频",
                color = Color.White,
            )
        }
    }
}

@Composable
private fun VideoPosterSurface(
    coverUrl: String?,
    onDismiss: () -> Unit,
    testTag: String,
    content: @Composable BoxScope.() -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .testTag(testTag),
    ) {
        VideoPoster(coverUrl = coverUrl, scrimAlpha = 0.52f)
        content()
        VideoDismissButton(onDismiss)
    }
}

@Composable
private fun BoxScope.VideoDismissButton(onDismiss: () -> Unit) {
    IconButton(
        onClick = onDismiss,
        modifier = Modifier
            .align(Alignment.TopEnd)
            .windowInsetsPadding(
                WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.End),
            )
            .padding(8.dp)
            .size(48.dp)
            .clip(CircleShape)
            .background(Color.Black.copy(alpha = 0.56f))
            .testTag("video-close"),
    ) {
        Icon(
            imageVector = Icons.Outlined.Close,
            contentDescription = "关闭视频",
            tint = Color.White,
        )
    }
}

@Composable
private fun VideoPoster(
    coverUrl: String?,
    scrimAlpha: Float,
    modifier: Modifier = Modifier,
) {
    Box(modifier.fillMaxSize().background(Color.Black)) {
        if (coverUrl != null) {
            RemoteImage(
                url = coverUrl,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Fit,
            )
        }
        if (scrimAlpha > 0f) {
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = scrimAlpha)))
        }
    }
}

@Composable
@OptIn(markerClass = [UnstableApi::class])
private fun LocalVideoPlayer(
    lease: VideoFileLease,
    coverUrl: String?,
    isReady: Boolean,
    request: VideoPlaybackRequest,
    sessionOwner: OrderedVideoSessionOwner<ExoPlayer, VideoFileLease>,
    isCurrent: () -> Boolean,
    onFirstFrame: () -> Unit,
    onFailure: () -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val latestIsCurrent by rememberUpdatedState(isCurrent)
    val latestOnFirstFrame by rememberUpdatedState(onFirstFrame)
    val latestOnFailure by rememberUpdatedState(onFailure)
    val player = remember(lease, request) {
        ExoPlayer.Builder(context).build().apply {
            addListener(object : Player.Listener {
                override fun onRenderedFirstFrame() {
                    if (latestIsCurrent()) latestOnFirstFrame()
                }

                override fun onPlayerError(error: PlaybackException) {
                    if (latestIsCurrent()) latestOnFailure()
                }
            })
            setAudioAttributes(
                AudioAttributes.Builder()
                    .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                    .setUsage(C.USAGE_MEDIA)
                    .build(),
                true,
            )
            setHandleAudioBecomingNoisy(true)
            setMediaItem(MediaItem.fromUri(Uri.fromFile(lease.file)))
            prepare()
            playWhenReady = lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
        }.also(sessionOwner::attachPlayer)
    }
    DisposableEffect(player, lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (shouldPauseVideoForLifecycleEvent(event)) player.pause()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            sessionOwner.releasePlayer(player)
        }
    }

    val posterAlpha by animateFloatAsState(
        targetValue = if (isReady) 0f else 1f,
        animationSpec = tween(durationMillis = 160),
        label = "video-poster",
    )
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(
            factory = { viewContext ->
                PlayerView(viewContext).apply {
                    useController = true
                    controllerAutoShow = true
                    controllerHideOnTouch = true
                    controllerShowTimeoutMs = 3_000
                    setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING)
                    this.player = player
                }
            },
            update = {
                it.player = player
                it.useController = true
                if (isReady) it.showController()
            },
            modifier = Modifier.fillMaxSize().testTag("video-player"),
        )
        if (posterAlpha > 0f) {
            VideoPoster(
                coverUrl = coverUrl,
                scrimAlpha = 0.44f,
                modifier = Modifier.graphicsLayer { alpha = posterAlpha },
            )
        }
        if (!isReady) {
            Column(
                modifier = Modifier.align(Alignment.Center).testTag("video-preparing"),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                CircularProgressIndicator(color = Color.White)
                Spacer(Modifier.height(12.dp))
                Text("正在准备播放", color = Color.White)
            }
        }
        VideoDismissButton(onDismiss)
    }
}

private fun removeStalePlaybackFiles(directory: File) {
    if (!directory.isDirectory && !directory.mkdirs()) return
    val cutoff = System.currentTimeMillis() - TimeUnit.HOURS.toMillis(24)
    directory.listFiles().orEmpty()
        .filter { it.isFile && it.lastModified() < cutoff }
        .forEach(File::delete)
}

internal class OrderedVideoSessionOwner<Player : Any, Lease : Any>(
    private val disposePlayer: (Player) -> Unit,
    private val disposeLease: (Lease) -> Unit,
) {
    private var player: Player? = null
    private var lease: Lease? = null

    fun replaceLease(value: Lease) {
        release()
        lease = value
    }

    fun attachPlayer(value: Player) {
        if (player === value) return
        player?.let(disposePlayer)
        player = value
    }

    fun releasePlayer(value: Player) {
        if (player !== value) return
        disposePlayer(value)
        player = null
    }

    fun release() {
        player?.let(disposePlayer)
        player = null
        lease?.let(disposeLease)
        lease = null
    }
}
