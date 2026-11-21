package com.example.service

import com.example.Zap2Application
import com.google.firebase.Firebase
import com.google.firebase.auth.auth
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class Zap2MessagingService : FirebaseMessagingService() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onNewToken(token: String) {
        super.onNewToken(token)
        if (Firebase.auth.currentUser != null) {
            val app = application as? Zap2Application ?: return
            serviceScope.launch {
                app.container.userRepository.saveFcmToken(token)
            }
        }
    }

    override fun onMessageReceived(message: RemoteMessage) {
        super.onMessageReceived(message)
        val title = message.notification?.title ?: message.data["title"] ?: "Nova mensagem no WhatwillIdo"
        val body = message.notification?.body ?: message.data["body"] ?: "Você recebeu uma nova mensagem."
        val isCall = message.data["type"] == "call"
        val chatId = message.data["chatId"]

        if (isCall) {
            val callId = message.data["callId"]?.takeIf { it.isNotBlank() } ?: return
            val callerName = message.data["callerName"]?.takeIf { it.isNotBlank() } ?: title
            val isVideo = message.data["callType"] == "VIDEO" || message.data["isVideo"] == "true"
            Zap2NotificationHelper.showIncomingCallNotification(
                context = this,
                callId = callId,
                callerName = callerName,
                isVideo = isVideo,
                chatId = chatId
            )
        } else {
            Zap2NotificationHelper.showMessageNotification(
                context = this,
                chatId = chatId,
                title = title,
                body = body
            )
        }
    }
}
