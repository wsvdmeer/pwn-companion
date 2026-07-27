package com.wsvdmeer.pwncompanion.services

import android.Manifest
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.core.content.PermissionChecker
import com.wsvdmeer.pwncompanion.models.DeviceState
import com.wsvdmeer.pwncompanion.models.ScreenData
import com.wsvdmeer.pwncompanion.protocol.MessageHandler
import com.wsvdmeer.pwncompanion.protocol.OutgoingMessageQueue
import com.wsvdmeer.pwncompanion.utils.NotificationHelper
import com.wsvdmeer.pwncompanion.workers.WorkScheduler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicLong

/**
 * Network Service Orchestrator.
 * Manages lifecycle of WebSocket server, UDP announcements, and Bluetooth monitoring.
 * Exposes reactive state via Flow/StateFlow for UI consumption.
 * Integrates WorkManager for battery-aware background task scheduling.
 * Coordinates message protocol handling via MessageHandler.
 */
class NetworkService(private val context: Context) {
    private val tag = "NetworkService"
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    // Message protocol handling
    private val messageHandler = MessageHandler()
    private val outgoingMessageQueue = OutgoingMessageQueue()

    /** Last GPS data we received from the Android location provider.
     *  Sent immediately to a newly connected Pwnagotchi and on every gps_request. */
    @Volatile
    private var lastGpsData: com.wsvdmeer.pwncompanion.models.ScreenData? = null

    /** The phone's own last GPS fix, exposed for the app's [ gps ] section.
     *  Sourced here (not from device echoes) because the Pwnagotchi has no GPS —
     *  the phone is the GPS provider, so this is the authoritative coordinate. */
    private val _gpsData = MutableStateFlow<com.wsvdmeer.pwncompanion.models.GpsData?>(null)
    val gpsData: StateFlow<com.wsvdmeer.pwncompanion.models.GpsData?> = _gpsData.asStateFlow()

    /**
     * Update the cached GPS snapshot. Called by GpsService on every location fix.
     * Thread-safe via @Volatile — only used for reads/single-assignment writes.
     */
    fun updateLastGpsData(data: com.wsvdmeer.pwncompanion.models.ScreenData) {
        lastGpsData = data
        val lat = data.latitude
        val lon = data.longitude
        if (lat != null && lon != null) {
            _gpsData.value = com.wsvdmeer.pwncompanion.models.GpsData(
                latitude = lat,
                longitude = lon,
                accuracy = data.accuracy ?: 0.0,
                altitude = data.altitude ?: 0.0,
                speed = data.speed,
                timestamp = data.timestamp ?: System.currentTimeMillis(),
            )
        }
    }

    // Services
    private val webSocketServer = WebSocketServerService(
        onClientConnected = { deviceId, deviceName, clientIp -> onClientConnected(deviceId, deviceName, clientIp) },
        onClientDisconnected = { deviceId -> onClientDisconnected(deviceId) },
        onDataReceived = { deviceId, data -> onDataReceived(deviceId, data) }
    )
    private val udpAnnouncer = UdpAnnouncementService(scope, context)  // Inject scope and context
    
    // Debouncing for BNEP0 state changes to prevent rapid flapping
    // This prevents "br-connection-busy" errors from too many start/stop cycles
    private val lastStateChangeTime = AtomicLong(0)
    private val stateChangeThrottleMs = 5000L  // ✅ CRITICAL FIX: Increased from 2s to 5s for stability
    private var lastBnep0State: Boolean? = null  // Track last known state to avoid duplicate callbacks
    private var lastBnep0Ip: String? = null  // Track last IP to detect IP changes on reconnect

    // Throttle the rich "live pet" notification (face + stats) — images arrive ~1/s.
    @Volatile private var lastRichNotifMs = 0L
    private val RICH_NOTIF_INTERVAL_MS = 8_000L

    /**
     * Build & post the glanceable foreground notification with the Pwnagotchi's e-ink
     * face and a live stats line (name · caught · clients · temp). Decodes the latest
     * screen frame off the wire's base64. No-op if there's no connected device/image.
     */
    private fun pushRichNotification() {
        val dev = _deviceStates.value.values.maxByOrNull { it.lastImageTimestamp ?: 0L } ?: return
        val face = dev.lastImageData?.let { b64 ->
            runCatching {
                val bytes = android.util.Base64.decode(b64, android.util.Base64.DEFAULT)
                android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            }.getOrNull()
        }
        val t = dev.telemetry
        val caught = t?.totalHandshakes ?: dev.captures.size
        val stats = buildList {
            add("$caught caught")
            t?.numSta?.let { add("$it clients") }
            t?.temperature?.let { add("${it.toInt()}°C") }
            // GPS fix we're feeding the device (now that GPS shares this one notification).
            lastGpsData?.let { g ->
                val la = g.latitude; val lo = g.longitude
                if (la != null && lo != null && (la != 0.0 || lo != 0.0)) {
                    add("gps ${"%.4f".format(la)},${"%.4f".format(lo)}")
                }
            }
        }.joinToString(" · ")
        val title = dev.pwnagotchiName ?: dev.deviceName ?: "PwnCompanion"
        NotificationHelper.updateNetworkNotification(
            context, lastBnep0Ip, webSocketServer.getConnectedClientCount(),
            title = title, stats = stats, face = face,
        )
    }
    @Volatile private var gpsServiceRunning = false  // Track if GPS foreground service is running
    @Volatile private var lastGpsEnsureMs = 0L       // throttle self-heal restarts of GpsService
    private var ipMonitoringJob: kotlinx.coroutines.Job? = null  // Job for monitoring IP changes
    private var messageQueueProcessorJob: kotlinx.coroutines.Job? = null  // Job for processing outgoing messages
    
    val bluetoothMonitor = BluetoothTetherMonitor(context) { detected ->
        scope.launch {
            handleBluetoothStateChange(detected)
        }
    }

    // State
    private val _deviceStates = MutableStateFlow<Map<String, DeviceState>>(emptyMap())
    @Suppress("UNUSED")
    val deviceStates: StateFlow<Map<String, DeviceState>> = _deviceStates.asStateFlow()

    private val _isServerRunning = MutableStateFlow(false)
    @Suppress("UNUSED")
    val isServerRunning: StateFlow<Boolean> = _isServerRunning.asStateFlow()

    private val _connectedDeviceCount = MutableStateFlow(0)
    @Suppress("UNUSED")
    val connectedDeviceCount: StateFlow<Int> = _connectedDeviceCount.asStateFlow()

    // Cracked-notification bookkeeping: BSSIDs we've already alerted on, plus a
    // "baselined" flag so the initial batch on (re)connect seeds silently instead of
    // firing a notification for every previously-cracked network.
    private val crackedNotified = java.util.Collections.synchronizedSet(HashSet<String>())
    @Volatile private var crackedBaselined = false

    private var serverStarted = false
    // "User wants networking on" — distinct from serverStarted (which is false after
    // a failed bind). The health check uses this to recover even when bnep is
    // already up so there's no down→up transition to re-trigger start().
    // Backed by a StateFlow so the UI can distinguish "armed but waiting for the
    // Bluetooth link" (desired && !running) from "stopped by the user" (!desired).
    private val _networkingDesired = MutableStateFlow(false)
    val networkingDesiredState: StateFlow<Boolean> = _networkingDesired.asStateFlow()
    private var networkingDesired: Boolean
        get() = _networkingDesired.value
        set(value) { _networkingDesired.value = value }
    private var healthCheckJob: kotlinx.coroutines.Job? = null

    /**
     * Handle Bluetooth state changes with debouncing.
     * Prevents rapid start/stop cycles that cause "br-connection-busy" errors.
     * Detects IP address changes on reconnect and forces proper restart.
     * - Ignores duplicate state changes
     * - Throttles rapid state changes (must wait 2s between state changes)
     * - Detects IP changes and forces restart for proper rebinding
     * - Logs all attempts for debugging flaky connections
     */
    private suspend fun handleBluetoothStateChange(detected: Boolean) {
        val currentTime = System.currentTimeMillis()
        val timeSinceLastChange = currentTime - lastStateChangeTime.get()
        
        // Check if this is a duplicate state change
        // Exception: if service isn't running and BT is detected, always allow a retry
        // (covers the case where start() failed due to DHCP not being ready yet)
        if (lastBnep0State == detected) {
            if (detected && !serverStarted) {
                Log.d(tag, "♻️ Allowing retry: BT still detected but service not running (previous start may have failed)")
            } else {
                Log.d(tag, "⏭️ Ignoring duplicate BNEP0 state change: $detected (already in this state)")
                return
            }
        }
        
        // Check if we're throttled (too soon after last change)
        if (timeSinceLastChange < stateChangeThrottleMs) {
            Log.w(tag, "⏱️ BNEP0 state change throttled: only ${timeSinceLastChange}ms since last change")
            Log.w(tag, "   Ignoring state change (will wait ${stateChangeThrottleMs - timeSinceLastChange}ms)")
            // Reset lastBnep0State so the next event won't be treated as a duplicate.
            // Without this, a throttled disconnect keeps lastBnep0State=true, causing the
            // subsequent reconnect to be silently ignored as a "duplicate".
            lastBnep0State = null
            return
        }
        
        // Valid state change - proceed
        lastStateChangeTime.set(currentTime)
        lastBnep0State = detected
        
        if (detected) {
            Log.i(tag, "✅ BNEP0 detected (state was stable for ${timeSinceLastChange}ms) - starting services")
            
            // Check if IP address changed on reconnect
            val currentIp = bluetoothMonitor.getBnep0InterfaceIp()
            if (currentIp != null && currentIp != lastBnep0Ip) {
                if (lastBnep0Ip != null) {
                    Log.i(tag, "🔄 BNEP0 IP changed: $lastBnep0Ip → $currentIp")
                    Log.i(tag, "   Force stopping old server to clean up port bindings...")
                    stop()
                    // Wait for port to be released from TIME_WAIT state
                    Log.i(tag, "   Waiting 5s for port to be released from TIME_WAIT...") // ✅ CRITICAL FIX: Increased to 5s
                    delay(5000)  // ✅ CRITICAL FIX: Increased from 3000ms to 5000ms
                    Log.i(tag, "   Starting fresh with new IP: $currentIp")
                }
                lastBnep0Ip = currentIp
            }
            
            start()
        } else {
            Log.i(tag, "❌ BNEP0 disconnected (state was stable for ${timeSinceLastChange}ms) - stopping services")
            lastBnep0Ip = null
            stop()
        }
    }

    /**
     * Initialize network service.
     * Registers Bluetooth monitor callback.
     * NOTE: Should be called AFTER Bluetooth permissions are granted.
     */
    fun initialize() {
        Log.i(tag, "Initializing NetworkService")
        // Networking is desired by default from launch, so the health check is a
        // reliable backstop: if bnep is already up when the app starts (app launched
        // AFTER the Pwnagotchi), there's no down→up transition for the monitor to
        // fire — the health check still brings the server up. Closes the timing gap.
        networkingDesired = true
        scope.launch {
            bluetoothMonitor.register()
        }
        startHealthCheck()
    }

    /**
     * Periodic self-heal: if networking is desired and the bnep link is up but the
     * WebSocket port is NOT actually bound, force a clean rebind. Covers two cases
     * the transition-based monitor misses: (a) the initial bind failed (stale
     * socket after reinstall) with bnep already up, and (b) the server engine died
     * while bnep stayed up. Without this the app silently advertises a dead port.
     */
    private fun startHealthCheck() {
        healthCheckJob?.cancel()
        healthCheckJob = scope.launch {
            while (isActive) {
                delay(12_000)
                try {
                    if (!networkingDesired) continue
                    val bnepUp = bluetoothMonitor.getBnep0InterfaceIp() != null
                    if (bnepUp && !webSocketServer.isRunning()) {
                        Log.w(tag, "🩺 Health check: bnep up but server not listening — forcing rebind")
                        serverStarted = false
                        lastBnep0State = null
                        lastBnep0Ip = null
                        start()
                    }
                } catch (e: Exception) {
                    Log.e(tag, "Health check error: ${e.message}")
                }
            }
        }
    }

    /**
     * Re-initialize Bluetooth monitor after permissions are granted.
     * Call this method from MainActivity after BLUETOOTH_CONNECT permission is granted.
     */
    fun reinitializeBluetoothMonitor() {
        Log.i(tag, "Re-initializing Bluetooth monitor (permissions now granted)")
        scope.launch {
            bluetoothMonitor.register()
        }
    }

    /**
     * Explicit user request to (re)start networking (the [ start service ] button).
     * Marks networking as desired and starts immediately if bnep is already up —
     * the BluetoothTetherMonitor only fires on a down→up transition, so without this
     * a start while the interface is already present would do nothing. The 12 s
     * health check then keeps it up. Safe no-op if bnep isn't present yet.
     */
    fun requestStart() {
        networkingDesired = true
        scope.launch {
            if (!serverStarted && bluetoothMonitor.getBnep0InterfaceIp() != null) {
                Log.i(tag, "requestStart: bnep already up — starting now")
                start()
            } else {
                Log.i(tag, "requestStart: networking desired; will start when bnep appears / via health check")
            }
        }
    }

    /**
     * Start WebSocket server and UDP announcements.
     * Schedules WorkManager background tasks with battery/network constraints.
     * CRITICAL: Reset device state when starting to ensure fresh discovery
     * (fixes issue where app restart doesn't announce to pwnagotchi)
     */
    fun start() {
        if (serverStarted) {
            Log.w(tag, "Server already started")
            return
        }

        serverStarted = true
        networkingDesired = true
        _isServerRunning.value = true
        Log.i(tag, "═══════════════════════════════════════════")
        Log.i(tag, "▶ NETWORK SERVICE STARTING")
        Log.i(tag, "═══════════════════════════════════════════")

        scope.launch(Dispatchers.IO) {
            try {
                // CRITICAL: Reset device states on service start
                _deviceStates.value = emptyMap()
                _connectedDeviceCount.value = 0
                Log.i(tag, "✓ Device states cleared (fresh discovery)")

                // Wait for DHCP to complete — the bnep0 interface can appear before Android's
                // DHCP client has assigned an IP. Retry up to 10 times (20 seconds total).
                // Without this, start() fails silently and the service never recovers because
                // checkBnep0Interface won't re-fire once bnep0Detected is already true.
                var bnep0Ip: String? = null
                val maxAttempts = 10
                for (attempt in 1..maxAttempts) {
                    bnep0Ip = bluetoothMonitor.getBnep0InterfaceIp()
                    if (bnep0Ip != null) {
                        Log.i(tag, "📡 Got bnep0 IP on attempt $attempt/$maxAttempts: $bnep0Ip")
                        break
                    }
                    Log.w(tag, "⏳ bnep0 IP not ready yet (attempt $attempt/$maxAttempts) — DHCP may still be running, retrying in 2s...")
                    delay(2000)
                }

                if (bnep0Ip == null) {
                    Log.e(tag, "✗ Failed to get bnep0 interface IP after $maxAttempts attempts — DHCP never completed?")
                    _isServerRunning.value = false
                    serverStarted = false
                    return@launch
                }
                
                Log.i(tag, "📡 Using bnep0 interface IP: $bnep0Ip")

                // Start WebSocket server on the actual bnep0 interface IP.
                // CRITICAL: gate the UDP announcer on the server ACTUALLY binding.
                // Previously the announcer started unconditionally, so a failed bind
                // (stale socket after reinstall) left us broadcasting a dead port —
                // the Pi would connect and get connection-refused forever.
                val serverUp = webSocketServer.start(bnep0Ip)
                if (!serverUp) {
                    Log.e(tag, "✗ WebSocket server failed to bind on $bnep0Ip:8081 — NOT announcing a dead port")
                    _isServerRunning.value = false
                    serverStarted = false
                    return@launch
                }
                Log.i(tag, "✓ WebSocket server bound on ws://$bnep0Ip:8081")

                // Update notification with live IP (still 0 devices at this point)
                NotificationHelper.updateNetworkNotification(context, ip = bnep0Ip, deviceCount = 0)

                // Start UDP announcements with the actual bnep0 IP
                udpAnnouncer.start(bnep0Ip, 8081)
                Log.i(tag, "✓ UDP announcements started (broadcasting $bnep0Ip:8081 to bnep0 subnet)")

                // Schedule WorkManager tasks (Phase 4)
                WorkScheduler.scheduleWebSocketServer(context)
                // Coarse fallback only — floored to WorkManager's 900 s minimum.
                // Real-time GPS comes from GpsService.
                WorkScheduler.scheduleGpsTracking(context)
                Log.i(tag, "✓ WorkManager background tasks scheduled")
                Log.i(tag, "═══════════════════════════════════════════")
                Log.i(tag, "▶ NETWORK SERVICE READY - Waiting for connections...")
                Log.i(tag, "═══════════════════════════════════════════")

            } catch (e: Exception) {
                Log.e(tag, "✗ Error starting services: ${e.message}", e)
                _isServerRunning.value = false
                serverStarted = false
            }
        }
    }

    /**
     * Stop WebSocket server and UDP announcements.
     * Cancels WorkManager background tasks.
     * ✅ NOTE: UDP is stopped here when service is fully stopped,
     *           but NOT stopped on client connect (allows reconnection)
     */
    fun stop() {
        if (!serverStarted) {
            Log.w(tag, "Server not running")
            return
        }

        serverStarted = false
        networkingDesired = false   // user stopped — don't let the health check rebind
        _isServerRunning.value = false
        // Reset BT state tracking so Bluetooth reconnect can restart the service after a manual stop
        lastBnep0State = null
        lastBnep0Ip = null
        Log.i(tag, "═══════════════════════════════════════════")
        Log.i(tag, "⏹ NETWORK SERVICE STOPPING")
        Log.i(tag, "═══════════════════════════════════════════")

        scope.launch(Dispatchers.IO) {
            try {
                webSocketServer.stop()
                Log.i(tag, "✓ WebSocket server stopped")

                udpAnnouncer.stop()
                Log.i(tag, "✓ UDP announcements stopped")

                // Cancel WorkManager tasks
                WorkScheduler.cancelAllWork(context)
                Log.i(tag, "✓ WorkManager background tasks cancelled")
                Log.i(tag, "═══════════════════════════════════════════")

            } catch (e: Exception) {
                Log.e(tag, "Error stopping services: ${e.message}", e)
            }
        }
    }

    /**
     * Cleanup resources.
     */
    fun cleanup() {
        scope.launch {
            stop()
            bluetoothMonitor.unregister()
            messageHandler.cleanup()
            outgoingMessageQueue.cleanup()
        }
    }

    // Callbacks

    private fun onClientConnected(deviceId: String, deviceName: String, clientIp: String) {
        scope.launch {
            // Merge onto any state a message already created (capture_history can land before this
            // registration coroutine runs) so we set the connection fields without wiping captures.
            _deviceStates.update { states ->
                val base = states[deviceId] ?: DeviceState(
                    deviceId = deviceId, deviceName = deviceName,
                    ipAddress = clientIp, interfaceName = "bnep0",
                )
                val state = base.copy(
                    deviceName = deviceName,
                    ipAddress = clientIp,  // Use the actual Pwnagotchi's IP
                    port = 8081,
                    interfaceName = "bnep0",
                    isConnected = true,
                    connectionState = DeviceState.ConnectionState.CONNECTED,
                )
                states.toMutableMap().apply { put(deviceId, state) }
            }
            _connectedDeviceCount.value = webSocketServer.getConnectedClientCount()

            // Refresh notification with updated device count
            val ip = bluetoothMonitor.getBnep0InterfaceIp()
            NotificationHelper.updateNetworkNotification(context, ip, webSocketServer.getConnectedClientCount())

            // (No separate "Connected" alert — the ongoing notice already shows link state, and
            // a flaky BT link would bounce it. Cracked-password alerts are the only ping.)

            // ✅ CRITICAL FIX: Keep UDP announcements running
            // If we stop them, Pwnagotchi can't reconnect after disconnect
            // Only stop them if we truly stop the service
            Log.i(tag, "✓ Keeping UDP announcements active for reconnection support")

            // Start GPS foreground service to provide location updates
            startGpsService()

            // Immediately push the last known GPS to the newly connected device so it gets
            // location data right away rather than waiting for the next Android location update.
            val cachedGps = lastGpsData
            if (cachedGps != null) {
                outgoingMessageQueue.enqueue(deviceId, cachedGps)
                Log.i(tag, "📍 Sent cached GPS to newly connected device $deviceId (lat=${cachedGps.latitude}, lon=${cachedGps.longitude})")
            } else {
                Log.d(tag, "No cached GPS yet — device will receive GPS on next Android location update")
            }

            // Start message queue processor to send queued messages to device
            if (messageQueueProcessorJob == null || !messageQueueProcessorJob!!.isActive) {
                startMessageQueueProcessor()
            }

            // Start IP monitoring if not already running
            if (ipMonitoringJob == null || !ipMonitoringJob!!.isActive) {
                startIpMonitoring()
            }

            Log.i(tag, "Device connected: $deviceName (Client IP: $clientIp)")
        }
    }

    private fun onClientDisconnected(deviceId: String) {
        scope.launch {
            _deviceStates.update { it.toMutableMap().apply { remove(deviceId) } }
            _connectedDeviceCount.value = webSocketServer.getConnectedClientCount()

            // Refresh notification with updated device count
            val ip = bluetoothMonitor.getBnep0InterfaceIp()
            NotificationHelper.updateNetworkNotification(context, ip, webSocketServer.getConnectedClientCount())

            // Resume UDP announcements when last client disconnects so the Pwnagotchi can
            // re-discover us.  Only restart if BT is still connected — if the client dropped
            // because BT itself went away we must NOT restart the announcer, or it will spam
            // ENETUNREACH until the service is eventually killed.
            if (webSocketServer.getConnectedClientCount() == 0) {
                scope.launch {
                    try {
                        val bnep0Ip = bluetoothMonitor.getBnep0InterfaceIp()
                        if (bnep0Ip != null) {
                            udpAnnouncer.start(bnep0Ip, 8081)
                        } else {
                            Log.i(tag, "BT interface gone — skipping UDP announcer restart")
                        }
                    } catch (e: Exception) {
                        Log.e(tag, "Error resuming UDP announcements: ${e.message}")
                    }
                }
            }

            // NOTE: GpsService is deliberately NOT stopped here. A BT tether blip drops the
            // client constantly, and re-starting a location foreground service on reconnect is
            // blocked when the app is backgrounded (Android 12+) — which left GPS stuck at
            // "acquiring" while the pet still showed the last fix. GpsService is now tied to the
            // companion service's lifetime (started in the foreground, stopped only on full
            // stop), so it survives reconnects. Still stop the connect-scoped helpers.
            if (webSocketServer.getConnectedClientCount() == 0) {
                // Stop IP monitoring when no more devices connected
                stopIpMonitoring()
                // Stop message queue processor
                stopMessageQueueProcessor()
            }

            Log.i(tag, "Device disconnected: $deviceId")
        }
    }

    private fun onDataReceived(deviceId: String, data: ScreenData) {
        scope.launch {
            messageHandler.handleIncomingMessage(deviceId, data)

            // Respond to gps_request immediately with the last cached location.
            // MessageHandler.handleGpsRequestMessage() is a stub (it cannot access the GPS cache),
            // so we handle the response here where we have access to the outgoing queue and cache.
            if (data.type == ScreenData.TYPE_GPS_REQUEST) {
                val cachedGps = lastGpsData
                if (cachedGps != null) {
                    outgoingMessageQueue.enqueue(deviceId, cachedGps)
                    Log.i(tag, "📍 GPS request from $deviceId — responded with cached GPS (lat=${cachedGps.latitude}, lon=${cachedGps.longitude})")
                } else {
                    Log.d(tag, "📍 GPS request from $deviceId — no cached GPS yet")
                    // Self-heal: GpsService may have failed to start (e.g. blocked while the
                    // app was backgrounded), so there's no location source. Retry, throttled,
                    // so GPS recovers on its own without needing a reconnect.
                    val now = System.currentTimeMillis()
                    if (now - lastGpsEnsureMs > 20_000) {
                        lastGpsEnsureMs = now
                        Log.i(tag, "📍 No GPS source — (re)starting GpsService")
                        startGpsService(force = true)
                    }
                }
            }

            // Atomic read-modify-write: the whole compute runs inside update{} so two
            // overlapping onDataReceived coroutines can't both read the old map and
            // clobber each other's merge (which silently dropped captures/telemetry).
            // The lambda is pure (copy/mergeCaptures have no side effects), so it's safe
            // to re-run on CAS retry.
            _deviceStates.update { states ->
                // Don't drop data that arrives before onClientConnected has registered the device
                // (the plugin fires capture_history immediately on connect — it used to race the
                // async registration and get discarded, so captures only showed after a reconnect).
                val currentState = states[deviceId] ?: DeviceState(
                    deviceId = deviceId,
                    deviceName = data.deviceName ?: "pwnagotchi",
                    ipAddress = "",
                    interfaceName = "bnep0",
                    isConnected = true,
                    connectionState = DeviceState.ConnectionState.CONNECTED,
                )
                val updatedState = currentState.copy(
                    deviceName = data.deviceName ?: currentState.deviceName,
                    // Capture the Pwnagotchi's own name from plugin status messages
                    pwnagotchiName = data.resolvedDeviceName ?: currentState.pwnagotchiName,
                    lastImageData = data.data,
                    lastImageTimestamp = data.timestamp,
                    lastStatusMessage = data.message,
                    // Update auto-tune stats if present
                    autotuneChannels = data.autotuneChannels ?: currentState.autotuneChannels,
                    autotuneBestChannel = data.autotuneBestChannel ?: currentState.autotuneBestChannel,
                    autotuneMinRssi = data.autotuneMinRssi ?: currentState.autotuneMinRssi,
                    // Merge capture history: the plugin sends the full log on connect and
                    // single-entry appends on each new handshake. Dedupe by key (BSSID),
                    // newest first, keeping the richer (incoming) record on collision.
                    captures = applyCracked(
                        mergeCaptures(currentState.captures, data.captures),
                        if (data.type == ScreenData.TYPE_CRACKED) data.crackedResults else null,
                    ),
                    // Merge telemetry field-by-field so the lightweight vitals push
                    // (temp/cpu/mem only, sent every ~12s regardless of mode) doesn't
                    // wipe the richer per-epoch reward/density values between epochs.
                    telemetry = if (data.type == ScreenData.TYPE_DEVICE_TELEMETRY)
                        mergeTelemetry(currentState.telemetry, data.toTelemetry()) else currentState.telemetry,
                    // wpa-sec on/off + service reachability (from status messages).
                    wpaSecEnabled = data.wpaSecEnabled ?: currentState.wpaSecEnabled,
                    wpaSecOnline = data.wpaSecOnline ?: currentState.wpaSecOnline,
                    captureFileCount = data.totalFiles ?: currentState.captureFileCount,
                )
                states.toMutableMap().apply { put(deviceId, updatedState) }
            }

            // Cracked-password alerts — run OUTSIDE the update{} lambda (which can re-run
            // on CAS retry) so we never double-notify.
            if (data.type == ScreenData.TYPE_CRACKED) handleCracked(data.crackedResults)

            // Refresh the glanceable "live pet" notification (face + stats), throttled so
            // we don't decode a bitmap + re-post on every 1s image frame.
            val now = System.currentTimeMillis()
            if (now - lastRichNotifMs > RICH_NOTIF_INTERVAL_MS) {
                lastRichNotifMs = now
                runCatching { pushRichNotification() }
                    .onFailure { Log.w(tag, "rich notification failed: ${it.message}") }
            }

            Log.d(tag, "Data received from $deviceId: type=${data.type}")
        }
    }

    /**
     * Merge a telemetry update onto the previous one: each new non-null field wins, and
     * missing fields keep their prior value. Lets a sparse push (e.g. temp/cpu/mem only)
     * refresh vitals live without erasing per-epoch reward/density.
     */
    private fun mergeTelemetry(
        old: com.wsvdmeer.pwncompanion.models.DeviceTelemetry?,
        new: com.wsvdmeer.pwncompanion.models.DeviceTelemetry,
    ): com.wsvdmeer.pwncompanion.models.DeviceTelemetry {
        if (old == null) return new
        return com.wsvdmeer.pwncompanion.models.DeviceTelemetry(
            temperature = new.temperature ?: old.temperature,
            cpuLoad = new.cpuLoad ?: old.cpuLoad,
            memUsage = new.memUsage ?: old.memUsage,
            reward = new.reward ?: old.reward,
            numAps = new.numAps ?: old.numAps,
            numSta = new.numSta ?: old.numSta,
            numPeers = new.numPeers ?: old.numPeers,
            activeForEpochs = new.activeForEpochs ?: old.activeForEpochs,
            inactiveForEpochs = new.inactiveForEpochs ?: old.inactiveForEpochs,
            boredForEpochs = new.boredForEpochs ?: old.boredForEpochs,
            sadForEpochs = new.sadForEpochs ?: old.sadForEpochs,
            blindForEpochs = new.blindForEpochs ?: old.blindForEpochs,
            epoch = new.epoch ?: old.epoch,
            totalHandshakes = new.totalHandshakes ?: old.totalHandshakes,
        )
    }

    /**
     * Merge incoming captures into the existing list: dedupe by [CaptureEntry.key],
     * prefer the incoming record on collision, sort newest-first. Returns the
     * existing list unchanged when there's nothing new (avoids churn).
     */
    private fun mergeCaptures(
        existing: List<com.wsvdmeer.pwncompanion.models.CaptureEntry>,
        incoming: List<com.wsvdmeer.pwncompanion.models.CaptureEntry>?,
    ): List<com.wsvdmeer.pwncompanion.models.CaptureEntry> {
        if (incoming.isNullOrEmpty()) return existing
        val byKey = LinkedHashMap<String, com.wsvdmeer.pwncompanion.models.CaptureEntry>()
        existing.forEach { byKey[it.key] = it.copy(timestamp = normalizeTs(it.timestamp)) }
        // Incoming wins on collision, BUT carry forward a cracked password AND the 22000
        // hash the fresh record lacks (a re-scan can omit either), so reconnects don't wipe them.
        // Also normalize the timestamp to seconds — live geotagged captures arrive in ms (the
        // phone's GPS clock) while scanned ones are in seconds, which mangled sort order + age.
        incoming.forEach { inc0 ->
            val inc = inc0.copy(timestamp = normalizeTs(inc0.timestamp))
            val prev = byKey[inc.key]
            byKey[inc.key] = if (prev != null && (inc.password == null && prev.password != null ||
                                                  inc.hash22000 == null && prev.hash22000 != null))
                inc.copy(password = inc.password ?: prev.password,
                         hash22000 = inc.hash22000 ?: prev.hash22000)
            else inc
        }
        return byKey.values.sortedByDescending { it.timestamp ?: 0L }
    }

    /** Coerce a capture timestamp to Unix **seconds**: values that look like milliseconds
     * (> ~year 5138 in seconds) are divided by 1000. Fixes mixed ms/s units from the plugin. */
    private fun normalizeTs(ts: Long?): Long? = when {
        ts == null -> null
        ts > 100_000_000_000L -> ts / 1000
        else -> ts
    }

    /** MAC → comparable form: lowercase hex, separators stripped. */
    private fun normMac(s: String): String =
        s.lowercase().replace(":", "").replace("-", "")

    /**
     * Fire a notification for each NEWLY cracked network. The first batch after a
     * (re)connect is seeded silently (baseline), so we only alert on cracks that land
     * while running — never re-announce the whole history on connect.
     */
    private fun handleCracked(results: List<com.wsvdmeer.pwncompanion.models.CrackedResult>?) {
        val cracked = results?.filter { it.bssid.isNotBlank() && it.password.isNotBlank() } ?: return
        if (!crackedBaselined) {
            cracked.forEach { crackedNotified.add(normMac(it.bssid)) }
            crackedBaselined = true
            return
        }
        cracked.forEach { r ->
            if (crackedNotified.add(normMac(r.bssid))) {   // add() == true → not seen before
                Log.i(tag, "🔓 New crack: ${r.ssid}")
                NotificationHelper.notifyCracked(context, r.ssid, r.password)
            }
        }
    }

    /**
     * Overlay wpa-sec cracked passwords onto captures, matched by BSSID. Returns the
     * same list untouched when nothing changes (avoids needless recompositions).
     */
    private fun applyCracked(
        captures: List<com.wsvdmeer.pwncompanion.models.CaptureEntry>,
        cracked: List<com.wsvdmeer.pwncompanion.models.CrackedResult>?,
    ): List<com.wsvdmeer.pwncompanion.models.CaptureEntry> {
        if (cracked.isNullOrEmpty()) return captures
        val byMac = cracked.filter { it.bssid.isNotBlank() && it.password.isNotBlank() }
            .associateBy { normMac(it.bssid) }
        if (byMac.isEmpty()) return captures
        var changed = false
        val out = captures.map { c ->
            val cr = byMac[normMac(c.bssid)]
            if (cr != null && c.password != cr.password) { changed = true; c.copy(password = cr.password) } else c
        }
        return if (changed) out else captures
    }

    /**
     * Get current device states for UI.
     */
    fun getDeviceStates(): Map<String, DeviceState> = _deviceStates.value

    /**
     * Drop the in-memory capture history from every device state (used by the app's "clear/wipe
     * captures" actions). Without this a later deviceStates emit would re-merge the old captures
     * straight back into the on-disk cache. A still-linked device repopulates on its next scan.
     */
    fun clearCaptures() {
        _deviceStates.update { states ->
            states.mapValues { (_, s) -> s.copy(captures = emptyList()) }
        }
    }

    /** Drop one capture (matched by BSSID, case-insensitive) from every device state. */
    fun removeCapture(bssid: String) {
        val b = bssid.trim().lowercase()
        if (b.isEmpty()) return
        _deviceStates.update { states ->
            states.mapValues { (_, s) ->
                s.copy(captures = s.captures.filterNot { it.bssid.trim().lowercase() == b })
            }
        }
    }

    /**
     * Get message handler for UI subscription to incoming messages.
     */
    fun getMessageHandler(): MessageHandler = messageHandler

    /**
     * Get outgoing message queue for command queuing.
     */
    fun getOutgoingMessageQueue(): OutgoingMessageQueue = outgoingMessageQueue

    /**
     * Check if server is running.
     */
    fun isRunning(): Boolean = _isServerRunning.value

    /**
     * Start GPS foreground service for location tracking.
     * Used when Pwnagotchi connects to provide GPS location updates.
     */
    /**
     * Public hook so the companion service can start GPS while the app is in the FOREGROUND
     * (at launch), where Android 12+ permits starting a `location` foreground service. Starting
     * it lazily on client-connect often happens in the background, where the OS blocks it.
     */
    fun startGpsTracking() = startGpsService()

    private fun startGpsService(force: Boolean = false) {
        if (gpsServiceRunning && !force) {
            Log.d(tag, "GPS service already running")
            return
        }

        // Guard: GpsService requires at least one location permission at runtime.
        // On Android 14+, startForeground(FOREGROUND_SERVICE_TYPE_LOCATION) throws a
        // SecurityException when neither permission is granted, which causes the service to
        // stop without calling startForeground() — triggering ForegroundServiceDidNotStartInTimeException.
        val hasFine = PermissionChecker.checkSelfPermission(
            context, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PermissionChecker.PERMISSION_GRANTED
        val hasCoarse = PermissionChecker.checkSelfPermission(
            context, Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PermissionChecker.PERMISSION_GRANTED
        if (!hasFine && !hasCoarse) {
            Log.w(tag, "Skipping GPS service start — location permission not granted")
            return
        }

        try {
            val gpsIntent = Intent(context, GpsService::class.java)
            ContextCompat.startForegroundService(context, gpsIntent)
            gpsServiceRunning = true
            Log.i(tag, "✓ GPS foreground service started")
        } catch (e: Exception) {
            // On Android 12+ starting a location foreground service while the app is in the
            // BACKGROUND throws ForegroundServiceStartNotAllowedException. Leave the flag
            // false so the gps_request self-heal retries — it succeeds within a BT-broadcast
            // allowlist window or once the app is brought to the foreground.
            Log.e(tag, "Failed to start GPS service (background-start restricted?): ${e.message}")
            gpsServiceRunning = false
        }
    }

    /** Public hook for the companion service's full-stop, so GPS is torn down through the
     *  same path that resets [gpsServiceRunning] (a direct stopService would desync the flag
     *  and block a later restart). */
    fun stopGpsTracking() = stopGpsService()

    /**
     * Stop GPS foreground service.
     * Used on full user stop.
     */
    private fun stopGpsService() {
        if (!gpsServiceRunning) {
            Log.d(tag, "GPS service not running")
            return
        }

        try {
            val gpsIntent = Intent(context, GpsService::class.java)
            context.stopService(gpsIntent)
            gpsServiceRunning = false
            Log.i(tag, "✓ GPS foreground service stopped")
        } catch (e: Exception) {
            Log.e(tag, "Failed to stop GPS service: ${e.message}", e)
        }
    }

    /**
     * Start monitoring bnep0 IP address for changes.
     * Logs IP changes but does NOT overwrite the Pwnagotchi's IP in DeviceState —
     * the Pwnagotchi's IP is captured from the WebSocket connection and is the remote client IP,
     * not the phone's own bnep0 IP.
     */
    private fun startIpMonitoring() {
        ipMonitoringJob = scope.launch {
            Log.i(tag, "🔍 Starting IP address monitoring")
            var previousIp: String? = null

            while (isActive && webSocketServer.getConnectedClientCount() > 0) {
                try {
                    val currentIp = bluetoothMonitor.getBnep0InterfaceIp()

                    if (currentIp != null && currentIp != previousIp) {
                        Log.i(tag, "🔄 Phone bnep0 IP changed: $previousIp → $currentIp (Pwnagotchi IPs unchanged)")
                        previousIp = currentIp
                        // NOTE: We intentionally do NOT update DeviceState.ipAddress here.
                        // DeviceState.ipAddress holds the Pwnagotchi's IP (remote client),
                        // not the phone's local bnep0 IP.
                    }

                    // Check every 5 seconds for IP changes
                    delay(5000)
                } catch (e: Exception) {
                    Log.e(tag, "Error monitoring IP address: ${e.message}")
                    delay(5000)
                }
            }

            Log.i(tag, "IP address monitoring stopped")
        }
    }

    /**
     * Stop IP address monitoring.
     */
    private fun stopIpMonitoring() {
        ipMonitoringJob?.cancel()
        ipMonitoringJob = null
        Log.i(tag, "⏹ IP address monitoring cancelled")
    }

    /**
     * Start processing outgoing message queue.
     * Sends queued messages to connected devices.
     * CRITICAL: Processes GPS location messages with priority and detailed logging.
     */
    private fun startMessageQueueProcessor() {
        messageQueueProcessorJob = scope.launch {
            Log.i(tag, "📤 Starting message queue processor")

            while (isActive && webSocketServer.getConnectedClientCount() > 0) {
                try {
                    val queue = outgoingMessageQueue
                    if (!queue.isEmpty()) {
                        val queuedMsg = queue.dequeue()
                        if (queuedMsg != null) {
                            // Log GPS messages with extra detail for debugging
                            if (queuedMsg.message.type == "gps") {
                                Log.i(tag, "📍 Sending GPS location: lat=${queuedMsg.message.latitude}, lon=${queuedMsg.message.longitude}, acc=${queuedMsg.message.accuracy}m to ${queuedMsg.deviceId}")
                            }
                            
                            val success = webSocketServer.sendToDevice(queuedMsg.deviceId, queuedMsg.message)
                            if (!success && queuedMsg.shouldRetry()) {
                                // Re-queue for retry if send failed
                                queue.requeue(queuedMsg)
                                if (queuedMsg.message.type == "gps") {
                                    Log.w(tag, "GPS message send failed, re-queuing for retry (attempt ${queuedMsg.attemptCount}/${queuedMsg.maxRetries})")
                                } else {
                                    Log.w(tag, "Message send failed, re-queued for retry: ${queuedMsg.message.type}")
                                }
                            } else if (success) {
                                if (queuedMsg.message.type == "gps") {
                                    Log.d(tag, "✓ GPS location successfully sent to ${queuedMsg.deviceId}")
                                } else {
                                    Log.d(tag, "✓ Queued message sent: ${queuedMsg.message.type} to ${queuedMsg.deviceId}")
                                }
                            } else if (!queuedMsg.shouldRetry()) {
                                Log.w(tag, "Message max retries exceeded, discarding: ${queuedMsg.message.type}")
                            }
                        }
                    }
                    delay(100)  // Check queue every 100ms
                } catch (e: Exception) {
                    Log.e(tag, "Error processing message queue: ${e.message}")
                    delay(500)
                }
            }
            Log.i(tag, "Message queue processor stopped")
        }
    }

    /**
     * Stop message queue processor.
     */
    private fun stopMessageQueueProcessor() {
        messageQueueProcessorJob?.cancel()
        messageQueueProcessorJob = null
        Log.i(tag, "⏹ Message queue processor cancelled")
    }
}
