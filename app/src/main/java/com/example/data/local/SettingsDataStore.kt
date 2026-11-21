package com.example.data.local

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.example.data.model.ThemeMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "zap2_settings")

const val STORAGE_MODE_AUTO = "AUTO"
const val STORAGE_MODE_FIRESTORE = "FIRESTORE"
const val STORAGE_MODE_GITHUB = "GITHUB"

data class UserSettings(
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val dynamicColor: Boolean = true,
    val autoCloudBackup: Boolean = false,
    val lastLocalBackupAtMs: Long = 0L,
    val lastCloudBackupAtMs: Long = 0L,
    val defaultZipExtractUri: String = "",
    val cachedDisplayName: String = "",
    val cachedUsername: String = "",
    val cachedAbout: String = "",
    val cachedPhotoUrl: String = "",
    val cachedBannerUrl: String = "",
    val storageBackendMode: String = STORAGE_MODE_AUTO,
    val githubRepo: String = "",
    val githubBranch: String = "main",
    val githubToken: String = "",
    val notificationsEnabled: Boolean = true,
    val appUpdateUrl: String = "",
    val autoCheckUpdates: Boolean = true,
    val lastInstalledReleaseId: Long = 0L,
    val lastInstalledCommitSha: String = "",
    val lastInstalledRunNumber: Int = 0,
    val pendingInstallReleaseId: Long = 0L,
    val pendingInstallCommitSha: String = "",
    val pendingInstallRunNumber: Int = 0,
    val pendingInstallPreUpdateTimeMs: Long = 0L
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
        val CACHED_USERNAME = stringPreferencesKey("cached_username")
        val CACHED_ABOUT = stringPreferencesKey("cached_about")
        val CACHED_PHOTO_URL = stringPreferencesKey("cached_photo_url")
        val CACHED_BANNER_URL = stringPreferencesKey("cached_banner_url")
        val STORAGE_BACKEND_MODE = stringPreferencesKey("storage_backend_mode")
        val GITHUB_REPO = stringPreferencesKey("github_repo")
        val GITHUB_BRANCH = stringPreferencesKey("github_branch")
        val GITHUB_TOKEN = stringPreferencesKey("github_token")
        val NOTIFICATIONS_ENABLED = booleanPreferencesKey("notifications_enabled")
        val APP_UPDATE_URL = stringPreferencesKey("app_update_url")
        val AUTO_CHECK_UPDATES = booleanPreferencesKey("auto_check_updates")
        val LAST_INSTALLED_RELEASE_ID = longPreferencesKey("last_installed_release_id")
        val LAST_INSTALLED_COMMIT_SHA = stringPreferencesKey("last_installed_commit_sha")
        val LAST_INSTALLED_RUN_NUMBER = intPreferencesKey("last_installed_run_number")
        val PENDING_INSTALL_RELEASE_ID = longPreferencesKey("pending_install_release_id")
        val PENDING_INSTALL_COMMIT_SHA = stringPreferencesKey("pending_install_commit_sha")
        val PENDING_INSTALL_RUN_NUMBER = intPreferencesKey("pending_install_run_number")
        val PENDING_INSTALL_PRE_UPDATE_TIME_MS = longPreferencesKey("pending_install_pre_update_time_ms")
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
            cachedUsername = prefs[Keys.CACHED_USERNAME] ?: "",
            cachedAbout = prefs[Keys.CACHED_ABOUT] ?: "",
            cachedPhotoUrl = prefs[Keys.CACHED_PHOTO_URL] ?: "",
            cachedBannerUrl = prefs[Keys.CACHED_BANNER_URL] ?: "",
            storageBackendMode = prefs[Keys.STORAGE_BACKEND_MODE] ?: STORAGE_MODE_AUTO,
            githubRepo = prefs[Keys.GITHUB_REPO] ?: "",
            githubBranch = (prefs[Keys.GITHUB_BRANCH] ?: "main").ifBlank { "main" },
            githubToken = prefs[Keys.GITHUB_TOKEN] ?: "",
            notificationsEnabled = prefs[Keys.NOTIFICATIONS_ENABLED] ?: true,
            appUpdateUrl = prefs[Keys.APP_UPDATE_URL] ?: "",
            autoCheckUpdates = prefs[Keys.AUTO_CHECK_UPDATES] ?: true,
            lastInstalledReleaseId = prefs[Keys.LAST_INSTALLED_RELEASE_ID] ?: 0L,
            lastInstalledCommitSha = prefs[Keys.LAST_INSTALLED_COMMIT_SHA] ?: "",
            lastInstalledRunNumber = prefs[Keys.LAST_INSTALLED_RUN_NUMBER] ?: 0,
            pendingInstallReleaseId = prefs[Keys.PENDING_INSTALL_RELEASE_ID] ?: 0L,
            pendingInstallCommitSha = prefs[Keys.PENDING_INSTALL_COMMIT_SHA] ?: "",
            pendingInstallRunNumber = prefs[Keys.PENDING_INSTALL_RUN_NUMBER] ?: 0,
            pendingInstallPreUpdateTimeMs = prefs[Keys.PENDING_INSTALL_PRE_UPDATE_TIME_MS] ?: 0L
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

    suspend fun setNotificationsEnabled(enabled: Boolean) {
        context.dataStore.edit { it[Keys.NOTIFICATIONS_ENABLED] = enabled }
    }

    suspend fun setAppUpdateUrl(url: String) {
        context.dataStore.edit { it[Keys.APP_UPDATE_URL] = url.trim() }
    }

    suspend fun setAutoCheckUpdates(enabled: Boolean) {
        context.dataStore.edit { it[Keys.AUTO_CHECK_UPDATES] = enabled }
    }

    suspend fun recordPendingApkInstall(
        releaseId: Long,
        commitSha: String,
        runNumber: Int,
        preInstallLastUpdateTimeMs: Long
    ) {
        context.dataStore.edit { prefs ->
            prefs[Keys.PENDING_INSTALL_RELEASE_ID] = releaseId
            prefs[Keys.PENDING_INSTALL_COMMIT_SHA] = commitSha
            prefs[Keys.PENDING_INSTALL_RUN_NUMBER] = runNumber
            prefs[Keys.PENDING_INSTALL_PRE_UPDATE_TIME_MS] = preInstallLastUpdateTimeMs
        }
    }

    suspend fun reconcileInstalledUpdateIfApplied(currentAppLastUpdateTimeMs: Long) {
        context.dataStore.edit { prefs ->
            val pendingPreTime = prefs[Keys.PENDING_INSTALL_PRE_UPDATE_TIME_MS] ?: 0L
            val pendingReleaseId = prefs[Keys.PENDING_INSTALL_RELEASE_ID] ?: 0L
            val pendingSha = prefs[Keys.PENDING_INSTALL_COMMIT_SHA] ?: ""
            val pendingRun = prefs[Keys.PENDING_INSTALL_RUN_NUMBER] ?: 0
            if (pendingPreTime > 0L && currentAppLastUpdateTimeMs > pendingPreTime) {
                if (pendingReleaseId > 0L) prefs[Keys.LAST_INSTALLED_RELEASE_ID] = pendingReleaseId
                if (pendingSha.isNotBlank()) prefs[Keys.LAST_INSTALLED_COMMIT_SHA] = pendingSha
                if (pendingRun > 0) prefs[Keys.LAST_INSTALLED_RUN_NUMBER] = pendingRun
                prefs.remove(Keys.PENDING_INSTALL_RELEASE_ID)
                prefs.remove(Keys.PENDING_INSTALL_COMMIT_SHA)
                prefs.remove(Keys.PENDING_INSTALL_RUN_NUMBER)
                prefs.remove(Keys.PENDING_INSTALL_PRE_UPDATE_TIME_MS)
            }
        }
    }

    suspend fun saveFreeStorageConfig(
        mode: String,
        githubRepo: String,
        githubBranch: String,
        githubToken: String
    ) {
        val cleanRepo = githubRepo.trim()
            .removePrefix("https://github.com/")
            .removePrefix("http://github.com/")
            .removeSuffix(".git")
            .trim('/')
        val cleanBranch = githubBranch.trim().ifBlank { "main" }
        context.dataStore.edit { prefs ->
            prefs[Keys.STORAGE_BACKEND_MODE] = mode
            prefs[Keys.GITHUB_REPO] = cleanRepo
            prefs[Keys.GITHUB_BRANCH] = cleanBranch
            prefs[Keys.GITHUB_TOKEN] = githubToken.trim()
        }
    }

    suspend fun saveLocalProfileCache(
        displayName: String? = null,
        username: String? = null,
        about: String? = null,
        photoUrl: String? = null,
        bannerUrl: String? = null
    ) {
        context.dataStore.edit { prefs ->
            if (displayName != null) prefs[Keys.CACHED_DISPLAY_NAME] = displayName
            if (username != null) prefs[Keys.CACHED_USERNAME] = username
            if (about != null) prefs[Keys.CACHED_ABOUT] = about
            if (photoUrl != null) prefs[Keys.CACHED_PHOTO_URL] = photoUrl
            if (bannerUrl != null) prefs[Keys.CACHED_BANNER_URL] = bannerUrl
        }
    }
}
