package com.example.audiotester.common

import android.content.Context
import android.util.Log
import org.json.JSONObject

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
                val json = ConfigLoader.loadRawText(
                    context, AudioConstants.CONFIG_FILE_PATH, AudioConstants.ASSETS_CONFIG_FILE
                )
                loadConfigsFromRaw(json, section)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load $section configurations", e)
                getDefaultConfigs(section)
            }
        }

        /** 解析失败 → 兜底默认（纯函数，可 JVM 测试） */
        internal fun loadConfigsFromRaw(json: String, section: String): List<AudioConfig> =
            try {
                parseConfigs(json, section)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to parse $section configurations", e)
                getDefaultConfigs(section)
            }

        internal fun parseConfigs(json: String, section: String): List<AudioConfig> {
            val configsArray = JSONObject(json).getJSONArray(section)
            // 单条坏配置只跳过该条，不拖垮整个 section 回退 emergency
            return (0 until configsArray.length()).mapNotNull { i ->
                runCatching {
                    val c = configsArray.getJSONObject(i)
                    AudioConfig(
                        usage = c.optString("usage", "USAGE_MEDIA"),
                        contentType = c.optString("contentType", "CONTENT_TYPE_MUSIC"),
                        performanceMode = c.optString("performanceMode", "PERFORMANCE_MODE_POWER_SAVING"),
                        audioSource = c.optString("audioSource", "MIC"),
                        sampleRate = c.optInt("sampleRate", 48000),
                        channelCount = c.optInt("channelCount", 2),
                        audioFormat = c.optInt("audioFormat", 16),
                        bufferMultiplier = c.optInt("bufferMultiplier", 2),
                        audioFilePath = c.optString("audioFilePath", ""),
                        description = c.optString("description", "Custom configuration")
                    )
                }.onFailure {
                    Log.e(TAG, "Skipping invalid $section config entry #$i", it)
                }.getOrNull()
            }
        }

        internal fun getDefaultConfigs(section: String): List<AudioConfig> = when (section) {
            AudioConstants.PLAYER_SECTION -> listOf(
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
