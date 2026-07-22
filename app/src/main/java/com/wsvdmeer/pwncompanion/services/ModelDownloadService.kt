package com.wsvdmeer.pwncompanion.services

import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import android.util.Log
import com.wsvdmeer.pwncompanion.ai.DownloadProgress
import com.wsvdmeer.pwncompanion.ai.ModelManager
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect

/**
 * Model Download Service - bound service for downloading/updating the AI model.
 *
 * NOTE: this is a plain (non-foreground) bound service and does NOT call
 * startForeground(); it is only safe while the app is in the foreground. The
 * primary download path is ModelDownloadViewModel → ModelManager. If background
 * downloads are needed, promote this to a foreground service (notification +
 * dataSync foregroundServiceType in the manifest) before relying on it.
 */
class ModelDownloadService : Service() {
    private val tag = "ModelDownloadService"
    private val binder = ModelDownloadBinder()
    private val serviceScope = CoroutineScope(Job() + Dispatchers.Main)
    private lateinit var modelManager: ModelManager

    // Callbacks
    private var onProgressListener: ((DownloadProgress) -> Unit)? = null

    override fun onCreate() {
        super.onCreate()
        Log.d(tag, "Service created")
        modelManager = ModelManager(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(tag, "onStartCommand called")

        when (intent?.action) {
            ACTION_DOWNLOAD_MODEL -> {
                Log.d(tag, "Starting model download...")
                downloadModelInBackground()
            }
            ACTION_UPDATE_MODEL -> {
                Log.d(tag, "Starting model update...")
                updateModelInBackground()
            }
        }

        // One-shot download job — don't have the OS restart us with a null intent.
        return START_NOT_STICKY
    }

    /**
     * Download model in background
     */
    private fun downloadModelInBackground() {
        serviceScope.launch {
            try {
                modelManager.downloadModel().collect { progress ->
                    onProgressListener?.invoke(progress)

                    when (progress) {
                        is DownloadProgress.Success -> {
                            Log.i(tag, "Download successful")
                        }
                        is DownloadProgress.Failed -> {
                            Log.e(tag, "Download failed: ${progress.error}")
                        }
                        else -> {
                            Log.d(tag, "Download progress: $progress")
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(tag, "Error during download: ${e.message}", e)
                onProgressListener?.invoke(DownloadProgress.Failed(e.message ?: "Unknown error"))
            }
        }
    }

    /**
     * Update model in background
     */
    private fun updateModelInBackground() {
        serviceScope.launch {
            try {
                // Clear old model
                modelManager.clearModel()

                // Download new version
                modelManager.downloadModel().collect { progress ->
                    onProgressListener?.invoke(progress)
                }
            } catch (e: Exception) {
                Log.e(tag, "Error during update: ${e.message}", e)
                onProgressListener?.invoke(DownloadProgress.Failed(e.message ?: "Unknown error"))
            }
        }
    }

    /**
     * Set progress listener
     */
    fun setProgressListener(listener: (DownloadProgress) -> Unit) {
        onProgressListener = listener
    }

    override fun onBind(intent: Intent?): IBinder {
        return binder
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
        Log.d(tag, "Service destroyed")
    }

    /**
     * Binder for local connections
     */
    inner class ModelDownloadBinder : Binder() {
        fun getService(): ModelDownloadService = this@ModelDownloadService
    }

    companion object {
        const val ACTION_DOWNLOAD_MODEL = "com.wsvdmeer.pwncompanion.ACTION_DOWNLOAD_MODEL"
        const val ACTION_UPDATE_MODEL = "com.wsvdmeer.pwncompanion.ACTION_UPDATE_MODEL"
    }
}

