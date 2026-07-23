package com.wsvdmeer.pwncompanion.crack

import android.content.Context
import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPInputStream

/**
 * Holds the on-phone cracking wordlist. MVP: one gzipped list (pwncrack `default.gz`, ~655K
 * WPA-shaped words). Downloaded once to app-private storage, then decompressed + length-filtered
 * (8–63, WPA-valid) into memory for the cracker to iterate.
 *
 * In-memory is fine for `default.gz` (~6 MB of strings). Medium HashMob lists (tens of M) would
 * need streaming instead — a later step.
 */
object WordlistManager {
    private const val TAG = "WordlistManager"
    const val DEFAULT_URL = "https://pwncrack.org/wordlists/default.gz"

    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    @Volatile private var candidates: List<String>? = null

    val isLoaded: Boolean get() = candidates != null
    fun words(): List<String> = candidates ?: emptyList()

    private fun file(context: Context) = File(context.filesDir, "wordlist.gz")

    /**
     * Ensure the wordlist is downloaded + loaded into memory. [onProgress] reports the
     * download fraction (0..1). Returns true once [words] is populated. Safe to call repeatedly.
     */
    fun ensure(context: Context, url: String = DEFAULT_URL, onProgress: (Float) -> Unit = {}): Boolean {
        candidates?.let { return true }
        val f = file(context)
        if (!f.exists() || f.length() == 0L) {
            try {
                val resp = http.newCall(Request.Builder().url(url).build()).execute()
                resp.use {
                    val body = it.body
                    if (!it.isSuccessful || body == null) {
                        Log.w(TAG, "download failed: HTTP ${it.code}")
                        return false
                    }
                    val total = body.contentLength()
                    f.outputStream().use { out ->
                        body.byteStream().use { inp ->
                            val buf = ByteArray(64 * 1024)
                            var read = 0L
                            var n: Int
                            while (inp.read(buf).also { c -> n = c } >= 0) {
                                out.write(buf, 0, n)
                                read += n
                                if (total > 0) onProgress((read.toFloat() / total).coerceIn(0f, 1f))
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "download error: ${e.message}")
                runCatching { f.delete() }
                return false
            }
        }
        onProgress(1f)
        return try {
            val list = ArrayList<String>(700_000)
            GZIPInputStream(f.inputStream().buffered()).bufferedReader().useLines { seq ->
                seq.forEach { raw ->
                    val w = raw.trim()
                    if (w.length in 8..63) list.add(w)   // WPA-valid lengths only
                }
            }
            candidates = list
            Log.i(TAG, "wordlist loaded: ${list.size} WPA-valid candidates")
            true
        } catch (e: Exception) {
            Log.e(TAG, "decompress error: ${e.message}")
            false
        }
    }
}
