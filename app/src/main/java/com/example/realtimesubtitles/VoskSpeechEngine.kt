package com.example.realtimesubtitles

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

    fun startListening() {
        recognizer?.close()
        recognizer = Recognizer(model, AudioCaptureManager.SAMPLE_RATE.toFloat())
    }

    /**
     * Feed audio bytes. Returns Pair(finalText, partialText) where only one is non-null at a time.
     */
    fun feedAudio(data: ByteArray): Pair<String?, String?> {
        val rec = recognizer ?: return null to null
        return if (rec.acceptWaveForm(data, data.size)) {
            val json = JSONObject(rec.result)
            val text = json.optString("text", "").trim()
            if (text.isNotBlank()) text to null else null to null
        } else {
            val json = JSONObject(rec.partialResult)
            val partial = json.optString("partial", "").trim()
            if (partial.isNotBlank()) null to partial else null to null
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
