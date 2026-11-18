package com.example.ui.calls

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.CallMade
import androidx.compose.material.icons.automirrored.rounded.CallReceived
import androidx.compose.material.icons.rounded.Call
import androidx.compose.material.icons.rounded.CallEnd
import androidx.compose.material.icons.rounded.Videocam
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.data.model.CallSession
import com.example.data.model.UiState
import com.example.ui.components.Zap2Avatar
import com.example.ui.components.formatRelativeDateTime
import com.example.ui.theme.ZapCallRed
import com.example.ui.theme.ZapOnlineGreen

@Composable
fun CallsHistoryScreen(
    viewModel: CallsViewModel,
    onStartCall: (callId: String, isVideo: Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    val state by viewModel.callHistoryState.collectAsStateWithLifecycle()

    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.TopCenter
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .widthIn(max = 720.dp)
                .padding(horizontal = 16.dp)
        ) {
            Text(
                text = "Chamadas",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(top = 12.dp, bottom = 10.dp)
            )

            when (val uiState = state) {
                UiState.Loading -> {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                }
                is UiState.Error -> {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(uiState.message, color = MaterialTheme.colorScheme.error)
                    }
                }
                is UiState.Success -> {
                    if (uiState.data.isEmpty()) {
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center
                        ) {
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Icon(
                                    Icons.Rounded.Call,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(48.dp)
                                )
                                Text(
                                    text = "Nenhuma chamada realizada",
                                    style = MaterialTheme.typography.titleMedium
                                )
                                Text(
                                    text = "Inicie ligações de voz ou vídeo em tempo real dentro de qualquer conversa.",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    } else {
                        LazyColumn(
                            contentPadding = PaddingValues(bottom = 24.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            items(uiState.data, key = { it.id }) { session ->
                                val isOutgoing = session.callerId == viewModel.currentUserId
                                val peerUid = if (isOutgoing) session.calleeId else session.callerId
                                val peerName = if (isOutgoing) session.calleeName else session.callerName
                                val isVideo = session.callType == "VIDEO"

                                Card(
                                    shape = RoundedCornerShape(22.dp),
                                    colors = CardDefaults.cardColors(
                                        containerColor = MaterialTheme.colorScheme.surfaceContainerLow
                                    ),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(horizontal = 14.dp, vertical = 12.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                                    ) {
                                        Zap2Avatar(name = peerName, photoUrl = null, size = 48.dp)
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                text = peerName,
                                                style = MaterialTheme.typography.titleMedium
                                            )
                                            Row(
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                                            ) {
                                                Icon(
                                                    imageVector = if (isOutgoing) {
                                                        Icons.AutoMirrored.Rounded.CallMade
                                                    } else {
                                                        Icons.AutoMirrored.Rounded.CallReceived
                                                    },
                                                    contentDescription = null,
                                                    tint = if (session.status == "DECLINED" || session.status == "MISSED") {
                                                        ZapCallRed
                                                    } else {
                                                        ZapOnlineGreen
                                                    },
                                                    modifier = Modifier.size(16.dp)
                                                )
                                                Text(
                                                    text = "${if (isVideo) "Vídeo" else "Voz"} • ${session.status} • ${formatRelativeDateTime(session.createdAt?.toDate())}",
                                                    style = MaterialTheme.typography.bodySmall,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                                )
                                            }
                                        }
                                        IconButton(
                                            onClick = {
                                                viewModel.startRedialCall(
                                                    peerUid = peerUid,
                                                    peerName = peerName,
                                                    chatId = session.chatId,
                                                    myName = if (isOutgoing) session.callerName else session.calleeName,
                                                    isVideo = isVideo,
                                                    onCreated = onStartCall
                                                )
                                            }
                                        ) {
                                            Icon(
                                                imageVector = if (isVideo) Icons.Rounded.Videocam else Icons.Rounded.Call,
                                                contentDescription = "Ligar novamente",
                                                tint = MaterialTheme.colorScheme.primary
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun IncomingCallBanner(
    call: CallSession,
    onAccept: () -> Unit,
    onDecline: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        shape = RoundedCornerShape(26.dp),
        color = MaterialTheme.colorScheme.inverseSurface,
        tonalElevation = 8.dp,
        shadowElevation = 8.dp,
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 10.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Zap2Avatar(name = call.callerName, photoUrl = null, size = 46.dp)
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = call.callerName,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.inverseOnSurface
                )
                Text(
                    text = if (call.callType == "VIDEO") "Chamada de vídeo recebida…" else "Chamada de voz recebida…",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.inverseOnSurface.copy(alpha = 0.8f)
                )
            }
            Button(
                onClick = onDecline,
                colors = ButtonDefaults.buttonColors(containerColor = ZapCallRed),
                shape = RoundedCornerShape(18.dp),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)
            ) {
                Icon(Icons.Rounded.CallEnd, contentDescription = "Recusar", tint = androidx.compose.ui.graphics.Color.White)
            }
            Button(
                onClick = onAccept,
                colors = ButtonDefaults.buttonColors(containerColor = ZapOnlineGreen),
                shape = RoundedCornerShape(18.dp),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)
            ) {
                Icon(
                    if (call.callType == "VIDEO") Icons.Rounded.Videocam else Icons.Rounded.Call,
                    contentDescription = "Atender",
                    tint = androidx.compose.ui.graphics.Color.White
                )
            }
        }
    }
}
