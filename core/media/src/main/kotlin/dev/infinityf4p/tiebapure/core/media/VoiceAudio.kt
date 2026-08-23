package dev.infinityf4p.tiebapure.core.media

import android.content.Context
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Replay
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import dev.infinityf4p.tiebapure.core.model.VoiceContent
import java.io.File
import java.util.Locale
import kotlin.math.roundToInt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient

object VoiceAudioUrlPolicy {
    private const val Host = "tiebac.baidu.com"
    private const val Path = "/c/p/voice"
    private val md5Pattern = Regex("^[0-9a-f]{32}$")

    fun normalizeMd5(value: String): String? = value.trim().lowercase(Locale.ROOT)
        .takeIf(md5Pattern::matches)

    fun urlForMd5(value: String): String? {
        val md5 = normalizeMd5(value) ?: return null
        return "https://$Host$Path".toHttpUrlOrNull()
            ?.newBuilder()
            ?.addQueryParameter("voice_md5", md5)
            ?.addQueryParameter("play_from", "pb_voice_play")
            ?.build()
            ?.toString()
    }

    internal fun isAllowedSourceUrl(rawUrl: String?): Boolean {
        val url = rawUrl?.toHttpUrlOrNull() ?: return false
        return MediaUrlPolicy.isAllowed(rawUrl) &&
            url.host == Host &&
            url.encodedPath == Path &&
            normalizeMd5(url.queryParameter("voice_md5").orEmpty()) != null &&
            url.queryParameter("play_from") == "pb_voice_play" &&
            url.querySize == 2
    }

    internal fun resolveRedirect(currentUrl: String, location: String): String? =
        currentUrl.toHttpUrlOrNull()
            ?.resolve(location)
            ?.toString()
            ?.takeIf(MediaUrlPolicy::isAllowed)

    internal fun isAllowedMimeType(value: String?): Boolean {
        val mimeType = value?.trim()?.lowercase(Locale.ROOT) ?: return false
        return mimeType == "application/octet-stream" ||
            (mimeType.startsWith("audio/") && mimeType.length > "audio/".length)
    }
}

class VoiceFileLease internal constructor(
    val file: File,
    private val releaseAction: () -> Unit,
) : AutoCloseable {
    internal constructor(file: File, lease: TemporaryMediaFileLease) : this(file, lease::release)
    internal constructor(file: File) : this(file, {})

    fun release() = releaseAction()

    override fun close() = release()
}

class SecureVoiceAudioDownloadClient internal constructor(
    context: Context,
    client: OkHttpClient,
    maximumBytes: Long,
) {
    constructor(context: Context) : this(context, OkHttpClient(), MAXIMUM_AUDIO_BYTES)

    private val downloader = SecureMediaDownloader(
        directory = File(context.cacheDir, "voice-playback"),
        suppliedClient = client,
        maximumBytes = maximumBytes,
        kind = RemoteMediaKind.Audio,
    )

    suspend fun download(md5: String, onProgress: (Float) -> Unit = {}): VoiceFileLease {
        val url = VoiceAudioUrlPolicy.urlForMd5(md5)
            ?: throw MediaDownloadException("Invalid voice identifier")
        val downloaded = downloader.download(url, onProgress)
        return VoiceFileLease(downloaded.lease.file, downloaded.lease)
    }

    suspend fun download(voice: VoiceContent, onProgress: (Float) -> Unit = {}): VoiceFileLease {
        voice.localUrl?.let { raw ->
            OfflineMediaPolicy.resolve(raw)?.let { local ->
                onProgress(1f)
                return VoiceFileLease(local)
            }
        }
        if (voice.offlineOnly) throw MediaDownloadException("Offline voice file is unavailable")
        return download(voice.md5, onProgress)
    }

    internal companion object {
        const val MAXIMUM_AUDIO_BYTES = 8L * 1_024 * 1_024
    }
}

enum class VoicePlaybackPhase {
    Idle,
    Loading,
    Playing,
    Paused,
    Completed,
    Failed,
}

data class VoicePlaybackState(
    val key: String? = null,
    val phase: VoicePlaybackPhase = VoicePlaybackPhase.Idle,
    val loadProgress: Float? = null,
    val positionMilliseconds: Long = 0,
    val durationMilliseconds: Long = 0,
    val errorMessage: String? = null,
) {
    val playbackProgress: Float
        get() = if (durationMilliseconds > 0) {
            positionMilliseconds.toFloat().div(durationMilliseconds).coerceIn(0f, 1f)
        } else {
            0f
        }
}

class VoicePlaybackCoordinator private constructor(
    context: Context,
    private val downloader: SecureVoiceAudioDownloadClient,
) {
    internal constructor(context: Context) : this(
        context.applicationContext,
        SecureVoiceAudioDownloadClient(context.applicationContext),
    )

    private val applicationContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val mutableState = MutableStateFlow(VoicePlaybackState())
    val state: StateFlow<VoicePlaybackState> = mutableState.asStateFlow()

    private var generation = 0L
    private var loadJob: Job? = null
    private var progressJob: Job? = null
    private var player: ExoPlayer? = null
    private var lease: VoiceFileLease? = null

    fun stateFor(md5: String): VoicePlaybackState {
        val normalized = VoiceAudioUrlPolicy.normalizeMd5(md5)
        return mutableState.value.takeIf { normalized != null && it.key == normalized }
            ?: VoicePlaybackState()
    }

    fun toggle(voice: VoiceContent) {
        val current = stateFor(voice.md5)
        if (current.key == null) {
            beginLoading(voice)
            return
        }
        when (current.phase) {
            VoicePlaybackPhase.Idle,
            VoicePlaybackPhase.Failed,
            -> beginLoading(voice)
            VoicePlaybackPhase.Loading -> Unit
            VoicePlaybackPhase.Playing -> pause()
            VoicePlaybackPhase.Paused -> resume()
            VoicePlaybackPhase.Completed -> replay()
        }
    }

    fun retry(voice: VoiceContent) = beginLoading(voice)

    fun stop() {
        generation += 1
        tearDown()
        mutableState.value = VoicePlaybackState()
    }

    internal fun close() {
        stop()
        scope.cancel()
    }

    private fun beginLoading(voice: VoiceContent) {
        val key = VoiceAudioUrlPolicy.normalizeMd5(voice.md5) ?: run {
            stop()
            mutableState.value = VoicePlaybackState(
                phase = VoicePlaybackPhase.Failed,
                errorMessage = "语音标识无效",
            )
            return
        }
        generation += 1
        val requestGeneration = generation
        tearDown()
        mutableState.value = VoicePlaybackState(key = key, phase = VoicePlaybackPhase.Loading)
        loadJob = scope.launch {
            try {
                val downloaded = downloader.download(voice) { progress ->
                    scope.launch {
                        if (isCurrent(key, requestGeneration)) {
                            mutableState.value = mutableState.value.copy(loadProgress = progress)
                        }
                    }
                }
                if (!isCurrent(key, requestGeneration)) {
                    downloaded.release()
                    return@launch
                }
                lease = downloaded
                startPlayer(key, requestGeneration, downloaded.file)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                fail(key, requestGeneration, "语音加载失败")
            }
        }
    }

    private fun startPlayer(key: String, requestGeneration: Long, file: File) {
        val newPlayer = ExoPlayer.Builder(applicationContext).build()
        val listener = object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (!isCurrent(key, requestGeneration) || player !== newPlayer) return
                when (playbackState) {
                    Player.STATE_READY -> publishPlayback(
                        if (newPlayer.isPlaying) VoicePlaybackPhase.Playing else VoicePlaybackPhase.Paused,
                    )
                    Player.STATE_ENDED -> {
                        progressJob?.cancel()
                        progressJob = null
                        publishPlayback(VoicePlaybackPhase.Completed)
                    }
                    Player.STATE_BUFFERING,
                    Player.STATE_IDLE,
                    -> Unit
                }
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (!isCurrent(key, requestGeneration) || player !== newPlayer) return
                if (newPlayer.playbackState == Player.STATE_READY) {
                    publishPlayback(
                        if (isPlaying) VoicePlaybackPhase.Playing else VoicePlaybackPhase.Paused,
                    )
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                fail(key, requestGeneration, "语音播放失败")
            }
        }
        player = newPlayer
        newPlayer.addListener(listener)
        newPlayer.setAudioAttributes(
            AudioAttributes.Builder()
                .setContentType(C.AUDIO_CONTENT_TYPE_SPEECH)
                .setUsage(C.USAGE_MEDIA)
                .build(),
            true,
        )
        newPlayer.setHandleAudioBecomingNoisy(true)
        newPlayer.setMediaItem(MediaItem.fromUri(Uri.fromFile(file)))
        newPlayer.prepare()
        newPlayer.playWhenReady = true
        startProgressUpdates(key, requestGeneration)
    }

    private fun pause() {
        player?.pause() ?: return
        progressJob?.cancel()
        progressJob = null
        publishPlayback(VoicePlaybackPhase.Paused)
    }

    private fun resume() {
        val currentPlayer = player ?: return
        currentPlayer.play()
        publishPlayback(VoicePlaybackPhase.Playing)
        startProgressUpdates(mutableState.value.key ?: return, generation)
    }

    private fun replay() {
        player?.seekTo(0) ?: return
        resume()
    }

    private fun startProgressUpdates(key: String, requestGeneration: Long) {
        progressJob?.cancel()
        progressJob = scope.launch {
            while (isCurrent(key, requestGeneration)) {
                delay(100)
                if (mutableState.value.phase == VoicePlaybackPhase.Playing) {
                    publishPlayback(VoicePlaybackPhase.Playing)
                }
            }
        }
    }

    private fun publishPlayback(phase: VoicePlaybackPhase) {
        val currentPlayer = player ?: return
        val duration = currentPlayer.duration.takeIf { it != C.TIME_UNSET && it > 0 } ?: 0
        val position = currentPlayer.currentPosition.coerceAtLeast(0)
        val effectivePosition = if (duration > 0) position.coerceAtMost(duration) else position
        mutableState.value = VoicePlaybackState(
            key = mutableState.value.key,
            phase = phase,
            positionMilliseconds = if (phase == VoicePlaybackPhase.Completed) duration else effectivePosition,
            durationMilliseconds = duration,
        )
    }

    private fun fail(key: String, requestGeneration: Long, message: String) {
        if (!isCurrent(key, requestGeneration)) return
        tearDown()
        mutableState.value = VoicePlaybackState(
            key = key,
            phase = VoicePlaybackPhase.Failed,
            errorMessage = message,
        )
    }

    private fun tearDown() {
        loadJob?.cancel()
        loadJob = null
        progressJob?.cancel()
        progressJob = null
        player?.release()
        player = null
        lease?.release()
        lease = null
    }

    private fun isCurrent(key: String, requestGeneration: Long): Boolean =
        generation == requestGeneration && mutableState.value.key == key

    companion object {
        @Volatile
        private var shared: VoicePlaybackCoordinator? = null

        fun shared(context: Context): VoicePlaybackCoordinator = shared ?: synchronized(this) {
            shared ?: VoicePlaybackCoordinator(context.applicationContext).also { shared = it }
        }
    }
}

data class VoicePlaybackPresentation(
    val title: String,
    val detail: String,
    val progress: Float?,
    val action: VoicePlaybackAction,
    val isFailure: Boolean = false,
)

enum class VoicePlaybackAction { Toggle, Retry, None }

object VoicePlaybackControlPolicy {
    fun presentation(
        state: VoicePlaybackState,
        fallbackDurationMilliseconds: Int,
    ): VoicePlaybackPresentation {
        val duration = state.durationMilliseconds.takeIf { it > 0 }
            ?: fallbackDurationMilliseconds.coerceAtLeast(0).toLong()
        val time = formatClock(duration)
        return when (state.phase) {
            VoicePlaybackPhase.Idle -> VoicePlaybackPresentation("语音", time, null, VoicePlaybackAction.Toggle)
            VoicePlaybackPhase.Loading -> VoicePlaybackPresentation(
                state.loadProgress?.let { "加载中 ${formatPercent(it)}" } ?: "加载中",
                time,
                state.loadProgress,
                VoicePlaybackAction.None,
            )
            VoicePlaybackPhase.Playing -> VoicePlaybackPresentation(
                "正在播放",
                "${formatClock(state.positionMilliseconds)} / $time",
                state.playbackProgress,
                VoicePlaybackAction.Toggle,
            )
            VoicePlaybackPhase.Paused -> VoicePlaybackPresentation(
                "已暂停",
                "${formatClock(state.positionMilliseconds)} / $time",
                state.playbackProgress,
                VoicePlaybackAction.Toggle,
            )
            VoicePlaybackPhase.Completed -> VoicePlaybackPresentation(
                "播放完毕",
                time,
                1f,
                VoicePlaybackAction.Toggle,
            )
            VoicePlaybackPhase.Failed -> VoicePlaybackPresentation(
                "加载失败，点击重试",
                time,
                null,
                VoicePlaybackAction.Retry,
                isFailure = true,
            )
        }
    }

    fun formatClock(milliseconds: Long): String {
        val seconds = ((milliseconds.coerceAtLeast(0) + 999) / 1_000)
        return "%d:%02d".format(Locale.ROOT, seconds / 60, seconds % 60)
    }

    internal fun formatPercent(progress: Float): String =
        "${(progress.coerceIn(0f, 1f) * 100).roundToInt()}%"
}

@Composable
fun VoicePlaybackControl(
    voice: VoiceContent,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current.applicationContext
    val coordinator = remember(context) { VoicePlaybackCoordinator.shared(context) }
    val aggregateState by coordinator.state.collectAsState()
    val state = aggregateState.takeIf { it.key == voice.md5 } ?: VoicePlaybackState()
    val presentation = VoicePlaybackControlPolicy.presentation(state, voice.durationMilliseconds)
    val actionDescription = when (state.phase) {
        VoicePlaybackPhase.Playing -> "暂停语音"
        VoicePlaybackPhase.Paused -> "继续播放语音"
        VoicePlaybackPhase.Completed -> "重新播放语音"
        VoicePlaybackPhase.Failed -> "重新加载语音"
        VoicePlaybackPhase.Loading -> "正在加载语音"
        VoicePlaybackPhase.Idle -> "播放语音"
    }

    Surface(
        onClick = {
            when (presentation.action) {
                VoicePlaybackAction.Toggle -> coordinator.toggle(voice)
                VoicePlaybackAction.Retry -> coordinator.retry(voice)
                VoicePlaybackAction.None -> Unit
            }
        },
        enabled = presentation.action != VoicePlaybackAction.None,
        modifier = modifier
            .widthIn(min = 152.dp, max = 280.dp)
            .heightIn(min = 48.dp)
            .semantics {
                contentDescription = actionDescription
                presentation.progress?.let {
                    progressBarRangeInfo = androidx.compose.ui.semantics.ProgressBarRangeInfo(it, 0f..1f)
                }
            },
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = MaterialTheme.shapes.small,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 44.dp)
                    .padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                when {
                    state.phase == VoicePlaybackPhase.Loading && presentation.progress == null ->
                        CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                    state.phase == VoicePlaybackPhase.Playing ->
                        Icon(Icons.Outlined.Pause, contentDescription = null)
                    state.phase == VoicePlaybackPhase.Completed ->
                        Icon(Icons.Outlined.Replay, contentDescription = null)
                    state.phase == VoicePlaybackPhase.Failed ->
                        Icon(Icons.Outlined.ErrorOutline, contentDescription = null)
                    else -> Icon(Icons.Outlined.PlayArrow, contentDescription = null)
                }
                Column(Modifier.weight(1f)) {
                    Text(
                        presentation.title,
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (presentation.isFailure) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.onSurface
                        },
                    )
                    Text(
                        presentation.detail,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            presentation.progress?.let {
                LinearProgressIndicator(progress = { it }, modifier = Modifier.fillMaxWidth())
            }
        }
    }
}
