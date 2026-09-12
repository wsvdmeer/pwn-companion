package com.wsvdmeer.pwncompanion.crack

import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Covers CrackStore's persistence + supersede precedence through the [KeyValueStore] seam — the
 * rules that were smeared across eight `SharedPreferences.edit()` sites in CrackEngine and had no
 * test. An in-memory fake stands in for SharedPreferences.
 */
class CrackStoreTest {
    /** In-memory [KeyValueStore] — the test adapter opposite [PrefsKeyValueStore]. */
    private class FakeStore(val map: MutableMap<String, String> = mutableMapOf()) : KeyValueStore {
        override fun getString(key: String) = map[key]
        override fun put(key: String, value: String) { map[key] = value }
        override fun remove(key: String) { map.remove(key) }
        override fun all(): Map<String, String> = map.toMap()
    }

    private val cracked = MutableStateFlow<Map<String, String>>(emptyMap())
    private val exhausted = MutableStateFlow<Set<String>>(emptySet())
    private val attempted = MutableStateFlow<Set<String>>(emptySet())
    private val checkpoints = FakeStore()
    private val results = FakeStore()
    private val store = CrackStore(checkpoints, results, cracked, exhausted, attempted)

    @Test fun markCracked_persists_updatesFlow_andSupersedesAttempted() {
        store.markAttempted("aa")
        assertTrue("aa" in attempted.value)
        store.markCracked("aa", "hunter2")
        assertEquals("hunter2", cracked.value["aa"])
        assertFalse("cracked supersedes attempted", "aa" in attempted.value)
        assertEquals(CrackResults.cracked("hunter2"), results.map["aa"])
    }

    @Test fun markExhausted_persists_updatesFlow_andSupersedesAttempted() {
        store.markAttempted("bb")
        store.markExhausted("bb")
        assertTrue("bb" in exhausted.value)
        assertFalse("exhausted supersedes attempted", "bb" in attempted.value)
        assertEquals(CrackResults.EXHAUSTED, results.map["bb"])
    }

    @Test fun markAttempted_isNoOp_whenAlreadyCrackedOrExhausted() {
        store.markCracked("cc", "pw")
        store.markAttempted("cc")
        assertFalse("cc" in attempted.value)          // not downgraded

        store.markExhausted("dd")
        store.markAttempted("dd")
        assertFalse("dd" in attempted.value)
    }

    @Test fun forget_clearsResult_checkpoint_andAllFlows() {
        store.markCracked("ee", "pw")
        store.saveCheckpoint("ee", "wl1", 500L)
        store.forget("ee")
        assertFalse("ee" in cracked.value)
        assertEquals(0L, store.checkpoint("ee", "wl1"))   // checkpoint gone
        assertFalse(results.map.containsKey("ee"))
    }

    @Test fun checkpoint_roundTrips_butIsIgnoredForADifferentWordlist() {
        store.saveCheckpoint("ff", "wl1", 1234L)
        assertEquals(1234L, store.checkpoint("ff", "wl1"))
        assertEquals("a stale checkpoint for another wordlist is ignored", 0L, store.checkpoint("ff", "wl2"))
    }

    @Test fun loadResults_parsesMixedRecordsIntoFlows_andIsIdempotent() {
        results.map["c1"] = CrackResults.cracked("secret")
        results.map["x1"] = CrackResults.EXHAUSTED
        results.map["a1"] = CrackResults.ATTEMPTED
        store.loadResults()
        assertEquals("secret", cracked.value["c1"])
        assertTrue("x1" in exhausted.value)
        assertTrue("a1" in attempted.value)

        // Second load must not throw or double-apply.
        results.map["c2"] = CrackResults.cracked("late")
        store.loadResults()
        assertFalse("load is idempotent — later writes aren't re-read", "c2" in cracked.value)
    }
}
