package com.wsvdmeer.pwncompanion.presentation.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Alignment
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalContext
import com.wsvdmeer.pwncompanion.ai.Franchise
import com.wsvdmeer.pwncompanion.utils.NotifSettings
import com.wsvdmeer.pwncompanion.utils.VoiceSettings
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wsvdmeer.pwncompanion.models.LearningStats
import com.wsvdmeer.pwncompanion.presentation.MainViewModel
import com.wsvdmeer.pwncompanion.presentation.theme.TerminalMono
import kotlin.math.roundToInt

/** Shared header row for a detail screen: "[ title ]" ........ "[ back ]". */
@Composable
private fun DetailHeader(title: String, onBack: () -> Unit) {
    val primary = MaterialTheme.colorScheme.primary
    Spacer(Modifier.height(8.dp))
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Text(
            "[ BACK ]", color = primary, fontWeight = FontWeight.Bold, fontSize = 15.sp,
            fontFamily = TerminalMono, modifier = Modifier.clickable { onBack() }
        )
        Text(title.uppercase(), color = primary, fontWeight = FontWeight.Bold, fontSize = 15.sp, fontFamily = TerminalMono)
    }
    Spacer(Modifier.height(6.dp))
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
    Spacer(Modifier.height(6.dp))
}

/**
 * Full-screen [ log ] detail: the complete event history (up to 200 lines), colored
 * by marker, newest-first. Reached by tapping the LOG summary.
 */
@Composable
fun LogDetailScreen(viewModel: MainViewModel, paddingValues: PaddingValues, onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    val log by viewModel.eventLog.collectAsState()
    val dim = MaterialTheme.colorScheme.onSurfaceVariant

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(paddingValues)
            .padding(horizontal = 12.dp)
    ) {
        DetailHeader("[ log ]", onBack)
        Text("${log.size} events", color = dim, fontSize = 11.sp, fontFamily = TerminalMono)
        Spacer(Modifier.height(4.dp))
        LazyColumn(modifier = Modifier.fillMaxWidth().weight(1f)) {
            items(log) { line ->
                val c = when {
                    line.contains("[+]") -> MaterialTheme.colorScheme.primary
                    line.contains("[!]") || line.contains("[x]") -> MaterialTheme.colorScheme.error
                    line.contains("[*]") -> MaterialTheme.colorScheme.tertiary
                    else -> dim
                }
                Text(
                    line, color = c, fontSize = 11.sp, lineHeight = 17.sp,
                    fontFamily = TerminalMono,
                    modifier = Modifier.padding(vertical = 1.dp)
                )
            }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }
}

/**
 * Full-screen [ stats ] readout — the pet's real numbers (Bjorn-style HUD). Progression is
 * the evolution STAGE (drives the voice) + plain terminal stats; no XP/levels/badges.
 * Reached by tapping the pet's stat strip.
 */
@Composable
fun StatsScreen(viewModel: MainViewModel, paddingValues: PaddingValues, onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    val captures by viewModel.captures.collectAsState()
    val stats by viewModel.learningStats.collectAsState()
    val mood by viewModel.deviceMood.collectAsState()
    val primary = MaterialTheme.colorScheme.primary

    val catches = captures.size
    val geo = captures.count { it.isGeolocated }
    val nets = stats?.totalObservations ?: 0
    val chans = stats?.channels?.count { it.observationCount > 0 } ?: 0
    val stage = com.wsvdmeer.pwncompanion.ai.ExperienceTier.fromCaptures(catches).label.lowercase()
    val lastCatch = agoLabel(captures.mapNotNull { it.timestamp }.maxOrNull())
    val best = stats?.bestChannel?.let { "ch$it · ${(stats!!.bestChannelSuccessRate * 100).roundToInt()}% yield" } ?: "—"

    // Richer derived stats (all from data we already hold).
    val crackable = captures.count { it.isCrackable }
    val partial = captures.count { it.isPartial }
    val cracked = captures.count { it.isCracked }
    val uniqueAps = captures.map { it.bssid }.filter { it.isNotBlank() }.distinct().size
    val dayAgo = System.currentTimeMillis() / 1000 - 86_400
    val weekAgo = System.currentTimeMillis() / 1000 - 7 * 86_400
    val last24h = captures.count { (it.timestamp ?: 0L) >= dayAgo }
    val last7d = captures.count { (it.timestamp ?: 0L) >= weekAgo }
    val busiest = stats?.busiestHourLabel()
    val tsList = captures.mapNotNull { it.timestamp }.filter { it > 0 }
    val perDay = if (tsList.size >= 2) {
        val spanDays = ((tsList.max() - tsList.min()) / 86_400.0).coerceAtLeast(1.0)
        "%.1f / day".format(catches / spanDays)
    } else null

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(paddingValues)
            .padding(horizontal = 12.dp)
    ) {
        DetailHeader("[ stats ]", onBack)

        // Evolution stage = the kept progression (it changes the pet's voice).
        Text(stage, color = primary, fontWeight = FontWeight.Bold, fontSize = 15.sp, fontFamily = TerminalMono)
        Spacer(Modifier.height(6.dp))

        StatRow("handshakes", "$catches", emphasize = true)
        if (crackable > 0 || partial > 0) {
            if (cracked > 0) StatRow("cracked", "$cracked pwned", emphasize = true)
            StatRow("crackable", "$crackable real", emphasize = true)
            if (partial > 0) StatRow("partials", "$partial can't crack")
        }
        if (uniqueAps > 0) StatRow("unique aps", "$uniqueAps")
        StatRow("mapped", "$geo geolocated")
        StatRow("networks", "$nets seen")
        StatRow("channels", "$chans hunted")
        StatRow("best chan", best, emphasize = stats?.bestChannel != null)
        busiest?.let { StatRow("busiest", it) }
        if (last24h > 0) StatRow("last 24h", "$last24h caught")
        if (last7d > 0) StatRow("last 7d", "$last7d caught")
        perDay?.let { StatRow("cadence", it) }
        mood?.takeIf { it.isNotBlank() }?.let { StatRow("mood", it.lowercase()) }
        StatRow("last catch", lastCatch)
    }
}

/** A terminal-aligned "label   value" stat row (monospace, fixed label column). */
@Composable
private fun StatRow(label: String, value: String, emphasize: Boolean = false) {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        Text(
            label.padEnd(11),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 12.sp, fontFamily = TerminalMono
        )
        Text(
            value,
            color = if (emphasize) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
            fontSize = 12.sp, fontFamily = TerminalMono, maxLines = 1
        )
    }
}

/** Compact "time ago" for a unix-seconds timestamp; "never" if none. */
private fun agoLabel(unixSeconds: Long?): String {
    if (unixSeconds == null || unixSeconds <= 0) return "never"
    val s = (System.currentTimeMillis() / 1000) - unixSeconds
    return when {
        s < 0 -> "—"
        s < 60 -> "${s}s ago"
        s < 3600 -> "${s / 60}m ago"
        s < 86400 -> "${s / 3600}h ago"
        else -> "${s / 86400}d ago"
    }
}

/**
 * Full-screen [ learning ] detail: summary, an ASCII hour-activity sparkline, and every
 * channel ranked by observations (block-bars). Reached from the [ learning ] section.
 */
@Composable
fun LearningDetailScreen(viewModel: MainViewModel, paddingValues: PaddingValues, onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    val stats by viewModel.learningStats.collectAsState()
    val dim = MaterialTheme.colorScheme.onSurfaceVariant
    val primary = MaterialTheme.colorScheme.primary
    val onSurface = MaterialTheme.colorScheme.onSurface

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(paddingValues)
            .padding(horizontal = 12.dp)
    ) {
        DetailHeader("[ learning ]", onBack)

        val s = stats
        if (s == null || s.totalObservations == 0) {
            Text("  gathering data…", color = dim, fontSize = 12.sp, fontFamily = TerminalMono)
            return@Column
        }

        // Summary.
        Text("seen     ${s.totalObservations} networks", color = dim, fontSize = 12.sp, fontFamily = TerminalMono)
        s.bestChannel?.let {
            Text(
                "best     ch$it · ${(s.bestChannelSuccessRate * 100).roundToInt()}% yield",
                color = primary, fontSize = 12.sp, fontFamily = TerminalMono
            )
        }

        // ── ASCII hour-activity sparkline (0–23) ────────────────────────────
        if (s.hourlyStats.isNotEmpty()) {
            Spacer(Modifier.height(10.dp))
            Text("activity by hour", color = dim, fontSize = 11.sp, fontFamily = TerminalMono)
            Spacer(Modifier.height(2.dp))
            // Full-width bars: 24 equal-weight columns (hours 0–23) fill the row and grow
            // from the bottom. The axis shows 7 evenly-spaced ticks 0·4·8·12·16·20·24 across
            // the same full width (0 at the left edge = midnight, 24 at the right edge).
            val byHour = (0..23).map { h -> s.hourlyStats.firstOrNull { it.hour == h }?.intensity ?: 0f }
            val maxI = byHour.maxOrNull()?.takeIf { it > 0f } ?: 1f
            val barMax = 44.dp
            Row(modifier = Modifier.fillMaxWidth().height(barMax)) {
                byHour.forEach { intensity ->
                    val frac = (intensity / maxI).coerceIn(0f, 1f)
                    Box(
                        modifier = Modifier.weight(1f).fillMaxHeight().padding(horizontal = 1.dp),
                        contentAlignment = Alignment.BottomCenter
                    ) {
                        if (frac > 0f) Box(Modifier.fillMaxWidth().fillMaxHeight(frac).background(primary))
                    }
                }
            }
            Spacer(Modifier.height(2.dp))
            val ticks = listOf(0, 4, 8, 12, 16, 20, 24)
            Row(modifier = Modifier.fillMaxWidth()) {
                ticks.forEachIndexed { i, h ->
                    Box(
                        modifier = Modifier.weight(1f),
                        contentAlignment = when (i) {
                            0 -> Alignment.CenterStart
                            ticks.lastIndex -> Alignment.CenterEnd
                            else -> Alignment.Center
                        }
                    ) {
                        Text(
                            "$h", color = dim.copy(alpha = 0.6f), fontSize = 9.sp,
                            fontFamily = TerminalMono, maxLines = 1, softWrap = false
                        )
                    }
                }
            }
        }

        Spacer(Modifier.height(10.dp))
        Text("channels (by activity)", color = dim, fontSize = 11.sp, fontFamily = TerminalMono)
        Spacer(Modifier.height(2.dp))

        val ranked = s.channels.sortedByDescending { it.observationCount }
        val maxObs = ranked.maxOfOrNull { it.observationCount }?.coerceAtLeast(1) ?: 1
        val off = dim.copy(alpha = 0.22f)
        LazyColumn(modifier = Modifier.fillMaxWidth().weight(1f)) {
            items(ranked) { ch ->
                val bars = (ch.observationCount * 10 / maxObs).coerceIn(0, 10)
                val yieldPct = (ch.successRate * 100).roundToInt()
                val rowColor = if (ch.isBest) primary else onSurface
                // Uniform Box cells (not █/· glyphs) so the bar segments stay pixel-perfect.
                Row(
                    modifier = Modifier.padding(vertical = 1.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "ch${ch.channel.toString().padEnd(3)} ",
                        color = rowColor, fontSize = 12.sp, lineHeight = 18.sp, fontFamily = TerminalMono,
                        maxLines = 1, softWrap = false
                    )
                    // Bars fill the width between the channel label and the count/yield.
                    Row(
                        modifier = Modifier.weight(1f),
                        horizontalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        repeat(10) { i ->
                            Box(
                                Modifier
                                    .weight(1f)
                                    .height(11.dp)
                                    .background(if (i < bars) rowColor else off)
                            )
                        }
                    }
                    Text(
                        "  ${ch.observationCount.toString().padStart(4)}  ${yieldPct}%",
                        color = rowColor, fontSize = 12.sp, lineHeight = 18.sp, fontFamily = TerminalMono,
                        maxLines = 1, softWrap = false
                    )
                }
            }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }
}

/** Settings — notification toggles (extensible). Terminal-styled like the other detail screens. */
@Composable
fun SettingsScreen(paddingValues: PaddingValues, onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    val context = LocalContext.current
    LaunchedEffect(Unit) { NotifSettings.ensureLoaded(context); VoiceSettings.ensureLoaded(context) }
    val onCatch by NotifSettings.onCatch.collectAsState()
    val onCracked by NotifSettings.onCracked.collectAsState()
    val enabledSet by VoiceSettings.enabled.collectAsState()
    val allOn = enabledSet.size == Franchise.entries.size
    val primary = MaterialTheme.colorScheme.primary
    val dim = MaterialTheme.colorScheme.onSurfaceVariant
    val onSurface = MaterialTheme.colorScheme.onSurface

    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(paddingValues)
            .padding(horizontal = 12.dp)
            .verticalScroll(rememberScrollState())
    ) {
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Text(
                "[ BACK ]", color = primary, fontWeight = FontWeight.Bold, fontSize = 15.sp,
                fontFamily = TerminalMono, modifier = Modifier.clickable { onBack() }
            )
            Text("[ SETTINGS ]", color = primary, fontWeight = FontWeight.Bold, fontSize = 15.sp, fontFamily = TerminalMono)
        }
        Spacer(Modifier.height(6.dp))
        HorizontalDivider(color = MaterialTheme.colorScheme.outline)
        Spacer(Modifier.height(14.dp))

        Text("notifications", color = dim, fontSize = 11.sp, fontFamily = TerminalMono)
        Spacer(Modifier.height(4.dp))
        SettingToggle("handshake caught", onCatch, primary, dim, onSurface) { NotifSettings.setOnCatch(context, !onCatch) }
        SettingToggle("password cracked", onCracked, primary, dim, onSurface) { NotifSettings.setOnCracked(context, !onCracked) }

        Spacer(Modifier.height(16.dp))
        Text("voice — franchises in rotation", color = dim, fontSize = 11.sp, fontFamily = TerminalMono)
        Spacer(Modifier.height(4.dp))
        SettingToggle("all franchises", allOn, primary, dim, onSurface) { VoiceSettings.setAll(context, !allOn) }
        HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.4f))
        Franchise.entries.sortedBy { it.label.lowercase() }.forEach { f ->
            val on = f.name in enabledSet
            SettingToggle(f.label, on, primary, dim, onSurface) { VoiceSettings.setEnabled(context, f, !on) }
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun SettingToggle(
    label: String, on: Boolean, primary: Color, dim: Color, onSurface: Color, onToggle: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().clickable { onToggle() }.padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(if (on) "[x]" else "[ ]", color = if (on) primary else dim, fontSize = 14.sp, fontFamily = TerminalMono)
        Spacer(Modifier.width(10.dp))
        Text(label, color = onSurface, fontSize = 13.sp, fontFamily = TerminalMono, modifier = Modifier.weight(1f))
    }
}
