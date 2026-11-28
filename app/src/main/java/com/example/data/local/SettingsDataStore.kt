package com.example.data.local

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.example.data.model.ThemeMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import org.json.JSONObject

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "zap2_settings")

const val STORAGE_MODE_AUTO = "AUTO"
const val STORAGE_MODE_FIRESTORE = "FIRESTORE"
const val STORAGE_MODE_GITHUB = "GITHUB"

const val DEFAULT_AI_MODEL = "gemini-3.8-flash"
const val AI_PROVIDER_AUTO = "AUTO"
const val AI_PROVIDER_GEMINI = "GEMINI"
const val AI_PROVIDER_GROK = "GROK"
const val AI_PROVIDER_CHATGPT = "CHATGPT"
const val AI_PROVIDER_CLAUDE = "CLAUDE"
const val AI_PROVIDER_GROQ = "GROQ"
const val AI_PROVIDER_DEEPSEEK = "DEEPSEEK"
const val AI_PROVIDER_OPENROUTER = "OPENROUTER"
const val AI_PROVIDER_OPENAI = "OPENAI_COMPATIBLE"
const val AI_PROVIDER_CUSTOM = "CUSTOM"

const val EMAIL_DELIVERY_AUTO = "AUTO"
const val EMAIL_DELIVERY_GMAIL_SMTP = "GMAIL_SMTP"
const val EMAIL_DELIVERY_APPS_SCRIPT = "APPS_SCRIPT"
const val EMAIL_DELIVERY_RESEND = "RESEND"

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
    val pendingInstallPreUpdateTimeMs: Long = 0L,
    val aiApiKey: String = "",
    val aiModel: String = DEFAULT_AI_MODEL,
    val aiProvider: String = AI_PROVIDER_AUTO,
    val aiCustomBaseUrl: String = "",
    val aiPersonaName: String = "",
    val aiCustomInstructions: String = "",
    val aiAutoReplyPeers: Boolean = true,
    val aiSavedKeysJson: String = "{}",
    val aiJoinedChatIds: Set<String> = emptySet(),
    val emailStateUid: String = "",
    val emailVerificationEnabled: Boolean = true,
    val emailCodeVerified: Boolean = false,
    val recoveryEmail: String = "",
    val recoveryEmailVerified: Boolean = false,
    val emailDeliveryMode: String = EMAIL_DELIVERY_AUTO,
    val emailSenderAddress: String = "",
    val emailSmtpAppPassword: String = "",
    val emailWebhookUrl: String = "",
    val emailResendApiKey: String = "",
    val emailResendFrom: String = ""
) {
    fun savedKeyForProvider(providerId: String): String {
        val stored = runCatching {
            JSONObject(aiSavedKeysJson.ifBlank { "{}" }).optString(providerId, "")
        }.getOrDefault("")
        return if (stored.isNotBlank()) stored else if (providerId == aiProvider) aiApiKey else ""
    }
}

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
        val AI_API_KEY = stringPreferencesKey("ai_api_key")
        val AI_MODEL = stringPreferencesKey("ai_model")
        val AI_PROVIDER = stringPreferencesKey("ai_provider")
        val AI_CUSTOM_BASE_URL = stringPreferencesKey("ai_custom_base_url")
        val AI_PERSONA_NAME = stringPreferencesKey("ai_persona_name")
        val AI_CUSTOM_INSTRUCTIONS = stringPreferencesKey("ai_custom_instructions")
        val AI_AUTO_REPLY_PEERS = booleanPreferencesKey("ai_auto_reply_peers")
        val AI_SAVED_KEYS_JSON = stringPreferencesKey("ai_saved_keys_json")
        val AI_JOINED_CHAT_IDS = stringSetPreferencesKey("ai_joined_chat_ids")
        val EMAIL_STATE_UID = stringPreferencesKey("email_state_uid")
        val EMAIL_VERIFICATION_ENABLED = booleanPreferencesKey("email_verification_enabled")
        val EMAIL_CODE_VERIFIED = booleanPreferencesKey("email_code_verified")
        val RECOVERY_EMAIL = stringPreferencesKey("recovery_email")
        val RECOVERY_EMAIL_VERIFIED = booleanPreferencesKey("recovery_email_verified")
        val EMAIL_DELIVERY_MODE = stringPreferencesKey("email_delivery_mode")
        val EMAIL_SENDER_ADDRESS = stringPreferencesKey("email_sender_address")
        val EMAIL_SMTP_APP_PASSWORD = stringPreferencesKey("email_smtp_app_password")
        val EMAIL_WEBHOOK_URL = stringPreferencesKey("email_webhook_url")
        val EMAIL_RESEND_API_KEY = stringPreferencesKey("email_resend_api_key")
        val EMAIL_RESEND_FROM = stringPreferencesKey("email_resend_from")
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
            pendingInstallPreUpdateTimeMs = prefs[Keys.PENDING_INSTALL_PRE_UPDATE_TIME_MS] ?: 0L,
            aiApiKey = prefs[Keys.AI_API_KEY] ?: "",
            aiModel = (prefs[Keys.AI_MODEL] ?: DEFAULT_AI_MODEL).ifBlank { DEFAULT_AI_MODEL },
            aiProvider = (prefs[Keys.AI_PROVIDER] ?: AI_PROVIDER_AUTO).ifBlank { AI_PROVIDER_AUTO },
            aiCustomBaseUrl = prefs[Keys.AI_CUSTOM_BASE_URL] ?: "",
            aiPersonaName = prefs[Keys.AI_PERSONA_NAME] ?: "",
            aiCustomInstructions = prefs[Keys.AI_CUSTOM_INSTRUCTIONS] ?: "",
            aiAutoReplyPeers = prefs[Keys.AI_AUTO_REPLY_PEERS] ?: true,
            aiSavedKeysJson = prefs[Keys.AI_SAVED_KEYS_JSON] ?: "{}",
            aiJoinedChatIds = prefs[Keys.AI_JOINED_CHAT_IDS] ?: emptySet(),
            emailStateUid = prefs[Keys.EMAIL_STATE_UID] ?: "",
            emailVerificationEnabled = prefs[Keys.EMAIL_VERIFICATION_ENABLED] ?: true,
            emailCodeVerified = prefs[Keys.EMAIL_CODE_VERIFIED] ?: false,
            recoveryEmail = prefs[Keys.RECOVERY_EMAIL] ?: "",
            recoveryEmailVerified = prefs[Keys.RECOVERY_EMAIL_VERIFIED] ?: false,
            emailDeliveryMode = (prefs[Keys.EMAIL_DELIVERY_MODE] ?: EMAIL_DELIVERY_AUTO).ifBlank { EMAIL_DELIVERY_AUTO },
            emailSenderAddress = prefs[Keys.EMAIL_SENDER_ADDRESS] ?: "",
            emailSmtpAppPassword = prefs[Keys.EMAIL_SMTP_APP_PASSWORD] ?: "",
            emailWebhookUrl = prefs[Keys.EMAIL_WEBHOOK_URL] ?: "",
            emailResendApiKey = prefs[Keys.EMAIL_RESEND_API_KEY] ?: "",
            emailResendFrom = prefs[Keys.EMAIL_RESEND_FROM] ?: ""
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

    suspend fun saveAiConfig(
        apiKey: String,
        model: String,
        provider: String,
        customBaseUrl: String,
        personaName: String? = null,
        customInstructions: String? = null,
        autoReplyPeers: Boolean? = null
    ) {
        val cleanModel = model.trim().trim('*', '`', '"', '\'').ifBlank { DEFAULT_AI_MODEL }
        val cleanProvider = provider.trim().ifBlank { AI_PROVIDER_AUTO }
        val cleanKey = apiKey.trim()
        context.dataStore.edit { prefs ->
            prefs[Keys.AI_API_KEY] = cleanKey
            prefs[Keys.AI_MODEL] = cleanModel
            prefs[Keys.AI_PROVIDER] = cleanProvider
            prefs[Keys.AI_CUSTOM_BASE_URL] = customBaseUrl.trim()
            if (personaName != null) {
                prefs[Keys.AI_PERSONA_NAME] = personaName.trim().take(48)
            }
            if (customInstructions != null) {
                prefs[Keys.AI_CUSTOM_INSTRUCTIONS] = customInstructions.trim().take(600)
            }
            if (autoReplyPeers != null) {
                prefs[Keys.AI_AUTO_REPLY_PEERS] = autoReplyPeers
            }
            val mapJson = runCatching {
                JSONObject(prefs[Keys.AI_SAVED_KEYS_JSON]?.ifBlank { "{}" } ?: "{}")
            }.getOrElse { JSONObject() }
            if (cleanKey.isNotBlank()) {
                mapJson.put(cleanProvider, cleanKey)
            } else {
                mapJson.remove(cleanProvider)
            }
            prefs[Keys.AI_SAVED_KEYS_JSON] = mapJson.toString()
        }
    }

    suspend fun setAiModel(model: String) {
        val cleanModel = model.trim().trim('*', '`', '"', '\'').ifBlank { DEFAULT_AI_MODEL }
        context.dataStore.edit { prefs ->
            prefs[Keys.AI_MODEL] = cleanModel
        }
    }

    suspend fun setChatAiJoined(chatId: String, enabled: Boolean) {
        if (chatId.isBlank()) return
        context.dataStore.edit { prefs ->
            val current = (prefs[Keys.AI_JOINED_CHAT_IDS] ?: emptySet()).toMutableSet()
            if (enabled) {
                current.add(chatId)
            } else {
                current.remove(chatId)
            }
            prefs[Keys.AI_JOINED_CHAT_IDS] = current
        }
    }

    suspend fun setAiAutoReplyPeers(enabled: Boolean) {
        context.dataStore.edit { prefs ->
            prefs[Keys.AI_AUTO_REPLY_PEERS] = enabled
        }
    }

    suspend fun saveEmailSecurityState(
        uid: String,
        verificationEnabled: Boolean? = null,
        codeVerified: Boolean? = null,
        recoveryEmail: String? = null,
        recoveryEmailVerified: Boolean? = null
    ) {
        context.dataStore.edit { prefs ->
            if (uid.isNotBlank() && prefs[Keys.EMAIL_STATE_UID] != uid) {
                prefs[Keys.EMAIL_STATE_UID] = uid
                prefs[Keys.EMAIL_VERIFICATION_ENABLED] = verificationEnabled ?: true
                prefs[Keys.EMAIL_CODE_VERIFIED] = codeVerified ?: false
                prefs[Keys.RECOVERY_EMAIL] = recoveryEmail?.trim().orEmpty()
                prefs[Keys.RECOVERY_EMAIL_VERIFIED] = recoveryEmailVerified ?: false
            } else {
                if (uid.isNotBlank()) prefs[Keys.EMAIL_STATE_UID] = uid
                if (verificationEnabled != null) prefs[Keys.EMAIL_VERIFICATION_ENABLED] = verificationEnabled
                if (codeVerified != null) prefs[Keys.EMAIL_CODE_VERIFIED] = codeVerified
                if (recoveryEmail != null) prefs[Keys.RECOVERY_EMAIL] = recoveryEmail.trim()
                if (recoveryEmailVerified != null) prefs[Keys.RECOVERY_EMAIL_VERIFIED] = recoveryEmailVerified
            }
        }
    }

    suspend fun saveEmailDeliveryConfig(
        mode: String,
        senderAddress: String,
        smtpAppPassword: String,
        webhookUrl: String,
        resendApiKey: String,
        resendFrom: String
    ) {
        context.dataStore.edit { prefs ->
            prefs[Keys.EMAIL_DELIVERY_MODE] = mode.trim().ifBlank { EMAIL_DELIVERY_AUTO }
            prefs[Keys.EMAIL_SENDER_ADDRESS] = senderAddress.trim().lowercase()
            prefs[Keys.EMAIL_SMTP_APP_PASSWORD] = smtpAppPassword.trim()
            prefs[Keys.EMAIL_WEBHOOK_URL] = webhookUrl.trim()
            prefs[Keys.EMAIL_RESEND_API_KEY] = resendApiKey.trim()
            prefs[Keys.EMAIL_RESEND_FROM] = resendFrom.trim()
        }
    }
}
