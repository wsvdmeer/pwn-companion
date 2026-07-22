package com.wsvdmeer.pwncompanion.services

import android.util.Log
import com.wsvdmeer.pwncompanion.models.ScreenData
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import io.ktor.server.routing.routing
import io.ktor.server.websocket.*
import io.ktor.websocket.Frame
import io.ktor.websocket.WebSocketSession
import io.ktor.websocket.close
import io.ktor.websocket.readText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * WebSocket Server Service using Ktor.
 * Listens for Pwnagotchi device connections on 127.0.0.1:8081.
 * Manages up to 20 concurrent device connections.
 * Handles message types: image, gps_request, status.
 */
class WebSocketServerService(
    private val onClientConnected: suspend (deviceId: String, deviceName: String, clientIp: String) -> Unit = { _, _, _ -> },
    private val onClientDisconnected: suspend (deviceId: String) -> Unit = { _ -> },
    private val onDataReceived: suspend (deviceId: String, data: ScreenData) -> Unit = { _, _ -> }
) {
    private val tag = "WebSocketServer"
    private val serverPort = 8081
    private var serverHost = "0.0.0.0"  // Default: listen on all interfaces
    private var server: io.ktor.server.engine.ApplicationEngine? = null
    // True only once a real TCP connect to the port succeeds — proves the async
    // CIO bind actually took. Without this we'd report "running" on a dead port.
    @Volatile private var bound = false
    private val connectedClients = ConcurrentHashMap<String, ClientConnection>()
    private val clientSessions = ConcurrentHashMap<String, WebSocketSession>()  // Track sessions for sending messages
    
    // JSON parser configured to ignore unknown keys from Pwnagotchi
    private val jsonParser = Json {
        ignoreUnknownKeys = true
    }

    data class ClientConnection(
        val sessionId: String,
        val deviceName: String,
        val ipAddress: String
    )

    /**
     * Start WebSocket server.
     * Listens on all interfaces (0.0.0.0) so it's reachable via bnep0 IP.
     * @param interfaceIp The bnep0 interface IP to announce in logs (e.g., "10.83.168.100")
     *                     Server still listens on 0.0.0.0 for compatibility
     */
    suspend fun start(interfaceIp: String? = null): Boolean {
        if (isRunning()) {
            Log.w(tag, "WebSocket server already running on ws://0.0.0.0:$serverPort")
            return true
        }

        // Always listen on all interfaces so it's reachable via the bnep0 IP.
        val announcedIp = interfaceIp ?: "0.0.0.0"
        serverHost = "0.0.0.0"

        val maxAttempts = 3
        var attempts = 0
        while (attempts < maxAttempts) {
            attempts++
            try {
                Log.i(tag, "Starting WebSocket server (attempt $attempts/$maxAttempts) on ws://0.0.0.0:$serverPort (announce $announcedIp)")

                server = embeddedServer(CIO, port = serverPort, host = "0.0.0.0") {
                    install(WebSockets)
                    configureRouting(::handleWebSocketSession)
                }.start(wait = false)

                // CIO binds ASYNCHRONOUSLY — .start() returning is NOT proof the
                // port is listening. A stale socket from a prior instance (after
                // force-stop/reinstall) throws BindException inside the engine, not
                // here. Probe a real TCP connect so we never report "running" / let
                // the UDP announcer advertise a dead port.
                if (probeListening()) {
                    bound = true
                    Log.i(tag, "✅ WebSocket server bound + listening on 0.0.0.0:$serverPort (Pwnagotchi → ws://$announcedIp:$serverPort)")
                    return true
                }
                throw Exception("port $serverPort not accepting connections after start — bind likely failed (stale socket / TIME_WAIT)")

            } catch (e: Exception) {
                Log.e(tag, "⚠️ WebSocket start attempt $attempts failed: ${e.javaClass.simpleName}: ${e.message}")
                runCatching { server?.stop(0, 0) }
                server = null
                bound = false
                if (attempts < maxAttempts) delay(1000L * attempts)  // 1s,2s — clears TIME_WAIT
            }
        }
        Log.e(tag, "❌ Failed to start WebSocket server after $maxAttempts attempts")
        return false
    }

    /**
     * Confirm the listening socket is actually accepting by doing a real loopback
     * TCP connect. Retries briefly because the async bind may lag .start().
     */
    private suspend fun probeListening(): Boolean = withContext(Dispatchers.IO) {
        repeat(10) {
            try {
                java.net.Socket().use { s ->
                    s.connect(java.net.InetSocketAddress("127.0.0.1", serverPort), 300)
                }
                return@withContext true
            } catch (_: Exception) {
                delay(100)
            }
        }
        false
    }

    /**
     * Configure WebSocket routing.
     */
    private fun Application.configureRouting(handler: suspend (WebSocketServerSession) -> Unit) {
        routing {
            webSocket("/") {
                handler(this)
            }
        }
    }

    /**
     * Handle incoming WebSocket connection.
     *
     * IMPORTANT: register the client and call onClientConnected() IMMEDIATELY after the WebSocket
     * upgrade, before waiting for any data.  The original design received one message first, which
     * caused onClientConnected() to never fire when the Pwnagotchi plugin connects but sits quietly
     * waiting for the server to speak first — leaving the session in a half-open limbo indefinitely.
     */
    private suspend fun handleWebSocketSession(session: WebSocketServerSession) {
        val sessionId = UUID.randomUUID().toString()
        var deviceName = "Device_$sessionId"

        // Check max concurrent connections before accepting
        if (connectedClients.size >= 20) {
            Log.w(tag, "Max connections (20) reached, rejecting client")
            session.close()
            return
        }

        // Extract the actual client (Pwnagotchi) IP address from socket-level connection
        val clientIpAddress = try {
            val remoteHost = try {
                session.call.request.headers["X-Forwarded-For"]?.split(",")?.firstOrNull()?.trim()
            } catch (_: Exception) { null }

            val finalAddress = remoteHost ?: try {
                session.call.request.local.remoteHost
            } catch (_: Exception) { null }

            if (!finalAddress.isNullOrBlank() && finalAddress != "0.0.0.0") {
                Log.d(tag, "Extracted client IP: $finalAddress")
                finalAddress
            } else {
                Log.d(tag, "Could not extract valid client IP from remoteHost or X-Forwarded-For")
                null
            }
        } catch (e: Exception) {
            Log.w(tag, "Error extracting client IP: ${e.message}")
            null
        }

        val finalClientIp = clientIpAddress ?: "unknown"

        // Register client and fire onClientConnected immediately — do NOT wait for first message.
        connectedClients[sessionId] = ClientConnection(sessionId, deviceName, finalClientIp)
        clientSessions[sessionId] = session
        onClientConnected(sessionId, deviceName, finalClientIp)

        Log.i(tag, "═══════════════════════════════════════════")
        Log.i(tag, "✓ CLIENT CONNECTED")
        Log.i(tag, "  Device: $deviceName")
        Log.i(tag, "  Client IP: $finalClientIp")
        Log.i(tag, "  Session: $sessionId")
        Log.i(tag, "  Total Clients: ${connectedClients.size}/20")
        Log.i(tag, "═══════════════════════════════════════════")

        try {
            // Listen for messages — update device name on the first parseable message
            while (session.isActive) {
                try {
                    val message = session.incoming.receive()
                    if (message is Frame.Text) {
                        try {
                            val text = message.readText()
                            val screenData = jsonParser.decodeFromString<ScreenData>(text)

                            // Lazily update device name once we know the real name
                            val resolvedName = screenData.deviceName
                            if (resolvedName != null && deviceName.startsWith("Device_")) {
                                deviceName = resolvedName
                                connectedClients[sessionId] = connectedClients[sessionId]!!.copy(deviceName = deviceName)
                                Log.d(tag, "Device name resolved: $deviceName")
                            }

                            onDataReceived(sessionId, screenData)
                        } catch (e: Exception) {
                            Log.e(tag, "Error parsing message from $deviceName: ${e.message}")
                        }
                    }
                } catch (e: Exception) {
                    Log.e(tag, "Error receiving message from $deviceName: ${e.message}")
                    break
                }
            }
        } finally {
            connectedClients.remove(sessionId)
            clientSessions.remove(sessionId)
            onClientDisconnected(sessionId)
            Log.i(tag, "═══════════════════════════════════════════")
            Log.i(tag, "✗ CLIENT DISCONNECTED")
            Log.i(tag, "  Device: $deviceName")
            Log.i(tag, "  Session: $sessionId")
            Log.i(tag, "  Remaining Clients: ${connectedClients.size}/20")
            Log.i(tag, "═══════════════════════════════════════════")
        }
    }

    /**
     * Stop WebSocket server.
     */
    suspend fun stop() {
        withContext(Dispatchers.IO) {
            connectedClients.clear()
            clientSessions.clear()

            server?.stop(gracePeriodMillis = 1000, timeoutMillis = 2000)
            server = null
            bound = false

            Log.i(tag, "WebSocket server stopped")
        }
    }

    /**
     * Check if server is running AND the port is confirmed bound (not just that an
     * engine object exists — see [bound]).
     */
    fun isRunning(): Boolean = server != null && bound

    /**
     * Get connected client count.
     */
    fun getConnectedClientCount(): Int = connectedClients.size

    /**
     * Get list of connected devices.
     */
    @Suppress("UNUSED")
    fun getConnectedDevices(): List<String> = connectedClients.values.map { it.deviceName }

    /**
     * Send a message to a specific connected device.
     * Used for sending GPS responses, commands, etc.
     */
    suspend fun sendToDevice(deviceId: String, message: ScreenData): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                val session = clientSessions[deviceId]
                if (session == null || !session.isActive) {
                    Log.w(tag, "Session not found or inactive for device $deviceId")
                    return@withContext false
                }

                val jsonMessage = Json.encodeToString(message)
                session.send(Frame.Text(jsonMessage))
                Log.d(tag, "Message sent to device $deviceId: type=${message.type}")
                true
            } catch (e: Exception) {
                Log.e(tag, "Error sending message to device $deviceId: ${e.message}", e)
                // Remove inactive session
                clientSessions.remove(deviceId)
                false
            }
        }
    }

    /**
     * Broadcast a message to all connected devices.
     */
    @Suppress("unused")
    suspend fun broadcastToAllDevices(message: ScreenData): Int {
        return withContext(Dispatchers.IO) {
            var successCount = 0
            for ((deviceId, _) in connectedClients) {
                if (sendToDevice(deviceId, message)) {
                    successCount++
                }
            }
            Log.i(tag, "Broadcast sent to $successCount/${connectedClients.size} devices")
            successCount
        }
    }
}
