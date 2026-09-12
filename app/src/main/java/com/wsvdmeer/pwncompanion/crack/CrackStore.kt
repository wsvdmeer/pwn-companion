package com.wsvdmeer.pwncompanion.crack

import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

/**
 * Minimal string key-value persistence seam. Two adapters justify it: [PrefsKeyValueStore] over
 * SharedPreferences in production, and an in-memory fake in tests — so [CrackStore]'s I/O and its
 * supersede precedence can be unit-tested without Android.
 */
interface KeyValueStore {
    fun getString(key: String): String?
    fun put(key: String, value: String)
    fun remove(key: String)
    fun all(): Map<String, String>
}

/** SharedPreferences-backed [KeyValueStore] (the production adapter). */
class PrefsKeyValueStore(private val prefs: SharedPreferences) : KeyValueStore {
    override fun getString(key: String): String? = prefs.getString(key, null)
    override fun put(key: String, value: String) { prefs.edit().putString(key, value).apply() }
    override fun remove(key: String) { prefs.edit().remove(key).apply() }
    override fun all(): Map<String, String> =
        prefs.all.mapNotNull { (k, v) -> (v as? String)?.let { k to it } }.toMap()
}

/**
 * Owns everything the crack pipeline persists: resume [CrackCheckpoint]s and the [CrackResults]
 * outcome per network, across two key-value stores, plus the in-memory result flows the UI observes.
 *
 * This is the one place the supersede precedence lives — cracked and exhausted both clear an
 * "attempted" flag, and a network already cracked/exhausted is never downgraded to "attempted".
 * [CrackEngine] used to smear these rules across eight scattered `SharedPreferences.edit()` sites;
 * now it delegates, and the rules are testable through the [KeyValueStore] seam.
 *
 * The result flows are passed in (owned by [CrackEngine], which builds them at process start before a
 * Context exists) and mutated here.
 */
class CrackStore(
    private val checkpoints: KeyValueStore,
    private val results: KeyValueStore,
    private val cracked: MutableStateFlow<Map<String, String>>,
    private val exhausted: MutableStateFlow<Set<String>>,
    private val attempted: MutableStateFlow<Set<String>>,
) {
    @Volatile private var loaded = false

    /** Load persisted outcomes into the result flows (cracked passwords, exhausted, attempted). Idempotent. */
    fun loadResults() {
        if (loaded) return
        loaded = true
        runCatching {
            val crackedNow = HashMap<String, String>()
            val exh = HashSet<String>()
            val att = HashSet<String>()
            for ((k, v) in results.all()) {
                when (val outcome = CrackResults.parse(v)) {
                    is CrackResults.Outcome.Cracked -> crackedNow[k] = outcome.password
                    CrackResults.Outcome.Exhausted -> exh.add(k)
                    CrackResults.Outcome.Attempted -> att.add(k)
                    null -> {}
                }
            }
            if (crackedNow.isNotEmpty()) cracked.update { crackedNow + it }
            if (exh.isNotEmpty()) exhausted.value = exh
            if (att.isNotEmpty()) attempted.value = att
        }
    }

    // ── Resume checkpoints ──────────────────────────────────────────────────────
    // "how far did we get" per network, tagged with the wordlist id so a checkpoint for a different
    // wordlist is ignored (decoded to 0). The encode/decode is the pure CrackCheckpoint codec.

    fun checkpoint(key: String, wordlistId: String): Long =
        CrackCheckpoint.decode(checkpoints.getString(key), wordlistId)

    fun saveCheckpoint(key: String, wordlistId: String, index: Long) =
        checkpoints.put(key, CrackCheckpoint.encode(index, wordlistId))

    fun clearCheckpoint(key: String) = checkpoints.remove(key)

    // ── Persisted outcomes (with the supersede precedence) ────────────────────────

    /** Record a hit; cracked supersedes "tried". */
    fun markCracked(key: String, password: String) {
        results.put(key, CrackResults.cracked(password))
        cracked.update { it + (key to password) }
        attempted.update { it - key }
    }

    /** Record a fully-searched miss; "no match" supersedes "tried". */
    fun markExhausted(key: String) {
        results.put(key, CrackResults.EXHAUSTED)
        exhausted.update { it + key }
        attempted.update { it - key }
    }

    /** Flag "started at least once" — but never downgrade a network already cracked or exhausted. */
    fun markAttempted(key: String) {
        if (exhausted.value.contains(key) || cracked.value.containsKey(key)) return
        results.put(key, CrackResults.ATTEMPTED)
        attempted.update { it + key }
    }

    /** Forget a network entirely (result + checkpoint), making it crackable again. */
    fun forget(key: String) {
        cracked.update { it - key }
        exhausted.update { it - key }
        attempted.update { it - key }
        clearCheckpoint(key)
        results.remove(key)
    }
}
