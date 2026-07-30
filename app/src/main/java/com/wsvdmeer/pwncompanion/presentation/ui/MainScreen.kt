package com.wsvdmeer.pwncompanion.presentation.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import com.wsvdmeer.pwncompanion.presentation.MainViewModel
import com.wsvdmeer.pwncompanion.presentation.theme.PwnCompanionTheme
import com.wsvdmeer.pwncompanion.presentation.theme.scanlines

/**
 * Main Screen - Top-level Compose entry point for PwnCompanion.
 * Orchestrates all sub-screens and manages navigation.
 * Connected to MainViewModel for state management.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(viewModel: MainViewModel) {
    PwnCompanionTheme {
        Surface(
            modifier = Modifier
                .fillMaxSize()
                .scanlines(),
            color = MaterialTheme.colorScheme.background
        ) {
            MainAppContent(viewModel)
        }
    }
}

/**
 * Main App Content - Scaffold with TopAppBar and content area.
 * Displays:
 * - Server status and device connection count
 * - Device list (if any connected)
 * - Latest image from device
 * - Status messages
 * - Bottom sheet with control buttons
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainAppContent(viewModel: MainViewModel) {
    // Collect ViewModel state
    val isServerRunning by viewModel.isServerRunning.collectAsState()
    val connectedDeviceCount by viewModel.connectedDeviceCount.collectAsState()
    val deviceStates by viewModel.deviceStates.collectAsState()
    val currentImageData by viewModel.currentImageData.collectAsState()
    val currentImageDeviceId by viewModel.currentImageDeviceId.collectAsState()
    val currentStatusMessage by viewModel.currentStatusMessage.collectAsState()
    val gpsData by viewModel.gpsData.collectAsState()
    val outgoingQueueSize by viewModel.outgoingQueueSize.collectAsState()
    val errorMessage by viewModel.errorMessage.collectAsState()
    val showControlSheet by viewModel.showControlSheet.collectAsState()
    val learningStats by viewModel.learningStats.collectAsState()  // NEW
    val detailScreen by viewModel.detailScreen.collectAsState()

    // Bottom Sheet State
    if (showControlSheet) {
        ModalBottomSheet(
            onDismissRequest = { viewModel.hideControlSheet() },
            containerColor = MaterialTheme.colorScheme.surface,
            scrimColor = MaterialTheme.colorScheme.scrim.copy(alpha = 0.32f)
        ) {
            ControlSheetContent(
                viewModel = viewModel,
                onDismiss = { viewModel.hideControlSheet() }
            )
        }
    }

    // No Material TopAppBar — the console renders its own header line.
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        modifier = Modifier.fillMaxSize()
    ) { paddingValues ->
        when (detailScreen) {
            com.wsvdmeer.pwncompanion.presentation.DetailScreen.CAPTURES ->
                CapturesDetailScreen(
                    viewModel = viewModel,
                    paddingValues = paddingValues,
                    onBack = { viewModel.closeDetail() }
                )
            com.wsvdmeer.pwncompanion.presentation.DetailScreen.LOG ->
                LogDetailScreen(
                    viewModel = viewModel,
                    paddingValues = paddingValues,
                    onBack = { viewModel.closeDetail() }
                )
            com.wsvdmeer.pwncompanion.presentation.DetailScreen.LEARNING ->
                LearningDetailScreen(
                    viewModel = viewModel,
                    paddingValues = paddingValues,
                    onBack = { viewModel.closeDetail() }
                )
            com.wsvdmeer.pwncompanion.presentation.DetailScreen.STATS ->
                StatsScreen(
                    viewModel = viewModel,
                    paddingValues = paddingValues,
                    onBack = { viewModel.closeDetail() }
                )
            com.wsvdmeer.pwncompanion.presentation.DetailScreen.SETTINGS ->
                SettingsScreen(
                    paddingValues = paddingValues,
                    onBack = { viewModel.closeDetail() }
                )
            else -> MainContentArea(
                paddingValues = paddingValues,
                mainViewModel = viewModel,
                isServerRunning = isServerRunning,
                connectedDevices = deviceStates.values.toList(),
                currentImageData = currentImageData,
                currentImageDeviceId = currentImageDeviceId,
                currentStatusMessage = currentStatusMessage,
                gpsData = gpsData,
                queueSize = outgoingQueueSize,
                errorMessage = errorMessage,
                onErrorDismissed = { viewModel.clearError() },
                learningStats = learningStats  // NEW
            )
        }
    }
}
