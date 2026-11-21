package com.example.data.remote

import android.content.Context
import android.net.Uri
import android.util.Base64
import android.util.Log
import com.example.Zap2Application
import com.example.data.local.STORAGE_MODE_AUTO
import com.example.data.local.STORAGE_MODE_FIRESTORE
import com.example.data.local.STORAGE_MODE_GITHUB
import com.example.data.local.SettingsDataStore
import com.example.data.local.UserSettings
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class FreeMediaStorageManager(
    private val context: Context,
    private val db: FirebaseFirestore,
    private val settingsStore: SettingsDataStore
) {
    private val resolvedCacheDir: File by lazy {
        File(context.cacheDir, "resolved_media").apply { mkdirs() }
    }

    private val inFlightDownloads = ConcurrentHashMap<String, CompletableDeferred<File>>()
    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private suspend fun chooseGitHubConfigOrNull(): GitHubRepoConfig? {
        val settings: UserSettings = settingsStore.settingsFlow.first()
        val hasRepoAndToken = settings.githubRepo.contains("/") && settings.githubToken.isNotBlank()
        return when (settings.storageBackendMode) {
            STORAGE_MODE_FIRESTORE -> null
            STORAGE_MODE_GITHUB -> {
                if (!hasRepoAndToken) {
                    throw IllegalStateException(
                        "Configure o Repositório GitHub (dono/repo) e o Token em Perfil > Armazenamento Gratuito, ou mude para Automático / Firestore."
                    )
                }
                GitHubRepoConfig(settings.githubRepo, settings.githubBranch, settings.githubToken)
            }
            STORAGE_MODE_AUTO, "" -> {
                if (hasRepoAndToken) {
                    GitHubRepoConfig(settings.githubRepo, settings.githubBranch, settings.githubToken)
                } else {
                    null
                }
            }
            else -> null
        }
    }

    suspend fun uploadChatFile(
        chatId: String,
        senderId: String,
        participantUids: List<String>,
        file: File,
        displayName: String,
        mimeType: String,
        onProgress: (Long, Long) -> Unit
    ): String {
        val totalBytes = file.length().coerceAtLeast(1L)
        onProgress(0L, totalBytes)

        val ghConfig = chooseGitHubConfigOrNull()
        if (ghConfig != null) {
            val safeName = sanitizeRepoFileName(displayName.ifBlank { file.name })
            val repoPath = "zap2_media/chats/$chatId/${System.currentTimeMillis()}_$safeName"
            return uploadToGitHubRepository(
                config = ghConfig,
                repoRelativePath = repoPath,
                file = file,
                mimeType = mimeType,
                onProgress = onProgress
            )
        }

        if (totalBytes > MAX_FIRESTORE_FILE_BYTES) {
            throw IllegalStateException(
                "Arquivo de ${totalBytes / (1024 * 1024)} MB excede o limite de 35 MB do Firestore gratuito. Para enviar até 200 MB grátis, configure seu Repositório GitHub em Perfil > Armazenamento Gratuito."
            )
        }

        val fileId = UUID.randomUUID().toString().replace("-", "").take(24)
        val ext = file.extension.lowercase().ifBlank { "bin" }.take(12)
        val totalChunks = ((totalBytes + FIRESTORE_CHUNK_BYTES - 1L) / FIRESTORE_CHUNK_BYTES).toInt().coerceAtLeast(1)
        val cleanParticipants = participantUids.distinct().take(50)

        withContext(Dispatchers.IO) {
            val buffer = ByteArray(FIRESTORE_CHUNK_BYTES)
            var bytesSent = 0L
            var chunkIdx = 0
            file.inputStream().use { input ->
                while (chunkIdx < totalChunks) {
                    val read = readFullChunk(input, buffer)
                    if (read <= 0) break
                    val encoded = Base64.encodeToString(buffer, 0, read, Base64.NO_WRAP)
                    val chunkDocId = "${fileId}_$chunkIdx"
                    db.collection("chats").document(chatId)
                        .collection("file_chunks").document(chunkDocId)
                        .set(
                            mapOf(
                                "chatId" to chatId,
                                "fileId" to fileId,
                                "chunkIndex" to chunkIdx,
                                "totalChunks" to totalChunks,
                                "senderId" to senderId,
                                "participantUids" to cleanParticipants,
                                "data" to encoded,
                                "createdAt" to FieldValue.serverTimestamp()
                            )
                        )
                        .await()
                    bytesSent += read
                    chunkIdx++
                    onProgress(bytesSent.coerceAtMost(totalBytes), totalBytes)
                }
            }
        }
        return "zap2chunk://chat/$chatId/$fileId?chunks=$totalChunks&ext=$ext"
    }

    suspend fun uploadPublicProfileMedia(
        ownerId: String,
        file: File,
        mimeType: String,
        onProgress: (Float) -> Unit
    ): String {
        val totalBytes = file.length().coerceAtLeast(1L)
        onProgress(0f)

        val ghConfig = chooseGitHubConfigOrNull()
        if (ghConfig != null) {
            val safeName = sanitizeRepoFileName(file.name)
            val repoPath = "zap2_media/profiles/$ownerId/${System.currentTimeMillis()}_$safeName"
            return uploadToGitHubRepository(
                config = ghConfig,
                repoRelativePath = repoPath,
                file = file,
                mimeType = mimeType,
                onProgress = { sent, total ->
                    onProgress((sent.toFloat() / total.toFloat()).coerceIn(0f, 1f))
                }
            )
        }

        if (totalBytes > MAX_FIRESTORE_PUBLIC_MEDIA_BYTES) {
            throw IllegalStateException(
                "A mídia do perfil ultrapassa 20 MB no modo Firestore gratuito. Use um clipe menor ou configure seu Repositório GitHub em Perfil."
            )
        }

        val mediaId = UUID.randomUUID().toString().replace("-", "").take(24)
        val ext = file.extension.lowercase().ifBlank { "jpg" }.take(12)
        val totalChunks = ((totalBytes + FIRESTORE_CHUNK_BYTES - 1L) / FIRESTORE_CHUNK_BYTES).toInt().coerceAtLeast(1)

        withContext(Dispatchers.IO) {
            val buffer = ByteArray(FIRESTORE_CHUNK_BYTES)
            var bytesSent = 0L
            var chunkIdx = 0
            file.inputStream().use { input ->
                while (chunkIdx < totalChunks) {
                    val read = readFullChunk(input, buffer)
                    if (read <= 0) break
                    val encoded = Base64.encodeToString(buffer, 0, read, Base64.NO_WRAP)
                    val chunkDocId = "${mediaId}_$chunkIdx"
                    db.collection("public_media_chunks").document(chunkDocId)
                        .set(
                            mapOf(
                                "ownerId" to ownerId,
                                "mediaId" to mediaId,
                                "chunkIndex" to chunkIdx,
                                "totalChunks" to totalChunks,
                                "mimeType" to mimeType.take(128).ifBlank { "application/octet-stream" },
                                "data" to encoded,
                                "createdAt" to FieldValue.serverTimestamp()
                            )
                        )
                        .await()
                    bytesSent += read
                    chunkIdx++
                    onProgress((bytesSent.toFloat() / totalBytes.toFloat()).coerceIn(0f, 1f))
                }
            }
        }
        return "zap2chunk://public/$mediaId?chunks=$totalChunks&ext=$ext"
    }

    suspend fun uploadBackupFile(
        ownerId: String,
        file: File,
        onProgress: (Float) -> Unit
    ): String {
        val totalBytes = file.length().coerceAtLeast(1L)
        onProgress(0f)

        val ghConfig = chooseGitHubConfigOrNull()
        if (ghConfig != null) {
            val repoPath = "zap2_media/backups/$ownerId/zap2_latest_backup.zip"
            return uploadToGitHubRepository(
                config = ghConfig,
                repoRelativePath = repoPath,
                file = file,
                mimeType = "application/zip",
                allowOverwriteExisting = true,
                onProgress = { sent, total ->
                    onProgress((sent.toFloat() / total.toFloat()).coerceIn(0f, 1f))
                }
            )
        }

        if (totalBytes > MAX_FIRESTORE_FILE_BYTES) {
            throw IllegalStateException(
                "Backup de ${totalBytes / (1024 * 1024)} MB excede 35 MB do Firestore gratuito. Configure seu Repositório GitHub em Perfil > Armazenamento Gratuito."
            )
        }

        val backupId = UUID.randomUUID().toString().replace("-", "").take(24)
        val totalChunks = ((totalBytes + FIRESTORE_CHUNK_BYTES - 1L) / FIRESTORE_CHUNK_BYTES).toInt().coerceAtLeast(1)

        withContext(Dispatchers.IO) {
            val buffer = ByteArray(FIRESTORE_CHUNK_BYTES)
            var bytesSent = 0L
            var chunkIdx = 0
            file.inputStream().use { input ->
                while (chunkIdx < totalChunks) {
                    val read = readFullChunk(input, buffer)
                    if (read <= 0) break
                    val encoded = Base64.encodeToString(buffer, 0, read, Base64.NO_WRAP)
                    val chunkDocId = "${backupId}_$chunkIdx"
                    db.collection("users").document(ownerId)
                        .collection("backup_chunks").document(chunkDocId)
                        .set(
                            mapOf(
                                "ownerId" to ownerId,
                                "backupId" to backupId,
                                "chunkIndex" to chunkIdx,
                                "totalChunks" to totalChunks,
                                "data" to encoded,
                                "createdAt" to FieldValue.serverTimestamp()
                            )
                        )
                        .await()
                    bytesSent += read
                    chunkIdx++
                    onProgress((bytesSent.toFloat() / totalBytes.toFloat()).coerceIn(0f, 1f))
                }
            }
        }
        return "zap2chunk://backup/$ownerId/$backupId?chunks=$totalChunks&ext=zip"
    }

    suspend fun deleteChatChunksIfNeeded(chatId: String, mediaUrl: String?) {
        val url = mediaUrl?.trim().orEmpty()
        if (!url.startsWith("zap2chunk://chat/")) return
        val parsed = Uri.parse(url)
        val segments = parsed.pathSegments
        val fileId = segments.getOrNull(0) ?: return
        val totalChunks = parsed.getQueryParameter("chunks")?.toIntOrNull()?.coerceIn(1, 120) ?: return
        for (i in 0 until totalChunks) {
            runCatching {
                db.collection("chats").document(chatId)
                    .collection("file_chunks").document("${fileId}_$i")
                    .delete()
                    .await()
            }
        }
    }

    suspend fun resolveToFile(
        url: String,
        preferredFileName: String? = null,
        expectedSizeBytes: Long? = null,
        onProgress: (Float) -> Unit = {}
    ): Result<File> = withContext(Dispatchers.IO) {
        try {
            val trimmed = url.trim()
            if (trimmed.isBlank()) {
                return@withContext Result.failure(IllegalArgumentException("URL de mídia vazia."))
            }

            if (trimmed.startsWith("file://")) {
                val f = File(Uri.parse(trimmed).path.orEmpty())
                return@withContext if (f.exists() && f.length() > 0L) {
                    onProgress(1f)
                    Result.success(f)
                } else {
                    Result.failure(IllegalStateException("Arquivo local não encontrado."))
                }
            }

            val extHint = parseExtFromChunkUri(trimmed)
                ?: preferredFileName?.substringAfterLast('.', "")?.lowercase()?.takeIf { it.isNotBlank() }
                ?: Uri.parse(trimmed).lastPathSegment?.substringAfterLast('.', "")?.lowercase()?.takeIf { it.length in 1..6 }
                ?: "bin"
            val cacheKey = sha256Short(trimmed)
            val safeName = preferredFileName
                ?.replace(Regex("[^a-zA-Z0-9._\\- ()]"), "_")
                ?.take(80)
                ?.ifBlank { "${cacheKey}.$extHint" }
                ?: "${cacheKey}.$extHint"
            val cachedFile = File(resolvedCacheDir, "${cacheKey}_$safeName")

            if (cachedFile.exists() && cachedFile.length() > 0L &&
                (expectedSizeBytes == null || expectedSizeBytes <= 0L || cachedFile.length() == expectedSizeBytes)
            ) {
                onProgress(1f)
                return@withContext Result.success(cachedFile)
            }

            if (trimmed.startsWith("data:")) {
                val b64 = trimmed.substringAfter(",", "")
                val bytes = Base64.decode(b64, Base64.DEFAULT)
                cachedFile.writeBytes(bytes)
                onProgress(1f)
                return@withContext Result.success(cachedFile)
            }

            val existingFlight = inFlightDownloads[cacheKey]
            if (existingFlight != null) {
                val doneFile = existingFlight.await()
                onProgress(1f)
                return@withContext Result.success(doneFile)
            }

            val deferred = CompletableDeferred<File>()
            val raceWinner = inFlightDownloads.putIfAbsent(cacheKey, deferred)
            if (raceWinner != null) {
                val doneFile = raceWinner.await()
                onProgress(1f)
                return@withContext Result.success(doneFile)
            }

            try {
                val tempFile = File(resolvedCacheDir, "${cacheKey}_$safeName.part")
                if (tempFile.exists()) tempFile.delete()

                if (trimmed.startsWith("zap2chunk://")) {
                    downloadChunkedFromFirestore(trimmed, tempFile, onProgress)
                } else {
                    val token = settingsStore.settingsFlow.first().githubToken.takeIf { it.isNotBlank() }
                    downloadHttpFile(trimmed, tempFile, expectedSizeBytes, token, onProgress)
                }

                if (cachedFile.exists()) cachedFile.delete()
                if (!tempFile.renameTo(cachedFile)) {
                    tempFile.copyTo(cachedFile, overwrite = true)
                    tempFile.delete()
                }
                onProgress(1f)
                deferred.complete(cachedFile)
                Result.success(cachedFile)
            } catch (e: Exception) {
                deferred.completeExceptionally(e)
                throw e
            } finally {
                inFlightDownloads.remove(cacheKey, deferred)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private suspend fun downloadChunkedFromFirestore(
        chunkUri: String,
        destFile: File,
        onProgress: (Float) -> Unit
    ) {
        val uri = Uri.parse(chunkUri)
        val kind = uri.host?.lowercase() ?: throw IllegalArgumentException("URI de bloco inválida.")
        val segments = uri.pathSegments
        val totalChunks = uri.getQueryParameter("chunks")?.toIntOrNull()?.coerceIn(1, 120)
            ?: throw IllegalArgumentException("Contagem de blocos ausente.")

        BufferedOutputStream(FileOutputStream(destFile)).use { out ->
            for (i in 0 until totalChunks) {
                val docSnap = when (kind) {
                    "chat" -> {
                        val chatId = segments.getOrNull(0) ?: error("chatId ausente")
                        val fileId = segments.getOrNull(1) ?: error("fileId ausente")
                        db.collection("chats").document(chatId)
                            .collection("file_chunks").document("${fileId}_$i")
                            .get().await()
                    }
                    "public" -> {
                        val mediaId = segments.getOrNull(0) ?: error("mediaId ausente")
                        db.collection("public_media_chunks").document("${mediaId}_$i")
                            .get().await()
                    }
                    "backup" -> {
                        val ownerId = segments.getOrNull(0) ?: error("ownerId ausente")
                        val backupId = segments.getOrNull(1) ?: error("backupId ausente")
                        db.collection("users").document(ownerId)
                            .collection("backup_chunks").document("${backupId}_$i")
                            .get().await()
                    }
                    else -> throw IllegalArgumentException("Tipo de bloco desconhecido: $kind")
                }

                if (!docSnap.exists()) {
                    throw IllegalStateException("Parte ${i + 1}/$totalChunks do arquivo não foi encontrada.")
                }
                val b64 = docSnap.getString("data")
                    ?: throw IllegalStateException("Bloco corrompido na parte ${i + 1}.")
                val decoded = Base64.decode(b64, Base64.DEFAULT)
                out.write(decoded)
                onProgress(((i + 1).toFloat() / totalChunks.toFloat()).coerceIn(0f, 1f))
            }
            out.flush()
        }
    }

    private fun downloadHttpFile(
        rawUrl: String,
        destFile: File,
        expectedSizeBytes: Long?,
        githubToken: String?,
        onProgress: (Float) -> Unit
    ) {
        val urlObj = URL(rawUrl)
        val conn = (urlObj.openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 45_000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "Zap2-Android")
            val host = urlObj.host.lowercase()
            if (!githubToken.isNullOrBlank() &&
                (host == "api.github.com" || host == "raw.githubusercontent.com" || host == "github.com")
            ) {
                setRequestProperty("Authorization", "Bearer $githubToken")
            }
        }
        try {
            val code = conn.responseCode
            if (code !in 200..299) {
                throw IllegalStateException("Erro ao baixar arquivo (HTTP $code).")
            }
            val total = conn.contentLengthLong.coerceAtLeast(expectedSizeBytes ?: 0L)
            var copied = 0L
            conn.inputStream.use { input ->
                destFile.outputStream().use { output ->
                    val buf = ByteArray(16_384)
                    var read: Int
                    while (input.read(buf).also { read = it } != -1) {
                        output.write(buf, 0, read)
                        copied += read
                        if (total > 0L) {
                            onProgress((copied.toFloat() / total.toFloat()).coerceIn(0f, 1f))
                        }
                    }
                }
            }
        } finally {
            conn.disconnect()
        }
    }

    private suspend fun uploadToGitHubRepository(
        config: GitHubRepoConfig,
        repoRelativePath: String,
        file: File,
        mimeType: String,
        allowOverwriteExisting: Boolean = false,
        onProgress: (Long, Long) -> Unit
    ): String = withContext(Dispatchers.IO) {
        val totalBytes = file.length().coerceAtLeast(1L)
        if (totalBytes <= GITHUB_CONTENTS_MAX_BYTES) {
            uploadViaGitHubContentsApi(
                config = config,
                repoRelativePath = repoRelativePath,
                file = file,
                allowOverwriteExisting = allowOverwriteExisting,
                onProgress = onProgress
            )
        } else {
            uploadViaGitHubReleaseAsset(
                config = config,
                assetFileName = "${System.currentTimeMillis()}_${sanitizeRepoFileName(file.name)}",
                file = file,
                mimeType = mimeType,
                onProgress = onProgress
            )
        }
    }

    private fun uploadViaGitHubContentsApi(
        config: GitHubRepoConfig,
        repoRelativePath: String,
        file: File,
        allowOverwriteExisting: Boolean,
        onProgress: (Long, Long) -> Unit
    ): String {
        val encodedPath = repoRelativePath.split("/")
            .joinToString("/") { URLEncoder.encode(it, "UTF-8").replace("+", "%20") }
        val apiUrl = "https://api.github.com/repos/${config.ownerRepo}/contents/$encodedPath"

        val existingSha = if (allowOverwriteExisting) {
            fetchExistingFileSha(apiUrl, config.branch, config.token)
        } else null

        val tempJsonPayload = File(context.cacheDir, "gh_put_${UUID.randomUUID()}.json")
        try {
            tempJsonPayload.outputStream().buffered().use { out ->
                val msgJson = JSONObject.quote("Zap2 upload: ${file.name}")
                val branchJson = JSONObject.quote(config.branch)
                val shaSegment = if (!existingSha.isNullOrBlank()) {
                    ""","sha":${JSONObject.quote(existingSha)}"""
                } else ""
                out.write("""{"message":$msgJson,"branch":$branchJson$shaSegment,"content":"""".toByteArray(Charsets.UTF_8))

                // 48 KB is divisible by 3 -> seamless Base64 chunking without padding in the middle!
                val buf = ByteArray(48 * 1024)
                file.inputStream().use { input ->
                    while (true) {
                        val read = readFullChunk(input, buf)
                        if (read <= 0) break
                        val b64Chunk = Base64.encode(buf, 0, read, Base64.NO_WRAP)
                        out.write(b64Chunk)
                    }
                }
                out.write(""""}""".toByteArray(Charsets.UTF_8))
                out.flush()
            }

            val payloadLength = tempJsonPayload.length()
            val conn = (URL(apiUrl).openConnection() as HttpURLConnection).apply {
                requestMethod = "PUT"
                doOutput = true
                connectTimeout = 20_000
                readTimeout = 90_000
                setFixedLengthStreamingMode(payloadLength)
                setRequestProperty("Authorization", "Bearer ${config.token}")
                setRequestProperty("Accept", "application/vnd.github+json")
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
                setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
                setRequestProperty("User-Agent", "Zap2-Android")
            }

            try {
                val fileTotal = file.length().coerceAtLeast(1L)
                var streamed = 0L
                val streamBuf = ByteArray(32 * 1024)
                tempJsonPayload.inputStream().use { input ->
                    conn.outputStream.use { output ->
                        var read: Int
                        while (input.read(streamBuf).also { read = it } != -1) {
                            output.write(streamBuf, 0, read)
                            streamed += read
                            val pct = (streamed.toFloat() / payloadLength.toFloat()).coerceIn(0f, 0.98f)
                            onProgress((fileTotal * pct).toLong(), fileTotal)
                        }
                        output.flush()
                    }
                }

                val code = conn.responseCode
                val respText = (if (code in 200..299) conn.inputStream else conn.errorStream)
                    ?.bufferedReader()?.use { it.readText() }.orEmpty()
                if (code !in 200..299) {
                    val ghMsg = runCatching { JSONObject(respText).optString("message") }.getOrDefault("")
                    throw IllegalStateException(
                        "Erro no GitHub (HTTP $code): ${ghMsg.ifBlank { "verifique repositório, branch e permissões Contents do token." }}"
                    )
                }

                onProgress(fileTotal, fileTotal)
                val json = JSONObject(respText)
                val downloadUrl = json.optJSONObject("content")?.optString("download_url").orEmpty()
                return downloadUrl.ifBlank {
                    "https://raw.githubusercontent.com/${config.ownerRepo}/${config.branch}/$encodedPath"
                }
            } finally {
                conn.disconnect()
            }
        } finally {
            tempJsonPayload.delete()
        }
    }

    private fun fetchExistingFileSha(apiUrl: String, branch: String, token: String): String? {
        return runCatching {
            val conn = (URL("$apiUrl?ref=${URLEncoder.encode(branch, "UTF-8")}").openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 10_000
                readTimeout = 15_000
                setRequestProperty("Authorization", "Bearer $token")
                setRequestProperty("Accept", "application/vnd.github+json")
                setRequestProperty("User-Agent", "Zap2-Android")
            }
            try {
                if (conn.responseCode in 200..299) {
                    val body = conn.inputStream.bufferedReader().use { it.readText() }
                    JSONObject(body).optString("sha").takeIf { it.isNotBlank() }
                } else null
            } finally {
                conn.disconnect()
            }
        }.getOrNull()
    }

    private fun uploadViaGitHubReleaseAsset(
        config: GitHubRepoConfig,
        assetFileName: String,
        file: File,
        mimeType: String,
        onProgress: (Long, Long) -> Unit
    ): String {
        val releaseId = ensureZap2MediaRelease(config)
        val encodedName = URLEncoder.encode(assetFileName, "UTF-8")
        val uploadUrl = "https://uploads.github.com/repos/${config.ownerRepo}/releases/$releaseId/assets?name=$encodedName"

        val totalSize = file.length().coerceAtLeast(1L)
        val conn = (URL(uploadUrl).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = 20_000
            readTimeout = 180_000
            setFixedLengthStreamingMode(totalSize)
            setRequestProperty("Authorization", "Bearer ${config.token}")
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("Content-Type", mimeType.ifBlank { "application/octet-stream" })
            setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
            setRequestProperty("User-Agent", "Zap2-Android")
        }

        try {
            var uploaded = 0L
            val buf = ByteArray(32 * 1024)
            file.inputStream().use { input ->
                conn.outputStream.use { output ->
                    var read: Int
                    while (input.read(buf).also { read = it } != -1) {
                        output.write(buf, 0, read)
                        uploaded += read
                        onProgress(uploaded, totalSize)
                    }
                    output.flush()
                }
            }
            val code = conn.responseCode
            val respText = (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code !in 200..299) {
                val ghMsg = runCatching { JSONObject(respText).optString("message") }.getOrDefault("")
                throw IllegalStateException("Erro ao enviar asset grande no GitHub (HTTP $code): $ghMsg")
            }
            val json = JSONObject(respText)
            val browserUrl = json.optString("browser_download_url")
            return browserUrl.ifBlank {
                throw IllegalStateException("GitHub não retornou a URL do asset.")
            }
        } finally {
            conn.disconnect()
        }
    }

    private fun ensureZap2MediaRelease(config: GitHubRepoConfig): Long {
        val tagUrl = "https://api.github.com/repos/${config.ownerRepo}/releases/tags/zap2-uploads"
        val getConn = (URL(tagUrl).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 12_000
            readTimeout = 20_000
            setRequestProperty("Authorization", "Bearer ${config.token}")
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("User-Agent", "Zap2-Android")
        }
        try {
            if (getConn.responseCode in 200..299) {
                val body = getConn.inputStream.bufferedReader().use { it.readText() }
                val id = JSONObject(body).optLong("id", -1L)
                if (id > 0L) return id
            }
        } finally {
            getConn.disconnect()
        }

        val createUrl = "https://api.github.com/repos/${config.ownerRepo}/releases"
        val postConn = (URL(createUrl).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = 15_000
            readTimeout = 25_000
            setRequestProperty("Authorization", "Bearer ${config.token}")
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
            setRequestProperty("User-Agent", "Zap2-Android")
        }
        try {
            val payload = JSONObject().apply {
                put("tag_name", "zap2-uploads")
                put("target_commitish", config.branch)
                put("name", "Zap2 Media & Files")
                put("body", "Arquivos gratuitos armazenados pelo Zap2")
                put("draft", false)
                put("prerelease", false)
            }.toString()
            postConn.outputStream.use { it.write(payload.toByteArray(Charsets.UTF_8)) }
            val code = postConn.responseCode
            val resp = (if (code in 200..299) postConn.inputStream else postConn.errorStream)
                ?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code !in 200..299) {
                throw IllegalStateException("Falha ao criar Release de uploads no GitHub (HTTP $code): $resp")
            }
            return JSONObject(resp).getLong("id")
        } finally {
            postConn.disconnect()
        }
    }

    suspend fun testGitHubConfig(repoRaw: String, branchRaw: String, tokenRaw: String): Result<String> =
        withContext(Dispatchers.IO) {
            try {
                val cleanRepo = repoRaw.trim()
                    .removePrefix("https://github.com/")
                    .removePrefix("http://github.com/")
                    .removeSuffix(".git")
                    .trim('/')
                val cleanBranch = branchRaw.trim().ifBlank { "main" }
                val cleanToken = tokenRaw.trim()
                if (!cleanRepo.contains("/") || cleanToken.isBlank()) {
                    return@withContext Result.failure(
                        IllegalArgumentException("Informe dono/repositorio e o Token GitHub.")
                    )
                }
                val conn = (URL("https://api.github.com/repos/$cleanRepo").openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = 12_000
                    readTimeout = 20_000
                    setRequestProperty("Authorization", "Bearer $cleanToken")
                    setRequestProperty("Accept", "application/vnd.github+json")
                    setRequestProperty("User-Agent", "Zap2-Android")
                }
                try {
                    val code = conn.responseCode
                    val body = (if (code in 200..299) conn.inputStream else conn.errorStream)
                        ?.bufferedReader()?.use { it.readText() }.orEmpty()
                    if (code !in 200..299) {
                        val msg = runCatching { JSONObject(body).optString("message") }.getOrDefault("Erro HTTP $code")
                        return@withContext Result.failure(IllegalStateException("GitHub recusou ($code): $msg"))
                    }
                    val json = JSONObject(body)
                    val pushOk = json.optJSONObject("permissions")?.optBoolean("push", true) ?: true
                    val isPrivate = json.optBoolean("private", false)
                    val visibilityNote = if (isPrivate) "privado" else "público"
                    if (!pushOk) {
                        return@withContext Result.failure(
                            IllegalStateException("Token não tem permissão de escrita (Contents: Read and Write) em $cleanRepo.")
                        )
                    }
                    Result.success("Conectado a $cleanRepo ($visibilityNote, branch $cleanBranch)!")
                } finally {
                    conn.disconnect()
                }
            } catch (e: Exception) {
                Log.w(TAG, "GitHub connection test error", e)
                Result.failure(e)
            }
        }

    fun resolveInBackground(url: String, onDone: (File?) -> Unit) {
        ioScope.launch {
            val result = resolveToFile(url).getOrNull()
            withContext(Dispatchers.Main) {
                onDone(result)
            }
        }
    }

    private fun readFullChunk(input: java.io.InputStream, buffer: ByteArray): Int {
        var offset = 0
        while (offset < buffer.size) {
            val read = input.read(buffer, offset, buffer.size - offset)
            if (read == -1) break
            offset += read
        }
        return offset
    }

    private fun sanitizeRepoFileName(raw: String): String {
        val cleaned = raw.replace(Regex("[^a-zA-Z0-9._\\-]"), "_").trim('_')
        return cleaned.ifBlank { "arquivo.bin" }.take(80)
    }

    private fun sha256Short(text: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
        return digest.take(12).joinToString("") { "%02x".format(it) }
    }

    private data class GitHubRepoConfig(
        val ownerRepo: String,
        val branch: String,
        val token: String
    )

    companion object {
        private const val TAG = "FreeMediaStorage"
        private const val FIRESTORE_CHUNK_BYTES = 450 * 1024
        private const val MAX_FIRESTORE_FILE_BYTES = 35L * 1024L * 1024L
        private const val MAX_FIRESTORE_PUBLIC_MEDIA_BYTES = 20L * 1024L * 1024L
        private const val GITHUB_CONTENTS_MAX_BYTES = 18L * 1024L * 1024L

        fun isChunkUri(url: String?): Boolean =
            url?.trim()?.startsWith("zap2chunk://", ignoreCase = true) == true

        fun parseExtFromChunkUri(url: String): String? {
            if (!isChunkUri(url)) return null
            return Uri.parse(url).getQueryParameter("ext")?.lowercase()?.takeIf { it.isNotBlank() }
        }

        suspend fun resolveIfChunkUri(context: Context, url: String?): Any? {
            val clean = url?.trim().orEmpty()
            if (clean.isBlank()) return null
            if (!isChunkUri(clean)) return clean
            val manager = (context.applicationContext as? Zap2Application)?.container?.freeStorageManager
                ?: return null
            return manager.resolveToFile(clean).getOrNull()
        }
    }
}
