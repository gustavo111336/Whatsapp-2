package com.example.ui.backup

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.local.SettingsDataStore
import com.example.data.local.UserSettings
import com.example.data.model.BackupSummary
import com.example.data.model.ThemeMode
import com.example.data.model.UserProfile
import com.example.data.remote.AuthRepository
import com.example.data.remote.BackupRepository
import com.example.data.remote.FreeMediaStorageManager
import com.example.data.remote.UserAndContactsRepository
import com.example.data.remote.unwrapFirestoreException
import com.example.service.ApkInstallLaunchResult
import com.example.service.AppUpdateManager
import com.example.service.AppUpdateReleaseInfo
import com.example.service.VerifiedApkInfo
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.File

sealed interface UsernameAvailability {
    data object Idle : UsernameAvailability
    data class Checking(val handle: String) : UsernameAvailability
    data class Available(val handle: String) : UsernameAvailability
    data class Taken(val handle: String) : UsernameAvailability
    data class Invalid(val reason: String) : UsernameAvailability
}

data class AppUpdateUiState(
    val isResolving: Boolean = false,
    val isDownloading: Boolean = false,
    val downloadFraction: Float? = null,
    val downloadedBytes: Long = 0L,
    val totalBytes: Long = -1L,
    val resolvedRelease: AppUpdateReleaseInfo? = null,
    val verifiedApk: VerifiedApkInfo? = null,
    val needsInstallPermissionDialog: Boolean = false,
    val awaitingInstallPermission: Boolean = false,
    val statusText: String? = null,
    val errorText: String? = null
)

class BackupViewModel(
    private val authRepository: AuthRepository,
    private val userRepository: UserAndContactsRepository,
    private val backupRepository: BackupRepository,
    private val settingsStore: SettingsDataStore,
    private val freeStorageManager: FreeMediaStorageManager,
    private val appUpdateManager: AppUpdateManager,
    val currentUserId: String
) : ViewModel() {

    val myProfile: StateFlow<UserProfile?> = userRepository.observeMyProfile(currentUserId)
        .catch { error ->
            val root = error.unwrapFirestoreException()
            if (root == error && error is CancellationException) throw error
            emit(null)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L), null)

    val userSettings: StateFlow<UserSettings> = settingsStore.settingsFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L), UserSettings())

    private val _localBackups = MutableStateFlow<List<BackupSummary>>(emptyList())
    val localBackups: StateFlow<List<BackupSummary>> = _localBackups.asStateFlow()

    private val _isBusy = MutableStateFlow(false)
    val isBusy: StateFlow<Boolean> = _isBusy.asStateFlow()

    private val _statusMessage = MutableStateFlow<String?>(null)
    val statusMessage: StateFlow<String?> = _statusMessage.asStateFlow()

    private val _pendingExportFile = MutableStateFlow<File?>(null)
    val pendingExportFile: StateFlow<File?> = _pendingExportFile.asStateFlow()

    private val _mediaUploadProgress = MutableStateFlow<Float?>(null)
    val mediaUploadProgress: StateFlow<Float?> = _mediaUploadProgress.asStateFlow()

    private val _usernameAvailability = MutableStateFlow<UsernameAvailability>(UsernameAvailability.Idle)
    val usernameAvailability: StateFlow<UsernameAvailability> = _usernameAvailability.asStateFlow()

    private val _appUpdateState = MutableStateFlow(AppUpdateUiState())
    val appUpdateState: StateFlow<AppUpdateUiState> = _appUpdateState.asStateFlow()

    private var usernameCheckJob: Job? = null
    private var appUpdateJob: Job? = null

    init {
        refreshLocalBackups()
        appUpdateManager.getCachedUpdateApk()?.let { cached ->
            appUpdateManager.validateApkFile(cached).onSuccess { verified ->
                _appUpdateState.value = _appUpdateState.value.copy(
                    verifiedApk = verified,
                    statusText = "APK pronto para instalar (${verified.sizeBytes / (1024 * 1024)} MB)."
                )
            }
        }
    }

    fun refreshLocalBackups() {
        _localBackups.value = backupRepository.listLocalBackups()
    }

    fun checkUsername(rawInput: String) {
        usernameCheckJob?.cancel()
        val normalized = UserAndContactsRepository.normalizeUsername(rawInput)
        val currentSaved = myProfile.value?.username?.let(UserAndContactsRepository::normalizeUsername)
        if (normalized.isEmpty()) {
            _usernameAvailability.value = UsernameAvailability.Invalid("Digite um @username (mín. 3 caracteres).")
            return
        }
        if (!UserAndContactsRepository.isValidUsername(normalized)) {
            _usernameAvailability.value = UsernameAvailability.Invalid(
                "Use 3 a 24 caracteres: a-z, 0-9, ponto (.) ou underline (_)."
            )
            return
        }
        if (normalized == currentSaved) {
            _usernameAvailability.value = UsernameAvailability.Idle
            return
        }
        usernameCheckJob = viewModelScope.launch {
            _usernameAvailability.value = UsernameAvailability.Checking(normalized)
            delay(350)
            userRepository.checkUsernameAvailability(normalized)
                .onSuccess { available ->
                    _usernameAvailability.value = if (available) {
                        UsernameAvailability.Available(normalized)
                    } else {
                        UsernameAvailability.Taken(normalized)
                    }
                }
                .onFailure { err ->
                    _usernameAvailability.value = UsernameAvailability.Invalid(
                        err.localizedMessage ?: "Não foi possível verificar disponibilidade."
                    )
                }
        }
    }

    fun updateProfile(
        displayName: String,
        username: String,
        about: String,
        phoneNumber: String?
    ) {
        viewModelScope.launch {
            _isBusy.value = true
            userRepository.updateProfile(
                displayName = displayName,
                username = username,
                about = about,
                phoneNumber = phoneNumber
            ).onSuccess { savedHandle ->
                _usernameAvailability.value = UsernameAvailability.Idle
                _statusMessage.value = "Perfil e @$savedHandle atualizados no Cloud Firestore."
            }.onFailure { err ->
                _statusMessage.value = err.localizedMessage ?: "Falha ao atualizar perfil."
            }
            _isBusy.value = false
        }
    }

    fun uploadBannerFromGallery(uri: Uri) {
        viewModelScope.launch {
            _isBusy.value = true
            _mediaUploadProgress.value = 0f
            userRepository.updateProfileBanner(
                localImageUri = uri,
                isVideo = false,
                onProgress = { _mediaUploadProgress.value = it }
            ).onSuccess {
                _statusMessage.value = "Banner atualizado na nuvem e salvo no cache local!"
            }.onFailure { err ->
                _statusMessage.value = err.localizedMessage ?: "Falha ao enviar banner."
            }
            _mediaUploadProgress.value = null
            _isBusy.value = false
        }
    }

    fun uploadBannerVideo(uri: Uri) {
        viewModelScope.launch {
            _isBusy.value = true
            _mediaUploadProgress.value = 0f
            userRepository.updateProfileBanner(
                localImageUri = uri,
                isVideo = true,
                onProgress = { _mediaUploadProgress.value = it }
            ).onSuccess {
                _statusMessage.value = "Banner em vídeo (MP4 em loop) atualizado!"
            }.onFailure { err ->
                _statusMessage.value = err.localizedMessage ?: "Falha ao enviar vídeo do banner."
            }
            _mediaUploadProgress.value = null
            _isBusy.value = false
        }
    }

    fun saveBannerUrl(url: String) {
        viewModelScope.launch {
            _isBusy.value = true
            userRepository.updateProfileBanner(bannerUrl = url)
                .onSuccess { saved ->
                    _statusMessage.value = if (saved.isBlank()) {
                        "Banner removido do perfil."
                    } else {
                        "Banner salvo na nuvem e no backup local (imagem, GIF ou MP4)!"
                    }
                }
                .onFailure { err ->
                    _statusMessage.value = err.localizedMessage ?: "URL inválida para banner."
                }
            _isBusy.value = false
        }
    }

    fun uploadAvatarImageOrGif(uri: Uri) {
        viewModelScope.launch {
            _isBusy.value = true
            _mediaUploadProgress.value = 0f
            userRepository.updateProfileAvatar(
                localMediaUri = uri,
                isVideo = false,
                onProgress = { _mediaUploadProgress.value = it }
            ).onSuccess {
                _statusMessage.value = "Foto de perfil atualizada!"
            }.onFailure { err ->
                _statusMessage.value = err.localizedMessage ?: "Falha ao atualizar foto de perfil."
            }
            _mediaUploadProgress.value = null
            _isBusy.value = false
        }
    }

    fun uploadAvatarVideo(uri: Uri) {
        viewModelScope.launch {
            _isBusy.value = true
            _mediaUploadProgress.value = 0f
            userRepository.updateProfileAvatar(
                localMediaUri = uri,
                isVideo = true,
                onProgress = { _mediaUploadProgress.value = it }
            ).onSuccess {
                _statusMessage.value = "Avatar animado (MP4 em loop) atualizado!"
            }.onFailure { err ->
                _statusMessage.value = err.localizedMessage ?: "Falha ao enviar vídeo de perfil."
            }
            _mediaUploadProgress.value = null
            _isBusy.value = false
        }
    }

    fun saveAvatarUrl(url: String) {
        viewModelScope.launch {
            _isBusy.value = true
            userRepository.updateProfileAvatar(avatarUrl = url)
                .onSuccess { saved ->
                    _statusMessage.value = if (saved.isBlank()) {
                        "Foto de perfil removida."
                    } else {
                        "Foto de perfil salva (suporta imagem, GIF ou MP4)!"
                    }
                }
                .onFailure { err ->
                    _statusMessage.value = err.localizedMessage ?: "URL inválida para avatar."
                }
            _isBusy.value = false
        }
    }

    fun createLocalEncryptedBackup(password: String, exportAfterCreate: Boolean = false) {
        viewModelScope.launch {
            _isBusy.value = true
            backupRepository.createEncryptedLocalBackup(password)
                .onSuccess { file ->
                    refreshLocalBackups()
                    if (exportAfterCreate) {
                        _pendingExportFile.value = file
                    } else {
                        _statusMessage.value = "Backup criptografado criado: ${file.name}"
                    }
                }
                .onFailure { err ->
                    _statusMessage.value = err.localizedMessage ?: "Erro ao gerar backup ZIP."
                }
            _isBusy.value = false
        }
    }

    fun exportPendingZipToUri(destinationUri: Uri) {
        val file = _pendingExportFile.value ?: return
        _pendingExportFile.value = null
        viewModelScope.launch {
            _isBusy.value = true
            backupRepository.exportZipToUri(file, destinationUri)
                .onSuccess {
                    _statusMessage.value = "Arquivo .zip criptografado salvo no armazenamento interno!"
                }
                .onFailure { err ->
                    _statusMessage.value = err.localizedMessage ?: "Erro ao exportar arquivo .zip."
                }
            _isBusy.value = false
        }
    }

    fun clearPendingExport() {
        _pendingExportFile.value = null
    }

    fun restoreBackupFromUri(uri: Uri, password: String) {
        viewModelScope.launch {
            _isBusy.value = true
            backupRepository.restoreBackupFromUri(uri, password)
                .onSuccess { manifest ->
                    _statusMessage.value = "Backup restaurado! ${manifest.chatCount} conversas, ${manifest.messageCount} mensagens e ${manifest.mediaFileCount} mídias."
                }
                .onFailure { err ->
                    _statusMessage.value = err.localizedMessage ?: "Falha ao restaurar backup .zip."
                }
            _isBusy.value = false
        }
    }

    fun restoreLocalBackupFile(summary: BackupSummary, password: String) {
        viewModelScope.launch {
            _isBusy.value = true
            backupRepository.restoreBackupFromFile(File(summary.filePath), password)
                .onSuccess { manifest ->
                    _statusMessage.value = "Restaurado (${manifest.messageCount} mensagens, ${manifest.mediaFileCount} mídias)."
                }
                .onFailure { err ->
                    _statusMessage.value = err.localizedMessage ?: "Senha inválida ou arquivo corrompido."
                }
            _isBusy.value = false
        }
    }

    fun uploadCloudBackup(password: String) {
        viewModelScope.launch {
            _isBusy.value = true
            backupRepository.uploadCloudBackup(password)
                .onSuccess {
                    refreshLocalBackups()
                    _statusMessage.value = "Backup criptografado enviado para a nuvem gratuita!"
                }
                .onFailure { err ->
                    _statusMessage.value = err.localizedMessage ?: "Erro no upload para nuvem."
                }
            _isBusy.value = false
        }
    }

    fun downloadAndRestoreCloudBackup(password: String) {
        viewModelScope.launch {
            _isBusy.value = true
            backupRepository.downloadAndRestoreCloudBackup(password)
                .onSuccess { manifest ->
                    _statusMessage.value = "Backup da nuvem restaurado! ${manifest.chatCount} conversas e ${manifest.messageCount} mensagens."
                }
                .onFailure { err ->
                    _statusMessage.value = err.localizedMessage ?: "Erro ao baixar backup da nuvem."
                }
            _isBusy.value = false
        }
    }

    fun saveFreeStorageConfig(
        mode: String,
        githubRepo: String,
        githubBranch: String,
        githubToken: String
    ) {
        viewModelScope.launch {
            settingsStore.saveFreeStorageConfig(mode, githubRepo, githubBranch, githubToken)
            _statusMessage.value = if (githubRepo.isNotBlank() && githubToken.isNotBlank()) {
                "Configuração de armazenamento gratuito salva (Repositório GitHub pronto)!"
            } else {
                "Configuração salva: usando Firestore Gratuito (sem custo)."
            }
        }
    }

    fun testGitHubConnection(repo: String, branch: String, token: String) {
        viewModelScope.launch {
            _isBusy.value = true
            freeStorageManager.testGitHubConfig(repo, branch, token)
                .onSuccess { msg -> _statusMessage.value = msg }
                .onFailure { err ->
                    _statusMessage.value = err.localizedMessage ?: "Falha ao testar repositório GitHub."
                }
            _isBusy.value = false
        }
    }

    fun setThemeMode(mode: ThemeMode) {
        viewModelScope.launch { settingsStore.setThemeMode(mode) }
    }

    fun setDynamicColor(enabled: Boolean) {
        viewModelScope.launch { settingsStore.setDynamicColor(enabled) }
    }

    fun setAutoCloudBackup(enabled: Boolean) {
        viewModelScope.launch { settingsStore.setAutoCloudBackup(enabled) }
    }

    fun setNotificationsEnabled(enabled: Boolean) {
        viewModelScope.launch { settingsStore.setNotificationsEnabled(enabled) }
    }

    fun setDefaultZipExtractUri(uriString: String) {
        viewModelScope.launch {
            settingsStore.setDefaultZipExtractUri(uriString)
            _statusMessage.value = if (uriString.isBlank()) {
                "Caminho padrão de extração ZIP restaurado para a pasta do WhatwillIdo."
            } else {
                "Pasta padrão de extração ZIP atualizada!"
            }
        }
    }

    fun signOut(context: Context, onComplete: () -> Unit) {
        viewModelScope.launch {
            userRepository.setOnlineStatus(false)
            authRepository.signOut(context)
            onComplete()
        }
    }

    fun downloadUpdateFromUrl(rawUrl: String, githubToken: String = "") {
        val cleanInput = rawUrl.trim()
        if (cleanInput.isBlank()) {
            _appUpdateState.value = _appUpdateState.value.copy(
                errorText = "Informe a URL do .apk, JSON de release ou repositório GitHub."
            )
            return
        }
        appUpdateJob?.cancel()
        appUpdateJob = viewModelScope.launch {
            settingsStore.setAppUpdateUrl(cleanInput)
            _appUpdateState.value = AppUpdateUiState(
                isResolving = true,
                statusText = "Verificando atualização..."
            )
            val resolved = appUpdateManager.resolveUpdateSource(cleanInput, githubToken)
            val release = resolved.getOrElse { err ->
                _appUpdateState.value = AppUpdateUiState(
                    errorText = err.localizedMessage ?: "Não foi possível localizar o APK de atualização."
                )
                return@launch
            }
            downloadAndLaunchRelease(release)
        }
    }

    fun downloadLatestGitHubReleaseUpdate(repo: String, githubToken: String = "") {
        val cleanRepo = repo.trim()
        if (cleanRepo.isBlank()) {
            _appUpdateState.value = _appUpdateState.value.copy(
                errorText = "Configure o repositório GitHub (usuario/repositorio) primeiro."
            )
            return
        }
        appUpdateJob?.cancel()
        appUpdateJob = viewModelScope.launch {
            _appUpdateState.value = AppUpdateUiState(
                isResolving = true,
                statusText = "Buscando última Release com APK no GitHub..."
            )
            val resolved = appUpdateManager.resolveLatestGitHubApkRelease(cleanRepo, githubToken)
            val release = resolved.getOrElse { err ->
                _appUpdateState.value = AppUpdateUiState(
                    errorText = err.localizedMessage ?: "Nenhuma Release com APK encontrada."
                )
                return@launch
            }
            downloadAndLaunchRelease(release)
        }
    }

    private suspend fun downloadAndLaunchRelease(release: AppUpdateReleaseInfo) {
        _appUpdateState.value = AppUpdateUiState(
            isDownloading = true,
            resolvedRelease = release,
            statusText = "Baixando ${release.fileName}..."
        )
        val downloadResult = appUpdateManager.downloadAndVerifyApk(release) { progress ->
            _appUpdateState.value = _appUpdateState.value.copy(
                isResolving = false,
                isDownloading = true,
                downloadFraction = progress.fraction,
                downloadedBytes = progress.downloadedBytes,
                totalBytes = progress.totalBytes
            )
        }
        downloadResult.onSuccess { verified ->
            _appUpdateState.value = _appUpdateState.value.copy(
                isResolving = false,
                isDownloading = false,
                downloadFraction = 1f,
                verifiedApk = verified,
                statusText = "APK validado (${verified.versionName ?: "nova versão"})! Abrindo instalador...",
                errorText = null
            )
            installDownloadedUpdate()
        }.onFailure { err ->
            _appUpdateState.value = AppUpdateUiState(
                resolvedRelease = release,
                errorText = err.localizedMessage ?: "Erro ao baixar ou validar o arquivo APK."
            )
        }
    }

    fun installDownloadedUpdate() {
        val verified = _appUpdateState.value.verifiedApk ?: run {
            _appUpdateState.value = _appUpdateState.value.copy(
                errorText = "Nenhum APK baixado para instalar."
            )
            return
        }
        when (val launch = appUpdateManager.launchApkInstaller(verified.file)) {
            ApkInstallLaunchResult.Launched -> {
                _appUpdateState.value = _appUpdateState.value.copy(
                    needsInstallPermissionDialog = false,
                    awaitingInstallPermission = false,
                    statusText = "Instalador do Android aberto! Confirme a atualização para continuar.",
                    errorText = null
                )
            }
            ApkInstallLaunchResult.NeedsInstallPermission -> {
                _appUpdateState.value = _appUpdateState.value.copy(
                    needsInstallPermissionDialog = true,
                    awaitingInstallPermission = true,
                    statusText = "Autorize \"Instalar apps desconhecidos\" para concluir a atualização.",
                    errorText = null
                )
            }
            is ApkInstallLaunchResult.Error -> {
                _appUpdateState.value = _appUpdateState.value.copy(
                    needsInstallPermissionDialog = false,
                    awaitingInstallPermission = false,
                    errorText = launch.message
                )
            }
        }
    }

    fun onResumeAfterInstallSettings() {
        val current = _appUpdateState.value
        if (current.awaitingInstallPermission &&
            current.verifiedApk != null &&
            appUpdateManager.canRequestPackageInstalls()
        ) {
            installDownloadedUpdate()
        }
    }

    fun dismissUpdateInstallPermissionDialog() {
        _appUpdateState.value = _appUpdateState.value.copy(
            needsInstallPermissionDialog = false
        )
    }

    fun buildManageUnknownAppSourcesIntent() = appUpdateManager.buildManageUnknownAppSourcesIntent()

    fun clearStatusMessage() {
        _statusMessage.value = null
    }
}
