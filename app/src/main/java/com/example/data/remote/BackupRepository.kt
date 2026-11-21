package com.example.data.remote

import android.content.Context
import android.net.Uri
import android.util.Log
import com.example.R
import com.example.data.local.SettingsDataStore
import com.example.data.local.Zap2Database
import com.example.data.model.BackupManifest
import com.example.data.model.BackupSummary
import com.example.data.model.ProfileBackupData
import com.google.firebase.Firebase
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.auth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import com.google.firebase.storage.FirebaseStorage
import com.google.firebase.storage.storage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import net.lingala.zip4j.ZipFile
import net.lingala.zip4j.model.ZipParameters
import net.lingala.zip4j.model.enums.AesKeyStrength
import net.lingala.zip4j.model.enums.EncryptionMethod
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class BackupRepository(
    private val context: Context,
    private val db: FirebaseFirestore,
    private val settingsStore: SettingsDataStore,
    private val auth: FirebaseAuth = Firebase.auth,
    private val storage: FirebaseStorage = Firebase.storage
) {
    constructor(context: Context, settingsStore: SettingsDataStore) : this(
        context.applicationContext,
        FirebaseFirestore.getInstance(
            context.applicationContext.getString(R.string.firestore_database_id)
        ),
        settingsStore
    )

    private val json = Json {
        ignoreUnknownKeys = true
        prettyPrint = true
    }

    private val backupsDir: File
        get() = File(context.filesDir, "backups").apply { mkdirs() }

    private val mediaDir: File
        get() = File(context.filesDir, "media").apply { mkdirs() }

    fun listLocalBackups(): List<BackupSummary> {
        return backupsDir.listFiles()
            ?.filter { it.isFile && it.extension.equals("zip", ignoreCase = true) }
            ?.sortedByDescending { it.lastModified() }
            ?.map { file ->
                BackupSummary(
                    fileName = file.name,
                    filePath = file.absolutePath,
                    sizeBytes = file.length(),
                    createdAtMs = file.lastModified(),
                    isCloud = false
                )
            } ?: emptyList()
    }

    suspend fun createEncryptedLocalBackup(password: String): Result<File> =
        withContext(Dispatchers.IO) {
            try {
                val uid = auth.currentUser?.uid
                    ?: return@withContext Result.failure(IllegalStateException("Usuário não autenticado."))
                require(password.length >= 4) { "A senha de criptografia precisa ter pelo menos 4 caracteres." }

                val roomDb = Zap2Database.getInstance(context)
                roomDb.flushWalCheckpoint()
                val dao = roomDb.zap2Dao()

                val chatCount = dao.getChatCount()
                val msgCount = dao.getMessageCount()
                val contactCount = dao.getContactCount()
                val mediaFiles = mediaDir.listFiles()?.filter { it.isFile } ?: emptyList()

                val stagingDir = File(context.cacheDir, "backup_staging_${System.currentTimeMillis()}").apply {
                    mkdirs()
                }
                try {
                    val manifest = BackupManifest(
                        version = 1,
                        userId = uid,
                        createdAtEpochMs = System.currentTimeMillis(),
                        chatCount = chatCount,
                        messageCount = msgCount,
                        contactCount = contactCount,
                        mediaFileCount = mediaFiles.size
                    )
                    val manifestFile = File(stagingDir, "backup_manifest.json")
                    manifestFile.writeText(json.encodeToString(manifest))

                    val cachedSettings = settingsStore.settingsFlow.first()
                    val remoteSnap = runCatching { db.collection("users").document(uid).get().await() }.getOrNull()
                    val profileBackup = ProfileBackupData(
                        uid = uid,
                        displayName = remoteSnap?.getString("displayName") ?: cachedSettings.cachedDisplayName,
                        about = remoteSnap?.getString("about") ?: cachedSettings.cachedAbout,
                        photoUrl = remoteSnap?.getString("photoUrl")
                            ?: cachedSettings.cachedPhotoUrl.takeIf { it.isNotBlank() },
                        bannerUrl = remoteSnap?.getString("bannerUrl")
                            ?: cachedSettings.cachedBannerUrl.takeIf { it.isNotBlank() }
                    )
                    val profileBackupFile = File(stagingDir, "profile_backup.json")
                    profileBackupFile.writeText(json.encodeToString(profileBackup))

                    val dbStaging = File(stagingDir, "database").apply { mkdirs() }
                    val mainDbFile = context.getDatabasePath(Zap2Database.DB_NAME)
                    if (mainDbFile.exists()) {
                        mainDbFile.copyTo(File(dbStaging, Zap2Database.DB_NAME), overwrite = true)
                    }
                    val walFile = File("${mainDbFile.absolutePath}-wal")
                    if (walFile.exists() && walFile.length() > 0L) {
                        walFile.copyTo(File(dbStaging, "${Zap2Database.DB_NAME}-wal"), overwrite = true)
                    }
                    val shmFile = File("${mainDbFile.absolutePath}-shm")
                    if (shmFile.exists() && shmFile.length() > 0L) {
                        shmFile.copyTo(File(dbStaging, "${Zap2Database.DB_NAME}-shm"), overwrite = true)
                    }

                    val mediaStaging = File(stagingDir, "media").apply { mkdirs() }
                    mediaFiles.forEach { media ->
                        media.copyTo(File(mediaStaging, media.name), overwrite = true)
                    }

                    val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
                    val outZipFile = File(backupsDir, "zap2_backup_$stamp.zip")
                    if (outZipFile.exists()) outZipFile.delete()

                    val zipFile = ZipFile(outZipFile, password.toCharArray())
                    val params = ZipParameters().apply {
                        isEncryptFiles = true
                        encryptionMethod = EncryptionMethod.AES
                        aesKeyStrength = AesKeyStrength.KEY_STRENGTH_256
                    }
                    zipFile.addFile(manifestFile, params)
                    zipFile.addFile(profileBackupFile, params)
                    if (dbStaging.listFiles()?.isNotEmpty() == true) {
                        zipFile.addFolder(dbStaging, params)
                    }
                    if (mediaStaging.listFiles()?.isNotEmpty() == true) {
                        zipFile.addFolder(mediaStaging, params)
                    }

                    settingsStore.recordLocalBackup(System.currentTimeMillis())
                    Result.success(outZipFile)
                } finally {
                    stagingDir.deleteRecursively()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Failed to create encrypted backup", e)
                Result.failure(e)
            }
        }

    suspend fun exportZipToUri(sourceZip: File, destinationUri: Uri): Result<Unit> =
        withContext(Dispatchers.IO) {
            try {
                context.contentResolver.openOutputStream(destinationUri)?.use { output ->
                    sourceZip.inputStream().use { input -> input.copyTo(output) }
                } ?: return@withContext Result.failure(IllegalStateException("Não foi possível gravar no destino selecionado."))
                Result.success(Unit)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

    suspend fun restoreBackupFromUri(uri: Uri, password: String): Result<BackupManifest> =
        withContext(Dispatchers.IO) {
            val tempZip = File(context.cacheDir, "restore_import_${System.currentTimeMillis()}.zip")
            try {
                context.contentResolver.openInputStream(uri)?.use { input ->
                    tempZip.outputStream().use { output -> input.copyTo(output) }
                } ?: return@withContext Result.failure(IllegalStateException("Não foi possível abrir o arquivo .zip selecionado."))

                restoreBackupFromFile(tempZip, password)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Result.failure(e)
            } finally {
                tempZip.delete()
            }
        }

    suspend fun restoreBackupFromFile(zipSourceFile: File, password: String): Result<BackupManifest> =
        withContext(Dispatchers.IO) {
            val extractDir = File(context.cacheDir, "restore_extract_${System.currentTimeMillis()}")
            try {
                require(zipSourceFile.exists() && zipSourceFile.length() > 0L) {
                    "Arquivo de backup inválido ou vazio."
                }
                require(password.isNotEmpty()) { "Digite a senha do backup criptografado." }

                extractDir.mkdirs()
                val zipFile = ZipFile(zipSourceFile, password.toCharArray())
                zipFile.extractAll(extractDir.absolutePath)

                val manifestFile = File(extractDir, "backup_manifest.json")
                if (!manifestFile.exists()) {
                    return@withContext Result.failure(
                        IllegalStateException("Arquivo não contém backup_manifest.json válido do Zap2.")
                    )
                }
                val manifest = json.decodeFromString<BackupManifest>(manifestFile.readText())

                val restoredDb = File(extractDir, "database/${Zap2Database.DB_NAME}")
                if (!restoredDb.exists()) {
                    return@withContext Result.failure(
                        IllegalStateException("Banco de dados SQLite não encontrado dentro do backup.")
                    )
                }

                // Close Room cleanly before overwriting SQLite files
                Zap2Database.closeForBackupRestore()

                val targetDb = context.getDatabasePath(Zap2Database.DB_NAME)
                targetDb.parentFile?.mkdirs()
                File("${targetDb.absolutePath}-wal").delete()
                File("${targetDb.absolutePath}-shm").delete()
                restoredDb.copyTo(targetDb, overwrite = true)

                val restoredWal = File(extractDir, "database/${Zap2Database.DB_NAME}-wal")
                if (restoredWal.exists()) {
                    restoredWal.copyTo(File("${targetDb.absolutePath}-wal"), overwrite = true)
                }
                val restoredShm = File(extractDir, "database/${Zap2Database.DB_NAME}-shm")
                if (restoredShm.exists()) {
                    restoredShm.copyTo(File("${targetDb.absolutePath}-shm"), overwrite = true)
                }

                val restoredMediaDir = File(extractDir, "media")
                if (restoredMediaDir.exists() && restoredMediaDir.isDirectory) {
                    restoredMediaDir.listFiles()?.filter { it.isFile }?.forEach { mediaFile ->
                        mediaFile.copyTo(File(mediaDir, mediaFile.name), overwrite = true)
                    }
                }

                // Re-open Room immediately to verify integrity
                Zap2Database.getInstance(context).zap2Dao().getChatCount()

                // Restore profile banner & avatar from local backup if available
                val profileBackupFile = File(extractDir, "profile_backup.json")
                if (profileBackupFile.exists()) {
                    val restoredProfile = runCatching {
                        json.decodeFromString<ProfileBackupData>(profileBackupFile.readText())
                    }.getOrNull()
                    val currentUid = auth.currentUser?.uid
                    if (restoredProfile != null) {
                        settingsStore.saveLocalProfileCache(
                            displayName = restoredProfile.displayName,
                            about = restoredProfile.about,
                            photoUrl = restoredProfile.photoUrl.orEmpty(),
                            bannerUrl = restoredProfile.bannerUrl.orEmpty()
                        )
                        if (currentUid != null) {
                            val profileUpdates = mutableMapOf<String, Any>(
                                "updatedAt" to FieldValue.serverTimestamp()
                            )
                            if (restoredProfile.displayName.isNotBlank()) {
                                profileUpdates["displayName"] = restoredProfile.displayName.take(80)
                            }
                            if (restoredProfile.about.isNotBlank()) {
                                profileUpdates["about"] = restoredProfile.about.take(160)
                            }
                            restoredProfile.photoUrl?.take(2048)?.let { profileUpdates["photoUrl"] = it }
                            restoredProfile.bannerUrl?.take(2048)?.let { profileUpdates["bannerUrl"] = it }

                            runCatching {
                                db.collection("users").document(currentUid).update(profileUpdates).await()
                                db.collection("public_profiles").document(currentUid)
                                    .set(profileUpdates + mapOf("uid" to currentUid), SetOptions.merge())
                                    .await()
                            }
                        }
                    }
                }

                Result.success(manifest)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Backup restore failed", e)
                val msg = if (e.message?.contains("password", ignoreCase = true) == true ||
                    e.message?.contains("Wrong", ignoreCase = true) == true
                ) {
                    "Senha incorreta ou arquivo .zip corrompido."
                } else {
                    e.localizedMessage ?: "Falha ao restaurar backup."
                }
                Result.failure(IllegalStateException(msg, e))
            } finally {
                extractDir.deleteRecursively()
            }
        }

    suspend fun uploadCloudBackup(password: String): Result<String> {
        val uid = auth.currentUser?.uid
            ?: return Result.failure(IllegalStateException("Usuário não autenticado."))
        val localResult = createEncryptedLocalBackup(password)
        val zipFile = localResult.getOrElse { return Result.failure(it) }

        return try {
            storage.maxUploadRetryTimeMillis = 120_000L
            val storageRef = storage.reference.child("backups/$uid/zap2_latest_backup.zip")
            storageRef.putFile(Uri.fromFile(zipFile)).await()
            val downloadUrl = storageRef.downloadUrl.await().toString()

            db.collection("users").document(uid).update(
                mapOf(
                    "lastBackupUrl" to downloadUrl.take(2048),
                    "lastBackupAt" to FieldValue.serverTimestamp(),
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            ).await()

            settingsStore.recordCloudBackup(System.currentTimeMillis())
            Result.success(downloadUrl)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, "users/$uid")
            val msg = if (e.message?.contains("not exist", ignoreCase = true) == true) {
                "Backup na nuvem requer Firebase Storage (plano Blaze). Use o Backup Local (.zip) acima gratuitamente!"
            } else {
                "Falha no backup em nuvem: ${e.localizedMessage ?: "verifique sua conexão."}"
            }
            Result.failure(IllegalStateException(msg))
        }
    }

    suspend fun downloadAndRestoreCloudBackup(password: String): Result<BackupManifest> {
        val uid = auth.currentUser?.uid
            ?: return Result.failure(IllegalStateException("Usuário não autenticado."))
        val tempCloudZip = File(context.cacheDir, "cloud_restore_${System.currentTimeMillis()}.zip")
        return try {
            val storageRef = storage.reference.child("backups/$uid/zap2_latest_backup.zip")
            storageRef.getFile(tempCloudZip).await()
            restoreBackupFromFile(tempCloudZip, password)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(
                IllegalStateException(
                    "Não foi possível baixar o backup na nuvem: ${e.localizedMessage ?: "nenhum backup encontrado."}"
                )
            )
        } finally {
            tempCloudZip.delete()
        }
    }

    private companion object {
        const val TAG = "BackupRepository"
    }
}
