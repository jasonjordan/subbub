package com.example.realtimesubtitles

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * Speech-to-text using the built-in Android SpeechRecognizer (microphone only).
 * Automatically restarts on error to provide continuous listening, with retry limiting
 * to prevent crash loops.
 */
class MicSpeechSource(
    private val context: Context,
    private val scope: CoroutineScope,
    private val onText: (String) -> Unit,
    private val onPartial: (String) -> Unit,
    private val onError: (String) -> Unit = {}
) {

    private var recognizer: SpeechRecognizer? = null
    private var isActive = false
    private var language: String = ""
    private var consecutiveErrors = 0

    companion object {
        private const val TAG = "MicSpeechSource"
        private const val MAX_CONSECUTIVE_ERRORS = 8
        private const val BASE_RETRY_DELAY_MS = 500L
    }

    fun start(lang: String) {
        if (isActive) return
        isActive = true
        consecutiveErrors = 0
        language = lang
        initRecognizer()
        startListening()
    }

    fun stop() {
        isActive = false
        consecutiveErrors = 0
        recognizer?.stopListening()
        recognizer?.destroy()
        recognizer = null
    }

    private fun initRecognizer() {
        recognizer?.destroy()
        recognizer = SpeechRecognizer.createSpeechRecognizer(context).apply {
            setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) {}
                override fun onBeginningOfSpeech() {
                    consecutiveErrors = 0 // reset on successful speech detection
                }
                override fun onRmsChanged(rmsdB: Float) {}
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEndOfSpeech() {}

                override fun onError(error: Int) {
                    val errorName = when (error) {
                        SpeechRecognizer.ERROR_AUDIO -> "AUDIO"
                        SpeechRecognizer.ERROR_CLIENT -> "CLIENT"
                        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "PERMISSIONS"
                        SpeechRecognizer.ERROR_NETWORK -> "NETWORK"
                        SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "NETWORK_TIMEOUT"
                        SpeechRecognizer.ERROR_NO_MATCH -> "NO_MATCH"
                        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "BUSY"
                        SpeechRecognizer.ERROR_SERVER -> "SERVER"
                        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "SPEECH_TIMEOUT"
                        else -> "UNKNOWN($error)"
                    }
                    Log.w(TAG, "Speech recognition error: $errorName")
                    consecutiveErrors++

                    if (consecutiveErrors >= MAX_CONSECUTIVE_ERRORS) {
                        Log.e(TAG, "Too many consecutive errors ($consecutiveErrors). Giving up.")
                        onError("Microphone mode failed after $consecutiveErrors errors. Try System Audio mode.")
                        stop()
                        return
                    }

                    if (isActive) {
                        scope.launch {
                            val backoffDelay = BASE_RETRY_DELAY_MS * consecutiveErrors.coerceAtMost(5)
                            delay(backoffDelay)
                            startListening()
                        }
                    }
                }

                override fun onResults(results: Bundle?) {
                    consecutiveErrors = 0
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
        try {
            recognizer?.startListening(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start listening", e)
            consecutiveErrors++
        }
    }
}
