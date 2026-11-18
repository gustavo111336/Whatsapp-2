package com.example.data.remote

import android.content.Context
import android.media.AudioManager
import android.util.Log
import com.example.data.model.CallSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import org.webrtc.AudioSource
import org.webrtc.AudioTrack
import org.webrtc.Camera2Enumerator
import org.webrtc.CameraVideoCapturer
import org.webrtc.DataChannel
import org.webrtc.DefaultVideoDecoderFactory
import org.webrtc.DefaultVideoEncoderFactory
import org.webrtc.EglBase
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RtpReceiver
import org.webrtc.RtpTransceiver
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.SurfaceTextureHelper
import org.webrtc.SurfaceViewRenderer
import org.webrtc.VideoSource
import org.webrtc.VideoTrack

class WebRtcCallManager(
    private val context: Context,
    private val signalingRepo: CallSignalingRepository
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    val eglBase: EglBase = EglBase.create()
    private var peerConnectionFactory: PeerConnectionFactory? = null
    private var peerConnection: PeerConnection? = null
    private var localAudioSource: AudioSource? = null
    private var localAudioTrack: AudioTrack? = null
    private var localVideoSource: VideoSource? = null
    private var localVideoTrack: VideoTrack? = null
    private var videoCapturer: CameraVideoCapturer? = null
    private var surfaceTextureHelper: SurfaceTextureHelper? = null

    private var localRenderer: SurfaceViewRenderer? = null
    private var remoteRenderer: SurfaceViewRenderer? = null
    private var remoteVideoTrack: VideoTrack? = null

    private var sessionJob: Job? = null
    private var candidatesJob: Job? = null
    private val seenCandidateIds = HashSet<String>()
    private var remoteSdpApplied = false

    private val _callSession = MutableStateFlow<CallSession?>(null)
    val callSession: StateFlow<CallSession?> = _callSession.asStateFlow()

    private val _isMuted = MutableStateFlow(false)
    val isMuted: StateFlow<Boolean> = _isMuted.asStateFlow()

    private val _isSpeakerOn = MutableStateFlow(true)
    val isSpeakerOn: StateFlow<Boolean> = _isSpeakerOn.asStateFlow()

    private val _isCameraEnabled = MutableStateFlow(true)
    val isCameraEnabled: StateFlow<Boolean> = _isCameraEnabled.asStateFlow()

    private val _connectionState = MutableStateFlow("Conectando…")
    val connectionState: StateFlow<String> = _connectionState.asStateFlow()

    fun attachRenderers(local: SurfaceViewRenderer?, remote: SurfaceViewRenderer?) {
        localRenderer = local
        remoteRenderer = remote
        local?.let { renderer ->
            localVideoTrack?.addSink(renderer)
        }
        remote?.let { renderer ->
            remoteVideoTrack?.addSink(renderer)
        }
    }

    fun startCallSession(
        callId: String,
        currentUserId: String,
        isCaller: Boolean,
        isVideoCall: Boolean
    ) {
        cleanupMedia()
        remoteSdpApplied = false
        seenCandidateIds.clear()
        _isCameraEnabled.value = isVideoCall
        _isMuted.value = false
        _connectionState.value = if (isCaller) "Chamando…" else "Conectando mídia…"

        setSpeakerphone(isVideoCall)
        ensureFactoryInitialized()

        sessionJob = scope.launch {
            signalingRepo.observeCallSession(callId, currentUserId).collectLatest { session ->
                _callSession.value = session
                if (session == null) return@collectLatest

                when (session.status) {
                    "ENDED", "DECLINED", "MISSED" -> {
                        _connectionState.value = "Chamada encerrada"
                        return@collectLatest
                    }
                }

                if (peerConnection == null) {
                    initPeerConnection(
                        callId = callId,
                        participantUids = session.participantUids,
                        isCaller = isCaller,
                        isVideoCall = session.callType == "VIDEO"
                    )
                    if (isCaller) {
                        createAndSendOffer(callId)
                    } else if (!session.offerSdp.isNullOrBlank() && !remoteSdpApplied) {
                        applyOfferAndSendAnswer(callId, session.offerSdp)
                    }
                    observeRemoteCandidates(
                        callId = callId,
                        subcollection = if (isCaller) "calleeCandidates" else "callerCandidates",
                        uid = currentUserId
                    )
                } else {
                    if (isCaller && !session.answerSdp.isNullOrBlank() && !remoteSdpApplied) {
                        applyAnswerSdp(session.answerSdp)
                    } else if (!isCaller && !session.offerSdp.isNullOrBlank() && !remoteSdpApplied) {
                        applyOfferAndSendAnswer(callId, session.offerSdp)
                    }
                }
            }
        }
    }

    private fun ensureFactoryInitialized() {
        if (peerConnectionFactory != null) return
        val initOptions = PeerConnectionFactory.InitializationOptions.builder(context)
            .setEnableInternalTracer(false)
            .createInitializationOptions()
        PeerConnectionFactory.initialize(initOptions)

        val encoderFactory = DefaultVideoEncoderFactory(eglBase.eglBaseContext, true, true)
        val decoderFactory = DefaultVideoDecoderFactory(eglBase.eglBaseContext)
        peerConnectionFactory = PeerConnectionFactory.builder()
            .setVideoEncoderFactory(encoderFactory)
            .setVideoDecoderFactory(decoderFactory)
            .createPeerConnectionFactory()
    }

    private fun initPeerConnection(
        callId: String,
        participantUids: List<String>,
        isCaller: Boolean,
        isVideoCall: Boolean
    ) {
        val factory = peerConnectionFactory ?: return
        val iceServers = listOf(
            PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer(),
            PeerConnection.IceServer.builder("stun:stun1.l.google.com:19302").createIceServer()
        )
        val rtcConfig = PeerConnection.RTCConfiguration(iceServers).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
        }

        val observer = object : PeerConnection.Observer {
            override fun onSignalingChange(newState: PeerConnection.SignalingState?) {}
            override fun onIceConnectionChange(newState: PeerConnection.IceConnectionState?) {
                scope.launch {
                    _connectionState.value = when (newState) {
                        PeerConnection.IceConnectionState.CONNECTED,
                        PeerConnection.IceConnectionState.COMPLETED -> "Conectado"
                        PeerConnection.IceConnectionState.DISCONNECTED -> "Reconectando…"
                        PeerConnection.IceConnectionState.FAILED -> "Falha na conexão"
                        else -> _connectionState.value
                    }
                }
            }

            override fun onIceConnectionReceivingChange(receiving: Boolean) {}
            override fun onIceGatheringChange(newState: PeerConnection.IceGatheringState?) {}
            override fun onIceCandidate(candidate: IceCandidate?) {
                if (candidate == null) return
                val targetSub = if (isCaller) "callerCandidates" else "calleeCandidates"
                scope.launch(Dispatchers.IO) {
                    signalingRepo.addIceCandidate(
                        callId = callId,
                        subcollection = targetSub,
                        participantUids = participantUids,
                        sdpMid = candidate.sdpMid ?: "0",
                        sdpMLineIndex = candidate.sdpMLineIndex,
                        sdp = candidate.sdp
                    )
                }
            }

            override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>?) {}
            override fun onAddStream(stream: MediaStream?) {
                val track = stream?.videoTracks?.firstOrNull()
                if (track != null) {
                    remoteVideoTrack = track
                    scope.launch { remoteRenderer?.let { track.addSink(it) } }
                }
            }

            override fun onRemoveStream(stream: MediaStream?) {}
            override fun onDataChannel(channel: DataChannel?) {}
            override fun onRenegotiationNeeded() {}
            override fun onAddTrack(receiver: RtpReceiver?, streams: Array<out MediaStream>?) {
                val track = receiver?.track()
                if (track is VideoTrack) {
                    remoteVideoTrack = track
                    scope.launch { remoteRenderer?.let { track.addSink(it) } }
                }
            }

            override fun onTrack(transceiver: RtpTransceiver?) {
                val track = transceiver?.receiver?.track()
                if (track is VideoTrack) {
                    remoteVideoTrack = track
                    scope.launch { remoteRenderer?.let { track.addSink(it) } }
                }
            }
        }

        val pc = factory.createPeerConnection(rtcConfig, observer)
        peerConnection = pc
        if (pc == null) {
            _connectionState.value = "Erro ao iniciar WebRTC"
            return
        }

        val audioConstraints = MediaConstraints()
        localAudioSource = factory.createAudioSource(audioConstraints)
        localAudioTrack = factory.createAudioTrack("ARDAMSa0", localAudioSource).apply {
            setEnabled(true)
        }
        localAudioTrack?.let { pc.addTrack(it, listOf("zap2_stream")) }

        if (isVideoCall) {
            setupLocalVideo(factory, pc)
        }
    }

    private fun setupLocalVideo(factory: PeerConnectionFactory, pc: PeerConnection) {
        val enumerator = Camera2Enumerator(context)
        val deviceName = enumerator.deviceNames.firstOrNull { enumerator.isFrontFacing(it) }
            ?: enumerator.deviceNames.firstOrNull()
        if (deviceName == null) {
            Log.w(TAG, "No camera device found on device")
            return
        }
        val capturer = enumerator.createCapturer(deviceName, null) ?: return
        videoCapturer = capturer
        val texHelper = SurfaceTextureHelper.create("CaptureThread", eglBase.eglBaseContext)
        surfaceTextureHelper = texHelper
        val vSource = factory.createVideoSource(capturer.isScreencast)
        localVideoSource = vSource
        capturer.initialize(texHelper, context, vSource.capturerObserver)
        try {
            capturer.startCapture(720, 1280, 30)
        } catch (e: Exception) {
            Log.w(TAG, "Camera capture start failed: ${e.message}")
        }
        val vTrack = factory.createVideoTrack("ARDAMSv0", vSource).apply { setEnabled(true) }
        localVideoTrack = vTrack
        localRenderer?.let { vTrack.addSink(it) }
        pc.addTrack(vTrack, listOf("zap2_stream"))
    }

    private fun createAndSendOffer(callId: String) {
        val pc = peerConnection ?: return
        val constraints = MediaConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", "true"))
        }
        pc.createOffer(object : SimpleSdpObserver() {
            override fun onCreateSuccess(sdp: SessionDescription?) {
                if (sdp == null) return
                pc.setLocalDescription(SimpleSdpObserver(), sdp)
                scope.launch(Dispatchers.IO) {
                    signalingRepo.publishOfferSdp(callId, sdp.description)
                }
            }
        }, constraints)
    }

    private fun applyOfferAndSendAnswer(callId: String, offerSdp: String) {
        val pc = peerConnection ?: return
        remoteSdpApplied = true
        val offer = SessionDescription(SessionDescription.Type.OFFER, offerSdp)
        pc.setRemoteDescription(object : SimpleSdpObserver() {
            override fun onSetSuccess() {
                val constraints = MediaConstraints().apply {
                    mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"))
                    mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", "true"))
                }
                pc.createAnswer(object : SimpleSdpObserver() {
                    override fun onCreateSuccess(answerSdp: SessionDescription?) {
                        if (answerSdp == null) return
                        pc.setLocalDescription(SimpleSdpObserver(), answerSdp)
                        scope.launch(Dispatchers.IO) {
                            signalingRepo.publishAnswerSdp(callId, answerSdp.description)
                        }
                    }
                }, constraints)
            }
        }, offer)
    }

    private fun applyAnswerSdp(answerSdp: String) {
        val pc = peerConnection ?: return
        remoteSdpApplied = true
        val answer = SessionDescription(SessionDescription.Type.ANSWER, answerSdp)
        pc.setRemoteDescription(SimpleSdpObserver(), answer)
    }

    private fun observeRemoteCandidates(callId: String, subcollection: String, uid: String) {
        candidatesJob?.cancel()
        candidatesJob = scope.launch {
            signalingRepo.observeIceCandidates(callId, subcollection, uid).collectLatest { list ->
                val pc = peerConnection ?: return@collectLatest
                list.forEach { cand ->
                    if (cand.id.isNotBlank() && seenCandidateIds.add(cand.id)) {
                        pc.addIceCandidate(IceCandidate(cand.sdpMid, cand.sdpMLineIndex, cand.sdp))
                    }
                }
            }
        }
    }

    fun toggleMute() {
        val next = !_isMuted.value
        _isMuted.value = next
        localAudioTrack?.setEnabled(!next)
    }

    fun toggleCamera() {
        val next = !_isCameraEnabled.value
        _isCameraEnabled.value = next
        localVideoTrack?.setEnabled(next)
    }

    fun switchCamera() {
        videoCapturer?.switchCamera(null)
    }

    fun toggleSpeakerphone() {
        setSpeakerphone(!_isSpeakerOn.value)
    }

    private fun setSpeakerphone(enabled: Boolean) {
        _isSpeakerOn.value = enabled
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        audioManager?.mode = AudioManager.MODE_IN_COMMUNICATION
        @Suppress("DEPRECATION")
        audioManager?.isSpeakerphoneOn = enabled
    }

    suspend fun hangUp(callId: String) {
        signalingRepo.updateCallStatus(callId, "ENDED")
        cleanupMedia()
    }

    fun cleanupMedia() {
        sessionJob?.cancel()
        sessionJob = null
        candidatesJob?.cancel()
        candidatesJob = null

        try {
            localRenderer?.let { r -> localVideoTrack?.removeSink(r) }
            remoteRenderer?.let { r -> remoteVideoTrack?.removeSink(r) }
        } catch (_: Exception) {}

        try {
            videoCapturer?.stopCapture()
            videoCapturer?.dispose()
        } catch (_: Exception) {}
        videoCapturer = null

        surfaceTextureHelper?.dispose()
        surfaceTextureHelper = null

        localVideoTrack?.dispose()
        localVideoTrack = null
        localVideoSource?.dispose()
        localVideoSource = null

        localAudioTrack?.dispose()
        localAudioTrack = null
        localAudioSource?.dispose()
        localAudioSource = null

        remoteVideoTrack = null

        peerConnection?.close()
        peerConnection?.dispose()
        peerConnection = null
    }

    fun release() {
        cleanupMedia()
        peerConnectionFactory?.dispose()
        peerConnectionFactory = null
        eglBase.release()
        scope.cancel()
    }

    private open class SimpleSdpObserver : SdpObserver {
        override fun onCreateSuccess(sdp: SessionDescription?) {}
        override fun onSetSuccess() {}
        override fun onCreateFailure(error: String?) {
            Log.w(TAG, "SDP create failure: $error")
        }
        override fun onSetFailure(error: String?) {
            Log.w(TAG, "SDP set failure: $error")
        }
    }

    private companion object {
        const val TAG = "WebRtcCallManager"
    }
}
