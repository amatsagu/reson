package com.amatsagu.reson.core.player

import android.content.Intent
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService

class ResonPlaybackService : MediaSessionService() {

    private var sessionAdded = false

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "ResonPlaybackService created")
        ensureSessionAdded()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        ensureSessionAdded()
        return super.onStartCommand(intent, flags, startId)
    }

    private fun ensureSessionAdded() {
        val session = AudioPlayerManager.activeMediaSession
        if (session != null && !sessionAdded) {
            try {
                addSession(session)
                sessionAdded = true
                Log.i(TAG, "Attached active MediaSession to ResonPlaybackService")
            } catch (e: Exception) {
                Log.w(TAG, "Could not add session to ResonPlaybackService", e)
            }
        }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? {
        val session = AudioPlayerManager.activeMediaSession
        if (session != null && !sessionAdded) {
            ensureSessionAdded()
        }
        return session
    }

    @OptIn(UnstableApi::class)
    override fun onTaskRemoved(rootIntent: Intent?) {
        val session = AudioPlayerManager.activeMediaSession
        val player = session?.player
        if (player == null || !player.playWhenReady || player.mediaItemCount == 0) {
            Log.i(TAG, "Task removed with inactive playback - stopping service")
            stopSelf()
        } else {
            Log.d(TAG, "Task removed while audio is playing - keeping foreground service alive")
        }
    }

    override fun onDestroy() {
        val session = AudioPlayerManager.activeMediaSession
        if (session != null && sessionAdded) {
            try {
                removeSession(session)
            } catch (e: Exception) {
                Log.w(TAG, "Could not remove session on destroy", e)
            }
            sessionAdded = false
        }
        Log.i(TAG, "ResonPlaybackService destroyed")
        super.onDestroy()
    }

    companion object {
        private const val TAG = "Reson:PlaybackService"
    }
}
