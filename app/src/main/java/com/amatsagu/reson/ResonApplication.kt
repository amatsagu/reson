package com.amatsagu.reson

import android.app.Application
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Main application class for Reson.
 * Equivalent to main() initialization before services start in a Go application.
 */
class ResonApplication : Application() {

    // Application-wide coroutine scope that lives as long as the process
    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "Reson application starting...")

        val ytDlpManager = YtDlpManager.getInstance(this)

        // Asynchronously initialize and update yt-dlp on app startup
        // This keeps app launch fast (doesn't block main UI thread)
        applicationScope.launch {
            Log.i(TAG, "Initializing yt-dlp on app start...")
            val ready = ytDlpManager.initialize()
            if (ready) {
                // Silently check if an update is available on start
                Log.i(TAG, "Checking for yt-dlp updates on start...")
                ytDlpManager.update()
            }
        }

        // Schedule periodic background maintenance worker
        YtDlpUpdateWorker.enqueuePeriodicUpdate(this)
    }

    companion object {
        private const val TAG = "Reson:App"
    }
}
