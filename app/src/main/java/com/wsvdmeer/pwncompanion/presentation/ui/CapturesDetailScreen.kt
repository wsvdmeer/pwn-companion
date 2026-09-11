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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.ui.Alignment
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
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
import com.wsvdmeer.pwncompanion.crack.CrackSettings
import com.wsvdmeer.pwncompanion.crack.KeyGenerators
import com.wsvdmeer.pwncompanion.crack.CrackState
import com.wsvdmeer.pwncompanion.crack.CrackStatus
import com.wsvdmeer.pwncompanion.presentation.MainViewModel
import com.wsvdmeer.pwncompanion.presentation.theme.TerminalMono
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

/**
 * Full-screen [ captures ] detail: a pixel-basemap map of where handshakes were caught,
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
    // One snapshot answers per-network crack status; no more cross-referencing raw flows here.
    val crackSnapshot by viewModel.crackSnapshot.collectAsState()
    var query by remember { mutableStateOf("") }
    var geoOnly by remember { mutableStateOf(false) }
    var crackedOnly by remember { mutableStateOf(false) }
    var crackableOnly by remember { mutableStateOf(false) }
    var showFilters by remember { mutableStateOf(false) }
    var showOptions by remember { mutableStateOf(false) }
    var showManage by remember { mutableStateOf(false) }
    // Destructive manage actions (clear phone cache / wipe device) route through the shared
    // ConfirmSheet, same as reboot/shutdown — a single tap opens it, confirm fires the action.
    var pendingConfirm by remember { mutableStateOf<ConfirmSpec?>(null) }
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
                    SlippyPixelMap(
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
                CaptureDetailRow(
                    c, primary, dim, onSurface,
                    onClick = { detailCapture = c },
                    rowState = crackSnapshot.statusOf(c.bssid),
                )
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
            partialCount = partial,
            onClearPhone = {
                showManage = false
                pendingConfirm = ConfirmSpec(
                    title = "[ clear phone cache ]",
                    body = "Drops the locally-stored captures. A connected Pi resends its history, so this mainly prunes stale offline grabs. Cracked passwords are kept.",
                    accent = Color(0xFFFFA533),
                    confirmLabel = "clear",
                    onConfirm = { viewModel.clearPhoneCaptures() },
                )
            },
            onCleanPartials = { viewModel.cleanDevicePartials(); showManage = false },
            onWipeDevice = {
                showManage = false
                pendingConfirm = ConfirmSpec(
                    title = "[ wipe device handshakes ]",
                    body = "Deletes the .pcap handshakes on the Pwnagotchi — irreversible — and clears the phone cache too. Cracked passwords are kept.",
                    accent = Color(0xFFFF5C5C),
                    confirmLabel = "WIPE",
                    onConfirm = { viewModel.wipeDeviceCaptures() },
                )
            },
            onDismiss = { showManage = false },
        )
    }

    pendingConfirm?.let { spec ->
        ConfirmSheet(spec = spec, onDismiss = { pendingConfirm = null })
    }

    clusterCaptures?.let { caps ->
        ClusterPickerSheet(
            captures = caps,
            onPick = { clusterCaptures = null; detailCapture = it },
            onDismiss = { clusterCaptures = null },
        )
    }

    detailCapture?.let { cap ->
        CaptureDetailSheet(
            capture = cap,
            onPhoneCrackable = WpaCracker.isOnPhoneCrackable(cap.hash22000),
            crackStatus = crackSnapshot.statusOf(cap.bssid),
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
    crackStatus: CrackStatus,
    onCrack: () -> Unit,
    onDequeue: () -> Unit,
    onStop: () -> Unit,
    onForget: () -> Unit,
    onDeleteDevice: () -> Unit,
    onDismiss: () -> Unit,
) {
    // "Running" folds in the power-paused state — both mean a crack is in flight for this network.
    val isRunning = crackStatus == CrackStatus.RUNNING || crackStatus == CrackStatus.PAUSED
    val isQueued = crackStatus == CrackStatus.QUEUED
    val isExhausted = crackStatus == CrackStatus.EXHAUSTED
    val isAttempted = crackStatus == CrackStatus.ATTEMPTED
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

/** Manage captures: clear the phone cache, or wipe the Pi's handshakes too. The two destructive
 *  actions (clear phone / wipe device) open the shared ConfirmSheet; clean-partials is safe
 *  housekeeping and keeps its inline two-tap confirm. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ManageCapturesSheet(
    count: Int,
    partialCount: Int,
    onClearPhone: () -> Unit,
    onCleanPartials: () -> Unit,
    onWipeDevice: () -> Unit,
    onDismiss: () -> Unit,
) {
    val primary = MaterialTheme.colorScheme.primary
    val dim = MaterialTheme.colorScheme.onSurfaceVariant
    val onSurface = MaterialTheme.colorScheme.onSurface
    val warn = Color(0xFFFFA533)
    val danger = Color(0xFFFF5C5C)
    var confirmPartials by remember { mutableStateOf(false) }
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
            SheetButton("[ clear phone cache ]", primary, onClearPhone)

            // Clean partials — only shown when there are uncrackable partials to remove. Deletes
            // just those on the device (keeping every crackable grab), so it's safe housekeeping.
            if (partialCount > 0) {
                Spacer(Modifier.height(18.dp))
                Text("clean partials", color = onSurface, fontSize = 12.sp, fontFamily = TerminalMono)
                Spacer(Modifier.height(2.dp))
                Text(
                    "Deletes the $partialCount uncrackable partial grab${if (partialCount == 1) "" else "s"} on the Pwnagotchi (no handshake to crack) and drops them here. Crackable captures are kept; a partial still being written to is left alone.",
                    color = dim, fontSize = 10.sp, fontFamily = TerminalMono
                )
                Spacer(Modifier.height(6.dp))
                SheetButton(
                    if (confirmPartials) "[ confirm — clean $partialCount partial${if (partialCount == 1) "" else "s"} ]" else "[ clean partials ]",
                    warn,
                ) { if (confirmPartials) onCleanPartials() else confirmPartials = true }
            }

            Spacer(Modifier.height(18.dp))
            Text("wipe device handshakes", color = onSurface, fontSize = 12.sp, fontFamily = TerminalMono)
            Spacer(Modifier.height(2.dp))
            Text(
                "Deletes the .pcap handshakes on the Pwnagotchi — irreversible — and clears the phone too.",
                color = dim, fontSize = 10.sp, fontFamily = TerminalMono
            )
            Spacer(Modifier.height(6.dp))
            SheetButton("[ wipe device handshakes ]", danger, onWipeDevice)

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

/** One capture row: geo marker, SSID, coords (if any), relative age. */
@Composable
private fun CaptureDetailRow(
    c: CaptureEntry,
    primary: Color,
    dim: Color,
    onSurface: Color,
    onClick: () -> Unit,
    rowState: CrackStatus = CrackStatus.NONE,
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
            rowState == CrackStatus.RUNNING -> Text(
                "cracking…",
                color = primary, fontWeight = FontWeight.Bold, fontSize = 10.sp,
                fontFamily = TerminalMono, modifier = Modifier.padding(end = 8.dp)
            )
            rowState == CrackStatus.PAUSED -> Text(
                "paused",
                color = Color(0xFFFFA533), fontSize = 10.sp,
                fontFamily = TerminalMono, modifier = Modifier.padding(end = 8.dp)
            )
            rowState == CrackStatus.EXHAUSTED -> Text(
                // Whole wordlist searched on-phone, no hit — a lasting result, not re-offered.
                "no match",
                color = dim, fontSize = 10.sp,
                fontFamily = TerminalMono, modifier = Modifier.padding(end = 8.dp)
            )
            rowState == CrackStatus.QUEUED -> Text(
                // Tap to remove from the queue.
                "queued ✕",
                color = Color(0xFFFFA533), fontSize = 10.sp,
                fontFamily = TerminalMono, modifier = Modifier.padding(end = 8.dp)
            )
            rowState == CrackStatus.ATTEMPTED -> Text(
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
