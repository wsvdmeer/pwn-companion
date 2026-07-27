package com.wsvdmeer.pwncompanion.presentation.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import com.wsvdmeer.pwncompanion.crack.CrackState
import com.wsvdmeer.pwncompanion.presentation.MainViewModel
import com.wsvdmeer.pwncompanion.presentation.theme.TerminalMono
import com.wsvdmeer.pwncompanion.utils.GeoPoint
import com.wsvdmeer.pwncompanion.utils.MapTiles
import com.wsvdmeer.pwncompanion.utils.TileMapLoader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor
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
    var showManage by remember { mutableStateOf(false) }
    var detailCapture by remember { mutableStateOf<CaptureEntry?>(null) }

    // Gentle-knob power settings for cracking (persisted; also read by CrackEngine).
    val context = LocalContext.current
    LaunchedEffect(Unit) { CrackSettings.ensureLoaded(context) }
    val gentleCpu by CrackSettings.gentleCpu.collectAsState()
    val chargerOnly by CrackSettings.chargerOnly.collectAsState()
    val lowBatteryStop by CrackSettings.lowBatteryStop.collectAsState()
    val quickCrack by CrackSettings.quickCrack.collectAsState()
    val mangle by CrackSettings.mangle.collectAsState()

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
                    PixelBasemap(
                        points = captures.filter { it.isGeolocated },
                        current = gps?.takeIf { it.isValid() },
                        modifier = Modifier
                            .fillMaxWidth()
                            .border(1.dp, MaterialTheme.colorScheme.outline)
                            .background(Color(0xFF02060A))
                            .padding(4.dp)
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
                        if (activeFilters > 0) "[ options · $activeFilters ]" else "[ options ]",
                        color = if (activeFilters > 0) primary else dim,
                        fontSize = 12.sp, fontFamily = TerminalMono,
                        modifier = Modifier
                            .border(1.dp, if (activeFilters > 0) primary else MaterialTheme.colorScheme.outline)
                            .clickable { showFilters = true }
                            .padding(horizontal = 10.dp, vertical = 6.dp)
                    )
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
            showPower = crackable > 0,
            gentleCpu = gentleCpu, chargerOnly = chargerOnly, lowBatteryStop = lowBatteryStop,
            quickCrack = quickCrack, mangle = mangle,
            onGentle = { CrackSettings.setGentleCpu(context, !gentleCpu) },
            onCharger = { CrackSettings.setChargerOnly(context, !chargerOnly) },
            onLowBatt = { CrackSettings.setLowBatteryStop(context, !lowBatteryStop) },
            onQuick = { CrackSettings.setQuickCrack(context, !quickCrack) },
            onMangle = { CrackSettings.setMangle(context, !mangle) },
            onDismiss = { showFilters = false },
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

/** "22h ago · 2026-07-27 12:30" from a Unix-seconds timestamp. */
private fun captureWhen(ts: Long?): String {
    if (ts == null || ts <= 0) return "—"
    val date = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.getDefault())
        .format(java.util.Date(ts * 1000))
    return "${relativeAgeLocal(ts)} ago · $date"
}

/** Bottom sheet holding the capture filters + cracking-power knobs (keeps the screen uncluttered). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FiltersSheet(
    geo: Boolean, crackableF: Boolean, cracked: Boolean,
    onGeo: () -> Unit, onCrackable: () -> Unit, onCracked: () -> Unit,
    showPower: Boolean,
    gentleCpu: Boolean, chargerOnly: Boolean, lowBatteryStop: Boolean, quickCrack: Boolean,
    mangle: Boolean,
    onGentle: () -> Unit, onCharger: () -> Unit, onLowBatt: () -> Unit, onQuick: () -> Unit,
    onMangle: () -> Unit,
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
            Text("[ OPTIONS ]", color = primary, fontWeight = FontWeight.Bold, fontSize = 15.sp, fontFamily = TerminalMono)
            Spacer(Modifier.height(12.dp))
            Text("filter", color = dim, fontSize = 11.sp, fontFamily = TerminalMono)
            Spacer(Modifier.height(6.dp))
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FilterChip("geo", geo, primary, dim, onGeo)
                FilterChip("crackable", crackableF, primary, dim, onCrackable)
                FilterChip("cracked", cracked, primary, dim, onCracked)
            }
            if (showPower) {
                Spacer(Modifier.height(16.dp))
                Text("cracking", color = dim, fontSize = 11.sp, fontFamily = TerminalMono)
                Spacer(Modifier.height(6.dp))
                Row(
                    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // Quick: try only the top-N (fast, may miss) instead of the whole list.
                    FilterChip("quick", quickCrack, primary, dim, onQuick)
                    // Mangle: expand each word into common variants (Word123, Welkom2024!, …) — wider, slower.
                    FilterChip("mangle", mangle, primary, dim, onMangle)
                    FilterChip("easy cpu", gentleCpu, primary, dim, onGentle)
                    FilterChip("charger only", chargerOnly, primary, dim, onCharger)
                    FilterChip("stop <15%", lowBatteryStop, primary, dim, onLowBatt)
                }
            }
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
                // Lead with the handshake type + engine ("eapol · native") so it's clear what's
                // being cracked and whether the fast native path is in use.
                val modePrefix = if (state.mode.isNotEmpty()) "${state.mode} · " else ""
                Text(
                    "$modePrefix${state.tried} / ${state.total} (${(frac * 100).roundToInt()}%) · ${state.perSec}/s · eta $eta",
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

/** A terminal-style toggle chip: `[x] label` when on, `[ ] label` when off. */
@Composable
private fun FilterChip(label: String, on: Boolean, primary: Color, dim: Color, onToggle: () -> Unit) {
    Text(
        if (on) "[x] $label" else "[ ] $label",
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
@Composable
private fun PixelBasemap(points: List<CaptureEntry>, current: GpsData?, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val dim = MaterialTheme.colorScheme.onSurfaceVariant
    val geo = remember(points) { points.filter { it.latitude != null && it.longitude != null } }

    var grid by remember(points) { mutableStateOf<MapGrid?>(null) }
    var mapTiles by remember(points) { mutableStateOf<MapTiles?>(null) }
    var failed by remember(points) { mutableStateOf(false) }

    // Rebuild when a GPS fix first appears (so the tile bounds include your position);
    // small position changes after that are handled by the live overlay below, not a rebuild.
    val hasFix = current?.isValid() == true
    LaunchedEffect(points, hasFix) {
        grid = null; mapTiles = null; failed = false
        if (geo.isEmpty()) { failed = true; return@LaunchedEffect }
        val pts = geo.map { GeoPoint(it.latitude!!, it.longitude!!) } +
            (current?.takeIf { it.isValid() }?.let { listOf(GeoPoint(it.latitude, it.longitude)) } ?: emptyList())
        val tiles = TileMapLoader.load(context, pts)
        if (tiles == null) { failed = true; return@LaunchedEffect }
        mapTiles = tiles
        grid = withContext(Dispatchers.Default) { buildMapGrid(tiles, geo) }
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
                var scale by remember(points) { mutableStateOf(if (youCell != null) 3f else 1f) }
                var offset by remember(points) { mutableStateOf(Offset.Zero) }
                var inited by remember(points) { mutableStateOf(false) }

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
                                detectTransformGestures { _, pan, zoom, _ ->
                                    val ns = (scale * zoom).coerceIn(1f, 10f)
                                    scale = ns
                                    offset = clampOffset(offset + pan, ns)
                                }
                            }
                            // Double-tap to reset to the full fit-all view.
                            .pointerInput(g) {
                                detectTapGestures(onDoubleTap = { scale = 1f; offset = Offset.Zero })
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
                    "pinch to zoom · drag to pan · double-tap to reset",
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

    small.recycle()
    return MapGrid(cols, rows, grey, catchCells)
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
