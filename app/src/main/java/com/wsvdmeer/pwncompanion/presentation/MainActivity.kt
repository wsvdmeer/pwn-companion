package com.wsvdmeer.pwncompanion.presentation

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.wsvdmeer.pwncompanion.protocol.MessageHandler
import com.wsvdmeer.pwncompanion.protocol.OutgoingMessageQueue
import com.wsvdmeer.pwncompanion.presentation.ui.MainScreen
import com.wsvdmeer.pwncompanion.presentation.theme.PwnCompanionTheme
import com.wsvdmeer.pwncompanion.services.CompanionBackgroundService
import com.wsvdmeer.pwncompanion.services.NetworkService
import com.wsvdmeer.pwncompanion.services.NetworkServiceSingleton
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import androidx.compose.foundation.layout.fillMaxSize

/**
 * Main Activity - Primary entry point for PwnCompanion app.
 * Manages activity lifecycle, service integration, and ViewModel coordination.
 * Uses Jetpack Compose for Material Design 3 UI (replaces XML layout).
 *
 * Lifecycle:
 * - onCreate: Initialize ViewModel, start background service, set Compose content
 * - onStart: Service already started, UI becomes visible
 * - onStop: Activity moves to background (service continues running)
 * - onDestroy: Cleanup handlers and message subscriptions
 */
class MainActivity : ComponentActivity() {
    private val tag = "MainActivity"
    private val viewModel: MainViewModel by viewModels()

    // Service references
    private var networkService: NetworkService? = null
    private var messageHandler: MessageHandler? = null
    private var outgoingQueue: OutgoingMessageQueue? = null

    private var serviceIntentStarted = false

    /**
     * Single batched permission launcher.
     * We request ALL required runtime permissions at once so the user sees one dialog
     * sequence, and we only start GPS / the foreground service after the user has
     * responded to every permission (granted or denied).
     */
    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        results.forEach { (perm, granted) ->
            Log.i(tag, "${perm.substringAfterLast('.')}: ${if (granted) "GRANTED" else "DENIED"}")
        }

        val locationGranted = results[Manifest.permission.ACCESS_FINE_LOCATION] == true
        val notifGranted   = results[Manifest.permission.POST_NOTIFICATIONS]    != false // not required pre-T

        if (!locationGranted) {
            Log.w(tag, "⚠️ ACCESS_FINE_LOCATION denied — GPS streaming will be unavailable")
        }
        if (!notifGranted) {
            Log.w(tag, "⚠️ POST_NOTIFICATIONS denied — foreground notification suppressed")
        }

        // Always proceed — service handles missing perms gracefully
        onAllPermissionsResolved()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.i(tag, "MainActivity created")

        // The voice is fully on-device + deterministic now (no model download) — go
        // straight to the console.
        showMainScreen()

        // Initialize ViewModel immediately (AI engine, UI state)
        lifecycleScope.launch { initializeViewModel() }

        // Request ALL required permissions before starting GPS / foreground service
        requestAllPermissions()
    }

    /**
     * Build the full list of runtime permissions this app needs and fire a single
     * batched request.  Any already-granted permissions are silently excluded from
     * the dialog but still appear in the results map as `true`.
     */
    private fun requestAllPermissions() {
        val needed = buildList {
            // Location — required for GPS streaming AND bnep0 detection
            if (ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.ACCESS_FINE_LOCATION)
                    != PackageManager.PERMISSION_GRANTED) {
                add(Manifest.permission.ACCESS_FINE_LOCATION)
            }
            if (ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.ACCESS_COARSE_LOCATION)
                    != PackageManager.PERMISSION_GRANTED) {
                add(Manifest.permission.ACCESS_COARSE_LOCATION)
            }
            // Notifications (Android 13+)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.POST_NOTIFICATIONS)
                    != PackageManager.PERMISSION_GRANTED) {
                add(Manifest.permission.POST_NOTIFICATIONS)
            }
            // Bluetooth (Android 12+)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                if (ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.BLUETOOTH_CONNECT)
                        != PackageManager.PERMISSION_GRANTED) {
                    add(Manifest.permission.BLUETOOTH_CONNECT)
                }
                if (ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.BLUETOOTH_SCAN)
                        != PackageManager.PERMISSION_GRANTED) {
                    add(Manifest.permission.BLUETOOTH_SCAN)
                }
            }
        }

        if (needed.isEmpty()) {
            Log.i(tag, "✅ All permissions already granted — starting services immediately")
            onAllPermissionsResolved()
        } else {
            Log.i(tag, "Requesting ${needed.size} permission(s): ${needed.map { it.substringAfterLast('.') }}")
            permissionLauncher.launch(needed.toTypedArray())
        }
    }

    /**
     * Called once the user has responded to every permission dialog.
     * Safe to start GPS / foreground service from here.
     */
    private fun onAllPermissionsResolved() {
        Log.i(tag, "All permissions resolved — starting companion service & BT monitor")
        startCompanionService()
        initializeBluetoothMonitor()
    }

    /** Render main Compose content. */
    private fun showMainScreen() {
        setContent {
            MainScreen(viewModel)
        }
    }


    override fun onStart() {
        super.onStart()
        Log.i(tag, "MainActivity started (visible)")
    }

    override fun onResume() {
        super.onResume()
        Log.i(tag, "MainActivity resumed")
    }

    override fun onPause() {
        super.onPause()
        Log.i(tag, "MainActivity paused")
    }

    override fun onStop() {
        super.onStop()
        Log.i(tag, "MainActivity stopped (background)")
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.i(tag, "MainActivity destroyed")

        // Do NOT clean up the MessageHandler / OutgoingMessageQueue here. They belong to
        // the NetworkService SINGLETON (shared with CompanionBackgroundService), and their
        // cleanup() cancels internal coroutine scopes that are never recreated. On a mere
        // config change (rotation) or Activity teardown while the service keeps running,
        // that would permanently silence the device's image/GPS/event feed until the whole
        // process restarts. Just drop our references and let the singleton/service own the
        // lifecycle; the service persists until explicitly stopped or the device powers off.
        networkService = null
        messageHandler = null
        outgoingQueue = null
    }

    /**
     * Start CompanionBackgroundService which orchestrates all network/device services.
     * Uses foreground service for Android 8.0+ (minimum SDK is 29).
     */
    private fun startCompanionService() {
        if (serviceIntentStarted) {
            Log.w(tag, "Service already started")
            return
        }

        // Don't start the foreground service (and its persistent notification) on launch
        // when there's no BT tether — nothing can connect over BT-PAN without bnep0
        // anyway, so an always-on "listening" notice with no link is just noise. The
        // manifest BluetoothConnectionReceiver starts us the moment the device tethers,
        // and the [ start service ] button still forces a start on demand.
        if (!com.wsvdmeer.pwncompanion.utils.BluetoothHelper.hasBluetoothTether()) {
            Log.i(tag, "No BT tether at launch — deferring service start to the BT receiver")
            viewModel.setServerRunning(false)
            return
        }

        try {
            val serviceIntent = Intent(this, CompanionBackgroundService::class.java).apply {
                action = CompanionBackgroundService.ACTION_START_NETWORKING
            }

            startForegroundService(serviceIntent)

            serviceIntentStarted = true
            viewModel.setServerRunning(true)
            Log.i(tag, "CompanionBackgroundService start intent sent")
        } catch (e: Exception) {
            Log.e(tag, "Error starting service: ${e.message}", e)
            viewModel.setServerRunning(false)
        }
    }

    /**
     * Initialize ViewModel with service instances.
     * Retrieves NetworkService singleton (and its contained MessageHandler/OutgoingQueue)
     * and passes them to ViewModel for UI binding.
     * 
     * IMPORTANT: Uses NetworkServiceSingleton to ensure we get the SAME instance
     * that CompanionBackgroundService is using. This is critical for message flow.
     */
    private fun initializeViewModel() {
        try {
            // Get singleton instance of NetworkService (shared with CompanionBackgroundService)
            networkService = NetworkServiceSingleton.getInstance(applicationContext)
            
            // Get protocol handlers from NetworkService
            messageHandler = networkService!!.getMessageHandler()
            outgoingQueue = networkService!!.getOutgoingMessageQueue()

            // Initialize ViewModel with services
            viewModel.initializeServices(
                networkService = networkService!!,
                messageHandler = messageHandler!!,
                outgoingQueue = outgoingQueue!!
            )
            
            Log.i(tag, "ViewModel initialized with singleton NetworkService instance")
        } catch (e: Exception) {
            Log.e(tag, "Error initializing ViewModel: ${e.message}", e)
        }
    }

    /**
     * Stop the background service.
     * Called from UI when user wants to disable networking.
     */
    @Suppress("UNUSED")
    fun stopCompanionService() {
        try {
            val serviceIntent = Intent(this, CompanionBackgroundService::class.java).apply {
                action = CompanionBackgroundService.ACTION_STOP_NETWORKING
            }
            startService(serviceIntent)
            serviceIntentStarted = false
            viewModel.setServerRunning(false)
            Log.i(tag, "CompanionBackgroundService stop intent sent")
        } catch (e: Exception) {
            Log.e(tag, "Error stopping service: ${e.message}", e)
        }
    }


    /**
     * Initialize Bluetooth monitor after permissions are confirmed.
     * Called after all required Bluetooth permissions are granted.
     */
    private fun initializeBluetoothMonitor() {
        Log.i(tag, "🚀 Initializing Bluetooth monitor with permissions confirmed...")
        
        try {
            // Get network service instance
            val netService = NetworkServiceSingleton.getInstance(applicationContext)
            networkService = netService
            
            // Initialize the monitor
            netService.bluetoothMonitor.register()
            Log.i(tag, "✅ Bluetooth monitor initialized and registered")
            
            // Direct check for already-connected bnep devices
            Log.i(tag, "   Checking for pre-connected bnep0 interface...")
            netService.bluetoothMonitor.directCheckForBtPan()
            
        } catch (e: Exception) {
            Log.e(tag, "Error initializing Bluetooth monitor: ${e.message}", e)
        }
    }
}
