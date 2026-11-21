package com.example.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.example.Zap2Application
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class Zap2CallActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        val callId = intent.getStringExtra(Zap2NotificationHelper.EXTRA_CALL_ID)
            ?.takeIf { it.isNotBlank() } ?: return

        Zap2NotificationHelper.cancelCallNotification(context, callId)

        val app = context.applicationContext as? Zap2Application ?: return
        val pendingResult = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                when (action) {
                    Zap2NotificationHelper.ACTION_DECLINE_CALL -> {
                        app.container.callSignalingRepository.updateCallStatus(callId, "DECLINED")
                    }
                    Zap2NotificationHelper.ACTION_HANGUP_CALL -> {
                        app.container.webRtcCallManager.hangUp(callId)
                        app.container.callSignalingRepository.updateCallStatus(callId, "ENDED")
                    }
                }
            } catch (e: Exception) {
                Log.w("Zap2CallAction", "Erro ao processar ação da notificação ($action): ${e.message}")
            } finally {
                pendingResult.finish()
            }
        }
    }
}
