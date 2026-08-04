package com.wsvdmeer.pwncompanion.crack

import com.wsvdmeer.pwncompanion.models.CaptureEntry

/**
 * Pure FIFO-queue logic for the crack processor: BSSID normalisation, add-if-absent (dedup by
 * normalised BSSID), remove-by-key, and take-first. Kept Context-free so the ordering and dedup
 * invariants are unit-tested without spinning up the engine's coroutine/service; [CrackEngine] holds
 * the actual `StateFlow<List<CaptureEntry>>` and mutates it through these.
 */
internal object CrackQueue {
    /** Canonical BSSID key: lower-case, separators stripped, so `AA:BB` and `aa-bb` collide. */
    fun norm(bssid: String): String = bssid.lowercase().replace(":", "").replace("-", "")

    /** [queue] with [capture] appended, or the same list unchanged if its BSSID is already present. */
    fun add(queue: List<CaptureEntry>, capture: CaptureEntry): List<CaptureEntry> {
        val key = norm(capture.bssid)
        return if (queue.any { norm(it.bssid) == key }) queue else queue + capture
    }

    /** [queue] without any entry matching [bssid] (normalised). */
    fun remove(queue: List<CaptureEntry>, bssid: String): List<CaptureEntry> {
        val key = norm(bssid)
        return queue.filterNot { norm(it.bssid) == key }
    }

    /** The head of [queue] (next to crack), or null when empty. */
    fun head(queue: List<CaptureEntry>): CaptureEntry? = queue.firstOrNull()

    /** [queue] with its head removed (empty stays empty). */
    fun tail(queue: List<CaptureEntry>): List<CaptureEntry> = queue.drop(1)
}
