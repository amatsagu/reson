package com.amatsagu.reson.data

import android.util.Log
import com.amatsagu.reson.core.coroutines.ResonDispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

object SearchSuggestClient {

    private const val TAG = "Reson:Suggest"
    private const val BASE_URL = "https://suggestqueries.google.com/complete/search?client=firefox&ds=yt&q="
    private const val MAX_SUGGESTIONS = 10

    suspend fun getSuggestions(query: String): List<String> = withContext(ResonDispatchers.Network) {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return@withContext emptyList()

        var connection: HttpURLConnection? = null
        try {
            val encoded = URLEncoder.encode(trimmed, StandardCharsets.UTF_8.name())
            val url = URL(BASE_URL + encoded)
            connection = (url.openConnection() as HttpURLConnection).apply {
                connectTimeout = 3000
                readTimeout = 3000
                requestMethod = "GET"
                setRequestProperty("User-Agent", "Mozilla/5.0")
            }

            if (connection.responseCode == HttpURLConnection.HTTP_OK) {
                val response = connection.inputStream.bufferedReader(StandardCharsets.UTF_8).use { it.readText() }

                // Response format: ["query", ["suggestion 1", "suggestion 2", ...]]
                val rootArray = JSONArray(response)
                if (rootArray.length() >= 2) {
                    val suggestionsArray = rootArray.getJSONArray(1)
                    val result = mutableListOf<String>()
                    val limit = minOf(suggestionsArray.length(), MAX_SUGGESTIONS)
                    for (i in 0 until limit) {
                        result.add(suggestionsArray.getString(i))
                    }
                    return@withContext result
                }
            } else {
                connection.errorStream?.close()
            }
        } catch (e: Exception) {
            Log.d(TAG, "Autocomplete suggestion error for '$trimmed': ${e.message}")
        } finally {
            connection?.disconnect()
        }

        emptyList()
    }
}
