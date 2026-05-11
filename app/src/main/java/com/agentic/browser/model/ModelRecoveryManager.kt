package com.agentic.browser.model

import android.content.Context
import android.net.Uri
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

sealed interface ModelImportState {
    data object Idle : ModelImportState
    data class Importing(val progressPercent: Int, val copiedBytes: Long, val totalBytes: Long) : ModelImportState
    data object Validating : ModelImportState
    data class Ready(val modelPath: String) : ModelImportState
    data class Error(val message: String) : ModelImportState
}

class ModelRecoveryManager(private val context: Context) {
    private val _state = MutableStateFlow<ModelImportState>(ModelImportState.Idle)
    val state: StateFlow<ModelImportState> = _state.asStateFlow()

    suspend fun importModelFromUri(uri: Uri, displayName: String? = null): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val resolver = context.contentResolver
            val baseName = sanitizeFileName(displayName ?: uri.lastPathSegment ?: "recovered-model.litertlm")
            val fileName = ensureAllowedExtension(baseName)
                ?: throw IOException("Unsupported model extension. Use .litertlm, .task, or .bin")

            val modelsDir = File(context.filesDir, "models")
            if (!modelsDir.exists() && !modelsDir.mkdirs()) {
                throw IOException("Unable to create models directory")
            }

            val targetFile = File(modelsDir, fileName)
            val partFile = File(modelsDir, "$fileName.part")
            if (partFile.exists()) partFile.delete()

            val totalBytes = resolver.openAssetFileDescriptor(uri, "r")?.use { it.length } ?: -1L
            var copiedBytes = 0L
            resolver.openInputStream(uri)?.use { input ->
                FileOutputStream(partFile).use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        copiedBytes += read.toLong()
                        val progress = if (totalBytes > 0L) ((copiedBytes * 100L) / totalBytes).toInt().coerceIn(0, 100) else -1
                        _state.value = ModelImportState.Importing(progress, copiedBytes, totalBytes)
                    }
                    output.flush()
                }
            } ?: throw IOException("Unable to open selected model URI")

            _state.value = ModelImportState.Validating
            if (!validateStagedModel(
                    stagedFile = partFile,
                    finalFile = targetFile,
                    copiedBytes = copiedBytes,
                    expectedTotalBytes = totalBytes
                )
            ) {
                partFile.delete()
                throw IOException("Imported model failed validation")
            }

            if (targetFile.exists() && !targetFile.delete()) {
                partFile.delete()
                throw IOException("Unable to replace existing recovered model")
            }
            if (!partFile.renameTo(targetFile)) {
                partFile.delete()
                throw IOException("Failed to finalize recovered model")
            }

            _state.value = ModelImportState.Ready(targetFile.absolutePath)
            targetFile.absolutePath
        }.onFailure { error ->
            _state.value = ModelImportState.Error(error.message ?: "Model import failed")
        }
    }

    fun getRecoveredModels(): List<File> {
        val modelsDir = File(context.filesDir, "models")
        if (!modelsDir.exists()) return emptyList()
        return modelsDir.listFiles()
            ?.filter { it.isFile && validateModel(it) }
            ?.sortedByDescending { it.lastModified() }
            .orEmpty()
    }

    fun validateModel(file: File): Boolean {
        return validateStagedModel(
            stagedFile = file,
            finalFile = file,
            copiedBytes = file.length(),
            expectedTotalBytes = -1L
        )
    }

    fun deleteRecoveredModel(path: String) {
        val target = File(path)
        if (target.exists()) target.delete()
        val partFile = File(target.parentFile ?: File(context.filesDir, "models"), "${target.name}.part")
        if (partFile.exists()) partFile.delete()
        _state.value = ModelImportState.Idle
    }

    private fun sanitizeFileName(name: String): String {
        val cleaned = name.trim().ifBlank { "recovered-model.litertlm" }
        val dotIndex = cleaned.lastIndexOf('.')
        if (dotIndex <= 0 || dotIndex == cleaned.lastIndex) {
            return cleaned.replace(Regex("[^A-Za-z0-9._-]"), "_").take(80)
        }

        val base = cleaned.substring(0, dotIndex).replace(Regex("[^A-Za-z0-9._-]"), "_").ifBlank { "recovered-model" }
        val extension = cleaned.substring(dotIndex).replace(Regex("[^A-Za-z0-9.]"), "")
        val maxBaseLength = (80 - extension.length).coerceAtLeast(1)
        return base.take(maxBaseLength) + extension
    }

    private fun ensureAllowedExtension(name: String): String? {
        val lower = name.lowercase()
        if (hasAllowedExtension(lower)) return name
        return null
    }

    private fun hasAllowedExtension(name: String): Boolean {
        val lower = name.lowercase()
        return lower.endsWith(".litertlm") || lower.endsWith(".task") || lower.endsWith(".bin")
    }

    private fun validateStagedModel(
        stagedFile: File,
        finalFile: File,
        copiedBytes: Long,
        expectedTotalBytes: Long
    ): Boolean {
        val finalPath = finalFile.absolutePath
        val finalFileName = finalFile.name
        val finalFileSizeBytes = if (stagedFile.exists() && stagedFile.isFile) stagedFile.length() else -1L
        val extensionDetected = finalFileName.substringAfterLast('.', "")
        val minimumRequiredBytes = MIN_MODEL_BYTES
        val reasons = mutableListOf<String>()

        if (!stagedFile.exists()) reasons += "file_missing"
        if (stagedFile.exists() && !stagedFile.isFile) reasons += "not_a_file"
        if (stagedFile.exists() && stagedFile.isFile && !stagedFile.canRead()) reasons += "file_not_readable"
        if (!hasAllowedExtension(finalFileName)) reasons += "unsupported_extension"
        if (stagedFile.exists() && stagedFile.isFile && finalFileSizeBytes < minimumRequiredBytes) reasons += "below_minimum_size"
        if (expectedTotalBytes > 0L && copiedBytes != expectedTotalBytes) reasons += "copied_bytes_mismatch"

        val isValid = reasons.isEmpty()
        val validationLog =
            "Model validation ${if (isValid) "passed" else "failed"}: " +
                "finalPath=$finalPath, " +
                "finalFileName=$finalFileName, " +
                "finalFileSizeBytes=$finalFileSizeBytes, " +
                "extensionDetected=$extensionDetected, " +
                "minimumRequiredBytes=$minimumRequiredBytes, " +
                "copiedBytes=$copiedBytes, " +
                "expectedTotalBytes=${if (expectedTotalBytes > 0L) expectedTotalBytes else "unknown"}, " +
                "reasons=${if (reasons.isEmpty()) "none" else reasons.joinToString(",")}"

        if (isValid) {
            Log.i(TAG, validationLog)
        } else {
            Log.w(TAG, validationLog)
        }
        return isValid
    }

    companion object {
        private const val MIN_MODEL_BYTES = 1024L * 1024L
        private const val TAG = "ModelRecoveryManager"
    }
}
