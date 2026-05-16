package com.jasonjordan.subbub

import android.media.projection.MediaProjection
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Speech-to-text using system audio capture + Vosk.
 * Feeds raw PCM from AudioPlaybackCapture into the on-device recognizer.
 */
class RawAudioSpeechSource(
    private val scope: CoroutineScope,
    private val onText: (String) -> Unit,
    private val onPartial: (String) -> Unit,
    private val onError: (String) -> Unit = {}
) {

    private val capture = AudioCaptureManager()
    private var vosk: VoskSpeechEngine? = null

    /**
     * Start listening from system audio via MediaProjection.
     * [onSilenceDetected] is called if the system audio stream is silent
     * for several seconds (the app may block capture).
     */
    fun startSystemAudio(
        projection: MediaProjection,
        modelPath: String,
        onSilenceDetected: () -> Unit = {}
    ): Boolean {
        vosk = VoskSpeechEngine(modelPath)
        vosk?.startListening()
        return capture.startSystemAudio(
            projection = projection,
            onPcmData = { pcm -> feedVosk(pcm) },
            onSilenceDetected = onSilenceDetected
        )
    }

    fun stop() {
        capture.stop()
        vosk?.stopListening()
    }

    fun release() {
        stop()
        vosk?.release()
        vosk = null
    }

    private fun feedVosk(pcm: ByteArray) {
        val (final, partial) = vosk?.feedAudio(pcm) ?: (null to null)
        final?.let { text ->
            scope.launch(Dispatchers.Main) { onText(text) }
        }
        partial?.let { text ->
            scope.launch(Dispatchers.Main) { onPartial(text) }
        }
    }
}
