package com.example.audiotester.common

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioConfigTest {

    private val json = """
        {
          // 播放配置
          "player": [
            { "usage": "USAGE_MEDIA", "contentType": "CONTENT_TYPE_MUSIC",
              "transferMode": "MODE_STREAM", "performanceMode": "PERFORMANCE_MODE_POWER_SAVING",
              "bufferMultiplier": 2, "audioFilePath": "asset://sample/48k_2ch_16bit.wav",
              "description": "Media Playback" }
          ],
          // 录音配置
          "recorder": [
            { "audioSource": "MIC", "sampleRate": 48000, "channelCount": 2, "audioFormat": 16,
              "bufferMultiplier": 2, "description": "Microphone Recording" }
          ]
        }
    """.trimIndent()

    @Test
    fun parseConfigs_playerSection() {
        val configs = AudioConfig.parseConfigs(ConfigLoader.stripComments(json), "player")
        assertEquals(1, configs.size)
        assertEquals("USAGE_MEDIA", configs[0].usage)
        assertEquals("asset://sample/48k_2ch_16bit.wav", configs[0].audioFilePath)
        assertEquals("Media Playback", configs[0].description)
    }

    @Test
    fun parseConfigs_recorderSection() {
        val configs = AudioConfig.parseConfigs(ConfigLoader.stripComments(json), "recorder")
        assertEquals(1, configs.size)
        assertEquals("MIC", configs[0].audioSource)
        assertEquals(48000, configs[0].sampleRate)
        assertEquals("", configs[0].audioFilePath)  // 省略 → 空 → 引擎自动生成路径
    }

    @Test
    fun sectionsAreIsolated() {
        val stripped = ConfigLoader.stripComments(json)
        assertTrue(AudioConfig.parseConfigs(stripped, "player").isNotEmpty())
        assertTrue(AudioConfig.parseConfigs(stripped, "recorder").isNotEmpty())
    }

    @Test
    fun defaultConfigs_forBothSections() {
        assertEquals("Emergency Fallback - Media Playback", AudioConfig.getDefaultConfigs("player")[0].description)
        assertEquals("Emergency Fallback - Stereo Recording", AudioConfig.getDefaultConfigs("recorder")[0].description)
    }

    @Test
    fun malformedJson_fallsBackToDefaults() {
        val configs = AudioConfig.loadConfigsFromRaw("this is not json", "player")
        assertEquals(1, configs.size)
        assertEquals("Emergency Fallback - Media Playback", configs[0].description)
    }

    @Test
    fun emptySection_returnsEmptyList() {
        // 空 section 非解析失败：保持空列表（ViewModel 层以 isNotEmpty 兜底提示），与原有行为一致
        val configs = AudioConfig.parseConfigs(ConfigLoader.stripComments("""{ "player": [], "recorder": [] }"""), "player")
        assertTrue(configs.isEmpty())
    }
}
