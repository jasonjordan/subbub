package com.example.realtimesubtitles

import kotlinx.coroutines.flow.MutableStateFlow

object SubtitleState {
    val currentText = MutableStateFlow("")
    val isListening = MutableStateFlow(false)
    val sourceLanguage = MutableStateFlow("")
    val targetLanguage = MutableStateFlow("en")
}
