package com.amatsagu.reson.data

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Fast YouTube autocomplete client.
 * Uses YouTube's lightweight public suggestion API with zero latency.
 */
object SearchSuggestClient {

    private const val TAG = "Reson:Suggest"
    private const val BASE_URL = "https://suggestqueries.google.com/complete/search?client=firefox&ds=yt&q="

    suspend fun getSuggestions(query: String): List<String> = withContext(Dispatchers.IO) {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return@withContext emptyList()

        var connection: HttpURLConnection? = null
        try {
            val encoded = URLEncoder.encode(trimmed, "UTF-8")
            val url = URL(BASE_URL + encoded)
            connection = (url.openConnection() as HttpURLConnection).apply {
                connectTimeout = 3000
                readTimeout = 3000
                requestMethod = "GET"
                setRequestProperty("User-Agent", "Mozilla/5.0")
            }

            if (connection.responseCode == HttpURLConnection.HTTP_OK) {
                val reader = BufferedReader(InputStreamReader(connection.inputStream))
                val response = reader.readText()
                reader.close()

                // Response format: ["query", ["suggestion 1", "suggestion 2", ...]]
                val rootArray = JSONArray(response)
                if (rootArray.length() >= 2) {
                    val suggestionsArray = rootArray.getJSONArray(1)
                    val result = mutableListOf<String>()
                    for (i in 0 until suggestionsArray.length()) {
                        result.add(suggestionsArray.getString(i))
                    }
                    return@withContext result
                }
            }
        } catch (e: Exception) {
            Log.d(TAG, "Autocomplete suggestion error for '$trimmed': ${e.message}")
        } finally {
            connection?.disconnect()
        }

        emptyList()
    }
}
