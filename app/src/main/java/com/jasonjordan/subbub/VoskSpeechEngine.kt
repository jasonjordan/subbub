package com.jasonjordan.subbub

import android.util.Log
import org.vosk.Model
import org.vosk.Recognizer
import org.json.JSONObject

/**
 * On-device speech-to-text using Vosk.
 * Accepts raw PCM 16-bit mono audio and returns partial/final transcripts.
 */
class VoskSpeechEngine(modelPath: String) {

    private val model: Model = Model(modelPath)
    private var recognizer: Recognizer? = null
    private var feedCount = 0

    companion object {
        private const val TAG = "VoskSpeechEngine"
    }

    fun startListening() {
        recognizer?.close()
        recognizer = Recognizer(model, AudioCaptureManager.SAMPLE_RATE.toFloat())
        feedCount = 0
        Log.d(TAG, "Vosk recognizer started at ${AudioCaptureManager.SAMPLE_RATE} Hz")
    }

    /**
     * Feed audio bytes. Returns Pair(finalText, partialText) where only one is non-null at a time.
     */
    fun feedAudio(data: ByteArray): Pair<String?, String?> {
        val rec = recognizer ?: return null to null
        feedCount++
        val hasResult = rec.acceptWaveForm(data, data.size)
        if (feedCount % 500 == 0) {
            Log.d(TAG, "Fed $feedCount buffers (${data.size} bytes each)")
        }
        return if (hasResult) {
            val json = JSONObject(rec.result)
            val text = json.optString("text", "").trim()
            if (text.isNotBlank()) {
                Log.d(TAG, "FINAL: $text")
                text to null
            } else null to null
        } else {
            val json = JSONObject(rec.partialResult)
            val partial = json.optString("partial", "").trim()
            if (partial.isNotBlank()) {
                Log.d(TAG, "PARTIAL: $partial")
                null to partial
            } else null to null
        }
    }

    fun stopListening() {
        recognizer?.close()
        recognizer = null
    }

    fun release() {
        stopListening()
        model.close()
    }
}
