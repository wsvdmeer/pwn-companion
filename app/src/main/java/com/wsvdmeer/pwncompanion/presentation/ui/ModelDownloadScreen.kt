package com.wsvdmeer.pwncompanion.presentation.ui

import android.app.Application
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.ui.draw.clip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.wsvdmeer.pwncompanion.ai.ModelDownloadState
import com.wsvdmeer.pwncompanion.ai.ModelDownloadViewModel
import com.wsvdmeer.pwncompanion.ai.ModelDownloadViewModelFactory
import com.wsvdmeer.pwncompanion.presentation.theme.TerminalBoxShape
import kotlin.math.roundToInt

/** Number of cells in the ASCII progress bar (matches the console block-bar style). */
private const val BAR_CELLS = 22

@Composable
fun ModelDownloadScreen(
    application: Application,
    onDownloadComplete: () -> Unit
) {
    val viewModel: ModelDownloadViewModel = viewModel(
        factory = ModelDownloadViewModelFactory(application)
    )

    val downloadState by viewModel.downloadState.collectAsState()
    val progress      by viewModel.progress.collectAsState()
    val errorMessage  by viewModel.errorMessage.collectAsState()
    val statusText    by viewModel.currentStatusText.collectAsState()
    val isReady       by viewModel.isReady.collectAsState()

    LaunchedEffect(isReady) { if (isReady) onDownloadComplete() }

    val primary = MaterialTheme.colorScheme.primary
    val dim = MaterialTheme.colorScheme.onSurfaceVariant
    val error = MaterialTheme.colorScheme.error
    val isError = downloadState is ModelDownloadState.Error

    Surface(
        modifier = Modifier.fillMaxSize(),
        color    = MaterialTheme.colorScheme.background
    ) {
        Column(
            modifier            = Modifier
                .fillMaxSize()
                .padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.Start,
            verticalArrangement = Arrangement.Center
        ) {
            // Header — same lowercase console identity as the main screen.
            Text(
                "pwncompanion · ai model",
                color = primary, fontWeight = FontWeight.Bold, fontSize = 16.sp, maxLines = 1
            )
            Spacer(Modifier.height(4.dp))
            Text(
                if (isError) "[ INSTALL FAILED ]" else "[ INSTALLING BRAIN ]",
                color = if (isError) error else dim, fontSize = 12.sp
            )
            Spacer(Modifier.height(20.dp))

            // Status line (what the download VM is currently doing).
            Text(statusText, color = dim, fontSize = 12.sp, lineHeight = 18.sp)
            Spacer(Modifier.height(12.dp))

            when (downloadState) {
                is ModelDownloadState.Downloading,
                is ModelDownloadState.Updating -> {
                    TerminalProgressBar(progress, primary, dim)
                }

                is ModelDownloadState.Checking -> {
                    // Indeterminate — a full dotted track with a scanning label.
                    Text(
                        "[${"·".repeat(BAR_CELLS)}] scanning…",
                        color = dim, fontSize = 14.sp
                    )
                }

                is ModelDownloadState.Error -> {
                    if (errorMessage.isNotEmpty()) {
                        Text("[ error ] $errorMessage", color = error, fontSize = 12.sp, lineHeight = 18.sp)
                        Spacer(Modifier.height(14.dp))
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        TerminalKey("[ retry ]", primary, onClick = viewModel::retryDownload)
                        TerminalKey("[ skip ]", dim, onClick = viewModel::skipDownload)
                    }
                }

                else -> {}
            }

            Spacer(Modifier.height(36.dp))

            Text(
                "Qwen2.5 0.5B Q4_K_M · ~491 MB · no account needed",
                color = dim.copy(alpha = 0.5f), fontSize = 11.sp
            )
        }
    }
}

/** ASCII block-bar `[████······] 42%` — the same glyph style as the console bars. */
@Composable
private fun TerminalProgressBar(progress: Float, primary: androidx.compose.ui.graphics.Color, dim: androidx.compose.ui.graphics.Color) {
    val p = progress.coerceIn(0f, 1f)
    val filled = (p * BAR_CELLS).roundToInt().coerceIn(0, BAR_CELLS)
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            "[${"█".repeat(filled)}${"·".repeat(BAR_CELLS - filled)}]",
            color = primary, fontSize = 14.sp, lineHeight = 18.sp
        )
        Spacer(Modifier.width(8.dp))
        Text("${(p * 100).toInt()}%", color = dim, fontSize = 13.sp)
    }
}

/** Flat bordered terminal button matching the console's `[ label ]` actions. */
@Composable
private fun TerminalKey(label: String, color: androidx.compose.ui.graphics.Color, onClick: () -> Unit) {
    Text(
        label,
        color = color, fontWeight = FontWeight.Bold, fontSize = 13.sp, textAlign = TextAlign.Center,
        modifier = Modifier
            .clip(TerminalBoxShape)
            .clickable { onClick() }
            .border(1.dp, color.copy(alpha = 0.5f), TerminalBoxShape)
            .padding(horizontal = 16.dp, vertical = 10.dp)
    )
}
