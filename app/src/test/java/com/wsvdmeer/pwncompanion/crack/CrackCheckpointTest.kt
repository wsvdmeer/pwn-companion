package com.wsvdmeer.pwncompanion.crack

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Covers the resume-checkpoint codec extracted from CrackEngine — specifically the gate that has to
 * hold for correctness: a checkpoint tagged with a different wordlist/mangle fingerprint must be
 * ignored (return 0) so a resume never jumps into the wrong candidate index.
 */
class CrackCheckpointTest {

    @Test fun encodeIsIndexAtWordlistId() {
        assertEquals("0@default:655000", CrackCheckpoint.encode(0, "default:655000"))
        assertEquals("4200@default:655000+m22", CrackCheckpoint.encode(4200, "default:655000+m22"))
    }

    @Test fun roundTripsWhenWordlistMatches() {
        val id = "default:655000+m22+isp3"
        val raw = CrackCheckpoint.encode(123_456, id)
        assertEquals(123_456L, CrackCheckpoint.decode(raw, id))
    }

    @Test fun absentCheckpointIsZero() {
        assertEquals(0L, CrackCheckpoint.decode(null, "default:655000"))
    }

    @Test fun differentWordlistIdIsIgnored() {
        val raw = CrackCheckpoint.encode(500, "default:655000")
        // toggling mangle / swapping starter→full changes the fingerprint → stale, restart from 0
        assertEquals(0L, CrackCheckpoint.decode(raw, "default:655000+m22"))
        assertEquals(0L, CrackCheckpoint.decode(raw, "starter:21000"))
    }

    @Test fun malformedValuesAreZero() {
        assertEquals(0L, CrackCheckpoint.decode("", "id"))
        assertEquals(0L, CrackCheckpoint.decode("noatsign", "id"))
        assertEquals(0L, CrackCheckpoint.decode("@id", "id"))        // empty index
        assertEquals(0L, CrackCheckpoint.decode("notanumber@id", "id"))
    }

    @Test fun splitsOnFirstAtOnlySoWordlistIdCompareIsWhole() {
        // A wordlistId containing '@' still round-trips: everything past the first '@' is the id.
        val id = "weird@id:1"
        assertEquals(7L, CrackCheckpoint.decode("7@$id", id))
        assertEquals(0L, CrackCheckpoint.decode("7@$id", "weird@id:2"))
    }
}
