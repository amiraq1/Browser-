package com.agentic.browser.model

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.TimeUnit

sealed interface ModelDownloadState {
    data object Idle : ModelDownloadState
    data class Downloading(val progressPercent: Int, val downloadedBytes: Long, val totalBytes: Long) : ModelDownloadState
    data object Extracting : ModelDownloadState
    data class Ready(val modelPath: String) : ModelDownloadState
    data class Error(val message: String) : ModelDownloadState
}

class ModelDownloader(private val context: Context) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.MINUTES)
        .build()
    private val _state = MutableStateFlow<ModelDownloadState>(ModelDownloadState.Idle)
    val state: StateFlow<ModelDownloadState> = _state.asStateFlow()
    private var downloadJob: Job? = null

    fun downloadModel(url: String, outputFileName: String) {
        if (url.isBlank()) {
            _state.value = ModelDownloadState.Error("Model URL is blank.")
            return
        }
        if (!isValidRemoteUrl(url)) {
            _state.value = ModelDownloadState.Error("Model URL is invalid.")
            return
        }
        val fileName = outputFileName.trim()
        if (fileName.isBlank()) {
            _state.value = ModelDownloadState.Error("Output filename is blank.")
            return
        }
        if (!hasValidModelExtension(fileName)) {
            _state.value = ModelDownloadState.Error("Output filename extension must be .litertlm, .task, or .bin.")
            return
        }

        downloadJob?.cancel()
        downloadJob = scope.launch {
            val targetFile = File(context.filesDir, fileName)
            val partFile = File(context.filesDir, "$fileName.part")
            runCatching {
                if (partFile.exists()) partFile.delete()
                val request = Request.Builder().url(url).get().build()
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
                    val body = response.body ?: throw IOException("Empty response body")
                    val totalBytes = body.contentLength().coerceAtLeast(-1L)
                    body.byteStream().use { input ->
                        FileOutputStream(partFile).use { output ->
                            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                            var downloaded = 0L
                            while (true) {
                                val read = input.read(buffer)
                                if (read < 0) break
                                output.write(buffer, 0, read)
                                downloaded += read
                                val progress = if (totalBytes > 0L) ((downloaded * 100) / totalBytes).toInt().coerceIn(0, 100) else -1
                                _state.value = ModelDownloadState.Downloading(progress, downloaded, totalBytes)
                            }
                            output.flush()
                        }
                    }
                }

                _state.value = ModelDownloadState.Extracting
                if (!validateModel(partFile)) throw IOException("Downloaded model failed validation.")

                if (targetFile.exists() && !targetFile.delete()) {
                    throw IOException("Unable to replace existing model file.")
                }
                if (!partFile.renameTo(targetFile)) {
                    throw IOException("Failed to finalize model file.")
                }
                _state.value = ModelDownloadState.Ready(targetFile.absolutePath)
            }.onFailure { error ->
                if (partFile.exists()) partFile.delete()
                _state.value = ModelDownloadState.Error(error.message ?: "Model download failed.")
            }
        }
    }

    fun cancelDownload() {
        scope.launch {
            downloadJob?.cancelAndJoin()
            downloadJob = null
            File(context.filesDir, ".").listFiles()?.forEach { file ->
                if (file.name.endsWith(".part")) file.delete()
            }
            _state.value = ModelDownloadState.Idle
        }
    }

    fun getExistingModelPath(fileName: String): String? {
        val file = File(context.filesDir, fileName.trim())
        return if (validateModel(file)) file.absolutePath else null
    }

    fun getExistingModelPathFromAbsolute(path: String?): String? {
        val file = path?.trim()?.takeIf { it.isNotBlank() }?.let(::File) ?: return null
        return if (validateModel(file)) file.absolutePath else null
    }

    fun deleteModel(fileName: String) {
        val target = File(context.filesDir, fileName.trim())
        if (target.exists()) target.delete()
        val part = File(context.filesDir, "${fileName.trim()}.part")
        if (part.exists()) part.delete()
        _state.value = ModelDownloadState.Idle
    }

    fun deleteModelAtPath(path: String?) {
        val target = path?.trim()?.takeIf { it.isNotBlank() }?.let(::File) ?: return
        if (target.exists()) target.delete()
        val part = File(target.parentFile ?: context.filesDir, "${target.name}.part")
        if (part.exists()) part.delete()
        _state.value = ModelDownloadState.Idle
    }

    fun validateModel(file: File): Boolean {
        if (!file.exists() || !file.isFile) return false
        if (!hasValidModelExtension(file.name)) return false
        return file.length() > MIN_MODEL_BYTES
    }

    private fun hasValidModelExtension(name: String): Boolean {
        val lower = name.lowercase()
        return lower.endsWith(".litertlm") || lower.endsWith(".task") || lower.endsWith(".bin")
    }

    private fun isValidRemoteUrl(url: String): Boolean {
        val lower = url.lowercase()
        return lower.startsWith("https://") || lower.startsWith("http://")
    }

    companion object {
        private const val MIN_MODEL_BYTES = 1024L * 1024L
    }
}
