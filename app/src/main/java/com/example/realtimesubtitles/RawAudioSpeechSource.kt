package com.example.realtimesubtitles

import android.media.projection.MediaProjection
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Unified speech-to-text using raw AudioRecord + Vosk.
 * Works with either microphone or system-audio input.
 * Runs entirely in the calling scope (typically a foreground service),
 * so it survives app switching unlike Android's SpeechRecognizer.
 */
class RawAudioSpeechSource(
    private val scope: CoroutineScope,
    private val onText: (String) -> Unit,
    private val onPartial: (String) -> Unit,
    private val onError: (String) -> Unit = {}
) {

    private val capture = AudioCaptureManager()
    private var vosk: VoskSpeechEngine? = null

    companion object {
        private const val TAG = "RawAudioSpeechSource"
    }

    /**
     * Start listening from the microphone.
     */
    fun startMicrophone(modelPath: String): Boolean {
        vosk = VoskSpeechEngine(modelPath)
        vosk?.startListening()
        return capture.startMicrophone { pcm ->
            feedVosk(pcm)
        }
    }

    /**
     * Start listening from system audio via MediaProjection.
     */
    fun startSystemAudio(projection: MediaProjection, modelPath: String): Boolean {
        vosk = VoskSpeechEngine(modelPath)
        vosk?.startListening()
        return capture.startSystemAudio(projection) { pcm ->
            feedVosk(pcm)
        }
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
