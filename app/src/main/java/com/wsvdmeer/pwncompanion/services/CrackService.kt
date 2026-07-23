package com.wsvdmeer.pwncompanion.services

import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.util.Log
import androidx.core.app.ServiceCompat
import com.wsvdmeer.pwncompanion.crack.CrackEngine
import com.wsvdmeer.pwncompanion.crack.CrackState
import com.wsvdmeer.pwncompanion.utils.NotificationHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Foreground service that keeps the process alive while [CrackEngine] cracks, and mirrors its
 * progress into a notification. It owns no crack logic — the engine does — it just stops Doze /
 * background-CPU throttling from stalling a multi-hour crack when the screen's off, and gives a
 * Stop button. The engine starts it when work begins and stops it when the queue drains.
 */
class CrackService : Service() {
    private val tag = "CrackService"
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    companion object {
        const val ACTION_STOP = "com.wsvdmeer.pwncompanion.CRACK_STOP"
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            Log.i(tag, "Stop action -> aborting crack queue")
            CrackEngine.cancelAll(this)
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }

        NotificationHelper.createNotificationChannels(this)
        val notification = NotificationHelper.createCrackServiceNotification(this)

        // Must call startForeground() within Android's 5s window. Try the typed variant first
        // (Android 14 requires a declared type), fall back to untyped so we never miss the window.
        try {
            ServiceCompat.startForeground(
                this,
                NotificationHelper.NOTIFICATION_ID_CRACK,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } catch (e: Exception) {
            Log.w(tag, "startForeground(DATA_SYNC) failed (${e.message}); trying untyped")
            try {
                @Suppress("DEPRECATION")
                startForeground(NotificationHelper.NOTIFICATION_ID_CRACK, notification)
            } catch (e2: Exception) {
                Log.e(tag, "startForeground failed: ${e2.message}", e2)
                stopSelf()
                return START_NOT_STICKY
            }
        }

        // Mirror engine state -> notification for this service's lifetime. When the engine
        // finishes it calls stopService(); if it goes Idle we also self-stop defensively.
        scope.launch {
            CrackEngine.state.collect { st ->
                if (st is CrackState.Idle) { stopSelf(); return@collect }
                updateFrom(st)
            }
        }
        // Not sticky: the crack state lives in-process (CrackEngine). If the process is killed the
        // queue + loaded wordlist are gone, so there's nothing to resurrect a bare service for.
        return START_NOT_STICKY
    }

    private fun updateFrom(st: CrackState) {
        when (st) {
            is CrackState.Downloading -> {
                val pct = (st.pct * 100).toInt()
                NotificationHelper.updateCrackNotification(
                    this, "Downloading wordlist", "$pct%",
                    max = 100, progress = pct, indeterminate = st.pct <= 0f
                )
            }
            is CrackState.Running -> {
                val queued = CrackEngine.queue.value.size
                val pct = if (st.total > 0) ((st.tried * 100) / st.total).toInt() else 0
                val suffix = if (queued > 0) " · $queued queued" else ""
                NotificationHelper.updateCrackNotification(
                    this, "Cracking ${st.ssid}$suffix",
                    "${st.tried} / ${st.total} · ${st.perSec}/s",
                    max = 100, progress = pct, indeterminate = st.total <= 0L
                )
            }
            is CrackState.Paused -> NotificationHelper.updateCrackNotification(
                this, "Cracking paused", st.reason, showProgress = false
            )
            is CrackState.Done -> NotificationHelper.updateCrackNotification(
                this, "Cracked ${st.ssid}", st.password, showProgress = false
            )
            is CrackState.Failed -> NotificationHelper.updateCrackNotification(
                this, "Crack stopped", "${st.ssid}: ${st.reason}", showProgress = false
            )
            CrackState.Idle -> { /* handled in the collector (self-stop) */ }
        }
    }

    override fun onDestroy() {
        try { ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE) } catch (_: Exception) {}
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
