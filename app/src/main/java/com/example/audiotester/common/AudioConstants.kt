package com.example.audiotester.common

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.MediaRecorder

/**
 * Audio constants (player domain + recorder domain combined)
 */
object AudioConstants {

    // Merged config file (player and recorder share a single file with two sections: "player" / "recorder")
    const val CONFIG_FILE_PATH = "/data/audio_configs.xml"
    const val ASSETS_CONFIG_FILE = "audio_configs.xml"
    const val DEFAULT_AUDIO_FILE = "asset://sample/48k_2ch_16bit.wav"

    /**
     * Error prefixes
     */
    object ErrorTypes {
        const val FILE = "[FILE]"
        const val STREAM = "[STREAM]"
        const val PERMISSION = "[PERMISSION]"
        const val PARAM = "[PARAM]"
        const val FOCUS = "[FOCUS]"
    }

    // ===== Player domain =====

    /** AudioTrack usage constant map */
    object Usage {
        // ---- System usages (1000-1004) ----
        // setUsage() only accepts SDK usages (switch-based whitelist); passing 1000-1004 always
        // throws IllegalArgumentException. The official entry point is @SystemApi
        // Builder.setSystemUsage() (requires MODIFY_AUDIO_ROUTING + system deployment; see the
        // reflection-based implementation in buildAudioAttributes() — @SystemApi is not in the
        // public SDK, so on normal installs reflection is blocked by hidden API restrictions or
        // lacks permission, consistent with the existing convention that "system-only configs
        // fail on normal installs").
        // USAGE_SPEAKER_CLEANUP(1004) is gated by feature flag android.media.audio.speaker_cleanup_usage
        // and is unavailable on some devices. Values per AOSP android-16.0.0_r4 (SYSTEM_USAGE_OFFSET = 1000).
        // Another way to test AAOS usages: AAudioTester (native AAudioStreamBuilder_setUsage supports them natively).

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

        /** System usages (1000-1004): @SystemApi constants not in the public SDK, values hardcoded */
        val SYSTEM_MAP = mapOf(
            "USAGE_EMERGENCY" to 1000,
            "USAGE_SAFETY" to 1001,
            "USAGE_VEHICLE_STATUS" to 1002,
            "USAGE_ANNOUNCEMENT" to 1003,
            "USAGE_SPEAKER_CLEANUP" to 1004,
        )
    }

    /** AudioTrack contentType constant map */
    object ContentType {
        val MAP = mapOf(
            "CONTENT_TYPE_UNKNOWN" to AudioAttributes.CONTENT_TYPE_UNKNOWN,
            "CONTENT_TYPE_MUSIC" to AudioAttributes.CONTENT_TYPE_MUSIC,
            "CONTENT_TYPE_MOVIE" to AudioAttributes.CONTENT_TYPE_MOVIE,
            "CONTENT_TYPE_SPEECH" to AudioAttributes.CONTENT_TYPE_SPEECH,
            "CONTENT_TYPE_SONIFICATION" to AudioAttributes.CONTENT_TYPE_SONIFICATION
        )
    }

    /** AudioTrack performance mode constant map */
    object PerformanceMode {
        val MAP = mapOf(
            "PERFORMANCE_MODE_LOW_LATENCY" to AudioTrack.PERFORMANCE_MODE_LOW_LATENCY,
            "PERFORMANCE_MODE_POWER_SAVING" to AudioTrack.PERFORMANCE_MODE_POWER_SAVING,
            "PERFORMANCE_MODE_NONE" to AudioTrack.PERFORMANCE_MODE_NONE
        )
    }

    /** SDK usages only (contract: the return value is always safe for setUsage(), never >= 1000) */
    fun getUsage(usage: String): Int =
        parseEnumValue(Usage.MAP, usage, AudioAttributes.USAGE_MEDIA, "Usage")

    /** Raw value resolution including system usages (>= 1000 means system usage; use resolveUsage, not getUsage, to detect it) */
    fun resolveUsage(usage: String): Int =
        parseEnumValue(ALL_USAGE_MAP, usage, AudioAttributes.USAGE_MEDIA, "Usage")

    /** System usage start value (matches @hide AudioAttributes.SYSTEM_USAGE_OFFSET) */
    private const val SYSTEM_USAGE_START = 1000

    private val ALL_USAGE_MAP: Map<String, Int> = Usage.MAP + Usage.SYSTEM_MAP

    /**
     * Builds player-domain AudioAttributes (single entry point, removes duplicate construction at call sites).
     * usage < 1000 goes through setUsage(); system usages (>= 1000) are set via reflection on
     * @SystemApi setSystemUsage(). The two cannot be mixed (build() throws IllegalArgumentException).
     * System usages require MODIFY_AUDIO_ROUTING + system deployment; failure on normal installs is expected.
     */
    fun buildAudioAttributes(usage: String, contentType: String): AudioAttributes {
        val builder = AudioAttributes.Builder()
            .setContentType(getContentType(contentType))
        builder.applyUsage(resolveUsage(usage))
        return builder.build()
    }

    private fun AudioAttributes.Builder.applyUsage(usage: Int) {
        if (usage < SYSTEM_USAGE_START) setUsage(usage) else SystemUsageSetter(this, usage)
    }

    /** Reflectively invokes @SystemApi AudioAttributes.Builder.setSystemUsage(int) (not in the public SDK) */
    private object SystemUsageSetter {
        private val method by lazy {
            AudioAttributes.Builder::class.java
                .getMethod("setSystemUsage", Int::class.javaPrimitiveType)
        }

        operator fun invoke(builder: AudioAttributes.Builder, usage: Int) {
            try {
                method.invoke(builder, usage)
            } catch (e: Throwable) {
                // Normalize into a handleable error: hidden API interception throws NoSuchMethodError
                // (an Error, which the engine's catch(Exception) cannot handle); normal installs /
                // missing permission throw IAE / InvocationTargetException.
                throw IllegalArgumentException(
                    "setSystemUsage failed for usage $usage (requires MODIFY_AUDIO_ROUTING + system deployment)",
                    e
                )
            }
        }
    }

    fun getContentType(contentType: String): Int = parseEnumValue(
        ContentType.MAP, contentType, AudioAttributes.CONTENT_TYPE_MUSIC, "ContentType"
    )

    fun getPerformanceMode(performanceMode: String): Int = parseEnumValue(
        PerformanceMode.MAP, performanceMode, AudioTrack.PERFORMANCE_MODE_POWER_SAVING, "PerformanceMode"
    )

    // ===== Recorder domain =====

    /** AudioRecord source constant map (system-level sources 1997-2000 require system permissions) */
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
            "ECHO_REFERENCE" to 1997, // Echo reference: requires RECORD_AUDIO + system permission
            "RADIO_TUNER" to 1998,    // Radio tuner: requires system signature
            "HOTWORD" to 1999,        // Hotword detection: requires system signature
            "ULTRASOUND" to 2000      // Ultrasound: requires RECORD_AUDIO + system permission
        )
    }

    fun getAudioSource(audioSource: String): Int =
        parseEnumValue(AudioSource.MAP, audioSource, MediaRecorder.AudioSource.MIC, "AudioSource")

    // ===== Shared helpers =====

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

    /** Bit depth → AudioFormat encoding; the valid bit-depth set shares this source (isValidBitDepth derives from it) */
    private val BIT_DEPTH_FORMATS = mapOf(
        8 to AudioFormat.ENCODING_PCM_8BIT,
        16 to AudioFormat.ENCODING_PCM_16BIT,
        24 to AudioFormat.ENCODING_PCM_24BIT_PACKED,
        32 to AudioFormat.ENCODING_PCM_32BIT,
    )

    /** Output channel masks (player domain); the valid output channel-count set shares this source (isValidOutputChannelCount derives from it) */
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

    /** Input channel masks (recorder domain); 8/10/12/14/16 are special masks */
    private val INPUT_CHANNEL_MASKS = mapOf(
        1 to AudioFormat.CHANNEL_IN_MONO,
        2 to AudioFormat.CHANNEL_IN_STEREO,
        8 to 0x3FC, // 8 channels: 6 mic + 2 reference (for active noise cancellation)
        10 to 0xFFC, // 10 channels: 5.1.4 surround recording
        12 to 0x3FFC, // 12 channels: 7.1.4 surround recording
        14 to 0xFFFC, // 14 channels: extended surround
        16 to 0x3FFFC, // 16 channels: full configuration
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

    // Valid channel counts share the mask tables as their source: channel counts without a mask
    // (e.g. input 4/6, output 3/5/7) must not silently fall back to stereo while the WAV header is
    // still written with the original channel count, which would misalign the data.
    fun isValidInputChannelCount(count: Int): Boolean = count in INPUT_CHANNEL_MASKS

    fun isValidOutputChannelCount(count: Int): Boolean = count in OUTPUT_CHANNEL_MASKS

    fun isValidBitDepth(depth: Int): Boolean = depth in BIT_DEPTH_FORMATS
}
