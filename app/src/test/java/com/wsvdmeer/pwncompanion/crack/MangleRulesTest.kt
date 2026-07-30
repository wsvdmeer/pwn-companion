package com.wsvdmeer.pwncompanion.crack

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Covers the word-mangling rules used to expand the wordlist during cracking. */
class MangleRulesTest {

    @Test fun ruleZeroIsIdentity() {
        assertEquals("hello", MangleRules.apply("hello", 0))
    }

    @Test fun everyRuleIndexProducesOutput() {
        for (i in 0 until MangleRules.size) {
            assertNotNull("rule $i", MangleRules.apply("word", i))
        }
    }

    @Test fun coversTheCommonHumanVariants() {
        val all = (0 until MangleRules.size).map { MangleRules.apply("welkom", it) }
        assertTrue("append 123 (welkom123)", all.contains("welkom123"))
        assertTrue("capitalize (Welkom)", all.contains("Welkom"))
        assertTrue("uppercase (WELKOM)", all.contains("WELKOM"))
        assertTrue("leet e→3/o→0 (w3lk0m)", all.contains("w3lk0m"))
    }
}
