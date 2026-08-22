package com.example.audiotester.common

import android.content.Context
import android.util.Log
import org.w3c.dom.Element
import java.io.File
import java.io.InputStream
import javax.xml.parsers.DocumentBuilderFactory

/**
 * 统一音频配置（播放域 + 录音域字段超集）。
 * audioFilePath 空值由引擎解释：播放→内置音源；录音→自动生成输出路径。
 */
data class AudioConfig(
    val usage: String = "USAGE_MEDIA",
    val contentType: String = "CONTENT_TYPE_MUSIC",
    val performanceMode: String = "PERFORMANCE_MODE_POWER_SAVING",
    val audioSource: String = "MIC",
    val sampleRate: Int = 48000,
    val channelCount: Int = 2,
    val audioFormat: Int = 16,
    val bufferMultiplier: Int = 2,
    val audioFilePath: String = "",
    val description: String = "Default Configuration",
) {
    init {
        require(bufferMultiplier > 0) { "Buffer multiplier must be positive: $bufferMultiplier" }
    }

    companion object {
        private const val TAG = "AudioConfig"

        fun loadConfigs(context: Context, section: String): List<AudioConfig> {
            return try {
                ConfigLoader.loadStream(
                    context, AudioConstants.CONFIG_FILE_PATH, AudioConstants.ASSETS_CONFIG_FILE
                ).use { loadConfigsFromRaw(it, section) }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load $section configurations", e)
                getDefaultConfigs(section)
            }
        }

        /** 解析失败 → 兜底默认（纯 JVM 流输入，可单测） */
        internal fun loadConfigsFromRaw(xml: InputStream, section: String): List<AudioConfig> =
            try {
                parseConfigs(xml, section)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to parse $section configurations", e)
                getDefaultConfigs(section)
            }

        /** section 缺失视为解析失败（由 loadConfigsFromRaw 兜底）；空 section 返回空列表 */
        internal fun parseConfigs(xml: InputStream, section: String): List<AudioConfig> {
            val sectionElement = DocumentBuilderFactory.newInstance().newDocumentBuilder()
                .parse(xml).documentElement.getElementsByTagName(section).item(0) as Element?
                ?: throw IllegalArgumentException("Missing section: $section")
            val entries = sectionElement.getElementsByTagName("config")
            // 单条坏配置只跳过该条，不拖垮整个 section 回退 emergency
            return (0 until entries.length).mapNotNull { i ->
                runCatching {
                    val c = entries.item(i) as Element
                    AudioConfig(
                        usage = c.childText("usage", "USAGE_MEDIA"),
                        contentType = c.childText("contentType", "CONTENT_TYPE_MUSIC"),
                        performanceMode = c.childText("performanceMode", "PERFORMANCE_MODE_POWER_SAVING"),
                        audioSource = c.childText("audioSource", "MIC"),
                        sampleRate = c.childInt("sampleRate", 48000),
                        channelCount = c.childInt("channelCount", 2),
                        audioFormat = c.childInt("audioFormat", 16),
                        bufferMultiplier = c.childInt("bufferMultiplier", 2),
                        audioFilePath = c.childText("audioFilePath", ""),
                        description = c.childText("description", "Custom configuration")
                    )
                }.onFailure {
                    Log.e(TAG, "Skipping invalid $section config entry #$i", it)
                }.getOrNull()
            }
        }

        internal fun getDefaultConfigs(section: String): List<AudioConfig> = when (section) {
            "player" -> listOf(
                AudioConfig(
                    usage = "USAGE_MEDIA", contentType = "CONTENT_TYPE_MUSIC",
                    performanceMode = "PERFORMANCE_MODE_POWER_SAVING",
                    bufferMultiplier = 2, description = "Emergency Fallback - Media Playback"
                )
            )
            else -> listOf(
                AudioConfig(
                    audioSource = "MIC", sampleRate = 48000, channelCount = 2, audioFormat = 16,
                    bufferMultiplier = 2, description = "Emergency Fallback - Stereo Recording"
                )
            )
        }
    }
}

/**
 * XML 配置流加载器：外部 /data 文件优先，否则读 assets（XML 原生支持注释，无需预处理）。
 */
object ConfigLoader {
    private const val TAG = "ConfigLoader"

    fun loadStream(context: Context, externalPath: String, assetName: String): InputStream {
        val externalFile = File(externalPath)
        return if (externalFile.exists()) {
            Log.i(TAG, "Loading configuration from external file")
            externalFile.inputStream()
        } else {
            Log.i(TAG, "Loading configuration from assets")
            context.assets.open(assetName)
        }
    }
}

/** 子元素文本读取：元素缺失 → 默认值 */
private fun Element.childText(name: String, default: String): String =
    getElementsByTagName(name).item(0)?.textContent?.trim() ?: default

private fun Element.childInt(name: String, default: Int): Int =
    getElementsByTagName(name).item(0)?.textContent?.trim()?.toIntOrNull() ?: default
