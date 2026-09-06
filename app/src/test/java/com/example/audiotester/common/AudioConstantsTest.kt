package com.example.audiotester.common

import android.media.AudioManager
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Baseline lock for the focus-type policy: exact keys over the closed Usage.MAP key set.
 * The former contains("NAVIGATION")/contains("VOICE_COMMUNICATION") heuristic produced
 * exactly this mapping — the table must not drift from it, and a future Usage.MAP entry
 * must make its focus type explicit rather than inherit one from a string fragment.
 */
class AudioConstantsTest {

    @Test
    fun focusType_matchesTheFormerSubstringHeuristic() {
        assertEquals(
            AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK,
            AudioConstants.getFocusType("USAGE_ASSISTANCE_NAVIGATION_GUIDANCE"),
        )
        assertEquals(
            AudioManager.AUDIOFOCUS_GAIN_TRANSIENT,
            AudioConstants.getFocusType("USAGE_VOICE_COMMUNICATION"),
        )
        assertEquals(
            AudioManager.AUDIOFOCUS_GAIN_TRANSIENT,
            AudioConstants.getFocusType("USAGE_VOICE_COMMUNICATION_SIGNALLING"),
        )
    }

    /** Every remaining Usage.MAP key gets AUDIOFOCUS_GAIN — the old heuristic's else branch */
    @Test
    fun focusType_defaultsToGainForAllOtherUsages() {
        val special = setOf(
            "USAGE_ASSISTANCE_NAVIGATION_GUIDANCE",
            "USAGE_VOICE_COMMUNICATION",
            "USAGE_VOICE_COMMUNICATION_SIGNALLING",
        )
        AudioConstants.Usage.MAP.keys.filter { it !in special }.forEach { usage ->
            assertEquals(
                "AUDIOFOCUS_GAIN expected for $usage",
                AudioManager.AUDIOFOCUS_GAIN,
                AudioConstants.getFocusType(usage),
            )
        }
    }
}
