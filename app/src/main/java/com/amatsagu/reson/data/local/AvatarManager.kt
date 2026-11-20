package com.amatsagu.reson.data.local

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * Downloads and prepares channel avatars as compact Base64 strings for instant offline SQLite persistence.
 */
object AvatarManager {
    private const val TAG = "Reson:Avatar"
    private const val MAX_IMAGE_DIMEN = 160 // Optimal for ~48-56dp circular avatar

    /**
     * Downloads an avatar from the given URL and converts it into a Base64 string.
     * Automatically downscales images larger than 160px to preserve minimal SQLite storage.
     */
    suspend fun downloadAvatarAsBase64(imageUrl: String): String? = withContext(Dispatchers.IO) {
        val trimmed = imageUrl.trim()
        if (trimmed.isEmpty()) return@withContext null

        try {
            val url = URL(trimmed)
            val connection = url.openConnection() as HttpURLConnection
            connection.connectTimeout = 8_000
            connection.readTimeout = 8_000
            connection.instanceFollowRedirects = true
            connection.requestMethod = "GET"
            connection.connect()

            if (connection.responseCode !in 200..299) {
                Log.w(TAG, "Avatar download HTTP ${connection.responseCode} for $trimmed")
                return@withContext null
            }

            val rawBytes = connection.inputStream.use { it.readBytes() }
            if (rawBytes.isEmpty()) return@withContext null

            // If byte array is already small (< 40KB), encode directly
            if (rawBytes.size < 40_000) {
                return@withContext Base64.encodeToString(rawBytes, Base64.NO_WRAP)
            }

            // Downscale large avatar to keep SQLite footprint ultra-lean
            val bitmap = BitmapFactory.decodeByteArray(rawBytes, 0, rawBytes.size) ?: return@withContext null
            val scaled = if (bitmap.width > MAX_IMAGE_DIMEN || bitmap.height > MAX_IMAGE_DIMEN) {
                val ratio = bitmap.width.toFloat() / bitmap.height.toFloat()
                val targetW = if (ratio >= 1f) MAX_IMAGE_DIMEN else (MAX_IMAGE_DIMEN * ratio).toInt()
                val targetH = if (ratio <= 1f) MAX_IMAGE_DIMEN else (MAX_IMAGE_DIMEN / ratio).toInt()
                Bitmap.createScaledBitmap(bitmap, targetW.coerceAtLeast(1), targetH.coerceAtLeast(1), true)
            } else {
                bitmap
            }

            val outputStream = ByteArrayOutputStream()
            scaled.compress(Bitmap.CompressFormat.JPEG, 85, outputStream)
            val compressedBytes = outputStream.toByteArray()

            if (scaled != bitmap) {
                scaled.recycle()
            }
            bitmap.recycle()

            Base64.encodeToString(compressedBytes, Base64.NO_WRAP)
        } catch (e: Exception) {
            Log.w(TAG, "Failed downloading avatar from $trimmed: ${e.message}")
            null
        }
    }
}
