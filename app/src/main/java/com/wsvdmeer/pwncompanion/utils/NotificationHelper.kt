package com.wsvdmeer.pwncompanion.utils

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.wsvdmeer.pwncompanion.R
import com.wsvdmeer.pwncompanion.presentation.MainActivity
import com.wsvdmeer.pwncompanion.services.CompanionBackgroundService
import com.wsvdmeer.pwncompanion.services.GpsService

/**
 * NotificationHelper — rich foreground-service notifications with live status + Stop buttons.
 *
 * Network notification  (id 1000): ws://ip:8081 · N devices · [Stop] button
 * Location notification (id 1002): lat/lon/accuracy · [Stop] button
 */
object NotificationHelper {
    private const val TAG = "NotificationHelper"

    // Channel IDs. The service channels use a "_v2" suffix because Android caches a
    // channel's importance at creation time — to actually drop them to IMPORTANCE_MIN
    // (no status-bar icon, collapsed & silent in the shade) we must register NEW ids
    // and delete the old LOW ones.
    const val NETWORK_SERVICE_CHANNEL  = "network_service_v2"
    // Alerts: user-facing events (cracked passwords) — HIGH importance so they heads-up +
    // make a sound, unlike the MIN foreground-service notice.
    const val ALERTS_CHANNEL           = "alerts_v2"

    // Notification IDs (must match those used in the services)
    const val NOTIFICATION_ID_NETWORK  = 1000
    private const val NOTIFICATION_ID_ALERTS_SUMMARY = 1004
    private const val CRACKED_ID_BASE  = 2_000_000

    // Group key: all alert notifications share it so Android bundles them under one
    // expandable "PwnCompanion" header in the shade instead of showing loose cards.
    private const val GROUP_ALERTS = "com.wsvdmeer.pwncompanion.ALERTS"

    // ── Channels ─────────────────────────────────────────────────────────────

    fun createNotificationChannels(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

            // Retire old channels (renamed / removed) so their stale settings don't linger.
            nm.deleteNotificationChannel("network_service_channel")
            nm.deleteNotificationChannel("location_service_channel")
            nm.deleteNotificationChannel("location_service_v2")        // GPS notice removed (shares the network one)
            nm.deleteNotificationChannel("general_notifications_channel")
            nm.deleteNotificationChannel("alerts_v1")                  // superseded by the HIGH alerts_v2

            // IMPORTANCE_MIN: no status-bar icon, no sound/vibration, collapsed at the
            // bottom of the shade, no app badge — a required-but-unobtrusive FGS notice.
            nm.createNotificationChannel(NotificationChannel(
                NETWORK_SERVICE_CHANNEL,
                "Connection",
                NotificationManager.IMPORTANCE_MIN
            ).apply {
                description = "Pwnagotchi link (required while connected)"
                enableLights(false)
                enableVibration(false)
                setShowBadge(false)
            })

            nm.createNotificationChannel(NotificationChannel(
                ALERTS_CHANNEL,
                "Alerts",
                NotificationManager.IMPORTANCE_HIGH   // heads-up + sound for a newly cracked password
            ).apply {
                description = "Cracked passwords"
                setShowBadge(true)
            })
        }
    }

    // ── Event alerts (cracked passwords, link up) ──────────────────────────────

    /** A network's handshake was cracked — show the SSID + recovered password. */
    fun notifyCracked(context: Context, ssid: String, password: String) {
        val net = ssid.ifBlank { "a network" }
        val n = NotificationCompat.Builder(context, ALERTS_CHANNEL)
            .setContentTitle("🔓 Cracked $net")
            .setContentText("password: $password")
            .setStyle(NotificationCompat.BigTextStyle().bigText("$net\npassword: $password"))
            .setSmallIcon(com.wsvdmeer.pwncompanion.R.drawable.ic_stat_pwn)
            .setColor(ContextCompat.getColor(context, R.color.phosphor_green))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setGroup(GROUP_ALERTS)
            .setContentIntent(mainActivityIntent(context))
            .build()
        // Stable per (network,password) so a re-seen crack replaces rather than dupes,
        // while different cracks stack under the group.
        val id = CRACKED_ID_BASE + ("$net:$password".hashCode() and 0xFFFF)
        postSafely(context, id, n)
        postAlertsSummary(context)
    }

    /**
     * The group summary that makes Android bundle the alert notifications under one
     * "PwnCompanion" header (children stack inside it). Children carry the sound/alert
     * (GROUP_ALERT_CHILDREN) so the summary itself stays silent.
     */
    private fun postAlertsSummary(context: Context) {
        val summary = NotificationCompat.Builder(context, ALERTS_CHANNEL)
            .setContentTitle("PwnCompanion")
            .setSmallIcon(com.wsvdmeer.pwncompanion.R.drawable.ic_stat_pwn)
            .setColor(ContextCompat.getColor(context, R.color.phosphor_green))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setGroup(GROUP_ALERTS)
            .setGroupSummary(true)
            .setGroupAlertBehavior(NotificationCompat.GROUP_ALERT_CHILDREN)
            .setAutoCancel(true)
            .setContentIntent(mainActivityIntent(context))
            .build()
        postSafely(context, NOTIFICATION_ID_ALERTS_SUMMARY, summary)
    }

    /** notify() is a no-op without POST_NOTIFICATIONS (Android 13+); never let it crash. */
    private fun postSafely(context: Context, id: Int, notification: Notification) {
        try {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.notify(id, notification)
        } catch (e: SecurityException) {
            android.util.Log.w(TAG, "notify($id) blocked — POST_NOTIFICATIONS not granted")
        }
    }

    // ── Network / WebSocket notification ─────────────────────────────────────

    /**
     * Initial network-service notification shown when the service first starts.
     * Call [updateNetworkNotification] to refresh it with live data.
     */
    fun createNetworkServiceNotification(context: Context): Notification =
        buildNetworkNotification(context, ip = null, deviceCount = 0)

    /**
     * Update the network notification with current IP and connected device count.
     * Call this from NetworkService whenever the connection state changes.
     */
    fun updateNetworkNotification(
        context: Context,
        ip: String?,
        deviceCount: Int,
        title: String? = null,
        stats: String? = null,
        face: Bitmap? = null,
    ) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIFICATION_ID_NETWORK, buildNetworkNotification(context, ip, deviceCount, title, stats, face))
    }

    private fun buildNetworkNotification(
        context: Context,
        ip: String?,
        deviceCount: Int,
        title: String? = null,
        stats: String? = null,
        face: Bitmap? = null,
    ): Notification {
        val defaultText = when {
            ip != null && deviceCount > 0 ->
                "ws://$ip:8081 · $deviceCount device${if (deviceCount != 1) "s" else ""} connected"
            ip != null ->
                "ws://$ip:8081 · waiting for Pwnagotchi…"
            else ->
                "Starting WebSocket server…"
        }
        val text = stats ?: defaultText
        val heading = title ?: "PwnCompanion"

        val builder = NotificationCompat.Builder(context, NETWORK_SERVICE_CHANNEL)
            .setContentTitle(heading)
            .setContentText(text)
            .setSmallIcon(com.wsvdmeer.pwncompanion.R.drawable.ic_stat_pwn)
            .setColor(ContextCompat.getColor(context, R.color.phosphor_green))
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setShowWhen(false)
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setContentIntent(mainActivityIntent(context))
            .addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                "Stop",
                stopNetworkServiceIntent(context)
            )

        // Live pet: show the Pwnagotchi's e-ink face as the icon, and full-size + stats
        // when the notification is expanded. Turns the required FGS notice into a
        // glanceable widget without raising its (MIN) importance.
        if (face != null) {
            builder.setLargeIcon(face)
            builder.setStyle(
                NotificationCompat.BigPictureStyle()
                    .bigPicture(face)
                    .bigLargeIcon(null as Bitmap?)
                    .setBigContentTitle(heading)
                    .setSummaryText(text)
            )
        }
        return builder.build()
    }

    // ── PendingIntents ────────────────────────────────────────────────────────

    /** Tap notification → open MainActivity */
    private fun mainActivityIntent(context: Context): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        return PendingIntent.getActivity(
            context, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    /** Stop button → send ACTION_STOP_NETWORKING to CompanionBackgroundService */
    private fun stopNetworkServiceIntent(context: Context): PendingIntent {
        val intent = Intent(context, CompanionBackgroundService::class.java).apply {
            action = CompanionBackgroundService.ACTION_STOP_NETWORKING
        }
        return PendingIntent.getService(
            context, 1, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

}
