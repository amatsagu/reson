package com.amatsagu.reson.data.local

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.ui.graphics.Color
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Material design theme color choices for Reson.
 */
enum class ThemeColor(
    val id: String,
    val displayName: String,
    val seedColor: Color,
    val isSystem: Boolean = false
) {
    System("system", "System", Color(0xFF6B7280), isSystem = true),
    Mint("mint", "Mint", Color(0xFF7CA183)),
    Ocean("ocean", "Ocean", Color(0xFF42A5F5)),
    Violet("violet", "Violet", Color(0xFFAB47BC)),
    Amber("amber", "Amber", Color(0xFFFFA726)),
    Rose("rose", "Rose", Color(0xFFFF7043)),
    Teal("teal", "Teal", Color(0xFF26A69A));

    companion object {
        fun fromId(id: String): ThemeColor = entries.find { it.id == id } ?: System
    }
}

/**
 * Manages user preferences: theme color, recommendations toggle, language, and listening stats.
 */
class UserPreferences private constructor(context: Context) {

    private val prefs: SharedPreferences = context.applicationContext.getSharedPreferences(
        PREFS_NAME,
        Context.MODE_PRIVATE
    )

    private val _themeColor = MutableStateFlow(
        ThemeColor.fromId(prefs.getString(KEY_THEME_COLOR, ThemeColor.System.id) ?: ThemeColor.System.id)
    )
    val themeColor: StateFlow<ThemeColor> = _themeColor.asStateFlow()

    private val _showRecommended = MutableStateFlow(
        prefs.getBoolean(KEY_SHOW_RECOMMENDED, true)
    )
    val showRecommended: StateFlow<Boolean> = _showRecommended.asStateFlow()

    private val _language = MutableStateFlow(
        prefs.getString(KEY_LANGUAGE, "en") ?: "en"
    )
    val language: StateFlow<String> = _language.asStateFlow()

    private val _totalListeningTimeMs = MutableStateFlow(
        prefs.getLong(KEY_TOTAL_LISTENING_MS, 0L)
    )
    val totalListeningTimeMs: StateFlow<Long> = _totalListeningTimeMs.asStateFlow()

    private val _lastFeedSyncTime = MutableStateFlow(
        prefs.getLong(KEY_LAST_FEED_SYNC_TIME, 0L)
    )
    val lastFeedSyncTime: StateFlow<Long> = _lastFeedSyncTime.asStateFlow()

    private val _lastChannelsSyncTime = MutableStateFlow(
        prefs.getLong(KEY_LAST_CHANNELS_SYNC_TIME, 0L)
    )
    val lastChannelsSyncTime: StateFlow<Long> = _lastChannelsSyncTime.asStateFlow()

    fun updateLastFeedSyncTime(time: Long = System.currentTimeMillis()) {
        prefs.edit().putLong(KEY_LAST_FEED_SYNC_TIME, time).apply()
        _lastFeedSyncTime.value = time
    }

    fun updateLastChannelsSyncTime(time: Long = System.currentTimeMillis()) {
        prefs.edit().putLong(KEY_LAST_CHANNELS_SYNC_TIME, time).apply()
        _lastChannelsSyncTime.value = time
    }

    fun setThemeColor(color: ThemeColor) {
        _themeColor.value = color
        prefs.edit().putString(KEY_THEME_COLOR, color.id).apply()
    }

    fun setShowRecommended(show: Boolean) {
        _showRecommended.value = show
        prefs.edit().putBoolean(KEY_SHOW_RECOMMENDED, show).apply()
    }

    fun setLanguage(lang: String) {
        _language.value = lang
        prefs.edit().putString(KEY_LANGUAGE, lang).apply()
    }

    fun addListeningTime(deltaMs: Long, sync: Boolean = false) {
        if (deltaMs <= 0L) return
        val updated = _totalListeningTimeMs.value + deltaMs
        _totalListeningTimeMs.value = updated
        val editor = prefs.edit().putLong(KEY_TOTAL_LISTENING_MS, updated)
        if (sync) {
            editor.commit()
        } else {
            editor.apply()
        }
    }

    fun resetPreferences() {
        prefs.edit().clear().apply()
        _themeColor.value = ThemeColor.System
        _showRecommended.value = true
        _language.value = "en"
        _totalListeningTimeMs.value = 0L
    }

    companion object {
        private const val PREFS_NAME = "reson_user_preferences"
        private const val KEY_THEME_COLOR = "pref_theme_color"
        private const val KEY_SHOW_RECOMMENDED = "pref_show_recommended"
        private const val KEY_LANGUAGE = "pref_language"
        private const val KEY_TOTAL_LISTENING_MS = "pref_total_listening_ms"
        private const val KEY_LAST_FEED_SYNC_TIME = "pref_last_feed_sync_time"
        private const val KEY_LAST_CHANNELS_SYNC_TIME = "pref_last_channels_sync_time"

        @Volatile
        private var instance: UserPreferences? = null

        fun getInstance(context: Context): UserPreferences {
            return instance ?: synchronized(this) {
                instance ?: UserPreferences(context).also { instance = it }
            }
        }
    }
}
