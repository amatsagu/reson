package com.amatsagu.reson

import android.app.Application
import android.util.Log
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.disk.DiskCache
import coil.memory.MemoryCache
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Main application class for Reson.
 */
class ResonApplication : Application(), ImageLoaderFactory {

    private val applicationScope = CoroutineScope(SupervisorJob() + com.amatsagu.reson.core.coroutines.ResonDispatchers.SingleWorker)

    override fun newImageLoader(): ImageLoader {
        return ImageLoader.Builder(this)
            .memoryCache {
                MemoryCache.Builder(this)
                    .maxSizePercent(0.25)
                    .build()
            }
            .diskCache {
                DiskCache.Builder()
                    .directory(cacheDir.resolve("image_cache"))
                    .maxSizeBytes(50L * 1024 * 1024)
                    .build()
            }
            .bitmapConfig(android.graphics.Bitmap.Config.HARDWARE)
            .allowHardware(true)
            .allowRgb565(true)
            .crossfade(enable = false)
            .respectCacheHeaders(enable = false)
            .build()
    }

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
