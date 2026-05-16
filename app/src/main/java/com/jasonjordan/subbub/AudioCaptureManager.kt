package com.jasonjordan.subbub

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
     *
     * TV audio is typically 48kHz stereo. We capture at native rate and downmix/resample
     * to 16kHz mono for Vosk.
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

        // Capture at 48kHz stereo (common TV output), then downsample to 16kHz mono
        val captureRate = 48000
        val format = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(captureRate)
            .setChannelMask(AudioFormat.CHANNEL_IN_STEREO)
            .build()

        val minBuffer = AudioRecord.getMinBufferSize(
            captureRate, AudioFormat.CHANNEL_IN_STEREO, AudioFormat.ENCODING_PCM_16BIT
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
        Log.d(TAG, "System audio capture started at $captureRate Hz stereo")

        captureJob = scope.launch {
            val stereoBuffer = ByteArray(minBuffer)
            while (isActive) {
                val read = audioRecord?.read(stereoBuffer, 0, stereoBuffer.size) ?: -1
                if (read > 0) {
                    val mono16k = downsample48kStereoTo16kMono(stereoBuffer.copyOf(read))
                    if (mono16k.isNotEmpty()) {
                        onPcmData(mono16k)
                    }
                }
            }
        }
        return true
    }

    /**
     * Convert 48kHz stereo 16-bit PCM to 16kHz mono 16-bit PCM.
     * Strategy: average L/R channels, then take every 3rd sample.
     */
    private fun downsample48kStereoTo16kMono(stereoBytes: ByteArray): ByteArray {
        // 48kHz stereo = 4 bytes per frame (2 bytes L + 2 bytes R)
        // We process complete frames only
        val frameCount = stereoBytes.size / 4
        if (frameCount == 0) return byteArrayOf()

        // Output: 1 mono sample for every 3 stereo frames (48000/16000 = 3)
        val outputFrames = frameCount / 3
        val monoBytes = ByteArray(outputFrames * 2)

        for (i in 0 until outputFrames) {
            val stereoIndex = i * 3 * 4  // every 3rd frame, 4 bytes per frame

            // Read left channel (little-endian)
            val left = (stereoBytes[stereoIndex].toInt() and 0xFF) or
                    ((stereoBytes[stereoIndex + 1].toInt() and 0xFF) shl 8)
            // Read right channel
            val right = (stereoBytes[stereoIndex + 2].toInt() and 0xFF) or
                    ((stereoBytes[stereoIndex + 3].toInt() and 0xFF) shl 8)

            // Convert to signed shorts, average, and clip
            val leftShort = left.toShort().toInt()
            val rightShort = right.toShort().toInt()
            val avg = ((leftShort + rightShort) / 2).toShort()

            val outIndex = i * 2
            monoBytes[outIndex] = (avg.toInt() and 0xFF).toByte()
            monoBytes[outIndex + 1] = ((avg.toInt() shr 8) and 0xFF).toByte()
        }
        return monoBytes
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
