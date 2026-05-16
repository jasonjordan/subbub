package com.example.realtimesubtitles

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.projection.MediaProjection
import android.os.Build
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Creates and manages AudioRecord instances for microphone or system-audio capture.
 * Both paths output raw PCM 16-bit mono 16kHz suitable for Vosk.
 */
class AudioCaptureManager {

    private var audioRecord: AudioRecord? = null
    private var captureJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    companion object {
        const val SAMPLE_RATE = 16000
        const val TAG = "AudioCaptureManager"
    }

    fun isSystemAudioSupported(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q

    /**
     * Start capturing from the microphone. Returns true if started successfully.
     */
    fun startMicrophone(onPcmData: (ByteArray) -> Unit): Boolean {
        stop()

        val format = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(SAMPLE_RATE)
            .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
            .build()

        val minBuffer = AudioRecord.getMinBufferSize(
            SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        )

        audioRecord = AudioRecord.Builder()
            .setAudioSource(MediaRecorder.AudioSource.DEFAULT)
            .setAudioFormat(format)
            .setBufferSizeInBytes(minBuffer * 2)
            .build()

        if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
            Log.e(TAG, "Microphone AudioRecord failed to initialize")
            audioRecord?.release()
            audioRecord = null
            return false
        }

        audioRecord?.startRecording()
        Log.d(TAG, "Microphone capture started")

        captureJob = scope.launch {
            val buffer = ByteArray(minBuffer)
            while (isActive) {
                val read = audioRecord?.read(buffer, 0, buffer.size) ?: -1
                if (read > 0) {
                    onPcmData(buffer.copyOf(read))
                }
            }
        }
        return true
    }

    /**
     * Start capturing system audio via MediaProjection. Returns true if started successfully.
     */
    fun startSystemAudio(projection: MediaProjection, onPcmData: (ByteArray) -> Unit): Boolean {
        if (!isSystemAudioSupported()) {
            Log.w(TAG, "System audio capture requires Android 10+")
            return false
        }
        stop()

        val config = AudioPlaybackCaptureConfiguration.Builder(projection)
            .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
            .addMatchingUsage(AudioAttributes.USAGE_GAME)
            .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
            .build()

        val format = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(SAMPLE_RATE)
            .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
            .build()

        val minBuffer = AudioRecord.getMinBufferSize(
            SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        )

        audioRecord = AudioRecord.Builder()
            .setAudioFormat(format)
            .setBufferSizeInBytes(minBuffer * 2)
            .setAudioPlaybackCaptureConfig(config)
            .build()

        if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
            Log.e(TAG, "System AudioRecord failed to initialize")
            audioRecord?.release()
            audioRecord = null
            return false
        }

        audioRecord?.startRecording()
        Log.d(TAG, "System audio capture started")

        captureJob = scope.launch {
            val buffer = ByteArray(minBuffer)
            while (isActive) {
                val read = audioRecord?.read(buffer, 0, buffer.size) ?: -1
                if (read > 0) {
                    onPcmData(buffer.copyOf(read))
                }
            }
        }
        return true
    }

    fun stop() {
        captureJob?.cancel()
        captureJob = null
        try {
            audioRecord?.stop()
        } catch (_: IllegalStateException) {
            // Not recording
        }
        audioRecord?.release()
        audioRecord = null
        Log.d(TAG, "Audio capture stopped")
    }
}
