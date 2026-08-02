package com.wsvdmeer.pwncompanion.crack

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.InputStream
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.zip.GZIPInputStream

/**
 * Holds the on-phone cracking wordlist. Two tiers so the FIRST crack works with no network:
 *  - a compact **bundled starter** (`assets/starter.txt`, ~21K WPA-relevant words — SecLists
 *    WiFi-WPA top + rockyou-75; the APK compresses it) loaded offline, and
 *  - the fuller **downloaded** list (pwncrack `default.gz`, ~655K), fetched in the background and
 *    used automatically on the next crack once present.
 *
 * The full list is refreshed with a throttled conditional GET (ETag / Last-Modified, ~once a day),
 * so an updated `default.gz` is picked up; a changed file is reloaded between cracks. The chosen list
 * is decompressed + length-filtered (8–63, WPA-valid) into memory.
 */
object WordlistManager {
    private const val TAG = "WordlistManager"
    const val DEFAULT_URL = "https://pwncrack.org/wordlists/default.gz"
    private const val STARTER_ASSET = "starter.txt"   // plain text — the build compresses it in the APK
    private const val META_PREFS = "wordlist_meta"
    private const val CHECK_INTERVAL_MS = 24L * 60 * 60 * 1000   // re-check for updates at most once a day

    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    private val bgScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val syncing = AtomicBoolean(false)

    @Volatile private var candidates: List<String>? = null
    @Volatile private var loadedId: String = ""     // "starter" | "default"
    @Volatile private var loadedStamp: Long = 0L    // full file's lastModified when loaded (upgrade detection)

    val isLoaded: Boolean get() = candidates != null
    fun words(): List<String> = candidates ?: emptyList()

    /**
     * Stable identity of the currently loaded list, used to validate resume checkpoints: a saved
     * crack position is only valid against the exact same ordered candidate set. Includes the tier
     * ("starter"/"default") + count so a starter→full swap or an update invalidates old checkpoints.
     */
    fun identity(): String = "$loadedId:" + (candidates?.size ?: 0)

    private fun fullFile(context: Context) = File(context.filesDir, "wordlist.gz")

    /**
     * Load the best wordlist available **without network**: the fuller downloaded list if it's on
     * disk, else the bundled starter. Reloads when the full list appears (starter→full) or its file
     * changes (an update landed). Applied between cracks, never mid-crack. Returns true once [words]
     * is populated.
     */
    fun ensureLoaded(context: Context): Boolean {
        val ctx = context.applicationContext
        val full = fullFile(ctx)
        val fullReady = full.exists() && full.length() > 0L
        if (candidates != null) {
            when {
                loadedId == "default" && fullReady && full.lastModified() == loadedStamp -> return true
                loadedId == "starter" && !fullReady -> return true
                // else: full appeared, or the full file changed → fall through and (re)load it.
            }
        }
        return if (fullReady) {
            runCatching { loadFull(full) }.getOrElse {
                Log.e(TAG, "full list decompress error: ${it.message}")
                runCatching { full.delete() }   // corrupt — drop it, fall back to the starter
                loadStarter(ctx)
            }
        } else {
            loadStarter(ctx)
        }
    }

    private fun loadFull(f: File): Boolean {
        val ok = loadFrom(GZIPInputStream(f.inputStream().buffered()), "default")
        loadedStamp = f.lastModified()
        return ok
    }

    private fun loadStarter(ctx: Context): Boolean = runCatching {
        loadFrom(ctx.assets.open(STARTER_ASSET), "starter")   // plain-text asset (no gzip)
    }.getOrElse {
        Log.e(TAG, "starter load error: ${it.message}"); false
    }

    private fun loadFrom(stream: InputStream, id: String): Boolean {
        val list = ArrayList<String>(if (id == "default") 700_000 else 32_000)
        stream.bufferedReader().useLines { seq ->
            seq.forEach { raw ->
                val w = raw.trim()
                if (w.length in 8..63) list.add(w)   // WPA-valid lengths only
            }
        }
        candidates = list
        loadedId = id
        Log.i(TAG, "wordlist loaded ($id): ${list.size} WPA-valid candidates")
        return true
    }

    /**
     * Download the full list if it isn't present, else (throttled to once a day, or [force]) check
     * for a newer version via a conditional GET. Fire-and-forget + de-duplicated; the next
     * [ensureLoaded] picks up a fresh/updated file. Safe to call on every crack start.
     */
    fun syncFull(context: Context, url: String = DEFAULT_URL, force: Boolean = false) {
        val ctx = context.applicationContext
        if (!syncing.compareAndSet(false, true)) return
        bgScope.launch {
            try {
                val f = fullFile(ctx)
                val have = f.exists() && f.length() > 0L
                val meta = ctx.getSharedPreferences(META_PREFS, Context.MODE_PRIVATE)
                if (have && !force &&
                    System.currentTimeMillis() - meta.getLong("lastCheck", 0L) < CHECK_INTERVAL_MS
                ) return@launch
                val etag = if (have) meta.getString("etag", null) else null
                val lastMod = if (have) meta.getString("lastMod", null) else null
                when (val r = fetch(url, f, etag, lastMod)) {
                    is Fetch.Updated -> {
                        meta.edit()
                            .putString("etag", r.etag).putString("lastMod", r.lastMod)
                            .putLong("lastCheck", System.currentTimeMillis()).apply()
                        Log.i(TAG, "full wordlist ${if (have) "updated" else "downloaded"} — used on the next crack")
                    }
                    Fetch.Unchanged -> {
                        meta.edit().putLong("lastCheck", System.currentTimeMillis()).apply()
                        Log.i(TAG, "full wordlist up to date")
                    }
                    Fetch.Failed -> Log.w(TAG, "full wordlist sync failed")
                }
            } finally {
                syncing.set(false)
            }
        }
    }

    private sealed interface Fetch {
        data class Updated(val etag: String?, val lastMod: String?) : Fetch
        object Unchanged : Fetch
        object Failed : Fetch
    }

    /** Conditional GET: 304 → Unchanged; 200 → download to a .part then atomically swap in. */
    private fun fetch(url: String, f: File, etag: String?, lastMod: String?): Fetch {
        val tmp = File(f.parentFile, f.name + ".part")
        return try {
            val req = Request.Builder().url(url).apply {
                if (etag != null) header("If-None-Match", etag)
                if (lastMod != null) header("If-Modified-Since", lastMod)
            }.build()
            http.newCall(req).execute().use { resp ->
                if (resp.code == 304) return Fetch.Unchanged
                val body = resp.body
                if (!resp.isSuccessful || body == null) {
                    Log.w(TAG, "download failed: HTTP ${resp.code}")
                    return Fetch.Failed
                }
                tmp.outputStream().use { out -> body.byteStream().use { it.copyTo(out, 64 * 1024) } }
                if (!tmp.renameTo(f)) { f.delete(); tmp.renameTo(f) }   // atomic swap, replacing any old file
                Fetch.Updated(resp.header("ETag"), resp.header("Last-Modified"))
            }
        } catch (e: Exception) {
            Log.e(TAG, "download error: ${e.message}")
            runCatching { tmp.delete() }
            Fetch.Failed
        }
    }
}
