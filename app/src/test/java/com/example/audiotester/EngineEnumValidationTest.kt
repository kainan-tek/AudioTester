package com.example.audiotester

import android.content.Context
import com.example.audiotester.common.AudioConfig
import com.example.audiotester.common.AudioEngine
import com.example.audiotester.common.AudioErrorType
import com.example.audiotester.player.AudioPlayer
import com.example.audiotester.recorder.AudioRecorder
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.Mockito

/**
 * Unknown enum strings in the config (typos in usage/contentType/performanceMode/audioSource)
 * must fail the start with a [PARAM] error naming the typo. The old fallback silently played
 * / recorded with the default constant instead — a test tool must not look successful while
 * measuring the wrong attributes.
 */
class EngineEnumValidationTest {

    private class CapturingListener : AudioEngine.Listener {
        val errors = mutableListOf<Pair<AudioErrorType, String>>()
        override fun onStarted() {}
        override fun onStopped() {}
        override fun onError(type: AudioErrorType, detail: String) { errors += type to detail }
    }

    private fun assertParamError(listener: CapturingListener, typo: String) {
        assertFalse(listener.errors.isEmpty())
        assertTrue(
            "expected a PARAM error naming the typo '$typo', got: ${listener.errors}",
            listener.errors.any { it.first == AudioErrorType.PARAM && typo in it.second }
        )
    }

    private fun playerFailsWithParamError(config: AudioConfig, typo: String) {
        val listener = CapturingListener()
        val player = AudioPlayer(Mockito.mock(Context::class.java))
        player.setListener(listener)
        player.setAudioConfig(config)

        assertFalse(player.start())
        assertParamError(listener, typo)
    }

    @Test
    fun player_unknownUsage_failsWithParamError() =
        playerFailsWithParamError(AudioConfig(usage = "USAGE_GME"), "USAGE_GME")

    @Test
    fun player_unknownContentType_failsWithParamError() =
        playerFailsWithParamError(AudioConfig(contentType = "CONTENT_TYPE_MUSIK"), "CONTENT_TYPE_MUSIK")

    @Test
    fun player_unknownPerformanceMode_failsWithParamError() =
        playerFailsWithParamError(AudioConfig(performanceMode = "PERFORMANCE_MODE_LOWLATENCY"), "PERFORMANCE_MODE_LOWLATENCY")

    @Test
    fun recorder_unknownAudioSource_failsWithParamError() {
        val listener = CapturingListener()
        val recorder = AudioRecorder(Mockito.mock(Context::class.java))
        recorder.setListener(listener)
        recorder.setAudioConfig(AudioConfig(audioSource = "MICC"))

        assertFalse(recorder.start())
        assertParamError(listener, "MICC")
    }
}
