package com.example.realtimesubtitles

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

object SubtitleState {
    val currentText = MutableStateFlow("")
    val isListening = MutableStateFlow(false)
    val sourceLanguage = MutableStateFlow("")
}
