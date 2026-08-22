package com.example.audiotester.common

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File

class AudioConfigTest {

    private val xml = """
        <!-- 播放/录音配置（XML 原生注释） -->
        <audioConfigs>
          <player>
            <config>
              <usage>USAGE_MEDIA</usage>
              <contentType>CONTENT_TYPE_MUSIC</contentType>
              <performanceMode>PERFORMANCE_MODE_POWER_SAVING</performanceMode>
              <bufferMultiplier>2</bufferMultiplier>
              <audioFilePath>asset://sample/48k_2ch_16bit.wav</audioFilePath>
              <description>Media Playback</description>
            </config>
          </player>
          <recorder>
            <config>
              <audioSource>MIC</audioSource>
              <sampleRate>48000</sampleRate>
              <channelCount>2</channelCount>
              <audioFormat>16</audioFormat>
              <bufferMultiplier>2</bufferMultiplier>
              <description>Microphone Recording</description>
            </config>
          </recorder>
        </audioConfigs>
    """.trimIndent()

    private fun stream(text: String) = ByteArrayInputStream(text.toByteArray())

    @Test
    fun parseConfigs_playerSection() {
        val configs = AudioConfig.parseConfigs(stream(xml), "player")
        assertEquals(1, configs.size)
        assertEquals("USAGE_MEDIA", configs[0].usage)
        assertEquals("asset://sample/48k_2ch_16bit.wav", configs[0].audioFilePath)
        assertEquals("Media Playback", configs[0].description)
    }

    @Test
    fun parseConfigs_recorderSection() {
        val configs = AudioConfig.parseConfigs(stream(xml), "recorder")
        assertEquals(1, configs.size)
        assertEquals("MIC", configs[0].audioSource)
        assertEquals(48000, configs[0].sampleRate)
        assertEquals("", configs[0].audioFilePath)  // 省略 → 空 → 引擎自动生成路径
    }

    @Test
    fun sectionsAreIsolated() {
        assertTrue(AudioConfig.parseConfigs(stream(xml), "player").isNotEmpty())
        assertTrue(AudioConfig.parseConfigs(stream(xml), "recorder").isNotEmpty())
    }

    @Test
    fun defaultConfigs_forBothSections() {
        assertEquals("Emergency Fallback - Media Playback", AudioConfig.getDefaultConfigs("player")[0].description)
        assertEquals("Emergency Fallback - Stereo Recording", AudioConfig.getDefaultConfigs("recorder")[0].description)
    }

    @Test
    fun malformedXml_fallsBackToDefaults() {
        val configs = AudioConfig.loadConfigsFromRaw(stream("this is not xml"), "player")
        assertEquals(1, configs.size)
        assertEquals("Emergency Fallback - Media Playback", configs[0].description)
    }

    @Test
    fun missingSection_fallsBackToDefaults() {
        val configs = AudioConfig.loadConfigsFromRaw(
            stream("""<audioConfigs><player/></audioConfigs>"""), "recorder"
        )
        assertEquals(1, configs.size)
        assertEquals("Emergency Fallback - Stereo Recording", configs[0].description)
    }

    @Test
    fun emptySection_returnsEmptyList() {
        // 空 section 非解析失败：保持空列表（ViewModel 层以 isNotEmpty 兜底提示），与原有行为一致
        val configs = AudioConfig.parseConfigs(
            stream("""<audioConfigs><player/><recorder/></audioConfigs>"""), "player"
        )
        assertTrue(configs.isEmpty())
    }

    @Test
    fun missingFields_useDefaults() {
        val configs = AudioConfig.parseConfigs(
            stream("""<audioConfigs><player><config><description>only</description></config></player></audioConfigs>"""),
            "player"
        )
        assertEquals(1, configs.size)
        assertEquals("USAGE_MEDIA", configs[0].usage)
        assertEquals(48000, configs[0].sampleRate)
        assertEquals("", configs[0].audioFilePath)
        assertEquals("only", configs[0].description)
    }

    @Test
    fun invalidNumberValue_fallsBackToDefault() {
        val configs = AudioConfig.parseConfigs(
            stream("""<audioConfigs><player><config><sampleRate>abc</sampleRate></config></player></audioConfigs>"""),
            "player"
        )
        assertEquals(48000, configs[0].sampleRate)
    }

    @Test
    fun realAssetsFile_parsesBothSections() {
        // 直接解析源码树中的真实资产，防 XML 笔误只在上设备后才暴露
        val file = File("src/main/assets/audio_configs.xml")
        val player = file.inputStream().use { AudioConfig.parseConfigs(it, "player") }
        val recorder = file.inputStream().use { AudioConfig.parseConfigs(it, "recorder") }
        assertEquals(12, player.size)
        assertEquals(15, recorder.size)
        assertEquals("USAGE_MEDIA", player[0].usage)
    }

    @Test
    fun invalidBufferMultiplier_skipsOnlyThatEntry() {
        // require(bufferMultiplier > 0) 抛出 → runCatching 只跳过该条，不拖垮整个 section
        val configs = AudioConfig.parseConfigs(
            stream("""
                <audioConfigs>
                  <player>
                    <config><bufferMultiplier>0</bufferMultiplier><description>bad</description></config>
                    <config><description>ok</description></config>
                  </player>
                </audioConfigs>
            """.trimIndent()), "player"
        )
        assertEquals(1, configs.size)
        assertEquals("ok", configs[0].description)
    }
}
