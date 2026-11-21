package com.example.service

import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
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
    val versionCode: Long? = null,
    val versionName: String? = null,
    val releaseNotes: String? = null,
    val expectedSha256: String? = null,
    val fileName: String = "WhatwillIdo-update.apk",
    val authTokenForApiRedirect: String? = null
)

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
    val versionCode: Long?
)

sealed interface ApkInstallLaunchResult {
    data object Launched : ApkInstallLaunchResult
    data object NeedsInstallPermission : ApkInstallLaunchResult
    data class Error(val message: String) : ApkInstallLaunchResult
}

class AppUpdateManager(private val context: Context) {

    fun updatesDir(): File = File(context.cacheDir, "updates").apply { mkdirs() }

    fun getCachedUpdateApk(): File? {
        val candidate = File(updatesDir(), DEFAULT_APK_FILE_NAME)
        return if (candidate.exists() && candidate.isFile && candidate.length() > MIN_VALID_APK_BYTES) {
            candidate
        } else {
            null
        }
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
        expectedSha256: String? = null
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
                versionCode = archiveVersionCode
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
            // 1. Check if input is a GitHub repository slug (owner/repo) or repo URL
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

            // Probe direct URL (HEAD or JSON auto-detect if user passed an API/manifest URL without .json)
            if (!lowerPath.endsWith(".apk") && trimmed.contains("api.github.com/repos/", ignoreCase = true)) {
                return@withContext parseGitHubReleasesPayload(
                    jsonBody = httpGetText(trimmed, githubToken),
                    authToken = githubToken.takeIf { it.isNotBlank() }
                )
            }

            val fileName = parsed.lastPathSegment
                ?.takeIf { it.lowercase(Locale.ROOT).endsWith(".apk") }
                ?: DEFAULT_APK_FILE_NAME
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
        val cleanRepo = ownerRepo.trim()
            .removePrefix("https://github.com/")
            .removePrefix("http://github.com/")
            .removeSuffix(".git")
            .trim('/')
        if (!cleanRepo.contains('/') || cleanRepo.count { it == '/' } != 1) {
            return@withContext Result.failure(
                IllegalArgumentException("Repositório GitHub inválido. Use o formato usuario/repositorio.")
            )
        }
        return@withContext try {
            val apiUrl = "https://api.github.com/repos/$cleanRepo/releases?per_page=15"
            val body = httpGetText(apiUrl, githubToken)
            parseGitHubReleasesPayload(
                jsonBody = body,
                authToken = githubToken.trim().takeIf { it.isNotBlank() }
            )
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
        val targetFile = File(dir, DEFAULT_APK_FILE_NAME)

        try {
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
                val digest = MessageDigest.getInstance("SHA-256")
                val buffer = ByteArray(16 * 1024)

                connection.inputStream.use { input ->
                    FileOutputStream(tempFile).use { output ->
                        while (true) {
                            val read = input.read(buffer)
                            if (read < 0) break
                            if (read > 0) {
                                output.write(buffer, 0, read)
                                digest.update(buffer, 0, read)
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

            validateApkFile(targetFile, release.expectedSha256).onFailure {
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
        val versionName = obj.optString("versionName").ifBlank { obj.optString("version") }
            .takeIf { it.isNotBlank() }
        val notes = obj.optString("releaseNotes").ifBlank { obj.optString("notes") }
            .takeIf { it.isNotBlank() }
        val sha256 = obj.optString("sha256").takeIf { it.isNotBlank() }
        return Result.success(
            AppUpdateReleaseInfo(
                apkUrl = apkUrl,
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

        for (i in 0 until releases.length()) {
            val rel = releases.optJSONObject(i) ?: continue
            if (rel.optBoolean("draft", false)) continue
            val tagName = rel.optString("tag_name")
            if (tagName.equals("zap2-uploads", ignoreCase = true)) continue

            val assets = rel.optJSONArray("assets") ?: continue
            for (a in 0 until assets.length()) {
                val asset = assets.optJSONObject(a) ?: continue
                val assetName = asset.optString("name")
                if (!assetName.lowercase(Locale.ROOT).endsWith(".apk")) continue

                val browserUrl = asset.optString("browser_download_url").trim()
                val apiAssetUrl = asset.optString("url").trim()
                val downloadUrl = if (!authToken.isNullOrBlank() && apiAssetUrl.isNotBlank()) {
                    apiAssetUrl
                } else {
                    browserUrl.ifBlank { apiAssetUrl }
                }
                if (downloadUrl.isBlank()) continue

                val title = rel.optString("name").ifBlank { tagName }.takeIf { it.isNotBlank() }
                val notes = rel.optString("body").takeIf { it.isNotBlank() }
                return Result.success(
                    AppUpdateReleaseInfo(
                        apkUrl = downloadUrl,
                        versionName = title,
                        releaseNotes = notes,
                        fileName = assetName.ifBlank { DEFAULT_APK_FILE_NAME },
                        authTokenForApiRedirect = authToken
                    )
                )
            }
        }
        return Result.failure(
            IllegalStateException("Nenhuma Release com arquivo .apk encontrada neste repositório GitHub.")
        )
    }

    private fun extractGitHubOwnerRepo(input: String): String? {
        val clean = input.trim().removeSuffix("/")
        if (clean.matches(Regex("^[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+$"))) {
            return clean
        }
        if (clean.startsWith("https://github.com/", ignoreCase = true) ||
            clean.startsWith("http://github.com/", ignoreCase = true)
        ) {
            val path = Uri.parse(clean).path?.trim('/') ?: return null
            val parts = path.split('/').filter { it.isNotBlank() }
            if (parts.size == 2 || (parts.size >= 3 && parts[2].equals("releases", ignoreCase = true))) {
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
                connectTimeout = 20_000
                readTimeout = 60_000
                setRequestProperty("User-Agent", "WhatwillIdo-Android")
                setRequestProperty("Accept", acceptHeader)
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
        private const val DEFAULT_APK_FILE_NAME = "whatwillido-update.apk"
        private const val MIN_VALID_APK_BYTES = 1024L
        private const val MAX_REDIRECTS = 6
    }
}
