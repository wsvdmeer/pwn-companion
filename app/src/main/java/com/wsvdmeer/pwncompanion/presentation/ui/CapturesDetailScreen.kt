package com.wsvdmeer.pwncompanion.presentation.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wsvdmeer.pwncompanion.crack.WpaCracker
import com.wsvdmeer.pwncompanion.models.CaptureEntry
import com.wsvdmeer.pwncompanion.models.GpsData
import com.wsvdmeer.pwncompanion.BuildConfig
import com.wsvdmeer.pwncompanion.crack.CrackEngine
import com.wsvdmeer.pwncompanion.crack.CrackSettings
import com.wsvdmeer.pwncompanion.crack.KeyGenerators
import com.wsvdmeer.pwncompanion.crack.CrackState
import com.wsvdmeer.pwncompanion.presentation.MainViewModel
import com.wsvdmeer.pwncompanion.presentation.theme.TerminalMono
import com.wsvdmeer.pwncompanion.utils.GeoPoint
import com.wsvdmeer.pwncompanion.utils.MapTiles
import com.wsvdmeer.pwncompanion.utils.TileMapLoader
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.withContext
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Full-screen [ captures ] detail: an ASCII heatmap of where handshakes were caught,
 * plus the complete, searchable capture log. Reached by tapping the captures summary.
 */
@Composable
fun CapturesDetailScreen(
    viewModel: MainViewModel,
    paddingValues: PaddingValues,
    onBack: () -> Unit,
) {
    BackHandler(onBack = onBack)

    val captures by viewModel.captures.collectAsState()
    val gps by viewModel.gpsData.collectAsState()
    val crackState by viewModel.crackState.collectAsState()
    val crackQueue by viewModel.crackQueue.collectAsState()
    val crackExhausted by viewModel.crackExhausted.collectAsState()
    val crackAttempted by viewModel.crackAttempted.collectAsState()
    var query by remember { mutableStateOf("") }
    var geoOnly by remember { mutableStateOf(false) }
    var crackedOnly by remember { mutableStateOf(false) }
    var crackableOnly by remember { mutableStateOf(false) }
    var showFilters by remember { mutableStateOf(false) }
    var showOptions by remember { mutableStateOf(false) }
    var showManage by remember { mutableStateOf(false) }
    var detailCapture by remember { mutableStateOf<CaptureEntry?>(null) }
    var clusterCaptures by remember { mutableStateOf<List<CaptureEntry>?>(null) }

    // Gentle-knob power settings for cracking (persisted; also read by CrackEngine).
    val context = LocalContext.current
    LaunchedEffect(Unit) { CrackSettings.ensureLoaded(context) }
    val gentleCpu by CrackSettings.gentleCpu.collectAsState()
    val chargerOnly by CrackSettings.chargerOnly.collectAsState()
    val lowBatteryStop by CrackSettings.lowBatteryStop.collectAsState()

    val primary = MaterialTheme.colorScheme.primary
    val dim = MaterialTheme.colorScheme.onSurfaceVariant
    val onSurface = MaterialTheme.colorScheme.onSurface

    val geoCount = remember(captures) { captures.count { it.isGeolocated } }
    val crackable = remember(captures) { captures.count { it.isCrackable } }
    val partial = remember(captures) { captures.count { it.isPartial } }
    val cracked = remember(captures) { captures.count { it.isCracked } }
    val filtered = remember(captures, query, geoOnly, crackedOnly, crackableOnly) {
        val anyFilter = geoOnly || crackedOnly || crackableOnly
        captures
            .filter { c ->
                // Toggle chips are OR'd — the union of whatever's on (none on = show all).
                // `crackable` uses the same eapol/pmkid definition as the "N crackable" count.
                val matchesFilter = !anyFilter ||
                    (geoOnly && c.isGeolocated) ||
                    (crackedOnly && c.isCracked) ||
                    (crackableOnly && c.isCrackable)
                // Search is AND'd on top of the filter union.
                val matchesQuery = query.isBlank() || c.ssid.contains(query, ignoreCase = true)
                matchesFilter && matchesQuery
            }
            .sortedByDescending { it.timestamp ?: 0L }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(paddingValues)
            .padding(horizontal = 12.dp)
    ) {
        Spacer(Modifier.height(8.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "[ BACK ]",
                color = primary,
                fontWeight = FontWeight.Bold,
                fontSize = 15.sp,
                fontFamily = TerminalMono,
                maxLines = 1, softWrap = false,
                modifier = Modifier.clickable { onBack() }
            )
            Text(
                "[ CAPTURES ]", color = primary, fontWeight = FontWeight.Bold, fontSize = 15.sp,
                fontFamily = TerminalMono, maxLines = 1, softWrap = false,
            )
            // Right-aligned action group (kept on one line so it never wraps on narrow screens).
            Spacer(Modifier.weight(1f))
            // Debug-only: inject a known-crackable capture to test the crack flow. Stripped in release.
            if (BuildConfig.DEBUG) {
                Text(
                    "[ +test ]",
                    color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp, fontFamily = TerminalMono,
                    maxLines = 1, softWrap = false,
                    modifier = Modifier.clickable { viewModel.injectTestCapture() }
                )
            }
            // Clear/wipe captures (phone cache, or the Pi's handshakes too).
            Text(
                "[ clear ]",
                color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp, fontFamily = TerminalMono,
                maxLines = 1, softWrap = false,
                modifier = Modifier.clickable { showManage = true }
            )
        }
        ConsoleRuleLocal()

        // Crack progress stays pinned under the header (visible while you scroll the list).
        if (crackState !is CrackState.Idle) {
            Spacer(Modifier.height(6.dp))
            CrackBanner(
                state = crackState,
                queueCount = crackQueue.size,
                onSkip = { viewModel.skipCrack() },
                onCancel = { viewModel.cancelCrack() },
                onDismiss = { viewModel.dismissCrack() },
            )
        }

        // Everything below scrolls together — map, search/filters, counts, and the list — so the
        // big map scrolls away with the list instead of pinning the top of the screen.
        val activeFilters = (if (geoOnly) 1 else 0) + (if (crackableOnly) 1 else 0) + (if (crackedOnly) 1 else 0)
        val currentKey = when (val s = crackState) {
            is CrackState.Running -> CrackEngine.norm(s.bssid)
            is CrackState.Paused -> CrackEngine.norm(s.bssid)
            else -> null
        }
        val currentPaused = crackState is CrackState.Paused
        val queuedKeys = remember(crackQueue) { crackQueue.map { CrackEngine.norm(it.bssid) }.toSet() }

        LazyColumn(modifier = Modifier.fillMaxWidth().weight(1f)) {
            // ── pixel map of catch locations (scrolls with the list) ──
            if (geoCount > 0) {
                item(key = "map") {
                    val hasFix = gps?.isValid() == true
                    Spacer(Modifier.height(4.dp))
                    Text(
                        buildAnnotatedString {
                            append("map · $geoCount geolocated   ")
                            withStyle(SpanStyle(color = Color(0xFF3DFF6E))) { append("■") }
                            append(" catch")
                            if (hasFix) {
                                append("   ")
                                withStyle(SpanStyle(color = Color(0xFFFFA533))) { append("■") }
                                append(" you")
                            }
                        },
                        color = dim, fontSize = 11.sp, fontFamily = TerminalMono
                    )
                    Spacer(Modifier.height(4.dp))
                    CaptureMap(
                        points = captures.filter { it.isGeolocated },
                        current = gps?.takeIf { it.isValid() },
                        onCatch = { caps ->
                            if (caps.size == 1) detailCapture = caps.first()
                            else clusterCaptures = caps   // several here → let the user pick
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(Color(0xFF02060A))
                    )
                    Spacer(Modifier.height(8.dp))
                    ConsoleRuleLocal()
                }
            }

            // ── search + filters + counts (scroll too) ──
            item(key = "controls") {
                Spacer(Modifier.height(6.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .weight(1f)
                            .border(1.dp, MaterialTheme.colorScheme.outline)
                            .padding(horizontal = 8.dp, vertical = 6.dp)
                    ) {
                        Text("/", color = dim, fontSize = 12.sp, fontFamily = TerminalMono)
                        Spacer(Modifier.width(6.dp))
                        SearchField(query, onQuery = { query = it }, primary = primary, dim = dim)
                    }
                    Text(
                        if (activeFilters > 0) "[ filters · $activeFilters ]" else "[ filters ]",
                        color = if (activeFilters > 0) primary else dim,
                        fontSize = 12.sp, fontFamily = TerminalMono,
                        modifier = Modifier
                            .border(1.dp, if (activeFilters > 0) primary else MaterialTheme.colorScheme.outline)
                            .clickable { showFilters = true }
                            .padding(horizontal = 10.dp, vertical = 6.dp)
                    )
                    // Cracking power options — only relevant when there's something crackable.
                    if (crackable > 0) {
                        Text(
                            "[ options ]",
                            color = dim, fontSize = 12.sp, fontFamily = TerminalMono,
                            modifier = Modifier
                                .border(1.dp, MaterialTheme.colorScheme.outline)
                                .clickable { showOptions = true }
                                .padding(horizontal = 10.dp, vertical = 6.dp)
                        )
                    }
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    "${filtered.size} / ${captures.size} shown",
                    color = dim, fontSize = 11.sp, fontFamily = TerminalMono
                )
                // Crackability breakdown — a capture only counts as a real win once it yields a
                // hash. Partials (only M1, no PMKID) can't crack; nudge you to re-hunt them.
                if (crackable > 0 || partial > 0) {
                    Text(
                        buildAnnotatedString {
                            if (cracked > 0) {
                                withStyle(SpanStyle(color = Color(0xFF3DFF6E), fontWeight = FontWeight.Bold)) { append("$cracked cracked") }
                                append("  ·  ")
                            }
                            withStyle(SpanStyle(color = Color(0xFF3DFF6E))) { append("$crackable crackable") }
                            if (partial > 0) {
                                append("  ·  ")
                                withStyle(SpanStyle(color = Color(0xFFFFA533))) { append("$partial partial") }
                            }
                        },
                        fontSize = 11.sp, fontFamily = TerminalMono
                    )
                }
                Spacer(Modifier.height(4.dp))
            }

            // ── the capture rows ── tap any row to open its detail sheet.
            items(filtered, key = { it.key }) { c ->
                val k = CrackEngine.norm(c.bssid)
                val rowState = when {
                    currentKey == k -> if (currentPaused) RowCrack.PAUSED else RowCrack.RUNNING
                    k in queuedKeys -> RowCrack.QUEUED
                    k in crackExhausted -> RowCrack.EXHAUSTED
                    k in crackAttempted -> RowCrack.ATTEMPTED
                    else -> RowCrack.NONE
                }
                CaptureDetailRow(c, primary, dim, onSurface, onClick = { detailCapture = c }, rowState = rowState)
            }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }

    if (showFilters) {
        FiltersSheet(
            geo = geoOnly, crackableF = crackableOnly, cracked = crackedOnly,
            onGeo = { geoOnly = !geoOnly },
            onCrackable = { crackableOnly = !crackableOnly },
            onCracked = { crackedOnly = !crackedOnly },
            onDismiss = { showFilters = false },
        )
    }

    if (showOptions) {
        CrackPowerSheet(
            gentleCpu = gentleCpu, chargerOnly = chargerOnly, lowBatteryStop = lowBatteryStop,
            onGentle = { CrackSettings.setGentleCpu(context, !gentleCpu) },
            onCharger = { CrackSettings.setChargerOnly(context, !chargerOnly) },
            onLowBatt = { CrackSettings.setLowBatteryStop(context, !lowBatteryStop) },
            onDismiss = { showOptions = false },
        )
    }

    if (showManage) {
        ManageCapturesSheet(
            count = captures.size,
            onClearPhone = { viewModel.clearPhoneCaptures(); showManage = false },
            onWipeDevice = { viewModel.wipeDeviceCaptures(); showManage = false },
            onDismiss = { showManage = false },
        )
    }

    clusterCaptures?.let { caps ->
        ClusterPickerSheet(
            captures = caps,
            onPick = { clusterCaptures = null; detailCapture = it },
            onDismiss = { clusterCaptures = null },
        )
    }

    detailCapture?.let { cap ->
        val k = CrackEngine.norm(cap.bssid)
        val running = when (val s = crackState) {
            is CrackState.Running -> CrackEngine.norm(s.bssid) == k
            is CrackState.Paused -> CrackEngine.norm(s.bssid) == k
            else -> false
        }
        CaptureDetailSheet(
            capture = cap,
            onPhoneCrackable = WpaCracker.isOnPhoneCrackable(cap.hash22000),
            isRunning = running,
            isQueued = crackQueue.any { CrackEngine.norm(it.bssid) == k },
            isExhausted = k in crackExhausted,
            isAttempted = k in crackAttempted,
            onCrack = { viewModel.enqueueCrack(cap); detailCapture = null },
            onDequeue = { viewModel.dequeueCrack(cap); detailCapture = null },
            onStop = { viewModel.cancelCrack() },   // keep sheet open so the status updates live
            onForget = { viewModel.forgetCapture(cap); detailCapture = null },
            onDeleteDevice = { viewModel.deleteDeviceCapture(cap); detailCapture = null },
            onDismiss = { detailCapture = null },
        )
    }
}

/** Full detail for one capture (tap a row): all we know about it + the crack action. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CaptureDetailSheet(
    capture: CaptureEntry,
    onPhoneCrackable: Boolean,
    isRunning: Boolean,
    isQueued: Boolean,
    isExhausted: Boolean,
    isAttempted: Boolean,
    onCrack: () -> Unit,
    onDequeue: () -> Unit,
    onStop: () -> Unit,
    onForget: () -> Unit,
    onDeleteDevice: () -> Unit,
    onDismiss: () -> Unit,
) {
    val primary = MaterialTheme.colorScheme.primary
    val dim = MaterialTheme.colorScheme.onSurfaceVariant
    val onSurface = MaterialTheme.colorScheme.onSurface
    val green = Color(0xFF3DFF6E)
    val warn = Color(0xFFFFA533)
    val danger = Color(0xFFFF5C5C)
    val clipboard = LocalClipboardManager.current
    var confirmForget by remember(capture.key) { mutableStateOf(false) }
    var confirmDelete by remember(capture.key) { mutableStateOf(false) }
    var hashCopied by remember(capture.key) { mutableStateOf(false) }
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = Color(0xFF02060A), contentColor = primary) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 28.dp)
        ) {
            Text(
                capture.ssid.ifBlank { "(hidden)" },
                color = onSurface, fontWeight = FontWeight.Bold, fontSize = 15.sp,
                fontFamily = TerminalMono, maxLines = 1, softWrap = false
            )
            Spacer(Modifier.height(12.dp))
            DetailKv("bssid", capture.bssid.ifBlank { "—" }, dim, onSurface)
            capture.band?.let { b ->
                DetailKv("band", "${if (b == "5") "5 GHz" else "2.4 GHz"} · ch ${capture.channel}", dim, onSurface)
            }
            DetailKv("quality", capture.quality ?: "unknown", dim, onSurface)
            DetailKv(
                "location",
                if (capture.isGeolocated)
                    "%.5f, %.5f  ±%.0fm".format(capture.latitude, capture.longitude, capture.accuracy ?: 0.0)
                else "not geolocated",
                dim, onSurface
            )
            DetailKv("captured", captureWhen(capture.timestamp), dim, onSurface)
            val status = when {
                capture.isCracked -> "cracked"
                isRunning -> "cracking…"
                isQueued -> "queued"
                isExhausted -> "no match (wordlist searched)"
                isAttempted -> "tried — stopped before finishing"
                onPhoneCrackable -> "ready to crack on-phone"
                capture.isCrackable -> "crackable — no on-phone hash yet"
                capture.isPartial -> "partial — can't crack"
                else -> "—"
            }
            DetailKv("status", status, dim, if (capture.isCracked) green else onSurface)
            if (capture.isCracked) DetailKv("password", capture.password ?: "", dim, green)

            // Search options for THIS crack, right where you launch it. quick/mangle define how
            // much gets tried and lock in when the run starts. Persisted (same toggles as the
            // [ options ] sheet), so a choice here sticks. Only shown when a crack can be started.
            val canStart = onPhoneCrackable && !isRunning && !isQueued && !capture.isCracked && !isExhausted
            if (canStart) {
                val ctx = LocalContext.current
                val quick by CrackSettings.quickCrack.collectAsState()
                val mangle by CrackSettings.mangle.collectAsState()
                Spacer(Modifier.height(14.dp))
                Text("wordlist", color = dim, fontSize = 11.sp, fontFamily = TerminalMono)
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip("quick", quick, primary, dim) { CrackSettings.setQuickCrack(ctx, !quick) }
                    FilterChip("mangle", mangle, primary, dim) { CrackSettings.setMangle(ctx, !mangle) }
                }
                Spacer(Modifier.height(5.dp))
                Text(
                    "quick — try only the top ~25k words (fast, may miss).\n" +
                        "mangle — also try variants of each: Word1 · Word123! · Word2024 · Caps · l33t (wider, slower).",
                    color = dim, fontSize = 10.sp, fontFamily = TerminalMono, lineHeight = 14.sp
                )
            }

            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                when {
                    isRunning -> SheetButton("[ stop ]", danger, onStop)
                    capture.isCracked ->
                        SheetButton("[ copy password ]", green) {
                            capture.password?.let { clipboard.setText(AnnotatedString(it)) }
                        }
                    isQueued -> SheetButton("[ remove from queue ]", warn, onDequeue)
                    onPhoneCrackable && !isExhausted ->
                        SheetButton("[ crack on phone ]", green, onCrack)
                    else -> {}
                }
                SheetButton("[ close ]", dim, onDismiss)
            }

            // Offload: copy the hashcat-22000 line to crack on a PC/GPU (millions/s vs the phone's
            // hundreds) — for keys the phone can't get. Shown whenever we have a distilled hash.
            if (!capture.hash22000.isNullOrBlank()) {
                Spacer(Modifier.height(10.dp))
                SheetButton(if (hashCopied) "[ hash copied ✓ ]" else "[ copy hash ]", primary) {
                    clipboard.setText(AnnotatedString(capture.hash22000!!))
                    hashCopied = true
                }
                Spacer(Modifier.height(3.dp))
                Text(
                    "paste into `hashcat -m 22000` on a PC for GPU cracking",
                    color = dim, fontSize = 10.sp, fontFamily = TerminalMono
                )
            }

            // Per-capture removal (two taps each). Forget = phone only (a linked Pi resends it);
            // delete on device removes the .pcap on the Pi too (irreversible).
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                SheetButton(if (confirmForget) "[ confirm forget ]" else "[ forget ]", warn) {
                    if (confirmForget) onForget() else confirmForget = true
                }
                SheetButton(if (confirmDelete) "[ confirm — DELETE ]" else "[ delete on device ]", danger) {
                    if (confirmDelete) onDeleteDevice() else confirmDelete = true
                }
            }
        }
    }
}

/** key : value line in the capture detail sheet. */
@Composable
private fun DetailKv(k: String, v: String, dim: Color, valueColor: Color) {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        Text(k.padEnd(10), color = dim, fontSize = 12.sp, fontFamily = TerminalMono)
        Text(v, color = valueColor, fontSize = 12.sp, fontFamily = TerminalMono)
    }
}

@Composable
private fun SheetButton(label: String, color: Color, onClick: () -> Unit) {
    Text(
        label, color = color, fontSize = 13.sp, fontFamily = TerminalMono,
        modifier = Modifier
            .border(1.dp, color.copy(alpha = 0.6f))
            .clickable { onClick() }
            .padding(horizontal = 10.dp, vertical = 8.dp)
    )
}

/** Manage captures: clear the phone cache, or wipe the Pi's handshakes too. Each action takes two
 *  taps (the second confirms) since wiping the device is irreversible. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ManageCapturesSheet(
    count: Int,
    onClearPhone: () -> Unit,
    onWipeDevice: () -> Unit,
    onDismiss: () -> Unit,
) {
    val primary = MaterialTheme.colorScheme.primary
    val dim = MaterialTheme.colorScheme.onSurfaceVariant
    val onSurface = MaterialTheme.colorScheme.onSurface
    val warn = Color(0xFFFFA533)
    val danger = Color(0xFFFF5C5C)
    var confirmPhone by remember { mutableStateOf(false) }
    var confirmWipe by remember { mutableStateOf(false) }
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = Color(0xFF02060A), contentColor = primary) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 28.dp)) {
            Text("[ MANAGE CAPTURES ]", color = primary, fontWeight = FontWeight.Bold, fontSize = 15.sp, fontFamily = TerminalMono)
            Spacer(Modifier.height(4.dp))
            Text(
                "$count in the list · cracked passwords are always kept",
                color = dim, fontSize = 11.sp, fontFamily = TerminalMono
            )

            Spacer(Modifier.height(16.dp))
            Text("clear phone cache", color = onSurface, fontSize = 12.sp, fontFamily = TerminalMono)
            Spacer(Modifier.height(2.dp))
            Text(
                "Drops the locally-stored captures. A connected Pi resends its history, so this mainly prunes stale offline grabs.",
                color = dim, fontSize = 10.sp, fontFamily = TerminalMono
            )
            Spacer(Modifier.height(6.dp))
            SheetButton(
                if (confirmPhone) "[ confirm — clear phone ]" else "[ clear phone cache ]",
                if (confirmPhone) warn else primary,
            ) { if (confirmPhone) onClearPhone() else confirmPhone = true }

            Spacer(Modifier.height(18.dp))
            Text("wipe device handshakes", color = onSurface, fontSize = 12.sp, fontFamily = TerminalMono)
            Spacer(Modifier.height(2.dp))
            Text(
                "Deletes the .pcap handshakes on the Pwnagotchi — irreversible — and clears the phone too.",
                color = dim, fontSize = 10.sp, fontFamily = TerminalMono
            )
            Spacer(Modifier.height(6.dp))
            SheetButton(
                if (confirmWipe) "[ confirm — WIPE device ]" else "[ wipe device handshakes ]",
                danger,
            ) { if (confirmWipe) onWipeDevice() else confirmWipe = true }

            Spacer(Modifier.height(18.dp))
            SheetButton("[ close ]", dim, onDismiss)
        }
    }
}

/** Picker shown when a tapped map cell holds several captures — choose which to open. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ClusterPickerSheet(
    captures: List<CaptureEntry>,
    onPick: (CaptureEntry) -> Unit,
    onDismiss: () -> Unit,
) {
    val primary = MaterialTheme.colorScheme.primary
    val dim = MaterialTheme.colorScheme.onSurfaceVariant
    val onSurface = MaterialTheme.colorScheme.onSurface
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = Color(0xFF02060A), contentColor = primary) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 28.dp)
        ) {
            Text("[ ${captures.size} HERE ]", color = primary, fontWeight = FontWeight.Bold, fontSize = 15.sp, fontFamily = TerminalMono)
            Spacer(Modifier.height(4.dp))
            Text("several captures at this spot — pick one", color = dim, fontSize = 11.sp, fontFamily = TerminalMono)
            Spacer(Modifier.height(10.dp))
            Column(Modifier.heightIn(max = 380.dp).verticalScroll(rememberScrollState())) {
                captures.forEach { c ->
                    Row(
                        Modifier.fillMaxWidth().clickable { onPick(c) }.padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                c.ssid.ifBlank { c.bssid.ifBlank { "(hidden)" } },
                                color = onSurface, fontSize = 13.sp, fontFamily = TerminalMono, maxLines = 1
                            )
                            Text(
                                if (c.isCracked) "cracked" else (c.quality ?: "unknown"),
                                color = if (c.isCracked) Color(0xFF3DFF6E) else dim,
                                fontSize = 10.sp, fontFamily = TerminalMono
                            )
                        }
                        Text("▸", color = primary, fontSize = 13.sp, fontFamily = TerminalMono)
                    }
                }
            }
        }
    }
}

/** "22h ago · 2026-07-27 12:30" from a Unix-seconds timestamp. */
private fun captureWhen(ts: Long?): String {
    if (ts == null || ts <= 0) return "—"
    val date = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.getDefault())
        .format(java.util.Date(ts * 1000))
    return "${relativeAgeLocal(ts)} ago · $date"
}

/** Bottom sheet: which captures to show (pure display filters). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FiltersSheet(
    geo: Boolean, crackableF: Boolean, cracked: Boolean,
    onGeo: () -> Unit, onCrackable: () -> Unit, onCracked: () -> Unit,
    onDismiss: () -> Unit,
) {
    val primary = MaterialTheme.colorScheme.primary
    val dim = MaterialTheme.colorScheme.onSurfaceVariant
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = Color(0xFF02060A),
        contentColor = primary,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(bottom = 28.dp)
        ) {
            Text("[ FILTERS ]", color = primary, fontWeight = FontWeight.Bold, fontSize = 15.sp, fontFamily = TerminalMono)
            Spacer(Modifier.height(4.dp))
            Text("show only captures matching…", color = dim, fontSize = 11.sp, fontFamily = TerminalMono)
            Spacer(Modifier.height(10.dp))
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FilterChip("geo", geo, primary, dim, onGeo)
                FilterChip("crackable", crackableF, primary, dim, onCrackable)
                FilterChip("cracked", cracked, primary, dim, onCracked)
            }
        }
    }
}

/** Bottom sheet: ongoing cracking-power policy (applies to whatever's cracking). The per-crack
 *  search choices — quick / mangle — live on each capture's crack sheet, not here. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CrackPowerSheet(
    gentleCpu: Boolean, chargerOnly: Boolean, lowBatteryStop: Boolean,
    onGentle: () -> Unit, onCharger: () -> Unit, onLowBatt: () -> Unit,
    onDismiss: () -> Unit,
) {
    val primary = MaterialTheme.colorScheme.primary
    val dim = MaterialTheme.colorScheme.onSurfaceVariant
    val context = LocalContext.current
    val disabledGens by CrackSettings.disabledGenerators.collectAsState()
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = Color(0xFF02060A),
        contentColor = primary,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(bottom = 28.dp)
        ) {
            Text("[ OPTIONS ]", color = primary, fontWeight = FontWeight.Bold, fontSize = 15.sp, fontFamily = TerminalMono)
            Spacer(Modifier.height(4.dp))
            Text("cracking power — keeps the phone cool/charged", color = dim, fontSize = 11.sp, fontFamily = TerminalMono)
            Spacer(Modifier.height(10.dp))
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FilterChip("easy cpu", gentleCpu, primary, dim, onGentle)
                FilterChip("charger only", chargerOnly, primary, dim, onCharger)
                FilterChip("stop <15%", lowBatteryStop, primary, dim, onLowBatt)
            }
            Spacer(Modifier.height(5.dp))
            Text(
                "easy cpu — cap at 2 cores (cooler, slower).\n" +
                    "charger only — crack only while plugged in.\n" +
                    "stop <15% — pause on low battery (unplugged).",
                color = dim, fontSize = 10.sp, fontFamily = TerminalMono, lineHeight = 14.sp
            )
            Spacer(Modifier.height(12.dp))
            Text("targeted candidates — smart guesses tried before the wordlist", color = dim, fontSize = 11.sp, fontFamily = TerminalMono)
            Spacer(Modifier.height(10.dp))
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // One chip per registered key generator — a new generator auto-appears here.
                KeyGenerators.registered.forEach { g ->
                    val on = g.id !in disabledGens
                    FilterChip(g.label, on, primary, dim) { CrackSettings.setGeneratorEnabled(context, g.id, !on) }
                }
            }
            Spacer(Modifier.height(5.dp))
            Text(
                "SpeedTouch — derive the exact SpeedTouch/Thomson key from the SSID.\n" +
                    "name guesses — the network name + common variants (digits/years).\n" +
                    "All off = a plain wordlist run.",
                color = dim, fontSize = 10.sp, fontFamily = TerminalMono, lineHeight = 14.sp
            )
        }
    }
}

/** Per-row crack status the list overlays onto a capture. */
private enum class RowCrack { NONE, QUEUED, RUNNING, PAUSED, EXHAUSTED, ATTEMPTED }

/** One capture row: geo marker, SSID, coords (if any), relative age. */
@Composable
private fun CaptureDetailRow(
    c: CaptureEntry,
    primary: Color,
    dim: Color,
    onSurface: Color,
    onClick: () -> Unit,
    rowState: RowCrack = RowCrack.NONE,
) {
    // Parse the hash once per row (not every recomposition) — it splits + hex-decodes twice.
    val onPhoneCrackable = remember(c.hash22000) { WpaCracker.isOnPhoneCrackable(c.hash22000) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(vertical = 3.dp)
    ) {
        Text(
            if (c.isGeolocated) "⌖ " else "· ",
            color = if (c.isGeolocated) primary else dim,
            fontSize = 12.sp, fontFamily = TerminalMono
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                c.ssid.ifBlank { "(hidden)" },
                color = onSurface, fontSize = 12.sp, fontFamily = TerminalMono, maxLines = 1
            )
            // Show the cracked password when we have it — the whole point of the loop.
            if (c.isCracked) {
                Text(
                    "pw: ${c.password}",
                    color = Color(0xFF3DFF6E), fontSize = 11.sp, fontFamily = TerminalMono, maxLines = 1
                )
            } else if (c.isGeolocated) {
                Text(
                    "%.5f, %.5f".format(c.latitude, c.longitude),
                    color = dim, fontSize = 10.sp, fontFamily = TerminalMono, maxLines = 1
                )
            }
        }
        // Band tag (2.4/5 GHz) from the capture's channel, when known.
        c.band?.let { b ->
            Text(
                if (b == "5") "5G" else "2.4G",
                color = if (b == "5") Color(0xFF6EC1FF) else dim,
                fontSize = 10.sp, fontFamily = TerminalMono, modifier = Modifier.padding(end = 8.dp)
            )
        }
        // Status tag: cracked > running/queued (on-phone) > ready-to-crack > crackable > partial.
        when {
            c.isCracked -> Text(
                "cracked",
                color = Color(0xFF3DFF6E), fontWeight = FontWeight.Bold, fontSize = 10.sp,
                fontFamily = TerminalMono, modifier = Modifier.padding(end = 8.dp)
            )
            rowState == RowCrack.RUNNING -> Text(
                "cracking…",
                color = primary, fontWeight = FontWeight.Bold, fontSize = 10.sp,
                fontFamily = TerminalMono, modifier = Modifier.padding(end = 8.dp)
            )
            rowState == RowCrack.PAUSED -> Text(
                "paused",
                color = Color(0xFFFFA533), fontSize = 10.sp,
                fontFamily = TerminalMono, modifier = Modifier.padding(end = 8.dp)
            )
            rowState == RowCrack.EXHAUSTED -> Text(
                // Whole wordlist searched on-phone, no hit — a lasting result, not re-offered.
                "no match",
                color = dim, fontSize = 10.sp,
                fontFamily = TerminalMono, modifier = Modifier.padding(end = 8.dp)
            )
            rowState == RowCrack.QUEUED -> Text(
                // Tap to remove from the queue.
                "queued ✕",
                color = Color(0xFFFFA533), fontSize = 10.sp,
                fontFamily = TerminalMono, modifier = Modifier.padding(end = 8.dp)
            )
            rowState == RowCrack.ATTEMPTED -> Text(
                // Started before but not finished (stopped/interrupted) — still crackable; tap to resume.
                "tried ▸",
                color = Color(0xFFFFA533).copy(alpha = 0.85f), fontSize = 10.sp,
                fontFamily = TerminalMono, modifier = Modifier.padding(end = 8.dp)
            )
            // We have the handshake (PMKID or EAPOL) on the phone → crackable locally right now.
            // Tap the row to queue it. Bright + arrow to read as actionable.
            onPhoneCrackable -> Text(
                "crack ▸",
                color = Color(0xFF3DFF6E), fontWeight = FontWeight.Bold, fontSize = 10.sp,
                fontFamily = TerminalMono, modifier = Modifier.padding(end = 8.dp)
            )
            c.isCrackable -> Text(
                // Crackable per quality, but the on-phone hash hasn't arrived yet — dimmer, not
                // yet actionable.
                if (c.quality == "pmkid") "pmkid" else "eapol",
                color = Color(0xFF3DFF6E).copy(alpha = 0.6f), fontSize = 10.sp, fontFamily = TerminalMono,
                modifier = Modifier.padding(end = 8.dp)
            )
            c.isPartial -> Text(
                "partial",
                color = Color(0xFFFFA533), fontSize = 10.sp, fontFamily = TerminalMono,
                modifier = Modifier.padding(end = 8.dp)
            )
        }
        Text(
            relativeAgeLocal(c.timestamp),
            color = dim, fontSize = 11.sp, fontFamily = TerminalMono
        )
    }
}

/**
 * On-phone crack status banner (Phase 4): download progress, live crack progress with a
 * terminal-style bar + rate + ETA and a cancel, or the finished result.
 */
@Composable
private fun CrackBanner(
    state: CrackState,
    queueCount: Int,
    onSkip: () -> Unit,
    onCancel: () -> Unit,
    onDismiss: () -> Unit,
) {
    val primary = MaterialTheme.colorScheme.primary
    val dim = MaterialTheme.colorScheme.onSurfaceVariant
    val error = MaterialTheme.colorScheme.error
    val green = Color(0xFF3DFF6E)
    // Live power policy, shown alongside the run's locked options so you can see everything in effect.
    val gentleCpu by CrackSettings.gentleCpu.collectAsState()
    val chargerOnly by CrackSettings.chargerOnly.collectAsState()
    val lowBatteryStop by CrackSettings.lowBatteryStop.collectAsState()
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, MaterialTheme.colorScheme.outline)
            .background(Color(0xFF02060A))
            .padding(8.dp)
    ) {
        when (state) {
            is CrackState.Downloading -> {
                Text(
                    "↓ downloading wordlist… ${(state.pct * 100).roundToInt()}%" +
                        if (queueCount > 0) " · $queueCount queued" else "",
                    color = dim, fontSize = 12.sp, fontFamily = TerminalMono
                )
                Spacer(Modifier.height(5.dp))
                CrackBar(state.pct, primary)
            }
            is CrackState.Running -> {
                val frac = if (state.total > 0) state.tried.toFloat() / state.total else 0f
                val eta = etaLocal(state.tried, state.total, state.perSec)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(
                        "⚙ cracking ${state.ssid}" + if (queueCount > 0) "  · $queueCount queued" else "",
                        color = primary, fontWeight = FontWeight.Bold, fontSize = 12.sp, fontFamily = TerminalMono,
                        maxLines = 1, modifier = Modifier.weight(1f)
                    )
                    // Skip → move to the next queued crack (only meaningful when something's queued).
                    if (queueCount > 0) {
                        Text(
                            "[ skip ]",
                            color = primary, fontSize = 12.sp, fontFamily = TerminalMono,
                            modifier = Modifier.clickable { onSkip() }.padding(end = 10.dp)
                        )
                    }
                    Text(
                        "[ stop ]",
                        color = error, fontSize = 12.sp, fontFamily = TerminalMono,
                        modifier = Modifier.clickable { onCancel() }
                    )
                }
                Spacer(Modifier.height(4.dp))
                // Options in effect: the run's locked type/engine/search (state.mode) plus the live
                // power policy. One scrollable line so it never wraps.
                val powerOpts = buildList {
                    if (gentleCpu) add("easy cpu")
                    if (chargerOnly) add("charger")
                    if (lowBatteryStop) add("stop<15%")
                }
                val optsLine = (listOf(state.mode).filter { it.isNotEmpty() } + powerOpts).joinToString(" · ")
                if (optsLine.isNotEmpty()) {
                    Text(
                        optsLine,
                        color = primary.copy(alpha = 0.7f), fontSize = 10.sp, fontFamily = TerminalMono,
                        maxLines = 1, softWrap = false,
                        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())
                    )
                    Spacer(Modifier.height(2.dp))
                }
                // Lead the progress line with the segment being tried right now (isp keys → wordlist).
                val phasePrefix = if (state.phase.isNotEmpty()) "${state.phase} · " else ""
                Text(
                    "$phasePrefix${state.tried} / ${state.total} (${(frac * 100).roundToInt()}%) · ${state.perSec}/s · eta $eta",
                    color = dim, fontSize = 11.sp, fontFamily = TerminalMono
                )
                Spacer(Modifier.height(5.dp))
                CrackBar(frac, primary)
            }
            is CrackState.Paused -> {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(
                        "⏸ paused · ${state.reason}",
                        color = Color(0xFFFFA533), fontWeight = FontWeight.Bold, fontSize = 12.sp,
                        fontFamily = TerminalMono, maxLines = 1, modifier = Modifier.weight(1f)
                    )
                    Text(
                        "[ stop ]", color = error, fontSize = 12.sp, fontFamily = TerminalMono,
                        modifier = Modifier.clickable { onCancel() }
                    )
                }
            }
            is CrackState.Done -> {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(
                        "✓ cracked ${state.ssid} · ${state.password}",
                        color = green, fontWeight = FontWeight.Bold, fontSize = 12.sp, fontFamily = TerminalMono,
                        maxLines = 1, modifier = Modifier.weight(1f)
                    )
                    Text(
                        "[ ok ]", color = dim, fontSize = 12.sp, fontFamily = TerminalMono,
                        modifier = Modifier.clickable { onDismiss() }
                    )
                }
            }
            is CrackState.Failed -> {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(
                        "✗ ${state.ssid}: ${state.reason}",
                        color = error, fontSize = 12.sp, fontFamily = TerminalMono,
                        maxLines = 1, modifier = Modifier.weight(1f)
                    )
                    Text(
                        "[ ok ]", color = dim, fontSize = 12.sp, fontFamily = TerminalMono,
                        modifier = Modifier.clickable { onDismiss() }
                    )
                }
            }
            CrackState.Idle -> {}
        }
    }
}

/** A terminal-style toggle chip: `[■] label` when on, `[ ] label` when off. */
@Composable
private fun FilterChip(label: String, on: Boolean, primary: Color, dim: Color, onToggle: () -> Unit) {
    Text(
        if (on) "[■] $label" else "[ ] $label",
        color = if (on) primary else dim,
        fontSize = 12.sp,
        fontFamily = TerminalMono,
        modifier = Modifier
            .border(1.dp, if (on) primary else MaterialTheme.colorScheme.outline)
            .clickable { onToggle() }
            .padding(horizontal = 8.dp, vertical = 6.dp)
    )
}

/** A chunky terminal-style progress bar (filled cells vs dim cells). */
@Composable
private fun CrackBar(fraction: Float, color: Color) {
    val cells = 24
    val filled = (fraction.coerceIn(0f, 1f) * cells).roundToInt()
    val off = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.22f)
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        repeat(cells) { i ->
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(8.dp)
                    .background(if (i < filled) color else off)
            )
        }
    }
}

/** ETA string from remaining candidates and current rate. */
private fun etaLocal(tried: Long, total: Long, perSec: Long): String {
    if (perSec <= 0L) return "…"
    val remaining = (total - tried).coerceAtLeast(0L)
    val secs = remaining / perSec
    return when {
        secs < 60 -> "${secs}s"
        secs < 3600 -> "${secs / 60}m${secs % 60}s"
        else -> "${secs / 3600}h${(secs % 3600) / 60}m"
    }
}

/**
 * Real-basemap pixel map: fetches OSM tiles for the capture bounding box, recolors them
 * to the phosphor palette as chunky nearest-neighbour pixels, and overlays each catch
 * (bright) plus your current position (cyan '@'-equivalent). Falls back to the offline
 * [AsciiHeatmap] when there's no network / tiles unavailable, so it always shows *some*
 * map. Loading + recolor run off the main thread.
 */
/** Map dispatcher: the smooth slippy renderer (continuous GPU zoom + pixel shader) on API 33+,
 *  else the coarse-grid fallback for older devices. */
@Composable
private fun CaptureMap(
    points: List<CaptureEntry>,
    current: GpsData?,
    onCatch: (List<CaptureEntry>) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
        SlippyPixelMap(points, current, onCatch, modifier)
    else
        PixelBasemap(points, current, onCatch, modifier)
}

/** Pinch past this scale (and settle) triggers a deeper-detail tile re-fetch of the viewport.
 *  Gated behind an actual pinch gesture ([userZoomed]) so the map's initial auto-zoom never fires it. */
private const val DEEP_ZOOM_TRIGGER = 2.5f

@Composable
private fun PixelBasemap(
    points: List<CaptureEntry>,
    current: GpsData?,
    onCatch: (List<CaptureEntry>) -> Unit = {},   // all captures in the tapped cell
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val dim = MaterialTheme.colorScheme.onSurfaceVariant
    val geo = remember(points) { points.filter { it.latitude != null && it.longitude != null } }
    val hasFix = current?.isValid() == true
    // Key the map state on ROUNDED geo bounds (+ a coarse fix cell), NOT the exact capture list — so
    // live capture churn and GPS jitter don't reload the map and throw away the user's zoom.
    val geoKey = if (geo.isEmpty()) "empty" else buildString {
        append((geo.minOf { it.latitude!! } * 1000).toInt()); append(',')
        append((geo.maxOf { it.latitude!! } * 1000).toInt()); append(',')
        append((geo.minOf { it.longitude!! } * 1000).toInt()); append(',')
        append((geo.maxOf { it.longitude!! } * 1000).toInt())
        if (hasFix && current != null) {
            append('|'); append((current.latitude * 100).toInt()); append(','); append((current.longitude * 100).toInt())
        }
    }

    var grid by remember(geoKey) { mutableStateOf<MapGrid?>(null) }
    var mapTiles by remember(geoKey) { mutableStateOf<MapTiles?>(null) }
    var failed by remember(geoKey) { mutableStateOf(false) }
    // Deeper-zoom-via-refetch state: keep the full-spread "home" map so a zoomed-in view can revert.
    // `zoomed` = showing a re-fetched finer viewport; `loadingDetail` gates the "loading detail…" hint.
    var homeTiles by remember(geoKey) { mutableStateOf<MapTiles?>(null) }
    var homeGrid by remember(geoKey) { mutableStateOf<MapGrid?>(null) }
    var zoomed by remember(geoKey) { mutableStateOf(false) }
    var loadingDetail by remember(geoKey) { mutableStateOf(false) }
    // True once the user actually pinches (so the deeper-zoom re-fetch can't misfire on the initial
    // auto-zoom when the map opens centred on "you"). Reset after a deepen / reset-to-home.
    var userZoomed by remember(geoKey) { mutableStateOf(false) }

    LaunchedEffect(geoKey) {
        grid = null; mapTiles = null; failed = false
        homeTiles = null; homeGrid = null; zoomed = false; loadingDetail = false
        if (geo.isEmpty()) { failed = true; return@LaunchedEffect }
        val pts = geo.map { GeoPoint(it.latitude!!, it.longitude!!) } +
            (current?.takeIf { it.isValid() }?.let { listOf(GeoPoint(it.latitude, it.longitude)) } ?: emptyList())
        val tiles = TileMapLoader.load(context, pts)
        if (tiles == null) { failed = true; return@LaunchedEffect }
        val built = withContext(Dispatchers.Default) { buildMapGrid(tiles, geo) }
        mapTiles = tiles; grid = built
        homeTiles = tiles; homeGrid = built   // remember the full spread as "home"
    }

    val g = grid
    when {
        g != null -> {
            // Precompute the "you" cell from the live fix (null if no fix / off-map).
            val youCell: Int? = run {
                val t = mapTiles ?: return@run null
                val cur = current ?: return@run null
                if (!cur.isValid()) return@run null
                val (px, py) = t.project(cur.latitude, cur.longitude)
                val c = (px / t.bitmap.width * g.cols).toInt()
                val r = (py / t.bitmap.height * g.rows).toInt()
                if (c in 0 until g.cols && r in 0 until g.rows) r * g.cols + c else null
            }
            Column(modifier = modifier) {
                // Pan + pinch-zoom state. Reset whenever the point set changes.
                var scale by remember(geoKey) { mutableStateOf(if (youCell != null) 3f else 1f) }
                var offset by remember(geoKey) { mutableStateOf(Offset.Zero) }
                var inited by remember(geoKey) { mutableStateOf(false) }

                // Cap the map height so a tall capture area can't push the list off-screen;
                // BoxWithConstraints gives us the pixel size for gesture clamping + centring.
                BoxWithConstraints(
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(1f)   // square map — matches the square tile composite
                ) {
                    val wPx = constraints.maxWidth.toFloat()
                    val hPx = constraints.maxHeight.toFloat()
                    // Fit-cell: integer size so squares + gaps stay pixel-perfect at scale 1.
                    val cw0 = floor(min(wPx / g.cols, hPx / g.rows)).coerceAtLeast(1f)

                    // Keep the content overlapping the viewport (can't drag it into the void).
                    fun clampOffset(o: Offset, s: Float): Offset {
                        val cW = cw0 * s * g.cols; val cH = cw0 * s * g.rows
                        val maxX = ((cW - wPx) / 2f).coerceAtLeast(0f)
                        val maxY = ((cH - hPx) / 2f).coerceAtLeast(0f)
                        return Offset(o.x.coerceIn(-maxX, maxX), o.y.coerceIn(-maxY, maxY))
                    }

                    // Deeper zoom: when the user pinches past the threshold (and settles), re-fetch
                    // tiles for the visible viewport and rebuild the SAME coarse grid over them — so
                    // pixels stay the same size + crisp while streets get finer (detail comes from
                    // tighter geographic bounds, never from up-sampling the old composite). One level
                    // deep; double-tap returns to the full spread.
                    LaunchedEffect(homeGrid, wPx, hPx) {
                        snapshotFlow { scale }.collectLatest { s ->
                            // Each settled pinch past the threshold deepens one more level (not a
                            // one-shot): z11 → z13 → … up to max tile detail. `zoomed` isn't gated
                            // here so it can keep going deeper; double-tap returns to the full spread.
                            if (loadingDetail || !userZoomed || s < DEEP_ZOOM_TRIGGER) return@collectLatest
                            delay(260)   // settle: collectLatest cancels this if the pinch continues
                            val gg = grid ?: return@collectLatest
                            val tt = mapTiles ?: return@collectLatest
                            if (tt.zoom >= 19) return@collectLatest   // already at max tile detail
                            val cwLocal = floor(min(wPx / gg.cols, hPx / gg.rows)).coerceAtLeast(1f) * scale
                            val ox = (wPx - cwLocal * gg.cols) / 2f + offset.x
                            val oy = (hPx - cwLocal * gg.rows) / 2f + offset.y
                            val colL = ((0f - ox) / cwLocal).coerceIn(0f, gg.cols.toFloat())
                            val colR = ((wPx - ox) / cwLocal).coerceIn(0f, gg.cols.toFloat())
                            val rowT = ((0f - oy) / cwLocal).coerceIn(0f, gg.rows.toFloat())
                            val rowB = ((hPx - oy) / cwLocal).coerceIn(0f, gg.rows.toFloat())
                            val bmpW = tt.bitmap.width.toFloat(); val bmpH = tt.bitmap.height.toFloat()
                            val c1 = tt.unproject(colL / gg.cols * bmpW, rowT / gg.rows * bmpH)
                            val c2 = tt.unproject(colR / gg.cols * bmpW, rowB / gg.rows * bmpH)
                            loadingDetail = true
                            val finer = TileMapLoader.load(context, listOf(c1, c2))
                            if (finer != null) {
                                val finerGrid = withContext(Dispatchers.Default) { buildMapGrid(finer, geo) }
                                mapTiles = finer; grid = finerGrid; zoomed = true
                                scale = 1f; offset = Offset.Zero; userZoomed = false
                            }
                            loadingDetail = false
                        }
                    }

                    // Start centred on the live "you" fix (zoomed in), so the map opens on
                    // where you actually are rather than the whole spread.
                    LaunchedEffect(g, youCell, wPx, hPx) {
                        if (!inited && wPx > 0f) {
                            inited = true
                            if (youCell != null && scale > 1f) {
                                val cw = cw0 * scale
                                val uc = youCell % g.cols; val ur = youCell / g.cols
                                offset = clampOffset(
                                    Offset(cw * g.cols / 2f - (uc + 0.5f) * cw,
                                           cw * g.rows / 2f - (ur + 0.5f) * cw),
                                    scale,
                                )
                            }
                        }
                    }

                    Canvas(
                        modifier = Modifier
                            .matchParentSize()
                            .clipToBounds()
                            // Pinch to zoom, drag to pan. Squares stay crisp at any zoom
                            // because the Canvas re-draws rects at the scaled cell size —
                            // no bitmap resampling, so the pixel look is preserved.
                            .pointerInput(g) {
                                detectTransformGestures { centroid, pan, zoom, _ ->
                                    if (zoom != 1f) userZoomed = true   // a real pinch (not just a pan)
                                    val s0 = scale
                                    val s1 = (s0 * zoom).coerceIn(1f, 10f)
                                    // Zoom toward the pinch focal point (centroid), not the map centre,
                                    // so the map zooms where your fingers are — otherwise the deeper
                                    // re-fetch grabs the wrong area. Keep the content under `centroid`
                                    // fixed as scale goes s0 → s1.
                                    val ox0 = (wPx - cw0 * s0 * g.cols) / 2f + offset.x
                                    val oy0 = (hPx - cw0 * s0 * g.rows) / 2f + offset.y
                                    val colF = (centroid.x - ox0) / (cw0 * s0)
                                    val rowF = (centroid.y - oy0) / (cw0 * s0)
                                    val offX = (centroid.x - colF * cw0 * s1) - (wPx - cw0 * s1 * g.cols) / 2f
                                    val offY = (centroid.y - rowF * cw0 * s1) - (hPx - cw0 * s1 * g.rows) / 2f
                                    scale = s1
                                    offset = clampOffset(Offset(offX + pan.x, offY + pan.y), s1)
                                }
                            }
                            // Single-tap → open the nearest catch's detail; double-tap → reset view.
                            .pointerInput(g) {
                                detectTapGestures(
                                    onDoubleTap = {
                                        // Back to the full spread if we'd zoomed into a finer viewport.
                                        if (zoomed) { mapTiles = homeTiles; grid = homeGrid; zoomed = false }
                                        scale = 1f; offset = Offset.Zero; userZoomed = false
                                    },
                                    onTap = { pos ->
                                        // Invert the same transform the draw pass uses, find the closest
                                        // catch cell, and hand back all captures in it (the caller opens
                                        // one directly or shows a picker when the cell holds several).
                                        val cwt0 = floor(min(size.width.toFloat() / g.cols, size.height.toFloat() / g.rows)).coerceAtLeast(1f)
                                        val cwt = cwt0 * scale
                                        val oxt = (size.width - cwt * g.cols) / 2f + offset.x
                                        val oyt = (size.height - cwt * g.rows) / 2f + offset.y
                                        var bestCell = -1
                                        var bestD = Float.MAX_VALUE
                                        for (cell in g.catchByCell.keys) {
                                            val cx = oxt + (cell % g.cols + 0.5f) * cwt
                                            val cy = oyt + (cell / g.cols + 0.5f) * cwt
                                            val d = hypot(pos.x - cx, pos.y - cy)
                                            if (d < bestD) { bestD = d; bestCell = cell }
                                        }
                                        if (bestCell >= 0 && bestD <= maxOf(cwt * 1.5f, 48f)) {
                                            g.catchByCell[bestCell]?.let { onCatch(it) }
                                        }
                                    },
                                )
                            }
                    ) {
                        drawRect(Color(0xFF02060A), size = size)     // console ink background
                        val cw = cw0 * scale
                        val ox = (size.width - cw * g.cols) / 2f + offset.x
                        val oy = (size.height - cw * g.rows) / 2f + offset.y
                        val gap = (cw * 0.14f).coerceIn(1f, 6f)      // gap scales with zoom
                        val sq = (cw - gap).coerceAtLeast(1f)
                        // Basemap: grey squares (gapped).
                        for (r in 0 until g.rows) {
                            for (c in 0 until g.cols) {
                                val v = g.grey[r * g.cols + c]
                                if (v < 0) continue
                                drawRect(Color(v, v, v), topLeft = Offset(ox + c * cw, oy + r * cw), size = Size(sq, sq))
                            }
                        }
                        // Catches: bright green, same gapped square size as the basemap so the
                        // markers sit ON the grid instead of overflowing it (was drawn at full
                        // cell width `cw`, which made them larger than the map pixels).
                        val green = Color(0x3D, 0xFF, 0x6E)
                        for (i in g.catchCells) {
                            val c = i % g.cols; val r = i / g.cols
                            drawRect(green, topLeft = Offset(ox + c * cw, oy + r * cw), size = Size(sq, sq))
                        }
                        // You: orange — a warm colour clearly distinct from the green catches.
                        youCell?.let { i ->
                            val c = i % g.cols; val r = i / g.cols
                            drawRect(Color(0xFF, 0xA5, 0x33), topLeft = Offset(ox + c * cw, oy + r * cw), size = Size(sq, sq))
                        }
                    }
                }
                Spacer(Modifier.height(2.dp))
                Text(
                    if (loadingDetail) "loading detail…"
                    else "tap a catch to open · pinch to zoom · drag to pan · double-tap to reset",
                    color = dim.copy(alpha = 0.6f), fontSize = 9.sp, fontFamily = TerminalMono
                )
            }
        }
        // No network / no tiles → the original ASCII heatmap so there's always a map.
        failed -> AsciiHeatmap(points, current, modifier)
        else -> Text("  rendering map…", color = dim, fontSize = 11.sp, fontFamily = TerminalMono, modifier = modifier)
    }
}

/** A coarse grid extracted from the OSM tiles: per-cell grey value + catch cells. */
private class MapGrid(
    val cols: Int,
    val rows: Int,
    val grey: IntArray,          // per cell (row*cols+col): -1 = empty, else grey 0..255
    val catchCells: List<Int>,
    val catchByCell: Map<Int, List<CaptureEntry>>,   // cell → all captures there (tap-to-open)
)

/**
 * Downsample the OSM composite to a coarse grid and auto-contrast it into per-cell grey
 * values (the street network), plus the grid cells where catches land. The current
 * position is drawn as a live overlay by the renderer, not baked in here. The Canvas
 * renderer draws this at integer cell sizes for a pixel-perfect grid.
 */
private fun buildMapGrid(tiles: MapTiles, geo: List<CaptureEntry>): MapGrid {
    val src = tiles.bitmap
    val cols = 120
    val rows = (cols.toDouble() * src.height / src.width).roundToInt().coerceIn(30, 220)
    val small = android.graphics.Bitmap.createScaledBitmap(src, cols, rows, true)

    // Measure luminance per cell, then auto-contrast: background level (~60th percentile,
    // since most of a map is background) → 0, bright tail (98th) → 1. Adapts to dark-rural
    // or bright-urban tiles so roads always show.
    val lum = DoubleArray(cols * rows)
    for (yy in 0 until rows) {
        for (xx in 0 until cols) {
            val p = small.getPixel(xx, yy)
            lum[yy * cols + xx] = (0.299 * ((p shr 16) and 0xFF) +
                0.587 * ((p shr 8) and 0xFF) + 0.114 * (p and 0xFF)) / 255.0
        }
    }
    val sorted = lum.sorted()
    val lo = sorted[(sorted.size * 0.60).toInt().coerceIn(0, sorted.size - 1)]
    val hi = sorted[(sorted.size * 0.98).toInt().coerceIn(0, sorted.size - 1)].coerceAtLeast(lo + 0.02)

    val grey = IntArray(cols * rows) { -1 }
    for (i in lum.indices) {
        val t = ((lum[i] - lo) / (hi - lo)).coerceIn(0.0, 1.0)
        if (t > 0.12) grey[i] = (50 + t * 120).toInt().coerceIn(0, 255)
    }

    fun cellIndex(lat: Double, lon: Double): Int? {
        val (px, py) = tiles.project(lat, lon)
        val c = (px / src.width * cols).toInt()
        val r = (py / src.height * rows).toInt()
        if (c < 0 || r < 0 || c >= cols || r >= rows) return null
        return r * cols + c
    }
    val catchCells = geo.mapNotNull { cellIndex(it.latitude!!, it.longitude!!) }
    // cell → all captures there (a coarse cell can hold several), for tap-to-open / cluster picker.
    val catchByCell = HashMap<Int, MutableList<CaptureEntry>>()
    geo.forEach { c ->
        cellIndex(c.latitude!!, c.longitude!!)?.let { catchByCell.getOrPut(it) { mutableListOf() }.add(c) }
    }

    small.recycle()
    return MapGrid(cols, rows, grey, catchCells, catchByCell)
}

/**
 * ASCII block-char heatmap: projects geolocated captures onto a coarse grid and draws
 * each cell as a block glyph (· ░ ▒ ▓ █) whose weight = how many catches landed there.
 * Pure terminal — no basemap, no dependency. '@' marks your current position. Kept as
 * the offline fallback for [PixelBasemap].
 */
@Composable
private fun AsciiHeatmap(points: List<CaptureEntry>, current: GpsData?, modifier: Modifier = Modifier) {
    val primary = MaterialTheme.colorScheme.primary
    val markerColor = Color(0xFF6FE8FF)   // cyan "you are here"

    val cols = 32
    val rows = 16

    val geo = points.filter { it.latitude != null && it.longitude != null }
    if (geo.isEmpty()) return

    // Fit bounds to the CAPTURES so they spread across the grid. The current position
    // is often far away; including it would squash all captures into one cell — instead
    // cell() clamps "@" to the nearest edge (see coerceIn below).
    val lats = geo.map { it.latitude!! }
    val lons = geo.map { it.longitude!! }
    val minLat = lats.min(); val maxLat = lats.max()
    val minLon = lons.min(); val maxLon = lons.max()
    val midLat = (minLat + maxLat) / 2.0
    val cosLat = cos(midLat * PI / 180.0).coerceAtLeast(0.01)
    val latRange = (maxLat - minLat).takeIf { it > 0 } ?: 1e-4
    val lonRange = ((maxLon - minLon) * cosLat).takeIf { it > 0 } ?: 1e-4

    fun cell(lat: Double, lon: Double): Pair<Int, Int> {
        val nx = ((lon - minLon) * cosLat / lonRange).coerceIn(0.0, 1.0)
        val ny = ((lat - minLat) / latRange).coerceIn(0.0, 1.0)
        val col = (nx * (cols - 1)).roundToInt()
        val row = ((1.0 - ny) * (rows - 1)).roundToInt()   // north at top
        return col to row
    }

    val grid = Array(rows) { IntArray(cols) }
    geo.forEach { val (col, row) = cell(it.latitude!!, it.longitude!!); grid[row][col]++ }
    val maxCount = grid.maxOf { r -> r.maxOrNull() ?: 0 }.coerceAtLeast(1)
    val here = current?.let { cell(it.latitude, it.longitude) }

    val text = buildAnnotatedString {
        for (r in 0 until rows) {
            for (c in 0 until cols) {
                if (here != null && here.first == c && here.second == r) {
                    withStyle(SpanStyle(color = markerColor)) { append('@') }
                } else {
                    val n = grid[r][c]
                    if (n == 0) {
                        withStyle(SpanStyle(color = primary.copy(alpha = 0.14f))) { append('·') }
                    } else {
                        val ch = when {
                            n >= maxCount * 0.75 -> '█'
                            n >= maxCount * 0.50 -> '▓'
                            n >= maxCount * 0.25 -> '▒'
                            else                 -> '░'
                        }
                        withStyle(SpanStyle(color = primary)) { append(ch) }
                    }
                }
            }
            if (r < rows - 1) append('\n')
        }
    }

    Text(
        text = text,
        fontFamily = TerminalMono,
        fontSize = 12.sp,
        lineHeight = 13.sp,
        modifier = modifier
    )
}

// ── small local helpers (kept private to this screen) ───────────────────────

@Composable
private fun ConsoleRuleLocal() {
    Spacer(Modifier.height(6.dp))
    androidx.compose.material3.HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
    Spacer(Modifier.height(6.dp))
}

@Composable
private fun SearchField(query: String, onQuery: (String) -> Unit, primary: Color, dim: Color) {
    Box(modifier = Modifier.fillMaxWidth()) {
        if (query.isEmpty()) {
            Text("search ssid…", color = dim.copy(alpha = 0.6f), fontSize = 12.sp, fontFamily = TerminalMono)
        }
        BasicTextField(
            value = query,
            onValueChange = onQuery,
            singleLine = true,
            textStyle = androidx.compose.ui.text.TextStyle(
                color = primary, fontSize = 12.sp, fontFamily = TerminalMono
            ),
            cursorBrush = SolidColor(primary),
            modifier = Modifier.fillMaxWidth()
        )
    }
}

/** Compact "time ago" for a unix-seconds timestamp; "—" if unknown. */
private fun relativeAgeLocal(unixSeconds: Long?): String {
    if (unixSeconds == null || unixSeconds <= 0) return "—"
    val ageS = (System.currentTimeMillis() / 1000) - unixSeconds
    return when {
        ageS < 0 -> "—"
        ageS < 60 -> "${ageS}s"
        ageS < 3600 -> "${ageS / 60}m"
        ageS < 86400 -> "${ageS / 3600}h"
        else -> "${ageS / 86400}d"
    }
}
