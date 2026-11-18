package com.example.ui.navigation

import kotlinx.serialization.Serializable

sealed interface Zap2Route {
    @Serializable
    data object ChatsRoute : Zap2Route

    @Serializable
    data object ContactsRoute : Zap2Route

    @Serializable
    data object CallsHistoryRoute : Zap2Route

    @Serializable
    data object BackupSettingsRoute : Zap2Route

    @Serializable
    data class ChatDetailRoute(val chatId: String) : Zap2Route

    @Serializable
    data class GroupInfoRoute(val chatId: String) : Zap2Route

    @Serializable
    data class ActiveCallRoute(
        val callId: String,
        val isIncoming: Boolean,
        val isVideo: Boolean
    ) : Zap2Route
}
