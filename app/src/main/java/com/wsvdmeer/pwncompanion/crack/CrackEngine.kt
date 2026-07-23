package com.wsvdmeer.pwncompanion.crack

import android.content.Context
import android.content.Intent
import android.os.BatteryManager
import android.os.Build
import android.util.Log
import com.wsvdmeer.pwncompanion.models.CaptureEntry
import com.wsvdmeer.pwncompanion.services.CrackService
import com.wsvdmeer.pwncompanion.utils.NotificationHelper
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/** On-phone crack progress, surfaced to the captures screen + the foreground notification. */
sealed interface CrackState {
    data object Idle : CrackState
    data class Downloading(val pct: Float) : CrackState
    data class Running(val bssid: String, val ssid: String, val tried: Long, val total: Long, val perSec: Long) : CrackState
    /** Held by a power policy (unplugged / low battery); resumes automatically when it clears. */
    data class Paused(val bssid: String, val ssid: String, val reason: String) : CrackState
    data class Done(val bssid: String, val ssid: String, val password: String) : CrackState
    data class Failed(val bssid: String, val ssid: String, val reason: String) : CrackState
}

/**
 * Process-lifetime engine that cracks captured PMKIDs on-phone, one at a time, from a FIFO queue.
 *
 * It's a singleton (not tied to any ViewModel/Activity) so a multi-hour crack keeps running while
 * you switch apps or lock the phone — a foreground [CrackService] keeps the process alive and
 * mirrors progress into a notification. Tap more `crack ▸` rows to queue them; they run in turn.
 * Serial by design: cracking is pure PBKDF2 already fanned out across cores, so two at once would
 * just halve each other's speed.
 */
object CrackEngine {
    private const val TAG = "CrackEngine"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _state = MutableStateFlow<CrackState>(CrackState.Idle)
    val state: StateFlow<CrackState> = _state.asStateFlow()

    // Captures waiting their turn (FIFO), excluding whichever is running now.
    private val _queue = MutableStateFlow<List<CaptureEntry>>(emptyList())
    val queue: StateFlow<List<CaptureEntry>> = _queue.asStateFlow()

    // Passwords cracked on-phone (normalised bssid → password), overlaid onto captures.
    private val _cracked = MutableStateFlow<Map<String, String>>(emptyMap())
    val cracked: StateFlow<Map<String, String>> = _cracked.asStateFlow()

    private var job: Job? = null
    private val skip = AtomicBoolean(false)
    private val paused = AtomicBoolean(false)

    fun norm(bssid: String): String = bssid.lowercase().replace(":", "").replace("-", "")

    private fun runningKey(): String? = (_state.value as? CrackState.Running)?.let { norm(it.bssid) }

    /** Queue [capture] for cracking (dedup vs cracked/running/queued); start the processor if idle. */
    fun enqueue(context: Context, capture: CaptureEntry) {
        val hash = capture.hash22000 ?: return
        if (!WpaCracker.isCrackablePmkid(hash)) return
        val key = norm(capture.bssid)
        if (_cracked.value.containsKey(key)) return
        if (runningKey() == key) return
        var added = false
        _queue.update { q ->
            if (q.any { norm(it.bssid) == key }) q else { added = true; q + capture }
        }
        if (added) start(context.applicationContext)
    }

    /** Remove a still-queued capture (no effect on the one currently running). */
    fun dequeue(capture: CaptureEntry) {
        val key = norm(capture.bssid)
        _queue.update { q -> q.filterNot { norm(it.bssid) == key } }
    }

    /** Skip the crack in progress and move on to the next queued one. */
    fun skipCurrent() { skip.set(true) }

    /** Abort everything: the current crack + the whole queue. */
    fun cancelAll(context: Context) {
        _queue.value = emptyList()
        job?.cancel()
        job = null
        _state.value = CrackState.Idle
        stopService(context.applicationContext)
    }

    /** Clear a finished (Done/Failed) banner once the processor is idle. */
    fun dismiss() { if (job?.isActive != true) _state.value = CrackState.Idle }

    private fun start(context: Context) {
        if (job?.isActive == true) return   // processor already draining the queue
        CrackSettings.ensureLoaded(context)
        startService(context)
        job = scope.launch {
            try {
                if (!WordlistManager.isLoaded) {
                    _state.value = CrackState.Downloading(0f)
                    val ok = WordlistManager.ensure(context) { p -> _state.value = CrackState.Downloading(p) }
                    if (!ok) {
                        _state.value = CrackState.Failed("", "wordlist", "wordlist unavailable")
                        _queue.value = emptyList()
                        return@launch
                    }
                }
                // `current` is the item being worked. It's held (not re-queued) across a power
                // pause so it resumes from its checkpoint when power returns, rather than being
                // dropped or restarted.
                var current: CaptureEntry? = null
                while (true) {
                    val reason = blockReason(context)
                    if (reason != null) {
                        val label = current ?: _queue.value.firstOrNull()
                        _state.value = CrackState.Paused(
                            label?.bssid ?: "",
                            label?.let { it.ssid.ifBlank { it.bssid } } ?: "",
                            reason,
                        )
                        delay(2000)   // re-check power every 2s
                        continue
                    }
                    if (current == null) current = dequeueNext()
                    if (current == null) break
                    val consumed = crackOne(context, current)
                    if (consumed) current = null   // else: paused mid-crack → retry (resumes)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "processor error: ${e.message}", e)
            } finally {
                stopService(context)
            }
        }
    }

    private fun dequeueNext(): CaptureEntry? {
        var next: CaptureEntry? = null
        _queue.update { q ->
            if (q.isEmpty()) { next = null; q } else { next = q.first(); q.drop(1) }
        }
        return next
    }

    /** Crack [capture]; returns true if it's consumed (advance the queue), false if paused mid-run. */
    private suspend fun crackOne(context: Context, capture: CaptureEntry): Boolean {
        skip.set(false)
        paused.set(false)
        val ssid = capture.ssid.ifBlank { capture.bssid.ifBlank { "network" } }
        val bssid = capture.bssid
        val key = norm(bssid)
        val h = WpaCracker.parsePmkid(capture.hash22000 ?: "")
        if (h == null) { _state.value = CrackState.Failed(bssid, ssid, "bad hash"); return true }
        val words = WordlistManager.words()
        val total = words.size.toLong()
        val cores = workerCores(context)

        // Resume: pick up from the saved checkpoint if it's valid for this exact wordlist.
        // Indices are handed out from a single monotonic cursor, so every index below
        // (cursor - cores) is guaranteed already verified — that floor is the safe checkpoint.
        val wordlistId = WordlistManager.identity()
        val startIndex = loadCheckpoint(context, key, wordlistId).coerceIn(0L, total)
        val cursor = AtomicLong(startIndex)      // next candidate index to hand out
        val tried = AtomicLong(startIndex)       // absolute position (for the bar)
        val found = AtomicReference<String?>(null)
        val startMs = System.currentTimeMillis()
        _state.value = CrackState.Running(bssid, ssid, startIndex, total, 0)
        if (startIndex > 0) Log.i(TAG, "resuming $ssid from $startIndex/$total")
        Log.i(TAG, "cracking $ssid with $cores workers over $total candidates")
        coroutineScope {
            // Ticker on Main so the CPU-bound workers (which never suspend) can't starve it off
            // the Default pool — that would freeze the progress bar even as cracking proceeds.
            val ticker = launch(Dispatchers.Main) {
                while (isActive && found.get() == null && !skip.get() && !paused.get()) {
                    // Power policy can pull the plug mid-crack — pause cleanly (checkpoint holds).
                    if (blockReason(context) != null) { paused.set(true); break }
                    val n = tried.get()
                    val secs = (System.currentTimeMillis() - startMs) / 1000.0
                    val done = (n - startIndex).coerceAtLeast(0)
                    val ps = if (secs > 0.5) (done / secs).toLong() else 0L
                    _state.value = CrackState.Running(bssid, ssid, n, total, ps)
                    // Persist the safe floor so a kill/reboot/cancel/pause resumes ~here, not from 0.
                    saveCheckpoint(context, key, wordlistId, (cursor.get() - cores).coerceAtLeast(0))
                    delay(400)
                }
            }
            // Workers pull the next index from the shared cursor (monotonic → resumable).
            (0 until cores).map {
                launch {
                    while (found.get() == null && !skip.get() && !paused.get() && isActive) {
                        val idx = cursor.getAndIncrement()
                        if (idx >= words.size) return@launch
                        val candidate = words[idx.toInt()]
                        if (WpaCracker.verify(h, candidate)) { found.set(candidate); return@launch }
                        tried.incrementAndGet()
                    }
                }
            }.forEach { it.join() }
            ticker.cancel()
        }
        // Persist the latest floor before deciding the outcome (covers a mid-crack pause).
        saveCheckpoint(context, key, wordlistId, (cursor.get() - cores).coerceAtLeast(0))
        val pw = found.get()
        val queueLeft = _queue.value.size
        return when {
            pw != null -> {
                clearCheckpoint(context, key)
                _cracked.update { it + (key to pw) }
                _state.value = CrackState.Done(bssid, ssid, pw)
                runCatching { NotificationHelper.notifyCracked(context, ssid, pw) }
                Log.i(TAG, "on-phone crack SUCCESS: $ssid -> $pw")
                if (queueLeft > 0) delay(1500)   // let the result show before the next one starts
                true
            }
            paused.get() -> false   // power policy paused us; retry the same network on resume
            skip.get() -> {
                // Keep the checkpoint — a skipped crack resumes if you queue it again.
                _state.value = CrackState.Failed(bssid, ssid, "skipped")
                if (queueLeft > 0) delay(600)
                true
            }
            else -> {
                // Wordlist exhausted — nothing left to resume.
                clearCheckpoint(context, key)
                _state.value = CrackState.Failed(bssid, ssid, "not in wordlist ($total tried)")
                if (queueLeft > 0) delay(1500)
                true
            }
        }
    }

    // ── Resume checkpoints ─────────────────────────────────────────────────────
    // Persist "how far did we get" per network so an interrupted crack (process kill, reboot,
    // cancel, skip) picks up where it left off instead of restarting from candidate 0. Value is
    // "<index>@<wordlistId>"; a checkpoint for a different wordlist is ignored.
    private const val PREFS = "crack_checkpoints"

    private fun loadCheckpoint(context: Context, bssidKey: String, wordlistId: String): Long {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(bssidKey, null)
            ?: return 0L
        val parts = raw.split("@", limit = 2)
        if (parts.size != 2 || parts[1] != wordlistId) return 0L
        return parts[0].toLongOrNull() ?: 0L
    }

    private fun saveCheckpoint(context: Context, bssidKey: String, wordlistId: String, index: Long) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(bssidKey, "$index@$wordlistId").apply()
    }

    private fun clearCheckpoint(context: Context, bssidKey: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(bssidKey).apply()
    }

    /** Worker count, honouring the gentle knobs: cap at 2 in easy-CPU mode, else half on battery
     * and all-but-one while charging. */
    private fun workerCores(context: Context): Int {
        val cpus = Runtime.getRuntime().availableProcessors()
        if (CrackSettings.gentleCpu.value) return 2.coerceIn(1, cpus)
        return if (isCharging(context)) (cpus - 1).coerceIn(1, 8) else (cpus / 2).coerceIn(1, 8)
    }

    private fun isCharging(context: Context): Boolean = runCatching {
        (context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager).isCharging
    }.getOrDefault(false)

    /** Why cracking is currently blocked by the power policy, or null if it may run. */
    private fun blockReason(context: Context): String? {
        CrackSettings.ensureLoaded(context)
        val bm = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        if (runCatching { bm.isCharging }.getOrDefault(false)) return null   // plugged in
        if (CrackSettings.chargerOnly.value) return "waiting for charger"
        if (CrackSettings.lowBatteryStop.value) {
            val level = runCatching { bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) }
                .getOrDefault(100)
            if (level in 0..CrackSettings.LOW_PCT) return "battery $level% - paused"
        }
        return null
    }

    private fun startService(context: Context) {
        val intent = Intent(context, CrackService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(intent)
        else context.startService(intent)
    }

    private fun stopService(context: Context) {
        runCatching { context.stopService(Intent(context, CrackService::class.java)) }
    }
}
