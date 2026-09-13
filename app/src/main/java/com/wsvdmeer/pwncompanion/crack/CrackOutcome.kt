package com.wsvdmeer.pwncompanion.crack

/**
 * The terminal result of one crack run — extracted from the bottom of `CrackEngine.crackOne` so the
 * decision is a pure function of the run's end state, testable without actually running a crack.
 *
 * [CrackEngine] pattern-matches the outcome to fire the side-effects (persist, notify, update state,
 * pace the next run); the branch *choice* — including the load-bearing rule that a quick-pass miss
 * must NOT be marked exhausted (a full crack may still find it) — lives here.
 */
sealed interface CrackOutcome {
    /** Whether the queue should advance past this capture. Only a power-pause holds it back to retry. */
    val consumed: Boolean

    /** A passphrase was found. */
    data class Cracked(val password: String) : CrackOutcome {
        override val consumed get() = true
    }

    /** The power policy paused mid-run — keep the capture and retry it when power returns. */
    data object Paused : CrackOutcome {
        override val consumed get() = false
    }

    /** The user skipped this crack. */
    data object Skipped : CrackOutcome {
        override val consumed get() = true
    }

    /** A quick-pass miss: not in the top-N, but a full crack may still find it — stays crackable. */
    data object QuickMiss : CrackOutcome {
        override val consumed get() = true
    }

    /** The whole candidate space was searched with no hit — a lasting "no match". */
    data object Exhausted : CrackOutcome {
        override val consumed get() = true
    }

    companion object {
        /**
         * Decide the outcome from the run's end state. Priority order is load-bearing:
         * a found passphrase wins over everything; a power-pause outranks skip (so a network paused
         * *and* skipped still resumes); an explicit skip outranks the search verdict; and a quick-pass
         * miss is reported as [QuickMiss], never [Exhausted], so the row stays crackable for a full run.
         */
        fun decide(found: String?, paused: Boolean, skipped: Boolean, quick: Boolean): CrackOutcome =
            when {
                found != null -> Cracked(found)
                paused -> Paused
                skipped -> Skipped
                quick -> QuickMiss
                else -> Exhausted
            }
    }
}
