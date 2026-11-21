package com.example.data.model

import kotlinx.serialization.Serializable

sealed interface UiState<out T> {
    data object Loading : UiState<Nothing>
    data class Success<T>(val data: T) : UiState<T>
    data class Error(val message: String) : UiState<Nothing>
}

enum class ThemeMode(val label: String) {
    SYSTEM("Sistema"),
    LIGHT("Claro"),
    DARK("Escuro")
}

@Serializable
data class BackupManifest(
    val version: Int = 1,
    val userId: String,
    val createdAtEpochMs: Long,
    val chatCount: Int,
    val messageCount: Int,
    val contactCount: Int,
    val mediaFileCount: Int
)

data class BackupSummary(
    val fileName: String,
    val filePath: String,
    val sizeBytes: Long,
    val createdAtMs: Long,
    val isCloud: Boolean = false
)

@Serializable
data class ProfileBackupData(
    val uid: String = "",
    val username: String = "",
    val displayName: String = "",
    val about: String = "",
    val photoUrl: String? = null,
    val bannerUrl: String? = null
)

data class UploadProgressState(
    val fileName: String,
    val bytesTransferred: Long,
    val totalBytes: Long,
    val progress: Float
)

data class ZipExtractionResult(
    val extractedFilesCount: Int,
    val destinationLabel: String,
    val destinationPath: String? = null
)
