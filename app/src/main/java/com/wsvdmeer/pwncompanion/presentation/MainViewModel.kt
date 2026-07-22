package com.wsvdmeer.pwncompanion.presentation

import android.app.Application
import android.content.Intent
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.wsvdmeer.pwncompanion.database.PwnCompanionDatabase
import com.wsvdmeer.pwncompanion.models.DeviceState
import com.wsvdmeer.pwncompanion.models.GpsData
import com.wsvdmeer.pwncompanion.models.LearningStats
import com.wsvdmeer.pwncompanion.models.PersonalityState
import com.wsvdmeer.pwncompanion.models.Strategy
import com.wsvdmeer.pwncompanion.protocol.MessageHandler
import com.wsvdmeer.pwncompanion.protocol.OutgoingMessageQueue
import com.wsvdmeer.pwncompanion.services.CompanionBackgroundService
import com.wsvdmeer.pwncompanion.services.NetworkService
import com.wsvdmeer.pwncompanion.services.StrategyDecisionEngine
import com.wsvdmeer.pwncompanion.services.SyncScheduler
import com.wsvdmeer.pwncompanion.services.WifiMemoryService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Full-screen detail views reachable from the console summaries (tap a section). */
enum class DetailScreen { NONE, CAPTURES, LOG, LEARNING, STATS }

/**
 * Main Activity ViewModel.
 * Manages state for device connections, incoming messages, and UI interactions.
 * Bridges between services (NetworkService, MessageHandler) and UI layer.
 * Implements MVVM pattern with StateFlow for reactive updates.
 */
class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val tag = "MainViewModel"

    // Service instances (injected from MainActivity)
    private var networkService: NetworkService? = null
    private var messageHandler: MessageHandler? = null
    private var outgoingQueue: OutgoingMessageQueue? = null
    private var wifiMemoryService: WifiMemoryService? = null
    private var strategyEngine: StrategyDecisionEngine? = null
    private var syncScheduler: SyncScheduler? = null

    // Database (NEVER access in constructor - only via initializeServices)
    @Deprecated("Use initializeServices() to access database")
    private var observationRepository: Any? = null

    // StateFlow for current strategy
    private val _currentStrategy = MutableStateFlow<Strategy?>(null)
    val currentStrategy: StateFlow<Strategy?> = _currentStrategy.asStateFlow()

    // StateFlow for sync status
    private val _syncStatus = MutableStateFlow("⏳ Pending first sync")
    val syncStatus: StateFlow<String> = _syncStatus.asStateFlow()

    // The ViewModel survives config changes, but MainActivity.onCreate re-runs on every
    // rotation and calls initializeServices() again. Without this guard each call would
    // spawn a fresh set of collectors + another SyncScheduler (3 while(isActive) loops),
    // duplicating logs and firing set_channel_priority N times per interval. Init once.
    private var servicesInitialized = false

    init {
        // Minimal initialization - NO database, service, or context access
        Log.d(tag, "ViewModel created (empty init)")
    }

    // UI State Flows
    private val _deviceStates = MutableStateFlow<Map<String, DeviceState>>(emptyMap())
    val deviceStates: StateFlow<Map<String, DeviceState>> = _deviceStates.asStateFlow()

    private val _currentImageData = MutableStateFlow<String?>(null)
    val currentImageData: StateFlow<String?> = _currentImageData.asStateFlow()

    private val _currentImageDeviceId = MutableStateFlow<String?>(null)
    val currentImageDeviceId: StateFlow<String?> = _currentImageDeviceId.asStateFlow()

    private val _currentImageTimestamp = MutableStateFlow<Long?>(null)
    @Suppress("UNUSED")
    val currentImageTimestamp: StateFlow<Long?> = _currentImageTimestamp.asStateFlow()

    private val _currentStatusMessage = MutableStateFlow<String?>(null)
    val currentStatusMessage: StateFlow<String?> = _currentStatusMessage.asStateFlow()

    private val _gpsData = MutableStateFlow<GpsData?>(null)
    val gpsData: StateFlow<GpsData?> = _gpsData.asStateFlow()

    private val _isServerRunning = MutableStateFlow(false)
    val isServerRunning: StateFlow<Boolean> = _isServerRunning.asStateFlow()

    // Networking is "armed" (the user wants it on) even when the WebSocket server isn't
    // bound yet because there's no Bluetooth tether. armed && !running = "waiting for link".
    private val _networkingArmed = MutableStateFlow(false)
    val networkingArmed: StateFlow<Boolean> = _networkingArmed.asStateFlow()

    private val _connectedDeviceCount = MutableStateFlow(0)
    val connectedDeviceCount: StateFlow<Int> = _connectedDeviceCount.asStateFlow()

    private val _outgoingQueueSize = MutableStateFlow(0)
    val outgoingQueueSize: StateFlow<Int> = _outgoingQueueSize.asStateFlow()

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    private val _showControlSheet = MutableStateFlow(false)
    val showControlSheet: StateFlow<Boolean> = _showControlSheet.asStateFlow()

    private val _learningStats = MutableStateFlow<LearningStats?>(null)
    val learningStats: StateFlow<LearningStats?> = _learningStats.asStateFlow()

    private val _showAnalyticsDashboard = MutableStateFlow(false)
    val showAnalyticsDashboard: StateFlow<Boolean> = _showAnalyticsDashboard.asStateFlow()

    // Which full-screen detail view is open (tap a console section to drill in).
    private val _detailScreen = MutableStateFlow(DetailScreen.NONE)
    val detailScreen: StateFlow<DetailScreen> = _detailScreen.asStateFlow()
    fun openDetail(screen: DetailScreen) { _detailScreen.value = screen }
    fun closeDetail() { _detailScreen.value = DetailScreen.NONE }

    // Last WiFi event from the Pwnagotchi — consumed by the AI personality card
    private val _lastNetworkEvent = MutableStateFlow<com.wsvdmeer.pwncompanion.protocol.MessageHandler.NetworkEventUpdate?>(null)
    val lastNetworkEvent: StateFlow<com.wsvdmeer.pwncompanion.protocol.MessageHandler.NetworkEventUpdate?> = _lastNetworkEvent.asStateFlow()

    // Raw pwnagotchi mood name (e.g. "HAPPY", "BORED") — used to auto-sync the AI personality mood
    private val _deviceMood = MutableStateFlow<String?>(null)
    val deviceMood: StateFlow<String?> = _deviceMood.asStateFlow()

    /**
     * True when the Pwnagotchi is in AUTO mode (scanning WiFi networks).
     * False in MANUAL mode — no scanning, no handshakes, learning should be paused.
     * Defaults to true so the UI shows full features before the first mode message arrives.
     */
    private val _isAutoMode = MutableStateFlow(true)
    val isAutoMode: StateFlow<Boolean> = _isAutoMode.asStateFlow()

    // Once the user explicitly picks a mode, that choice is authoritative: the
    // plugin currently reports a stale "AUTO" on every status message (its
    // _current_mode defaults to AUTO and resets on restart), which would otherwise
    // flip the user's manual selection straight back to auto.
    @Volatile
    private var modeUserControlled = false

    /**
     * Terminal-style live event feed (most recent first, capped). Fed from real
     * WiFi events + connection changes for the on-screen "log" panel.
     */
    private val _eventLog = MutableStateFlow<List<String>>(emptyList())
    val eventLog: StateFlow<List<String>> = _eventLog.asStateFlow()

    // Geolocated handshakes captured by the device (newest first).
    private val _captures = MutableStateFlow<List<com.wsvdmeer.pwncompanion.models.CaptureEntry>>(emptyList())
    val captures: StateFlow<List<com.wsvdmeer.pwncompanion.models.CaptureEntry>> = _captures.asStateFlow()

    // Latest per-epoch device telemetry (vitals, reward, mood counters).
    private val _telemetry = MutableStateFlow<com.wsvdmeer.pwncompanion.models.DeviceTelemetry?>(null)
    val telemetry: StateFlow<com.wsvdmeer.pwncompanion.models.DeviceTelemetry?> = _telemetry.asStateFlow()

    // Channels the app is currently steering the device's recon toward (learning → attack).
    private val _channelPriority = MutableStateFlow<List<Int>>(emptyList())
    val channelPriority: StateFlow<List<Int>> = _channelPriority.asStateFlow()

    // Live snapshot of the phone-side personality tuner (re-implements jayofelony's removed
    // RL), rendered as bars in the UI. Null until it first runs.
    private val _tuning = MutableStateFlow<com.wsvdmeer.pwncompanion.models.TuningState?>(null)
    val tuning: StateFlow<com.wsvdmeer.pwncompanion.models.TuningState?> = _tuning.asStateFlow()

    // ── Untapped-target spotting ─────────────────────────────────────────────
    // APs the pwnagotchi keeps SEEING (association/discovery events) but has never
    // CAPTURED a handshake from — prime deauth candidates. Tracked in-memory per app
    // run, keyed by BSSID. Exposed as a ready phrase the advisor + pet can voice.
    private data class SeenAp(val ssid: String, val bssid: String, val rssi: Int, val channel: Int, val count: Int)
    private val discoveredAps = java.util.concurrent.ConcurrentHashMap<String, SeenAp>()
    private val _untappedTarget = MutableStateFlow<String?>(null)
    val untappedTarget: StateFlow<String?> = _untappedTarget.asStateFlow()
    // The channel of that untapped AP, so steering can park recon there to finally grab it.
    private val _untappedChannel = MutableStateFlow<Int?>(null)
    val untappedChannel: StateFlow<Int?> = _untappedChannel.asStateFlow()

    // ── Motion detection (stationary vs moving) ──────────────────────────────
    // Steering pins channels only when STILL; when MOVING the environment changes
    // constantly so a learned pin is already stale — better to hop the wide 2.4GHz
    // band fast. Primary signal: GPS speed (our edge over a GPS-less Pi); fallback:
    // how fast new APs are appearing (an AP-churn heuristic).
    private val _isMoving = MutableStateFlow(false)
    val isMoving: StateFlow<Boolean> = _isMoving.asStateFlow()
    @Volatile private var _prevGps: GpsData? = null
    @Volatile private var _prevGpsAtMs = 0L
    @Volatile private var _lastGpsAtMs = 0L
    private val _newApTimes = java.util.concurrent.ConcurrentLinkedDeque<Long>()

    private fun noteNewApSeen() {
        val now = System.currentTimeMillis()
        _newApTimes.add(now)
        while (_newApTimes.peekFirst()?.let { now - it > 60_000 } == true) _newApTimes.pollFirst()
    }

    private fun onGpsFix(gps: GpsData) {
        val now = System.currentTimeMillis()
        _lastGpsAtMs = now
        val prev = _prevGps; val prevAt = _prevGpsAtMs
        if (prev != null && prev.isValid() && gps.isValid() && prevAt > 0L) {
            val dt = (now - prevAt) / 1000.0
            if (dt >= 1.0) {
                val speedMps = haversineMeters(prev.latitude, prev.longitude, gps.latitude, gps.longitude) / dt
                _isMoving.value = when {
                    speedMps > 1.4 -> true    // ~5 km/h+ → walking or faster
                    speedMps < 0.6 -> false   // basically stationary
                    else -> _isMoving.value   // hysteresis band → keep current
                }
            }
        }
        _prevGps = gps; _prevGpsAtMs = now
    }

    /** Stationary vs moving, decided at call time (GPS speed, else AP-churn fallback). */
    fun isMovingNow(): Boolean {
        val now = System.currentTimeMillis()
        val gpsFresh = (now - _lastGpsAtMs) < 30_000 && _gpsData.value?.isValid() == true
        if (gpsFresh) return _isMoving.value
        val cutoff = now - 60_000
        return _newApTimes.count { it >= cutoff } >= 3   // ≥3 new APs/min ≈ moving
    }

    private fun haversineMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 6_371_000.0
        val dLat = Math.toRadians(lat2 - lat1); val dLon = Math.toRadians(lon2 - lon1)
        val a = Math.sin(dLat / 2) * Math.sin(dLat / 2) +
            Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) *
            Math.sin(dLon / 2) * Math.sin(dLon / 2)
        return r * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a))
    }

    /** Pick the most-seen AP that's never been captured (needs a few sightings). */
    private fun recomputeUntapped() {
        val caught = _captures.value
            .map { it.bssid.lowercase() }
            .filter { it.isNotBlank() }
            .toSet()
        val cand = discoveredAps.values
            .filter { it.bssid.isNotBlank() && it.bssid.lowercase() !in caught }
            .sortedWith(compareByDescending<SeenAp> { it.count }.thenByDescending { it.rssi })
            .firstOrNull { it.count >= 3 }
        _untappedTarget.value = cand?.let {
            "${it.ssid.ifBlank { "(hidden)" }} (${it.rssi}dBm, ch${it.channel}) seen ${it.count}x but never caught"
        }
        _untappedChannel.value = cand?.channel?.takeIf { it in 1..165 }
    }

    private fun appendLog(line: String) {
        val ts = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US)
            .format(java.util.Date())
        // Atomic update (callers may run off the main thread, e.g. onSteer); keep 200
        // lines so the [ log ] detail page has real history, not just the last few.
        _eventLog.update { (listOf("[$ts] $line") + it).take(200) }
    }

    /**
     * Toggle bottom sheet visibility.
     */
    fun toggleControlSheet() {
        _showControlSheet.value = !_showControlSheet.value
    }

    /**
     * Toggle analytics dashboard visibility.
     */
    fun toggleAnalyticsDashboard() {
        _showAnalyticsDashboard.value = !_showAnalyticsDashboard.value
    }

    /**
     * Hide bottom sheet.
     */
    fun hideControlSheet() {
        _showControlSheet.value = false
    }

    /**
     * Initialize ViewModel with service instances.
     * Called from MainActivity after services are available.
     * MUST be called before using any service-dependent features.
     */
    fun initializeServices(
        networkService: NetworkService,
        messageHandler: MessageHandler,
        outgoingQueue: OutgoingMessageQueue
    ) {
        // Always refresh the service references (a rotation can hand us fresh singletons),
        // but only wire up collectors / scheduler once per ViewModel lifetime.
        this.networkService = networkService
        this.messageHandler = messageHandler
        this.outgoingQueue = outgoingQueue

        if (servicesInitialized) {
            Log.d(tag, "Services already initialized — refreshed references only (skipping re-subscribe)")
            return
        }
        servicesInitialized = true

        // Initialize database-dependent services NOW (not in constructor)
        try {
            val repo = PwnCompanionDatabase.getRepository(getApplication())
            this.observationRepository = repo
            
            wifiMemoryService = WifiMemoryService(repo)
            strategyEngine = StrategyDecisionEngine(wifiMemoryService!!)
            Log.d(tag, "Database and strategy engine initialized successfully")
        } catch (e: Exception) {
            Log.e(tag, "Error initializing database services: ${e.message}", e)
            // Continue anyway - app will work without learning stats
        }

        // Subscribe to updates
        subscribeToMessageUpdates()
        subscribeToDeviceStates()
        subscribeToQueueState()
        loadLearningStats()

        // Initialize sync scheduler if services are available
        if (wifiMemoryService != null && strategyEngine != null) {
            try {
                syncScheduler = SyncScheduler(
                    outgoingQueue, wifiMemoryService!!, strategyEngine!!,
                    connectedDeviceIds = { _deviceStates.value.keys },
                    onSteer = { chans ->
                        _channelPriority.value = chans
                        appendLog("[*] steering recon -> ch ${chans.joinToString(",")}")
                    },
                    currentLocation = {
                        _gpsData.value?.takeIf { it.isValid() }?.let { it.latitude to it.longitude }
                    },
                    // Device ground-truth per-channel capture stats. Parse the string-keyed
                    // autotune map from any connected device into channel-int keys.
                    autotuneStats = {
                        _deviceStates.value.values
                            .firstOrNull { !it.autotuneChannels.isNullOrEmpty() }
                            ?.autotuneChannels
                            ?.mapNotNull { (k, v) -> k.toIntOrNull()?.let { it to v } }
                            ?.toMap()
                            ?: emptyMap()
                    },
                    // Only steer while actively hunting — no steering (or logs) in manual.
                    isAutoMode = { _isAutoMode.value },
                    // Channel of the AP seen-often-but-never-caught, so steering can chase it.
                    untappedChannel = { _untappedChannel.value },
                    // Moving → hop the wide band instead of pinning a (now-stale) learned set.
                    isMoving = { isMovingNow() },
                    // Reward inputs for the personality tuner (re-implements the removed RL).
                    totalCaptures = { _captures.value.size },
                    deviceReward = { _telemetry.value?.reward?.toFloat() },
                    onTune = { t ->
                        val prev = _tuning.value
                        _tuning.value = t
                        if (prev != t) appendLog("[*] tuning :: rssi ${t.minRssi} · ttl ${t.apTtl}/${t.staTtl} · recon ${t.reconTime}s · hop ${t.hopRecon}s")
                    },
                )
                syncScheduler?.startPeriodicSync("pwnagotchi_main", viewModelScope)
            } catch (e: Exception) {
                Log.e(tag, "Error initializing sync scheduler: ${e.message}")
                // Continue - sync not critical
            }
        }

        Log.i(tag, "ViewModel fully initialized with all services")
    }

    /**
     * Subscribe to incoming message updates from MessageHandler.
     * Updates UI state when images, GPS, or status messages arrive.
     */
    private fun subscribeToMessageUpdates() {
        val handler = messageHandler ?: return

        // Subscribe to incoming image updates
        viewModelScope.launch {
            handler.deviceImageUpdates.collect { imageUpdate ->
                try {
                    _currentImageData.value = imageUpdate.imageData
                    _currentImageDeviceId.value = imageUpdate.deviceId
                    _currentImageTimestamp.value = imageUpdate.timestamp
                    Log.d(tag, "Image received: deviceId=${imageUpdate.deviceId}, size=${imageUpdate.imageData.length}")
                } catch (e: Exception) {
                    Log.e(tag, "Error processing image update: ${e.message}", e)
                    _errorMessage.value = "Failed to process image: ${e.message}"
                }
            }
        }

        // Subscribe to incoming GPS updates
        viewModelScope.launch {
            handler.deviceGpsUpdates.collect { gpsUpdate ->
                try {
                    updateGpsData(
                        gpsUpdate.latitude,
                        gpsUpdate.longitude,
                        gpsUpdate.accuracy,
                        gpsUpdate.altitude
                    )
                    Log.d(tag, "GPS received: deviceId=${gpsUpdate.deviceId}, lat=${gpsUpdate.latitude}, lon=${gpsUpdate.longitude}")
                } catch (e: Exception) {
                    Log.e(tag, "Error processing GPS update: ${e.message}", e)
                    _errorMessage.value = "Failed to process GPS: ${e.message}"
                }
            }
        }

        // Subscribe to network events (deauths, handshakes, discoveries) → feeds AI + learning
        viewModelScope.launch {
            handler.networkEventUpdates.collect { event ->
                _lastNetworkEvent.value = event
                Log.i(tag, "Network event for AI: ${event.eventType} — ${event.description}")

                // Track discovered APs (for untapped-target spotting). Every association/
                // discovery with a BSSID bumps its sighting count; a capture later removes
                // it from the "never caught" set via recomputeUntapped().
                if (event.eventType == "network_discovered" && !event.bssid.isNullOrBlank()) {
                    val k = event.bssid!!.lowercase()
                    val prevAp = discoveredAps[k]
                    if (prevAp == null) noteNewApSeen()   // genuinely new BSSID → churn signal
                    discoveredAps[k] = SeenAp(
                        ssid = event.network ?: prevAp?.ssid ?: "",
                        bssid = event.bssid!!,
                        rssi = event.signal ?: prevAp?.rssi ?: -90,
                        channel = event.channel ?: prevAp?.channel ?: 0,
                        count = (prevAp?.count ?: 0) + 1,
                    )
                    recomputeUntapped()
                }

                // Feed the on-screen terminal log (themed, hacker-style lines)
                val ch = event.channel?.let { " (ch$it)" } ?: ""
                appendLog(when (event.eventType) {
                    "handshakes_captured" -> "[+] handshake captured :: ${event.network ?: "?"}$ch"
                    "network_discovered"  -> "[*] target acquired :: ${event.network ?: "?"}$ch"
                    "anomaly_detected"    -> "[!] deauth/anomaly :: ${event.network ?: "spectrum"}$ch"
                    else                  -> "[>] ${event.eventType} :: ${event.network ?: ""}"
                })

                // Record observation for learning — only in AUTO mode (no scanning in MANUAL)
                val memService = wifiMemoryService ?: return@collect
                if (!_isAutoMode.value) {
                    Log.d(tag, "MANUAL mode — skipping learning observation for ${event.eventType}")
                    return@collect
                }
                if (event.eventType == "handshakes_captured" || event.eventType == "network_discovered" || event.eventType == "anomaly_detected") {
                    viewModelScope.launch {
                        try {
                            val gps = _gpsData.value
                            val now = System.currentTimeMillis()
                            val hour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
                            val obs = com.wsvdmeer.pwncompanion.database.WifiObservation(
                                ssid               = event.network ?: "unknown",
                                bssid              = event.bssid ?: "",
                                channel            = event.channel ?: 0,
                                security           = event.security ?: "WPA2",
                                attacks_sent       = if (event.eventType == "anomaly_detected") 1 else 0,
                                handshakes_captured = if (event.eventType == "handshakes_captured") event.count.coerceAtLeast(1) else 0,
                                latitude           = gps?.latitude ?: 0.0,
                                longitude          = gps?.longitude ?: 0.0,
                                timestamp          = now,
                                hourOfDay          = hour,
                            )
                            memService.recordObservation(obs)
                            Log.d(tag, "Learning observation recorded: ${event.eventType} ch=${obs.channel} hs=${obs.handshakes_captured}")
                            // Refresh learning stats after each new observation
                            loadLearningStats()
                        } catch (e: Exception) {
                            Log.e(tag, "Failed to record learning observation: ${e.message}", e)
                        }
                    }
                }
            }
        }

        // Subscribe to device mood updates — sync pwnagotchi mood → app mood
        viewModelScope.launch {
            handler.deviceMoodUpdates.collect { moodUpdate ->
                _deviceMood.value = moodUpdate.moodName
                Log.i(tag, "Device mood synced: ${moodUpdate.moodName}")
            }
        }

        // Subscribe to device mode updates — AUTO vs MANUAL
        viewModelScope.launch {
            handler.deviceModeUpdates.collect { modeUpdate ->
                when {
                    !modeUserControlled -> {
                        // Plugin now reports the REAL mode (read from pwnagotchi's View),
                        // so trust it by default.
                        _isAutoMode.value = modeUpdate.isAutoMode
                        Log.i(tag, "Device mode synced: ${if (modeUpdate.isAutoMode) "AUTO" else "MANUAL"}")
                    }
                    modeUpdate.isAutoMode == _isAutoMode.value -> {
                        // Device confirmed the mode the user just requested — stop
                        // overriding and resume following the device.
                        modeUserControlled = false
                        Log.d(tag, "Device confirmed user-requested mode; resuming sync")
                    }
                    else -> Log.d(tag, "Ignoring stale mode report until device confirms user's choice")
                }
            }
        }

        // Subscribe to incoming status updates
        viewModelScope.launch {
            handler.deviceStatusUpdates.collect { statusUpdate ->
                try {
                    _currentStatusMessage.value = statusUpdate.message
                    Log.d(tag, "Status received: deviceId=${statusUpdate.deviceId}, status=${statusUpdate.status}")
                } catch (e: Exception) {
                    Log.e(tag, "Error processing status update: ${e.message}", e)
                    _errorMessage.value = "Failed to process status: ${e.message}"
                }
            }
        }
    }

    /**
     * Subscribe to device state updates from NetworkService.
     * Uses reactive Flow subscription instead of polling.
     */
    private fun subscribeToDeviceStates() {
        val service = networkService ?: return

        // Mirror the REAL server-running state, so the status indicator + start/stop
        // button stay correct no matter who started the service (e.g. the BT receiver
        // on connect, not just the launch path).
        viewModelScope.launch {
            service.isServerRunning.collect { running -> _isServerRunning.value = running }
        }
        // "Networking armed" — desired but maybe not yet bound (no Bluetooth link). Lets
        // the UI show a "waiting for link" state instead of a dead "start service" button.
        viewModelScope.launch {
            service.networkingDesiredState.collect { desired -> _networkingArmed.value = desired }
        }

        // The phone's own GPS fix → app [ gps ] section (Pwnagotchi has no GPS).
        viewModelScope.launch {
            service.gpsData.collect { gps -> if (gps != null) { _gpsData.value = gps; onGpsFix(gps) } }
        }

        viewModelScope.launch {
            service.deviceStates.collect { states ->
                val prev = _connectedDeviceCount.value
                _deviceStates.value = states
                _connectedDeviceCount.value = states.size
                Log.d(tag, "Device states updated: ${states.size} devices")

                // Drive the screen image from the persistent device state (not only the
                // transient image SharedFlow). The SharedFlow can miss an emit across a
                // disconnect→reconnect, which left the screen blank until an app restart;
                // device state always carries the last frame, so the screen self-heals.
                states.values
                    .filter { !it.lastImageData.isNullOrEmpty() }
                    .maxByOrNull { it.lastImageTimestamp ?: 0L }
                    ?.let { freshest ->
                        _currentImageData.value = freshest.lastImageData
                        _currentImageDeviceId.value = freshest.deviceId
                        _currentImageTimestamp.value = freshest.lastImageTimestamp
                    }

                // Surface the device's capture history (newest first across all nodes)
                val allCaptures = states.values
                    .flatMap { it.captures }
                    .sortedByDescending { it.timestamp ?: 0L }
                if (allCaptures.size > _captures.value.size) {
                    appendLog("[*] captures :: ${allCaptures.size} logged")
                }
                _captures.value = allCaptures
                recomputeUntapped()   // a fresh catch may retire an untapped target

                // Surface latest device telemetry (vitals / reward / mood).
                states.values.firstNotNullOfOrNull { it.telemetry }?.let { _telemetry.value = it }

                // Terminal log on link transitions
                if (states.size > prev) appendLog("[+] node linked :: ${states.size} online")
                else if (states.isEmpty() && prev > 0) appendLog("[x] node disconnected")

                // Clear transient display data when no devices are connected
                if (states.isEmpty()) {
                    _currentImageData.value = null
                    _currentImageDeviceId.value = null
                    _currentImageTimestamp.value = null
                    _currentStatusMessage.value = null
                    _gpsData.value = null
                    _lastNetworkEvent.value = null
                    _telemetry.value = null
                    _channelPriority.value = emptyList()
                    Log.i(tag, "Device disconnected — display data cleared")
                }
            }
        }
    }

    /**
     * Load learning statistics from database.
     * Called periodically to update UI with learned data.
     */
    private fun loadLearningStats() {
        viewModelScope.launch {
            try {
                val service = wifiMemoryService ?: return@launch
                val stats = service.getLearningStats()
                _learningStats.value = stats
                Log.d(tag, "Learning stats loaded: ${stats.totalObservations} observations, best Ch=${stats.bestChannel}")
            } catch (e: Exception) {
                Log.e(tag, "Error loading learning stats: ${e.message}", e)
                _errorMessage.value = "Failed to load learning data: ${e.message}"
            }
        }
    }

    /**
     * Subscribe to outgoing queue state.
     */
    private fun subscribeToQueueState() {
        val queue = outgoingQueue ?: return

        viewModelScope.launch {
            queue.queueSize.collect { size ->
                _outgoingQueueSize.value = size
                Log.d(tag, "Outgoing queue size: $size")
            }
        }
    }

    /**
     * Queue a location response to the device.
     * Used when device requests GPS location.
     */
    @Suppress("UNUSED")
    fun sendLocationResponse(deviceId: String, latitude: Double, longitude: Double, accuracy: Float?, altitude: Double = 0.0) {
        val queue = outgoingQueue ?: run {
            _errorMessage.value = "Outgoing queue not initialized"
            return
        }

        try {
            queue.queueLocationResponse(deviceId, latitude, longitude, accuracy, altitude)
            Log.i(tag, "Location response queued: $deviceId")
        } catch (e: Exception) {
            Log.e(tag, "Error sending location response: ${e.message}", e)
            _errorMessage.value = "Failed to queue location: ${e.message}"
        }
    }

    /**
     * Send a command to the first connected pwnagotchi device.
     * Used for restart_auto, restart_manual, etc.
     */
    fun sendPwnagotchiCommand(action: String) {
        val queue = outgoingQueue ?: run {
            _errorMessage.value = "Outgoing queue not initialized"
            return
        }
        val deviceId = _deviceStates.value.keys.firstOrNull() ?: run {
            _errorMessage.value = "No device connected"
            return
        }
        try {
            // commandType becomes the "message" field in ScreenData JSON,
            // plugin reads it via: action = data.get("action") or data.get("message")
            queue.queueCommand(deviceId, action, null)
            Log.i(tag, "Pwnagotchi command queued: $action → $deviceId")
        } catch (e: Exception) {
            Log.e(tag, "Error sending pwnagotchi command: ${e.message}", e)
            _errorMessage.value = "Failed to send command: ${e.message}"
        }
    }

    /**
     * Request an AUTO/MANUAL mode switch. Updates the displayed mode immediately
     * (optimistic) so the toggle visibly responds, and tells the Pwnagotchi to
     * switch. The device's own mode report (deviceModeUpdates) reconciles later.
     */
    fun requestMode(auto: Boolean) {
        modeUserControlled = true
        _isAutoMode.value = auto
        appendLog(if (auto) "[>] switch → AUTO" else "[>] switch → MANUAL")
        sendPwnagotchiCommand(if (auto) "restart_auto" else "restart_manual")
    }

    /**
     * Push the app's AI voice pool to every connected device. The plugin splices these
     * per-category lines into the pwnagotchi's own speech bubble. Sent via the command
     * channel (set_voice_pool) so no new wire type is needed; JSON is {category:[line,…]}.
     */
    fun sendVoicePool(lines: Map<String, List<String>>) {
        val queue = outgoingQueue ?: return
        if (lines.isEmpty()) return
        val deviceIds = _deviceStates.value.keys
        if (deviceIds.isEmpty()) return
        val json = try {
            val obj = org.json.JSONObject()
            lines.forEach { (k, v) -> obj.put(k, org.json.JSONArray(v)) }
            obj.toString()
        } catch (e: Exception) {
            Log.e(tag, "Error serializing voice pool: ${e.message}")
            return
        }
        // Dedupe: the observing LaunchedEffect re-fires on connectedDevices churn, so
        // skip re-pushing an identical pool to the same device set. Keying on the device
        // set means a new/re-added device still forces a fresh seed.
        val key = deviceIds.sorted().joinToString(",") + "|" + json
        if (key == _lastVoicePoolKey) return
        _lastVoicePoolKey = key
        deviceIds.forEach { queue.queueCommand(it, "set_voice_pool", json) }
        Log.d(tag, "Voice pool pushed to ${deviceIds.size} device(s): ${lines.keys}")
    }
    @Volatile private var _lastVoicePoolKey: String? = null

    /**
     * Queue a generic command to the device.
     */
    @Suppress("UNUSED")
    fun sendCommand(deviceId: String, commandType: String, commandData: String?) {
        val queue = outgoingQueue ?: run {
            _errorMessage.value = "Outgoing queue not initialized"
            return
        }

        try {
            queue.queueCommand(deviceId, commandType, commandData)
            Log.i(tag, "Command queued: device=$deviceId, type=$commandType")
        } catch (e: Exception) {
            Log.e(tag, "Error sending command: ${e.message}", e)
            _errorMessage.value = "Failed to queue command: ${e.message}"
        }
    }

    /**
     * Update server running state.
     * Called from MainActivity lifecycle.
     */
    fun setServerRunning(running: Boolean) {
        _isServerRunning.value = running
    }

    /**
     * Stop the WebSocket server and all network services.
     * Stops: WebSocket server, UDP announcements, GPS service, message queue.
     */
    fun stopServer() {
        val service = networkService ?: run {
            _errorMessage.value = "Network service not available"
            return
        }

        try {
            // Stop the WebSocket/UDP server immediately via the singleton so the UI reflects
            // the stopped state before the service intent round-trip completes.
            service.stop()
            _isServerRunning.value = false

            // Tell CompanionBackgroundService to do a full shutdown:
            // removes the foreground notification, unregisters BluetoothTetherMonitor (so
            // bnep0 still being alive cannot auto-restart the server), and stops GpsService.
            val stopIntent = Intent(getApplication(), CompanionBackgroundService::class.java).apply {
                action = CompanionBackgroundService.ACTION_STOP_NETWORKING
            }
            getApplication<Application>().startService(stopIntent)

            Log.i(tag, "Stop requested — sent ACTION_STOP_NETWORKING to CompanionBackgroundService")
        } catch (e: Exception) {
            Log.e(tag, "Error stopping server: ${e.message}", e)
            _errorMessage.value = "Failed to stop server: ${e.message}"
        }
    }

    /**
     * Start networking — re-registers the Bluetooth monitor and brings the
     * WebSocket server + UDP announcer back up (the inverse of [stopServer]).
     * Used by the [ start service ] command after a manual stop.
     */
    fun startServer() {
        try {
            val startIntent = Intent(getApplication(), CompanionBackgroundService::class.java).apply {
                action = CompanionBackgroundService.ACTION_START_NETWORKING
            }
            getApplication<Application>().startService(startIntent)
            // Immediate feedback: the WebSocket server can only bind over the Bluetooth
            // tether (bnep0), so if you're away from the pwnagotchi it can't come up yet —
            // it'll auto-bind the moment the link appears. Say so rather than no-op silently.
            appendLog("[*] networking armed — waiting for the Bluetooth link to the pwnagotchi…")
            Log.i(tag, "Start requested — sent ACTION_START_NETWORKING to CompanionBackgroundService")
        } catch (e: Exception) {
            Log.e(tag, "Error starting server: ${e.message}", e)
            _errorMessage.value = "Failed to start server: ${e.message}"
        }
    }

    /**
     * Clear error message.
     */
    fun clearError() {
        _errorMessage.value = null
    }

    /**
     * Update GPS data from device.
     */
    fun updateGpsData(latitude: Double, longitude: Double, accuracy: Double, altitude: Double) {
        _gpsData.value = GpsData(
            latitude = latitude,
            longitude = longitude,
            accuracy = accuracy,
            altitude = altitude,
            timestamp = System.currentTimeMillis()
        )
        Log.d(tag, "GPS updated: $latitude, $longitude (±${accuracy}m)")
    }

    /**
     * Get connected devices list.
     */
    @Suppress("UNUSED")
    fun getConnectedDevices(): List<DeviceState> = _deviceStates.value.values.toList()

    /**
     * Get device by ID.
     */
    @Suppress("UNUSED")
    fun getDevice(deviceId: String): DeviceState? = _deviceStates.value[deviceId]

    override fun onCleared() {
        super.onCleared()
        Log.i(tag, "ViewModel cleared")
    }
}
