package com.wsvdmeer.pwncompanion.crack

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
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
    // mode: what's being cracked + how, e.g. "eapol · native" / "pmkid · cpu" — shown in the banner.
    data class Running(val bssid: String, val ssid: String, val tried: Long, val total: Long, val perSec: Long, val mode: String = "") : CrackState
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

    // Networks whose whole wordlist was searched with no hit — a lasting "no match" status so a
    // finished crack shows a result and you don't burn hours re-running the same one.
    private val _exhausted = MutableStateFlow<Set<String>>(emptySet())
    val exhausted: StateFlow<Set<String>> = _exhausted.asStateFlow()

    // Networks we've started cracking at least once but that didn't finish (stopped/interrupted, no
    // hit yet). Lets the UI flag "already tried" so you don't unknowingly re-run the same one.
    // Superseded once a network becomes cracked or exhausted.
    private val _attempted = MutableStateFlow<Set<String>>(emptySet())
    val attempted: StateFlow<Set<String>> = _attempted.asStateFlow()
    @Volatile private var resultsLoaded = false

    private var job: Job? = null
    private val skip = AtomicBoolean(false)
    private val paused = AtomicBoolean(false)

    fun norm(bssid: String): String = bssid.lowercase().replace(":", "").replace("-", "")

    private fun runningKey(): String? = (_state.value as? CrackState.Running)?.let { norm(it.bssid) }

    /** Queue [capture] for cracking (dedup vs cracked/running/queued); start the processor if idle. */
    fun enqueue(context: Context, capture: CaptureEntry) {
        val hash = capture.hash22000 ?: return
        if (!WpaCracker.isOnPhoneCrackable(hash)) return
        loadResults(context.applicationContext)
        val key = norm(capture.bssid)
        if (_cracked.value.containsKey(key)) return          // already cracked
        if (_exhausted.value.contains(key)) return           // already fully searched, no match
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

    /** Forget a network's crack result + checkpoint, making it crackable again (e.g. for testing). */
    fun forget(context: Context, bssid: String) {
        val key = norm(bssid)
        _cracked.update { it - key }
        _exhausted.update { it - key }
        _attempted.update { it - key }
        clearCheckpoint(context.applicationContext, key)
        context.applicationContext.getSharedPreferences(RESULTS_PREFS, Context.MODE_PRIVATE)
            .edit().remove(key).apply()
    }

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

    private const val QUICK_LIMIT = 25_000L   // quick-pass tries only the most-likely top-N
    private const val BATCH = 256             // candidates per native JNI call / Kotlin chunk

    /** Crack [capture]; returns true if it's consumed (advance the queue), false if paused mid-run. */
    private suspend fun crackOne(context: Context, capture: CaptureEntry): Boolean {
        skip.set(false)
        paused.set(false)
        val ssid = capture.ssid.ifBlank { capture.bssid.ifBlank { "network" } }
        val bssid = capture.bssid
        val key = norm(bssid)
        // The capture is either a PMKID (WPA*01) or an EAPOL handshake (WPA*02). Parse whichever it
        // is; both share the wordlist loop below, differing only in how a candidate is verified.
        val line = capture.hash22000 ?: ""
        val pmkidH = WpaCracker.parsePmkid(line)
        val eapolH = if (pmkidH == null) WpaCracker.parseEapol(line) else null
        if (pmkidH == null && eapolH == null) {
            _state.value = CrackState.Failed(bssid, ssid, "bad hash"); return true
        }
        persistResultAttempted(context.applicationContext, key)   // flag "tried" from the first run
        // Per-flavour verify + native-batch closures (chosen once, reused by every worker).
        val verifyOne: (String) -> Boolean =
            if (pmkidH != null) { c -> WpaCracker.verify(pmkidH, c) }
            else { c -> WpaCracker.verifyEapol(eapolH!!, c) }
        val nativeBatch: (Array<String>) -> Int =
            if (pmkidH != null) { arr ->
                NativeWpaCracker.crackBatch(pmkidH.essid, pmkidH.macAp, pmkidH.macSta, pmkidH.pmkid, 4096, arr)
            } else { arr ->
                eapolH!!.let {
                    NativeWpaCracker.crackBatchEapol(it.essid, it.macAp, it.macSta, it.mic, it.anonce, it.eapol, 4096, arr)
                }
            }
        val words = WordlistManager.words()
        val cores = workerCores(context)
        val quick = CrackSettings.quickCrack.value
        val mangle = CrackSettings.mangle.value
        val mult = if (mangle) MangleRules.size else 1
        // Candidate space: quick uses only the top-N words; full uses all. Mangling expands each
        // word into MangleRules.size variants, so the space (and the progress total that drives the
        // ETA) is wordCount × mult.
        val wordCount = if (quick) minOf(QUICK_LIMIT, words.size.toLong()) else words.size.toLong()
        val limit = wordCount * mult

        // Resume (full only): the checkpoint is tagged with the wordlist + mangle factor, so toggling
        // mangle (which changes what each index means) invalidates a stale checkpoint instead of
        // resuming into the wrong candidate. One monotonic cursor → everything below is done.
        val wordlistId = WordlistManager.identity() + if (mangle) "+m$mult" else ""
        val startIndex = if (quick) 0L else loadCheckpoint(context, key, wordlistId).coerceIn(0L, limit)
        val cursor = AtomicLong(startIndex)
        val tried = AtomicLong(startIndex)
        val found = AtomicReference<String?>(null)
        val startMs = System.currentTimeMillis()
        val useNative = NativeWpaCracker.available &&
            (if (pmkidH != null) NativeWpaCracker.verified else NativeWpaCracker.eapolVerified)
        val inflight = BATCH.toLong() * cores
        // Map a flat candidate index to its passphrase: word = idx / mult, rule = idx % mult.
        // With mangle off, mult == 1 so this is just words[idx] — no per-candidate allocation cost.
        fun candidateAt(idx: Long): String {
            val w = words[(idx / mult).toInt()]
            return if (mangle) MangleRules.apply(w, (idx % mult).toInt()) else w
        }
        // Human-readable "what · how · which options" for the progress banner, e.g.
        // "eapol · native · mangle". quick/mangle are locked in for this run, so snapshot them here.
        val mode = buildString {
            append(if (pmkidH != null) "pmkid" else "eapol")
            append(" · ").append(if (useNative) "native" else "cpu")
            if (quick) append(" · quick")
            if (mangle) append(" · mangle")
        }
        _state.value = CrackState.Running(bssid, ssid, startIndex, limit, 0, mode)
        if (!quick && startIndex > 0) Log.i(TAG, "resuming $ssid from $startIndex/$limit")
        Log.i(TAG, "cracking $ssid: ${if (quick) "quick" else "full"}, $cores workers, " +
                "${if (useNative) "native" else "kotlin"}, $limit candidates")
        coroutineScope {
            // Ticker on Main so the CPU-bound workers (which never suspend) can't starve it off
            // the Default pool — that would freeze the progress bar even as cracking proceeds.
            val ticker = launch(Dispatchers.Main) {
                while (isActive && found.get() == null && !skip.get() && !paused.get()) {
                    if (blockReason(context) != null) { paused.set(true); break }
                    val n = tried.get()
                    val secs = (System.currentTimeMillis() - startMs) / 1000.0
                    val done = (n - startIndex).coerceAtLeast(0)
                    val ps = if (secs > 0.5) (done / secs).toLong() else 0L
                    _state.value = CrackState.Running(bssid, ssid, n, limit, ps, mode)
                    if (!quick) saveCheckpoint(context, key, wordlistId, (cursor.get() - inflight).coerceAtLeast(0))
                    delay(400)
                }
            }
            // Workers pull a batch of indices from the shared cursor and verify — one native JNI
            // call per batch when the lib is present + self-checked, else the pure-Kotlin path.
            (0 until cores).map {
                launch {
                    while (found.get() == null && !skip.get() && !paused.get() && isActive) {
                        val start = cursor.getAndAdd(BATCH.toLong())
                        if (start >= limit) return@launch
                        val end = minOf(start + BATCH, limit)
                        if (useNative) {
                            // Build the batch of candidates for these indices, dropping any that fall
                            // outside WPA's 8..63-char passphrase range (mangling can push a word past
                            // 63). Indices still advance for all of them so progress/checkpoint stay
                            // aligned to the flat candidate space.
                            val batch = ArrayList<String>(BATCH)
                            var i = start
                            while (i < end) { val c = candidateAt(i); if (c.length in 8..63) batch.add(c); i++ }
                            tried.addAndGet(end - start)
                            if (batch.isNotEmpty()) {
                                val arr = batch.toTypedArray()
                                val hit = nativeBatch(arr)
                                if (hit >= 0) { found.set(arr[hit]); return@launch }
                            }
                        } else {
                            var i = start
                            while (i < end) {
                                if (found.get() != null || skip.get() || paused.get() || !isActive) return@launch
                                val cand = candidateAt(i)
                                if (cand.length in 8..63 && verifyOne(cand)) { found.set(cand); return@launch }
                                tried.incrementAndGet()
                                i++
                            }
                        }
                    }
                }
            }.forEach { it.join() }
            ticker.cancel()
        }
        if (!quick) saveCheckpoint(context, key, wordlistId, (cursor.get() - inflight).coerceAtLeast(0))
        val pw = found.get()
        val queueLeft = _queue.value.size
        return when {
            pw != null -> {
                clearCheckpoint(context, key)
                _cracked.update { it + (key to pw) }
                persistResultCracked(context, key, pw)   // survives app restart
                _state.value = CrackState.Done(bssid, ssid, pw)
                runCatching { NotificationHelper.notifyCracked(context, ssid, pw) }
                Log.i(TAG, "on-phone crack SUCCESS: $ssid -> $pw")
                if (queueLeft > 0) delay(1500)   // let the result show before the next one starts
                true
            }
            paused.get() -> false   // power policy paused us; retry the same network on resume
            skip.get() -> {
                _state.value = CrackState.Failed(bssid, ssid, "skipped")
                if (queueLeft > 0) delay(600)
                true
            }
            quick -> {
                // A quick miss only means "not in the top-$limit" — a full crack may still find it,
                // so DON'T mark it exhausted; leave the row crackable.
                _state.value = CrackState.Failed(bssid, ssid, "not in quick set ($limit)")
                if (queueLeft > 0) delay(800)
                true
            }
            else -> {
                // Whole list searched, no hit — lasting "no match" so it isn't re-offered.
                clearCheckpoint(context, key)
                persistResultExhausted(context, key)
                _state.value = CrackState.Failed(bssid, ssid, "not in wordlist ($limit tried)")
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

    // ── Persisted crack results ─────────────────────────────────────────────────
    // Outcomes survive app restart / process death so a finished crack keeps its status:
    // "c:<password>" for a hit (also re-overlaid onto captures), "x" for a fully-searched miss.
    private const val RESULTS_PREFS = "crack_results"

    /** Load persisted crack outcomes into memory (cracked passwords + "no match" set). Idempotent. */
    fun loadResults(context: Context) {
        if (resultsLoaded) return
        resultsLoaded = true
        runCatching {
            val all = context.getSharedPreferences(RESULTS_PREFS, Context.MODE_PRIVATE).all
            val crackedNow = HashMap<String, String>()
            val exh = HashSet<String>()
            val att = HashSet<String>()
            for ((k, v) in all) {
                val s = v as? String ?: continue
                when {
                    s.startsWith("c:") -> crackedNow[k] = s.substring(2)
                    s == "x" -> exh.add(k)
                    s == "a" -> att.add(k)
                }
            }
            if (crackedNow.isNotEmpty()) _cracked.update { crackedNow + it }
            if (exh.isNotEmpty()) _exhausted.value = exh
            if (att.isNotEmpty()) _attempted.value = att
        }
    }

    private fun persistResultCracked(context: Context, bssidKey: String, password: String) {
        context.getSharedPreferences(RESULTS_PREFS, Context.MODE_PRIVATE)
            .edit().putString(bssidKey, "c:$password").apply()
        _attempted.update { it - bssidKey }   // cracked supersedes "tried"
    }

    private fun persistResultExhausted(context: Context, bssidKey: String) {
        context.getSharedPreferences(RESULTS_PREFS, Context.MODE_PRIVATE)
            .edit().putString(bssidKey, "x").apply()
        _exhausted.update { it + bssidKey }
        _attempted.update { it - bssidKey }   // "no match" supersedes "tried"
    }

    /** Mark a network as attempted (started at least once) unless it's already cracked/exhausted. */
    private fun persistResultAttempted(context: Context, bssidKey: String) {
        if (_exhausted.value.contains(bssidKey) || _cracked.value.containsKey(bssidKey)) return
        context.getSharedPreferences(RESULTS_PREFS, Context.MODE_PRIVATE)
            .edit().putString(bssidKey, "a").apply()
        _attempted.update { it + bssidKey }
    }

    /** Worker count, honouring the gentle knobs: cap at 2 in easy-CPU mode, else half on battery
     * and all-but-one while plugged in. */
    private fun workerCores(context: Context): Int {
        val cpus = Runtime.getRuntime().availableProcessors()
        if (CrackSettings.gentleCpu.value) return 2.coerceIn(1, cpus)
        return if (isPlugged(context)) (cpus - 1).coerceIn(1, 8) else (cpus / 2).coerceIn(1, 8)
    }

    /**
     * "On a charger" = plugged in (AC/USB/wireless), NOT "battery actively charging". Many phones
     * report status != CHARGING while plugged (topped off, or throttled under a heavy CPU load),
     * so BatteryManager.isCharging would wrongly say "waiting for charger" on a charger. We read
     * the sticky battery broadcast's plugged flag instead.
     */
    private fun isPlugged(context: Context): Boolean = runCatching {
        val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        (intent?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0) != 0
    }.getOrDefault(false)

    private fun batteryLevel(context: Context): Int = runCatching {
        (context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager)
            .getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
    }.getOrDefault(100)

    /** Why cracking is currently blocked by the power policy, or null if it may run. */
    private fun blockReason(context: Context): String? {
        CrackSettings.ensureLoaded(context)
        if (isPlugged(context)) return null   // on a charger → nothing to hold for
        if (CrackSettings.chargerOnly.value) return "waiting for charger"
        if (CrackSettings.lowBatteryStop.value) {
            val level = batteryLevel(context)
            if (level in 0..CrackSettings.LOW_PCT) return "battery $level% - paused"
        }
        return null
    }

    private fun startService(context: Context) {
        val intent = Intent(context, CrackService::class.java)
        // The crack itself runs in `scope`, independent of this service — the service only lets it
        // survive lock/backgrounding. Starting a foreground service can be refused by the OS
        // (ForegroundServiceStartNotAllowedException on Android 12+), most often when no other FGS
        // is already running — e.g. cracking an offline capture with no Pi link. Swallow it and crack
        // anyway (best-effort: works while the app is open) instead of crashing.
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(intent)
            else context.startService(intent)
        } catch (e: Exception) {
            Log.w(TAG, "crack foreground service start refused: ${e.message}")
        }
    }

    private fun stopService(context: Context) {
        runCatching { context.stopService(Intent(context, CrackService::class.java)) }
    }
}
