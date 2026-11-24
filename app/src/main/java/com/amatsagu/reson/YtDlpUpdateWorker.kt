package com.amatsagu.reson

import android.content.Context
import android.util.Log
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.amatsagu.reson.Result as ResonResult
import com.yausername.youtubedl_android.YoutubeDL.UpdateChannel
import java.util.concurrent.TimeUnit

class YtDlpUpdateWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        Log.i(TAG, "YtDlpUpdateWorker triggered in background")
        val manager = YtDlpManager.getInstance(applicationContext)

        manager.initialize()

        return when (val updateResult = manager.update(UpdateChannel.STABLE)) {
            is ResonResult.Success -> {
                Log.i(TAG, "Background update succeeded: ${updateResult.data}")
                Result.success()
            }
            is ResonResult.Failure -> {
                Log.w(TAG, "Background update failed: ${updateResult.message}")
                if (runAttemptCount >= 3) {
                    Result.failure()
                } else {
                    Result.retry()
                }
            }
        }
    }

    companion object {
        private const val TAG = "Reson:UpdateWorker"
        private const val WORK_NAME = "reson_ytdlp_periodic_update"

        fun enqueuePeriodicUpdate(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .setRequiresBatteryNotLow(requiresBatteryNotLow = true)
                .build()

            val updateRequest = PeriodicWorkRequestBuilder<YtDlpUpdateWorker>(
                repeatInterval = 24,
                repeatIntervalTimeUnit = TimeUnit.HOURS,
                flexTimeInterval = 6,
                flexTimeIntervalUnit = TimeUnit.HOURS,
            )
                .setConstraints(constraints)
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                updateRequest
            )
            Log.i(TAG, "Scheduled periodic yt-dlp update work (24h interval)")
        }
    }
}
