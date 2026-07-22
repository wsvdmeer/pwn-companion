package com.wsvdmeer.pwncompanion.ai

import android.app.Application
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * ModelDownloadViewModel - Manages model download/update UI state
 *
 * States:
 * - CHECKING: Initial check for model availability
 * - DOWNLOADING: Model download in progress
 * - UPDATING: Updating to newer model version
 * - SUCCESS: Model ready, can start app
 * - ERROR: Download/update failed
 * - READY: Model already available, skipped download
 */
class ModelDownloadViewModel(application: Application) : ViewModel() {
    private val modelManager = ModelManager(application)
    private val tag = "ModelDownloadVM"

    // Download state
    private val _downloadState = MutableStateFlow<ModelDownloadState>(ModelDownloadState.Checking)
    val downloadState = _downloadState.asStateFlow()

    private val _progress = MutableStateFlow(0f)
    val progress = _progress.asStateFlow()

    private val _errorMessage = MutableStateFlow("")
    val errorMessage = _errorMessage.asStateFlow()

    /** Non-null when the model download requires a licence click — URL to open in browser */
    private val _licenceUrl = MutableStateFlow<String?>(null)
    val licenceUrl = _licenceUrl.asStateFlow()

    private val _isReady = MutableStateFlow(false)
    val isReady = _isReady.asStateFlow()

    private val _currentStatusText = MutableStateFlow("Checking for model...")
    val currentStatusText = _currentStatusText.asStateFlow()

    /** HuggingFace read token — only needed for gated models. Qwen2 is free, no token required. */
    private val _hfToken = MutableStateFlow("")
    val hfToken = _hfToken.asStateFlow()

    fun setHfToken(token: String) { _hfToken.value = token.trim() }

    init {
        checkAndPrepareModel()
    }

    /**
     * Check if model is available, download if needed
     */
    private fun checkAndPrepareModel() {
        viewModelScope.launch {
            try {
                _downloadState.value = ModelDownloadState.Checking
                _currentStatusText.value = "Checking for AI model..."

                // Check model availability
                when {
                    modelManager.isModelAvailable() && !modelManager.needsUpdate() -> {
                        Log.i(tag, "Model already available and up-to-date")
                        _currentStatusText.value = "AI model ready! 🎭"
                        _downloadState.value = ModelDownloadState.Ready
                        _isReady.value = true
                    }

                    modelManager.needsUpdate() -> {
                        Log.i(tag, "Model needs update (current v${modelManager.getCurrentModelVersion()}, target v${modelManager.modelVersion})")
                        downloadOrUpdateModel(isUpdate = true)
                    }

                    else -> {
                        Log.i(tag, "Model not found, starting download")
                        downloadOrUpdateModel(isUpdate = false)
                    }
                }
            } catch (e: Exception) {
                Log.e(tag, "Error checking model: ${e.message}", e)
                handleError("Failed to check model: ${e.message}")
            }
        }
    }

    /**
     * Download or update model with progress tracking
     */
    private fun downloadOrUpdateModel(isUpdate: Boolean = false) {
        viewModelScope.launch {
            try {
                _downloadState.value = if (isUpdate) ModelDownloadState.Updating else ModelDownloadState.Downloading
                _currentStatusText.value = if (isUpdate) "Updating AI model..." else "Downloading AI model..."

                // Check disk space — Qwen2 Q4_K_M is ~395 MB; require 800 MB free
                val available = modelManager.getAvailableDiskSpace()
                if (available < 800_000_000L) {
                    handleError("Not enough disk space (need ~800 MB free)")
                    return@launch
                }

                // Qwen2 is ungated — no token required
                modelManager.downloadModel().collect { progress ->
                    when (progress) {
                        is DownloadProgress.Starting -> {
                            Log.d(tag, "Download starting")
                            _currentStatusText.value = "Starting download..."
                            _progress.value = 0f
                        }

                        is DownloadProgress.Downloading -> {
                            val percent = progress.progressPercent
                            _progress.value = progress.progressFloat
                            _currentStatusText.value = buildStatusText(
                                percent,
                                progress.bytesCurrent,
                                progress.bytesTotal,
                                isUpdate
                            )
                            Log.d(tag, "Download progress: $percent%")
                        }

                        is DownloadProgress.Success -> {
                            Log.i(tag, "Download successful: ${progress.modelPath}")
                            _progress.value = 1f
                            _currentStatusText.value = "✅ AI model ready!"
                            _downloadState.value = ModelDownloadState.Success
                            _isReady.value = true
                        }

                        is DownloadProgress.Failed -> {
                            Log.e(tag, "Download failed: ${progress.error}")
                            handleError(progress.error)
                        }

                        is DownloadProgress.Cancelled -> {
                            Log.w(tag, "Download cancelled")
                            handleError("Download cancelled")
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(tag, "Error during download: ${e.message}", e)
                handleError("Download error: ${e.message}")
            }
        }
    }

    /**
     * Build human-readable status text with byte counts
     */
    private fun buildStatusText(
        percent: Int,
        current: Long,
        total: Long,
        isUpdate: Boolean
    ): String {
        val currentMB = current / (1024 * 1024)
        val totalMB = total / (1024 * 1024)
        val action = if (isUpdate) "Updating" else "Downloading"
        return "$action: $percent% ($currentMB MB / $totalMB MB)"
    }

    /**
     * Handle download error.
     */
    private fun handleError(message: String) {
        Log.e(tag, "Error: $message")
        _licenceUrl.value = null
        _downloadState.value = ModelDownloadState.Error
        _errorMessage.value = message
        _isReady.value = false
    }

    /**
     * Retry download after error
     */
    fun retryDownload() {
        Log.i(tag, "Retrying download...")
        _errorMessage.value = ""
        _licenceUrl.value = null
        _progress.value = 0f
        checkAndPrepareModel()
    }

    /**
     * Called by the UI after the user has opened the HuggingFace licence page and tapped
     * "I've accepted the licence — retry download".
     */
    fun retryAfterLicence() {
        Log.i(tag, "Retrying download after licence acceptance...")
        _licenceUrl.value = null
        _progress.value = 0f
        checkAndPrepareModel()
    }

    /**
     * Skip download and continue (if model somehow available despite error)
     */
    fun skipDownload() {
        Log.w(tag, "User skipped download")
        _isReady.value = true
        _downloadState.value = ModelDownloadState.Success
    }
}

/**
 * Download/update state machine
 */
sealed class ModelDownloadState {
    object Checking : ModelDownloadState()
    object Downloading : ModelDownloadState()
    object Updating : ModelDownloadState()
    object Success : ModelDownloadState()
    object Ready : ModelDownloadState()
    object Error : ModelDownloadState()
    /** Model download requires a free HuggingFace licence click before it will succeed */
    object NeedsLicence : ModelDownloadState()
}

/**
 * Factory for ModelDownloadViewModel
 */
class ModelDownloadViewModelFactory(private val application: Application) :
    ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(ModelDownloadViewModel::class.java)) {
            return ModelDownloadViewModel(application) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}

