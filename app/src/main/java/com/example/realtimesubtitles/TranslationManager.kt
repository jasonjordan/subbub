package com.example.realtimesubtitles

import android.content.Context
import android.util.Log
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.TranslatorOptions
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class TranslationManager(context: Context) {

    private val translators = mutableMapOf<Pair<String, String>, com.google.mlkit.nl.translate.Translator>()
    private val mutex = Mutex()

    suspend fun translate(text: String, sourceLangCode: String, targetLangCode: String): String {
        if (text.isBlank()) return text
        if (sourceLangCode == targetLangCode) return text

        val sourceLang = mapToMlKitLanguage(sourceLangCode)
        val targetLang = mapToMlKitLanguage(targetLangCode)

        if (sourceLang == null || targetLang == null) {
            Log.w("TranslationManager", "Unsupported language pair: $sourceLangCode -> $targetLangCode")
            return text
        }

        val key = sourceLang to targetLang
        val translator = mutex.withLock {
            translators.getOrPut(key) {
                val options = TranslatorOptions.Builder()
                    .setSourceLanguage(sourceLang)
                    .setTargetLanguage(targetLang)
                    .build()
                Translation.getClient(options)
            }
        }

        return try {
            val conditions = DownloadConditions.Builder()
                .requireWifi()
                .build()
            translator.downloadModelIfNeeded(conditions).await()
            translator.translate(text).await()
        } catch (e: Exception) {
            Log.e("TranslationManager", "Translation failed", e)
            text
        }
    }

    private fun mapToMlKitLanguage(code: String): String? {
        return when (code.lowercase().take(2)) {
            "en" -> TranslateLanguage.ENGLISH
            "es" -> TranslateLanguage.SPANISH
            "fr" -> TranslateLanguage.FRENCH
            "de" -> TranslateLanguage.GERMAN
            "it" -> TranslateLanguage.ITALIAN
            "pt" -> TranslateLanguage.PORTUGUESE
            "ru" -> TranslateLanguage.RUSSIAN
            "zh" -> TranslateLanguage.CHINESE
            "ja" -> TranslateLanguage.JAPANESE
            "ko" -> TranslateLanguage.KOREAN
            "ar" -> TranslateLanguage.ARABIC
            "hi" -> TranslateLanguage.HINDI
            "pl" -> TranslateLanguage.POLISH
            "nl" -> TranslateLanguage.DUTCH
            "tr" -> TranslateLanguage.TURKISH
            "vi" -> TranslateLanguage.VIETNAMESE
            "th" -> TranslateLanguage.THAI
            "id" -> TranslateLanguage.INDONESIAN
            "cs" -> TranslateLanguage.CZECH
            "el" -> TranslateLanguage.GREEK
            "he" -> TranslateLanguage.HEBREW
            "ro" -> TranslateLanguage.ROMANIAN
            "sv" -> TranslateLanguage.SWEDISH
            "hu" -> TranslateLanguage.HUNGARIAN
            "da" -> TranslateLanguage.DANISH
            "fi" -> TranslateLanguage.FINNISH
            "no" -> TranslateLanguage.NORWEGIAN
            "uk" -> TranslateLanguage.UKRAINIAN
            "bg" -> TranslateLanguage.BULGARIAN
            "hr" -> TranslateLanguage.CROATIAN
            "sk" -> TranslateLanguage.SLOVAK
            "sl" -> TranslateLanguage.SLOVENIAN
            "lt" -> TranslateLanguage.LITHUANIAN
            "lv" -> TranslateLanguage.LATVIAN
            "et" -> TranslateLanguage.ESTONIAN
            "ms" -> TranslateLanguage.MALAY
            "tl" -> TranslateLanguage.FILIPINO
            else -> null
        }
    }
}
