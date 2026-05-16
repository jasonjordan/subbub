package com.example.realtimesubtitles

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.os.IBinder
import android.provider.Settings
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.example.realtimesubtitles.databinding.ActivityMainBinding
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var service: SpeechRecognitionService? = null
    private var bound = false

    private val micPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            tryStartAfterPermissions()
        } else {
            Toast.makeText(this, "Microphone permission is required", Toast.LENGTH_LONG).show()
        }
    }

    private val mediaProjectionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK && result.data != null) {
            startServiceWithProjection(result.data!!, result.resultCode)
        } else {
            Toast.makeText(this, "Screen capture permission denied. Falling back to microphone.", Toast.LENGTH_LONG).show()
            startServiceDirect(audioMode = SpeechRecognitionService.AUDIO_MODE_MIC)
        }
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val localBinder = binder as? SpeechRecognitionService.LocalBinder
            service = localBinder?.getService()
            bound = true
            updateUIState()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            service = null
            bound = false
            updateUIState()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupSpinners()
        setupButtons()
        observeSubtitles()
    }

    override fun onResume() {
        super.onResume()
        updateAccessibilityBanner()
    }

    override fun onStart() {
        super.onStart()
        Intent(this, SpeechRecognitionService::class.java).also { intent ->
            bindService(intent, connection, Context.BIND_AUTO_CREATE)
        }
    }

    override fun onStop() {
        super.onStop()
        if (bound) {
            unbindService(connection)
            bound = false
        }
    }

    private fun updateAccessibilityBanner() {
        val enabled = SubtitleAccessibilityService.isEnabled(this)
        if (enabled) {
            binding.accessibilityBanner.visibility = View.GONE
        } else {
            binding.accessibilityBanner.visibility = View.VISIBLE
        }
    }

    private fun setupSpinners() {
        // Audio source
        val audioSources = listOf("System Audio" to SpeechRecognitionService.AUDIO_MODE_SYSTEM, "Microphone" to SpeechRecognitionService.AUDIO_MODE_MIC)
        val audioAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, audioSources.map { it.first })
        audioAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        binding.audioSourceSpinner.adapter = audioAdapter

        // Languages available for both source and target
        val languages = listOf(
            "English" to "en",
            "Spanish" to "es",
            "French" to "fr",
            "German" to "de",
            "Italian" to "it",
            "Portuguese" to "pt",
            "Russian" to "ru",
            "Chinese" to "zh",
            "Polish" to "pl",
            "Japanese" to "ja",
            "Korean" to "ko",
            "Arabic" to "ar",
            "Hindi" to "hi",
            "Dutch" to "nl",
            "Turkish" to "tr",
            "Vietnamese" to "vi",
            "Czech" to "cs",
            "Greek" to "el",
            "Hebrew" to "he",
            "Romanian" to "ro",
            "Swedish" to "sv",
            "Hungarian" to "hu"
        )

        // Source language (what is being spoken)
        val sourceAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, languages.map { it.first })
        sourceAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        binding.sourceLanguageSpinner.adapter = sourceAdapter
        binding.sourceLanguageSpinner.setSelection(0)

        binding.sourceLanguageSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                SubtitleState.sourceLanguage.value = languages[position].second
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        // Target language (what subtitles should show)
        val targetAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, languages.map { it.first })
        targetAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        binding.targetLanguageSpinner.adapter = targetAdapter
        binding.targetLanguageSpinner.setSelection(0)

        binding.targetLanguageSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                SubtitleState.targetLanguage.value = languages[position].second
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        SubtitleState.sourceLanguage.value = languages[0].second
        SubtitleState.targetLanguage.value = languages[0].second
    }

    private fun setupButtons() {
        binding.startButton.setOnClickListener {
            tryStartAfterPermissions()
        }
        binding.stopButton.setOnClickListener {
            stopSubtitleService()
        }
        binding.helpButton.setOnClickListener {
            startActivity(Intent(this, HelpActivity::class.java))
        }
        binding.accessibilityEnableButton.setOnClickListener {
            val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            startActivity(intent)
        }
    }

    private fun observeSubtitles() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                SubtitleState.currentText.collect { text ->
                    // Only show subtitle text if not currently downloading
                    if (SubtitleState.downloadProgress.value < 0) {
                        binding.subtitleText.text = text
                        binding.subtitleText.visibility = if (text.isBlank()) View.GONE else View.VISIBLE
                    }
                }
            }
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                SubtitleState.isListening.collect { updateUIState() }
            }
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                SubtitleState.downloadStatus.collect { status ->
                    val progress = SubtitleState.downloadProgress.value
                    if (progress >= 0 && status.isNotBlank()) {
                        binding.subtitleText.text = status
                        binding.subtitleText.visibility = View.VISIBLE
                    } else if (progress == -1 && SubtitleState.currentText.value.isBlank()) {
                        binding.subtitleText.visibility = View.GONE
                    }
                }
            }
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                SubtitleState.downloadProgress.collect { progress ->
                    val status = SubtitleState.downloadStatus.value
                    if (progress >= 0 && status.isNotBlank()) {
                        binding.subtitleText.text = status
                        binding.subtitleText.visibility = View.VISIBLE
                    } else if (progress == -1 && SubtitleState.currentText.value.isBlank()) {
                        binding.subtitleText.visibility = View.GONE
                    }
                }
            }
        }
    }

    private fun updateUIState() {
        val listening = SubtitleState.isListening.value
        val accessibilityEnabled = SubtitleAccessibilityService.isEnabled(this)

        binding.startButton.isEnabled = accessibilityEnabled && !listening
        binding.startButton.alpha = if (accessibilityEnabled && !listening) 1.0f else 0.4f
        binding.startButton.visibility = if (listening) View.GONE else View.VISIBLE
        binding.helpButton.visibility = if (listening) View.GONE else View.VISIBLE
        binding.stopButton.visibility = if (listening) View.VISIBLE else View.GONE
        binding.sourceLanguageSpinner.isEnabled = !listening
        binding.targetLanguageSpinner.isEnabled = !listening
        binding.audioSourceSpinner.isEnabled = !listening
    }

    private fun tryStartAfterPermissions() {
        if (SubtitleState.isListening.value) return

        // 1. Accessibility service is required
        if (!SubtitleAccessibilityService.isEnabled(this)) {
            Toast.makeText(this, "Please enable subbub in Settings > Accessibility first", Toast.LENGTH_LONG).show()
            val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            startActivity(intent)
            return
        }

        // 2. Microphone permission
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            return
        }

        // 3. Determine audio mode
        val audioMode = if (binding.audioSourceSpinner.selectedItemPosition == 0)
            SpeechRecognitionService.AUDIO_MODE_SYSTEM
        else
            SpeechRecognitionService.AUDIO_MODE_MIC

        if (audioMode == SpeechRecognitionService.AUDIO_MODE_SYSTEM) {
            val mpManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            mediaProjectionLauncher.launch(mpManager.createScreenCaptureIntent())
        } else {
            startServiceDirect(audioMode)
        }
    }

    private fun startServiceWithProjection(data: Intent, resultCode: Int) {
        val intent = Intent(this, SpeechRecognitionService::class.java).apply {
            putExtra(SpeechRecognitionService.EXTRA_AUDIO_MODE, SpeechRecognitionService.AUDIO_MODE_SYSTEM)
            putExtra(SpeechRecognitionService.EXTRA_LANGUAGE, SubtitleState.sourceLanguage.value)
            putExtra(SpeechRecognitionService.EXTRA_TARGET_LANGUAGE, SubtitleState.targetLanguage.value)
            putExtra(SpeechRecognitionService.EXTRA_PROJECTION_DATA, data)
            putExtra(SpeechRecognitionService.EXTRA_PROJECTION_RESULT_CODE, resultCode)
        }
        ContextCompat.startForegroundService(this, intent)
        SubtitleState.isListening.value = true
        updateUIState()
    }

    private fun startServiceDirect(audioMode: String) {
        val intent = Intent(this, SpeechRecognitionService::class.java).apply {
            putExtra(SpeechRecognitionService.EXTRA_AUDIO_MODE, audioMode)
            putExtra(SpeechRecognitionService.EXTRA_LANGUAGE, SubtitleState.sourceLanguage.value)
            putExtra(SpeechRecognitionService.EXTRA_TARGET_LANGUAGE, SubtitleState.targetLanguage.value)
        }
        ContextCompat.startForegroundService(this, intent)
        SubtitleState.isListening.value = true
        updateUIState()
    }

    private fun stopSubtitleService() {
        service?.stopListening()
        SubtitleState.isListening.value = false
        updateUIState()
        binding.subtitleText.text = ""
    }
}
