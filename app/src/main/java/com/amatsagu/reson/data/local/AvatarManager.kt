package com.amatsagu.reson.data.local

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import android.util.Log
import com.amatsagu.reson.core.coroutines.ResonDispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * Downloads and prepares channel avatars as compact Base64 strings for instant offline SQLite persistence.
 */
object AvatarManager {
    private const val TAG = "Reson:Avatar"
    private const val MAX_IMAGE_DIMEN = 160 // Optimal for ~48-56dp circular avatar
    private const val MAX_DOWNLOAD_BYTES = 5 * 1024 * 1024 // 5 MB safety bound

    /**
     * Downloads an avatar from the given URL and converts it into a Base64 string.
     * Automatically downscales images larger than 160px to preserve minimal SQLite storage.
     */
    suspend fun downloadAvatarAsBase64(imageUrl: String): String? = withContext(ResonDispatchers.Network) {
        val trimmed = imageUrl.trim()
        if (trimmed.isEmpty()) return@withContext null

        var connection: HttpURLConnection? = null
        try {
            val url = URL(trimmed)
            connection = (url.openConnection() as HttpURLConnection).apply {
                connectTimeout = 8_000
                readTimeout = 8_000
                instanceFollowRedirects = true
                requestMethod = "GET"
            }
            connection.connect()

            if (connection.responseCode !in 200..299) {
                Log.w(TAG, "Avatar download HTTP ${connection.responseCode} for $trimmed")
                connection.errorStream?.close()
                return@withContext null
            }

            val rawBytes = connection.inputStream.use { stream ->
                readBytesBounded(stream, MAX_DOWNLOAD_BYTES)
            }
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

            val compressedBytes = ByteArrayOutputStream().use { outputStream ->
                scaled.compress(Bitmap.CompressFormat.JPEG, 85, outputStream)
                outputStream.toByteArray()
            }

            if (scaled != bitmap) {
                scaled.recycle()
            }
            bitmap.recycle()

            Base64.encodeToString(compressedBytes, Base64.NO_WRAP)
        } catch (e: Exception) {
            Log.w(TAG, "Failed downloading avatar from $trimmed: ${e.message}")
            null
        } finally {
            connection?.disconnect()
        }
    }

    private fun readBytesBounded(stream: InputStream, maxBytes: Int): ByteArray {
        val buffer = ByteArray(8192)
        val baos = ByteArrayOutputStream()
        var total = 0
        var read: Int
        while (stream.read(buffer).also { read = it } != -1) {
            baos.write(buffer, 0, read)
            total += read
            if (total > maxBytes) {
                Log.w(TAG, "Avatar exceeded safety bound of $maxBytes bytes")
                return ByteArray(0)
            }
        }
        return baos.toByteArray()
    }
}
