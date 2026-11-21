package com.example.service

import com.example.BuildConfig
import com.example.data.local.SettingsDataStore
import com.example.data.local.UserSettings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

data class AppUpdateUiState(
    val isResolving: Boolean = false,
    val isDownloading: Boolean = false,
    val isWaitingForCiBuild: Boolean = false,
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

class AppUpdateCoordinator(
    private val appUpdateManager: AppUpdateManager,
    private val settingsStore: SettingsDataStore
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val _state = MutableStateFlow(AppUpdateUiState())
    val state: StateFlow<AppUpdateUiState> = _state.asStateFlow()

    private var activeJob: Job? = null
    private var ciPollJob: Job? = null
    private var lastAutoCheckAtMs: Long = 0L

    fun hasConfiguredSource(settings: UserSettings): Boolean {
        return settings.appUpdateUrl.isNotBlank() ||
            settings.githubRepo.isNotBlank() ||
            BuildConfig.CI_GITHUB_REPO.isNotBlank()
    }

    fun checkForUpdatesAutomatically(force: Boolean = false) {
        scope.launch {
            appUpdateManager.reconcileInstalledUpdateIfAppliedCompat(settingsStore)
            val settings = settingsStore.settingsFlow.first()
            if (!force && !settings.autoCheckUpdates) return@launch
            if (!hasConfiguredSource(settings)) return@launch

            val now = System.currentTimeMillis()
            if (!force && (now - lastAutoCheckAtMs) < AUTO_CHECK_COOLDOWN_MS) return@launch
            if (_state.value.isResolving || _state.value.isDownloading) return@launch
            lastAutoCheckAtMs = now

            runCheckAndAutoDownload(
                settings = settings,
                overrideSourceInput = null,
                overrideToken = null,
                silentUpToDate = !force
            )
        }
    }

    fun downloadUpdateFromUrl(rawUrl: String, githubToken: String = "") {
        val cleanInput = rawUrl.trim()
        if (cleanInput.isBlank()) {
            _state.value = _state.value.copy(
                errorText = "Informe a URL do .apk, JSON de release ou repositório GitHub."
            )
            return
        }
        ciPollJob?.cancel()
        activeJob?.cancel()
        activeJob = scope.launch {
            settingsStore.setAppUpdateUrl(cleanInput)
            val settings = settingsStore.settingsFlow.first()
            runCheckAndAutoDownload(
                settings = settings,
                overrideSourceInput = cleanInput,
                overrideToken = githubToken,
                silentUpToDate = false
            )
        }
    }

    fun downloadLatestGitHubReleaseUpdate(repo: String, githubToken: String = "") {
        val cleanRepo = repo.trim()
        if (cleanRepo.isBlank()) {
            _state.value = _state.value.copy(
                errorText = "Configure o repositório GitHub (usuario/repositorio) primeiro."
            )
            return
        }
        ciPollJob?.cancel()
        activeJob?.cancel()
        activeJob = scope.launch {
            val settings = settingsStore.settingsFlow.first()
            runCheckAndAutoDownload(
                settings = settings,
                overrideSourceInput = cleanRepo,
                overrideToken = githubToken,
                silentUpToDate = false
            )
        }
    }

    private suspend fun runCheckAndAutoDownload(
        settings: UserSettings,
        overrideSourceInput: String?,
        overrideToken: String?,
        silentUpToDate: Boolean
    ) {
        _state.value = _state.value.copy(
            isResolving = true,
            isWaitingForCiBuild = false,
            errorText = null,
            statusText = if (silentUpToDate) null else "Verificando novos commits e releases..."
        )

        val checkResult = appUpdateManager.checkForCommitOrReleaseUpdate(
            settings = settings,
            overrideSourceInput = overrideSourceInput,
            overrideToken = overrideToken
        )

        val outcome = checkResult.getOrElse { err ->
            _state.value = _state.value.copy(
                isResolving = false,
                errorText = if (silentUpToDate) null else (err.localizedMessage ?: "Falha ao verificar atualização."),
                statusText = if (silentUpToDate) _state.value.statusText else null
            )
            return
        }

        when (outcome) {
            is UpdateCheckResult.UpdateAvailable -> {
                ciPollJob?.cancel()
                downloadAndLaunchRelease(outcome.release, outcome.reason)
            }
            is UpdateCheckResult.WaitingForCiBuild -> {
                val shortSha = outcome.latestCommit.sha.take(7)
                _state.value = _state.value.copy(
                    isResolving = false,
                    isWaitingForCiBuild = true,
                    resolvedRelease = outcome.currentRelease,
                    statusText = "Novo commit ($shortSha) detectado! Aguardando GitHub Actions gerar o APK...",
                    errorText = null
                )
                scheduleCiBuildPolling(overrideSourceInput, overrideToken)
            }
            is UpdateCheckResult.UpToDate -> {
                val currentVer = outcome.latestRelease?.versionName
                    ?: outcome.latestCommit?.sha?.take(7)
                    ?: "v${BuildConfig.VERSION_NAME}"
                _state.value = _state.value.copy(
                    isResolving = false,
                    isWaitingForCiBuild = false,
                    resolvedRelease = outcome.latestRelease,
                    statusText = if (silentUpToDate) null else "Você já está na build mais recente ($currentVer).",
                    errorText = null
                )
            }
        }
    }

    private fun scheduleCiBuildPolling(
        overrideSourceInput: String?,
        overrideToken: String?
    ) {
        ciPollJob?.cancel()
        ciPollJob = scope.launch {
            repeat(MAX_CI_POLL_ATTEMPTS) {
                delay(CI_POLL_INTERVAL_MS)
                if (_state.value.isDownloading || _state.value.isResolving) return@repeat
                val settings = settingsStore.settingsFlow.first()
                if (!settings.autoCheckUpdates && overrideSourceInput == null) return@launch

                val res = appUpdateManager.checkForCommitOrReleaseUpdate(
                    settings = settings,
                    overrideSourceInput = overrideSourceInput,
                    overrideToken = overrideToken
                ).getOrNull() ?: return@repeat

                if (res is UpdateCheckResult.UpdateAvailable) {
                    downloadAndLaunchRelease(res.release, res.reason)
                    return@launch
                }
            }
        }
    }

    private suspend fun downloadAndLaunchRelease(
        release: AppUpdateReleaseInfo,
        reasonText: String
    ) {
        _state.value = _state.value.copy(
            isResolving = false,
            isDownloading = true,
            isWaitingForCiBuild = false,
            downloadFraction = null,
            downloadedBytes = 0L,
            totalBytes = -1L,
            resolvedRelease = release,
            statusText = "$reasonText Baixando ${release.fileName}...",
            errorText = null
        )

        val downloadResult = try {
            appUpdateManager.downloadAndVerifyApk(release) { progress ->
                _state.value = _state.value.copy(
                    isResolving = false,
                    isDownloading = true,
                    downloadFraction = progress.fraction,
                    downloadedBytes = progress.downloadedBytes,
                    totalBytes = progress.totalBytes
                )
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }

        downloadResult.onSuccess { verified ->
            _state.value = _state.value.copy(
                isResolving = false,
                isDownloading = false,
                downloadFraction = 1f,
                verifiedApk = verified,
                statusText = "APK validado! Abrindo instalador do Android...",
                errorText = null
            )
            installDownloadedUpdate()
        }.onFailure { err ->
            _state.value = _state.value.copy(
                isResolving = false,
                isDownloading = false,
                resolvedRelease = release,
                errorText = err.localizedMessage ?: "Erro ao baixar ou validar o arquivo APK."
            )
        }
    }

    fun installDownloadedUpdate() {
        val verified = _state.value.verifiedApk ?: run {
            _state.value = _state.value.copy(errorText = "Nenhum APK baixado para instalar.")
            return
        }
        val rel = verified.release ?: _state.value.resolvedRelease
        scope.launch {
            settingsStore.recordPendingApkInstall(
                releaseId = rel?.releaseId ?: 0L,
                commitSha = rel?.commitSha.orEmpty(),
                runNumber = rel?.runNumber ?: (verified.versionCode?.toInt() ?: 0),
                preInstallLastUpdateTimeMs = appUpdateManager.getCurrentAppLastUpdateTimeMs()
            )
        }

        when (val launch = appUpdateManager.launchApkInstaller(verified.file)) {
            ApkInstallLaunchResult.Launched -> {
                _state.value = _state.value.copy(
                    needsInstallPermissionDialog = false,
                    awaitingInstallPermission = false,
                    statusText = "Instalador do Android aberto! Confirme a atualização para concluir.",
                    errorText = null
                )
            }
            ApkInstallLaunchResult.NeedsInstallPermission -> {
                _state.value = _state.value.copy(
                    needsInstallPermissionDialog = true,
                    awaitingInstallPermission = true,
                    statusText = "Autorize \"Instalar apps desconhecidos\" para abrir o instalador.",
                    errorText = null
                )
            }
            is ApkInstallLaunchResult.Error -> {
                _state.value = _state.value.copy(
                    needsInstallPermissionDialog = false,
                    awaitingInstallPermission = false,
                    errorText = launch.message
                )
            }
        }
    }

    fun onResumeAfterInstallSettings() {
        val current = _state.value
        if (current.awaitingInstallPermission &&
            current.verifiedApk != null &&
            appUpdateManager.canRequestPackageInstalls()
        ) {
            installDownloadedUpdate()
        }
    }

    fun dismissInstallPermissionDialog() {
        _state.value = _state.value.copy(needsInstallPermissionDialog = false)
    }

    fun buildManageUnknownAppSourcesIntent() = appUpdateManager.buildManageUnknownAppSourcesIntent()

    companion object {
        private const val AUTO_CHECK_COOLDOWN_MS = 45_000L
        private const val CI_POLL_INTERVAL_MS = 20_000L
        private const val MAX_CI_POLL_ATTEMPTS = 15
    }
}

private suspend fun AppUpdateManager.reconcileInstalledUpdateIfAppliedCompat(
    settingsStore: SettingsDataStore
) {
    settingsStore.reconcileInstalledUpdateIfApplied(getCurrentAppLastUpdateTimeMs())
}
