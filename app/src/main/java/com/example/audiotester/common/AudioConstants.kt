package com.example.audiotester.common

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.MediaRecorder

/**
 * 音频常量（播放域 + 录音域合一）
 */
object AudioConstants {

    // 合并配置文件（播放与录音共用单个文件，双 section："player" / "recorder"）
    const val CONFIG_FILE_PATH = "/data/audio_configs.xml"
    const val ASSETS_CONFIG_FILE = "audio_configs.xml"
    const val DEFAULT_AUDIO_FILE = "asset://sample/48k_2ch_16bit.wav"

    /**
     * 错误前缀
     */
    object ErrorTypes {
        const val FILE = "[FILE]"
        const val STREAM = "[STREAM]"
        const val PERMISSION = "[PERMISSION]"
        const val PARAM = "[PARAM]"
        const val FOCUS = "[FOCUS]"
    }

    // ===== 播放域 =====

    /** AudioTrack usage 常量映射 */
    object Usage {
        // ---- AAOS 专属 usage（1000-1004）已停用 ----
        // 原因：AudioTrack 的公开 AudioAttributes.Builder.setUsage() 对 @hide 值（1000-1004）
        // 一律抛 IllegalArgumentException——即使 priv-app 系统部署 + MODIFY_AUDIO_ROUTING 也无效，
        // 拒绝发生在 Java 公开 API 的值校验层，与权限无关。须经隐藏 API 反射修改 AudioAttributes
        // 私有字段 mUsage 才能使用，脆弱且不适合测试工具。AAOS usage 测试请改用 AAudioTester
        // （native AAudioStreamBuilder_setUsage 天然支持）。其中 SPEAKER_CLEANUP(1004) 亦非平台真实值。

        val MAP = mapOf(
            "USAGE_UNKNOWN" to AudioAttributes.USAGE_UNKNOWN,
            "USAGE_MEDIA" to AudioAttributes.USAGE_MEDIA,
            "USAGE_VOICE_COMMUNICATION" to AudioAttributes.USAGE_VOICE_COMMUNICATION,
            "USAGE_VOICE_COMMUNICATION_SIGNALLING" to AudioAttributes.USAGE_VOICE_COMMUNICATION_SIGNALLING,
            "USAGE_ALARM" to AudioAttributes.USAGE_ALARM,
            "USAGE_NOTIFICATION" to AudioAttributes.USAGE_NOTIFICATION,
            "USAGE_NOTIFICATION_RINGTONE" to AudioAttributes.USAGE_NOTIFICATION_RINGTONE,
            "USAGE_NOTIFICATION_EVENT" to AudioAttributes.USAGE_NOTIFICATION_EVENT,
            "USAGE_ASSISTANCE_ACCESSIBILITY" to AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY,
            "USAGE_ASSISTANCE_NAVIGATION_GUIDANCE" to AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE,
            "USAGE_ASSISTANCE_SONIFICATION" to AudioAttributes.USAGE_ASSISTANCE_SONIFICATION,
            "USAGE_GAME" to AudioAttributes.USAGE_GAME,
            "USAGE_ASSISTANT" to AudioAttributes.USAGE_ASSISTANT
        )
    }

    /** AudioTrack contentType 常量映射 */
    object ContentType {
        val MAP = mapOf(
            "CONTENT_TYPE_UNKNOWN" to AudioAttributes.CONTENT_TYPE_UNKNOWN,
            "CONTENT_TYPE_MUSIC" to AudioAttributes.CONTENT_TYPE_MUSIC,
            "CONTENT_TYPE_MOVIE" to AudioAttributes.CONTENT_TYPE_MOVIE,
            "CONTENT_TYPE_SPEECH" to AudioAttributes.CONTENT_TYPE_SPEECH,
            "CONTENT_TYPE_SONIFICATION" to AudioAttributes.CONTENT_TYPE_SONIFICATION
        )
    }

    /** AudioTrack 性能模式常量映射 */
    object PerformanceMode {
        val MAP = mapOf(
            "PERFORMANCE_MODE_LOW_LATENCY" to AudioTrack.PERFORMANCE_MODE_LOW_LATENCY,
            "PERFORMANCE_MODE_POWER_SAVING" to AudioTrack.PERFORMANCE_MODE_POWER_SAVING,
            "PERFORMANCE_MODE_NONE" to AudioTrack.PERFORMANCE_MODE_NONE
        )
    }

    fun getUsage(usage: String): Int =
        parseEnumValue(Usage.MAP, usage, AudioAttributes.USAGE_MEDIA, "Usage")

    fun getContentType(contentType: String): Int = parseEnumValue(
        ContentType.MAP, contentType, AudioAttributes.CONTENT_TYPE_MUSIC, "ContentType"
    )

    fun getPerformanceMode(performanceMode: String): Int = parseEnumValue(
        PerformanceMode.MAP, performanceMode, AudioTrack.PERFORMANCE_MODE_POWER_SAVING, "PerformanceMode"
    )

    // ===== 录音域 =====

    /** AudioRecord 音源常量映射（系统级音源 1997-2000 需系统权限） */
    object AudioSource {
        val MAP = mapOf(
            "DEFAULT" to MediaRecorder.AudioSource.DEFAULT,
            "MIC" to MediaRecorder.AudioSource.MIC,
            "VOICE_UPLINK" to MediaRecorder.AudioSource.VOICE_UPLINK,
            "VOICE_DOWNLINK" to MediaRecorder.AudioSource.VOICE_DOWNLINK,
            "VOICE_CALL" to MediaRecorder.AudioSource.VOICE_CALL,
            "CAMCORDER" to MediaRecorder.AudioSource.CAMCORDER,
            "VOICE_RECOGNITION" to MediaRecorder.AudioSource.VOICE_RECOGNITION,
            "VOICE_COMMUNICATION" to MediaRecorder.AudioSource.VOICE_COMMUNICATION,
            "REMOTE_SUBMIX" to MediaRecorder.AudioSource.REMOTE_SUBMIX,
            "UNPROCESSED" to MediaRecorder.AudioSource.UNPROCESSED,
            "VOICE_PERFORMANCE" to MediaRecorder.AudioSource.VOICE_PERFORMANCE,
            "ECHO_REFERENCE" to 1997, // 回声参考：需 RECORD_AUDIO + 系统权限
            "RADIO_TUNER" to 1998,    // 收音机调谐：需系统签名
            "HOTWORD" to 1999,        // 热词检测：需系统签名
            "ULTRASOUND" to 2000      // 超声波：需 RECORD_AUDIO + 系统权限
        )
    }

    fun getAudioSource(audioSource: String): Int =
        parseEnumValue(AudioSource.MAP, audioSource, MediaRecorder.AudioSource.MIC, "AudioSource")

    // ===== 共享 helper =====

    private fun parseEnumValue(
        map: Map<String, Int>,
        value: String,
        default: Int,
        typeName: String = "",
    ): Int {
        val result = map[value]
        if (result != null) return result
        if (value.isNotEmpty()) {
            android.util.Log.w("AudioConstants", "Unknown $typeName value: $value, using default: $default")
        }
        return default
    }

    /** 位深 → AudioFormat 编码；合法位深集合同源（isValidBitDepth 派生自它） */
    private val BIT_DEPTH_FORMATS = mapOf(
        8 to AudioFormat.ENCODING_PCM_8BIT,
        16 to AudioFormat.ENCODING_PCM_16BIT,
        24 to AudioFormat.ENCODING_PCM_24BIT_PACKED,
        32 to AudioFormat.ENCODING_PCM_32BIT,
    )

    /** 输出声道掩码（播放域）；合法输出声道数集合同源（isValidOutputChannelCount 派生自它） */
    private val OUTPUT_CHANNEL_MASKS = mapOf(
        1 to AudioFormat.CHANNEL_OUT_MONO,
        2 to AudioFormat.CHANNEL_OUT_STEREO,
        4 to AudioFormat.CHANNEL_OUT_QUAD,
        6 to AudioFormat.CHANNEL_OUT_5POINT1,
        8 to AudioFormat.CHANNEL_OUT_7POINT1_SURROUND,
        10 to AudioFormat.CHANNEL_OUT_5POINT1POINT4,
        12 to AudioFormat.CHANNEL_OUT_7POINT1POINT4,
        16 to AudioFormat.CHANNEL_OUT_9POINT1POINT6,
    )

    /** 输入声道掩码（录音域），8/10/12/14/16 为特殊掩码 */
    private val INPUT_CHANNEL_MASKS = mapOf(
        1 to AudioFormat.CHANNEL_IN_MONO,
        2 to AudioFormat.CHANNEL_IN_STEREO,
        8 to 0x3FC, // 8 声道：6 mic + 2 reference（主动降噪用）
        10 to 0xFFC, // 10 声道：5.1.4 环绕录音
        12 to 0x3FFC, // 12 声道：7.1.4 环绕录音
        14 to 0xFFFC, // 14 声道：扩展环绕
        16 to 0x3FFFC, // 16 声道：完整配置
    )

    fun getFormatFromBitDepth(bitsPerSample: Int): Int =
        BIT_DEPTH_FORMATS[bitsPerSample] ?: run {
            android.util.Log.w("AudioConstants", "Unsupported bit depth: $bitsPerSample, using 16-bit")
            AudioFormat.ENCODING_PCM_16BIT
        }

    fun getOutputChannelMask(channelCount: Int): Int =
        OUTPUT_CHANNEL_MASKS[channelCount] ?: run {
            android.util.Log.w("AudioConstants", "Unsupported channel count: $channelCount, using stereo playback")
            AudioFormat.CHANNEL_OUT_STEREO
        }

    fun getInputChannelMask(channelCount: Int): Int =
        INPUT_CHANNEL_MASKS[channelCount] ?: run {
            android.util.Log.w("AudioConstants", "Unsupported input channel count: $channelCount, using CHANNEL_IN_STEREO")
            AudioFormat.CHANNEL_IN_STEREO
        }

    fun isValidSampleRate(rate: Int): Boolean = rate in 8000..192000

    // 合法声道数与掩码表同源：无掩码的声道数（如输入 4/6、输出 3/5/7）不会静默降级成
    // stereo 却仍按原声道数写 WAV 头导致错位。
    fun isValidInputChannelCount(count: Int): Boolean = count in INPUT_CHANNEL_MASKS

    fun isValidOutputChannelCount(count: Int): Boolean = count in OUTPUT_CHANNEL_MASKS

    fun isValidBitDepth(depth: Int): Boolean = depth in BIT_DEPTH_FORMATS
}
