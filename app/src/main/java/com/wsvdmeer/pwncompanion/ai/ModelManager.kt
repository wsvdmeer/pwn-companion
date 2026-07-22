package com.wsvdmeer.pwncompanion.ai

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * ModelManager - Centralized model lifecycle management
 *
 * Model: Qwen2-0.5B-Instruct Q4_K_M GGUF (~350 MB)
 * Source: https://huggingface.co/bartowski/Qwen2-0.5B-Instruct-GGUF
 * Format: GGUF — loaded by llama.cpp JNI (see GgufInference.kt)
 * No HuggingFace token required — this is a free, ungated model.
 *
 * Inference engine: llama.cpp via NDK/CMake (app/src/main/cpp/)
 *
 * Early prototypes tried a few engines/models before settling on llama.cpp; the current
 * model (v12) is Qwen2.5-0.5B-Instruct Q4_K_M GGUF — a small, free, ungated model chosen
 * for its tiny footprint, decent instruction-following, and low persona-refusal.
 */

data class ModelMetadata(
    val name: String = "qwen2.5-0.5b-instruct-q4_k_m.gguf",
    val version: Int = 12,
    val sizeBytes: Long = 491_000_000,
    val downloadUrl: String = "https://huggingface.co/Qwen/Qwen2.5-0.5B-Instruct-GGUF/resolve/main/qwen2.5-0.5b-instruct-q4_k_m.gguf?download=true"
)

class ModelManager(private val context: Context) {
    private val tag = "ModelManager"

    /** Filename as stored on disk */
    val modelName = "qwen2.5-0.5b-instruct-q4_k_m.gguf"

    /** Human-friendly display name for UI */
    val displayName = "Qwen2.5 0.5B"

    /** Current model schema version — bump when switching models.
     *  Must match [ModelMetadata.version]; needsUpdate() and saveModelVersion()
     *  both key off this, and the migration log reports metadata.version. */
    val modelVersion = 12

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    /**
     * Model storage directory — uses filesDir, NOT cacheDir.
     *
     * llama.cpp loads the model file via mmap(). On many Android devices the cache
     * partition is mounted with flags that block mmap reads, so we keep the model in
     * filesDir — same /data partition, without those restrictions, and the documented
     * location for large persistent app data files.
     */
    val modelCacheDir: File
        get() = File(context.filesDir, "models").apply { mkdirs() }

    val modelPath: String
        get() = File(modelCacheDir, modelName).absolutePath

    private val versionFile: File
        get() = File(modelCacheDir, "model_version.txt")

    /**
     * Move any existing model from the old cacheDir storage to filesDir.
     * Safe to call repeatedly — no-op if nothing to migrate.
     *
     * MUST be invoked off the main thread: the copy+delete fallback (cross-filesystem
     * rename failure) copies a ~350 MB file, which would ANR if run during onCreate.
     * Previously this ran from an `init {}` block, so simply touching ModelManager on
     * the main thread could trigger the copy — that constructor side-effect is gone;
     * callers now run this explicitly on Dispatchers.IO.
     */
    fun migrateFromCacheDir() {
        val oldDir = File(context.cacheDir, "models")
        if (!oldDir.exists()) return
        val newDir = modelCacheDir
        oldDir.listFiles()?.forEach { oldFile ->
            val newFile = File(newDir, oldFile.name)
            if (newFile.exists()) return@forEach          // already migrated
            val moved = oldFile.renameTo(newFile)
            if (moved) {
                Log.i(tag, "Migrated ${oldFile.name} → filesDir (instant rename)")
            } else {
                // renameTo can fail if cross-filesystem; copy then delete as fallback
                try {
                    oldFile.copyTo(newFile, overwrite = false)
                    oldFile.delete()
                    Log.i(tag, "Migrated ${oldFile.name} → filesDir (copy+delete)")
                } catch (e: Exception) {
                    Log.w(tag, "Could not migrate ${oldFile.name}: ${e.message}")
                }
            }
        }
        // Remove old directory if now empty
        if (oldDir.listFiles().isNullOrEmpty()) oldDir.delete()
    }


    /**
     * Minimum file size to be considered a valid (complete) model.
     * Qwen2-0.5B Q4_K_M is ~350 MB; anything under 250 MB is corrupt/incomplete.
     */
    private val minModelBytes = 250_000_000L  // 250 MB

    /**
     * Minimum size to consider an in-progress download resumable.
     */
    private val minResumeBytes = 1_000_000L   // 1 MB

    fun isModelAvailable(): Boolean {
        val file = File(modelPath)
        return file.exists() && file.length() >= minModelBytes
    }

    fun needsUpdate(): Boolean {
        if (!isModelAvailable()) return true
        return try {
            (versionFile.readText().toIntOrNull() ?: 0) < modelVersion
        } catch (e: Exception) {
            true
        }
    }

    fun getCurrentModelVersion(): Int = try {
        versionFile.readText().toIntOrNull() ?: 0
    } catch (e: Exception) { 0 }

    fun getModelSize(): Long = File(modelPath).takeIf { it.exists() }?.length() ?: 0L

    fun getAvailableDiskSpace(): Long = try {
        val stat = android.os.StatFs(modelCacheDir.absolutePath)
        stat.availableBlocksLong * stat.blockSizeLong
    } catch (e: Exception) { 0 }

    /**
     * Download model from HuggingFace with byte-level progress.
     * The current model is free and ungated, so [hfToken] is normally null; it's a legacy
     * hook that adds a HuggingFace bearer token when provided (for gated models).
     */
    fun downloadModel(metadata: ModelMetadata = ModelMetadata(), hfToken: String? = null): Flow<DownloadProgress> = channelFlow {
        send(DownloadProgress.Starting)

        withContext(Dispatchers.IO) {
            // Sweep stale model files from previous versions (different filename, e.g. the
            // old Qwen2 .gguf) so switching models doesn't leave a ~350 MB orphan. Keep any
            // partial download of the CURRENT target so resume still works.
            modelCacheDir.listFiles()
                ?.filter { it.extension == "gguf" && it.name != modelName }
                ?.forEach {
                    if (it.delete()) Log.i(tag, "Removed stale model file ${it.name}")
                }

            // Migration: delete old model if version mismatch
            if (isModelAvailable() && needsUpdate()) {
                Log.i(tag, "Migrating from v${getCurrentModelVersion()} → v${metadata.version}: deleting old model")
                clearModel()
            }

            val modelFile = File(modelPath)
            val existingBytes = modelFile.length()
            val resumeBytes = when {
                !modelFile.exists()             -> 0L
                existingBytes < minResumeBytes  -> {
                    Log.w(tag, "Deleting corrupt/incomplete model file (${existingBytes} bytes < ${minResumeBytes} min)")
                    modelFile.delete()
                    0L
                }
                else -> existingBytes
            }

            val requestBuilder = Request.Builder().url(metadata.downloadUrl)
            // Legacy: send a HuggingFace bearer token if one was provided (gated models).
            if (!hfToken.isNullOrBlank()) {
                requestBuilder.addHeader("Authorization", "Bearer $hfToken")
            }
            if (resumeBytes > 0) {
                Log.d(tag, "Resuming download from byte $resumeBytes")
                requestBuilder.addHeader("Range", "bytes=$resumeBytes-")
            }

            try {
                val response = httpClient.newCall(requestBuilder.build()).execute()

                when (response.code) {
                    401 -> {
                        send(DownloadProgress.Failed("HTTP 401 — HuggingFace token invalid or missing. Generate a token at huggingface.co/settings/tokens"))
                        return@withContext
                    }
                    403 -> {
                        send(DownloadProgress.Failed("HTTP 403 — the server denied the model download."))
                        return@withContext
                    }
                }

                if (!response.isSuccessful && response.code != 206) {
                    send(DownloadProgress.Failed("HTTP ${response.code}: ${response.message}"))
                    return@withContext
                }

                val body = response.body ?: run {
                    send(DownloadProgress.Failed("Empty response body"))
                    return@withContext
                }

                // Only resume (append) when the server actually honored the Range
                // request with 206 Partial Content. If it returned 200 it's sending
                // the whole file, so we must truncate and start from zero — appending
                // a full copy onto the partial file would corrupt the model.
                val append = resumeBytes > 0 && response.code == 206
                val startBytes = if (append) resumeBytes else 0L

                val contentLength = body.contentLength()
                val totalBytes = if (contentLength > 0) startBytes + contentLength else metadata.sizeBytes

                modelFile.parentFile?.mkdirs()
                // Open exactly one stream in the correct mode. The previous code eagerly
                // evaluated modelFile.outputStream() (truncate mode) before deciding to
                // append, which zeroed the partial file — and leaked that stream — on
                // every resume, guaranteeing a corrupt download.
                val outputStream = java.io.FileOutputStream(modelFile, append)

                outputStream.use { out ->
                    body.byteStream().use { input ->
                        val buffer = ByteArray(16 * 1024) // 16 KB buffer
                        var downloaded = startBytes
                        var bytes: Int

                        while (input.read(buffer).also { bytes = it } != -1) {
                            out.write(buffer, 0, bytes)
                            downloaded += bytes
                            send(DownloadProgress.Downloading(downloaded, totalBytes))
                        }
                    }
                }

                saveModelVersion()
                Log.i(tag, "Download complete: $modelPath (${modelFile.length()} bytes)")
                send(DownloadProgress.Success(modelPath))

            } catch (e: Exception) {
                Log.e(tag, "Download failed: ${e.message}", e)
                send(DownloadProgress.Failed(e.message ?: "Unknown error"))
            }
        }
    }

    /**
     * Clear model and version files (for updates/cleanup)
     */
    fun clearModel() {
        try {
            // Delete the current model file by its known path first
            File(modelPath).takeIf { it.exists() }?.let {
                it.delete()
                Log.d(tag, "Deleted current model: ${it.name}")
            }
            // Also sweep for any legacy model files from previous versions (.gguf, .task, .bin)
            modelCacheDir.listFiles()
                ?.filter { it.extension in listOf("gguf", "task", "bin") }
                ?.forEach {
                    it.delete()
                    Log.d(tag, "Deleted legacy model: ${it.name}")
                }
            versionFile.delete()
            Log.d(tag, "Model cleared")
        } catch (e: Exception) {
            Log.e(tag, "Failed to clear model: ${e.message}")
        }
    }

    /**
     * Save model version to file
     */
    private fun saveModelVersion() {
        try {
            versionFile.writeText(modelVersion.toString())
        } catch (e: Exception) {
            Log.e(tag, "Failed to save model version: ${e.message}")
        }
    }
}

sealed class DownloadProgress {
    object Starting : DownloadProgress()
    data class Downloading(val bytesCurrent: Long, val bytesTotal: Long) : DownloadProgress() {
        val progressPercent: Int get() = if (bytesTotal > 0) (bytesCurrent * 100 / bytesTotal).toInt() else 0
        val progressFloat: Float get() = progressPercent / 100f
    }
    data class Success(val modelPath: String) : DownloadProgress()
    data class Failed(val error: String) : DownloadProgress()
    object Cancelled : DownloadProgress()
}

