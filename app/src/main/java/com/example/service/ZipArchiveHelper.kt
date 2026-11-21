package com.example.service

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import android.webkit.MimeTypeMap
import com.example.data.model.ZipExtractionResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream

object ZipArchiveHelper {

    fun getAppDefaultRootDir(context: Context): File {
        val base = context.getExternalFilesDir(null) ?: context.filesDir
        return File(base, "WhatwillIdo_Extraidos").apply { mkdirs() }
    }

    fun formatDefaultExtractSummary(context: Context, customTreeUri: String): String {
        if (customTreeUri.isNotBlank()) {
            val parsed = runCatching { Uri.parse(customTreeUri) }.getOrNull()
            val readable = parsed?.lastPathSegment?.substringAfterLast(':')?.ifBlank { null }
            return readable?.let { "Pasta selecionada ($it)" } ?: "Pasta personalizada configurada"
        }
        return getAppDefaultRootDir(context).absolutePath
    }

    suspend fun extractZipToDefault(
        context: Context,
        zipFile: File,
        archiveDisplayName: String,
        configuredTreeUri: String
    ): Result<ZipExtractionResult> = withContext(Dispatchers.IO) {
        if (configuredTreeUri.isNotBlank()) {
            val uri = runCatching { Uri.parse(configuredTreeUri) }.getOrNull()
            if (uri != null) {
                val customResult = extractZipToTreeUri(context, zipFile, archiveDisplayName, uri)
                if (customResult.isSuccess) {
                    return@withContext customResult
                }
            }
        }
        extractZipToLocalDefaultDir(context, zipFile, archiveDisplayName)
    }

    suspend fun extractZipToLocalDefaultDir(
        context: Context,
        zipFile: File,
        archiveDisplayName: String
    ): Result<ZipExtractionResult> = withContext(Dispatchers.IO) {
        runCatching {
            require(zipFile.exists() && zipFile.length() > 0L) {
                "Arquivo ZIP não encontrado ou vazio."
            }
            val folderName = sanitizeSegment(archiveDisplayName.substringBeforeLast('.', archiveDisplayName))
                .ifBlank { "arquivo_extraido" }
            val targetRoot = uniqueLocalFolder(getAppDefaultRootDir(context), folderName)
            targetRoot.mkdirs()
            val canonicalRoot = targetRoot.canonicalPath + File.separator

            var count = 0
            ZipInputStream(BufferedInputStream(FileInputStream(zipFile))).use { zis ->
                var entry: ZipEntry? = zis.nextEntry
                while (entry != null) {
                    val safeSegments = parseSafeRelativeSegments(entry.name)
                    if (safeSegments.isNotEmpty()) {
                        val outFile = safeSegments.fold(targetRoot) { acc, seg -> File(acc, seg) }
                        val canonicalOut = outFile.canonicalPath
                        if (!canonicalOut.startsWith(canonicalRoot) && canonicalOut != targetRoot.canonicalPath) {
                            throw SecurityException("Entrada ZIP inválida detectada (Zip Slip): ${entry.name}")
                        }
                        if (entry.isDirectory) {
                            outFile.mkdirs()
                        } else {
                            outFile.parentFile?.mkdirs()
                            outFile.outputStream().use { out ->
                                val buf = ByteArray(8192)
                                var read: Int
                                while (zis.read(buf).also { read = it } != -1) {
                                    out.write(buf, 0, read)
                                }
                            }
                            count++
                        }
                    }
                    zis.closeEntry()
                    entry = zis.nextEntry
                }
            }

            ZipExtractionResult(
                extractedFilesCount = count,
                destinationLabel = targetRoot.name,
                destinationPath = targetRoot.absolutePath
            )
        }
    }

    suspend fun extractZipToTreeUri(
        context: Context,
        zipFile: File,
        archiveDisplayName: String,
        treeUri: Uri
    ): Result<ZipExtractionResult> = withContext(Dispatchers.IO) {
        runCatching {
            require(zipFile.exists() && zipFile.length() > 0L) {
                "Arquivo ZIP não encontrado ou vazio."
            }
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    treeUri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
            }

            val resolver = context.contentResolver
            val rootDocId = DocumentsContract.getTreeDocumentId(treeUri)
            val treeRootDocUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, rootDocId)

            val archiveFolderName = sanitizeSegment(archiveDisplayName.substringBeforeLast('.', archiveDisplayName))
                .ifBlank { "zip_extraido" }
            val archiveFolderUri = findOrCreateDirectory(resolver, treeUri, treeRootDocUri, archiveFolderName)
                ?: throw IllegalStateException("Não foi possível criar pasta de destino no caminho selecionado.")

            val dirCache = HashMap<String, Uri>()
            dirCache[""] = archiveFolderUri

            var count = 0
            ZipInputStream(BufferedInputStream(FileInputStream(zipFile))).use { zis ->
                var entry: ZipEntry? = zis.nextEntry
                while (entry != null) {
                    val safeSegments = parseSafeRelativeSegments(entry.name)
                    if (safeSegments.isNotEmpty()) {
                        if (entry.isDirectory) {
                            ensureSafDirectoryPath(resolver, treeUri, dirCache, safeSegments)
                        } else {
                            val parentSegments = safeSegments.dropLast(1)
                            val fileName = safeSegments.last()
                            val parentUri = ensureSafDirectoryPath(resolver, treeUri, dirCache, parentSegments)
                            val ext = fileName.substringAfterLast('.', "").lowercase()
                            val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext)
                                ?: "application/octet-stream"
                            val createdFileUri = DocumentsContract.createDocument(
                                resolver,
                                parentUri,
                                mime,
                                fileName
                            ) ?: throw IllegalStateException("Falha ao criar arquivo $fileName na pasta escolhida.")

                            resolver.openOutputStream(createdFileUri)?.use { out ->
                                val buf = ByteArray(8192)
                                var read: Int
                                while (zis.read(buf).also { read = it } != -1) {
                                    out.write(buf, 0, read)
                                }
                            } ?: throw IllegalStateException("Não foi possível gravar $fileName na pasta escolhida.")
                            count++
                        }
                    }
                    zis.closeEntry()
                    entry = zis.nextEntry
                }
            }

            val readableDest = treeUri.lastPathSegment?.substringAfterLast(':')?.ifBlank { null }
                ?: "Pasta selecionada"

            ZipExtractionResult(
                extractedFilesCount = count,
                destinationLabel = "$readableDest/$archiveFolderName",
                destinationPath = treeUri.toString()
            )
        }
    }

    private fun parseSafeRelativeSegments(rawEntryName: String): List<String> {
        val normalized = rawEntryName.replace('\\', '/')
        if (normalized.startsWith("/") || normalized.contains(':')) {
            throw SecurityException("Entrada ZIP com caminho absoluto rejeitada: $rawEntryName")
        }
        val segments = normalized.split('/').filter { it.isNotEmpty() && it != "." }
        if (segments.any { it == ".." }) {
            throw SecurityException("Entrada ZIP com traversal '..' rejeitada: $rawEntryName")
        }
        return segments
    }

    private fun sanitizeSegment(name: String): String {
        return name.replace(Regex("[^a-zA-Z0-9._\\- ()]"), "_").trim().take(80)
    }

    private fun uniqueLocalFolder(parent: File, baseName: String): File {
        var candidate = File(parent, baseName)
        var idx = 1
        while (candidate.exists()) {
            candidate = File(parent, "${baseName}_$idx")
            idx++
        }
        return candidate
    }

    private fun ensureSafDirectoryPath(
        resolver: ContentResolver,
        treeUri: Uri,
        cache: MutableMap<String, Uri>,
        segments: List<String>
    ): Uri {
        var currentKey = ""
        var currentUri = cache.getValue("")
        for (segment in segments) {
            val nextKey = if (currentKey.isEmpty()) segment else "$currentKey/$segment"
            val cached = cache[nextKey]
            if (cached != null) {
                currentUri = cached
            } else {
                val created = findOrCreateDirectory(resolver, treeUri, currentUri, segment)
                    ?: throw IllegalStateException("Falha ao criar subpasta $segment.")
                cache[nextKey] = created
                currentUri = created
            }
            currentKey = nextKey
        }
        return currentUri
    }

    private fun findOrCreateDirectory(
        resolver: ContentResolver,
        treeUri: Uri,
        parentDocUri: Uri,
        dirName: String
    ): Uri? {
        val parentDocId = DocumentsContract.getDocumentId(parentDocUri)
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentDocId)
        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE
        )
        runCatching {
            resolver.query(childrenUri, projection, null, null, null)?.use { cursor ->
                val idIdx = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
                val nameIdx = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                val mimeIdx = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_MIME_TYPE)
                while (cursor.moveToNext()) {
                    val name = if (nameIdx >= 0) cursor.getString(nameIdx) else null
                    val mime = if (mimeIdx >= 0) cursor.getString(mimeIdx) else null
                    if (name == dirName && mime == DocumentsContract.Document.MIME_TYPE_DIR && idIdx >= 0) {
                        val docId = cursor.getString(idIdx)
                        return DocumentsContract.buildDocumentUriUsingTree(treeUri, docId)
                    }
                }
            }
        }
        return DocumentsContract.createDocument(
            resolver,
            parentDocUri,
            DocumentsContract.Document.MIME_TYPE_DIR,
            dirName
        )
    }
}
