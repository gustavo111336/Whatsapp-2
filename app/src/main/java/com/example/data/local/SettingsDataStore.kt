package com.example.data.local

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.example.data.model.ThemeMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "zap2_settings")

data class UserSettings(
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val dynamicColor: Boolean = true,
    val autoCloudBackup: Boolean = false,
    val lastLocalBackupAtMs: Long = 0L,
    val lastCloudBackupAtMs: Long = 0L,
    val defaultZipExtractUri: String = "",
    val cachedDisplayName: String = "",
    val cachedAbout: String = "",
    val cachedPhotoUrl: String = "",
    val cachedBannerUrl: String = ""
)

class SettingsDataStore(private val context: Context) {
    private object Keys {
        val THEME_MODE = stringPreferencesKey("theme_mode")
        val DYNAMIC_COLOR = booleanPreferencesKey("dynamic_color")
        val AUTO_CLOUD_BACKUP = booleanPreferencesKey("auto_cloud_backup")
        val LAST_LOCAL_BACKUP_AT = longPreferencesKey("last_local_backup_at")
        val LAST_CLOUD_BACKUP_AT = longPreferencesKey("last_cloud_backup_at")
        val DEFAULT_ZIP_EXTRACT_URI = stringPreferencesKey("default_zip_extract_uri")
        val CACHED_DISPLAY_NAME = stringPreferencesKey("cached_display_name")
        val CACHED_ABOUT = stringPreferencesKey("cached_about")
        val CACHED_PHOTO_URL = stringPreferencesKey("cached_photo_url")
        val CACHED_BANNER_URL = stringPreferencesKey("cached_banner_url")
    }

    val settingsFlow: Flow<UserSettings> = context.dataStore.data.map { prefs ->
        val modeName = prefs[Keys.THEME_MODE] ?: ThemeMode.SYSTEM.name
        val themeMode = ThemeMode.entries.firstOrNull { it.name == modeName } ?: ThemeMode.SYSTEM
        UserSettings(
            themeMode = themeMode,
            dynamicColor = prefs[Keys.DYNAMIC_COLOR] ?: true,
            autoCloudBackup = prefs[Keys.AUTO_CLOUD_BACKUP] ?: false,
            lastLocalBackupAtMs = prefs[Keys.LAST_LOCAL_BACKUP_AT] ?: 0L,
            lastCloudBackupAtMs = prefs[Keys.LAST_CLOUD_BACKUP_AT] ?: 0L,
            defaultZipExtractUri = prefs[Keys.DEFAULT_ZIP_EXTRACT_URI] ?: "",
            cachedDisplayName = prefs[Keys.CACHED_DISPLAY_NAME] ?: "",
            cachedAbout = prefs[Keys.CACHED_ABOUT] ?: "",
            cachedPhotoUrl = prefs[Keys.CACHED_PHOTO_URL] ?: "",
            cachedBannerUrl = prefs[Keys.CACHED_BANNER_URL] ?: ""
        )
    }

    suspend fun setThemeMode(mode: ThemeMode) {
        context.dataStore.edit { it[Keys.THEME_MODE] = mode.name }
    }

    suspend fun setDynamicColor(enabled: Boolean) {
        context.dataStore.edit { it[Keys.DYNAMIC_COLOR] = enabled }
    }

    suspend fun setAutoCloudBackup(enabled: Boolean) {
        context.dataStore.edit { it[Keys.AUTO_CLOUD_BACKUP] = enabled }
    }

    suspend fun recordLocalBackup(timestampMs: Long) {
        context.dataStore.edit { it[Keys.LAST_LOCAL_BACKUP_AT] = timestampMs }
    }

    suspend fun recordCloudBackup(timestampMs: Long) {
        context.dataStore.edit { it[Keys.LAST_CLOUD_BACKUP_AT] = timestampMs }
    }

    suspend fun setDefaultZipExtractUri(uriString: String) {
        context.dataStore.edit { it[Keys.DEFAULT_ZIP_EXTRACT_URI] = uriString }
    }

    suspend fun saveLocalProfileCache(
        displayName: String? = null,
        about: String? = null,
        photoUrl: String? = null,
        bannerUrl: String? = null
    ) {
        context.dataStore.edit { prefs ->
            if (displayName != null) prefs[Keys.CACHED_DISPLAY_NAME] = displayName
            if (about != null) prefs[Keys.CACHED_ABOUT] = about
            if (photoUrl != null) prefs[Keys.CACHED_PHOTO_URL] = photoUrl
            if (bannerUrl != null) prefs[Keys.CACHED_BANNER_URL] = bannerUrl
        }
    }
}
