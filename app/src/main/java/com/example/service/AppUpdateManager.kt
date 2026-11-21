package com.example.service

import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import com.example.BuildConfig
import com.example.data.local.UserSettings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.Locale

data class AppUpdateReleaseInfo(
    val apkUrl: String,
    val releaseId: Long? = null,
    val tagName: String? = null,
    val runNumber: Int? = null,
    val commitSha: String? = null,
    val publishedAt: String? = null,
    val assetUpdatedAt: String? = null,
    val versionCode: Long? = null,
    val versionName: String? = null,
    val releaseNotes: String? = null,
    val expectedSha256: String? = null,
    val fileName: String = "WhatwillIdo-update.apk",
    val authTokenForApiRedirect: String? = null
) {
    fun uniqueKey(): String = when {
        runNumber != null && runNumber > 0 -> "build_$runNumber"
        releaseId != null && releaseId > 0L -> "rel_$releaseId"
        !commitSha.isNullOrBlank() -> "sha_${commitSha.take(10)}"
        else -> "direct_${apkUrl.hashCode().toUInt()}"
    }
}

data class GitCommitHeadInfo(
    val sha: String,
    val message: String? = null,
    val dateIso: String? = null
)

sealed interface UpdateCheckResult {
    data class UpdateAvailable(
        val release: AppUpdateReleaseInfo,
        val latestBranchCommit: GitCommitHeadInfo? = null,
        val reason: String
    ) : UpdateCheckResult

    data class WaitingForCiBuild(
        val latestCommit: GitCommitHeadInfo,
        val currentRelease: AppUpdateReleaseInfo?
    ) : UpdateCheckResult

    data class UpToDate(
        val latestCommit: GitCommitHeadInfo?,
        val latestRelease: AppUpdateReleaseInfo?
    ) : UpdateCheckResult
}

data class ApkDownloadProgress(
    val downloadedBytes: Long,
    val totalBytes: Long,
    val fraction: Float?
)

data class VerifiedApkInfo(
    val file: File,
    val sizeBytes: Long,
    val sha256: String,
    val packageName: String?,
    val versionName: String?,
    val versionCode: Long?,
    val release: AppUpdateReleaseInfo? = null
)

sealed interface ApkInstallLaunchResult {
    data object Launched : ApkInstallLaunchResult
    data object NeedsInstallPermission : ApkInstallLaunchResult
    data class Error(val message: String) : ApkInstallLaunchResult
}

class AppUpdateManager(private val context: Context) {

    fun updatesDir(): File = File(context.cacheDir, "updates").apply { mkdirs() }

    fun clearStaleCachedApks(keepFileName: String? = null) {
        updatesDir().listFiles()?.forEach { file ->
            if (file.isFile && file.name != keepFileName) {
                runCatching { file.delete() }
            }
        }
    }

    fun getCurrentAppLastUpdateTimeMs(): Long {
        return runCatching {
            val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.packageManager.getPackageInfo(
                    context.packageName,
                    PackageManager.PackageInfoFlags.of(0L)
                )
            } else {
                @Suppress("DEPRECATION")
                context.packageManager.getPackageInfo(context.packageName, 0)
            }
            info.lastUpdateTime
        }.getOrDefault(0L)
    }

    fun canRequestPackageInstalls(): Boolean {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.O ||
            context.packageManager.canRequestPackageInstalls()
    }

    fun buildManageUnknownAppSourcesIntent(): Intent {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Intent(
                Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:${context.packageName}")
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        } else {
            Intent(Settings.ACTION_SECURITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }

    fun validateApkFile(
        apkFile: File,
        expectedSha256: String? = null,
        release: AppUpdateReleaseInfo? = null
    ): Result<VerifiedApkInfo> {
        if (!apkFile.exists() || !apkFile.isFile) {
            return Result.failure(IllegalStateException("Arquivo APK não encontrado."))
        }
        val length = apkFile.length()
        if (length <= MIN_VALID_APK_BYTES) {
            return Result.failure(
                IllegalStateException("Arquivo APK vazio ou incompleto ($length bytes).")
            )
        }
        val header = ByteArray(4)
        val readCount = runCatching {
            FileInputStream(apkFile).use { it.read(header) }
        }.getOrElse { -1 }
        val isZipHeader = readCount == 4 &&
            header[0] == 0x50.toByte() &&
            header[1] == 0x4B.toByte() &&
            (header[2] == 0x03.toByte() || header[2] == 0x05.toByte()) &&
            (header[3] == 0x04.toByte() || header[3] == 0x06.toByte())
        if (!isZipHeader) {
            return Result.failure(
                IllegalStateException("O arquivo baixado não é um pacote APK válido (cabeçalho inválido ou HTML de erro).")
            )
        }

        val actualSha256 = runCatching { computeSha256(apkFile) }.getOrElse { "" }
        val cleanExpected = expectedSha256?.trim()?.lowercase(Locale.ROOT)?.takeIf { it.isNotBlank() }
        if (cleanExpected != null && actualSha256.isNotBlank() && actualSha256 != cleanExpected) {
            return Result.failure(
                IllegalStateException("Verificação SHA-256 falhou: o APK baixado está corrompido ou adulterado.")
            )
        }

        val pkgInfo = readPackageArchiveInfo(apkFile)
            ?: return Result.failure(
                IllegalStateException("Pacote APK corrompido ou ilegível pelo instalador Android.")
            )
        if (!pkgInfo.packageName.isNullOrBlank() && pkgInfo.packageName != context.packageName) {
            return Result.failure(
                IllegalStateException(
                    "O APK pertence ao pacote '${pkgInfo.packageName}', diferente deste app ('${context.packageName}')."
                )
            )
        }

        val archiveVersionCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            pkgInfo.longVersionCode
        } else {
            @Suppress("DEPRECATION")
            pkgInfo.versionCode.toLong()
        }

        return Result.success(
            VerifiedApkInfo(
                file = apkFile,
                sizeBytes = length,
                sha256 = actualSha256,
                packageName = pkgInfo.packageName,
                versionName = pkgInfo.versionName,
                versionCode = archiveVersionCode,
                release = release
            )
        )
    }

    fun launchApkInstaller(apkFile: File): ApkInstallLaunchResult {
        val validation = validateApkFile(apkFile)
        if (validation.isFailure) {
            return ApkInstallLaunchResult.Error(
                validation.exceptionOrNull()?.localizedMessage ?: "Arquivo APK inválido para instalação."
            )
        }
        if (!canRequestPackageInstalls()) {
            return ApkInstallLaunchResult.NeedsInstallPermission
        }
        return runCatching {
            val uri: Uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                apkFile
            )
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, APK_MIME_TYPE)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            ApkInstallLaunchResult.Launched
        }.getOrElse { err ->
            ApkInstallLaunchResult.Error(
                err.localizedMessage ?: "Não foi possível abrir o instalador de pacotes do Android."
            )
        }
    }

    suspend fun checkForCommitOrReleaseUpdate(
        settings: UserSettings,
        overrideSourceInput: String? = null,
        overrideToken: String? = null
    ): Result<UpdateCheckResult> = withContext(Dispatchers.IO) {
        val token = (overrideToken ?: settings.githubToken).trim()
        val effectiveInput = (overrideSourceInput?.trim()?.takeIf { it.isNotBlank() }
            ?: settings.appUpdateUrl.trim().takeIf { it.isNotBlank() }
            ?: settings.githubRepo.trim().takeIf { it.isNotBlank() }
            ?: BuildConfig.CI_GITHUB_REPO.trim().takeIf { it.isNotBlank() })
            ?: return@withContext Result.failure(
                IllegalArgumentException("Configure um repositório GitHub (usuario/repo) ou URL de atualização.")
            )

        val effectiveBranch = settings.githubBranch.trim()
            .ifBlank { BuildConfig.CI_GITHUB_BRANCH.trim() }
            .ifBlank { "main" }

        try {
            val ownerRepo = extractGitHubOwnerRepo(effectiveInput)
            if (ownerRepo != null) {
                val latestCommit = runCatching {
                    fetchLatestBranchCommit(ownerRepo, effectiveBranch, token)
                }.getOrNull()
                val latestRelease = resolveLatestGitHubApkRelease(ownerRepo, token).getOrNull()

                if (latestRelease != null && isReleaseNewerThanInstalled(latestRelease, settings)) {
                    val shortSha = latestRelease.commitSha?.take(7)
                    val label = latestRelease.tagName ?: latestRelease.versionName ?: "nova build"
                    val reason = if (shortSha != null) {
                        "Nova versão $label (commit $shortSha) disponível!"
                    } else {
                        "Nova release $label disponível!"
                    }
                    return@withContext Result.success(
                        UpdateCheckResult.UpdateAvailable(
                            release = latestRelease,
                            latestBranchCommit = latestCommit,
                            reason = reason
                        )
                    )
                }

                if (latestCommit != null && isCommitWaitingForRelease(latestCommit, latestRelease, settings)) {
                    return@withContext Result.success(
                        UpdateCheckResult.WaitingForCiBuild(
                            latestCommit = latestCommit,
                            currentRelease = latestRelease
                        )
                    )
                }

                if (latestRelease == null) {
                    return@withContext Result.failure(
                        IllegalStateException("Nenhuma Release com arquivo .apk encontrada em $ownerRepo.")
                    )
                }

                return@withContext Result.success(
                    UpdateCheckResult.UpToDate(
                        latestCommit = latestCommit,
                        latestRelease = latestRelease
                    )
                )
            }

            // Non-GitHub-repo source: JSON manifest or direct .apk URL
            val resolved = resolveUpdateSource(effectiveInput, token).getOrThrow()
            if (isReleaseNewerThanInstalled(resolved, settings) || overrideSourceInput != null) {
                Result.success(
                    UpdateCheckResult.UpdateAvailable(
                        release = resolved,
                        latestBranchCommit = null,
                        reason = "Atualização encontrada (${resolved.versionName ?: resolved.fileName})."
                    )
                )
            } else {
                Result.success(
                    UpdateCheckResult.UpToDate(
                        latestCommit = null,
                        latestRelease = resolved
                    )
                )
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun isReleaseNewerThanInstalled(
        release: AppUpdateReleaseInfo,
        settings: UserSettings
    ): Boolean {
        val installedRun = maxOf(BuildConfig.CI_RUN_NUMBER, settings.lastInstalledRunNumber)
        val installedSha = BuildConfig.GIT_COMMIT_SHA.trim().ifBlank { settings.lastInstalledCommitSha.trim() }
        val installedReleaseId = settings.lastInstalledReleaseId

        // 1. Exact release ID match already installed
        if (release.releaseId != null && installedReleaseId > 0L && release.releaseId == installedReleaseId) {
            return false
        }

        // 2. Compare GitHub Actions build run numbers when available
        val relRun = release.runNumber
        if (relRun != null && relRun > 0) {
            if (installedRun > 0) {
                if (relRun > installedRun) return true
                if (relRun < installedRun) return false
            } else if (installedReleaseId == 0L && installedSha.isBlank()) {
                // Legacy/local APK without CI metadata -> upgrade to latest GitHub release
                return true
            }
        }

        // 3. Compare versionCode from JSON manifest if available
        val relVersionCode = release.versionCode
        val installedVersionCode = maxOf(BuildConfig.VERSION_CODE.toLong(), installedRun.toLong())
        if (relVersionCode != null && relVersionCode > installedVersionCode) {
            return true
        }

        // 4. Compare commit SHA
        val relSha = release.commitSha?.trim().orEmpty()
        if (relSha.isNotBlank() && installedSha.isNotBlank()) {
            val sameCommit = relSha.equals(installedSha, ignoreCase = true) ||
                relSha.startsWith(installedSha, ignoreCase = true) ||
                installedSha.startsWith(relSha, ignoreCase = true)
            if (!sameCommit && (relRun == null || installedRun == 0 || relRun >= installedRun)) {
                return true
            }
        }

        // 5. Fallback releaseId monotonic comparison
        if (release.releaseId != null && installedReleaseId > 0L && release.releaseId > installedReleaseId) {
            return true
        }

        return false
    }

    private fun isCommitWaitingForRelease(
        latestCommit: GitCommitHeadInfo,
        latestRelease: AppUpdateReleaseInfo?,
        settings: UserSettings
    ): Boolean {
        val installedSha = BuildConfig.GIT_COMMIT_SHA.trim().ifBlank { settings.lastInstalledCommitSha.trim() }
        if (installedSha.isNotBlank() && latestCommit.sha.equals(installedSha, ignoreCase = true)) {
            return false
        }
        val relSha = latestRelease?.commitSha?.trim().orEmpty()
        if (relSha.isNotBlank() && latestCommit.sha.equals(relSha, ignoreCase = true)) {
            return false
        }
        // Only treat as waiting when we either have a known installed SHA or an existing release to compare against
        return installedSha.isNotBlank() || relSha.isNotBlank()
    }

    suspend fun resolveUpdateSource(
        rawInput: String,
        githubToken: String = ""
    ): Result<AppUpdateReleaseInfo> = withContext(Dispatchers.IO) {
        val trimmed = rawInput.trim()
        if (trimmed.isBlank()) {
            return@withContext Result.failure(
                IllegalArgumentException("Informe uma URL de APK, JSON ou repositório GitHub.")
            )
        }

        try {
            extractGitHubOwnerRepo(trimmed)?.let { ownerRepo ->
                return@withContext resolveLatestGitHubApkRelease(ownerRepo, githubToken)
            }

            val parsed = Uri.parse(trimmed)
            val scheme = parsed.scheme?.lowercase(Locale.ROOT)
            if (scheme != "https" && scheme != "http") {
                return@withContext Result.failure(
                    IllegalArgumentException("A URL deve começar com https:// ou ser um repositório owner/repo.")
                )
            }

            val lowerPath = (parsed.path ?: "").lowercase(Locale.ROOT)
            if (lowerPath.endsWith(".json")) {
                return@withContext fetchAndParseUpdateJson(trimmed, githubToken)
            }

            if (trimmed.contains("api.github.com/repos/", ignoreCase = true) && !lowerPath.endsWith(".apk")) {
                return@withContext parseGitHubReleasesPayload(
                    jsonBody = httpGetText(trimmed, githubToken),
                    authToken = githubToken.takeIf { it.isNotBlank() }
                )
            }

            val fileName = parsed.lastPathSegment
                ?.takeIf { it.lowercase(Locale.ROOT).endsWith(".apk") }
                ?: "WhatwillIdo-update.apk"
            Result.success(
                AppUpdateReleaseInfo(
                    apkUrl = trimmed,
                    fileName = fileName,
                    authTokenForApiRedirect = if (trimmed.contains("api.github.com", ignoreCase = true)) {
                        githubToken.takeIf { it.isNotBlank() }
                    } else null
                )
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun resolveLatestGitHubApkRelease(
        ownerRepo: String,
        githubToken: String = ""
    ): Result<AppUpdateReleaseInfo> = withContext(Dispatchers.IO) {
        val cleanRepo = normalizeOwnerRepo(ownerRepo)
            ?: return@withContext Result.failure(
                IllegalArgumentException("Repositório GitHub inválido. Use o formato usuario/repositorio.")
            )
        return@withContext try {
            val tokenOrNull = githubToken.trim().takeIf { it.isNotBlank() }
            val candidates = mutableListOf<AppUpdateReleaseInfo>()

            runCatching {
                val listJson = httpGetText(
                    "https://api.github.com/repos/$cleanRepo/releases?per_page=30",
                    githubToken
                )
                collectReleasesFromJson(JSONArray(listJson), tokenOrNull, candidates)
            }

            runCatching {
                val latestJson = httpGetText(
                    "https://api.github.com/repos/$cleanRepo/releases/latest",
                    githubToken
                )
                collectReleasesFromJson(JSONArray().apply { put(JSONObject(latestJson)) }, tokenOrNull, candidates)
            }

            val newest = selectNewestRelease(candidates)
                ?: return@withContext Result.failure(
                    IllegalStateException("Nenhuma Release com arquivo .apk encontrada em $cleanRepo.")
                )
            Result.success(newest)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun downloadAndVerifyApk(
        release: AppUpdateReleaseInfo,
        onProgress: (ApkDownloadProgress) -> Unit = {}
    ): Result<VerifiedApkInfo> = withContext(Dispatchers.IO) {
        val dir = updatesDir()
        val tempFile = File(dir, "download_${System.currentTimeMillis()}.apk.part")
        val targetFileName = "whatwillido_${release.uniqueKey()}.apk"
        val targetFile = File(dir, targetFileName)

        try {
            // Remove older cached APKs so an old app-debug.apk can never be picked up
            clearStaleCachedApks(keepFileName = null)

            val connection = openConnectionWithRedirects(
                initialUrl = release.apkUrl,
                authToken = release.authTokenForApiRedirect,
                acceptHeader = if (!release.authTokenForApiRedirect.isNullOrBlank()) {
                    "application/octet-stream"
                } else {
                    "*/*"
                }
            )
            try {
                val code = connection.responseCode
                if (code !in 200..299) {
                    val errText = connection.errorStream?.bufferedReader()?.use { it.readText() }
                        ?.take(220).orEmpty()
                    throw IllegalStateException("Falha no download do APK (HTTP $code): $errText")
                }

                val contentType = connection.contentType?.lowercase(Locale.ROOT).orEmpty()
                if (contentType.contains("text/html")) {
                    throw IllegalStateException(
                        "A URL retornou uma página HTML em vez de um arquivo .apk direto."
                    )
                }

                val totalBytes = connection.contentLengthLong.takeIf { it > 0L } ?: -1L
                var downloadedBytes = 0L
                val buffer = ByteArray(16 * 1024)

                connection.inputStream.use { input ->
                    FileOutputStream(tempFile).use { output ->
                        while (true) {
                            val read = input.read(buffer)
                            if (read < 0) break
                            if (read > 0) {
                                output.write(buffer, 0, read)
                                downloadedBytes += read
                                val fraction = if (totalBytes > 0L) {
                                    (downloadedBytes.toFloat() / totalBytes.toFloat()).coerceIn(0f, 1f)
                                } else null
                                onProgress(
                                    ApkDownloadProgress(
                                        downloadedBytes = downloadedBytes,
                                        totalBytes = totalBytes,
                                        fraction = fraction
                                    )
                                )
                            }
                        }
                        output.flush()
                    }
                }
            } finally {
                connection.disconnect()
            }

            if (targetFile.exists()) targetFile.delete()
            if (!tempFile.renameTo(targetFile)) {
                tempFile.copyTo(targetFile, overwrite = true)
                tempFile.delete()
            }

            validateApkFile(
                apkFile = targetFile,
                expectedSha256 = release.expectedSha256,
                release = release
            ).onFailure {
                targetFile.delete()
            }
        } catch (e: CancellationException) {
            tempFile.delete()
            throw e
        } catch (e: Exception) {
            tempFile.delete()
            Result.failure(e)
        }
    }

    private fun fetchLatestBranchCommit(
        ownerRepo: String,
        branch: String,
        githubToken: String
    ): GitCommitHeadInfo? {
        val cleanBranch = Uri.encode(branch.ifBlank { "main" })
        val body = httpGetText(
            "https://api.github.com/repos/$ownerRepo/commits/$cleanBranch",
            githubToken
        )
        val obj = JSONObject(body)
        val sha = obj.optString("sha").trim()
        if (sha.isBlank()) return null
        val commitObj = obj.optJSONObject("commit")
        val msg = commitObj?.optString("message")?.lineSequence()?.firstOrNull()?.take(100)
        val dateIso = commitObj?.optJSONObject("committer")?.optString("date")
            ?.ifBlank { commitObj.optJSONObject("author")?.optString("date").orEmpty() }
        return GitCommitHeadInfo(sha = sha, message = msg, dateIso = dateIso)
    }

    private fun fetchAndParseUpdateJson(
        jsonUrl: String,
        githubToken: String
    ): Result<AppUpdateReleaseInfo> {
        val text = httpGetText(jsonUrl, githubToken)
        val obj = JSONObject(text)
        val apkUrl = obj.optString("apkUrl")
            .ifBlank { obj.optString("downloadUrl") }
            .ifBlank { obj.optString("browser_download_url") }
            .ifBlank { obj.optString("url") }
            .trim()
        if (apkUrl.isBlank()) {
            return Result.failure(
                IllegalStateException("JSON de atualização não contém o campo 'apkUrl' ou 'url'.")
            )
        }
        val versionCode = if (obj.has("versionCode") && !obj.isNull("versionCode")) {
            obj.optLong("versionCode").takeIf { it > 0L }
        } else null
        val runNumber = if (obj.has("runNumber") && !obj.isNull("runNumber")) {
            obj.optInt("runNumber").takeIf { it > 0 }
        } else versionCode?.toInt()
        val versionName = obj.optString("versionName").ifBlank { obj.optString("version") }
            .takeIf { it.isNotBlank() }
        val commitSha = obj.optString("commitSha").ifBlank { obj.optString("sha") }
            .takeIf { it.isNotBlank() }
        val notes = obj.optString("releaseNotes").ifBlank { obj.optString("notes") }
            .takeIf { it.isNotBlank() }
        val sha256 = obj.optString("sha256").takeIf { it.isNotBlank() }
        return Result.success(
            AppUpdateReleaseInfo(
                apkUrl = apkUrl,
                runNumber = runNumber,
                commitSha = commitSha,
                versionCode = versionCode,
                versionName = versionName,
                releaseNotes = notes,
                expectedSha256 = sha256
            )
        )
    }

    private fun parseGitHubReleasesPayload(
        jsonBody: String,
        authToken: String?
    ): Result<AppUpdateReleaseInfo> {
        val trimmed = jsonBody.trim()
        val releases = if (trimmed.startsWith("[")) {
            JSONArray(trimmed)
        } else {
            JSONArray().apply { put(JSONObject(trimmed)) }
        }
        val candidates = mutableListOf<AppUpdateReleaseInfo>()
        collectReleasesFromJson(releases, authToken, candidates)
        val newest = selectNewestRelease(candidates)
            ?: return Result.failure(
                IllegalStateException("Nenhuma Release com arquivo .apk encontrada neste repositório GitHub.")
            )
        return Result.success(newest)
    }

    private fun collectReleasesFromJson(
        releases: JSONArray,
        authToken: String?,
        out: MutableList<AppUpdateReleaseInfo>
    ) {
        for (i in 0 until releases.length()) {
            val rel = releases.optJSONObject(i) ?: continue
            if (rel.optBoolean("draft", false)) continue
            val tagName = rel.optString("tag_name")
            if (tagName.equals("zap2-uploads", ignoreCase = true)) continue

            val assets = rel.optJSONArray("assets") ?: continue
            val apkAssets = mutableListOf<JSONObject>()
            for (a in 0 until assets.length()) {
                val asset = assets.optJSONObject(a) ?: continue
                val assetName = asset.optString("name")
                val size = asset.optLong("size", 0L)
                if (assetName.lowercase(Locale.ROOT).endsWith(".apk") && size > MIN_VALID_APK_BYTES) {
                    apkAssets += asset
                }
            }
            if (apkAssets.isEmpty()) continue

            // Prefer WhatwillIdo*.apk over Zap2*.apk over app-debug.apk, then newest updated_at
            val bestAsset = apkAssets.maxWithOrNull(
                compareBy<JSONObject>(
                    { assetPriorityScore(it.optString("name")) },
                    { it.optString("updated_at") }
                )
            ) ?: continue

            val assetName = bestAsset.optString("name")
            val browserUrl = bestAsset.optString("browser_download_url").trim()
            val apiAssetUrl = bestAsset.optString("url").trim()
            val downloadUrl = if (!authToken.isNullOrBlank() && apiAssetUrl.isNotBlank()) {
                apiAssetUrl
            } else {
                browserUrl.ifBlank { apiAssetUrl }
            }
            if (downloadUrl.isBlank()) continue

            val releaseId = rel.optLong("id", 0L).takeIf { it > 0L }
            val body = rel.optString("body")
            val title = rel.optString("name").ifBlank { tagName }.takeIf { it.isNotBlank() }
            val runNumber = parseRunNumber(tagName, title)
            val commitSha = extractCommitSha(
                targetCommitish = rel.optString("target_commitish"),
                releaseBody = body
            )
            val publishedAt = rel.optString("published_at").ifBlank { rel.optString("created_at") }

            out += AppUpdateReleaseInfo(
                apkUrl = downloadUrl,
                releaseId = releaseId,
                tagName = tagName.takeIf { it.isNotBlank() },
                runNumber = runNumber,
                commitSha = commitSha,
                publishedAt = publishedAt.takeIf { it.isNotBlank() },
                assetUpdatedAt = bestAsset.optString("updated_at").takeIf { it.isNotBlank() },
                versionCode = runNumber?.toLong(),
                versionName = title,
                releaseNotes = body.takeIf { it.isNotBlank() },
                fileName = assetName.ifBlank { "WhatwillIdo-update.apk" },
                authTokenForApiRedirect = authToken
            )
        }
    }

    private fun selectNewestRelease(candidates: List<AppUpdateReleaseInfo>): AppUpdateReleaseInfo? {
        if (candidates.isEmpty()) return null
        return candidates.distinctBy { it.releaseId ?: it.apkUrl.hashCode().toLong() }
            .maxWithOrNull(
                compareBy<AppUpdateReleaseInfo>(
                    { it.runNumber ?: -1 },
                    { it.publishedAt.orEmpty() },
                    { it.assetUpdatedAt.orEmpty() },
                    { it.releaseId ?: 0L }
                )
            )
    }

    private fun assetPriorityScore(assetName: String): Int {
        val lower = assetName.lowercase(Locale.ROOT)
        return when {
            lower.contains("whatwillido") -> 3
            lower.contains("zap2") -> 2
            else -> 1
        }
    }

    private fun parseRunNumber(tagName: String?, title: String?): Int? {
        val regex = Regex("(?:build[-_# ]*|#)(\\d+)", RegexOption.IGNORE_CASE)
        tagName?.let { tag ->
            regex.find(tag)?.groupValues?.getOrNull(1)?.toIntOrNull()?.let { return it }
        }
        title?.let { t ->
            regex.find(t)?.groupValues?.getOrNull(1)?.toIntOrNull()?.let { return it }
        }
        return null
    }

    private fun extractCommitSha(targetCommitish: String?, releaseBody: String?): String? {
        val hex40 = Regex("^[0-9a-fA-F]{40}$")
        val target = targetCommitish?.trim().orEmpty()
        if (hex40.matches(target)) return target.lowercase(Locale.ROOT)
        if (!releaseBody.isNullOrBlank()) {
            val bodyMatch = Regex("commit\\s+([0-9a-fA-F]{7,40})", RegexOption.IGNORE_CASE)
                .find(releaseBody)
                ?.groupValues
                ?.getOrNull(1)
            if (!bodyMatch.isNullOrBlank()) return bodyMatch.lowercase(Locale.ROOT)
        }
        return null
    }

    private fun normalizeOwnerRepo(ownerRepo: String): String? {
        val clean = ownerRepo.trim()
            .removePrefix("https://github.com/")
            .removePrefix("http://github.com/")
            .removeSuffix(".git")
            .trim('/')
        return if (clean.matches(Regex("^[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+$"))) clean else null
    }

    private fun extractGitHubOwnerRepo(input: String): String? {
        val clean = input.trim().removeSuffix("/")
        normalizeOwnerRepo(clean)?.let { return it }

        if (clean.startsWith("https://github.com/", ignoreCase = true) ||
            clean.startsWith("http://github.com/", ignoreCase = true)
        ) {
            val path = Uri.parse(clean).path?.trim('/') ?: return null
            val parts = path.split('/').filter { it.isNotBlank() }
            if (parts.size == 2) {
                return "${parts[0]}/${parts[1]}"
            }
            // Match repo releases page (/releases, /releases/latest) but NOT a direct asset .apk download URL
            if (parts.size >= 3 &&
                parts[2].equals("releases", ignoreCase = true) &&
                !clean.lowercase(Locale.ROOT).endsWith(".apk") &&
                (parts.size == 3 || parts[3].equals("latest", ignoreCase = true))
            ) {
                return "${parts[0]}/${parts[1]}"
            }
        }
        return null
    }

    private fun httpGetText(urlStr: String, githubToken: String): String {
        val conn = openConnectionWithRedirects(
            initialUrl = urlStr,
            authToken = githubToken.trim().takeIf { it.isNotBlank() },
            acceptHeader = "application/vnd.github+json, application/json"
        )
        return try {
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val body = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code !in 200..299) {
                throw IllegalStateException("Erro HTTP $code ao consultar atualização: ${body.take(200)}")
            }
            body
        } finally {
            conn.disconnect()
        }
    }

    private fun openConnectionWithRedirects(
        initialUrl: String,
        authToken: String?,
        acceptHeader: String
    ): HttpURLConnection {
        var currentUrl = initialUrl
        var redirects = 0
        val initialHost = URL(initialUrl).host

        while (true) {
            val urlObj = URL(currentUrl)
            val conn = (urlObj.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                instanceFollowRedirects = false
                useCaches = false
                defaultUseCaches = false
                connectTimeout = 20_000
                readTimeout = 60_000
                setRequestProperty("User-Agent", "WhatwillIdo-Android")
                setRequestProperty("Accept", acceptHeader)
                setRequestProperty("Cache-Control", "no-cache, no-store, must-revalidate")
                setRequestProperty("Pragma", "no-cache")
                if (!authToken.isNullOrBlank() && urlObj.host.equals(initialHost, ignoreCase = true)) {
                    setRequestProperty("Authorization", "Bearer $authToken")
                }
            }
            val code = conn.responseCode
            if (code in listOf(
                    HttpURLConnection.HTTP_MOVED_PERM,
                    HttpURLConnection.HTTP_MOVED_TEMP,
                    HttpURLConnection.HTTP_SEE_OTHER,
                    307,
                    308
                )
            ) {
                val location = conn.getHeaderField("Location")
                conn.disconnect()
                if (location.isNullOrBlank() || redirects >= MAX_REDIRECTS) {
                    throw IllegalStateException("Redirecionamento HTTP inválido ao baixar atualização.")
                }
                currentUrl = URL(urlObj, location).toString()
                redirects++
                continue
            }
            return conn
        }
    }

    private fun readPackageArchiveInfo(apkFile: File): PackageInfo? {
        return runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.packageManager.getPackageArchiveInfo(
                    apkFile.absolutePath,
                    PackageManager.PackageInfoFlags.of(0L)
                )
            } else {
                @Suppress("DEPRECATION")
                context.packageManager.getPackageArchiveInfo(apkFile.absolutePath, 0)
            }
        }.getOrNull()
    }

    private fun computeSha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val buf = ByteArray(16 * 1024)
        FileInputStream(file).use { input ->
            while (true) {
                val read = input.read(buf)
                if (read < 0) break
                if (read > 0) digest.update(buf, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    companion object {
        const val APK_MIME_TYPE = "application/vnd.android.package-archive"
        private const val MIN_VALID_APK_BYTES = 1024L
        private const val MAX_REDIRECTS = 6
    }
}
