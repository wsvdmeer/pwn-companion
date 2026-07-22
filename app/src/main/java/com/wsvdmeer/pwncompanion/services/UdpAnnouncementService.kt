package com.wsvdmeer.pwncompanion.services

import android.content.Context
import android.net.ConnectivityManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.InternalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import android.util.Log
import kotlin.OptIn
import kotlinx.coroutines.CancellationException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Inet4Address

/**
 * UDP Announcement Service for Pwnagotchi discovery.
 * Broadcasts server endpoint every 5 seconds until WebSocket client connects.
 * Runs on port 8888, targets bnep0 subnet for Bluetooth tether discovery.
 * Uses injected CoroutineScope for lifecycle management (no GlobalScope dependency).
 */
class UdpAnnouncementService(
    private val scope: CoroutineScope,
    private val context: Context? = null
) {
    private val tag = "UdpAnnouncer"
    private var socket: DatagramSocket? = null
    private var announcementJob: Job? = null
    private val broadcastPort = 8888

    @OptIn(InternalSerializationApi::class)
    @Serializable
    data class AnnouncementMessage(
        val type: String,
        val serverIp: String,
        val serverPort: Int,
        val timestamp: Long,
        val message: String
    )

    /**
     * Calculate broadcast address from IP address and subnet mask.
     * For a /24 subnet (255.255.255.0), broadcast is IP with last octet set to 255.
     */
    private fun calculateBroadcastAddress(ipAddress: String): String {
        return try {
            val parts = ipAddress.split(".")
            if (parts.size == 4) {
                // Assume /24 subnet (most common for local networks)
                "${parts[0]}.${parts[1]}.${parts[2]}.255"
            } else {
                "255.255.255.255"  // Fallback to general broadcast
            }
        } catch (e: Exception) {
            Log.e(tag, "Error calculating broadcast address: ${e.message}")
            "255.255.255.255"
        }
    }

    /**
     * Start UDP announcements.
     * Broadcasts to the actual bnep0/bt-pan subnet every 5 seconds.
     * @param serverIp The actual bnep0 interface IP (e.g., "10.83.168.100") - REQUIRED
     * @param serverPort The WebSocket server port (default 8081)
     */
    fun start(serverIp: String, serverPort: Int = 8081) {
        if (announcementJob?.isActive == true) {
            Log.w(tag, "UDP announcements already running")
            return
        }

        // A previously-cancelled job may still be closing its socket on
        // Dispatchers.IO. Close any lingering socket up front; combined with
        // SO_REUSEADDR below this stops a rapid stop()→start() from hitting
        // BindException on the fixed broadcast port.
        try { socket?.close() } catch (_: Exception) {}
        socket = null

        announcementJob = scope.launch(Dispatchers.IO) {
            // Each job owns its socket as a local; the shared `socket` field is
            // only a mirror for stop(). The finally closes the LOCAL socket, so a
            // restart that reassigns the field can never close the new socket.
            var localSocket: DatagramSocket? = null
            try {
                // Calculate the broadcast address from the provided server IP
                // For /24 subnet: 10.83.168.100 -> 10.83.168.255
                val calculatedBroadcast = calculateBroadcastAddress(serverIp)
                Log.i(tag, "📡 Calculated broadcast address: $calculatedBroadcast from server IP: $serverIp")

                // Try to bind to bt-pan interface specifically
                val btPanAddress = getNetworkInterfaceAddress()
                localSocket = if (btPanAddress != null) {
                    try {
                        Log.i(tag, "📍 Binding UDP socket to interface: $btPanAddress")
                        bindReusableSocket(InetAddress.getByName(btPanAddress))
                    } catch (e: Exception) {
                        Log.w(tag, "⚠️ Failed to bind to interface address, using any interface: ${e.message}")
                        bindReusableSocket(null)
                    }
                } else {
                    Log.i(tag, "📍 No specific interface found, binding to any address")
                    bindReusableSocket(null)
                }
                socket = localSocket

                localSocket.broadcast = true
                Log.i(tag, "✓ UDP announcer started on port $broadcastPort")
                Log.i(tag, "  📢 Server: $serverIp:$serverPort")
                Log.i(tag, "  🎯 Broadcast target: $calculatedBroadcast:$broadcastPort")
                Log.i(tag, "  📡 Socket Broadcast Enabled: ${localSocket.broadcast}")
                Log.i(tag, "  📍 Local Address: ${localSocket.localAddress?.hostAddress}:${localSocket.localPort}")
                Log.i(tag, "═══════════════════════════════════════════")

                // Create announcement message
                val announcementMsg = AnnouncementMessage(
                    type = "announcement",
                    serverIp = serverIp,
                    serverPort = serverPort,
                    timestamp = System.currentTimeMillis(),
                    message = "PwnCompanion Server Available"
                )
                val messageJson = Json.encodeToString(announcementMsg)
                Log.i(tag, "📨 Announcement message format:")
                Log.i(tag, "   $messageJson")

                var broadcastCount = 0
                var consecutiveFailures = 0
                while (isActive) {
                    try {
                        // Re-resolve the LIVE bt-pan IP every cycle. After a reconnect
                        // bnep0 can come back with a new IP; without this we'd keep
                        // announcing a stale endpoint and broadcasting to a dead subnet,
                        // and the Pwnagotchi could never discover us until an app restart.
                        val liveIp = getNetworkInterfaceAddress() ?: serverIp
                        val currentBroadcast = calculateBroadcastAddress(liveIp)
                        val liveMessage = Json.encodeToString(
                            AnnouncementMessage(
                                type = "announcement",
                                serverIp = liveIp,
                                serverPort = serverPort,
                                timestamp = System.currentTimeMillis(),
                                message = "PwnCompanion Server Available"
                            )
                        )
                        try {
                            val packet = liveMessage.toByteArray()
                            val packetData = DatagramPacket(
                                packet, packet.size,
                                InetAddress.getByName(currentBroadcast), broadcastPort
                            )
                            localSocket?.send(packetData)
                            broadcastCount++
                            consecutiveFailures = 0  // Reset failure counter on success
                            Log.d(tag, "✅ UDP broadcast #$broadcastCount → $currentBroadcast:$broadcastPort (serverIp=$liveIp)")
                        } catch (e: Exception) {
                            consecutiveFailures++
                            // ENETUNREACH is normal during network transitions.
                            if (consecutiveFailures > 3) {
                                Log.w(tag, "⚠️ UDP broadcast failed (attempt $consecutiveFailures): ${e.message} — bnep0 likely re-IPing")
                            }

                            // SELF-HEAL — do NOT permanently stop. The socket may be bound
                            // to an interface IP that has since changed; periodically rebind
                            // to the current interface and keep announcing. The
                            // BluetoothTetherMonitor stops this service when BT is truly gone,
                            // so we don't need a "give up forever" path here (that was the bug
                            // that left the Pwnagotchi unable to reconnect without a re-plug).
                            if (consecutiveFailures % 6 == 0) {
                                Log.w(tag, "♻️ Rebinding announcer socket after $consecutiveFailures consecutive failures")
                                try { localSocket?.close() } catch (_: Exception) {}
                                localSocket = try {
                                    val addr = getNetworkInterfaceAddress()
                                    bindReusableSocket(addr?.let { InetAddress.getByName(it) })
                                } catch (e2: Exception) {
                                    Log.w(tag, "Rebind to interface failed, using any: ${e2.message}")
                                    try { bindReusableSocket(null) } catch (_: Exception) { null }
                                }
                                localSocket?.broadcast = true
                                socket = localSocket
                            }
                        }

                        delay(5000) // Wait 5 seconds between announcements
                    } catch (e: CancellationException) {
                        // Job was cancelled — exit the loop cleanly without logging as an error
                        throw e
                    } catch (e: Exception) {
                        Log.e(tag, "Unexpected error in announcement loop: ${e.message}")
                        delay(1000)
                    }
                }
            } catch (e: Exception) {
                Log.e(tag, "UDP announcement error: ${e.message}", e)
            } finally {
                try { localSocket?.close() } catch (_: Exception) {}
                // Only clear the shared mirror if it still points at our socket.
                if (socket === localSocket) socket = null
                Log.i(tag, "✗ UDP announcements stopped")
            }
        }
    }

    /**
     * Create a UDP socket with SO_REUSEADDR set *before* binding to the fixed
     * broadcast port, so a fresh start() doesn't fail with BindException while a
     * just-cancelled socket lingers in TIME_WAIT. DatagramSocket(port, addr) binds
     * in its constructor, so we create it unbound and bind explicitly.
     */
    private fun bindReusableSocket(address: InetAddress?): DatagramSocket {
        val s = DatagramSocket(null)
        s.reuseAddress = true
        s.bind(
            if (address != null) InetSocketAddress(address, broadcastPort)
            else InetSocketAddress(broadcastPort)
        )
        return s
    }

    /**
     * Get the IP address of the bt-pan/bnep interface for binding.
     */
    private fun getNetworkInterfaceAddress(): String? {
        return try {
            val context = this.context ?: return null

            @Suppress("DEPRECATION")
            val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
                ?: return null

            @Suppress("DEPRECATION")
            val allNetworks = connectivityManager.allNetworks
            for (network in allNetworks) {
                try {
                    val linkProperties = connectivityManager.getLinkProperties(network) ?: continue
                    val interfaceName = linkProperties.interfaceName ?: continue

                    // Check for bt-pan or bnep interface
                    if (interfaceName == "bt-pan" || interfaceName.startsWith("bnep")) {
                        for (linkAddress in linkProperties.linkAddresses) {
                            val address = linkAddress.address
                            if (address is Inet4Address && !address.isLoopbackAddress) {
                                val ipAddress = address.hostAddress ?: continue
                                Log.i(tag, "Found bt-pan interface IP for binding: $ipAddress")
                                return ipAddress
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.e(tag, "Error reading network: ${e.message}")
                }
            }
            null
        } catch (e: Exception) {
            Log.e(tag, "Error getting network interface address: ${e.message}")
            null
        }
    }

    /**
     * Stop UDP announcements.
     */
    suspend fun stop() {
        announcementJob?.cancel()
        announcementJob = null
        withContext(Dispatchers.IO) {
            socket?.close()
        }
        Log.i(tag, "UDP announcements stopped")
    }

    /**
     * Check if announcements are running.
     */
    @Suppress("UNUSED")
    fun isRunning(): Boolean = announcementJob?.isActive == true
}
