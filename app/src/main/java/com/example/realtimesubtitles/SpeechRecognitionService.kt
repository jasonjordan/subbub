package com.example.realtimesubtitles

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Binder
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SpeechRecognitionService : LifecycleService() {

    private val binder = LocalBinder()
    private var overlayManager: OverlayManager? = null
    private var translationManager: TranslationManager? = null

    private var micSource: MicSpeechSource? = null
    private var systemSource: SystemAudioSource? = null
    private var mediaProjection: MediaProjection? = null

    private var isListening = false
    private var currentLanguage = ""
    private var audioMode = AUDIO_MODE_MIC
    private var overlayWatchdogJob: kotlinx.coroutines.Job? = null

    companion object {
        const val CHANNEL_ID = "RealtimeSubtitlesChannel"
        const val NOTIFICATION_ID = 1
        const val EXTRA_AUDIO_MODE = "audio_mode"
        const val EXTRA_LANGUAGE = "language"
        const val EXTRA_TARGET_LANGUAGE = "target_language"
        const val EXTRA_PROJECTION_DATA = "projection_data"
        const val EXTRA_PROJECTION_RESULT_CODE = "projection_result_code"
        const val ACTION_STOP = "com.example.realtimesubtitles.ACTION_STOP"
        const val AUDIO_MODE_SYSTEM = "system"
        const val AUDIO_MODE_MIC = "mic"
        const val TAG = "SpeechRecService"
    }

    inner class LocalBinder : Binder() {
        fun getService(): SpeechRecognitionService = this@SpeechRecognitionService
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        overlayManager = OverlayManager(applicationContext)
        translationManager = TranslationManager(applicationContext)
    }

    override fun onBind(intent: Intent): IBinder {
        super.onBind(intent)
        return binder
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)

        if (intent?.action == ACTION_STOP) {
            stopListening()
            return START_NOT_STICKY
        }

        startForeground(NOTIFICATION_ID, buildNotification("subbub — Starting subtitles..."))

        audioMode = intent?.getStringExtra(EXTRA_AUDIO_MODE) ?: AUDIO_MODE_MIC
        currentLanguage = intent?.getStringExtra(EXTRA_LANGUAGE) ?: ""
        val targetLang = intent?.getStringExtra(EXTRA_TARGET_LANGUAGE) ?: "en"
        SubtitleState.sourceLanguage.value = currentLanguage
        SubtitleState.targetLanguage.value = targetLang

        if (audioMode == AUDIO_MODE_SYSTEM) {
            val resultCode = intent?.getIntExtra(EXTRA_PROJECTION_RESULT_CODE, 0) ?: 0
            val data = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent?.getParcelableExtra(EXTRA_PROJECTION_DATA, Intent::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent?.getParcelableExtra(EXTRA_PROJECTION_DATA)
            }
            if (data != null && resultCode != 0) {
                val mpManager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                mediaProjection = mpManager.getMediaProjection(resultCode, data)
                startSystemAudioMode()
            } else {
                startMicMode()
            }
        } else {
            startMicMode()
        }
        return START_STICKY
    }

    fun stopListening() {
        isListening = false
        SubtitleState.isListening.value = false
        overlayWatchdogJob?.cancel()
        overlayWatchdogJob = null
        micSource?.stop()
        micSource = null
        systemSource?.stop()
        systemSource?.release()
        systemSource = null
        mediaProjection?.stop()
        mediaProjection = null
        overlayManager?.remove()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
        stopSelf()
    }

    private fun startOverlayWatchdog() {
        overlayWatchdogJob?.cancel()
        overlayWatchdogJob = lifecycleScope.launch {
            while (isActive && isListening) {
                delay(1200)
                val text = SubtitleState.currentText.value
                if (text.isNotBlank() && overlayManager?.canOverlay == true) {
                    overlayManager?.show(text)
                }
            }
        }
    }

    private fun startMicMode() {
        isListening = true
        SubtitleState.isListening.value = true
        updateNotification("subbub — Listening via microphone...")
        startOverlayWatchdog()

        micSource = MicSpeechSource(
            context = this,
            scope = lifecycleScope,
            onText = { processText(it) },
            onPartial = { showSubtitle(it, isPartial = true) }
        )
        micSource?.start(currentLanguage)
    }

    private fun startSystemAudioMode() {
        if (mediaProjection == null) {
            startMicMode()
            return
        }
        lifecycleScope.launch {
            val lang = currentLanguage.takeIf { it.isNotBlank() } ?: "en"
            val modelPath = if (ModelManager.isModelAvailable(applicationContext, lang)) {
                ModelManager.modelDir(applicationContext, lang).absolutePath
            } else {
                updateNotification("subbub — Downloading speech model...")
                ModelManager.prepareModel(applicationContext, lang)
            }

            if (modelPath == null) {
                updateNotification("subbub — Model unavailable, falling back to microphone")
                startMicMode()
                return@launch
            }

            isListening = true
            SubtitleState.isListening.value = true
            updateNotification("subbub — Capturing system audio...")
            startOverlayWatchdog()

            systemSource = SystemAudioSource(
                modelPath = modelPath,
                scope = lifecycleScope,
                onText = { processText(it) },
                onPartial = { showSubtitle(it, isPartial = true) }
            )
            systemSource?.start(mediaProjection!!)
        }
    }

    private fun processText(text: String) {
        val sourceLang = SubtitleState.sourceLanguage.value
        val targetLang = SubtitleState.targetLanguage.value
        val needsTranslation = sourceLang.isNotBlank() && targetLang.isNotBlank() && sourceLang != targetLang

        if (needsTranslation) {
            lifecycleScope.launch {
                val translated = withContext(Dispatchers.IO) {
                    translationManager?.translate(text, sourceLang, targetLang) ?: text
                }
                showSubtitle(translated, isPartial = false)
                scheduleClear(translated)
            }
        } else {
            showSubtitle(text, isPartial = false)
            scheduleClear(text)
        }
    }

    private fun showSubtitle(text: String, isPartial: Boolean) {
        SubtitleState.currentText.value = text
        if (overlayManager?.canOverlay == true) {
            overlayManager?.show(text)
        }
    }

    private fun scheduleClear(lastText: String) {
        lifecycleScope.launch {
            delay(4500)
            if (SubtitleState.currentText.value == lastText) {
                SubtitleState.currentText.value = ""
                overlayManager?.hide()
            }
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Live Subtitles",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "subbub by Jason Jordan — Real-time translated subtitles"
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun updateNotification(content: String) {
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, buildNotification(content))
    }

    private fun buildNotification(content: String): Notification {
        val openIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        val stopIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, SpeechRecognitionService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("subbub")
            .setContentText(content)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentIntent(openIntent)
            .setOngoing(true)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop", stopIntent)
            .build()
    }
}
