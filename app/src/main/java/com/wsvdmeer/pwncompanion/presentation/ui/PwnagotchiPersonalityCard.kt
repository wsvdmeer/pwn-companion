package com.wsvdmeer.pwncompanion.presentation.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.KeyboardArrowUp
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wsvdmeer.pwncompanion.ai.EmergentPersonality
import com.wsvdmeer.pwncompanion.ai.PwnagotchiViewModel
import com.wsvdmeer.pwncompanion.presentation.theme.TerminalBoxShape
import com.wsvdmeer.pwncompanion.models.LearningStats
import kotlin.math.roundToInt

/**
 * Pwnagotchi Personality Card - Real-time AI Response Display
 *
 * Features:
 * - Live token streaming inside a terminal-framed console
 * - Real-time token counter
 * - Status indicator (Thinking/Ready)
 * - EMERGENT personality readout (disposition + live traits) — no mood picker;
 *   the disposition is computed from accumulated history + live events
 */
@Composable
fun PwnagotchiPersonalityCard(
    viewModel: PwnagotchiViewModel,
    pwnagotchiName: String = "Pwnagotchi",
    isAutoMode: Boolean = true,
    onStats: () -> Unit = {},
    // "where next?" is the deauth hunt-advisor voiced by the pet. Enabled only when we
    // actually have a recommendation to speak; the tap hands pre-computed facts to the VM.
    whereNextEnabled: Boolean = false,
    onWhereNext: () -> Unit = {},
) {
    val personalityText by viewModel.personalityText.collectAsState()
    val isGenerating    by viewModel.isGenerating.collectAsState()
    val isThinking      by viewModel.isThinking.collectAsState()
    val personality     by viewModel.personality.collectAsState()
    val petState        by viewModel.personalityState.collectAsState()
    val totalCaptures   by viewModel.totalCaptures.collectAsState()
    val lastCapture     by viewModel.lastCaptureTime.collectAsState()
    val accent = Color(personality.accentArgb)
    // Dropped from the card as dev-facing clutter: experienceTier + model name (the
    // header subline), and statusMessage + wordCount (the footer).
    val isModelReady    by viewModel.isModelReady.collectAsState()
    val isDownloading   by viewModel.isDownloading.collectAsState()
    val downloadProgress    by viewModel.downloadProgress.collectAsState()
    val downloadStatusText  by viewModel.downloadStatusText.collectAsState()
    val downloadError       by viewModel.downloadError.collectAsState()
    val modelLoadError      by viewModel.modelLoadError.collectAsState()
    val isModelInstalled = viewModel.isModelInstalled

    // Borderless — this card lives inside the single console, no chrome of its own.
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Color.Transparent),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        shape = TerminalBoxShape
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {

            // HEADER: M3 icon + Title + Status badge
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        "[ AI ]",
                        fontWeight = FontWeight.Bold,
                        fontSize = 12.sp,
                        letterSpacing = 2.sp,
                        color = MaterialTheme.colorScheme.primary
                    )
                    // One mood word — the live disposition. Tier + model name were
                    // dev-facing noise and are dropped from the always-visible line.
                    Text(
                        personality.disposition.lowercase(),
                        fontSize = 10.sp,
                        color = accent
                    )
                }

                // Status badge
                when {
                    !isModelReady && isModelInstalled && modelLoadError != null -> {
                        Text(
                            "Load Error",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error,
                            fontSize = 10.sp,
                            modifier = Modifier
                                .background(
                                    MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.5f),
                                    shape = RoundedCornerShape(8.dp)
                                )
                                .padding(horizontal = 8.dp, vertical = 4.dp)
                        )
                    }
                    !isModelReady && isDownloading -> {
                        Text(
                            "Downloading",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                            fontSize = 10.sp,
                            modifier = Modifier
                                .background(
                                    MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.6f),
                                    shape = RoundedCornerShape(8.dp)
                                )
                                .padding(horizontal = 8.dp, vertical = 4.dp)
                        )
                    }
                    !isModelReady && isModelInstalled -> {
                        // File exists but still loading into memory
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .background(
                                    MaterialTheme.colorScheme.primary.copy(alpha = 0.1f),
                                    shape = RoundedCornerShape(8.dp)
                                )
                                .padding(horizontal = 8.dp, vertical = 4.dp)
                        ) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(12.dp),
                                strokeWidth = 1.5.dp,
                                color = MaterialTheme.colorScheme.primary
                            )
                            Text(
                                "Loading",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary,
                                fontSize = 10.sp
                            )
                        }
                    }
                    !isModelReady -> {
                        Text(
                            "No Model",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error,
                            fontSize = 10.sp,
                            modifier = Modifier
                                .background(
                                    MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.5f),
                                    shape = RoundedCornerShape(8.dp)
                                )
                                .padding(horizontal = 8.dp, vertical = 4.dp)
                        )
                    }
                    // Model ready (thinking or idle): status is shown once, in the
                    // footer status line — no duplicate "Thinking"/"Ready" badge here.
                    else -> {}
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // ── PET STATUS STRIP — the little hacker creature's vitals ───────────
            // Evolution stage (grows with catches), an energy meter, "hunger" (time
            // since the last catch) and its catch count. This replaced the movie-voice
            // chip row: the voice now lives as a quiet cycler at the bottom of the card.
            PetStatStrip(
                stage = personality.tier.label.lowercase(),
                energy = petState.energy,
                lastCaptureMs = lastCapture,
                catches = totalCaptures,
                modifier = Modifier
                    .clip(RoundedCornerShape(2.dp))
                    .clickable { onStats() }
            )

            Spacer(modifier = Modifier.height(10.dp))

            // AI OUTPUT: terminal-framed console — flat black, neon border
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFF02060A), shape = RoundedCornerShape(8.dp))
                    .border(
                        width = 1.dp,
                        // Green while generating (calmer than the red mood accent).
                        color = (if (isGenerating) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline)
                            .copy(alpha = 0.7f),
                        shape = RoundedCornerShape(8.dp)
                    )
                    .padding(12.dp)
                    .heightIn(min = 60.dp),
                contentAlignment = Alignment.TopStart
            ) {
                when {
                    !isModelReady && isModelInstalled && !isDownloading -> {
                        if (modelLoadError != null) {
                            // Load failed or timed out — show error + retry
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        imageVector = Icons.Outlined.Info,
                                        contentDescription = null,
                                        modifier = Modifier.size(14.dp),
                                        tint = MaterialTheme.colorScheme.error
                                    )
                                    Text(
                                        "Failed to load model",
                                        style = MaterialTheme.typography.bodySmall,
                                        fontWeight = FontWeight.SemiBold,
                                        color = MaterialTheme.colorScheme.error
                                    )
                                }
                                Text(
                                     text = modelLoadError!!,
                                     style = MaterialTheme.typography.bodySmall,
                                     color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f),
                                     lineHeight = 16.sp
                                 )
                                 Row(
                                     modifier = Modifier.fillMaxWidth(),
                                     horizontalArrangement = Arrangement.spacedBy(8.dp)
                                 ) {
                                     Button(
                                         onClick = { viewModel.retryModelLoad() },
                                         modifier = Modifier.weight(1f),
                                         colors = ButtonDefaults.buttonColors(
                                             containerColor = MaterialTheme.colorScheme.secondary
                                         )
                                     ) {
                                         Text(
                                             "Retry",
                                             style = MaterialTheme.typography.labelMedium
                                         )
                                     }
                                     Button(
                                         onClick = { viewModel.clearAndRedownload() },
                                         modifier = Modifier.weight(1f),
                                         colors = ButtonDefaults.buttonColors(
                                             containerColor = MaterialTheme.colorScheme.error
                                         )
                                     ) {
                                         Text(
                                             "Re-download",
                                             style = MaterialTheme.typography.labelMedium
                                         )
                                     }
                                 }
                            }
                        } else {
                            // Model file exists, loading in progress
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(16.dp),
                                    strokeWidth = 2.dp,
                                    color = MaterialTheme.colorScheme.primary
                                )
                                Text(
                                    "Loading ${viewModel.modelName} into memory…",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f)
                                )
                            }
                        }
                    }
                    !isModelReady -> {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            // Title row
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = Icons.Outlined.Info,
                                    contentDescription = null,
                                    modifier = Modifier.size(14.dp),
                                    tint = MaterialTheme.colorScheme.error
                                )
                                Text(
                                    "AI model not installed",
                                    style = MaterialTheme.typography.bodySmall,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.error
                                )
                            }

                            // Error message (if download failed)
                            if (downloadError != null) {
                                Text(
                                    text = downloadError!!,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.error.copy(alpha = 0.8f),
                                    lineHeight = 16.sp
                                )
                            }

                            if (isDownloading) {
                                // Progress state
                                if (downloadStatusText.isNotEmpty()) {
                                    Text(
                                        text = downloadStatusText,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f)
                                    )
                                }
                                LinearProgressIndicator(
                                    progress = { downloadProgress },
                                    modifier = Modifier.fillMaxWidth(),
                                    color = MaterialTheme.colorScheme.primary,
                                    trackColor = MaterialTheme.colorScheme.surfaceVariant
                                )
                            } else {
                                // Download button
                                Button(
                                    onClick = { viewModel.startModelDownload() },
                                    modifier = Modifier.fillMaxWidth(),
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = MaterialTheme.colorScheme.primary
                                    )
                                ) {
                                    Icon(
                                        imageVector = Icons.Filled.CloudDownload,
                                        contentDescription = null,
                                        modifier = Modifier.size(16.dp)
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        "Download ${viewModel.modelName} (~300 MB)",
                                        style = MaterialTheme.typography.labelMedium
                                    )
                                }
                            }
                        }
                    }
                    personalityText.isEmpty() && !isThinking && !isAutoMode && isModelReady -> {
                        // MANUAL, no line yet — controls live once, below the box.
                        Text(
                            "$pwnagotchiName is resting. Poke it.",
                            style = MaterialTheme.typography.bodyMedium,
                            fontStyle = FontStyle.Italic,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                        )
                    }
                    personalityText.isEmpty() && !isThinking -> {
                        Text(
                            if (isAutoMode) "Waiting for network events..."
                            else "Manual mode — no scanning active.",
                            style = MaterialTheme.typography.bodyMedium,
                            fontStyle = FontStyle.Italic,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                        )
                    }
                    isThinking && personalityText.isEmpty() -> {
                        // Small terminal-style line instead of the big pulsing dots.
                        Text(
                            "> thinking…",
                            style = MaterialTheme.typography.bodyMedium,
                            fontStyle = FontStyle.Italic,
                            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.7f)
                        )
                    }
                    else -> {
                        // Streaming or done — show text with a blinking cursor while generating
                        val cursor = if (isGenerating) {
                            val inf = rememberInfiniteTransition(label = "cursor")
                            val alpha by inf.animateFloat(
                                initialValue = 1f, targetValue = 0f,
                                animationSpec = infiniteRepeatable(
                                    animation = tween(500, easing = LinearEasing),
                                    repeatMode = RepeatMode.Reverse
                                ),
                                label = "cursorAlpha"
                            )
                            "\u2588".let { if (alpha > 0.5f) it else "" }
                        } else ""
                        Text(
                            "$pwnagotchiName:~$ $personalityText$cursor",
                            style = MaterialTheme.typography.bodyMedium,
                            // Terminal green (not the red mood accent) — readable, not alarming.
                            color = MaterialTheme.colorScheme.primary,
                            lineHeight = 20.sp
                        )
                    }
                }
            }

            // (No footer status/word-count row — the blinking cursor and the accent
            //  border already signal generating; the count was dev-facing noise.)

            // ── INTENTS — tap the pet to make it talk, grounded in real data ─────
            // Replaces the free-text "ask" box: a 0.5B model is unreliable at open Q&A
            // (it would blank/refuse and fall back to a non-sequitur), but great at
            // voicing pre-chewed facts. Each intent hands it correct data to phrase:
            //   hunt   → the deauth hunt-advisor voiced (auto)  /  poke → a quip (manual)
            //   recap  → a data-grounded session digest
            //   status → a short in-character status check-in
            // Labels are single words so three fit one row without wrapping.
            if (isModelReady) {
                Spacer(modifier = Modifier.height(10.dp))
                Text(
                    "ask your pet:",
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                    fontSize = 10.sp
                )
                Spacer(modifier = Modifier.height(4.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    if (isAutoMode) {
                        TermButton(
                            "[ hunt ]", Modifier.weight(1f),
                            enabled = !isGenerating && whereNextEnabled
                        ) { onWhereNext() }
                    } else {
                        TermButton("[ poke ]", Modifier.weight(1f), enabled = !isGenerating) { viewModel.poke() }
                    }
                    TermButton("[ recap ]", Modifier.weight(1f), enabled = !isGenerating) { viewModel.digest() }
                    TermButton("[ status ]", Modifier.weight(1f), enabled = !isGenerating) { viewModel.checkIn() }
                }

                // (No free-text ask box — the intents + `recap` cover the useful queries
                // and the on-screen data answers the rest; a 0.5B model is weak at open Q&A.)
                val dimc = MaterialTheme.colorScheme.onSurfaceVariant

                // ── AI FEED — recent lines + what triggered them (verify features fire) ──
                val feed by viewModel.aiFeed.collectAsState()
                var showFeed by remember { mutableStateOf(false) }
                if (feed.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        if (showFeed) "ai feed ⌄" else "ai feed · ${feed.size} ›",
                        color = dimc.copy(alpha = 0.7f), fontSize = 10.sp,
                        modifier = Modifier
                            .clickable { showFeed = !showFeed }
                            .padding(vertical = 2.dp)
                    )
                    if (showFeed) {
                        Column(modifier = Modifier.padding(top = 2.dp)) {
                            feed.take(8).forEach { e ->
                                Text("› ${e.line}", color = MaterialTheme.colorScheme.onSurface, fontSize = 10.sp, lineHeight = 13.sp)
                                Text("   ${e.trigger}", color = dimc.copy(alpha = 0.6f), fontSize = 9.sp, lineHeight = 12.sp, maxLines = 1)
                            }
                        }
                    }
                }
            }

            // The pet speaks in one blended cult-movie voice whose tone shifts with its
            // mood — there is no voice picker any more (mood does the choosing).
        }
    }
}

/**
 * The hacker-Tamagotchi status strip: evolution stage, an energy meter, "hunger"
 * (time since the last catch) and total catches — the creature's vitals at a glance.
 * Terminal-styled with block/dot glyphs (no emoji — those render as tofu on-device).
 */
@Composable
private fun PetStatStrip(
    stage: String,
    energy: Float,
    lastCaptureMs: Long?,
    catches: Int,
    modifier: Modifier = Modifier,
) {
    val dim = MaterialTheme.colorScheme.onSurfaceVariant
    val primary = MaterialTheme.colorScheme.primary
    val pct = (energy.coerceIn(0f, 1f) * 100).roundToInt()
    val filled = (energy.coerceIn(0f, 1f) * 10).roundToInt().coerceIn(0, 10)
    val hunger = when (val t = lastCaptureMs) {
        null -> "hungry"
        else -> {
            val m = (System.currentTimeMillis() - t) / 60_000
            when {
                m <= 0L        -> "fed just now"
                m < 60L        -> "fed ${m}m ago"
                m < 60L * 48   -> "hungry ${m / 60}h"
                else           -> "hungry ${m / 1440}d"
            }
        }
    }
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        // Identity line: stage · catches · hunger. Tap → [ stats ] ('›' hints it).
        Text(
            "$stage · $catches caught · $hunger  ›",
            color = dim, fontSize = 11.sp, lineHeight = 16.sp, maxLines = 1
        )
        // Energy as a bar — uniform Box cells (pixel-perfect, like the vitals gauges);
        // the font's █ blurred the gaps between segments.
        val barColor = if (pct >= 50) primary else dim
        val off = dim.copy(alpha = 0.22f)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("energy ", color = barColor, fontSize = 11.sp, lineHeight = 16.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                repeat(10) { i ->
                    Box(
                        Modifier
                            .size(width = 8.dp, height = 10.dp)
                            .background(if (i < filled) barColor else off)
                    )
                }
            }
            Text("  $pct%", color = barColor, fontSize = 11.sp, lineHeight = 16.sp, maxLines = 1)
        }
    }
}

/** Flat terminal-style button: bordered `[ label ]`, green when enabled, grey when not. */
@Composable
private fun TermButton(
    label: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    val c = if (enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
    Text(
        label,
        color = c,
        fontWeight = FontWeight.Bold,
        fontSize = 12.sp,
        textAlign = TextAlign.Center,
        maxLines = 1,
        softWrap = false,
        modifier = modifier
            .clip(RoundedCornerShape(2.dp))
            .then(if (enabled) Modifier.clickable { onClick() } else Modifier)
            .border(1.dp, c.copy(alpha = 0.5f), RoundedCornerShape(2.dp))
            .padding(vertical = 12.dp, horizontal = 4.dp)
    )
}

