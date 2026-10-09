package com.example.audiotester.player

import com.example.audiotester.common.AudioConfig
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The info panel mirrors what the engine actually does (the resolvePlaybackSource contract):
 * a configured preferredDeviceAddress must surface as a Route line, and its absence must not
 * add one (every unrouted case keeps the panel unchanged).
 */
class PlayerFragmentInfoTest {

    @Test
    fun routedConfig_showsRouteLine() {
        val text = PlayerFragment.buildInfoText(
            AudioConfig(description = "routed", preferredDeviceAddress = "bus5_alarm_out"))
        assertTrue(text, text.contains("Route: bus5_alarm_out"))
    }

    @Test
    fun unroutedConfig_hasNoRouteLine() {
        val text = PlayerFragment.buildInfoText(AudioConfig(description = "plain"))
        assertFalse(text, text.contains("Route:"))
    }
}
