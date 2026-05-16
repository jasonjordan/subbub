package com.example.realtimesubtitles

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * Speech-to-text using the built-in Android SpeechRecognizer (microphone only).
 * Automatically restarts on error to provide continuous listening.
 */
class MicSpeechSource(
    private val context: Context,
    private val scope: CoroutineScope,
    private val onText: (String) -> Unit,
    private val onPartial: (String) -> Unit
) {

    private var recognizer: SpeechRecognizer? = null
    private var isActive = false
    private var language: String = ""

    companion object {
        private const val TAG = "MicSpeechSource"
    }

    fun start(lang: String) {
        if (isActive) return
        isActive = true
        language = lang
        initRecognizer()
        startListening()
    }

    fun stop() {
        isActive = false
        recognizer?.stopListening()
        recognizer?.destroy()
        recognizer = null
    }

    private fun initRecognizer() {
        recognizer?.destroy()
        recognizer = SpeechRecognizer.createSpeechRecognizer(context).apply {
            setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) {}
                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(rmsdB: Float) {}
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEndOfSpeech() {}

                override fun onError(error: Int) {
                    Log.w(TAG, "Error: $error")
                    if (isActive) {
                        scope.launch {
                            delay(500)
                            startListening()
                        }
                    }
                }

                override fun onResults(results: Bundle?) {
                    val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    val text = matches?.firstOrNull()?.trim() ?: ""
                    if (text.isNotBlank()) onText(text)
                    if (isActive) {
                        scope.launch {
                            delay(300)
                            startListening()
                        }
                    }
                }

                override fun onPartialResults(partialResults: Bundle?) {
                    val matches = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    val text = matches?.firstOrNull()?.trim() ?: ""
                    if (text.isNotBlank()) onPartial(text)
                }

                override fun onEvent(eventType: Int, params: Bundle?) {}
            })
        }
    }

    private fun startListening() {
        if (!isActive) return
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
            putExtra(
                RecognizerIntent.EXTRA_LANGUAGE,
                if (language.isNotBlank()) language else Locale.getDefault().toString()
            )
        }
        recognizer?.startListening(intent)
    }
}
