package com.example.ui.calls

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CallEnd
import androidx.compose.material.icons.rounded.Cameraswitch
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.MicOff
import androidx.compose.material.icons.rounded.Videocam
import androidx.compose.material.icons.rounded.VideocamOff
import androidx.compose.material.icons.rounded.VolumeOff
import androidx.compose.material.icons.rounded.VolumeUp
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.data.remote.WebRtcCallManager
import com.example.ui.components.Zap2Avatar
import com.example.ui.theme.ZapCallRed
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.webrtc.RendererCommon
import org.webrtc.SurfaceViewRenderer

@Composable
fun ActiveCallScreen(
    callId: String,
    currentUserId: String,
    isIncoming: Boolean,
    isVideo: Boolean,
    callManager: WebRtcCallManager,
    onCallEnded: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val session by callManager.callSession.collectAsStateWithLifecycle()
    val connectionState by callManager.connectionState.collectAsStateWithLifecycle()
    val isMuted by callManager.isMuted.collectAsStateWithLifecycle()
    val isSpeakerOn by callManager.isSpeakerOn.collectAsStateWithLifecycle()
    val isCameraEnabled by callManager.isCameraEnabled.collectAsStateWithLifecycle()

    var permissionsGranted by remember { mutableStateOf(false) }
    var elapsedSeconds by remember { mutableLongStateOf(0L) }
    var localRenderer by remember { mutableStateOf<SurfaceViewRenderer?>(null) }
    var remoteRenderer by remember { mutableStateOf<SurfaceViewRenderer?>(null) }

    val permLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        val audioOk = grants[Manifest.permission.RECORD_AUDIO] == true
        val videoOk = !isVideo || grants[Manifest.permission.CAMERA] == true
        permissionsGranted = audioOk && videoOk
        if (!permissionsGranted) {
            scope.launch {
                callManager.hangUp(callId)
                onCallEnded()
            }
        }
    }

    LaunchedEffect(Unit) {
        val hasAudio = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
        val hasCam = !isVideo || ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED
        if (hasAudio && hasCam) {
            permissionsGranted = true
        } else {
            val req = if (isVideo) {
                arrayOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.CAMERA)
            } else {
                arrayOf(Manifest.permission.RECORD_AUDIO)
            }
            permLauncher.launch(req)
        }
    }

    LaunchedEffect(permissionsGranted, callId) {
        if (permissionsGranted) {
            callManager.startCallSession(
                callId = callId,
                currentUserId = currentUserId,
                isCaller = !isIncoming,
                isVideoCall = isVideo
            )
        }
    }

    LaunchedEffect(connectionState) {
        if (connectionState == "Conectado") {
            while (true) {
                delay(1000L)
                elapsedSeconds++
            }
        }
    }

    LaunchedEffect(session?.status) {
        when (session?.status) {
            "ENDED", "DECLINED", "MISSED" -> {
                callManager.cleanupMedia()
                onCallEnded()
            }
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            callManager.attachRenderers(null, null)
            try {
                localRenderer?.release()
                remoteRenderer?.release()
            } catch (_: Exception) {}
            callManager.cleanupMedia()
        }
    }

    BackHandler {
        scope.launch {
            callManager.hangUp(callId)
            onCallEnded()
        }
    }

    val peerName = session?.let {
        if (it.callerId == currentUserId) it.calleeName else it.callerName
    } ?: "Chamada Zap2"

    val formattedDuration = if (elapsedSeconds > 0L) {
        "${elapsedSeconds / 60}:${(elapsedSeconds % 60).toString().padStart(2, '0')}"
    } else {
        connectionState
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    listOf(Color(0xFF0A221A), Color(0xFF06110D))
                )
            )
    ) {
        if (isVideo) {
            AndroidView(
                factory = { ctx ->
                    SurfaceViewRenderer(ctx).apply {
                        init(callManager.eglBase.eglBaseContext, null)
                        setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FILL)
                        setEnableHardwareScaler(true)
                        remoteRenderer = this
                        callManager.attachRenderers(localRenderer, this)
                    }
                },
                modifier = Modifier.fillMaxSize()
            )

            Surface(
                shape = RoundedCornerShape(22.dp),
                shadowElevation = 6.dp,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .statusBarsPadding()
                    .padding(16.dp)
                    .size(width = 112.dp, height = 164.dp)
            ) {
                AndroidView(
                    factory = { ctx ->
                        SurfaceViewRenderer(ctx).apply {
                            init(callManager.eglBase.eglBaseContext, null)
                            setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FILL)
                            setMirror(true)
                            setZOrderMediaOverlay(true)
                            localRenderer = this
                            callManager.attachRenderers(this, remoteRenderer)
                        }
                    },
                    modifier = Modifier.fillMaxSize()
                )
            }
        } else {
            Column(
                modifier = Modifier
                    .align(Alignment.Center)
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Zap2Avatar(name = peerName, photoUrl = null, size = 120.dp)
                Text(
                    text = peerName,
                    style = MaterialTheme.typography.headlineMedium,
                    color = Color.White,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = formattedDuration,
                    style = MaterialTheme.typography.titleMedium,
                    color = Color.White.copy(alpha = 0.8f)
                )
            }
        }

        // Top info pill during video call
        if (isVideo) {
            Surface(
                shape = RoundedCornerShape(20.dp),
                color = Color.Black.copy(alpha = 0.45f),
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .statusBarsPadding()
                    .padding(16.dp)
            ) {
                Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                    Text(
                        text = peerName,
                        style = MaterialTheme.typography.titleMedium,
                        color = Color.White
                    )
                    Text(
                        text = formattedDuration,
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.White.copy(alpha = 0.85f)
                    )
                }
            }
        }

        // Bottom Expressive Controls Bar
        Surface(
            shape = RoundedCornerShape(32.dp),
            color = Color.Black.copy(alpha = 0.65f),
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(horizontal = 20.dp, vertical = 24.dp)
                .fillMaxWidth()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                CallControlCircle(
                    icon = if (isMuted) Icons.Rounded.MicOff else Icons.Rounded.Mic,
                    contentDescription = if (isMuted) "Ativar microfone" else "Silenciar",
                    active = isMuted,
                    onClick = callManager::toggleMute
                )
                CallControlCircle(
                    icon = if (isSpeakerOn) Icons.Rounded.VolumeUp else Icons.Rounded.VolumeOff,
                    contentDescription = "Viva-voz",
                    active = isSpeakerOn,
                    onClick = callManager::toggleSpeakerphone
                )
                if (isVideo) {
                    CallControlCircle(
                        icon = if (isCameraEnabled) Icons.Rounded.Videocam else Icons.Rounded.VideocamOff,
                        contentDescription = "Câmera",
                        active = !isCameraEnabled,
                        onClick = callManager::toggleCamera
                    )
                    CallControlCircle(
                        icon = Icons.Rounded.Cameraswitch,
                        contentDescription = "Alternar câmera",
                        active = false,
                        onClick = callManager::switchCamera
                    )
                }
                Surface(
                    onClick = {
                        scope.launch {
                            callManager.hangUp(callId)
                            onCallEnded()
                        }
                    },
                    shape = CircleShape,
                    color = ZapCallRed,
                    modifier = Modifier
                        .size(56.dp)
                        .testTag("hangup_call_button")
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            Icons.Rounded.CallEnd,
                            contentDescription = "Encerrar chamada",
                            tint = Color.White
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun CallControlCircle(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    contentDescription: String,
    active: Boolean,
    onClick: () -> Unit
) {
    Surface(
        shape = CircleShape,
        color = if (active) Color.White else Color.White.copy(alpha = 0.18f),
        modifier = Modifier
            .size(50.dp)
            .clip(CircleShape)
    ) {
        IconButton(onClick = onClick) {
            Icon(
                imageVector = icon,
                contentDescription = contentDescription,
                tint = if (active) Color.Black else Color.White
            )
        }
    }
}
