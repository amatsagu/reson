package com.amatsagu.reson.core.player

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.annotation.OptIn
import androidx.core.content.ContextCompat
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.session.MediaSession
import com.amatsagu.reson.MainActivity
import com.amatsagu.reson.core.network.NetworkMonitor
import com.amatsagu.reson.core.network.NetworkTransition
import com.amatsagu.reson.model.Video
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.abs

data class PlaybackState(
    val currentVideo: Video? = null,
    val isPlaying: Boolean = false,
    val isBuffering: Boolean = false,
    val isSlowConnection: Boolean = false,
    val currentPositionMs: Long = 0L,
    val durationMs: Long = 0L,
    val progress: Float = 0f,
    val playbackSpeed: Float = 1.0f,
    val errorMessage: String? = null,
)

@OptIn(UnstableApi::class)
class AudioPlayerManager(
    private val context: Context,
    initialSpeed: Float = 1.0f,
    private val onProgressUpdate: (video: Video, positionMs: Long, durationMs: Long) -> Unit = { _, _, _ -> },
    private val onListeningTimeDelta: (deltaMs: Long, sync: Boolean) -> Unit = { _, _ -> },
    private val onStreamRecoveryRequested: (suspend (video: Video, positionMs: Long) -> Unit)? = null,
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val networkMonitor = NetworkMonitor.getInstance(context)
    private var mediaSession: MediaSession? = null
    private var tickerJob: Job? = null
    private var isPlayingLocal = false
    private var lastSavedPositionMs = 0L
    private var recoveryAttempts = 0
    private var isRecovering = false
    private var continuousBufferingMs = 0L
    private var wasPlayingBeforeNetworkLoss = false
    private var stallPositionMs = 0L
    private var accumulatedListeningTimeMs = 0L
    private var lastListeningTickRealtime = 0L
    private var currentPlaybackSpeed: Float = initialSpeed
    private var pausedTimestampMs: Long = 0L
    private var interruptionStartTimeMs: Long = 0L
    private var wasPlayingBeforeInterruption: Boolean = false

    private val _playbackState = MutableStateFlow(PlaybackState(playbackSpeed = initialSpeed))
    val playbackState: StateFlow<PlaybackState> = _playbackState.asStateFlow()

    private val playerListener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            _playbackState.update { it.copy(isPlaying = isPlaying) }
            if (isPlaying) {
                recoveryAttempts = 0
                isRecovering = false
                continuousBufferingMs = 0L
                lastListeningTickRealtime = android.os.SystemClock.elapsedRealtime()
                startProgressTicker()
            } else {
                pausedTimestampMs = android.os.SystemClock.elapsedRealtime()
                flushListeningTime(sync = true)
                stopProgressTicker()
                saveCurrentProgress()
            }
        }

        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
            if (playWhenReady) {
                applyRewindIfPausedLong()
            }
        }

        override fun onPlaybackSuppressionReasonChanged(playbackSuppressionReason: Int) {
            when (playbackSuppressionReason) {
                Player.PLAYBACK_SUPPRESSION_REASON_TRANSIENT_AUDIO_FOCUS_LOSS -> {
                    Log.i(TAG, "Audio focus lost transiently (interruption started)")
                    interruptionStartTimeMs = android.os.SystemClock.elapsedRealtime()
                    wasPlayingBeforeInterruption = true
                }
                Player.PLAYBACK_SUPPRESSION_REASON_NONE -> {
                    if (wasPlayingBeforeInterruption) {
                        Log.i(TAG, "Audio focus restored (interruption ended) - auto-resuming")
                        val duration = if (interruptionStartTimeMs > 0L) {
                            android.os.SystemClock.elapsedRealtime() - interruptionStartTimeMs
                        } else 0L
                        if (duration >= 60_000L) {
                            val currentPos = exoPlayer.currentPosition.coerceAtLeast(0L)
                            val targetPos = (currentPos - 15_000L).coerceAtLeast(0L)
                            Log.i(TAG, "Interruption lasted ${duration / 1000}s (>60s). Rewinding 15s to $targetPos ms before auto-resuming")
                            exoPlayer.seekTo(targetPos)
                            val dur = exoPlayer.duration.coerceAtLeast(0L)
                            val progress = if (dur > 0) (targetPos.toFloat() / dur.toFloat()).coerceIn(0f, 1f) else 0f
                            _playbackState.update { it.copy(currentPositionMs = targetPos, progress = progress) }
                            saveCurrentProgress()
                        }
                        interruptionStartTimeMs = 0L
                        wasPlayingBeforeInterruption = false
                        exoPlayer.play()
                    }
                }
                else -> {}
            }
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            when (playbackState) {
                Player.STATE_BUFFERING -> {
                    _playbackState.update { it.copy(isBuffering = true) }
                    startProgressTicker()
                }
                Player.STATE_READY -> {
                    recoveryAttempts = 0
                    isRecovering = false
                    continuousBufferingMs = 0L
                    val duration = exoPlayer.duration.coerceAtLeast(0L)
                    val position = exoPlayer.currentPosition.coerceAtLeast(0L)
                    val progress = if (duration > 0) (position.toFloat() / duration.toFloat()).coerceIn(0f, 1f) else 0f
                    _playbackState.update {
                        it.copy(
                            isBuffering = false,
                            isSlowConnection = false,
                            durationMs = duration,
                            currentPositionMs = position,
                            progress = progress,
                            errorMessage = null
                        )
                    }
                }
                Player.STATE_ENDED -> {
                    currentPlaybackSpeed = 1.0f
                    exoPlayer.setPlaybackSpeed(1.0f)
                    _playbackState.update {
                        it.copy(
                            isPlaying = false,
                            isBuffering = false,
                            progress = 1.0f,
                            playbackSpeed = 1.0f
                        )
                    }
                    flushListeningTime(sync = true)
                    stopProgressTicker()
                    val video = _playbackState.value.currentVideo
                    val duration = exoPlayer.duration.coerceAtLeast(0L)
                    if (video != null && duration > 0) {
                        onProgressUpdate(video, duration, duration)
                    }
                }
                Player.STATE_IDLE -> {
                    if (!isRecovering) {
                        _playbackState.update { it.copy(isBuffering = false) }
                    }
                }
            }
        }

        override fun onPlayerError(error: PlaybackException) {
            Log.e(TAG, "ExoPlayer playback error (code ${error.errorCode}): ${error.message}", error)
            stopProgressTicker()

            if (isPlayingLocal) {
                _playbackState.update {
                    it.copy(
                        isPlaying = false,
                        isBuffering = false,
                        errorMessage = error.message ?: "Local audio error"
                    )
                }
                return
            }

            if (!networkMonitor.isOnline()) {
                Log.w(TAG, "Playback error occurred while offline. Waiting for network connection...")
                val video = _playbackState.value.currentVideo
                if (video != null) {
                    wasPlayingBeforeNetworkLoss = true
                    stallPositionMs = exoPlayer.currentPosition.coerceAtLeast(_playbackState.value.currentPositionMs)
                    _playbackState.update {
                        it.copy(
                            isPlaying = false,
                            isBuffering = true,
                            errorMessage = "Waiting for internet connection..."
                        )
                    }
                }
                return
            }

            val video = _playbackState.value.currentVideo
            if (video != null && recoveryAttempts < MAX_RECOVERY_ATTEMPTS && onStreamRecoveryRequested != null) {
                recoveryAttempts++
                isRecovering = true
                val lastPos = _playbackState.value.currentPositionMs
                Log.w(TAG, "Triggering automatic stream self-recovery (attempt $recoveryAttempts/$MAX_RECOVERY_ATTEMPTS) at $lastPos ms for '${video.title}'")
                _playbackState.update {
                    it.copy(
                        isBuffering = true,
                        errorMessage = "Reconnecting stream..."
                    )
                }
                scope.launch {
                    try {
                        onStreamRecoveryRequested.invoke(video, lastPos)
                    } catch (e: Throwable) {
                        Log.e(TAG, "Stream self-recovery failed", e)
                        _playbackState.update {
                            it.copy(
                                isPlaying = false,
                                isBuffering = false,
                                errorMessage = "Playback error. Tap play to retry."
                            )
                        }
                    }
                }
            } else {
                _playbackState.update {
                    it.copy(
                        isPlaying = false,
                        isBuffering = false,
                        errorMessage = error.message ?: "Playback error"
                    )
                }
            }
        }
    }

    private val exoPlayer: ExoPlayer by lazy {
        // Low-latency buffer tuning for audio: start playing after 500ms buffer, recover from rebuffers in 1s
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                /* minBufferMs = */ 15_000,
                /* maxBufferMs = */ 60_000,
                /* bufferForPlaybackMs = */ 500,
                /* bufferForPlaybackAfterRebufferMs = */ 1_000
            )
            .setPrioritizeTimeOverSizeThresholds(true)
            .build()

        // Robust HTTP data source with mobile Chrome User-Agent and increased timeouts for 4G/5G
        val httpDataSourceFactory = DefaultHttpDataSource.Factory()
            .setUserAgent("Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36")
            .setConnectTimeoutMs(15_000)
            .setReadTimeoutMs(20_000)
            .setAllowCrossProtocolRedirects(true)
            .setKeepPostFor302Redirects(true)

        val loadErrorHandlingPolicy = object : DefaultLoadErrorHandlingPolicy(
            /* minimumLoadableRetryCount = */ 5
        ) {
            override fun getRetryDelayMsFor(loadErrorInfo: androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy.LoadErrorInfo): Long {
                return 600L.coerceAtLeast(super.getRetryDelayMsFor(loadErrorInfo))
            }
        }

        val dataSourceFactory = DefaultDataSource.Factory(context, httpDataSourceFactory)

        val mediaSourceFactory = DefaultMediaSourceFactory(context)
            .setDataSourceFactory(dataSourceFactory)
            .setLoadErrorHandlingPolicy(loadErrorHandlingPolicy)

        ExoPlayer.Builder(context)
            .setLoadControl(loadControl)
            .setMediaSourceFactory(mediaSourceFactory)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setContentType(C.AUDIO_CONTENT_TYPE_SPEECH)
                    .setUsage(C.USAGE_MEDIA)
                    .build(),
                /* handleAudioFocus = */ true
            )
            .setHandleAudioBecomingNoisy(true)
            .setSeekBackIncrementMs(10_000L)
            .setSeekForwardIncrementMs(10_000L)
            .build().apply {
                setPlaybackSpeed(currentPlaybackSpeed)
                addListener(playerListener)
            }
    }

    init {
        try {
            activeMediaSession?.release()
        } catch (_: Exception) {}
        activeMediaSession = null

        val sessionActivityIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            0,
            sessionActivityIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val session = MediaSession.Builder(context, exoPlayer)
            .setSessionActivity(pendingIntent)
            .setId("ResonMediaSession")
            .build()
        mediaSession = session
        activeMediaSession = session

        scope.launch {
            networkMonitor.transitions.collect { transition ->
                handleNetworkTransition(transition)
            }
        }
    }


    private fun handleNetworkTransition(transition: NetworkTransition) {
        if (isPlayingLocal) {
            Log.d(TAG, "Ignoring network transition ${transition::class.simpleName} during local file playback")
            return
        }
        when (transition) {
            is NetworkTransition.Lost -> {
                Log.w(TAG, "AudioPlayer observed network LOST (was ${transition.lastState.displayName})")
                val isCurrentlyActive = exoPlayer.isPlaying || _playbackState.value.isPlaying || _playbackState.value.isBuffering
                if (isCurrentlyActive && _playbackState.value.currentVideo != null) {
                    wasPlayingBeforeNetworkLoss = true
                    stallPositionMs = exoPlayer.currentPosition.coerceAtLeast(_playbackState.value.currentPositionMs)
                    stopProgressTicker()
                    _playbackState.update {
                        it.copy(
                            isPlaying = false,
                            isBuffering = true,
                            errorMessage = "Waiting for internet connection..."
                        )
                    }
                }
            }
            is NetworkTransition.Switched -> {
                Log.i(TAG, "AudioPlayer observed network SWITCHED: ${transition.from.displayName} -> ${transition.to.displayName}")
                triggerSmartNetworkRecovery(transition.to.displayName)
            }
            is NetworkTransition.Restored -> {
                Log.i(TAG, "AudioPlayer observed network RESTORED on ${transition.to.displayName}")
                triggerSmartNetworkRecovery(transition.to.displayName)
            }
        }
    }

    private fun triggerSmartNetworkRecovery(networkDisplayName: String) {
        if (isPlayingLocal) return
        val video = _playbackState.value.currentVideo ?: return
        val shouldRecover = wasPlayingBeforeNetworkLoss ||
                exoPlayer.isPlaying ||
                _playbackState.value.isPlaying ||
                _playbackState.value.isBuffering ||
                _playbackState.value.errorMessage != null

        if (shouldRecover && onStreamRecoveryRequested != null) {
            val resumePos = if (stallPositionMs > 0) stallPositionMs else exoPlayer.currentPosition.coerceAtLeast(_playbackState.value.currentPositionMs)
            stallPositionMs = 0L
            wasPlayingBeforeNetworkLoss = false
            recoveryAttempts = 0
            isRecovering = true
            continuousBufferingMs = 0L

            _playbackState.update {
                it.copy(
                    isBuffering = true,
                    errorMessage = "Reconnecting on $networkDisplayName..."
                )
            }

            scope.launch {
                try {
                    Log.i(TAG, "Executing automatic handoff recovery on $networkDisplayName for '${video.title}' at $resumePos ms")
                    onStreamRecoveryRequested.invoke(video, resumePos)
                } catch (e: Throwable) {
                    Log.e(TAG, "Handoff recovery failed on $networkDisplayName", e)
                    _playbackState.update {
                        it.copy(
                            isPlaying = false,
                            isBuffering = false,
                            errorMessage = "Failed to reconnect on $networkDisplayName"
                        )
                    }
                }
            }
        }
    }

    fun play(video: Video, mediaUri: String, isLocal: Boolean = false, initialPositionMs: Long = 0L) {
        scope.launch {
            try {
                isPlayingLocal = isLocal
                recoveryAttempts = 0
                isRecovering = false
                continuousBufferingMs = 0L
                wasPlayingBeforeNetworkLoss = false
                stallPositionMs = 0L

                val safePosition = initialPositionMs.coerceAtLeast(0L)
                val effectiveDuration = if (video.playbackDurationMs > 0) video.playbackDurationMs else (video.durationSeconds * 1000L)

                currentPlaybackSpeed = 1.0f
                exoPlayer.setPlaybackSpeed(1.0f)

                _playbackState.update {
                    it.copy(
                        currentVideo = video,
                        isPlaying = false,
                        isBuffering = true,
                        currentPositionMs = safePosition,
                        durationMs = effectiveDuration,
                        progress = if (effectiveDuration > 0) (safePosition.toFloat() / effectiveDuration.toFloat()).coerceIn(0f, 1f) else 0f,
                        playbackSpeed = 1.0f,
                        errorMessage = null
                    )
                }

                val metadata = MediaMetadata.Builder()
                    .setTitle(video.title)
                    .setArtist(video.channelTitle)
                    .setArtworkUri(if (video.thumbnailUrl.isNotBlank()) Uri.parse(video.thumbnailUrl) else null)
                    .build()

                val mediaItem = MediaItem.Builder()
                    .setUri(mediaUri)
                    .setMediaId(video.id)
                    .setMediaMetadata(metadata)
                    .build()

                // Set media item with start position atomically before prepare() to prevent Progressive seek races
                exoPlayer.setMediaItem(mediaItem, safePosition)
                exoPlayer.setPlaybackSpeed(currentPlaybackSpeed)
                exoPlayer.prepare()
                exoPlayer.play()
                pausedTimestampMs = 0L
                interruptionStartTimeMs = 0L
                wasPlayingBeforeInterruption = false
                startProgressTicker()
                startForegroundPlaybackService()
                Log.d(TAG, "Playback initialized for '${video.title}' (isLocal=$isLocal, speed=${currentPlaybackSpeed}x) starting at $safePosition ms")
            } catch (e: Throwable) {
                Log.e(TAG, "Failed to start playback for ${video.id}", e)
                _playbackState.update {
                    it.copy(
                        isPlaying = false,
                        isBuffering = false,
                        errorMessage = e.message ?: "Failed to start stream"
                    )
                }
            }
        }
    }

    fun resumeWithFreshUrl(newUrl: String, positionMs: Long) {
        scope.launch {
            val video = _playbackState.value.currentVideo ?: return@launch
            try {
                Log.i(TAG, "Applying fresh stream URL for '${video.title}' at $positionMs ms")
                val metadata = MediaMetadata.Builder()
                    .setTitle(video.title)
                    .setArtist(video.channelTitle)
                    .setArtworkUri(if (video.thumbnailUrl.isNotBlank()) Uri.parse(video.thumbnailUrl) else null)
                    .build()

                val mediaItem = MediaItem.Builder()
                    .setUri(newUrl)
                    .setMediaId(video.id)
                    .setMediaMetadata(metadata)
                    .build()

                exoPlayer.setMediaItem(mediaItem, positionMs.coerceAtLeast(0L))
                exoPlayer.prepare()
                exoPlayer.play()
                startProgressTicker()
                startForegroundPlaybackService()
            } catch (e: Throwable) {
                Log.e(TAG, "Failed to resume with fresh stream URL", e)
                _playbackState.update {
                    it.copy(
                        isPlaying = false,
                        isBuffering = false,
                        errorMessage = "Connection lost. Tap play to retry."
                    )
                }
            }
        }
    }

    private fun startForegroundPlaybackService() {
        try {
            val serviceIntent = Intent(context, ResonPlaybackService::class.java)
            ContextCompat.startForegroundService(context, serviceIntent)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to start ResonPlaybackService", e)
        }
    }

    private fun runOnMain(block: () -> Unit) {
        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) {
            block()
        } else {
            scope.launch { block() }
        }
    }

    private fun applyRewindIfPausedLong() {
        if (pausedTimestampMs > 0L) {
            val pausedDuration = android.os.SystemClock.elapsedRealtime() - pausedTimestampMs
            if (pausedDuration >= 60_000L) {
                val currentPos = exoPlayer.currentPosition.coerceAtLeast(0L)
                val targetPos = (currentPos - 15_000L).coerceAtLeast(0L)
                Log.i(TAG, "Audio was paused for ${pausedDuration / 1000}s (>=60s). Rewinding 15s from $currentPos to $targetPos ms")
                exoPlayer.seekTo(targetPos)
                val duration = exoPlayer.duration.coerceAtLeast(0L)
                val progress = if (duration > 0) (targetPos.toFloat() / duration.toFloat()).coerceIn(0f, 1f) else 0f
                _playbackState.update {
                    it.copy(
                        currentPositionMs = targetPos,
                        progress = progress
                    )
                }
                saveCurrentProgress()
            }
            pausedTimestampMs = 0L
        }
    }

    fun pause() {
        runOnMain {
            if (exoPlayer.isPlaying) {
                exoPlayer.pause()
            }
        }
    }

    fun resume() {
        runOnMain {
            applyRewindIfPausedLong()
            if (exoPlayer.playbackState == Player.STATE_IDLE || _playbackState.value.errorMessage != null) {
                // If in error or idle, trigger self recovery
                val video = _playbackState.value.currentVideo
                if (video != null && onStreamRecoveryRequested != null) {
                    _playbackState.update { it.copy(isBuffering = true, errorMessage = null) }
                    scope.launch {
                        onStreamRecoveryRequested.invoke(video, _playbackState.value.currentPositionMs)
                    }
                    return@runOnMain
                }
            }
            if (!exoPlayer.isPlaying) {
                exoPlayer.play()
            }
        }
    }

    fun setPlaybackSpeed(speed: Float) {
        runOnMain {
            currentPlaybackSpeed = speed
            exoPlayer.setPlaybackSpeed(speed)
            _playbackState.update { it.copy(playbackSpeed = speed) }
            Log.d(TAG, "Playback speed updated to ${speed}x")
        }
    }

    fun togglePlayPause() {
        runOnMain {
            if (exoPlayer.isPlaying) {
                pause()
            } else {
                resume()
            }
        }
    }

    fun getCurrentPositionMs(): Long = _playbackState.value.currentPositionMs

    fun seekTo(positionMs: Long) {
        runOnMain {
            flushListeningTime(sync = false)
            lastListeningTickRealtime = android.os.SystemClock.elapsedRealtime()
            val safePos = positionMs.coerceAtLeast(0L)
            exoPlayer.seekTo(safePos)
            val duration = exoPlayer.duration.coerceAtLeast(0L)
            val progress = if (duration > 0) (safePos.toFloat() / duration.toFloat()).coerceIn(0f, 1f) else 0f
            _playbackState.update {
                it.copy(
                    currentPositionMs = safePos,
                    progress = progress
                )
            }
            saveCurrentProgress()
        }
    }

    fun stop() {
        runOnMain {
            flushListeningTime(sync = true)
            saveCurrentProgress()
            isRecovering = false
            continuousBufferingMs = 0L
            wasPlayingBeforeNetworkLoss = false
            stallPositionMs = 0L
            currentPlaybackSpeed = 1.0f
            exoPlayer.setPlaybackSpeed(1.0f)
            exoPlayer.stop()
            exoPlayer.clearMediaItems()
            stopProgressTicker()
            try {
                context.stopService(Intent(context, ResonPlaybackService::class.java))
            } catch (e: Exception) {
                Log.w(TAG, "Could not stop ResonPlaybackService", e)
            }
            _playbackState.update {
                it.copy(
                    currentVideo = null,
                    isPlaying = false,
                    isBuffering = false,
                    currentPositionMs = 0L,
                    progress = 0f,
                    playbackSpeed = 1.0f,
                    errorMessage = null
                )
            }
        }
    }

    fun release() {
        flushListeningTime(sync = true)
        saveCurrentProgress()
        stopProgressTicker()
        exoPlayer.removeListener(playerListener)
        mediaSession?.release()
        mediaSession = null
        activeMediaSession = null
        exoPlayer.release()
        scope.cancel()
    }

    fun flushListeningTime(sync: Boolean = false) {
        if (accumulatedListeningTimeMs > 0L) {
            val toFlush = accumulatedListeningTimeMs
            accumulatedListeningTimeMs = 0L
            onListeningTimeDelta(toFlush, sync)
        }
    }

    fun saveCurrentProgress() {
        val video = _playbackState.value.currentVideo ?: return
        val pos = _playbackState.value.currentPositionMs
        val dur = _playbackState.value.durationMs
        lastSavedPositionMs = pos
        if (pos > 0 || dur > 0) {
            onProgressUpdate(video, pos, dur)
        }
    }

    private fun startProgressTicker() {
        tickerJob?.cancel()
        lastListeningTickRealtime = android.os.SystemClock.elapsedRealtime()
        tickerJob = scope.launch {
            while (isActive) {
                delay(400)
                val current = exoPlayer.currentPosition.coerceAtLeast(0L)
                val duration = exoPlayer.duration.coerceAtLeast(0L)
                val progress = if (duration > 0) (current.toFloat() / duration.toFloat()).coerceIn(0f, 1f) else 0f
                _playbackState.update {
                    it.copy(
                        currentPositionMs = current,
                        durationMs = if (duration > 0) duration else it.durationMs,
                        progress = progress
                    )
                }

                val nowRealtime = android.os.SystemClock.elapsedRealtime()
                val tickElapsed = if (lastListeningTickRealtime > 0L) (nowRealtime - lastListeningTickRealtime) else 0L
                lastListeningTickRealtime = nowRealtime

                if (exoPlayer.isPlaying && exoPlayer.playbackState == Player.STATE_READY && tickElapsed in 1L..2000L) {
                    accumulatedListeningTimeMs += tickElapsed
                    if (accumulatedListeningTimeMs >= 1000L) {
                        val toFlush = accumulatedListeningTimeMs
                        accumulatedListeningTimeMs = 0L
                        onListeningTimeDelta(toFlush, false)
                    }
                }

                if (exoPlayer.playbackState == Player.STATE_BUFFERING) {
                    continuousBufferingMs += 400L

                    if (continuousBufferingMs >= 20_000L) {
                        _playbackState.update {
                            it.copy(
                                isSlowConnection = true,
                                errorMessage = if (!networkMonitor.isOnline()) {
                                    "Waiting for internet connection..."
                                } else {
                                    "Slow connection. Still loading audio..."
                                }
                            )
                        }
                    }

                    if (continuousBufferingMs in 12_000L..12_400L && !isRecovering) {
                        val video = _playbackState.value.currentVideo
                        if (video != null && onStreamRecoveryRequested != null && recoveryAttempts < MAX_RECOVERY_ATTEMPTS) {
                            Log.w(TAG, "Buffering watchdog triggered after 12s stall. Attempting stream recovery...")
                            recoveryAttempts++
                            isRecovering = true
                            onStreamRecoveryRequested.invoke(video, current)
                        }
                    }
                } else {
                    if (continuousBufferingMs > 0L) {
                        continuousBufferingMs = 0L
                        if (_playbackState.value.isSlowConnection) {
                            _playbackState.update {
                                it.copy(isSlowConnection = false, errorMessage = null)
                            }
                        }
                    }
                }

                if (abs(current - lastSavedPositionMs) >= 3000L) {
                    saveCurrentProgress()
                }
            }
        }
    }

    private fun stopProgressTicker() {
        tickerJob?.cancel()
        tickerJob = null
    }

    companion object {
        private const val TAG = "Reson:AudioPlayer"
        private const val MAX_RECOVERY_ATTEMPTS = 3

        @Volatile
        var activeMediaSession: MediaSession? = null
            internal set
    }
}
