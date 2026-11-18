package com.example.service

import android.content.Context
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.os.Build
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File

class AudioRecorderPlayer(private val context: Context) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private var recorder: MediaRecorder? = null
    private var currentFile: File? = null
    private var recordStartMs: Long = 0L
    private var amplitudeJob: Job? = null

    private var mediaPlayer: MediaPlayer? = null
    private var playbackJob: Job? = null

    private val _isRecording = MutableStateFlow(false)
    val isRecording: StateFlow<Boolean> = _isRecording.asStateFlow()

    private val _recordingDurationMs = MutableStateFlow(0L)
    val recordingDurationMs: StateFlow<Long> = _recordingDurationMs.asStateFlow()

    private val _amplitude = MutableStateFlow(0f)
    val amplitude: StateFlow<Float> = _amplitude.asStateFlow()

    private val _playingMessageId = MutableStateFlow<String?>(null)
    val playingMessageId: StateFlow<String?> = _playingMessageId.asStateFlow()

    private val _playbackProgress = MutableStateFlow(0f)
    val playbackProgress: StateFlow<Float> = _playbackProgress.asStateFlow()

    fun startRecording(): File? {
        stopRecording(discard = true)
        val outFile = File(context.cacheDir, "voice_${System.currentTimeMillis()}.m4a")
        return try {
            val mr = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                MediaRecorder(context)
            } else {
                @Suppress("DEPRECATION")
                MediaRecorder()
            }
            mr.setAudioSource(MediaRecorder.AudioSource.MIC)
            mr.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            mr.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            mr.setAudioEncodingBitRate(96_000)
            mr.setAudioSamplingRate(44_100)
            mr.setOutputFile(outFile.absolutePath)
            mr.prepare()
            mr.start()

            recorder = mr
            currentFile = outFile
            recordStartMs = System.currentTimeMillis()
            _isRecording.value = true
            _recordingDurationMs.value = 0L

            amplitudeJob = scope.launch {
                while (isActive && _isRecording.value) {
                    _recordingDurationMs.value = System.currentTimeMillis() - recordStartMs
                    val maxAmp = try {
                        recorder?.maxAmplitude ?: 0
                    } catch (_: Exception) {
                        0
                    }
                    _amplitude.value = (maxAmp / 18000f).coerceIn(0.08f, 1f)
                    delay(80L)
                }
            }
            outFile
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start audio recording", e)
            outFile.delete()
            null
        }
    }

    fun stopRecording(discard: Boolean = false): Pair<File, Long>? {
        amplitudeJob?.cancel()
        amplitudeJob = null
        val duration = (System.currentTimeMillis() - recordStartMs).coerceAtLeast(0L)
        val file = currentFile
        try {
            recorder?.stop()
        } catch (_: Exception) {
        } finally {
            try {
                recorder?.release()
            } catch (_: Exception) {}
            recorder = null
            _isRecording.value = false
            _amplitude.value = 0f
            _recordingDurationMs.value = 0L
        }

        if (discard || file == null || !file.exists() || duration < 400L) {
            file?.delete()
            currentFile = null
            return null
        }
        currentFile = null
        return file to duration
    }

    fun togglePlayback(messageId: String, sourcePathOrUrl: String) {
        if (_playingMessageId.value == messageId) {
            stopPlayback()
            return
        }
        stopPlayback()
        try {
            val mp = MediaPlayer().apply {
                setDataSource(sourcePathOrUrl)
                setOnPreparedListener { player ->
                    player.start()
                    _playingMessageId.value = messageId
                    playbackJob = scope.launch {
                        while (isActive && mediaPlayer != null && _playingMessageId.value == messageId) {
                            val total = player.duration.coerceAtLeast(1)
                            val pos = player.currentPosition
                            _playbackProgress.value = (pos.toFloat() / total.toFloat()).coerceIn(0f, 1f)
                            delay(80L)
                        }
                    }
                }
                setOnCompletionListener {
                    stopPlayback()
                }
                setOnErrorListener { _, _, _ ->
                    stopPlayback()
                    true
                }
                prepareAsync()
            }
            mediaPlayer = mp
        } catch (e: Exception) {
            Log.e(TAG, "Audio playback error", e)
            stopPlayback()
        }
    }

    fun stopPlayback() {
        playbackJob?.cancel()
        playbackJob = null
        try {
            mediaPlayer?.stop()
        } catch (_: Exception) {
        } finally {
            try {
                mediaPlayer?.release()
            } catch (_: Exception) {}
            mediaPlayer = null
            _playingMessageId.value = null
            _playbackProgress.value = 0f
        }
    }

    private companion object {
        const val TAG = "AudioRecorderPlayer"
    }
}
