package com.example.audiotester.common

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.MediaRecorder

/**
 * 音频常量（播放域 + 录音域合一）
 */
object AudioConstants {

    // 合并配置文件（播放与录音共用单个文件，双 section）
    const val CONFIG_FILE_PATH = "/data/audio_configs.json"
    const val ASSETS_CONFIG_FILE = "audio_configs.json"
    const val PLAYER_SECTION = "player"
    const val RECORDER_SECTION = "recorder"
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

    object Usage {
        const val UNKNOWN = AudioAttributes.USAGE_UNKNOWN
        const val MEDIA = AudioAttributes.USAGE_MEDIA
        const val VOICE_COMMUNICATION = AudioAttributes.USAGE_VOICE_COMMUNICATION
        const val VOICE_COMMUNICATION_SIGNALLING =
            AudioAttributes.USAGE_VOICE_COMMUNICATION_SIGNALLING
        const val ALARM = AudioAttributes.USAGE_ALARM
        const val NOTIFICATION = AudioAttributes.USAGE_NOTIFICATION
        const val NOTIFICATION_RINGTONE = AudioAttributes.USAGE_NOTIFICATION_RINGTONE
        const val NOTIFICATION_EVENT = AudioAttributes.USAGE_NOTIFICATION_EVENT
        const val ASSISTANCE_ACCESSIBILITY = AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY
        const val ASSISTANCE_NAVIGATION_GUIDANCE =
            AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE
        const val ASSISTANCE_SONIFICATION = AudioAttributes.USAGE_ASSISTANCE_SONIFICATION
        const val GAME = AudioAttributes.USAGE_GAME
        const val ASSISTANT = AudioAttributes.USAGE_ASSISTANT

        // AAOS 专属 usage（需系统权限）
        const val EMERGENCY = 1000
        const val SAFETY = 1001
        const val VEHICLE_STATUS = 1002
        const val ANNOUNCEMENT = 1003
        const val SPEAKER_CLEANUP = 1004

        val MAP = mapOf(
            UNKNOWN to "USAGE_UNKNOWN", MEDIA to "USAGE_MEDIA",
            VOICE_COMMUNICATION to "USAGE_VOICE_COMMUNICATION",
            VOICE_COMMUNICATION_SIGNALLING to "USAGE_VOICE_COMMUNICATION_SIGNALLING",
            ALARM to "USAGE_ALARM", NOTIFICATION to "USAGE_NOTIFICATION",
            NOTIFICATION_RINGTONE to "USAGE_NOTIFICATION_RINGTONE",
            NOTIFICATION_EVENT to "USAGE_NOTIFICATION_EVENT",
            ASSISTANCE_ACCESSIBILITY to "USAGE_ASSISTANCE_ACCESSIBILITY",
            ASSISTANCE_NAVIGATION_GUIDANCE to "USAGE_ASSISTANCE_NAVIGATION_GUIDANCE",
            ASSISTANCE_SONIFICATION to "USAGE_ASSISTANCE_SONIFICATION",
            GAME to "USAGE_GAME", ASSISTANT to "USAGE_ASSISTANT",
            EMERGENCY to "USAGE_EMERGENCY", SAFETY to "USAGE_SAFETY",
            VEHICLE_STATUS to "USAGE_VEHICLE_STATUS",
            ANNOUNCEMENT to "USAGE_ANNOUNCEMENT",
            SPEAKER_CLEANUP to "USAGE_SPEAKER_CLEANUP"
        )
    }

    object ContentType {
        const val UNKNOWN = AudioAttributes.CONTENT_TYPE_UNKNOWN
        const val MUSIC = AudioAttributes.CONTENT_TYPE_MUSIC
        const val MOVIE = AudioAttributes.CONTENT_TYPE_MOVIE
        const val SPEECH = AudioAttributes.CONTENT_TYPE_SPEECH
        const val SONIFICATION = AudioAttributes.CONTENT_TYPE_SONIFICATION

        val MAP = mapOf(
            UNKNOWN to "CONTENT_TYPE_UNKNOWN", MUSIC to "CONTENT_TYPE_MUSIC",
            MOVIE to "CONTENT_TYPE_MOVIE", SPEECH to "CONTENT_TYPE_SPEECH",
            SONIFICATION to "CONTENT_TYPE_SONIFICATION"
        )
    }

    object TransferMode {
        const val STREAM = AudioTrack.MODE_STREAM
        const val STATIC = AudioTrack.MODE_STATIC
        val MAP = mapOf(STREAM to "MODE_STREAM", STATIC to "MODE_STATIC")
    }

    object PerformanceMode {
        const val LOW_LATENCY = AudioTrack.PERFORMANCE_MODE_LOW_LATENCY
        const val POWER_SAVING = AudioTrack.PERFORMANCE_MODE_POWER_SAVING
        const val NONE = AudioTrack.PERFORMANCE_MODE_NONE
        val MAP = mapOf(
            LOW_LATENCY to "PERFORMANCE_MODE_LOW_LATENCY",
            POWER_SAVING to "PERFORMANCE_MODE_POWER_SAVING",
            NONE to "PERFORMANCE_MODE_NONE"
        )
    }

    fun getUsage(usage: String): Int =
        parseEnumValue(Usage.MAP, usage, AudioAttributes.USAGE_MEDIA, "Usage")

    fun getContentType(contentType: String): Int = parseEnumValue(
        ContentType.MAP, contentType, AudioAttributes.CONTENT_TYPE_MUSIC, "ContentType"
    )

    fun getTransferMode(transferMode: String): Int =
        parseEnumValue(TransferMode.MAP, transferMode, AudioTrack.MODE_STREAM, "TransferMode")

    fun getPerformanceMode(performanceMode: String): Int = parseEnumValue(
        PerformanceMode.MAP, performanceMode, AudioTrack.PERFORMANCE_MODE_POWER_SAVING, "PerformanceMode"
    )

    // ===== 录音域 =====

    object AudioSource {
        const val DEFAULT = MediaRecorder.AudioSource.DEFAULT
        const val MIC = MediaRecorder.AudioSource.MIC
        const val VOICE_UPLINK = MediaRecorder.AudioSource.VOICE_UPLINK
        const val VOICE_DOWNLINK = MediaRecorder.AudioSource.VOICE_DOWNLINK
        const val VOICE_CALL = MediaRecorder.AudioSource.VOICE_CALL
        const val CAMCORDER = MediaRecorder.AudioSource.CAMCORDER
        const val VOICE_RECOGNITION = MediaRecorder.AudioSource.VOICE_RECOGNITION
        const val VOICE_COMMUNICATION = MediaRecorder.AudioSource.VOICE_COMMUNICATION
        const val REMOTE_SUBMIX = MediaRecorder.AudioSource.REMOTE_SUBMIX
        const val UNPROCESSED = MediaRecorder.AudioSource.UNPROCESSED
        const val VOICE_PERFORMANCE = MediaRecorder.AudioSource.VOICE_PERFORMANCE

        // 系统级音源（需系统权限）
        const val ECHO_REFERENCE = 1997
        const val RADIO_TUNER = 1998
        const val HOTWORD = 1999
        const val ULTRASOUND = 2000

        val MAP = mapOf(
            DEFAULT to "DEFAULT", MIC to "MIC", VOICE_UPLINK to "VOICE_UPLINK",
            VOICE_DOWNLINK to "VOICE_DOWNLINK", VOICE_CALL to "VOICE_CALL",
            CAMCORDER to "CAMCORDER", VOICE_RECOGNITION to "VOICE_RECOGNITION",
            VOICE_COMMUNICATION to "VOICE_COMMUNICATION", REMOTE_SUBMIX to "REMOTE_SUBMIX",
            UNPROCESSED to "UNPROCESSED", VOICE_PERFORMANCE to "VOICE_PERFORMANCE",
            ECHO_REFERENCE to "ECHO_REFERENCE", RADIO_TUNER to "RADIO_TUNER",
            HOTWORD to "HOTWORD", ULTRASOUND to "ULTRASOUND"
        )
    }

    fun getAudioSource(audioSource: String): Int =
        parseEnumValue(AudioSource.MAP, audioSource, MediaRecorder.AudioSource.MIC, "AudioSource")

    // ===== 共享 helper =====

    private fun parseEnumValue(
        map: Map<Int, String>,
        value: String,
        default: Int,
        typeName: String = "",
    ): Int {
        val entry = map.entries.find { it.value == value }
        if (entry != null) return entry.key
        if (value.isNotEmpty()) {
            android.util.Log.w("AudioConstants", "Unknown $typeName value: $value, using default: $default")
        }
        return default
    }

    fun getFormatFromBitDepth(bitsPerSample: Int): Int = when (bitsPerSample) {
        8 -> AudioFormat.ENCODING_PCM_8BIT
        16 -> AudioFormat.ENCODING_PCM_16BIT
        24 -> AudioFormat.ENCODING_PCM_24BIT_PACKED
        32 -> AudioFormat.ENCODING_PCM_32BIT
        else -> {
            android.util.Log.w("AudioConstants", "Unsupported bit depth: $bitsPerSample, using 16-bit")
            AudioFormat.ENCODING_PCM_16BIT
        }
    }

    /** 输出声道掩码（播放域），支持 1-16 声道 */
    fun getOutputChannelMask(channelCount: Int): Int = when (channelCount) {
        1 -> AudioFormat.CHANNEL_OUT_MONO
        2 -> AudioFormat.CHANNEL_OUT_STEREO
        4 -> AudioFormat.CHANNEL_OUT_QUAD
        6 -> AudioFormat.CHANNEL_OUT_5POINT1
        8 -> AudioFormat.CHANNEL_OUT_7POINT1_SURROUND
        10 -> AudioFormat.CHANNEL_OUT_5POINT1POINT4
        12 -> AudioFormat.CHANNEL_OUT_7POINT1POINT4
        16 -> AudioFormat.CHANNEL_OUT_9POINT1POINT6
        else -> {
            android.util.Log.w("AudioConstants", "Unsupported channel count: $channelCount, using stereo playback")
            AudioFormat.CHANNEL_OUT_STEREO
        }
    }

    /** 输入声道掩码（录音域），8/10/12/14/16 为特殊掩码 */
    fun getInputChannelMask(channelCount: Int): Int = when (channelCount) {
        1 -> AudioFormat.CHANNEL_IN_MONO
        2 -> AudioFormat.CHANNEL_IN_STEREO
        8 -> 0x3FC
        10 -> 0xFFC
        12 -> 0x3FFC
        14 -> 0xFFFC
        16 -> 0x3FFFC
        else -> {
            android.util.Log.w("AudioConstants", "Unsupported input channel count: $channelCount, using CHANNEL_IN_STEREO")
            AudioFormat.CHANNEL_IN_STEREO
        }
    }

    fun isValidSampleRate(rate: Int): Boolean = rate in 8000..192000
    fun isValidChannelCount(count: Int): Boolean = count in 1..16
    fun isValidBitDepth(depth: Int): Boolean = depth in listOf(8, 16, 24, 32)
}
