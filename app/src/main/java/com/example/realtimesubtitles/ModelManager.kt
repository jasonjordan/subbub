package com.example.realtimesubtitles

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream

/**
 * Downloads and caches Vosk speech models.
 */
object ModelManager {

    private fun getModelUrl(langCode: String): String? = when (langCode.take(2).lowercase()) {
        "en" -> "https://alphacephei.com/vosk/models/vosk-model-small-en-us-0.15.zip"
        "es" -> "https://alphacephei.com/vosk/models/vosk-model-small-es-0.42.zip"
        "fr" -> "https://alphacephei.com/vosk/models/vosk-model-small-fr-0.22.zip"
        "de" -> "https://alphacephei.com/vosk/models/vosk-model-small-de-0.15.zip"
        "it" -> "https://alphacephei.com/vosk/models/vosk-model-small-it-0.22.zip"
        "pt" -> "https://alphacephei.com/vosk/models/vosk-model-small-pt-0.3.zip"
        "ru" -> "https://alphacephei.com/vosk/models/vosk-model-small-ru-0.22.zip"
        "zh" -> "https://alphacephei.com/vosk/models/vosk-model-small-cn-0.22.zip"
        "pl" -> "https://alphacephei.com/vosk/models/vosk-model-small-pl-0.22.zip"
        "ja" -> null
        "ko" -> null
        "ar" -> null
        "hi" -> null
        else -> null
    }

    fun modelDir(context: Context, langCode: String): File {
        val modelsDir = File(context.getExternalFilesDir(null), "vosk-models")
        return File(modelsDir, "model-$langCode")
    }

    fun isModelAvailable(context: Context, langCode: String): Boolean {
        return modelDir(context, langCode).exists()
    }

    /**
     * Downloads and unzips the model if not already cached.
     * Returns the model directory path, or null if unavailable/failed.
     */
    suspend fun prepareModel(context: Context, langCode: String): String? = withContext(Dispatchers.IO) {
        val targetDir = modelDir(context, langCode)
        if (targetDir.exists()) return@withContext targetDir.absolutePath

        val urlStr = getModelUrl(langCode) ?: return@withContext null
        val modelsDir = targetDir.parentFile ?: return@withContext null
        modelsDir.mkdirs()

        val zipFile = File(modelsDir, "$langCode.zip")
        try {
            downloadFile(urlStr, zipFile)
            unzip(zipFile, targetDir)
            zipFile.delete()
            targetDir.absolutePath
        } catch (e: Exception) {
            e.printStackTrace()
            zipFile.delete()
            targetDir.deleteRecursively()
            null
        }
    }

    private fun downloadFile(urlStr: String, dest: File) {
        val url = URL(urlStr)
        val connection = url.openConnection() as HttpURLConnection
        connection.connectTimeout = 30000
        connection.readTimeout = 30000
        connection.connect()

        BufferedInputStream(connection.inputStream).use { input ->
            FileOutputStream(dest).use { output ->
                input.copyTo(output)
            }
        }
        connection.disconnect()
    }

    private fun unzip(zipFile: File, destDir: File) {
        ZipInputStream(FileInputStream(zipFile)).use { zis ->
            var entry: ZipEntry?
            while (zis.nextEntry.also { entry = it } != null) {
                val newFile = File(destDir, entry!!.name)
                if (entry!!.isDirectory) {
                    newFile.mkdirs()
                } else {
                    newFile.parentFile?.mkdirs()
                    FileOutputStream(newFile).use { fos ->
                        zis.copyTo(fos)
                    }
                }
            }
        }
    }
}
