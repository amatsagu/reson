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

object AvatarManager {
    private const val TAG = "Reson:Avatar"
    private const val MAX_IMAGE_DIMEN = 160
    private const val MAX_DOWNLOAD_BYTES = 5 * 1024 * 1024

    suspend fun fetchChannelAvatarUrl(channelId: String, handle: String = ""): String? = withContext(ResonDispatchers.Network) {
        val cleanId = channelId.trim()
        val cleanHandle = handle.trim()
        val target = when {
            cleanId.startsWith("UC") -> "https://www.youtube.com/channel/$cleanId"
            cleanHandle.isNotBlank() && cleanHandle.startsWith("@") -> "https://www.youtube.com/$cleanHandle"
            cleanHandle.isNotBlank() -> "https://www.youtube.com/@$cleanHandle"
            cleanId.startsWith("@") -> "https://www.youtube.com/$cleanId"
            cleanId.isNotBlank() -> "https://www.youtube.com/channel/$cleanId"
            else -> null
        } ?: return@withContext null

        var connection: HttpURLConnection? = null
        try {
            val url = URL(target)
            connection = (url.openConnection() as HttpURLConnection).apply {
                connectTimeout = 6_000
                readTimeout = 6_000
                instanceFollowRedirects = true
                requestMethod = "GET"
                setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
                setRequestProperty("Accept-Language", "en-US,en;q=0.9")
            }
            if ((connection.responseCode in 200..299)) {
                val html = connection.inputStream.bufferedReader().use { it.readText() }
                val match = Regex("""<meta property="og:image" content="([^"]+)"""").find(html)
                match?.groupValues?.get(1)
            } else {
                null
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed resolving avatar for $target: ${e.message}")
            null
        } finally {
            connection?.disconnect()
        }
    }

    fun extractAvatarUrlFromThumbnails(thumbs: org.json.JSONArray?): String? {
        if (thumbs == null || thumbs.length() == 0) return null

        var bestUrl: String? = null
        var bestScore = -1

        for (i in 0 until thumbs.length()) {
            val t = thumbs.optJSONObject(i) ?: continue
            val u = t.optString("url", "").trim()
            if (u.isEmpty()) continue

            val id = t.optString("id", "").lowercase()
            val w = t.optInt("width", 0)
            val h = t.optInt("height", 0)

            // Skip banner images explicitly
            if (id.contains("banner") || (w > 0 && h > 0 && w.toFloat() / h > 2.0f)) {
                continue
            }

            var score = 0
            if (id == "avatar_uncropped") score += 100
            else if (id.contains("avatar")) score += 80
            else if (w > 0 && h > 0 && w == h) score += 60
            else if (u.contains("=s") && !u.contains("=w")) score += 40

            if (w in 88..500) score += 20

            if (score > bestScore) {
                bestScore = score
                bestUrl = u
            }
        }

        val finalUrl = bestUrl ?: thumbs.optJSONObject(0)?.optString("url", "")?.ifEmpty { null }
        return finalUrl?.let { if (it.startsWith("//")) "https:$it" else it }
    }

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
                setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
                setRequestProperty("Accept", "image/*,*/*;q=0.8")
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

            if (rawBytes.size < 40_000) {
                return@withContext Base64.encodeToString(rawBytes, Base64.NO_WRAP)
            }

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
