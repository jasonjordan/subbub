package com.jasonjordan.subbub

import kotlinx.coroutines.flow.MutableStateFlow

object SubtitleState {
    val currentText = MutableStateFlow("")
    val isListening = MutableStateFlow(false)
    val sourceLanguage = MutableStateFlow("")
    val targetLanguage = MutableStateFlow("en")
    val downloadProgress = MutableStateFlow(-1) // -1 = not downloading, 0-100 = progress
    val downloadStatus = MutableStateFlow("")
}
