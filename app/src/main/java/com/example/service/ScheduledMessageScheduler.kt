package com.example.service

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import com.example.Zap2Application
import com.example.data.local.ScheduledMessageEntity
import com.example.data.local.Zap2Database
import com.example.data.model.ChatRoom
import com.google.firebase.Firebase
import com.google.firebase.auth.auth
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID

object ScheduledMessageTimeUtils {
    private val ptBrLocale = Locale("pt", "BR")

    fun combineDateAndTimeMillis(
        utcDatePickerMillis: Long,
        hour: Int,
        minute: Int,
        targetTimeZone: TimeZone = TimeZone.getDefault()
    ): Long {
        val utcCal = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
            timeInMillis = utcDatePickerMillis
        }
        val targetCal = Calendar.getInstance(targetTimeZone).apply {
            set(Calendar.YEAR, utcCal.get(Calendar.YEAR))
            set(Calendar.MONTH, utcCal.get(Calendar.MONTH))
            set(Calendar.DAY_OF_MONTH, utcCal.get(Calendar.DAY_OF_MONTH))
            set(Calendar.HOUR_OF_DAY, hour.coerceIn(0, 23))
            set(Calendar.MINUTE, minute.coerceIn(0, 59))
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        return targetCal.timeInMillis
    }

    fun formatScheduledDateTime(epochMs: Long, timeZone: TimeZone = TimeZone.getDefault()): String {
        val fmt = SimpleDateFormat("dd/MM/yyyy 'às' HH:mm", ptBrLocale).apply {
            this.timeZone = timeZone
        }
        return fmt.format(Date(epochMs))
    }

    fun isValidFutureSchedule(scheduledAtMs: Long, nowMs: Long = System.currentTimeMillis()): Boolean {
        return scheduledAtMs >= nowMs + 10_000L
    }
}

object ScheduledMessageScheduler {
    private const val TAG = "ScheduledMsgScheduler"
    const val ACTION_DISPATCH_SCHEDULED = "com.example.zap2.ACTION_DISPATCH_SCHEDULED"
    const val EXTRA_SCHEDULE_ID = "extra_schedule_id"

    private val dispatchMutex = Mutex()

    suspend fun scheduleMessage(
        context: Context,
        chat: ChatRoom,
        senderUid: String,
        senderName: String,
        messageType: String,
        text: String,
        localMediaFile: File? = null,
        localAudioFile: File? = null,
        mediaDurationMs: Long = 0L,
        mimeType: String? = null,
        viewOnce: Boolean = false,
        scheduledAtMs: Long
    ): Result<ScheduledMessageEntity> = withContext(Dispatchers.IO) {
        if (!ScheduledMessageTimeUtils.isValidFutureSchedule(scheduledAtMs)) {
            return@withContext Result.failure(
                IllegalArgumentException("Escolha uma data e hora no futuro (pelo menos 10 segundos à frente).")
            )
        }
        val appContext = context.applicationContext
        val id = UUID.randomUUID().toString().replace("-", "").take(18)
        val chatRepo = (appContext as Zap2Application).container.chatRepository
        val persistedMedia = localMediaFile?.takeIf { it.exists() }?.let {
            val fallbackExt = when (messageType) {
                "STICKER" -> "png"
                "GIF", "GIF_AUDIO" -> "gif"
                "VIDEO" -> "mp4"
                "AUDIO" -> "m4a"
                else -> "jpg"
            }
            chatRepo.copyFileToScheduledMedia(it, fallbackExt)
        }
        val persistedAudio = localAudioFile?.takeIf { it.exists() }?.let {
            chatRepo.copyFileToScheduledMedia(it, "m4a")
        }

        val entity = ScheduledMessageEntity(
            id = id,
            chatId = chat.id,
            senderUid = senderUid,
            senderName = senderName.ifBlank { "Você" },
            chatTitle = chat.resolveDisplayTitle(senderUid),
            messageType = messageType,
            text = text.trim().take(4000),
            localMediaPath = persistedMedia?.absolutePath,
            localAudioPath = persistedAudio?.absolutePath,
            mediaDurationMs = mediaDurationMs.coerceAtLeast(0L),
            mimeType = mimeType,
            viewOnce = viewOnce,
            scheduledAtMs = scheduledAtMs,
            status = "PENDING",
            errorMessage = null,
            createdAtMs = System.currentTimeMillis()
        )
        Zap2Database.getInstance(appContext).zap2Dao().upsertScheduledMessage(entity)
        scheduleAlarm(appContext, entity)
        Result.success(entity)
    }

    suspend fun cancelScheduledMessage(context: Context, id: String) = withContext(Dispatchers.IO) {
        val appContext = context.applicationContext
        val dao = Zap2Database.getInstance(appContext).zap2Dao()
        val item = dao.getScheduledMessageById(id)
        cancelAlarm(appContext, id)
        if (item != null) {
            item.localMediaPath?.let { path -> runCatching { File(path).delete() } }
            item.localAudioPath?.let { path -> runCatching { File(path).delete() } }
        }
        dao.deleteScheduledMessageById(id)
    }

    suspend fun sendScheduledNow(context: Context, id: String): Result<Unit> = withContext(Dispatchers.IO) {
        val appContext = context.applicationContext
        val dao = Zap2Database.getInstance(appContext).zap2Dao()
        val item = dao.getScheduledMessageById(id)
            ?: return@withContext Result.failure(IllegalStateException("Agendamento não encontrado."))
        dao.upsertScheduledMessage(
            item.copy(
                status = "PENDING",
                errorMessage = null,
                scheduledAtMs = System.currentTimeMillis() - 1_000L
            )
        )
        dispatchDueMessages(appContext)
        Result.success(Unit)
    }

    suspend fun rescheduleAllPending(context: Context) = withContext(Dispatchers.IO) {
        val appContext = context.applicationContext
        val pending = Zap2Database.getInstance(appContext).zap2Dao().getAllPendingScheduledOnce()
        val now = System.currentTimeMillis()
        pending.forEach { entity ->
            if (entity.scheduledAtMs <= now) {
                scheduleAlarm(appContext, entity.copy(scheduledAtMs = now + 2_000L))
            } else {
                scheduleAlarm(appContext, entity)
            }
        }
    }

    suspend fun dispatchDueMessages(context: Context) = dispatchMutex.withLock {
        withContext(Dispatchers.IO) {
            val user = Firebase.auth.currentUser ?: return@withContext
            val appContext = context.applicationContext
            val app = appContext as? Zap2Application ?: return@withContext
            val dao = Zap2Database.getInstance(appContext).zap2Dao()
            val chatRepo = app.container.chatRepository
            val due = dao.getDueScheduledMessages(System.currentTimeMillis())
                .filter { it.senderUid == user.uid }
            if (due.isEmpty()) return@withContext

            for (item in due) {
                dao.updateScheduledStatus(item.id, "SENDING", null)
                val chat = chatRepo.getChatRoomOnce(item.chatId)
                if (chat == null) {
                    dao.updateScheduledStatus(item.id, "FAILED", "Conversa não encontrada ou sem permissão.")
                    continue
                }

                val sendResult: Result<Unit> = when (item.messageType) {
                    "TEXT" -> chatRepo.sendTextMessage(
                        chat = chat,
                        senderName = item.senderName,
                        text = item.text,
                        viewOnce = item.viewOnce
                    )
                    "GIF" -> {
                        val file = item.localMediaPath?.let(::File)?.takeIf { it.exists() }
                        if (file == null) {
                            Result.failure(IllegalStateException("Arquivo GIF agendado não encontrado."))
                        } else {
                            chatRepo.sendGifFileMessage(
                                chat = chat,
                                senderName = item.senderName,
                                gifFile = file,
                                caption = item.text,
                                viewOnce = item.viewOnce
                            )
                        }
                    }
                    "STICKER" -> {
                        val file = item.localMediaPath?.let(::File)?.takeIf { it.exists() }
                        if (file == null) {
                            Result.failure(IllegalStateException("Fig agendada não encontrada."))
                        } else {
                            chatRepo.sendStickerMessage(
                                chat = chat,
                                senderName = item.senderName,
                                stickerFile = file,
                                viewOnce = item.viewOnce
                            )
                        }
                    }
                    "GIF_AUDIO" -> {
                        val gifFile = item.localMediaPath?.let(::File)?.takeIf { it.exists() }
                        val audioFile = item.localAudioPath?.let(::File)?.takeIf { it.exists() }
                        if (gifFile == null || audioFile == null) {
                            Result.failure(IllegalStateException("Mídia do GIF+Áudio agendado indisponível."))
                        } else {
                            chatRepo.sendGifWithAudioMessage(
                                chat = chat,
                                senderName = item.senderName,
                                gifFile = gifFile,
                                audioFile = audioFile,
                                audioDurationMs = item.mediaDurationMs,
                                caption = item.text,
                                viewOnce = item.viewOnce
                            )
                        }
                    }
                    "VIDEO" -> {
                        val file = item.localMediaPath?.let(::File)?.takeIf { it.exists() }
                        if (file == null) {
                            Result.failure(IllegalStateException("Vídeo agendado não encontrado."))
                        } else {
                            chatRepo.sendVideoFileMessage(
                                chat = chat,
                                senderName = item.senderName,
                                videoFile = file,
                                mimeType = item.mimeType ?: "video/mp4",
                                caption = item.text,
                                viewOnce = item.viewOnce
                            )
                        }
                    }
                    "AUDIO" -> {
                        val file = item.localMediaPath?.let(::File)?.takeIf { it.exists() }
                        if (file == null) {
                            Result.failure(IllegalStateException("Áudio agendado não encontrado."))
                        } else {
                            chatRepo.sendAudioMessage(
                                chat = chat,
                                senderName = item.senderName,
                                audioFile = file,
                                durationMs = item.mediaDurationMs,
                                viewOnce = item.viewOnce
                            )
                        }
                    }
                    else -> chatRepo.sendTextMessage(chat, item.senderName, item.text, viewOnce = item.viewOnce)
                }

                sendResult.onSuccess {
                    cancelAlarm(appContext, item.id)
                    item.localMediaPath?.let { path -> runCatching { File(path).delete() } }
                    item.localAudioPath?.let { path -> runCatching { File(path).delete() } }
                    dao.deleteScheduledMessageById(item.id)
                    Zap2NotificationHelper.showMessageNotification(
                        context = appContext,
                        chatId = item.chatId,
                        title = "Mensagem agendada enviada",
                        body = "Enviada para ${item.chatTitle}: ${item.text.ifBlank { item.messageType }}"
                    )
                }.onFailure { err ->
                    Log.w(TAG, "Failed scheduled message ${item.id}: ${err.message}")
                    dao.updateScheduledStatus(
                        id = item.id,
                        status = "FAILED",
                        error = err.message ?: "Erro ao enviar mensagem agendada."
                    )
                }
            }
        }
    }

    private fun scheduleAlarm(context: Context, entity: ScheduledMessageEntity) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        val pi = buildPendingIntent(context, entity.id)
        val triggerAt = entity.scheduledAtMs.coerceAtLeast(System.currentTimeMillis() + 1_000L)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi)
        } else {
            alarmManager.set(AlarmManager.RTC_WAKEUP, triggerAt, pi)
        }
    }

    private fun cancelAlarm(context: Context, id: String) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        val pi = buildPendingIntent(context, id)
        alarmManager.cancel(pi)
        pi.cancel()
    }

    private fun buildPendingIntent(context: Context, scheduleId: String): PendingIntent {
        val intent = Intent(context, ScheduledMessageReceiver::class.java).apply {
            action = ACTION_DISPATCH_SCHEDULED
            putExtra(EXTRA_SCHEDULE_ID, scheduleId)
        }
        return PendingIntent.getBroadcast(
            context,
            scheduleId.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }
}
