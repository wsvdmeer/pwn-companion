package com.wsvdmeer.pwncompanion.presentation.ui

import kotlin.math.roundToInt
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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.ContextCompat
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.style.TextAlign
import kotlinx.coroutines.delay
import com.wsvdmeer.pwncompanion.presentation.theme.TerminalBoxShape
import com.wsvdmeer.pwncompanion.presentation.theme.TerminalMono
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.LocationOn
import androidx.compose.material.icons.outlined.PhoneAndroid
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Favorite
import androidx.compose.material.icons.filled.Circle
import androidx.compose.material.icons.outlined.BarChart
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Build
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.material3.HorizontalDivider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.wsvdmeer.pwncompanion.utils.ImageUtil
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import android.app.Application
import com.wsvdmeer.pwncompanion.ai.PwnagotchiViewModel
import com.wsvdmeer.pwncompanion.ai.PwnagotchiViewModelFactory
import androidx.compose.ui.unit.sp
import com.wsvdmeer.pwncompanion.models.DeviceState
import com.wsvdmeer.pwncompanion.presentation.MainViewModel

/**
 * Top App Bar - Clean title bar with dark styling.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PwnCompanionTopAppBar(
    serverRunning: Boolean,
    deviceCount: Int
) {
    TopAppBar(
        title = {
            Column {
                Text(
                    "[ PWN_COMPANION ]",
                    fontWeight = FontWeight.ExtraBold,
                    fontSize = 20.sp,
                    color = MaterialTheme.colorScheme.primary
                )
                // Show server status with device count, console-style
                Row(
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Filled.Circle,
                        contentDescription = "Server status",
                        tint = if (serverRunning) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(8.dp)
                    )
                    Text(
                        text = if (serverRunning) "> online :: $deviceCount node${if (deviceCount != 1) "s" else ""}" else "> offline",
                        fontWeight = FontWeight.Normal,
                        fontSize = 10.sp,
                        color = if (serverRunning) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.error
                    )
                }
            }
        },
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = MaterialTheme.colorScheme.background,
            titleContentColor = MaterialTheme.colorScheme.onSurface
        )
    )
}

/**
 * Main Content Area - Scrollable content with image at top, dark expressive styling.
 */
@Composable
fun MainContentArea(
    paddingValues: PaddingValues,
    mainViewModel: MainViewModel,
    isServerRunning: Boolean,
    connectedDevices: List<DeviceState>,
    currentImageData: String?,
    currentImageDeviceId: String?,
    currentStatusMessage: String?,
    gpsData: com.wsvdmeer.pwncompanion.models.GpsData?,
    queueSize: Int,
    errorMessage: String?,
    onErrorDismissed: () -> Unit,
    learningStats: com.wsvdmeer.pwncompanion.models.LearningStats?  // NEW
) {
    // Create AI ViewModel using fully-qualified path to avoid parameter name shadowing
    val context = LocalContext.current
    val pwnagotchiVM = androidx.lifecycle.viewmodel.compose.viewModel<PwnagotchiViewModel>(
        factory = PwnagotchiViewModelFactory(context.applicationContext as Application)
    )

    // Live terminal event feed
    val eventLog by mainViewModel.eventLog.collectAsState()

    // Geolocated handshake capture log (seeded from device on connect, grows live)
    val captures by mainViewModel.captures.collectAsState()

    // Per-epoch device telemetry (vitals / reward / mood)
    val telemetry by mainViewModel.telemetry.collectAsState()

    // Channels the app is steering the device's recon toward (learning → attack)
    val channelPriority by mainViewModel.channelPriority.collectAsState()
    // Live personality-tuner readout (re-implements jayofelony's removed RL param-tuner)
    val tuning by mainViewModel.tuning.collectAsState()


    // ── Sync Pwnagotchi AUTO/MANUAL mode → AI personality ────────────────────
    // Declared early so it can be read inside the network-event LaunchedEffect below.
    val isAutoMode by mainViewModel.isAutoMode.collectAsState()
    // Networking armed (desired) but maybe not bound yet — drives the "waiting for link"
    // states in the status line + command bar so the start button isn't a silent no-op.
    val networkingArmed by mainViewModel.networkingArmed.collectAsState()
    LaunchedEffect(isAutoMode) {
        pwnagotchiVM.setAutoMode(isAutoMode)
    }

    // ── Wire network events from Pwnagotchi → AI personality ─────────────────
    val lastNetworkEvent by mainViewModel.lastNetworkEvent.collectAsState()
    LaunchedEffect(lastNetworkEvent) {
        val event = lastNetworkEvent ?: return@LaunchedEffect
        // Normalise event_type: plugin sends snake_case ("idle", "handshakes_captured"),
        // but WifiEvent.type is expected in UPPER_SNAKE. Map idle only in AUTO mode.
        val eventType = event.eventType.uppercase()
        if (eventType == "IDLE" && !isAutoMode) return@LaunchedEffect  // skip idle in manual
        pwnagotchiVM.generatePersonality(
            com.wsvdmeer.pwncompanion.ai.WifiEvent(
                description  = event.description,
                type         = eventType,
                network      = event.network,
                count        = event.count,
                rssi         = event.signal,
                channel      = event.channel,
                security     = event.security,
                timestamp    = event.timestamp
            )
        )
        // Update capture count so the AI knows the running total
        if (event.totalCaptures > 0) {
            pwnagotchiVM.recordCapture(event.totalCaptures)
        }
    }

    // ── Sync pwnagotchi device mood → app personality mood ───────────────────
    val deviceMood by mainViewModel.deviceMood.collectAsState()
    LaunchedEffect(deviceMood) {
        val mood = deviceMood ?: return@LaunchedEffect
        pwnagotchiVM.applyDeviceMood(mood)
    }


    // ── Push learning stats into the voice engine so its lines can reference channel intel ─
    LaunchedEffect(learningStats) {
        pwnagotchiVM.updateLearningStats(learningStats)
    }

    // ── Feed device telemetry → emergent personality ─────────────────────────
    // reward = the device's own self-score; the *_for_epochs counters are its mood
    // over time; temp/cpu = a "running hot / overworked" stress signal.
    LaunchedEffect(telemetry) {
        telemetry?.let { pwnagotchiVM.applyTelemetry(it) }
    }

    // ── Sync the pet's real catch count + last-catch from the device's capture history ──
    // Fixes the creature panel showing "0 caught · hungry" despite a full capture log —
    // "caught" previously read only this session's live events. Timestamps are unix seconds.
    LaunchedEffect(captures) {
        val newestSec = captures.mapNotNull { it.timestamp }.maxOrNull()
        pwnagotchiVM.syncCaptureHistory(
            total = captures.size,
            lastCaptureMs = newestSec?.let { it * 1000L },
        )
    }

    // ── Sync Pwnagotchi device name → AI companion name ──────────────────────
    // pwnagotchiName comes from the plugin's status message (device_name field).
    // deviceName is the BT identifier (often the phone name) — never use it as fallback.
    val pwnagotchiName = remember(connectedDevices) {
        connectedDevices.firstOrNull()
            ?.pwnagotchiName?.takeIf(String::isNotBlank)
            ?: "Pwnagotchi"
    }
    LaunchedEffect(pwnagotchiName) {
        pwnagotchiVM.updatePwnagotchiName(pwnagotchiName)
    }


    val device = connectedDevices.firstOrNull()

    // ── deauth hunt advisor — phone-side analytics; the pet's voice only phrases it ────
    // Decides where to hunt from the device's own per-channel captures (autotune),
    // live client/AP counts and blind/thermal signals. Deterministic + always correct;
    // the pet's "where next?" just phrases advice.voiceFacts in-character.
    val minsSinceCatch = remember(captures) {
        captures.mapNotNull { it.timestamp }.maxOrNull()?.let {
            ((System.currentTimeMillis() / 1000) - it) / 60
        }
    }
    val advice = remember(device?.autotuneChannels, telemetry, learningStats, isAutoMode, minsSinceCatch, channelPriority) {
        val auto = device?.autotuneChannels
            ?.mapNotNull { (k, v) -> k.toIntOrNull()?.let { it to v } }?.toMap() ?: emptyMap()
        com.wsvdmeer.pwncompanion.ai.HuntAdvisor.recommend(
            autotune = auto,
            telemetry = telemetry,
            learning = learningStats,
            isAutoMode = isAutoMode,
            minutesSinceLastCatch = minsSinceCatch,
            steeredChannel = channelPriority.firstOrNull(),   // name the bandit's actual pick
        )
    }
    // Proactively voice a NEW alert (blind / hot / no-clients / dry) on onset, and a
    // RECOVERY line when it clears. alertKey changes only on real escalation, so no spam.
    var prevAlertKey by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(advice?.alertKey) {
        val a = advice
        val key = a?.alertKey
        if (connectedDevices.isNotEmpty()) {
            if (key != null && a!!.warnings.isNotEmpty()) {
                pwnagotchiVM.speakAdvice(a.voiceFacts, a.warnings.first())          // onset
            } else if (key == null && prevAlertKey != null) {
                val what = when {                                                 // recovered
                    prevAlertKey!!.startsWith("blind")     -> "I can see the air again"
                    prevAlertKey!!.startsWith("hot")       -> "cooled back down"
                    prevAlertKey!!.startsWith("noclients") -> "clients are back in range"
                    prevAlertKey!!.startsWith("dry")       -> "the catches are flowing again"
                    else                                    -> "back to normal"
                }
                pwnagotchiVM.speakAdvice("Good news — $what.", "$what.")
            }
        }
        prevAlertKey = key
    }
    // An AP seen repeatedly but never captured — a prime deauth target the pet can nag about.
    val untappedTarget by mainViewModel.untappedTarget.collectAsState()

    // Occasionally nag about that untapped target — throttled to ≤ once / 10 min so it
    // doesn't spam as the sighting count ticks up.
    var lastNagMs by remember { mutableStateOf(0L) }
    LaunchedEffect(untappedTarget) {
        val t = untappedTarget
        val now = System.currentTimeMillis()
        if (t != null && connectedDevices.isNotEmpty() && now - lastNagMs > 10 * 60 * 1000L) {
            lastNagMs = now
            pwnagotchiVM.speakAdvice("Unfinished business: $t.", t)
        }
    }

    // Feed the current recommendation + untapped target to the pet as live context, so its
    // ambient quips and recap cite real deauth data. Only include the channel pick when
    // there's an actual recommendation (not the "manual mode — not hunting" placeholder).
    LaunchedEffect(advice?.headline, untappedTarget) {
        val ctx = listOfNotNull(
            advice?.takeIf { it.channel != null }?.headline,
            untappedTarget?.let { "unfinished business: $it" },
        )
        pwnagotchiVM.updateHuntContext(ctx.joinToString(". ").ifBlank { null })
    }

    // Push richer capture stats the ViewModel can't derive itself (it has counts, not
    // the list): crackable/partial split, today's tally, the AP that keeps escaping.
    // Surfaced in the AI facts block so natural-language questions can cite them.
    LaunchedEffect(captures, untappedTarget) {
        val stats = mutableListOf<String>()
        val crackable = captures.count { it.isCrackable }
        val partial = captures.count { it.isPartial }
        val cracked = captures.count { it.isCracked }
        if (crackable > 0 || partial > 0) {
            stats += "crackable captures: $crackable real" + if (partial > 0) ", $partial uncrackable partials" else ""
        }
        if (cracked > 0) stats += "cracked passwords so far: $cracked"
        val dayAgo = System.currentTimeMillis() / 1000 - 24 * 3600
        val today = captures.count { (it.timestamp ?: 0L) >= dayAgo }
        if (today > 0) stats += "caught in last 24h: $today"
        untappedTarget?.let { stats += "keeps escaping (seen often, never caught): $it" }
        pwnagotchiVM.updateCaptureStats(stats)
    }

    // Light auto-insight: occasionally volunteer the best hunting spot, so the
    // "where's it best?" answer surfaces on its own (no asking). Fires when a best
    // channel is established/changes, throttled to ~30 min and only while hunting.
    var lastInsightMs by remember { mutableStateOf(0L) }
    LaunchedEffect(learningStats?.bestChannel, isAutoMode) {
        val ch = learningStats?.bestChannel ?: return@LaunchedEffect
        if (!isAutoMode) return@LaunchedEffect
        val now = System.currentTimeMillis()
        if (now - lastInsightMs < 30 * 60_000) return@LaunchedEffect
        lastInsightMs = now
        val pct = ((learningStats?.bestChannelSuccessRate ?: 0f) * 100).roundToInt()
        pwnagotchiVM.speakAdvice(
            "Your best hunting so far is channel $ch (${pct}% yield). Mention it as a tip.",
            "Best spot so far: ch$ch (${pct}% yield).",
        )
    }

    // Announce a network the moment wpa-sec cracks it. Cracks already on disk when the
    // app opens are seeded silently (a 20s warm-up) so we don't flood on connect; only
    // cracks that land DURING the session make the pet gloat.
    val announcedCracks = remember { mutableSetOf<String>() }
    val crackMountMs = remember { System.currentTimeMillis() }
    LaunchedEffect(captures) {
        val crackedNow = captures.filter { it.isCracked }
        if (System.currentTimeMillis() - crackMountMs < 20_000) {
            crackedNow.forEach { announcedCracks += it.key }
            return@LaunchedEffect
        }
        val fresh = crackedNow.filter { it.key !in announcedCracks }
        if (fresh.isNotEmpty()) {
            fresh.forEach { announcedCracks += it.key }
            val f = fresh.first()
            pwnagotchiVM.announceCracked(f.ssid, f.password ?: "")
        }
    }

    // ── Drive the pwnagotchi's on-screen voice with the app's AI ─────────────
    // Only generate voice-pool lines while a device is linked (saves battery), and
    // push the pool down whenever it changes or a device (re)connects so the plugin
    // can speak our fresh lines in the device's own speech bubble.
    LaunchedEffect(connectedDevices) {
        pwnagotchiVM.setDeviceConnected(connectedDevices.isNotEmpty())
    }
    val voicePool by pwnagotchiVM.voicePool.collectAsState()
    LaunchedEffect(voicePool, connectedDevices) {
        if (connectedDevices.isNotEmpty() && voicePool.isNotEmpty()) {
            mainViewModel.sendVoicePool(voicePool)
        }
    }

    // Detect missing runtime permissions so we can surface a fix-it banner.
    val missingPerms = buildList {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED) add("location")
        if (Build.VERSION.SDK_INT >= 31 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT)
            != PackageManager.PERMISSION_GRANTED) add("bluetooth")
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED) add("notifications")
    }

    // Single full-bleed console — no cards, no icons. Sections are separated by
    // rules and rendered as left-aligned monospace text.
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(paddingValues)
            .padding(horizontal = 12.dp),
        verticalArrangement = Arrangement.spacedBy(0.dp)
    ) {
        // ── permissions banner — only when something is missing ──
        if (missingPerms.isNotEmpty()) {
            item {
                Spacer(modifier = Modifier.height(8.dp))
                ConsolePermissionBanner(missingPerms) {
                    val i = Intent(
                        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.fromParts("package", context.packageName, null)
                    ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(i)
                }
                ConsoleRule()
            }
        }

        // ── device screen (top — the pwnagotchi's face is the first thing you see) ──
        // No header/title: the face + status flow together as the top block (the node
        // name lives in the status "node" row; the e-ink image needs no label).
        item { Spacer(modifier = Modifier.height(8.dp)) }
        if (currentImageData != null) {
            item {
                RawDeviceImage(currentImageData)
                // Caption the mirrored e-ink with the film-world the current quote is from
                // (the persistent franchise drives every line on screen right now).
                val voiceWorld by pwnagotchiVM.franchiseLabel.collectAsState()
                if (voiceWorld.isNotBlank()) {
                    Spacer(modifier = Modifier.height(3.dp))
                    Text(
                        "‹ ${voiceWorld.lowercase()} ›",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 11.sp, lineHeight = 14.sp,
                        maxLines = 1, textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                Spacer(modifier = Modifier.height(8.dp))
            }
        }

        // ── status ───────────────────────────────────────────────
        item {
            ConsoleStatusBlock(
                serverRunning = isServerRunning,
                networkingArmed = networkingArmed,
                device = device,
                queueSize = queueSize,
                isAutoMode = isAutoMode,
                gpsData = gpsData,
            )
            ConsoleRule()
        }

        // The pet's voice now lives on the pwnagotchi's own e-ink screen (voice pool), so the
        // phone no longer shows a speech/pet card — it's a pure console. The ViewModel is
        // still fed (mood/telemetry/captures/advice below) because that drives the e-ink voice.

        // ── standby — when no pwnagotchi is linked, show a "waiting" panel so the console
        // reads as idle-and-alive rather than a screen of empty sections. ──
        if (connectedDevices.isEmpty()) {
            item {
                ConsoleStandbyBlock(networkingArmed = networkingArmed)
                ConsoleRule()
            }
        }

        // ── alerts — only when there's actually something to warn about (a mission alert
        // or an untapped target to chase); otherwise the section is hidden entirely. ──
        if (connectedDevices.isNotEmpty() && advice != null &&
            (advice.warnings.isNotEmpty() || untappedTarget != null)) {
            item {
                ConsoleAdvisorBlock(advice, untappedTarget)
                ConsoleRule()
            }
        }

        // ── steering — live: the channels + params the phone is working right now ──
        if (connectedDevices.isNotEmpty() && (channelPriority.isNotEmpty() || tuning != null)) {
            item {
                ConsoleSteeringBlock(channelPriority, tuning)
                ConsoleRule()
            }
        }

        // ── history — aggregate learning (channels/times/locations) lives in its detail
        // screen now; the main console keeps only a one-line link to it. ──
        if (connectedDevices.isNotEmpty()) {
            item {
                val seen = learningStats?.totalObservations ?: 0
                // Top 3 channels by activity (matches the detail's "channels (by activity)"),
                // shown inline so the best spots are readable without opening the detail.
                val top3 = learningStats?.channels
                    ?.filter { it.observationCount > 0 }
                    ?.sortedByDescending { it.observationCount }
                    ?.take(3)
                    ?: emptyList()
                Column(
                    modifier = Modifier.fillMaxWidth().clickable {
                        mainViewModel.openDetail(com.wsvdmeer.pwncompanion.presentation.DetailScreen.LEARNING)
                    }
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        ConsoleLabel("[ history ]")
                        Text(
                            if (seen > 0) "$seen networks seen · details ›" else "details ›",
                            color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp,
                        )
                    }
                    if (top3.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(3.dp))
                        // Bar = relative activity (descending, like the detail); value = yield %.
                        // Same gauge style as [ vitals ].
                        val maxObs = (learningStats?.channels?.maxOfOrNull { it.observationCount } ?: 1).coerceAtLeast(1)
                        top3.forEach { ch ->
                            ConsoleBarRow(
                                "ch${ch.channel}",
                                ch.observationCount.toFloat() / maxObs,
                                "${(ch.successRate * 100).roundToInt()}%",
                                if (ch.isBest) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                            )
                        }
                    }
                }
                ConsoleRule()
            }
        }

        // ── vitals — device telemetry ────────────────────────────
        telemetry?.let { t ->
            if (t.hasData) {
                item {
                    ConsoleVitalsBlock(t)
                    ConsoleRule()
                }
            }
        }

        // ── gps details — only while linked (not needed on the idle/standby screen);
        // shown even without a fix yet, as 'acquiring…', so it never vanishes mid-session. ──
        if (connectedDevices.isNotEmpty()) {
            item {
                ConsoleGpsBlock(gpsData)
                ConsoleRule()
            }
        }

        // ── captures — geolocated handshake log ──────────────────
        if (captures.isNotEmpty()) {
            item {
                ConsoleCapturesBlock(captures, fileCount = device?.captureFileCount, wpaSecEnabled = device?.wpaSecEnabled, wpaSecOnline = device?.wpaSecOnline, onOpen = {
                    mainViewModel.openDetail(com.wsvdmeer.pwncompanion.presentation.DetailScreen.CAPTURES)
                })
                ConsoleRule()
            }
        }

        // ── log — at the bottom ──────────────────────────────────
        if (eventLog.isNotEmpty()) {
            item {
                EventFeedCard(eventLog, onOpen = {
                    mainViewModel.openDetail(com.wsvdmeer.pwncompanion.presentation.DetailScreen.LOG)
                })
                ConsoleRule()
            }
        }

        // ── settings ─────────────────────────────────────────────
        item {
            Text(
                "[ settings ]",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 12.sp, fontFamily = TerminalMono,
                modifier = Modifier
                    .clickable { mainViewModel.openDetail(com.wsvdmeer.pwncompanion.presentation.DetailScreen.SETTINGS) }
                    .padding(vertical = 6.dp)
            )
            ConsoleRule()
        }

        // ── command bar ──────────────────────────────────────────
        item {
            ConsoleCommandBar(
                isAutoMode = isAutoMode,
                isServerRunning = isServerRunning,
                networkingArmed = networkingArmed,
                deviceConnected = connectedDevices.isNotEmpty(),
                onAuto = { mainViewModel.requestMode(true) },
                onManual = { mainViewModel.requestMode(false) },
                onToggleService = {
                    // Running OR armed-and-waiting → a tap turns it off (disarm); only a
                    // fully-off service is (re)started.
                    if (isServerRunning || networkingArmed) mainViewModel.stopServer()
                    else mainViewModel.startServer()
                }
            )
            if (errorMessage != null) {
                Spacer(modifier = Modifier.height(10.dp))
                ErrorCard(message = errorMessage, onDismiss = onErrorDismissed)
            }
            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}

// ── Console building blocks ───────────────────────────────────────────────────

@Composable
private fun ConsoleRule() {
    HorizontalDivider(
        modifier = Modifier.padding(vertical = 7.dp),
        thickness = 1.dp,
        color = MaterialTheme.colorScheme.outline
    )
}

@Composable
private fun ConsolePermissionBanner(missing: List<String>, onFix: () -> Unit) {
    Column {
        ConsoleLabel("[ permissions ]")
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            "missing: ${missing.joinToString(", ")}",
            color = MaterialTheme.colorScheme.error,
            fontSize = 11.sp, lineHeight = 16.sp
        )
        Text(
            "tap to grant — some features need these.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 11.sp, lineHeight = 16.sp
        )
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            "[ grant access ]",
            color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.Bold,
            fontSize = 13.sp,
            modifier = Modifier
                .clip(TerminalBoxShape)
                .clickable { onFix() }
                .border(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.5f), TerminalBoxShape)
                .padding(horizontal = 14.dp, vertical = 10.dp)
        )
    }
}

@Composable
private fun ConsoleLabel(text: String) {
    Text(
        text.uppercase(),
        color = MaterialTheme.colorScheme.primary,
        fontWeight = FontWeight.Bold,
        fontSize = 12.sp,
        letterSpacing = 2.sp
    )
}

@Composable
private fun ConsoleRow(key: String, value: String, valueColor: Color) {
    Row {
        Text(
            key.padEnd(7) + ": ",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 12.sp,
            lineHeight = 18.sp
        )
        Text(value, color = valueColor, fontSize = 12.sp, lineHeight = 18.sp, maxLines = 1)
    }
}

@Composable
private fun ConsoleStatusBlock(
    serverRunning: Boolean,
    networkingArmed: Boolean,
    device: DeviceState?,
    queueSize: Int,
    isAutoMode: Boolean,
    gpsData: com.wsvdmeer.pwncompanion.models.GpsData?,
) {
    val online = device?.isConnected == true
    val primary = MaterialTheme.colorScheme.primary
    val dim = MaterialTheme.colorScheme.onSurfaceVariant
    ConsoleRow(
        "link",
        when {
            online -> "● ${device?.interfaceName ?: "?"}  ${device?.ipAddress ?: "?"}"
            serverRunning -> "○ listening…"
            // Armed but not bound: the server can't come up until the BT tether exists.
            networkingArmed -> "○ waiting for Bluetooth link to the pwnagotchi…"
            else -> "○ offline"
        },
        when {
            online -> primary
            networkingArmed -> MaterialTheme.colorScheme.tertiary
            else -> dim
        }
    )
    if (device != null) {
        ConsoleRow("node", "${device.pwnagotchiName ?: device.deviceName}   ws:${device.port}", MaterialTheme.colorScheme.onSurface)
        ConsoleRow("mode", if (isAutoMode) "auto · hunting" else "manual · paused", if (isAutoMode) primary else MaterialTheme.colorScheme.tertiary)
    }
    ConsoleRow(
        "version",
        "v${com.wsvdmeer.pwncompanion.BuildConfig.VERSION_NAME}",
        dim
    )
    // GPS is shown compactly with just a fix indicator here; full detail is in the
    // dedicated [ gps ] block below. No "queue" row — it flips 0↔1 each GPS tick and
    // would make the whole console jump.
    if (gpsData != null && gpsData.isValid()) {
        ConsoleRow("gps", "⌖ fix acquired", MaterialTheme.colorScheme.onSurface)
    }
}

/**
 * The deauth hunt advisor: a phone-computed "where to hunt now" line plus any
 * mission-blocking warnings (dead antenna, no clients to deauth, thermal, dry spell).
 * Deterministic and always correct — the pet's "where next?" button voices the same data.
 */
@Composable
private fun ConsoleAdvisorBlock(advice: com.wsvdmeer.pwncompanion.ai.HuntAdvice, untapped: String?) {
    val dim = MaterialTheme.colorScheme.onSurfaceVariant
    val error = MaterialTheme.colorScheme.error
    // Alerts only. The channel "try chX" headline was dropped — the [ steering ] section
    // shows the channels the bandit is actually working, which is the honest source of truth.
    Column {
        ConsoleLabel("[ alerts ]")
        Spacer(modifier = Modifier.height(4.dp))
        untapped?.let {
            Text("» chase: $it", color = dim, fontSize = 12.sp, lineHeight = 18.sp)
        }
        advice.warnings.forEach { w ->
            Text("! $w", color = error, fontSize = 12.sp, lineHeight = 18.sp)
        }
    }
}

/** Value's position (0..1) within [lo, hi] — for the tuner gauges. */
private fun rangeFrac(v: Int, lo: Int, hi: Int): Float =
    ((v - lo).toFloat() / (hi - lo)).coerceIn(0f, 1f)

/**
 * Visual readout of the phone-side AI's live control of the device: the steered
 * channels as on/off cells (2.4GHz 1–11, green = steered-to) and the auto-tuned
 * personality params as range gauges (reusing the vitals bar style), so you can see
 * at a glance what the re-implemented tuner is doing.
 */
@Composable
private fun ConsoleSteeringBlock(
    channels: List<Int>,
    tuning: com.wsvdmeer.pwncompanion.models.TuningState?,
) {
    val primary = MaterialTheme.colorScheme.primary
    val dim = MaterialTheme.colorScheme.onSurfaceVariant
    val off = dim.copy(alpha = 0.22f)
    Column {
        ConsoleLabel("[ steering ]")
        Spacer(modifier = Modifier.height(4.dp))
        if (channels.isNotEmpty()) {
            Row(modifier = Modifier.padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("recon".padEnd(7) + ": ", color = dim, fontSize = 12.sp, lineHeight = 18.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                    (1..11).forEach { ch ->
                        Box(Modifier.size(width = 8.dp, height = 11.dp).background(if (ch in channels) primary else off))
                    }
                }
                Text("  ch ${channels.joinToString(",")}", color = primary, fontSize = 12.sp, lineHeight = 18.sp, maxLines = 1)
            }
        }
        tuning?.let { t ->
            ConsoleBarRow("rssi", rangeFrac(t.minRssi, -90, -55), "${t.minRssi}dBm", primary)
            ConsoleBarRow("recon", rangeFrac(t.reconTime, 10, 60), "${t.reconTime}s", primary)
            ConsoleBarRow("ap ttl", rangeFrac(t.apTtl, 30, 300), "${t.apTtl}s", dim)
            ConsoleBarRow("sta ttl", rangeFrac(t.staTtl, 60, 600), "${t.staTtl}s", dim)
            ConsoleBarRow("hop", rangeFrac(t.hopRecon, 2, 30), "${t.hopRecon}s", dim)
        }
    }
}

/**
 * Standby panel — shown when no pwnagotchi is linked. A calm "waiting to hunt" screen (an
 * idle face + a slowly-rotating in-character line + what to do) so the disconnected console
 * reads as idle-and-alive rather than a page of empty sections.
 */
@Composable
private fun ConsoleStandbyBlock(networkingArmed: Boolean) {
    val primary = MaterialTheme.colorScheme.primary
    val dim = MaterialTheme.colorScheme.onSurfaceVariant
    val tertiary = MaterialTheme.colorScheme.tertiary

    val quips = listOf(
        "the spectrum's quiet… for now.",
        "sharpening my fangs.",
        "no signal is safe.",
        "waiting to hunt.",
        "dreaming of handshakes.",
    )
    var qi by remember { mutableStateOf(0) }
    LaunchedEffect(Unit) {
        while (true) { delay(4500); qi = (qi + 1) % quips.size }
    }

    Column {
        ConsoleLabel("[ standby ]")
        Spacer(modifier = Modifier.height(6.dp))
        Text("(⌐■_■)  zzz", color = primary, fontSize = 18.sp, lineHeight = 22.sp)
        Spacer(modifier = Modifier.height(6.dp))
        Text(quips[qi], color = dim, fontSize = 12.sp, lineHeight = 18.sp)
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            if (networkingArmed) "○ waiting for the Bluetooth tether…" else "○ no pwnagotchi linked",
            color = tertiary, fontSize = 12.sp, lineHeight = 18.sp,
        )
        Text(
            "› enable Bluetooth tethering + pair your pwnagotchi to link up",
            color = dim, fontSize = 11.sp, lineHeight = 16.sp,
        )
    }
}

/**
 * Dedicated GPS details block — the place to read the precise fix. The Pwnagotchi's
 * own screen now shows only a compact indicator + age; the numbers live here.
 */
@Composable
private fun ConsoleGpsBlock(gps: com.wsvdmeer.pwncompanion.models.GpsData?) {
    val dim = MaterialTheme.colorScheme.onSurfaceVariant
    Column {
        ConsoleLabel("[ gps ]")
        Spacer(modifier = Modifier.height(4.dp))
        // Always shown so the section never vanishes: until the phone has a real fix
        // (lat/lon != 0) we report "acquiring…" instead of hiding the whole block.
        if (gps == null || !gps.isValid()) {
            ConsoleRow("fix", "○ acquiring…", dim)
        } else {
            val ageS = if (gps.timestamp > 0) ((System.currentTimeMillis() - gps.timestamp) / 1000) else -1
            // lat+lon on one line, fix+accuracy on another (alt dropped as rarely useful).
            ConsoleRow("pos", "%.5f, %.5f".format(gps.latitude, gps.longitude), MaterialTheme.colorScheme.onSurface)
            val fix = if (ageS < 0) "—" else if (ageS < 2) "live" else "${ageS}s ago"
            ConsoleRow("fix", "%s · ±%.0f m".format(fix, gps.accuracy), dim)
        }
    }
}

/**
 * Vitals — per-epoch device telemetry from the pwnagotchi: system health, its own
 * RL reward (self-score), environment density, and nearby peers.
 */
@Composable
private fun ConsoleVitalsBlock(t: com.wsvdmeer.pwncompanion.models.DeviceTelemetry) {
    val dim = MaterialTheme.colorScheme.onSurfaceVariant
    val primary = MaterialTheme.colorScheme.primary
    val onSurface = MaterialTheme.colorScheme.onSurface
    val error = MaterialTheme.colorScheme.error
    Column {
        ConsoleLabel("[ vitals ]")
        Spacer(modifier = Modifier.height(4.dp))
        t.temperature?.let {
            // Gauge scaled to ~85°C ceiling; >70°C runs hot → red.
            ConsoleBarRow("temp", (it / 85.0).toFloat(), "%.0f°C".format(it), if (it >= 70) error else onSurface)
        }
        t.cpuLoad?.let {
            ConsoleBarRow("cpu", it.toFloat(), "%.0f%%".format(it * 100), if (it >= 0.85) error else primary)
        }
        t.memUsage?.let {
            ConsoleBarRow("mem", it.toFloat(), "%.0f%%".format(it * 100), if (it >= 0.90) error else dim)
        }
        t.reward?.let {
            // Signed self-score mapped onto a 0-centred gauge: -1 → empty, +1 → full.
            ConsoleBarRow("reward", ((it + 1.0) / 2.0).toFloat(), "%+.2f".format(it), if (it >= 0) primary else error)
        }
        // Environment density + peers folded onto one line.
        val aps = t.numAps; val sta = t.numSta; val peers = t.numPeers ?: 0
        if (aps != null || sta != null) {
            val peerStr = if (peers > 0) " · $peers peers" else ""
            ConsoleRow("env", "${aps ?: 0} aps · ${sta ?: 0} clients$peerStr", onSurface)
        }
    }
}

/**
 * A `label  [▮▮▮▮······]  value` gauge row. The bar is drawn as uniform Box cells (not
 * block glyphs), so every segment and gap is exactly the same size — pixel-perfect,
 * unlike the font's █ which leaves hairline gaps.
 */
@Composable
private fun ConsoleBarRow(key: String, fraction: Float, value: String, color: Color) {
    val cells = 12
    val filled = (fraction.coerceIn(0f, 1f) * cells).roundToInt().coerceIn(0, cells)
    val off = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.22f)
    Row(
        modifier = Modifier.padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            key.padEnd(7) + ": ",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 12.sp, lineHeight = 18.sp, maxLines = 1, softWrap = false
        )
        // Flexible bar cells fill the middle and shrink on a narrow screen, so the label +
        // value are never pushed off / clipped (fixed-width cells used to overflow).
        Row(
            modifier = Modifier.weight(1f),
            horizontalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            repeat(cells) { i ->
                Box(
                    Modifier
                        .weight(1f)
                        .height(11.dp)
                        .background(if (i < filled) color else off)
                )
            }
        }
        Text("  $value", color = color, fontSize = 12.sp, lineHeight = 18.sp, maxLines = 1, softWrap = false)
    }
}

/**
 * Capture log — geolocated handshakes the device has grabbed. Seeded from the
 * Pwnagotchi's handshake dir on connect (via the .gps.json sidecars our plugin
 * writes) and appended live on each new capture.
 */
@Composable
private fun ConsoleCapturesBlock(
    captures: List<com.wsvdmeer.pwncompanion.models.CaptureEntry>,
    fileCount: Int? = null,
    wpaSecEnabled: Boolean? = null,
    wpaSecOnline: Boolean? = null,
    onOpen: () -> Unit = {},
) {
    val dim = MaterialTheme.colorScheme.onSurfaceVariant
    val primary = MaterialTheme.colorScheme.primary
    val geo = captures.count { it.isGeolocated }
    Column {
        // Header doubles as the entry point to the full captures detail (map + list).
        Row(
            modifier = Modifier.fillMaxWidth().clickable { onOpen() },
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            ConsoleLabel("[ captures ]")
            Text("details ›", color = dim, fontSize = 11.sp)
        }
        Spacer(modifier = Modifier.height(4.dp))
        // The device counts pcap FILES; the app dedupes by BSSID (one row per network).
        // When they differ (same AP saved under 2 filenames), show both so it reconciles
        // with the pwnagotchi's own on-screen count.
        val networks = captures.size
        // ── one counts line: networks/handshakes + mapped, folded together ──
        val caught = if (fileCount != null && fileCount > networks)
            "$networks nets · $fileCount caught"
        else
            "$networks caught"
        ConsoleRow("total", caught + if (geo > 0) " · $geo mapped" else "", primary)

        // ── one crack line: result + wpa-sec state, shown only when it says something.
        // (partial grabs + the verbose "wpa-sec online" live in the detail — this keeps
        // the console from stacking separate crackable/cracking rows.) ──
        val crackable = captures.count { it.isCrackable }
        val cracked = captures.count { it.isCracked }
        val crackText = when {
            cracked > 0   -> "$cracked cracked · $crackable crackable"
            crackable > 0 -> "$crackable crackable"
            else          -> null
        }
        val offline = wpaSecEnabled == true && wpaSecOnline == false
        val wpaNote = when {
            offline                -> "wpa-sec offline"
            wpaSecEnabled == false -> "wpa-sec off"
            else                   -> null
        }
        val crackLine = listOfNotNull(crackText, wpaNote).joinToString(" · ")
        if (crackLine.isNotEmpty()) {
            ConsoleRow(
                "crack",
                crackLine,
                when {
                    offline           -> MaterialTheme.colorScheme.error
                    crackText != null -> primary
                    else              -> MaterialTheme.colorScheme.tertiary
                },
            )
        }
        Spacer(modifier = Modifier.height(2.dp))
        // Recent captures, newest first. ⌖ marks ones with a GPS fix.
        captures.take(5).forEach { c ->
            val marker = if (c.isGeolocated) "⌖" else "·"
            val ssid = c.ssid.take(20)
            Row {
                Text(
                    "$marker ",
                    color = if (c.isGeolocated) primary else dim,
                    fontSize = 11.sp, lineHeight = 16.sp
                )
                Text(
                    ssid,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontSize = 11.sp, lineHeight = 16.sp,
                    maxLines = 1,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    relativeAge(c.timestamp),
                    color = dim,
                    fontSize = 11.sp, lineHeight = 16.sp,
                    maxLines = 1
                )
            }
        }
        if (captures.size > 5) {
            Text(
                "  +${captures.size - 5} more",
                color = dim, fontSize = 11.sp, lineHeight = 16.sp,
                modifier = Modifier.clickable { onOpen() }
            )
        }
    }
}

/** Compact "time ago" for a unix-seconds timestamp; "—" if unknown. */
private fun relativeAge(unixSeconds: Long?): String {
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

@Composable
private fun RawDeviceImage(imageData: String) {
    // Decode the current frame; if it fails (occasional partial/corrupt push over
    // the WebSocket), keep showing the last good frame instead of blanking out.
    // This is why the screen sometimes "disappeared" — a single bad frame, not a
    // threading issue. Decoding is memoised per-frame so it stays off the main path.
    var lastGood by remember { mutableStateOf<androidx.compose.ui.graphics.ImageBitmap?>(null) }
    val decoded = remember(imageData) { ImageUtil.decodeBase64ToImageBitmap(imageData) }
    if (decoded != null) lastGood = decoded
    val bmp = decoded ?: lastGood
    if (bmp != null) {
        androidx.compose.foundation.Image(
            bitmap = bmp,
            contentDescription = "device screen",
            // No border — let the e-ink frame blend into the console background.
            modifier = Modifier.fillMaxWidth(),
            contentScale = ContentScale.FillWidth,
            // Nearest-neighbour upscale: the e-ink frame is a tiny bitmap, so bilinear
            // (the default) smears it. None keeps each e-ink pixel a crisp block.
            filterQuality = androidx.compose.ui.graphics.FilterQuality.None
        )
    }
}

@Composable
private fun ConsoleLearningBlock(
    stats: com.wsvdmeer.pwncompanion.models.LearningStats,
    onOpen: () -> Unit = {},
) {
    val dim = MaterialTheme.colorScheme.onSurfaceVariant
    // Rank by observation count — the reliable signal. (The old success-rate % was
    // handshakes/deauths, which read 0% whenever there were captures but no deauths.)
    val ranked = stats.channels.sortedByDescending { it.observationCount }
    val maxObs = ranked.firstOrNull()?.observationCount ?: 0
    Column {
        Row(
            modifier = Modifier.fillMaxWidth().clickable { onOpen() },
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            ConsoleLabel("[ learning ]")
            Text("details ›", color = dim, fontSize = 11.sp)
        }
        Spacer(modifier = Modifier.height(4.dp))
        ConsoleRow("seen", "${stats.totalObservations} networks", dim)
        ranked.firstOrNull()?.let {
            ConsoleRow("busiest", "ch${it.channel} · ${it.observationCount} seen", MaterialTheme.colorScheme.primary)
        }
        if (ranked.isEmpty()) {
            Text("  gathering data…", color = dim, fontSize = 11.sp, lineHeight = 16.sp)
        } else {
            Spacer(modifier = Modifier.height(2.dp))
            val off = dim.copy(alpha = 0.22f)
            ranked.take(3).forEach { ch ->
                val bars = if (maxObs > 0) (ch.observationCount * 10 / maxObs).coerceIn(0, 10) else 0
                val barColor = if (ch.isBest) MaterialTheme.colorScheme.primary else dim
                // Uniform Box cells (not █/· glyphs) so segments + gaps are pixel-perfect,
                // matching the vitals gauges — the font's █ blurs the gaps between bars.
                Row(
                    modifier = Modifier.padding(vertical = 1.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "ch${ch.channel.toString().padEnd(3)} ",
                        color = barColor, fontSize = 11.sp, lineHeight = 16.sp
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                        repeat(10) { i ->
                            Box(
                                Modifier
                                    .size(width = 8.dp, height = 10.dp)
                                    .background(if (i < bars) barColor else off)
                            )
                        }
                    }
                    Text(
                        "  ${ch.observationCount}",
                        color = barColor, fontSize = 11.sp, lineHeight = 16.sp, maxLines = 1
                    )
                }
            }
            if (ranked.size > 3) {
                Text(
                    "+${ranked.size - 3} more",
                    color = dim, fontSize = 11.sp, lineHeight = 16.sp,
                    modifier = Modifier.clickable { onOpen() }
                )
            }
        }
    }
}

@Composable
private fun ConsoleCommandBar(
    isAutoMode: Boolean,
    isServerRunning: Boolean,
    networkingArmed: Boolean,
    deviceConnected: Boolean,
    onAuto: () -> Unit,
    onManual: () -> Unit,
    onToggleService: () -> Unit,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        // Mode toggle — only meaningful while a pwnagotchi is actually LINKED (not merely
        // while the server is listening), since it sends restart_auto/manual to the device.
        if (deviceConnected) {
            if (isAutoMode) CmdAction("go manual", MaterialTheme.colorScheme.tertiary, onManual)
            else CmdAction("go auto", MaterialTheme.colorScheme.primary, onAuto)
        }
        // Service toggle — three states so a "start" tap is never a silent no-op:
        //   running               → stop service
        //   armed but not bound    → waiting for the Bluetooth link (tap cancels/disarms)
        //   fully off              → start service
        when {
            isServerRunning ->
                CmdAction("stop service", MaterialTheme.colorScheme.error, onToggleService)
            networkingArmed ->
                CmdAction("waiting for link — cancel", MaterialTheme.colorScheme.tertiary, onToggleService)
            else ->
                CmdAction("start service", MaterialTheme.colorScheme.primary, onToggleService)
        }
    }
}

/** A real, clearly-tappable terminal button: bordered, padded, with a ripple. */
@Composable
private fun CmdAction(label: String, color: Color, onClick: () -> Unit) {
    Text(
        "[ $label ]",
        color = color,
        fontSize = 13.sp,
        fontWeight = FontWeight.Bold,
        modifier = Modifier
            .clip(TerminalBoxShape)
            .clickable { onClick() }
            .border(1.dp, color.copy(alpha = 0.5f), TerminalBoxShape)
            .padding(horizontal = 14.dp, vertical = 10.dp)
    )
}

/**
 * Error line — a flat console row in the terminal idiom (was a rounded Material card
 * with icons, which clashed with the console). Message in the error color + a tappable
 * [ dismiss ].
 */
@Composable
fun ErrorCard(message: String, onDismiss: () -> Unit) {
    val error = MaterialTheme.colorScheme.error
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            "[ error ] $message",
            color = error,
            fontSize = 12.sp,
            lineHeight = 18.sp
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            "[ dismiss ]",
            color = error,
            fontWeight = FontWeight.Bold,
            fontSize = 13.sp,
            modifier = Modifier
                .clip(TerminalBoxShape)
                .clickable { onDismiss() }
                .border(1.dp, error.copy(alpha = 0.5f), TerminalBoxShape)
                .padding(horizontal = 14.dp, vertical = 8.dp)
        )
    }
}

/**
 * Connection Status Card - Compact status showing server connectivity.
 * Note: Only 1 device can be tethered at a time via Bluetooth.
 */
@Composable
fun ConnectionStatusCard(serverRunning: Boolean, connectedCount: Int) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .shadow(4.dp, shape = RoundedCornerShape(12.dp)),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(10.dp)
                    .background(
                        color = when {
                            connectedCount > 0 -> MaterialTheme.colorScheme.tertiary
                            serverRunning -> MaterialTheme.colorScheme.secondary
                            else -> MaterialTheme.colorScheme.error
                        },
                        shape = RoundedCornerShape(50)
                    )
                    .shadow(3.dp, shape = RoundedCornerShape(50))
            )
            Text(
                text = when {
                    connectedCount > 0 -> "Connected"
                    serverRunning -> "Listening for device..."
                    else -> "Server offline"
                },
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.ExtraBold,
                color = when {
                    connectedCount > 0 -> MaterialTheme.colorScheme.tertiary
                    serverRunning -> MaterialTheme.colorScheme.secondary
                    else -> MaterialTheme.colorScheme.error
                }
            )
        }
    }
}

/**
 * Live event feed — a terminal-style scrolling log of real events
 * (handshakes, deauths, discoveries, link up/down). Most-recent-first, capped.
 */
@Composable
fun EventFeedCard(lines: List<String>, onOpen: () -> Unit = {}) {
    Column {
        Row(
            modifier = Modifier.fillMaxWidth().clickable { onOpen() },
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                "[ LOG ]",
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Bold,
                fontSize = 12.sp,
                letterSpacing = 2.sp
            )
            Text("details ›", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp)
        }
        Spacer(modifier = Modifier.height(4.dp))
        lines.take(8).forEach { line ->
            val c = when {
                line.contains("[+]") -> MaterialTheme.colorScheme.primary
                line.contains("[!]") || line.contains("[x]") -> MaterialTheme.colorScheme.error
                line.contains("[*]") -> MaterialTheme.colorScheme.tertiary
                else -> MaterialTheme.colorScheme.onSurfaceVariant
            }
            Text(line, color = c, fontSize = 11.sp, lineHeight = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
fun DeviceCard(device: DeviceState, viewModel: MainViewModel) {
    // Prefer the Pwnagotchi's own name from the plugin status; fall back to BT device name
    val displayName = device.pwnagotchiName?.takeIf { it.isNotBlank() } ?: device.deviceName

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .shadow(4.dp, shape = RoundedCornerShape(12.dp)),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        )
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            // Device Name Header
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(
                    imageVector = Icons.Outlined.PhoneAndroid,
                    contentDescription = "Device",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(18.dp)
                )
                Text(
                    text = displayName,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.ExtraBold,
                    color = MaterialTheme.colorScheme.primary,
                    fontSize = 14.sp,
                    modifier = Modifier.weight(1f)
                )
                // Connection status dot with label
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        text = if (device.isConnected) "Online" else "Offline",
                        style = MaterialTheme.typography.labelSmall,
                        fontSize = 9.sp,
                        color = if (device.isConnected) MaterialTheme.colorScheme.tertiary
                                else MaterialTheme.colorScheme.secondary
                    )
                    Box(
                        modifier = Modifier
                            .size(10.dp)
                            .background(
                                color = if (device.isConnected) MaterialTheme.colorScheme.tertiary
                                        else MaterialTheme.colorScheme.secondary,
                                shape = RoundedCornerShape(50)
                            )
                            .shadow(2.dp, shape = RoundedCornerShape(50))
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            Column(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                // IP Address Row
                Row(
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(text = "Pwnagotchi IP", style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp)
                    Text(text = device.ipAddress, style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold, fontSize = 12.sp)
                }

                // Interface
                Row(
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(text = "Interface", style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp)
                    Text(text = device.interfaceName, style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurface, fontSize = 12.sp)
                }

                // Port
                Row(
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(text = "WebSocket Port", style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp)
                    Text(text = device.port.toString(), style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurface, fontSize = 12.sp)
                }

                // Status
                Row(
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(text = "Status", style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp)
                    Text(
                        text = when (device.connectionState) {
                            DeviceState.ConnectionState.CONNECTED -> "Connected"
                            DeviceState.ConnectionState.CONNECTING -> "Connecting"
                            DeviceState.ConnectionState.DISCONNECTED -> "Disconnected"
                            else -> device.connectionState.toString()
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = when (device.connectionState) {
                            DeviceState.ConnectionState.CONNECTED -> MaterialTheme.colorScheme.tertiary
                            DeviceState.ConnectionState.CONNECTING -> MaterialTheme.colorScheme.secondary
                            else -> MaterialTheme.colorScheme.error
                        },
                        fontWeight = FontWeight.SemiBold, fontSize = 12.sp
                    )
                }

                // Auto-tune stats (shown if available from plugin)
                val bestCh = device.autotuneBestChannel
                val minRssi = device.autotuneMinRssi
                val chMap = device.autotuneChannels
                if (bestCh != null || minRssi != null || !chMap.isNullOrEmpty()) {
                    Spacer(modifier = Modifier.height(4.dp))
                    androidx.compose.material3.HorizontalDivider(
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Auto-tune",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.secondary,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 10.sp
                    )
                    if (bestCh != null) {
                        Row(
                            horizontalArrangement = Arrangement.SpaceBetween,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("Best Channel", style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp)
                            Text("CH $bestCh", style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.tertiary, fontWeight = FontWeight.SemiBold, fontSize = 11.sp)
                        }
                    }
                    if (minRssi != null) {
                        Row(
                            horizontalArrangement = Arrangement.SpaceBetween,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("Min RSSI", style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp)
                            Text("${minRssi} dBm", style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurface, fontSize = 11.sp)
                        }
                    }
                    if (!chMap.isNullOrEmpty()) {
                        val sorted = chMap.entries.sortedByDescending { it.value.handshakes }.take(3)
                        Text(
                            text = "Top channels: " + sorted.joinToString("  ") { "CH${it.key}(${it.value.handshakes}✓)" },
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 10.sp
                        )
                    }
                }
            }
        }
    }
}

/**
 * Image Viewer Card - Shows latest image from device.
 * Full width with auto height based on image aspect ratio.
 */
@Composable
fun ImageViewerCard(imageData: String, deviceId: String) {
    android.util.Log.d("ImageViewerCard", "Rendering ImageViewerCard with imageData size=${imageData.length}, deviceId=$deviceId")
    
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .shadow(4.dp, shape = RoundedCornerShape(12.dp)),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(8.dp)
        ) {
            // Decode image only when displaying (efficient memory usage)
            val imageBitmap = remember(imageData) {
                android.util.Log.d("ImageViewerCard", "Decoding image, size=${imageData.length}")
                val bitmap = ImageUtil.decodeBase64ToImageBitmap(imageData)
                android.util.Log.d("ImageViewerCard", "Image decoded: ${bitmap != null}")
                bitmap
            }
            
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.background),
                contentAlignment = Alignment.Center
            ) {
                if (imageBitmap != null) {
                    android.util.Log.d("ImageViewerCard", "Displaying image")
                    androidx.compose.foundation.Image(
                        bitmap = imageBitmap,
                        contentDescription = "Device screen capture",
                        modifier = Modifier.fillMaxWidth(),
                        contentScale = ContentScale.FillWidth,
                        // Crisp pixel-for-pixel upscale of the small e-ink frame.
                        filterQuality = androidx.compose.ui.graphics.FilterQuality.None
                    )
                } else {
                    android.util.Log.d("ImageViewerCard", "Image bitmap is null, showing loading")
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(200.dp)
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(32.dp),
                            color = MaterialTheme.colorScheme.primary,
                            strokeWidth = 2.dp
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "Loading...",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
            
            Spacer(modifier = Modifier.height(6.dp))
            
            // Only show bytes info
            Text(
                text = "${ImageUtil.getBase64Size(imageData)} bytes",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 4.dp)
            )
        }
    }
}

/**
 * GPS Data Card - Shows latitude, longitude, accuracy, and altitude from device GPS.
 */
@Composable
fun GpsDataCard(gpsData: com.wsvdmeer.pwncompanion.models.GpsData) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .shadow(4.dp, shape = RoundedCornerShape(12.dp)),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        )
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    imageVector = Icons.Outlined.LocationOn,
                    contentDescription = "Location",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(18.dp)
                )
                Text(
                    text = "GPS Location",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.ExtraBold,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
            Column(
                verticalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.padding(start = 26.dp)
            ) {
                Text(
                    text = "Latitude: ${String.format("%.6f", gpsData.latitude)}°",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = "Longitude: ${String.format("%.6f", gpsData.longitude)}°",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = "Accuracy: ±${String.format("%.1f", gpsData.accuracy)}m",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.tertiary,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = "Altitude: ${String.format("%.1f", gpsData.altitude)}m",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
        }
    }
}

/**
 * Status Message Card - Shows latest device status.
 */
@Composable
fun StatusMessageCard(message: String) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .shadow(4.dp, shape = RoundedCornerShape(12.dp)),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        )
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Text(
                text = "Device Status",
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.ExtraBold,
                color = MaterialTheme.colorScheme.tertiary
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface
            )
        }
    }
}


/**
 * Control Sheet Content - Bottom sheet with control buttons.
 * Contains server control options in an M3-compliant sheet.
 */
@Composable
fun ControlSheetContent(viewModel: MainViewModel, onDismiss: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Title (no manual drag handle — ModalBottomSheet draws its own pill)
        Text(
            text = "Controls",
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onSurface,
            fontWeight = FontWeight.ExtraBold,
            modifier = Modifier.padding(top = 8.dp)
        )

        Spacer(modifier = Modifier.height(4.dp))

        // ── Pwnagotchi Device Controls ─────────────────────────────
        Text(
            text = "PWNAGOTCHI",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(bottom = 4.dp)
        )

        // Restart in AUTO mode
        Button(
            onClick = {
                viewModel.sendPwnagotchiCommand("restart_auto")
                onDismiss()
            },
            modifier = Modifier.fillMaxWidth().height(48.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.primaryContainer
            ),
            shape = RoundedCornerShape(12.dp)
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Outlined.Refresh,
                    contentDescription = "Restart Auto",
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.size(18.dp)
                )
                Text(
                    "Restart: AUTO Mode",
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp
                )
            }
        }

        // Restart in MANUAL mode
        Button(
            onClick = {
                viewModel.sendPwnagotchiCommand("restart_manual")
                onDismiss()
            },
            modifier = Modifier.fillMaxWidth().height(48.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.secondaryContainer
            ),
            shape = RoundedCornerShape(12.dp)
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Outlined.Build,
                    contentDescription = "Restart Manual",
                    tint = MaterialTheme.colorScheme.onSecondaryContainer,
                    modifier = Modifier.size(18.dp)
                )
                Text(
                    "Restart: MANUAL Mode",
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp
                )
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // ── App Server Controls ────────────────────────────────────
        Text(
            text = "APP SERVER",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(bottom = 4.dp)
        )

        // Stop Server Button
        Button(
            onClick = {
                viewModel.stopServer()
                onDismiss()
            },
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.errorContainer
            ),
            shape = RoundedCornerShape(12.dp)
         ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                    Icon(
                        imageVector = Icons.Outlined.Close,
                        contentDescription = "Stop",
                    tint = MaterialTheme.colorScheme.onErrorContainer,
                    modifier = Modifier.size(18.dp)
                )
                Text(
                    "Stop Server",
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp
                )
            }
         }

        Spacer(modifier = Modifier.height(8.dp))

        // Info cards
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .shadow(2.dp, shape = RoundedCornerShape(12.dp)),
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant
            )
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Info,
                        contentDescription = "Information",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(16.dp)
                    )
                    Text(
                        text = "What this does:",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontWeight = FontWeight.SemiBold
                    )
                }
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = "Restart AUTO: pwnagotchi resumes autonomous hunting.\nRestart MANUAL: pwnagotchi pauses — safe for config changes.\nStop Server: disconnects WebSocket, UDP, and GPS service.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))
    }
}


