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

data class DeviceContact(
    val lookupId: String,
    val name: String,
    val rawPhone: String,
    val normalizedDigits: String,
    val registeredUid: String? = null,
    val registeredDisplayName: String? = null,
    val registeredPhotoUrl: String? = null
)

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
