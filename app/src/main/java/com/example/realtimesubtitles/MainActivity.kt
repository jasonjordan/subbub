package com.example.realtimesubtitles

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
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

    private val overlaySettingsLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        // User returned from overlay settings
        if (Settings.canDrawOverlays(this)) {
            tryStartAfterPermissions()
        } else {
            Toast.makeText(this, "Overlay permission is required to show subtitles over other apps", Toast.LENGTH_LONG).show()
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

    private fun setupSpinners() {
        // Audio source
        val audioSources = listOf("System Audio" to SpeechRecognitionService.AUDIO_MODE_SYSTEM, "Microphone" to SpeechRecognitionService.AUDIO_MODE_MIC)
        val audioAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, audioSources.map { it.first })
        audioAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        binding.audioSourceSpinner.adapter = audioAdapter

        // Languages
        val languages = listOf(
            "Auto-detect / English" to "en",
            "Spanish" to "es",
            "French" to "fr",
            "German" to "de",
            "Italian" to "it",
            "Portuguese" to "pt",
            "Russian" to "ru",
            "Chinese" to "zh"
        )
        val langAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, languages.map { it.first })
        langAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        binding.languageSpinner.adapter = langAdapter

        binding.languageSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                SubtitleState.sourceLanguage.value = languages[position].second
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
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
    }

    private fun observeSubtitles() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                SubtitleState.currentText.collect { text ->
                    binding.subtitleText.text = text
                    binding.subtitleText.visibility = if (text.isBlank()) View.GONE else View.VISIBLE
                }
            }
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                SubtitleState.isListening.collect { updateUIState() }
            }
        }
    }

    private fun updateUIState() {
        val listening = SubtitleState.isListening.value
        binding.startButton.visibility = if (listening) View.GONE else View.VISIBLE
        binding.helpButton.visibility = if (listening) View.GONE else View.VISIBLE
        binding.stopButton.visibility = if (listening) View.VISIBLE else View.GONE
        binding.languageSpinner.isEnabled = !listening
        binding.audioSourceSpinner.isEnabled = !listening
    }

    private fun tryStartAfterPermissions() {
        if (SubtitleState.isListening.value) return

        // 1. Overlay permission
        if (!Settings.canDrawOverlays(this)) {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
            overlaySettingsLauncher.launch(intent)
            return
        }

        // 2. Microphone permission (required for both modes on modern Android)
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
        val lang = SubtitleState.sourceLanguage.value
        val intent = Intent(this, SpeechRecognitionService::class.java).apply {
            putExtra(SpeechRecognitionService.EXTRA_AUDIO_MODE, SpeechRecognitionService.AUDIO_MODE_SYSTEM)
            putExtra(SpeechRecognitionService.EXTRA_LANGUAGE, lang)
            putExtra(SpeechRecognitionService.EXTRA_PROJECTION_DATA, data)
            putExtra(SpeechRecognitionService.EXTRA_PROJECTION_RESULT_CODE, resultCode)
        }
        ContextCompat.startForegroundService(this, intent)
        SubtitleState.isListening.value = true
        updateUIState()
    }

    private fun startServiceDirect(audioMode: String) {
        val lang = SubtitleState.sourceLanguage.value
        val intent = Intent(this, SpeechRecognitionService::class.java).apply {
            putExtra(SpeechRecognitionService.EXTRA_AUDIO_MODE, audioMode)
            putExtra(SpeechRecognitionService.EXTRA_LANGUAGE, lang)
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
