package com.wsvdmeer.pwncompanion.storage

import android.content.Context
import android.util.Log
import com.wsvdmeer.pwncompanion.models.CaptureEntry
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Local, on-disk cache of captured handshakes so the app is useful **without the Pwnagotchi
 * connected**. The device only streams its capture history while linked; this store accumulates
 * those entries (keyed by [CaptureEntry.key] = BSSID) to `filesDir/captures.json`, so after a
 * restart — or with the Pi nowhere near — the captures (and their 22000 hashes) are still there to
 * browse and crack on-phone.
 *
 * Merge policy: live device data wins per key (it carries the freshest hash/quality), and entries
 * the device isn't currently reporting are kept (that's the whole point — offline persistence).
 * Cracked passwords live separately in CrackEngine and are overlaid on top, so clearing this cache
 * never loses them. [clear] drops the cache; that only sticks while disconnected (a linked Pi
 * resends its full history), which is why the device-wipe path clears here too.
 */
object CaptureStore {
    private const val TAG = "CaptureStore"
    private const val FILE = "captures.json"
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val lock = Any()
    @Volatile private var cache: LinkedHashMap<String, CaptureEntry>? = null

    private fun file(context: Context) = File(context.applicationContext.filesDir, FILE)

    private fun ensure(context: Context): LinkedHashMap<String, CaptureEntry> {
        cache?.let { return it }
        val m = LinkedHashMap<String, CaptureEntry>()
        val f = file(context)
        if (f.exists()) {
            runCatching { json.decodeFromString<List<CaptureEntry>>(f.readText()) }
                .onSuccess { list -> list.forEach { m[it.key] = it } }
                .onFailure { Log.w(TAG, "load failed: ${it.message}") }
        }
        cache = m
        return m
    }

    /** Persisted captures, newest first. Call once at startup to seed the UI before the Pi links. */
    fun load(context: Context): List<CaptureEntry> = synchronized(lock) {
        ensure(context).values.sortedByDescending { it.timestamp ?: 0L }
    }

    /**
     * Fold live [incoming] captures into the store (incoming wins per key), persist only when the
     * merged set actually changed, and return the full set newest-first.
     */
    fun merge(context: Context, incoming: List<CaptureEntry>): List<CaptureEntry> = synchronized(lock) {
        val m = ensure(context)
        var changed = false
        for (c in incoming) {
            val k = c.key
            if (m[k] != c) { m[k] = c; changed = true }
        }
        if (changed) persist(context, m)
        m.values.sortedByDescending { it.timestamp ?: 0L }
    }

    /** Wipe the local capture cache (does not touch the Pi or cracked passwords). */
    fun clear(context: Context) = synchronized(lock) {
        cache = LinkedHashMap()
        runCatching { file(context).delete() }
        Log.i(TAG, "capture cache cleared")
    }

    private fun persist(context: Context, m: Map<String, CaptureEntry>) {
        runCatching { file(context).writeText(json.encodeToString<List<CaptureEntry>>(m.values.toList())) }
            .onFailure { Log.w(TAG, "persist failed: ${it.message}") }
    }
}
