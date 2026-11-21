package com.example.service

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.Person
import androidx.core.content.ContextCompat
import com.example.MainActivity
import com.example.R
import com.example.Zap2Application
import com.example.data.model.ChatRoom

sealed interface Zap2NotificationNavigation {
    data class OpenChat(val chatId: String) : Zap2NotificationNavigation
    data class OpenCall(
        val callId: String,
        val isIncoming: Boolean,
        val isVideo: Boolean
    ) : Zap2NotificationNavigation
}

object Zap2NotificationHelper {
    private const val TAG = "Zap2Notifications"

    const val EXTRA_NAV_ACTION = "zap2_nav_action"
    const val EXTRA_CHAT_ID = "chatId"
    const val EXTRA_CALL_ID = "callId"
    const val EXTRA_CALL_IS_VIDEO = "callIsVideo"

    const val ACTION_OPEN_CHAT = "com.example.zap2.OPEN_CHAT"
    const val ACTION_OPEN_INCOMING_CALL = "com.example.zap2.OPEN_INCOMING_CALL"
    const val ACTION_ANSWER_CALL = "com.example.zap2.ANSWER_CALL"
    const val ACTION_DECLINE_CALL = "com.example.zap2.DECLINE_CALL"
    const val ACTION_HANGUP_CALL = "com.example.zap2.HANGUP_CALL"

    private const val MESSAGE_NOTIFICATION_OFFSET = 10_000
    private const val CALL_NOTIFICATION_OFFSET = 50_000
    private const val TEST_MESSAGE_NOTIFICATION_ID = 9_901

    fun hasNotificationPermission(context: Context): Boolean {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
    }

    fun parseNavigationFromIntent(intent: Intent?): Zap2NotificationNavigation? {
        if (intent == null) return null
        val action = intent.getStringExtra(EXTRA_NAV_ACTION) ?: intent.action
        val chatId = intent.getStringExtra(EXTRA_CHAT_ID)?.takeIf { it.isNotBlank() }
        val callId = intent.getStringExtra(EXTRA_CALL_ID)?.takeIf { it.isNotBlank() }
        val isVideo = intent.getBooleanExtra(EXTRA_CALL_IS_VIDEO, false)

        return when {
            callId != null && (action == ACTION_ANSWER_CALL || action == ACTION_OPEN_INCOMING_CALL) -> {
                Zap2NotificationNavigation.OpenCall(
                    callId = callId,
                    isIncoming = true,
                    isVideo = isVideo
                )
            }
            chatId != null -> Zap2NotificationNavigation.OpenChat(chatId)
            else -> null
        }
    }

    fun clearNavigationExtras(intent: Intent?) {
        intent?.removeExtra(EXTRA_NAV_ACTION)
        intent?.removeExtra(EXTRA_CHAT_ID)
        intent?.removeExtra(EXTRA_CALL_ID)
        intent?.removeExtra(EXTRA_CALL_IS_VIDEO)
    }

    fun messagePreviewForChat(chat: ChatRoom): String {
        val raw = chat.lastMessageText.trim()
        return when (chat.lastMessageType) {
            "IMAGE" -> raw.ifBlank { "Enviou uma foto" }
            "VIDEO" -> raw.ifBlank { "Enviou um vídeo" }
            "AUDIO" -> raw.ifBlank { "Enviou um áudio" }
            "PDF" -> raw.ifBlank { "Enviou um arquivo PDF" }
            "APK" -> raw.ifBlank { "Enviou um aplicativo APK" }
            "ZIP" -> raw.ifBlank { "Enviou um arquivo ZIP" }
            "FILE" -> raw.ifBlank { "Enviou um arquivo" }
            else -> raw.ifBlank { "Nova mensagem no Zap2" }
        }
    }

    @SuppressLint("MissingPermission")
    fun showMessageNotification(
        context: Context,
        chatId: String?,
        title: String,
        body: String,
        isTest: Boolean = false
    ) {
        if (!hasNotificationPermission(context)) return

        val openIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            if (!chatId.isNullOrBlank()) {
                putExtra(EXTRA_NAV_ACTION, ACTION_OPEN_CHAT)
                putExtra(EXTRA_CHAT_ID, chatId)
            }
        }
        val notifId = if (isTest || chatId.isNullOrBlank()) {
            TEST_MESSAGE_NOTIFICATION_ID
        } else {
            MESSAGE_NOTIFICATION_OFFSET + (chatId.hashCode() and 0x7FFF)
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            notifId,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val cleanTitle = title.ifBlank { "Nova mensagem no Zap2" }
        val cleanBody = body.ifBlank { "Você recebeu uma nova mensagem." }

        val notification = NotificationCompat.Builder(context, Zap2Application.CHANNEL_MESSAGES)
            .setSmallIcon(R.drawable.ic_notification_zap2)
            .setContentTitle(cleanTitle)
            .setContentText(cleanBody)
            .setStyle(
                NotificationCompat.BigTextStyle()
                    .setBigContentTitle(cleanTitle)
                    .bigText(cleanBody)
            )
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setDefaults(NotificationCompat.DEFAULT_ALL)
            .setAutoCancel(true)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setContentIntent(pendingIntent)
            .build()

        runCatching {
            NotificationManagerCompat.from(context).notify(notifId, notification)
        }.onFailure { err ->
            Log.w(TAG, "Falha ao exibir notificação de mensagem: ${err.message}")
        }
    }

    @SuppressLint("MissingPermission")
    fun showIncomingCallNotification(
        context: Context,
        callId: String,
        callerName: String,
        isVideo: Boolean,
        chatId: String? = null
    ) {
        if (!hasNotificationPermission(context)) return
        val notifId = callNotificationId(callId)
        val cleanCaller = callerName.trim().ifBlank { "Contato Zap2" }
        val callerPerson = Person.Builder()
            .setName(cleanCaller)
            .setImportant(true)
            .build()

        val fullScreenIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra(EXTRA_NAV_ACTION, ACTION_OPEN_INCOMING_CALL)
            putExtra(EXTRA_CALL_ID, callId)
            putExtra(EXTRA_CALL_IS_VIDEO, isVideo)
            if (!chatId.isNullOrBlank()) putExtra(EXTRA_CHAT_ID, chatId)
        }
        val fullScreenPendingIntent = PendingIntent.getActivity(
            context,
            notifId + 1,
            fullScreenIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val answerIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra(EXTRA_NAV_ACTION, ACTION_ANSWER_CALL)
            putExtra(EXTRA_CALL_ID, callId)
            putExtra(EXTRA_CALL_IS_VIDEO, isVideo)
            if (!chatId.isNullOrBlank()) putExtra(EXTRA_CHAT_ID, chatId)
        }
        val answerPendingIntent = PendingIntent.getActivity(
            context,
            notifId + 2,
            answerIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val declineIntent = Intent(context, Zap2CallActionReceiver::class.java).apply {
            action = ACTION_DECLINE_CALL
            putExtra(EXTRA_CALL_ID, callId)
        }
        val declinePendingIntent = PendingIntent.getBroadcast(
            context,
            notifId + 3,
            declineIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val callSubtitle = if (isVideo) {
            "Chamada de vídeo recebida no Zap2"
        } else {
            "Chamada de voz recebida no Zap2"
        }

        val callStyle = NotificationCompat.CallStyle.forIncomingCall(
            callerPerson,
            declinePendingIntent,
            answerPendingIntent
        ).setIsVideo(isVideo)

        val notification = NotificationCompat.Builder(context, Zap2Application.CHANNEL_CALLS)
            .setSmallIcon(R.drawable.ic_notification_zap2)
            .setContentTitle(cleanCaller)
            .setContentText(callSubtitle)
            .setStyle(callStyle)
            .addPerson(callerPerson)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOngoing(true)
            .setAutoCancel(false)
            .setColorized(true)
            .setContentIntent(fullScreenPendingIntent)
            .setFullScreenIntent(fullScreenPendingIntent, true)
            .build()

        notification.flags = notification.flags or
            Notification.FLAG_ONGOING_EVENT or
            Notification.FLAG_NO_CLEAR or
            Notification.FLAG_INSISTENT

        runCatching {
            NotificationManagerCompat.from(context).notify(notifId, notification)
        }.onFailure { err ->
            Log.w(TAG, "Falha ao exibir notificação de chamada recebida: ${err.message}")
        }
    }

    @SuppressLint("MissingPermission")
    fun showOngoingCallNotification(
        context: Context,
        callId: String,
        peerName: String,
        isVideo: Boolean
    ) {
        if (!hasNotificationPermission(context)) return
        val notifId = callNotificationId(callId)
        val cleanPeer = peerName.trim().ifBlank { "Chamada Zap2" }
        val peerPerson = Person.Builder()
            .setName(cleanPeer)
            .setImportant(true)
            .build()

        val openCallIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra(EXTRA_NAV_ACTION, ACTION_OPEN_INCOMING_CALL)
            putExtra(EXTRA_CALL_ID, callId)
            putExtra(EXTRA_CALL_IS_VIDEO, isVideo)
        }
        val contentPendingIntent = PendingIntent.getActivity(
            context,
            notifId + 4,
            openCallIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val hangUpIntent = Intent(context, Zap2CallActionReceiver::class.java).apply {
            action = ACTION_HANGUP_CALL
            putExtra(EXTRA_CALL_ID, callId)
        }
        val hangUpPendingIntent = PendingIntent.getBroadcast(
            context,
            notifId + 5,
            hangUpIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val callStyle = NotificationCompat.CallStyle.forOngoingCall(
            peerPerson,
            hangUpPendingIntent
        ).setIsVideo(isVideo)

        val notification = NotificationCompat.Builder(context, Zap2Application.CHANNEL_CALLS)
            .setSmallIcon(R.drawable.ic_notification_zap2)
            .setContentTitle(cleanPeer)
            .setContentText(if (isVideo) "Chamada de vídeo em andamento" else "Chamada de voz em andamento")
            .setStyle(callStyle)
            .addPerson(peerPerson)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOngoing(true)
            .setAutoCancel(false)
            .setUsesChronometer(true)
            .setSilent(true)
            .setColorized(true)
            .setContentIntent(contentPendingIntent)
            .build()

        notification.flags = notification.flags or
            Notification.FLAG_ONGOING_EVENT or
            Notification.FLAG_NO_CLEAR

        runCatching {
            NotificationManagerCompat.from(context).notify(notifId, notification)
        }.onFailure { err ->
            Log.w(TAG, "Falha ao exibir notificação de chamada ativa: ${err.message}")
        }
    }

    fun cancelChatNotification(context: Context, chatId: String) {
        if (chatId.isBlank()) return
        val notifId = MESSAGE_NOTIFICATION_OFFSET + (chatId.hashCode() and 0x7FFF)
        runCatching {
            NotificationManagerCompat.from(context).cancel(notifId)
        }
    }

    fun cancelCallNotification(context: Context, callId: String) {
        if (callId.isBlank()) return
        runCatching {
            NotificationManagerCompat.from(context).cancel(callNotificationId(callId))
        }
    }

    private fun callNotificationId(callId: String): Int {
        return CALL_NOTIFICATION_OFFSET + (callId.hashCode() and 0x7FFF)
    }
}
