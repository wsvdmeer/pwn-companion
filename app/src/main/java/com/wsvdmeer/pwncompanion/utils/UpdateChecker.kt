package com.wsvdmeer.pwncompanion.utils

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Best-effort "new version on GitHub" check. PwnCompanion is sideloaded (no store auto-update),
 * so a user has no other signal that a fresh APK exists — this surfaces one as a single console
 * line. Deliberately silent: any failure (offline, GitHub rate-limit, unexpected JSON) returns
 * null and the app simply shows nothing.
 *
 * Privacy note: this pings api.github.com, which reveals the caller's IP to GitHub — the one cost
 * of the check. It runs once per app launch (see MainViewModel), unauthenticated (GitHub allows
 * 60 such requests/hour per IP, far above one-per-launch).
 */
object UpdateChecker {
    private const val TAG = "UpdateChecker"
    private const val LATEST_RELEASE_URL =
        "https://api.github.com/repos/wsvdmeer/pwn-companion/releases/latest"

    /**
     * The latest published release's version (e.g. "1.2.5", the tag with any leading "v" stripped)
     * if it is strictly newer than [current], otherwise null. [current] is the installed
     * `BuildConfig.VERSION_NAME`.
     */
    suspend fun latestNewerThan(current: String): String? = withContext(Dispatchers.IO) {
        try {
            val conn = (URL(LATEST_RELEASE_URL).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 8000
                readTimeout = 8000
                setRequestProperty("Accept", "application/vnd.github+json")
                setRequestProperty("User-Agent", "pwn-companion-app")
            }
            try {
                if (conn.responseCode != HttpURLConnection.HTTP_OK) return@withContext null
                val body = conn.inputStream.bufferedReader().use { it.readText() }
                val tag = JSONObject(body).optString("tag_name").trim().removePrefix("v")
                if (tag.isNotEmpty() && isNewer(tag, current)) tag else null
            } finally {
                conn.disconnect()
            }
        } catch (e: Exception) {
            Log.d(TAG, "update check skipped: ${e.message}")
            null
        }
    }

    /**
     * True if dotted version [candidate] is strictly greater than [current], compared segment by
     * segment as integers (missing segments count as 0, so "1.3" > "1.2.9"). Non-numeric segments
     * degrade to 0 rather than throwing.
     */
    fun isNewer(candidate: String, current: String): Boolean {
        val a = candidate.split(".").map { it.toIntOrNull() ?: 0 }
        val b = current.split(".").map { it.toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0 }
            val y = b.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        return false
    }
}
