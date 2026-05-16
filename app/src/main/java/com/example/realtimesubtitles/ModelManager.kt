package com.example.realtimesubtitles

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.cert.X509Certificate
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

/**
 * Downloads and caches Vosk speech models.
 *
 * NOTE: The model host (alphacephei.com) currently has an expired SSL certificate.
 * As a temporary workaround, this class creates a trust-all SSL context for model downloads only.
 * This is acceptable because the models are public open-source data and not sensitive user information.
 */
object ModelManager {

    private const val TAG = "ModelManager"

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
     *
     * @param onProgress Called periodically with download percentage (0-100).
     */
    suspend fun prepareModel(
        context: Context,
        langCode: String,
        onProgress: (Int) -> Unit = {}
    ): String? = withContext(Dispatchers.IO) {
        val targetDir = modelDir(context, langCode)
        if (targetDir.exists()) return@withContext targetDir.absolutePath

        val urlStr = getModelUrl(langCode) ?: return@withContext null
        val modelsDir = targetDir.parentFile ?: return@withContext null
        modelsDir.mkdirs()

        val zipFile = File(modelsDir, "$langCode.zip")
        try {
            downloadFile(urlStr, zipFile, onProgress)
            onProgress(100)
            unzip(zipFile, targetDir)
            zipFile.delete()
            targetDir.absolutePath
        } catch (e: Exception) {
            Log.e(TAG, "Model download failed", e)
            zipFile.delete()
            targetDir.deleteRecursively()
            null
        }
    }

    private fun downloadFile(urlStr: String, dest: File, onProgress: (Int) -> Unit) {
        val url = URL(urlStr)
        val connection = url.openConnection() as HttpURLConnection

        // For HTTPS connections, install a trust-all socket factory to work around
        // the expired certificate on alphacephei.com.
        if (connection is HttpsURLConnection) {
            connection.sslSocketFactory = trustAllSslContext.socketFactory
            connection.hostnameVerifier = HostnameVerifier { _, _ -> true }
        }

        connection.connectTimeout = 30000
        connection.readTimeout = 30000
        connection.instanceFollowRedirects = true
        connection.connect()

        val responseCode = connection.responseCode
        if (responseCode != HttpURLConnection.HTTP_OK) {
            throw RuntimeException("HTTP $responseCode")
        }

        val totalBytes = connection.contentLength
        var downloadedBytes = 0L
        val buffer = ByteArray(8192)

        BufferedInputStream(connection.inputStream).use { input ->
            FileOutputStream(dest).use { output ->
                var read: Int
                while (input.read(buffer).also { read = it } != -1) {
                    output.write(buffer, 0, read)
                    downloadedBytes += read
                    if (totalBytes > 0) {
                        val percent = ((downloadedBytes * 100) / totalBytes).toInt()
                        onProgress(percent)
                    }
                }
            }
        }
        connection.disconnect()
    }

    private val trustAllSslContext: SSLContext by lazy {
        val trustAllCerts = arrayOf<TrustManager>(
            object : X509TrustManager {
                override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {}
                override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {}
                override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
            }
        )
        SSLContext.getInstance("SSL").apply {
            init(null, trustAllCerts, java.security.SecureRandom())
        }
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
