package com.wsvdmeer.pwncompanion.services

import android.Manifest
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.util.Log
import androidx.core.content.PermissionChecker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Bluetooth Tether Monitor.
 * Detects appearance/disappearance of bnep0 interface (Bluetooth tether).
 * Triggers network service start/stop based on interface availability.
 */
class BluetoothTetherMonitor(
    context: Context,
    private val onBluetoothStateChanged: suspend (detected: Boolean) -> Unit
) {
    private val tag = "BtTetherMonitor"
    private val appContext = context
    private val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private val scope = CoroutineScope(Dispatchers.Main)
    private var bnep0Detected = false

    /**
     * Whether register() has already been called.
     * Making register() idempotent prevents a leaked NetworkCallback when both
     * MainActivity.initializeBluetoothMonitor() and CompanionBackgroundService.initialize()
     * call register() on the same singleton BluetoothTetherMonitor.  Without this guard the
     * first NetworkCallback is silently overwritten (the reference is lost) but it stays
     * registered with ConnectivityManager, causing all future BT events to fire twice.
     */
    private var isRegistered = false

    /**
     * The Network object for the active bt-pan/bnep interface, cached in onAvailable().
     *
     * On modern Android (12+), getLinkProperties(network) returns null inside onLost() for the
     * lost network — the OS has already torn down the link properties before firing the callback.
     * Without caching we can never match the interface name and onBluetoothStateChanged(false) is
     * never called, leaving the UDP announcer running forever after BT disconnects.
     */
    private var btPanNetwork: Network? = null

    // Debounce mechanism to prevent rapid event triggers
    private var debounceTimer: kotlinx.coroutines.Job? = null
    private val debounceMs = 500L  // Wait 500ms for network to stabilize

    // Periodic poll — the ConnectivityManager TRANSPORT_BLUETOOTH callback often does
    // NOT fire for the phone's NAP (tethering) side of bt-pan, so a bt-pan that appears
    // AFTER the app started (app launched before the Pwnagotchi connected) is never
    // detected by the callback. This poll checks the real OS interface and fires the
    // state change ourselves, so detection no longer depends on the flaky callback.
    private var pollJob: Job? = null
    private val pollIntervalMs = 3000L

    /**
     * Check if required Bluetooth permissions are granted.
     */
    @androidx.annotation.RequiresApi(31)
    private fun hasBluetoothPermissionsApi31(): Boolean {
        val bluetoothConnect = PermissionChecker.checkSelfPermission(
            appContext,
            Manifest.permission.BLUETOOTH_CONNECT
        ) == PermissionChecker.PERMISSION_GRANTED

        val bluetoothScan = PermissionChecker.checkSelfPermission(
            appContext,
            Manifest.permission.BLUETOOTH_SCAN
        ) == PermissionChecker.PERMISSION_GRANTED

        return bluetoothConnect || bluetoothScan
    }

    private fun hasBluetoothPermissions(): Boolean {
        // On API < 31 there are no BLUETOOTH_CONNECT / BLUETOOTH_SCAN permissions; always allowed.
        if (android.os.Build.VERSION.SDK_INT < 31) return true
        return hasBluetoothPermissionsApi31()
    }

    /**
     * Register Bluetooth tether interface monitoring.
     * Safe to call multiple times — will no-op if already registered.
     */
    fun register() {
        if (isRegistered) {
            Log.d(tag, "Already registered — skipping duplicate registration")
            return
        }
        Log.i(tag, "Registering Bluetooth tether monitor...")
        
        // Check Bluetooth permissions
        if (!hasBluetoothPermissions()) {
            Log.e(tag, "❌ CRITICAL: Bluetooth permissions not granted!")
            Log.e(tag, "   Required: BLUETOOTH_CONNECT or BLUETOOTH_SCAN")
            Log.e(tag, "   bnep0 detection WILL NOT WORK without these permissions")
            Log.e(tag, "   App should request these permissions in MainActivity BEFORE initializing this monitor")
            return  // ✅ CRITICAL FIX: Don't continue without permissions
        }

        networkCallback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                checkBnep0Interface(network, available = true)
            }

            override fun onLost(network: Network) {
                // On Android 12+, getLinkProperties(network) returns null inside onLost() because
                // the OS tears down link state before firing the callback. checkBnep0Interface()
                // therefore returns early and onBluetoothStateChanged(false) is never called,
                // leaving the UDP announcer running indefinitely.
                //
                // Fix: compare the lost network object to the one we cached in onAvailable().
                if (network == btPanNetwork) {
                    Log.i(tag, "✗ bt-pan/bnep0 network lost (matched cached network)")
                    btPanNetwork = null
                    val wasDetected = bnep0Detected
                    bnep0Detected = false
                    if (wasDetected) {
                        scope.launch { onBluetoothStateChanged(false) }
                    }
                } else {
                    // Fallback for any other network — still attempt the property lookup
                    checkBnep0Interface(network, available = false)
                }
            }

            override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
                // Debounce to prevent rapid retriggering
                debounceTimer?.cancel()
                debounceTimer = scope.launch {
                    delay(debounceMs)
                    checkBnep0Interface(network, available = true)
                }
            }
        }

        // Listen for Bluetooth transport networks.
        // DO NOT use NET_CAPABILITY_INTERNET — bnep0/bt-pan does not have internet capability
        // and would never be delivered to the callback if we filter for it.
        val request = android.net.NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_BLUETOOTH)
            .build()

        connectivityManager.registerNetworkCallback(request, networkCallback!!)
        isRegistered = true
        Log.i(tag, "✓ Network monitoring registered")

        // Check current state - including already-connected bnep devices
        checkCurrentNetworks()

        // Explicitly check for already-connected bnep devices
        detectAlreadyConnectedBnepDevices()

        // Start the polling fallback so a LATE bt-pan (app started before the
        // Pwnagotchi) is still detected even when the callback never fires.
        startPolling()
    }

    /**
     * Poll the real OS interface every few seconds and fire a state change on any
     * appear/disappear transition. This is the reliable path: it does not depend on
     * the ConnectivityManager callback, which misses the NAP-side bt-pan on many phones.
     */
    private fun startPolling() {
        pollJob?.cancel()
        pollJob = scope.launch {
            while (true) {
                delay(pollIntervalMs)
                try {
                    val present = btPanPresent()
                    if (present != bnep0Detected) {
                        Log.i(tag, "🔁 Poll detected bt-pan transition → present=$present (callback missed it)")
                        bnep0Detected = present
                        onBluetoothStateChanged(present)
                    }
                } catch (e: Exception) {
                    Log.e(tag, "Poll error: ${e.message}")
                }
            }
        }
    }

    /**
     * Quiet, reliable check for an up bt-pan/bnep interface with an IPv4 address,
     * via java.net (no logging, no ConnectivityManager capability filtering).
     */
    private fun btPanPresent(): Boolean {
        return try {
            java.net.NetworkInterface.getNetworkInterfaces()?.toList()?.any { iface ->
                val n = iface.name ?: return@any false
                (n.startsWith("bnep") || n.startsWith("bt-pan")) &&
                    iface.isUp &&
                    iface.inetAddresses.toList().any {
                        it is java.net.Inet4Address && !it.isLoopbackAddress
                    }
            } ?: false
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Re-initialize Bluetooth tether monitoring.
     * Call this after permissions are granted to enable full bnep/bt-pan detection.
     */
    @Suppress("unused")
    fun reinitialize() {
        Log.i(tag, "Reinitializing Bluetooth tether monitor (permissions granted)...")
        // Unregister first to avoid duplicate callbacks
        unregister()
        register()
    }

    /**
     * Unregister Bluetooth tether interface monitoring.
     */
    fun unregister() {
        pollJob?.cancel()
        pollJob = null
        if (networkCallback != null) {
            connectivityManager.unregisterNetworkCallback(networkCallback!!)
            networkCallback = null
            isRegistered = false
            Log.i(tag, "Network monitoring unregistered")
        }
    }

    /**
     * Direct check for bt-pan interface availability.
     * Called immediately after permissions are granted to detect active connections.
     */
    @Suppress("DEPRECATION")
    fun directCheckForBtPan() {
        Log.i(tag, "🔍 Direct check for bt-pan interface (permissions now available)...")
        try {
            val allNetworks = connectivityManager.allNetworks
            for (network in allNetworks) {
                try {
                    val linkProperties = connectivityManager.getLinkProperties(network)
                    val interfaceName = linkProperties?.interfaceName ?: continue
                    
                    if (interfaceName == "bt-pan" || interfaceName.startsWith("bnep")) {
                        Log.i(tag, "✅ FOUND: $interfaceName is ACTIVE and CONNECTED")
                        Log.i(tag, "   Triggering immediate service startup...")
                        
                        val addresses = linkProperties.linkAddresses.joinToString(", ") { it.address?.hostAddress ?: "N/A" }
                        Log.i(tag, "   IP: $addresses")
                        Log.i(tag, "   Starting networking service NOW...")
                        
                        bnep0Detected = true
                        scope.launch {
                            onBluetoothStateChanged(true)
                        }
                        return
                    }
                } catch (e: Exception) {
                    Log.e(tag, "Error checking network: ${e.message}")
                }
            }
            Log.i(tag, "   No active bt-pan/bnep found currently")
        } catch (e: Exception) {
            Log.e(tag, "Error in direct check: ${e.message}")
        }
    }

    /**
     * Check if specific network is bnep0 (Bluetooth tether).
     * Also re-triggers the callback if the interface was already detected but the service
     * never started (e.g. DHCP completed after first detection).
     */
    private fun checkBnep0Interface(network: Network, available: Boolean) {
        try {
            val linkProperties = connectivityManager.getLinkProperties(network)
            val interfaceName = linkProperties?.interfaceName ?: return

            // Support both bnep* (traditional) and bt-pan (newer) interface names
            if (interfaceName.startsWith("bnep") || interfaceName.startsWith("bt-pan")) {
                val wasDetected = bnep0Detected
                bnep0Detected = available

                if (available && !wasDetected) {
                    // Interface just appeared — cache the Network object so onLost() can match it
                    btPanNetwork = network
                    val addresses = linkProperties.linkAddresses.joinToString(", ") { it.address.hostAddress ?: "N/A" }
                    Log.i(tag, "✓ bnep0 interface UP: $interfaceName")
                    Log.i(tag, "  IP Addresses: $addresses")
                    Log.i(tag, "  Gateway: ${linkProperties.routes.firstOrNull()?.gateway?.hostAddress ?: "N/A"}")
                    Log.i(tag, "  DNS: ${linkProperties.dnsServers.joinToString(", ") { it.hostAddress ?: "N/A" }}")
                    scope.launch { onBluetoothStateChanged(true) }

                } else if (available && wasDetected) {
                    // Interface already known — capabilities changed (e.g. DHCP just assigned an IP).
                    // Re-trigger so the service can start if it failed the first time due to missing IP.
                    val hasIp = linkProperties.linkAddresses.any {
                        it.address is java.net.Inet4Address && !it.address.isLoopbackAddress
                    }
                    if (hasIp) {
                        Log.d(tag, "ℹ️ bnep0 capabilities changed (IP now available) — re-triggering startup check")
                        scope.launch { onBluetoothStateChanged(true) }
                    }

                } else if (!available && wasDetected) {
                    Log.i(tag, "✗ bnep0 interface DOWN: $interfaceName")
                    btPanNetwork = null
                    scope.launch { onBluetoothStateChanged(false) }
                }
            }
        } catch (e: Exception) {
            Log.e(tag, "Error checking interface: ${e.message}")
        }
    }

    /**
     * Check all current networks for bnep0.
     */
    private fun checkCurrentNetworks() {
        try {
            val activeNetwork = connectivityManager.activeNetwork
            if (activeNetwork != null) {
                checkBnep0Interface(activeNetwork, available = true)
            }
            
            // Log all available networks (including bnep devices)
            logAllBnepDevices()
        } catch (e: Exception) {
            Log.e(tag, "Error checking current networks: ${e.message}")
        }
    }

    /**
     * Detect already-connected bnep devices on app startup.
     * This ensures we start the service if bnep0 is already connected when the app launches.
     */
    @Suppress("DEPRECATION")
    private fun detectAlreadyConnectedBnepDevices() {
        try {
            Log.i(tag, "🔍 Checking for already-connected bnep devices...")
            
            val allNetworks = connectivityManager.allNetworks
            Log.i(tag, "   Total networks found: ${allNetworks.size}")
            
            if (allNetworks.isEmpty()) {
                Log.w(tag, "   ⚠️ No networks detected! This may indicate permission issues.")
                Log.w(tag, "   Ensure BLUETOOTH_CONNECT or BLUETOOTH_SCAN permissions are granted at runtime.")
                Log.i(tag, "   No pre-connected bnep devices found")
                return
            }
            
            var foundBnep = false

            for ((index, network) in allNetworks.withIndex()) {
                try {
                    val linkProperties = connectivityManager.getLinkProperties(network)
                    val interfaceName = linkProperties?.interfaceName
                    val caps = connectivityManager.getNetworkCapabilities(network)
                    
                    Log.d(tag, "   Network $index: $interfaceName (Caps: ${caps?.toString() ?: "N/A"})")
                    
                    if (interfaceName == null) continue
                    
                    // Support both bnep* (traditional) and bt-pan (newer) interface names
                    if ((interfaceName.startsWith("bnep") || interfaceName.startsWith("bt-pan")) && !bnep0Detected) {
                        Log.i(tag, "✓ Found already-connected bnep device: $interfaceName")
                        Log.i(tag, "  → Triggering service startup...")
                        
                        foundBnep = true
                        bnep0Detected = true
                        
                        // Trigger the callback to start the service
                        scope.launch {
                            onBluetoothStateChanged(true)
                        }
                        break  // Only process first bnep device
                    }
                } catch (e: Exception) {
                    Log.e(tag, "Error checking pre-connected network $index: ${e.message}")
                }
            }
            
            if (!foundBnep) {
                Log.i(tag, "   No pre-connected bnep devices found")
            }
        } catch (e: Exception) {
            Log.e(tag, "❌ Error detecting pre-connected bnep devices: ${e.message}", e)
        }
    }

    /**
     * Log all bnep devices currently available on the system.
     */
    @Suppress("DEPRECATION")
    private fun logAllBnepDevices() {
        try {
            Log.i(tag, "═══════════════════════════════════════════")
            Log.i(tag, "📱 BLUETOOTH TETHER DEVICES (bnep)")
            Log.i(tag, "═══════════════════════════════════════════")
            
            val allNetworks = connectivityManager.allNetworks
            var bnepCount = 0
            
            for (network in allNetworks) {
                try {
                    val linkProperties = connectivityManager.getLinkProperties(network)
                    val interfaceName = linkProperties?.interfaceName ?: continue
                    
                    // Support both bnep* (traditional) and bt-pan (newer) interface names
                    if (interfaceName.startsWith("bnep") || interfaceName.startsWith("bt-pan")) {
                        bnepCount++
                        val addresses = linkProperties.linkAddresses.joinToString(", ") { it.address?.hostAddress ?: "N/A" }
                        val gateway = linkProperties.routes.firstOrNull()?.gateway?.hostAddress ?: "N/A"
                        val dns = linkProperties.dnsServers.joinToString(", ") { it.hostAddress ?: "N/A" }

                        Log.i(tag, "Device $bnepCount: $interfaceName")
                        Log.i(tag, "  IP Address: $addresses")
                        Log.i(tag, "  Gateway: $gateway")
                        Log.i(tag, "  DNS: $dns")
                        
                        // Check capabilities
                        val caps = connectivityManager.getNetworkCapabilities(network)
                        if (caps != null) {
                            val capsList = mutableListOf<String>()
                            if (caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) capsList.add("Internet")
                            if (caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) capsList.add("Validated")
                            if (caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)) capsList.add("Unmetered")
                            Log.i(tag, "  Capabilities: ${capsList.joinToString(", ")}")
                        }
                    }
                } catch (e: Exception) {
                    Log.e(tag, "Error reading network properties: ${e.message}")
                }
            }
            
            if (bnepCount == 0) {
                Log.i(tag, "No bnep devices detected")
            } else {
                Log.i(tag, "Total bnep devices: $bnepCount")
            }
            Log.i(tag, "═══════════════════════════════════════════")
        } catch (e: Exception) {
            Log.e(tag, "Error logging bnep devices: ${e.message}")
        }
    }

    /**
     * Check if bnep0 is currently available.
     */
    @Suppress("UNUSED")
    fun isBnep0Available(): Boolean {
        try {
            val activeNetwork = connectivityManager.activeNetwork
            if (activeNetwork != null) {
                val linkProperties = connectivityManager.getLinkProperties(activeNetwork)
                return linkProperties?.interfaceName?.startsWith("bnep") == true
            }
        } catch (e: Exception) {
            Log.e(tag, "Error checking bnep0: ${e.message}")
        }
        return false
    }

    /**
     * Get the IPv4 address of the active bnep0/bt-pan interface.
     * Returns null if no Bluetooth tether interface is available.
     * Tries ConnectivityManager first; falls back to java.net.NetworkInterface
     * which is reliable on all Android versions and needs no special permissions.
     */
    @Suppress("DEPRECATION")
    fun getBnep0InterfaceIp(): String? {
        // PRIMARY: ConnectivityManager approach
        val cmIp = getBnep0IpFromConnectivityManager()
        if (cmIp != null) return cmIp

        // FALLBACK: java.net.NetworkInterface — works on all Android versions,
        // no ConnectivityManager capability filtering issues.
        Log.w(tag, "⚠️ ConnectivityManager found no bnep0 IP, trying NetworkInterface fallback...")
        return getBnep0IpFromNetworkInterfaces()
    }

    @Suppress("DEPRECATION")
    private fun getBnep0IpFromConnectivityManager(): String? {
        return try {
            val allNetworks = connectivityManager.allNetworks
            Log.d(tag, "🔍 Scanning ${allNetworks.size} networks for bnep0 interface...")

            for (network in allNetworks) {
                try {
                    val linkProperties = connectivityManager.getLinkProperties(network)
                    val interfaceName = linkProperties?.interfaceName ?: continue

                    Log.d(tag, "   Checking interface: $interfaceName")

                    if (interfaceName.startsWith("bnep") || interfaceName.startsWith("bt-pan")) {
                        Log.i(tag, "   ✓ Found Bluetooth interface: $interfaceName")

                        val addresses = mutableListOf<String>()
                        for (linkAddress in linkProperties.linkAddresses) {
                            val address = linkAddress.address
                            Log.d(tag, "     - Address: ${address.hostAddress} (${address.javaClass.simpleName})")
                            if (address is java.net.Inet4Address && !address.isLoopbackAddress) {
                                addresses.add(address.hostAddress ?: continue)
                            }
                        }

                        if (addresses.isEmpty()) {
                            Log.w(tag, "   ⚠️ No IPv4 addresses found on $interfaceName")
                            continue
                        }

                        val ip = addresses.first()
                        Log.i(tag, "   ✅ Using bnep0 interface IP: $ip (via ConnectivityManager)")
                        return ip
                    }
                } catch (e: Exception) {
                    Log.e(tag, "Error reading network properties: ${e.message}")
                }
            }
            null
        } catch (e: Exception) {
            Log.e(tag, "❌ Error getting bnep0 IP via ConnectivityManager: ${e.message}", e)
            null
        }
    }

    /**
     * Fallback: enumerate OS-level network interfaces via java.net.
     * This bypasses ConnectivityManager entirely and is always reliable.
     */
    private fun getBnep0IpFromNetworkInterfaces(): String? {
        return try {
            val interfaces = java.net.NetworkInterface.getNetworkInterfaces()?.toList() ?: return null
            for (iface in interfaces) {
                val name = iface.name ?: continue
                if (!name.startsWith("bnep") && !name.startsWith("bt-pan")) continue
                if (!iface.isUp) continue

                Log.i(tag, "   NetworkInterface fallback — found: $name")
                for (addr in iface.inetAddresses) {
                    if (addr is java.net.Inet4Address && !addr.isLoopbackAddress) {
                        val ip = addr.hostAddress ?: continue
                        Log.i(tag, "   ✅ Using bnep0 interface IP: $ip (via NetworkInterface fallback)")
                        return ip
                    }
                }
            }
            Log.w(tag, "❌ No bnep0/bt-pan interface IP found via any method")
            null
        } catch (e: Exception) {
            Log.e(tag, "❌ Error in NetworkInterface fallback: ${e.message}", e)
            null
        }
    }
}
