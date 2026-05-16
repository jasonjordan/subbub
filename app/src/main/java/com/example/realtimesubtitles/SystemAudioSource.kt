package com.example.realtimesubtitles

import android.media.projection.MediaProjection
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Speech-to-text using system audio capture + Vosk.
 * Feeds raw PCM from AudioPlaybackCapture into the on-device recognizer.
 */
class SystemAudioSource(
    modelPath: String,
    private val scope: CoroutineScope,
    private val onText: (String) -> Unit,
    private val onPartial: (String) -> Unit
) {

    private val capture = AudioCaptureManager()
    private val vosk = VoskSpeechEngine(modelPath)

    fun start(projection: MediaProjection) {
        vosk.startListening()
        capture.start(projection) { pcm ->
            val (final, partial) = vosk.feedAudio(pcm)
            final?.let { text ->
                scope.launch(Dispatchers.Main) { onText(text) }
            }
            partial?.let { text ->
                scope.launch(Dispatchers.Main) { onPartial(text) }
            }
        }
    }

    fun stop() {
        capture.stop()
        vosk.stopListening()
    }

    fun release() {
        stop()
        vosk.release()
    }
}
