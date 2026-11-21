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
import com.example.service.AppUpdateCoordinator
import com.example.service.AppUpdateUiState
import com.example.service.CodeVerificationOutcome
import com.example.service.EmailCodeDispatchResult
import com.example.service.EmailDeliveryService
import com.example.service.EmailVerificationChallenge
import com.example.service.EmailVerificationHelper
import com.example.service.EmailVerificationTarget
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
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

class BackupViewModel(
    private val authRepository: AuthRepository,
    private val userRepository: UserAndContactsRepository,
    private val backupRepository: BackupRepository,
    private val settingsStore: SettingsDataStore,
    private val freeStorageManager: FreeMediaStorageManager,
    private val appUpdateCoordinator: AppUpdateCoordinator,
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

    val appUpdateState: StateFlow<AppUpdateUiState> = appUpdateCoordinator.state

    private val _activeVerificationChallenge = MutableStateFlow<EmailVerificationChallenge?>(null)
    val activeVerificationChallenge: StateFlow<EmailVerificationChallenge?> =
        _activeVerificationChallenge.asStateFlow()

    private val _firebaseEmailVerified = MutableStateFlow(authRepository.currentUser()?.isEmailVerified == true)
    val firebaseEmailVerified: StateFlow<Boolean> = _firebaseEmailVerified.asStateFlow()

    private val _needsMailerSetupHint = MutableStateFlow(false)
    val needsMailerSetupHint: StateFlow<Boolean> = _needsMailerSetupHint.asStateFlow()

    private val _lastSentMaskedEmail = MutableStateFlow<String?>(null)
    val lastSentMaskedEmail: StateFlow<String?> = _lastSentMaskedEmail.asStateFlow()

    private var usernameCheckJob: Job? = null

    init {
        refreshLocalBackups()
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

    fun saveAiConfig(
        apiKey: String,
        model: String,
        provider: String,
        customBaseUrl: String
    ) {
        viewModelScope.launch {
            settingsStore.saveAiConfig(apiKey, model, provider, customBaseUrl)
            val savedModel = model.trim().ifBlank { "gemini-3.8-flash" }
            _statusMessage.value = if (apiKey.isBlank()) {
                "API Key da IA removida. Modelo padrão: $savedModel."
            } else {
                "Configuração da IA salva (modelo ativo: $savedModel)!"
            }
        }
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
            userRepository.resetCodeVerificationOnSignOut()
            _activeVerificationChallenge.value = null
            _lastSentMaskedEmail.value = null
            authRepository.signOut(context)
            onComplete()
        }
    }

    fun setAutoCheckUpdates(enabled: Boolean) {
        viewModelScope.launch {
            settingsStore.setAutoCheckUpdates(enabled)
            if (enabled) {
                appUpdateCoordinator.checkForUpdatesAutomatically(force = true)
            }
        }
    }

    fun checkForUpdatesNow() {
        appUpdateCoordinator.checkForUpdatesAutomatically(force = true)
    }

    fun downloadUpdateFromUrl(rawUrl: String, githubToken: String = "") {
        appUpdateCoordinator.downloadUpdateFromUrl(rawUrl, githubToken)
    }

    fun downloadLatestGitHubReleaseUpdate(repo: String, githubToken: String = "") {
        appUpdateCoordinator.downloadLatestGitHubReleaseUpdate(repo, githubToken)
    }

    fun installDownloadedUpdate() {
        appUpdateCoordinator.installDownloadedUpdate()
    }

    fun setEmailVerificationEnabled(enabled: Boolean) {
        viewModelScope.launch {
            _isBusy.value = true
            userRepository.setEmailVerificationEnabled(enabled)
                .onSuccess {
                    if (enabled) {
                        val targetEmail = (myProfile.value?.email
                            ?: authRepository.currentUser()?.email).orEmpty()
                        if (EmailVerificationHelper.isValidEmail(targetEmail)) {
                            dispatchChallengeToEmail(
                                targetEmail = targetEmail,
                                target = EmailVerificationTarget.PRIMARY,
                                sendFirebaseFallback = true
                            )
                        } else {
                            _statusMessage.value =
                                "Verificação de e-mail ativada. Faça login com Conta Google para receber o código."
                        }
                    } else {
                        if (_activeVerificationChallenge.value?.target == EmailVerificationTarget.PRIMARY) {
                            _activeVerificationChallenge.value = null
                        }
                        _needsMailerSetupHint.value = false
                        _lastSentMaskedEmail.value = null
                        _statusMessage.value = "Verificação de e-mail desativada."
                    }
                }
                .onFailure { err ->
                    _statusMessage.value = err.localizedMessage ?: "Falha ao alterar verificação de e-mail."
                }
            _isBusy.value = false
        }
    }

    fun generatePrimaryEmailCode(sendFirebaseFallback: Boolean = true) {
        val targetEmail = (myProfile.value?.email ?: authRepository.currentUser()?.email).orEmpty()
        if (!EmailVerificationHelper.isValidEmail(targetEmail)) {
            _statusMessage.value = "Nenhum e-mail válido vinculado à conta atual."
            return
        }
        viewModelScope.launch {
            _isBusy.value = true
            dispatchChallengeToEmail(
                targetEmail = targetEmail,
                target = EmailVerificationTarget.PRIMARY,
                sendFirebaseFallback = sendFirebaseFallback
            )
            _isBusy.value = false
        }
    }

    fun generateRecoveryEmailCode(emailOverride: String? = null) {
        val targetEmail = (emailOverride ?: myProfile.value?.recoveryEmail ?: userSettings.value.recoveryEmail).trim()
        if (!EmailVerificationHelper.isValidEmail(targetEmail)) {
            _statusMessage.value = "Salve ou informe um e-mail de recuperação válido primeiro."
            return
        }
        viewModelScope.launch {
            _isBusy.value = true
            dispatchChallengeToEmail(
                targetEmail = targetEmail,
                target = EmailVerificationTarget.RECOVERY,
                sendFirebaseFallback = false
            )
            _isBusy.value = false
        }
    }

    private suspend fun dispatchChallengeToEmail(
        targetEmail: String,
        target: EmailVerificationTarget,
        sendFirebaseFallback: Boolean
    ) {
        val challenge = EmailVerificationHelper.createChallenge(
            targetEmail = targetEmail,
            target = target
        )
        _activeVerificationChallenge.value = challenge
        val currentSettings = settingsStore.settingsFlow.first()
        when (
            val result = EmailDeliveryService.sendVerificationCodeEmail(
                recipientEmail = targetEmail,
                code = challenge.code,
                isRecovery = target == EmailVerificationTarget.RECOVERY,
                settings = currentSettings
            )
        ) {
            is EmailCodeDispatchResult.SentViaGmailSmtp -> {
                _needsMailerSetupHint.value = false
                _lastSentMaskedEmail.value = result.maskedRecipient
                _statusMessage.value =
                    "Código de 6 dígitos (letras e números) enviado para ${result.maskedRecipient} via Gmail! Verifique Caixa de Entrada ou Spam."
            }
            is EmailCodeDispatchResult.SentViaWebhook -> {
                _needsMailerSetupHint.value = false
                _lastSentMaskedEmail.value = result.maskedRecipient
                _statusMessage.value =
                    "Código de 6 dígitos (letras e números) enviado para ${result.maskedRecipient}! Verifique seu Gmail ou Spam."
            }
            is EmailCodeDispatchResult.SentViaResend -> {
                _needsMailerSetupHint.value = false
                _lastSentMaskedEmail.value = result.maskedRecipient
                _statusMessage.value =
                    "Código de 6 dígitos (letras e números) enviado para ${result.maskedRecipient}! Confira seu Gmail."
            }
            is EmailCodeDispatchResult.NoCustomMailerConfigured -> {
                _needsMailerSetupHint.value = true
                _lastSentMaskedEmail.value = EmailVerificationHelper.maskEmail(targetEmail)
                if (sendFirebaseFallback && target == EmailVerificationTarget.PRIMARY) {
                    authRepository.sendFirebaseEmailVerification()
                        .onSuccess {
                            _statusMessage.value =
                                "E-mail oficial enviado para ${EmailVerificationHelper.maskEmail(targetEmail)}. Para enviar o código de 6 dígitos (letras e números) direto ao Gmail, configure o remetente Gmail SMTP/Webhook abaixo."
                        }
                        .onFailure {
                            _statusMessage.value =
                                "Configure o envio Gmail SMTP ou Apps Script Webhook abaixo para receber o código de 6 dígitos no seu Gmail."
                        }
                } else {
                    _statusMessage.value =
                        "Configure o envio Gmail SMTP ou Apps Script Webhook abaixo para entregar o código de 6 dígitos diretamente no Gmail."
                }
            }
            is EmailCodeDispatchResult.Failed -> {
                _needsMailerSetupHint.value = true
                _statusMessage.value = result.message
            }
        }
    }

    fun saveEmailDeliveryConfig(
        mode: String,
        senderAddress: String,
        smtpAppPassword: String,
        webhookUrl: String,
        resendApiKey: String,
        resendFrom: String
    ) {
        viewModelScope.launch {
            _isBusy.value = true
            settingsStore.saveEmailDeliveryConfig(
                mode = mode,
                senderAddress = senderAddress,
                smtpAppPassword = smtpAppPassword,
                webhookUrl = webhookUrl,
                resendApiKey = resendApiKey,
                resendFrom = resendFrom
            )
            _needsMailerSetupHint.value = false
            _statusMessage.value = "Configuração de envio de e-mail para Gmail salva!"
            _isBusy.value = false
        }
    }

    fun submitEmailVerificationCode(rawInput: String) {
        val challenge = _activeVerificationChallenge.value
        if (challenge == null) {
            _statusMessage.value = "Solicite o envio do código de 6 dígitos para o seu Gmail primeiro."
            return
        }
        val (outcome, updatedChallenge) = EmailVerificationHelper.verifyChallenge(challenge, rawInput)
        when (outcome) {
            CodeVerificationOutcome.Verified -> {
                viewModelScope.launch {
                    _isBusy.value = true
                    val result = when (challenge.target) {
                        EmailVerificationTarget.PRIMARY -> userRepository.markPrimaryEmailCodeVerified()
                        EmailVerificationTarget.RECOVERY -> userRepository.markRecoveryEmailCodeVerified()
                    }
                    result.onSuccess {
                        _activeVerificationChallenge.value = null
                        _statusMessage.value = when (challenge.target) {
                            EmailVerificationTarget.PRIMARY ->
                                "E-mail principal verificado com sucesso!"
                            EmailVerificationTarget.RECOVERY ->
                                "E-mail de recuperação confirmado com sucesso!"
                        }
                    }.onFailure { err ->
                        _statusMessage.value = err.localizedMessage ?: "Falha ao salvar verificação."
                    }
                    _isBusy.value = false
                }
            }
            CodeVerificationOutcome.InvalidFormat -> {
                _statusMessage.value = "Digite os 6 caracteres (letras e números) recebidos no e-mail."
            }
            is CodeVerificationOutcome.Mismatch -> {
                _activeVerificationChallenge.value = updatedChallenge
                _statusMessage.value =
                    "Código incorreto. Restam ${outcome.attemptsRemaining} tentativa(s)."
            }
            CodeVerificationOutcome.Expired -> {
                _activeVerificationChallenge.value = null
                _statusMessage.value = "Código expirado. Solicite um novo envio para o Gmail."
            }
            CodeVerificationOutcome.LockedOut -> {
                _activeVerificationChallenge.value = null
                _statusMessage.value = "Muitas tentativas. Solicite um novo código para o Gmail."
            }
        }
    }

    fun saveRecoveryEmail(rawEmail: String) {
        val clean = rawEmail.trim()
        if (clean.isNotEmpty() && !EmailVerificationHelper.isValidEmail(clean)) {
            _statusMessage.value = "Digite um e-mail de recuperação válido (ex: backup@dominio.com)."
            return
        }
        viewModelScope.launch {
            _isBusy.value = true
            userRepository.updateRecoveryEmail(clean.ifBlank { null })
                .onSuccess { saved ->
                    if (saved == null) {
                        if (_activeVerificationChallenge.value?.target == EmailVerificationTarget.RECOVERY) {
                            _activeVerificationChallenge.value = null
                        }
                        _statusMessage.value = "E-mail de recuperação removido da conta."
                    } else {
                        dispatchChallengeToEmail(
                            targetEmail = saved,
                            target = EmailVerificationTarget.RECOVERY,
                            sendFirebaseFallback = false
                        )
                    }
                }
                .onFailure { err ->
                    _statusMessage.value = err.localizedMessage ?: "Falha ao atualizar e-mail de recuperação."
                }
            _isBusy.value = false
        }
    }

    fun sendFirebaseVerificationLink() {
        viewModelScope.launch {
            _isBusy.value = true
            authRepository.sendFirebaseEmailVerification()
                .onSuccess {
                    _statusMessage.value =
                        "E-mail oficial enviado para seu Gmail! Abra sua caixa de entrada e clique no link."
                }
                .onFailure { err ->
                    _statusMessage.value = err.localizedMessage ?: "Não foi possível enviar o e-mail de verificação."
                }
            _isBusy.value = false
        }
    }

    fun refreshFirebaseVerificationStatus(markCodeVerifiedIfConfirmed: Boolean = false) {
        viewModelScope.launch {
            _isBusy.value = true
            authRepository.reloadCurrentUserEmailVerified()
                .onSuccess { verified ->
                    _firebaseEmailVerified.value = verified
                    if (verified && markCodeVerifiedIfConfirmed) {
                        userRepository.markPrimaryEmailCodeVerified()
                            .onSuccess {
                                _activeVerificationChallenge.value = null
                                _statusMessage.value = "E-mail confirmado pelo link no Gmail! Acesso liberado."
                            }
                            .onFailure { err ->
                                _statusMessage.value = err.localizedMessage ?: "Falha ao atualizar perfil."
                            }
                    } else {
                        _statusMessage.value = if (verified) {
                            "E-mail confirmado no Google/Firebase!"
                        } else {
                            "Ainda aguardando confirmação no seu Gmail."
                        }
                    }
                }
                .onFailure { err ->
                    _statusMessage.value = err.localizedMessage ?: "Não foi possível atualizar status do e-mail."
                }
            _isBusy.value = false
        }
    }

    fun sendAccountRecoveryEmail(targetEmail: String) {
        viewModelScope.launch {
            _isBusy.value = true
            authRepository.sendRecoveryEmail(targetEmail)
                .onSuccess {
                    _statusMessage.value =
                        "Solicitação de recuperação enviada para ${EmailVerificationHelper.maskEmail(targetEmail)}!"
                }
                .onFailure { err ->
                    _statusMessage.value = err.localizedMessage ?: "Falha ao enviar recuperação para $targetEmail."
                }
            _isBusy.value = false
        }
    }

    fun clearStatusMessage() {
        _statusMessage.value = null
    }
}
